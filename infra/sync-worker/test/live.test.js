/**
 * Live control (src/live.js, spec §6.7, sync-v1 §5.7): the `ws` route, hello-first auth checked like the REST routes, what a socket
 * that never authenticates may cost, fan-out of `state`, addressed `cmd`, the direction rules, the per-socket rate limit and frame
 * cap, idle sockets, the socket cap, and membership changes closing sockets.
 *
 * As in the runtime, a socket the object closes stays in getWebSockets() until the client answers the close (`answered`).
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { box, keyBox, makeDevice, req, world } from "./fakes.js";
import { joinBrowser, linkNew, newSpaceId } from "./link.js";
import { CLOSE, LIVE } from "../src/live.js";

const upgrade = { headers: { Upgrade: "websocket" } };

/** Opens the space's socket through the Worker (`player`: as the player forwards a browser's) and says hello as `device`. */
async function connect(w, spaceId, device, { player = false, hello = true } = {}) {
    const res = await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, { player, ...upgrade }));
    assert.equal(res.status, 101);
    const ws = res.webSocket.server;
    if (hello) await frame(w, spaceId, ws, { t: "hello", device: device.id, token: device.token });
    return ws;
}
const frame = (w, spaceId, ws, msg) => w.env.SPACES.objects.get(spaceId).webSocketMessage(ws, typeof msg === "string" ? msg : JSON.stringify(msg));
/** The client answers the object's close: the runtime calls webSocketClose, then drops the socket. */
async function answered(w, spaceId, ws) {
    await w.env.SPACES.objects.get(spaceId).webSocketClose(ws, ws.closeCode ?? 1000, ws.closeReason ?? "");
    ws.gone = true;
}

test("the ws route: GET with an upgrade only, the space id checked, the caller's rate limit applied", async () => {
    const w = world({ limits: { api: 1000 } });
    const { phone, spaceId } = await linkNew(w);
    assert.equal((await w.fetch(req("POST", `/v1/spaces/${spaceId}/ws`, upgrade))).status, 405);
    assert.equal((await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, { device: phone }))).status, 426);
    assert.equal((await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws?d=1`, upgrade))).status, 400);
    assert.equal((await w.fetch(req("GET", "/v1/spaces/short/ws", upgrade))).status, 404);
    assert.equal((await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, { headers: { Upgrade: "WebSocket" } }))).status, 101, "case-insensitive");
    const limited = world({ limits: { api: 0 } });
    const res = await limited.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, upgrade));
    assert.equal(res.status, 429);
    assert.equal((await res.json()).error.code, "rate_limited");
});

test("hello: a member's token binds the socket; a wrong token, a stranger, another space's member are all 4401 revoked", async () => {
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

test("a space that doesn't exist: its first frame closes it 4401, as a revoked device's hello; one that never speaks goes at the next upgrade", async () => {
    const w = world();
    const made = newSpaceId();
    const res = await w.fetch(req("GET", `/v1/spaces/${made}/ws`, upgrade));
    assert.equal(res.status, 101, "answered like a space whose device was revoked, not a 404 (no probing which ids exist)");
    const ws = res.webSocket.server;
    const phone = await makeDevice("p");
    await frame(w, made, ws, { t: "hello", device: phone.id, token: phone.token });
    assert.equal(ws.closeCode, CLOSE.revoked);
    assert.equal(ws.sent.length, 0);
    await frame(w, made, ws, { t: "hello", device: phone.id, token: phone.token });
    assert.equal(ws.sent.length, 0, "its later frames wake nothing");
    const silent = (await w.fetch(req("GET", `/v1/spaces/${made}/ws`, upgrade))).webSocket.server;
    assert.equal(silent.closed, false);
    await w.fetch(req("GET", `/v1/spaces/${made}/ws`, upgrade)); // any upgrade sweeps
    assert.equal(silent.closeCode, CLOSE.idle);
    assert.equal(w.env.SPACES.ctxs.get(made).sql.exec("SELECT name FROM sqlite_master WHERE type = 'table'").toArray().length, 0, "nothing stored");
});

test("before hello, one frame decides: junk, a state, a malformed hello, a late hello or a second frame close it (4401)", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const junk = await connect(w, spaceId, null, { hello: false });
    await frame(w, spaceId, junk, "not json");
    assert.equal(junk.closeCode, CLOSE.revoked);
    await frame(w, spaceId, junk, { t: "hello", device: phone.id, token: phone.token });
    assert.equal(junk.of("ready").length, 0, "a closed socket's later frames change nothing");

    const state = await connect(w, spaceId, null, { player: true, hello: false });
    await frame(w, spaceId, state, { t: "state", env: box(1) });
    assert.equal(state.closeCode, CLOSE.revoked);

    const shapeless = await connect(w, spaceId, null, { hello: false });
    await frame(w, spaceId, shapeless, { t: "hello", device: "nope", token: phone.token });
    assert.equal(shapeless.closeCode, CLOSE.revoked);

    const late = await connect(w, spaceId, null, { hello: false });
    w.clock.t += LIVE.helloMs + 1;
    await frame(w, spaceId, late, { t: "hello", device: phone.id, token: phone.token });
    assert.equal(late.closeCode, CLOSE.revoked);
    assert.equal(late.of("ready").length, 0);

    const twice = await connect(w, spaceId, null, { player: true, hello: false });
    const hello = frame(w, spaceId, twice, { t: "hello", device: browser.id, token: browser.token }); // still hashing...
    await frame(w, spaceId, twice, { t: "state", env: box(1) }); // ...when a second frame comes
    await hello;
    assert.equal(twice.closeCode, CLOSE.revoked);
    assert.equal(twice.of("ready").length, 0, "the hello in flight doesn't bind a closed socket");

    // A good one still works, and only the player's sockets may be browsers (the object's own header can't be sent in).
    assert.equal((await connect(w, spaceId, browser)).closeCode, CLOSE.forbidden);
    assert.equal((await connect(w, spaceId, browser, { player: true })).of("ready").length, 1);
    const spoof = await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, { headers: { Upgrade: "websocket", "X-Stash-Live-Player": "1" } }));
    await frame(w, spaceId, spoof.webSocket.server, { t: "hello", device: browser.id, token: browser.token });
    assert.equal(spoof.webSocket.server.closeCode, CLOSE.forbidden);
});

test("fan-out: a browser's state reaches the phone only, a phone's command reaches only the browser it names", async () => {
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
    assert.equal(b2.of("state").length, 0, "a browser's state is for phones only");

    const cmd = box(1);
    await frame(w, spaceId, p, { t: "cmd", to: second.id, env: cmd });
    assert.deepEqual(b2.of("cmd"), [{ t: "cmd", from: phone.id, env: cmd }]);
    assert.equal(b1.of("cmd").length, 0, "only the device it names");

    // Unknown fields are dropped: what goes out is rebuilt from e, n, c.
    await frame(w, spaceId, p, { t: "cmd", to: browser.id, env: { ...cmd, extra: "x" } });
    assert.deepEqual(b1.of("cmd").at(-1).env, cmd);

    // Direction: a browser can't command, a phone has no state to relay, a command can't go to a phone.
    await frame(w, spaceId, b1, { t: "cmd", to: second.id, env: box(1) });
    assert.deepEqual(b1.of("error").at(-1), { t: "error", code: "bad_request" });
    assert.equal(b2.of("cmd").length, 1);
    await frame(w, spaceId, p, { t: "state", env: box(1) });
    assert.deepEqual(p.of("error").at(-1), { t: "error", code: "bad_request" });
    assert.equal(b1.of("state").length + b2.of("state").length, 0);

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
    assert.deepEqual(p.of("error").map((e) => e.code), ["bad_request", "bad_request", "bad_request"]);
    await frame(w, spaceId, b, { t: "state", env: box(1, 20 * 1024) });
    assert.deepEqual(b.of("error").map((e) => e.code), ["bad_request"], "an envelope over 16 KiB");
    await frame(w, spaceId, p, { t: "cmd", to: browser.id, env: box(2) });
    assert.deepEqual(p.of("error").at(-1), { t: "error", code: "epoch", epoch: 1 });
    await frame(w, spaceId, p, { t: "future", env: box(1) });
    assert.equal(b.sent.filter((m) => m.t !== "peer" && m.t !== "ready" && m.t !== "error").length, 0, "nothing reached the browser");
    assert.equal(p.of("state").length, 0);
    assert.equal(w.env.SPACES.ctxs.get(spaceId).sql.exec("SELECT bytesUsed FROM meta").toArray()[0].bytesUsed, before);

    const third = (await joinBrowser(w, spaceId, phone)).device;
    assert.equal((await w.fetch(req("DELETE", `/v1/spaces/${spaceId}/devices/${third.id}`, { device: phone }))).status, 204);
    await frame(w, spaceId, p, { t: "cmd", to: browser.id, env: box(1) });
    assert.deepEqual(p.of("error").at(-1), { t: "error", code: "rotation_due" });
});

test("a second socket of the same device replaces the first (4409): the others hear it join again, never leave", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const b = await connect(w, spaceId, browser, { player: true });
    const p1 = await connect(w, spaceId, phone);
    const peersBefore = b.of("peer").length;
    const p2 = await connect(w, spaceId, phone); // the phone came back from the background
    assert.equal(p1.closeCode, CLOSE.replaced);
    assert.equal(b.of("peer").length, peersBefore + 1);
    assert.deepEqual(b.of("peer").at(-1), { t: "peer", device: phone.id, type: "phone", on: true }, "a new session: the browser speaks again");
    await answered(w, spaceId, p1);
    assert.equal(b.of("peer").filter((x) => !x.on).length, 0, "the phone never left");
    await frame(w, spaceId, b, { t: "state", env: box(1) });
    assert.equal(p2.of("state").at(-1).from, browser.id);
    assert.equal(p1.of("state").length, 0);

    const envelopes = { [phone.id]: keyBox(2, phone), [browser.id]: keyBox(2, browser) };
    assert.equal((await w.fetch(req("POST", `/v1/spaces/${spaceId}/rotate`, { device: phone, body: { epoch: 2, envelopes } }))).status, 200);
    assert.deepEqual(p2.of("epoch"), [{ t: "epoch", epoch: 2 }]);
    assert.deepEqual(b.of("epoch"), [{ t: "epoch", epoch: 2 }]);
});

test("rate limit: more than 10 frames in a second closes the socket (1008); a frame over 32 KiB closes it (1009)", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const p = await connect(w, spaceId, phone);
    const b = await connect(w, spaceId, browser, { player: true });
    for (let i = 0; i < LIVE.perSecond; i++) await frame(w, spaceId, b, { t: "state", env: box(1) });
    assert.equal(b.closed, false);
    w.clock.t += 1000;
    for (let i = 0; i < LIVE.perSecond; i++) await frame(w, spaceId, b, { t: "state", env: box(1) });
    assert.equal(b.closed, false, "a new second starts over");
    await frame(w, spaceId, b, { t: "state", env: box(1) });
    assert.equal(b.closeCode, CLOSE.flood);
    assert.deepEqual(p.of("peer").at(-1), { t: "peer", device: browser.id, type: "web", on: false }, "the phone hears it went");
    await frame(w, spaceId, p, "x".repeat(LIVE.maxFrame + 1));
    assert.equal(p.closeCode, CLOSE.tooBig);
});

test("idle sockets: no hello in 10 s, or no frame and no ping for 90 s, closed by the sweep (an upgrade, or a frame every 10 s)", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const silent = await connect(w, spaceId, null, { hello: false });
    const p = await connect(w, spaceId, phone);
    const b = await connect(w, spaceId, browser, { player: true });
    w.clock.t += LIVE.helloMs + 1;
    await frame(w, spaceId, b, { t: "state", env: box(1) }); // a frame sweeps too
    assert.equal(silent.closeCode, CLOSE.idle);
    assert.equal(p.closed, false);
    const silent2 = await connect(w, spaceId, null, { hello: false }); // the upgrade swept
    w.clock.t += LIVE.helloMs;
    await frame(w, spaceId, b, { t: "state", env: box(1) }); // this frame sweeps; silent2 isn't late yet
    w.clock.t += 1;
    await frame(w, spaceId, b, { t: "state", env: box(1) });
    assert.equal(silent2.closed, false, "late now, but a frame sweeps at most every 10 s");
    w.clock.t += LIVE.sweepMs;
    await frame(w, spaceId, b, { t: "state", env: box(1) });
    assert.equal(silent2.closeCode, CLOSE.idle);

    w.clock.t += LIVE.idleMs;
    b.pingedAt = w.clock.t; // the runtime answered the browser's ping
    await connect(w, spaceId, null, { hello: false }); // an upgrade always sweeps
    assert.equal(p.closeCode, CLOSE.idle, "the phone went quiet");
    assert.equal(b.closed, false, "the browser kept pinging");
    assert.deepEqual(b.of("peer").at(-1), { t: "peer", device: phone.id, type: "phone", on: false });
});

test("the socket cap: a full space makes room by closing the oldest socket still saying hello, never a member's", async () => {
    const w = world();
    const { phone, browser, spaceId } = await linkNew(w);
    const p = await connect(w, spaceId, phone);
    const waiting = [];
    for (let i = 1; i < LIVE.maxSockets; i++) {
        waiting.push(await connect(w, spaceId, null, { hello: false }));
        w.clock.t += 10;
    }
    // A removed device that knows the space id fills it with sockets that never authenticate...
    const b = await connect(w, spaceId, browser, { player: true }); // ...and the real browser still gets in
    assert.equal(b.of("ready").length, 1);
    assert.equal(waiting[0].closeCode, CLOSE.idle, "the oldest unauthenticated socket made room");
    assert.equal(waiting.filter((ws) => ws.closed).length, 1);
    assert.equal(p.closed, false, "a member's socket is never the one to go");
    // The closed one is still listed until its client answers, yet counts for nothing.
    assert.equal(w.env.SPACES.ctxs.get(spaceId).getWebSockets().length, LIVE.maxSockets + 1);
    const again = await connect(w, spaceId, phone);
    assert.equal(again.of("ready").length, 1);
    assert.equal(waiting.filter((ws) => ws.closed).length, 2);
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
    await answered(w, spaceId, b);
    assert.equal(p.of("peer").filter((x) => !x.on).length, 1, "told once, not again on the close handshake");
    const again = await connect(w, spaceId, browser, { player: true });
    assert.equal(again.closeCode, CLOSE.revoked, "its token no longer works");

    assert.equal((await w.fetch(req("DELETE", `/v1/spaces/${spaceId}`, { device: phone }))).status, 204);
    assert.equal(p.closeCode, CLOSE.revoked);
});
