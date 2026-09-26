/**
 * Stash Community routes (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2).
 * D1 tables: posts, votes, blocked (migrations/0001_community.sql). A phone is sha256(its key);
 * a network is sha256(COMMUNITY_SALT | IPv4, or IPv6 /56). Every error body is {error: "<code>"}.
 */
import { json, networkOf } from "./http.js";
import { validEditKey } from "./validate.js";
import { newId, sha256Hex } from "./store.js";
import { cleanPost, summaryOf } from "./community-post.js";

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
export const POST_TTL_MS = 30 * DAY;
export const DAILY_POSTS = 2;
export const LIVE_POSTS = 5;
export const NETWORK_DAILY_POSTS = 10;
export const HIDE_AT = -3; // up - down at or below this hides a post
export const VOTES_PER_NETWORK = 2; // counted votes per network per post, in each direction
export const MAX_REQUEST_BYTES = 256 * 1024;
/** 3.6 h per net vote: 10 more votes keep a post above a newer one for 36 h (spec §2 Ordering). */
export const MS_PER_VOTE = 12_960_000;

const POST_API = /^\/v1\/community\/posts\/([A-Za-z0-9]{8})(\/vote)?$/;
const KEY_HEADER = "X-Stash-Community-Key";
const rateLimited = () => json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
const notAllowed = () => json({ error: "method_not_allowed" }, 405);
const gone = () => json({ error: "gone" }, 404);

/** Routes every /v1/community/* request. [method] has HEAD folded into GET. */
export async function communityRoute(request, env, path, method) {
    const now = Date.now();
    if (method === "GET" && !(await env.COMMUNITY_READ_RL.limit({ key: network(request) })).success) return rateLimited();
    if (path === "/v1/community/posts") return method === "POST" ? createPost(request, env, now) : notAllowed();
    return json({ error: "not_found" }, 404);
}

/** The caller's poster id: {id} with a valid key, {none} without one, {bad} with a malformed one. */
async function identity(request) {
    const key = request.headers.get(KEY_HEADER);
    if (key === null) return { none: true };
    if (!validEditKey(key)) return { bad: true };
    return { id: await sha256Hex(key) };
}

/**
 * The caller's network (an IPv4 address, or an IPv6 /56). The per-minute limits count by it too (spec §2), so
 * hopping /64s inside one /56 doesn't dodge them; the stored form is salted and hashed.
 */
const network = (request) => networkOf(request.headers.get("CF-Connecting-IP") || "?");
const networkHash = (request, env) => {
    // Unsalted, the stored IPv4 hashes could be reversed by trying every address (spec §2): refuse instead.
    if (!env.COMMUNITY_SALT) throw new Error("COMMUNITY_SALT is not set");
    return sha256Hex(`${env.COMMUNITY_SALT}|${network(request)}`);
};

async function readJson(request) {
    if (Number(request.headers.get("content-length")) > MAX_REQUEST_BYTES) return { tooBig: true };
    const buf = await request.arrayBuffer();
    if (buf.byteLength > MAX_REQUEST_BYTES) return { tooBig: true };
    try { return { body: JSON.parse(new TextDecoder().decode(buf)) }; } catch { return { body: null }; }
}

/**
 * How many posts [poster] made in the last day, has live, and [net] made in the last day; and a block.
 * Binds ?1 poster, ?2 a day ago, ?3 now, ?4 network. The post INSERT selects from it, so each limit is counted in one place.
 */
const LIMITS_SQL = `SELECT
    (SELECT COUNT(*) FROM blocked WHERE poster = ?1) AS blocked,
    (SELECT COUNT(*) FROM posts WHERE poster = ?1 AND created_at > ?2) AS today,
    (SELECT COUNT(*) FROM posts WHERE poster = ?1 AND removed_at IS NULL AND expires_at > ?3) AS live,
    (SELECT COUNT(*) FROM posts WHERE ip_hash = ?4 AND created_at > ?2) AS network`;

async function createPost(request, env, now) {
    if (!(await env.COMMUNITY_WRITE_RL.limit({ key: network(request) })).success) return rateLimited();
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const { body, tooBig } = await readJson(request);
    if (tooBig) return json({ error: "too_large" }, 413);
    const post = cleanPost(body);
    if (!post) return json({ error: "bad_request" }, 400);
    const net = await networkHash(request, env);
    const limits = [who.id, now - DAY, now, net]; // LIMITS_SQL's ?1..?4
    // The limits are checked inside the INSERT itself, so two posts at the same moment can't both slip under
    // them; only when nothing was inserted is the reason looked up.
    for (let attempt = 0; attempt < 5; attempt++) {
        const id = newId();
        const { meta: { changes } } = await env.COMMUNITY_DB.prepare(
            `INSERT INTO posts (id, kind, title, poster_name, poster, ip_hash, summary, body, track_count, created_at, expires_at)
             SELECT ?5, ?6, ?7, ?8, ?1, ?4, ?9, ?10, ?11, ?3, ?12 FROM (${LIMITS_SQL})
             WHERE blocked = 0 AND today < ${DAILY_POSTS} AND live < ${LIVE_POSTS} AND network < ${NETWORK_DAILY_POSTS}
             ON CONFLICT (id) DO NOTHING`,
        ).bind(...limits, id, post.kind, post.title, post.name, JSON.stringify(post.summary), JSON.stringify(post.body),
            post.count, now + POST_TTL_MS).run();
        if (changes === 1) return json({ id }, 201);
        const c = await env.COMMUNITY_DB.prepare(LIMITS_SQL).bind(...limits).first();
        if (c.blocked) return json({ error: "blocked" }, 403);
        if (c.today >= DAILY_POSTS) return json({ error: "daily_limit" }, 429);
        if (c.live >= LIVE_POSTS) return json({ error: "live_limit" }, 429);
        if (c.network >= NETWORK_DAILY_POSTS) return json({ error: "network_limit" }, 429);
        // None hit: the id was taken, or a take-down or unblock landed in between. Try again.
    }
    return json({ error: "unavailable" }, 503, { "Retry-After": "2" });
}
