/**
 * The daily lossless counter (spec docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md):
 * FREE_LOSSLESS_DAILY lossless answers per subject per UTC day, counted in ONE D1 database both relays share
 * (`stash-quota`, binding QUOTA_DB, schema in infra/lossless-quota/migrations), so a user's 500 covers Qobuz
 * and Tidal together. Subject = "i:<verified install id>". Supporters (a valid X-Stash-Entitlement token,
 * entitlementOf) are unlimited up to a per-token daily ceiling (subject "t:<tid>"), unless their tid is revoked.
 *
 * KEEP IN SYNC: infra/lossless-relay/src/quota.js and infra/lossless-relay/tidal-worker/src/quota.js are
 * identical copies (each Worker deploys on its own). Pure apart from the D1 calls and WebCrypto; never throws.
 */

/** Usage rows kept (the cap only reads today's; the rest is for looking back). */
export const USAGE_KEEP_DAYS = 7;
const DEFAULT_FREE_DAILY = 500;

export const quotaDay = (nowSec) => new Date(nowSec * 1000).toISOString().slice(0, 10); // "2026-10-17", UTC
/** The next UTC midnight, epoch seconds: one reset moment for everyone. */
export const nextResetSec = (nowSec) => (Math.floor(nowSec / 86400) + 1) * 86400;

export function freeDailyLimit(env) {
    const n = Number.parseInt(env.FREE_LOSSLESS_DAILY, 10);
    return n > 0 ? n : DEFAULT_FREE_DAILY;
}

export const ENTITLEMENT_HEADER = "X-Stash-Entitlement";
const DEFAULT_SUPPORTER_DAILY = 5000;
/** A token's iat may be this far ahead of our clock (the tip jar's skew), no further. */
const IAT_SKEW_S = 300;
/** Far longer than any real token (~170 chars): refused before any decoding work. */
const MAX_TOKEN_CHARS = 512;
const B64URL = /^[A-Za-z0-9_-]+$/;
const TID = /^[0-9a-f]{32}$/;
/** Verified tokens kept per isolate; the oldest goes first past this. */
const VERIFIED_MAX = 256;

/** The per-token safety ceiling: answers a day under one supporter token before it is treated as free again. */
export function supporterDailyCap(env) {
    const n = Number.parseInt(env.SUPPORTER_DAILY_CAP, 10);
    return n > 0 ? n : DEFAULT_SUPPORTER_DAILY;
}

/** Standard base64 → bytes (throws on junk; callers catch). */
function bytesOf(b64) {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
}
/** base64url without padding (the token's two segments) → bytes. */
const b64urlBytes = (s) => bytesOf(s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (s.length % 4)) % 4));

/** Imported public keys by their base64 text: one per isolate in practice (a key change adds a second). */
const publicKeys = new Map();
function publicKey(b64) {
    let key = publicKeys.get(b64);
    if (!key) {
        const raw = bytesOf(b64);
        if (raw.length !== 32) throw new Error("ENTITLEMENT_PUBLIC_KEY is not 32 bytes");
        key = crypto.subtle.importKey("raw", raw, { name: "Ed25519" }, false, ["verify"]);
        key.catch(() => publicKeys.delete(b64)); // never keep a rejected import around
        publicKeys.set(b64, key);
    }
    return key;
}

/** Tokens whose signature verified: "<public key>|<token>" → tid. Positive answers only. */
const verified = new Map();
let warnedNoPublicKey = false;

/**
 * The supporter entitlement on a request: the tip jar's token in `X-Stash-Entitlement`,
 *   base64url(UTF-8 JSON {"tier":"supporter","iat":<epoch s>,"tid":"<32 hex>","v":1}) + "." +
 *   base64url(Ed25519 signature over the ASCII bytes of the first segment), both without padding,
 * verified with ENTITLEMENT_PUBLIC_KEY (standard base64 of the raw 32-byte public key). Returns `{ tid }`, or null
 * for anything else: no header, junk, a bad signature or another key, another tier or version, a tid that is not
 * 32 hex, an iat more than 300 s ahead. A request without a valid token simply gets the free quota, never an error.
 * Tokens never expire; a tid is withdrawn in revoked_tokens, which readStanding checks (with the per-token ceiling)
 * in the same D1 read as the install's count, so this check costs no D1 call. Verified tokens are cached per
 * isolate, so the signature is checked once, not on every request. Never throws.
 */
export async function entitlementOf(request, env, nowSec = Math.floor(Date.now() / 1000)) {
    const token = request.headers.get(ENTITLEMENT_HEADER);
    if (!token) return null;
    if (!env.ENTITLEMENT_PUBLIC_KEY) {
        if (!warnedNoPublicKey) { warnedNoPublicKey = true; console.warn("ENTITLEMENT_PUBLIC_KEY not set: supporter tokens are ignored"); }
        return null;
    }
    const cacheKey = `${env.ENTITLEMENT_PUBLIC_KEY}|${token}`;
    const known = verified.get(cacheKey);
    if (known) return { tid: known };
    if (token.length > MAX_TOKEN_CHARS) return null;
    const parts = token.split(".");
    if (parts.length !== 2 || !B64URL.test(parts[0]) || !B64URL.test(parts[1])) return null;
    let payload, signature;
    try {
        signature = b64urlBytes(parts[1]);
        payload = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(b64urlBytes(parts[0])));
    } catch {
        return null; // not base64, not UTF-8 or not JSON: junk
    }
    if (signature.length !== 64 || !payload || payload.tier !== "supporter" || payload.v !== 1) return null;
    if (typeof payload.tid !== "string" || !TID.test(payload.tid)) return null;
    if (!Number.isInteger(payload.iat) || payload.iat > nowSec + IAT_SKEW_S) return null;
    try {
        const key = await publicKey(env.ENTITLEMENT_PUBLIC_KEY);
        if (!(await crypto.subtle.verify({ name: "Ed25519" }, key, signature, new TextEncoder().encode(parts[0])))) return null;
    } catch (e) {
        console.error(`entitlement check failed: ${e?.message || e}`); // a malformed public key, most likely
        return null;
    }
    if (verified.size >= VERIFIED_MAX) verified.delete(verified.keys().next().value);
    verified.set(cacheKey, payload.tid);
    return { tid: payload.tid };
}

/** Indirection so tests can swap the entitlement check without touching the relays. */
export const hooks = { entitlementOf };

let warnedNoDb = false;
function quotaDb(env) {
    if (env.QUOTA_DB) return env.QUOTA_DB;
    // Tests and local dev run without it; in production it silently drops the counter, so say so once per isolate.
    if (!warnedNoDb) { warnedNoDb = true; console.warn("QUOTA_DB not bound: the daily lossless counter is OFF"); }
    return null;
}

/**
 * Where `subject` stands today, read before serving:
 *   { db, subject, day, used, limit (null = unlimited), resetsAt, over, supporter, tid (a supporter's, else null) }
 * or null when the counter is off for this request: no QUOTA_DB, no subject, or D1 failed. A counter
 * failure must never stop the music; the relays' abuse caps still apply.
 *
 * With a verified token (entitlementOf) the request is a supporter (no limit) unless its tid is in revoked_tokens
 * or the token already served SUPPORTER_DAILY_CAP answers today (usage row `t:<tid>`); then it is an ordinary
 * install under the free limit. Both come back in the same single read as the install's count. Without a token
 * only the install's row is read, as before.
 */
export async function readStanding(request, env, subject, nowSec) {
    const db = quotaDb(env);
    if (!db || !subject) return null;
    try {
        const entitlement = await hooks.entitlementOf(request, env, nowSec);
        const day = quotaDay(nowSec);
        let used, supporterTid = null;
        if (entitlement?.tid) {
            const row = await db.prepare(
                "SELECT (SELECT n FROM usage WHERE day = ?1 AND subject = ?2) AS used,"
                + " (SELECT n FROM usage WHERE day = ?1 AND subject = ?3) AS token_used,"
                + " EXISTS (SELECT 1 FROM revoked_tokens WHERE tid = ?4) AS revoked",
            ).bind(day, subject, "t:" + entitlement.tid, entitlement.tid).first();
            used = Number(row?.used ?? 0);
            if (!Number(row?.revoked) && Number(row?.token_used ?? 0) < supporterDailyCap(env)) supporterTid = entitlement.tid;
        } else {
            const row = await db.prepare("SELECT n FROM usage WHERE day = ?1 AND subject = ?2").bind(day, subject).first();
            used = row ? Number(row.n) : 0;
        }
        const limit = supporterTid ? null : freeDailyLimit(env);
        return {
            db, subject, day, used, limit, resetsAt: nextResetSec(nowSec), over: limit !== null && used >= limit,
            supporter: Boolean(supporterTid), tid: supporterTid,
        };
    } catch (e) {
        console.error(`quota read failed: ${e?.message || e}`);
        return null;
    }
}

/**
 * One unit for a lossless answer just served: one atomic upsert, and `standing.used` becomes the install's count
 * after it. A supporter's answer bumps the install's row (telemetry) and the token's `t:<tid>` row (its ceiling)
 * in the same statement. Read-then-bump can serve one or two past a cap under concurrency (accepted in the spec).
 * Returns the standing (null stays null).
 */
export async function spendLossless(standing) {
    if (!standing) return standing;
    try {
        if (standing.tid) {
            const { results } = await standing.db.prepare(
                "INSERT INTO usage (day, subject, n) VALUES (?1, ?2, 1), (?1, ?3, 1)"
                + " ON CONFLICT(day, subject) DO UPDATE SET n = n + 1 RETURNING subject, n",
            ).bind(standing.day, standing.subject, "t:" + standing.tid).all();
            const mine = (results || []).find((r) => r.subject === standing.subject);
            standing.used = mine ? Number(mine.n) : standing.used + 1;
        } else {
            const row = await standing.db.prepare(
                "INSERT INTO usage (day, subject, n) VALUES (?1, ?2, 1) ON CONFLICT(day, subject) DO UPDATE SET n = n + 1 RETURNING n",
            ).bind(standing.day, standing.subject).first();
            standing.used = row ? Number(row.n) : standing.used + 1;
        }
    } catch (e) {
        console.error(`quota bump failed: ${e?.message || e}`);
        standing.used += 1; // the answer was served: show it, even if the write was lost
    }
    return standing;
}

/** The counter headers on every counted answer, the 429 and /quota. {} with the counter off. */
export function losslessHeaders(standing) {
    if (!standing) return {};
    return { "X-Stash-Lossless": `${standing.used}/${standing.limit ?? "unlimited"}`, "X-Stash-Lossless-Reset": String(standing.resetsAt) };
}

/** Header names for Access-Control-Expose-Headers, should a relay ever answer browsers directly. */
export const LOSSLESS_HEADER_NAMES = "X-Stash-Lossless, X-Stash-Lossless-Reset";

/** The answer at the cap: body, status and headers (Retry-After = seconds to the reset, at least 1). */
export function capAnswer(standing, nowSec) {
    return {
        body: { error: "daily_lossless_cap", used: standing.used, limit: standing.limit, resetsAt: standing.resetsAt },
        status: 429,
        headers: { ...losslessHeaders(standing), "Retry-After": String(Math.max(1, standing.resetsAt - nowSec)) },
    };
}

/** The GET /quota body. `limit` is null for a supporter (unlimited), matching the header's "unlimited". */
export function quotaBody(standing) {
    return { used: standing.used, limit: standing.limit, resetsAt: standing.resetsAt, supporter: standing.supporter };
}

/** Hourly cron: drop usage rows older than USAGE_KEEP_DAYS. Both relays run it; it is idempotent. */
export async function pruneUsage(env, nowSec) {
    if (!env.QUOTA_DB) return;
    try {
        await env.QUOTA_DB.prepare("DELETE FROM usage WHERE day < ?1").bind(quotaDay(nowSec - USAGE_KEEP_DAYS * 86400)).run();
    } catch (e) {
        console.error(`quota prune failed: ${e?.message || e}`);
    }
}
