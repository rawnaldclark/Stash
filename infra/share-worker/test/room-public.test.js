import { test } from "node:test";
import assert from "node:assert/strict";
import {
    step, preview, MAX_MEMBERS, MAX_PUBLIC_MEMBERS, PUBLIC_V, PREPARE_MS, HOST_WAIT_CAP_MS, PAUSE_END_MS, HANDOVER_MS, MAX_AGE_MS,
    MAX_QUEUE_CHARS, LIVE_BEAT_MS, LIVE_GAP_MS, nextAlarm,
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
/** Runs the room's alarms from [r], each at the time it asked for, until it closes or the next is after [until]. */
function alarmsFrom(r, until = Infinity) {
    const fired = [];
    while (!r.closed && r.alarmAt <= until) {
        r = step(r.state, { type: "alarm" }, r.alarmAt);
        fired.push(r);
    }
    return fired;
}

test("paused 15 minutes, a session ends; playing again before that keeps it going", () => {
    const paused = send(onAir(), "h", { t: "pause" }, T0 + 1_000);
    assert.equal(paused.state.pub.pausedSince, T0 + 1_000);
    // The alarms it sets (the live directory's writes among them) end it at the 15 minutes exactly.
    const fired = alarmsFrom(paused);
    assert.equal(fired.at(-1).closed, true);
    assert.deepEqual(ended(fired.at(-1)), [["all", "paused"]]);
    assert.equal(fired.at(-2).alarmAt, T0 + 1_000 + PAUSE_END_MS);
    assert.ok(fired.filter((r) => r.live?.upsert).length >= 14, "the directory heard from it every minute meanwhile");
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

// ── Task 15: requests ─────────────────────────────────────────────────────────

const A = { t: "Pink + White", a: "Frank Ocean" };
const B = { t: "Redbone", a: "Childish Gambino" };
const C = { t: "Nights", a: "Frank Ocean" };
/** [s] with member [id] made a co-host, as `cohost` does (task 16). */
function withCohost(s, id) {
    const c = structuredClone(s);
    c.pub.cohosts.push(c.members.find((m) => m.id === id).kh);
    return c;
}
const request = (s, from, track, id, at = T0) => send(s, from, { t: "request", track }, at, { newId: id });
/** Who got a `requests` frame, and which ids it held. */
const views = (r) => Object.fromEntries(sent(r, "requests").map((o) => [o.to, o.msg.requests.map((x) => x.id)]));

test("a listener's request waits, seen by the host and co-hosts, and by its requester alone", () => {
    const s = withCohost(hello(session(), "c", { at: T0 + 3 }).state, "b");
    const r = request(s, "a", A, "r1", T0 + 10);
    assert.deepEqual(sent(r, "requestAnswer").map((o) => [o.to, o.msg.state]), [["a", "waiting"]]);
    assert.deepEqual(views(r), { h: ["r1"], b: ["r1"], a: ["r1"] });
    assert.deepEqual(sent(r, "requests").find((o) => o.to === "h").msg.requests, [{ id: "r1", from: "a", track: A }]);
    const r2 = request(r.state, "c", B, "r2", T0 + 11);
    assert.deepEqual(views(r2), { h: ["r1", "r2"], b: ["r1", "r2"], c: ["r2"] });
    assert.deepEqual(sent(r2, "suggestions"), [], "never the old suggestions broadcast");
    const late = hello(r2.state, "d", { at: T0 + 12 });
    assert.deepEqual(sent(late, "welcome")[0].msg.state.suggestions, [], "the state everyone gets has none");
    assert.deepEqual(views(late), { d: [] });
});

test("the host and co-hosts add songs: a request from them does nothing", () => {
    const s = withCohost(session(), "b");
    for (const who of ["h", "b"]) {
        const r = request(s, who, A, "r1");
        assert.equal(r.state, s, who);
        assert.deepEqual(r.out, [], who);
    }
});

test("a 4th waiting from one listener, or a 31st in all, is full", () => {
    let s = session();
    for (let i = 0; i < 3; i++) s = request(s, "a", { t: `Song ${i}`, a: "A" }, `a${i}`).state;
    const fourth = request(s, "a", { t: "Song 3", a: "A" }, "a3");
    assert.deepEqual(fourth.out, [{ to: "a", msg: { t: "requestAnswer", state: "full" } }]);
    assert.equal(fourth.state, s);
    let t = hello(pub(), "h", { host: true }).state;
    for (let i = 0; i < 11; i++) t = hello(t, `m${i}`).state;
    for (let i = 0; i < 10; i++) for (let j = 0; j < 3; j++) t = request(t, `m${i}`, { t: `S${i}-${j}`, a: "A" }, `m${i}-${j}`).state;
    assert.equal(t.suggestions.length, 30);
    assert.equal(sent(request(t, "m10", A, "late"), "requestAnswer")[0].msg.state, "full");
});

test("taken requests land where Play next and Add to queue put them, as their requester's", () => {
    let s = onAir();
    s = send(s, "h", { t: "queue", queue: [{ t: "Album 1", a: "Host" }, { t: "Album 2", a: "Host" }], qv: s.qv }).state;
    s = request(s, "a", A, "r1").state;
    s = request(s, "b", B, "r2").state;
    let r = send(s, "h", { t: "requestAction", id: "r1", action: "queue" });
    r = send(r.state, "h", { t: "requestAction", id: "r2", action: "next" });
    assert.deepEqual(r.state.queue.map((t) => [t.t, t.by]), [["Redbone", "b"], ["Pink + White", "a"], ["Album 1", "h"], ["Album 2", "h"]]);
    assert.equal(sent(r, "queue")[0].to, "all");
    assert.deepEqual(r.state.suggestions, []);
    assert.deepEqual(views(r), { h: [], b: [] });
});

test("a pass reaches its requester alone, saying who passed", () => {
    const s = request(withCohost(session(), "b"), "a", A, "r1").state;
    const r = send(s, "b", { t: "requestAction", id: "r1", action: "pass" });
    assert.deepEqual(sent(r, "requestPassed"), [{ to: "a", msg: { t: "requestPassed", track: A, by: "b" } }]);
    assert.deepEqual(sent(r, "queue"), []);
    assert.deepEqual(r.state.suggestions, []);
    assert.deepEqual(views(r), { h: [], b: [], a: [] });
});

test("a listener takes back their own waiting request, and the host, co-hosts and they see it gone", () => {
    const s = request(request(withCohost(session(), "b"), "a", A, "r1").state, "a", B, "r2").state;
    const r = send(s, "a", { t: "requestCancel", id: "r1" });
    assert.deepEqual(r.state.suggestions.map((x) => x.id), ["r2"]);
    assert.deepEqual(views(r), { h: ["r2"], b: ["r2"], a: ["r2"] });
    assert.equal(r.state.rev, s.rev + 1);
    // Their place among the 3 a listener may have waiting is free again.
    const again = request(request(r.state, "a", { t: "S3", a: "A" }, "r3").state, "a", { t: "S4", a: "A" }, "r4");
    assert.deepEqual(sent(again, "requestAnswer").map((o) => o.msg.state), ["waiting"]);
});

test("nobody takes back someone else's request, one that isn't there, or one already taken", () => {
    const s = request(withCohost(hello(session(), "c", { at: T0 + 3 }).state, "b"), "a", A, "r1").state;
    for (const [from, id] of [["c", "r1"], ["h", "r1"], ["b", "r1"], ["a", "nope"], ["a", 7]]) {
        const r = send(s, from, { t: "requestCancel", id });
        assert.equal(r.state, s, `${from} ${id}`);
        assert.deepEqual(r.out, []);
    }
    const taken = send(s, "h", { t: "requestAction", id: "r1", action: "queue" }).state;
    const late = send(taken, "a", { t: "requestCancel", id: "r1" });
    assert.equal(late.state, taken);
    assert.equal(late.state.queue.length, taken.queue.length); // it stays in the queue: Unqueue is how that's taken back
});

test("listeners can't act on requests, and nobody can on one that isn't there", () => {
    const s = request(session(), "a", A, "r1").state;
    for (const [from, msg] of [["b", { id: "r1", action: "next" }], ["h", { id: "nope", action: "next" }], ["h", { id: "r1", action: "play" }]]) {
        const r = send(s, from, { t: "requestAction", ...msg });
        assert.equal(r.state, s);
        assert.deepEqual(r.out, []);
    }
});

test("Auto-accept lets the waiting requests in, in order, and each later one straight in", () => {
    let s = onAir();
    s = request(s, "a", A, "r1").state;
    s = request(s, "b", B, "r2").state;
    s = request(s, "a", C, "r3").state;
    const r = send(s, "h", { t: "autoAccept", on: true });
    assert.deepEqual(r.state.queue.map((t) => [t.t, t.by]), [["Pink + White", "a"], ["Redbone", "b"], ["Nights", "a"]]);
    assert.deepEqual(r.state.suggestions, []);
    assert.equal(sent(r, "session")[0].msg.autoAccept, true);
    assert.deepEqual(views(r), { h: [], a: [], b: [] });
    const next = request(r.state, "b", { t: "Ivy", a: "Frank Ocean" }, "r4");
    assert.deepEqual(sent(next, "requestAnswer")[0].msg.state, "queued");
    assert.equal(next.state.queue.at(-1).t, "Ivy");
    assert.equal(send(r.state, "a", { t: "autoAccept", on: false }).state, r.state, "the host's switch alone");
});

test("Auto-accept holds a listener to 20 songs in the queue, as a guest's adds are; past that the request waits", () => {
    let s = send(onAir(), "h", { t: "autoAccept", on: true }).state;
    for (let i = 0; i < 20; i++) s = request(s, "a", { t: `S${i}`, a: "A" }, `r${i}`).state;
    assert.equal(s.queue.length, 20);
    const r = request(s, "a", { t: "S20", a: "A" }, "r20");
    assert.equal(sent(r, "requestAnswer")[0].msg.state, "waiting");
    const taken = send(r.state, "h", { t: "requestAction", id: "r20", action: "queue" });
    assert.equal(taken.state.queue.length, 21, "the host's own choice isn't held to that");
});

test("a request the queue has no room left for stays waiting, and the one taking it is told", () => {
    const longSong = (t) => ({ t: `${t}-${"x".repeat(480)}`, a: "a".repeat(490), al: "b".repeat(490) });
    let s = onAir();
    s = send(s, "h", { t: "add", tracks: Array.from({ length: 200 }, (_, i) => longSong(`H${i}`)), next: false }).state;
    s = request(s, "a", longSong("req"), "r1").state;
    const r = send(s, "h", { t: "requestAction", id: "r1", action: "next" });
    assert.deepEqual(r.out, [{ to: "h", msg: { t: "refused", what: "request", reason: "queue_full" } }]);
    assert.equal(r.state, s);
    assert.ok(JSON.stringify(s.queue).length <= MAX_QUEUE_CHARS);
});

test("a requester gone 60 s takes their requests with them, and the host and co-hosts hear", () => {
    let s = request(withCohost(session(), "b"), "a", A, "r1").state;
    s = step(s, { type: "close", from: "a" }, T0 + 10).state;
    const r = step(s, { type: "alarm" }, T0 + 10 + HANDOVER_MS);
    assert.deepEqual(r.state.suggestions, []);
    assert.deepEqual(views(r), { h: [], b: [] });
    assert.deepEqual(sent(r, "suggestions"), []);
});

test("in a session suggestions are off, and a listener's add is answered with nothing added", () => {
    const s = session();
    assert.equal(send(s, "a", { t: "suggest", track: A }, T0, { newId: "x" }).state, s);
    const add = send(s, "a", { t: "add", tracks: [A], next: true }, T0, { newId: "y" });
    assert.equal(add.state, s);
    assert.deepEqual(add.out, [{ to: "a", msg: { t: "added", by: "a", next: true, count: 0 } }]);
    assert.equal(send(s, "h", { t: "add", tracks: [A], next: true }).state.queue[0].t, A.t, "the host adds straight in");
});

test("a private room knows nothing of requests or Auto-accept", () => {
    let p = privateRoom();
    p = step(p, { type: "hello", msg: { t: "hello", v: 2 }, hostKeyOk: true, newId: "h", newToken: "th" }, T0).state;
    p = step(p, { type: "hello", msg: { t: "hello", v: 2 }, hostKeyOk: false, newId: "g", newToken: "tg" }, T0).state;
    for (const [from, msg] of [["g", { t: "request", track: A }], ["h", { t: "requestAction", id: "x", action: "next" }], ["h", { t: "autoAccept", on: true }]]) {
        const r = send(p, from, msg, T0, { newId: "n" });
        assert.equal(r.state, p, msg.t);
        assert.deepEqual(r.out, [], msg.t);
    }
});

// ── Task 16: co-hosts ─────────────────────────────────────────────────────────

const cohost = (s, memberId, on = true) => send(s, "h", { t: "cohost", memberId, on });

test("the host makes a listener a co-host and back; everyone hears, and their view of the requests follows", () => {
    let s = request(session(), "b", A, "r1").state;
    const made = cohost(s, "a");
    assert.deepEqual(sent(made, "session")[0].msg.cohosts, ["a"]);
    assert.deepEqual(views(made), { a: ["r1"] }, "now every request");
    assert.deepEqual(made.state.pub.cohosts, [kh("a")], "kept by key hash");
    const unmade = cohost(made.state, "a", false);
    assert.deepEqual(sent(unmade, "session")[0].msg.cohosts, []);
    assert.deepEqual(views(unmade), { a: [] }, "back to their own");
    assert.equal(cohost(unmade.state, "a", false).state, unmade.state, "already not one: nothing");
    assert.equal(cohost(s, "h").state, s, "never the host");
    assert.equal(send(s, "a", { t: "cohost", memberId: "b", on: true }).state, s, "the host's call alone");
});

test("a 4th co-host is refused", () => {
    let s = hello(hello(session(), "c", { at: T0 + 3 }).state, "d", { at: T0 + 4 }).state;
    for (const id of ["a", "b", "c"]) s = cohost(s, id).state;
    const r = cohost(s, "d");
    assert.deepEqual(r.out, [{ to: "h", msg: { t: "refused", what: "cohost", reason: "max_cohosts" } }]);
    assert.equal(r.state, s);
});

test("a co-host who comes back is still one, even on a new socket without their token; gone 60 s, the place is free", () => {
    let s = cohost(session(), "a").state;
    s = step(s, { type: "close", from: "a" }, T0 + 10).state;
    const back = hello(s, "a2", { listenerHash: kh("a"), at: T0 + 20 });
    assert.deepEqual(sent(back, "session")[0].msg.cohosts, ["a2"]);
    const kept = step(back.state, { type: "alarm" }, T0 + 10 + HANDOVER_MS).state;
    assert.deepEqual(kept.pub.cohosts, [kh("a")], "the old slot went, the key hash is still held");
    s = step(kept, { type: "close", from: "a2" }, T0 + 100_000).state;
    const freed = step(s, { type: "alarm" }, T0 + 100_000 + HANDOVER_MS).state;
    assert.deepEqual(freed.pub.cohosts, []);
    assert.equal(sent(hello(freed, "a3", { listenerHash: kh("a"), at: T0 + 200_000 }), "session")[0].msg.cohosts.length, 0);
});

test("co-hosts add straight in and take out any song; a listener takes out only their own", () => {
    let s = cohost(onAir(), "a").state;
    s = send(s, "h", { t: "queue", queue: [{ t: "Album 1", a: "Host" }], qv: s.qv }).state;
    s = send(s, "a", { t: "add", tracks: [A], next: true }).state;
    assert.deepEqual(s.queue.map((t) => [t.t, t.by]), [["Pink + White", "a"], ["Album 1", "h"]]);
    s = request(s, "b", B, "r1").state;
    s = send(s, "a", { t: "requestAction", id: "r1", action: "queue" }).state;
    // Songs are named as phones see them in the queue, adder and all.
    const queued = (t) => s.queue.find((q) => q.t === t);
    assert.equal(send(s, "b", { t: "unqueue", track: queued("Album 1") }).state, s, "not the host's");
    const own = send(s, "b", { t: "unqueue", track: queued("Redbone") });
    assert.ok(!own.state.queue.some((t) => t.t === "Redbone"), "their own taken request");
    const any = send(s, "a", { t: "unqueue", track: queued("Album 1") });
    assert.ok(!any.state.queue.some((t) => t.t === "Album 1"));
});

test("a co-host's Skip goes to the host's phone with the song it was for; with the host away it's refused", () => {
    const s = cohost(onAir(), "a").state;
    const r = send(s, "a", { t: "command", action: "skip" });
    assert.deepEqual(r.out, [{ to: "h", msg: { t: "command", action: "skip", from: "a", trackKey: s.trackKey } }]);
    assert.equal(r.state, s, "the room itself doesn't change: the host's phone moves it");
    const away = step(s, { type: "close", from: "h" }, T0 + 5_000).state;
    assert.deepEqual(send(away, "a", { t: "command", action: "skip" }, T0 + 6_000).out,
        [{ to: "a", msg: { t: "refused", what: "skip", reason: "host_reconnecting" } }]);
});

test("a listener's command, an unknown one, and any in a private room do nothing", () => {
    const s = cohost(onAir(), "a").state;
    for (const [from, action] of [["b", "skip"], ["a", "seek"], ["h", "skip"]]) {
        const r = send(s, from, { t: "command", action });
        assert.equal(r.state, s);
        assert.deepEqual(r.out, []);
    }
    let p = privateRoom();
    p = step(p, { type: "hello", msg: { t: "hello", v: 2 }, hostKeyOk: true, newId: "h", newToken: "th" }, T0).state;
    p = step(p, { type: "hello", msg: { t: "hello", v: 2 }, hostKeyOk: false, newId: "g", newToken: "tg" }, T0).state;
    assert.equal(send(p, "h", { t: "cohost", memberId: "g", on: true }).state, p);
    assert.deepEqual(send(p, "g", { t: "command", action: "skip" }).out, []);
});

// ── Task 17: booting and presence ─────────────────────────────────────────────

test("a booted listener is told first, then put out: slot, requests and reaction clock gone, everyone updated", () => {
    let s = request(session(), "a", A, "r1").state;
    s = send(s, "a", { t: "react", emoji: "\u{1F525}" }, T0 + 5).state;
    const r = send(s, "h", { t: "boot", memberId: "a" }, T0 + 10);
    assert.deepEqual(r.out[0], { to: "a", msg: { t: "ended", reason: "booted" } }, "told before anything else");
    assert.deepEqual(r.kick, ["a"]);
    assert.deepEqual(r.state.members.map((m) => m.id), ["h", "b"]);
    assert.deepEqual(r.state.suggestions, []);
    assert.equal(r.state.reactAt.a, undefined);
    assert.deepEqual(r.state.pub.booted, [kh("a")]);
    assert.equal(sent(r, "session")[0].msg.listeners, 1);
    assert.deepEqual(sent(r, "members")[0].msg.members.map((m) => m.id), ["h", "b"]);
    assert.deepEqual(views(r), { h: [] }, "the host's list loses their request");
});

test("a booted key stays out, by token or by key; a new key is a new listener", () => {
    const s = send(session(), "h", { t: "boot", memberId: "a" }, T0 + 10).state;
    assert.deepEqual(hello(s, "a2", { listenerHash: kh("a"), at: T0 + 20 }).reject, { code: 4403, reason: "booted" });
    assert.deepEqual(hello(s, "a3", { listenerHash: kh("a"), resumeToken: "tok-a", at: T0 + 20 }).reject, { code: 4403, reason: "booted" });
    const fresh = hello(s, "a4", { listenerHash: kh("someone-new"), resumeToken: "tok-a", at: T0 + 20 });
    assert.equal(fresh.bind, "a4", "the old token holds no slot any more");
});

test("the host can't boot a co-host (take that away first) or themselves; a listener can't boot anyone", () => {
    const s = cohost(session(), "a").state;
    const r = send(s, "h", { t: "boot", memberId: "a" });
    assert.deepEqual(r.out, [{ to: "h", msg: { t: "refused", what: "boot", reason: "cohost" } }]);
    assert.equal(r.state, s);
    assert.equal(send(s, "h", { t: "boot", memberId: "h" }).state, s);
    assert.equal(send(s, "b", { t: "boot", memberId: "a" }).state, s);
    const unmade = cohost(s, "a", false).state;
    assert.deepEqual(send(unmade, "h", { t: "boot", memberId: "a" }).kick, ["a"]);
});

test("a listener dropped but not gone can be booted too: their slot goes and their key stays out", () => {
    let s = step(session(), { type: "close", from: "a" }, T0 + 10).state;
    const r = send(s, "h", { t: "boot", memberId: "a" }, T0 + 20);
    assert.deepEqual(r.kick, ["a"]);
    assert.deepEqual(sent(r, "ended"), [], "nobody to tell");
    assert.deepEqual(hello(r.state, "a2", { resumeToken: "tok-a", listenerHash: kh("a") }).reject, { code: 4403, reason: "booted" });
});

test("the host's presence reaches everyone; nobody else sets it", () => {
    const s = session();
    const r = send(s, "h", { t: "presence", state: "away" });
    assert.deepEqual(sent(r, "session").map((o) => [o.to, o.msg.presence]), [["all", "away"]]);
    assert.equal(r.state.pub.presence, "away");
    assert.equal(send(r.state, "h", { t: "presence", state: "away" }).state, r.state, "no change, nothing sent");
    assert.equal(send(s, "h", { t: "presence", state: "asleep" }).state, s);
    assert.equal(send(s, "a", { t: "presence", state: "away" }).state, s);
});

test("a private room ignores boot and presence", () => {
    let p = privateRoom();
    p = step(p, { type: "hello", msg: { t: "hello", v: 2 }, hostKeyOk: true, newId: "h", newToken: "th" }, T0).state;
    p = step(p, { type: "hello", msg: { t: "hello", v: 2 }, hostKeyOk: false, newId: "g", newToken: "tg" }, T0).state;
    for (const msg of [{ t: "boot", memberId: "g" }, { t: "presence", state: "away" }]) {
        const r = send(p, "h", msg);
        assert.equal(r.state, p, msg.t);
        assert.equal(r.kick, undefined);
    }
});

// ── Task 18: the live directory ───────────────────────────────────────────────

/** The row session() writes at its first song, at T0 + 10. */
const ROW = (over = {}) => ({
    stationId: "STATION1", room: "ABCDEFGH", track: TRACK, listeners: 2, paused: false,
    crowd: [{ i: "H", id: "h" }, { i: "A", id: "a" }, { i: "B", id: "b" }], startedAt: T0, seenAt: T0 + 10, ...over,
});

test("a session's first song puts it in the directory: nothing before that, and its start isn't a second write", () => {
    const s = session();
    assert.equal(hello(s, "c", { at: T0 + 5 }).live, undefined, "no song, no row");
    const loaded = send(s, "h", { t: "load", track: TRACK }, T0 + 10);
    assert.deepEqual(loaded.live, { upsert: ROW() }, "the song without the room's `by`");
    assert.equal(loaded.state.pub.liveAt, T0 + 10);
    const started = send(loaded.state, "h", { t: "status", status: "ready", trackKey: loaded.state.trackKey }, T0 + 20);
    assert.equal(started.state.timeline.playing, true);
    assert.equal(started.live, undefined, "the song on its way already counted as playing");
});

test("a new song, a pause and a play each write; a join, a seek or a listener's status doesn't", () => {
    let s = onAir(); // written at T0 + 10
    const t1 = T0 + 10 + LIVE_GAP_MS;
    const joined = hello(s, "c", { at: t1 });
    assert.equal(joined.live, undefined);
    const paused = send(joined.state, "h", { t: "pause" }, t1);
    assert.deepEqual(paused.live.upsert, ROW({ listeners: 3, paused: true, seenAt: t1, crowd: [...ROW().crowd, { i: "C", id: "c" }] }));
    s = paused.state;
    const t2 = t1 + LIVE_GAP_MS;
    assert.equal(send(s, "h", { t: "seek", positionMs: 30_000 }, t2).live, undefined);
    assert.equal(send(s, "a", { t: "status", status: "buffering" }, t2).live, undefined);
    const played = send(s, "h", { t: "play" }, t2);
    assert.equal(played.live.upsert.paused, false);
    const next = send(played.state, "h", { t: "load", track: { t: "Xtal", a: "Aphex Twin" } }, t2 + LIVE_GAP_MS);
    assert.deepEqual(next.live.upsert.track, { t: "Xtal", a: "Aphex Twin" });
    assert.equal(next.live.upsert.paused, false);
});

test("with nothing changing, the alarm writes the row again each minute, and the room stores that it did", () => {
    const s = onAir();
    assert.equal(nextAlarm(s), T0 + 10 + LIVE_BEAT_MS);
    const beat = step(s, { type: "alarm" }, T0 + 10 + LIVE_BEAT_MS);
    assert.deepEqual(beat.live, { upsert: ROW({ seenAt: T0 + 10 + LIVE_BEAT_MS }) });
    assert.notEqual(beat.state, s, "a state to store, though the alarm itself changed nothing");
    assert.equal(beat.state.pub.liveAt, T0 + 10 + LIVE_BEAT_MS);
    assert.equal(beat.alarmAt, T0 + 10 + 2 * LIVE_BEAT_MS);
    assert.equal(s.pub.liveAt, T0 + 10, "the state passed in is untouched");
});

test("changes within 5 s of the last write wait for the gap's end, then go as one write of how things are", () => {
    let r = send(onAir(), "h", { t: "pause" }, T0 + 1_000);
    assert.equal(r.live, undefined);
    assert.equal(r.alarmAt, T0 + 10 + LIVE_GAP_MS);
    r = send(r.state, "h", { t: "play" }, T0 + 2_000);
    r = send(r.state, "h", { t: "pause" }, T0 + 3_000);
    assert.equal(r.live, undefined);
    const due = step(r.state, { type: "alarm" }, T0 + 10 + LIVE_GAP_MS);
    assert.equal(due.live.upsert.paused, true);
    assert.equal(due.live.upsert.seenAt, T0 + 10 + LIVE_GAP_MS);
    // A change undone inside the gap leaves nothing to write: the heartbeat's time again.
    let t = send(onAir(), "h", { t: "pause" }, T0 + 1_000).state;
    t = send(t, "h", { t: "play" }, T0 + 2_000).state;
    assert.equal(nextAlarm(t), T0 + 10 + LIVE_BEAT_MS);
});

test("a session's end takes its row down; one that never played, or whose station went, has none to take down", () => {
    const remove = { remove: { stationId: "STATION1", room: "ABCDEFGH" } };
    assert.deepEqual(send(onAir(), "h", { t: "end" }, T0 + 30).live, remove);
    const gone = step(onAir(), { type: "close", from: "h" }, T0 + 30).state;
    const left = step(gone, { type: "alarm" }, T0 + 30 + HANDOVER_MS);
    assert.deepEqual(ended(left), [["all", "host_left"]]);
    assert.deepEqual(left.live, remove);
    assert.equal(send(session(), "h", { t: "end" }).live, undefined);
    assert.equal(step(onAir(), { type: "stationGone", reason: "replaced" }, T0 + 30).live, undefined);
});

test("the directory's faces: up to 4, the host's first, then by who joined; initials and member ids, never names", () => {
    let s = pub();
    s = hello(s, "a", { name: "émile", at: T0 + 1 }).state;
    s = hello(s, "b", { name: "🇯🇵 Kenji", at: T0 + 2 }).state;
    s = hello(s, "c", { name: "", at: T0 + 3 }).state;
    s = hello(s, "d", { name: "sam", at: T0 + 4 }).state;
    s = hello(s, "h", { host: true, name: "rawn", at: T0 + 5 }).state;
    const r = send(s, "h", { t: "load", track: TRACK }, T0 + 10);
    assert.deepEqual(r.live.upsert.crowd, [{ i: "R", id: "h" }, { i: "É", id: "a" }, { i: "🇯🇵", id: "b" }, { i: null, id: "c" }]);
    assert.equal(r.live.upsert.listeners, 4);
    const wire = JSON.stringify(r.live);
    for (const secret of ["rawn", "Kenji", "sam", kh("a"), "tok-a"]) assert.ok(!wire.includes(secret), secret);
    // Someone dropped isn't a face at the next write, nor counted.
    const dropped = step(r.state, { type: "close", from: "a" }, T0 + 20).state;
    const beat = step(dropped, { type: "alarm" }, T0 + 10 + LIVE_BEAT_MS);
    assert.deepEqual(beat.live.upsert.crowd.map((f) => f.id), ["h", "b", "c", "d"]);
    assert.equal(beat.live.upsert.listeners, 3);
});

test("a private room never writes to the directory, nor wakes for it", () => {
    let p = privateRoom();
    p = step(p, { type: "hello", msg: { t: "hello", v: 2 }, hostKeyOk: true, newId: "h", newToken: "th" }, T0).state;
    const loaded = send(p, "h", { t: "load", track: TRACK }, T0 + 10);
    assert.equal(loaded.live, undefined);
    const playing = send(loaded.state, "h", { t: "status", status: "ready", trackKey: 1 }, T0 + 20);
    assert.equal(nextAlarm(playing.state), T0 + MAX_AGE_MS);
    assert.equal(step(playing.state, { type: "alarm" }, T0 + 10 + LIVE_BEAT_MS).live, undefined);
    assert.equal(send(playing.state, "h", { t: "end" }).live, undefined);
});

// ── The load check's finding: a reaction doesn't rewrite a session ────────────

test("in a session a reaction reaches everyone and leaves the room as it was: nothing to store", () => {
    const s = session();
    const r = send(s, "a", { t: "react", emoji: "\u{1F525}" }, T0 + 5);
    assert.deepEqual(r.out, [{ to: "all", msg: { t: "reaction", from: "a", emoji: "\u{1F525}" } }]);
    assert.equal(r.state, s, "the same state: the shell stores nothing");
    assert.deepEqual(send(s, "a", { t: "react", emoji: "x" }).out, [], "still one of the six");
    // The once-a-second limit is the shell's (src/listen-room.js) in a session: the room passes each one on.
    assert.equal(send(r.state, "a", { t: "react", emoji: "\u{1F525}" }, T0 + 6).out.length, 1);
});
