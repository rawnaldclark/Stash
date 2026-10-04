/**
 * stashfm.app early access and the monthly goal, from the Ko-fi side.
 *
 * These keys live in their own KV namespace, ACCESS_KV, which the website Worker (web/worker) binds
 * too. The website never sees STASH_KV (the app's supporters list and the relay config). Every key in
 * both namespaces is listed in ../README.md, "KV keys".
 *
 * A Ko-fi Donation or Subscription:
 *   - gives the donor's email early access: access:<hash> = { source: "kofi", firstAt, lastAt }.
 *     Only the hash of the email is stored, never the email itself.
 *   - adds one goal entry, goal:<YYYY-MM>:kofi:<transaction id>, with { cents, source, at, orig? } as
 *     its KV metadata. One key per donation means two Workers never overwrite each other's totals, and
 *     a retry writes the same key again instead of counting twice. The website sums a month with
 *     list({ prefix: "goal:<YYYY-MM>:" }).
 *
 * The email hash must match web/worker/crypto.js and scripts/import-kofi-csv.mjs exactly:
 * the email is trimmed and lowercased, then SHA-256 (hex), or HMAC-SHA256 (hex) keyed with
 * EMAIL_PEPPER (required by the website; plain SHA-256 is only the fallback if it's missing, and the
 * website's admin page then warns of the mismatch). Set the same EMAIL_PEPPER on both Workers before
 * any access:* entry exists: changing it later orphans every entry.
 */

export const KEYS = {
    access: (hash) => `access:${hash}`,
    goalEntry: (month, kind, id) => `goal:${month}:${kind}:${id}`,
    /** In STASH_KV: this Ko-fi transaction's progress, "listed" then "done" (src/index.js). */
    kofiTxn: (id) => `kofitxn:${id}`,
    /** hashEmail(HASHCHECK_EMAIL): lets the website's admin page spot a pepper mismatch. */
    hashCheck: "meta:hashcheck",
};

export const HASHCHECK_EMAIL = "hashcheck@stashfm.app";
export const KOFI_TXN_TTL_S = 60 * 24 * 3600;

/**
 * Ko-fi's "Send test" buttons on its webhooks page all use this transaction id (and an
 * @example.com email). A test never reaches the goal or the access list. It reaches the
 * supporters list the first time only: like any transaction, it's then marked done for 60
 * days (src/index.js), so further tests in that time change nothing.
 */
export const KOFI_TEST_TXN = "00000000-1111-2222-3333-444444444444";

/**
 * Approximate US dollars per unit, for counting non-USD Ko-fi payments toward the goal.
 * Fixed on purpose (no rate API to call or trust); updated by hand now and then. Last set
 * 2026-10. The admin page says these totals are approximate. A currency missing here is
 * left out of the goal (the donor still gets access) and logged.
 */
export const USD_PER_UNIT = {
    USD: 1,
    EUR: 1.08,
    GBP: 1.27,
    CAD: 0.73,
    AUD: 0.66,
    NZD: 0.6,
    CHF: 1.13,
    JPY: 0.0067,
    SEK: 0.095,
    NOK: 0.093,
    DKK: 0.145,
    PLN: 0.25,
    CZK: 0.043,
    HUF: 0.0027,
    BRL: 0.18,
    MXN: 0.055,
    INR: 0.012,
    SGD: 0.75,
    HKD: 0.128,
    PHP: 0.017,
    MYR: 0.22,
    ZAR: 0.055,
    ILS: 0.27,
};

const enc = new TextEncoder();
const hex = (buf) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("");

export const normalizeEmail = (email) => String(email ?? "").trim().toLowerCase();

/** The access-list hash of an email. See the top of this file. */
export async function hashEmail(email, pepper) {
    const data = enc.encode(normalizeEmail(email));
    if (!pepper) return hex(await crypto.subtle.digest("SHA-256", data));
    const key = await crypto.subtle.importKey("raw", enc.encode(pepper), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    return hex(await crypto.subtle.sign("HMAC", key, data));
}

/** Compares a received token with the secret in constant time (both hashed first, so lengths don't leak either). */
export async function sameSecret(received, secret) {
    if (typeof received !== "string" || typeof secret !== "string" || !secret) return false;
    const [a, b] = await Promise.all([crypto.subtle.digest("SHA-256", enc.encode(received)), crypto.subtle.digest("SHA-256", enc.encode(secret))]);
    const x = new Uint8Array(a);
    const y = new Uint8Array(b);
    let diff = 0;
    for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
    return diff === 0;
}

/** The id a Ko-fi retry repeats: the transaction id, else the message id, else "" (no dedupe possible). Safe for a KV key. */
export function dedupeId(payload) {
    for (const v of [payload.kofi_transaction_id, payload.message_id]) {
        const id = typeof v === "string" ? v.trim() : "";
        if (/^[A-Za-z0-9_.:-]{1,128}$/.test(id)) return id;
    }
    return "";
}

/** "2026-10" for any moment in October 2026, UTC. */
export const monthKey = (date) => date.toISOString().slice(0, 7);

/** An amount in a currency to US cents, or null for an unknown currency or a bad amount. */
export function toUsdCents(amount, currency) {
    const value = Number.parseFloat(amount);
    const rate = USD_PER_UNIT[String(currency || "USD").trim().toUpperCase()];
    if (!Number.isFinite(value) || value <= 0 || rate === undefined) return null;
    return Math.round(value * rate * 100);
}

/**
 * Everything early access needs from one Ko-fi Donation or Subscription. Called after the supporters
 * list is written. It's safe to run again for the same donation (every write is to a key of its own),
 * which is what a Ko-fi retry does after a throw here.
 */
export async function recordKofiSupport(env, payload, now = new Date()) {
    const kv = env.ACCESS_KV;
    if (!kv) throw new Error("ACCESS_KV is not bound");
    const id = dedupeId(payload);
    if (id === KOFI_TEST_TXN) return { test: true };

    const result = { access: false, cents: null };
    const email = normalizeEmail(payload.email);
    if (email.includes("@")) {
        const hash = await hashEmail(email, env.EMAIL_PEPPER);
        const prev = await kv.get(KEYS.access(hash), "json");
        const at = now.toISOString();
        await kv.put(KEYS.access(hash), JSON.stringify({ source: "kofi", firstAt: prev?.firstAt ?? prev?.at ?? at, lastAt: at }));
        const check = await hashEmail(HASHCHECK_EMAIL, env.EMAIL_PEPPER);
        if ((await kv.get(KEYS.hashCheck)) !== check) await kv.put(KEYS.hashCheck, check);
        result.access = true;
    }

    const cents = toUsdCents(payload.amount, payload.currency);
    if (cents === null) {
        console.log(`goal: not counted (amount ${JSON.stringify(payload.amount)}, currency ${JSON.stringify(payload.currency)})`);
    } else {
        const when = new Date(payload.timestamp);
        const month = monthKey(Number.isNaN(when.getTime()) ? now : when);
        const currency = String(payload.currency || "USD").trim().toUpperCase();
        const entry = { cents, source: payload.type === "Subscription" ? "Ko-fi (monthly)" : "Ko-fi", at: now.toISOString() };
        if (currency !== "USD") entry.orig = `${String(payload.amount).slice(0, 16)} ${currency}`;
        await kv.put(KEYS.goalEntry(month, "kofi", id || crypto.randomUUID()), JSON.stringify(entry), { metadata: entry });
        result.cents = cents;
    }
    return result;
}
