import { before, beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../index.js";
import { forgetAccessKeys, verifyAccess } from "../access-jwt.js";
import { b64urlEncode } from "../crypto.js";
import { hashFor as hashEmail } from "./fakes.js";
import { forgetGoalCache } from "../goal.js";
import { forgetAllAccess, hasAccess, signSession } from "../session.js";
import { assertSignedOutHome, BASE, env, fakeEmail, fakeKV, get, post, SECRET } from "./fakes.js";

const TEAM = "https://stash-test.cloudflareaccess.com";
const AUD = "aud-0123456789abcdef0123456789abcdef";
const ALG = { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" };

let good; // { privateKey, publicJwk, kid }
let other;
let certFetches = 0;

async function keypair(kid) {
    const { privateKey, publicKey } = await crypto.subtle.generateKey(ALG, true, ["sign", "verify"]);
    const jwk = await crypto.subtle.exportKey("jwk", publicKey);
    return { privateKey, kid, publicJwk: { kid, kty: "RSA", alg: "RS256", use: "sig", e: jwk.e, n: jwk.n } };
}

/** Cloudflare Access's certs endpoint, with the current and the previous key, like the docs show. */
const fetchCerts = async (url) => {
    certFetches++;
    assert.equal(url, `${TEAM}/cdn-cgi/access/certs`);
    return new Response(JSON.stringify({ keys: [good.publicJwk, { ...good.publicJwk, kid: "previous-key" }] }), { headers: { "content-type": "application/json" } });
};

async function jwt(claims = {}, { key = good, header = {} } = {}) {
    const now = Math.floor(Date.now() / 1000);
    const head = b64urlEncode(JSON.stringify({ alg: "RS256", kid: key.kid, typ: "JWT", ...header }));
    const body = b64urlEncode(JSON.stringify({ aud: [AUD], email: "owner@example.com", exp: now + 3600, iat: now, nbf: now, iss: TEAM, type: "app", sub: "u1", ...claims }));
    const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key.privateKey, new TextEncoder().encode(`${head}.${body}`)));
    return `${head}.${body}.${b64urlEncode(sig)}`;
}

const adminGet = async (token, e = env(), path = "/admin") => handle(get(path, token ? { "Cf-Access-Jwt-Assertion": token } : {}), e, undefined, { fetchImpl: fetchCerts });
const adminPost = async (fields, e, { token, origin } = {}) =>
    handle(post("/admin", fields, { origin, headers: { "Cf-Access-Jwt-Assertion": token ?? (await jwt()) } }), e, undefined, { fetchImpl: fetchCerts });

before(async () => {
    good = await keypair("current-key");
    other = await keypair("current-key"); // same kid, different key: a forgery
});

beforeEach(() => {
    forgetAccessKeys();
    forgetAllAccess();
    forgetGoalCache();
    certFetches = 0;
});

test("a valid Access token for a listed admin opens /admin", async () => {
    const res = await adminGet(await jwt());
    assert.equal(res.status, 200);
    const page = await res.text();
    assert.match(page, /id="admin"/);
    assert.match(page, /<strong data-fill="admin-email">owner@example\.com<\/strong>/);
    assert.equal(res.headers.get("cache-control"), "no-store");
});

test("ADMIN_EMAILS is matched without regard to case or spaces", async () => {
    assert.equal((await adminGet(await jwt({ email: "EVO@example.com" }))).status, 200);
});

test("refused: no token, a forged signature, wrong aud, wrong iss, expired, not yet valid, not an admin, no email", async () => {
    const now = Math.floor(Date.now() / 1000);
    const cases = {
        "no token": null,
        "forged signature": await jwt({}, { key: other }),
        "wrong aud": await jwt({ aud: ["some-other-app-aud-0000000000000000"] }),
        "aud as a plain wrong string": await jwt({ aud: "nope" }),
        "wrong iss": await jwt({ iss: "https://evil.cloudflareaccess.com" }),
        expired: await jwt({ exp: now - 120 }),
        "not yet valid": await jwt({ nbf: now + 600 }),
        "not an admin": await jwt({ email: "someone@example.com" }),
        "no email (a service token)": await jwt({ email: undefined }),
        "unknown kid": await jwt({}, { header: { kid: "nope" } }),
        "alg none": (await jwt({}, { header: { alg: "none" } })).split(".").slice(0, 2).join(".") + ".",
        "alg HS256": await jwt({}, { header: { alg: "HS256" } }),
        garbage: "not.a.jwt",
    };
    for (const [name, token] of Object.entries(cases)) {
        const res = await adminGet(token);
        assert.equal(res.status, 403, name);
        const page = await res.text();
        assert.match(page, /id="problem"/, name);
        assert.doesNotMatch(page, /id="admin"/, name);
    }
});

test("the plain email header Access also sends is never enough", async () => {
    const res = await handle(get("/admin", { "Cf-Access-Authenticated-User-Email": "owner@example.com" }), env(), undefined, { fetchImpl: fetchCerts });
    assert.equal(res.status, 403);
});

test("placeholder or missing settings refuse everyone, even with a valid token", async () => {
    const token = await jwt();
    for (const over of [
        { ACCESS_TEAM_DOMAIN: "https://TODO-team-name.cloudflareaccess.com" },
        { ACCESS_AUD: "TODO-application-audience-tag" },
        { ACCESS_AUD: "" },
        { ADMIN_EMAILS: "" },
        { ACCESS_TEAM_DOMAIN: "https://stash-test.example.com" },
    ]) {
        assert.equal((await adminGet(token, env(over))).status, 403, JSON.stringify(over));
    }
});

test("Access's keys are fetched once and cached, and fetched again for a new kid only after a minute", async () => {
    const e = env();
    await adminGet(await jwt(), e);
    await adminGet(await jwt(), e);
    assert.equal(certFetches, 1);
    await adminGet(await jwt({}, { header: { kid: "brand-new" } }), e);
    assert.equal(certFetches, 1, "no refetch storm from made-up kids");
    assert.ok(await verifyAccess(new Request(BASE, { headers: { "Cf-Access-Jwt-Assertion": await jwt() } }), e, fetchCerts));
});

test("a failing certs endpoint means no admin, not a crash", async () => {
    const res = await handle(get("/admin", { "Cf-Access-Jwt-Assertion": await jwt() }), env(), undefined, { fetchImpl: async () => new Response("down", { status: 502 }) });
    assert.equal(res.status, 403);
});

/** A stored access request, as POST /request writes it (the time in metadata too). */
const request = (email, note, at) => ({ value: JSON.stringify({ email, note, at }), metadata: { at } });

test("the page lists pending requests, newest first, escaped", async () => {
    const h1 = await hashEmail("old@example.com");
    const h2 = await hashEmail("new@example.com");
    const e = env({
        ACCESS_KV: fakeKV({
            [`request:${h1}`]: request("old@example.com", "", "2026-10-01T00:00:00Z"),
            [`request:${h2}`]: request("new@example.com", "<b>I donated</b> on PayPal", "2026-10-03T00:00:00Z"),
        }),
    });
    const page = await (await adminGet(await jwt(), e)).text();
    assert.match(page, /2 waiting/);
    assert.ok(page.indexOf("new@example.com") < page.indexOf("old@example.com"));
    assert.match(page, /&lt;b&gt;I donated&lt;\/b&gt; on PayPal/);
    assert.doesNotMatch(page, /<b>I donated/);
    assert.match(page, new RegExp(`name="h" value="${h2}"`));
    assert.doesNotMatch(page, /NOBODY WAITING/);
});

test("with many requests: the real total, newest first across pages of 25, and links between pages", async () => {
    const seed = {};
    for (let i = 0; i < 1030; i++) {
        const day = String(1 + (i % 28)).padStart(2, "0");
        const at = `2026-09-${day}T${String(i % 24).padStart(2, "0")}:00:${String(i % 60).padStart(2, "0")}.${String(i).padStart(3, "0")}Z`;
        seed[`request:${await hashEmail(`r${i}@example.com`)}`] = request(`r${i}@example.com`, "", at);
    }
    const e = env({ ACCESS_KV: fakeKV(seed) });
    const first = await (await adminGet(await jwt(), e)).text();
    assert.match(first, /1030 waiting/);
    assert.equal((first.match(/value="approve"/g) || []).length, 25);
    assert.match(first, /href="\/admin\?page=2"/);
    const ats = Object.values(seed).map((r) => r.metadata.at).sort().reverse();
    const newest = Object.values(seed).find((r) => r.metadata.at === ats[0]);
    assert.ok(first.includes(JSON.parse(newest.value).email), "the newest request is on page 1");
    const last = await (await adminGet(await jwt(), e, "/admin?page=42")).text();
    assert.equal((last.match(/value="approve"/g) || []).length, 5);
    const oldest = Object.values(seed).find((r) => r.metadata.at === ats.at(-1));
    assert.ok(last.includes(JSON.parse(oldest.value).email), "the oldest request is on the last page");
    assert.match(last, /href="\/admin\?page=41"/);
    assert.doesNotMatch(last, /page=43/);
});

test("approve: on the list as approved (by whom, when), request gone, 'You're in' emailed, back to /admin", async () => {
    const h = await hashEmail("new@example.com");
    const e = env({ ACCESS_KV: fakeKV({ [`request:${h}`]: request("new@example.com", "", "2026-10-03T00:00:00Z") }) });
    const res = await adminPost({ action: "approve", h }, e);
    assert.equal(res.status, 303);
    assert.equal(res.headers.get("location"), "/admin?done=approved");
    const access = e.ACCESS_KV.json(`access:${h}`);
    assert.equal(access.source, "approved");
    assert.equal(access.by, "owner@example.com");
    assert.ok(access.at);
    assert.equal(e.ACCESS_KV.json(`request:${h}`), null);
    assert.equal(e.EMAIL.sent.length, 1);
    assert.equal(e.EMAIL.sent[0].to, "new@example.com");
    assert.equal(e.EMAIL.sent[0].subject, "You're in: Stash early access");
    assert.match(e.EMAIL.sent[0].text, /Sign in at https:\/\/stashfm\.app\//);
    const flash = await (await adminGet(await jwt(), e, "/admin?done=approved")).text();
    assert.match(flash, /Approved\. They&#39;ve been emailed\.|Approved\. They've been emailed\./);
});

test("approve when the email fails still approves, and says which error", async () => {
    const h = await hashEmail("new@example.com");
    const e = env({ EMAIL: fakeEmail({ failWith: "E_RECIPIENT_SUPPRESSED" }), ACCESS_KV: fakeKV({ [`request:${h}`]: { email: "new@example.com", at: "x" } }) });
    const res = await adminPost({ action: "approve", h }, e);
    assert.equal(res.headers.get("location"), "/admin?done=approved-noemail&code=E_RECIPIENT_SUPPRESSED");
    assert.ok(e.ACCESS_KV.json(`access:${h}`));
    const page = await (await adminGet(await jwt(), e, "/admin?done=approved-noemail&code=E_RECIPIENT_SUPPRESSED")).text();
    assert.match(page, /the email didn.t send \(E_RECIPIENT_SUPPRESSED\)/);
    const junk = await (await adminGet(await jwt(), e, "/admin?done=approved-noemail&code=%3Cscript%3E")).text();
    assert.doesNotMatch(junk, /script/i);
});

test("deny removes the request and sends nothing; a handled request says so", async () => {
    const h = await hashEmail("new@example.com");
    const e = env({ ACCESS_KV: fakeKV({ [`request:${h}`]: { email: "new@example.com", at: "x" } }) });
    assert.equal((await adminPost({ action: "deny", h }, e)).headers.get("location"), "/admin?done=denied");
    assert.equal(e.ACCESS_KV.json(`request:${h}`), null);
    assert.equal(e.ACCESS_KV.json(`access:${h}`), null);
    assert.equal(e.EMAIL.sent.length, 0);
    assert.equal((await adminPost({ action: "approve", h }, e)).headers.get("location"), "/admin?done=gone");
});

test("add by email (source manual), with or without the email; remove ends access at once in this instance", async () => {
    const e = env();
    const h = await hashEmail("sponsor@example.com");
    assert.equal((await adminPost({ action: "add", email: " Sponsor@Example.com " }, e)).headers.get("location"), "/admin?done=added");
    assert.deepEqual(Object.keys(e.ACCESS_KV.json(`access:${h}`)).sort(), ["at", "by", "source"]);
    assert.equal(e.ACCESS_KV.json(`access:${h}`).source, "manual");
    assert.equal(e.EMAIL.sent.length, 0);
    assert.equal((await adminPost({ action: "add", email: "sponsor@example.com", notify: "yes" }, e)).headers.get("location"), "/admin?done=added-emailed");
    assert.equal(e.EMAIL.sent.length, 1);

    // Signed in, then removed: the very next request is signed out (no cache wait in this instance).
    const cookie = `stash_access=${await signSession(SECRET, h)}`;
    assert.ok(await hasAccess(e, h));
    assert.equal((await adminPost({ action: "remove", email: "sponsor@example.com" }, e)).headers.get("location"), "/admin?done=removed");
    assert.equal(e.ACCESS_KV.json(`access:${h}`), null);
    assertSignedOutHome(await (await handle(get("/", { Cookie: cookie }), e)).text());
    assert.equal((await adminPost({ action: "remove", email: "sponsor@example.com" }, e)).headers.get("location"), "/admin?done=not-listed");
    assert.equal((await adminPost({ action: "add", email: "not an email" }, e)).headers.get("location"), "/admin?done=bad-email");
});

test("donations by hand: one key each in this month, shown, and removable; Ko-fi's entries aren't", async () => {
    const month = new Date().toISOString().slice(0, 7);
    const kofi = { cents: 500, source: "Ko-fi", at: "2026-10-01T00:00:00Z", orig: "4.63 EUR" };
    const e = env({ ACCESS_KV: fakeKV({ [`goal:${month}:kofi:tx1`]: { value: JSON.stringify(kofi), metadata: kofi } }) });
    assert.equal((await adminPost({ action: "donation", amount: "$12.5", source: "GitHub  Sponsors" }, e)).headers.get("location"), "/admin?done=donation");
    const manualKeys = [...e.ACCESS_KV.map.keys()].filter((k) => k.startsWith(`goal:${month}:manual:`));
    assert.equal(manualKeys.length, 1);
    const meta = e.ACCESS_KV.map.get(manualKeys[0]).opts.metadata;
    assert.equal(meta.cents, 1250);
    assert.equal(meta.source, "GitHub Sponsors");
    assert.equal(meta.by, "owner@example.com");
    const manual = { id: manualKeys[0].split(":").pop() };

    const page = await (await adminGet(await jwt(), e)).text();
    assert.match(page, /\$17\.50 of \$100\.00/);
    assert.match(page, /GitHub Sponsors/);
    assert.match(page, /4\.63 EUR/);
    assert.equal((page.match(/value="remove-entry"/g) || []).length, 1, "only the hand-added entry has Remove");

    assert.equal((await adminPost({ action: "remove-entry", id: manual.id }, e)).headers.get("location"), "/admin?done=entry-removed");
    assert.equal(e.ACCESS_KV.map.has(manualKeys[0]), false);
    assert.match(await (await adminGet(await jwt(), e)).text(), /\$5\.00 of \$100\.00/);
    assert.equal((await adminPost({ action: "remove-entry", id: "tx1" }, e)).headers.get("location"), "/admin?done=gone", "Ko-fi's can't be removed");
    assert.equal((await adminPost({ action: "remove-entry", id: "nope" }, e)).headers.get("location"), "/admin?done=gone");

    for (const amount of ["", "abc", "0", "-5", "1.234", "9999999"]) {
        assert.equal((await adminPost({ action: "donation", amount, source: "PayPal (Evo)" }, e)).headers.get("location"), "/admin?done=bad-amount", amount);
    }
    assert.equal((await adminPost({ action: "donation", amount: "5", source: "  " }, e)).headers.get("location"), "/admin?done=bad-source");
});

test("a donation added by hand shows on the front page within the minute", async () => {
    const e = env();
    await adminPost({ action: "donation", amount: "25", source: "PayPal (Evo)" }, e);
    assert.match(await (await handle(get("/"), e)).text(), /\$25<\/strong>/);
});

test("admin POSTs need the same Origin check, and an unknown action does nothing", async () => {
    const e = env();
    const res = await adminPost({ action: "add", email: "x@example.com" }, e, { origin: "https://evil.example" });
    assert.equal(res.headers.get("location"), "/admin?done=bad-form");
    assert.equal([...e.ACCESS_KV.map.keys()].length, 0);
    assert.equal((await adminPost({ action: "drop-tables" }, e)).headers.get("location"), "/admin?done=bad-form");
});

test("without EMAIL_PEPPER, the admin page won't add or remove emails (they'd be hashed wrong)", async () => {
    const e = env({ EMAIL_PEPPER: undefined });
    for (const action of ["add", "remove"]) {
        assert.equal((await adminPost({ action, email: "x@example.com" }, e)).headers.get("location"), "/admin?done=no-pepper");
    }
    assert.equal([...e.ACCESS_KV.map.keys()].length, 0);
});

test("a pepper mismatch between the tip jar and the site shows a warning", async () => {
    const plainCheck = await hashEmail("hashcheck@stashfm.app");
    const same = env({ ACCESS_KV: fakeKV({ "meta:hashcheck": plainCheck }) });
    assert.doesNotMatch(await (await adminGet(await jwt(), same)).text(), /HASH MISMATCH/);
    const differs = env({ EMAIL_PEPPER: "a-pepper", ACCESS_KV: fakeKV({ "meta:hashcheck": plainCheck }) });
    assert.match(await (await adminGet(await jwt(), differs)).text(), /HASH MISMATCH/);
});
