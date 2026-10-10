/**
 * Shapes the server accepts. Everything a device stores is ciphertext the server can't read, so the checks are about
 * form and size only: ids are short base64url, public keys are uncompressed P-256 points, and every encrypted value is
 * rebuilt from its known fields (unknown ones are dropped) before it is stored.
 */
import { fromBase64url } from "./http.js";

const B64URL = /^[A-Za-z0-9_-]+$/;

/** Device, space and send ids: 8–64 base64url characters (spaces and pairs: 128 random bits, 22 characters, or more). */
export const isDeviceId = (v) => typeof v === "string" && /^[A-Za-z0-9_-]{8,64}$/.test(v);
export const isSendId = isDeviceId;
export const isSpaceId = (v) => typeof v === "string" && /^[A-Za-z0-9_-]{22,64}$/.test(v);
export const isPairId = (v) => typeof v === "string" && /^[A-Za-z0-9_-]{22}$/.test(v);
/** The device token's server-side form (sync-v1): base64url(SHA-256(the token's 32 bytes)), 43 characters. */
export const isTokenHash = (v) => typeof v === "string" && /^[A-Za-z0-9_-]{43}$/.test(v);
export const DEVICE_TYPES = ["phone", "web"];

/** A P-256 public key, uncompressed (65 bytes, 0x04 first), base64url. */
export function isPub(v) {
    if (typeof v !== "string" || v.length !== 87 || !B64URL.test(v)) return false;
    const b = fromBase64url(v);
    return !!b && b.length === 65 && b[0] === 4;
}

/** 12-byte AES-GCM nonce, base64url. */
const isNonce = (v) => typeof v === "string" && v.length === 16 && B64URL.test(v);

/**
 * An encrypted value as sync-v1 writes it: `{ e, n, c, p? }` (epoch, 12-byte nonce, ciphertext+tag, and on a key envelope
 * the sender's ephemeral public key), base64url. Pairing messages use epoch 0. `pub: true` requires `p` (key envelopes).
 * Returns the rebuilt value or null.
 */
export function cleanBox(v, maxC, { minEpoch = 0, pub = false } = {}) {
    if (!v || typeof v !== "object" || Array.isArray(v)) return null;
    const { e, n, c, p } = v;
    if (!isNonce(n) || typeof c !== "string" || c.length < 22 || c.length > maxC || !B64URL.test(c)) return null;
    if (!Number.isSafeInteger(e) || e < minEpoch || e > 2 ** 31) return null;
    if (p === undefined) return pub ? null : { e, n, c };
    return isPub(p) ? { e, n, c, p } : null;
}

/** An envelope of space data (log batches, slots, snapshot parts, sends): epoch 1 or more, no `p`. */
export function cleanEnv(v, maxC) {
    const b = cleanBox(v, maxC, { minEpoch: 1 });
    return b && { e: b.e, n: b.n, c: b.c };
}

/** A device as introduced at pairing: `{ id, tokenHash, pub, labelCt }`. */
export function cleanDevice(v, type) {
    if (!v || typeof v !== "object") return null;
    const labelCt = cleanBox(v.labelCt, LIMITS.labelChars);
    if (!isDeviceId(v.id) || !isTokenHash(v.tokenHash) || !isPub(v.pub) || !labelCt) return null;
    return { id: v.id, type, tokenHash: v.tokenHash, pub: v.pub, labelCt };
}

/** Whole numbers in a path segment. */
export function pathInt(s, max = Number.MAX_SAFE_INTEGER) {
    if (!/^(0|[1-9][0-9]{0,15})$/.test(s)) return null;
    const n = Number(s);
    return n <= max ? n : null;
}

const MiB = 1024 * 1024;

/** Spec §6.4. Ciphertext lengths are in base64url characters (4 per 3 bytes). */
export const LIMITS = {
    /** Any request body. */
    body: MiB,
    /** One stored blob (log batch, slot, snapshot part, send part); a body holds one, plus its JSON wrapping. */
    blobChars: MiB - 1024,
    /** stash-now is ≤ 2 KB of plaintext; gzip and base64url leave plenty of room in 16 KiB. */
    nowChars: 16 * 1024,
    /** The mirror config: three directions and a list of playlist ids. */
    configChars: 64 * 1024,
    labelChars: 1024,
    /** One pairing message (phone → browser or back): a label, ids, a public key and maybe a space key. */
    pairChars: 4096,
    /** One key envelope (ECIES of a 32-byte key and its epoch). */
    keyChars: 1024,
    parts: 16,
    phones: 1,
    browsers: 4,
    /** Whole space, excluding a snapshot being uploaded (which is at most parts × blob). */
    spaceBytes: 32 * MiB,
    /** The server refuses new batches past this many; clients compact at 500. */
    logBatches: 2000,
    logPage: 200,
    /** A log page also stops once it holds this much. */
    logPageBytes: 4 * MiB,
    writesPerDay: 3000,
    readsPerDay: 20000,
    /** Pending sends addressed to one device. */
    sendsPerDevice: 8,
};
