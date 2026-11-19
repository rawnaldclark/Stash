/**
 * What the home relay passes between a phone and a Stash desktop, as plain functions (tested in node, used by the
 * Worker and the Durable Object). Nothing here keeps state.
 *
 * A request from a phone goes to the desktop over its WebSocket as one JSON text message:
 *   { t: "req", id: <request id>, method: "GET"|"POST"|"HEAD", url: "/rest/...?...", range?: "bytes=...",
 *     form?: <base64 of an x-www-form-urlencoded POST body>, ip?: "<the phone's address>" }
 * The desktop answers by POSTing to /home/<home>/answer/<request id> with its home key, the answer's status in
 * X-Stash-Status, its headers in X-Stash-Headers (a JSON array of [name, value]), its length (when known) in
 * X-Stash-Length, and the answer's body as the POST's body, streamed.
 */

/** A home's id: what the desktop made up, and what the phone's address carries. */
export const HOME_ID = /^[a-z2-7]{16,32}$/;
/** A request's id. */
export const REQUEST_ID = /^[a-z0-9]{1,24}$/;
/** The most of a POSTed form passed on (the Subsonic API's forms are small). */
export const MOST_FORM = 256 * 1024;
/** The headers of the desktop's answer that reach the phone. */
const PASSED = new Set(["content-type", "content-range", "accept-ranges", "cache-control"]);

/**
 * Where a request goes: `{ kind: "connect"|"answer"|"phone", home, rest?, request? }`, or null (answered 404 at the
 * edge, the home never woken).
 *   /home/<home>/connect            the desktop's WebSocket
 *   /home/<home>/answer/<request>   the desktop's answer
 *   /h/<home>/rest/<method>         a phone's request of the Subsonic API
 */
export function routeOf(pathname) {
    const parts = pathname.split("/");
    if (parts[1] === "home" && HOME_ID.test(parts[2] ?? "")) {
        if (parts.length === 4 && parts[3] === "connect") return { kind: "connect", home: parts[2] };
        if (parts.length === 5 && parts[3] === "answer" && REQUEST_ID.test(parts[4])) {
            return { kind: "answer", home: parts[2], request: parts[4] };
        }
        return null;
    }
    if (parts[1] === "h" && HOME_ID.test(parts[2] ?? "") && parts[3] === "rest" && parts.length === 5 && parts[4]) {
        return { kind: "phone", home: parts[2], rest: `/rest/${parts[4]}` };
    }
    return null;
}

/** Bytes as base64. */
export function base64(bytes) {
    let bin = "";
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    return btoa(bin);
}

/**
 * The message a phone's request becomes, or `{ error, status }` for one that is not passed on. `body` is the POST's
 * body (bytes) when it is a form.
 */
export function requestMessage({ id, method, rest, search, headers, body }) {
    if (!["GET", "POST", "HEAD"].includes(method)) return { error: "only GET, POST and HEAD", status: 405 };
    const message = { t: "req", id, method, url: rest + (search || "") };
    const range = headers.get("range");
    if (range) message.range = range.slice(0, 200);
    const ip = headers.get("cf-connecting-ip");
    if (ip) message.ip = ip;
    if (method === "POST" && (headers.get("content-type") || "").startsWith("application/x-www-form-urlencoded")) {
        if (body && body.length > MOST_FORM) return { error: "that form is too big", status: 413 };
        if (body && body.length) message.form = base64(body);
    }
    return { message };
}

/**
 * The answer the desktop gave, from its POST's headers: `{ status, headers: [[name, value]...], length }`, or null
 * when they make no sense. Only the headers a player needs are passed on.
 */
export function answerOf(headers) {
    const status = Number(headers.get("x-stash-status"));
    if (!Number.isInteger(status) || status < 200 || status > 599) return null;
    let given;
    try {
        given = JSON.parse(headers.get("x-stash-headers") || "[]");
    } catch {
        return null;
    }
    if (!Array.isArray(given)) return null;
    const passed = [];
    for (const pair of given) {
        if (!Array.isArray(pair) || pair.length !== 2) continue;
        const [name, value] = pair;
        if (typeof name !== "string" || typeof value !== "string") continue;
        if (PASSED.has(name.toLowerCase()) && !/[\r\n]/.test(value)) passed.push([name, value]);
    }
    const lengthText = headers.get("x-stash-length");
    const length = lengthText != null && /^\d{1,15}$/.test(lengthText) ? Number(lengthText) : null;
    return { status, headers: passed, length };
}

/** SHA-256 of text, as lowercase hex. */
export async function sha256Hex(text) {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
    return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Equal strings, compared in time that does not depend on where they differ. */
export function sameText(a, b) {
    if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
    let diff = 0;
    for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    return diff === 0;
}

/** The home key a desktop sends: `Authorization: Bearer <43 base64url characters>` (32 random bytes). */
export function homeKeyOf(headers) {
    const auth = headers.get("authorization") || "";
    const m = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(auth);
    return m ? m[1] : null;
}
