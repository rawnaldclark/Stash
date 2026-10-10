import { test } from "node:test";
import assert from "node:assert/strict";
import { SyncSpace } from "../src/sync-space.js";
import { DEVICE_IDLE_DAYS, RETENTION_EVERY_MS, SEEN_WRITE_MS, SLOT_KEEP_DAYS, STAGING_KEEP_MS } from "../src/space.js";
import { LIMITS } from "../src/validate.js";
import { box, fakeCtx, keyBox, makeDevice, record } from "./fakes.js";

const T0 = 1_760_000_000_000;
const DAY = 86_400_000;
const SPACE_ID = "S".repeat(22);
const dev = (d, type) => ({ ...record(d), type });

/** A space with a phone and a browser, driven directly (no Worker), on a test clock. */
async function setup() {
    const clock = { t: T0 };
    const ctx = fakeCtx();
    const so = new SyncSpace(ctx, {}, { now: () => clock.t });
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    assert.equal((await so.create({ spaceId: SPACE_ID, phone: dev(phone, "phone"), browser: dev(browser, "web") })).status, 201);
    // Browsers come through the player (the Worker sets `player`); phones don't need it.
    const call = (d, op, params = {}, body, ifMatch, player = true) => so.call({ op, caller: d.caller, params, body, ifMatch, player });
    const addBrowser = async () => {
        const b = await makeDevice("w");
        ctx.storage.transactionSync(() => so.space.insertDevice(dev(b, "web"), clock.t));
        return b;
    };
    return { clock, ctx, so, phone, browser, call, addBrowser };
}

/** Temporarily lowers a limit for one test. */
async function withLimit(name, value, fn) {
    const old = LIMITS[name];
    LIMITS[name] = value;
    try { await fn(); } finally { LIMITS[name] = old; }
}

const env1 = (bytes) => ({ env: box(1, bytes) });
const code = (r) => r.body?.error?.code;

test("the log: the server numbers and stamps each batch with its own clock; pages stop at 200 or 4 MiB", async () => {
    const { clock, call, phone, browser } = await setup();
    const a = await call(phone, "logAppend", {}, env1(100));
    assert.deepEqual(a, { status: 201, body: { seq: 1, serverAt: T0 } });
    clock.t += 5;
    const b = await call(browser, "logAppend", {}, env1(100));
    assert.deepEqual(b.body, { seq: 2, serverAt: T0 + 5 });
    const page = await call(phone, "logAfter", { seq: 0 });
    assert.deepEqual(page.body.entries.map((e) => [e.seq, e.device, e.serverAt]), [[1, phone.id, T0], [2, browser.id, T0 + 5]]);
    assert.equal(page.body.head, 2);
    assert.equal(page.body.more, false);
    assert.deepEqual((await call(phone, "logAfter", { seq: 1 })).body.entries.map((e) => e.seq), [2]);
    assert.deepEqual((await call(phone, "logAfter", { seq: 9 })).body.entries, []);

    for (let i = 0; i < 250; i++) await call(phone, "logAppend", {}, env1(16));
    const p1 = await call(phone, "logAfter", { seq: 0 });
    assert.equal(p1.body.entries.length, 200);
    assert.equal(p1.body.more, true);
    // Big batches: a page stops before 4 MiB (and always carries at least one).
    for (let i = 0; i < 5; i++) await call(phone, "logAppend", {}, env1(700_000));
    const big = await call(phone, "logAfter", { seq: 252 });
    const bytes = big.body.entries.reduce((n, e) => n + JSON.stringify(e.env).length, 0);
    assert.ok(big.body.entries.length >= 1 && big.body.entries.length < 5 && bytes <= LIMITS.logPageBytes, `${big.body.entries.length} entries, ${bytes} bytes`);
    assert.equal(big.body.more, true);
});

test("malformed input is refused, unknown fields are dropped, and writes under an old key get 409 epoch", async () => {
    const { call, phone } = await setup();
    for (const body of [undefined, {}, { env: "x" }, { env: { n: "x", c: "y" } }, { env: { e: 1, n: "short", c: "A".repeat(40) } },
        { env: { e: 1, n: "A".repeat(16), c: "not base64url!" } }, { env: { e: 0, n: "A".repeat(16), c: "A".repeat(40) } },
        { env: { e: "1", n: "A".repeat(16), c: "A".repeat(40) } }]) {
        assert.equal((await call(phone, "logAppend", {}, body)).status, 400, JSON.stringify(body));
    }
    const stale = await call(phone, "logAppend", {}, { env: box(2) });
    assert.equal(stale.status, 409);
    assert.equal(code(stale), "epoch");
    await call(phone, "logAppend", {}, { env: { ...box(1), extra: "dropped", e: 1 } });
    const [entry] = (await call(phone, "logAfter", { seq: 0 })).body.entries;
    assert.deepEqual(Object.keys(entry.env).sort(), ["c", "e", "n"]);
    assert.equal((await call(phone, "label", {}, { labelCt: box(undefined, 2000) })).status, 400, "labels are small");
    assert.equal((await call(phone, "nowPut", {}, { env: box(1, 20_000) })).status, 400, "now is small");
    assert.equal((await call(phone, "nowPut", {}, { env: box(1, 10_000) })).status, 200);
});

test("the log refuses new batches past 2,000 until a snapshot compacts it", async () => {
    const { call, phone, browser } = await setup();
    for (let i = 0; i < LIMITS.logBatches; i++) assert.equal((await call(phone, "logAppend", {}, env1(16))).status, 201);
    const full = await call(phone, "logAppend", {}, env1(16));
    assert.equal(full.status, 409);
    assert.equal(code(full), "compact");
    assert.deepEqual((await call(browser, "snapshotPut", { upto: 2000, part: 0, count: 1 }, env1(64))).body, { complete: true });
    assert.equal((await call(phone, "logAppend", {}, env1(16))).status, 201);
    const behind = await call(browser, "logAfter", { seq: 10 });
    assert.equal(behind.status, 409);
    assert.equal(code(behind), "snapshot");
    assert.deepEqual((await call(browser, "logAfter", { seq: 2000 })).body.entries.map((e) => e.seq), [2001]);
});

test("snapshots: parts are staged, a new upload replaces an unfinished one, the last part swaps and compacts", async () => {
    const { clock, call, phone, browser } = await setup();
    for (let i = 0; i < 10; i++) await call(phone, "logAppend", {}, env1(32));
    assert.equal((await call(phone, "snapshotPut", { upto: 11, part: 0, count: 1 }, env1(32))).status, 400, "past the head");
    assert.equal((await call(phone, "snapshotPut", { upto: 5, part: 2, count: 2 }, env1(32))).status, 400, "part out of range");
    assert.deepEqual((await call(phone, "snapshotPut", { upto: 5, part: 0, count: 2 }, env1(32))).body, { complete: false });
    assert.equal((await call(phone, "snapshotGet", { part: 0 })).status, 404, "staged parts aren't served");
    // Another device starts a newer compaction: the unfinished one is dropped.
    assert.deepEqual((await call(browser, "snapshotPut", { upto: 8, part: 1, count: 3 }, env1(32))).body, { complete: false });
    assert.deepEqual((await call(browser, "snapshotPut", { upto: 8, part: 0, count: 3 }, env1(32))).body, { complete: false });
    clock.t += 1;
    assert.deepEqual((await call(browser, "snapshotPut", { upto: 8, part: 2, count: 3 }, env1(32))).body, { complete: true });
    const info = (await call(phone, "get")).body;
    assert.deepEqual(info.snapshot, { uptoSeq: 8, parts: 3, epoch: 1 });
    assert.equal(info.head, 10);
    assert.deepEqual((await call(phone, "logAfter", { seq: 8 })).body.entries.map((e) => e.seq), [9, 10]);
    const part = await call(phone, "snapshotGet", { part: 2 });
    assert.deepEqual([part.body.uptoSeq, part.body.part, part.body.count, part.body.epoch], [8, 2, 3, 1]);
    const stale = await call(phone, "snapshotPut", { upto: 5, part: 0, count: 1 }, env1(32));
    assert.equal(code(stale), "stale");
});

test("a space holds 32 MiB at most: 413 space_full (a send says to save a file instead)", async () => {
    const { call, phone, browser } = await setup();
    await withLimit("spaceBytes", 3000, async () => {
        assert.equal((await call(phone, "logAppend", {}, env1(1500))).status, 201);
        const full = await call(phone, "logAppend", {}, env1(1500));
        assert.equal(full.status, 413);
        assert.equal(code(full), "space_full");
        const send = await call(phone, "inboxPut", { to: browser.id, sendId: "send_0001", part: 0, count: 1 }, env1(1500));
        assert.equal(send.status, 413);
        assert.match(send.body.error.message, /Save it as a file/);
        // Compaction frees the space again.
        await call(phone, "snapshotPut", { upto: 1, part: 0, count: 1 }, env1(16));
        assert.equal((await call(phone, "logAppend", {}, env1(1500))).status, 201);
    });
});

test("daily caps per device: 3,000 writes and 20,000 reads, reset the next UTC day", async () => {
    const { clock, call, phone, browser } = await setup();
    await withLimit("writesPerDay", 3, async () => {
        await withLimit("readsPerDay", 3, async () => {
            for (let i = 0; i < 3; i++) assert.equal((await call(phone, "nowPut", {}, env1(16))).status, 200);
            const over = await call(phone, "nowPut", {}, env1(16));
            assert.equal(over.status, 429);
            assert.equal(code(over), "daily_limit");
            const next = Math.ceil((Math.floor(T0 / DAY) + 1) * DAY - T0) / 1000;
            assert.equal(over.headers["Retry-After"], String(Math.ceil(next)));
            assert.equal((await call(browser, "nowPut", {}, env1(16))).status, 200, "another device has its own budget");
            for (let i = 0; i < 3; i++) assert.equal((await call(phone, "nowGet")).status, 200);
            assert.equal((await call(phone, "nowGet")).status, 429);
            assert.equal((await call(browser, "nowGet")).status, 200);
            clock.t = (Math.floor(T0 / DAY) + 1) * DAY;
            assert.equal((await call(phone, "nowPut", {}, env1(16))).status, 200);
            assert.equal((await call(phone, "nowGet")).status, 200);
        });
    });
});

test("a stolen laptop that floods the space can't stop the phone from seeing it, removing it, rotating or unlinking", async () => {
    const { call, phone, browser } = await setup();
    await withLimit("writesPerDay", 50, async () => {
        await withLimit("readsPerDay", 50, async () => {
            // The laptop spends its whole budget, and the phone spends its own too (a busy day).
            for (let i = 0; i < 50; i++) await call(browser, "nowPut", {}, env1(16));
            for (let i = 0; i < 50; i++) await call(browser, "nowGet");
            for (let i = 0; i < 50; i++) await call(phone, "nowPut", {}, env1(16));
            for (let i = 0; i < 50; i++) await call(phone, "nowGet");
            assert.equal((await call(browser, "nowPut", {}, env1(16))).status, 429);
            assert.equal((await call(phone, "nowGet")).status, 429);
            // The safety actions still go through.
            const list = await call(phone, "get");
            assert.equal(list.status, 200, "the device list the Remove screen needs");
            assert.equal((await call(phone, "removeDevice", { target: browser.id })).status, 204);
            assert.equal(code(await call(browser, "get")), "revoked", "cut off at once");
            const r = await call(phone, "rotate", {}, { epoch: 2, envelopes: { [phone.id]: keyBox(2, phone) } });
            assert.equal(r.status, 200);
            assert.equal((await call(phone, "key", { epoch: 2 })).status, 200);
            assert.equal((await call(phone, "deleteSpace")).status, 204);
        });
    });
});

test("a browser's token works only through the player; a phone's works directly", async () => {
    const { call, phone, browser } = await setup();
    const direct = await call(browser, "get", {}, undefined, undefined, false);
    assert.equal(direct.status, 403);
    assert.equal(code(direct), "forbidden");
    assert.equal((await call(browser, "get")).status, 200);
    assert.equal((await call(phone, "get", {}, undefined, undefined, false)).status, 200);
});

test("a token from one space opens nothing in another", async () => {
    const a = await setup();
    const b = await setup();
    // setup() reuses one space id, so give b's objects another one: what matters is the storage, which is separate.
    for (const d of [a.phone, a.browser]) assert.equal(code(await b.call(d, "get")), "revoked");
    assert.equal(code(await b.call(a.phone, "removeDevice", { target: b.phone.id })), "revoked");
    assert.equal((await b.call(b.phone, "get")).body.devices.length, 2);
});

test("handoff slots: every device's now, newest first, stamped by the server, gone after 7 days; queues by device", async () => {
    const { clock, call, phone, browser } = await setup();
    const a = await call(phone, "nowPut", {}, env1(200));
    const b = await call(phone, "nowPut", {}, env1(200));
    assert.equal(b.body.serverAt, a.body.serverAt + 1, "serverAt strictly increases even within one millisecond");
    clock.t += 1000;
    await call(browser, "nowPut", {}, env1(200));
    const all = (await call(browser, "nowGet")).body;
    assert.deepEqual(all.slots.map((s) => s.device), [browser.id, phone.id]);
    assert.equal(all.serverTime, clock.t);

    assert.equal((await call(browser, "queueGet", { did: phone.id })).status, 404);
    const q = env1(5000);
    await call(phone, "queuePut", {}, q);
    const got = await call(browser, "queueGet", { did: phone.id });
    assert.deepEqual([got.body.device, got.body.env], [phone.id, q.env]);

    clock.t += SLOT_KEEP_DAYS * DAY + 1;
    assert.deepEqual((await call(browser, "nowGet")).body.slots, []);
    assert.equal((await call(browser, "queueGet", { did: phone.id })).status, 404);
});

test("the mirror config: last writer wins only with the version it read (If-Match)", async () => {
    const { call, phone, browser } = await setup();
    assert.equal((await call(phone, "configGet")).status, 204);
    const first = await call(phone, "configPut", {}, env1(100));
    assert.equal(first.status, 200);
    const v1 = first.body.serverAt;
    const blind = await call(browser, "configPut", {}, env1(100));
    assert.equal(blind.status, 412);
    assert.equal(code(blind), "changed");
    assert.equal(blind.headers["X-Stash-Config-At"], String(v1));
    const v2 = (await call(browser, "configPut", {}, env1(100), `"${v1}"`)).body.serverAt;
    assert.ok(v2 > v1);
    assert.equal((await call(phone, "configPut", {}, env1(100), String(v1))).status, 412, "a stale version loses");
    assert.equal((await call(phone, "configGet")).body.device, browser.id);
    assert.equal((await call(phone, "configPut", {}, env1(100), String(v2))).status, 200);
});

test("one-off sends: parts, only complete sends listed, only the receiver reads, either side deletes, 7-day life", async () => {
    const { clock, call, phone, browser, addBrowser } = await setup();
    const p = { to: browser.id, sendId: "send_abc1", count: 2 };
    assert.equal((await call(phone, "inboxPut", { ...p, to: phone.id, part: 0 }, env1(64))).status, 400, "not to yourself");
    assert.equal((await call(phone, "inboxPut", { ...p, to: "d_nobody_x", part: 0 }, env1(64))).status, 404);
    assert.deepEqual((await call(phone, "inboxPut", { ...p, part: 0 }, env1(64))).body, { complete: false });
    assert.deepEqual((await call(browser, "inboxList")).body.sends, []);
    assert.equal((await call(browser, "inboxGet", { sendId: p.sendId, part: 0 })).status, 404, "not until complete");
    assert.deepEqual((await call(phone, "inboxPut", { ...p, part: 1 }, env1(64))).body, { complete: true });
    const [s] = (await call(browser, "inboxList")).body.sends;
    assert.deepEqual([s.sendId, s.from, s.count], [p.sendId, phone.id, 2]);
    assert.equal((await call(browser, "inboxGet", { sendId: p.sendId, part: 1 })).body.part, 1);
    const other = await addBrowser();
    assert.equal((await call(other, "inboxGet", { sendId: p.sendId, part: 0 })).status, 404, "only the receiver reads");
    assert.equal((await call(other, "inboxPut", { ...p, part: 0 }, env1(64))).status, 409, "someone else's send id");
    assert.equal((await call(other, "inboxDelete", { sendId: p.sendId })).status, 404);
    assert.equal((await call(browser, "inboxDelete", { sendId: p.sendId })).status, 204);
    assert.deepEqual((await call(browser, "inboxList")).body.sends, []);

    // At most 8 sends waiting per receiver; old ones expire after 7 days.
    for (let i = 0; i < LIMITS.sendsPerDevice; i++) assert.equal((await call(phone, "inboxPut", { to: browser.id, sendId: `send_n${i}xxxx`, part: 0, count: 1 }, env1(16))).status, 200);
    const full = await call(phone, "inboxPut", { to: browser.id, sendId: "send_toomany", part: 0, count: 1 }, env1(16));
    assert.equal(code(full), "inbox_full");
    clock.t += 7 * DAY + 1;
    assert.deepEqual((await call(browser, "inboxList")).body.sends, []);
});

test("removing a device cuts it off at once, clears its slots and sends, and asks the others to rotate", async () => {
    const { call, phone, browser, addBrowser } = await setup();
    const laptop = await addBrowser();
    await call(laptop, "nowPut", {}, env1(64));
    await call(laptop, "queuePut", {}, env1(64));
    await call(phone, "inboxPut", { to: laptop.id, sendId: "send_lap1", part: 0, count: 1 }, env1(64));
    assert.equal((await call(phone, "removeDevice", { target: "d_unknown_1" })).status, 404);
    assert.equal((await call(phone, "removeDevice", { target: laptop.id })).status, 204);
    for (const op of ["get", "nowGet", "inboxList"]) {
        const r = await call(laptop, op);
        assert.equal(r.status, 401, op);
        assert.equal(code(r), "revoked");
    }
    assert.deepEqual((await call(browser, "nowGet")).body.slots, []);
    assert.equal((await call(browser, "queueGet", { did: laptop.id })).status, 404);
    const info = (await call(browser, "get")).body;
    assert.equal(info.rotationDue, true);
    assert.deepEqual(info.devices.map((d) => d.id).sort(), [browser.id, phone.id].sort());
    // A wrong token for a real device is refused the same way.
    assert.equal((await call({ caller: { deviceId: phone.id, tokenHash: "0".repeat(64) } }, "get")).status, 401);
});

test("the last device leaving, or unlink everything, deletes all storage and the alarm", async () => {
    const a = await setup();
    assert.ok(a.ctx.alarm);
    assert.equal((await a.call(a.phone, "removeDevice", { target: "me" })).status, 204);
    assert.equal((await a.call(a.browser, "removeDevice", { target: "me" })).status, 204);
    assert.deepEqual(a.ctx.sql.tables(), []);
    assert.equal(a.ctx.alarm, null);
    assert.equal(code(await a.call(a.browser, "get")), "revoked");

    const b = await setup();
    assert.equal((await b.call(b.browser, "deleteSpace")).status, 204);
    assert.deepEqual(b.ctx.sql.tables(), []);
    assert.equal(b.ctx.alarm, null);
    const r = await b.call(b.phone, "get");
    assert.equal(r.status, 401, "a deleted space answers like a removed device");
    assert.equal(code(r), "revoked");
    assert.deepEqual(b.ctx.sql.tables(), [], "asking about a space that isn't there creates nothing");
});

test("key rotation: epoch + 1, one envelope per device, config re-encrypted, old slots and sends dropped", async () => {
    const { call, phone, browser } = await setup();
    await call(phone, "configPut", {}, env1(64));
    await call(phone, "nowPut", {}, env1(64));
    await call(phone, "inboxPut", { to: browser.id, sendId: "send_rot1", part: 0, count: 1 }, env1(64));
    for (let i = 0; i < 3; i++) await call(phone, "logAppend", {}, env1(64));
    const envelopes = { [phone.id]: keyBox(2, phone), [browser.id]: keyBox(2, browser) };

    assert.equal(code(await call(phone, "rotate", {}, { epoch: 3, envelopes, config: box(3) })), "epoch");
    assert.equal(code(await call(phone, "rotate", {}, { epoch: 2, envelopes: { [phone.id]: keyBox(2, phone) }, config: box(2) })), "devices_changed", "every device needs one");
    assert.equal(code(await call(phone, "rotate", {}, { epoch: 2, envelopes: { ...envelopes, d_stranger1: keyBox(2, phone) }, config: box(2) })), "devices_changed", "and nobody else");
    assert.equal((await call(phone, "rotate", {}, { epoch: 2, envelopes })).status, 400, "the config must come along");
    assert.equal((await call(phone, "rotate", {}, { epoch: 2, envelopes, config: box(1) })).status, 400, "under the new key");
    const labels = { [browser.id]: box(2) };
    const r = await call(phone, "rotate", {}, { epoch: 2, envelopes, config: box(2), labels });
    assert.deepEqual(r, { status: 200, body: { epoch: 2 } });
    assert.equal(code(await call(browser, "rotate", {}, { epoch: 2, envelopes, config: box(2) })), "epoch", "a second rotator loses the race");

    const info = (await call(browser, "get")).body;
    assert.equal(info.epoch, 2);
    assert.equal(info.rotationDue, false);
    assert.deepEqual(info.devices.find((d) => d.id === browser.id).labelCt, labels[browser.id]);
    assert.deepEqual((await call(browser, "key", { epoch: 2 })).body, { epoch: 2, ct: envelopes[browser.id] });
    assert.equal(code(await call(browser, "key", { epoch: 1 })), "no_key");
    assert.deepEqual((await call(browser, "nowGet")).body.slots, []);
    assert.deepEqual((await call(browser, "inboxList")).body.sends, []);
    assert.equal((await call(browser, "configGet")).body.env.e, 2);
    assert.equal(code(await call(phone, "logAppend", {}, env1(16))), "epoch");
    // The old log stays readable (for devices that still hold the old key) until a snapshot under the new key is complete.
    assert.equal((await call(browser, "logAfter", { seq: 0 })).body.entries.length, 3);
    assert.equal(code(await call(phone, "logAppend", {}, { env: box(2, 16) })), "compact", "nothing new until the snapshot under the new key");
    assert.deepEqual((await call(phone, "snapshotPut", { upto: 3, part: 0, count: 1 }, { env: box(2, 64) })).body, { complete: true });
    assert.deepEqual((await call(browser, "logAfter", { seq: 3 })).body.entries, []);
    assert.equal((await call(phone, "logAppend", {}, { env: box(2, 16) })).status, 201);
    assert.equal(code(await call(browser, "logAfter", { seq: 0 })), "snapshot");
});

test("rotation with the snapshot inline replaces the old log at once; it must cover the whole log", async () => {
    const { call, phone, browser } = await setup();
    for (let i = 0; i < 3; i++) await call(phone, "logAppend", {}, env1(64));
    const envelopes = { [phone.id]: keyBox(2, phone), [browser.id]: keyBox(2, browser) };
    assert.equal(code(await call(phone, "rotate", {}, { epoch: 2, envelopes, snapshot: { uptoSeq: 2, parts: [box(2)] } })), "stale");
    assert.equal((await call(phone, "rotate", {}, { epoch: 2, envelopes, snapshot: { uptoSeq: 3, parts: [box(1)] } })).status, 400);
    assert.equal((await call(phone, "rotate", {}, { epoch: 2, envelopes, snapshot: { uptoSeq: 3, parts: [box(2), box(2)] } })).status, 200);
    const info = (await call(browser, "get")).body;
    assert.deepEqual(info.snapshot, { uptoSeq: 3, parts: 2, epoch: 2 });
    assert.deepEqual((await call(browser, "logAfter", { seq: 3 })).body.entries, []);
    assert.equal(code(await call(browser, "logAfter", { seq: 0 })), "snapshot");
});

test("retention: the alarm removes devices idle for 90 days, old slots and stalled uploads, then the empty space", async () => {
    const { clock, ctx, so, call, phone, browser } = await setup();
    assert.equal(ctx.alarm, T0 + RETENTION_EVERY_MS);
    await call(browser, "nowPut", {}, env1(64));
    await call(phone, "logAppend", {}, env1(64));
    await call(phone, "snapshotPut", { upto: 1, part: 0, count: 2 }, env1(64));
    clock.t += STAGING_KEEP_MS + 1;
    await call(phone, "get"); // the phone stays active
    await so.alarm();
    assert.equal(ctx.sql.exec("SELECT COUNT(*) AS n FROM snapshot").toArray()[0].n, 0, "the stalled upload is gone");
    assert.equal(ctx.alarm, clock.t + RETENTION_EVERY_MS, "re-armed");
    clock.t += SLOT_KEEP_DAYS * DAY;
    await call(phone, "get");
    await so.alarm();
    assert.equal(ctx.sql.exec("SELECT COUNT(*) AS n FROM slots").toArray()[0].n, 0, "the browser's week-old now slot is gone");

    clock.t = T0 + DEVICE_IDLE_DAYS * DAY + 1;
    await call(phone, "get");
    await so.alarm();
    const info = (await call(phone, "get")).body;
    assert.deepEqual(info.devices.map((d) => d.id), [phone.id], "the browser, unseen for 90 days, is removed");
    assert.equal(info.rotationDue, true);
    clock.t += DEVICE_IDLE_DAYS * DAY + 1;
    await so.alarm();
    assert.deepEqual(ctx.sql.tables(), [], "nobody left: the space is deleted");
    assert.equal(ctx.alarm, null);
});

test("a device idle for 90 days is refused even before the alarm runs", async () => {
    const { clock, call, phone, browser } = await setup();
    clock.t += DEVICE_IDLE_DAYS * DAY - 1000;
    assert.equal((await call(phone, "get")).status, 200);
    clock.t += 2000;
    assert.equal(code(await call(browser, "get")), "revoked");
    assert.deepEqual((await call(phone, "get")).body.devices.map((d) => d.id), [phone.id]);
});

test("lastSeenAt is written at most hourly; the alarm comes back if lost", async () => {
    const { clock, ctx, call, phone } = await setup();
    const seen = () => ctx.sql.exec("SELECT lastSeenAt FROM devices WHERE id = ?", phone.id).toArray()[0].lastSeenAt;
    clock.t += SEEN_WRITE_MS - 1;
    await call(phone, "nowGet");
    assert.equal(seen(), T0);
    clock.t += 1;
    await call(phone, "nowGet");
    assert.equal(seen(), T0 + SEEN_WRITE_MS);
    ctx.alarm = null;
    await call(phone, "get");
    assert.equal(ctx.alarm, clock.t + RETENTION_EVERY_MS);
});

test("a rotation that can't carry the snapshot leaves compactDue: no new batches until a whole-log snapshot under the new key", async () => {
    const { call, phone, browser } = await setup();
    for (let i = 0; i < 3; i++) await call(phone, "logAppend", {}, env1(64));
    const envelopes = { [phone.id]: keyBox(2, phone), [browser.id]: keyBox(2, browser) };
    assert.equal((await call(phone, "rotate", {}, { epoch: 2, envelopes })).status, 200);
    let info = (await call(browser, "get")).body;
    assert.equal(info.rotationDue, false);
    assert.equal(info.compactDue, true, "any device can see the job isn't finished");
    const refused = await call(browser, "logAppend", {}, { env: box(2, 32) });
    assert.equal(refused.status, 409);
    assert.equal(code(refused), "compact");
    assert.equal(code(await call(browser, "snapshotPut", { upto: 2, part: 0, count: 1 }, { env: box(2, 32) })), "stale", "it must cover the whole log");
    // The other device does it, in parts (the large-library path).
    assert.deepEqual((await call(browser, "snapshotPut", { upto: 3, part: 0, count: 2 }, { env: box(2, 32) })).body, { complete: false });
    assert.deepEqual((await call(browser, "snapshotPut", { upto: 3, part: 1, count: 2 }, { env: box(2, 32) })).body, { complete: true });
    info = (await call(phone, "get")).body;
    assert.equal(info.compactDue, false);
    assert.deepEqual(info.snapshot, { uptoSeq: 3, parts: 2, epoch: 2 });
    assert.equal(code(await call(phone, "logAfter", { seq: 0 })), "snapshot", "the old-key log is gone");
    assert.equal((await call(phone, "logAppend", {}, { env: box(2, 32) })).status, 201);
});

test("a rotation of a space with nothing stored needs no snapshot", async () => {
    const { call, phone, browser } = await setup();
    const envelopes = { [phone.id]: keyBox(2, phone), [browser.id]: keyBox(2, browser) };
    assert.equal((await call(phone, "rotate", {}, { epoch: 2, envelopes })).status, 200);
    assert.equal((await call(phone, "get")).body.compactDue, false);
    assert.equal((await call(phone, "logAppend", {}, { env: box(2, 32) })).status, 201);
});

test("a rotation racing a device-list change gets a retryable 409 with the current devices", async () => {
    const { call, phone, browser, addBrowser } = await setup();
    const envelopes = { [phone.id]: keyBox(2, phone), [browser.id]: keyBox(2, browser) };
    const laptop = await addBrowser(); // joined after the rotator read the list
    const r = await call(phone, "rotate", {}, { epoch: 2, envelopes });
    assert.equal(r.status, 409);
    assert.equal(code(r), "devices_changed");
    assert.equal(r.body.epoch, 1);
    assert.deepEqual(r.body.devices.map((d) => d.id).sort(), [phone.id, browser.id, laptop.id].sort());
    assert.ok(r.body.devices.every((d) => d.pub && d.type));
    assert.equal((await call(phone, "rotate", {}, { epoch: 2, envelopes: { ...envelopes, [laptop.id]: keyBox(2, laptop) } })).status, 200, "the retry");
});

test("join checks the epoch the newcomer was given: stale without its envelope is a retryable 409 before the code is used", async () => {
    const clock = { t: T0 };
    const ctx = fakeCtx();
    const claims = [];
    let beforeClaim = async () => {};
    const laptop = await makeDevice("w");
    const so = new SyncSpace(ctx, {}, { now: () => clock.t });
    so.space.claimPair = async (pairId, caller, mode) => {
        claims.push(pairId);
        await beforeClaim();
        return { status: 200, body: { add: { ...record(laptop), type: "web" } } };
    };
    const phone = await makeDevice("p");
    const browser = await makeDevice("w");
    await so.create({ spaceId: SPACE_ID, phone: dev(phone, "phone"), browser: dev(browser, "web") });
    const call = (d, op, params = {}, body) => so.call({ op, caller: d.caller, params, body, player: true });
    const pairId = "P".repeat(22);
    assert.equal((await call(phone, "rotate", {}, { epoch: 2, envelopes: { [phone.id]: keyBox(2, phone), [browser.id]: keyBox(2, browser) } })).status, 200);

    assert.equal((await call(phone, "join", {}, { pairId })).status, 400, "epoch is required");
    const stale = await call(phone, "join", {}, { pairId, epoch: 1 });
    assert.equal(stale.status, 409);
    assert.equal(code(stale), "epoch");
    assert.equal(stale.body.epoch, 2);
    assert.equal(claims.length, 0, "the code wasn't used");
    // The retry carries the newcomer's envelope for the current epoch; the newcomer can open the key at once.
    const env = keyBox(2, laptop);
    const ok2 = await call(phone, "join", {}, { pairId, epoch: 1, envelope: env });
    assert.deepEqual(ok2.body, { device: laptop.id, type: "web", epoch: 2 });
    assert.deepEqual((await call(laptop, "key", { epoch: 2 })).body, { epoch: 2, ct: env });
    assert.equal((await call(laptop, "get")).body.rotationDue, false);

    // A rotation that lands while the code is being claimed: the device is added and the next rotation must include it.
    await call(phone, "removeDevice", { target: laptop.id });
    await call(phone, "rotate", {}, { epoch: 3, envelopes: { [phone.id]: keyBox(3, phone), [browser.id]: keyBox(3, browser) } });
    beforeClaim = async () => {
        await call(phone, "rotate", {}, { epoch: 4, envelopes: { [phone.id]: keyBox(4, phone), [browser.id]: keyBox(4, browser) } });
    };
    const raced = await call(phone, "join", {}, { pairId, epoch: 3 });
    assert.equal(raced.status, 201);
    assert.equal(raced.body.epoch, 4);
    assert.equal((await call(phone, "get")).body.rotationDue, true);
});
