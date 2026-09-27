import { handle } from "../src/index.js";
import { sha256Hex } from "../src/store.js";

export const BASE = "https://share.test";
export const KEY_A = "a".repeat(43);
export const KEY_B = "b".repeat(43);
export const KEY_C = "c".repeat(43);
export const HOUR = 3_600_000;
export const DAY = 24 * HOUR;
export const song = { t: "garden", a: "Death Plus", yt: "9Vz-MkbnSg4" };
export const songPost = (over = {}) => ({ kind: "song", name: "Sam", title: "garden", body: { track: song }, ...over });

/** A request to the Worker. `key` goes in X-Stash-Community-Key; `from` is the caller's IP. */
export function call(e, method, path, { key, body, from = "203.0.113.9" } = {}) {
    const headers = { "CF-Connecting-IP": from };
    if (key !== undefined) headers["X-Stash-Community-Key"] = key;
    if (body !== undefined) headers["content-type"] = "application/json";
    return handle(new Request(`${BASE}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) }), e);
}

/** [e] with every COMMUNITY_DB query and batch taking ~5 ms, like a real D1 round trip, so races between requests show. */
export function laggy(e) {
    const lag = () => new Promise((r) => setTimeout(r, 5));
    const wrap = (st) => ({ ...st, bind: (...a) => wrap(st.bind(...a)),
        first: async (c) => { await lag(); return st.first(c); },
        run: async () => { await lag(); return st.run(); },
        all: async () => { await lag(); return st.all(); } });
    const d1 = e.COMMUNITY_DB;
    e.COMMUNITY_DB = { ...d1, prepare: (sql) => wrap(d1.prepare(sql)), batch: async (s) => { await lag(); return d1.batch(s); } };
    return e;
}

/**
 * Inserts a post row directly, for states the API can't reach quickly (old, expired, many).
 * A [removed] post is the owner's removal unless [removedBy] is "poster".
 */
export async function seed(e, { id, poster, key, ip = "n".repeat(64), created = Date.now(), removed = null, removedBy = "owner", expires, up = 0, down = 0, kind = "song", summary = { artist: "A" }, body = { track: { t: "T", a: "A" } } } = {}) {
    const owner = poster ?? (key ? await sha256Hex(key) : "p".repeat(64));
    await e.COMMUNITY_DB.prepare(
        "INSERT INTO posts (id, kind, title, poster_name, poster, ip_hash, summary, body, track_count, created_at, expires_at, up, down, removed_at, removed_by) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)",
    ).bind(id, kind, "T", "Seed", owner, ip, JSON.stringify(summary), JSON.stringify(body), 1, created, expires ?? created + 30 * DAY, up, down, removed,
        removed === null ? null : removedBy).run();
}
