/**
 * One-time sign-in codes. Only an HMAC of each code is stored (code:<hash>, keyed with SESSION_SECRET, so
 * even someone reading KV can't work the code out), for 10 minutes, with a count of wrong tries: the fifth
 * wrong try deletes it. An email gets at most SENDS_PER_HOUR codes an hour (sends:<hash>); past that,
 * requests still get the usual "a code is on its way" page but no email goes out.
 *
 * KV isn't transactional, so two tries landing at once can both count as one. The per-IP limit on tries
 * (VERIFY_IP_RL) and the 6-digit space keep that harmless.
 */
import { clock, hmacHex, sameHex, sixDigitCode } from "./crypto.js";
import { KEYS } from "./keys.js";

export const CODE_TTL_S = 10 * 60;
export const MAX_TRIES = 5;
export const SENDS_PER_HOUR = 5;
const HOUR_S = 3600;
/** KV's shortest expiry. */
const MIN_TTL_S = 60;

const codeMac = (secret, h, code) => hmacHex(secret, `code:${h}:${code}`);
const nowS = () => Math.floor(clock.now() / 1000);

/** KV put options that expire at [exp] (unix seconds), never sooner than KV allows. */
const expiresAt = (exp) => (exp - nowS() >= MIN_TTL_S ? { expiration: exp } : { expirationTtl: MIN_TTL_S });

/** "123 456", "123-456" and " 123456 " all mean 123456. Anything else isn't a code. */
export function cleanCode(input) {
    const digits = String(input ?? "").replace(/[\s-]/g, "");
    return /^\d{6}$/.test(digits) ? digits : null;
}

/** A fresh code for [h], replacing any earlier one, or null when this email has had its codes for the hour. */
export async function issueCode(env, secret, h) {
    const kv = env.STASH_KV;
    const now = nowS();
    const sends = await kv.get(KEYS.sends(h), "json");
    const window = sends && sends.until > now ? sends : { n: 0, until: now + HOUR_S };
    if (window.n >= SENDS_PER_HOUR) return null;
    const code = sixDigitCode();
    const exp = now + CODE_TTL_S;
    await kv.put(KEYS.code(h), JSON.stringify({ c: await codeMac(secret, h, code), n: 0, exp }), { expirationTtl: CODE_TTL_S });
    await kv.put(KEYS.sends(h), JSON.stringify({ n: window.n + 1, until: window.until }), expiresAt(window.until));
    return code;
}

/** True once for the right code within its 10 minutes and 5 tries; the code is used up either way it ends. */
export async function checkCode(env, secret, h, code) {
    const kv = env.STASH_KV;
    const key = KEYS.code(h);
    const record = await kv.get(key, "json");
    if (!record || !Number.isInteger(record.exp) || record.exp <= nowS() || !(record.n < MAX_TRIES)) {
        if (record) await kv.delete(key);
        return false;
    }
    if (code && sameHex(await codeMac(secret, h, code), record.c)) {
        await kv.delete(key);
        return true;
    }
    const n = record.n + 1;
    if (n >= MAX_TRIES) await kv.delete(key);
    else await kv.put(key, JSON.stringify({ ...record, n }), expiresAt(record.exp));
    return false;
}
