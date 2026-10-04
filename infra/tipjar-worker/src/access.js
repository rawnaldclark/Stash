/**
 * stashfm.app early access and the monthly goal, from the Ko-fi side.
 *
 * The website Worker (web/worker) reads what this writes. Both bind the same KV namespace
 * (STASH_KV), and every key either of them uses is listed in ../README.md, "STASH_KV keys".
 *
 * A Ko-fi Donation or Subscription:
 *   - gives the donor's email early access: access:<hash> = { source: "kofi", firstAt, lastAt }.
 *     Only the hash of the email is stored, never the email itself.
 *   - adds the amount, in US cents, to this month's goal: goal:<YYYY-MM>.
 *   - marks its kofi_transaction_id as done (kofitxn:<id>, kept 60 days), so a retry from Ko-fi
 *     doesn't count twice.
 *
 * The email hash must match web/worker/crypto.js and scripts/import-kofi-csv.mjs exactly:
 * the email is trimmed and lowercased, then SHA-256 (hex), or HMAC-SHA256 (hex) keyed with
 * EMAIL_PEPPER when that secret is set. Set the same EMAIL_PEPPER on both Workers, or on
 * neither, before any access:* entry exists: changing it later orphans every entry.
 */

export const KEYS = {
    access: (hash) => `access:${hash}`,
    goal: (month) => `goal:${month}`,
    kofiTxn: (id) => `kofitxn:${id}`,
    /** hashEmail(HASHCHECK_EMAIL): lets the website's admin page spot a pepper mismatch. */
    hashCheck: "meta:hashcheck",
};

export const HASHCHECK_EMAIL = "hashcheck@stashfm.app";
const KOFI_TXN_TTL_S = 60 * 24 * 3600;

/**
 * Ko-fi's "Send test" buttons on its webhooks page all use this transaction id (and an
 * @example.com email). Tests still reach the supporters list, as they always have, but never
 * the goal or the access list.
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

const hex = (buf) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("");

export const normalizeEmail = (email) => String(email ?? "").trim().toLowerCase();

/** The access-list hash of an email. See the top of this file. */
export async function hashEmail(email, pepper) {
    const data = new TextEncoder().encode(normalizeEmail(email));
    if (!pepper) return hex(await crypto.subtle.digest("SHA-256", data));
    const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(pepper), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    return hex(await crypto.subtle.sign("HMAC", key, data));
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

/** Adds one entry to a month's goal record: { cents, entries: [...], updatedAt }. */
export async function addGoalEntry(kv, month, entry, now = new Date()) {
    const key = KEYS.goal(month);
    const goal = (await kv.get(key, "json")) ?? { cents: 0, entries: [] };
    goal.entries = [...(goal.entries ?? []), entry];
    goal.cents = goal.entries.reduce((sum, e) => sum + (Number.isInteger(e.cents) ? e.cents : 0), 0);
    goal.updatedAt = now.toISOString();
    await kv.put(key, JSON.stringify(goal));
    return goal;
}

/** True if this transaction was fully handled before (a Ko-fi retry). */
export async function seenTransaction(kv, txn) {
    return Boolean(txn) && (await kv.get(KEYS.kofiTxn(txn))) !== null;
}

/**
 * Everything early access needs from one Ko-fi Donation or Subscription. Called after the
 * supporters list is written; a throw here is logged by the caller and never fails the webhook.
 */
export async function recordKofiSupport(env, payload, now = new Date()) {
    const kv = env.STASH_KV;
    const txn = typeof payload.kofi_transaction_id === "string" ? payload.kofi_transaction_id.trim() : "";
    if (txn === KOFI_TEST_TXN) return { test: true };

    const result = { access: false, cents: null };
    const email = normalizeEmail(payload.email);
    if (email.includes("@")) {
        const hash = await hashEmail(email, env.EMAIL_PEPPER);
        const prev = await kv.get(KEYS.access(hash), "json");
        const at = now.toISOString();
        await kv.put(KEYS.access(hash), JSON.stringify({ source: "kofi", firstAt: prev?.firstAt ?? prev?.at ?? at, lastAt: at }));
        await kv.put(KEYS.hashCheck, await hashEmail(HASHCHECK_EMAIL, env.EMAIL_PEPPER));
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
        if (currency !== "USD") entry.orig = `${payload.amount} ${currency}`;
        await addGoalEntry(kv, month, entry, now);
        result.cents = cents;
    }

    if (txn) await kv.put(KEYS.kofiTxn(txn), "1", { expirationTtl: KOFI_TXN_TTL_S });
    return result;
}
