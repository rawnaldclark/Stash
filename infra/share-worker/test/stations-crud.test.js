import { test } from "node:test";
import assert from "node:assert/strict";
import { env } from "./fake-kv.js";
import { sha256Hex } from "../src/store.js";
import { call, DAY, KEY_A, KEY_B } from "./community-helpers.js";

const sess = (over = {}) => env({ SESSIONS: "1", ...over });
const create = (e, body, key = KEY_A) => call(e, "POST", "/v1/community/stations", { key, body });
const mine = async (e, key = KEY_A) => (await (await call(e, "GET", "/v1/community/stations/mine", { key })).json()).station;
const get = (e, id, key) => call(e, "GET", `/v1/community/stations/${id}`, { key });
const patch = (e, id, body, key = KEY_A) => call(e, "PATCH", `/v1/community/stations/${id}`, { key, body });
const count = (e, table) => e.COMMUNITY_DB.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first("n");
const NY_WEEKDAYS = { tz: "America/New_York", windows: [1, 2, 3, 4, 5].map((day) => ({ day, start: "09:00", end: "17:00" })) };

async function station(e, body = { name: "Rawn's Basement", hostName: "Rawn" }, key = KEY_A) {
    const r = await create(e, body, key);
    assert.equal(r.status, 201);
    return (await r.json()).id;
}

test("with SESSIONS off every station route answers 503 sessions_off, and posts carry on", async () => {
    for (const e of [env(), env({ SESSIONS: "0" })]) {
        for (const [method, path] of [["POST", "/v1/community/stations"], ["GET", "/v1/community/stations/mine"],
            ["GET", "/v1/community/stations/ABCDEFGH"], ["PUT", "/v1/community/stations/ABCDEFGH/vote"],
            ["GET", "/v1/community/live"], ["GET", "/v1/community/upcoming"], ["GET", "/v1/community/stations/following"]]) {
            const r = await call(e, method, path, { key: KEY_A, body: method === "GET" ? undefined : { name: "x" } });
            assert.equal(r.status, 503, `${method} ${path}`);
            assert.deepEqual(await r.json(), { error: "sessions_off" });
        }
        assert.equal((await call(e, "GET", "/v1/community/feed")).status, 200);
    }
});

test("a phone makes its station, and sees it as its own", async () => {
    const e = sess();
    const before = Date.now();
    const id = await station(e, { name: "  Rawn's Basement ", hostName: "Rawn", vibe: "late-night r&b" });
    assert.match(id, /^[A-Za-z0-9]{8}$/);
    const s = await mine(e);
    assert.ok(s.deleteAt >= before + 7 * DAY && s.deleteAt <= Date.now() + 7 * DAY);
    delete s.deleteAt;
    assert.deepEqual(s, {
        id, name: "Rawn's Basement", hostName: "Rawn", vibe: "late-night r&b", up: 0, down: 0, followers: 0,
        myVote: 0, mine: true, heard: true, following: false,
        autoLive: "off", schedule: null, hidden: false, removed: false, lastLiveAt: null,
    });
    assert.equal(await mine(e, KEY_B), null);
});

test("one station per phone: a second create answers 409 with the first one's id", async () => {
    const e = sess();
    const id = await station(e);
    const r = await create(e, { name: "Another" });
    assert.equal(r.status, 409);
    assert.deepEqual(await r.json(), { error: "have_station", id });
    assert.equal(await count(e, "stations"), 1);
});

test("creating is refused without a key, with a bad body, and for a blocked phone", async () => {
    const e = sess();
    assert.equal((await call(e, "POST", "/v1/community/stations", { body: { name: "x" } })).status, 401, "no key");
    assert.equal((await create(e, { name: "x" }, "short")).status, 401);
    assert.equal((await create(e, { name: "x".repeat(41) })).status, 400);
    assert.equal((await create(e, { name: "ok", vibe: "x".repeat(61) })).status, 400);
    assert.equal((await call(e, "POST", "/v1/community/stations", { key: KEY_A, body: "nope" })).status, 400);
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_A)).run();
    const r = await create(e, { name: "x" });
    assert.equal(r.status, 403);
    assert.deepEqual(await r.json(), { error: "blocked" });
});

test("writes go through the Community write limit", async () => {
    const e = sess({ COMMUNITY_WRITE_RL: { limit: async () => ({ success: false }) } });
    assert.equal((await create(e, { name: "x" })).status, 429);
});

test("only its owner changes a station; a bad change is refused whole", async () => {
    const e = sess();
    const id = await station(e);
    const other = await patch(e, id, { name: "Mine now" }, KEY_B);
    assert.equal(other.status, 403);
    assert.deepEqual(await other.json(), { error: "not_yours" });
    assert.equal((await patch(e, id, { schedule: { tz: "Nowhere/Land", windows: [] } })).status, 400);
    assert.equal((await patch(e, id, {})).status, 400);
    assert.equal((await patch(e, "ZZZZZZZZ", { name: "x" })).status, 404);
    const r = await patch(e, id, { vibe: "90s house, requests open", autoLive: "schedule", schedule: NY_WEEKDAYS });
    assert.equal(r.status, 200);
    const s = (await r.json()).station;
    assert.equal(s.vibe, "90s house, requests open");
    assert.equal(s.autoLive, "schedule");
    assert.deepEqual(s.schedule, NY_WEEKDAYS);
    assert.equal(s.name, "Rawn's Basement", "fields not sent stay as they were");
    const cleared = (await (await patch(e, id, { vibe: null })).json()).station;
    assert.equal(cleared.vibe, null);
    assert.equal(cleared.autoLive, "schedule");
});

test("anyone reads a station; its hours and next window only when it runs on a schedule", async () => {
    const e = sess();
    const id = await station(e);
    const plain = await (await get(e, id)).json();
    assert.deepEqual(plain, { station: { id, name: "Rawn's Basement", hostName: "Rawn", vibe: null, up: 0, down: 0, followers: 0 } });
    await patch(e, id, { schedule: NY_WEEKDAYS });
    assert.equal((await (await get(e, id)).json()).station.schedule, undefined, "auto-live off: the hours aren't shown");
    await patch(e, id, { autoLive: "schedule" });
    const s = (await (await get(e, id, KEY_B)).json()).station;
    assert.deepEqual(s.schedule, NY_WEEKDAYS);
    assert.ok(s.next.start < s.next.end);
    assert.equal(s.mine, false);
});

test("a station on the air comes with its session; a stale directory row doesn't", async () => {
    const e = sess();
    const id = await station(e);
    const put = (seen) => e.COMMUNITY_DB.prepare(
        "INSERT OR REPLACE INTO live (station_id, room, track, listeners, paused, started_at, seen_at) VALUES (?1, 'ROOMCODE', ?2, 12, 0, 1, ?3)",
    ).bind(id, JSON.stringify({ t: "Nude", a: "Radiohead" }), seen).run();
    await put(Date.now());
    assert.deepEqual((await (await get(e, id)).json()).live,
        { room: "ROOMCODE", track: { t: "Nude", a: "Radiohead" }, listeners: 12, paused: false, full: false });
    await put(Date.now() - 4 * 60_000);
    assert.equal((await (await get(e, id)).json()).live, undefined);
});

test("a hidden station is gone to others and still there for its owner; vouched, it shows", async () => {
    const e = sess();
    const id = await station(e);
    await e.COMMUNITY_DB.prepare("UPDATE stations SET down = 3 WHERE id = ?1").bind(id).run();
    assert.equal((await get(e, id, KEY_B)).status, 404);
    assert.equal((await get(e, id)).status, 404, "no key: someone else");
    assert.equal((await get(e, id, KEY_A)).status, 200);
    assert.equal((await mine(e)).hidden, true);
    await e.COMMUNITY_DB.prepare("UPDATE stations SET vouched = 1 WHERE id = ?1").bind(id).run();
    assert.equal((await get(e, id, KEY_B)).status, 200);
});

test("a removed station is gone, and its owner is told so", async () => {
    const e = sess();
    const id = await station(e);
    await e.COMMUNITY_DB.prepare("UPDATE stations SET removed_at = 1, removed_by = 'owner' WHERE id = ?1").bind(id).run();
    assert.equal((await get(e, id, KEY_A)).status, 404);
    assert.equal((await mine(e)).removed, true);
    assert.equal((await patch(e, id, { name: "Back" })).status, 404);
});

test("only its owner deletes a station, and it goes with its votes, heard marks, follows and live row", async () => {
    const e = sess();
    const id = await station(e);
    const db = e.COMMUNITY_DB;
    await db.prepare("INSERT INTO station_votes (station_id, voter, ip_hash, value, at) VALUES (?1, 'v', 'n', 1, 1)").bind(id).run();
    await db.prepare("INSERT INTO station_heard (station_id, voter, at) VALUES (?1, 'v', 1)").bind(id).run();
    await db.prepare("INSERT INTO station_follows (station_id, follower, at) VALUES (?1, 'v', 1)").bind(id).run();
    await db.prepare("INSERT INTO live (station_id, room, started_at, seen_at) VALUES (?1, 'ROOMCODE', 1, 1)").bind(id).run();
    assert.equal((await call(e, "DELETE", `/v1/community/stations/${id}`, { key: KEY_B })).status, 403);
    assert.equal((await call(e, "DELETE", `/v1/community/stations/${id}`, { key: KEY_A })).status, 204);
    for (const t of ["stations", "station_votes", "station_heard", "station_follows", "live"]) assert.equal(await count(e, t), 0, t);
    assert.equal((await call(e, "DELETE", `/v1/community/stations/${id}`, { key: KEY_A })).status, 404);
    assert.equal(await mine(e), null);
    await station(e, { name: "A fresh start" });
});

test("the wrong method is 405, an unknown path 404", async () => {
    const e = sess();
    assert.equal((await call(e, "GET", "/v1/community/stations", { key: KEY_A })).status, 405);
    assert.equal((await call(e, "POST", "/v1/community/stations/mine", { key: KEY_A, body: {} })).status, 405);
    assert.equal((await call(e, "GET", "/v1/community/stations/short", { key: KEY_A })).status, 404);
});
