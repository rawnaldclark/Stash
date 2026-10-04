/** Hashing, signing and encoding helpers for the early-access gate. WebCrypto only, so they run in Workers and Node. */

const enc = new TextEncoder();

/** The time source. Tests move it; nothing else should. */
export const clock = { now: () => Date.now() };

export const hex = (buf) => Array.from(new Uint8Array(buf), (b) => b.toString(16).padStart(2, "0")).join("");

export const normalizeEmail = (email) => String(email ?? "").trim().toLowerCase();

/**
 * Loose on purpose (Ko-fi and PayPal addresses vary): one @, something on each side, a dot after it. Apostrophes
 * are fine (o'brien@...); spaces, line breaks, control characters, quotes, angle brackets, commas and semicolons
 * are not. Every place an email is shown escapes it anyway.
 */
const EMAIL_CHAR = String.raw`[^\s\u0000-\u001f\u007f@<>",;]`;
const EMAIL = new RegExp(`^${EMAIL_CHAR}+@${EMAIL_CHAR}+\\.${EMAIL_CHAR}+$`);
export const validEmail = (email) => email.length <= 254 && EMAIL.test(email);

export async function sha256Hex(text) {
    return hex(await crypto.subtle.digest("SHA-256", enc.encode(text)));
}

const hmacKey = (secret, usage) => crypto.subtle.importKey("raw", enc.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, usage);

export async function hmacHex(secret, text) {
    return hex(await crypto.subtle.sign("HMAC", await hmacKey(secret, ["sign"]), enc.encode(text)));
}

export async function hmacBytes(secret, text) {
    return new Uint8Array(await crypto.subtle.sign("HMAC", await hmacKey(secret, ["sign"]), enc.encode(text)));
}

/** Checks an HMAC with WebCrypto's verify, which compares in constant time. */
export async function hmacVerify(secret, text, signature) {
    return crypto.subtle.verify("HMAC", await hmacKey(secret, ["verify"]), signature, enc.encode(text));
}

/**
 * The access-list hash of an email: trimmed and lowercased, then SHA-256 (hex), or HMAC-SHA256 (hex)
 * keyed with EMAIL_PEPPER when that secret is set. It must match infra/tipjar-worker/src/access.js and
 * scripts/import-kofi-csv.mjs there exactly; test/crypto.test.js checks the same vectors as the tip jar's tests.
 */
export async function hashEmail(email, pepper) {
    const normalized = normalizeEmail(email);
    return pepper ? hmacHex(pepper, normalized) : sha256Hex(normalized);
}

/** Constant-time comparison of two equal-length hex strings. */
export function sameHex(a, b) {
    if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
}

export function b64urlEncode(input) {
    const bytes = typeof input === "string" ? enc.encode(input) : input;
    let bin = "";
    for (const b of bytes) bin += String.fromCharCode(b);
    return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Throws on anything that isn't base64url. */
export function b64urlDecode(text) {
    if (typeof text !== "string" || !/^[A-Za-z0-9_-]*$/.test(text)) throw new Error("not base64url");
    const bin = atob(text.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (text.length % 4)) % 4));
    return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

/** Six uniformly random digits. Values at or above 4,294,000,000 (a multiple of 10^6) are drawn again. */
export function sixDigitCode() {
    const buf = new Uint32Array(1);
    do crypto.getRandomValues(buf);
    while (buf[0] >= 4_294_000_000);
    return String(buf[0] % 1_000_000).padStart(6, "0");
}
