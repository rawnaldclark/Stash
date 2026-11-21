import { env } from "./fake-kv.js";
import { sha256Hex } from "../src/store.js";
import { call } from "./community-helpers.js";

/** A test env with Sessions switched on. */
export const sess = (over = {}) => env({ SESSIONS: "1", ...over });

/**
 * Inserts a station row directly, for states the API can't reach quickly. [key] (or [owner], a hash) is its phone;
 * [schedule] an object; [removed] the owner's removal time.
 */
export async function seedStation(e, {
    id, key, owner, name = "Station", hostName = null, vibe = null, autoLive = "off", schedule = null,
    created = Date.now(), lastLive = null, up = 0, down = 0, vouched = 0, removed = null,
} = {}) {
    const o = owner ?? (key ? await sha256Hex(key) : `${id}`.padEnd(64, "o"));
    await e.COMMUNITY_DB.prepare(
        `INSERT INTO stations (id, owner, name, host_name, vibe, auto_live, schedule, created_at, last_live_at, up, down, vouched, removed_at, removed_by)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)`,
    ).bind(id, o, name, hostName, vibe, autoLive, schedule ? JSON.stringify(schedule) : null, created, lastLive, up, down, vouched,
        removed, removed === null ? null : "owner").run();
    return id;
}

/** A directory row, as a room writes it: [seen] ms ago (0 = now). */
export function seedLive(e, { station, room = "ROOMCODE", track = { t: "Nude", a: "Radiohead" }, listeners = 0, paused = 0, crowd = [], started = 1, seen = 0 }) {
    return e.COMMUNITY_DB.prepare(
        "INSERT OR REPLACE INTO live (station_id, room, track, listeners, paused, crowd, started_at, seen_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
    ).bind(station, room, JSON.stringify(track), listeners, paused, JSON.stringify(crowd), started, Date.now() - seen).run();
}

export const stationVote = (e, id, value, key, from) => call(e, "PUT", `/v1/community/stations/${id}/vote`, { key, body: { value }, from });
export const stationHeard = (e, id, key, from) => call(e, "PUT", `/v1/community/stations/${id}/heard`, { key, from });
/** A phone that listened 2 minutes, then voted. */
export async function listenAndVote(e, id, value, key, from) {
    await stationHeard(e, id, key, from);
    return stationVote(e, id, value, key, from);
}
export const keyN = (n) => `${n}`.padStart(43, "k");
