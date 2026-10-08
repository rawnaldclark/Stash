import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { hashEmail, KOFI_TEST_TXN } from "../src/access.js";
import { maskEmail, sendWelcome } from "../src/welcome.js";
import { donation, env, fakeKV, kofiPost } from "./fake-kv.js";

const TOKEN = "mailer-token-for-tests";

/** The MAILER service binding: records each call; [respond] answers it (default: sent). */
function fakeMailer(respond = (emails) => Response.json({ results: emails.map((email) => ({ email, sent: true })), sent: emails.length })) {
    return {
        calls: [],
        async fetch(url, init) {
            const emails = JSON.parse(init.body).emails;
            this.calls.push({ url, auth: init.headers.authorization, emails });
            return respond(emails);
        },
    };
}

/** ExecutionContext: keeps what waitUntil was given. */
const fakeCtx = () => ({ waiting: [], waitUntil(p) { this.waiting.push(p); } });

/** Captures console output so a test can check what's logged. */
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

const welcomeEnv = (over = {}) => env({ MAILER: fakeMailer(), MAILER_TOKEN: TOKEN, ...over });

test("a donor new to the list is welcomed once, through MAILER with the token, after the response (waitUntil)", async () => {
    const e = welcomeEnv();
    const ctx = fakeCtx();
    const res = await worker.fetch(kofiPost(donation()), e, ctx);
    assert.equal(res.status, 200);
    assert.equal(ctx.waiting.length, 1);
    assert.equal(await ctx.waiting[0], true);
    assert.deepEqual(e.MAILER.calls, [{ url: "https://stash-mailer/welcome", auth: `Bearer ${TOKEN}`, emails: ["ann@example.org"] }]);
});

test("the webhook answers without waiting for the mailer", async () => {
    const e = welcomeEnv({ MAILER: fakeMailer(() => new Promise(() => {})) }); // never answers
    const ctx = fakeCtx();
    const res = await worker.fetch(kofiPost(donation()), e, ctx);
    assert.equal(res.status, 200);
    assert.equal(e.MAILER.calls.length, 1);
    assert.equal(ctx.waiting.length, 1);
});

test("someone already on the list (any source) is not welcomed again", async () => {
    for (const prev of [{ source: "kofi", firstAt: "2026-01-01T00:00:00Z", lastAt: "2026-01-01T00:00:00Z" }, { source: "manual", by: "cli", at: "2026-01-01T00:00:00Z" }]) {
        const access = fakeKV({ [`access:${await hashEmail("ann@example.org")}`]: JSON.stringify(prev) });
        const e = welcomeEnv({ ACCESS_KV: access });
        const ctx = fakeCtx();
        assert.equal((await worker.fetch(kofiPost(donation()), e, ctx)).status, 200);
        assert.equal(e.MAILER.calls.length, 0, prev.source);
        assert.equal(ctx.waiting.length, 0);
    }
});

test("a second donation, and a Ko-fi retry, send nothing more", async () => {
    const e = welcomeEnv();
    await worker.fetch(kofiPost(donation()), e, fakeCtx());
    await worker.fetch(kofiPost(donation()), e, fakeCtx()); // retry, same transaction
    await worker.fetch(kofiPost(donation({ kofi_transaction_id: "tx-2", message_id: "m2" })), e, fakeCtx());
    assert.equal(e.MAILER.calls.length, 1);
});

test("if the goal write fails after access is written: the welcome still goes once, and the retry doesn't repeat it", async () => {
    const access = fakeKV();
    const put = access.put.bind(access);
    let failGoal = true;
    access.put = async (key, value, opts) => {
        if (failGoal && key.startsWith("goal:")) throw new Error("KV write failed");
        return put(key, value, opts);
    };
    const e = welcomeEnv({ ACCESS_KV: access });
    const { result: first } = await logsOf(() => worker.fetch(kofiPost(donation()), e, fakeCtx()));
    assert.equal(first.status, 500);
    assert.equal(e.MAILER.calls.length, 1);
    failGoal = false;
    assert.equal((await worker.fetch(kofiPost(donation()), e, fakeCtx())).status, 200);
    assert.equal(e.MAILER.calls.length, 1);
});

test("Ko-fi's test webhook, shop orders and donations without an email send nothing", async () => {
    const e = welcomeEnv();
    await worker.fetch(kofiPost(donation({ kofi_transaction_id: KOFI_TEST_TXN })), e, fakeCtx());
    await worker.fetch(kofiPost(donation({ type: "Shop Order", kofi_transaction_id: "tx-shop" })), e, fakeCtx());
    await worker.fetch(kofiPost(donation({ email: "", kofi_transaction_id: "tx-none" })), e, fakeCtx());
    assert.equal(e.MAILER.calls.length, 0);
});

test("a failing or refusing mailer never fails the webhook, and logs only a masked address", async () => {
    for (const mailer of [
        fakeMailer(() => { throw new Error("connection reset"); }),
        fakeMailer(() => Response.json({ error: "unauthorized" }, { status: 401 })),
        fakeMailer((emails) => Response.json({ results: emails.map((email) => ({ email, sent: false, error: "E_RECIPIENT_SUPPRESSED" })), sent: 0 })),
        fakeMailer(() => new Response("not json", { status: 502 })),
    ]) {
        const e = welcomeEnv({ MAILER: mailer });
        const ctx = fakeCtx();
        const { result: res, logs } = await logsOf(async () => {
            const r = await worker.fetch(kofiPost(donation({ email: "Secret.Person@Private-Domain.org" })), e, ctx);
            assert.equal(await ctx.waiting[0], false);
            return r;
        });
        assert.equal(res.status, 200);
        assert.match(logs, /welcome not sent to s\*\*\*@p\*\*\*\.org/);
        assert.ok(!/secret\.person|private-domain/i.test(logs), logs);
        assert.ok(!logs.includes(TOKEN));
    }
});

test("without MAILER or MAILER_TOKEN, nobody is emailed and the webhook works as before", async () => {
    for (const over of [{ MAILER: undefined }, { MAILER_TOKEN: undefined }, { MAILER_TOKEN: "" }]) {
        const e = welcomeEnv(over);
        const ctx = fakeCtx();
        const { result: res } = await logsOf(() => worker.fetch(kofiPost(donation()), e, ctx));
        assert.equal(res.status, 200);
        assert.equal(await ctx.waiting[0], false);
        assert.ok(e.ACCESS_KV.json(`access:${await hashEmail("ann@example.org")}`));
    }
    // No ctx at all (older callers): still fine.
    assert.equal((await worker.fetch(kofiPost(donation()), welcomeEnv())).status, 200);
});

test("sendWelcome on its own: true only when the mailer says sent", async () => {
    assert.equal(await sendWelcome(welcomeEnv(), "a@example.org"), true);
    const { result } = await logsOf(() => sendWelcome(welcomeEnv({ MAILER: fakeMailer(() => Response.json({ results: [] })) }), "a@example.org"));
    assert.equal(result, false);
});

test("maskEmail", () => {
    assert.equal(maskEmail("Ann.Lee@Example.org"), "a***@e***.org");
    assert.equal(maskEmail("nope"), "***");
    assert.equal(maskEmail(undefined), "***");
});
