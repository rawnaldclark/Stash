/**
 * Stash Share: Cloudflare Worker (spec docs/superpowers/specs/2026-09-23-shared-mixes-design.md §4).
 * One KV document per shared mix; updates and deletes are authorised by a per-mix edit key whose
 * SHA-256 is all we store.
 */
import { cleanDoc, validateDoc, validEditKey, MAX_BODY_BYTES } from "./validate.js";
import { freeId, readMix, sameHex, sha256Hex, writeMix, writeTombstone } from "./store.js";
import { assetLinks, messagePage, mixPage, roomPage, trackPage } from "./pages.js";
import { base64url, randomCode } from "./listen-room.js";

export { ListenRoom } from "./listen-room.js";

const MIX_API = /^\/v1\/mixes\/([A-Za-z0-9]{8})(\/version)?$/;

/** Room codes: 8 of the 32 unambiguous symbols in listen-room.js (no 0/O, 1/I). */
const ROOM_API = /^\/v1\/rooms\/([A-HJ-NP-Z2-9]{8})(\/ws)?$/;
const ROOM_PAGE = /^\/l\/([A-HJ-NP-Z2-9]{8})$/;

export default {
    /** Any throw (a KV write 429, freeId giving up) becomes a retryable 503, not a bare 500. */
    async fetch(request, env) {
        try {
            return await handle(request, env);
        } catch (e) {
            console.error(e); // visible in `wrangler tail`
            return json({ error: "unavailable" }, 503, { "Retry-After": "2" });
        }
    },
};

export async function handle(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method === "HEAD" ? "GET" : request.method;

    if (path === "/v1/mixes") return method === "POST" ? createMix(request, env, url) : methodNotAllowed();
    const m = MIX_API.exec(path);
    if (m) {
        const id = m[1];
        if (m[2]) return method === "GET" ? getVersion(env, id) : methodNotAllowed();
        if (method === "GET") return getMix(env, id);
        if (method === "PUT") return updateMix(request, env, id);
        if (method === "DELETE") return deleteMix(request, env, id);
        return methodNotAllowed();
    }
    if (path === "/v1/rooms") return method === "POST" ? createRoom(request, env, url) : methodNotAllowed();
    const room = ROOM_API.exec(path);
    if (room) {
        if (method !== "GET") return methodNotAllowed();
        // Before the Durable Object: a code-guessing walk never wakes a room (spec §2).
        if (!(await joinAllowed(request, env))) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
        const stub = env.ROOMS.get(env.ROOMS.idFromName(room[1]));
        if (room[2]) return stub.fetch(new Request(`https://room/ws${url.search}`, request));
        const pv = await roomPreview(stub);
        return pv ? json(pv, 200, { "cache-control": "no-store" }) : json({ error: "not_found" }, 404);
    }
    if (method === "GET" && path === "/.well-known/assetlinks.json") return json(assetLinks(), 200, { "cache-control": "public, max-age=3600" });
    if (method === "GET" && path === "/t") return html(trackPage(url.searchParams, url.href));
    const page = /^\/m\/([A-Za-z0-9]{8})$/.exec(path);
    if (method === "GET" && page) {
        const record = await readMix(env.SHARE_KV, page[1]);
        if (record === null) return html(messagePage("Mix not found", "This link doesn't point to a mix."), 404);
        if (record.deleted) return html(messagePage("No longer shared", "This mix is no longer shared."), 410);
        return html(mixPage(record.doc, url.href));
    }
    const invite = ROOM_PAGE.exec(path);
    if (method === "GET" && invite) {
        if (!(await joinAllowed(request, env))) return html(messagePage("Slow down", "Too many requests. Try again in a minute."), 429);
        const pv = await roomPreview(env.ROOMS.get(env.ROOMS.idFromName(invite[1])));
        return pv ? html(roomPage(pv, url.href)) : html(messagePage("Session ended", "This listening session has ended."), 404);
    }
    return json({ error: "not_found" }, 404);
}

export function json(obj, status = 200, extra = {}) {
    return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", ...extra } });
}
const HTML_HEADERS = {
    "content-type": "text/html; charset=utf-8",
    "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; img-src https:",
    "x-content-type-options": "nosniff",
};
const html = (body, status = 200) => new Response(body, { status, headers: HTML_HEADERS });
const methodNotAllowed = () => json({ error: "method_not_allowed" }, 405);

async function readBody(request) {
    if (Number(request.headers.get("content-length")) > MAX_BODY_BYTES) return { tooBig: true };
    const buf = await request.arrayBuffer();
    if (buf.byteLength > MAX_BODY_BYTES) return { tooBig: true };
    try { return { body: JSON.parse(new TextDecoder().decode(buf)) }; } catch { return { body: null }; }
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

const ip = (request) => limitKey(request.headers.get("CF-Connecting-IP") || "?");

async function createMix(request, env, url) {
    if (!(await env.CREATE_RL.limit({ key: ip(request) })).success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    const { body, tooBig } = await readBody(request);
    if (tooBig) return json({ error: "too_large" }, 413);
    if (!body || !validEditKey(body.editKey)) return json({ error: "bad_request" }, 400);
    const problem = validateDoc(body.doc);
    if (problem) return json({ error: "bad_request", message: problem }, 400);
    const id = await freeId(env.SHARE_KV);
    const doc = { ...cleanDoc(body.doc), id, version: 1, updatedAt: Math.floor(Date.now() / 1000) };
    await writeMix(env.SHARE_KV, id, { doc, keyHash: await sha256Hex(body.editKey), deleted: false });
    return json({ id, version: 1, url: `${url.origin}/m/${id}` }, 201);
}

/** Loads a mix: { record } when live, else a ready 404/410 response. */
async function load(env, id) {
    const record = await readMix(env.SHARE_KV, id);
    if (record === null) return { response: json({ error: "not_found" }, 404) };
    if (record.deleted) return { response: json({ error: "gone" }, 410) };
    return { record };
}

async function getMix(env, id) {
    const { record, response } = await load(env, id);
    return response ?? json(record.doc, 200, { "cache-control": "no-store" });
}

async function getVersion(env, id) {
    const { record, response } = await load(env, id);
    return response ?? json({ version: record.doc.version }, 200, { "cache-control": "no-store" });
}

async function authorised(request, record) {
    const key = request.headers.get("X-Stash-Edit-Key") || "";
    return validEditKey(key) && sameHex(await sha256Hex(key), record.keyHash);
}

async function updateMix(request, env, id) {
    if (!(await env.WRITE_RL.limit({ key: ip(request) })).success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    const { record, response } = await load(env, id);
    if (response) return response;
    if (!(await authorised(request, record))) return json({ error: "forbidden" }, 403);
    const { body, tooBig } = await readBody(request);
    if (tooBig) return json({ error: "too_large" }, 413);
    const problem = validateDoc(body?.doc);
    if (problem) return json({ error: "bad_request", message: problem }, 400);
    // ponytail: KV is eventually consistent, so a stale read can return an old version; the owner (the
    // only writer) sends the version it last got back, which floors the new one. Still open: a stale
    // read just after DELETE could resurrect the mix (the app stops PUTting after a delete). Upgrade
    // path: a Durable Object per mix.
    const base = Number.isInteger(body.baseVersion) && body.baseVersion >= 0 && body.baseVersion <= 1_000_000_000 ? body.baseVersion : 0; // stays a Kotlin Int
    const version = Math.max(record.doc.version, base) + 1;
    const doc = { ...cleanDoc(body.doc), id, version, updatedAt: Math.floor(Date.now() / 1000) };
    await writeMix(env.SHARE_KV, id, { ...record, doc });
    return json({ version });
}

async function deleteMix(request, env, id) {
    if (!(await env.WRITE_RL.limit({ key: ip(request) })).success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    const { record, response } = await load(env, id);
    if (response) return response;
    if (!(await authorised(request, record))) return json({ error: "forbidden" }, 403);
    await writeTombstone(env.SHARE_KV, id);
    return new Response(null, { status: 204 });
}

/** JOIN_RL: 60 room lookups (preview, socket, invite page) a minute per IP, so live codes can't be found by walking. */
const joinAllowed = async (request, env) => (await env.JOIN_RL.limit({ key: ip(request) })).success;

async function roomPreview(stub) {
    const r = await stub.fetch(new Request("https://room/preview"));
    return r.status === 200 ? r.json() : null;
}

/** POST /v1/rooms (spec 2026-09-24 §2): a fresh room, its code and the host key (only its SHA-256 is kept). */
async function createRoom(request, env, url) {
    if (!(await env.ROOM_RL.limit({ key: ip(request) })).success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    const { body, tooBig } = await readBody(request);
    if (tooBig) return json({ error: "too_large" }, 413);
    const hostName = typeof body?.hostName === "string" ? body.hostName.trim().slice(0, 40) || undefined : undefined;
    const hostKey = base64url(crypto.getRandomValues(new Uint8Array(32)));
    const keyHash = await sha256Hex(hostKey);
    for (let i = 0; i < 5; i++) {
        const code = randomCode();
        const stub = env.ROOMS.get(env.ROOMS.idFromName(code));
        const r = await stub.fetch(new Request("https://room/init", { method: "POST", body: JSON.stringify({ code, hostName, keyHash }) }));
        if (r.status === 201) return json({ code, hostKey, url: `${url.origin}/l/${code}` }, 201);
    }
    return json({ error: "unavailable" }, 503, { "Retry-After": "2" });
}
