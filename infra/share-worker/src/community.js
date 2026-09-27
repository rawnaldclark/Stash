/**
 * Stash Community routes (spec docs/superpowers/specs/2026-09-26-stash-community-design.md §2).
 * D1 tables: posts, votes, blocked (migrations/). A phone is sha256(its key);
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
export const HIDE_AT = -3; // up - down at or below this hides a post, unless the owner vouched for it
export const VOTES_PER_NETWORK = 2; // counted votes per network per post, in each direction
export const MAX_REQUEST_BYTES = 256 * 1024;
/**
 * 3.6 h per net vote: 10 more votes keep a post above a newer one for 36 h (spec §2 Ordering).
 * The posts_hot index holds this number: changing it needs a migration that rebuilds posts_hot,
 * or the list silently goes back to a full scan and sort.
 */
export const MS_PER_VOTE = 12_960_000;

const POST_API = /^\/v1\/community\/posts\/([A-Za-z0-9]{8})(\/vote)?$/;
const KEY_HEADER = "X-Stash-Community-Key";
const rateLimited = () => json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
const notAllowed = () => json({ error: "method_not_allowed" }, 405);
const gone = () => json({ error: "gone" }, 404);
/** A post that's missing, taken down or removed, or expired. Hidden is checked separately: its poster still sees it. */
const isGone = (post, now) => !post || post.removed_at !== null || post.expires_at <= now;
/** At or below HIDE_AT, unless the owner vouched for it (`restore`): votes never hide a vouched post. The feed's WHERE is the same rule in SQL. */
export const hiddenByVotes = (p) => p.up - p.down <= HIDE_AT && !p.vouched;

/** Routes every /v1/community/* request. [method] has HEAD folded into GET. */
export async function communityRoute(request, env, path, method) {
    const now = Date.now();
    if (method === "GET" && !(await env.COMMUNITY_READ_RL.limit({ key: network(request) })).success) return rateLimited();
    if (path === "/v1/community/feed") return method === "GET" ? feed(request, env, now) : notAllowed();
    if (path === "/v1/community/mine") return method === "GET" ? mine(request, env, now) : notAllowed();
    if (path === "/v1/community/me") return method === "GET" ? me(request, env, now) : notAllowed();
    if (path === "/v1/community/posts") return method === "POST" ? createPost(request, env, now) : notAllowed();
    const m = POST_API.exec(path);
    if (m) {
        if (m[2]) return method === "PUT" ? vote(request, env, m[1], now) : notAllowed();
        if (method === "GET") return getPost(request, env, m[1], now);
        if (method === "DELETE") return takeDown(request, env, m[1], now);
        return notAllowed();
    }
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

/** The columns a list needs. Never `body`: a Home open must not load song lists (spec §2). */
const LIST_COLUMNS = "p.id, p.kind, p.title, p.poster_name, p.poster, p.summary, p.track_count, p.created_at, p.up, p.down";
const NO_STORE = { "cache-control": "no-store" };

async function feed(request, env, now) {
    const who = await identity(request);
    if (who.bad) return json({ error: "bad_key" }, 401);
    const asked = Number.parseInt(new URL(request.url).searchParams.get("limit"), 10);
    const limit = Math.min(Math.max(Number.isNaN(asked) ? 5 : asked, 1), 100);
    const { results } = await env.COMMUNITY_DB.prepare(
        `SELECT ${LIST_COLUMNS}, v.value AS my_vote FROM posts p
         LEFT JOIN votes v ON v.post_id = p.id AND v.voter = ?1
         WHERE p.removed_at IS NULL AND p.expires_at > ?2 AND ((p.up - p.down) > ${HIDE_AT} OR p.vouched = 1)
         ORDER BY (p.up - p.down) + p.created_at / ${MS_PER_VOTE}.0 DESC, p.created_at DESC
         LIMIT ?3`,
    ).bind(who.id ?? "", now, limit).all();
    return json({ posts: results.map((r) => summaryOf(r, who.id ?? null)) }, 200, NO_STORE);
}

async function mine(request, env, now) {
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const { results } = await env.COMMUNITY_DB.prepare(
        `SELECT ${LIST_COLUMNS}, p.vouched, p.expires_at, NULL AS my_vote FROM posts p
         WHERE p.poster = ?1 AND p.removed_at IS NULL AND p.expires_at > ?2
         ORDER BY p.created_at DESC`,
    ).bind(who.id, now).all();
    const posts = results.map((r) => ({ ...summaryOf(r, who.id), hidden: hiddenByVotes(r), expiresAt: r.expires_at }));
    return json({ posts }, 200, NO_STORE);
}

async function me(request, env, now) {
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    // ?4 (the network) is unused: /me reports the per-phone limits only.
    const c = await env.COMMUNITY_DB.prepare(LIMITS_SQL).bind(who.id, now - DAY, now, "").first();
    return json({
        postsLeftToday: Math.max(0, DAILY_POSTS - c.today),
        spotsFree: Math.max(0, LIVE_POSTS - c.live),
        blocked: c.blocked > 0,
    }, 200, NO_STORE);
}

async function getPost(request, env, id, now) {
    const who = await identity(request);
    if (who.bad) return json({ error: "bad_key" }, 401);
    const r = await env.COMMUNITY_DB.prepare(
        `SELECT ${LIST_COLUMNS}, p.vouched, p.body, p.removed_at, p.expires_at, v.value AS my_vote FROM posts p
         LEFT JOIN votes v ON v.post_id = p.id AND v.voter = ?2
         WHERE p.id = ?1`,
    ).bind(id, who.id ?? "").first();
    if (isGone(r, now)) return gone();
    if (hiddenByVotes(r) && r.poster !== who.id) return gone(); // hidden: only its poster sees it
    const body = JSON.parse(r.body);
    const songs = body.track ? { track: body.track } : { tracks: body.tracks };
    return json({ post: { ...summaryOf(r, who.id ?? null), ...songs } }, 200, NO_STORE);
}

/**
 * After every vote: each direction is the sum, over networks, of min(that network's votes, 2). A script
 * making keys behind one connection moves a post by 2 at most (spec §2 Votes). Exported for the CLI.
 */
export const RECOUNT_SQL = `UPDATE posts SET
    up = (SELECT COALESCE(SUM(MIN(n, ${VOTES_PER_NETWORK})), 0) FROM (SELECT COUNT(*) AS n FROM votes WHERE post_id = ?1 AND value = 1 GROUP BY ip_hash)),
    down = (SELECT COALESCE(SUM(MIN(n, ${VOTES_PER_NETWORK})), 0) FROM (SELECT COUNT(*) AS n FROM votes WHERE post_id = ?1 AND value = -1 GROUP BY ip_hash))
    WHERE id = ?1 RETURNING up, down`;

async function vote(request, env, id, now) {
    if (!(await env.COMMUNITY_VOTE_RL.limit({ key: network(request) })).success) return rateLimited();
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const { body, tooBig } = await readJson(request);
    const value = body?.value;
    if (tooBig || ![-1, 0, 1].includes(value)) return json({ error: "bad_request" }, 400);
    const post = await env.COMMUNITY_DB.prepare(
        "SELECT poster, up, down, vouched, removed_at, expires_at, EXISTS (SELECT 1 FROM blocked WHERE poster = ?2) AS blocked FROM posts WHERE id = ?1",
    ).bind(id, who.id).first();
    if (isGone(post, now) || hiddenByVotes(post)) return gone();
    if (post.poster === who.id) return json({ error: "own_post" }, 403);
    if (post.blocked) return json({ error: "blocked" }, 403);
    const net = await networkHash(request, env);
    // SELECT … FROM posts, not VALUES: if the daily cleanup deleted the post since the check above, no vote row
    // is left behind, and the recount returns no row.
    const write = value === 0
        ? env.COMMUNITY_DB.prepare("DELETE FROM votes WHERE post_id = ?1 AND voter = ?2").bind(id, who.id)
        : env.COMMUNITY_DB.prepare(
            `INSERT INTO votes (post_id, voter, ip_hash, value, at) SELECT ?1, ?2, ?3, ?4, ?5 FROM posts WHERE id = ?1
             ON CONFLICT (post_id, voter) DO UPDATE SET value = excluded.value, ip_hash = excluded.ip_hash, at = excluded.at`,
        ).bind(id, who.id, net, value, now);
    const [, counted] = await env.COMMUNITY_DB.batch([write, env.COMMUNITY_DB.prepare(RECOUNT_SQL).bind(id)]);
    if (!counted.results[0]) return gone();
    const { up, down } = counted.results[0];
    return json({ up, down, myVote: value });
}

async function takeDown(request, env, id, now) {
    if (!(await env.COMMUNITY_WRITE_RL.limit({ key: network(request) })).success) return rateLimited();
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const post = await env.COMMUNITY_DB.prepare("SELECT poster, up, down, vouched, removed_at, expires_at FROM posts WHERE id = ?1").bind(id).first();
    if (isGone(post, now) || (post.poster !== who.id && hiddenByVotes(post))) return gone(); // hidden: only its poster sees it
    if (post.poster !== who.id) return json({ error: "not_yours" }, 403);
    // No `removed_at IS NULL` guard, on purpose: a take-down always wins a race with the owner's `remove`, so
    // `restore` can never republish it.
    await env.COMMUNITY_DB.prepare("UPDATE posts SET removed_at = ?2, removed_by = 'poster' WHERE id = ?1").bind(id, now).run();
    return new Response(null, { status: 204 });
}

/**
 * The daily cron (spec §2 Cleanup): expired posts, and posts removed more than a day ago that were also
 * created more than a day ago, with their votes. An owner's removal stays restorable for a day (a poster's
 * take-down stays too, but restore won't undo it), and every post of the last 24 hours stays for the daily limit.
 */
export async function cleanup(env, now) {
    // ponytail: one DELETE per table; D1 needs batching past ~hundreds of MB (~1,000 max-size posts in a day).
    // Upgrade: ORDER BY id LIMIT n in `doomed` (both statements then pick the same ids) and loop until a run deletes nothing.
    const doomed = "SELECT id FROM posts WHERE expires_at <= ?1 OR (removed_at IS NOT NULL AND removed_at < ?2 AND created_at < ?2)";
    await env.COMMUNITY_DB.batch([
        env.COMMUNITY_DB.prepare(`DELETE FROM votes WHERE post_id IN (${doomed})`).bind(now, now - DAY),
        env.COMMUNITY_DB.prepare(`DELETE FROM posts WHERE id IN (${doomed})`).bind(now, now - DAY),
    ]);
}
