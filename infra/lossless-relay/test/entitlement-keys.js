import { generateKeyPairSync, sign, randomBytes } from "node:crypto";

/**
 * Test-only supporter tokens in the tip jar's exact format:
 *   base64url-no-pad(UTF-8 JSON payload) + "." + base64url-no-pad(Ed25519 signature over the ASCII first segment).
 * `publicKey` is what the relays take as ENTITLEMENT_PUBLIC_KEY: standard base64 of the raw 32-byte key.
 * KEEP IN SYNC: infra/lossless-relay/test/entitlement-keys.js and infra/lossless-relay/tidal-worker/test/entitlement-keys.js.
 */
export function keypair() {
    const { publicKey, privateKey } = generateKeyPairSync("ed25519");
    const raw = Buffer.from(publicKey.export({ format: "jwk" }).x, "base64url");
    return { publicKey: raw.toString("base64"), privateKey };
}

export const newTid = () => randomBytes(16).toString("hex");

/** A signed token; `over` replaces payload fields (undefined drops one). */
export function token(privateKey, over = {}) {
    const payload = { tier: "supporter", iat: Math.floor(Date.now() / 1000) - 60, tid: newTid(), v: 1, ...over };
    const head = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
    return `${head}.${sign(null, Buffer.from(head, "ascii"), privateKey).toString("base64url")}`;
}

/** The tid inside a token (for revoking it in a test). */
export const tidOf = (t) => JSON.parse(Buffer.from(t.split(".")[0], "base64url").toString("utf8")).tid;
