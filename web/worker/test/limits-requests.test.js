import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../index.js";
import { hashFor as hashEmail } from "./fakes.js";
import { forgetAllAccess } from "../session.js";
import { env, fakeCtx, fakeKV, fakeRL, get, post } from "./fakes.js";

const EMAIL = "supporter@example.com";

beforeEach(() => forgetAllAccess());

async function run(e, req) {
    const ctx = fakeCtx();
    const res = await handle(req, e, ctx);
    await ctx.drain();
    return res;
}

async function expectSlowDown(res) {
    assert.equal(res.status, 429);
    assert.equal(res.headers.get("retry-after"), "60");
    assert.match(await res.text(), /id="slow-down"/);
}

test("code sends per IP: over the limit is a friendly 429 page, and no email", async () => {
    const h = await hashEmail(EMAIL);
    const e = env({ SEND_IP_RL: fakeRL(5), ACCESS_KV: fakeKV({ [`access:${h}`]: { source: "kofi" } }) });
    for (let i = 0; i < 5; i++) assert.equal((await run(e, post("/access", { email: `p${i}@example.com` }))).status, 200);
    await expectSlowDown(await run(e, post("/access", { email: EMAIL })));
    assert.equal(e.EMAIL.sent.length, 0);
    // A different address isn't held up. IPv6 counts by its /48 (one subscriber often has a whole /48 or /56).
    assert.equal((await run(e, post("/access", { email: EMAIL }, { ip: "198.51.100.1" }))).status, 200);
    await run(e, post("/access", { email: "a@example.com" }, { ip: "2001:db8:1:2::1" }));
    await run(e, post("/access", { email: "b@example.com" }, { ip: "2001:db8:1:ffff:ffff::9" }));
    assert.equal(e.SEND_IP_RL.counts.get("2001:db8:1::/48"), 2);
});

test("code sends per email apply to every email, so the limit says nothing about who's listed", async () => {
    const h = await hashEmail(EMAIL);
    const e = env({ SEND_EMAIL_RL: fakeRL(3), ACCESS_KV: fakeKV({ [`access:${h}`]: { source: "kofi" } }) });
    for (const email of [EMAIL, "stranger@example.com"]) {
        for (let i = 0; i < 3; i++) assert.equal((await run(e, post("/access", { email }, { ip: `192.0.2.${i}` }))).status, 200);
        await expectSlowDown(await run(e, post("/access", { email }, { ip: "192.0.2.99" })));
    }
    assert.ok(e.SEND_EMAIL_RL.counts.has(h), "keyed by the email's hash, not the email");
    assert.ok(![...e.SEND_EMAIL_RL.counts.keys()].some((k) => k.includes("@")));
});

test("code tries per IP: over the limit is a 429 before the code is even looked at", async () => {
    const e = env({ VERIFY_IP_RL: fakeRL(10) });
    for (let i = 0; i < 10; i++) assert.equal((await run(e, post("/access/verify", { email: EMAIL, code: "123456" }))).status, 400);
    await expectSlowDown(await run(e, post("/access/verify", { email: EMAIL, code: "123456" })));
});

test("code tries per email: every email is limited before its code is looked up, listed or not", async () => {
    const h = await hashEmail(EMAIL);
    const e = env({ VERIFY_EMAIL_RL: fakeRL(5), ACCESS_KV: fakeKV({ [`access:${h}`]: { source: "kofi" } }) });
    for (const email of [EMAIL, "stranger@example.com"]) {
        // Different addresses each time: only the per-email limit can catch this.
        for (let i = 0; i < 5; i++) assert.equal((await run(e, post("/access/verify", { email, code: "123456" }, { ip: `192.0.2.${i}` }))).status, 400);
        await expectSlowDown(await run(e, post("/access/verify", { email, code: "123456" }, { ip: "192.0.2.99" })));
    }
    assert.ok(e.VERIFY_EMAIL_RL.counts.has(h));
    assert.ok(![...e.VERIFY_EMAIL_RL.counts.keys()].some((k) => k.includes("@")));
});

test("IPv6 is bucketed by /48 for every per-IP limit", async () => {
    const e = env({ VERIFY_IP_RL: fakeRL(2), REQUEST_IP_RL: fakeRL(2) });
    await run(e, post("/access/verify", { email: EMAIL, code: "123456" }, { ip: "2001:db8:aa::1" }));
    await run(e, post("/access/verify", { email: EMAIL, code: "123456" }, { ip: "2001:db8:aa:ffff::2" }));
    await expectSlowDown(await run(e, post("/access/verify", { email: EMAIL, code: "123456" }, { ip: "2001:db8:aa:1234:5678::3" })));
    await run(e, post("/request", { email: "a@example.com" }, { ip: "2001:db8:bb:1::1" }));
    await run(e, post("/request", { email: "b@example.com" }, { ip: "2001:db8:bb:2::1" }));
    await expectSlowDown(await run(e, post("/request", { email: "c@example.com" }, { ip: "2001:db8:bb:3::1" })));
    assert.equal((await run(e, post("/request", { email: "d@example.com" }, { ip: "2001:db8:cc::1" }))).status, 200, "another /48");
});

test("access requests per IP are limited too", async () => {
    const e = env({ REQUEST_IP_RL: fakeRL(3) });
    for (let i = 0; i < 3; i++) assert.equal((await run(e, post("/request", { email: `r${i}@example.com` }))).status, 200);
    await expectSlowDown(await run(e, post("/request", { email: "r9@example.com" })));
});

test("a request keeps {email, note, at} for 30 days at most, keyed by the email's hash", async () => {
    const e = env();
    const res = await run(e, post("/request", { email: " New.Person@Example.com ", note: "  I donated on PayPal as Jo\r\nthanks  " }));
    assert.equal(res.status, 200);
    assert.match(await res.text(), /id="requested"/);
    const h = await hashEmail("new.person@example.com");
    const rec = e.ACCESS_KV.json(`request:${h}`);
    assert.equal(rec.email, "new.person@example.com");
    assert.equal(rec.note, "I donated on PayPal as Jo\nthanks");
    assert.ok(rec.at);
    assert.equal(e.ACCESS_KV.map.get(`request:${h}`).opts.expirationTtl, 30 * 24 * 3600);
    assert.deepEqual(e.ACCESS_KV.map.get(`request:${h}`).opts.metadata, { at: rec.at }, "the time in metadata, for sorting the admin list");
});

test("notes are cut at 500 characters", async () => {
    const e = env();
    await run(e, post("/request", { email: "n@example.com", note: "x".repeat(800) }));
    assert.equal(e.ACCESS_KV.json(`request:${await hashEmail("n@example.com")}`).note.length, 500);
});

test("someone already on the list gets the same page, and nothing is stored", async () => {
    const h = await hashEmail(EMAIL);
    const e = env({ ACCESS_KV: fakeKV({ [`access:${h}`]: { source: "kofi" } }) });
    const listed = await run(e, post("/request", { email: EMAIL }));
    const other = await run(e, post("/request", { email: "other@example.com" }));
    assert.equal(await listed.text(), await other.text());
    assert.equal(e.ACCESS_KV.json(`request:${h}`), null);
    assert.ok(e.ACCESS_KV.json(`request:${await hashEmail("other@example.com")}`));
});

test("storing the request happens after the response", async () => {
    const e = env();
    const ctx = fakeCtx();
    await handle(post("/request", { email: "later@example.com" }), e, ctx);
    assert.equal([...e.ACCESS_KV.map.keys()].length, 0);
    await ctx.drain();
    assert.equal([...e.ACCESS_KV.map.keys()].length, 1);
});

test("GET /request is the form; a bad email is a 400", async () => {
    const e = env();
    const page = await handle(get("/request"), e);
    assert.equal(page.status, 200);
    assert.match(await page.text(), /id="request"/);
    assert.equal((await run(e, post("/request", { email: "nope" }))).status, 400);
});
