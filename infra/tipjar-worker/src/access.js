/**
 * stashfm.app early access and the monthly goal, from the Ko-fi side.
 *
 * These keys live in their own KV namespace, ACCESS_KV, which the website Worker (web/worker) binds
 * too. The website never sees STASH_KV (the app's supporters list and the relay config). Every key in
 * both namespaces is listed in ../README.md, "KV keys".
 *
 * A Ko-fi Donation or Subscription:
 *   - gives the donor's email early access once that email's Ko-fi payments add up to ENTRY_USD_CENTS (US$5):
 *     access:<hash> = { source: "kofi", firstAt, lastAt }. Only the hash of the email is stored, never the email
 *     itself. Until then each payment's amount is kept under the hash, kofipay:<hash>:<transaction id> (see
 *     recordKofiSupport), so the next one can add them up.
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
    /** One Ko-fi payment from an email still under the entry: { cents, at }, the same as metadata. Kept for good. */
    kofiPay: (hash, id) => `kofipay:${hash}:${id}`,
    kofiPayPrefix: (hash) => `kofipay:${hash}:`,
    /**
     * Unlocked in the app by hand (the website's admin page, "Also unlock unlimited lossless in the app"): { by, at },
     * metadata { at }. Read by the claim only (src/claim.js); not early access, not a donation. Until removed there.
     */
    comp: (hash) => `comp:${hash}`,
    goalEntry: (month, kind, id) => `goal:${month}:${kind}:${id}`,
    /** In STASH_KV: this Ko-fi transaction's progress, "listed" then "done" (src/index.js). */
    kofiTxn: (id) => `kofitxn:${id}`,
    /** hashEmail(HASHCHECK_EMAIL): lets the website's admin page spot a pepper mismatch. */
    hashCheck: "meta:hashcheck",
};

export const HASHCHECK_EMAIL = "hashcheck@stashfm.app";
export const KOFI_TXN_TTL_S = 60 * 24 * 3600;

/**
 * The entry, in US cents: what one email's Ko-fi payments must add up to before it gets early access (and with it the
 * app's supporter claim, src/claim.js). Owner's call, 2026-11-02: "$5 is the entry to the site and supporter status on
 * the app". One-off donations and monthly payments both count; other currencies count at USD_PER_UNIT; a currency
 * missing from that table can't be checked and is let in. Everyone already on the list keeps their entry, whatever
 * they gave. The copy that states the amount (the site, the mailer, the app) says "$5" in words: change it with this.
 */
export const ENTRY_USD_CENTS = 500;

/**
 * Ko-fi's "Send test" buttons on its webhooks page all use this transaction id (and an
 * @example.com email). A test never reaches the goal or the access list. It reaches the
 * supporters list the first time only: like any transaction, it's then marked done for 60
 * days (src/index.js), so further tests in that time change nothing.
 */
export const KOFI_TEST_TXN = "00000000-1111-2222-3333-444444444444";

/**
 * Approximate US dollars per unit, for counting non-USD Ko-fi payments toward the goal and
 * the entry. Fixed on purpose (no rate API to call or trust); updated by hand now and then.
 * Last set 2026-10. The admin page says these totals are approximate. A currency missing
 * here is left out of the goal (the donor still gets access) and logged.
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

/** A payment's currency as the table names it: trimmed and upper case, USD when Ko-fi sends none (or only spaces). */
export const currencyOf = (currency) => String(currency ?? "").trim().toUpperCase() || "USD";

/** An amount in a currency to US cents, or null for an unknown currency or a bad amount. */
export function toUsdCents(amount, currency) {
    const value = Number.parseFloat(amount);
    const rate = USD_PER_UNIT[currencyOf(currency)];
    if (!Number.isFinite(value) || value <= 0 || rate === undefined) return null;
    return Math.round(value * rate * 100);
}

/**
 * What one email (by its hash) has given on Ko-fi while under the entry, in US cents: the sum of its
 * kofipay:<hash>:* entries, read from their KV metadata. [except] (a key name) is left out, for a caller that
 * adds that payment itself. Also read by the supporter claim (src/claim.js) for its "not unlocked yet" email.
 */
export async function kofiTotalCents(kv, hash, except = null) {
    let total = 0;
    let cursor;
    do {
        const page = await kv.list({ prefix: KEYS.kofiPayPrefix(hash), cursor });
        for (const { name, metadata } of page.keys) {
            const cents = metadata?.cents;
            if (name !== except && Number.isSafeInteger(cents) && cents > 0) total += cents;
        }
        cursor = page.list_complete ? undefined : page.cursor;
    } while (cursor);
    return total;
}

/**
 * Everything early access needs from one Ko-fi Donation or Subscription. Called after the supporter is
 * stored and the list rebuilt (src/supporters.js). It's safe to run again for the same donation (every
 * write is to a key of its own), which is what a Ko-fi retry does after a throw here.
 *
 * Who gets early access (access:<hash>, source "kofi"), checked in this order:
 *   - an email that already has a "kofi" entry keeps it, whatever this payment is: everyone let in before the
 *     entry existed keeps their place (only its lastAt moves, as before);
 *   - a currency missing from USD_PER_UNIT can't be checked: let in, and logged;
 *   - otherwise this payment is kept as kofipay:<hash>:<transaction id> = { cents, at } (metadata too), and the
 *     email is let in once its kofipay entries add up to ENTRY_USD_CENTS. A Ko-fi retry writes the same key, and
 *     this payment is added by hand rather than read back, so a retry counts it once and a list() that hasn't
 *     caught up with the write still counts it. Without EMAIL_PEPPER nothing is kept (the hash would be a plain,
 *     guessable SHA-256 of the email, and the website signs nobody in without the pepper anyway): only this payment
 *     counts, and the log says so.
 * Under the entry nothing is granted and no welcome is sent, and an "approved" or "manual" entry is left exactly as
 * it was (only reaching the entry turns it into "kofi", as any donation did before). The supporters list and the
 * goal count every payment, as before.
 *
 * Known limit: two payments from one email whose webhooks land within KV's list() lag (up to a minute) may each
 * miss the other, so neither lets the email in. The app's supporter claim still works for them (src/claim.js counts
 * the kofipay entries itself). For the website, their next payment adds everything up and lets them in; to let them
 * in sooner, the owner adds the email by hand on the admin page ("manual": the website only; the claim doesn't need it).
 *
 * [onNewAccess] (optional) is called with the normalised email right after an access entry is written for
 * an email that had none: the welcome email (src/welcome.js). Called at that point, not at the end, so a
 * throw later on (the goal write) doesn't lose the welcome, and the retry, which finds the entry, doesn't
 * send it twice. It must not throw.
 */
export async function recordKofiSupport(env, payload, now = new Date(), { onNewAccess } = {}) {
    const kv = env.ACCESS_KV;
    if (!kv) throw new Error("ACCESS_KV is not bound");
    const id = dedupeId(payload);
    if (id === KOFI_TEST_TXN) return { test: true };

    const result = { access: false, newAccess: false, cents: null, totalCents: null };
    const cents = toUsdCents(payload.amount, payload.currency);
    const currency = currencyOf(payload.currency);
    const email = normalizeEmail(payload.email);
    if (email.includes("@")) {
        const hash = await hashEmail(email, env.EMAIL_PEPPER);
        const prev = await kv.get(KEYS.access(hash), "json");
        const at = now.toISOString();
        let letIn;
        if (prev?.source === "kofi") {
            letIn = true;
        } else if (USD_PER_UNIT[currency] === undefined) {
            letIn = true;
            console.log(`access: currency ${JSON.stringify(currency.slice(0, 8))} isn't in the table, so the entry can't be checked: let in`);
        } else {
            let payKey = null;
            if (!env.EMAIL_PEPPER) {
                console.log("access: EMAIL_PEPPER isn't set, so no amount is kept: only this payment counts toward the entry");
            } else if (cents !== null) {
                payKey = KEYS.kofiPay(hash, id || crypto.randomUUID());
                const pay = { cents, at };
                await kv.put(payKey, JSON.stringify(pay), { metadata: pay });
            }
            const total = (cents ?? 0) + (env.EMAIL_PEPPER ? await kofiTotalCents(kv, hash, payKey) : 0);
            result.totalCents = total;
            letIn = total >= ENTRY_USD_CENTS;
            if (!letIn) console.log(`access: under the entry so far (${total} of ${ENTRY_USD_CENTS} US cents): not added yet`);
        }
        if (letIn) {
            await kv.put(KEYS.access(hash), JSON.stringify({ source: "kofi", firstAt: prev?.firstAt ?? prev?.at ?? at, lastAt: at }));
            if (!prev) {
                result.newAccess = true;
                onNewAccess?.(email);
            }
            const check = await hashEmail(HASHCHECK_EMAIL, env.EMAIL_PEPPER);
            if ((await kv.get(KEYS.hashCheck)) !== check) await kv.put(KEYS.hashCheck, check);
            result.access = true;
        }
    }

    if (cents === null) {
        console.log(`goal: not counted (amount ${JSON.stringify(payload.amount)}, currency ${JSON.stringify(payload.currency)})`);
    } else {
        const when = new Date(payload.timestamp);
        const month = monthKey(Number.isNaN(when.getTime()) ? now : when);
        const entry = { cents, source: payload.type === "Subscription" ? "Ko-fi (monthly)" : "Ko-fi", at: now.toISOString() };
        if (currency !== "USD") entry.orig = `${String(payload.amount).slice(0, 16)} ${currency}`;
        await kv.put(KEYS.goalEntry(month, "kofi", id || crypto.randomUUID()), JSON.stringify(entry), { metadata: entry });
        result.cents = cents;
    }
    return result;
}
