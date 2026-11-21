import { test } from "node:test";
import assert from "node:assert/strict";
import { ListenRoom } from "../src/listen-room.js";
import { sha256Hex } from "../src/store.js";
import { fakeCtx, fakeSocket } from "./fake-room.js";

/** A valid Community key (43 base64url characters), one per [n]. */
const communityKey = (n) => `${n}`.padStart(43, "k");

/** A session for station STATION1, host key "KEY". [env] is the room's env (COMMUNITY_DB for the live directory). */
async function openSession(clock, env = {}) {
    const ctx = fakeCtx();
    const room = new ListenRoom(ctx, env, () => clock.t);
    await room.apply({ type: "create", code: "ABCDEFGH", hostName: "Rawn", keyHash: await sha256Hex("KEY"), stationId: "STATION1" });
    return { ctx, room };
}

async function connect(ctx, room, hello) {
    const ws = fakeSocket();
    ctx.acceptWebSocket(ws);
    await room.webSocketMessage(ws, JSON.stringify({ t: "hello", v: 3, ...hello }));
    return ws;
}

// ── Task 12: who you are, from the shell ──────────────────────────────────────

test("a listener's Community key reaches the room only as its hash, and no frame carries either", async () => {
    const { ctx, room } = await openSession({ t: 1_000 });
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const key = communityKey(1);
    const listener = await connect(ctx, room, { name: "Sam", communityKey: key });
    const hash = await sha256Hex(key);
    const stored = await ctx.storage.get("room");
    assert.equal(stored.members.find((m) => m.name === "Sam").kh, hash);
    assert.ok(!JSON.stringify(stored).includes(key), "the key itself is never stored");
    const wire = JSON.stringify([...host.sent, ...listener.sent]);
    assert.ok(listener.sent.some((m) => m.t === "welcome"));
    assert.ok(!wire.includes(key) && !wire.includes(hash), "nor sent, nor its hash");
});

test("without a valid Community key a session closes the socket with 4401; an older app gets 4426", async () => {
    const { ctx, room } = await openSession({ t: 1_000 });
    assert.equal((await connect(ctx, room, { communityKey: "too-short" })).closeCode, 4401);
    assert.equal((await connect(ctx, room, {})).closeCode, 4401);
    const old = fakeSocket();
    ctx.acceptWebSocket(old);
    await room.webSocketMessage(old, JSON.stringify({ t: "hello", communityKey: communityKey(2) }));
    assert.equal(old.closeCode, 4426);
    assert.equal((await ctx.storage.get("room")).members.length, 0);
});

test("a session's socket route lets 54 members in, then answers 409", async () => {
    const { ctx, room } = await openSession({ t: 1_000 });
    await connect(ctx, room, { hostKey: "KEY" });
    for (let i = 1; i < 54; i++) await connect(ctx, room, { communityKey: communityKey(i) });
    assert.equal((await ctx.storage.get("room")).members.length, 54);
    const r = await room.fetch(new Request("https://room/ws", { headers: { Upgrade: "websocket" } }));
    assert.equal(r.status, 409);
    const late = await connect(ctx, room, { communityKey: communityKey(99) });
    assert.equal(late.closeCode, 4409, "and a hello past that is full");
});

test("a session's socket cap is twice its 54, not a private room's 20", async () => {
    const { ctx, room } = await openSession({ t: 1_000 });
    const saved = { Pair: globalThis.WebSocketPair, Response: globalThis.Response };
    globalThis.WebSocketPair = function () { return [fakeSocket(), fakeSocket()]; };
    globalThis.Response = function (body, init) { return { status: init.status }; };
    try {
        const upgrade = () => room.fetch(new Request("https://room/ws?r=1", { headers: { Upgrade: "websocket" } }));
        for (let i = 0; i < 108; i++) assert.equal((await upgrade()).status, 101, `socket ${i}`);
        assert.equal((await upgrade()).status, 409);
    } finally {
        globalThis.WebSocketPair = saved.Pair;
        globalThis.Response = saved.Response;
    }
});

// ── Task 17: booting, in the shell ────────────────────────────────────────────

test("a booted listener's socket hears why, then closes with 4403; their key can't come back", async () => {
    const { ctx, room } = await openSession({ t: 1_000 });
    const host = await connect(ctx, room, { name: "Rawn", hostKey: "KEY" });
    const key = communityKey(5);
    const sam = await connect(ctx, room, { name: "Sam", communityKey: key });
    const samId = sam.sent.find((m) => m.t === "welcome").memberId;
    await room.webSocketMessage(host, JSON.stringify({ t: "boot", memberId: samId }));
    assert.ok(sam.sent.some((m) => m.t === "ended" && m.reason === "booted"));
    assert.equal(sam.closeCode, 4403);
    assert.equal(sam.deserializeAttachment().memberId, null, "unbound, so a late message can't act as them");
    await room.webSocketClose(sam, 4403, "booted");
    assert.equal(host.closed, false);
    const again = await connect(ctx, room, { name: "Sam", communityKey: key });
    assert.equal(again.closeCode, 4403);
});
