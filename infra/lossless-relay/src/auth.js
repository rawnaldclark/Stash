import { createHmac, timingSafeEqual } from "node:crypto";

/** Clock skew tolerated on X-Stash-Ts, in seconds (spec §5.4: ±5 min). */
export const MAX_SKEW_S = 300;

/**
 * The install signature every Stash relay checks:
 *   X-Stash-Auth = hex( HMAC-SHA256( key, "<install>:<middle>:<ts>" ) )
 * with the key used as its UTF-8 string (not hex-decoded), `ts` within ±MAX_SKEW_S of the server clock.
 * `keys` holds the current key and, during a rotation, the previous one; blanks are skipped.
 * Pure: header values as strings, server clock in unix seconds. Never throws.
 * KEEP IN SYNC with tidal-worker/src/auth.js (a copy: the Tidal relay verifies the same headers).
 */
export function verifySigned({ install, ts, auth } = {}, middle, nowSec, keys) {
    if (!/^[A-Za-z0-9-]{8,64}$/.test(install || "")) return false;
    if (!/^\d{1,12}$/.test(ts || "")) return false;
    if (Math.abs(nowSec - Number(ts)) > MAX_SKEW_S) return false;
    if (!/^[0-9a-f]{64}$/.test(auth || "")) return false;
    const got = Buffer.from(auth, "hex");
    const msg = `${install}:${middle}:${ts}`;
    return keys.filter(Boolean).some((k) => timingSafeEqual(createHmac("sha256", k).update(msg).digest(), got));
}

/**
 * A mint (spec §5.4): "<install>:<track_id>:<format_id>:<ts>". The client attaches it when the signed
 * config carries a `relay_key`.
 */
export function verifyMint(headers, trackId, formatId, nowSec, keys) {
    return verifySigned(headers, `${trackId}:${formatId}`, nowSec, keys);
}

/** GET /v1/quota: "<install>:quota:<ts>", the same key and encoding as a mint. */
export function verifyQuota(headers, nowSec, keys) {
    return verifySigned(headers, "quota", nowSec, keys);
}
