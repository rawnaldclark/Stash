/**
 * The supporter claim: a Ko-fi donor proves they own the email they donated with and gets a supporter token
 * (src/entitlement.js). Design: docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md, "Supporters".
 *
 *   POST /claim/email/start   {"email"}          -> 202 {"ok":true}, then at most ONE email through the stash-mailer Worker
 *   POST /claim/email/verify  {"email","code"}   -> 200 {"token"}, or 401 {"error":"bad_code"} for every failure
 *
 * Neither route says whether the address donated.
 *   - start: every valid, non-rate-limited request gets the same 202, and the whole lookup (access list, email,
 *     claim write) runs after the response (ctx.waitUntil), so its timing says nothing either. The email is what
 *     differs: a donor (ACCESS_KV access:<hash> with source "kofi", see isDonation, or Ko-fi payments kept under the
 *     hash, kofipay:<hash>:*, that add up to the entry) or a comped email (isComped, or comp:<hash> from the
 *     website's admin page) gets a 6-digit code, valid 10 minutes. Anyone else is told it
 *     isn't unlocked yet and that $5 or more on Ko-fi (ENTRY_USD_CENTS, added up per email) unlocks unlimited
 *     lossless: an address with Ko-fi payments under the entry gets the "below_entry" email with its total so far,
 *     any other the "no_donation" one.
 *   - verify: no pending claim, an expired one, a wrong code and a claim whose tries are used up all get the same
 *     401 {"error":"bad_code"}, after the same work (two HMACs, one D1 statement, one constant-time compare). An
 *     exhausted claim is deleted after the response.
 *
 * Pending claim: a row in the D1 database CLAIMS_DB (src/claim-store.js, ../migrations/0001_claims.sql), keyed by
 * the email hash, holding a keyed hash of the code (codeHashOf), its tries and its expiry. D1 makes the try limit
 * and the single use atomic: each try is taken with one UPDATE ... RETURNING before the code is compared, and a
 * right code is consumed with one DELETE ... RETURNING, so parallel requests can't get more than MAX_TRIES guesses
 * or mint two tokens from one code.
 *
 * A claim is stored (or replaced) only once the mailer says the code email was sent: a start the mailer refuses
 * (its one-per-minute-per-address limit, a failure) leaves the previous claim, and the code its owner holds, as
 * it was.
 *
 * Rate limits (start and verify answer 429 {"error":"rate_limited"} with Retry-After):
 *   - CLAIM_RL (Cloudflare [[ratelimits]] binding, wrangler.toml, 10 a minute per key): start per IP; verify per
 *     IP and per email hash. "Per IP" is the full address for IPv4 and its /64 for IPv6 (ipBucket): one host gets
 *     a whole /64, so a per-address IPv6 limit would limit nothing. Optional: without the binding (local tests)
 *     these are skipped.
 *   - KV counters in ACCESS_KV, fixed one-hour windows, on start only: per IP (LIMITS.ipPerHour) and per email hash
 *     (LIMITS.emailPerHour). KV isn't atomic, so a burst of parallel requests can slip a few past these: the burst
 *     binding bounds that, and these only limit email volume (nothing secret depends on them).
 *   - A global cap on "no donation found" emails (LIMITS.noDonationPerHour, an atomic D1 counter): past it those
 *     emails are skipped without a word (still 202), so start can't be turned into a mass mailer. Donors' code
 *     emails don't count against it.
 *
 * Logs never hold the email (not even masked), the code, the IP or the token.
 *
 * The emails themselves are the mailer's (templates in stash-mailer, like the welcome): this Worker sends
 *   POST https://stash-mailer/supporter-claim   Authorization: Bearer <MAILER_TOKEN>
 *     {"email","kind":"code","code":"123456"}  or  {"email","kind":"no_donation"}
 *     or  {"email","kind":"below_entry","cents":300}   (cents: the address's Ko-fi total so far, 1 to 499)
 * and counts only 200 {"sent":true} as sent. A mailer from before below_entry answers it 400 {"error":"bad_kind"}; the
 * no_donation email goes instead, so this Worker can be deployed before or after the mailer. See ../README.md,
 * "Supporter claim", for the contract and the copy.
 */
import { ENTRY_USD_CENTS, hashEmail, KEYS, kofiTotalCents, normalizeEmail, sameSecret } from "./access.js";
import { consumeClaim, countWithin, dropExhausted, putClaim, takeTry } from "./claim-store.js";
import { mintSupporterToken } from "./entitlement.js";

export const CLAIM_TTL_S = 10 * 60;
export const MAX_TRIES = 5;
export const LIMITS = { ipPerHour: 5, emailPerHour: 3, noDonationPerHour: 30 };
/** The burst binding's window (wrangler.toml [[ratelimits]] simple.period), for Retry-After. */
const BURST_PERIOD_S = 60;
const WINDOW_S = 3600;
/** KV refuses an expiration under 60 seconds from now. */
const KV_MIN_TTL_S = 60;
const MAX_BODY_BYTES = 4096;

export const MAILER_CLAIM_URL = "https://stash-mailer/supporter-claim";

export const CLAIM_KEYS = {
    /** A start counter in ACCESS_KV: <kind> is "ip" or "email", <id> a hash, <window> the hour (epoch s / 3600). */
    rate: (kind, id, window) => `claimrl:${kind}:${id}:${window}`,
    /** The no-donation email counter in CLAIMS_DB claim_counters, per hour. */
    noDonation: (window) => `nodonation:${window}`,
};

/** Clock seam for tests (milliseconds). */
export const clock = { now: () => Date.now() };

/**
 * Whether an access-list entry stands for a donation. Only "kofi" does: the Ko-fi webhook (src/access.js) and the
 * Ko-fi CSV import (scripts/import-kofi-csv.mjs) write it, and nothing else does. Since 2026-11-02 the webhook writes
 * it only once an email's Ko-fi payments add up to the entry ($5, ENTRY_USD_CENTS); entries from before then are kept
 * whatever they gave. The website's entries are not donations: "approved" (an access request the owner accepted),
 * "manual" (added by hand on the admin page, with no amount behind it) and "open" (an open-window sign-up). A Ko-fi
 * donation that brings the email to the entry overwrites any of those with "kofi". (deliverClaim also counts Ko-fi
 * payments that add up to the entry without an access entry; see there.)
 */
export function isDonation(entry) {
    return !!entry && typeof entry === "object" && entry.source === "kofi";
}

/**
 * Emails the owner has comped (mods, friends): treated as donors for the claim only. COMPED_EMAILS is a
 * Worker secret, comma/space/newline separated. Nothing is stored in KV and no donation is recorded:
 * no early-access entry, no goal amount, no supporters-list row. The same goes for an email unlocked by hand
 * on the website's admin page (KEYS.comp, read in deliverClaim): the list there can be added to one person at a
 * time, where this secret can only be replaced whole.
 */
export function isComped(env, email) {
    const raw = typeof env.COMPED_EMAILS === "string" ? env.COMPED_EMAILS : "";
    return raw.split(/[\s,;]+/).map(normalizeEmail).filter(Boolean).includes(normalizeEmail(email));
}

/** The same rule as the website's validEmail (web/worker/crypto.js), on a normalised address. */
const EMAIL_CHAR = String.raw`[^\s\u0000-\u001f\u007f@<>",;]`;
const EMAIL = new RegExp(`^${EMAIL_CHAR}+@${EMAIL_CHAR}+\\.${EMAIL_CHAR}+$`);
export const validEmail = (email) => typeof email === "string" && email.length <= 254 && EMAIL.test(email);

const enc = new TextEncoder();
const hex = (buf) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("");

/** The HMAC key for a pepper, imported once per isolate per pepper value. */
let hmacCache = { pepper: null, key: null };
async function hmacKey(pepper) {
    if (hmacCache.pepper === pepper && hmacCache.key) return hmacCache.key;
    const key = await crypto.subtle.importKey("raw", enc.encode(pepper), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    hmacCache = { pepper, key };
    return key;
}

/**
 * What a claim stores for a code: HMAC-SHA256 hex, keyed with EMAIL_PEPPER, of "claim-code:v1:<email hash>:<code>".
 * There are only a million codes, so an unkeyed hash would fall to a brute force in moments; keyed with the
 * pepper, a D1 read alone (an export, a backup, a dashboard query) gives no way to test a guess. The prefix keeps
 * it apart from the email hash, which uses the same pepper.
 */
export async function codeHashOf(code, emailHash, pepper) {
    const mac = await crypto.subtle.sign("HMAC", await hmacKey(pepper), enc.encode(`claim-code:v1:${emailHash}:${code}`));
    return hex(mac);
}

/** Compared against when there is no claim, so that path does the same constant-time compare as a real one. */
const NO_CLAIM_HASH = "0".repeat(64);

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

/** verify's one failure answer: no claim, expired, wrong code and too many tries are indistinguishable. */
const badCode = () => json(401, { error: "bad_code" });

/** CLAIMS_DB failed (an outage, not an answer about the claim): 503 unavailable, for the app to retry later. */
function storeDown(err) {
    console.error(`claim: CLAIMS_DB failed: ${String(err?.message ?? err).slice(0, 120)}`);
    return json(503, { error: "unavailable" }, { "retry-after": "30" });
}

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

/**
 * What a per-IP limit counts by: an IPv4 address as is; an IPv6 address by its /64 ("2001:db8:0:1::/64"), since
 * one host is routinely given a whole /64 and could otherwise pick a fresh address for every request. An
 * IPv4-mapped IPv6 address ("::ffff:192.0.2.1") counts as the IPv4 one. Anything unparseable is used as is: still
 * a key, just a narrow one.
 */
export function ipBucket(ip) {
    const s = String(ip ?? "").trim().toLowerCase().replace(/%.*$/, ""); // drop a zone id ("fe80::1%eth0")
    if (!s.includes(":")) return s || "unknown";
    const g = ipv6Groups(s);
    if (!g) return s;
    if (g.slice(0, 5).every((x) => x === 0) && g[5] === 0xffff) return [g[6] >> 8, g[6] & 255, g[7] >> 8, g[7] & 255].join(".");
    return `${g.slice(0, 4).map((x) => x.toString(16).padStart(4, "0")).join(":")}::/64`;
}

/** An IPv6 address as its eight 16-bit groups, or null when it doesn't parse. A dotted IPv4 tail is two groups. */
function ipv6Groups(s) {
    const halves = s.split("::");
    if (halves.length > 2) return null;
    const parse = (part, last) => {
        if (!part) return [];
        const items = part.split(":");
        const out = [];
        for (let i = 0; i < items.length; i++) {
            const x = items[i];
            if (x.includes(".")) {
                const o = x.split(".");
                if (!last || i !== items.length - 1 || o.length !== 4 || !o.every((n) => /^\d{1,3}$/.test(n) && Number(n) < 256)) return null;
                out.push((Number(o[0]) << 8) | Number(o[1]), (Number(o[2]) << 8) | Number(o[3]));
            } else {
                if (!/^[0-9a-f]{1,4}$/.test(x)) return null;
                out.push(Number.parseInt(x, 16));
            }
        }
        return out;
    };
    const head = parse(halves[0], halves.length === 1);
    const tail = halves.length === 2 ? parse(halves[1], true) : [];
    if (!head || !tail) return null;
    if (halves.length === 1) return head.length === 8 ? head : null;
    const missing = 8 - head.length - tail.length;
    return missing >= 1 ? [...head, ...Array(missing).fill(0), ...tail] : null;
}

/** The IP's id in limit keys: its bucket, keyed with EMAIL_PEPPER like the email (hashEmail), so no raw IP is stored. */
const ipId = (ip, pepper) => hashEmail(`ip:${ipBucket(ip)}`, pepper);

/** One CLAIM_RL check. True when the request may go on; skipped (true) when the binding isn't there. */
async function burstAllowed(env, key) {
    if (!env.CLAIM_RL || typeof env.CLAIM_RL.limit !== "function") return true;
    try {
        return (await env.CLAIM_RL.limit({ key })).success !== false;
    } catch (err) {
        // A failing limiter shouldn't take the claim down; the other limits still apply.
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

/**
 * Asks the mailer for one claim email. Resolves { sent: true } only when it answers 200 {"sent":true}; anything else
 * (its per-address limit's 200 {"sent":false}, an error status, a network failure) is { sent: false }, with
 * badKind: true when the mailer doesn't know the kind (400 {"error":"bad_kind"}). Never rejects; logs no address,
 * code or amount.
 */
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
            return { sent: true };
        }
        console.error(`claim: ${message.kind} email not sent: ${res.status} ${String(body?.error ?? "").slice(0, 64)}`);
        return { sent: false, badKind: res.status === 400 && body?.error === "bad_kind" };
    } catch (err) {
        console.error(`claim: ${message.kind} email not sent: ${String(err?.message ?? err).slice(0, 120)}`);
        return { sent: false };
    }
}

/**
 * The email for an address that can't claim: "below_entry" with its Ko-fi total so far ([cents], under the entry)
 * when it has one, else "no_donation". A mailer that doesn't know below_entry yet gets no_donation instead.
 */
async function sendNotUnlocked(env, email, cents) {
    if (cents > 0) {
        const below = await sendClaimMail(env, { email, kind: "below_entry", cents });
        if (!below.badKind) return below.sent;
    }
    return (await sendClaimMail(env, { email, kind: "no_donation" })).sent;
}

/**
 * Everything start does after its 202: look the address up; for a donor send a code and, once the mailer says
 * it's sent, store it as the pending claim; for anyone else send the not-unlocked email (sendNotUnlocked), within
 * the global cap. Resolves true when an email went out. Never rejects (it runs in waitUntil).
 *
 * A donor is a comped email (COMPED_EMAILS, or unlocked by hand on the website's admin page: comp:<hash>), a "kofi"
 * access entry (isDonation), or Ko-fi payments kept under the hash that add up to the entry. The last covers two
 * payments whose webhooks landed close enough together that KV's list() lag hid each from the other, so neither wrote
 * the access entry (src/access.js, recordKofiSupport): they paid $5 and can claim, even before their next payment puts
 * them on the site's list.
 */
export async function deliverClaim(env, email, hash, nowMs = clock.now()) {
    try {
        const nowS = Math.floor(nowMs / 1000);
        const comped = isComped(env, email) || (await env.ACCESS_KV.get(KEYS.comp(hash))) !== null;
        const entry = comped ? null : await env.ACCESS_KV.get(KEYS.access(hash), "json");
        const cents = comped || isDonation(entry) ? null : await kofiTotalCents(env.ACCESS_KV, hash);
        if (cents !== null && cents < ENTRY_USD_CENTS) {
            const window = Math.floor(nowS / WINDOW_S);
            const within = await countWithin(env.CLAIMS_DB, CLAIM_KEYS.noDonation(window), LIMITS.noDonationPerHour, (window + 1) * WINDOW_S + WINDOW_S);
            if (!within) {
                console.log("claim: no_donation email skipped: hourly cap reached");
                return false;
            }
            return await sendNotUnlocked(env, email, cents);
        }
        const code = newCode();
        // Hashed before the send, so nothing but the store is left to do once the mailer answers.
        const codeHash = await codeHashOf(code, hash, env.EMAIL_PEPPER);
        // Send first, store after: a code the mailer refused (its one-per-minute limit, a failure) never reaches
        // anyone, so it must not replace the claim whose code the donor already holds.
        if (!(await sendClaimMail(env, { email, kind: "code", code })).sent) return false;
        await putClaim(env.CLAIMS_DB, hash, codeHash, nowS + CLAIM_TTL_S);
        return true;
    } catch (err) {
        console.error(`claim: start failed after the response: ${String(err?.message ?? err).slice(0, 120)}`);
        return false;
    }
}

/** Runs [work] after the response when the runtime allows it, else before (local runs, tests). */
async function afterResponse(ctx, work) {
    if (ctx && typeof ctx.waitUntil === "function") ctx.waitUntil(work);
    else await work;
}

/** POST /claim/email/start. */
export async function handleClaimStart(request, env, ctx) {
    // Without these a donor would get no code, or (no pepper) a hash that matches nobody on the access list.
    if (!env.ACCESS_KV || !env.CLAIMS_DB || !env.EMAIL_PEPPER || !env.MAILER || !env.MAILER_TOKEN || !env.ENTITLEMENT_PRIVATE_KEY) {
        console.error("claim: not configured (ACCESS_KV, CLAIMS_DB, EMAIL_PEPPER, MAILER, MAILER_TOKEN, ENTITLEMENT_PRIVATE_KEY)");
        return json(503, { error: "not_configured" });
    }
    const body = await readJson(request);
    if (!body) return json(400, { error: "bad_request" });
    const email = normalizeEmail(typeof body.email === "string" ? body.email : "");
    if (!validEmail(email)) return json(400, { error: "bad_email" });

    const ipHash = await ipId(clientIp(request), env.EMAIL_PEPPER);
    if (!(await burstAllowed(env, `start:${ipHash}`))) return limited(BURST_PERIOD_S);
    const hash = await hashEmail(email, env.EMAIL_PEPPER);
    const nowMs = clock.now();
    const wait = await hourlyLimit(env.ACCESS_KV, ipHash, hash, Math.floor(nowMs / 1000));
    if (wait !== null) return limited(wait);

    await afterResponse(ctx, deliverClaim(env, email, hash, nowMs));
    return json(202, { ok: true });
}

/** POST /claim/email/verify. */
export async function handleClaimVerify(request, env, ctx) {
    if (!env.ENTITLEMENT_PRIVATE_KEY || !env.CLAIMS_DB || !env.EMAIL_PEPPER) {
        console.error("claim: not configured (ENTITLEMENT_PRIVATE_KEY, CLAIMS_DB, EMAIL_PEPPER)");
        return json(503, { error: "not_configured" });
    }
    const body = await readJson(request);
    if (!body) return json(400, { error: "bad_request" });
    const email = normalizeEmail(typeof body.email === "string" ? body.email : "");
    if (!validEmail(email)) return json(400, { error: "bad_email" });
    const code = typeof body.code === "string" || typeof body.code === "number" ? String(body.code).trim() : "";

    const ipHash = await ipId(clientIp(request), env.EMAIL_PEPPER);
    if (!(await burstAllowed(env, `verify:${ipHash}`))) return limited(BURST_PERIOD_S);
    const hash = await hashEmail(email, env.EMAIL_PEPPER);
    // Per address too, whatever the IP: a guesser spread over many IPs still meets this (and MAX_TRIES).
    if (!(await burstAllowed(env, `v:${hash}`))) return limited(BURST_PERIOD_S);

    const db = env.CLAIMS_DB;
    const nowMs = clock.now();
    const nowS = Math.floor(nowMs / 1000);
    // The same work on every path: the code's hash, one D1 statement, one constant-time compare.
    const candidate = await codeHashOf(code.slice(0, 64), hash, env.EMAIL_PEPPER);
    let claim;
    try {
        claim = await takeTry(db, hash, MAX_TRIES, nowS);
    } catch (err) {
        return storeDown(err);
    }
    const same = await sameSecret(candidate, claim ? claim.codeHash : NO_CLAIM_HASH);
    // A malformed code is just a wrong one (and has used a try).
    const ok = !!claim && same && /^\d{6}$/.test(code);

    if (!ok) {
        // That was the last try: the claim is dead (takeTry refuses it from now on); tidy it away after the response,
        // so this answer takes no longer than any other bad_code.
        if (claim && claim.tries >= MAX_TRIES) {
            await afterResponse(
                ctx,
                dropExhausted(db, hash, MAX_TRIES).catch((err) => console.error(`claim: exhausted claim not deleted: ${String(err?.message ?? err).slice(0, 80)}`)),
            );
        }
        return badCode();
    }

    let token;
    try {
        // Minted before the claim is consumed, so a malformed key leaves the claim for a retry once it's fixed.
        token = await mintSupporterToken(env.ENTITLEMENT_PRIVATE_KEY, nowMs);
    } catch (err) {
        console.error(`claim: token not minted (ENTITLEMENT_PRIVATE_KEY unreadable?): ${String(err?.name ?? "error")}`);
        return json(503, { error: "not_configured" });
    }
    // Single use: of parallel right answers, only the one whose DELETE removed the row hands its token out.
    let consumed;
    try {
        consumed = await consumeClaim(db, hash, claim.codeHash, nowS);
    } catch (err) {
        return storeDown(err);
    }
    if (!consumed) return badCode();
    console.log("claim: supporter token issued");
    return json(200, { token });
}
