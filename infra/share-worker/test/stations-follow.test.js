import { test } from "node:test";
import assert from "node:assert/strict";
import { sha256Hex } from "../src/store.js";
import { call, KEY_A, KEY_B } from "./community-helpers.js";
import { seedLive, seedStation, sess } from "./stations-helpers.js";
import { MAX_FOLLOWS } from "../src/stations-api.js";

const follow = (e, id, on, key = KEY_B) => call(e, "PUT", `/v1/community/stations/${id}/follow`, { key, body: { on } });
const following = async (e, key = KEY_B) => (await (await call(e, "GET", "/v1/community/stations/following", { key })).json()).stations;
const hhmm = (t) => new Date(t).toISOString().slice(11, 16);
/** Every day, a half hour starting [inMs] from now (UTC). */
const daily = (inMs) => ({ tz: "UTC", windows: [1, 2, 3, 4, 5, 6, 7].map((day) => ({ day, start: hhmm(Date.now() + inMs), end: hhmm(Date.now() + inMs + 1_800_000) })) });

test("following a station and unfollowing it; the station counts its followers", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    assert.deepEqual(await (await follow(e, "STATION1", true)).json(), { following: true, followers: 1 });
    assert.deepEqual(await (await follow(e, "STATION1", true)).json(), { following: true, followers: 1 }, "again changes nothing");
    assert.deepEqual(await (await follow(e, "STATION1", true, "c".repeat(43))).json(), { following: true, followers: 2 });
    const s = (await (await call(e, "GET", "/v1/community/stations/STATION1", { key: KEY_B })).json()).station;
    assert.equal(s.following, true);
    assert.equal(s.followers, 2);
    assert.deepEqual(await (await follow(e, "STATION1", false)).json(), { following: false, followers: 1 });
    assert.equal((await (await call(e, "GET", "/v1/community/stations/mine", { key: KEY_A })).json()).station.followers, 1);
});

test("no following your own station, a gone or hidden one, without a key, or with a bad body", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await seedStation(e, { id: "HIDDEN01", down: 3 });
    assert.deepEqual(await (await follow(e, "STATION1", true, KEY_A)).json(), { error: "own_station" });
    for (const id of ["HIDDEN01", "NOSUCHID"]) assert.equal((await follow(e, id, true)).status, 404, id);
    assert.equal((await call(e, "PUT", "/v1/community/stations/STATION1/follow", { body: { on: true } })).status, 401, "no key");
    assert.equal((await follow(e, "STATION1", "yes")).status, 400);
    assert.equal((await call(e, "GET", "/v1/community/stations/following")).status, 401);
});

test(`a phone follows at most ${MAX_FOLLOWS} stations`, async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    const me = await sha256Hex(KEY_B);
    for (let i = 0; i < MAX_FOLLOWS; i++) {
        await e.COMMUNITY_DB.prepare("INSERT INTO station_follows (station_id, follower, at) VALUES (?1, ?2, 1)").bind(`S${i}`.padEnd(8, "x"), me).run();
    }
    const r = await follow(e, "STATION1", true);
    assert.equal(r.status, 409);
    assert.deepEqual(await r.json(), { error: "too_many" });
    // Stopping always works, even for a station that isn't there any more, and frees a place.
    assert.deepEqual(await (await follow(e, "S0xxxxxx", false)).json(), { following: false, followers: 0 });
    assert.equal((await follow(e, "STATION1", true)).status, 200);
});

test("a station hidden since you followed it: you can still stop following it", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    assert.equal((await follow(e, "STATION1", true)).status, 200);
    await e.COMMUNITY_DB.prepare("UPDATE stations SET down = 3 WHERE id = 'STATION1'").run();
    assert.equal((await follow(e, "STATION1", true)).status, 404, "no new follows of a hidden station");
    assert.deepEqual(await (await follow(e, "STATION1", false)).json(), { following: false, followers: 0 });
    assert.equal(await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM station_follows").first("n"), 0);
});

test("follows are counted with the votes' limit", async () => {
    const e = sess({ COMMUNITY_VOTE_RL: { limit: async () => ({ success: false }) } });
    await seedStation(e, { id: "STATION1", key: KEY_A });
    assert.equal((await follow(e, "STATION1", true)).status, 429);
});

test("the stations you follow: live ones first by listeners, then the soonest on the air, then the rest", async () => {
    const e = sess();
    await seedStation(e, { id: "QUIET001", name: "Quiet" });
    await seedStation(e, { id: "LATER001", name: "Later", autoLive: "schedule", schedule: daily(3 * 3_600_000) });
    await seedStation(e, { id: "SOON0001", name: "Soon", autoLive: "schedule", schedule: daily(3_600_000) });
    await seedStation(e, { id: "SMALL001", name: "Small" });
    await seedStation(e, { id: "BIG00001", name: "Big" });
    await seedLive(e, { station: "SMALL001", room: "ROOMAAAA", listeners: 5 });
    await seedLive(e, { station: "BIG00001", room: "ROOMBBBB", listeners: 20, crowd: [{ i: "M", id: "m1" }] });
    for (const id of ["QUIET001", "LATER001", "SOON0001", "SMALL001", "BIG00001"]) await follow(e, id, true);
    const list = await following(e);
    assert.deepEqual(list.map((s) => s.id), ["BIG00001", "SMALL001", "SOON0001", "LATER001", "QUIET001"]);
    assert.deepEqual(list[0].live, { room: "ROOMBBBB", track: { t: "Nude", a: "Radiohead" }, listeners: 20, paused: false, full: false, crowd: [{ i: "M", id: "m1" }] });
    assert.ok(list[2].next.start > Date.now() && list[2].next.start < list[3].next.start);
    assert.equal(list[4].live, undefined);
    assert.equal(list[4].next, undefined);
    assert.equal(list[0].following, true);
});

test("a followed station that's hidden, removed, stale or blocked shows as such: off the list, or not live", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1", key: KEY_A });
    await seedStation(e, { id: "STATION2" });
    await follow(e, "STATION1", true);
    await follow(e, "STATION2", true);
    await seedLive(e, { station: "STATION2", seen: 4 * 60_000 });
    assert.equal((await following(e)).find((s) => s.id === "STATION2").live, undefined, "a stale row isn't live");
    await e.COMMUNITY_DB.prepare("UPDATE stations SET down = 3 WHERE id = 'STATION2'").run();
    assert.deepEqual((await following(e)).map((s) => s.id), ["STATION1"]);
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_A)).run();
    assert.deepEqual(await following(e), []);
});
