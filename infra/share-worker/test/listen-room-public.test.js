import { test } from "node:test";
import assert from "node:assert/strict";
import { ListenRoom, EDIT_BURST, EDITS_PER_SECOND } from "../src/listen-room.js";
import { LIVE_BEAT_MS, LIVE_GAP_MS } from "../src/room.js";
import { sha256Hex } from "../src/store.js";
import { call } from "./community-helpers.js";
import { fakeCtx, fakeSocket } from "./fake-room.js";
import { seedLive, seedStation, sess } from "./stations-helpers.js";

/** A valid Community key (43 base64url characters), one per [n]. */
const communityKey = (n) => `${n}`.padStart(43, "k");

/** A session for station STATION1, host key "KEY". [env] is the room's env (COMMUNITY_DB for the live directory). */
async function openSession(clock, env = {}, code = "ABCDEFGH") {
    const ctx = fakeCtx();
    const room = new ListenRoom(ctx, env, () => clock.t);
    await room.apply({ type: "create", code, hostName: "Rawn", keyHash: await sha256Hex("KEY"), stationId: "STATION1" });
    return { ctx, room };
}

async function connect(ctx, room, hello) {
    const ws = fakeSocket();
    ctx.acceptWebSocket(ws);
    await room.webSocketMessage(ws, JSON.stringify({ t: "hello", v: 3, ...hello }));
    return ws;
}

// ── Task 12: who you are, from the shell ──────────────────────────────────────

test("a listener's Community key reaches the room only as its hash, and no frame carries either", async () => {
    const { ctx, room } = await openSession({ t: 1_000 });
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const key = communityKey(1);
    const listener = await connect(ctx, room, { name: "Sam", communityKey: key });
    const hash = await sha256Hex(key);
    const stored = await ctx.storage.get("room");
    assert.equal(stored.members.find((m) => m.name === "Sam").kh, hash);
    assert.ok(!JSON.stringify(stored).includes(key), "the key itself is never stored");
    const wire = JSON.stringify([...host.sent, ...listener.sent]);
    assert.ok(listener.sent.some((m) => m.t === "welcome"));
    assert.ok(!wire.includes(key) && !wire.includes(hash), "nor sent, nor its hash");
});

test("without a valid Community key a session closes the socket with 4401; an older app gets 4426", async () => {
    const { ctx, room } = await openSession({ t: 1_000 });
    assert.equal((await connect(ctx, room, { communityKey: "too-short" })).closeCode, 4401);
    assert.equal((await connect(ctx, room, {})).closeCode, 4401);
    const old = fakeSocket();
    ctx.acceptWebSocket(old);
    await room.webSocketMessage(old, JSON.stringify({ t: "hello", communityKey: communityKey(2) }));
    assert.equal(old.closeCode, 4426);
    assert.equal((await ctx.storage.get("room")).members.length, 0);
});

test("a session's socket route lets 54 members in, then answers 409", async () => {
    const { ctx, room } = await openSession({ t: 1_000 });
    await connect(ctx, room, { hostKey: "KEY" });
    for (let i = 1; i < 54; i++) await connect(ctx, room, { communityKey: communityKey(i) });
    assert.equal((await ctx.storage.get("room")).members.length, 54);
    const r = await room.fetch(new Request("https://room/ws", { headers: { Upgrade: "websocket" } }));
    assert.equal(r.status, 409);
    const late = await connect(ctx, room, { communityKey: communityKey(99) });
    assert.equal(late.closeCode, 4409, "and a hello past that is full");
});

test("a session's socket cap is twice its 54, not a private room's 20", async () => {
    const { ctx, room } = await openSession({ t: 1_000 });
    const saved = { Pair: globalThis.WebSocketPair, Response: globalThis.Response };
    globalThis.WebSocketPair = function () { return [fakeSocket(), fakeSocket()]; };
    globalThis.Response = function (body, init) { return { status: init.status }; };
    try {
        const upgrade = () => room.fetch(new Request("https://room/ws?r=1", { headers: { Upgrade: "websocket" } }));
        for (let i = 0; i < 108; i++) assert.equal((await upgrade()).status, 101, `socket ${i}`);
        assert.equal((await upgrade()).status, 409);
    } finally {
        globalThis.WebSocketPair = saved.Pair;
        globalThis.Response = saved.Response;
    }
});

// ── Task 17: booting, in the shell ────────────────────────────────────────────

test("a booted listener's socket hears why, then closes with 4403; their key can't come back", async () => {
    const { ctx, room } = await openSession({ t: 1_000 });
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const key = communityKey(5);
    const sam = await connect(ctx, room, { name: "Sam", communityKey: key });
    const samId = sam.sent.find((m) => m.t === "welcome").memberId;
    await room.webSocketMessage(host, JSON.stringify({ t: "boot", memberId: samId }));
    assert.ok(sam.sent.some((m) => m.t === "ended" && m.reason === "booted"));
    assert.equal(sam.closeCode, 4403);
    assert.equal(sam.deserializeAttachment().memberId, null, "unbound, so a late message can't act as them");
    await room.webSocketClose(sam, 4403, "booted");
    assert.equal(host.closed, false);
    const again = await connect(ctx, room, { name: "Sam", communityKey: key });
    assert.equal(again.closeCode, 4403);
});

// ── Task 18: the live directory, in the shell ─────────────────────────────────

const TRACK = { t: "Avril 14th", a: "Aphex Twin", d: 125000 };
const liveRows = (e) => e.COMMUNITY_DB.db.prepare("SELECT * FROM live ORDER BY room").all().map((r) => ({ ...r }));
const lastLive = (e) => e.COMMUNITY_DB.db.prepare("SELECT last_live_at FROM stations WHERE id = 'STATION1'").get().last_live_at;
const endedWith = (ws, reason) => ws.sent.some((m) => m.t === "ended" && m.reason === reason);

/** An env with Sessions on and station STATION1 in its database. */
async function withStation() {
    const e = sess();
    await seedStation(e, { id: "STATION1", name: "Late Night", created: 1 });
    return e;
}

/** A session of STATION1 in [e]: its host and one listener connected, its first song loaded (so written). */
async function liveSession(clock, e, code = "ABCDEFGH") {
    const { ctx, room } = await openSession(clock, e, code);
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const sam = await connect(ctx, room, { name: "Sam", communityKey: communityKey(7) });
    await room.webSocketMessage(host, JSON.stringify({ t: "load", track: TRACK }));
    return { ctx, room, host, sam };
}

test("a session's first song puts it in the directory, where Live now finds it", async () => {
    const e = await withStation();
    const clock = { t: Date.now() };
    const { host, sam } = await liveSession(clock, e);
    const [row] = liveRows(e);
    assert.deepEqual({ ...row, track: JSON.parse(row.track), crowd: JSON.parse(row.crowd) }, {
        station_id: "STATION1", room: "ABCDEFGH", track: TRACK, listeners: 1, paused: 0,
        crowd: [{ i: "R", id: host.deserializeAttachment().memberId }, { i: "S", id: sam.deserializeAttachment().memberId }],
        started_at: clock.t, seen_at: clock.t,
    });
    assert.equal(lastLive(e), clock.t, "on the air: the 7-day clock starts again");
    const { sessions } = await (await call(e, "GET", "/v1/community/live")).json();
    assert.deepEqual(sessions.map((s) => [s.station.name, s.room, s.track.t, s.listeners, s.paused, s.full]),
        [["Late Night", "ABCDEFGH", "Avril 14th", 1, false, false]]);
});

test("an older session can't take a newer one's row back, and ends `replaced` at its next write", async () => {
    const e = await withStation();
    const clock = { t: Date.now() };
    const older = await liveSession(clock, e, "ABCDEFGH");
    clock.t += 1_000;
    const newer = await liveSession(clock, e, "JKLMNPQR");
    assert.deepEqual(liveRows(e).map((r) => r.room), ["JKLMNPQR"]);
    clock.t += LIVE_BEAT_MS;
    await older.room.alarm(); // its heartbeat
    assert.deepEqual(liveRows(e).map((r) => [r.room, r.seen_at]), [["JKLMNPQR", clock.t - LIVE_BEAT_MS]], "untouched");
    assert.ok(endedWith(older.sam, "replaced"));
    assert.equal(older.sam.closeCode, 4000);
    assert.equal(older.ctx.store.size, 0);
    assert.equal(newer.sam.closed, false);
});

test("a station removed, hidden by votes or blocked ends its session at the next write, and its row goes", async () => {
    for (const [what, sql] of [
        ["removed", "UPDATE stations SET removed_at = 1, removed_by = 'owner' WHERE id = 'STATION1'"],
        ["hidden", "UPDATE stations SET down = 3 WHERE id = 'STATION1'"],
        ["blocked", "INSERT INTO blocked (poster, at) SELECT owner, 1 FROM stations WHERE id = 'STATION1'"],
    ]) {
        const e = await withStation();
        const clock = { t: Date.now() };
        const s = await liveSession(clock, e);
        const stamped = lastLive(e);
        e.COMMUNITY_DB.db.exec(sql);
        clock.t += LIVE_BEAT_MS;
        await s.room.alarm();
        assert.deepEqual(liveRows(e), [], what);
        assert.ok(endedWith(s.sam, "station_removed"), what);
        assert.equal(s.ctx.store.size, 0, what);
        assert.equal(lastLive(e), stamped, `${what}: no longer on the air`);
    }
});

test("a database error leaves the session going, and the next heartbeat writes again", async () => {
    const e = await withStation();
    const batch = e.COMMUNITY_DB.batch;
    e.COMMUNITY_DB.batch = async () => { throw new Error("D1_ERROR: overloaded"); };
    const logged = [];
    const error = console.error;
    console.error = (...a) => logged.push(a.join(" "));
    try {
        const clock = { t: Date.now() };
        const s = await liveSession(clock, e);
        assert.equal(s.sam.closed, false);
        assert.ok((await s.ctx.storage.get("room")).track, "still playing");
        assert.deepEqual(liveRows(e), []);
        assert.ok(logged.some((l) => l.includes("overloaded")));
        e.COMMUNITY_DB.batch = batch;
        clock.t += LIVE_BEAT_MS;
        await s.room.alarm();
        assert.deepEqual(liveRows(e).map((r) => r.seen_at), [clock.t]);
    } finally {
        console.error = error;
    }
});

test("a session's end takes its own row down, never a newer session's", async () => {
    const e = await withStation();
    const clock = { t: Date.now() };
    const s = await liveSession(clock, e);
    await s.room.webSocketMessage(s.host, JSON.stringify({ t: "end" }));
    assert.deepEqual(liveRows(e), []);
    const t = await liveSession(clock, e, "JKLMNPQR");
    await seedLive(e, { station: "STATION1", room: "STUVWXYZ", started: clock.t + 5 }); // a newer session wrote since
    await t.room.webSocketMessage(t.host, JSON.stringify({ t: "end" }));
    assert.deepEqual(liveRows(e).map((r) => r.room), ["STUVWXYZ"]);
});

test("directory writes go one at a time, in the room's order: a slow write can't land after the next", async () => {
    const e = await withStation();
    const batch = e.COMMUNITY_DB.batch;
    let release;
    const held = new Promise((r) => { release = r; });
    let calls = 0;
    e.COMMUNITY_DB.batch = async (statements) => { if (calls++ === 0) await held; return batch(statements); };
    const clock = { t: Date.now() };
    const { ctx, room } = await openSession(clock, e);
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const first = room.webSocketMessage(host, JSON.stringify({ t: "load", track: TRACK })); // its write is held
    await new Promise((r) => setImmediate(r));
    clock.t += LIVE_GAP_MS;
    const second = room.webSocketMessage(host, JSON.stringify({ t: "load", track: { t: "Xtal", a: "Aphex Twin" } }));
    await new Promise((r) => setImmediate(r));
    assert.equal(calls, 1, "the second write waits for the first");
    release();
    await Promise.all([first, second]);
    assert.equal(calls, 2);
    assert.deepEqual(JSON.parse(liveRows(e)[0].track), { t: "Xtal", a: "Aphex Twin" });
});

test("requests share the edit limit with add: with Auto-accept on each one sends 54 phones the queue", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openSession(clock);
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const sam = await connect(ctx, room, { name: "Sam", communityKey: communityKey(3) });
    await room.webSocketMessage(host, JSON.stringify({ t: "autoAccept", on: true }));
    const request = (t) => room.webSocketMessage(sam, JSON.stringify({ t: "request", track: { t, a: "A" } }));
    const queues = () => host.sent.filter((m) => m.t === "queue").length;
    for (let i = 0; i < EDIT_BURST; i++) await request(`S${i}`);
    assert.equal(queues(), EDIT_BURST);
    await request("over");
    assert.equal(sam.closed, false);
    assert.equal(queues(), EDIT_BURST, "nobody gets another whole queue");
    assert.deepEqual(sam.sent.at(-1), { t: "requestAnswer", state: "full" }, "the requester still hears");
    clock.t += 1_000 / EDITS_PER_SECOND;
    await request("later");
    assert.equal(queues(), EDIT_BURST + 1, "and it comes back at the steady rate");
});

test("a reaction a second a socket, held by the shell; in a session no reaction stores the room", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openSession(clock);
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const sam = await connect(ctx, room, { name: "Sam", communityKey: communityKey(4) });
    let puts = 0;
    const put = ctx.storage.put;
    ctx.storage.put = async (...a) => { puts++; return put(...a); };
    const react = (emoji = "\u{1F525}") => room.webSocketMessage(sam, JSON.stringify({ t: "react", emoji }));
    const reactions = () => host.sent.filter((m) => m.t === "reaction").length;
    await react();
    assert.equal(reactions(), 1);
    clock.t += 999;
    await react();
    assert.equal(reactions(), 1, "under a second since the last");
    await react("x");
    clock.t += 1;
    await react();
    assert.equal(reactions(), 2, "a second on; the bad emoji didn't count as one");
    assert.equal(puts, 0, "nothing stored for any of them");
});

test("a phrase every 3 s a socket, held by the shell, and none of them stores the room", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openSession(clock);
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const sam = await connect(ctx, room, { name: "Sam", communityKey: communityKey(4) });
    const jo = await connect(ctx, room, { name: "Jo", communityKey: communityKey(5) });
    let puts = 0;
    const put = ctx.storage.put;
    ctx.storage.put = async (...a) => { puts++; return put(...a); };
    const say = (ws, phrase = "tune") => room.webSocketMessage(ws, JSON.stringify({ t: "say", phrase }));
    const said = () => host.sent.filter((m) => m.t === "said").length;
    await say(sam);
    assert.equal(said(), 1);
    clock.t += 2_999;
    await say(sam, "chills");
    assert.equal(said(), 1, "under 3 s since Sam's last");
    await say(jo);
    assert.equal(said(), 2, "Jo's pace is Jo's own");
    clock.t += 1;
    await say(sam, "chills");
    assert.equal(said(), 3);
    assert.equal(puts, 0);
});

test("an animated reaction passes the shell as an emoji does", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openSession(clock);
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const sam = await connect(ctx, room, { name: "Sam", communityKey: communityKey(4) });
    await room.webSocketMessage(sam, JSON.stringify({ t: "react", emoji: "a:fire" }));
    assert.equal(host.sent.filter((m) => m.t === "reaction" && m.emoji === "a:fire").length, 1);
    clock.t += 1_000;
    await room.webSocketMessage(sam, JSON.stringify({ t: "react", emoji: "a:nope" }));
    assert.equal(host.sent.filter((m) => m.t === "reaction").length, 1);
});
