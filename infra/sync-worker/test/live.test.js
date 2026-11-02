/**
 * Live control (src/live.js, spec §6.7, sync-v1 §5.7): the `ws` route, hello-first auth checked like the REST routes, fan-out of
 * `state`, addressed `cmd`, the per-socket rate limit and frame cap, idle sockets, and membership changes closing sockets.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { box, keyBox, makeDevice, req, world } from "./fakes.js";
import { joinBrowser, linkNew } from "./link.js";
import { CLOSE, LIVE } from "../src/live.js";

/** Opens the space's socket through the Worker (`player`: as the player forwards a browser's) and says hello as `device`. */
async function connect(w, spaceId, device, { player = false, hello = true } = {}) {
    const res = await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, { player, headers: { Upgrade: "websocket" } }));
    assert.equal(res.status, 101);
    const ws = res.webSocket.server;
    const obj = w.env.SPACES.objects.get(spaceId);
    if (hello) await obj.webSocketMessage(ws, JSON.stringify({ t: "hello", device: device.id, token: device.token }));
    return ws;
}
const frame = (w, spaceId, ws, msg) => w.env.SPACES.objects.get(spaceId).webSocketMessage(ws, typeof msg === "string" ? msg : JSON.stringify(msg));

test("the ws route: GET with an upgrade only, the space id checked, the caller's rate limit applied", async () => {
    const w = world({ limits: { api: 1000 } });
    const { phone, spaceId } = await linkNew(w);
    const ws = { headers: { Upgrade: "websocket" } };
    assert.equal((await w.fetch(req("POST", `/v1/spaces/${spaceId}/ws`, ws))).status, 405);
    assert.equal((await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, { device: phone }))).status, 426);
    assert.equal((await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws?d=1`, ws))).status, 400);
    assert.equal((await w.fetch(req("GET", "/v1/spaces/short/ws", ws))).status, 404);
    assert.equal((await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, { ...ws, headers: { Upgrade: "WebSocket" } }))).status, 101, "case-insensitive");
    const limited = world({ limits: { api: 0 } });
    const res = await limited.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, ws));
    assert.equal(res.status, 429);
    assert.equal((await res.json()).error.code, "rate_limited");
});

test("hello: a member's token binds the socket; a wrong token, a stranger and an unknown space are all 4401 revoked", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const ok = await connect(w, spaceId, phone);
    assert.deepEqual(ok.of("ready"), [{ t: "ready", me: phone.id, epoch: 1, peers: [] }]);
    assert.equal(ok.closed, false);

    const wrong = await connect(w, spaceId, { id: phone.id, token: browser.token });
    assert.equal(wrong.closeCode, CLOSE.revoked);
    const stranger = await connect(w, spaceId, await makeDevice("p"));
    assert.equal(stranger.closeCode, CLOSE.revoked);
    const malformed = await connect(w, spaceId, { id: phone.id, token: "short" });
    assert.equal(malformed.closeCode, CLOSE.revoked);
    const other = (await linkNew(w)).spaceId;
    const elsewhere = await connect(w, other, phone);
    assert.equal(elsewhere.closeCode, CLOSE.revoked, "a space it isn't in answers like one that doesn't exist");
    assert.equal(ok.closed, false, "the phone's own socket is untouched");
});

test("hello: a browser's token works only through the player; frames before hello are ignored", async () => {
    const w = world();
    const { browser, spaceId } = await linkNew(w);
    const direct = await connect(w, spaceId, browser);
    assert.equal(direct.closeCode, CLOSE.forbidden);
    const viaPlayer = await connect(w, spaceId, browser, { player: true, hello: false });
    await frame(w, spaceId, viaPlayer, { t: "state", env: box(1) });
    await frame(w, spaceId, viaPlayer, "not json");
    assert.equal(viaPlayer.sent.length, 0);
    await frame(w, spaceId, viaPlayer, { t: "hello", device: browser.id, token: browser.token });
    assert.equal(viaPlayer.of("ready").length, 1);
    // A caller can't claim the player's route by sending the object's own header.
    const spoof = await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, { headers: { Upgrade: "websocket", "X-Stash-Live-Player": "1" } }));
    const ws = spoof.webSocket.server;
    await frame(w, spaceId, ws, { t: "hello", device: browser.id, token: browser.token });
    assert.equal(ws.closeCode, CLOSE.forbidden);
});

test("fan-out: peers come and go, a browser's state reaches the phone (not itself), a command reaches only the device it names", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const second = (await joinBrowser(w, spaceId, phone)).device;
    const b1 = await connect(w, spaceId, browser, { player: true });
    const b2 = await connect(w, spaceId, second, { player: true });
    const p = await connect(w, spaceId, phone);
    assert.deepEqual(p.of("ready")[0].peers.map((x) => x.device).sort(), [browser.id, second.id].sort());
    assert.deepEqual(b1.of("peer").at(-1), { t: "peer", device: phone.id, type: "phone", on: true });

    const env = box(1);
    await frame(w, spaceId, b1, { t: "state", env });
    assert.deepEqual(p.of("state"), [{ t: "state", from: browser.id, env }]);
    assert.equal(b1.of("state").length, 0, "never echoed");

    const cmd = box(1);
    await frame(w, spaceId, p, { t: "cmd", to: second.id, env: cmd });
    assert.deepEqual(b2.of("cmd"), [{ t: "cmd", from: phone.id, env: cmd }]);
    assert.equal(b1.of("cmd").length, 0, "only the device it names");

    // Unknown fields are dropped: what goes out is rebuilt from e, n, c.
    await frame(w, spaceId, p, { t: "cmd", to: browser.id, env: { ...cmd, extra: "x" } });
    assert.deepEqual(b1.of("cmd").at(-1).env, cmd);

    await w.env.SPACES.objects.get(spaceId).webSocketClose(b2, 1000, "bye");
    assert.deepEqual(p.of("peer").at(-1), { t: "peer", device: second.id, type: "web", on: false });
    await frame(w, spaceId, p, { t: "cmd", to: second.id, env: cmd });
    assert.deepEqual(p.of("error").at(-1), { t: "error", code: "offline", to: second.id });
});

test("frames are checked, never stored: a bad envelope, a command to itself, an old epoch, a removal waiting for its rotation", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const p = await connect(w, spaceId, phone);
    const b = await connect(w, spaceId, browser, { player: true });
    const before = w.env.SPACES.ctxs.get(spaceId).sql.exec("SELECT bytesUsed FROM meta").toArray()[0].bytesUsed;
    await frame(w, spaceId, p, { t: "cmd", to: browser.id, env: { e: 1, n: "x", c: "y" } });
    await frame(w, spaceId, p, { t: "cmd", to: phone.id, env: box(1) });
    await frame(w, spaceId, p, { t: "cmd", to: "x", env: box(1) });
    await frame(w, spaceId, p, { t: "state", env: box(1, 20 * 1024) });
    assert.deepEqual(p.of("error").map((e) => e.code), ["bad_request", "bad_request", "bad_request", "bad_request"]);
    await frame(w, spaceId, p, { t: "cmd", to: browser.id, env: box(2) });
    assert.deepEqual(p.of("error").at(-1), { t: "error", code: "epoch", epoch: 1 });
    await frame(w, spaceId, p, { t: "future", env: box(1) });
    assert.equal(b.sent.filter((m) => m.t !== "peer" && m.t !== "ready").length, 0, "nothing reached the browser");
    assert.equal(w.env.SPACES.ctxs.get(spaceId).sql.exec("SELECT bytesUsed FROM meta").toArray()[0].bytesUsed, before);

    const third = (await joinBrowser(w, spaceId, phone)).device;
    assert.equal((await w.fetch(req("DELETE", `/v1/spaces/${spaceId}/devices/${third.id}`, { device: phone }))).status, 204);
    await frame(w, spaceId, p, { t: "cmd", to: browser.id, env: box(1) });
    assert.deepEqual(p.of("error").at(-1), { t: "error", code: "rotation_due" });
});

test("a second socket of the same device replaces the first (4409, no peer-off); a rotation announces the epoch", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const p = await connect(w, spaceId, phone);
    const b1 = await connect(w, spaceId, browser, { player: true });
    const peersBefore = p.of("peer").length;
    const b2 = await connect(w, spaceId, browser, { player: true });
    assert.equal(b1.closeCode, CLOSE.replaced);
    await w.env.SPACES.objects.get(spaceId).webSocketClose(b1, CLOSE.replaced, "replaced");
    assert.equal(p.of("peer").length, peersBefore, "the browser never left");
    await frame(w, spaceId, b2, { t: "state", env: box(1) });
    assert.equal(p.of("state").at(-1).from, browser.id);

    const envelopes = { [phone.id]: keyBox(2, phone), [browser.id]: keyBox(2, browser) };
    assert.equal((await w.fetch(req("POST", `/v1/spaces/${spaceId}/rotate`, { device: phone, body: { epoch: 2, envelopes } }))).status, 200);
    assert.deepEqual(p.of("epoch"), [{ t: "epoch", epoch: 2 }]);
    assert.deepEqual(b2.of("epoch"), [{ t: "epoch", epoch: 2 }]);
});

test("rate limit: more than 10 frames in a second closes the socket (1008); a frame over 32 KiB closes it (1009)", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const p = await connect(w, spaceId, phone);
    const b = await connect(w, spaceId, browser, { player: true });
    // The hello was frame 1 of this second.
    for (let i = 1; i < LIVE.perSecond; i++) await frame(w, spaceId, b, { t: "state", env: box(1) });
    assert.equal(b.closed, false);
    w.clock.t += 1000;
    for (let i = 0; i < LIVE.perSecond; i++) await frame(w, spaceId, b, { t: "state", env: box(1) });
    assert.equal(b.closed, false, "a new second starts over");
    await frame(w, spaceId, b, { t: "state", env: box(1) });
    assert.equal(b.closeCode, CLOSE.flood);
    await frame(w, spaceId, p, "x".repeat(LIVE.maxFrame + 1));
    assert.equal(p.closeCode, CLOSE.tooBig);
});

test("idle sockets: no hello in 10 s, or no frame and no ping for 90 s, closed when the object next runs", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const silent = await connect(w, spaceId, phone, { hello: false });
    const p = await connect(w, spaceId, phone);
    const b = await connect(w, spaceId, browser, { player: true });
    w.clock.t += LIVE.helloMs + 1;
    b.pingedAt = w.clock.t; // the runtime answered the browser's ping
    await connect(w, spaceId, phone, { hello: false }); // any upgrade sweeps
    assert.equal(silent.closeCode, CLOSE.idle);
    assert.equal(p.closed, false);
    w.clock.t += LIVE.idleMs - LIVE.helloMs;
    b.pingedAt = w.clock.t;
    await connect(w, spaceId, phone, { hello: false });
    assert.equal(p.closeCode, CLOSE.idle, "the phone went quiet");
    assert.equal(b.closed, false, "the browser kept pinging");
    assert.deepEqual(b.of("peer").at(-1), { t: "peer", device: phone.id, type: "phone", on: false });
});

test("the socket cap: at most 10 per space; idle unbound ones make room", async () => {
    const w = world();
    const { phone, spaceId } = await linkNew(w);
    for (let i = 0; i < LIVE.maxSockets; i++) await connect(w, spaceId, phone, { hello: false });
    const full = await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, { headers: { Upgrade: "websocket" } }));
    assert.equal(full.status, 409);
    w.clock.t += LIVE.helloMs + 1;
    assert.equal((await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, { headers: { Upgrade: "websocket" } }))).status, 101);
});

test("membership: a removed device's socket closes at once (4401); unlinking everything closes every socket", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const p = await connect(w, spaceId, phone);
    const b = await connect(w, spaceId, browser, { player: true });
    assert.equal((await w.fetch(req("DELETE", `/v1/spaces/${spaceId}/devices/${browser.id}`, { device: phone }))).status, 204);
    assert.equal(b.closeCode, CLOSE.revoked);
    assert.deepEqual(p.of("peer").at(-1), { t: "peer", device: browser.id, type: "web", on: false });
    assert.equal(p.closed, false);
    const again = await connect(w, spaceId, browser, { player: true });
    assert.equal(again.closeCode, CLOSE.revoked, "its token no longer works");

    assert.equal((await w.fetch(req("DELETE", `/v1/spaces/${spaceId}`, { device: phone }))).status, 204);
    assert.equal(p.closeCode, CLOSE.revoked);
});
