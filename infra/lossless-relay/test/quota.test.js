import { test } from "node:test";
import assert from "node:assert/strict";
import { fakeD1, QUOTA_MIGRATIONS } from "./fake-d1.js";
import {
    quotaDay, nextResetSec, freeDailyLimit, readStanding, spendLossless, losslessHeaders, capAnswer, quotaBody,
    pruneUsage, hooks, entitlementOf, supporterDailyCap,
} from "../src/quota.js";
import { keypair, token, tidOf } from "./entitlement-keys.js";

// Real SQLite with the shared schema from infra/lossless-quota/migrations.
const NOW = 1792195200 + 3600; // 2026-10-17T01:00:00Z
const REQ = new Request("https://relay.test/");

test("day is the UTC date; the reset is the next UTC midnight", () => {
    assert.equal(quotaDay(NOW), "2026-10-17");
    assert.equal(nextResetSec(NOW), 1792195200 + 86400);
    assert.equal(nextResetSec(1792195200), 1792195200 + 86400, "exactly midnight resets at the next one");
    assert.equal(quotaDay(1792195200 + 86399), "2026-10-17");
});

test("FREE_LOSSLESS_DAILY defaults to 500; junk or non-positive values fall back", () => {
    assert.equal(freeDailyLimit({}), 500);
    assert.equal(freeDailyLimit({ FREE_LOSSLESS_DAILY: "20" }), 20);
    for (const v of ["0", "-3", "x"]) assert.equal(freeDailyLimit({ FREE_LOSSLESS_DAILY: v }), 500, v);
});

test("no QUOTA_DB or no subject: the counter is off (null) and every helper accepts that", async () => {
    assert.equal(await readStanding(REQ, {}, "i:abc", NOW), null);
    assert.equal(await readStanding(REQ, { QUOTA_DB: fakeD1(QUOTA_MIGRATIONS) }, "", NOW), null);
    assert.equal(await spendLossless(null), null);
    assert.deepEqual(losslessHeaders(null), {});
    await pruneUsage({}, NOW); // no throw
});

test("read, then one atomic upsert per answer: used counts per day and subject", async () => {
    const env = { QUOTA_DB: fakeD1(QUOTA_MIGRATIONS), FREE_LOSSLESS_DAILY: "2" };
    let s = await readStanding(REQ, env, "i:a", NOW);
    assert.equal(s.used, 0);
    assert.equal(s.limit, 2);
    assert.equal(s.over, false);
    assert.equal((await spendLossless(s)).used, 1);
    assert.deepEqual(losslessHeaders(s), { "X-Stash-Lossless": "1/2", "X-Stash-Lossless-Reset": String(nextResetSec(NOW)) });
    await spendLossless(await readStanding(REQ, env, "i:a", NOW));
    s = await readStanding(REQ, env, "i:a", NOW);
    assert.equal(s.used, 2);
    assert.equal(s.over, true);
    assert.equal((await readStanding(REQ, env, "i:b", NOW)).used, 0, "other subjects have their own count");
    assert.equal((await readStanding(REQ, env, "i:a", NOW + 86400)).used, 0, "a new UTC day starts at 0");
    assert.deepEqual({ ...env.QUOTA_DB.raw.prepare("SELECT day, subject, n FROM usage").get() }, { day: "2026-10-17", subject: "i:a", n: 2 });
});

test("the cap answer: 429 daily_lossless_cap with used/limit/resetsAt and Retry-After to the reset", async () => {
    const env = { QUOTA_DB: fakeD1(QUOTA_MIGRATIONS), FREE_LOSSLESS_DAILY: "1" };
    await spendLossless(await readStanding(REQ, env, "i:a", NOW));
    const a = capAnswer(await readStanding(REQ, env, "i:a", NOW), NOW);
    assert.equal(a.status, 429);
    assert.deepEqual(a.body, { error: "daily_lossless_cap", used: 1, limit: 1, resetsAt: nextResetSec(NOW) });
    assert.equal(a.headers["Retry-After"], String(nextResetSec(NOW) - NOW));
    assert.equal(a.headers["X-Stash-Lossless"], "1/1");
    assert.equal(capAnswer({ used: 1, limit: 1, resetsAt: NOW }, NOW).headers["Retry-After"], "1", "never 0");
});

test("a supporter entitlement skips the cap: limit null, header 'unlimited', still counted", async () => {
    assert.equal(await entitlementOf(REQ, {}), null, "no token, no entitlement");
    const env = { QUOTA_DB: fakeD1(QUOTA_MIGRATIONS), FREE_LOSSLESS_DAILY: "1" };
    await spendLossless(await readStanding(REQ, env, "i:a", NOW));
    const original = hooks.entitlementOf;
    hooks.entitlementOf = async () => ({ tier: "supporter", tid: "t1" });
    try {
        const s = await readStanding(REQ, env, "i:a", NOW);
        assert.equal(s.over, false);
        assert.equal(s.limit, null);
        await spendLossless(s);
        assert.equal(losslessHeaders(s)["X-Stash-Lossless"], "2/unlimited");
        assert.deepEqual(quotaBody(s), { used: 2, limit: null, resetsAt: nextResetSec(NOW), supporter: true });
    } finally { hooks.entitlementOf = original; }
});

test("D1 failures never throw: a failed read turns the counter off, a failed bump still shows the answer", async () => {
    const broken = { prepare: () => { throw new Error("D1 down"); } };
    assert.equal(await readStanding(REQ, { QUOTA_DB: broken }, "i:a", NOW), null);
    const s = { db: broken, day: "2026-10-17", subject: "i:a", used: 4, limit: 500, resetsAt: 1 };
    assert.equal((await spendLossless(s)).used, 5);
    await pruneUsage({ QUOTA_DB: broken }, NOW);
});

test("pruneUsage keeps a week plus today and drops older days", async () => {
    const env = { QUOTA_DB: fakeD1(QUOTA_MIGRATIONS) };
    const put = env.QUOTA_DB.raw.prepare("INSERT INTO usage (day, subject, n) VALUES (?, 'i:a', 1)");
    for (const d of ["2026-10-09", "2026-10-10", "2026-10-17"]) put.run(d);
    await pruneUsage(env, NOW);
    assert.deepEqual(env.QUOTA_DB.raw.prepare("SELECT day FROM usage ORDER BY day").all().map((r) => r.day), ["2026-10-10", "2026-10-17"]);
});

// ---- Supporter tokens (X-Stash-Entitlement), with a test keypair; real SQLite with migrations 0001 + 0002 ----

const KEYS = keypair();
/** A token issued a minute before NOW (the helper defaults iat to the real clock, which is past NOW). */
const tok = (over = {}, keys = KEYS) => token(keys.privateKey, { iat: NOW - 60, ...over });
const tokenReq = (t) => new Request("https://relay.test/", { headers: t === undefined ? {} : { "X-Stash-Entitlement": t } });
const supporterEnv = (over = {}) => ({ QUOTA_DB: fakeD1(QUOTA_MIGRATIONS), ENTITLEMENT_PUBLIC_KEY: KEYS.publicKey, FREE_LOSSLESS_DAILY: "2", ...over });
const rowN = (env, subject) => env.QUOTA_DB.raw.prepare("SELECT n FROM usage WHERE day = ? AND subject = ?").get(quotaDay(NOW), subject)?.n ?? 0;

test("a valid token is { tid }; past the free limit it stays unlimited, and both the install and t:<tid> rows count", async () => {
    const t = tok({ iat: NOW - 86400 * 400 }); // no expiry: a year-old token still works
    assert.deepEqual(await entitlementOf(tokenReq(t), { ENTITLEMENT_PUBLIC_KEY: KEYS.publicKey }, NOW), { tid: tidOf(t) });
    const env = supporterEnv();
    for (let i = 1; i <= 4; i++) {
        const s = await readStanding(tokenReq(t), env, "i:a", NOW);
        assert.equal(s.over, false, `answer ${i}`);
        assert.equal(s.supporter, true);
        assert.equal(s.limit, null);
        await spendLossless(s);
        assert.equal(losslessHeaders(s)["X-Stash-Lossless"], `${i}/unlimited`);
    }
    assert.equal(rowN(env, "i:a"), 4);
    assert.equal(rowN(env, "t:" + tidOf(t)), 4);
    assert.deepEqual(quotaBody(await readStanding(tokenReq(t), env, "i:a", NOW)), { used: 4, limit: null, resetsAt: nextResetSec(NOW), supporter: true });
});

test("iat up to 300 s ahead is accepted, further ahead is not", async () => {
    const env = { ENTITLEMENT_PUBLIC_KEY: KEYS.publicKey };
    assert.notEqual(await entitlementOf(tokenReq(tok({ iat: NOW + 300 })), env, NOW), null);
    assert.equal(await entitlementOf(tokenReq(tok({ iat: NOW + 301 })), env, NOW), null);
});

test("no token, garbage, a bad signature, another key, wrong claims or no public key: null and the free quota", async () => {
    const other = keypair();
    const good = tok();
    const [head, sig] = good.split(".");
    assert.deepEqual(await entitlementOf(tokenReq(good), { ENTITLEMENT_PUBLIC_KEY: KEYS.publicKey }, NOW), { tid: tidOf(good) }, "the control passes");
    const flipped = Buffer.from(sig, "base64url"); flipped[0] ^= 1;
    const b64u = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
    const bad = {
        missing: undefined, empty: "", garbage: "not-a-token", dots: "a.b.c", spaces: `${head} .${sig}`,
        "not base64": "%%%.%%%", "not json": `${Buffer.from("hello").toString("base64url")}.${sig}`,
        "short signature": `${head}.${sig.slice(0, 20)}`, "bad signature": `${head}.${flipped.toString("base64url")}`,
        "payload swapped": `${b64u({ tier: "supporter", iat: NOW, tid: "f".repeat(32), v: 1 })}.${sig}`,
        "another key": tok({}, other),
        "wrong tier": tok({ tier: "admin" }), "v 2": tok({ v: 2 }),
        "v as string": tok({ v: "1" }), "short tid": tok({ tid: "abc" }),
        "upper-case tid": tok({ tid: "A".repeat(32) }), "no iat": tok({ iat: undefined }),
        "huge": "a".repeat(600) + "." + sig,
    };
    for (const [name, t] of Object.entries(bad)) {
        assert.equal(await entitlementOf(tokenReq(t), { ENTITLEMENT_PUBLIC_KEY: KEYS.publicKey }, NOW), null, name);
        const env = supporterEnv({ FREE_LOSSLESS_DAILY: "1" });
        await spendLossless(await readStanding(tokenReq(t), env, "i:a", NOW));
        const s = await readStanding(tokenReq(t), env, "i:a", NOW);
        assert.equal(s.supporter, false, name);
        assert.equal(s.over, true, name);
        assert.equal(losslessHeaders(s)["X-Stash-Lossless"], "1/1", name);
    }
    assert.equal(await entitlementOf(tokenReq(good), {}, NOW), null, "no ENTITLEMENT_PUBLIC_KEY: nobody is a supporter");
    assert.equal(await entitlementOf(tokenReq(good), { ENTITLEMENT_PUBLIC_KEY: "AAAA" }, NOW), null, "a malformed public key");
    assert.equal(await entitlementOf(tokenReq(good), { ENTITLEMENT_PUBLIC_KEY: other.publicKey }, NOW), null, "the relay's key is another one");
});

test("a revoked tid gets the free quota", async () => {
    const t = tok();
    const env = supporterEnv({ FREE_LOSSLESS_DAILY: "1" });
    await spendLossless(await readStanding(tokenReq(t), env, "i:a", NOW));
    env.QUOTA_DB.raw.prepare("INSERT INTO revoked_tokens (tid, revoked_at, reason) VALUES (?, ?, 'posted publicly')").run(tidOf(t), NOW);
    const s = await readStanding(tokenReq(t), env, "i:a", NOW);
    assert.equal(s.supporter, false);
    assert.equal(s.tid, null);
    assert.equal(s.over, true);
    assert.deepEqual(quotaBody(s), { used: 1, limit: 1, resetsAt: nextResetSec(NOW), supporter: false });
    assert.equal((await readStanding(tokenReq(tok()), env, "i:a", NOW)).supporter, true, "other tokens are unaffected");
});

test("SUPPORTER_DAILY_CAP: at the token's ceiling it counts as free (the install's own count and limit), across installs", async () => {
    const t = tok();
    const env = supporterEnv({ SUPPORTER_DAILY_CAP: "3", FREE_LOSSLESS_DAILY: "2" });
    // One token shared by two installs: the ceiling is per token.
    for (const subject of ["i:a", "i:b", "i:a"]) await spendLossless(await readStanding(tokenReq(t), env, subject, NOW));
    assert.equal(rowN(env, "t:" + tidOf(t)), 3);
    const a = await readStanding(tokenReq(t), env, "i:a", NOW);
    assert.equal(a.supporter, false);
    assert.equal(a.over, true, "i:a already has 2 = the free limit");
    assert.equal(losslessHeaders(a)["X-Stash-Lossless"], "2/2");
    const b = await readStanding(tokenReq(t), env, "i:b", NOW);
    assert.equal(b.over, false, "i:b has 1 of its 2 free");
    await spendLossless(b);
    assert.equal(rowN(env, "t:" + tidOf(t)), 3, "a free answer does not bump the token's row");
    assert.equal((await readStanding(tokenReq(t), env, "i:a", NOW + 86400)).supporter, true, "the ceiling resets with the day");
    assert.equal(supporterDailyCap({}), 5000);
    for (const v of ["0", "-1", "x"]) assert.equal(supporterDailyCap({ SUPPORTER_DAILY_CAP: v }), 5000, v);
});

test("a verified token is cached per isolate: the signature is checked once", async () => {
    const t = tok();
    const env = { ENTITLEMENT_PUBLIC_KEY: KEYS.publicKey };
    const original = crypto.subtle.verify;
    let calls = 0;
    crypto.subtle.verify = function (...a) { calls++; return original.apply(this, a); };
    try {
        for (let i = 0; i < 3; i++) assert.deepEqual(await entitlementOf(tokenReq(t), env, NOW), { tid: tidOf(t) });
        assert.equal(calls, 1);
        const bad = tok({}, keypair());
        for (let i = 0; i < 2; i++) assert.equal(await entitlementOf(tokenReq(bad), env, NOW), null);
        assert.equal(calls, 3, "a failure is not cached as a pass");
    } finally { crypto.subtle.verify = original; }
});
