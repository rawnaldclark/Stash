import { test } from "node:test";
import assert from "node:assert/strict";
import { step, preview, EMOJI, MAX_MEMBERS } from "../src/room.js";

const T0 = 1_000_000;
const TRACK = { t: "Avril 14th", a: "Aphex Twin", d: 125000, isrc: "GBBPW0100025" };
const NEXT = { t: "Xtal", a: "Aphex Twin" };

const created = () => step(null, { type: "create", code: "ABCDEF", hostName: "Rawn", keyHash: "h" }, T0).state;
/** A hello from a new socket. `id` becomes the new member's id and `tok-<id>` its token. */
const hello = (s, id, { name = id, hostKeyOk = false, resumeToken, at = T0 } = {}) =>
    step(s, { type: "hello", msg: { t: "hello", name, resumeToken }, hostKeyOk, newId: id, newToken: `tok-${id}` }, at);
const send = (s, from, msg, at = T0, extra = {}) => step(s, { type: "msg", from, msg, ...extra }, at);
const sent = (r, t) => r.out.filter((o) => o.msg.t === t);
/** A room with host "h" and listeners "a", "b" (joined in that order), everyone connected. */
function party() {
    let s = created();
    s = hello(s, "h", { hostKeyOk: true }).state;
    s = hello(s, "a", { at: T0 + 1 }).state;
    return hello(s, "b", { at: T0 + 2 }).state;
}

// ── Task 1: create, join, host key, resume, cap, ping ─────────────────────────

test("a new room is empty and closes 5 minutes later unless someone joins", () => {
    const r = step(null, { type: "create", code: "ABCDEF", hostName: "Rawn", keyHash: "h" }, T0);
    assert.equal(r.state.host, null);
    assert.equal(r.alarmAt, T0 + 5 * 60_000);
    assert.equal(step(r.state, { type: "create", code: "ABCDEF", keyHash: "x" }, T0).reject.code, 409);
});

test("the first hello with the host key becomes host and gets a private welcome", () => {
    const r = hello(created(), "h", { hostKeyOk: true });
    assert.equal(r.bind, "h");
    const [welcome] = sent(r, "welcome");
    assert.equal(welcome.to, "h");
    assert.equal(welcome.msg.memberId, "h");
    assert.equal(welcome.msg.token, "tok-h");
    assert.equal(welcome.msg.state.host, "h");
    const json = JSON.stringify(welcome.msg.state);
    assert.ok(!json.includes("tok-h") && !json.includes("keyHash"), "state never carries tokens or the key hash");
    assert.equal(sent(r, "members")[0].to, "all");
});

test("without the key you are a listener, and a later key can't take a claimed room", () => {
    let s = hello(created(), "a").state;
    assert.equal(s.host, null);
    s = hello(s, "h", { hostKeyOk: true }).state;
    assert.equal(s.host, "h");
    s = hello(s, "x", { hostKeyOk: true }).state;
    assert.equal(s.host, "h");
});

test("the room holds 10 members and turns the 11th away", () => {
    let s = created();
    for (let i = 0; i < MAX_MEMBERS; i++) s = hello(s, `m${i}`).state;
    const r = hello(s, "late");
    assert.deepEqual(r.reject, { code: 4409, reason: "full" });
    assert.equal(r.state, s);
    assert.equal(preview(s).full, true);
});

test("a resume token rejoins the same slot; someone else's memberId does not", () => {
    let s = party();
    s = step(s, { type: "close", from: "a" }, T0 + 10).state;
    const back = hello(s, "a2", { resumeToken: "tok-a", at: T0 + 20 });
    assert.equal(back.bind, "a");
    assert.equal(back.state.members.length, 3);
    const thief = hello(s, "t", { resumeToken: "a", at: T0 + 20 });
    assert.equal(thief.bind, "t", "a bare memberId is just a new member");
});

test("ping gets a pong with the room clock, to the sender only, and changes nothing", () => {
    const s = party();
    const r = send(s, "a", { t: "ping", c: 42 }, T0 + 500);
    assert.deepEqual(r.out, [{ to: "a", msg: { t: "pong", c: 42, r: T0 + 500 } }]);
    assert.equal(r.state, s);
});

test("strangers and listeners can't use host commands", () => {
    const s = party();
    assert.equal(send(s, "nobody", { t: "play" }).state, s);
    assert.equal(send(s, "a", { t: "load", track: TRACK, positionMs: 0, queue: [] }).state, s);
    assert.equal(send(s, "a", { t: "constructor" }).state, s);
});

test("a closed socket leaves the member list; the last one out starts the 5 minute close", () => {
    let s = party();
    let r = step(s, { type: "close", from: "a" }, T0 + 10);
    assert.deepEqual(sent(r, "members")[0].msg.members.map((m) => m.id), ["h", "b"]);
    s = step(r.state, { type: "close", from: "b" }, T0 + 20).state;
    r = step(s, { type: "close", from: "h" }, T0 + 30);
    assert.equal(r.state.lastLeftAt, T0 + 30);
    assert.equal(r.alarmAt, T0 + 10 + 60_000, "the earliest timer is a's 60 s grace");
});

// ── Task 2: the ready handshake ───────────────────────────────────────────────

const loaded = (s = party(), at = T0 + 100) => send(s, "h", { t: "load", track: TRACK, positionMs: 30_000, queue: [NEXT] }, at);
const ready = (s, from, at = T0 + 200, trackKey = 1) => send(s, from, { t: "status", status: "ready", trackKey }, at);

test("load broadcasts prepare with an 8 s deadline, marks everyone buffering and sets the alarm", () => {
    const r = loaded();
    const [prep] = sent(r, "prepare");
    assert.equal(prep.to, "all");
    assert.deepEqual(prep.msg, { t: "prepare", trackKey: 1, track: TRACK, positionMs: 30_000, deadlineMs: T0 + 100 + 8_000 });
    assert.ok(r.state.members.every((m) => m.status === "buffering"));
    assert.deepEqual(r.state.queue, [NEXT]);
    assert.equal(r.alarmAt, T0 + 100 + 8_000);
});

test("when every connected phone is ready, playback starts 500 ms later for everyone", () => {
    let s = loaded().state;
    s = ready(s, "h").state;
    s = ready(s, "a").state;
    const r = ready(s, "b", T0 + 300);
    const [tl] = sent(r, "timeline");
    assert.deepEqual({ ...tl.msg, rev: 0 }, { t: "timeline", rev: 0, trackKey: 1, positionMs: 30_000, atRoomMs: T0 + 800, playing: true });
    assert.equal(r.state.phase.kind, "playing");
    assert.equal(r.state.members.find((m) => m.id === "b").status, "ok");
});

test("a ready for an earlier song doesn't count, and an unavailable phone isn't waited for", () => {
    let s = loaded().state;
    s = ready(s, "h").state;
    s = ready(s, "a").state;
    assert.equal(ready(s, "b", T0 + 300, 0).state, s, "stale trackKey ignored");
    const r = send(s, "b", { t: "status", status: "unavailable", trackKey: 1 }, T0 + 300);
    assert.equal(sent(r, "timeline").length, 1);
});

test("the deadline alarm starts playback without the slow phone", () => {
    const s = ready(loaded().state, "h").state;
    const early = step(s, { type: "alarm" }, T0 + 5_000);
    assert.equal(early.state, s);
    const r = step(s, { type: "alarm" }, T0 + 100 + 8_000);
    const [tl] = sent(r, "timeline");
    assert.equal(tl.msg.atRoomMs, T0 + 8_100 + 500);
    assert.equal(tl.msg.playing, true);
});

// ── Task 3: play, pause, seek ─────────────────────────────────────────────────

/** Loaded, everyone ready at T0 + 300: playing from 30 s in since T0 + 800. */
function playing() {
    let s = loaded().state;
    for (const id of ["h", "a", "b"]) s = ready(s, id, T0 + 300).state;
    return s;
}

test("pause freezes the position the song has reached", () => {
    const r = send(playing(), "h", { t: "pause" }, T0 + 10_800);
    assert.deepEqual(r.state.timeline, { positionMs: 40_000, atRoomMs: T0 + 10_800, playing: false });
    assert.equal(sent(r, "timeline")[0].msg.playing, false);
});

test("play and a seek while playing start 400 ms ahead so nobody arrives late", () => {
    const s = send(playing(), "h", { t: "pause" }, T0 + 10_800).state;
    let r = send(s, "h", { t: "play" }, T0 + 20_000);
    assert.deepEqual(r.state.timeline, { positionMs: 40_000, atRoomMs: T0 + 20_400, playing: true });
    r = send(r.state, "h", { t: "seek", positionMs: 90_000 }, T0 + 30_000);
    assert.deepEqual(r.state.timeline, { positionMs: 90_000, atRoomMs: T0 + 30_400, playing: true });
});

test("a seek while paused only moves the position; transport during the handshake is ignored", () => {
    const paused = send(playing(), "h", { t: "pause" }, T0 + 10_800).state;
    const r = send(paused, "h", { t: "seek", positionMs: 5_000 }, T0 + 11_000);
    assert.deepEqual(r.state.timeline, { positionMs: 5_000, atRoomMs: T0 + 11_000, playing: false });
    const preparing = loaded().state;
    assert.equal(send(preparing, "h", { t: "play" }).state, preparing);
    assert.equal(send(preparing, "h", { t: "seek", positionMs: 1 }).state, preparing);
    assert.equal(send(playing(), "h", { t: "seek", positionMs: -5 }).out.length, 0);
});

// ── Task 4: queue, suggestions, reactions ─────────────────────────────────────

test("the host replaces the queue; bad songs are dropped and it is capped at 200", () => {
    const long = Array.from({ length: 250 }, (_, i) => ({ t: `T${i}`, a: "A" }));
    const r = send(party(), "h", { t: "queue", queue: [{ t: "", a: "x" }, ...long] });
    assert.equal(r.state.queue.length, 200);
    assert.equal(r.state.queue[0].t, "T0");
});

test("a listener may have 3 suggestions waiting; the host's own are ignored", () => {
    let s = party();
    for (let i = 0; i < 4; i++) s = send(s, "a", { t: "suggest", track: { t: `S${i}`, a: "A" } }, T0, { newId: `s${i}` }).state;
    assert.deepEqual(s.suggestions.map((x) => x.id), ["s0", "s1", "s2"]);
    assert.equal(send(s, "h", { t: "suggest", track: NEXT }, T0, { newId: "s9" }).state, s);
});

test("adding a suggestion queues it and tells the host; dismissing just removes it", () => {
    let s = send(party(), "a", { t: "suggest", track: NEXT }, T0, { newId: "s1" }).state;
    s = send(s, "b", { t: "suggest", track: TRACK }, T0, { newId: "s2" }).state;
    const r = send(s, "h", { t: "suggestion", id: "s1", action: "add" });
    assert.deepEqual(r.state.queue, [NEXT]);
    assert.deepEqual(sent(r, "suggestions")[0].msg.suggestions.map((x) => x.id), ["s2"]);
    assert.equal(sent(r, "state")[0].to, "h");
    const d = send(r.state, "h", { t: "suggestion", id: "s2", action: "dismiss" });
    assert.deepEqual(d.state.queue, [NEXT]);
    assert.equal(d.state.suggestions.length, 0);
});

test("reactions: one of the six, at most one per second per member", () => {
    const s = party();
    const r = send(s, "a", { t: "react", emoji: EMOJI[1] }, T0);
    assert.deepEqual(sent(r, "reaction")[0], { to: "all", msg: { t: "reaction", from: "a", emoji: EMOJI[1] } });
    assert.equal(send(r.state, "a", { t: "react", emoji: EMOJI[0] }, T0 + 999).out.length, 0);
    assert.equal(send(r.state, "a", { t: "react", emoji: EMOJI[0] }, T0 + 1_000).out.length, 1);
    assert.equal(send(s, "a", { t: "react", emoji: "x" }).out.length, 0);
});
