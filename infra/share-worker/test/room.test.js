import { test } from "node:test";
import assert from "node:assert/strict";
import {
    step, preview, EMOJI, MAX_MEMBERS, MAX_QUEUE_CHARS, PREPARE_MS, HOST_PREPARE_MS, HOST_WAIT_CAP_MS,
} from "../src/room.js";

const T0 = 1_000_000;
const TRACK = { t: "Avril 14th", a: "Aphex Twin", d: 125000, isrc: "GBBPW0100025" };
const NEXT = { t: "Xtal", a: "Aphex Twin" };

const created = () => step(null, { type: "create", code: "ABCDEF", hostName: "Rawn", keyHash: "h" }, T0).state;
/** A hello from a new socket. `id` becomes the new member's id and `tok-<id>` its token. */
const hello = (s, id, { name = id, hostKeyOk = false, resumeToken, at = T0, v, guestsAdd } = {}) =>
    step(s, { type: "hello", msg: { t: "hello", name, resumeToken, v, guestsAdd }, hostKeyOk, newId: id, newToken: `tok-${id}` }, at);
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
    assert.deepEqual(prep.msg, { t: "prepare", trackKey: 1, track: { ...TRACK, by: "h" }, positionMs: 30_000, deadlineMs: T0 + 100 + 8_000, by: "h" });
    assert.ok(r.state.members.every((m) => m.status === "buffering"));
    assert.deepEqual(r.state.queue, [{ ...NEXT, by: "h" }]);
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

test("a status that changes nothing writes nothing and broadcasts nothing", () => {
    const s = ready(loaded().state, "h").state;
    const again = ready(s, "h", T0 + 300);
    assert.equal(again.state, s);
    assert.deepEqual(again.out, []);
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
    assert.deepEqual(r.state.queue, [{ ...NEXT, by: "a" }]);
    assert.deepEqual(sent(r, "suggestions")[0].msg.suggestions.map((x) => x.id), ["s2"]);
    assert.equal(sent(r, "queue")[0].to, "all");
    const d = send(r.state, "h", { t: "suggestion", id: "s2", action: "dismiss" });
    assert.deepEqual(d.state.queue, [{ ...NEXT, by: "a" }]);
    assert.equal(d.state.suggestions.length, 0);
});

test("accepted suggestions play next, in the order the host accepted them", () => {
    const [A, B, C] = ["A1", "B2", "C3"].map((t) => ({ t, a: "Friend" }));
    let s = send(party(), "h", { t: "queue", queue: [NEXT, TRACK] }).state;
    s = send(s, "a", { t: "suggest", track: A }, T0, { newId: "sa" }).state;
    s = send(s, "a", { t: "suggest", track: B }, T0, { newId: "sb" }).state;
    s = send(s, "h", { t: "suggestion", id: "sa", action: "add" }).state;
    s = send(s, "h", { t: "suggestion", id: "sb", action: "add" }).state;
    assert.deepEqual(s.queue, [{ ...A, by: "a" }, { ...B, by: "a" }, { ...NEXT, by: "h" }, { ...TRACK, by: "h" }]);
    // A plays and the host's app resends the rest of the queue; C, accepted now, still waits behind B.
    s = send(s, "h", { t: "load", track: A, positionMs: 0, queue: s.queue.slice(1) }).state;
    s = send(s, "b", { t: "suggest", track: C }, T0, { newId: "sc" }).state;
    s = send(s, "h", { t: "suggestion", id: "sc", action: "add" }).state;
    assert.deepEqual(s.queue, [{ ...B, by: "a" }, { ...C, by: "b" }, { ...NEXT, by: "h" }, { ...TRACK, by: "h" }]);
});

test("songs carry who added them; a listener can't claim someone else's pick", () => {
    let s = send(party(), "h", { t: "load", track: TRACK, positionMs: 0, queue: [NEXT] }).state;
    assert.equal(s.track.by, "h");
    assert.equal(s.queue[0].by, "h");
    s = send(s, "h", { t: "queue", queue: [NEXT, { ...TRACK, by: "a" }] }).state;
    assert.deepEqual(s.queue.map((x) => x.by), ["h", "a"]); // kept when the host's app sends it back
    s = send(s, "a", { t: "suggest", track: { t: "S", a: "A", by: "h" } }, T0, { newId: "s1" }).state;
    s = send(s, "h", { t: "suggestion", id: "s1", action: "add" }).state;
    assert.equal(s.queue[0].by, "a");
    assert.equal(send(party(), "h", { t: "queue", queue: [{ ...NEXT, by: "x".repeat(17) }] }).state.queue.length, 0);
});

test("a song's cover link comes along only from a known cover host", () => {
    const cover = "https://lastfm-img.freetls.fastly.net/i/u/770x0/ab12.jpg";
    const s = send(party(), "h", { t: "queue", queue: [
        { ...NEXT, art: cover },
        { ...TRACK, art: "https://tracker.example/pixel.gif" },
        { t: "C", a: "A", art: "http://i.ytimg.com/vi/x/maxresdefault.jpg" },
    ] }).state;
    assert.equal(s.queue[0].art, cover);
    // Dropped, never fatal: the song stays, only the link goes.
    assert.deepEqual(s.queue.slice(1).map((x) => ["art" in x, x.t]), [[false, TRACK.t], [false, "C"]]);
    let t = send(party(), "a", { t: "suggest", track: { ...NEXT, art: cover } }, T0, { newId: "s1" }).state;
    t = send(t, "h", { t: "suggestion", id: "s1", action: "add" }).state;
    assert.equal(t.queue[0].art, cover);
});

test("prepare says who loaded and why; timelines say who played, paused or seeked", () => {
    const r = send(party(), "h", { t: "load", track: TRACK, positionMs: 0, queue: [], why: "skip" });
    assert.equal(sent(r, "prepare")[0].msg.by, "h");
    assert.equal(sent(r, "prepare")[0].msg.why, "skip");
    assert.equal("why" in sent(send(party(), "h", { t: "load", track: TRACK, positionMs: 0, queue: [], why: "x" }), "prepare")[0].msg, false);
    let s = r.state;
    for (const m of ["h", "a", "b"]) s = ready(s, m).state;
    const p = send(s, "h", { t: "pause" }, T0 + 1_000);
    assert.equal(sent(p, "timeline")[0].msg.by, "h");
    assert.equal(sent(send(p.state, "h", { t: "play" }, T0 + 2_000), "timeline")[0].msg.by, "h");
    assert.equal(sent(send(s, "h", { t: "seek", positionMs: 5_000 }, T0 + 1_000), "timeline")[0].msg.by, "h");
});

test("every queue change goes to everyone, so each phone's Up next stays current", () => {
    const l = send(party(), "h", { t: "load", track: TRACK, positionMs: 0, queue: [NEXT] });
    assert.deepEqual(sent(l, "queue")[0], { to: "all", msg: { t: "queue", queue: [{ ...NEXT, by: "h" }], qv: 1 } });
    const q = send(l.state, "h", { t: "queue", queue: [NEXT, TRACK] });
    assert.equal(sent(q, "queue")[0].to, "all");
    let s = send(q.state, "a", { t: "suggest", track: { t: "S", a: "A" } }, T0, { newId: "s1" }).state;
    const acc = send(s, "h", { t: "suggestion", id: "s1", action: "add" });
    assert.deepEqual(sent(acc, "queue")[0].msg.queue.map((x) => x.t), ["S", "Xtal", "Avril 14th"]);
    assert.equal(sent(send(s, "h", { t: "suggestion", id: "s1", action: "dismiss" }), "queue").length, 0);
});

test("reactions: one of the six, at most one per second per member", () => {
    const s = party();
    const r = send(s, "a", { t: "react", emoji: EMOJI[1] }, T0);
    assert.deepEqual(sent(r, "reaction")[0], { to: "all", msg: { t: "reaction", from: "a", emoji: EMOJI[1] } });
    assert.equal(send(r.state, "a", { t: "react", emoji: EMOJI[0] }, T0 + 999).out.length, 0);
    assert.equal(send(r.state, "a", { t: "react", emoji: EMOJI[0] }, T0 + 1_000).out.length, 1);
    assert.equal(send(s, "a", { t: "react", emoji: "x" }).out.length, 0);
});

// ── Task 5: host handover, makeHost, end, closing ─────────────────────────────

test("makeHost hands control to a connected member and tells everyone", () => {
    const r = send(party(), "h", { t: "makeHost", memberId: "b" });
    assert.equal(r.state.host, "b");
    assert.equal(sent(r, "state")[0].msg.state.host, "b");
    assert.equal(send(r.state, "h", { t: "play" }).state, r.state, "the old host is a listener now");
    assert.equal(send(party(), "h", { t: "makeHost", memberId: "ghost" }).out.length, 0);
});

test("a host gone for 60 s is replaced by the longest-joined member", () => {
    const s = step(party(), { type: "close", from: "h" }, T0 + 1_000).state;
    assert.equal(step(s, { type: "alarm" }, T0 + 60_999).state.host, "h");
    const r = step(s, { type: "alarm" }, T0 + 61_000);
    assert.equal(r.state.host, "a");
    assert.deepEqual(r.state.members.map((m) => m.id), ["a", "b"]);
    assert.equal(sent(r, "state")[0].msg.state.host, "a");
});

test("a host back within 60 s on its resume token stays host", () => {
    let s = step(party(), { type: "close", from: "h" }, T0 + 1_000).state;
    s = hello(s, "h2", { resumeToken: "tok-h", at: T0 + 30_000 }).state;
    assert.equal(step(s, { type: "alarm" }, T0 + 61_000).state.host, "h");
});

test("end tells everyone and closes the room", () => {
    const r = send(party(), "h", { t: "end" });
    assert.equal(r.closed, true);
    assert.equal(r.state, null);
    assert.deepEqual(sent(r, "ended")[0], { to: "all", msg: { t: "ended", reason: "host_ended" } });
});

test("an empty room closes 5 minutes after the last member leaves; any room closes after 12 hours", () => {
    let s = party();
    for (const id of ["h", "a", "b"]) s = step(s, { type: "close", from: id }, T0 + 1_000).state;
    s = step(s, { type: "alarm" }, T0 + 61_000).state; // grace over: the slots are pruned
    assert.equal(s.members.length, 0);
    assert.equal(step(s, { type: "alarm" }, T0 + 1_000 + 5 * 60_000).closed, true);
    assert.equal(step(party(), { type: "alarm" }, T0 + 12 * 3_600_000).closed, true);
});

test("the alarm is always the earliest pending timer", () => {
    const s = step(loaded().state, { type: "close", from: "a" }, T0 + 150).state;
    assert.equal(step(s, { type: "msg", from: "b", msg: { t: "ping", c: 1 } }, T0 + 160).alarmAt, T0 + 100 + 8_000);
});

// ── Hardening (review fixes) ──────────────────────────────────────────────────

test("prototype keys in status and emoji change nothing and never throw", () => {
    const s = loaded().state;
    for (const status of ["constructor", "__proto__", "toString", "hasOwnProperty"]) {
        const r = send(s, "a", { t: "status", status, trackKey: 1 });
        assert.equal(r.state, s, status);
        assert.doesNotThrow(() => send(r.state, "a", { t: "ping", c: 1 }));
        assert.doesNotThrow(() => structuredClone(r.state));
    }
    for (const emoji of ["constructor", "__proto__", "toString"]) {
        assert.equal(send(s, "a", { t: "react", emoji }).state, s, emoji);
    }
});

test("pruned members take their suggestions and reaction clocks with them; the room holds at most 20", () => {
    let s = party();
    let at = T0;
    for (let i = 0; i < 50; i++) {
        const id = `x${i}`;
        s = hello(s, id, { at }).state;
        for (let k = 0; k < 3; k++) s = send(s, id, { t: "suggest", track: { t: `S${i}-${k}`, a: "A" } }, at, { newId: `${id}-${k}` }).state;
        s = send(s, id, { t: "react", emoji: EMOJI[0] }, at).state;
        s = step(s, { type: "close", from: id }, at).state;
        at += 60_000;
        s = step(s, { type: "alarm" }, at).state;
        assert.ok(s.suggestions.length <= 20);
        assert.equal(s.reactAt[id], undefined);
    }
    assert.equal(s.suggestions.length, 0);
    // Seven listeners at three each would be 21: the 21st is ignored.
    s = party();
    for (let i = 0; i < 7; i++) {
        s = hello(s, `y${i}`).state;
        for (let k = 0; k < 3; k++) s = send(s, `y${i}`, { t: "suggest", track: NEXT }, T0, { newId: `y${i}-${k}` }).state;
    }
    assert.equal(s.suggestions.length, 20);
});

test("positions are bounded: absurd seeks are refused, huge loads clamp to the song or 24 h", () => {
    const p = playing();
    assert.equal(send(p, "h", { t: "seek", positionMs: 1e300 }).state, p);
    assert.equal(send(p, "h", { t: "seek", positionMs: 1.5 }).state, p);
    assert.equal(send(p, "h", { t: "seek", positionMs: 999_999_999 }, T0 + 20_000).state.timeline.positionMs, TRACK.d);
    assert.equal(loaded(party(), T0).state.timeline.positionMs, 30_000);
    const big = send(party(), "h", { t: "load", track: TRACK, positionMs: 1e12, queue: [] });
    assert.equal(big.state.timeline.positionMs, TRACK.d);
    const noD = send(party(), "h", { t: "load", track: NEXT, positionMs: 1e12, queue: [] });
    assert.equal(noD.state.timeline.positionMs, 86_400_000);
    const badD = send(party(), "h", { t: "load", track: { ...NEXT, d: 1e300 }, positionMs: 0, queue: [] });
    assert.deepEqual(badD.state.track, { ...NEXT, by: "h" }, "an out-of-range duration is dropped, the song kept");
    assert.equal(send(party(), "h", { t: "load", track: { ...NEXT, d: 0 } }).state.track.d, undefined);
});

test("a member who rejoins during the handshake is waited for", () => {
    // b was ready for the last song (status ok) when it dropped; the host loads the next one.
    let s = step(playing(), { type: "close", from: "b" }, T0 + 1_000).state;
    s = loaded(s, T0 + 2_000).state;
    s = ready(s, "h", T0 + 2_100, 2).state;
    s = hello(s, "b2", { resumeToken: "tok-b", at: T0 + 2_200 }).state;
    assert.equal(s.members.find((m) => m.id === "b").status, "buffering");
    const r = ready(s, "a", T0 + 2_300, 2);
    assert.equal(r.state.phase.kind, "preparing", "still waiting for b");
    assert.equal(sent(ready(r.state, "b", T0 + 2_400, 2), "timeline").length, 1);
});

test("a member leaving during the handshake lets it start", () => {
    let s = loaded().state;
    s = ready(s, "h").state;
    s = ready(s, "a").state;
    const r = step(s, { type: "close", from: "b" }, T0 + 300);
    assert.equal(r.state.phase.kind, "playing");
    assert.equal(sent(r, "timeline")[0].msg.playing, true);
});

test("a room left hostless gives the host to the first member back", () => {
    let s = party();
    for (const id of ["h", "a", "b"]) s = step(s, { type: "close", from: id }, T0 + 1_000).state;
    s = step(s, { type: "alarm" }, T0 + 61_000).state;
    assert.equal(s.host, null);
    const r = hello(s, "n", { at: T0 + 62_000 });
    assert.equal(r.state.host, "n");
    assert.equal(sent(r, "welcome")[0].msg.state.host, "n");
});

test("a queue message without an array changes nothing", () => {
    const s = loaded().state;
    assert.equal(send(s, "h", { t: "queue" }).state, s);
    assert.equal(send(s, "h", { t: "queue", queue: "x" }).state, s);
});

test("listeners can't makeHost, end or answer suggestions", () => {
    const s = send(party(), "a", { t: "suggest", track: NEXT }, T0, { newId: "s1" }).state;
    assert.equal(send(s, "a", { t: "makeHost", memberId: "a" }).state, s);
    const e = send(s, "b", { t: "end" });
    assert.equal(e.state, s);
    assert.notEqual(e.closed, true);
    assert.equal(send(s, "b", { t: "suggestion", id: "s1", action: "add" }).state, s);
});

test("a ping with a non-numeric clock is ignored safely", () => {
    const s = party();
    for (const c of ["42", null, {}, NaN, Infinity, undefined]) {
        const r = send(s, "a", { t: "ping", c });
        assert.equal(r.state, s);
        assert.equal(r.out.length, 0);
    }
});

// ── Guests add songs (spec 2026-11-04) ────────────────────────────────────────

const song = (t, extra = {}) => ({ t, a: "Friend", ...extra });
const ALBUM = ["A1", "A2", "A3"].map((t) => ({ t, a: "Host" }));
const titles = (s) => s.queue.map((x) => x.t);
/**
 * Host "h" and guests "a", "b" on apps that know guest adds (v 2), plus "o" on an older app (no v). The host's
 * switch is [ga]; the host has loaded TRACK with ALBUM after it, so the room is at qv 1.
 */
function room2({ ga = true } = {}) {
    let s = created();
    s = hello(s, "h", { hostKeyOk: true, v: 2, guestsAdd: ga }).state;
    s = hello(s, "a", { at: T0 + 1, v: 2, guestsAdd: true }).state;
    s = hello(s, "b", { at: T0 + 2, v: 2, guestsAdd: false }).state;
    s = hello(s, "o", { at: T0 + 3 }).state;
    return send(s, "h", { t: "load", track: TRACK, positionMs: 0, queue: ALBUM, qv: 0 }, T0 + 10).state;
}
const add = (s, from, tracks, next, extra = {}) => send(s, from, { t: "add", tracks, next }, T0 + 20, { newId: "n", ...extra });

test("the room says it knows guest adds, and whether they're on: a v2 host's switch, never an older host's", () => {
    const s = room2();
    const r = hello(s, "c", { at: T0 + 30 });
    const { state } = sent(r, "welcome")[0].msg;
    assert.equal(state.v, 2);
    assert.equal(state.guestsAdd, true);
    assert.equal(state.qv, 1);
    assert.deepEqual(state.members.map((m) => [m.id, m.v ?? null]), [["h", 2], ["a", 2], ["b", 2], ["o", null], ["c", null]]);
    assert.equal(sent(hello(party(), "c"), "welcome")[0].msg.state.guestsAdd, false, "an older host keeps approvals");
    assert.equal(sent(hello(room2({ ga: false }), "c"), "welcome")[0].msg.state.guestsAdd, false);
});

test("Play next is first come, first played, across guests, in front of the host's album", () => {
    let s = room2();
    s = add(s, "a", [song("P1")], true).state;
    s = add(s, "b", [song("P2"), song("P3")], true).state;
    s = add(s, "a", [song("P4")], true).state;
    assert.deepEqual(titles(s), ["P1", "P2", "P3", "P4", "A1", "A2", "A3"]);
    assert.deepEqual(s.queue.map((x) => x.by), ["a", "b", "b", "a", "h", "h", "h"]);
});

test("Add to queue goes after every added song, before the rest of the host's album", () => {
    let s = room2();
    s = add(s, "a", [song("Q1")], false).state;
    s = add(s, "b", [song("P1")], true).state;
    s = add(s, "a", [song("Q2")], false).state;
    s = add(s, "h", [song("P2")], true).state; // the host's own follow the same rules
    s = add(s, "h", [song("Q3")], false).state;
    assert.deepEqual(titles(s), ["P1", "P2", "Q1", "Q2", "Q3", "A1", "A2", "A3"]);
});

test("an add is stamped with its sender whatever it claims, and everyone hears who added what", () => {
    const r = add(room2(), "a", [song("X", { by: "h" }), song("Y")], true);
    assert.deepEqual(r.state.queue.slice(0, 2).map((x) => x.by), ["a", "a"]);
    assert.deepEqual(sent(r, "added"), [{ to: "all", msg: { t: "added", by: "a", next: true, count: 2, track: { ...song("X"), by: "a" } } }]);
    assert.deepEqual(sent(r, "queue")[0].msg, { t: "queue", queue: r.state.queue, qv: 2 });
});

test("a guest has at most 20 songs waiting; the host is held only by the room's 200", () => {
    const many = (p, n) => Array.from({ length: n }, (_, i) => song(`${p}${i}`));
    let r = add(room2(), "a", many("G", 25), true);
    assert.equal(r.state.queue.filter((x) => x.by === "a").length, 20);
    assert.equal(sent(r, "added")[0].msg.count, 20);
    const full = r.state;
    assert.equal(add(full, "a", [song("more")], false).state, full, "the 21st waits until one of the 20 plays");
    // One of a's songs plays: the host's next load takes it off the queue, and a may add one more.
    let s = send(full, "h", { t: "load", track: full.queue[0], positionMs: 0, queue: full.queue.slice(1), qv: full.qv }).state;
    s = add(s, "a", [song("more"), song("too many")], false).state;
    assert.equal(s.queue.filter((x) => x.by === "a").length, 20);
    assert.ok(titles(s).includes("more") && !titles(s).includes("too many"));
    r = add(room2(), "h", many("H", 25), false);
    assert.equal(r.state.queue.length, 28);
});

test("the queue holds 200: an add pushes out the end of the host's album, never another add", () => {
    const album = Array.from({ length: 200 }, (_, i) => ({ t: `A${i}`, a: "Host" }));
    let s = send(room2(), "h", { t: "queue", queue: album, qv: 1 }).state;
    s = add(s, "a", [song("P1")], true).state;
    s = add(s, "b", [song("Q1")], false).state;
    assert.equal(s.queue.length, 200);
    assert.deepEqual(titles(s).slice(0, 3), ["P1", "Q1", "A0"]);
    assert.equal(s.queue.at(-1).t, "A197");
});

test("with the switch off, or under an older host, a guest's add becomes suggestions; the host's own still goes in", () => {
    let r = add(room2({ ga: false }), "a", [song("S1"), song("S2")], true);
    assert.deepEqual(r.state.suggestions.map((x) => [x.id, x.from, x.track.t]), [["n-0", "a", "S1"], ["n-1", "a", "S2"]]);
    assert.deepEqual(titles(r.state), ALBUM.map((x) => x.t));
    assert.deepEqual(sent(r, "added").map((o) => [o.to, o.msg.count, o.msg.suggested]), [["a", 0, 2]], "only the sender hears, and nobody gets a chip");
    r = add(party(), "a", [song("S1"), song("S2"), song("S3"), song("S4")], true);
    assert.equal(r.state.suggestions.length, 3, "still 3 waiting per listener");
    r = add(room2({ ga: false }), "h", [song("H1")], true);
    assert.deepEqual(titles(r.state)[0], "H1");
});

test("an older app's Suggest goes straight in as Play next while guests can add", () => {
    let s = add(room2(), "a", [song("P1")], true).state;
    const r = send(s, "o", { t: "suggest", track: song("Old") }, T0, { newId: "s1" });
    assert.deepEqual(titles(r.state).slice(0, 2), ["P1", "Old"]);
    assert.equal(r.state.queue[1].by, "o");
    assert.equal(r.state.suggestions.length, 0);
    assert.equal(sent(r, "added")[0].msg.by, "o");
    s = send(room2({ ga: false }), "o", { t: "suggest", track: song("Old") }, T0, { newId: "s1" }).state;
    assert.deepEqual(s.suggestions.map((x) => x.id), ["s1"], "with the switch off it waits for the host, as today");
});

test("settings is the host's; turning it on lets waiting suggestions in as Play next, in the order they came", () => {
    let s = room2({ ga: false });
    s = add(s, "a", [song("S1"), song("S2")], true).state;
    s = send(s, "o", { t: "suggest", track: song("S3") }, T0, { newId: "s3" }).state;
    assert.equal(send(s, "a", { t: "settings", guestsAdd: true }).state, s);
    assert.equal(send(s, "h", { t: "settings", guestsAdd: "yes" }).state, s);
    const on = send(s, "h", { t: "settings", guestsAdd: true });
    assert.deepEqual(titles(on.state), ["S1", "S2", "S3", "A1", "A2", "A3"]);
    assert.deepEqual(on.state.queue.slice(0, 3).map((x) => x.by), ["a", "a", "o"]);
    assert.deepEqual(on.out.map((o) => o.msg.t), ["suggestions", "queue", "settings"]);
    assert.deepEqual(sent(on, "settings")[0], { to: "all", msg: { t: "settings", guestsAdd: true } });
    const off = send(on.state, "h", { t: "settings", guestsAdd: false });
    assert.deepEqual(off.out.map((o) => o.msg), [{ t: "settings", guestsAdd: false }]);
    assert.deepEqual(titles(off.state), titles(on.state));
    assert.equal(send(off.state, "h", { t: "settings", guestsAdd: false }).state, off.state, "the same again changes nothing");
});

test("turning the switch on keeps a suggestion in the tray when its guest already has 20 waiting", () => {
    let s = add(room2(), "a", Array.from({ length: 20 }, (_, i) => song(`G${i}`)), true).state;
    s = send(s, "h", { t: "settings", guestsAdd: false }).state;
    s = add(s, "a", [song("Late")], true).state;
    s = add(s, "b", [song("B1")], true).state;
    const on = send(s, "h", { t: "settings", guestsAdd: true }).state;
    assert.deepEqual(on.suggestions.map((x) => x.track.t), ["Late"]);
    assert.ok(titles(on).includes("B1") && !titles(on).includes("Late"));
});

test("the switch follows whoever hosts: handing over recomputes it", () => {
    let r = send(room2(), "h", { t: "makeHost", memberId: "o" });
    assert.equal(sent(r, "state")[0].msg.state.guestsAdd, false, "an older app as host means approvals");
    let s = add(r.state, "a", [song("S1")], true).state;
    assert.equal(s.suggestions.length, 1);
    r = send(s, "o", { t: "makeHost", memberId: "a" }); // a's own switch is on
    assert.equal(sent(r, "state")[0].msg.state.guestsAdd, true);
    assert.equal(r.state.suggestions.length, 0);
    assert.equal(r.state.queue[0].t, "S1", "waiting suggestions joined the queue with the new host");
    r = send(room2(), "h", { t: "makeHost", memberId: "b" }); // b's switch is off
    assert.equal(sent(r, "state")[0].msg.state.guestsAdd, false);
    // The (older) host gone for 60 s: the longest-joined, h, takes over again with h's switch.
    s = step(send(room2(), "h", { t: "makeHost", memberId: "o" }).state, { type: "close", from: "o" }, T0 + 1_000).state;
    r = step(s, { type: "alarm" }, T0 + 61_000);
    assert.equal(r.state.host, "h");
    assert.equal(sent(r, "state")[0].msg.state.guestsAdd, true);
});

test("a switch flipped while the host's socket was down lands with its next hello", () => {
    const s = step(room2(), { type: "close", from: "h" }, T0 + 1_000).state;
    assert.equal(add(s, "a", [song("P1")], true).state.queue[0].t, "P1", "a host away under 60 s still rules");
    const r = hello(s, "h2", { resumeToken: "tok-h", at: T0 + 2_000, v: 2, guestsAdd: false });
    assert.deepEqual(sent(r, "settings")[0].msg, { t: "settings", guestsAdd: false });
    assert.equal(sent(r, "welcome")[0].msg.state.guestsAdd, false);
});

test("a host load or queue built from an older version keeps the adds made in between", () => {
    let s = room2(); // qv 1: [A1, A2, A3]
    s = add(s, "a", [song("P1")], true).state; // qv 2
    s = add(s, "b", [song("Q1")], false).state; // qv 3
    // The song ends; the host's app hadn't heard of either add yet.
    s = send(s, "h", { t: "load", track: ALBUM[0], positionMs: 0, queue: ALBUM.slice(1), qv: 1, why: "end" }).state;
    assert.deepEqual(titles(s), ["P1", "Q1", "A2", "A3"]);
    // The host drags A3 up, from the version after that load, while a guest adds.
    const seen = s.qv;
    const view = s.queue;
    s = add(s, "a", [song("P2")], true).state;
    s = send(s, "h", { t: "queue", queue: [view[0], view[1], view[3], view[2]], qv: seen }).state;
    assert.deepEqual(titles(s), ["P1", "P2", "Q1", "A3", "A2"]);
});

test("a song the host removed knowingly stays removed; an older view brings nothing back", () => {
    let s = add(room2(), "a", [song("P1")], true).state;
    s = send(s, "h", { t: "queue", queue: ALBUM, qv: s.qv }).state; // the host saw P1 and swiped it away
    assert.deepEqual(titles(s), ["A1", "A2", "A3"]);
    assert.deepEqual(s.adds, []);
    s = send(s, "h", { t: "queue", queue: ALBUM.slice(1), qv: 1 }).state;
    assert.deepEqual(titles(s), ["A2", "A3"]);
});

test("without qv (an app from before guest adds) a host's queue is taken as sent, as today", () => {
    let s = add(room2(), "a", [song("P1")], true).state;
    s = send(s, "h", { t: "queue", queue: ALBUM }).state;
    assert.deepEqual(titles(s), ["A1", "A2", "A3"]);
    s = add(s, "a", [song("P2")], true).state;
    s = send(s, "h", { t: "load", track: TRACK, positionMs: 0, queue: [NEXT], why: "pick" }).state;
    assert.deepEqual(titles(s), [NEXT.t]);
});

test("the host starting a new album keeps everyone's adds in front of it", () => {
    let s = add(room2(), "a", [song("Q1")], false).state;
    s = add(s, "b", [song("P1")], true).state;
    const album = ["B1", "B2"].map((t) => ({ t, a: "Host" }));
    s = send(s, "h", { t: "load", track: { t: "B0", a: "Host" }, positionMs: 0, queue: album, qv: s.qv, why: "pick" }).state;
    assert.deepEqual(titles(s), ["P1", "Q1", "B1", "B2"]);
    s = add(s, "a", [song("Q2")], false).state;
    assert.deepEqual(titles(s), ["P1", "Q1", "Q2", "B1", "B2"], "they're still adds after the pick");
});

test("unqueue: a guest removes only their own songs, the host any", () => {
    const s = add(add(room2(), "a", [song("X")], true).state, "b", [song("Y")], false).state;
    const x = s.queue[0];
    assert.equal(send(s, "b", { t: "unqueue", track: x, at: 0 }).state, s, "not b's");
    assert.equal(send(s, "a", { t: "unqueue", track: { ...x, by: "b" }, at: 0 }).state, s, "claiming b as adder finds no such song");
    assert.equal(send(s, "a", { t: "unqueue", track: s.queue[2], at: 2 }).state, s, "the host's album isn't a's");
    let r = send(s, "a", { t: "unqueue", track: x, at: 5 }); // a stale index: the song is found where it is
    assert.deepEqual(titles(r.state), ["Y", "A1", "A2", "A3"]);
    assert.equal(sent(r, "queue")[0].to, "all");
    assert.ok(r.state.adds.every((e) => !e.k.includes('"X"')), "its add is forgotten");
    r = send(r.state, "h", { t: "unqueue", track: r.state.queue[0], at: 0 });
    assert.deepEqual(titles(r.state), ["A1", "A2", "A3"]);
    for (const bad of [{}, { track: "x" }, { track: { t: "" } }]) assert.equal(send(s, "a", { t: "unqueue", ...bad }).state, s);
});

test("a song a guest removed stays out when the host's app resends a queue that still had it", () => {
    let s = add(room2(), "a", [song("X")], true).state;
    const seen = s.qv; // the host's app has [X, A1, A2, A3]
    const view = s.queue;
    s = send(s, "a", { t: "unqueue", track: view[0], at: 0 }).state;
    s = send(s, "h", { t: "load", track: view[1], positionMs: 0, queue: [view[0], view[2], view[3]], qv: seen }).state;
    assert.deepEqual(titles(s), ["A2", "A3"]);
    // Removed, then added again: the new copy is kept.
    s = add(room2(), "a", [song("X")], true).state;
    const seen2 = s.qv;
    const view2 = s.queue;
    s = send(s, "a", { t: "unqueue", track: view2[0], at: 0 }).state;
    s = add(s, "a", [song("X")], true).state;
    s = send(s, "h", { t: "queue", queue: view2, qv: seen2 }).state;
    assert.deepEqual(titles(s), ["X", "A1", "A2", "A3"]);
});

test("a guest's copy of a song on the host's album is its own song", () => {
    let s = add(room2(), "a", [{ ...ALBUM[2] }], true).state;
    assert.deepEqual(titles(s), ["A3", "A1", "A2", "A3"]);
    // The host's resend from before the add has A3 by "h" only: a's copy still comes back.
    s = send(s, "h", { t: "queue", queue: ALBUM, qv: 1 }).state;
    assert.deepEqual(s.queue.map((x) => `${x.t}/${x.by}`), ["A3/a", "A1/h", "A2/h", "A3/h"]);
});

test("accepted keeps naming the Play next run, so a rolled-back Worker still finds it", () => {
    let s = add(room2(), "a", [song("P1")], true).state;
    s = add(s, "b", [song("Q1")], false).state;
    assert.deepEqual(s.accepted, [JSON.stringify(["P1", "Friend", null, null])]);
});

test("a room stored before guest adds keeps working", () => {
    // What the previous Worker stored: no qv, adds or removed, and an accepted suggestion waiting at the front.
    let s = send(party(), "h", { t: "queue", queue: [NEXT] }).state;
    s = send(s, "a", { t: "suggest", track: song("A") }, T0, { newId: "s1" }).state;
    s = send(s, "h", { t: "suggestion", id: "s1", action: "add" }).state;
    for (const k of ["qv", "adds", "removed"]) delete s[k];
    s.accepted = [JSON.stringify(["A", "Friend", null, null])];
    const welcome = sent(hello(s, "c"), "welcome")[0].msg.state;
    assert.equal(welcome.qv, 0);
    assert.equal(welcome.guestsAdd, false);
    s = send(s, "b", { t: "suggest", track: song("B") }, T0, { newId: "s2" }).state;
    s = send(s, "h", { t: "suggestion", id: "s2", action: "add" }).state;
    assert.deepEqual(titles(s), ["A", "B", NEXT.t], "still behind the accepted song waiting at the front");
});

test("a load because the host couldn't play the song says so", () => {
    const r = send(room2(), "h", { t: "load", track: NEXT, positionMs: 0, queue: [], qv: 1, why: "unavailable" });
    assert.equal(sent(r, "prepare")[0].msg.why, "unavailable");
});

test("bad adds and settings change nothing; a listener can't send settings", () => {
    const s = room2();
    for (const tracks of [undefined, "x", [], [{ t: "" }], [null]]) assert.equal(add(s, "a", tracks, true).state, s);
    assert.equal(send(s, "a", { t: "settings", guestsAdd: false }).state, s);
    assert.equal(send(s, "nobody", { t: "add", tracks: [song("X")], next: true }).state, s);
    const off = room2({ ga: false });
    assert.equal(send(off, "a", { t: "add", tracks: [song("X")], next: true }).state, off, "a suggestion needs an id");
});

// ── Review fixes: who owns the album after a handover, Back's place in the queue, answers to an add, the size limit ──

const LONG = "x".repeat(500);
const longSong = (t) => ({ t: `${t}${LONG}`.slice(0, 500), a: LONG, al: LONG, art: `https://i.scdn.co/image/${"a".repeat(900)}` });
const album30 = () => Array.from({ length: 30 }, (_, i) => ({ t: `B${i}`, a: "Host" }));
const room30 = () => {
    const s = room2();
    return send(s, "h", { t: "load", track: TRACK, positionMs: 0, queue: album30(), qv: s.qv }, T0 + 11).state;
};

test("a former host's album doesn't count against them: after makeHost they can add, and can't swipe it away", () => {
    const s = send(room30(), "h", { t: "makeHost", memberId: "a" }).state;
    assert.ok(s.queue.every((t) => t.by === "a"), "the album is the new host's now");
    const r = add(s, "h", [song("Mine")], true);
    assert.equal(r.state.queue[0].t, "Mine", "not refused as 20 waiting");
    assert.equal(r.state.queue[0].by, "h");
    assert.equal(sent(r, "added")[0].msg.count, 1);
    assert.equal(send(s, "h", { t: "unqueue", track: s.queue[0], at: 0 }).state, s, "a guest takes out only their own");
    const swiped = send(s, "a", { t: "unqueue", track: s.queue[0], at: 0 }).state;
    assert.equal(swiped.queue.length, 29, "the new host can");
});

test("the host's own earlier adds stay theirs through a handover, and the room's phones hear the new owner", () => {
    let s = add(room30(), "h", [song("HostAdd")], true).state;
    s = add(s, "b", [song("GuestAdd")], true).state;
    const r = send(s, "h", { t: "makeHost", memberId: "a" });
    assert.deepEqual(r.state.queue.slice(0, 2).map((t) => [t.t, t.by]), [["HostAdd", "h"], ["GuestAdd", "b"]]);
    assert.ok(r.state.queue.slice(2).every((t) => t.by === "a"));
    assert.ok(sent(r, "state")[0].msg.state.queue.slice(2).every((t) => t.by === "a"), "every phone's Up next says so");
    assert.equal(add(r.state, "a", [song("After")], true).state.queue[2].t, "After", "the adds still hold the front run");
});

test("the album goes to a 60 s heir too, and the old host's copy of it stays gone with them", () => {
    let s = step(room30(), { type: "close", from: "h" }, T0 + 1_000).state;
    const r = step(s, { type: "alarm" }, T0 + 61_000);
    assert.equal(r.state.host, "a");
    assert.ok(r.state.queue.every((t) => t.by === "a"));
    assert.ok(sent(r, "state")[0].msg.state.queue.every((t) => t.by === "a"));
    s = add(r.state, "b", [song("G1")], true).state;
    assert.equal(s.queue[0].t, "G1", "the album isn't an add, so a guest's Play next goes in front of it");
});

test("a room left hostless gives the album to whoever takes it, by the first hello back or the host key", () => {
    let s = room30();
    for (const id of ["h", "a", "b", "o"]) s = step(s, { type: "close", from: id }, T0 + 1_000).state;
    s = step(s, { type: "alarm" }, T0 + 61_000).state;
    assert.equal(s.host, null);
    assert.ok(s.queue.every((t) => t.by === "h"), "nobody to give it to yet");
    const first = hello(s, "n", { at: T0 + 62_000, v: 2 });
    assert.equal(first.state.host, "n");
    assert.ok(sent(first, "welcome")[0].msg.state.queue.every((t) => t.by === "n"));
    const keyed = hello(s, "k", { hostKeyOk: true, at: T0 + 62_000, v: 2 });
    assert.equal(keyed.state.host, "k");
    assert.ok(keyed.state.queue.every((t) => t.by === "k"));
});

test("an app's whole queue after a handover keeps the new owner, so the cap stays right as the album plays", () => {
    let s = send(room30(), "h", { t: "makeHost", memberId: "a" }).state;
    s = send(s, "a", { t: "load", track: s.queue[0], positionMs: 0, queue: s.queue.slice(1), qv: s.qv, why: "end" }).state;
    assert.equal(s.queue.filter((t) => t.by === "h").length, 0);
    for (let i = 0; i < 20; i++) s = add(s, "h", [song(`F${i}`)], false).state;
    assert.equal(s.queue.filter((t) => t.by === "h").length, 20);
    assert.equal(sent(add(s, "h", [song("F21")], false), "added")[0].msg.count, 0, "their real 20 still hold");
});

/** The room with [waiting] guest songs in front and the host one song in, its app about to go Back to TRACK. */
function playedOne(...waiting) {
    let s = room2(); // TRACK playing, ALBUM after it, qv 1
    s = send(s, "h", { t: "load", track: ALBUM[0], positionMs: 0, queue: ALBUM.slice(1), qv: s.qv, why: "end" }).state;
    for (const [t, next] of waiting) s = add(s, "a", [song(t)], next).state;
    return s;
}
const goBack = (s, seen = s.qv) => send(s, "h", {
    t: "load", track: TRACK, positionMs: 0, queue: [s.track, ...s.queue], qv: seen, why: "back",
}).state;

test("Back: the song it re-queues holds the front, so a new Play next goes behind it and behind the Play next run", () => {
    let s = goBack(playedOne(["P1", true], ["P2", true]));
    assert.deepEqual(titles(s), ["A1", "P1", "P2", "A2", "A3"]);
    s = add(s, "b", [song("P3")], true).state;
    assert.deepEqual(titles(s), ["A1", "P1", "P2", "P3", "A2", "A3"], "was P3 A1 P1 P2: in front of the song Back left");
});

test("Back: an Add to queue goes behind the added songs, before the album", () => {
    let s = goBack(playedOne(["P1", true], ["Q1", false]));
    s = add(s, "b", [song("Q2")], false).state;
    assert.deepEqual(titles(s), ["A1", "P1", "Q1", "Q2", "A2", "A3"]);
});

test("Back with an app that hadn't heard of an add yet: the add goes behind the re-queued song", () => {
    const before = playedOne();
    const seen = before.qv;
    const view = before.queue;
    const s = add(before, "a", [song("P1")], true).state;
    const back = send(s, "h", { t: "load", track: TRACK, positionMs: 0, queue: [s.track, ...view], qv: seen, why: "back" }).state;
    assert.deepEqual(titles(back), ["A1", "P1", "A2", "A3"]);
});

test("the song Back re-queued is an ordinary song again once it plays, and doesn't survive a new album", () => {
    let s = goBack(playedOne(["P1", true]));
    const album = [{ t: "N1", a: "Host" }];
    const picked = send(s, "h", { t: "load", track: { t: "N0", a: "Host" }, positionMs: 0, queue: album, qv: s.qv, why: "pick" }).state;
    assert.deepEqual(titles(picked), ["P1", "N1"], "P1 is a person's add waiting; A1 has been heard");
    s = send(s, "h", { t: "load", track: s.queue[0], positionMs: 0, queue: s.queue.slice(1), qv: s.qv, why: "end" }).state;
    assert.deepEqual(titles(s), ["P1", "A2", "A3"]);
    assert.deepEqual(s.adds.map((e) => e.back ?? false), [false], "its log entry left with it");
});

test("Back twice keeps both songs in front of what's added next", () => {
    let s = playedOne();
    s = send(s, "h", { t: "load", track: ALBUM[1], positionMs: 0, queue: ALBUM.slice(2), qv: s.qv, why: "end" }).state;
    s = send(s, "h", { t: "load", track: ALBUM[0], positionMs: 0, queue: [s.track, ...s.queue], qv: s.qv, why: "back" }).state;
    s = send(s, "h", { t: "load", track: TRACK, positionMs: 0, queue: [s.track, ...s.queue], qv: s.qv, why: "back" }).state;
    s = add(s, "a", [song("P1")], true).state;
    assert.deepEqual(titles(s), ["A1", "A2", "P1", "A3"]);
});

test("an older app's Back is taken as sent, and the host dragging an album song to the top stays as it was", () => {
    const before = playedOne();
    const old = send(before, "h", { t: "load", track: TRACK, positionMs: 0, queue: [before.track, ...before.queue], why: "back" }).state;
    assert.deepEqual(old.adds, [], "no qv: nothing logged");
    // The drag: A3 above A2. It isn't an add, so a Play next goes in front of it, as the host left it.
    let s = room2();
    s = send(s, "h", { t: "queue", queue: [ALBUM[2], ALBUM[0], ALBUM[1]], qv: s.qv }).state;
    s = add(s, "a", [song("P1")], true).state;
    assert.deepEqual(titles(s), ["P1", "A3", "A1", "A2"]);
    // It jumps guests who added earlier too, so first come breaks here. Today's behaviour, and the owner's call.
    let d = add(room2(), "a", [song("g1")], true).state; // g1 A1 A2 A3
    d = send(d, "h", { t: "queue", queue: [d.queue[2], d.queue[0], d.queue[1], d.queue[3]], qv: d.qv }).state;
    assert.deepEqual(titles(d), ["A2", "g1", "A1", "A3"]);
    d = add(d, "b", [song("g2")], true).state;
    assert.deepEqual(titles(d), ["g2", "A2", "g1", "A1", "A3"]);
});

test("an add that can't go in is answered to the sender alone, and changes nothing", () => {
    let s = room2();
    for (let i = 0; i < 20; i++) s = add(s, "a", [song(`S${i}`)], true).state;
    const r = add(s, "a", [song("One too many")], true);
    assert.equal(r.state, s);
    assert.deepEqual(r.out, [{ to: "a", msg: { t: "added", by: "a", next: true, count: 0 } }]);
    const bad = add(s, "b", [{ t: "" }], false);
    assert.equal(bad.state, s);
    assert.deepEqual(bad.out, [{ to: "b", msg: { t: "added", by: "b", next: false, count: 0 } }]);
});

test("an add that became suggestions says so to the sender alone: how many fit, none when the tray is full", () => {
    const off = room2({ ga: false });
    const r = add(off, "a", [song("S1"), song("S2")], true);
    assert.deepEqual(sent(r, "added"), [{ to: "a", msg: { t: "added", by: "a", next: true, count: 0, suggested: 2 } }]);
    const three = add(r.state, "a", [song("S3"), song("S4")], true);
    assert.equal(sent(three, "added")[0].msg.suggested, 1);
    const full = add(three.state, "a", [song("S5")], false);
    assert.equal(full.state, three.state);
    assert.deepEqual(full.out, [{ to: "a", msg: { t: "added", by: "a", next: false, count: 0, suggested: 0 } }]);
});

test("the queue stops taking adds before the host's next Load could pass the room's frame limit", () => {
    let s = created();
    s = hello(s, "h", { hostKeyOk: true, v: 2, guestsAdd: true }).state;
    for (let g = 0; g < 9; g++) s = hello(s, `g${g}`, { at: T0 + g + 1, v: 2 }).state;
    let refused = 0;
    for (let g = 0; g < 9; g++) {
        const r = add(s, `g${g}`, Array.from({ length: 20 }, (_, i) => longSong(`${g}-${i}`)), true);
        refused += 20 - (sent(r, "added")[0]?.msg.count ?? 0);
        s = r.state;
    }
    assert.ok(refused > 0, "this much doesn't fit");
    assert.ok(JSON.stringify(s.queue).length <= MAX_QUEUE_CHARS);
    const refusal = add(s, "g8", [longSong("late")], true);
    assert.deepEqual(refusal.out, [{ to: "g8", msg: { t: "added", by: "g8", next: true, count: 0 } }]);
    // Twenty waiting suggestions of the same size can still go in when the switch turns on, and it all still fits.
    const load = JSON.stringify({ t: "load", track: longSong("now"), positionMs: 0, why: "end", qv: s.qv, queue: s.queue });
    const twenty = JSON.stringify(Array.from({ length: 20 }, (_, i) => ({ ...longSong(`w${i}`), by: "g1" })));
    assert.ok(load.length + twenty.length < 262_144, `${load.length + twenty.length} chars`);
});

test("ordinary songs never meet the size limit: the room's whole 200 fit", () => {
    let s = room2();
    const ordinary = (t) => ({ t, a: "An Artist With A Name", al: "An Album Title", d: 200000, isrc: "GBBPW0100025", art: `https://i.scdn.co/image/${"a".repeat(40)}` });
    s = send(s, "h", { t: "add", tracks: Array.from({ length: 200 }, (_, i) => ordinary(`H${i}`)), next: false }).state;
    assert.equal(s.queue.length, 200);
    assert.ok(JSON.stringify(s.queue).length < MAX_QUEUE_CHARS);
});

test("suggestions can't carry a queue past the frame limit: a song over 4,000 characters as JSON is refused", () => {
    const CTL = String.fromCharCode(1); // 6 characters in JSON
    const nasty = (i) => ({ t: CTL.repeat(497) + String(i).padStart(3, "0"), a: CTL.repeat(500), al: CTL.repeat(500) });
    const worst = (i) => ({ t: CTL.repeat(497) + String(i).padStart(3, "0"), a: CTL.repeat(100), al: CTL.repeat(60) });
    const longest = (i, g) => ({ t: `${g}-${i}-${"x".repeat(480)}`, a: "a".repeat(490), al: "b".repeat(490) });
    assert.ok(JSON.stringify(worst(0)).length <= 4_000, "the longest that passes");

    let s = created();
    s = hello(s, "h", { hostKeyOk: true, v: 2, guestsAdd: true }).state;
    const guests = Array.from({ length: 9 }, (_, g) => `g${g}`);
    guests.forEach((g, i) => { s = hello(s, g, { at: T0 + i + 1, v: 2 }).state; });
    s = send(s, "h", { t: "load", track: song("start"), positionMs: 0, queue: [], qv: 0 }).state;
    // The queue up to the stop, a song at a time, so every guest still has room in their 20.
    let n = 0;
    for (let refused = false; !refused;) {
        for (const g of guests) {
            const r = add(s, g, [longest(n++, g)], false);
            refused ||= sent(r, "added")[0].msg.count === 0;
            s = r.state;
        }
    }
    assert.ok(JSON.stringify(s.queue).length > 160_000);
    s = send(s, "h", { t: "settings", guestsAdd: false }).state;

    // At 9,000 characters each they would make the host's next Load about 350,000, and close its socket.
    let tried = 0;
    for (const g of guests) for (let i = 0; i < 3; i++) s = send(s, g, { t: "suggest", track: nasty(tried++) }, T0, { newId: `n${tried}` }).state;
    assert.equal(s.suggestions.length, 0);

    // As long as a song may be, 20 of them flushed by the switch, and the Load's own song, still fit.
    let m = 0;
    for (const g of guests) for (let i = 0; i < 3; i++) s = send(s, g, { t: "suggest", track: worst(m++) }, T0, { newId: `w${m}` }).state;
    assert.equal(s.suggestions.length, 20);
    s = send(s, "h", { t: "settings", guestsAdd: true }).state;
    assert.equal(s.suggestions.length, 0, "they all went in");
    const frame = JSON.stringify({ t: "load", track: worst(99), positionMs: 0, queue: s.queue, qv: s.qv, why: "skip" });
    assert.ok(frame.length < 262_144, `${frame.length} characters`);
});

// ── The room waits for its host (owner decision 2026-11-07) ──────────────────────────────────────────────────────────

/** Host "h" and guests "a", "b" on apps that give up on a song within HOST_PREPARE_MS (v 2); TRACK loaded at T0 + 100. */
function waitParty() {
    let s = created();
    s = hello(s, "h", { hostKeyOk: true, v: 2 }).state;
    s = hello(s, "a", { at: T0 + 1, v: 2 }).state;
    s = hello(s, "b", { at: T0 + 2, v: 2 }).state;
    return loaded(s).state;
}
const LOAD = T0 + 100;
const unavailable = (s, from, at, trackKey = 1) => send(s, from, { t: "status", status: "unavailable", trackKey }, at);

test("the guests are ready and the host isn't: nobody starts, and the alarm is the end of the wait for the host", () => {
    assert.equal(HOST_PREPARE_MS, 20_000);
    assert.ok(HOST_WAIT_CAP_MS > HOST_PREPARE_MS, "the app's own report comes first");
    let s = waitParty();
    s = ready(s, "a").state;
    const r = ready(s, "b", LOAD + 300);
    assert.equal(sent(r, "timeline").length, 0);
    assert.equal(r.state.phase.kind, "preparing");
    assert.equal(r.alarmAt, LOAD + HOST_WAIT_CAP_MS);
    const deadline = step(r.state, { type: "alarm" }, LOAD + PREPARE_MS);
    assert.equal(deadline.state, r.state, "the guests' deadline starts nothing without the host");
    // The host has it 15 s in: everyone starts together, 500 ms later.
    const go = ready(r.state, "h", LOAD + 15_000);
    assert.deepEqual(sent(go, "timeline").map((o) => [o.msg.atRoomMs, o.msg.playing]), [[LOAD + 15_500, true]]);
});

test("a slow guest still waits only 8 s once the host is ready, and joins late", () => {
    let s = ready(waitParty(), "h").state;
    s = ready(s, "a").state;
    assert.equal(step(s, { type: "alarm" }, LOAD + PREPARE_MS - 1).state, s);
    const r = step(s, { type: "alarm" }, LOAD + PREPARE_MS);
    assert.equal(sent(r, "timeline")[0].msg.atRoomMs, LOAD + PREPARE_MS + 500);
});

test("a host ready after the guests' deadline starts the song at once, without the slow guest", () => {
    let s = ready(waitParty(), "a").state;
    s = step(s, { type: "alarm" }, LOAD + PREPARE_MS).state; // b isn't ready, and the host is still looking
    assert.equal(s.phase.kind, "preparing");
    const r = ready(s, "h", LOAD + 12_000);
    assert.equal(sent(r, "timeline")[0].msg.atRoomMs, LOAD + 12_500);
});

test("a host that can't get the song says so and is waited for no longer: its next load follows", () => {
    let s = ready(waitParty(), "a").state;
    s = ready(s, "b").state;
    const r = unavailable(s, "h", LOAD + 20_200);
    assert.equal(sent(r, "timeline").length, 1, "as for any phone that can't play it, the way the app's skip has always run");
    const next = send(r.state, "h", { t: "load", track: NEXT, positionMs: 0, queue: [], why: "unavailable" }, LOAD + 20_250);
    assert.equal(sent(next, "prepare")[0].msg.why, "unavailable");
    assert.equal(next.state.phase.kind, "preparing");
});

test("a host that never says is waited for 25 s from the load, then the room starts without it", () => {
    let s = ready(waitParty(), "a").state;
    s = ready(s, "b").state;
    assert.equal(step(s, { type: "alarm" }, LOAD + HOST_WAIT_CAP_MS - 1).state, s);
    const r = step(s, { type: "alarm" }, LOAD + HOST_WAIT_CAP_MS);
    assert.equal(sent(r, "timeline")[0].msg.playing, true);
    assert.equal(r.state.phase.kind, "playing");
});

test("an older host app (no v) is waited for 8 s, as before: it only gives up when its player fails", () => {
    let s = ready(loaded().state, "a").state; // party(): h, a and b on released apps
    s = ready(s, "b").state;
    assert.equal(s.phase.kind, "preparing", "its own status is still buffering");
    assert.equal(send(s, "a", { t: "ping", c: 1 }, LOAD + 300).alarmAt, LOAD + PREPARE_MS);
    const go = step(s, { type: "alarm" }, LOAD + PREPARE_MS);
    assert.equal(sent(go, "timeline")[0].msg.playing, true);
});

test("the host dropping while the room waits for it starts the song for the guests", () => {
    let s = ready(waitParty(), "a").state;
    s = ready(s, "b").state;
    const r = step(s, { type: "close", from: "h" }, LOAD + 5_000);
    assert.equal(sent(r, "timeline")[0].msg.playing, true);
});

test("a host back mid-handshake is waited for again, within the same 25 s", () => {
    let s = step(waitParty(), { type: "close", from: "h" }, LOAD + 1_000).state; // nobody ready yet: still preparing
    s = hello(s, "h2", { resumeToken: "tok-h", at: LOAD + 2_000, v: 2 }).state;
    s = ready(s, "a").state;
    const r = ready(s, "b", LOAD + 3_000);
    assert.equal(r.state.phase.kind, "preparing");
    assert.equal(r.alarmAt, LOAD + HOST_WAIT_CAP_MS);
    assert.equal(sent(ready(r.state, "h", LOAD + 4_000), "timeline").length, 1);
});

test("handing over while the room waits for the host starts it if the new host is ready, else waits for that one", () => {
    let s = ready(waitParty(), "a").state;
    s = step(s, { type: "alarm" }, LOAD + PREPARE_MS).state; // b is slow, h still looking: waiting on h
    const toReady = send(s, "h", { t: "makeHost", memberId: "a" }, LOAD + 9_000);
    assert.equal(sent(toReady, "timeline")[0].msg.playing, true);
    assert.equal(sent(toReady, "state")[0].msg.state.timeline.playing, true, "the new host's state already says it started");
    const toSlow = send(s, "h", { t: "makeHost", memberId: "b" }, LOAD + 9_000);
    assert.equal(sent(toSlow, "timeline").length, 0);
    assert.equal(toSlow.alarmAt, LOAD + HOST_WAIT_CAP_MS);
    assert.equal(sent(ready(toSlow.state, "b", LOAD + 10_000), "timeline").length, 1);
});

test("phones see only the guests' deadline, never the host's", () => {
    const r = hello(waitParty(), "c", { at: LOAD + 50, v: 2 });
    assert.deepEqual(sent(r, "welcome")[0].msg.state.phase, { kind: "preparing", trackKey: 1, deadlineMs: LOAD + PREPARE_MS });
});

test("a room stored mid-handshake before this rule keeps its one deadline", () => {
    let s = ready(waitParty(), "a").state;
    s = ready(s, "b").state;
    const old = structuredClone(s);
    delete old.phase.hostDeadlineMs;
    const r = step(old, { type: "msg", from: "a", msg: { t: "ping", c: 1 } }, LOAD + 400);
    assert.equal(r.alarmAt, LOAD + PREPARE_MS);
    assert.equal(sent(step(old, { type: "alarm" }, LOAD + PREPARE_MS), "timeline").length, 1);
});
