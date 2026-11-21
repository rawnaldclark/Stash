/**
 * Sessions' station routes (spec docs/superpowers/specs/2026-11-20-sessions-design.md §3; plan
 * docs/superpowers/plans/2026-11-20-sessions-step1-server.md, 1a). Under /v1/community, keyed like Community: a phone is
 * sha256(its key). Off until SESSIONS = "1" (wrangler.toml [vars]): until then every route here answers 503 sessions_off.
 * D1 tables: stations, station_votes, station_heard, station_follows, live (migrations/0007_sessions.sql).
 */
import { json } from "./http.js";
import { newId } from "./store.js";
import {
    NO_STORE, VOTES_PER_NETWORK, VOTE_ROWS_PER_NETWORK, gone, hiddenByVotes, identity, network, networkHash, notAllowed, rateLimited,
    readJson,
} from "./community-shared.js";
import { cleanStationFields, nextWindow, summaryOfStation } from "./stations.js";

const DAY = 86_400_000;
/** A station that hasn't gone live for this long is deleted with its votes (owner, 2026-11-20). */
export const STATION_IDLE_MS = 7 * DAY;
/** A directory row its room hasn't written for this long belongs to a room that died without saying so. */
export const LIVE_STALE_MS = 3 * 60_000;
/** A session is full at this many listeners (the room's own cap, src/room.js, says the same). */
export const MAX_LISTENERS = 50;
/** Stations one phone may follow. */
export const MAX_FOLLOWS = 200;

const STATION_API = /^\/v1\/community\/stations\/([A-Za-z0-9]{8})(\/vote|\/heard|\/follow)?$/;
const PATHS = new Set(["/v1/community/stations", "/v1/community/live", "/v1/community/upcoming"]);

/** Whether [path] is one of the routes here (communityRoute hands them over). */
export const isStationsPath = (path) => PATHS.has(path) || path.startsWith("/v1/community/stations/");
export const sessionsOn = (env) => env.SESSIONS === "1";

/**
 * The columns every station read takes, with its follower count. `owner` is read to answer `mine` and never sent.
 * Binds nothing: callers add their own joins and WHERE.
 */
const COLUMNS = `s.id, s.owner, s.name, s.host_name, s.vibe, s.auto_live, s.schedule, s.created_at, s.last_live_at,
    s.up, s.down, s.vouched, s.removed_at, (SELECT COUNT(*) FROM station_follows c WHERE c.station_id = s.id) AS followers`;
/** The caller's own vote, heard mark and follow on station `s`; ?1 is the caller's id ("" without a key). */
const MY_JOINS = `LEFT JOIN station_votes v ON v.station_id = s.id AND v.voter = ?1
    LEFT JOIN station_heard h ON h.station_id = s.id AND h.voter = ?1
    LEFT JOIN station_follows f ON f.station_id = s.id AND f.follower = ?1`;
const MY_COLUMNS = "v.value AS my_vote, h.voter IS NOT NULL AS heard, f.follower IS NOT NULL AS following";
/** Station `s` may be shown to others: not removed, not hidden by votes, its phone not blocked. */
export const SHOWN_SQL = `s.removed_at IS NULL AND ((s.up - s.down) > -3 OR s.vouched = 1)
    AND NOT EXISTS (SELECT 1 FROM blocked b WHERE b.poster = s.owner)`;

const scheduleOf = (row) => (row.schedule ? JSON.parse(row.schedule) : null);
/**
 * A row's session on the air (from a `LEFT JOIN live l` that already dropped stale rows), or null. [withCrowd]: the
 * lists that draw faces get the crowd's initials too.
 */
function liveOf(row, withCrowd = false) {
    if (row.room === null || row.room === undefined) return null;
    return {
        room: row.room, track: row.track ? JSON.parse(row.track) : null, listeners: row.listeners, paused: Boolean(row.paused),
        full: row.listeners >= MAX_LISTENERS, ...(withCrowd ? { crowd: JSON.parse(row.crowd ?? "[]") } : {}),
    };
}

/** Routes every station path. [method] has HEAD folded into GET. */
export async function stationsRoute(request, env, path, method, now) {
    if (!sessionsOn(env)) return json({ error: "sessions_off" }, 503);
    if (path === "/v1/community/stations") return method === "POST" ? createStation(request, env, now) : notAllowed();
    if (path === "/v1/community/live") return method === "GET" ? liveNow(request, env, now) : notAllowed();
    if (path === "/v1/community/upcoming") return method === "GET" ? upcoming(request, env, now) : notAllowed();
    if (path === "/v1/community/stations/mine") return method === "GET" ? mine(request, env, now) : notAllowed();
    if (path === "/v1/community/stations/following") return method === "GET" ? followed(request, env, now) : notAllowed();
    const m = STATION_API.exec(path);
    if (m) {
        const id = m[1];
        if (!m[2]) {
            if (method === "GET") return getStation(request, env, id, now);
            if (method === "PATCH") return changeStation(request, env, id);
            if (method === "DELETE") return deleteStation(request, env, id);
            return notAllowed();
        }
        if (m[2] === "/vote") return method === "PUT" ? voteStation(request, env, id, now) : notAllowed();
        if (m[2] === "/heard") return method === "PUT" ? heardStation(request, env, id, now) : notAllowed();
        if (m[2] === "/follow") return method === "PUT" ? followStation(request, env, id, now) : notAllowed();
    }
    return json({ error: "not_found" }, 404);
}

/** A write's checks: the write limit, then a valid key. Answers the refusal, or {who}. */
async function writer(request, env) {
    if (!(await env.COMMUNITY_WRITE_RL.limit({ key: network(request, env) })).success) return { refusal: rateLimited() };
    const who = await identity(request);
    if (!who.id) return { refusal: json({ error: "bad_key" }, 401) };
    return { who };
}

async function createStation(request, env, now) {
    const { who, refusal } = await writer(request, env);
    if (refusal) return refusal;
    const { body, tooBig } = await readJson(request);
    if (tooBig) return json({ error: "too_large" }, 413);
    const f = cleanStationFields(body);
    if (!f) return json({ error: "bad_request" }, 400);
    const db = env.COMMUNITY_DB;
    if (await db.prepare("SELECT 1 AS b FROM blocked WHERE poster = ?1").bind(who.id).first()) return json({ error: "blocked" }, 403);
    for (let i = 0; i < 3; i++) {
        const id = newId();
        // No conflict target: a taken id and the phone's existing station (owner UNIQUE) both insert nothing.
        const made = await db.prepare(
            `INSERT INTO stations (id, owner, name, host_name, vibe, auto_live, schedule, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8) ON CONFLICT DO NOTHING RETURNING id`,
        ).bind(id, who.id, f.name, f.hostName ?? null, f.vibe ?? null, f.autoLive ?? "off",
            f.schedule ? JSON.stringify(f.schedule) : null, now).first();
        if (made) return json({ id: made.id }, 201);
        const have = await db.prepare("SELECT id FROM stations WHERE owner = ?1").bind(who.id).first();
        if (have) return json({ error: "have_station", id: have.id }, 409);
    }
    return json({ error: "unavailable" }, 503, { "Retry-After": "2" });
}

/** The owner's view of their station: everything others see, plus its settings and its standing. */
function ownView(row, me) {
    return {
        ...summaryOfStation(row, me),
        autoLive: row.auto_live, schedule: scheduleOf(row),
        hidden: hiddenByVotes(row), removed: row.removed_at !== null, lastLiveAt: row.last_live_at ?? null,
        deleteAt: (row.last_live_at ?? row.created_at) + STATION_IDLE_MS,
    };
}

const readOwn = (env, who) => env.COMMUNITY_DB.prepare(
    `SELECT ${COLUMNS}, ${MY_COLUMNS} FROM stations s ${MY_JOINS} WHERE s.owner = ?1`,
).bind(who).first();

async function mine(request, env) {
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const row = await readOwn(env, who.id);
    return json({ station: row ? ownView(row, who.id) : null }, 200, NO_STORE);
}

async function getStation(request, env, id, now) {
    const who = await identity(request);
    if (who.bad) return json({ error: "bad_key" }, 401);
    const me = who.id ?? null;
    const row = await env.COMMUNITY_DB.prepare(
        `SELECT ${COLUMNS}, ${MY_COLUMNS}, EXISTS (SELECT 1 FROM blocked b WHERE b.poster = s.owner) AS blocked,
            l.room, l.track, l.listeners, l.paused
         FROM stations s ${MY_JOINS}
         LEFT JOIN live l ON l.station_id = s.id AND l.seen_at > ?3
         WHERE s.id = ?2`,
    ).bind(me ?? "", id, now - LIVE_STALE_MS).first();
    if (!row || row.removed_at !== null) return gone();
    const yours = me !== null && row.owner === me;
    if (!yours && (hiddenByVotes(row) || row.blocked)) return gone();
    const schedule = row.auto_live === "schedule" ? scheduleOf(row) : null;
    const station = { ...summaryOfStation(row, me), ...(schedule ? { schedule, next: nextWindow(schedule, now) } : {}) };
    const live = liveOf(row);
    return json({ station, ...(live ? { live } : {}) }, 200, NO_STORE);
}

/** Field name → column. Only these names ever reach the SQL; the values are bound. */
const FIELD_COLUMNS = { name: "name", hostName: "host_name", vibe: "vibe", autoLive: "auto_live", schedule: "schedule" };

async function changeStation(request, env, id) {
    const { who, refusal } = await writer(request, env);
    if (refusal) return refusal;
    const { body, tooBig } = await readJson(request);
    if (tooBig) return json({ error: "too_large" }, 413);
    const f = cleanStationFields(body, { partial: true });
    if (!f) return json({ error: "bad_request" }, 400);
    const db = env.COMMUNITY_DB;
    const row = await db.prepare("SELECT owner, removed_at FROM stations WHERE id = ?1").bind(id).first();
    if (!row || row.removed_at !== null) return gone();
    if (row.owner !== who.id) return json({ error: "not_yours" }, 403);
    const keys = Object.keys(f);
    const values = keys.map((k) => (k === "schedule" && f.schedule ? JSON.stringify(f.schedule) : f[k]));
    await db.prepare(
        `UPDATE stations SET ${keys.map((k, i) => `${FIELD_COLUMNS[k]} = ?${i + 2}`).join(", ")} WHERE id = ?1 AND removed_at IS NULL`,
    ).bind(id, ...values).run();
    const changed = await readOwn(env, who.id);
    return changed ? json({ station: ownView(changed, who.id) }, 200, NO_STORE) : gone();
}

async function deleteStation(request, env, id) {
    const { who, refusal } = await writer(request, env);
    if (refusal) return refusal;
    const db = env.COMMUNITY_DB;
    const row = await db.prepare("SELECT owner FROM stations WHERE id = ?1").bind(id).first();
    if (!row) return gone();
    if (row.owner !== who.id) return json({ error: "not_yours" }, 403);
    await db.batch(["station_votes", "station_heard", "station_follows", "live"]
        .map((t) => db.prepare(`DELETE FROM ${t} WHERE station_id = ?1`).bind(id))
        .concat(db.prepare("DELETE FROM stations WHERE id = ?1").bind(id)));
    return new Response(null, { status: 204 });
}

/** Station `s`'s counted votes, the per-network cap applied: the post recount (src/community.js RECOUNT_SQL) on stations. */
export const STATION_RECOUNT_SQL = `UPDATE stations SET
    up = (SELECT COALESCE(SUM(MIN(n, ${VOTES_PER_NETWORK})), 0) FROM (SELECT COUNT(*) AS n FROM station_votes WHERE station_id = ?1 AND value = 1 GROUP BY ip_hash)),
    down = (SELECT COALESCE(SUM(MIN(n, ${VOTES_PER_NETWORK})), 0) FROM (SELECT COUNT(*) AS n FROM station_votes WHERE station_id = ?1 AND value = -1 GROUP BY ip_hash))
    WHERE id = ?1 RETURNING up, down`;

/**
 * The station a vote or heard mark is about, as the caller sees it: null when it's missing, removed, hidden by votes, or
 * its phone is blocked (all "gone" to a voter). ?2 is the caller.
 */
async function votable(env, id, me) {
    const st = await env.COMMUNITY_DB.prepare(
        `SELECT s.owner, s.up, s.down, s.vouched, s.removed_at,
            EXISTS (SELECT 1 FROM blocked b WHERE b.poster = s.owner) AS owner_blocked,
            EXISTS (SELECT 1 FROM blocked b WHERE b.poster = ?2) AS blocked,
            EXISTS (SELECT 1 FROM station_heard h WHERE h.station_id = ?1 AND h.voter = ?2) AS heard
         FROM stations s WHERE s.id = ?1`,
    ).bind(id, me).first();
    return !st || st.removed_at !== null || hiddenByVotes(st) || st.owner_blocked ? null : st;
}

/**
 * PUT stations/{id}/vote {value: -1 | 0 | 1}: the post vote's rules on a station (one per phone, each network counted at
 * most twice each way, VOTE_ROWS_PER_NETWORK rows kept per network), except that a +1 or -1 always needs the caller's
 * heard mark: no app ever voted on a station without sending one, so there is no HEARD_REQUIRED switch to wait for.
 */
async function voteStation(request, env, id, now) {
    if (!(await env.COMMUNITY_VOTE_RL.limit({ key: network(request, env) })).success) return rateLimited();
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const { body, tooBig } = await readJson(request);
    const value = body?.value;
    if (tooBig || ![-1, 0, 1].includes(value)) return json({ error: "bad_request" }, 400);
    const st = await votable(env, id, who.id);
    if (!st) return gone();
    if (st.owner === who.id) return json({ error: "own_station" }, 403);
    if (st.blocked) return json({ error: "blocked" }, 403);
    if (value !== 0 && !st.heard) return json({ error: "listen_first" }, 403);
    const net = await networkHash(request, env);
    const db = env.COMMUNITY_DB;
    // SELECT … FROM stations, as the post vote does: a station deleted since the check above keeps no vote row.
    const write = value === 0
        ? db.prepare("DELETE FROM station_votes WHERE station_id = ?1 AND voter = ?2").bind(id, who.id)
        : db.prepare(
            `INSERT INTO station_votes (station_id, voter, ip_hash, value, at) SELECT ?1, ?2, ?3, ?4, ?5 FROM stations WHERE id = ?1
             AND ((SELECT COUNT(*) FROM station_votes WHERE station_id = ?1 AND ip_hash = ?3) < ${VOTE_ROWS_PER_NETWORK}
               OR EXISTS (SELECT 1 FROM station_votes WHERE station_id = ?1 AND voter = ?2))
             ON CONFLICT (station_id, voter) DO UPDATE SET value = excluded.value, ip_hash = excluded.ip_hash, at = excluded.at`,
        ).bind(id, who.id, net, value, now);
    const [, counted] = await db.batch([write, db.prepare(STATION_RECOUNT_SQL).bind(id)]);
    if (!counted.results[0]) return gone();
    const { up, down } = counted.results[0];
    return json({ up, down, myVote: value });
}

/**
 * PUT stations/{id}/heard: the caller's app has played 2 minutes of this station's sessions, in total. Stored once
 * (station, the key's hash, when); again changes nothing and still answers 204. Counted with the votes.
 */
async function heardStation(request, env, id, now) {
    if (!(await env.COMMUNITY_VOTE_RL.limit({ key: network(request, env) })).success) return rateLimited();
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const st = await votable(env, id, who.id);
    if (!st) return gone();
    if (st.owner === who.id) return json({ error: "own_station" }, 403);
    await env.COMMUNITY_DB.prepare(
        "INSERT INTO station_heard (station_id, voter, at) SELECT ?1, ?2, ?3 FROM stations WHERE id = ?1 ON CONFLICT (station_id, voter) DO NOTHING",
    ).bind(id, who.id, now).run();
    return new Response(null, { status: 204 });
}

/**
 * PUT stations/{id}/follow {on}: follow a station or stop. Not your own; not a gone or hidden one; at most MAX_FOLLOWS a
 * phone. Counted with the votes. Answers the caller's state and the station's follower count.
 */
async function followStation(request, env, id, now) {
    if (!(await env.COMMUNITY_VOTE_RL.limit({ key: network(request, env) })).success) return rateLimited();
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const { body, tooBig } = await readJson(request);
    if (tooBig || typeof body?.on !== "boolean") return json({ error: "bad_request" }, 400);
    const st = await votable(env, id, who.id);
    if (!st) return gone();
    if (st.owner === who.id) return json({ error: "own_station" }, 403);
    const db = env.COMMUNITY_DB;
    if (body.on) {
        const c = await db.prepare(
            `SELECT COUNT(*) AS n, EXISTS (SELECT 1 FROM station_follows WHERE station_id = ?2 AND follower = ?1) AS already
             FROM station_follows WHERE follower = ?1`,
        ).bind(who.id, id).first();
        if (!c.already && c.n >= MAX_FOLLOWS) return json({ error: "too_many" }, 409);
    }
    const write = body.on
        ? db.prepare("INSERT INTO station_follows (station_id, follower, at) SELECT ?1, ?2, ?3 FROM stations WHERE id = ?1 ON CONFLICT DO NOTHING").bind(id, who.id, now)
        : db.prepare("DELETE FROM station_follows WHERE station_id = ?1 AND follower = ?2").bind(id, who.id);
    const [, counted] = await db.batch([write, db.prepare("SELECT COUNT(*) AS n FROM station_follows WHERE station_id = ?1").bind(id)]);
    return json({ following: body.on, followers: counted.results[0].n });
}

/**
 * GET stations/following: the stations the caller follows, live ones first (most listeners first), then by when their
 * schedule next has them on the air, then the rest by name. Hidden, removed and blocked ones are left out.
 */
async function followed(request, env, now) {
    const who = await identity(request);
    if (!who.id) return json({ error: "bad_key" }, 401);
    const { results } = await env.COMMUNITY_DB.prepare(
        `SELECT ${COLUMNS}, ${MY_COLUMNS}, l.room, l.track, l.listeners, l.paused, l.crowd
         FROM station_follows ff JOIN stations s ON s.id = ff.station_id
         ${MY_JOINS}
         LEFT JOIN live l ON l.station_id = s.id AND l.seen_at > ?2
         WHERE ff.follower = ?1 AND ${SHOWN_SQL}`,
    ).bind(who.id, now - LIVE_STALE_MS).all();
    const stations = results.map((row) => {
        const live = liveOf(row, true);
        const schedule = row.auto_live === "schedule" ? scheduleOf(row) : null;
        const next = schedule ? nextWindow(schedule, now) : null;
        return { ...summaryOfStation(row, who.id), ...(live ? { live } : {}), ...(next ? { next } : {}) };
    });
    const rank = (s) => (s.live ? 0 : s.next ? 1 : 2);
    stations.sort((a, b) => rank(a) - rank(b)
        || (a.live && b.live ? b.live.listeners - a.live.listeners : 0)
        || (a.next && b.next ? a.next.start - b.next.start : 0)
        || a.name.localeCompare(b.name));
    return json({ stations }, 200, NO_STORE);
}

/** `?limit=` for the lists: [fallback] when absent or not a number, held to 1..50. */
function limitOf(request, fallback) {
    const asked = Number.parseInt(new URL(request.url).searchParams.get("limit"), 10);
    return Math.min(Math.max(Number.isNaN(asked) ? fallback : asked, 1), 50);
}

/**
 * GET live?limit=N: the sessions on the air, best first. Each listener in a session right now counts as one vote, so a
 * new station with people in it surfaces and a well-voted one leads (spec §3); the `1.0` is the constant to tune. Rows
 * whose room hasn't written for LIVE_STALE_MS are a room that died without saying so, and are left out.
 */
async function liveNow(request, env, now) {
    const who = await identity(request);
    if (who.bad) return json({ error: "bad_key" }, 401);
    const me = who.id ?? null;
    const { results } = await env.COMMUNITY_DB.prepare(
        `SELECT ${COLUMNS}, ${MY_COLUMNS}, l.room, l.track, l.listeners, l.paused, l.crowd, l.started_at
         FROM live l JOIN stations s ON s.id = l.station_id
         ${MY_JOINS}
         WHERE l.seen_at > ?2 AND ${SHOWN_SQL}
         ORDER BY (s.up - s.down) + l.listeners * 1.0 DESC, l.started_at ASC
         LIMIT ?3`,
    ).bind(me ?? "", now - LIVE_STALE_MS, limitOf(request, 10)).all();
    const sessions = results.map((row) => {
        const { room, track, listeners, paused, full, crowd } = liveOf(row, true);
        return { station: summaryOfStation(row, me), room, track, listeners, paused, full, crowd, startedAt: row.started_at };
    });
    return json({ sessions }, 200, NO_STORE);
}

/**
 * Upcoming's choice, pure: of scheduled station [rows], those whose next window starts after [now] and within a day,
 * soonest first, cut to [limit]. A window already in progress isn't upcoming: its station is live, or its host isn't
 * playing.
 */
export function pickUpcoming(rows, now, limit, me) {
    return rows
        .map((row) => ({ row, next: nextWindow(scheduleOf(row), now) }))
        .filter(({ next }) => next && next.start > now && next.start <= now + DAY)
        .sort((a, b) => a.next.start - b.next.start)
        .slice(0, limit)
        .map(({ row, next }) => ({ ...summaryOfStation(row, me), next }));
}

/**
 * GET upcoming?limit=N: stations on a schedule that aren't on the air now, next on within 24 hours, soonest first.
 * Every scheduled station is read and its window worked out here (the time zones make it awkward in SQL); fine at this
 * size. Not cached: on the stashfm.app zone the Cache API ignores the query string, so ?limit= answers would mix.
 */
async function upcoming(request, env, now) {
    const who = await identity(request);
    if (who.bad) return json({ error: "bad_key" }, 401);
    const me = who.id ?? null;
    const { results } = await env.COMMUNITY_DB.prepare(
        `SELECT ${COLUMNS}, ${MY_COLUMNS} FROM stations s
         ${MY_JOINS}
         WHERE s.auto_live = 'schedule' AND s.schedule IS NOT NULL AND ${SHOWN_SQL}
           AND NOT EXISTS (SELECT 1 FROM live l WHERE l.station_id = s.id AND l.seen_at > ?2)`,
    ).bind(me ?? "", now - LIVE_STALE_MS).all();
    return json({ stations: pickUpcoming(results, now, limitOf(request, 10), me) }, 200, NO_STORE);
}
