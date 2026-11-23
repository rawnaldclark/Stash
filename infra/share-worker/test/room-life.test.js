// Sessions step 4: quick phrases, animated reactions, a song's crowd counts and the crowd favourite.
import { test } from "node:test";
import assert from "node:assert/strict";
import { step, publicState, nextAlarm, PUBLIC_V, ANIMS, PHRASES, STATS_GAP_MS, FAVOURITE_MIN } from "../src/room.js";

const T0 = 1_000_000;
const TRACK = { t: "Avril 14th", a: "Aphex Twin", d: 125000 };
const NEXT = { t: "Xtal", a: "Aphex Twin", d: 290000 };
const FIRE = "\u{1F525}";

const hello = (s, id, { host = false, at = T0 } = {}) =>
    step(s, { type: "hello", msg: { t: "hello", name: id, v: PUBLIC_V }, hostKeyOk: host, listenerHash: host ? undefined : `kh-${id}`, newId: id, newToken: `tok-${id}` }, at);
const send = (s, from, msg, at = T0) => step(s, { type: "msg", from, msg }, at);
const sent = (r, t) => r.out.filter((o) => o.msg.t === t).map((o) => o.msg);

/** A session with host "h" and `n` listeners "l1".."ln", a song playing (trackKey 1). */
function playing(n = 2) {
    let s = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h", stationId: "STATION1" }, T0).state;
    s = hello(s, "h", { host: true }).state;
    for (let i = 1; i <= n; i++) s = hello(s, `l${i}`, { at: T0 + i }).state;
    s = send(s, "h", { t: "load", track: TRACK, positionMs: 0, queue: [NEXT] }).state;
    return send(s, "h", { t: "status", status: "ready", trackKey: 1 }).state;
}
const privateRoom = () => {
    let s = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h" }, T0).state;
    s = step(s, { type: "hello", msg: { t: "hello", name: "h" }, hostKeyOk: true, newId: "h", newToken: "t" }, T0).state;
    return step(s, { type: "hello", msg: { t: "hello", name: "g" }, hostKeyOk: false, newId: "g", newToken: "t2" }, T0).state;
};

// ── Phrases ───────────────────────────────────────────────────────────────────

test("there are 10 phrases and 12 animated reactions, as ids", () => {
    assert.equal(PHRASES.length, 10);
    assert.equal(ANIMS.length, 12);
    assert.ok(ANIMS.every((a) => a.startsWith("a:")));
    assert.equal(new Set([...PHRASES, ...ANIMS]).size, 22);
});

test("a phrase reaches everyone under the sender's id, and only a listener's first is stored", () => {
    const s = playing();
    const r = send(s, "l1", { t: "say", phrase: "tune" });
    assert.deepEqual(r.out, [{ to: "all", msg: { t: "said", from: "l1", phrase: "tune" } }]);
    // The first marks them as having spoken, for the scoreboard; after that a phrase changes nothing.
    assert.notEqual(r.state, s);
    const again = send(r.state, "l1", { t: "say", phrase: "chills" });
    assert.deepEqual(again.out, [{ to: "all", msg: { t: "said", from: "l1", phrase: "chills" } }]);
    assert.equal(again.state, r.state);
    // The host is no listener: theirs is never stored.
    assert.equal(send(s, "h", { t: "say", phrase: "tune" }).state, s);
});

test("only the fixed phrases pass: no text, no unknown id", () => {
    const s = playing();
    for (const phrase of ["Tune!", "hello everyone", "", null, 3, undefined]) {
        assert.deepEqual(send(s, "l1", { t: "say", phrase }).out, []);
    }
});

test("a private room has no phrases, saves or animated reactions", () => {
    const s = privateRoom();
    assert.deepEqual(send(s, "g", { t: "say", phrase: "tune" }).out, []);
    assert.deepEqual(send(s, "g", { t: "saved", trackKey: 0 }).out, []);
    assert.deepEqual(send(s, "g", { t: "react", emoji: "a:fire" }).out, []);
    assert.equal(send(s, "g", { t: "react", emoji: FIRE }).out.length, 1, "the six emoji as before");
});

// ── Animated reactions ────────────────────────────────────────────────────────

test("a session passes on each of the 12 animated reactions, and nothing that isn't one", () => {
    const s = playing();
    for (const anim of ANIMS) {
        assert.deepEqual(sent(send(s, "l1", { t: "react", emoji: anim }), "reaction"), [{ t: "reaction", from: "l1", emoji: anim }]);
    }
    assert.deepEqual(send(s, "l1", { t: "react", emoji: "a:nope" }).out, []);
    assert.deepEqual(send(s, "l1", { t: "react", emoji: "fire" }).out, []);
});

// ── A song's counts ───────────────────────────────────────────────────────────

test("a person's first reaction to a song counts and is stored; their next ones only pass through", () => {
    const s = playing();
    const first = send(s, "l1", { t: "react", emoji: FIRE }, T0 + 10);
    assert.deepEqual(sent(first, "songStats"), [{ t: "songStats", trackKey: 1, reactions: 1, saves: 0, favourite: false }]);
    assert.notEqual(first.state, s);
    const again = send(first.state, "l1", { t: "react", emoji: "a:hearts" }, T0 + 2000);
    assert.equal(sent(again, "reaction").length, 1);
    assert.deepEqual(sent(again, "songStats"), []);
    assert.equal(again.state, first.state, "nothing new to store");
});

test("before any song there is nothing to count", () => {
    let s = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h", stationId: "STATION1" }, T0).state;
    s = hello(s, "h", { host: true }).state;
    s = hello(s, "l1").state;
    const r = send(s, "l1", { t: "react", emoji: FIRE });
    assert.equal(r.out.length, 1);
    assert.equal(r.state, s);
    assert.ok(!("stats" in r.state.pub));
});

test("a save counts once a person, for the song that's playing only", () => {
    const s = playing();
    const r = send(s, "l1", { t: "saved", trackKey: 1 }, T0 + 10);
    assert.deepEqual(sent(r, "songStats"), [{ t: "songStats", trackKey: 1, reactions: 0, saves: 1, favourite: false }]);
    assert.deepEqual(send(r.state, "l1", { t: "saved", trackKey: 1 }, T0 + 5000).out, []);
    assert.deepEqual(send(r.state, "l2", { t: "saved", trackKey: 7 }, T0 + 5000).out, [], "another song's key");
    assert.deepEqual(send(r.state, "l2", { t: "saved" }, T0 + 5000).out, []);
    // Who saved is never in what phones get.
    assert.ok(!JSON.stringify(sent(r, "songStats")).includes("l1"));
});

test("counts go out at most once every 2 s: a change inside the gap waits for the alarm", () => {
    let s = playing(3);
    s = send(s, "l1", { t: "react", emoji: FIRE }, T0 + 100).state;
    const quiet = send(s, "l2", { t: "react", emoji: FIRE }, T0 + 600);
    assert.deepEqual(sent(quiet, "songStats"), []);
    assert.equal(sent(quiet, "reaction").length, 1, "the reaction itself is never held");
    assert.equal(nextAlarm(quiet.state), T0 + 100 + STATS_GAP_MS);
    const more = send(quiet.state, "l3", { t: "saved", trackKey: 1 }, T0 + 900);
    assert.deepEqual(sent(more, "songStats"), []);
    const flushed = step(more.state, { type: "alarm" }, T0 + 100 + STATS_GAP_MS);
    assert.deepEqual(sent(flushed, "songStats"), [{ t: "songStats", trackKey: 1, reactions: 2, saves: 1, favourite: false }]);
    assert.notEqual(nextAlarm(flushed.state), T0 + 100 + STATS_GAP_MS, "nothing left to send");
    // After the gap a change goes at once again.
    const later = send(flushed.state, "l3", { t: "react", emoji: FIRE }, T0 + 100 + 2 * STATS_GAP_MS);
    assert.equal(sent(later, "songStats").length, 1);
});

test("the next song starts from nothing, and a late count for the old one is dropped", () => {
    let s = playing();
    s = send(s, "l1", { t: "react", emoji: FIRE }, T0 + 100).state;
    s = send(s, "l2", { t: "saved", trackKey: 1 }, T0 + 200).state; // inside the gap: waiting
    s = send(s, "h", { t: "load", track: NEXT, positionMs: 0, queue: [] }, T0 + 300).state;
    assert.ok(!("songStats" in publicState(s)) || publicState(s).songStats.reactions === 0);
    const alarm = step(s, { type: "alarm" }, T0 + 100 + STATS_GAP_MS);
    assert.deepEqual(sent(alarm, "songStats"), [], "the old song's waiting counts never go out");
    const r = send(s, "l1", { t: "react", emoji: FIRE }, T0 + 5000);
    assert.deepEqual(sent(r, "songStats"), [{ t: "songStats", trackKey: 2, reactions: 1, saves: 0, favourite: false }]);
    assert.deepEqual(send(r.state, "l1", { t: "saved", trackKey: 1 }, T0 + 9000).out, [], "a save for the song before");
});

test("someone joining mid-song gets its counts in the welcome's state", () => {
    let s = playing();
    assert.ok(!("songStats" in publicState(s)), "none until someone reacts or saves");
    s = send(s, "l1", { t: "react", emoji: FIRE }, T0 + 100).state;
    const r = hello(s, "late", { at: T0 + 200 });
    assert.deepEqual(sent(r, "welcome")[0].state.songStats, { trackKey: 1, reactions: 1, saves: 0, favourite: false });
});

// ── The crowd favourite ───────────────────────────────────────────────────────

test("a favourite needs 3 people and a third of the room", () => {
    assert.equal(FAVOURITE_MIN, 3);
    // 4 in the room (host + 3): two reacting is over a third but under 3 people.
    let s = playing(3);
    s = send(s, "l1", { t: "react", emoji: FIRE }, T0 + 10).state;
    let r = send(s, "l2", { t: "react", emoji: FIRE }, T0 + 3000);
    assert.equal(sent(r, "songStats")[0].favourite, false);
    r = send(r.state, "l3", { t: "react", emoji: FIRE }, T0 + 6000);
    assert.equal(sent(r, "songStats")[0].favourite, true);

    // 13 in the room: 3 people is under a third, 5 is over.
    s = playing(12);
    let at = T0 + 10;
    for (const id of ["l1", "l2", "l3", "l4"]) {
        r = send(s, id, { t: "react", emoji: FIRE }, (at += 3000));
        s = r.state;
        assert.equal(sent(r, "songStats")[0].favourite, false, id);
    }
    r = send(s, "l5", { t: "react", emoji: FIRE }, (at += 3000));
    assert.equal(sent(r, "songStats")[0].favourite, true);
});

test("once a favourite, a song stays one as people come in, and saves don't make one", () => {
    let s = playing(3);
    for (const [i, id] of ["l1", "l2", "l3"].entries()) s = send(s, id, { t: "react", emoji: FIRE }, T0 + 10 + i * 3000).state;
    for (let i = 4; i <= 12; i++) s = hello(s, `l${i}`, { at: T0 + 9000 + i }).state;
    assert.equal(publicState(s).songStats.favourite, true);

    let quiet = playing(3);
    for (const [i, id] of ["l1", "l2", "l3"].entries()) quiet = send(quiet, id, { t: "saved", trackKey: 1 }, T0 + 10 + i * 3000).state;
    assert.equal(publicState(quiet).songStats.favourite, false);
    assert.equal(publicState(quiet).songStats.saves, 3);
});

test("reactions while the song is paused count, but don't make a favourite", () => {
    let s = playing(3);
    s = send(s, "h", { t: "pause" }, T0 + 5).state;
    let r;
    for (const [i, id] of ["l1", "l2", "l3"].entries()) { r = send(s, id, { t: "react", emoji: FIRE }, T0 + 10 + i * 3000); s = r.state; }
    assert.deepEqual(sent(r, "songStats"), [{ t: "songStats", trackKey: 1, reactions: 3, saves: 0, favourite: false }]);
});

// ── Step 5: the last song ─────────────────────────────────────────────────────

test("the host calls the last song: everyone's session says so, and the next load ends it", () => {
    let s = playing();
    const called = send(s, "h", { t: "lastSong" }, T0 + 100);
    assert.equal(sent(called, "session")[0].lastSong, true);
    assert.equal(publicState(called.state).session.lastSong, true, "someone joining now sees it too");
    assert.deepEqual(send(called.state, "h", { t: "lastSong" }, T0 + 200).out, [], "calling it twice says nothing new");
    const end = send(called.state, "h", { t: "load", track: NEXT, positionMs: 0, queue: [], why: "end" }, T0 + 9000);
    assert.deepEqual(sent(end, "ended"), [{ t: "ended", reason: "last_song" }]);
    assert.equal(sent(end, "prepare").length, 0, "the next song never starts");
    assert.equal(end.state, null, "the room is closed");
});

test("the host can take it back, and then the session goes on", () => {
    let s = send(playing(), "h", { t: "lastSong" }, T0 + 100).state;
    const back = send(s, "h", { t: "lastSong", on: false }, T0 + 200);
    assert.ok(!("lastSong" in sent(back, "session")[0]));
    const next = send(back.state, "h", { t: "load", track: NEXT, positionMs: 0, queue: [] }, T0 + 9000);
    assert.equal(sent(next, "prepare").length, 1);
    assert.deepEqual(sent(next, "ended"), []);
});

test("only the host calls it, only with a song on, and never in a private room", () => {
    const s = playing();
    assert.deepEqual(send(s, "l1", { t: "lastSong" }).out, []);
    assert.ok(!("lastSong" in publicState(s).session), "absent until called: the session's shape is as before");
    let empty = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h", stationId: "STATION1" }, T0).state;
    empty = hello(empty, "h", { host: true }).state;
    assert.deepEqual(send(empty, "h", { t: "lastSong" }).out, []);
    const p = privateRoom();
    assert.deepEqual(send(p, "h", { t: "lastSong" }).out, []);
});

test("End now still ends at once, last song or not", () => {
    const s = send(playing(), "h", { t: "lastSong" }, T0 + 100).state;
    assert.deepEqual(sent(send(s, "h", { t: "end" }, T0 + 200), "ended"), [{ t: "ended", reason: "host_ended" }]);
});
