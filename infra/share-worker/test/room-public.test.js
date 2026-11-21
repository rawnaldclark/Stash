import { test } from "node:test";
import assert from "node:assert/strict";
import {
    step, preview, MAX_MEMBERS, MAX_PUBLIC_MEMBERS, PUBLIC_V, PREPARE_MS, HOST_WAIT_CAP_MS, PAUSE_END_MS, HANDOVER_MS, MAX_AGE_MS,
} from "../src/room.js";

const T0 = 1_000_000;
const TRACK = { t: "Avril 14th", a: "Aphex Twin", d: 125000, isrc: "GBBPW0100025" };

const privateRoom = () => step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h" }, T0).state;
const pub = () => step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h", stationId: "STATION1" }, T0).state;
/** A member's key hash, as the shell hands it over (sha256 of their Community key). */
const kh = (id) => `kh-${id}`;
/**
 * A hello from a new socket in a session: v3, the host with the host key, everyone else with a key hash of their own.
 * `id` becomes the member's id and `tok-<id>` its token.
 */
const hello = (s, id, { host = false, at = T0, v = PUBLIC_V, name = id, resumeToken, listenerHash = host ? undefined : kh(id) } = {}) =>
    step(s, { type: "hello", msg: { t: "hello", name, v, resumeToken }, hostKeyOk: host, listenerHash, newId: id, newToken: `tok-${id}` }, at);
const send = (s, from, msg, at = T0, extra = {}) => step(s, { type: "msg", from, msg, ...extra }, at);
const sent = (r, t) => r.out.filter((o) => o.msg.t === t);
/** A session: host "h", listeners "a" and "b" (joined in that order), everyone connected. */
function session() {
    let s = pub();
    s = hello(s, "h", { host: true }).state;
    s = hello(s, "a", { at: T0 + 1 }).state;
    return hello(s, "b", { at: T0 + 2 }).state;
}

// ── Task 11: a public room, its size, protocol v3 ─────────────────────────────

test("a private room is as it was: no session fields, 10 members, v 2", () => {
    let s = privateRoom();
    assert.ok(!("pub" in s), "nothing new is stored for a private room");
    const r = step(s, { type: "hello", msg: { t: "hello", name: "Rawn" }, hostKeyOk: true, listenerHash: kh("h"), newId: "h", newToken: "tok-h" }, T0);
    const state = sent(r, "welcome")[0].msg.state;
    assert.equal(state.v, 2);
    assert.ok(!("session" in state));
    assert.ok(!("kh" in r.state.members[0]), "a private room never keeps a key hash");
    assert.deepEqual(sent(r, "requests"), []);
    s = r.state;
    for (let i = 1; i < MAX_MEMBERS; i++) s = step(s, { type: "hello", msg: { t: "hello" }, hostKeyOk: false, newId: `m${i}`, newToken: `t${i}` }, T0).state;
    const late = step(s, { type: "hello", msg: { t: "hello" }, hostKeyOk: false, newId: "late", newToken: "t" }, T0);
    assert.deepEqual(late.reject, { code: 4409, reason: "full" });
    assert.deepEqual(preview(s), { hostName: "Rawn", memberCount: 10, full: true });
});

test("a session is a room with its station: v 3, no suggestions, the session's own state", () => {
    const s = pub();
    assert.deepEqual(s.pub, {
        stationId: "STATION1", cohosts: [], booted: [], autoAccept: false, presence: "booth", pausedSince: T0, liveAt: null, liveKey: null,
    });
    const r = hello(s, "h", { host: true });
    const state = sent(r, "welcome")[0].msg.state;
    assert.equal(state.v, PUBLIC_V);
    assert.equal(state.guestsAdd, false);
    assert.deepEqual(state.suggestions, []);
    assert.deepEqual(state.session, { station: "STATION1", listeners: 0, cohosts: [], autoAccept: false, presence: "booth" });
});

test("a session holds the host, 3 co-hosts and 50 listeners, and turns the 55th away", () => {
    let s = hello(pub(), "h", { host: true }).state;
    for (let i = 1; i < MAX_PUBLIC_MEMBERS; i++) {
        const r = hello(s, `m${i}`);
        assert.equal(r.reject, undefined, `member ${i}`);
        s = r.state;
    }
    assert.equal(s.members.length, 54);
    assert.deepEqual(hello(s, "late").reject, { code: 4409, reason: "full" });
    assert.deepEqual(preview(s), { hostName: "h", memberCount: 54, full: true, station: "STATION1", listeners: 53 });
});

test("an app that doesn't speak sessions is told to update: 4426", () => {
    for (const v of [1, 2, "3", 2.5]) {
        const r = hello(pub(), "a", { v });
        assert.deepEqual(r.reject, { code: 4426, reason: "update" }, `v ${v}`);
        assert.equal(r.state.members.length, 0);
    }
    // Every release before guest adds sends no `v` at all.
    const old = step(pub(), { type: "hello", msg: { t: "hello", name: "a" }, hostKeyOk: false, listenerHash: kh("a"), newId: "a", newToken: "t" }, T0);
    assert.deepEqual(old.reject, { code: 4426, reason: "update" });
    assert.equal(hello(pub(), "a", { v: 4 }).reject, undefined, "a newer app is welcome");
});

test("a session needs a Community key (or the host key): 4401", () => {
    const r = hello(pub(), "a", { listenerHash: null });
    assert.deepEqual(r.reject, { code: 4401, reason: "key" });
    assert.equal(hello(pub(), "h", { host: true, listenerHash: null }).reject, undefined);
});

test("each member keeps its key hash, and no frame ever carries one", () => {
    let s = pub();
    const frames = [];
    const keep = (r) => { frames.push(...r.out); return r.state; };
    s = keep(hello(s, "h", { host: true, listenerHash: kh("h") }));
    s = keep(hello(s, "a", { at: T0 + 1 }));
    s = keep(send(s, "h", { t: "load", track: TRACK }, T0 + 2));
    s = keep(step(s, { type: "close", from: "a" }, T0 + 3));
    s = keep(hello(s, "a2", { resumeToken: "tok-a", listenerHash: kh("a"), at: T0 + 4 }));
    assert.deepEqual(s.members.map((m) => m.kh), [kh("h"), kh("a")]);
    assert.ok(frames.length > 0);
    assert.ok(!JSON.stringify(frames).includes("kh-"), "never a key hash on the wire");
    assert.ok(!JSON.stringify(preview(s)).includes("kh-"));
});

// ── Task 13: listeners never hold up the music ────────────────────────────────

test("the host's ready starts the song at once, however many listeners are still buffering", () => {
    let s = hello(pub(), "h", { host: true }).state;
    for (let i = 1; i <= 50; i++) s = hello(s, `m${i}`).state;
    s = send(s, "h", { t: "load", track: TRACK }, T0 + 10).state;
    const r = send(s, "h", { t: "status", status: "ready", trackKey: s.trackKey }, T0 + 20);
    assert.equal(sent(r, "timeline")[0].msg.playing, true);
    assert.equal(r.state.phase.kind, "playing");
});

test("a listener's status changes nothing and sends nothing", () => {
    let s = session();
    s = send(s, "h", { t: "load", track: TRACK }, T0 + 10).state;
    const r = send(s, "a", { t: "status", status: "ready", trackKey: s.trackKey }, T0 + 20);
    assert.equal(r.state, s);
    assert.deepEqual(r.out, []);
});

test("a session waits for its host alone: not at the guests' 8 s, but still at the host's cap", () => {
    let s = session();
    s = send(s, "h", { t: "load", track: TRACK }, T0 + 10).state;
    let r = step(s, { type: "alarm" }, T0 + 10 + PREPARE_MS);
    assert.equal(r.state.phase.kind, "preparing");
    r = step(r.state, { type: "alarm" }, T0 + 10 + HOST_WAIT_CAP_MS);
    assert.equal(r.state.phase.kind, "playing");
});

test("a private room still waits for its guests, and still shares every status", () => {
    let s = privateRoom();
    s = step(s, { type: "hello", msg: { t: "hello", v: 2 }, hostKeyOk: true, newId: "h", newToken: "th" }, T0).state;
    s = step(s, { type: "hello", msg: { t: "hello", v: 2 }, hostKeyOk: false, newId: "g", newToken: "tg" }, T0).state;
    s = send(s, "h", { t: "load", track: TRACK }, T0 + 10).state;
    let r = send(s, "h", { t: "status", status: "ready", trackKey: s.trackKey }, T0 + 20);
    assert.equal(r.state.phase.kind, "preparing");
    assert.equal(sent(r, "members").length, 1);
    r = send(r.state, "g", { t: "status", status: "ready", trackKey: s.trackKey }, T0 + 30);
    assert.equal(r.state.phase.kind, "playing");
});

test("arrivals and departures update the session's count", () => {
    let r = hello(session(), "c", { at: T0 + 3 });
    assert.equal(sent(r, "session")[0].msg.listeners, 3);
    r = step(r.state, { type: "close", from: "a" }, T0 + 4);
    assert.equal(sent(r, "session")[0].msg.listeners, 2);
});

// ── Task 14: how a session ends ───────────────────────────────────────────────

/** The session with the host's song playing since T0 + 20. */
function onAir() {
    let s = session();
    s = send(s, "h", { t: "load", track: TRACK }, T0 + 10).state;
    return send(s, "h", { t: "status", status: "ready", trackKey: s.trackKey }, T0 + 20).state;
}
const ended = (r) => sent(r, "ended").map((o) => [o.to, o.msg.reason]);

test("paused 15 minutes, a session ends; playing again before that keeps it going", () => {
    const paused = send(onAir(), "h", { t: "pause" }, T0 + 1_000);
    assert.equal(paused.state.pub.pausedSince, T0 + 1_000);
    assert.equal(paused.alarmAt, T0 + 1_000 + PAUSE_END_MS);
    assert.equal(step(paused.state, { type: "alarm" }, T0 + 1_000 + PAUSE_END_MS - 1).closed, undefined);
    const r = step(paused.state, { type: "alarm" }, T0 + 1_000 + PAUSE_END_MS);
    assert.equal(r.closed, true);
    assert.deepEqual(ended(r), [["all", "paused"]]);
    const back = send(paused.state, "h", { t: "play" }, T0 + 1_000 + 14 * 60_000);
    assert.equal(back.state.pub.pausedSince, null);
    assert.equal(step(back.state, { type: "alarm" }, T0 + 1_000 + PAUSE_END_MS).closed, undefined);
});

test("a seek while paused doesn't restart the 15 minutes", () => {
    const paused = send(onAir(), "h", { t: "pause" }, T0 + 1_000).state;
    const sought = send(paused, "h", { t: "seek", positionMs: 30_000 }, T0 + 5 * 60_000);
    assert.equal(sought.state.pub.pausedSince, T0 + 1_000);
});

test("a session whose host never plays ends 15 minutes after it opened", () => {
    const s = hello(pub(), "h", { host: true }).state;
    assert.equal(step(s, { type: "alarm" }, T0 + PAUSE_END_MS).closed, true);
});

test("the host gone 60 s ends the session: no hand-over", () => {
    const s = step(onAir(), { type: "close", from: "h" }, T0 + 1_000).state;
    const waiting = step(s, { type: "alarm" }, T0 + 1_000 + HANDOVER_MS - 1);
    assert.equal(waiting.closed, undefined);
    assert.equal(waiting.state.host, "h");
    const r = step(s, { type: "alarm" }, T0 + 1_000 + HANDOVER_MS);
    assert.equal(r.closed, true);
    assert.deepEqual(ended(r), [["all", "host_left"]]);
});

test("a host back within the 60 s carries on", () => {
    let s = step(onAir(), { type: "close", from: "h" }, T0 + 1_000).state;
    s = hello(s, "h2", { host: true, resumeToken: "tok-h", at: T0 + 30_000 }).state;
    const r = step(s, { type: "alarm" }, T0 + 1_000 + HANDOVER_MS);
    assert.equal(r.closed, undefined);
});

test("nobody can be made host in a session", () => {
    const s = onAir();
    const r = send(s, "h", { t: "makeHost", memberId: "a" });
    assert.equal(r.state, s);
    assert.deepEqual(r.out, []);
});

test("at 12 hours a session ends max_age; a private room still says closed", () => {
    assert.deepEqual(ended(step(onAir(), { type: "alarm" }, T0 + MAX_AGE_MS)), [["all", "max_age"]]);
    let p = step(privateRoom(), { type: "hello", msg: { t: "hello" }, hostKeyOk: true, newId: "h", newToken: "t" }, T0).state;
    assert.deepEqual(ended(step(p, { type: "alarm" }, T0 + MAX_AGE_MS)), [["all", "closed"]]);
});

test("a station gone from outside ends its session with the reason; a private room ignores it", () => {
    for (const [reason, said] of [["station_removed", "station_removed"], ["replaced", "replaced"], ["nonsense", "station_removed"]]) {
        const r = step(onAir(), { type: "stationGone", reason }, T0 + 5_000);
        assert.equal(r.closed, true);
        assert.deepEqual(ended(r), [["all", said]]);
    }
    const p = step(privateRoom(), { type: "hello", msg: { t: "hello" }, hostKeyOk: true, newId: "h", newToken: "t" }, T0).state;
    const r = step(p, { type: "stationGone", reason: "station_removed" }, T0);
    assert.equal(r.closed, undefined);
    assert.equal(r.state, p);
});

test("loading a song counts as playing: one picked just before the 15 minutes keeps the session going", () => {
    const paused = send(onAir(), "h", { t: "pause" }, T0 + 1_000).state;
    const loaded = send(paused, "h", { t: "load", track: TRACK }, T0 + 1_000 + PAUSE_END_MS - 1);
    assert.equal(loaded.state.pub.pausedSince, null);
    assert.equal(step(loaded.state, { type: "alarm" }, T0 + 1_000 + PAUSE_END_MS).closed, undefined);
});
