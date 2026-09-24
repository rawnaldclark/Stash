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
