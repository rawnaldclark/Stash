import { test } from "node:test";
import assert from "node:assert/strict";
import { ListenRoom, MSG_PER_SECOND, EDIT_BURST, EDITS_PER_SECOND } from "../src/listen-room.js";
import { sha256Hex } from "../src/store.js";
import { fakeCtx, fakeSocket } from "./fake-room.js";

async function openRoom(clock) {
    const ctx = fakeCtx();
    const room = new ListenRoom(ctx, {}, () => clock.t);
    await room.apply({ type: "create", code: "ABCDEF", hostName: "Rawn", keyHash: await sha256Hex("KEY") });
    return { ctx, room };
}

async function connect(ctx, room, hello) {
    const ws = fakeSocket();
    ctx.acceptWebSocket(ws);
    await room.webSocketMessage(ws, JSON.stringify({ t: "hello", ...hello }));
    return ws;
}

test("the host key makes the first socket host; a wrong key is a plain listener", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const welcome = host.sent.find((m) => m.t === "welcome");
    assert.equal(welcome.state.host, welcome.memberId);
    assert.equal(host.deserializeAttachment().memberId, welcome.memberId);
    const other = await connect(ctx, room, { name: "Sam", hostKey: "WRONG" });
    assert.notEqual(other.sent.find((m) => m.t === "welcome").state.host, other.deserializeAttachment().memberId);
    assert.ok(host.sent.some((m) => m.t === "members" && m.members.length === 2), "everyone hears about the new member");
    assert.equal(ctx.alarm, 1_000 + 12 * 3_600_000);
});

test("messages before hello are ignored; a room that has closed turns the hello away", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const ws = fakeSocket();
    ctx.acceptWebSocket(ws);
    await room.webSocketMessage(ws, JSON.stringify({ t: "play" }));
    await room.webSocketMessage(ws, "not json");
    assert.equal(ws.sent.length, 0);
    await ctx.storage.deleteAll();
    await room.webSocketMessage(ws, JSON.stringify({ t: "hello" }));
    assert.equal(ws.closeCode, 4404);
});

test("a resume replaces the old socket, whose late close doesn't drop the member", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const first = await connect(ctx, room, { hostKey: "KEY" });
    const { memberId, token } = first.sent.find((m) => m.t === "welcome");
    const second = await connect(ctx, room, { resumeToken: token });
    assert.ok(first.closed);
    assert.equal(second.sent.find((m) => m.t === "welcome").memberId, memberId);
    await room.webSocketClose(first, 1000, "replaced");
    const stored = await ctx.storage.get("room");
    assert.equal(stored.members.find((m) => m.id === memberId).leftAt, null);
});

test("more than 20 messages in a second closes the connection", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const ws = await connect(ctx, room, {});
    for (let i = 1; i < MSG_PER_SECOND; i++) await room.webSocketMessage(ws, JSON.stringify({ t: "ping", c: i }));
    assert.equal(ws.closed, false);
    await room.webSocketMessage(ws, JSON.stringify({ t: "ping", c: 99 }));
    assert.equal(ws.closeCode, 1008);
});

test("end tells everyone, closes every socket and wipes the room", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const host = await connect(ctx, room, { hostKey: "KEY" });
    const guest = await connect(ctx, room, {});
    await room.webSocketMessage(host, JSON.stringify({ t: "end" }));
    assert.ok(guest.sent.some((m) => m.t === "ended"));
    assert.equal(guest.closeCode, 4000);
    assert.equal(ctx.store.size, 0);
    assert.equal(ctx.alarm, null);
});

test("the alarm hands a vanished host's room to the next member", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const host = await connect(ctx, room, { hostKey: "KEY" });
    clock.t = 2_000;
    const guest = await connect(ctx, room, {});
    const guestId = guest.deserializeAttachment().memberId;
    host.closed = true;
    await room.webSocketClose(host, 1006, "gone");
    assert.equal(ctx.alarm, 2_000 + 60_000);
    clock.t = 62_000;
    await room.alarm();
    assert.equal(guest.sent.filter((m) => m.t === "state").at(-1).state.host, guestId);
});

test("an alarm that changes nothing re-arms, so an idle room still closes", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    ctx.alarm = null; // alarms are one-shot: this one has just fired
    clock.t = 5_000;
    await room.alarm();
    assert.equal(ctx.alarm, 1_000 + 5 * 60_000, "re-armed for the empty-room close");
    clock.t = ctx.alarm;
    await room.alarm();
    assert.equal(ctx.store.size, 0);
});

test("the socket route refuses an upgrade once 20 sockets are open, resume or not", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    // Sockets that never send hello hold no member slot, so only this cap stops them piling up.
    for (let i = 0; i < 20; i++) ctx.acceptWebSocket(fakeSocket());
    const r = await room.fetch(new Request("https://room/ws?r=1", { headers: { Upgrade: "websocket" } }));
    assert.equal(r.status, 409);
});

test("the socket route takes the Upgrade header in any case, as the Worker does", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    for (let i = 0; i < 20; i++) ctx.acceptWebSocket(fakeSocket());
    // Past the 426 check (here to the full-room 409), not refused as "no upgrade".
    const r = await room.fetch(new Request("https://room/ws?r=1", { headers: { Upgrade: "WebSocket" } }));
    assert.equal(r.status, 409);
    assert.equal((await room.fetch(new Request("https://room/ws?r=1"))).status, 426);
});

test("an oversized frame closes with 1009 before it is parsed", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const ws = fakeSocket();
    ctx.acceptWebSocket(ws);
    await room.webSocketMessage(ws, JSON.stringify({ t: "hello", name: "x".repeat(262_144) }));
    assert.equal(ws.closeCode, 1009);
    assert.equal(ws.sent.length, 0);
    const bin = fakeSocket();
    ctx.acceptWebSocket(bin);
    await room.webSocketMessage(bin, new ArrayBuffer(262_145));
    assert.equal(bin.closeCode, 1009);
});

test("a host key over 64 characters is never accepted, even when it is the right one", async () => {
    const clock = { t: 1_000 };
    const ctx = fakeCtx();
    const room = new ListenRoom(ctx, {}, () => clock.t);
    const longKey = "K".repeat(65);
    await room.apply({ type: "create", code: "ABCDEF", hostName: "Rawn", keyHash: await sha256Hex(longKey) });
    const ws = await connect(ctx, room, { hostKey: longKey });
    const welcome = ws.sent.find((m) => m.t === "welcome");
    assert.notEqual(welcome.state.host, welcome.memberId);
});

test("a full socket cap evicts unbound sockets idle over 10 s, then accepts", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const saved = { Pair: globalThis.WebSocketPair, Response: globalThis.Response };
    globalThis.WebSocketPair = function () { return [fakeSocket(), fakeSocket()]; };
    globalThis.Response = function (body, init) { return { status: init.status }; };
    try {
        const upgrade = () => room.fetch(new Request("https://room/ws?r=1", { headers: { Upgrade: "websocket" } }));
        for (let i = 0; i < 20; i++) assert.equal((await upgrade()).status, 101);
        const idle = ctx.getWebSockets();
        assert.equal((await upgrade()).status, 409, "fresh unbound sockets are not evicted");
        clock.t += 11_000;
        assert.equal((await upgrade()).status, 101);
        assert.ok(idle.every((ws) => ws.closed), "the stale ones were closed");
    } finally {
        globalThis.WebSocketPair = saved.Pair;
        globalThis.Response = saved.Response;
    }
});

test("setAlarm is only written when the alarm time moves", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    await connect(ctx, room, { hostKey: "KEY" });
    const writes = ctx.alarmWrites;
    await connect(ctx, room, {}); // state changes, the 12 h expiry doesn't
    assert.equal(ctx.alarmWrites, writes);
});

test("a guest's 25 songs in one add are one message: the socket stays open and 20 go in", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const host = await connect(ctx, room, { hostKey: "KEY", v: 2, guestsAdd: true });
    const guest = await connect(ctx, room, { name: "Sam", v: 2 });
    const tracks = Array.from({ length: 25 }, (_, i) => ({ t: `S${i}`, a: "A" }));
    await room.webSocketMessage(guest, JSON.stringify({ t: "add", tracks, next: true }));
    assert.equal(guest.closed, false);
    assert.equal(host.sent.filter((m) => m.t === "queue").at(-1).queue.length, 20);
    assert.deepEqual(host.sent.filter((m) => m.t === "added").map((m) => m.count), [20]);
});

test("with guest adds off, an add's songs become suggestions with ids of their own", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const host = await connect(ctx, room, { hostKey: "KEY", v: 2, guestsAdd: false });
    const guest = await connect(ctx, room, { v: 2 });
    await room.webSocketMessage(guest, JSON.stringify({ t: "add", tracks: [{ t: "A", a: "B" }, { t: "C", a: "D" }], next: true }));
    const ids = host.sent.filter((m) => m.t === "suggestions").at(-1).suggestions.map((x) => x.id);
    assert.equal(ids.length, 2);
    assert.notEqual(ids[0], ids[1]);
});

test("a client looping add and unqueue is ignored past the burst, not closed; real use never meets the limit", async () => {
    const clock = { t: 1_000 };
    const { ctx, room } = await openRoom(clock);
    const host = await connect(ctx, room, { hostKey: "KEY", v: 2, guestsAdd: true });
    const guest = await connect(ctx, room, { name: "Sam", v: 2 });
    const add = (t) => room.webSocketMessage(guest, JSON.stringify({ t: "add", tracks: [{ t, a: "A" }], next: true }));
    const queues = () => host.sent.filter((m) => m.t === "queue").length;
    for (let i = 0; i < EDIT_BURST; i++) await add(`S${i}`);
    assert.equal(queues(), EDIT_BURST, "a burst all goes in");
    await add("over");
    assert.equal(guest.closed, false);
    assert.equal(queues(), EDIT_BURST, "nobody gets another whole queue");
    assert.deepEqual(guest.sent.at(-1), { t: "added", by: guest.deserializeAttachment().memberId, next: true, count: 0 }, "the sender still gets its answer");
    assert.equal(host.sent.filter((m) => m.t === "added").at(-1).count, 1, "only the sender heard that one");
    await room.webSocketMessage(guest, JSON.stringify({ t: "unqueue", track: { t: "S0", a: "A", by: guest.deserializeAttachment().memberId }, at: 0 }));
    assert.equal(queues(), EDIT_BURST, "a swipe counts too");
    clock.t += 1_000 / EDITS_PER_SECOND;
    await add("later");
    assert.equal(queues(), EDIT_BURST + 1, "and it comes back at the steady rate");
    await room.webSocketMessage(guest, JSON.stringify({ t: "ping", c: 1 }));
    assert.ok(guest.sent.some((m) => m.t === "pong"), "other messages aren't held up");
});
