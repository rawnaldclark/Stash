import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { hashEmail, KOFI_TEST_TXN } from "../src/access.js";
import { donation, env, fakeKV, goalEntries, kofiPost } from "./fake-kv.js";

const BASE = "https://stash-tipjar.example.workers.dev";
const SUPPORTERS = {
    supporters: [
        { name: "Cedric", amountUsd: 10, message: "Just downloaded", timestamp: "2025-01-01T00:00:00Z" },
        { name: "Slowcab", amountUsd: 5, message: "Amazing work!", timestamp: "2025-01-01T00:00:00Z" },
    ],
};
const supportersOf = (e) => JSON.parse(e.STASH_KV.map.get("supporters").value).supporters;

test("GET / serves the supporters list exactly as before: same bytes, same headers", async () => {
    const e = env({ STASH_KV: fakeKV({ supporters: JSON.stringify(SUPPORTERS) }) });
    const res = await worker.fetch(new Request(`${BASE}/`), e);
    assert.equal(res.status, 200);
    assert.equal(
        await res.text(),
        '{"supporters":[{"name":"Cedric","amountUsd":10,"message":"Just downloaded"},{"name":"Slowcab","amountUsd":5,"message":"Amazing work!"}]}',
    );
    assert.equal(res.headers.get("content-type"), "application/json");
    assert.equal(res.headers.get("cache-control"), "public, max-age=60");
    assert.equal(res.headers.get("access-control-allow-origin"), "*");
});

test("GET / with nothing stored is an empty list; any other GET path is the list too", async () => {
    assert.equal(await (await worker.fetch(new Request(`${BASE}/`), env())).text(), '{"supporters":[]}');
    assert.equal(await (await worker.fetch(new Request(`${BASE}/anything`), env())).text(), '{"supporters":[]}');
});

test("GET /lossless.json and .sig are unchanged: byte for byte from KV, 404 before the first publish", async () => {
    const e = env({ STASH_KV: fakeKV({ lossless_config: '{"v":1}', lossless_config_sig: "c2ln" }) });
    const json = await worker.fetch(new Request(`${BASE}/lossless.json`), e);
    assert.equal(await json.text(), '{"v":1}');
    assert.equal(json.headers.get("cache-control"), "public, max-age=300");
    assert.equal(await (await worker.fetch(new Request(`${BASE}/lossless.json.sig`), e)).text(), "c2ln");
    assert.equal((await worker.fetch(new Request(`${BASE}/lossless.json`), env())).status, 404);
});

test("other methods are 405; a bad token is 401; a bad payload is 400", async () => {
    assert.equal((await worker.fetch(new Request(`${BASE}/`, { method: "PUT" }), env())).status, 405);
    const e = env();
    const bad = kofiPost({ ...donation(), verification_token: "nope" });
    assert.equal((await worker.fetch(bad, e)).status, 401);
    assert.equal(e.STASH_KV.map.size + e.ACCESS_KV.map.size, 0);
    const missing = new Request(`${BASE}/`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(donation()) });
    assert.equal((await worker.fetch(missing, env())).status, 401, "no token in the payload");
    const junk = new Request(`${BASE}/`, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: "data=%7Bnot-json" });
    assert.equal((await worker.fetch(junk, env())).status, 400);
});

test("without KOFI_VERIFICATION_TOKEN set, every webhook is refused with 500 (even one with no token)", async () => {
    for (const token of [undefined, ""]) {
        const e = env({ KOFI_VERIFICATION_TOKEN: token });
        const noToken = new Request(`${BASE}/`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(donation()) });
        assert.equal((await worker.fetch(noToken, e)).status, 500);
        assert.equal((await worker.fetch(kofiPost(donation()), e)).status, 500);
        assert.equal(e.STASH_KV.map.size + e.ACCESS_KV.map.size, 0);
    }
});

test("a donation still lands at the top of the supporters list exactly as before", async () => {
    const e = env({ STASH_KV: fakeKV({ supporters: JSON.stringify(SUPPORTERS) }) });
    const res = await worker.fetch(kofiPost(donation({ amount: "5.75", message: "x".repeat(300) })), e);
    assert.equal(res.status, 200);
    assert.equal(await res.text(), "OK");
    const list = supportersOf(e);
    assert.deepEqual(list[0], { name: "Ann", amountUsd: 5, message: "x".repeat(280), timestamp: "2026-10-04T12:00:00Z" });
    assert.deepEqual(list.slice(1), SUPPORTERS.supporters);
});

test("early access lives in ACCESS_KV by email hash, never the email; STASH_KV keeps only the tip jar's own keys", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation()), e);
    const hash = await hashEmail("ann@example.org");
    const access = e.ACCESS_KV.json(`access:${hash}`);
    assert.equal(access.source, "kofi");
    assert.ok(access.firstAt && access.lastAt);
    assert.deepEqual([...e.STASH_KV.map.keys()].sort(), ["kofitxn:tx-1", "supporters"]);
    for (const [key, { value, opts }] of e.ACCESS_KV.map) {
        const all = `${key} ${value} ${JSON.stringify(opts.metadata ?? {})}`.toLowerCase();
        assert.ok(!all.includes("ann@example.org") && !all.includes("ann@"), `${key} holds the email`);
    }
});

test("each donation is its own goal entry, keyed by month and transaction, with the amount in metadata", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation({ amount: "5.00" })), e);
    await worker.fetch(kofiPost(donation({ kofi_transaction_id: "tx-2", message_id: "m2", type: "Subscription", amount: "3.00", is_subscription_payment: true })), e);
    const entries = goalEntries(e.ACCESS_KV, "2026-10");
    assert.deepEqual(entries.map((x) => x.key).sort(), ["goal:2026-10:kofi:tx-1", "goal:2026-10:kofi:tx-2"]);
    assert.equal(entries.reduce((s, x) => s + x.cents, 0), 800);
    assert.equal(entries.find((x) => x.key.endsWith("tx-2")).source, "Ko-fi (monthly)");
    assert.equal(e.STASH_KV.map.get("kofitxn:tx-1").value, "done");
    assert.equal(e.STASH_KV.map.get("kofitxn:tx-1").opts.expirationTtl, 60 * 24 * 3600);
});

test("a Ko-fi retry of a handled transaction changes nothing", async () => {
    const e = env({ STASH_KV: fakeKV({ supporters: JSON.stringify(SUPPORTERS) }) });
    await worker.fetch(kofiPost(donation()), e);
    const snapshot = (kv) => JSON.stringify([...kv.map].map(([k, v]) => [k, v.value]));
    const before = [snapshot(e.STASH_KV), snapshot(e.ACCESS_KV)];
    const res = await worker.fetch(kofiPost(donation()), e);
    assert.equal(res.status, 200);
    assert.deepEqual([snapshot(e.STASH_KV), snapshot(e.ACCESS_KV)], before);
});

test("if the early-access writes fail: 500 so Ko-fi retries, not marked done, and the retry doesn't list the supporter twice", async () => {
    const access = fakeKV();
    const put = access.put.bind(access);
    let failing = true;
    access.put = async (key, value, opts) => {
        if (failing) throw new Error("KV write failed");
        return put(key, value, opts);
    };
    const e = env({ ACCESS_KV: access, STASH_KV: fakeKV({ supporters: JSON.stringify(SUPPORTERS) }) });
    const first = await worker.fetch(kofiPost(donation()), e);
    assert.equal(first.status, 500);
    assert.equal(supportersOf(e).length, 3, "the supporter is listed");
    assert.equal(e.STASH_KV.map.get("kofitxn:tx-1").value, "listed", "not marked done");

    failing = false;
    const retry = await worker.fetch(kofiPost(donation()), e);
    assert.equal(retry.status, 200);
    assert.equal(supportersOf(e).length, 3, "listed once, not twice");
    assert.equal(goalEntries(access, "2026-10").length, 1);
    assert.ok(access.json(`access:${await hashEmail("ann@example.org")}`));
    assert.equal(e.STASH_KV.map.get("kofitxn:tx-1").value, "done");
});

test("with no ACCESS_KV bound, the supporter is still listed and Ko-fi is asked to retry", async () => {
    const e = env({ ACCESS_KV: undefined });
    assert.equal((await worker.fetch(kofiPost(donation()), e)).status, 500);
    assert.equal(supportersOf(e)[0].name, "Ann");
});

test("no transaction id: the message id is the dedupe key; neither: still counted, once", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation({ kofi_transaction_id: "", message_id: "msg-9" })), e);
    await worker.fetch(kofiPost(donation({ kofi_transaction_id: "", message_id: "msg-9" })), e);
    assert.equal(supportersOf(e).length, 1);
    assert.deepEqual(goalEntries(e.ACCESS_KV, "2026-10").map((x) => x.key), ["goal:2026-10:kofi:msg-9"]);
    const e2 = env();
    await worker.fetch(kofiPost(donation({ kofi_transaction_id: undefined, message_id: undefined })), e2);
    assert.equal(goalEntries(e2.ACCESS_KV, "2026-10").length, 1);
});

test("shop orders and commissions are ignored: no access, no goal", async () => {
    const e = env();
    const res = await worker.fetch(kofiPost(donation({ type: "Shop Order" })), e);
    assert.equal(await res.text(), "Ignored");
    assert.equal(e.STASH_KV.map.size + e.ACCESS_KV.map.size, 0);
});

test("non-USD amounts are converted with the fixed table; the original is kept for the admin page", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation({ amount: "10.00", currency: "EUR" })), e);
    const [entry] = goalEntries(e.ACCESS_KV, "2026-10");
    assert.equal(entry.cents, 1080);
    assert.equal(entry.orig, "10.00 EUR");
});

test("an unknown currency isn't counted, but the donor still gets access", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation({ currency: "XYZ" })), e);
    assert.equal(goalEntries(e.ACCESS_KV, "2026-10").length, 0);
    assert.ok(e.ACCESS_KV.map.has(`access:${await hashEmail("ann@example.org")}`));
    assert.equal(e.STASH_KV.map.get("kofitxn:tx-1").value, "done");
});

test("a donation counts toward the month it was made in (UTC)", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation({ timestamp: "2026-09-30T23:59:00Z" })), e);
    assert.equal(goalEntries(e.ACCESS_KV, "2026-09").length, 1);
});

test("Ko-fi's test webhook reaches the supporters list once in 60 days, and never the goal or the access list", async () => {
    const e = env();
    const test = () => kofiPost(donation({ kofi_transaction_id: KOFI_TEST_TXN, message_id: "test-msg", email: "jo.example@example.com", from_name: "Jo Example" }));
    assert.equal((await worker.fetch(test(), e)).status, 200);
    assert.equal(e.ACCESS_KV.map.size, 0);
    assert.deepEqual(supportersOf(e).map((s) => s.name), ["Jo Example"]);
    // A second "Send test" within 60 days: its transaction id is the same, so it's already done.
    assert.equal((await worker.fetch(test(), e)).status, 200);
    assert.equal(supportersOf(e).length, 1);
    assert.equal(e.STASH_KV.map.get(`kofitxn:${KOFI_TEST_TXN}`).value, "done");
});

test("a later donation keeps the supporter's firstAt", async () => {
    const e = env();
    const hash = await hashEmail("ann@example.org");
    await e.ACCESS_KV.put(`access:${hash}`, JSON.stringify({ source: "kofi", firstAt: "2026-01-01T00:00:00.000Z", lastAt: "2026-01-01T00:00:00.000Z" }));
    await worker.fetch(kofiPost(donation()), e);
    const access = e.ACCESS_KV.json(`access:${hash}`);
    assert.equal(access.firstAt, "2026-01-01T00:00:00.000Z");
    assert.notEqual(access.lastAt, "2026-01-01T00:00:00.000Z");
});

test("meta:hashcheck is written once, not on every donation", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation()), e);
    await worker.fetch(kofiPost(donation({ kofi_transaction_id: "tx-2", message_id: "m2" })), e);
    await worker.fetch(kofiPost(donation({ kofi_transaction_id: "tx-3", message_id: "m3" })), e);
    assert.equal(e.ACCESS_KV.puts.filter((k) => k === "meta:hashcheck").length, 1);
    assert.equal(e.ACCESS_KV.map.get("meta:hashcheck").value, await hashEmail("hashcheck@stashfm.app"));
});

test("with EMAIL_PEPPER set, the access key uses the peppered hash", async () => {
    const e = env({ EMAIL_PEPPER: "test-pepper" });
    await worker.fetch(kofiPost(donation({ email: " Supporter@Example.com " })), e);
    assert.ok(e.ACCESS_KV.map.has("access:82f2e5dc9b8f0f6d69290575810eda34b58a779e96eaa29a554050b8024d09df"));
});

test("JSON bodies (not form-encoded) still work, as before", async () => {
    const e = env();
    const req = new Request(`${BASE}/`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ verification_token: "secret", ...donation() }) });
    assert.equal((await worker.fetch(req, e)).status, 200);
    assert.equal(goalEntries(e.ACCESS_KV, "2026-10").length, 1);
});
