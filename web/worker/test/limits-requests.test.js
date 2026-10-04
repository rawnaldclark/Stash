import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../index.js";
import { hashEmail } from "../crypto.js";
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
    const e = env({ SEND_IP_RL: fakeRL(5), STASH_KV: fakeKV({ [`access:${h}`]: { source: "kofi" } }) });
    for (let i = 0; i < 5; i++) assert.equal((await run(e, post("/access", { email: `p${i}@example.com` }))).status, 200);
    await expectSlowDown(await run(e, post("/access", { email: EMAIL })));
    assert.equal(e.EMAIL.sent.length, 0);
    // A different address isn't held up. IPv6 counts by its /64.
    assert.equal((await run(e, post("/access", { email: EMAIL }, { ip: "198.51.100.1" }))).status, 200);
    await run(e, post("/access", { email: "a@example.com" }, { ip: "2001:db8:1:2::1" }));
    await run(e, post("/access", { email: "b@example.com" }, { ip: "2001:db8:1:2:ffff::9" }));
    assert.equal(e.SEND_IP_RL.counts.get("2001:db8:1:2"), 2);
});

test("code sends per email apply to every email, so the limit says nothing about who's listed", async () => {
    const h = await hashEmail(EMAIL);
    const e = env({ SEND_EMAIL_RL: fakeRL(3), STASH_KV: fakeKV({ [`access:${h}`]: { source: "kofi" } }) });
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
    const rec = e.STASH_KV.json(`request:${h}`);
    assert.equal(rec.email, "new.person@example.com");
    assert.equal(rec.note, "I donated on PayPal as Jo\nthanks");
    assert.ok(rec.at);
    assert.equal(e.STASH_KV.map.get(`request:${h}`).opts.expirationTtl, 30 * 24 * 3600);
});

test("notes are cut at 500 characters", async () => {
    const e = env();
    await run(e, post("/request", { email: "n@example.com", note: "x".repeat(800) }));
    assert.equal(e.STASH_KV.json(`request:${await hashEmail("n@example.com")}`).note.length, 500);
});

test("someone already on the list gets the same page, and nothing is stored", async () => {
    const h = await hashEmail(EMAIL);
    const e = env({ STASH_KV: fakeKV({ [`access:${h}`]: { source: "kofi" } }) });
    const listed = await run(e, post("/request", { email: EMAIL }));
    const other = await run(e, post("/request", { email: "other@example.com" }));
    assert.equal(await listed.text(), await other.text());
    assert.equal(e.STASH_KV.json(`request:${h}`), null);
    assert.ok(e.STASH_KV.json(`request:${await hashEmail("other@example.com")}`));
});

test("storing the request happens after the response", async () => {
    const e = env();
    const ctx = fakeCtx();
    await handle(post("/request", { email: "later@example.com" }), e, ctx);
    assert.equal([...e.STASH_KV.map.keys()].length, 0);
    await ctx.drain();
    assert.equal([...e.STASH_KV.map.keys()].length, 1);
});

test("GET /request is the form; a bad email is a 400", async () => {
    const e = env();
    const page = await handle(get("/request"), e);
    assert.equal(page.status, 200);
    assert.match(await page.text(), /id="request"/);
    assert.equal((await run(e, post("/request", { email: "nope" }))).status, 400);
});
