/** Helpers shared by the mix, room and Community routes. */

export function json(obj, status = 200, extra = {}) {
    return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...extra } });
}

/** Rate-limit key: an IPv4 address as is, an IPv6 one by its /64 (one host usually holds the whole /64). */
export function limitKey(addr) {
    if (!addr.includes(":") || addr.includes(".")) return addr; // IPv4, or IPv4-mapped IPv6
    const [head, tail] = addr.split("::");
    const left = head ? head.split(":") : [];
    const right = tail ? tail.split(":") : [];
    const groups = tail === undefined ? left : [...left, ...Array(8 - left.length - right.length).fill("0"), ...right];
    return groups.slice(0, 4).join(":");
}

export const ip = (request) => limitKey(request.headers.get("CF-Connecting-IP") || "?");

/**
 * Community's "network" (spec 2026-09-26 §2): an IPv4 address as is, an IPv6 address by its /56.
 * Coarser than limitKey's /64 because homes often get a whole /56, and a free tunnel a /48.
 */
export function networkOf(addr) {
    const key = limitKey(addr);
    if (!key.includes(":") || key.includes(".")) return key;
    const g = key.split(":").map((x) => parseInt(x || "0", 16) || 0);
    return `${g[0].toString(16)}:${g[1].toString(16)}:${g[2].toString(16)}:${(g[3] >> 8).toString(16)}/56`;
}
