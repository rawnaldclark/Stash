import { test } from "node:test";
import assert from "node:assert/strict";
import { fakeD1 } from "./fake-d1.js";
import {
    selectAccount, ensureAccounts, coolAccount, killAccount,
    getCached, putCachedStmt, readQuota, readQuotas, bumpQuotaStmt, prune, dayKey, hourKey,
} from "../src/db.js";

const CAPS = { hourly: 2, daily: 3 };
const T0 = 1788282000; // 2026-09-01T17:00:00Z

test("day and hour keys are UTC", () => {
    assert.equal(dayKey(T0), "2026-09-01");
    assert.equal(hourKey(T0), "2026-09-01T17");
});

test("selectAccount rotates least-recently-used under the hourly cap and stamps in the same write", async () => {
    const db = fakeD1();
    await ensureAccounts(db, ["a", "b", "c"]);
    const picks = [];
    for (let i = 0; i < 7; i++) picks.push(await selectAccount(db, T0 + i, CAPS));
    assert.deepEqual(picks, ["a", "b", "c", "a", "b", "c", null]);
    const row = await db.prepare("SELECT hour_n, day_n, last_used_at FROM accounts WHERE label = 'a'").first();
    assert.deepEqual(row, { hour_n: 2, day_n: 2, last_used_at: T0 + 3 });
});

test("a new hour resets the hourly counter but the daily cap still binds", async () => {
    const db = fakeD1();
    await ensureAccounts(db, ["a"]);
    for (let i = 0; i < 2; i++) await selectAccount(db, T0 + i, CAPS);
    assert.equal(await selectAccount(db, T0 + 3600, CAPS), "a");   // third of the day
    assert.equal(await selectAccount(db, T0 + 3601, CAPS), null);  // daily cap 3
    assert.equal(await selectAccount(db, T0 + 86400, CAPS), "a");  // next UTC day
});

test("cooling, dead and reserve accounts are never selected", async () => {
    const db = fakeD1();
    await ensureAccounts(db, ["cool", "dead", "res", "ok"]);
    await coolAccount(db, "cool", T0 + 300);
    await killAccount(db, "dead", "401");
    await db.prepare("UPDATE accounts SET state = 'reserve' WHERE label = 'res'").run();
    assert.equal(await selectAccount(db, T0, CAPS), "ok");
    assert.equal(await selectAccount(db, T0 + 1, CAPS), "ok");
    assert.equal(await selectAccount(db, T0 + 2, CAPS), null);      // ok at its hourly cap, nothing else eligible
    assert.equal(await selectAccount(db, T0 + 3600, CAPS), "cool"); // cooled off and least recently used
    assert.deepEqual(await db.prepare("SELECT state, dead_reason FROM accounts WHERE label = 'dead'").first(), { state: "dead", dead_reason: "401" });
});

test("ensureAccounts is idempotent and never resets state", async () => {
    const db = fakeD1();
    await ensureAccounts(db, ["a"]);
    await killAccount(db, "a", "x");
    await ensureAccounts(db, ["a", "b"]);
    assert.equal((await db.prepare("SELECT state FROM accounts WHERE label = 'a'").first()).state, "dead");
    assert.equal((await db.prepare("SELECT COUNT(*) AS n FROM accounts").first()).n, 2);
    await ensureAccounts(db, []); // no-op, no throw
});

test("the cache serves only while the URL has ≥ 15 min left; prune drops expired rows", async () => {
    const db = fakeD1();
    const m = { url: "https://cdn/x?etsp=5000", formatId: 7, bitDepth: 24, sampleRateHz: 96000, etsp: 5000 };
    await db.batch([putCachedStmt(db, 1, 27, m)]);
    assert.deepEqual(await getCached(db, 1, 27, 4100), { url: m.url, got_format_id: 7, bit_depth: 24, sample_rate: 96000, etsp: 5000 });
    assert.equal(await getCached(db, 1, 27, 4101), null);
    assert.equal(await getCached(db, 1, 7, 4000), null); // a different requested format is a different key
    await prune(db, 5001);
    assert.equal(await getCached(db, 1, 27, 0), null);
});

test("prune gives a dead account another chance after six hours; a fresh kill stays dead", async () => {
    // Insurance against a misclassified kill (2026-09-05: five healthy accounts were
    // retired as 'preview'). A truly dead account costs one failed mint every six hours.
    const db = fakeD1();
    await ensureAccounts(db, ["old", "fresh"]);
    await db.prepare("UPDATE accounts SET last_used_at = ?1 WHERE label = 'old'").bind(T0 - 7 * 3600).run();
    await db.prepare("UPDATE accounts SET last_used_at = ?1 WHERE label = 'fresh'").bind(T0 - 3600).run();
    await killAccount(db, "old", "401");
    await killAccount(db, "fresh", "401");
    await prune(db, T0);
    assert.deepEqual(await db.prepare("SELECT state, dead_reason FROM accounts WHERE label = 'old'").first(), { state: "live", dead_reason: "" });
    assert.deepEqual(await db.prepare("SELECT state, dead_reason FROM accounts WHERE label = 'fresh'").first(), { state: "dead", dead_reason: "401" });
});

test("prune never revives an account Qobuz blocked (USER_BLOCKED), however long ago; a 401 still gets its second chance", async () => {
    const db = fakeD1();
    await ensureAccounts(db, ["blocked", "token"]);
    await db.prepare("UPDATE accounts SET last_used_at = ?1").bind(T0 - 30 * 86400).run();
    await killAccount(db, "blocked", "USER_BLOCKED");
    await killAccount(db, "token", "401");
    await prune(db, T0);
    await prune(db, T0 + 7 * 3600);
    assert.deepEqual(await db.prepare("SELECT state, dead_reason FROM accounts WHERE label = 'blocked'").first(), { state: "dead", dead_reason: "USER_BLOCKED" });
    assert.deepEqual(await db.prepare("SELECT state, dead_reason FROM accounts WHERE label = 'token'").first(), { state: "live", dead_reason: "" });
    assert.equal(await selectAccount(db, T0 + 8 * 3600, CAPS), "token");
    assert.equal(await selectAccount(db, T0 + 8 * 3600 + 1, CAPS), "token"); // never the blocked one
});

test("quota upserts per day and key", async () => {
    const db = fakeD1();
    await db.batch([bumpQuotaStmt(db, "2026-09-01", "global"), bumpQuotaStmt(db, "2026-09-01", "global"), bumpQuotaStmt(db, "2026-09-01", "i:x")]);
    assert.equal(await readQuota(db, "2026-09-01", "global"), 2);
    assert.equal(await readQuota(db, "2026-09-01", "i:x"), 1);
    assert.equal(await readQuota(db, "2026-09-02", "global"), 0);
});

test("prune keeps a week of quota rows for the IP report, and drops older ones", async () => {
    const db = fakeD1();
    const now = 1788282000; // 2026-09-01
    await db.batch([bumpQuotaStmt(db, "2026-08-24", "global"), bumpQuotaStmt(db, "2026-08-25", "global"), bumpQuotaStmt(db, "2026-09-01", "global")]);
    await prune(db, now);
    const days = db.raw.prepare("SELECT day FROM quota ORDER BY day").all().map((r) => r.day);
    assert.deepEqual(days, ["2026-08-25", "2026-09-01"]);
});

test("readQuotas reads several of today's counters at once, 0 for a key with no row", async () => {
    const db = fakeD1();
    await db.batch([bumpQuotaStmt(db, "2026-09-01", "i:x"), bumpQuotaStmt(db, "2026-09-01", "n:t"), bumpQuotaStmt(db, "2026-08-31", "nf:t")]);
    assert.deepEqual(await readQuotas(db, "2026-09-01", ["i:x", "n:t", "nf:t"]), { "i:x": 1, "n:t": 1, "nf:t": 0 });
});
