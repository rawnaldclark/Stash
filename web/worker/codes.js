/**
 * One-time sign-in codes. code:<hash> holds up to LIVE_CODES live codes for an email, each as an HMAC keyed
 * with SESSION_SECRET (so even someone reading KV can't work a code out) and its expiry (10 minutes), plus the
 * wrong tries so far. Asking again adds a code instead of replacing the one already on its way, so someone who
 * knows your email can't cancel it. Any live code signs you in, and signing in clears them all. The fifth wrong
 * try clears them all too.
 *
 * Timing: a wrong code is answered without waiting on KV (the try is counted afterwards, in ctx.waitUntil). The
 * read itself can still be a little slower when a code record exists than when none does (web/README.md).
 *
 * KV isn't transactional and can lag up to a minute between locations, so tries landing at once can count as
 * one, and the 5-try count isn't exact. The tries are also rate-limited per email (VERIFY_EMAIL_RL, 5 a minute)
 * and per address (VERIFY_IP_RL), but those limits are per Cloudflare location, so someone spread over many
 * locations gets proportionally more. What they realistically stop is guessing at scale: each try has about a
 * 3-in-a-million chance (up to 3 live codes out of 1,000,000), at a few tries a minute per location.
 */
import { clock, hmacHex, sameHex, sixDigitCode } from "./crypto.js";
import { KEYS } from "./keys.js";

export const CODE_TTL_S = 10 * 60;
export const MAX_TRIES = 5;
export const LIVE_CODES = 3;
export const SENDS_PER_HOUR = 5;
const HOUR_S = 3600;
/** KV's shortest expiry. */
const MIN_TTL_S = 60;

const codeMac = (secret, h, code) => hmacHex(secret, `code:${h}:${code}`);
const nowS = () => Math.floor(clock.now() / 1000);

/** KV put options that expire at [exp] (unix seconds), never sooner than KV allows. */
const expiresAt = (exp) => (exp - nowS() >= MIN_TTL_S ? { expiration: exp } : { expirationTtl: MIN_TTL_S });

const liveCodes = (record, now) => (Array.isArray(record?.codes) ? record.codes.filter((c) => Number.isInteger(c?.exp) && c.exp > now && typeof c.c === "string") : []);

/** "123 456", "123-456" and " 123456 " all mean 123456. Anything else isn't a code. */
export function cleanCode(input) {
    const digits = String(input ?? "").replace(/[\s-]/g, "");
    return /^\d{6}$/.test(digits) ? digits : null;
}

/**
 * A fresh code for [h], added to the ones still live (the oldest dropped past LIVE_CODES), or null when this
 * email has had SENDS_PER_HOUR codes this hour. That hourly count is a KV counter, so it's approximate under
 * concurrency and across locations. CODE_SEND_RL (one a minute, checked before this) is tighter but per
 * Cloudflare location too: together they keep one address from being flooded, not to an exact number.
 */
export async function issueCode(env, secret, h) {
    const kv = env.ACCESS_KV;
    const now = nowS();
    const sends = await kv.get(KEYS.sends(h), "json");
    const window = sends && sends.until > now ? sends : { n: 0, until: now + HOUR_S };
    if (window.n >= SENDS_PER_HOUR) return null;
    const record = await kv.get(KEYS.code(h), "json");
    const live = liveCodes(record, now);
    const n = live.length && Number.isInteger(record.n) ? record.n : 0;
    const code = sixDigitCode();
    const codes = [...live, { c: await codeMac(secret, h, code), exp: now + CODE_TTL_S }].slice(-LIVE_CODES);
    await kv.put(KEYS.code(h), JSON.stringify({ codes, n }), { expirationTtl: CODE_TTL_S });
    await kv.put(KEYS.sends(h), JSON.stringify({ n: window.n + 1, until: window.until }), expiresAt(window.until));
    return code;
}

/**
 * True for any of [h]'s live codes, within its tries; all of them are cleared then. A wrong or missing code is
 * false at once: the bookkeeping (counting the try, clearing expired or used-up codes) goes to [later].
 */
export async function checkCode(env, secret, h, code, later) {
    const kv = env.ACCESS_KV;
    const key = KEYS.code(h);
    const [record, mac] = await Promise.all([kv.get(key, "json"), codeMac(secret, h, code ?? "")]);
    const live = liveCodes(record, nowS());
    if (!record) return false;
    if (!live.length || !(record.n < MAX_TRIES)) {
        later(kv.delete(key));
        return false;
    }
    if (code && live.some((c) => sameHex(mac, c.c))) {
        await kv.delete(key);
        return true;
    }
    const n = record.n + 1;
    const lastExp = Math.max(...live.map((c) => c.exp));
    later(n >= MAX_TRIES ? kv.delete(key) : kv.put(key, JSON.stringify({ codes: live, n }), expiresAt(lastExp)));
    return false;
}
