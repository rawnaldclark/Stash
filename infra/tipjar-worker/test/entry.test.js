// The $5 entry (owner's call, 2026-11-02): a Ko-fi email gets early access, and with it the app's supporter claim,
// once its Ko-fi payments add up to US$5. Everyone already on the list keeps their place.
import { test } from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.js";
import { ENTRY_USD_CENTS, hashEmail, KOFI_TEST_TXN, kofiTotalCents, recordKofiSupport } from "../src/access.js";
import { donation, env, fakeKV, goalEntries, kofiPost } from "./fake-kv.js";

const EMAIL = "ann@example.org";
const PEPPER = "pepper-for-tests";
/** The access-list hash, keyed like the live Workers (kofipay keys are only kept with EMAIL_PEPPER set). */
const hashOf = (email) => hashEmail(email, PEPPER);

/** The MAILER binding, for the welcome: records each address it's asked to welcome. */
function fakeMailer() {
    return {
        welcomed: [],
        async fetch(url, init) {
            const emails = JSON.parse(init.body).emails;
            this.welcomed.push(...emails);
            return Response.json({ results: emails.map((email) => ({ email, sent: true })), sent: emails.length });
        },
    };
}

const fakeCtx = () => ({ waiting: [], waitUntil(p) { this.waiting.push(p); } });
const entryEnv = (over = {}) => env({ MAILER: fakeMailer(), MAILER_TOKEN: "mailer-token", EMAIL_PEPPER: PEPPER, ...over });

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

/** Posts one Ko-fi payment (transaction tx-<n>) and waits for the welcome, if any. */
let seq = 0;
async function pay(e, over = {}) {
    seq += 1;
    const ctx = fakeCtx();
    const res = await worker.fetch(kofiPost(donation({ kofi_transaction_id: `tx-e${seq}`, message_id: `m-e${seq}`, ...over })), e, ctx);
    await Promise.all(ctx.waiting);
    assert.equal(res.status, 200);
    return res;
}

const accessOf = async (e, email = EMAIL) => e.ACCESS_KV.json(`access:${await hashOf(email)}`);
const supporters = (e) => JSON.parse(e.STASH_KV.map.get("supporters").value).supporters;
const snapshot = (kv) => new Map([...kv.map].map(([k, v]) => [k, v.value]));

test("the entry is one constant: US$5 in cents", () => {
    assert.equal(ENTRY_USD_CENTS, 500);
});

test("under $5: listed and counted toward the goal, but no access, no welcome; the amount is kept under the email's hash", async () => {
    const e = entryEnv();
    const { logs } = await logsOf(() => pay(e, { amount: "3.00" }));
    assert.equal(await accessOf(e), null, "no access entry");
    assert.deepEqual(e.MAILER.welcomed, [], "no welcome");
    assert.equal(supporters(e)[0].amountUsd, 3, "still on the supporters list");
    assert.equal(goalEntries(e.ACCESS_KV, "2026-10").reduce((s, x) => s + x.cents, 0), 300, "still counted in the goal");
    const hash = await hashOf(EMAIL);
    const pays = [...e.ACCESS_KV.map].filter(([k]) => k.startsWith(`kofipay:${hash}:`));
    assert.equal(pays.length, 1);
    assert.equal(pays[0][1].opts.metadata.cents, 300);
    assert.deepEqual(JSON.parse(pays[0][1].value), pays[0][1].opts.metadata);
    assert.match(logs, /under the entry so far \(300 of 500 US cents\)/);
    assert.ok(!/ann@|example\.org/i.test(logs), "no email in the logs");
});

test("$4.99 is under; exactly $5.00 is in, with one welcome", async () => {
    const under = entryEnv();
    await logsOf(() => pay(under, { amount: "4.99" }));
    assert.equal(await accessOf(under), null);
    const exact = entryEnv();
    await logsOf(() => pay(exact, { amount: "5.00" }));
    assert.equal((await accessOf(exact)).source, "kofi");
    assert.deepEqual(exact.MAILER.welcomed, [EMAIL]);
});

test("donations add up per email: $3 then $2 lets the email in on the second, welcomed once", async () => {
    const e = entryEnv();
    await logsOf(() => pay(e, { amount: "3.00" }));
    assert.equal(await accessOf(e), null);
    await logsOf(() => pay(e, { amount: "2.00", email: " ANN@example.org " }));
    const access = await accessOf(e);
    assert.equal(access.source, "kofi");
    assert.ok(access.firstAt && access.lastAt);
    await logsOf(() => pay(e, { amount: "1.00" }));
    assert.deepEqual(e.MAILER.welcomed, [EMAIL], "welcomed once, not again on a later payment");
});

test("another email's payments don't count toward this one", async () => {
    const e = entryEnv();
    await logsOf(() => pay(e, { amount: "3.00", email: "bob@example.net" }));
    await logsOf(() => pay(e, { amount: "3.00" }));
    assert.equal(await accessOf(e), null);
    assert.equal(await accessOf(e, "bob@example.net"), null);
});

test("monthly payments count: a $3 Subscription twice lets the email in on the second", async () => {
    const e = entryEnv();
    const monthly = { type: "Subscription", amount: "3.00", is_subscription_payment: true };
    await logsOf(() => pay(e, { ...monthly, is_first_subscription_payment: true }));
    assert.equal(await accessOf(e), null);
    await logsOf(() => pay(e, { ...monthly, timestamp: "2026-11-04T12:00:00Z" }));
    assert.equal((await accessOf(e)).source, "kofi");
    assert.equal(goalEntries(e.ACCESS_KV, "2026-11")[0].source, "Ko-fi (monthly)");
    assert.deepEqual(e.MAILER.welcomed, [EMAIL]);
});

test("a one-off and a monthly payment add up together", async () => {
    const e = entryEnv();
    await logsOf(() => pay(e, { amount: "2.00" }));
    await logsOf(() => pay(e, { type: "Subscription", amount: "3.00", is_subscription_payment: true }));
    assert.equal((await accessOf(e)).source, "kofi");
});

test("other currencies count at the fixed table's US$ equivalent", async () => {
    for (const [amount, currency, inside] of [
        ["5.00", "EUR", true], // 540 cents
        ["4.00", "GBP", true], // 508
        ["5.00", "CHF", true], // 565
        ["5.00", "CAD", false], // 365
        ["7.00", "CAD", true], // 511
        ["5.00", "AUD", false], // 330
        ["800", "JPY", true], // 536
        ["400", "INR", false], // 480
    ]) {
        const e = entryEnv();
        await logsOf(() => pay(e, { amount, currency }));
        assert.equal(!!(await accessOf(e)), inside, `${amount} ${currency}`);
    }
    // A non-USD payment adds to a USD one: €2 (216) + $3 (300) = 516.
    const e = entryEnv();
    await logsOf(() => pay(e, { amount: "2.00", currency: "eur" }));
    assert.equal(await accessOf(e), null);
    await logsOf(() => pay(e, { amount: "3.00", currency: "USD" }));
    assert.equal((await accessOf(e)).source, "kofi");
});

test("a currency missing from the table can't be checked: let in, welcomed, and logged (not counted in the goal)", async () => {
    const e = entryEnv();
    const { logs } = await logsOf(() => pay(e, { amount: "1.00", currency: "XYZ" }));
    assert.equal((await accessOf(e)).source, "kofi");
    assert.deepEqual(e.MAILER.welcomed, [EMAIL]);
    assert.match(logs, /currency "XYZ" isn't in the table/);
    assert.equal(goalEntries(e.ACCESS_KV, "2026-10").length, 0);
    assert.equal([...e.ACCESS_KV.map.keys()].filter((k) => k.startsWith("kofipay:")).length, 0, "no amount to keep");
});

test("a bad amount in a known currency is not let in and keeps nothing", async () => {
    for (const amount of ["", "abc", "0", "-5"]) {
        const e = entryEnv();
        await logsOf(() => pay(e, { amount }));
        assert.equal(await accessOf(e), null, JSON.stringify(amount));
        assert.equal([...e.ACCESS_KV.map.keys()].filter((k) => k.startsWith("kofipay:")).length, 0);
    }
});

test("existing kofi entries are kept, whatever the new payment: $1 keeps access and firstAt, keeps no amount, sends no welcome", async () => {
    const hash = await hashOf(EMAIL);
    const old = { source: "kofi", firstAt: "2026-10-05T00:00:00.000Z", lastAt: "2026-10-05T00:00:00.000Z" };
    const e = entryEnv({ ACCESS_KV: fakeKV({ [`access:${hash}`]: JSON.stringify(old) }) });
    await logsOf(() => pay(e, { amount: "1.00" }));
    const access = await accessOf(e);
    assert.equal(access.source, "kofi");
    assert.equal(access.firstAt, old.firstAt);
    assert.deepEqual(e.MAILER.welcomed, []);
    assert.equal([...e.ACCESS_KV.map.keys()].filter((k) => k.startsWith("kofipay:")).length, 0);
});

test("under $5, approved, manual and open entries are left exactly as they were, and nobody is welcomed", async () => {
    const hash = await hashOf(EMAIL);
    for (const prev of [
        { source: "approved", by: "owner@example.com", at: "2026-10-02T00:00:00.000Z" },
        { source: "manual", by: "cli", at: "2026-10-02T00:00:00.000Z" },
        { source: "open", at: "2026-10-02T00:00:00.000Z" },
    ]) {
        const raw = JSON.stringify(prev);
        const e = entryEnv({ ACCESS_KV: fakeKV({ [`access:${hash}`]: raw }) });
        await logsOf(() => pay(e, { amount: "3.00" }));
        assert.equal(e.ACCESS_KV.map.get(`access:${hash}`).value, raw, prev.source);
        assert.deepEqual(e.MAILER.welcomed, [], prev.source);
    }
});

test("reaching $5 turns an approved or manual entry into kofi, as any donation did before, keeping its date and sending no welcome", async () => {
    const hash = await hashOf(EMAIL);
    const e = entryEnv({ ACCESS_KV: fakeKV({ [`access:${hash}`]: JSON.stringify({ source: "manual", by: "cli", at: "2026-10-02T00:00:00.000Z" }) }) });
    await logsOf(() => pay(e, { amount: "5.00" }));
    const access = await accessOf(e);
    assert.equal(access.source, "kofi");
    assert.equal(access.firstAt, "2026-10-02T00:00:00.000Z");
    assert.deepEqual(e.MAILER.welcomed, []);
});

test("no existing entry is changed by someone else's payment, under or over the entry", async () => {
    const others = {};
    for (const [i, source] of ["kofi", "manual", "approved", "open"].entries()) {
        others[`access:${await hashOf(`old${i}@example.org`)}`] = JSON.stringify({ source, at: "2026-10-01T00:00:00.000Z" });
    }
    const e = entryEnv({ ACCESS_KV: fakeKV(others) });
    const before = snapshot(e.ACCESS_KV);
    await logsOf(() => pay(e, { amount: "3.00" }));
    await logsOf(() => pay(e, { amount: "9.00", email: "new@example.net" }));
    for (const [k, v] of before) assert.equal(e.ACCESS_KV.map.get(k).value, v, k);
});

test("a Ko-fi retry of a payment under the entry counts it once", async () => {
    const e = entryEnv();
    const p = donation({ amount: "3.00", kofi_transaction_id: "tx-retry" });
    await logsOf(async () => {
        await recordKofiSupport(e, p);
        await recordKofiSupport(e, p);
        await recordKofiSupport(e, p);
    });
    assert.equal(await kofiTotalCents(e.ACCESS_KV, await hashOf(EMAIL)), 300);
    assert.equal(await accessOf(e), null);
});

test("a webhook retried after its early-access step failed still adds up right", async () => {
    const access = fakeKV();
    const put = access.put.bind(access);
    let failGoal = false;
    access.put = async (key, value, opts) => {
        if (failGoal && key.startsWith("goal:")) throw new Error("KV write failed");
        return put(key, value, opts);
    };
    const e = entryEnv({ ACCESS_KV: access });
    await logsOf(() => pay(e, { amount: "3.00" }));
    failGoal = true;
    const first = await logsOf(() => worker.fetch(kofiPost(donation({ amount: "2.00", kofi_transaction_id: "tx-r2" })), e, fakeCtx()));
    assert.equal(first.result.status, 500);
    failGoal = false;
    const retry = await logsOf(() => worker.fetch(kofiPost(donation({ amount: "2.00", kofi_transaction_id: "tx-r2" })), e, fakeCtx()));
    assert.equal(retry.result.status, 200);
    assert.equal(await kofiTotalCents(access, await hashOf(EMAIL)), 500);
    assert.equal((await accessOf(e)).source, "kofi");
});

test("this payment counts even when KV's list() hasn't caught up with its write", async () => {
    const kv = fakeKV();
    const list = kv.list.bind(kv);
    let hidden = null;
    const put = kv.put.bind(kv);
    kv.put = async (key, value, opts) => {
        if (key.startsWith("kofipay:")) hidden = key;
        return put(key, value, opts);
    };
    kv.list = async (opts) => {
        const page = await list(opts);
        return { ...page, keys: page.keys.filter((k) => k.name !== hidden) };
    };
    const e = entryEnv({ ACCESS_KV: kv });
    await logsOf(() => pay(e, { amount: "3.00" }));
    await logsOf(() => pay(e, { amount: "2.00" }));
    assert.equal((await accessOf(e)).source, "kofi");
});

test("kofiTotalCents pages through more than one list() page and skips junk metadata", async () => {
    const hash = "h".repeat(64);
    const kv = fakeKV();
    for (let i = 0; i < 1203; i++) await kv.put(`kofipay:${hash}:t${i}`, "{}", { metadata: { cents: 1, at: "x" } });
    await kv.put(`kofipay:${hash}:junk1`, "{}", { metadata: { cents: "500" } });
    await kv.put(`kofipay:${hash}:junk2`, "{}", { metadata: { cents: -400 } });
    await kv.put(`kofipay:${hash}:junk3`, "{}");
    await kv.put(`kofipay:${"g".repeat(64)}:t1`, "{}", { metadata: { cents: 999 } });
    assert.equal(await kofiTotalCents(kv, hash), 1203);
    assert.equal(await kofiTotalCents(kv, hash, `kofipay:${hash}:t0`), 1202);
});

test("Ko-fi's test webhook still touches neither the access list nor the kept amounts", async () => {
    const e = entryEnv();
    await logsOf(() => worker.fetch(kofiPost(donation({ kofi_transaction_id: KOFI_TEST_TXN, amount: "3.00" })), e, fakeCtx()));
    assert.equal(e.ACCESS_KV.map.size, 0);
});

test("the kept amounts hold no email, only its hash", async () => {
    const e = entryEnv({ EMAIL_PEPPER: "test-pepper" });
    await logsOf(() => pay(e, { amount: "3.00", email: "Secret.Person@Private-Domain.org" }));
    const hash = await hashEmail("secret.person@private-domain.org", "test-pepper");
    const keys = [...e.ACCESS_KV.map.keys()].filter((k) => k.startsWith("kofipay:"));
    assert.equal(keys.length, 1);
    assert.ok(keys[0].startsWith(`kofipay:${hash}:`));
    for (const [key, { value, opts }] of e.ACCESS_KV.map) {
        const all = `${key} ${value} ${JSON.stringify(opts.metadata ?? {})}`.toLowerCase();
        assert.ok(!all.includes("secret.person") && !all.includes("private-domain"), key);
    }
});

// ---- review follow-ups ----

test("without EMAIL_PEPPER no amount is kept: only the payment itself counts, and the log says so", async () => {
    const e = entryEnv({ EMAIL_PEPPER: undefined });
    const { logs } = await logsOf(() => pay(e, { amount: "3.00" }));
    assert.match(logs, /EMAIL_PEPPER isn't set, so no amount is kept/);
    assert.equal([...e.ACCESS_KV.map.keys()].filter((k) => k.startsWith("kofipay:")).length, 0);
    await logsOf(() => pay(e, { amount: "2.00" }));
    assert.equal(e.ACCESS_KV.json(`access:${await hashEmail(EMAIL)}`), null, "$3 + $2 can't add up without kept amounts");
    await logsOf(() => pay(e, { amount: "5.00" }));
    assert.equal(e.ACCESS_KV.json(`access:${await hashEmail(EMAIL)}`).source, "kofi", "$5 on its own still gets in");
});

test("a retry that arrives after a later payment still counts each payment once", async () => {
    // $3, then $1 (400, under), then Ko-fi retries the $3: still 400.
    const e = entryEnv();
    const first = donation({ amount: "3.00", kofi_transaction_id: "tx-late-1", message_id: "m-late-1" });
    await logsOf(async () => {
        await recordKofiSupport(e, first);
        await recordKofiSupport(e, donation({ amount: "1.00", kofi_transaction_id: "tx-late-2", message_id: "m-late-2" }));
        await recordKofiSupport(e, first);
    });
    assert.equal(await kofiTotalCents(e.ACCESS_KV, await hashOf(EMAIL)), 400);
    assert.equal(await accessOf(e), null);
    // $3, then $2 (in, welcomed), then the $3 again through the webhook (state "listed" in STASH_KV): no second welcome.
    const w = entryEnv();
    await logsOf(() => pay(w, { amount: "3.00", kofi_transaction_id: "tx-w1", message_id: "m-w1" }));
    await logsOf(() => pay(w, { amount: "2.00", kofi_transaction_id: "tx-w2", message_id: "m-w2" }));
    await w.STASH_KV.put("kofitxn:tx-w1", "listed");
    await logsOf(() => pay(w, { amount: "3.00", kofi_transaction_id: "tx-w1", message_id: "m-w1" }));
    assert.equal((await accessOf(w)).source, "kofi");
    assert.deepEqual(w.MAILER.welcomed, [EMAIL]);
    assert.equal(await kofiTotalCents(w.ACCESS_KV, await hashOf(EMAIL)), 500);
});

test("an amount sent as a JSON number counts like a string", async () => {
    const e = entryEnv();
    const json = (over) => new Request("https://stash-tipjar.example.workers.dev/", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ verification_token: "secret", ...donation(over) }),
    });
    await logsOf(() => worker.fetch(json({ amount: 3, kofi_transaction_id: "tx-n1", message_id: "m-n1" }), e, fakeCtx()));
    assert.equal(await accessOf(e), null);
    assert.equal(await kofiTotalCents(e.ACCESS_KV, await hashOf(EMAIL)), 300);
    await logsOf(() => worker.fetch(json({ amount: 2.5, kofi_transaction_id: "tx-n2", message_id: "m-n2" }), e, fakeCtx()));
    assert.equal((await accessOf(e)).source, "kofi");
});

test("rounding at the edge: €4.63 is 500 US cents (in), €4.62 is 499 (not yet)", async () => {
    const inside = entryEnv();
    await logsOf(() => pay(inside, { amount: "4.63", currency: "EUR" }));
    assert.equal((await accessOf(inside)).source, "kofi");
    const under = entryEnv();
    await logsOf(() => pay(under, { amount: "4.62", currency: "EUR" }));
    assert.equal(await accessOf(under), null);
    assert.equal(await kofiTotalCents(under.ACCESS_KV, await hashOf(EMAIL)), 499);
});

test("a currency of only spaces is USD, not an unknown currency let in unchecked", async () => {
    const e = entryEnv();
    const { logs } = await logsOf(() => pay(e, { amount: "3.00", currency: "   " }));
    assert.equal(await accessOf(e), null);
    assert.doesNotMatch(logs, /isn't in the table/);
    assert.equal(await kofiTotalCents(e.ACCESS_KV, await hashOf(EMAIL)), 300);
    const five = entryEnv();
    await logsOf(() => pay(five, { amount: "5.00", currency: " " }));
    assert.equal((await accessOf(five)).source, "kofi");
});
