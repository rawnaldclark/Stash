// Sessions step 5: regulars. The room counts a listener's stay; the shell reads and writes the count.
import { test } from "node:test";
import assert from "node:assert/strict";
import { step, PUBLIC_V, REGULAR_STAY_MS, REGULAR_SESSIONS, HANDOVER_MS } from "../src/room.js";
import { ListenRoom } from "../src/listen-room.js";
import { cleanupStations, countRegulars, visitsOf, REGULAR_IDLE_MS } from "../src/stations-api.js";
import { sha256Hex } from "../src/store.js";
import { fakeCtx, fakeSocket } from "./fake-room.js";
import { seedStation, sess } from "./stations-helpers.js";

const T0 = 1_000_000;
const MIN = 60_000;
const kh = (id) => `kh-${id}`;
const hello = (s, id, { host = false, at = T0, visits, resumeToken, hash = host ? undefined : kh(id) } = {}) =>
    step(s, { type: "hello", msg: { t: "hello", name: id, v: PUBLIC_V, resumeToken }, hostKeyOk: host, listenerHash: hash, visits, newId: id, newToken: `tok-${id}` }, at);
const close = (s, id, at) => step(s, { type: "close", from: id }, at);
const alarm = (s, at) => step(s, { type: "alarm" }, at);
const send = (s, from, msg, at) => step(s, { type: "msg", from, msg }, at);

function session() {
    let s = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h", stationId: "STATION1" }, T0).state;
    s = hello(s, "h", { host: true }).state;
    // Playing, so the session doesn't end for being paused while the tests wait.
    s = send(s, "h", { t: "load", track: { t: "Avril 14th", a: "Aphex Twin", d: 125000 }, positionMs: 0, queue: [] }, T0).state;
    return send(s, "h", { t: "status", status: "ready", trackKey: 1 }, T0).state;
}

// ── The room ──────────────────────────────────────────────────────────────────

test("a listener who stayed 10 minutes is counted when their slot lapses, once", () => {
    let s = session();
    s = hello(s, "a", { at: T0 }).state;
    s = close(s, "a", T0 + REGULAR_STAY_MS).state;
    const r = alarm(s, T0 + REGULAR_STAY_MS + HANDOVER_MS);
    assert.deepEqual(r.regulars, { stationId: "STATION1", listeners: [kh("a")] });
    assert.equal(alarm(r.state, T0 + REGULAR_STAY_MS + 2 * HANDOVER_MS).regulars, undefined);
});

test("a shorter stay counts nothing", () => {
    let s = session();
    s = hello(s, "a", { at: T0 }).state;
    s = close(s, "a", T0 + REGULAR_STAY_MS - 1).state;
    assert.equal(alarm(s, T0 + REGULAR_STAY_MS + HANDOVER_MS).regulars, undefined);
});

test("time adds up across a reconnect, and across leaving and coming back as someone new", () => {
    let s = session();
    s = hello(s, "a", { at: T0 }).state;
    s = close(s, "a", T0 + 4 * MIN).state;
    s = hello(s, "a2", { at: T0 + 4 * MIN + 10_000, resumeToken: "tok-a" }).state; // back on the same slot
    s = close(s, "a", T0 + 8 * MIN).state; // 4 min + 3 min 50 s
    let r = alarm(s, T0 + 8 * MIN + HANDOVER_MS);
    assert.equal(r.regulars, undefined, "7 min 50 s so far");
    // A new slot, the same phone (key hash): the session remembers the 7 min 50 s.
    s = hello(r.state, "b", { at: T0 + 20 * MIN, hash: kh("a") }).state;
    s = close(s, "b", T0 + 23 * MIN).state;
    r = alarm(s, T0 + 23 * MIN + HANDOVER_MS);
    assert.deepEqual(r.regulars?.listeners, [kh("a")]);
    // Coming back again after being counted never counts the same session twice.
    s = hello(r.state, "c", { at: T0 + 30 * MIN, hash: kh("a") }).state;
    s = close(s, "c", T0 + 50 * MIN).state;
    assert.equal(alarm(s, T0 + 50 * MIN + HANDOVER_MS).regulars, undefined);
});

test("when the session ends, everyone in it who stayed is counted; the host never is", () => {
    let s = session();
    s = hello(s, "long", { at: T0 }).state;
    s = hello(s, "late", { at: T0 + 5 * MIN }).state;
    const r = send(s, "h", { t: "end" }, T0 + 12 * MIN);
    assert.equal(r.closed, true);
    assert.deepEqual(r.regulars, { stationId: "STATION1", listeners: [kh("long")] });
});

test("a private room counts nobody", () => {
    let s = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h" }, T0).state;
    s = step(s, { type: "hello", msg: { t: "hello" }, hostKeyOk: true, newId: "h", newToken: "t" }, T0).state;
    s = step(s, { type: "hello", msg: { t: "hello" }, hostKeyOk: false, newId: "g", newToken: "t2" }, T0).state;
    const r = send(s, "h", { t: "end" }, T0 + 30 * MIN);
    assert.equal(r.regulars, undefined);
});

test("three sessions before make a regular: the members list says so, and never how many or who by", () => {
    assert.equal(REGULAR_SESSIONS, 3);
    let s = session();
    s = hello(s, "new", { at: T0 }).state;
    s = hello(s, "two", { at: T0, visits: 2 }).state;
    const r = hello(s, "ben", { at: T0, visits: 5 });
    const members = r.out.find((o) => o.msg.t === "members").msg.members;
    assert.deepEqual(members.map((m) => [m.id, m.regular ?? false]), [["h", false], ["new", false], ["two", false], ["ben", true]]);
    assert.ok(!JSON.stringify(r.out).includes("visits"));
    assert.ok(!JSON.stringify(r.out).includes(kh("ben")));
});

test("the host's own visits are never read into the room", () => {
    const s = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h", stationId: "STATION1" }, T0).state;
    const r = hello(s, "h", { host: true, visits: 9 });
    assert.ok(!("regular" in r.out.find((o) => o.msg.t === "members").msg.members[0]));
});

// ── The database ──────────────────────────────────────────────────────────────

test("a count starts at 1, adds one a session, and reads back", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1" });
    const db = e.COMMUNITY_DB;
    assert.equal(await visitsOf(db, "STATION1", "ben"), 0);
    await countRegulars(db, "STATION1", ["ben", "ana"], 1_000);
    await countRegulars(db, "STATION1", ["ben"], 2_000);
    assert.equal(await visitsOf(db, "STATION1", "ben"), 2);
    assert.equal(await visitsOf(db, "STATION1", "ana"), 1);
    assert.equal(await visitsOf(db, "OTHER001", "ben"), 0, "a count is the station's");
    const row = await db.prepare("SELECT last_at FROM station_regulars WHERE listener = 'ben'").first();
    assert.equal(row.last_at, 2_000);
});

test("a station that is gone gets no rows", async () => {
    const e = sess();
    await countRegulars(e.COMMUNITY_DB, "GONE0001", ["ben"], 1_000);
    assert.equal((await e.COMMUNITY_DB.prepare("SELECT COUNT(*) AS n FROM station_regulars").first()).n, 0);
});

test("the cron forgets a count not added to for 60 days, and a deleted station's counts", async () => {
    const e = sess();
    const now = Date.now();
    await seedStation(e, { id: "STATION1", created: now });
    await seedStation(e, { id: "IDLE0001", created: now - 30 * 24 * 60 * MIN });
    await countRegulars(e.COMMUNITY_DB, "STATION1", ["old"], now - REGULAR_IDLE_MS - MIN);
    await countRegulars(e.COMMUNITY_DB, "STATION1", ["fresh"], now - REGULAR_IDLE_MS + MIN);
    await countRegulars(e.COMMUNITY_DB, "IDLE0001", ["fan"], now - MIN);
    await cleanupStations(e, now);
    const left = (await e.COMMUNITY_DB.prepare("SELECT station_id, listener FROM station_regulars ORDER BY listener").all()).results;
    assert.deepEqual(left, [{ station_id: "STATION1", listener: "fresh" }]);
});

// ── The shell ─────────────────────────────────────────────────────────────────

const communityKey = (n) => `${n}`.padStart(43, "k");

async function open(clock, env) {
    const ctx = fakeCtx();
    const room = new ListenRoom(ctx, env, () => clock.t);
    await room.apply({ type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: await sha256Hex("KEY"), stationId: "STATION1" });
    const connect = async (hello) => {
        const ws = fakeSocket();
        ctx.acceptWebSocket(ws);
        await room.webSocketMessage(ws, JSON.stringify({ t: "hello", v: 3, ...hello }));
        return ws;
    };
    return { ctx, room, connect };
}

test("a listener's count is read at hello: a regular arrives with the star, a newcomer without", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1" });
    await countRegulars(e.COMMUNITY_DB, "STATION1", [await sha256Hex(communityKey(1))], 1);
    await countRegulars(e.COMMUNITY_DB, "STATION1", [await sha256Hex(communityKey(1))], 2);
    await countRegulars(e.COMMUNITY_DB, "STATION1", [await sha256Hex(communityKey(1))], 3);
    const { connect } = await open({ t: 10_000 }, e);
    const host = await connect({ name: "Rawn", hostKey: "KEY" });
    await connect({ name: "Ben", communityKey: communityKey(1) });
    await connect({ name: "New", communityKey: communityKey(2) });
    const members = host.sent.filter((m) => m.t === "members").at(-1).members;
    assert.deepEqual(members.map((m) => [m.name, m.regular ?? false]), [["Rawn", false], ["Ben", true], ["New", false]]);
});

test("ending a session writes a count for everyone who stayed 10 minutes", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1" });
    const clock = { t: 10_000 };
    const { room, connect } = await open(clock, e);
    const host = await connect({ name: "Rawn", hostKey: "KEY" });
    await connect({ name: "Ben", communityKey: communityKey(1) });
    clock.t += 6 * MIN;
    await connect({ name: "Late", communityKey: communityKey(2) });
    clock.t += 5 * MIN;
    await room.webSocketMessage(host, JSON.stringify({ t: "end" }));
    assert.equal(await visitsOf(e.COMMUNITY_DB, "STATION1", await sha256Hex(communityKey(1))), 1);
    assert.equal(await visitsOf(e.COMMUNITY_DB, "STATION1", await sha256Hex(communityKey(2))), 0);
});

test("without the table (the code out before its migration) a session still opens and ends", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1" });
    e.COMMUNITY_DB.db.exec("DROP TABLE station_regulars");
    const clock = { t: 10_000 };
    const { room, connect } = await open(clock, e);
    const host = await connect({ name: "Rawn", hostKey: "KEY" });
    const ben = await connect({ name: "Ben", communityKey: communityKey(1) });
    assert.ok(ben.sent.some((m) => m.t === "welcome"));
    clock.t += 11 * MIN;
    await room.webSocketMessage(host, JSON.stringify({ t: "end" }));
    assert.ok(ben.sent.some((m) => m.t === "ended"));
});
