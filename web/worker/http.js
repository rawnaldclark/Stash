/** Request helpers: client address for rate limits, same-origin checks, small form bodies. */

/**
 * Rate-limit key: an IPv4 address as is, an IPv6 one by its /48. One subscriber is often given a whole
 * /48 or /56, so a /64 key would let one home rotate through thousands of "addresses".
 */
export function limitKey(addr) {
    if (!addr.includes(":") || addr.includes(".")) return addr; // IPv4, or IPv4-mapped IPv6
    const [head, tail] = addr.toLowerCase().split("::");
    const left = head ? head.split(":") : [];
    const right = tail ? tail.split(":") : [];
    const groups = tail === undefined ? left : [...left, ...Array(Math.max(0, 8 - left.length - right.length)).fill("0"), ...right];
    return `${groups.slice(0, 3).map((g) => (parseInt(g || "0", 16) || 0).toString(16)).join(":")}::/48`;
}

export const clientKey = (request) => limitKey(request.headers.get("CF-Connecting-IP") || "?");

/**
 * CSRF check for every POST: the cookie is SameSite=Lax, and the request must say it came from this site.
 * Browsers send Origin on form posts; Sec-Fetch-Site is the fallback. With neither, it's refused.
 */
export function sameOrigin(request, url) {
    const origin = request.headers.get("Origin");
    if (origin) return origin === url.origin;
    const site = request.headers.get("Sec-Fetch-Site");
    return site === "same-origin";
}

export const MAX_FORM_BYTES = 4096;

/** The body, read only until it passes [max] bytes (a chunked body has no Content-Length to check first). */
async function readCapped(request, max) {
    if (!request.body) return new Uint8Array(0);
    const reader = request.body.getReader();
    const parts = [];
    let size = 0;
    for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > max) {
            await reader.cancel().catch(() => {});
            return null;
        }
        parts.push(value);
    }
    const out = new Uint8Array(size);
    let at = 0;
    for (const p of parts) {
        out.set(p, at);
        at += p.byteLength;
    }
    return out;
}

/** A urlencoded or multipart form as a plain object of strings, or null if it's too big or not a form. */
export async function readForm(request, max = MAX_FORM_BYTES) {
    if (Number(request.headers.get("content-length")) > max) return null;
    const type = request.headers.get("content-type") || "";
    if (!type.includes("application/x-www-form-urlencoded") && !type.includes("multipart/form-data")) return null;
    const bytes = await readCapped(request, max);
    if (!bytes) return null;
    try {
        const form = await new Response(bytes, { headers: { "content-type": type } }).formData();
        const out = {};
        for (const [k, v] of form) if (typeof v === "string" && !(k in out)) out[k] = v;
        return out;
    } catch {
        return null;
    }
}
