/**
 * The sign-in cookie, stash_access: b64url(payload).b64url(HMAC-SHA256(SESSION_SECRET, b64url(payload))),
 * where payload is {"h": <email hash>, "exp": <unix seconds>}. It holds no email, only the hash the
 * access list is keyed by, and lasts 90 days.
 *
 * A signed-in request also needs access:<h> to still exist, so removing someone on the admin page signs
 * them out. That lookup is cached in memory for ACCESS_CACHE_MS per Worker instance, and KV itself can
 * take up to a minute to show a delete everywhere, so a removal takes effect within about 6 minutes.
 * Signing out deletes the cookie from that browser; it doesn't revoke a copy of it, which keeps working until
 * it expires or the email is taken off the list (accepted: the cookie is HttpOnly and sent only over HTTPS).
 */
import { b64urlDecode, b64urlEncode, clock, hmacBytes, hmacVerify } from "./crypto.js";
import { KEYS } from "./keys.js";

export const COOKIE = "stash_access";
export const SESSION_SECONDS = 90 * 24 * 3600; // 7,776,000
const ACCESS_CACHE_MS = 5 * 60 * 1000;
const HASH = /^[0-9a-f]{64}$/;

/** SESSION_SECRET, or null when it's missing or too short to trust (then nobody can sign in). */
export function sessionSecret(env) {
    const secret = env.SESSION_SECRET;
    return typeof secret === "string" && secret.length >= 32 ? secret : null;
}

export async function signSession(secret, h, nowMs = clock.now()) {
    const payload = b64urlEncode(JSON.stringify({ h, exp: Math.floor(nowMs / 1000) + SESSION_SECONDS }));
    return `${payload}.${b64urlEncode(await hmacBytes(secret, payload))}`;
}

/** The cookie's {h, exp} if its signature is good and it hasn't expired; null otherwise. */
export async function readSession(secret, value, nowMs = clock.now()) {
    if (!secret || typeof value !== "string") return null;
    const parts = value.split(".");
    if (parts.length !== 2) return null;
    try {
        if (!(await hmacVerify(secret, parts[0], b64urlDecode(parts[1])))) return null;
        const payload = JSON.parse(new TextDecoder().decode(b64urlDecode(parts[0])));
        if (!HASH.test(payload?.h) || !Number.isInteger(payload.exp) || payload.exp <= Math.floor(nowMs / 1000)) return null;
        return { h: payload.h, exp: payload.exp };
    } catch {
        return null;
    }
}

export function cookieValue(request, name = COOKIE) {
    for (const part of (request.headers.get("Cookie") || "").split(";")) {
        const i = part.indexOf("=");
        if (i !== -1 && part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
    }
    return null;
}

export const sessionCookie = (value) => `${COOKIE}=${value}; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=${SESSION_SECONDS}`;
export const clearedCookie = `${COOKIE}=; HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=0`;

const accessCache = new Map();

/** Remembers (or forgets) an access-list answer in this instance, e.g. right after the admin page changes it. */
export function rememberAccess(h, ok) {
    if (ok === undefined) accessCache.delete(h);
    else accessCache.set(h, { ok, until: clock.now() + ACCESS_CACHE_MS });
}

/** Empties the cache (tests). */
export const forgetAllAccess = () => accessCache.clear();

/** Is this hash on the access list? Cached for a few minutes. */
export async function hasAccess(env, h) {
    const hit = accessCache.get(h);
    if (hit && hit.until > clock.now()) return hit.ok;
    if (!env.ACCESS_KV) return false; // a Preview: no access list, so nobody is signed in
    const ok = (await env.ACCESS_KV.get(KEYS.access(h))) !== null;
    if (accessCache.size > 5000) accessCache.clear();
    rememberAccess(h, ok);
    return ok;
}

/** The signed-in visitor ({h}) or null: a valid, unexpired cookie whose hash is still on the access list. */
export async function currentSession(request, env) {
    const session = await readSession(sessionSecret(env), cookieValue(request));
    if (!session || !(await hasAccess(env, session.h))) return null;
    return session;
}
