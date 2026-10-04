/** Request helpers: client address for rate limits, same-origin checks, small form bodies. */

/** Rate-limit key: an IPv4 address as is, an IPv6 one by its /64 (as infra/share-worker/src/http.js). */
export function limitKey(addr) {
    if (!addr.includes(":") || addr.includes(".")) return addr;
    const [head, tail] = addr.split("::");
    const left = head ? head.split(":") : [];
    const right = tail ? tail.split(":") : [];
    const groups = tail === undefined ? left : [...left, ...Array(8 - left.length - right.length).fill("0"), ...right];
    return groups.slice(0, 4).join(":");
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

/** A urlencoded or multipart form as a plain object of strings, or null if it's too big or not a form. */
export async function readForm(request, max = MAX_FORM_BYTES) {
    if (Number(request.headers.get("content-length")) > max) return null;
    const type = request.headers.get("content-type") || "";
    if (!type.includes("application/x-www-form-urlencoded") && !type.includes("multipart/form-data")) return null;
    const buf = await request.arrayBuffer();
    if (buf.byteLength > max) return null;
    try {
        const form = await new Response(buf, { headers: { "content-type": type } }).formData();
        const out = {};
        for (const [k, v] of form) if (typeof v === "string" && !(k in out)) out[k] = v;
        return out;
    } catch {
        return null;
    }
}
