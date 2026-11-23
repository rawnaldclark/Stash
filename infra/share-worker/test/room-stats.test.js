// The Sessions scoreboard (build plan, decision 10): a session's totals as its room closes, the day's row they join,
// and the report. Numbers only: no person, station or song.
import { test } from "node:test";
import assert from "node:assert/strict";
import { step, PUBLIC_V, HANDOVER_MS } from "../src/room.js";
import { addSessionStats } from "../src/stations-api.js";
import { commandSql } from "../scripts/community.mjs";
import { sess } from "./stations-helpers.js";

const T0 = Date.UTC(2026, 10, 22, 12, 0, 0);
const MIN = 60_000;
const TRACK = { t: "Avril 14th", a: "Aphex Twin", d: 125000 };
const FIRE = "\u{1F525}";
const kh = (id) => `kh-${id}`;
const hello = (s, id, { host = false, at = T0 } = {}) =>
    step(s, { type: "hello", msg: { t: "hello", name: id, v: PUBLIC_V }, hostKeyOk: host, listenerHash: host ? undefined : kh(id), newId: id, newToken: `tok-${id}` }, at);
const send = (s, from, msg, at = T0, extra = {}) => step(s, { type: "msg", from, msg, ...extra }, at);

/** A session on the air: host "h", a song playing. */
function onAir() {
    let s = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h", stationId: "STATION1" }, T0).state;
    s = hello(s, "h", { host: true }).state;
    s = send(s, "h", { t: "load", track: TRACK, positionMs: 0, queue: [] }).state;
    return send(s, "h", { t: "status", status: "ready", trackKey: 1 }).state;
}

test("a session's totals come out once, as it ends: listeners, their time, how long they stayed, what they did", () => {
    let s = onAir();
    s = hello(s, "ana", { at: T0 }).state; // stays 40 minutes, reacts twice, says a phrase, asks for a song
    s = hello(s, "ben", { at: T0 }).state; // stays 5 minutes, saves the song
    s = hello(s, "cy", { at: T0 }).state; // looks in for a minute
    s = send(s, "ana", { t: "react", emoji: FIRE }, T0 + 1000).state;
    s = send(s, "ana", { t: "react", emoji: FIRE }, T0 + 5000).state;
    s = send(s, "ana", { t: "say", phrase: "tune" }, T0 + 6000).state;
    s = send(s, "ana", { t: "request", track: { t: "Xtal", a: "Aphex Twin" } }, T0 + 7000, { newId: "r1" }).state;
    s = send(s, "h", { t: "requestAction", id: "r1", action: "queue" }, T0 + 8000).state;
    s = send(s, "ben", { t: "saved", trackKey: 1 }, T0 + 9000).state;
    s = send(s, "h", { t: "react", emoji: FIRE }, T0 + 9500).state; // the host is no listener
    s = step(s, { type: "close", from: "cy" }, T0 + 1 * MIN).state;
    s = step(s, { type: "alarm" }, T0 + 1 * MIN + HANDOVER_MS).state; // cy's slot lapses
    s = step(s, { type: "close", from: "ben" }, T0 + 5 * MIN).state;
    const r = send(s, "h", { t: "end" }, T0 + 40 * MIN);
    assert.equal(r.closed, true);
    assert.deepEqual(r.stats, {
        listeners: 3, minutes: 46, stays: [1, 1, 0, 1],
        reacted: 1, said: 1, saved: 1, requested: 1, requestsTaken: 1,
    });
    // Nothing that names anyone, any station or any song.
    const text = JSON.stringify(r.stats);
    for (const secret of ["ana", "kh-", "STATION1", "Avril", "ABCDEFGH"]) assert.ok(!text.includes(secret), secret);
});

test("a listener who comes back in the same session is one listener, with both stays added up", () => {
    let s = hello(onAir(), "ana", { at: T0 }).state;
    s = step(s, { type: "close", from: "ana" }, T0 + 1 * MIN).state;
    s = step(s, { type: "alarm" }, T0 + 1 * MIN + HANDOVER_MS).state;
    s = step(s, { type: "hello", msg: { t: "hello", name: "ana", v: PUBLIC_V }, listenerHash: kh("ana"), newId: "ana2", newToken: "tok-ana2" }, T0 + 10 * MIN).state;
    const r = send(s, "h", { t: "end" }, T0 + 12 * MIN);
    assert.equal(r.stats.listeners, 1);
    assert.equal(r.stats.minutes, 3);
    assert.deepEqual(r.stats.stays, [0, 1, 0, 0]);
});

test("a session that never reached the air, and a private room, have no totals", () => {
    let s = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h", stationId: "STATION1" }, T0).state;
    s = hello(s, "h", { host: true }).state;
    assert.equal(send(s, "h", { t: "end" }, T0 + MIN).stats, undefined);

    let p = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h" }, T0).state;
    p = step(p, { type: "hello", msg: { t: "hello", name: "h" }, hostKeyOk: true, newId: "h", newToken: "t" }, T0).state;
    assert.equal(send(p, "h", { t: "end" }, T0 + MIN).stats, undefined);
});

// ── The database and the report ────────────────────────────────────────────

const run = async (e, sql) => {
    const rows = [];
    for (const s of sql.split(";\n")) rows.push((await e.COMMUNITY_DB.prepare(s).all()).results);
    return rows;
};

test("each session adds to its day's row, and the report gives the days, the totals and the shares", async () => {
    const e = sess();
    const db = e.COMMUNITY_DB;
    const a = { listeners: 3, minutes: 46, stays: [1, 1, 0, 1], reacted: 1, said: 1, saved: 1, requested: 1, requestsTaken: 1 };
    const b = { listeners: 1, minutes: 20, stays: [0, 0, 1, 0], reacted: 1, said: 0, saved: 0, requested: 0, requestsTaken: 0 };
    await addSessionStats(db, a, T0);
    await addSessionStats(db, b, T0 + 60 * MIN);
    await addSessionStats(db, b, T0 - 26 * 60 * MIN); // the day before
    await addSessionStats(db, { ...b, listeners: -4, minutes: "lots" }, T0 - 40 * 24 * 60 * MIN); // nonsense reads as 0; too old to report

    const [days, totals, returning] = await run(e, commandSql("stats", "7", T0 + 2 * 60 * MIN));
    assert.deepEqual(days.map((d) => [d.day, d.sessions, d.listeners, d.listener_minutes]), [["2026-11-22", 2, 4, 66], ["2026-11-21", 1, 1, 20]]);
    assert.deepEqual(totals, [{
        sessions: 3, listeners: 5, listener_minutes: 86, avg_stay_min: 17.2, median_stay: "10 to 30 min",
        reacted_pct: 60, said_pct: 20, saved_pct: 20, requested_pct: 20, requests_taken: 1, follows: 0, follows_per_session: 0,
    }]);
    assert.deepEqual(returning, [{ returning_listeners_7d: 0, at_stations: 0 }]);
    const old = await db.prepare("SELECT listeners, listener_minutes FROM session_stats WHERE day < '2026-11-01'").first();
    assert.deepEqual(old, { listeners: 0, listener_minutes: 0 });
});

test("the report on an empty scoreboard is empty, not an error, and takes 1 to 200 days", async () => {
    const e = sess();
    const [days, totals] = await run(e, commandSql("stats", undefined, T0));
    assert.deepEqual(days, []);
    assert.equal(totals[0].sessions, null);
    assert.throws(() => commandSql("stats", "0", T0));
    assert.throws(() => commandSql("stats", "seven", T0));
});

// ── Through the shell ─────────────────────────────────────────────────────────

import { ListenRoom } from "../src/listen-room.js";
import { sha256Hex } from "../src/store.js";
import { fakeCtx, fakeSocket } from "./fake-room.js";
import { seedStation } from "./stations-helpers.js";

async function liveSession(e, clock) {
    const ctx = fakeCtx();
    const room = new ListenRoom(ctx, e, () => clock.t);
    await room.apply({ type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: await sha256Hex("KEY"), stationId: "STATION1" });
    const connect = async (hello) => {
        const ws = fakeSocket();
        ctx.acceptWebSocket(ws);
        await room.webSocketMessage(ws, JSON.stringify({ t: "hello", v: 3, ...hello }));
        return ws;
    };
    const host = await connect({ name: "Rawn", hostKey: "KEY" });
    const say = (ws, msg) => room.webSocketMessage(ws, JSON.stringify(msg));
    await say(host, { t: "load", track: TRACK, positionMs: 0, queue: [] });
    await say(host, { t: "status", status: "ready", trackKey: 1 });
    return { connect, say, host };
}

test("a session that ends writes its totals to the day's row; without the table it ends just the same", async () => {
    const e = sess();
    await seedStation(e, { id: "STATION1" });
    const clock = { t: T0 };
    const { connect, say, host } = await liveSession(e, clock);
    const sam = await connect({ name: "Sam", communityKey: "s".repeat(43) });
    await say(sam, { t: "say", phrase: "tune" });
    clock.t += 12 * MIN;
    await say(host, { t: "end" });
    const row = await e.COMMUNITY_DB.prepare("SELECT * FROM session_stats").first();
    assert.deepEqual(
        [row.day, row.sessions, row.listeners, row.listener_minutes, row.stay_10_to_30, row.said, row.reacted],
        ["2026-11-22", 1, 1, 12, 1, 1, 0],
    );

    // This code deployed before its migration: the write fails, is logged, and the session still ends.
    const bare = sess();
    await seedStation(bare, { id: "STATION1" });
    await bare.COMMUNITY_DB.prepare("DROP TABLE session_stats").run();
    const errors = [];
    const log = console.error;
    console.error = (m) => errors.push(String(m));
    try {
        const second = await liveSession(bare, clock);
        const jo = await second.connect({ name: "Jo", communityKey: "j".repeat(43) });
        await second.say(second.host, { t: "end" });
        assert.ok(jo.sent.some((m) => m.t === "ended"));
    } finally {
        console.error = log;
    }
    assert.ok(errors.some((m) => m.startsWith("session stats:")), errors.join(" | "));
});
