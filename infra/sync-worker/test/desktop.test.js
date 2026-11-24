/**
 * Stash on a computer (device type `desktop`): it opens its own code at /v1/desktop/pair (no player, a quota per IP), makes its
 * pairing calls directly, joins a space as a browser does, uses its token directly, says what it plays over the live socket and
 * takes a phone's commands. A browser's own calls still go only through the player.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import { box, makeDevice, record, req, world } from "./fakes.js";
import { answerBody, linkNew, newSpaceId, readLabel } from "./link.js";

const upgrade = { headers: { Upgrade: "websocket" } };
const frame = (w, spaceId, ws, msg) => w.env.SPACES.objects.get(spaceId).webSocketMessage(ws, JSON.stringify(msg));
async function connect(w, spaceId, device) {
    const res = await w.fetch(req("GET", `/v1/spaces/${spaceId}/ws`, upgrade));
    assert.equal(res.status, 101);
    const ws = res.webSocket.server;
    await frame(w, spaceId, ws, { t: "hello", device: device.id, token: device.token });
    return ws;
}

async function openDesktopCode(w, desktop, ip) {
    const res = await w.fetch(req("POST", "/v1/desktop/pair", { body: { device: record(desktop) }, ...(ip ? { ip } : {}) }));
    assert.equal(res.status, 201);
    return (await res.json()).pairId;
}

/** A phone with no space links a desktop: the desktop's code, the phone's answer, the desktop's confirmation, the new space. */
async function linkDesktop(w) {
    const phone = await makeDevice("p");
    const desktop = await makeDevice("c");
    const pairId = await openDesktopCode(w, desktop);
    const label = await readLabel(w, pairId);
    assert.equal(label.browser.id, desktop.id);
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }))).status, 201);
    const poll = await w.fetch(req("GET", `/v1/pair/${pairId}`, { device: desktop }));
    assert.equal(poll.status, 200, "the desktop waits for the answer itself");
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/reply`, { device: desktop, body: { ct: box() } }))).status, 201);
    const spaceId = newSpaceId();
    const made = await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, spaceId, labels: { [phone.id]: box(1), [desktop.id]: box(1) } } }));
    assert.equal(made.status, 201);
    return { phone, desktop, spaceId };
}

test("a desktop opens its own code, waits and confirms without the player, and its token works directly", async () => {
    const w = world();
    const { phone, desktop, spaceId } = await linkDesktop(w);
    const space = await w.fetch(req("GET", `/v1/spaces/${spaceId}`, { device: desktop }));
    assert.equal(space.status, 200);
    const devices = (await space.json()).devices.map((d) => [d.id, d.type]);
    assert.deepEqual(devices.sort(), [[desktop.id, "desktop"], [phone.id, "phone"]].sort());
    // A desktop's code is opened only with a desktop's record, and only by POST.
    assert.equal((await w.fetch(req("GET", "/v1/desktop/pair"))).status, 405);
    assert.equal((await w.fetch(req("POST", "/v1/desktop/pair", { body: { device: { id: "x" } } }))).status, 400);
});

test("a browser's code still answers only through the player: its poll and reply straight to the server are refused", async () => {
    const w = world();
    const browser = await makeDevice("w");
    const open = await w.fetch(req("POST", "/v1/pair", { player: true, body: { device: record(browser) }, headers: { "X-Stash-Session": "sess_abcdef12" } }));
    const { pairId } = await open.json();
    await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(await makeDevice("p")) }));
    const poll = await w.fetch(req("GET", `/v1/pair/${pairId}`, { device: browser }));
    assert.equal(poll.status, 403);
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/reply`, { device: browser, body: { ct: box() } }))).status, 403);
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}`, { device: browser, player: true }))).status, 200);
    // And without the player, /v1/pair is still closed.
    assert.equal((await w.fetch(req("POST", "/v1/pair", { body: { device: record(browser) } }))).status, 403);
    // Someone else's token can't wait on a desktop's code either.
    const desktop = await makeDevice("c");
    const other = await openDesktopCode(w, desktop);
    assert.equal((await w.fetch(req("GET", `/v1/pair/${other}`, { device: browser }))).status, 403);
});

test("desktop codes are counted per IP address: 6 in 10 minutes", async () => {
    const w = world();
    const d = await makeDevice("c");
    for (let i = 0; i < 6; i++) await openDesktopCode(w, d, "198.51.100.9");
    const seventh = await w.fetch(req("POST", "/v1/desktop/pair", { body: { device: record(d) }, ip: "198.51.100.9" }));
    assert.equal(seventh.status, 429);
    await openDesktopCode(w, d, "198.51.100.10");
});

test("a phone adds a desktop to the space it has with a browser; browsers and desktops share four places", async () => {
    const w = world();
    const { phone, spaceId } = await linkNew(w);
    const add = async () => {
        const desktop = await makeDevice("c");
        const pairId = await openDesktopCode(w, desktop, `198.51.100.${Math.floor(Math.random() * 200)}`);
        await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }));
        await w.fetch(req("POST", `/v1/pair/${pairId}/reply`, { device: desktop, body: { ct: box() } }));
        return { desktop, res: await w.fetch(req("POST", `/v1/spaces/${spaceId}/devices`, { device: phone, body: { pairId, epoch: 1, labelCt: box(1) } })) };
    };
    for (let i = 0; i < 3; i++) {
        const { res } = await add();
        assert.equal(res.status, 201);
        assert.equal((await res.json()).type, "desktop");
    }
    const { res } = await add();
    assert.equal(res.status, 409);
    assert.match((await res.json()).error.message, /4 browsers and computers/);
});

test("live: a desktop's state reaches the phone, the phone's command reaches the desktop, and the desktop can't send commands", async () => {
    const w = world();
    const { phone, desktop, spaceId } = await linkDesktop(w);
    const p = await connect(w, spaceId, phone);
    const d = await connect(w, spaceId, desktop);
    assert.deepEqual(p.of("peer").at(-1), { t: "peer", device: desktop.id, type: "desktop", on: true });
    const env = box(1);
    await frame(w, spaceId, d, { t: "state", env });
    assert.deepEqual(p.of("state"), [{ t: "state", from: desktop.id, env }]);
    await frame(w, spaceId, p, { t: "cmd", to: desktop.id, env });
    assert.deepEqual(d.of("cmd"), [{ t: "cmd", from: phone.id, env }]);
    await frame(w, spaceId, d, { t: "cmd", to: phone.id, env });
    assert.equal(p.of("cmd").length, 0);
    assert.deepEqual(d.of("error").at(-1), { t: "error", code: "bad_request" });
});
