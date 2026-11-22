// Sessions step 7: open decks. The host and up to four DJs take turns, one song each; more wait in a line.
import { test } from "node:test";
import assert from "node:assert/strict";
import { step, publicState, PUBLIC_V, DECK_SEATS, DECK_LINE, DECK_MISSES, HANDOVER_MS } from "../src/room.js";

const T0 = 1_000_000;
const song = (t) => ({ t, a: "Artist", d: 200000 });
const HOSTS = [song("Host 1"), song("Host 2"), song("Host 3"), song("Host 4"), song("Host 5"), song("Host 6")];

const hello = (s, id, { host = false, at = T0, hash = host ? undefined : `kh-${id}`, resumeToken } = {}) =>
    step(s, { type: "hello", msg: { t: "hello", name: id, v: PUBLIC_V, resumeToken }, hostKeyOk: host, listenerHash: hash, newId: id, newToken: `tok-${id}` }, at);
const send = (s, from, msg, at = T0) => step(s, { type: "msg", from, msg }, at);
const sent = (r, t) => r.out.filter((o) => o.msg.t === t).map((o) => o.msg);
const titles = (s) => s.queue.map((t) => `${t.t}${t.by && t.by !== "h" ? `(${t.by})` : ""}`);

/** A session with host "h" and listeners `ids`, the host's first song playing and the rest of the host's queue behind it. */
function session(ids = ["a", "b", "c"]) {
    let s = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h", stationId: "STATION1" }, T0).state;
    s = hello(s, "h", { host: true }).state;
    for (const [i, id] of ids.entries()) s = hello(s, id, { at: T0 + 1 + i }).state;
    s = send(s, "h", { t: "load", track: HOSTS[0], positionMs: 0, queue: HOSTS.slice(1) }).state;
    return send(s, "h", { t: "status", status: "ready", trackKey: 1 }).state;
}
const open = (s) => send(s, "h", { t: "decks", open: true }).state;
/** The host's phone moves to the next song: the front of the queue, as the app does. */
function next(s, at = T0 + 1000) {
    const [track, ...queue] = s.queue;
    return send(s, "h", { t: "load", track, positionMs: 0, queue, qv: s.qv, why: "end" }, at);
}
const decks = (s) => publicState(s).decks;

// ── Opening and sitting down ──────────────────────────────────────────────────

test("only the host opens and closes the decks; everyone hears, and the state says so", () => {
    const s = session();
    assert.ok(!("decks" in publicState(s)), "absent until opened");
    assert.deepEqual(send(s, "a", { t: "decks", open: true }).out, []);
    const r = send(s, "h", { t: "decks", open: true });
    assert.deepEqual(sent(r, "decksState"), [{ t: "decksState", open: true, djs: [], line: [], now: "h", next: null }]);
    assert.deepEqual(send(r.state, "h", { t: "decks", open: true }).out, [], "already open");
    const closed = send(r.state, "h", { t: "decks", open: false });
    assert.deepEqual(sent(closed, "decksState"), [{ t: "decksState", open: false, djs: [], line: [], now: null, next: null }]);
    assert.ok(!("decks" in publicState(closed.state)));
});

test("joining takes a seat with a song ready, and the first DJ's song goes next at once", () => {
    const s = open(session());
    const r = send(s, "a", { t: "deckJoin", track: song("A1") });
    assert.deepEqual(decks(r.state), { open: true, djs: [{ id: "a", ready: true }], line: [], now: "h", next: "a" });
    assert.deepEqual(titles(r.state).slice(0, 2), ["A1(a)", "Host 2"], "in the queue as a's add, ahead of the host's");
    assert.equal(sent(r, "queue").length, 1);
    assert.equal(sent(r, "decksState").length, 1);
});

test("joining needs the decks open, a song, and not to be the host", () => {
    const closed = session();
    assert.deepEqual(send(closed, "a", { t: "deckJoin", track: song("A1") }).out, []);
    const s = open(closed);
    assert.deepEqual(send(s, "a", { t: "deckJoin" }).out, []);
    assert.deepEqual(send(s, "a", { t: "deckJoin", track: { t: "" } }).out, []);
    assert.deepEqual(send(s, "h", { t: "deckJoin", track: song("H") }).out, []);
});

test("four seats, then a line; the line is capped, and a seat that frees goes to the first in line", () => {
    assert.equal(DECK_SEATS, 4);
    const ids = Array.from({ length: DECK_SEATS + DECK_LINE + 1 }, (_, i) => `m${i}`);
    let s = open(session(ids));
    for (const id of ids.slice(0, DECK_SEATS + DECK_LINE)) s = send(s, id, { t: "deckJoin", track: song(`${id} song`) }).state;
    assert.deepEqual(decks(s).djs.map((x) => x.id), ["m0", "m1", "m2", "m3"]);
    assert.equal(decks(s).line.length, DECK_LINE);
    const full = send(s, ids.at(-1), { t: "deckJoin", track: song("late") });
    assert.deepEqual(full.out, [{ to: ids.at(-1), msg: { t: "refused", what: "decks", reason: "full" } }]);
    const left = send(s, "m1", { t: "deckLeave" });
    assert.deepEqual(decks(left.state).djs.map((x) => x.id), ["m0", "m2", "m3", "m4"]);
    assert.equal(decks(left.state).line.length, DECK_LINE - 1);
});

// ── Turns ─────────────────────────────────────────────────────────────────────

test("a full round: the host, then each DJ in the order they sat down, then the host again", () => {
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state;
    s = send(s, "b", { t: "deckJoin", track: song("B1") }).state;
    const played = [s.track.t];
    const who = [decks(s).now];
    for (let i = 0; i < 6; i++) {
        s = next(s, T0 + 1000 * (i + 1)).state;
        played.push(s.track.t);
        who.push(decks(s).now);
        // On the decks you keep a song ready: each DJ readies their next as theirs starts.
        if (s.track.by === "a") s = send(s, "a", { t: "deckPick", track: song(`A${played.filter((t) => t.startsWith("A")).length + 1}`) }).state;
        if (s.track.by === "b") s = send(s, "b", { t: "deckPick", track: song(`B${played.filter((t) => t.startsWith("B")).length + 1}`) }).state;
    }
    assert.deepEqual(played, ["Host 1", "A1", "B1", "Host 2", "A2", "B2", "Host 3"]);
    assert.deepEqual(who, ["h", "a", "b", "h", "a", "b", "h"]);
});

test("the song that starts says whose turn is next", () => {
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state;
    s = send(s, "b", { t: "deckJoin", track: song("B1") }).state;
    assert.equal(decks(s).next, "a");
    const r = next(s);
    assert.deepEqual(sent(r, "decksState").at(-1), { t: "decksState", open: true, djs: [{ id: "a", ready: false }, { id: "b", ready: true }], line: [], now: "a", next: "b" });
    assert.equal(r.state.queue[0].t, "B1");
});

test("a DJ with nothing ready is skipped, and off the decks after two turns missed in a row", () => {
    assert.equal(DECK_MISSES, 2);
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state;
    s = send(s, "b", { t: "deckJoin", track: song("B1") }).state;
    s = next(s, T0 + 1000).state; // A1 plays; b's B1 is next; a readies nothing more
    s = next(s, T0 + 2000).state; // B1 plays; the host is next
    s = send(s, "b", { t: "deckPick", track: song("B2") }).state;
    s = next(s, T0 + 3000).state; // the host's song; a has nothing: skipped (1), b's B2 next
    assert.equal(s.track.t, "Host 2");
    assert.equal(decks(s).next, "b");
    assert.deepEqual(decks(s).djs.map((x) => x.id), ["a", "b"], "one miss: still on the decks");
    s = next(s, T0 + 4000).state; // B2 plays; the host next
    s = send(s, "b", { t: "deckPick", track: song("B3") }).state;
    const r = next(s, T0 + 5000); // the host's song; a misses again: off the decks
    assert.deepEqual(sent(r, "deckOut"), [{ t: "deckOut", memberId: "a", reason: "skipped" }]);
    assert.deepEqual(decks(r.state).djs.map((x) => x.id), ["b"]);
});

test("readying a song clears the misses: skipped once, then played, then skipped once is not two in a row", () => {
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state;
    s = next(s, T0 + 1000).state; // A1
    s = next(s, T0 + 2000).state; // host; a has nothing: miss 1
    s = send(s, "a", { t: "deckPick", track: song("A2") }).state; // too late for this turn...
    assert.equal(decks(s).next, "a", "...but a comes before the host in the round, so it's theirs after all");
    s = next(s, T0 + 3000).state; // A2
    const r = next(s, T0 + 4000); // host; a has nothing: miss 1 again, not 2
    assert.deepEqual(sent(r, "deckOut"), []);
    assert.deepEqual(decks(r.state).djs.map((x) => x.id), ["a"]);
    // The turn after that, still nothing: now it is two in a row.
    assert.deepEqual(sent(next(r.state, T0 + 5000), "deckOut"), [{ t: "deckOut", memberId: "a", reason: "skipped" }]);
});

test("someone who sits down mid-song with the host next takes the next turn, and nobody is counted as missing between songs", () => {
    let s = open(session());
    s = next(s, T0 + 1000).state; // decks open and empty: the host is next
    assert.equal(decks(s).next, "h");
    const r = send(s, "a", { t: "deckJoin", track: song("A1") }, T0 + 1500);
    assert.equal(decks(r.state).next, "a");
    assert.equal(r.state.queue[0].t, "A1");
    // b sits down with a song but a is already next: b waits their turn, uncounted.
    const b = send(r.state, "b", { t: "deckJoin", track: song("B1") }, T0 + 1600);
    assert.equal(decks(b.state).next, "a");
    assert.deepEqual(decks(b.state).djs, [{ id: "a", ready: true }, { id: "b", ready: true }]);
    assert.equal(b.state.queue.filter((t) => t.t === "B1").length, 0, "still theirs to change");
});

test("a ready song can be changed until it's queued", () => {
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state; // queued at once: a is next
    s = send(s, "b", { t: "deckJoin", track: song("B1") }).state;
    s = send(s, "b", { t: "deckPick", track: song("B better") }).state;
    s = next(s, T0 + 1000).state;
    assert.equal(s.queue[0].t, "B better");
    assert.deepEqual(send(s, "c", { t: "deckPick", track: song("C") }).out, [], "not on the decks or in the line");
});

// ── Leaving ───────────────────────────────────────────────────────────────────

test("stepping down, and the host taking someone off, free the seat and drop the ready song", () => {
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state;
    s = send(s, "b", { t: "deckJoin", track: song("B1") }).state;
    const down = send(s, "b", { t: "deckLeave" });
    assert.deepEqual(decks(down.state).djs.map((x) => x.id), ["a"]);
    assert.deepEqual(send(down.state, "b", { t: "deckLeave" }).out, [], "not on them any more");
    assert.deepEqual(send(s, "a", { t: "deckRemove", memberId: "b" }).out, [], "only the host removes");
    const off = send(s, "h", { t: "deckRemove", memberId: "a" });
    assert.deepEqual(decks(off.state).djs.map((x) => x.id), ["b"]);
    // a's song was already queued: it stays, as any add does, and the round carries on from the host.
    assert.equal(off.state.queue[0].t, "A1");
});

test("closing the decks drops every ready song; one already in the queue stays", () => {
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state;
    s = send(s, "b", { t: "deckJoin", track: song("B1") }).state;
    const closed = send(s, "h", { t: "decks", open: false }).state;
    assert.equal(closed.queue[0].t, "A1");
    const after = next(closed, T0 + 1000).state;
    assert.equal(after.queue.filter((t) => t.t === "B1").length, 0);
    assert.equal(after.queue[0].t, "Host 2", "the host's queue from here");
});

test("a DJ gone for good leaves the decks; one who reconnects keeps the seat", () => {
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state;
    s = send(s, "b", { t: "deckJoin", track: song("B1") }).state;
    s = step(s, { type: "close", from: "b" }, T0 + 100).state;
    const back = hello(s, "b2", { at: T0 + 200, resumeToken: "tok-b" });
    assert.deepEqual(decks(back.state).djs.map((x) => x.id), ["a", "b"]);
    s = step(s, { type: "close", from: "a" }, T0 + 300).state;
    const gone = step(s, { type: "alarm" }, T0 + 300 + HANDOVER_MS);
    assert.ok(!decks(gone.state).djs.some((x) => x.id === "a"));
    assert.equal(sent(gone, "decksState").length, 1);
});

test("a booted DJ is off the decks", () => {
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state;
    const r = send(s, "h", { t: "boot", memberId: "a" });
    assert.deepEqual(decks(r.state).djs, []);
});

test("the host playing something else first doesn't lose the DJ's turn", () => {
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state;
    // The host taps another song of its own; a's song is still at the front of the queue.
    s = send(s, "h", { t: "load", track: song("Host pick"), positionMs: 0, queue: s.queue, qv: s.qv, why: "pick" }, T0 + 1000).state;
    assert.equal(decks(s).next, "a");
    assert.equal(decks(s).now, "h");
    s = next(s, T0 + 2000).state;
    assert.equal(s.track.t, "A1");
    assert.equal(decks(s).now, "a");
});

test("a private room has no decks", () => {
    let s = step(null, { type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: "h" }, T0).state;
    s = step(s, { type: "hello", msg: { t: "hello" }, hostKeyOk: true, newId: "h", newToken: "t" }, T0).state;
    s = step(s, { type: "hello", msg: { t: "hello" }, hostKeyOk: false, newId: "g", newToken: "t2" }, T0).state;
    assert.deepEqual(send(s, "h", { t: "decks", open: true }).out, []);
    assert.deepEqual(send(s, "g", { t: "deckJoin", track: song("G") }).out, []);
});

test("someone joining the session gets the decks in the welcome's state", () => {
    let s = open(session());
    s = send(s, "a", { t: "deckJoin", track: song("A1") }).state;
    const r = hello(s, "late", { at: T0 + 500 });
    assert.deepEqual(sent(r, "welcome")[0].state.decks, { open: true, djs: [{ id: "a", ready: true }], line: [], now: "h", next: "a" });
});
