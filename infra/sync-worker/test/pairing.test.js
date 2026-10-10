import { test } from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { base64url } from "../src/http.js";
import { PAIR_TTL_MS, CLAIM_GRACE_MS, POLL_MS, answerSlot, claimSlot, openSlot, pollSlot, readLabel } from "../src/pair.js";
import { box, makeDevice, record, req, world } from "./fakes.js";
import { answerBody, labelsFor, linkNew, newSpaceId, openCode } from "./link.js";
import { QUOTAS } from "../src/quota.js";

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
    const shown = await label.json();
    assert.deepEqual(shown.labelCt, browser.labelCt);
    assert.deepEqual(shown.browser, { id: browser.id, pub: browser.pub }, "the phone can seal a key envelope to the browser later");


    const a = answerBody(phone);
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: a }))).status, 201);
    const poll = await w.fetch(req("GET", `/v1/pair/${pairId}`, { player: true, device: browser }));
    assert.equal(poll.status, 200);
    assert.deepEqual(await poll.json(), { phonePub: phone.pub, ct: a.ct });

    const spaceId = newSpaceId();
    for (const weak of ["s_short", "S".repeat(22), `s_${"A".repeat(21)}B`, spaceId + "x"]) {
        assert.equal((await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, labels: labelsFor(phone, browser), spaceId: weak } }))).status, 400, `weak id ${weak}`);
    }
    assert.equal((await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, labels: labelsFor(phone, browser), spaceId } }))).status, 201);
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
    const twice = await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, labels: labelsFor(phone, browser), spaceId: newSpaceId() } }));
    assert.equal((await twice.json()).error.code, "used");
    const join = await w.fetch(req("POST", `/v1/spaces/${spaceId}/devices`, { device: phone, body: { pairId, epoch: 1, labelCt: box(1) } }));
    assert.equal((await join.json()).error.code, "used");
    assert.ok(browser);
});

test("only the answering phone can create the space; only the browser can poll; a refused create gives the code back", async () => {
    const w = world();
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    const stranger = await makeDevice("x");
    const { pairId } = await openCode(w, browser);
    assert.equal((await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, labels: labelsFor(phone, browser), spaceId: newSpaceId() } }))).status, 409, "no answer yet");
    await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }));
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}`, { player: true, device: stranger }))).status, 403);
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}`, { player: true, device: { ...browser, auth: `Stash-Device ${browser.id}:${stranger.token}` } }))).status, 403);
    assert.equal((await w.fetch(req("POST", "/v1/spaces", { device: stranger, body: { pairId, labels: labelsFor(phone, browser), spaceId: newSpaceId() } }))).status, 403);
    assert.equal((await w.fetch(req("POST", "/v1/spaces", { device: browser, body: { pairId, labels: labelsFor(phone, browser), spaceId: newSpaceId() } }))).status, 403);

    // The session's daily space quota is spent: the create is refused, but the code isn't burned.
    const old = QUOTAS.space.n;
    QUOTAS.space.n = 0;
    try {
        const refused = await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, labels: labelsFor(phone, browser), spaceId: newSpaceId() } }));
        assert.equal(refused.status, 429);
    } finally {
        QUOTAS.space.n = old;
    }
    // A taken id is refused and the code is given back too; only a create that succeeds burns it.
    const taken = (await linkNew(w)).spaceId;
    const clash = await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, labels: labelsFor(phone, browser), spaceId: taken } }));
    assert.equal((await clash.json()).error.code, "exists");
    assert.equal((await w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, labels: labelsFor(phone, browser), spaceId: newSpaceId() } }))).status, 201, "the same code works once allowed");
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
    await w.polling();
    assert.equal(w.timers.length, 1);
    assert.equal(w.timers[0].ms, POLL_MS);
    await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }));
    assert.equal((await waiting).status, 200);
    assert.equal(w.timers.length, 0, "the timer is cleared when the answer wakes the poll");

    const { pairId: idle } = await openCode(w, browser);
    const idleWait = w.fetch(req("GET", `/v1/pair/${idle}`, { player: true, device: browser }));
    await w.polling();
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
        const res = await w.fetch(req("POST", `/v1/spaces/${spaceId}/devices`, { device: phone, body: { pairId, epoch: 1, labelCt: box(1) } }));
        if (i < 3) {
            assert.equal(res.status, 201);
            assert.deepEqual(await res.json(), { device: b.id, type: "web", epoch: 1 });
            browsers.push(b);
        } else {
            assert.equal(res.status, 409);
            assert.equal((await res.json()).error.code, "full");
            // The code is still good: remove a browser and try the same code again.
            assert.equal((await w.fetch(req("DELETE", `/v1/spaces/${spaceId}/devices/${browsers[0].id}`, { device: phone }))).status, 204);
            assert.equal((await w.fetch(req("POST", `/v1/spaces/${spaceId}/devices`, { device: phone, body: { pairId, epoch: 1, labelCt: box(1) } }))).status, 201);
        }
    }
    const info = await (await w.fetch(req("GET", `/v1/spaces/${spaceId}`, { device: phone }))).json();
    assert.equal(info.devices.filter((d) => d.type === "web").length, 4);
});

test("a browser holding a phone-less space adds the phone, then replies (sync-v1 order); the phone reads the reply", async () => {
    const w = world();
    const first = await linkNew(w);
    // The phone leaves: the browser is left alone in its space.
    assert.equal((await w.fetch(req("DELETE", `/v1/spaces/${first.spaceId}/devices/me`, { device: first.phone }))).status, 204);
    const newPhone = await makeDevice("p");
    const { pairId } = await openCode(w, first.browser);
    await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(newPhone) }));
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}`, { player: true, device: first.browser }))).status, 200);

    const join = await w.fetch(req("POST", `/v1/spaces/${first.spaceId}/devices`, { player: true, device: first.browser, body: { pairId, epoch: 1, labelCt: box(1) } }));
    assert.equal(join.status, 201);
    assert.deepEqual(await join.json(), { device: newPhone.id, type: "phone", epoch: 1 });

    const waiting = w.fetch(req("GET", `/v1/pair/${pairId}/reply`, { device: newPhone }));
    await w.polling();
    const reply = box();
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/reply`, { player: true, device: first.browser, body: { ct: reply } }))).status, 201);
    const got = await waiting;
    assert.equal(got.status, 200);
    assert.deepEqual(await got.json(), { ct: reply });
    assert.equal((await w.fetch(req("POST", `/v1/pair/${pairId}/reply`, { player: true, device: first.browser, body: { ct: box() } }))).status, 409);

    assert.equal((await w.fetch(req("GET", `/v1/spaces/${first.spaceId}`, { device: newPhone }))).status, 200);

    // One phone per space: a second phone is refused before its code is burned.
    const third = await makeDevice("p");
    const { pairId: p2 } = await openCode(w, first.browser);
    await w.fetch(req("POST", `/v1/pair/${p2}/answer`, { body: answerBody(third) }));
    const refused = await w.fetch(req("POST", `/v1/spaces/${first.spaceId}/devices`, { player: true, device: first.browser, body: { pairId: p2, epoch: 1, labelCt: box(1) } }));
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
    const w = world({ limits: { pair: 10 } });
    const browser = await makeDevice("w");
    const { pairId } = await openCode(w, browser, { "X-Stash-Session": "sess_one_1" });
    for (let i = 0; i < 10; i++) assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}/label`))).status, 200);
    const over = await w.fetch(req("GET", `/v1/pair/${pairId}/label`));
    assert.equal(over.status, 429);
    assert.equal(over.headers.get("retry-after"), "60");
    assert.equal((await w.fetch(req("GET", `/v1/pair/${pairId}/label`, { ip: "198.51.100.1" }))).status, 200, "another IP has its own bucket");

    // 6 codes per session per 10 minutes (spec §6.4): the first was opened above.
    for (let i = 0; i < 5; i++) await openCode(w, browser, { "X-Stash-Session": "sess_one_1" });
    const seventh = await w.fetch(req("POST", "/v1/pair", { player: true, body: { device: record(browser) }, headers: { "X-Stash-Session": "sess_one_1" } }));
    assert.equal(seventh.status, 429);
    assert.ok(Number(seventh.headers.get("retry-after")) > 0);
    await openCode(w, browser, { "X-Stash-Session": "sess_two_2" });
    w.clock.t += 10 * 60_000;
    await openCode(w, browser, { "X-Stash-Session": "sess_one_1" });

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

test("new spaces per session and per phone IP per day are capped", async () => {
    const w = world();
    const oldSpace = QUOTAS.space.n;
    const oldIp = QUOTAS.spaceIp.n;
    QUOTAS.space.n = 2;
    QUOTAS.spaceIp.n = 3;
    try {
        await linkNew(w, { headers: { "X-Stash-Session": "sess_space_1" } });
        await linkNew(w, { headers: { "X-Stash-Session": "sess_space_1" } });
        await assert.rejects(linkNew(w, { headers: { "X-Stash-Session": "sess_space_1" } }), "a third space from one session");
        await linkNew(w, { headers: { "X-Stash-Session": "sess_space_2" } });
        await assert.rejects(linkNew(w, { headers: { "X-Stash-Session": "sess_space_3" } }), "a fourth from one phone IP");
        w.clock.t += 86_400_000;
        await linkNew(w, { headers: { "X-Stash-Session": "sess_space_1" } });
    } finally {
        QUOTAS.space.n = oldSpace;
        QUOTAS.spaceIp.n = oldIp;
    }
});

test("the space and the join carry the labels re-sealed under the data key; they replace the pairing-time ones", async () => {
    const w = world();
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    const { pairId } = await openCode(w, browser);
    await w.fetch(req("POST", `/v1/pair/${pairId}/answer`, { body: answerBody(phone) }));
    const spaceId = newSpaceId();
    const create = (labels) => w.fetch(req("POST", "/v1/spaces", { device: phone, body: { pairId, spaceId, labels } }));
    for (const bad of [undefined, {}, { [phone.id]: box(1) }, { [phone.id]: box(1), [browser.id]: box(1), d_extra_12: box(1) },
        { [phone.id]: box(1), [browser.id]: box(0) }, { [phone.id]: box(1), [browser.id]: box(2) }, { [phone.id]: box(1), [browser.id]: box(1, 2000) }]) {
        assert.equal((await create(bad)).status, 400, JSON.stringify(bad && Object.keys(bad)));
    }
    const other = await makeDevice("x");
    assert.equal((await create({ [phone.id]: box(1), [other.id]: box(1) })).status, 400, "the two devices of this code, no other");
    const labels = labelsFor(phone, browser);
    assert.equal((await create(labels)).status, 201, "the refused tries didn't burn the code");
    const info = await (await w.fetch(req("GET", `/v1/spaces/${spaceId}`, { device: phone }))).json();
    for (const d of info.devices) assert.deepEqual(d.labelCt, labels[d.id]);

    // Join: the newcomer's label under the current key, required.
    const laptop = await makeDevice("w");
    const { pairId: p2 } = await openCode(w, laptop);
    await w.fetch(req("POST", `/v1/pair/${p2}/answer`, { body: answerBody(phone) }));
    const join = (body) => w.fetch(req("POST", `/v1/spaces/${spaceId}/devices`, { device: phone, body: { pairId: p2, epoch: 1, ...body } }));
    assert.equal((await join({})).status, 400);
    assert.equal((await join({ labelCt: box(0) })).status, 400, "not a pairing label");
    const stale = await join({ labelCt: box(2) });
    assert.equal(stale.status, 409, "sealed with another key");
    const labelCt = box(1);
    assert.equal((await join({ labelCt })).status, 201);
    const after = await (await w.fetch(req("GET", `/v1/spaces/${spaceId}`, { device: phone }))).json();
    assert.deepEqual(after.devices.find((d) => d.id === laptop.id).labelCt, labelCt);
});
