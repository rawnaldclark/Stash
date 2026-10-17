import { test } from "node:test";
import assert from "node:assert/strict";
import { generateKeyPairSync, verify as nodeVerify, createPublicKey } from "node:crypto";
import worker from "../src/index.js";
import { hashEmail } from "../src/access.js";
import { CLAIM_KEYS, CLAIM_TTL_S, clock, codeHashOf, isDonation, LIMITS, MAILER_CLAIM_URL, MAX_TRIES, newCode } from "../src/claim.js";
import { b64url, mintSupporterToken } from "../src/entitlement.js";
import { donation, env, fakeKV, kofiPost } from "./fake-kv.js";

const BASE = "https://stash-tipjar.example.workers.dev";
const PEPPER = "pepper-for-tests";
const TOKEN = "mailer-token-for-tests";
const EMAIL = "ann@example.org";

// A fresh keypair per run: the private key as the Worker gets it (std base64 of PKCS#8 DER), the public one for node:crypto.
const { privateKey, publicKey } = generateKeyPairSync("ed25519");
const PRIVATE_B64 = privateKey.export({ format: "der", type: "pkcs8" }).toString("base64");

/** The MAILER binding: records each claim email; [respond] answers (default: sent). */
function fakeMailer(respond = () => Response.json({ sent: true })) {
    return {
        calls: [],
        async fetch(url, init) {
            this.calls.push({ url, auth: init.headers.authorization, body: JSON.parse(init.body) });
            return respond();
        },
    };
}

/** CLAIM_RL: allows [limit] calls per key. */
function fakeRateLimit(limit = 1000) {
    const counts = new Map();
    return {
        keys: [],
        async limit({ key }) {
            this.keys.push(key);
            const n = (counts.get(key) ?? 0) + 1;
            counts.set(key, n);
            return { success: n <= limit };
        },
    };
}

const fakeCtx = () => ({ waiting: [], waitUntil(p) { this.waiting.push(p); } });

const claimEnv = (over = {}) =>
    env({ EMAIL_PEPPER: PEPPER, MAILER: fakeMailer(), MAILER_TOKEN: TOKEN, ENTITLEMENT_PRIVATE_KEY: PRIVATE_B64, CLAIM_RL: fakeRateLimit(), ...over });

const post = (path, body, ip = "203.0.113.7") =>
    new Request(`${BASE}${path}`, {
        method: "POST",
        headers: { "content-type": "application/json", "cf-connecting-ip": ip },
        body: typeof body === "string" ? body : JSON.stringify(body),
    });

/** Runs a start and waits for its after-response work. */
async function start(e, email = EMAIL, ip) {
    const ctx = fakeCtx();
    const res = await worker.fetch(post("/claim/email/start", { email }, ip), e, ctx);
    await Promise.all(ctx.waiting);
    return { res, body: await res.json(), ctx };
}

const verifyReq = (e, code, email = EMAIL, ip) => worker.fetch(post("/claim/email/verify", { email, code }, ip), e, fakeCtx());

async function asDonor(e, email = EMAIL, source = "kofi") {
    const h = await hashEmail(email, PEPPER);
    await e.ACCESS_KV.put(`access:${h}`, JSON.stringify({ source, firstAt: "2026-10-01T00:00:00.000Z", lastAt: "2026-10-01T00:00:00.000Z" }));
    return h;
}

/** Captures console output. */
async function logsOf(fn) {
    const lines = [];
    const [log, error] = [console.log, console.error];
    console.log = (...a) => lines.push(a.join(" "));
    console.error = (...a) => lines.push(a.join(" "));
    try {
        return { result: await fn(), logs: lines.join("\n") };
    } finally {
        console.log = log;
        console.error = error;
    }
}

const fromB64url = (s) => Buffer.from(s.replace(/-/g, "+").replace(/_/g, "/"), "base64");

/** Verifies a token with node:crypto and a public key; returns the parsed payload and its raw JSON. */
function checkToken(token, pub = publicKey) {
    const parts = token.split(".");
    assert.equal(parts.length, 2);
    assert.match(parts[0], /^[A-Za-z0-9_-]+$/, "first segment is base64url, no padding");
    assert.match(parts[1], /^[A-Za-z0-9_-]+$/, "signature is base64url, no padding");
    assert.equal(nodeVerify(null, Buffer.from(parts[0], "ascii"), pub, fromB64url(parts[1])), true, "Ed25519 signature verifies");
    const raw = fromB64url(parts[0]).toString("utf8");
    return { raw, payload: JSON.parse(raw) };
}

// ---- start ----

test("start for a Ko-fi donor: 202 {ok:true}, one code email through MAILER, a pending claim with the code's hash", async () => {
    const e = claimEnv();
    const h = await asDonor(e);
    const { res, body } = await start(e, "  Ann@Example.ORG ");
    assert.equal(res.status, 202);
    assert.deepEqual(body, { ok: true });
    assert.equal(res.headers.get("cache-control"), "no-store");
    assert.equal(e.MAILER.calls.length, 1);
    const call = e.MAILER.calls[0];
    assert.equal(call.url, MAILER_CLAIM_URL);
    assert.equal(call.auth, `Bearer ${TOKEN}`);
    assert.equal(call.body.email, EMAIL);
    assert.equal(call.body.kind, "code");
    assert.match(call.body.code, /^\d{6}$/);
    const stored = e.ACCESS_KV.map.get(CLAIM_KEYS.claim(h));
    assert.equal(stored.opts.expirationTtl, CLAIM_TTL_S);
    const claim = JSON.parse(stored.value);
    assert.deepEqual(Object.keys(claim).sort(), ["codeHash", "exp", "tries"]);
    assert.equal(claim.tries, 0);
    assert.equal(claim.codeHash, await codeHashOf(call.body.code, h));
    assert.ok(Math.abs(claim.exp - (Math.floor(Date.now() / 1000) + 600)) <= 2);
    assert.ok(!stored.value.includes(call.body.code), "the code itself is not stored");
});

test("start for an address that never donated: the same 202, one no-donation email, no claim", async () => {
    const e = claimEnv();
    const { res, body } = await start(e);
    assert.equal(res.status, 202);
    assert.deepEqual(body, { ok: true });
    assert.deepEqual(e.MAILER.calls.map((c) => c.body), [{ email: EMAIL, kind: "no_donation" }]);
    assert.equal([...e.ACCESS_KV.map.keys()].filter((k) => k.startsWith("claim:")).length, 0);
});

test("only source kofi is a donation: approved, manual and open entries get the no-donation email", async () => {
    assert.equal(isDonation({ source: "kofi" }), true);
    for (const source of ["approved", "manual", "open", "", "Ko-fi"]) {
        assert.equal(isDonation({ source }), false, String(source));
        const e = claimEnv();
        await asDonor(e, EMAIL, source);
        await start(e);
        assert.deepEqual(e.MAILER.calls.map((c) => c.body.kind), ["no_donation"], String(source));
    }
    assert.equal(isDonation(null), false);
});

test("the response is the same for donor and non-donor; the lookup runs after it (waitUntil)", async () => {
    const donor = claimEnv();
    await asDonor(donor);
    const other = claimEnv();
    const a = await worker.fetch(post("/claim/email/start", { email: EMAIL }), donor, fakeCtx());
    const b = await worker.fetch(post("/claim/email/start", { email: EMAIL }), other, fakeCtx());
    assert.equal(a.status, b.status);
    assert.equal(await a.text(), await b.text());
    assert.deepEqual([...a.headers], [...b.headers]);
});

test("start with an invalid email is 400 bad_email; a non-JSON body is 400 bad_request; no mail, no writes", async () => {
    for (const email of ["", "nope", "a@b", "a b@c.de", "<a@b.co>", 42, null, "x".repeat(250) + "@b.co"]) {
        const e = claimEnv();
        const res = await worker.fetch(post("/claim/email/start", { email }), e, fakeCtx());
        assert.equal(res.status, 400, JSON.stringify(email));
        assert.deepEqual(await res.json(), { error: "bad_email" });
        assert.equal(e.MAILER.calls.length, 0);
        assert.equal(e.ACCESS_KV.map.size, 0);
    }
    for (const raw of ["{not json", "[]", '"a@b.co"']) {
        const e = claimEnv();
        const res = await worker.fetch(post("/claim/email/start", raw), e, fakeCtx());
        assert.equal(res.status, 400);
        assert.deepEqual(await res.json(), { error: "bad_request" });
    }
});

test("start: the 4th request for one email in an hour is 429 rate_limited with Retry-After, and sends nothing", async () => {
    const e = claimEnv();
    await asDonor(e);
    for (let i = 0; i < LIMITS.emailPerHour; i++) {
        const { res } = await start(e, EMAIL, `198.51.100.${i}`);
        assert.equal(res.status, 202);
    }
    const { res, body } = await start(e, EMAIL, "198.51.100.200");
    assert.equal(res.status, 429);
    assert.deepEqual(body, { error: "rate_limited" });
    const retry = Number(res.headers.get("retry-after"));
    assert.ok(retry >= 1 && retry <= 3600, `Retry-After ${retry}`);
    assert.equal(e.MAILER.calls.length, LIMITS.emailPerHour);
});

test("start: the 6th request from one IP in an hour is 429, across different emails", async () => {
    const e = claimEnv();
    for (let i = 0; i < LIMITS.ipPerHour; i++) assert.equal((await start(e, `u${i}@example.org`)).res.status, 202);
    const { res, body } = await start(e, "late@example.org");
    assert.equal(res.status, 429);
    assert.deepEqual(body, { error: "rate_limited" });
    assert.ok(Number(res.headers.get("retry-after")) >= 1);
    assert.equal(e.MAILER.calls.length, LIMITS.ipPerHour);
    // Another IP is unaffected.
    assert.equal((await start(e, "late@example.org", "192.0.2.9")).res.status, 202);
});

test("start: the hourly window resets", async () => {
    const e = claimEnv();
    const realNow = clock.now;
    try {
        clock.now = () => Date.UTC(2026, 9, 17, 10, 59, 0);
        for (let i = 0; i < LIMITS.emailPerHour; i++) await start(e, EMAIL, `198.51.100.${i}`);
        const { res } = await start(e, EMAIL, "198.51.100.99");
        assert.equal(res.status, 429);
        assert.equal(res.headers.get("retry-after"), "60");
        clock.now = () => Date.UTC(2026, 9, 17, 11, 0, 1);
        assert.equal((await start(e, EMAIL, "198.51.100.99")).res.status, 202);
    } finally {
        clock.now = realNow;
    }
});

test("start and verify: the CLAIM_RL burst binding answers 429 with Retry-After 60, keyed by route and hashed IP", async () => {
    const e = claimEnv({ CLAIM_RL: fakeRateLimit(1) });
    assert.equal((await start(e)).res.status, 202);
    const { res } = await start(e);
    assert.equal(res.status, 429);
    assert.equal(res.headers.get("retry-after"), "60");
    assert.ok(e.CLAIM_RL.keys.every((k) => /^start:[0-9a-f]{64}$/.test(k)), "no raw IP in the key");
    assert.equal((await verifyReq(e, "000000")).status, 410, "verify has its own bucket");
    const second = await verifyReq(e, "000000");
    assert.equal(second.status, 429);
    assert.deepEqual(await second.json(), { error: "rate_limited" });
});

test("start: no raw IP or email in any KV key or value", async () => {
    const e = claimEnv();
    await asDonor(e);
    await start(e);
    for (const [k, v] of e.ACCESS_KV.map) {
        assert.ok(!k.includes("203.0.113.7") && !String(v.value).includes("203.0.113.7"), k);
        assert.ok(!k.includes(EMAIL) && !String(v.value).includes(EMAIL), k);
    }
});

test("start: a failing mailer still gets the 202 (the outcome is never revealed); a new start replaces the claim", async () => {
    const e = claimEnv({ MAILER: fakeMailer(() => new Response("boom", { status: 500 })) });
    const h = await asDonor(e);
    const { res } = await start(e);
    assert.equal(res.status, 202);
    const first = e.ACCESS_KV.json(CLAIM_KEYS.claim(h));
    await e.ACCESS_KV.put(CLAIM_KEYS.claim(h), JSON.stringify({ ...first, tries: 3 }));
    await start(e, EMAIL, "192.0.2.1");
    const second = e.ACCESS_KV.json(CLAIM_KEYS.claim(h));
    assert.equal(second.tries, 0);
});

test("start: 503 not_configured without the pepper, the mailer, its token or the signing key", async () => {
    for (const missing of ["EMAIL_PEPPER", "MAILER", "MAILER_TOKEN", "ENTITLEMENT_PRIVATE_KEY"]) {
        const e = claimEnv({ [missing]: undefined });
        const res = await worker.fetch(post("/claim/email/start", { email: EMAIL }), e, fakeCtx());
        assert.equal(res.status, 503, missing);
        assert.deepEqual(await res.json(), { error: "not_configured" });
    }
});

test("start and verify log nothing holding the email, the code, the IP or the token", async () => {
    const e = claimEnv();
    await asDonor(e);
    const { logs } = await logsOf(async () => {
        await start(e);
        const code = e.MAILER.calls[0].body.code;
        await verifyReq(e, code === "000000" ? "111111" : "000000");
        const res = await verifyReq(e, code);
        const { token } = await res.json();
        return { code, token };
    }).then(({ result, logs }) => {
        for (const secret of [EMAIL, "ann@", "a***", "203.0.113.7", result.code, result.token]) assert.ok(!logs.includes(secret), secret);
        return { logs };
    });
    assert.ok(logs.length > 0, "it does log something");
});

// ---- verify ----

test("verify with the right code: 200 {token}, the token verifies with the public key, the exact payload, the claim is gone", async () => {
    const e = claimEnv();
    const h = await asDonor(e);
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    const before = Math.floor(Date.now() / 1000);
    const res = await verifyReq(e, code, " ANN@example.org");
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("cache-control"), "no-store");
    const body = await res.json();
    assert.deepEqual(Object.keys(body), ["token"]);
    const { raw, payload } = checkToken(body.token);
    assert.deepEqual(Object.keys(payload), ["tier", "iat", "tid", "v"], "key order");
    assert.equal(payload.tier, "supporter");
    assert.equal(payload.v, 1);
    assert.match(payload.tid, /^[0-9a-f]{32}$/);
    assert.ok(Number.isInteger(payload.iat) && payload.iat >= before && payload.iat <= before + 5, "epoch seconds");
    assert.equal(raw, `{"tier":"supporter","iat":${payload.iat},"tid":"${payload.tid}","v":1}`);
    assert.ok(!raw.includes("ann"), "no personal data");
    assert.equal(e.ACCESS_KV.map.has(CLAIM_KEYS.claim(h)), false);
    // The code is single-use.
    assert.equal((await verifyReq(e, code)).status, 410);
});

test("verify: a code sent as a number is the same code", async () => {
    const e = claimEnv();
    await asDonor(e);
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    if (code.startsWith("0")) return; // a leading zero can't survive as a number; nothing to check
    assert.equal((await verifyReq(e, Number(code))).status, 200);
});

test("verify with a wrong code: 401 bad_code and the try is counted; the right code still works after", async () => {
    const e = claimEnv();
    const h = await asDonor(e);
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    const wrong = code === "123456" ? "654321" : "123456";
    const res = await verifyReq(e, wrong);
    assert.equal(res.status, 401);
    assert.deepEqual(await res.json(), { error: "bad_code" });
    assert.equal(e.ACCESS_KV.json(CLAIM_KEYS.claim(h)).tries, 1);
    assert.equal((await verifyReq(e, "12ab56")).status, 401, "malformed counts as wrong");
    assert.equal(e.ACCESS_KV.json(CLAIM_KEYS.claim(h)).tries, 2);
    assert.equal((await verifyReq(e, code)).status, 200);
});

test("verify: the 5th wrong code deletes the claim and answers 429 too_many_tries; the right code then is 410", async () => {
    const e = claimEnv();
    const h = await asDonor(e);
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    const wrong = code === "000000" ? "999999" : "000000";
    for (let i = 1; i < MAX_TRIES; i++) assert.equal((await verifyReq(e, wrong)).status, 401, `try ${i}`);
    const res = await verifyReq(e, wrong);
    assert.equal(res.status, 429);
    assert.deepEqual(await res.json(), { error: "too_many_tries" });
    assert.equal(e.ACCESS_KV.map.has(CLAIM_KEYS.claim(h)), false);
    assert.equal((await verifyReq(e, code)).status, 410);
});

test("verify with no pending claim, or an expired one, is 410 expired", async () => {
    const e = claimEnv();
    const res = await verifyReq(e, "123456");
    assert.equal(res.status, 410);
    assert.deepEqual(await res.json(), { error: "expired" });

    const h = await asDonor(e);
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    const claim = e.ACCESS_KV.json(CLAIM_KEYS.claim(h));
    await e.ACCESS_KV.put(CLAIM_KEYS.claim(h), JSON.stringify({ ...claim, exp: Math.floor(Date.now() / 1000) - 1 }));
    assert.equal((await verifyReq(e, code)).status, 410);
    assert.equal(e.ACCESS_KV.map.has(CLAIM_KEYS.claim(h)), false, "the stale claim is cleared");
});

test("verify for a non-donor (who never got a code) is 410", async () => {
    const e = claimEnv();
    await start(e);
    assert.equal((await verifyReq(e, "123456")).status, 410);
});

test("verify: 503 not_configured without ENTITLEMENT_PRIVATE_KEY (checked first); 503 for an unreadable key, claim kept", async () => {
    const e = claimEnv({ ENTITLEMENT_PRIVATE_KEY: undefined });
    const res = await verifyReq(e, "123456");
    assert.equal(res.status, 503);
    assert.deepEqual(await res.json(), { error: "not_configured" });

    const bad = claimEnv();
    const h = await asDonor(bad);
    await start(bad);
    const code = bad.MAILER.calls[0].body.code;
    bad.ENTITLEMENT_PRIVATE_KEY = Buffer.from("not a key").toString("base64");
    const { result } = await logsOf(() => verifyReq(bad, code));
    assert.equal(result.status, 503);
    assert.equal(bad.ACCESS_KV.map.has(CLAIM_KEYS.claim(h)), true);
});

test("verify: bad email 400, bad body 400", async () => {
    const e = claimEnv();
    const res = await verifyReq(e, "123456", "nope");
    assert.equal(res.status, 400);
    assert.deepEqual(await res.json(), { error: "bad_email" });
    assert.equal((await worker.fetch(post("/claim/email/verify", "{"), e)).status, 400);
});

// ---- token + helpers ----

test("mintSupporterToken: iat in seconds, fresh tid each time, verifies with the raw 32-byte public key", async () => {
    const t1 = await mintSupporterToken(PRIVATE_B64, Date.UTC(2026, 9, 17, 12, 0, 0, 999));
    const t2 = await mintSupporterToken(PRIVATE_B64, Date.UTC(2026, 9, 17, 12, 0, 0));
    const p1 = checkToken(t1).payload;
    assert.equal(p1.iat, Date.UTC(2026, 9, 17, 12, 0, 0) / 1000);
    assert.notEqual(p1.tid, checkToken(t2).payload.tid);
    // The relays and the app hold the public key as raw 32 bytes (std base64), like the production one in the README.
    const raw = publicKey.export({ format: "jwk" }).x;
    const rebuilt = createPublicKey({ key: { kty: "OKP", crv: "Ed25519", x: raw }, format: "jwk" });
    assert.equal(fromB64url(raw).length, 32);
    checkToken(t1, rebuilt);
    // A tampered payload fails.
    const [head, sig] = t1.split(".");
    const forged = b64url(Buffer.from(fromB64url(head).toString().replace('"supporter"', '"supporteR"'))) + "." + sig;
    assert.throws(() => checkToken(forged));
});

test("newCode is always six digits", () => {
    for (let i = 0; i < 2000; i++) assert.match(newCode(), /^\d{6}$/);
});

// ---- existing routes ----

test("existing routes are unchanged: GET / and any GET path list supporters; POST / and other POST paths are the webhook", async () => {
    const e = claimEnv();
    assert.equal(await (await worker.fetch(new Request(`${BASE}/`), e)).text(), '{"supporters":[]}');
    assert.equal(await (await worker.fetch(new Request(`${BASE}/claim/email/start`), e)).text(), '{"supporters":[]}', "GET on a claim path is the list");
    assert.equal((await worker.fetch(kofiPost(donation()), e, fakeCtx())).status, 200);
    assert.equal((await worker.fetch(kofiPost(donation({ kofi_transaction_id: "tx-2", message_id: "m2" }), `${BASE}/hook`), e, fakeCtx())).status, 200);
    assert.equal((await worker.fetch(new Request(`${BASE}/claim/email/start`, { method: "PUT" }), e)).status, 405);
    // A Ko-fi donor made by the webhook can then claim.
    await start(e, "ann@example.org");
    assert.equal(e.MAILER.calls.at(-1).body.kind, "code");
});
