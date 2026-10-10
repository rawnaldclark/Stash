import { test } from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { base64url } from "../src/http.js";
import { PAIR_TTL_MS, CLAIM_GRACE_MS, POLL_MS, answerSlot, claimSlot, openSlot, pollSlot, readLabel } from "../src/pair.js";
import { box, makeDevice, record, req, world } from "./fakes.js";

const spaceIdNew = () => base64url(webcrypto.getRandomValues(new Uint8Array(16)));

/** Opens a code in the browser (through the player), and returns its id. */
async function openCode(w, browser, headers = { "X-Stash-Session": "sess_abcdef12" }) {
    const res = await w.fetch(req("POST", "/v1/pair", { player: true, body: { device: record(browser) }, headers }));
    assert.equal(res.status, 201);
    const body = await res.json();
    assert.match(body.pairId, /^[A-Za-z0-9_-]{22}$/);
    return body;
}

const answerBody = (phone) => ({ phonePub: phone.pub, ct: box(), device: record(phone) });

/** The whole first link (§5.1, neither device has a space): browser code, phone answer, browser poll, phone creates. */
export async function linkNew(w) {
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    const { pairId } = await openCode(w, browser);
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }))).status, 201);
    const spaceId = spaceIdNew();
    const created = await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, spaceId } }));
    assert.equal(created.status, 201);
    assert.deepEqual(await created.json(), { spaceId, epoch: 1 });
    return { phone, browser, spaceId, pairId };
}

test("a first link: code, label, answer, poll, space; both devices are members and nothing is cached", async () => {
    const w = world();
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    const { pairId, expiresAt } = await openCode(w, browser);
    assert.equal(expiresAt, w.clock.t + PAIR_TTL_MS);

    const label = await w.fetch(req("GET", `/v1/pair/${pairId}/label`));
    assert.equal(label.status, 200);
    assert.equal(label.headers.get("cache-control"), "no-store");
    assert.ok(label.headers.get("x-stash-server-time"));
    assert.deepEqual((await label.json()).labelCt, browser.labelCt);

    const a = answerBody(phone);
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: a }))).status, 201);
    const poll = await w.fetch(req("GET", `/v1/pair/${pairId}`, { player: true, device: browser }));
    assert.equal(poll.status, 200);
    assert.deepEqual(await poll.json(), { phonePub: phone.pub, ct: a.ct });

    const spaceId = spaceIdNew();
    assert.equal((await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, spaceId } }))).status, 201);
    for (const d of [phone, browser]) {
        const res = await w.fetch(req("GET", `/v1/spaces/${spaceId}`, { device: d, player: d === browser }));
        assert.equal(res.status, 200);
        assert.equal(res.headers.get("cache-control"), "no-store");
        const info = await res.json();
        assert.equal(info.me, d.id);
        assert.equal(info.epoch, 1);
        assert.equal(info.rotationDue, false);
        assert.deepEqual(info.devices.map((x) => [x.id, x.type]).sort(), [[browser.id, "web"], [phone.id, "phone"]].sort());
        assert.ok(!JSON.stringify(info).includes(d.tokenHash), "token hashes never leave the server");
    }
});

test("a code answers once and burns once; a second phone hears 'already used'", async () => {
    const w = world();
    const { pairId, phone, browser, spaceId } = await linkNew(w);
    const other = await makeDevice("p");
    const again = await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(other) }));
    assert.equal(again.status, 409);
    assert.equal((await again.json()).error.code, "used");
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}/label`))).status, 409);
    const twice = await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, spaceId: spaceIdNew() } }));
    assert.equal((await twice.json()).error.code, "used");
    const join = await w.fetch(req("POST", `/v1/spaces/${spaceId}/devices`, { device: phone, body: { pairId } }));
    assert.equal((await join.json()).error.code, "used");
    assert.ok(browser);
});

test("only the answering phone can create the space; only the browser can poll; a taken space id is refused", async () => {
    const w = world();
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    const stranger = await makeDevice("x");
    const { pairId } = await openCode(w, browser);
    assert.equal((await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, spaceId: spaceIdNew() } }))).status, 409, "no answer yet");
    await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }));
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}`, { player: true, device: stranger }))).status, 403);
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}`, { player: true, device: { ...browser, auth: `Stash-Device ${browser.id}:${stranger.token}` } }))).status, 403);
    assert.equal((await w.fetch(req("POST", "/v1/spaces", { device: stranger, body: { pairId, spaceId: spaceIdNew() } }))).status, 403);
    assert.equal((await w.fetch(req("POST", "/v1/spaces", { device: browser, body: { pairId, spaceId: spaceIdNew() } }))).status, 403);

    const taken = (await linkNew(w)).spaceId;
    const res = await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, spaceId: taken } }));
    assert.equal(res.status, 409);
    assert.equal((await res.json()).error.code, "exists");
});

test("a code expires after 3 minutes; the slot is deleted by its alarm", async () => {
    const w = world();
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    const { pairId } = await openCode(w, browser);
    const ctx = w.env.PAIRS.ctxs.get(pairId);
    assert.equal(ctx.alarm, w.clock.t + PAIR_TTL_MS + CLAIM_GRACE_MS);
    w.clock.t += PAIR_TTL_MS;
    const late = await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }));
    assert.equal(late.status, 410);
    assert.equal((await late.json()).error.code, "expired");
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}/label`))).status, 410);
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}`, { player: true, device: browser }))).status, 410);
    await w.env.PAIRS.objects.get(pairId).alarm();
    assert.equal(ctx.kv.size, 0);
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}/label`))).status, 410);
    // A code nobody opened answers the same and stores nothing.
    const ghost = base64url(webcrypto.getRandomValues(new Uint8Array(16)));
    assert.equal((await w.fetch(req("GET", `/v1/pair/${ghost}/label`))).status, 410);
    assert.equal(w.env.PAIRS.ctxs.get(ghost).kv.size, 0);
});

test("the browser's long-poll wakes on the answer, gives 204 after 25 s, and 410 once the code is dead", async () => {
    const w = world();
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    const { pairId } = await openCode(w, browser);

    const waiting = w.fetch(req("GET", `/v1/pair/${pairId}`, { player: true, device: browser }));
    await new Promise((r) => setTimeout(r, 10));
    assert.equal(w.timers.length, 1);
    assert.equal(w.timers[0].ms, POLL_MS);
    await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }));
    assert.equal((await waiting).status, 200);
    assert.equal(w.timers.length, 0, "the timer is cleared when the answer wakes the poll");

    const { pairId: idle } = await openCode(w, browser);
    const idleWait = w.fetch(req("GET", `/v1/pair/${idle}`, { player: true, device: browser }));
    await new Promise((r) => setTimeout(r, 10));
    w.fire();
    assert.equal((await idleWait).status, 204);
    w.clock.t += PAIR_TTL_MS;
    assert.equal((await w.fetch(req("GET", `/v1/pair/${idle}`, { player: true, device: browser }))).status, 410);
});

test("a phone with a space adds a browser; 4 browsers at most, refused before the code is burned", async () => {
    const w = world();
    const { phone, spaceId } = await linkNew(w);
    const browsers = [];
    for (let i = 0; i < 4; i++) {
        const b = await makeDevice("w");
        const { pairId } = await openCode(w, b);
        await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }));
        const res = await w.fetch(req("POST", `/v1/spaces/${spaceId}/devices`, { device: phone, body: { pairId } }));
        if (i < 3) {
            assert.equal(res.status, 201);
            assert.deepEqual(await res.json(), { device: b.id, type: "web" });
            browsers.push(b);
        } else {
            assert.equal(res.status, 409);
            assert.equal((await res.json()).error.code, "full");
            // The code is still good: remove a browser and try the same code again.
            assert.equal((await w.fetch(req("DELETE", `/v1/spaces/${spaceId}/devices/${browsers[0].id}`, { device: phone }))).status, 204);
            assert.equal((await w.fetch(req("POST", `/v1/spaces/${spaceId}/devices`, { device: phone, body: { pairId } }))).status, 201);
        }
    }
    const info = await (await w.fetch(req("GET", `/v1/spaces/${spaceId}`, { device: phone }))).json();
    assert.equal(info.devices.filter((d) => d.type === "web").length, 4);
});

test("a browser holding a phone-less space replies, the phone reads the reply, and the browser adds the phone", async () => {
    const w = world();
    const first = await linkNew(w);
    // The phone leaves: the browser is left alone in its space.
    assert.equal((await w.fetch(req("DELETE", `/v1/spaces/${first.spaceId}/devices/me`, { device: first.phone }))).status, 204);
    const newPhone = await makeDevice("p");
    const { pairId } = await openCode(w, first.browser);
    await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(newPhone) }));
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}`, { player: true, device: first.browser }))).status, 200);

    const waiting = w.fetch(req("GET", `/v1/pair/${pairId}/reply`, { device: newPhone }));
    await new Promise((r) => setTimeout(r, 10));
    const reply = box();
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/reply`, { player: true, device: first.browser, body: { ct: reply } }))).status, 201);
    const got = await waiting;
    assert.equal(got.status, 200);
    assert.deepEqual(await got.json(), { ct: reply });
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/reply`, { player: true, device: first.browser, body: { ct: box() } }))).status, 409);

    const join = await w.fetch(req("POST", `/v1/spaces/${first.spaceId}/devices`, { player: true, device: first.browser, body: { pairId } }));
    assert.equal(join.status, 201);
    assert.deepEqual(await join.json(), { device: newPhone.id, type: "phone" });
    assert.equal((await w.fetch(req("GET", `/v1/spaces/${first.spaceId}`, { device: newPhone }))).status, 200);

    // One phone per space: a second phone is refused before its code is burned.
    const third = await makeDevice("p");
    const { pairId: p2 } = await openCode(w, first.browser);
    await w.fetch(req("POST", `/v1/pair/${p2}/answer`, { body: answerBody(third) }));
    const refused = await w.fetch(req("POST", `/v1/spaces/${first.spaceId}/devices`, { player: true, device: first.browser, body: { pairId: p2 } }));
    assert.equal(refused.status, 409);
    assert.equal((await refused.json()).error.code, "full");
});

test("the phone's pairing calls are public; the browser's need the player key", async () => {
    const w = world();
    const browser = await makeDevice("w");
    const body = { device: record(browser) };
    assert.equal((await w.fetch(req("POST", "/v1/pair", { body }))).status, 403);
    const wrong = await w.fetch(req("POST", "/v1/pair", { body, headers: { "X-Stash-Player-Key": "nope" } }));
    assert.equal(wrong.status, 403);
    const { pairId } = await openCode(w, browser);
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}`, { device: browser }))).status, 403, "no polling around the player");
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/reply`, { device: browser, body: { ct: box() } }))).status, 403);
    // Without a PLAYER_KEY secret, the player's routes are off (not open).
    delete w.env.PLAYER_KEY;
    assert.equal((await w.fetch(req("POST", "/v1/pair", { body }))).status, 503);
    assert.equal((await w.fetch(req("POST", "/v1/pair", { body, player: true }))).status, 403, "any key is wrong when none is set");
});

test("pairing rate limits: the phone's calls per IP, codes per player session, the forwarded IP only from the player", async () => {
    const w = world({ limits: { pair: 10, open: 3 } });
    const browser = await makeDevice("w");
    const { pairId } = await openCode(w, browser, { "X-Stash-Session": "sess_one_1" });
    for (let i = 0; i < 10; i++) assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}/label`))).status, 200);
    const over = await w.fetch(req("GET", `/v1/pair/${pairId}/label`));
    assert.equal(over.status, 429);
    assert.equal(over.headers.get("retry-after"), "60");
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}/label`, { ip: "198.51.100.1" }))).status, 200, "another IP has its own bucket");

    await openCode(w, browser, { "X-Stash-Session": "sess_one_1" });
    await openCode(w, browser, { "X-Stash-Session": "sess_one_1" });
    const fourth = await w.fetch(req("POST", "/v1/pair", { player: true, body: { device: record(browser) }, headers: { "X-Stash-Session": "sess_one_1" } }));
    assert.equal(fourth.status, 429);
    await openCode(w, browser, { "X-Stash-Session": "sess_two_2" });

    // X-Stash-Client-IP counts only behind the player key; a phone can't pick its own bucket.
    await w.fetch(req("GET", `/v1/pair/${pairId}/label`, { ip: "192.0.2.9", headers: { "X-Stash-Client-IP": "10.0.0.1" } }));
    assert.equal(w.env.PAIR_RL.counts.get("192.0.2.9"), 1);
    assert.equal(w.env.PAIR_RL.counts.has("10.0.0.1"), false);
    await w.fetch(req("POST", `/v1/pair/${pairId}/reply`, { player: true, device: browser, body: { ct: box() }, headers: { "X-Stash-Client-IP": "2001:db8:1:2:3:4:5:6" } }));
    assert.equal(w.env.API_RL.counts.get("2001:db8:1:2"), 1, "IPv6 by its /64");
});

test("pure slot rules: shapes, the same device on both sides, claim modes", async () => {
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    const t = 1_000;
    assert.equal(openSlot("x".repeat(22), { ...record(browser), pub: "AAAA" }, t).status, 400);
    assert.equal(openSlot("x".repeat(22), { ...record(browser), tokenHash: "ABC" }, t).status, 400);
    assert.equal(openSlot("x".repeat(22), { ...record(browser), labelCt: { n: "short", c: "x" } }, t).status, 400);
    const { state } = openSlot("x".repeat(22), { ...record(browser), extra: "dropped" }, t);
    assert.equal("extra" in state.browser, false);
    assert.equal(answerSlot(state, { phonePub: phone.pub, ct: box(), device: record(browser) }, t).status, 400, "a device can't pair with itself");
    assert.equal(answerSlot(state, { phonePub: "nope", ct: box(), device: record(phone) }, t).status, 400);
    assert.equal(answerSlot(state, { phonePub: phone.pub, ct: box(undefined, 4000), device: record(phone) }, t).status, 400, "pairing messages are small");
    const answered = answerSlot(state, { phonePub: phone.pub, ct: box(), device: record(phone) }, t).state;
    assert.equal(readLabel(answered, t).status, 409);
    assert.equal(pollSlot(answered, phone.caller, t).status, 403);
    assert.equal(claimSlot(answered, browser.caller, "create", t).status, 403, "only the phone creates");
    assert.deepEqual(claimSlot(answered, browser.caller, "join", t).body.add.id, phone.id);
    assert.deepEqual(claimSlot(answered, phone.caller, "join", t).body.add.id, browser.id);
    assert.equal(claimSlot(answered, phone.caller, "create", t + PAIR_TTL_MS + CLAIM_GRACE_MS).status, 410);
    assert.equal(claimSlot(answered, phone.caller, "create", t + PAIR_TTL_MS + 1).status, 200, "the grace lets a late answer finish");
});
