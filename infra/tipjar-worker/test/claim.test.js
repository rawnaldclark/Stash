import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, verify as nodeVerify, createPublicKey } from "node:crypto";
import worker from "../src/index.js";
import { hashEmail } from "../src/access.js";
import { CLAIM_TTL_S, clock, codeHashOf, ipBucket, isComped, isDonation, LIMITS, MAILER_CLAIM_URL, MAX_TRIES, newCode } from "../src/claim.js";
import { putClaim, takeTry } from "../src/claim-store.js";
import { b64url, mintSupporterToken } from "../src/entitlement.js";
import { donation, env, fakeKV, kofiPost } from "./fake-kv.js";
import { fakeD1 } from "./fake-d1.js";

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
    env({ EMAIL_PEPPER: PEPPER, MAILER: fakeMailer(), MAILER_TOKEN: TOKEN, ENTITLEMENT_PRIVATE_KEY: PRIVATE_B64, CLAIM_RL: fakeRateLimit(), CLAIMS_DB: fakeD1(), ...over });

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

/** Runs a verify and waits for its after-response work (deleting an exhausted claim). */
async function verifyReq(e, code, email = EMAIL, ip) {
    const ctx = fakeCtx();
    const res = await worker.fetch(post("/claim/email/verify", { email, code }, ip), e, ctx);
    await Promise.all(ctx.waiting);
    return res;
}

/** verify's one failure answer, whatever the reason. */
async function assertBadCode(res, why) {
    assert.equal(res.status, 401, why);
    assert.deepEqual(await res.json(), { error: "bad_code" }, why);
}

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
    const claim = e.CLAIMS_DB.claim(h);
    assert.deepEqual(Object.keys(claim).sort(), ["code_hash", "email_hash", "expires_at", "tries"]);
    assert.equal(claim.tries, 0);
    assert.equal(claim.code_hash, await codeHashOf(call.body.code, h, PEPPER));
    assert.ok(Math.abs(claim.expires_at - (Math.floor(Date.now() / 1000) + CLAIM_TTL_S)) <= 2);
    assert.ok(!JSON.stringify(claim).includes(call.body.code), "the code itself is not stored");
    assert.equal([...e.ACCESS_KV.map.keys()].filter((k) => k.startsWith("claim:")).length, 0, "nothing of the claim in KV");
});

test("the stored code hash is keyed with EMAIL_PEPPER: without it a D1 read can't test a guess", async () => {
    const h = await hashEmail(EMAIL, PEPPER);
    const keyed = await codeHashOf("123456", h, PEPPER);
    assert.match(keyed, /^[0-9a-f]{64}$/);
    assert.equal(keyed, await codeHashOf("123456", h, PEPPER), "deterministic");
    assert.notEqual(keyed, await codeHashOf("123456", h, "another-pepper"));
    assert.notEqual(keyed, await codeHashOf("123457", h, PEPPER));
    assert.notEqual(keyed, await codeHashOf("123456", "0".repeat(64), PEPPER), "bound to the address");
    for (const unkeyed of [`123456${h}`, `${h}123456`, `claim-code:v1:${h}:123456`, "123456"]) {
        assert.notEqual(keyed, createHash("sha256").update(unkeyed).digest("hex"));
    }
});

test("start for an address that never donated: the same 202, one no-donation email, no claim", async () => {
    const e = claimEnv();
    const { res, body } = await start(e);
    assert.equal(res.status, 202);
    assert.deepEqual(body, { ok: true });
    assert.deepEqual(e.MAILER.calls.map((c) => c.body), [{ email: EMAIL, kind: "no_donation" }]);
    assert.equal(e.CLAIMS_DB.count("claims"), 0);
});

test("isComped: exact, case-insensitive match on a comma/space/newline list; unset, empty or junk matches nobody", () => {
    const list = " Mod@Example.com,friend@example.org;\n  pal@example.net ";
    for (const email of ["mod@example.com", "FRIEND@example.org", " pal@example.net "]) {
        assert.equal(isComped({ COMPED_EMAILS: list }, email), true, email);
    }
    for (const email of ["joe@example.com", "od@example.com", "xmod@example.com", "friend@example.org.evil.com", ""]) {
        assert.equal(isComped({ COMPED_EMAILS: list }, email), false, JSON.stringify(email));
    }
    for (const COMPED_EMAILS of [undefined, null, "", "   ", " , ;\n", 42]) {
        assert.equal(isComped({ COMPED_EMAILS }, EMAIL), false, String(COMPED_EMAILS));
        assert.equal(isComped({ COMPED_EMAILS }, ""), false, "an empty address never matches an empty list entry");
    }
});

test("start for a comped email with no donation: a code email, a pending claim, and verify mints a valid token", async () => {
    const e = claimEnv({ COMPED_EMAILS: "Mod@Example.com, friend@example.org" }); // ACCESS_KV has no donor entry
    const h = await hashEmail("mod@example.com", PEPPER);
    const { res, body } = await start(e, "mod@example.com");
    assert.equal(res.status, 202);
    assert.deepEqual(body, { ok: true });
    assert.equal(e.MAILER.calls.length, 1);
    const call = e.MAILER.calls[0];
    assert.equal(call.body.kind, "code", "not no_donation");
    assert.equal(call.body.email, "mod@example.com");
    assert.match(call.body.code, /^\d{6}$/);
    assert.equal(e.CLAIMS_DB.claim(h).code_hash, await codeHashOf(call.body.code, h, PEPPER), "verify has a claim to check");

    const ok = await verifyReq(e, call.body.code, "mod@example.com");
    assert.equal(ok.status, 200);
    assert.equal(checkToken((await ok.json()).token).payload.tier, "supporter");
});

test("a comped email still has to receive the code: a wrong code is bad_code, and the claim is not skipped", async () => {
    const e = claimEnv({ COMPED_EMAILS: EMAIL });
    // No start was ever made for this address, so there is nothing to verify against.
    await assertBadCode(await verifyReq(e, "123456"), "comped, but no code requested");
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    await assertBadCode(await verifyReq(e, code === "000000" ? "111111" : "000000"), "comped, wrong code");
    assert.equal((await verifyReq(e, code)).status, 200);
});

test("with a comp list set, an address that is not on it still gets the no-donation email and no claim", async () => {
    const e = claimEnv({ COMPED_EMAILS: "mod@example.com" });
    const { res } = await start(e, "stranger@example.org");
    assert.equal(res.status, 202);
    assert.deepEqual(e.MAILER.calls.map((c) => c.body), [{ email: "stranger@example.org", kind: "no_donation" }]);
    assert.equal(e.CLAIMS_DB.count("claims"), 0);
});

test("a comped claim records no donation: no access entry, no supporters row, no no-donation counter", async () => {
    const e = claimEnv({ COMPED_EMAILS: EMAIL });
    await start(e);
    await verifyReq(e, e.MAILER.calls[0].body.code);
    const keys = [...e.ACCESS_KV.map.keys()];
    assert.equal(keys.filter((k) => k.startsWith("access:") || k.startsWith("goal:")).length, 0, "nothing early-access or goal");
    assert.equal([...e.STASH_KV.map.keys()].filter((k) => k.startsWith("supporter:")).length, 0, "not in the public ticker");
    assert.equal(e.CLAIMS_DB.count("claim_counters"), 0, "a comped code isn't counted against the no-donation cap");
});

test("a comped email's code isn't held back by the no-donation cap", async () => {
    const e = claimEnv({ COMPED_EMAILS: "mod@example.com" });
    const realNow = clock.now;
    try {
        clock.now = () => Date.UTC(2026, 9, 17, 10, 5, 0);
        for (let i = 0; i < LIMITS.noDonationPerHour + 1; i++) {
            await logsOf(() => start(e, `nobody${i}@example.org`, `198.51.${Math.floor(i / 200)}.${i % 200}`));
        }
        await start(e, "mod@example.com", "192.0.2.50");
        assert.equal(e.MAILER.calls.at(-1).body.kind, "code");
    } finally {
        clock.now = realNow;
    }
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
        assert.equal(e.CLAIMS_DB.count("claims") + e.CLAIMS_DB.count("claim_counters"), 0);
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
    await assertBadCode(await verifyReq(e, "000000"), "verify has its own bucket");
    const second = await verifyReq(e, "000000");
    assert.equal(second.status, 429);
    assert.equal(second.headers.get("retry-after"), "60");
    assert.deepEqual(await second.json(), { error: "rate_limited" });
});

test("verify is also limited per email hash (v:<hash>), whatever the IP", async () => {
    const e = claimEnv({ CLAIM_RL: fakeRateLimit(2) });
    const h = await hashEmail(EMAIL, PEPPER);
    await assertBadCode(await verifyReq(e, "000000", EMAIL, "198.51.100.1"));
    await assertBadCode(await verifyReq(e, "000000", EMAIL, "198.51.100.2"));
    const third = await verifyReq(e, "000000", EMAIL, "198.51.100.3");
    assert.equal(third.status, 429, "a fresh IP doesn't help");
    assert.deepEqual(await third.json(), { error: "rate_limited" });
    const verifyKeys = e.CLAIM_RL.keys.filter((k) => !k.startsWith("start:"));
    assert.ok(verifyKeys.includes(`v:${h}`));
    assert.ok(verifyKeys.every((k) => /^(verify:|v:)[0-9a-f]{64}$/.test(k)), "no raw IP or email in a key");
    // Another address from a fresh IP is unaffected.
    await assertBadCode(await verifyReq(e, "000000", "bob@example.org", "198.51.100.4"));
});

test("start: no raw IP or email in any KV key or value", async () => {
    const e = claimEnv();
    await asDonor(e);
    await start(e);
    for (const [k, v] of e.ACCESS_KV.map) {
        assert.ok(!k.includes("203.0.113.7") && !String(v.value).includes("203.0.113.7"), k);
        assert.ok(!k.includes(EMAIL) && !String(v.value).includes(EMAIL), k);
    }
    const code = e.MAILER.calls[0].body.code;
    const rows = JSON.stringify([...e.CLAIMS_DB.raw.prepare("SELECT * FROM claims").all(), ...e.CLAIMS_DB.raw.prepare("SELECT * FROM claim_counters").all()]);
    for (const secret of ["203.0.113.7", EMAIL, code]) assert.ok(!rows.includes(secret), secret);
});

test("start: a failing mailer still gets the 202 (the outcome is never revealed), and stores no claim", async () => {
    const failures = [
        () => new Response("boom", { status: 500 }),
        () => Response.json({ sent: false, error: "E_RATE_LIMIT_EXCEEDED" }),
        () => Response.json({ error: "too_many_calls" }, { status: 429 }),
        () => {
            throw new Error("network");
        },
    ];
    for (const respond of failures) {
        const e = claimEnv({ MAILER: fakeMailer(respond) });
        const h = await asDonor(e);
        const { result } = await logsOf(() => start(e));
        assert.equal(result.res.status, 202);
        assert.deepEqual(result.body, { ok: true });
        assert.equal(e.CLAIMS_DB.claim(h), null, "a code nobody received is not stored");
    }
});

test("start the mailer refuses (its one-a-minute limit) leaves the pending claim and its code as they were", async () => {
    let refuse = false;
    const e = claimEnv({ MAILER: fakeMailer(() => (refuse ? Response.json({ sent: false, error: "rate_limited" }) : Response.json({ sent: true }))) });
    const h = await asDonor(e);
    await start(e);
    const firstCode = e.MAILER.calls[0].body.code;
    await assertBadCode(await verifyReq(e, firstCode === "000000" ? "111111" : "000000"));
    const before = e.CLAIMS_DB.claim(h);
    assert.equal(before.tries, 1);

    refuse = true;
    const { result } = await logsOf(() => start(e, EMAIL, "192.0.2.1"));
    assert.equal(result.res.status, 202, "still the same 202");
    assert.equal(e.MAILER.calls.length, 2, "the mailer was asked");
    assert.deepEqual(e.CLAIMS_DB.claim(h), before, "untouched: same code, same tries, same expiry");
    assert.equal((await verifyReq(e, firstCode)).status, 200, "the code the donor holds still works");
});

test("start the mailer sends replaces the claim: new code, tries back to 0; the old code stops working", async () => {
    const e = claimEnv();
    const h = await asDonor(e);
    await start(e);
    const oldCode = e.MAILER.calls[0].body.code;
    await assertBadCode(await verifyReq(e, oldCode === "000000" ? "111111" : "000000"));
    assert.equal(e.CLAIMS_DB.claim(h).tries, 1);
    let newCodeSent;
    do {
        await start(e, EMAIL, `192.0.2.${e.MAILER.calls.length}`);
        newCodeSent = e.MAILER.calls.at(-1).body.code;
    } while (newCodeSent === oldCode); // one in a million
    assert.equal(e.CLAIMS_DB.claim(h).tries, 0);
    await assertBadCode(await verifyReq(e, oldCode), "the replaced code");
    assert.equal((await verifyReq(e, newCodeSent)).status, 200);
});

test("start: 503 not_configured without the pepper, the mailer, its token, the signing key or CLAIMS_DB", async () => {
    for (const missing of ["EMAIL_PEPPER", "MAILER", "MAILER_TOKEN", "ENTITLEMENT_PRIVATE_KEY", "CLAIMS_DB"]) {
        const e = claimEnv({ [missing]: undefined });
        const { result: res } = await logsOf(() => worker.fetch(post("/claim/email/start", { email: EMAIL }), e, fakeCtx()));
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
    assert.equal(e.CLAIMS_DB.claim(h), null);
    // The code is single-use.
    await assertBadCode(await verifyReq(e, code), "a used code");
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
    await assertBadCode(await verifyReq(e, wrong));
    assert.equal(e.CLAIMS_DB.claim(h).tries, 1);
    await assertBadCode(await verifyReq(e, "12ab56"), "malformed counts as wrong");
    assert.equal(e.CLAIMS_DB.claim(h).tries, 2);
    assert.equal((await verifyReq(e, code)).status, 200);
});

test("verify: the 5th wrong code deletes the claim and answers the same 401 bad_code; the right code then is bad_code too", async () => {
    const e = claimEnv();
    const h = await asDonor(e);
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    const wrong = code === "000000" ? "999999" : "000000";
    for (let i = 1; i <= MAX_TRIES; i++) await assertBadCode(await verifyReq(e, wrong), `try ${i}`);
    assert.equal(e.CLAIMS_DB.claim(h), null, "deleted (after the response)");
    await assertBadCode(await verifyReq(e, code), "the right code after the claim is spent");
});

test("verify: the right code on the 5th (last) try still works", async () => {
    const e = claimEnv();
    await asDonor(e);
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    const wrong = code === "000000" ? "999999" : "000000";
    for (let i = 1; i < MAX_TRIES; i++) await assertBadCode(await verifyReq(e, wrong), `try ${i}`);
    assert.equal((await verifyReq(e, code)).status, 200);
});

test("verify with no pending claim, an expired one, or a non-donor's address is the same 401 bad_code", async () => {
    const e = claimEnv();
    await assertBadCode(await verifyReq(e, "123456"), "no claim");

    const h = await asDonor(e);
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    e.CLAIMS_DB.setClaim(h, { expires_at: Math.floor(Date.now() / 1000) - 1 });
    await assertBadCode(await verifyReq(e, code), "expired, even with the right code");
    assert.equal(e.CLAIMS_DB.claim(h).tries, 0, "an expired claim takes no tries");

    const other = claimEnv();
    await start(other);
    await assertBadCode(await verifyReq(other, "123456"), "a non-donor who never got a code");
});

test("verify reveals nothing: no claim, expired, wrong code and used-up tries get byte-identical answers", async () => {
    const answer = async (res) => ({ status: res.status, headers: [...res.headers], body: await res.text() });
    const wrongFor = (code) => (code === "000000" ? "111111" : "000000");

    const none = await answer(await verifyReq(claimEnv(), "123456"));

    const ex = claimEnv();
    const exHash = await asDonor(ex);
    await start(ex);
    ex.CLAIMS_DB.setClaim(exHash, { expires_at: 1 });
    const expired = await answer(await verifyReq(ex, ex.MAILER.calls[0].body.code));

    const wr = claimEnv();
    await asDonor(wr);
    await start(wr);
    const wrong = await answer(await verifyReq(wr, wrongFor(wr.MAILER.calls[0].body.code)));

    const tm = claimEnv();
    await asDonor(tm);
    await start(tm);
    const tmCode = tm.MAILER.calls[0].body.code;
    for (let i = 1; i < MAX_TRIES; i++) await verifyReq(tm, wrongFor(tmCode));
    const lastTry = await answer(await verifyReq(tm, wrongFor(tmCode)));
    const afterLimit = await answer(await verifyReq(tm, tmCode));

    for (const [name, a] of Object.entries({ expired, wrong, lastTry, afterLimit })) assert.deepEqual(a, none, name);
    assert.equal(none.status, 401);
    assert.equal(none.body, '{"error":"bad_code"}');
});

test("verify under parallel requests: 20 wrong guesses at once use 5 tries, not 20; the claim is then spent", async () => {
    const e = claimEnv();
    const h = await asDonor(e);
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    const guesses = [];
    for (let i = 0; guesses.length < 20; i++) {
        const g = String(i).padStart(6, "0");
        if (g !== code) guesses.push(g);
    }
    const results = await Promise.all(guesses.map((g, i) => verifyReq(e, g, EMAIL, `198.51.100.${i}`)));
    for (const res of results) await assertBadCode(res);
    assert.equal(e.CLAIMS_DB.claim(h), null, "the claim is spent and deleted");
    await assertBadCode(await verifyReq(e, code), "the right code can't ride on a 6th try");
});

test("takeTry is atomic: of 50 parallel calls on one claim exactly MAX_TRIES get a row, with tries 1..MAX_TRIES", async () => {
    const db = fakeD1();
    const nowS = Math.floor(Date.now() / 1000);
    await putClaim(db, "h".repeat(64), "c".repeat(64), nowS + 600);
    const rows = await Promise.all(Array.from({ length: 50 }, () => takeTry(db, "h".repeat(64), MAX_TRIES, nowS)));
    const granted = rows.filter(Boolean);
    assert.equal(granted.length, MAX_TRIES);
    assert.deepEqual(granted.map((r) => r.tries).sort((a, b) => a - b), Array.from({ length: MAX_TRIES }, (_, i) => i + 1));
    assert.equal(db.claim("h".repeat(64)).tries, MAX_TRIES);
});

test("verify under parallel requests: the right code sent 5 times at once mints exactly one token", async () => {
    const e = claimEnv();
    const h = await asDonor(e);
    await start(e);
    const code = e.MAILER.calls[0].body.code;
    const results = await Promise.all(Array.from({ length: MAX_TRIES }, (_, i) => verifyReq(e, code, EMAIL, `198.51.100.${i}`)));
    const ok = results.filter((r) => r.status === 200);
    assert.equal(ok.length, 1, "single use holds under a race");
    for (const res of results.filter((r) => r.status !== 200)) await assertBadCode(res);
    checkToken((await ok[0].json()).token);
    assert.equal(e.CLAIMS_DB.claim(h), null);
});

test("verify: 503 not_configured without ENTITLEMENT_PRIVATE_KEY or CLAIMS_DB (checked first); 503 for an unreadable key, claim kept", async () => {
    for (const missing of ["ENTITLEMENT_PRIVATE_KEY", "CLAIMS_DB", "EMAIL_PEPPER"]) {
        const e = claimEnv({ [missing]: undefined });
        const { result: res } = await logsOf(() => verifyReq(e, "123456"));
        assert.equal(res.status, 503, missing);
        assert.deepEqual(await res.json(), { error: "not_configured" });
    }

    const bad = claimEnv();
    const h = await asDonor(bad);
    await start(bad);
    const code = bad.MAILER.calls[0].body.code;
    bad.ENTITLEMENT_PRIVATE_KEY = Buffer.from("not a key").toString("base64");
    const { result } = await logsOf(() => verifyReq(bad, code));
    assert.equal(result.status, 503);
    assert.notEqual(bad.CLAIMS_DB.claim(h), null, "kept for a retry once the key is fixed");
    bad.ENTITLEMENT_PRIVATE_KEY = PRIVATE_B64;
    assert.equal((await verifyReq(bad, code)).status, 200);
});

// ---- start abuse: IPv6 /64, the no-donation cap ----

test("ipBucket: IPv4 as is, IPv6 by its /64, IPv4-mapped as the IPv4 address", () => {
    assert.equal(ipBucket("203.0.113.7"), "203.0.113.7");
    assert.equal(ipBucket("2001:db8:1:2:3:4:5:6"), "2001:0db8:0001:0002::/64");
    assert.equal(ipBucket("2001:DB8:1:2::9"), "2001:0db8:0001:0002::/64");
    assert.equal(ipBucket("2001:db8:1:2:ffff:ffff:ffff:ffff"), "2001:0db8:0001:0002::/64");
    assert.notEqual(ipBucket("2001:db8:1:3::1"), ipBucket("2001:db8:1:2::1"), "the next /64 is another bucket");
    assert.equal(ipBucket("2001:db8::1"), "2001:0db8:0000:0000::/64");
    assert.equal(ipBucket("::ffff:192.0.2.1"), "192.0.2.1");
    assert.equal(ipBucket("::ffff:c000:201"), "192.0.2.1");
    assert.equal(ipBucket("fe80::1%eth0"), "fe80:0000:0000:0000::/64");
    assert.equal(ipBucket("64:ff9b::192.0.2.1"), "0064:ff9b:0000:0000::/64");
    for (const junk of ["1::2::3", "1:2:3:4:5:6:7", "zz::1", "1:2:3:4:5:6:7:8:9"]) assert.equal(ipBucket(junk), junk, junk);
    assert.equal(ipBucket(""), "unknown");
});

test("start: addresses in one IPv6 /64 share the hourly IP limit; another /64 doesn't", async () => {
    const e = claimEnv();
    for (let i = 0; i < LIMITS.ipPerHour; i++) {
        assert.equal((await start(e, `u${i}@example.org`, `2001:db8:aa:bb::${i + 1}`)).res.status, 202);
    }
    const { res } = await start(e, "late@example.org", "2001:db8:aa:bb:dead:beef:1:2");
    assert.equal(res.status, 429, "a fresh address in the same /64");
    assert.equal((await start(e, "late@example.org", "2001:db8:aa:bc::1")).res.status, 202, "the next /64");
});

test("start: the burst key is the /64 too", async () => {
    const e = claimEnv({ CLAIM_RL: fakeRateLimit(1) });
    assert.equal((await start(e, "a@example.org", "2001:db8:aa:bb::1")).res.status, 202);
    assert.equal((await start(e, "b@example.org", "2001:db8:aa:bb::2")).res.status, 429);
});

test(`start: past ${LIMITS.noDonationPerHour} no-donation emails an hour the rest are skipped silently (still 202); donors still get codes`, async () => {
    const e = claimEnv();
    const realNow = clock.now;
    try {
        clock.now = () => Date.UTC(2026, 9, 17, 10, 5, 0);
        const cap = LIMITS.noDonationPerHour;
        for (let i = 0; i < cap + 3; i++) {
            const { res, body } = await logsOf(() => start(e, `nobody${i}@example.org`, `198.51.${Math.floor(i / 200)}.${i % 200}`)).then((r) => r.result);
            assert.equal(res.status, 202, `start ${i}`);
            assert.deepEqual(body, { ok: true });
        }
        assert.equal(e.MAILER.calls.length, cap, "emails stop at the cap");
        assert.ok(e.MAILER.calls.every((c) => c.body.kind === "no_donation"));

        await asDonor(e, "donor@example.org");
        await start(e, "donor@example.org", "192.0.2.50");
        assert.equal(e.MAILER.calls.at(-1).body.kind, "code", "a donor's code isn't held back by the cap");

        clock.now = () => Date.UTC(2026, 9, 17, 11, 0, 1);
        await start(e, "nobody-next-hour@example.org", "192.0.2.51");
        assert.equal(e.MAILER.calls.at(-1).body.kind, "no_donation", "the next hour has its own count");
    } finally {
        clock.now = realNow;
    }
});

test("the daily cron drops expired claims and counters, and keeps live ones", async () => {
    const e = claimEnv();
    const nowS = Math.floor(Date.now() / 1000);
    await putClaim(e.CLAIMS_DB, "a".repeat(64), "c".repeat(64), nowS - 1);
    await putClaim(e.CLAIMS_DB, "b".repeat(64), "c".repeat(64), nowS + 600);
    e.CLAIMS_DB.raw.prepare("INSERT INTO claim_counters (key, n, expires_at) VALUES ('nodonation:1', 3, ?), ('nodonation:2', 3, ?)").run(nowS - 1, nowS + 3600);
    await logsOf(() => worker.scheduled({}, e));
    assert.equal(e.CLAIMS_DB.claim("a".repeat(64)), null);
    assert.notEqual(e.CLAIMS_DB.claim("b".repeat(64)), null);
    assert.equal(e.CLAIMS_DB.count("claim_counters"), 1);
});

test("verify: a CLAIMS_DB failure is 503 unavailable with Retry-After, not a bad_code (an outage isn't an answer)", async () => {
    const down = { prepare: () => ({ bind: () => ({ first: async () => { throw new Error("D1_ERROR: boom"); } }) }) };
    const e = claimEnv({ CLAIMS_DB: down });
    const { result: res, logs } = await logsOf(() => verifyReq(e, "123456"));
    assert.equal(res.status, 503);
    assert.equal(res.headers.get("retry-after"), "30");
    assert.deepEqual(await res.json(), { error: "unavailable" });
    assert.match(logs, /CLAIMS_DB failed/);
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

// ---- the $5 entry (owner's call, 2026-11-02) ----

/** One Ko-fi payment from [email] through the webhook, as transaction [txn]. */
async function kofiPay(e, amount, txn, email = EMAIL, over = {}) {
    const ctx = fakeCtx();
    const res = await worker.fetch(kofiPost(donation({ email, amount, kofi_transaction_id: txn, message_id: `m-${txn}`, ...over })), e, ctx);
    await Promise.all(ctx.waiting);
    assert.equal(res.status, 200);
}

/** The claim emails asked of MAILER (the webhook's welcomes go through it too). */
const claimMails = (e) => e.MAILER.calls.filter((c) => c.url === MAILER_CLAIM_URL).map((c) => c.body);

test("start for a Ko-fi donor under $5: the below_entry email with the total so far, no claim, and verify mints nothing", async () => {
    const e = claimEnv();
    await logsOf(() => kofiPay(e, "3.00", "tx-a"));
    const { res, body } = await logsOf(() => start(e)).then((r) => r.result);
    assert.equal(res.status, 202);
    assert.deepEqual(body, { ok: true });
    assert.deepEqual(claimMails(e), [{ email: EMAIL, kind: "below_entry", cents: 300 }]);
    assert.equal(e.CLAIMS_DB.count("claims"), 0);
    await assertBadCode(await verifyReq(e, "123456"), "no claim to verify");
});

test("start once an email's Ko-fi payments add up to $5 ($3, then $2): a code, and verify mints a token", async () => {
    const e = claimEnv();
    await logsOf(() => kofiPay(e, "3.00", "tx-a"));
    await logsOf(() => kofiPay(e, "2.00", "tx-b"));
    await logsOf(() => start(e));
    const [mail] = claimMails(e);
    assert.equal(mail.kind, "code");
    const res = await verifyReq(e, mail.code);
    assert.equal(res.status, 200);
    assert.equal(checkToken((await res.json()).token).payload.tier, "supporter");
});

test("monthly payments count toward the claim: $3 a month is below_entry after one, a code after two", async () => {
    const e = claimEnv();
    const monthly = { type: "Subscription", is_subscription_payment: true };
    await logsOf(() => kofiPay(e, "3.00", "tx-m1", EMAIL, monthly));
    await logsOf(() => start(e, EMAIL, "203.0.113.20"));
    await logsOf(() => kofiPay(e, "3.00", "tx-m2", EMAIL, { ...monthly, timestamp: "2026-11-04T12:00:00Z" }));
    await logsOf(() => start(e, EMAIL, "203.0.113.21"));
    assert.deepEqual(claimMails(e).map((m) => m.kind), ["below_entry", "code"]);
});

test("a non-USD donor under the entry hears their US$ total; at the US$5 equivalent they get a code", async () => {
    const e = claimEnv();
    await logsOf(() => kofiPay(e, "3.00", "tx-c1", EMAIL, { currency: "CAD" })); // 219 US cents
    await logsOf(() => start(e, EMAIL, "203.0.113.30"));
    assert.deepEqual(claimMails(e).at(-1), { email: EMAIL, kind: "below_entry", cents: 219 });
    await logsOf(() => kofiPay(e, "3.00", "tx-e1", EMAIL, { currency: "EUR" })); // + 324 = 543
    await logsOf(() => start(e, EMAIL, "203.0.113.31"));
    assert.equal(claimMails(e).at(-1).kind, "code");
});

test("an unknown currency can't be checked: the donor is let in and can claim", async () => {
    const e = claimEnv();
    await logsOf(() => kofiPay(e, "1.00", "tx-x", EMAIL, { currency: "XYZ" }));
    await logsOf(() => start(e));
    assert.equal(claimMails(e)[0].kind, "code");
});

test("a donor let in before the entry keeps their claim: a $1 payment now changes nothing", async () => {
    const e = claimEnv();
    await asDonor(e); // a kofi entry from before 2026-11-02, whatever it was for
    await logsOf(() => kofiPay(e, "1.00", "tx-small"));
    await logsOf(() => start(e));
    assert.equal(claimMails(e)[0].kind, "code");
});

test("an address added by hand (manual) with Ko-fi payments under $5 gets below_entry, and its entry is untouched", async () => {
    const e = claimEnv();
    const h = await asDonor(e, EMAIL, "manual");
    const raw = e.ACCESS_KV.map.get(`access:${h}`).value;
    await logsOf(() => kofiPay(e, "4.00", "tx-man"));
    await logsOf(() => start(e));
    assert.deepEqual(claimMails(e), [{ email: EMAIL, kind: "below_entry", cents: 400 }]);
    assert.equal(e.ACCESS_KV.map.get(`access:${h}`).value, raw);
});

test("a mailer from before below_entry (400 bad_kind) gets no_donation instead, so the address still hears back", async () => {
    const mailer = fakeMailer(() =>
        mailer.calls.at(-1).body.kind === "below_entry" ? Response.json({ error: "bad_kind" }, { status: 400 }) : Response.json({ sent: true }),
    );
    const e = claimEnv({ MAILER: mailer });
    await logsOf(() => kofiPay(e, "3.00", "tx-a"));
    const { ctx } = await logsOf(() => start(e)).then((r) => r.result);
    assert.equal(await ctx.waiting[0], true, "an email went out");
    assert.deepEqual(claimMails(e).map((m) => m.kind), ["below_entry", "no_donation"]);
    assert.equal(e.CLAIMS_DB.count("claims"), 0);
});

test("below_entry the mailer refuses for any other reason (its per-address limit) is not sent twice", async () => {
    const mailer = fakeMailer(() =>
        mailer.calls.at(-1).body.kind === "below_entry" ? Response.json({ sent: false, error: "rate_limited" }) : Response.json({ sent: true }),
    );
    const e = claimEnv({ MAILER: mailer });
    await logsOf(() => kofiPay(e, "3.00", "tx-a"));
    const { ctx } = await logsOf(() => start(e)).then((r) => r.result);
    assert.equal(await ctx.waiting[0], false);
    assert.deepEqual(claimMails(e).map((m) => m.kind), ["below_entry"]);
});

test("below_entry emails count against the hourly no-donation cap like no_donation ones", async () => {
    const e = claimEnv();
    const realNow = clock.now;
    try {
        clock.now = () => Date.UTC(2026, 10, 3, 10, 5, 0);
        await logsOf(() => kofiPay(e, "3.00", "tx-cap"));
        for (let i = 0; i < LIMITS.noDonationPerHour; i++) await logsOf(() => start(e, `nobody${i}@example.org`, `198.51.100.${i}`));
        await logsOf(() => start(e, EMAIL, "192.0.2.60"));
        assert.equal(claimMails(e).length, LIMITS.noDonationPerHour, "the cap held");
        assert.ok(claimMails(e).every((m) => m.kind === "no_donation"));
    } finally {
        clock.now = realNow;
    }
});

test("start logs no amount, address or code for a below_entry email", async () => {
    const e = claimEnv();
    await logsOf(() => kofiPay(e, "3.21", "tx-log"));
    const { logs } = await logsOf(() => start(e));
    assert.match(logs, /below_entry email sent/);
    assert.ok(!/321|3\.21|ann@|example\.org/.test(logs), logs);
});
