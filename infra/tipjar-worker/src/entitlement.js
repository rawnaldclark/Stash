/**
 * The supporter token: an offline-signed Ed25519 entitlement the Stash app sends to the relays as
 * `X-Stash-Entitlement` (docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md, "Supporters").
 *
 * Format (the relays and the app depend on it byte for byte):
 *
 *   payload = JSON.stringify({ tier: "supporter", iat: <epoch seconds>, tid: "<32 hex>", v: 1 })   (this key order)
 *   token   = b64url(UTF-8 payload) + "." + b64url(Ed25519 signature over the ASCII bytes of that first segment)
 *
 * b64url is base64url with no padding. The token holds no personal data: `tid` is 16 random bytes, the handle a
 * revocation uses (the relays check `revoked_tokens` in the stash-quota D1 database; see ../README.md).
 *
 * The private key is the secret ENTITLEMENT_PRIVATE_KEY: standard base64 of the PKCS#8 DER Ed25519 key. The public
 * key (raw 32 bytes) lives in the relays and the app; the production one is in ../README.md.
 */

const enc = new TextEncoder();

/** base64url without padding, of bytes. */
export function b64url(bytes) {
    const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    let bin = "";
    for (const b of arr) bin += String.fromCharCode(b);
    return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** n random bytes as lowercase hex. */
export function randomHex(n) {
    return Array.from(crypto.getRandomValues(new Uint8Array(n)), (b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * The imported signing key, cached per isolate for the secret it came from (importKey on every claim is cheap,
 * but there's no reason to repeat it). A different secret (a rotation) imports afresh.
 */
let cached = { secret: null, key: null };

async function signingKey(b64Pkcs8) {
    if (cached.secret === b64Pkcs8 && cached.key) return cached.key;
    const der = Uint8Array.from(atob(b64Pkcs8.trim()), (c) => c.charCodeAt(0));
    const key = await crypto.subtle.importKey("pkcs8", der, { name: "Ed25519" }, false, ["sign"]);
    cached = { secret: b64Pkcs8, key };
    return key;
}

/** The payload object, in the exact key order the format needs. */
export function supporterPayload(nowMs = Date.now(), tid = randomHex(16)) {
    return { tier: "supporter", iat: Math.floor(nowMs / 1000), tid, v: 1 };
}

/**
 * Mints a supporter token. Throws if the key can't be imported (a malformed secret): the caller answers 503 and
 * logs nothing of the secret.
 */
export async function mintSupporterToken(b64Pkcs8, nowMs = Date.now()) {
    const head = b64url(enc.encode(JSON.stringify(supporterPayload(nowMs))));
    const sig = await crypto.subtle.sign({ name: "Ed25519" }, await signingKey(b64Pkcs8), enc.encode(head));
    return `${head}.${b64url(sig)}`;
}
