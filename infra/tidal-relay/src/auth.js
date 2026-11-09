import { createHmac, timingSafeEqual } from "node:crypto";

/** Clock skew tolerated on X-Stash-Ts, in seconds (±5 min, as the Qobuz relay). */
export const MAX_SKEW_S = 300;

/**
 * The install signature every Stash relay checks:
 *   X-Stash-Auth = hex( HMAC-SHA256( key, "<install>:<middle>:<ts>" ) )
 * with the key used as its UTF-8 string (not hex-decoded), `ts` within ±MAX_SKEW_S of the server clock.
 * `keys` holds the current key and, during a rotation, the previous one; blanks are skipped.
 * Pure: header values as strings, server clock in unix seconds. Never throws.
 * KEEP IN SYNC with infra/lossless-relay/src/auth.js (the Qobuz relay's verifySigned; same key, RELAY_KEY).
 * Here: /stream signs "td:<trackId>", /quota signs "quota".
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
