import { test } from "node:test";
import assert from "node:assert/strict";
import { sha256Hex } from "../src/store.js";
import { call, KEY_A, KEY_B } from "./community-helpers.js";
import { listenAndVote, seedLive, seedStation, sess } from "./stations-helpers.js";
import { pickUpcoming } from "../src/stations-api.js";

const live = async (e, query = "", key) => (await call(e, "GET", `/v1/community/live${query}`, { key })).json();
const upcoming = async (e, query = "", key) => (await call(e, "GET", `/v1/community/upcoming${query}`, { key })).json();
const HOUR = 3_600_000;
const hhmm = (t) => new Date(t).toISOString().slice(11, 16);
const isoDay = (t) => ((new Date(t).getUTCDay() + 6) % 7) + 1;
/** One window a week, starting [inMs] from now (UTC), half an hour long. */
const once = (inMs) => {
    const at = Date.now() + inMs;
    return { tz: "UTC", windows: [{ day: isoDay(at), start: hhmm(at), end: hhmm(at + 1_800_000) }] };
};

test("Live now ranks by net votes plus listeners; a tie goes to the session on the air longer", async () => {
    const e = sess();
    await seedStation(e, { id: "MAYA0001", name: "Maya's Desk Radio", up: 48 });
    await seedStation(e, { id: "DILLA001", name: "Late Night Dilla", up: 31 });
    await seedStation(e, { id: "SOUL0001", name: "Sunday Soul Kitchen", up: 19 });
    await seedStation(e, { id: "TWIN0001", name: "Twin", up: 19 });
    await seedLive(e, { station: "MAYA0001", room: "ROOMMAYA", listeners: 12 });
    await seedLive(e, { station: "DILLA001", room: "ROOMDILL", listeners: 27 });
    await seedLive(e, { station: "SOUL0001", room: "ROOMSOUL", listeners: 6, started: 50 });
    await seedLive(e, { station: "TWIN0001", room: "ROOMTWIN", listeners: 6, started: 10 });
    const { sessions } = await live(e);
    assert.deepEqual(sessions.map((s) => s.station.id), ["MAYA0001", "DILLA001", "TWIN0001", "SOUL0001"]);
});

test("a session as Live now shows it: the station, the song, the crowd's initials, full at 50", async () => {
    const e = sess();
    await seedStation(e, { id: "MAYA0001", name: "Maya's Desk Radio", hostName: "Maya", vibe: "late-night r&b" });
    await seedLive(e, { station: "MAYA0001", room: "ROOMMAYA", listeners: 50, paused: 1, crowd: [{ i: "M", id: "abc" }, { i: null, id: "def" }], started: 123 });
    const [s] = (await live(e)).sessions;
    assert.deepEqual(s, {
        station: { id: "MAYA0001", name: "Maya's Desk Radio", hostName: "Maya", vibe: "late-night r&b", up: 0, down: 0, followers: 0 },
        room: "ROOMMAYA", track: { t: "Nude", a: "Radiohead" }, listeners: 50, paused: true, full: true,
        crowd: [{ i: "M", id: "abc" }, { i: null, id: "def" }], startedAt: 123,
    });
});

test("stale, hidden, removed and blocked stations are not live", async () => {
    const e = sess();
    await seedStation(e, { id: "STALE001" });
    await seedStation(e, { id: "HIDDEN01", down: 3 });
    await seedStation(e, { id: "VOUCHED1", down: 3, vouched: 1 });
    await seedStation(e, { id: "REMOVED1", removed: 1 });
    await seedStation(e, { id: "BLOCKED1", key: KEY_A });
    await e.COMMUNITY_DB.prepare("INSERT INTO blocked (poster, at) VALUES (?1, 1)").bind(await sha256Hex(KEY_A)).run();
    await seedLive(e, { station: "STALE001", room: "ROOMSTAL", seen: 3 * 60_000 + 1000 });
    for (const [station, room] of [["HIDDEN01", "ROOMHIDE"], ["VOUCHED1", "ROOMVOUC"], ["REMOVED1", "ROOMREMO"], ["BLOCKED1", "ROOMBLOC"]]) {
        await seedLive(e, { station, room });
    }
    assert.deepEqual((await live(e)).sessions.map((s) => s.station.id), ["VOUCHED1"]);
});

test("with a key, each session carries your vote, heard mark and follow; without one, none of that", async () => {
    const e = sess();
    await seedStation(e, { id: "MAYA0001", key: KEY_A });
    await seedLive(e, { station: "MAYA0001" });
    await listenAndVote(e, "MAYA0001", 1, KEY_B);
    await call(e, "PUT", "/v1/community/stations/MAYA0001/follow", { key: KEY_B, body: { on: true } });
    const mine = (await live(e, "", KEY_B)).sessions[0].station;
    assert.deepEqual([mine.myVote, mine.heard, mine.following, mine.mine, mine.up, mine.followers], [1, true, true, false, 1, 1]);
    assert.equal((await live(e, "", KEY_A)).sessions[0].station.mine, true);
    const anon = (await live(e)).sessions[0].station;
    assert.equal(anon.myVote, undefined);
    assert.equal(anon.following, undefined);
    assert.equal((await call(e, "GET", "/v1/community/live", { key: "bad" })).status, 401);
});

test("Live now's limit: 10 by default, 1 to 50", async () => {
    const e = sess();
    for (let i = 0; i < 12; i++) {
        const id = `LIVE${i}`.padEnd(8, "x");
        await seedStation(e, { id });
        await seedLive(e, { station: id, room: `ROOM${i}`.padEnd(8, "x") });
    }
    assert.equal((await live(e)).sessions.length, 10);
    assert.equal((await live(e, "?limit=3")).sessions.length, 3);
    assert.equal((await live(e, "?limit=0")).sessions.length, 1);
    assert.equal((await live(e, "?limit=500")).sessions.length, 12);
    assert.equal((await live(e, "?limit=abc")).sessions.length, 10);
});

test("Upcoming: scheduled stations next on the air within 24 hours, soonest first", async () => {
    const e = sess();
    await seedStation(e, { id: "LATER001", name: "Later", autoLive: "schedule", schedule: once(5 * HOUR) });
    await seedStation(e, { id: "SOON0001", name: "Soon", autoLive: "schedule", schedule: once(HOUR) });
    await seedStation(e, { id: "TOMORROW", name: "Too far", autoLive: "schedule", schedule: once(25 * HOUR) });
    await seedStation(e, { id: "ALWAYS01", name: "Always", autoLive: "always", schedule: once(HOUR) });
    await seedStation(e, { id: "OFF00001", name: "Off", schedule: once(HOUR) });
    const { stations } = await upcoming(e);
    assert.deepEqual(stations.map((s) => s.id), ["SOON0001", "LATER001"]);
    assert.ok(stations[0].next.start > Date.now() && stations[0].next.end > stations[0].next.start);
    assert.equal(stations[0].schedule, undefined, "the hours themselves are on the station's page");
});

test("Upcoming leaves out a station that's on the air now, a hidden one and a removed one", async () => {
    const e = sess();
    for (const [id, over] of [["LIVENOW1", {}], ["HIDDEN01", { down: 3 }], ["REMOVED1", { removed: 1 }], ["SHOWN001", {}]]) {
        await seedStation(e, { id, autoLive: "schedule", schedule: once(HOUR), ...over });
    }
    await seedLive(e, { station: "LIVENOW1" });
    assert.deepEqual((await upcoming(e)).stations.map((s) => s.id), ["SHOWN001"]);
});

test("Upcoming's edges: in progress is not upcoming; exactly 24 hours ahead is, a minute past isn't", () => {
    const now = Date.UTC(2026, 10, 16, 12, 0, 30); // Mon 12:00:30 UTC
    const row = (id, start, end) => ({ id, name: id, owner: "o", up: 0, down: 0, auto_live: "schedule", schedule: JSON.stringify({ tz: "UTC", windows: [{ day: 1 + (id === "TUE" || id === "TUE2" ? 1 : 0), start, end }] }) });
    const rows = [row("NOW", "11:30", "13:00"), row("TUE", "12:00", "13:00"), row("TUE2", "12:01", "13:00")];
    // TUE starts 23 h 59.5 min ahead; TUE2 at 24 h 0.5 min.
    assert.deepEqual(pickUpcoming(rows, now, 10, null).map((s) => s.id), ["TUE"]);
    assert.deepEqual(pickUpcoming(rows, Date.UTC(2026, 10, 16, 12, 1, 0), 10, null).map((s) => s.id), ["TUE", "TUE2"]);
});

test("Upcoming's limit, and a station in another time zone", async () => {
    const e = sess();
    for (let i = 1; i <= 4; i++) await seedStation(e, { id: `SCHED00${i}`, autoLive: "schedule", schedule: once(i * HOUR) });
    assert.deepEqual((await upcoming(e, "?limit=2")).stations.map((s) => s.id), ["SCHED001", "SCHED002"]);
    // Kolkata, +05:30: a window that starts in 2 hours there.
    const at = Date.now() + 2 * HOUR;
    const local = new Date(at + 5.5 * HOUR);
    await seedStation(e, { id: "KOLKATA1", autoLive: "schedule", schedule: { tz: "Asia/Kolkata",
        windows: [{ day: ((local.getUTCDay() + 6) % 7) + 1, start: local.toISOString().slice(11, 16), end: new Date(local.getTime() + 1_800_000).toISOString().slice(11, 16) }] } });
    const k = (await upcoming(e, "?limit=10")).stations.find((s) => s.id === "KOLKATA1");
    assert.ok(Math.abs(k.next.start - at) < 60_000, `${k.next.start} vs ${at}`);
});

test("Upcoming walks stations_schedule, not every station", () => {
    const db = sess().COMMUNITY_DB.db;
    const plan = db.prepare(`EXPLAIN QUERY PLAN SELECT s.id FROM stations s
        WHERE s.auto_live = 'schedule' AND s.schedule IS NOT NULL AND s.removed_at IS NULL AND ((s.up - s.down) > -3 OR s.vouched = 1)`).all()
        .map((r) => r.detail).join(" | ");
    assert.match(plan, /stations_schedule/);
});
