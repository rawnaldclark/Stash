/** Helpers shared by the routes and both Durable Objects: answers, hashing, constant-time compares, limits keys. */

/** Every answer is private, never cached, and JSON (204s have no body). */
export const NO_STORE = { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" };

export function json(obj, status = 200, extra = {}) {
    return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...NO_STORE, ...extra } });
}

export function empty(status = 204, extra = {}) {
    return new Response(null, { status, headers: { ...NO_STORE, ...extra } });
}

/** The error shape of the share service: `{ error: { code, message } }`. */
export function fail(status, code, message, extra = {}) {
    return json({ error: { code, message } }, status, extra);
}

/** What the Durable Objects hand back to the Worker over RPC: a plain object, turned into a Response here. */
export function answer(r, extra = {}) {
    const headers = { ...extra, ...(r.headers ?? {}) };
    if (r.body === undefined || r.body === null) return empty(r.status, headers);
    return json(r.body, r.status, headers);
}

/** A ready RPC result for an error. */
export const err = (status, code, message, headers) => ({ status, body: { error: { code, message } }, ...(headers ? { headers } : {}) });
export const ok = (body, status = 200) => ({ status, body });

/** Rate-limit key: an IPv4 address as is, an IPv6 one by its /64 (one host usually holds the whole /64). */
export function limitKey(addr) {
    if (!addr.includes(":") || addr.includes(".")) return addr; // IPv4, or IPv4-mapped IPv6
    const [head, tail] = addr.split("::");
    const left = head ? head.split(":") : [];
    const right = tail ? tail.split(":") : [];
    const groups = tail === undefined ? left : [...left, ...Array(Math.max(0, 8 - left.length - right.length)).fill("0"), ...right];
    return groups.slice(0, 4).join(":");
}

const enc = new TextEncoder();

export async function sha256Hex(text) {
    const d = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(text)));
    return Array.from(d, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time equality of two strings of the same length (lengths are public: hashes are fixed-size). */
export function sameText(a, b) {
    if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
}

/** Secrets of any length (the player key): both sides are hashed first, so the compare is constant-time and length-blind. */
export async function sameSecret(given, expected) {
    if (typeof given !== "string" || typeof expected !== "string" || !expected) return false;
    return sameText(await sha256Hex(given), await sha256Hex(expected));
}

export function base64url(bytes) {
    let s = "";
    for (const b of bytes) s += String.fromCharCode(b);
    return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromBase64url(text) {
    try {
        const s = atob(text.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((text.length + 3) % 4));
        return Uint8Array.from(s, (c) => c.charCodeAt(0));
    } catch {
        return null;
    }
}

/** 128 random bits, base64url (22 characters): pair ids. */
export const randomId = () => base64url(crypto.getRandomValues(new Uint8Array(16)));

/** `Authorization: Stash-Device <deviceId>:<token>` → { deviceId, token } or null. */
export function parseDeviceAuth(header) {
    const m = /^Stash-Device ([A-Za-z0-9_-]{8,64}):([A-Za-z0-9_-]{43})$/.exec(header ?? "");
    return m ? { deviceId: m[1], token: m[2] } : null;
}

/** The header the player Worker adds to every forwarded request (its value is the PLAYER_KEY secret). */
export const PLAYER_HEADER = "X-Stash-Player-Key";

/**
 * Calls one method of a Durable Object through its fetch handler: JSON in, `{ status, body, headers? }` out.
 * (Native RPC needs classes extending `DurableObject` from "cloudflare:workers", which plain Node can't load for the tests.)
 */
export async function rpc(stub, method, arg = {}) {
    const res = await stub.fetch(`https://do.internal/${method}`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(arg) });
    if (!res.ok) throw new Error(`object answered ${res.status} to ${method}`);
    return res.json();
}

/** The object side of rpc(): runs `target[method](arg)` for an allowed method. */
export async function serveRpc(request, target, methods) {
    const method = new URL(request.url).pathname.slice(1);
    if (request.method !== "POST" || !methods.includes(method)) return new Response(null, { status: 404 });
    return Response.json(await target[method](await request.json()));
}
