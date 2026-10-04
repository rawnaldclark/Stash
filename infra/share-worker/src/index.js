/**
 * Stash Share: Cloudflare Worker (spec docs/superpowers/specs/2026-09-23-shared-mixes-design.md §4).
 * One KV document per shared mix; updates and deletes are authorised by a per-mix edit key whose
 * SHA-256 is all we store. Short song links (src/tracks.js) and their preview art (src/art.js) follow the
 * stashfm.app contract of 2026-10-03.
 */
import { cleanDoc, cleanTrackLink, validateDoc, validateTrackLink, validEditKey, MAX_BODY_BYTES, MAX_TRACK_BODY_BYTES } from "./validate.js";
import { freeId, readMix, sameHex, sha256Hex, writeMix, writeTombstone } from "./store.js";
import { APP_LINK_ORIGIN, assetLinks, landingPage, messagePage, mixPage, roomPage, songPage } from "./pages.js";
import { legacyTrackUrl, publicTrack, readTrack, saveTrack, trackFromQuery, writeTrack } from "./tracks.js";
import { legacyArt, songLinkArt } from "./art.js";
import { base64url, randomCode } from "./listen-room.js";
import { ip, json, limitKey } from "./http.js";
import { cleanup, communityRoute } from "./community.js";
export { limitKey }; // test/hardening.test.js imports it from here

export { ListenRoom } from "./listen-room.js";

const MIX_API = /^\/v1\/mixes\/([A-Za-z0-9]{8})(\/version)?$/;
const TRACK_API = /^\/v1\/tracks\/([A-Za-z0-9]{8})$/;
const TRACK_PAGE = /^\/t\/([A-Za-z0-9]{8})$/;
/** The canonical host since 2026-10-03. Its `/` is a placeholder landing page; workers.dev's `/` stays a 404. */
const SITE_HOST = "stashfm.app";

/** Room codes: 8 of the 32 unambiguous symbols in listen-room.js (no 0/O, 1/I). */
const ROOM_API = /^\/v1\/rooms\/([A-HJ-NP-Z2-9]{8})(\/ws)?$/;
const ROOM_PAGE = /^\/l\/([A-HJ-NP-Z2-9]{8})$/;
/** Uppercases the code segment only, so hand-typed lowercase links work and `/ws` stays as is. */
const upperCode = (path) => path.replace(/^(\/v1\/rooms\/|\/l\/)([^/]+)/, (_, head, code) => head + code.toUpperCase());

export default {
    /** Any throw (a KV write 429, freeId giving up) becomes a retryable 503, not a bare 500. */
    async fetch(request, env, ctx) {
        try {
            return await handle(request, env, ctx);
        } catch (e) {
            console.error(e); // visible in `wrangler tail`
            return json({ error: "unavailable" }, 503, { "Retry-After": "2" });
        }
    },
    /** The daily cron in wrangler.toml: expired and long-removed Community posts go (spec 2026-09-26 §2). */
    async scheduled(_controller, env, ctx) {
        ctx.waitUntil(cleanup(env, Date.now()));
    },
};

/**
 * Every URL a response carries is built from the request's own origin, so stashfm.app requests get stashfm.app
 * links and workers.dev ones (old app versions) keep getting workers.dev links.
 */
export async function handle(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method === "HEAD" ? "GET" : request.method;
    // Art write-backs run after the response when there's a ctx; tests have none, so there they finish first.
    const later = (p) => (ctx ? ctx.waitUntil(p) : p);
    // ART_RL: art lookups per IP a minute. Over it, a page uses hqdefault (or no image) instead of asking Spotify and Deezer.
    const allowLookup = async () => (await env.ART_RL.limit({ key: ip(request) })).success;

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
    if (path === "/v1/tracks") return method === "POST" ? createTrack(request, env, url) : methodNotAllowed();
    const track = TRACK_API.exec(path);
    if (track) return method === "GET" ? getTrack(env, track[1]) : methodNotAllowed();
    if (path === "/v1/rooms") return method === "POST" ? createRoom(request, env, url) : methodNotAllowed();
    const room = ROOM_API.exec(upperCode(path));
    if (room) {
        if (method !== "GET") return methodNotAllowed();
        if (room[2] && request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return new Response(null, { status: 426 });
        // Before the Durable Object: a code-guessing walk never wakes a room (spec §2).
        if (!(await joinAllowed(request, env))) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
        const stub = env.ROOMS.get(env.ROOMS.idFromName(room[1]));
        if (room[2]) return stub.fetch(new Request(`https://room/ws${url.search}`, request));
        const pv = await roomPreview(stub);
        return pv ? json(pv, 200, { "cache-control": "no-store" }) : json({ error: "not_found" }, 404);
    }
    if (path.startsWith("/v1/community/")) return communityRoute(request, env, path, method);
    if (method === "GET" && path === "/.well-known/assetlinks.json") return json(assetLinks(), 200, { "cache-control": "public, max-age=3600" });
    // Pages forgive one trailing slash (`/t/{id}/`, `/m/{id}/`, `/t/?…`), which some apps and people add; APIs stay exact.
    const pagePath = path.length > 1 && path.endsWith("/") ? path.slice(0, -1) : path;
    if (method === "GET" && pagePath === "/t") {
        const song = trackFromQuery(url.searchParams);
        const image = await legacyArt(url.origin, song, { later, allowLookup });
        return html(songPage(song, { pageUrl: legacyTrackUrl(url.origin, song), image }));
    }
    const short = TRACK_PAGE.exec(pagePath);
    if (method === "GET" && short) return songLinkPage(env, url, short[1], { later, allowLookup });
    // Page URLs drop the query (`?fbclid=…`) and the slash, so a preview's og:url and canonical are the link itself.
    const here = `${url.origin}${pagePath}`;
    const page = /^\/m\/([A-Za-z0-9]{8})$/.exec(pagePath);
    if (method === "GET" && page) {
        const record = await readMix(env.SHARE_KV, page[1]);
        if (record === null) return html(messagePage("Mix not found", "This link doesn't point to a mix.", here), 404);
        if (record.deleted) return html(messagePage("No longer shared", "This mix is no longer shared.", here), 410);
        return html(mixPage(record.doc, here));
    }
    const invite = ROOM_PAGE.exec(upperCode(pagePath));
    if (method === "GET" && invite) {
        const inviteUrl = `${url.origin}/l/${invite[1]}`;
        if (!(await joinAllowed(request, env))) return html(messagePage("Slow down", "Too many requests. Try again in a minute.", inviteUrl), 429);
        const pv = await roomPreview(env.ROOMS.get(env.ROOMS.idFromName(invite[1])));
        return pv ? html(roomPage(pv, inviteUrl)) : html(messagePage("Session ended", "This listening session has ended.", inviteUrl), 404);
    }
    if (method === "GET" && path === "/" && url.hostname === SITE_HOST) return html(landingPage(`${url.origin}/`));
    return json({ error: "not_found" }, 404);
}

const HTML_HEADERS = {
    "content-type": "text/html; charset=utf-8",
    "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; img-src https:",
    "x-content-type-options": "nosniff",
};
const html = (body, status = 200) => new Response(body, { status, headers: HTML_HEADERS });
const methodNotAllowed = () => json({ error: "method_not_allowed" }, 405);

async function readBody(request, max = MAX_BODY_BYTES) {
    if (Number(request.headers.get("content-length")) > max) return { tooBig: true };
    const buf = await request.arrayBuffer();
    if (buf.byteLength > max) return { tooBig: true };
    try { return { body: JSON.parse(new TextDecoder().decode(buf)) }; } catch { return { body: null }; }
}

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

/**
 * POST /v1/tracks (contract 2026-10-03): a short link for one song. The id comes from the song, so a song shared
 * before answers 200 with its existing link, and a new one 201.
 */
async function createTrack(request, env, url) {
    if (!(await env.TRACK_RL.limit({ key: ip(request) })).success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
    const { body, tooBig } = await readBody(request, MAX_TRACK_BODY_BYTES);
    if (tooBig) return json({ error: "too_large" }, 413);
    const problem = validateTrackLink(body);
    if (problem) return json({ error: "bad_request", message: problem }, 400);
    const saved = await saveTrack(env.SHARE_KV, cleanTrackLink(body));
    // All five ids taken by other songs: that won't change, so no Retry-After. The app shares the long link instead.
    if (!saved) return json({ error: "unavailable" }, 503);
    return json({ id: saved.id, url: `${url.origin}/t/${saved.id}` }, saved.created ? 201 : 200);
}

/**
 * GET /v1/tracks/{id}. A 404 isn't cacheable: KV can take a minute to show a new link in another region, and a
 * friend opening it that soon mustn't be told "not found" for five minutes.
 */
async function getTrack(env, id) {
    const record = await readTrack(env.SHARE_KV, id);
    if (record === null) return json({ error: "not_found" }, 404, { "cache-control": "no-store" });
    return json({ track: publicTrack(record) }, 200, { "cache-control": "public, max-age=300" });
}

/**
 * GET /t/{id}: the short song page. A lookup's result (found art, or when it found nothing) is written back, so later
 * views and GET /v1/tracks skip it. "Open in Stash" hands the app the long workers.dev form, which every version reads.
 */
async function songLinkPage(env, url, id, { later, allowLookup }) {
    const pageUrl = `${url.origin}/t/${id}`;
    const record = await readTrack(env.SHARE_KV, id);
    if (record === null) return html(messagePage("Song not found", "This link doesn't point to a song.", pageUrl), 404);
    const { art, patch } = await songLinkArt(record, { allowLookup });
    if (patch) {
        // KV takes one write a second per key and chat apps fetch a fresh link several times at once, so a write can
        // fail; that only costs a lookup on the next view, never the page.
        await later(writeTrack(env.SHARE_KV, id, { ...record, ...patch }).catch((e) => console.error(e)));
    }
    return html(songPage(record.track, { pageUrl, appUrl: legacyTrackUrl(APP_LINK_ORIGIN, record.track), image: art }));
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
