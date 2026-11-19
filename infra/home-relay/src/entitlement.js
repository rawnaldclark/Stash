/**
 * The supporter entitlement a Stash desktop connects with: the tip jar's token in `X-Stash-Entitlement`,
 *   base64url(UTF-8 JSON {"tier":"supporter","iat":<epoch s>,"tid":"<32 hex>","v":1}) + "." +
 *   base64url(Ed25519 signature over the ASCII bytes of the first segment), both without padding,
 * verified with ENTITLEMENT_PUBLIC_KEY (standard base64 of the raw 32-byte public key), and not withdrawn in the
 * stash-quota database's revoked_tokens (binding QUOTA_DB, when bound).
 *
 * KEEP IN SYNC with infra/tidal-relay/src/quota.js entitlementOf (the token format is the tip jar's,
 * infra/tipjar-worker/src/entitlement.js). Never throws.
 */

export const ENTITLEMENT_HEADER = "X-Stash-Entitlement";
/** A token's iat may be this far ahead of our clock (the tip jar's skew), no further. */
const IAT_SKEW_S = 300;
/** Far longer than any real token (~170 chars): refused before any decoding work. */
const MAX_TOKEN_CHARS = 512;
const B64URL = /^[A-Za-z0-9_-]+$/;
const TID = /^[0-9a-f]{32}$/;

function bytesOf(b64) {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
}
const b64urlBytes = (s) => bytesOf(s.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (s.length % 4)) % 4));

const publicKeys = new Map();
function publicKey(b64) {
    let key = publicKeys.get(b64);
    if (!key) {
        const raw = bytesOf(b64);
        if (raw.length !== 32) throw new Error("ENTITLEMENT_PUBLIC_KEY is not 32 bytes");
        key = crypto.subtle.importKey("raw", raw, { name: "Ed25519" }, false, ["verify"]);
        key.catch(() => publicKeys.delete(b64));
        publicKeys.set(b64, key);
    }
    return key;
}

/** The token's tid when its signature and fields check out, else null. */
export async function verifiedTid(token, publicKeyB64, nowSec = Math.floor(Date.now() / 1000)) {
    if (!token || !publicKeyB64 || token.length > MAX_TOKEN_CHARS) return null;
    const parts = token.split(".");
    if (parts.length !== 2 || !B64URL.test(parts[0]) || !B64URL.test(parts[1])) return null;
    let payload, signature;
    try {
        signature = b64urlBytes(parts[1]);
        payload = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(b64urlBytes(parts[0])));
    } catch {
        return null;
    }
    if (signature.length !== 64 || !payload || payload.tier !== "supporter" || payload.v !== 1) return null;
    if (typeof payload.tid !== "string" || !TID.test(payload.tid)) return null;
    if (!Number.isInteger(payload.iat) || payload.iat > nowSec + IAT_SKEW_S) return null;
    try {
        const key = await publicKey(publicKeyB64);
        if (!(await crypto.subtle.verify({ name: "Ed25519" }, key, signature, new TextEncoder().encode(parts[0])))) return null;
    } catch (e) {
        console.error(`entitlement check failed: ${e?.message || e}`);
        return null;
    }
    return payload.tid;
}

/**
 * The supporter on a request: `{ tid }`, `{ revoked: true }` for a withdrawn token, or null (no token, or not a valid
 * one). A database that cannot be read lets a valid token through (a supporter is not locked out by an outage).
 */
export async function supporterOf(request, env) {
    const tid = await verifiedTid(request.headers.get(ENTITLEMENT_HEADER), env.ENTITLEMENT_PUBLIC_KEY);
    if (!tid) return null;
    if (env.QUOTA_DB) {
        try {
            const row = await env.QUOTA_DB.prepare("SELECT 1 AS r FROM revoked_tokens WHERE tid = ?1").bind(tid).first();
            if (row) return { revoked: true };
        } catch (e) {
            console.error(`revoked_tokens unreadable: ${e?.message || e}`);
        }
    }
    return { tid };
}
