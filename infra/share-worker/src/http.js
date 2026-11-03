/** Helpers shared by the mix, room and Community routes. */
import { sameHex } from "./store.js";

/**
 * The two headers a trusted proxy (the web player's Worker, play.stashfm.app) adds to the calls it passes on, so each
 * listener counts as their own connection instead of all of them arriving as the proxy's one address (README "Proxy key").
 */
export const PROXY_KEY_HEADER = "X-Stash-Proxy-Key";
export const CLIENT_IP_HEADER = "X-Stash-Client-IP";
/**
 * The player's own rule for a usable SHARE_PROXY_KEY (its worker/community.ts `usableProxyKey`): a secret outside it is
 * treated as unset here too, so a short or mistyped one can never be guessed into, and the player never sends one anyway.
 */
const PROXY_KEY_FORMAT = /^[A-Za-z0-9_\-.~+/=]{32,256}$/;
const IPV4 = /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;
const HEX_GROUP = /^[0-9A-Fa-f]{1,4}$/;

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

/**
 * A well-formed IPv4 address (dotted quad, no leading zeros) or plain IPv6 address (hex groups, at most one `::`): the
 * forms CF-Connecting-IP carries. Refused: zones, prefixes, brackets, lists, and IPv4-in-IPv6 text (::ffff:203.0.113.9,
 * 1:2:3:4:5:6:1.2.3.4), which networkOf keys whole rather than by its /56. A forwarded address that isn't one is ignored.
 */
export function validIp(s) {
    if (s.length > 39) return false; // the longest IPv6 text: 8 groups of 4 and 7 colons
    if (IPV4.test(s)) return true;
    if (!s.includes(":") || s.includes(".")) return false;
    const halves = s.split("::");
    if (halves.length > 2) return false;
    const groups = halves.flatMap((h) => (h === "" ? [] : h.split(":")));
    if (!groups.every((g) => HEX_GROUP.test(g))) return false;
    return halves.length === 2 ? groups.length <= 7 : groups.length === 8;
}

/** Proxy keys this isolate refused: the warning's only content, so a key value never reaches the logs. */
let wrongProxyKeys = 0;
/** Calls this isolate couldn't place (no usable address at all): counted together as one caller, "?". */
let unplaced = 0;
/** Whether this isolate has said once that CF-Connecting-IP is missing and X-Real-IP stood in. */
let saidRealIp = false;

/**
 * The address Cloudflare saw connect to it. Normally CF-Connecting-IP. A zone rule can remove that header before any
 * Worker runs ("Remove visitor IP headers", or a Request Header Transform Rule; it's the one `cf-` header a rule may
 * remove), and from 2026-10-07 stashfm.app did: every call on stashfm.app/* arrived without it, so every app user was the
 * one caller "?" (one network for the per-network vote cap, one bucket for every per-minute limit).
 *
 * X-Real-IP then stands in. Cloudflare's edge writes it for every call that reaches a Worker, replacing whatever the
 * client sent, and no zone rule may change it. A Worker's own fetch() can set it, though, so it's trusted only on a call
 * without CF-Worker, the header Cloudflare adds to every Worker subrequest (the web player's calls come with the proxy key
 * instead, below). With neither, the caller is "?", logged as a count.
 */
function connectingAddress(request) {
    const own = request.headers.get("CF-Connecting-IP");
    if (own) return own;
    const real = request.headers.get("X-Real-IP");
    if (real !== null && request.headers.get("CF-Worker") === null && validIp(real)) {
        if (!saidRealIp) {
            saidRealIp = true;
            console.warn("share: CF-Connecting-IP is missing (a zone rule removing it?); counting callers by X-Real-IP");
        }
        return real;
    }
    // Logged at the 1st, 10th, 100th… such call: enough to show in `wrangler tail` without a line per request.
    if (/^10*$/.test(String(++unplaced))) console.warn(`share: a call with no usable client address counts as "?" (${unplaced} in this isolate)`);
    return "?";
}

/**
 * The caller's address: the connecting address above, unless a trusted proxy vouches for another. The web player's Worker
 * reaches this Worker with its own address there, so it also sends the listener's (X-Stash-Client-IP) with X-Stash-Proxy-Key.
 * That address is used only when this Worker has a usable SHARE_PROXY_KEY secret, the key header equals it (compared in
 * constant time) and the address is well formed. Anything else (no secret here, no key, a wrong one, a malformed
 * address) falls back to the connecting address: a browser or script sending the headers itself is still counted by its own.
 * A key that doesn't match (or arrives while this Worker has no usable secret) logs a count-only warning, so a rotation
 * that reached one Worker but not the other shows in `wrangler tail`.
 */
export function clientAddress(request, env) {
    const key = request.headers.get(PROXY_KEY_HEADER);
    if (key === null) return connectingAddress(request);
    const secret = env?.SHARE_PROXY_KEY?.trim();
    // sameHex compares any two strings' characters in constant time; only a length mismatch returns early.
    if (!secret || !PROXY_KEY_FORMAT.test(secret) || !sameHex(key, secret)) {
        wrongProxyKeys++;
        console.warn(`share proxy: ${PROXY_KEY_HEADER} doesn't match SHARE_PROXY_KEY here; counted by its connecting address (${wrongProxyKeys} in this isolate)`);
        return connectingAddress(request);
    }
    const forwarded = request.headers.get(CLIENT_IP_HEADER);
    return forwarded !== null && validIp(forwarded) ? forwarded : connectingAddress(request);
}

/**
 * The per-minute limits' key: the client's network (networkOf below: an IPv4 address, or an IPv6 /56), as Community's
 * limits use. A /64 key let one host with a routed /48 (common on VPSes and tunnel brokers) spread over 65,536 buckets.
 * The client is clientAddress's: the listener behind the web player's proxy when it vouches for them.
 */
export const ip = (request, env) => networkOf(clientAddress(request, env));

/**
 * Community's "network" (spec 2026-09-26 §2): an IPv4 address as is, an IPv6 address by its /56.
 * Coarser than limitKey's /64: homes often get a whole /56. A free tunnel's /48 still counts as 256 networks (not 65,536).
 * The result is salted, hashed and stored. Its form (`2001:db8:aa:bb/56`) isn't standard prefix notation, on purpose:
 * changing it re-keys every stored network hash, which resets the per-network caps, the same as rotating COMMUNITY_SALT.
 */
export function networkOf(addr) {
    const key = limitKey(addr);
    if (!key.includes(":") || key.includes(".")) return key;
    const g = key.split(":").map((x) => parseInt(x || "0", 16) || 0);
    return `${g[0].toString(16)}:${g[1].toString(16)}:${g[2].toString(16)}:${(g[3] >> 8).toString(16)}/56`;
}

/**
 * The body, read only until it passes [max] bytes; null past it. A chunked or HTTP/2 body has no Content-Length
 * to check first, and `arrayBuffer()` would buffer all of it (Cloudflare accepts up to 100 MB) into the isolate
 * before any size check, so a few such requests could push it over its memory limit and reset other requests.
 */
export async function readCapped(request, max) {
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
