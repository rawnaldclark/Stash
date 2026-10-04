import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { hashEmail, KOFI_TEST_TXN } from "../src/access.js";
import { donation, env, fakeKV, kofiPost } from "./fake-kv.js";

const BASE = "https://stash-tipjar.example.workers.dev";
const SUPPORTERS = {
    supporters: [
        { name: "Cedric", amountUsd: 10, message: "Just downloaded", timestamp: "2025-01-01T00:00:00Z" },
        { name: "Slowcab", amountUsd: 5, message: "Amazing work!", timestamp: "2025-01-01T00:00:00Z" },
    ],
};

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
    const bad = new Request(`${BASE}/`, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ data: JSON.stringify({ ...donation(), verification_token: "nope" }) }) });
    const e = env();
    assert.equal((await worker.fetch(bad, e)).status, 401);
    assert.equal(e.STASH_KV.map.size, 0);
    const junk = new Request(`${BASE}/`, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: "data=%7Bnot-json" });
    assert.equal((await worker.fetch(junk, env())).status, 400);
});

test("a donation still lands at the top of the supporters list exactly as before", async () => {
    const e = env({ STASH_KV: fakeKV({ supporters: JSON.stringify(SUPPORTERS) }) });
    const res = await worker.fetch(kofiPost(donation({ amount: "5.75", message: "x".repeat(300) })), e);
    assert.equal(res.status, 200);
    assert.equal(await res.text(), "OK");
    const list = JSON.parse(e.STASH_KV.map.get("supporters").value).supporters;
    assert.deepEqual(list[0], { name: "Ann", amountUsd: 5, message: "x".repeat(280), timestamp: "2026-10-04T12:00:00Z" });
    assert.deepEqual(list.slice(1), SUPPORTERS.supporters);
});

test("a donation gives early access by email hash, never storing the email", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation()), e);
    const hash = await hashEmail("ann@example.org");
    const access = JSON.parse(e.STASH_KV.map.get(`access:${hash}`).value);
    assert.equal(access.source, "kofi");
    assert.ok(access.firstAt && access.lastAt);
    for (const [key, { value }] of e.STASH_KV.map) {
        if (key === "supporters") continue; // the existing list never held emails either
        assert.ok(!String(value).toLowerCase().includes("ann@example.org"), `${key} holds the email`);
        assert.ok(!key.toLowerCase().includes("ann@"), `${key} is the email`);
    }
});

test("a donation adds to this month's goal, and its transaction is remembered for 60 days", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation({ amount: "5.00", timestamp: "2026-10-04T12:00:00Z" })), e);
    const goal = JSON.parse(e.STASH_KV.map.get("goal:2026-10").value);
    assert.equal(goal.cents, 500);
    assert.equal(goal.entries.length, 1);
    assert.equal(goal.entries[0].source, "Ko-fi");
    const marker = e.STASH_KV.map.get("kofitxn:tx-1");
    assert.equal(marker.opts.expirationTtl, 60 * 24 * 3600);
});

test("a Ko-fi retry of the same transaction changes nothing", async () => {
    const e = env({ STASH_KV: fakeKV({ supporters: JSON.stringify(SUPPORTERS) }) });
    await worker.fetch(kofiPost(donation()), e);
    const before = new Map([...e.STASH_KV.map].map(([k, v]) => [k, v.value]));
    const res = await worker.fetch(kofiPost(donation()), e);
    assert.equal(res.status, 200);
    assert.equal(await res.text(), "OK");
    for (const [k, v] of e.STASH_KV.map) assert.equal(v.value, before.get(k), `${k} changed`);
    assert.equal(JSON.parse(e.STASH_KV.map.get("goal:2026-10").value).cents, 500);
});

test("two different donations add up; a subscription counts and is labelled monthly", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation()), e);
    await worker.fetch(kofiPost(donation({ kofi_transaction_id: "tx-2", type: "Subscription", amount: "3.00", is_subscription_payment: true })), e);
    const goal = JSON.parse(e.STASH_KV.map.get("goal:2026-10").value);
    assert.equal(goal.cents, 800);
    assert.equal(goal.entries[1].source, "Ko-fi (monthly)");
});

test("shop orders and commissions are ignored: no access, no goal", async () => {
    const e = env();
    const res = await worker.fetch(kofiPost(donation({ type: "Shop Order" })), e);
    assert.equal(await res.text(), "Ignored");
    assert.equal(e.STASH_KV.map.size, 0);
});

test("non-USD amounts are converted with the fixed table; the original is kept for the admin page", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation({ amount: "10.00", currency: "EUR" })), e);
    const goal = JSON.parse(e.STASH_KV.map.get("goal:2026-10").value);
    assert.equal(goal.cents, 1080);
    assert.equal(goal.entries[0].orig, "10.00 EUR");
});

test("an unknown currency isn't counted, but the donor still gets access", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation({ currency: "XYZ" })), e);
    assert.equal(e.STASH_KV.map.has("goal:2026-10"), false);
    assert.ok(e.STASH_KV.map.has(`access:${await hashEmail("ann@example.org")}`));
});

test("a donation counts toward the month it was made in (UTC)", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation({ timestamp: "2026-09-30T23:59:00Z" })), e);
    assert.ok(e.STASH_KV.map.has("goal:2026-09"));
});

test("Ko-fi's test webhook reaches the supporters list as before, but not the goal or access list", async () => {
    const e = env();
    await worker.fetch(kofiPost(donation({ kofi_transaction_id: KOFI_TEST_TXN, email: "jo.example@example.com", from_name: "Jo Example" })), e);
    assert.deepEqual([...e.STASH_KV.map.keys()], ["supporters"]);
});

test("a later donation keeps the supporter's firstAt", async () => {
    const e = env();
    const hash = await hashEmail("ann@example.org");
    await e.STASH_KV.put(`access:${hash}`, JSON.stringify({ source: "kofi", firstAt: "2026-01-01T00:00:00.000Z", lastAt: "2026-01-01T00:00:00.000Z" }));
    await worker.fetch(kofiPost(donation()), e);
    const access = JSON.parse(e.STASH_KV.map.get(`access:${hash}`).value);
    assert.equal(access.firstAt, "2026-01-01T00:00:00.000Z");
    assert.notEqual(access.lastAt, "2026-01-01T00:00:00.000Z");
});

test("with EMAIL_PEPPER set, the access key uses the peppered hash", async () => {
    const e = env({ EMAIL_PEPPER: "test-pepper" });
    await worker.fetch(kofiPost(donation({ email: " Supporter@Example.com " })), e);
    assert.ok(e.STASH_KV.map.has("access:82f2e5dc9b8f0f6d69290575810eda34b58a779e96eaa29a554050b8024d09df"));
});

test("if the early-access writes fail, Ko-fi still gets 200 and the supporter is listed", async () => {
    const kv = fakeKV();
    const put = kv.put.bind(kv);
    kv.put = async (key, value, opts) => {
        if (key !== "supporters") throw new Error("KV write failed");
        return put(key, value, opts);
    };
    const e = env({ STASH_KV: kv });
    const res = await worker.fetch(kofiPost(donation()), e);
    assert.equal(res.status, 200);
    assert.equal(JSON.parse(kv.map.get("supporters").value).supporters[0].name, "Ann");
});

test("JSON bodies (not form-encoded) still work, as before", async () => {
    const e = env();
    const req = new Request(`${BASE}/`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ verification_token: "secret", ...donation() }) });
    assert.equal((await worker.fetch(req, e)).status, 200);
    assert.ok(e.STASH_KV.map.has("goal:2026-10"));
});
