/**
 * stash-sync: links Stash for Android with Stash on the web (spec docs/superpowers/specs/2026-10-10-link-sync-handoff-design.md §6).
 * Pairing slots (PairSlot) and sync spaces (SyncSpace) are Durable Objects; this Worker only routes, checks shapes,
 * sizes, rate limits and who may call what, and hashes device tokens before they reach an object.
 *
 * Callers: the phone directly, the browser only through the player Worker (play.stashfm.app's /api/sync/*), which adds
 * `X-Stash-Player-Key` (the PLAYER_KEY secret), `X-Stash-Client-IP` and `X-Stash-Session`. Routes marked "player" refuse
 * anything else, so pairing slots can only be opened from a signed-in player session (no open relay).
 * Every id is in the path, never in a query (the stashfm.app zone's cache ignores query strings); every answer is no-store.
 */
import { answer, fail, limitKey, parseDeviceAuth, randomId, sameSecret, sha256Hex } from "./http.js";
import { isDeviceId, isPairId, isSendId, isSpaceId, LIMITS, pathInt } from "./validate.js";

export { SyncSpace } from "./sync-space.js";
export { PairSlot } from "./pair-slot.js";

export const PLAYER_HEADER = "X-Stash-Player-Key";

export default {
    /** Any throw (an object reset, a storage error) becomes a retryable 503, not a bare 500. */
    async fetch(request, env, ctx) {
        let res;
        try {
            res = await handle(request, env, ctx);
        } catch (e) {
            console.error(e); // visible in `wrangler tail`; never logs bodies or tokens
            res = fail(503, "unavailable", "Can't reach Stash right now. Try again in a minute.", { "Retry-After": "2" });
        }
        res.headers.set("X-Stash-Server-Time", String(Date.now()));
        return res;
    },
};

/**
 * The routes under /v1/spaces/{sid}/…: path pattern → method → operation. `:x` segments are checked by PARAMS.
 * `devices/me/label` is listed before `devices/:did` (literal segments win).
 */
const SPACE_ROUTES = [
    ["", { GET: "get", DELETE: "deleteSpace" }],
    ["devices", { POST: "join" }],
    ["devices/me/label", { PUT: "label" }],
    ["devices/:did", { DELETE: "removeDevice" }],
    ["key/:epoch", { GET: "key" }],
    ["rotate", { POST: "rotate" }],
    ["log", { POST: "logAppend" }],
    ["log/after/:seq", { GET: "logAfter" }],
    ["snapshot/:upto/:part/:count", { PUT: "snapshotPut" }],
    ["snapshot/:part", { GET: "snapshotGet" }],
    ["slots/config", { GET: "configGet", PUT: "configPut" }],
    ["slots/now", { GET: "nowGet", PUT: "nowPut" }],
    ["slots/queue", { PUT: "queuePut" }],
    ["slots/queue/:did", { GET: "queueGet" }],
    ["inbox", { GET: "inboxList" }],
    ["inbox/:sendId", { DELETE: "inboxDelete" }],
    ["inbox/:sendId/:part", { GET: "inboxGet" }],
    ["inbox/:to/:sendId/:part/:count", { PUT: "inboxPut" }],
].map(([p, methods]) => [p ? p.split("/") : [], methods]);

const PARAMS = {
    did: (s) => (s === "me" || isDeviceId(s) ? s : null),
    to: (s) => (isDeviceId(s) ? s : null),
    sendId: (s) => (isSendId(s) ? s : null),
    epoch: (s) => pathInt(s, 2 ** 31),
    seq: (s) => pathInt(s),
    upto: (s) => pathInt(s),
    part: (s) => pathInt(s, LIMITS.parts - 1),
    count: (s) => pathInt(s, LIMITS.parts),
};

const WITH_BODY = new Set(["join", "label", "rotate", "logAppend", "snapshotPut", "configPut", "nowPut", "queuePut", "inboxPut"]);

/** `{ op, params }`, `{ allow }` (the path exists, the method doesn't), or null. */
export function spaceRoute(segments, method) {
    for (const [pattern, methods] of SPACE_ROUTES) {
        if (pattern.length !== segments.length) continue;
        const params = {};
        let match = true;
        for (let i = 0; i < pattern.length && match; i++) {
            const p = pattern[i];
            if (p.startsWith(":")) {
                const v = PARAMS[p.slice(1)](segments[i]);
                if (v === null) match = false;
                else params[p.slice(1)] = v;
            } else if (p !== segments[i]) match = false;
        }
        if (!match) continue;
        return methods[method] ? { op: methods[method], params } : { allow: Object.keys(methods).join(", ") };
    }
    return null;
}

const notFound = () => fail(404, "not_found", "No such route");
const methodNotAllowed = (allow) => fail(405, "bad_request", "Method not allowed", { Allow: allow });

/** Reads a JSON body of at most LIMITS.body bytes, stopping as soon as it is too big. `{ value }` or `{ response }`. */
export async function readJson(request, max = LIMITS.body) {
    if (!(request.headers.get("content-type") ?? "").toLowerCase().startsWith("application/json")) return { response: fail(415, "bad_request", "Send JSON") };
    if (Number(request.headers.get("content-length")) > max) return { response: fail(413, "too_large", "Too large") };
    if (!request.body) return { response: fail(400, "bad_request", "No body") };
    const reader = request.body.getReader();
    const chunks = [];
    let size = 0;
    for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > max) {
            await reader.cancel().catch(() => {});
            return { response: fail(413, "too_large", "Too large") };
        }
        chunks.push(value);
    }
    const buf = new Uint8Array(size);
    let at = 0;
    for (const c of chunks) { buf.set(c, at); at += c.byteLength; }
    try {
        return { value: JSON.parse(new TextDecoder().decode(buf)) };
    } catch {
        return { response: fail(400, "bad_request", "Not JSON") };
    }
}

/** A ratelimits binding keyed by `key`: null when allowed, else the 429. A missing binding (local tests) allows. */
async function limited(binding, key) {
    if (!binding) return null;
    const { success } = await binding.limit({ key });
    return success ? null : fail(429, "rate_limited", "Too many requests, try again in a minute", { "Retry-After": "60" });
}

/** The caller's device from `Authorization: Stash-Device <id>:<token>`: its id and the token's SHA-256 (the token goes no further). */
async function deviceCaller(request) {
    const auth = parseDeviceAuth(request.headers.get("authorization"));
    return auth ? { deviceId: auth.deviceId, tokenHash: await sha256Hex(auth.token) } : null;
}
const noAuth = () => fail(401, "unauthorized", "Sign the request with this device's token");

export async function handle(request, env, _ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;
    if (url.search) return fail(400, "bad_request", "Ids go in the path, never in a query");

    // Player requests: the forwarding header must be right when present (a wrong key is never treated as a phone).
    const keyHeader = request.headers.get(PLAYER_HEADER);
    const player = keyHeader !== null && (await sameSecret(keyHeader, env.PLAYER_KEY));
    if (keyHeader !== null && !player) return fail(403, "forbidden", "Not the player");
    const fwdIp = request.headers.get("X-Stash-Client-IP");
    const ip = limitKey(player && fwdIp && fwdIp.length <= 64 && /^[0-9A-Fa-f:.]+$/.test(fwdIp) ? fwdIp : request.headers.get("CF-Connecting-IP") || "?");
    const playerOnly = () => (env.PLAYER_KEY ? fail(403, "forbidden", "Only through Stash on the web") : fail(503, "unavailable", "Pairing is not set up"));

    // ------------------------------------------------------------ pairing
    if (path === "/v1/pair") {
        if (method !== "POST") return methodNotAllowed("POST");
        if (!player) return playerOnly();
        const session = request.headers.get("X-Stash-Session");
        const sessionKey = session && /^[A-Za-z0-9_-]{8,64}$/.test(session) ? `s:${session}` : `ip:${ip}`;
        const over = (await limited(env.PAIR_OPEN_RL, sessionKey)) ?? (await limited(env.API_RL, ip));
        if (over) return over;
        const r = await readJson(request);
        if ("response" in r) return r.response;
        const pairId = randomId();
        return answer(await env.PAIRS.get(env.PAIRS.idFromName(pairId)).open({ pairId, device: r.value?.device }));
    }
    let m = /^\/v1\/pair\/([^/]+)(?:\/(label|answer|reply))?$/.exec(path);
    if (m) {
        const [, pairId, sub = ""] = m;
        if (!isPairId(pairId)) return notFound();
        const allowed = { "": ["GET"], label: ["GET"], answer: ["POST"], reply: ["GET", "POST"] }[sub];
        if (!allowed.includes(method)) return methodNotAllowed(allowed.join(", "));
        const slot = env.PAIRS.get(env.PAIRS.idFromName(pairId));
        // The browser's two calls come through the player; the phone's three are public and share PAIR_RL (10/min per IP).
        const fromBrowser = sub === "" || (sub === "reply" && method === "POST");
        if (fromBrowser && !player) return playerOnly();
        const over = await limited(fromBrowser ? env.API_RL : env.PAIR_RL, ip);
        if (over) return over;
        if (sub === "label") return answer(await slot.label());
        if (sub === "answer") {
            const r = await readJson(request);
            return "response" in r ? r.response : answer(await slot.answer({ body: r.value }));
        }
        const caller = await deviceCaller(request);
        if (!caller) return noAuth();
        if (sub === "") return answer(await slot.poll({ caller }));
        if (method === "GET") return answer(await slot.readReply({ caller }));
        const r = await readJson(request);
        return "response" in r ? r.response : answer(await slot.reply({ caller, body: r.value }));
    }

    // ------------------------------------------------------------ spaces
    if (path === "/v1/spaces") {
        if (method !== "POST") return methodNotAllowed("POST");
        const over = await limited(env.API_RL, ip);
        if (over) return over;
        const caller = await deviceCaller(request);
        if (!caller) return noAuth();
        const r = await readJson(request);
        if ("response" in r) return r.response;
        const { pairId, spaceId } = r.value ?? {};
        if (!isPairId(pairId) || !isSpaceId(spaceId)) return fail(400, "bad_request", "Need pairId and spaceId");
        // The phone that answered the slot creates the space; the claim burns the slot and hands over both devices.
        const claim = await env.PAIRS.get(env.PAIRS.idFromName(pairId)).claim({ caller, mode: "create" });
        if (claim.status !== 200) return answer(claim);
        return answer(await env.SPACES.get(env.SPACES.idFromName(spaceId)).create({ spaceId, phone: claim.body.phone, browser: claim.body.browser }));
    }
    m = /^\/v1\/spaces\/([^/]+)((?:\/[^/]+)*)$/.exec(path);
    if (m) {
        const [, spaceId, rest] = m;
        if (!isSpaceId(spaceId)) return notFound();
        const segments = rest ? rest.slice(1).split("/") : [];
        if (segments.length === 1 && segments[0] === "ws") return notFound(); // reserved for live control (spec §6.7)
        const route = spaceRoute(segments, method);
        if (!route) return notFound();
        if (route.allow) return methodNotAllowed(route.allow);
        const over = await limited(env.API_RL, ip);
        if (over) return over;
        const caller = await deviceCaller(request);
        if (!caller) return noAuth();
        let body;
        if (WITH_BODY.has(route.op)) {
            const r = await readJson(request);
            if ("response" in r) return r.response;
            body = r.value;
        }
        const ifMatch = route.op === "configPut" ? request.headers.get("if-match") : undefined;
        return answer(await env.SPACES.get(env.SPACES.idFromName(spaceId)).call({ op: route.op, caller, params: route.params, body, ifMatch }));
    }
    return notFound();
}
