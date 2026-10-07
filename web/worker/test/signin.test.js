import { beforeEach, test } from "node:test";
import assert from "node:assert/strict";
import worker, { handle } from "../index.js";
import { hashFor as hashEmail } from "./fakes.js";
import { forgetAllAccess } from "../session.js";
import { assertSignedOutHome, env, fakeCtx, fakeEmail, fakeKV, fakeRL, freezeClock, get, post } from "./fakes.js";

const EMAIL = "supporter@example.com";
const T0 = Date.UTC(2026, 9, 4, 12, 0, 0);

beforeEach(() => forgetAllAccess());

async function listedEnv(over = {}) {
    const h = await hashEmail(EMAIL);
    return { h, e: env({ ACCESS_KV: fakeKV({ [`access:${h}`]: { source: "kofi" } }), ...over }) };
}

/** Runs a request and then the work it left for after the response (ctx.waitUntil). */
async function run(e, req) {
    const ctx = fakeCtx();
    const res = await handle(req, e, ctx);
    await ctx.drain();
    return res;
}

const askForCode = (e, email = EMAIL, opts) => run(e, post("/access", { email }, opts));
const tryCode = (e, code, email = EMAIL) => run(e, post("/access/verify", { email, code }));
const codeFrom = (mail) => /is (\d{6})/.exec(mail.text)[1];
const wrongFor = (...codes) => ["000000", "111111", "222222", "333333", "444444"].find((c) => !codes.includes(c));

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
    const code = codeFrom(mail);

    // Only an HMAC of the code is stored, never the code.
    const stored = e.ACCESS_KV.json(`code:${h}`);
    assert.equal(stored.n, 0);
    assert.equal(stored.codes.length, 1);
    assert.ok(!JSON.stringify(stored).includes(code));

    const verified = await tryCode(e, `${code.slice(0, 3)} ${code.slice(3)}`);
    assert.equal(verified.status, 303);
    assert.equal(verified.headers.get("location"), "/");
    const cookie = verified.headers.get("set-cookie");
    assert.match(cookie, /^stash_access=[\w-]+\.[\w-]+; HttpOnly; Secure; SameSite=Lax; Path=\/; Max-Age=7776000$/);
    assert.equal(e.ACCESS_KV.json(`code:${h}`), null, "a used code is gone");

    const home = await handle(get("/", { Cookie: cookie.split(";")[0] }), e);
    assert.equal(home.status, 200);
    assert.match(await home.text(), /id="home"/);
    assert.equal(home.headers.get("cache-control"), "no-store");
    assert.equal((await tryCode(e, code)).status, 400, "the same code can't be used twice");
});

test("the code is in the email's body only, never its subject (lock screens, inbox previews)", async () => {
    const { e } = await listedEnv();
    await askForCode(e);
    const mail = e.EMAIL.sent[0];
    const code = codeFrom(mail);
    assert.equal(mail.subject, "Your Stash sign-in code");
    assert.ok(mail.text.includes(code) && mail.html.includes(code));
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
    assert.equal([...e.ACCESS_KV.map.keys()].filter((k) => k.startsWith("code:")).length, 1);
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

test("a wrong code is answered without waiting on KV: the try is counted after the response", async () => {
    const { h, e } = await listedEnv();
    await askForCode(e);
    const code = codeFrom(e.EMAIL.sent[0]);
    // Any write to the code record now hangs until released. A response that waited on it would never come.
    const release = e.ACCESS_KV.holdPuts((k) => k.startsWith("code:"));
    const ctx = fakeCtx();
    const res = await handle(post("/access/verify", { email: EMAIL, code: wrongFor(code) }), e, ctx);
    assert.equal(res.status, 400);
    assert.equal(e.ACCESS_KV.json(`code:${h}`).n, 0, "not counted yet");
    release();
    await ctx.drain();
    assert.equal(e.ACCESS_KV.json(`code:${h}`).n, 1);
});

test("one failure page for every kind of bad code, listed or not", async () => {
    const { e } = await listedEnv();
    await askForCode(e, EMAIL);
    const code = codeFrom(e.EMAIL.sent[0]);
    const cases = [
        { email: EMAIL, code: wrongFor(code) }, // wrong code, listed
        { email: "stranger@example.com", code: wrongFor(code) }, // never had a code
        { email: EMAIL, code: "12345" }, // not six digits
        { email: "not-an-email", code },
    ];
    const pages = [];
    for (const fields of cases) {
        const res = await run(e, post("/access/verify", fields));
        assert.equal(res.status, 400);
        assert.equal(res.headers.get("set-cookie"), null);
        pages.push((await res.text()).replace(/value="[^"]*"/g, 'value=""'));
    }
    assert.ok(pages.every((p) => p === pages[0] && /id="code-failed"/.test(p)));
});

test("five wrong tries use the email's codes up: the right one fails after that", async () => {
    const { h, e } = await listedEnv();
    await askForCode(e);
    const code = codeFrom(e.EMAIL.sent[0]);
    for (let i = 1; i <= 4; i++) {
        await tryCode(e, wrongFor(code));
        assert.equal(e.ACCESS_KV.json(`code:${h}`).n, i);
    }
    await tryCode(e, wrongFor(code));
    assert.equal(e.ACCESS_KV.json(`code:${h}`), null);
    assert.equal((await tryCode(e, code)).status, 400);
});

test("a code expires after 10 minutes", async () => {
    const clock = freezeClock(T0);
    try {
        const { e } = await listedEnv();
        await askForCode(e);
        const code = codeFrom(e.EMAIL.sent[0]);
        clock.advance(10 * 60 * 1000 + 1000);
        assert.equal((await tryCode(e, code)).status, 400);
    } finally {
        clock.restore();
    }
});

test("a code still works at 9 minutes, after a wrong try, and the try keeps its original expiry", async () => {
    const clock = freezeClock(T0);
    try {
        const { h, e } = await listedEnv();
        await askForCode(e);
        const code = codeFrom(e.EMAIL.sent[0]);
        clock.advance(9 * 60 * 1000 + 30_000);
        await tryCode(e, wrongFor(code));
        assert.equal(e.ACCESS_KV.map.get(`code:${h}`).opts.expirationTtl, 60, "under a minute left: KV's shortest expiry");
        assert.equal((await tryCode(e, code)).status, 303);
    } finally {
        clock.restore();
    }
});

test("asking again doesn't cancel a code still on its way: the last 3 all work, and signing in clears them all", async () => {
    const clock = freezeClock(T0);
    try {
        const { h, e } = await listedEnv();
        for (let i = 0; i < 4; i++) {
            await askForCode(e);
            clock.advance(61_000); // past the one-a-minute send limit
        }
        const [first, second, third, fourth] = e.EMAIL.sent.map(codeFrom);
        assert.equal(e.ACCESS_KV.json(`code:${h}`).codes.length, 3);
        if (![second, third, fourth].includes(first)) assert.equal((await tryCode(e, first)).status, 400, "the oldest of 4 is dropped");
        assert.equal((await tryCode(e, third)).status, 303);
        assert.equal(e.ACCESS_KV.json(`code:${h}`), null);
        if (third !== fourth) assert.equal((await tryCode(e, fourth)).status, 400, "all cleared on success");
    } finally {
        clock.restore();
    }
});

test("one code email a minute per address: a second ask that minute sends nothing but looks the same", async () => {
    const { e } = await listedEnv({ CODE_SEND_RL: fakeRL(1) });
    const a = await askForCode(e);
    const b = await askForCode(e);
    assert.equal(e.EMAIL.sent.length, 1);
    assert.equal(await a.text(), await b.text());
});

test("at most 5 codes an hour per email: the 6th gets the same page and no email", async () => {
    const clock = freezeClock(T0);
    try {
        const { h, e } = await listedEnv();
        for (let i = 0; i < 6; i++) {
            const res = await askForCode(e, EMAIL);
            assert.equal(res.status, 200);
            assert.match(await res.text(), /id="sent"/);
            clock.advance(61_000);
        }
        assert.equal(e.EMAIL.sent.length, 5);
        assert.equal(e.ACCESS_KV.json(`sends:${h}`).n, 5);
        clock.advance(3600 * 1000);
        await askForCode(e, EMAIL);
        assert.equal(e.EMAIL.sent.length, 6, "a new hour, a new allowance");
    } finally {
        clock.restore();
    }
});

test("someone removed while their code is on the way can't sign in with it", async () => {
    const { h, e } = await listedEnv();
    await askForCode(e);
    const code = codeFrom(e.EMAIL.sent[0]);
    await e.ACCESS_KV.delete(`access:${h}`);
    assert.equal((await tryCode(e, code)).status, 400);
});

test("if the email doesn't send, the visitor still sees the usual page (and it's logged)", async () => {
    const { e } = await listedEnv({ EMAIL: fakeEmail({ failWith: "E_RATE_LIMIT_EXCEEDED" }) });
    const res = await askForCode(e, EMAIL);
    assert.equal(res.status, 200);
    assert.match(await res.text(), /id="sent"/);
});

test("an address with an apostrophe works", async () => {
    const h = await hashEmail("o'brien@example.com");
    const e = env({ ACCESS_KV: fakeKV({ [`access:${h}`]: { source: "manual" } }) });
    const res = await askForCode(e, "O'Brien@example.com");
    assert.equal(res.status, 200);
    assert.equal(e.EMAIL.sent[0].to, "o'brien@example.com");
    assert.equal((await tryCode(e, codeFrom(e.EMAIL.sent[0]), "o'brien@example.com")).status, 303);
});

test("forms must come from this site: no Origin, or another one, is refused; Sec-Fetch-Site same-origin is fine", async () => {
    const { e } = await listedEnv();
    for (const opts of [{ origin: null }, { origin: "https://evil.example" }, { origin: null, headers: { "Sec-Fetch-Site": "cross-site" } }]) {
        for (const path of ["/access", "/access/verify", "/request"]) {
            const res = await run(e, post(path, { email: EMAIL, code: "123456" }, opts));
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
    assert.match(await bad.text(), /doesn.t look like an email/);
    const off = await askForCode(env({ SESSION_SECRET: "" }), EMAIL);
    assert.equal(off.status, 503);
});

test("without the gate's bindings or EMAIL_PEPPER (a Preview has none), sign-in and requests say they aren't working; the page still loads", async () => {
    for (const missing of ["ACCESS_KV", "EMAIL", "SEND_IP_RL", "VERIFY_EMAIL_RL", "CODE_SEND_RL", "EMAIL_PEPPER"]) {
        const e = env({ [missing]: undefined });
        for (const [path, fields] of [["/access", { email: EMAIL }], ["/access/verify", { email: EMAIL, code: "123456" }], ["/request", { email: EMAIL }]]) {
            const res = await run(e, post(path, fields));
            assert.equal(res.status, 503, `${missing} ${path}`);
            assert.match(await res.text(), /isn.t working right now/);
        }
    }
    const preview = env({ ACCESS_KV: undefined, EMAIL: undefined, SESSION_SECRET: undefined });
    const front = await handle(get("/"), preview);
    assert.equal(front.status, 200);
    assertSignedOutHome(await front.text());
    const res = await worker.fetch(get("/admin"), preview, fakeCtx());
    assert.equal(res.status, 403);
});

test("a body that isn't a small form is refused", async () => {
    const { e } = await listedEnv();
    const big = await run(e, post("/access", { email: EMAIL, pad: "x".repeat(5000) }));
    assert.equal(big.status, 400);
    const json = new Request("https://stashfm.app/access", { method: "POST", headers: { Origin: "https://stashfm.app", "content-type": "application/json" }, body: "{}" });
    assert.equal((await run(e, json)).status, 400);
});

test("signed in, /access sends you home; GET on the POST-only routes goes home too", async () => {
    const { e } = await listedEnv();
    await askForCode(e);
    const ok = await tryCode(e, codeFrom(e.EMAIL.sent[0]));
    const cookie = ok.headers.get("set-cookie").split(";")[0];
    const access = await handle(get("/access", { Cookie: cookie }), e);
    assert.equal(access.status, 303);
    assert.equal(access.headers.get("location"), "/");
    for (const path of ["/access/verify", "/signout"]) assert.equal((await handle(get(path), e)).status, 303);
});
