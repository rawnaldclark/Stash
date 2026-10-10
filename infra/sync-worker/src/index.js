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
import { answer, fail, limitKey, parseDeviceAuth, PLAYER_HEADER, randomId, rpc, sameSecret, tokenHashOf } from "./http.js";
import { cleanDevice, isDeviceId, isNewSpaceId, isPairId, isSendId, isSpaceId, LIMITS, pathInt } from "./validate.js";

export { SyncSpace } from "./sync-space.js";
export { PairSlot } from "./pair-slot.js";
export { Quota } from "./quota.js";

/** Only functions and classes may be exported from the main module (workerd refuses other values), so constants live in http.js. */

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
    ["devices/:target", { DELETE: "removeDevice" }],
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
    /** Only removal takes `me`; a queue is always named by its device's id. */
    target: (s) => (s === "me" || isDeviceId(s) ? s : null),
    did: (s) => (isDeviceId(s) ? s : null),
    to: (s) => (isDeviceId(s) ? s : null),
    sendId: (s) => (isSendId(s) ? s : null),
    epoch: (s) => pathInt(s, 2 ** 31),
    seq: (s) => pathInt(s),
    upto: (s) => pathInt(s),
    part: (s) => pathInt(s, LIMITS.parts - 1),
    count: (s) => pathInt(s, LIMITS.parts),
};

/**
 * The routes the daily caps never refuse (src/space.js EXEMPT) get their own limits instead, keyed on the hash of the token
 * presented: a caller can only fill the bucket of a token it holds, so forging another device's id (public to members, to a
 * removed device, to anyone who saw the QR) fills a bucket nobody uses. Calls with a bad token are then refused as revoked.
 */
const SAFETY = new Set(["removeDevice", "deleteSpace", "rotate"]);
const OPENS = new Set(["get", "key"]);

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
    const tokenHash = auth && (await tokenHashOf(auth.token));
    return tokenHash ? { deviceId: auth.deviceId, tokenHash } : null;
}
const noAuth = () => fail(401, "unauthorized", "Sign the request with this device's token");

/** A Quota object's take (src/quota.js): null when allowed, else the 429. */
async function overQuota(env, name, kind) {
    const q = await rpc(env.QUOTAS.get(env.QUOTAS.idFromName(name)), "take", { kind });
    return q.ok ? null : fail(429, "rate_limited", "Too many new links, try again later", { "Retry-After": String(Math.max(1, q.retryAfter)) });
}

export async function handle(request, env, _ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;
    if (url.search) return fail(400, "bad_request", "Ids go in the path, never in a query");

    // Player requests: the forwarding header must be right when present (a wrong key is never treated as a phone).
    const keyHeader = request.headers.get(PLAYER_HEADER);
    // Both sides trimmed: headers arrive whitespace-trimmed, and a secret put from a file may end in a newline.
    const player = keyHeader !== null && (await sameSecret(keyHeader.trim(), env.PLAYER_KEY?.trim()));
    if (keyHeader !== null && !player) return fail(403, "forbidden", "Not the player");
    const fwdIp = request.headers.get("X-Stash-Client-IP");
    const ip = limitKey(player && fwdIp && fwdIp.length <= 64 && /^[0-9A-Fa-f:.]+$/.test(fwdIp) ? fwdIp : request.headers.get("CF-Connecting-IP") || "?");
    const playerOnly = () => (env.PLAYER_KEY?.trim() ? fail(403, "forbidden", "Only through Stash on the web") : fail(503, "unavailable", "Pairing is not set up"));

    // ------------------------------------------------------------ pairing
    if (path === "/v1/pair") {
        if (method !== "POST") return methodNotAllowed("POST");
        if (!player) return playerOnly();
        const header = request.headers.get("X-Stash-Session");
        const session = header && /^[A-Za-z0-9_-]{8,64}$/.test(header) ? `s:${header}` : `ip:${ip}`;
        const over = await limited(env.API_RL, ip);
        if (over) return over;
        const r = await readJson(request);
        if ("response" in r) return r.response;
        if (!cleanDevice(r.value?.device, "web")) return fail(400, "bad_request", "Not a device");
        // 6 codes per session per 10 minutes (spec §6.4), counted where a binding can't: a Quota object per session.
        const quota = await overQuota(env, session, "code");
        if (quota) return quota;
        const pairId = randomId();
        return answer(await rpc(env.PAIRS.get(env.PAIRS.idFromName(pairId)), "open", { pairId, device: r.value?.device, session }));
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
        if (sub === "label") return answer(await rpc(slot, "label"));
        if (sub === "answer") {
            const r = await readJson(request);
            return "response" in r ? r.response : answer(await rpc(slot, "answer", { body: r.value }));
        }
        const caller = await deviceCaller(request);
        if (!caller) return noAuth();
        if (sub === "") return answer(await rpc(slot, "poll", { caller }));
        if (method === "GET") return answer(await rpc(slot, "readReply", { caller }));
        const r = await readJson(request);
        return "response" in r ? r.response : answer(await rpc(slot, "reply", { caller, body: r.value }));
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
        if (!isPairId(pairId) || !isNewSpaceId(spaceId)) return fail(400, "bad_request", "Need pairId and spaceId (s_ + 16 random bytes)");
        // The phone that answered the slot creates the space under the id it minted (sync-v1: it seals that id into its answer);
        // the claim hands over both devices and holds the code while the space is made. The code is burned only if that
        // succeeds: a refusal (quota, a taken id) or an error releases it, so the same code can be tried again.
        const slot = env.PAIRS.get(env.PAIRS.idFromName(pairId));
        const claim = await rpc(slot, "claim", { caller, mode: "create" });
        if (claim.status !== 200) return answer(claim);
        const { phone, browser, session } = claim.body;
        let made;
        try {
            // At most 10 new spaces a day from one player session's codes, and 20 from one phone IP.
            const quota = (session ? await overQuota(env, session, "space") : null) ?? (await overQuota(env, `ip:${ip}`, "spaceIp"));
            if (quota) {
                await rpc(slot, "release");
                return quota;
            }
            made = await rpc(env.SPACES.get(env.SPACES.idFromName(spaceId)), "create", { spaceId, phone, browser });
        } catch (e) {
            await rpc(slot, "release").catch(() => {});
            throw e;
        }
        if (made.status !== 201) await rpc(slot, "release");
        return answer(made);
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
        if (SAFETY.has(route.op) || OPENS.has(route.op)) {
            const own = await limited(SAFETY.has(route.op) ? env.SAFE_RL : env.OPEN_RL, `t:${caller.tokenHash}`);
            if (own) return own;
        }
        let body;
        if (WITH_BODY.has(route.op)) {
            const r = await readJson(request);
            if ("response" in r) return r.response;
            body = r.value;
        }
        const ifMatch = route.op === "configPut" ? request.headers.get("if-match") : undefined;
        return answer(await rpc(env.SPACES.get(env.SPACES.idFromName(spaceId)), "call", { op: route.op, caller, params: route.params, body, ifMatch, player }));
    }
    return notFound();
}
