const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
const TOMBSTONE_TTL_S = 180 * 24 * 3600;
const kvKey = (id) => `mix:${id}`;

/** 8 unbiased alphanumerics: bytes >= 248 (4 × 62) are rejected so `% 62` stays uniform. */
export function newId() {
    let id = "";
    while (id.length < 8) {
        for (const b of crypto.getRandomValues(new Uint8Array(8))) {
            if (b < 248 && id.length < 8) id += ALPHABET[b % 62];
        }
    }
    return id;
}

export async function sha256Hex(s) {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Constant-time comparison of two strings (hex digests here, the proxy key in http.js): the time depends on their
 * length, never on where they differ. Different lengths return false at once.
 */
export function sameHex(a, b) {
    if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
}

export const readMix = (kv, id) => kv.get(kvKey(id), "json");
export const writeMix = (kv, id, record) => kv.put(kvKey(id), JSON.stringify(record));
export const writeTombstone = (kv, id) =>
    kv.put(kvKey(id), JSON.stringify({ deleted: true }), { expirationTtl: TOMBSTONE_TTL_S });

/** A fresh id that isn't taken (collisions are ~impossible, but retry anyway). */
export async function freeId(kv) {
    for (let i = 0; i < 5; i++) {
        const id = newId();
        if ((await readMix(kv, id)) === null) return id;
    }
    throw new Error("could not allocate an id");
}
