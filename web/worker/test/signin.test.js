import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../index.js";
import { hashEmail } from "../crypto.js";
import { forgetAllAccess } from "../session.js";
import { env, fakeCtx, fakeEmail, fakeKV, freezeClock, get, post } from "./fakes.js";

const EMAIL = "supporter@example.com";
const T0 = Date.UTC(2026, 9, 4, 12, 0, 0);

beforeEach(() => forgetAllAccess());

async function listedEnv(over = {}) {
    const h = await hashEmail(EMAIL);
    return { h, e: env({ STASH_KV: fakeKV({ [`access:${h}`]: { source: "kofi" } }), ...over }) };
}

/** POST /access, then let the after-response work (code + email) finish. */
async function askForCode(e, email = EMAIL, opts) {
    const ctx = fakeCtx();
    const res = await handle(post("/access", { email }, opts), e, ctx);
    await ctx.drain();
    return res;
}

const codeFrom = (mail) => /is (\d{6})/.exec(mail.text)[1];

test("the whole flow: email, code by email, code back, cookie, the real home page", async () => {
    const { h, e } = await listedEnv();
    const res = await askForCode(e, "  Supporter@Example.com ");
    assert.equal(res.status, 200);
    const page = await res.text();
    assert.match(page, /id="sent"/);
    assert.match(page, /<strong data-fill="email">supporter@example\.com<\/strong>/);
    assert.match(page, /name="email" value="supporter@example\.com"/);

    assert.equal(e.EMAIL.sent.length, 1);
    const mail = e.EMAIL.sent[0];
    assert.equal(mail.to, EMAIL);
    assert.deepEqual(mail.from, { email: "access@stashfm.app", name: "Stash" });
    assert.match(mail.subject, /^Your Stash sign-in code: \d{6}$/);
    const code = codeFrom(mail);

    // Only an HMAC of the code is stored, never the code.
    const stored = e.STASH_KV.json(`code:${h}`);
    assert.equal(stored.n, 0);
    assert.ok(!JSON.stringify(stored).includes(code));
    assert.equal(e.STASH_KV.map.get(`code:${h}`).opts.expirationTtl, 600);

    const verified = await handle(post("/access/verify", { email: EMAIL, code: `${code.slice(0, 3)} ${code.slice(3)}` }), e);
    assert.equal(verified.status, 303);
    assert.equal(verified.headers.get("location"), "/");
    const cookie = verified.headers.get("set-cookie");
    assert.match(cookie, /^stash_access=[\w-]+\.[\w-]+; HttpOnly; Secure; SameSite=Lax; Path=\/; Max-Age=7776000$/);
    assert.equal(e.STASH_KV.json(`code:${h}`), null, "a used code is gone");

    const home = await handle(get("/", { Cookie: cookie.split(";")[0] }), e);
    assert.equal(home.status, 200);
    assert.match(await home.text(), /id="home"/);
    assert.equal(home.headers.get("cache-control"), "no-store");

    // The same code can't be used twice.
    const again = await handle(post("/access/verify", { email: EMAIL, code }), e);
    assert.equal(again.status, 400);
});

test("the 'code sent' page is the same whether or not the email is on the list, and only listed emails get mail", async () => {
    const { e } = await listedEnv();
    const listed = await askForCode(e, EMAIL);
    const other = await askForCode(e, "stranger@example.com");
    assert.equal(listed.status, other.status);
    const strip = (html) => html.replaceAll("supporter@example.com", "X").replaceAll("stranger@example.com", "X");
    assert.equal(strip(await listed.text()), strip(await other.text()));
    assert.equal(e.EMAIL.sent.length, 1);
    assert.equal(e.EMAIL.sent[0].to, EMAIL);
    assert.deepEqual([...e.STASH_KV.map.keys()].filter((k) => k.startsWith("code:")).length, 1);
});

test("the code and email work happen after the response (no timing difference to measure)", async () => {
    const { e } = await listedEnv();
    const ctx = fakeCtx();
    await handle(post("/access", { email: EMAIL }), e, ctx);
    assert.equal(e.EMAIL.sent.length, 0, "sent before the response");
    assert.equal(ctx.work.length, 1);
    await ctx.drain();
    assert.equal(e.EMAIL.sent.length, 1);
});

test("one failure page for every kind of bad code, listed or not", async () => {
    const { e } = await listedEnv();
    await askForCode(e, EMAIL);
    const code = codeFrom(e.EMAIL.sent[0]);
    const wrong = code === "000000" ? "111111" : "000000";
    const cases = [
        { email: EMAIL, code: wrong }, // wrong code, listed
        { email: "stranger@example.com", code: wrong }, // never had a code
        { email: EMAIL, code: "12345" }, // not six digits
        { email: "not-an-email", code },
    ];
    const pages = [];
    for (const fields of cases) {
        const res = await handle(post("/access/verify", fields), e);
        assert.equal(res.status, 400);
        assert.equal(res.headers.get("set-cookie"), null);
        pages.push((await res.text()).replace(/value="[^"]*"/g, 'value=""'));
    }
    assert.ok(pages.every((p) => p === pages[0] && /id="code-failed"/.test(p)));
});

test("five wrong tries use the code up: the right one fails after that", async () => {
    const { h, e } = await listedEnv();
    await askForCode(e, EMAIL);
    const code = codeFrom(e.EMAIL.sent[0]);
    const wrong = code === "000000" ? "111111" : "000000";
    for (let i = 1; i <= 4; i++) {
        await handle(post("/access/verify", { email: EMAIL, code: wrong }), e);
        assert.equal(e.STASH_KV.json(`code:${h}`).n, i);
    }
    await handle(post("/access/verify", { email: EMAIL, code: wrong }), e);
    assert.equal(e.STASH_KV.json(`code:${h}`), null);
    assert.equal((await handle(post("/access/verify", { email: EMAIL, code }), e)).status, 400);
});

test("a code expires after 10 minutes", async () => {
    const clock = freezeClock(T0);
    try {
        const { e } = await listedEnv();
        await askForCode(e, EMAIL);
        const code = codeFrom(e.EMAIL.sent[0]);
        clock.advance(10 * 60 * 1000 + 1000);
        assert.equal((await handle(post("/access/verify", { email: EMAIL, code }), e)).status, 400);
    } finally {
        clock.restore();
    }
});

test("a code still works at 9 minutes, after a wrong try, and the try keeps its original expiry", async () => {
    const clock = freezeClock(T0);
    try {
        const { h, e } = await listedEnv();
        await askForCode(e, EMAIL);
        const code = codeFrom(e.EMAIL.sent[0]);
        clock.advance(9 * 60 * 1000 + 30_000);
        await handle(post("/access/verify", { email: EMAIL, code: code === "000000" ? "111111" : "000000" }), e);
        assert.equal(e.STASH_KV.map.get(`code:${h}`).opts.expirationTtl, 60, "under a minute left: KV's shortest expiry");
        assert.equal((await handle(post("/access/verify", { email: EMAIL, code }), e)).status, 303);
    } finally {
        clock.restore();
    }
});

test("asking again replaces the code: only the newest one works", async () => {
    const { e } = await listedEnv();
    await askForCode(e, EMAIL);
    await askForCode(e, EMAIL);
    const [first, second] = e.EMAIL.sent.map(codeFrom);
    if (first !== second) assert.equal((await handle(post("/access/verify", { email: EMAIL, code: first }), e)).status, 400);
    assert.equal((await handle(post("/access/verify", { email: EMAIL, code: second }), e)).status, 303);
});

test("at most 5 codes an hour per email: the 6th gets the same page and no email", async () => {
    const clock = freezeClock(T0);
    try {
        const { h, e } = await listedEnv();
        for (let i = 0; i < 6; i++) {
            const res = await askForCode(e, EMAIL);
            assert.equal(res.status, 200);
            assert.match(await res.text(), /id="sent"/);
        }
        assert.equal(e.EMAIL.sent.length, 5);
        assert.equal(e.STASH_KV.json(`sends:${h}`).n, 5);
        clock.advance(3600 * 1000 + 1000);
        await askForCode(e, EMAIL);
        assert.equal(e.EMAIL.sent.length, 6, "a new hour, a new allowance");
    } finally {
        clock.restore();
    }
});

test("someone removed while their code is on the way can't sign in with it", async () => {
    const { h, e } = await listedEnv();
    await askForCode(e, EMAIL);
    const code = codeFrom(e.EMAIL.sent[0]);
    await e.STASH_KV.delete(`access:${h}`);
    assert.equal((await handle(post("/access/verify", { email: EMAIL, code }), e)).status, 400);
});

test("if the email doesn't send, the visitor still sees the usual page (and it's logged)", async () => {
    const { e } = await listedEnv({ EMAIL: fakeEmail({ failWith: "E_RATE_LIMIT_EXCEEDED" }) });
    const res = await askForCode(e, EMAIL);
    assert.equal(res.status, 200);
    assert.match(await res.text(), /id="sent"/);
});

test("forms must come from this site: no Origin, or another one, is refused; Sec-Fetch-Site same-origin is fine", async () => {
    const { e } = await listedEnv();
    for (const opts of [{ origin: null }, { origin: "https://evil.example" }, { origin: null, headers: { "Sec-Fetch-Site": "cross-site" } }]) {
        for (const path of ["/access", "/access/verify", "/request"]) {
            const res = await handle(post(path, { email: EMAIL, code: "123456" }, opts), e, fakeCtx());
            assert.equal(res.status, 403, `${path} ${JSON.stringify(opts)}`);
            assert.match(await res.text(), /id="problem"/);
        }
    }
    assert.equal(e.EMAIL.sent.length, 0);
    const ok = await askForCode(e, EMAIL, { origin: null, headers: { "Sec-Fetch-Site": "same-origin" } });
    assert.equal(ok.status, 200);
});

test("a bad email gets a friendly 400; a missing SESSION_SECRET turns sign-in off with a 503", async () => {
    const { e } = await listedEnv();
    const bad = await askForCode(e, "nope");
    assert.equal(bad.status, 400);
    assert.match(await bad.text(), /doesn&#39;t look like an email|doesn't look like an email/);
    const off = await askForCode(env({ SESSION_SECRET: "" }), EMAIL);
    assert.equal(off.status, 503);
});

test("a body that isn't a small form is refused", async () => {
    const { e } = await listedEnv();
    const big = await handle(post("/access", { email: EMAIL, pad: "x".repeat(5000) }), e);
    assert.equal(big.status, 400);
    const json = new Request("https://stashfm.app/access", { method: "POST", headers: { Origin: "https://stashfm.app", "content-type": "application/json" }, body: "{}" });
    assert.equal((await handle(json, e)).status, 400);
});

test("signed in, /access sends you home; GET on the POST-only routes goes home too", async () => {
    const { e } = await listedEnv();
    await askForCode(e, EMAIL);
    const ok = await handle(post("/access/verify", { email: EMAIL, code: codeFrom(e.EMAIL.sent[0]) }), e);
    const cookie = ok.headers.get("set-cookie").split(";")[0];
    const access = await handle(get("/access", { Cookie: cookie }), e);
    assert.equal(access.status, 303);
    assert.equal(access.headers.get("location"), "/");
    for (const path of ["/access/verify", "/signout"]) assert.equal((await handle(get(path), e)).status, 303);
});
