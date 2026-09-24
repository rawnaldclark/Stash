import { test } from "node:test";
import assert from "node:assert/strict";
import { ListenRoom, MSG_PER_SECOND } from "../src/listen-room.js";
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
