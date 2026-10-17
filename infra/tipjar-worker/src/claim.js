/**
 * The supporter claim: a Ko-fi donor proves they own the email they donated with and gets a supporter token
 * (src/entitlement.js). Design: docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md, "Supporters".
 *
 *   POST /claim/email/start   {"email"}          -> 202 {"ok":true}, then ONE email through the stash-mailer Worker
 *   POST /claim/email/verify  {"email","code"}   -> 200 {"token"}
 *
 * start never says whether the address donated: every valid, non-rate-limited request gets the same 202, and
 * the whole lookup (access list, pending claim, email) runs after the response (ctx.waitUntil), so its timing
 * says nothing either. The email is what differs:
 *   - a donor (ACCESS_KV access:<hash> with source "kofi", see isDonation) gets a 6-digit code, valid 10 minutes;
 *   - anyone else is told no donation was found for the address and that any amount on Ko-fi unlocks unlimited
 *     lossless.
 *
 * Pending claim: ACCESS_KV claim:<hash> = { codeHash: SHA-256 hex of (code + hash), tries, exp (epoch s) }, with a
 * 10-minute expirationTtl. A new start replaces it (new code, tries back to 0). verify allows MAX_TRIES wrong codes,
 * then deletes it.
 *
 * Rate limits (all answer 429 {"error":"rate_limited"} with Retry-After):
 *   - CLAIM_RL (Cloudflare [[ratelimits]] binding, wrangler.toml): a per-IP burst limit on start and verify.
 *     Optional: without the binding (local tests) the burst limit is skipped.
 *   - KV counters in ACCESS_KV, fixed one-hour windows, on start only: per IP (LIMITS.ipPerHour) and per email hash
 *     (LIMITS.emailPerHour). The ratelimits binding only counts over 10 or 60 seconds, so the hourly limits need
 *     KV. KV isn't atomic, so a burst of parallel requests can slip a few past a limit: the burst binding is what
 *     bounds that.
 *
 * Logs never hold the email (not even masked), the code, the IP or the token.
 *
 * The emails themselves are the mailer's (templates in stash-mailer, like the welcome): this Worker sends
 *   POST https://stash-mailer/supporter-claim   Authorization: Bearer <MAILER_TOKEN>
 *     {"email","kind":"code","code":"123456"}  or  {"email","kind":"no_donation"}
 * and expects 200 {"sent":true}. See ../README.md, "Supporter claim", for the contract and the copy.
 */
import { hashEmail, KEYS, normalizeEmail, sameSecret } from "./access.js";
import { mintSupporterToken } from "./entitlement.js";

export const CLAIM_TTL_S = 10 * 60;
export const MAX_TRIES = 5;
export const LIMITS = { ipPerHour: 5, emailPerHour: 3 };
/** The burst binding's window (wrangler.toml [[ratelimits]] simple.period), for Retry-After. */
const BURST_PERIOD_S = 60;
const WINDOW_S = 3600;
/** KV refuses an expiration under 60 seconds from now. */
const KV_MIN_TTL_S = 60;
const MAX_BODY_BYTES = 4096;

export const MAILER_CLAIM_URL = "https://stash-mailer/supporter-claim";

export const CLAIM_KEYS = {
    claim: (hash) => `claim:${hash}`,
    /** A start counter: <kind> is "ip" or "email", <id> a hash, <window> the hour (epoch s / 3600). */
    rate: (kind, id, window) => `claimrl:${kind}:${id}:${window}`,
};

/** Clock seam for tests (milliseconds). */
export const clock = { now: () => Date.now() };

/**
 * Whether an access-list entry stands for a donation. Only "kofi" does: the Ko-fi webhook (src/access.js) and the
 * Ko-fi CSV import (scripts/import-kofi-csv.mjs) write it, and nothing else does. The website's entries are not
 * donations: "approved" (an access request the owner accepted), "manual" (added by hand on the admin page, with no
 * amount behind it) and "open" (an open-window sign-up). A Ko-fi donation overwrites any of those with "kofi".
 */
export function isDonation(entry) {
    return !!entry && typeof entry === "object" && entry.source === "kofi";
}

/** The same rule as the website's validEmail (web/worker/crypto.js), on a normalised address. */
const EMAIL_CHAR = String.raw`[^\s\u0000-\u001f\u007f@<>",;]`;
const EMAIL = new RegExp(`^${EMAIL_CHAR}+@${EMAIL_CHAR}+\\.${EMAIL_CHAR}+$`);
export const validEmail = (email) => typeof email === "string" && email.length <= 254 && EMAIL.test(email);

const enc = new TextEncoder();
const hex = (buf) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("");
const sha256Hex = async (text) => hex(await crypto.subtle.digest("SHA-256", enc.encode(text)));

/** What the claim stores for a code: SHA-256 hex of (code + email hash), so a KV read alone doesn't give the code. */
export const codeHashOf = (code, hash) => sha256Hex(`${code}${hash}`);

/** A uniform 6-digit code, "000000" to "999999" (rejection sampling, so no digit is more likely than another). */
export function newCode() {
    const buf = new Uint32Array(1);
    const limit = 4_294_000_000; // the largest multiple of 1,000,000 below 2^32
    do crypto.getRandomValues(buf);
    while (buf[0] >= limit);
    return String(buf[0] % 1_000_000).padStart(6, "0");
}

const json = (status, body, headers = {}) =>
    new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store", ...headers } });

const limited = (retryAfterS) => json(429, { error: "rate_limited" }, { "retry-after": String(Math.max(1, Math.ceil(retryAfterS))) });

/** The JSON body as an object, or null (not JSON, too big, or not an object). */
async function readJson(request) {
    if (Number(request.headers.get("content-length")) > MAX_BODY_BYTES) return null;
    let text;
    try {
        text = await request.text();
    } catch {
        return null;
    }
    if (enc.encode(text).length > MAX_BODY_BYTES) return null;
    try {
        const value = JSON.parse(text);
        return value && typeof value === "object" && !Array.isArray(value) ? value : null;
    } catch {
        return null;
    }
}

/** The client's IP (Cloudflare sets CF-Connecting-IP), or "unknown" off Cloudflare (local runs, tests). */
const clientIp = (request) => request.headers.get("cf-connecting-ip") || "unknown";

/** The IP's id in limit keys: keyed with EMAIL_PEPPER like the email (hashEmail), so no raw IP is stored. */
const ipId = (ip, pepper) => hashEmail(`ip:${ip}`, pepper);

/** The burst limit (CLAIM_RL). True when the request may go on; skipped (true) when the binding isn't there. */
async function burstAllowed(env, route, ipHash) {
    if (!env.CLAIM_RL || typeof env.CLAIM_RL.limit !== "function") return true;
    try {
        return (await env.CLAIM_RL.limit({ key: `${route}:${ipHash}` })).success !== false;
    } catch (err) {
        // A failing limiter shouldn't take the claim down; the hourly KV limits still apply.
        console.error(`claim: CLAIM_RL failed: ${String(err?.message ?? err).slice(0, 80)}`);
        return true;
    }
}

/** The current hourly count for one start counter. */
async function countOf(kv, key) {
    const n = Number.parseInt((await kv.get(key)) ?? "0", 10);
    return Number.isFinite(n) && n > 0 ? n : 0;
}

/**
 * The hourly start limits. Returns null when allowed (and counts this request against both), or the seconds until
 * the window ends when a limit is reached (nothing is counted then).
 */
async function hourlyLimit(kv, ipHash, emailHash, nowS) {
    const window = Math.floor(nowS / WINDOW_S);
    const retryAfter = (window + 1) * WINDOW_S - nowS;
    const ipKey = CLAIM_KEYS.rate("ip", ipHash, window);
    const emailKey = CLAIM_KEYS.rate("email", emailHash, window);
    const [ipCount, emailCount] = await Promise.all([countOf(kv, ipKey), countOf(kv, emailKey)]);
    if (ipCount >= LIMITS.ipPerHour || emailCount >= LIMITS.emailPerHour) return retryAfter;
    const ttl = { expirationTtl: retryAfter + KV_MIN_TTL_S };
    await Promise.all([kv.put(ipKey, String(ipCount + 1), ttl), kv.put(emailKey, String(emailCount + 1), ttl)]);
    return null;
}

/** Asks the mailer for one claim email. Resolves true when it says sent; never rejects; logs no address or code. */
async function sendClaimMail(env, message) {
    try {
        const res = await env.MAILER.fetch(MAILER_CLAIM_URL, {
            method: "POST",
            headers: { authorization: `Bearer ${env.MAILER_TOKEN}`, "content-type": "application/json" },
            body: JSON.stringify(message),
        });
        let body = null;
        try {
            body = await res.json();
        } catch {
            // not JSON
        }
        if (res.status === 200 && body?.sent === true) {
            console.log(`claim: ${message.kind} email sent`);
            return true;
        }
        console.error(`claim: ${message.kind} email not sent: ${res.status} ${String(body?.error ?? "").slice(0, 64)}`);
        return false;
    } catch (err) {
        console.error(`claim: ${message.kind} email not sent: ${String(err?.message ?? err).slice(0, 120)}`);
        return false;
    }
}

/**
 * Everything start does after its 202: look the address up, store a claim for a donor, send the one email.
 * Never rejects (it runs in waitUntil).
 */
export async function deliverClaim(env, email, hash, nowMs = clock.now()) {
    try {
        const entry = await env.ACCESS_KV.get(KEYS.access(hash), "json");
        if (!isDonation(entry)) return await sendClaimMail(env, { email, kind: "no_donation" });
        const code = newCode();
        const claim = { codeHash: await codeHashOf(code, hash), tries: 0, exp: Math.floor(nowMs / 1000) + CLAIM_TTL_S };
        await env.ACCESS_KV.put(CLAIM_KEYS.claim(hash), JSON.stringify(claim), { expirationTtl: CLAIM_TTL_S });
        return await sendClaimMail(env, { email, kind: "code", code });
    } catch (err) {
        console.error(`claim: start failed after the response: ${String(err?.message ?? err).slice(0, 120)}`);
        return false;
    }
}

/** POST /claim/email/start. */
export async function handleClaimStart(request, env, ctx) {
    // Without these a donor would get no code, or (no pepper) a hash that matches nobody on the access list.
    if (!env.ACCESS_KV || !env.EMAIL_PEPPER || !env.MAILER || !env.MAILER_TOKEN || !env.ENTITLEMENT_PRIVATE_KEY) {
        console.error("claim: not configured (ACCESS_KV, EMAIL_PEPPER, MAILER, MAILER_TOKEN, ENTITLEMENT_PRIVATE_KEY)");
        return json(503, { error: "not_configured" });
    }
    const body = await readJson(request);
    if (!body) return json(400, { error: "bad_request" });
    const email = normalizeEmail(typeof body.email === "string" ? body.email : "");
    if (!validEmail(email)) return json(400, { error: "bad_email" });

    const ipHash = await ipId(clientIp(request), env.EMAIL_PEPPER);
    if (!(await burstAllowed(env, "start", ipHash))) return limited(BURST_PERIOD_S);
    const hash = await hashEmail(email, env.EMAIL_PEPPER);
    const nowMs = clock.now();
    const wait = await hourlyLimit(env.ACCESS_KV, ipHash, hash, Math.floor(nowMs / 1000));
    if (wait !== null) return limited(wait);

    const work = deliverClaim(env, email, hash, nowMs);
    if (ctx && typeof ctx.waitUntil === "function") ctx.waitUntil(work);
    else await work;
    return json(202, { ok: true });
}

/** POST /claim/email/verify. */
export async function handleClaimVerify(request, env) {
    if (!env.ENTITLEMENT_PRIVATE_KEY || !env.ACCESS_KV || !env.EMAIL_PEPPER) {
        console.error("claim: not configured (ENTITLEMENT_PRIVATE_KEY, ACCESS_KV, EMAIL_PEPPER)");
        return json(503, { error: "not_configured" });
    }
    const body = await readJson(request);
    if (!body) return json(400, { error: "bad_request" });
    const email = normalizeEmail(typeof body.email === "string" ? body.email : "");
    if (!validEmail(email)) return json(400, { error: "bad_email" });
    const code = typeof body.code === "string" || typeof body.code === "number" ? String(body.code).trim() : "";

    const ipHash = await ipId(clientIp(request), env.EMAIL_PEPPER);
    if (!(await burstAllowed(env, "verify", ipHash))) return limited(BURST_PERIOD_S);

    const kv = env.ACCESS_KV;
    const hash = await hashEmail(email, env.EMAIL_PEPPER);
    const key = CLAIM_KEYS.claim(hash);
    const nowMs = clock.now();
    const nowS = Math.floor(nowMs / 1000);
    let claim = null;
    try {
        claim = await kv.get(key, "json");
    } catch {
        claim = null; // unreadable: treated as no claim
    }
    if (!claim || typeof claim.codeHash !== "string" || !(Number(claim.exp) > nowS)) {
        if (claim) await kv.delete(key);
        return json(410, { error: "expired" });
    }

    // Constant time: both sides are hashed and compared without an early exit (sameSecret). A malformed code is
    // just a wrong one, and counts as a try.
    const ok = /^\d{6}$/.test(code) && (await sameSecret(await codeHashOf(code, hash), claim.codeHash));
    if (!ok) {
        const tries = (Number(claim.tries) || 0) + 1;
        if (tries >= MAX_TRIES) {
            await kv.delete(key);
            return json(429, { error: "too_many_tries" });
        }
        // Keep the claim's own expiry (KV needs it at least 60 s out; exp above is what counts).
        const expiration = Math.max(Number(claim.exp), nowS + KV_MIN_TTL_S);
        await kv.put(key, JSON.stringify({ ...claim, tries }), { expiration });
        return json(401, { error: "bad_code" });
    }

    let token;
    try {
        token = await mintSupporterToken(env.ENTITLEMENT_PRIVATE_KEY, nowMs);
    } catch (err) {
        // A malformed key. The claim stays, so the donor can try again once the secret is fixed.
        console.error(`claim: token not minted (ENTITLEMENT_PRIVATE_KEY unreadable?): ${String(err?.name ?? "error")}`);
        return json(503, { error: "not_configured" });
    }
    await kv.delete(key);
    console.log("claim: supporter token issued");
    return json(200, { token });
}
