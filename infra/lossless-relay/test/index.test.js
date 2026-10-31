import { test } from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { fakeD1, QUOTA_MIGRATIONS } from "./fake-d1.js";
import worker, { handle as handleWithCtx, networkOf, network48Of, refusalNamesTrack } from "../src/index.js";
import { bumpQuotaStmt } from "../src/db.js";
import { hooks } from "../src/quota.js";
import { keypair, token, tidOf } from "./entitlement-keys.js";

const NOW = 1788282000; // 2026-09-01T17:00:00Z
const KEY = "k-secret";
const BASE = "https://relay.test";
const ACCOUNTS = [
    { label: "a", token: "tok-a", app_id: "111111111", app_secret: "s-a" },
    { label: "b", token: "tok-b", app_id: "111111111", app_secret: "s-b" },
];
const GOOD = { url: "https://cdn.example/f.flac?etsp=" + (NOW + 3599), format_id: 7, bit_depth: 24, sampling_rate: 96 };

/** A Worker ExecutionContext that keeps what waitUntil was handed; settle() awaits all of it. */
function execCtx() {
    const pending = [];
    return { pending, waitUntil: (p) => { pending.push(p); }, passThroughOnException() {}, settle: () => Promise.all(pending) };
}

/** handle() as the runtime calls it (with a ctx), then every waitUntil settled: D1 rows are final when it returns. */
async function handle(request, e, fetchImpl, nowSec) {
    const ctx = execCtx();
    const r = await handleWithCtx(request, e, fetchImpl, nowSec, ctx);
    await ctx.settle();
    return r;
}

function env(over = {}) {
    return {
        DB: fakeD1(), RELAY_KEY: KEY, QOBUZ_ACCOUNTS: JSON.stringify(ACCOUNTS),
        MINT_RL: { limit: async () => ({ success: true }) },
        GLOBAL_DAILY_CAP: "600", INSTALL_DAILY_CAP: "100", ACCOUNT_HOURLY_CAP: "20", ACCOUNT_DAILY_CAP: "200",
        ...over,
    };
}

function mintReq(trackId, formatId, { key = KEY, install = "install-0001", ts = NOW, signed = true, version = "1", purpose, ip = "203.0.113.9" } = {}) {
    const h = { "X-Stash-Version": version, Accept: "application/json", "CF-Connecting-IP": ip };
    if (purpose) h["X-Stash-Purpose"] = purpose;
    if (signed) {
        h["X-Stash-Install"] = install;
        h["X-Stash-Ts"] = String(ts);
        h["X-Stash-Auth"] = createHmac("sha256", key).update(`${install}:${trackId}:${formatId}:${ts}`).digest("hex");
    }
    return new Request(`${BASE}/v1/qobuz/file?track_id=${trackId}&format_id=${formatId}`, { headers: h });
}

/** A fake Qobuz: `replies` are [status, body] pairs consumed in order; every call is recorded. */
function qobuz(...replies) {
    const calls = [];
    const f = async (url, init) => {
        calls.push({ url: String(url), init });
        const [status, body] = replies.length ? replies.shift() : [500, ""];
        return new Response(typeof body === "string" ? body : JSON.stringify(body), { status });
    };
    f.calls = calls;
    return f;
}

test("routing: status answers 200 with a body, unknown paths 404, non-GET 405; the module exports both handlers", async () => {
    const e = env();
    const s = await handle(new Request(`${BASE}/v1/status`), e, qobuz(), NOW);
    assert.equal(s.status, 200);
    assert.deepEqual(await s.json(), { ok: true });
    assert.equal((await handle(new Request(`${BASE}/`), e, qobuz(), NOW)).status, 404);
    assert.equal((await handle(new Request(`${BASE}/v1/qobuz/file`, { method: "POST" }), e, qobuz(), NOW)).status, 405);
    assert.equal(typeof worker.fetch, "function");
    assert.equal(typeof worker.scheduled, "function");
});

test("400 for another protocol version or malformed ids; Qobuz untouched", async () => {
    const e = env(); const q = qobuz();
    assert.equal((await handle(mintReq(42, 27, { version: "2" }), e, q, NOW)).status, 400);
    assert.equal((await handle(new Request(`${BASE}/v1/qobuz/file?track_id=abc&format_id=27`, { headers: { "X-Stash-Version": "1" } }), e, q, NOW)).status, 400);
    assert.equal((await handle(new Request(`${BASE}/v1/qobuz/file?track_id=42&format_id=x`, { headers: { "X-Stash-Version": "1" } }), e, q, NOW)).status, 400);
    assert.equal(q.calls.length, 0);
});

test("401 when unsigned or signed with the wrong key; the previous key still works", async () => {
    const e = env({ RELAY_KEY: "new", RELAY_KEY_PREV: KEY }); const q = qobuz([200, GOOD]);
    assert.equal((await handle(mintReq(42, 27, { signed: false }), e, q, NOW)).status, 401);
    assert.equal((await handle(mintReq(42, 27, { key: "bogus" }), e, q, NOW)).status, 401);
    assert.equal((await handle(mintReq(42, 27, { key: KEY }), e, q, NOW)).status, 200);
});

test("a 401 carries the server's clock in X-Stash-Server-Time; a skewed timestamp is still refused", async () => {
    const e = env(); const q = qobuz([200, GOOD]);
    for (const opts of [{ signed: false }, { key: "bogus" }, { ts: NOW - 301 }, { ts: NOW + 301 }]) {
        const r = await handle(mintReq(42, 27, opts), e, q, NOW);
        assert.equal(r.status, 401, JSON.stringify(opts));
        assert.equal(r.headers.get("X-Stash-Server-Time"), String(NOW), JSON.stringify(opts));
    }
    assert.equal(q.calls.length, 0);
    const okR = await handle(mintReq(42, 27, { ts: NOW - 300 }), e, q, NOW);
    assert.equal(okR.status, 200);
    assert.equal(okR.headers.get("X-Stash-Server-Time"), null); // only on the 401
});

test("500 when RELAY_KEY is unset: an open relay is a misconfiguration, not a mode", async () => {
    assert.equal((await handle(mintReq(42, 27), env({ RELAY_KEY: undefined }), qobuz([200, GOOD]), NOW)).status, 500);
});

test("Data Saver (format 5) or any other non-lossless format → 404 with no Qobuz call and no quota", async () => {
    const e = env(); const q = qobuz([200, GOOD]);
    assert.equal((await handle(mintReq(42, 5), e, q, NOW)).status, 404);
    assert.equal((await handle(mintReq(42, 8), e, q, NOW)).status, 404);
    assert.equal(q.calls.length, 0);
    assert.equal(await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first(), null);
});

test("miss → 200 in the wire shape after one Qobuz call; the same track is then a HIT with no Qobuz call and no quota", async () => {
    const e = env(); const q = qobuz([200, GOOD]);
    const r1 = await handle(mintReq(42, 27), e, q, NOW);
    assert.equal(r1.status, 200);
    assert.equal(r1.headers.get("X-Stash-Cache"), "MISS");
    assert.deepEqual(await r1.json(), { url: GOOD.url, format_id: 7, bit_depth: 24, sample_rate: 96000 });
    assert.equal(q.calls.length, 1);
    assert.match(q.calls[0].url, /request_sig=[0-9a-f]{32}/);
    assert.equal(q.calls[0].init.headers["X-User-Auth-Token"], "tok-a");
    const r2 = await handle(mintReq(42, 27, { install: "install-0002" }), e, q, NOW + 60);
    assert.equal(r2.status, 200);
    assert.equal(r2.headers.get("X-Stash-Cache"), "HIT");
    assert.equal(q.calls.length, 1);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 1);
});

test("every label in the secret joins the rotation at once: two misses use two accounts", async () => {
    const e = env(); const q = qobuz([200, GOOD], [200, { ...GOOD, url: "https://cdn.example/g.flac?etsp=" + (NOW + 3599) }]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200);
    assert.equal((await handle(mintReq(43, 27), e, q, NOW + 1)).status, 200);
    assert.deepEqual(q.calls.map((c) => c.init.headers["X-User-Auth-Token"]), ["tok-a", "tok-b"]);
});

test("QOBUZ_ACCOUNTS_EXTRA accounts join the pool and can serve a miss", async () => {
    const extra = { label: "extra", token: "extra-token", app_id: "222222222", app_secret: "extra-secret" };
    const e = env({ QOBUZ_ACCOUNTS: "[]", QOBUZ_ACCOUNTS_EXTRA: JSON.stringify([extra]) });
    const q = qobuz([200, GOOD]);
    assert.equal((await handle(mintReq(44, 27), e, q, NOW)).status, 200);
    assert.equal(q.calls.length, 1);
    assert.equal(q.calls[0].init.headers["X-User-Auth-Token"], "extra-token");
    assert.deepEqual(await e.DB.prepare("SELECT label FROM accounts").all(), { results: [{ label: "extra" }] });
});

test("new recovered secret slots join the pool", async () => {
    for (const slot of [9, 10]) {
        const account = { label: `jobavachess${slot}`, token: `jobava-token-${slot}`, app_id: "222222222", app_secret: "jobava-secret" };
        const e = env({ QOBUZ_ACCOUNTS: "[]", [`QOBUZ_ACCOUNTS_RECOVERED_${slot}`]: JSON.stringify([account]) });
        const q = qobuz([200, GOOD]);
        assert.equal((await handle(mintReq(45, 27), e, q, NOW)).status, 200);
        assert.equal(q.calls[0].init.headers["X-User-Auth-Token"], `jobava-token-${slot}`);
    }
});

test("a dead account is retired and the next one serves the same request", async () => {
    const e = env(); const q = qobuz([401, {}], [200, GOOD]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200);
    assert.equal(q.calls[1].init.headers["X-User-Auth-Token"], "tok-b");
    assert.deepEqual(await e.DB.prepare("SELECT state, dead_reason FROM accounts WHERE label = 'a'").first(), { state: "dead", dead_reason: "401" });
});

const RIGHTS_LOCK = { format_id: 7, restrictions: [{ code: "SampleRestrictedByRightHolders" }] }; // live 2026-09-27

test("region lock → 404, never cached, quota spent", async () => {
    const e = env(); const q = qobuz([200, RIGHTS_LOCK], [200, RIGHTS_LOCK]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 404);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 404);
    assert.equal(q.calls.length, 2);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 2);
});

test("a refusal that names the track is a 404 at once: no second account is asked", async () => {
    const e = env(); const q = qobuz([200, RIGHTS_LOCK], [200, GOOD]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 404);
    assert.equal(q.calls.length, 1);
});

test("a refusal with no track-level code is cross-checked: another account serves it and the refuser cools, never dies", async () => {
    const e = env(); const q = qobuz([200, { ...GOOD, sample: true }], [200, GOOD]);
    const r = await handle(mintReq(42, 27), e, q, NOW);
    assert.equal(r.status, 200);
    assert.deepEqual(q.calls.map((c) => c.init.headers["X-User-Auth-Token"]), ["tok-a", "tok-b"]);
    const a = await e.DB.prepare("SELECT state, cooling_until FROM accounts WHERE label = 'a'").first();
    assert.equal(a.state, "live");
    assert.equal(a.cooling_until, NOW + 3600);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 1);
});

test("both accounts refusing with no code → 404 as before, quota spent once, nobody cooled", async () => {
    const e = env(); const q = qobuz([200, { format_id: 7 }], [200, { format_id: 7 }]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 404);
    assert.equal(q.calls.length, 2);
    assert.equal((await e.DB.prepare("SELECT COUNT(*) AS n FROM accounts WHERE cooling_until > 0").first()).n, 0);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 1);
});

test("a codeless refusal then a transient failure still answers 404, not 503: quota once, the failing account cools 300 s", async () => {
    const e = env(); const q = qobuz([200, { format_id: 7 }], [500, ""]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 404);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 1);
    assert.equal((await e.DB.prepare("SELECT cooling_until FROM accounts WHERE label = 'b'").first()).cooling_until, NOW + 300);
    assert.equal((await e.DB.prepare("SELECT cooling_until FROM accounts WHERE label = 'a'").first()).cooling_until, 0);
});

test("a codeless refusal from the only account: no second call to it, 404 with quota once, not cooled", async () => {
    const e = env({ QOBUZ_ACCOUNTS: JSON.stringify([ACCOUNTS[0]]) }); const q = qobuz([200, { ...GOOD, sample: true }], [200, GOOD]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 404);
    assert.equal(q.calls.length, 1);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 1);
    assert.equal((await e.DB.prepare("SELECT cooling_until FROM accounts WHERE label = 'a'").first()).cooling_until, 0);
});

test("a codeless refusal, then a dead second account: the dead one is retired, the answer is 404 with quota once", async () => {
    const e = env(); const q = qobuz([200, { format_id: 7 }], [401, {}]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 404);
    assert.equal((await e.DB.prepare("SELECT state FROM accounts WHERE label = 'b'").first()).state, "dead");
    assert.equal((await e.DB.prepare("SELECT state FROM accounts WHERE label = 'a'").first()).state, "live");
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 1);
});

test("a codeless refusal with every other account unavailable → 503 busy with Retry-After, never a 404 the device would keep", async () => {
    const e = env({ IP_SALT: "salt-1" });
    assert.equal((await handle(mintReq(41, 27), e, qobuz([200, GOOD]), NOW)).status, 200); // rows for a and b; a served
    await e.DB.prepare("UPDATE accounts SET cooling_until = ?1 WHERE label = 'b'").bind(NOW + 300).run();
    await e.DB.prepare("UPDATE accounts SET last_used_at = 0 WHERE label = 'a'").run();
    const q = qobuz([200, { ...GOOD, sample: true }]);
    const r = await handle(mintReq(42, 27), e, q, NOW + 1);
    assert.equal(r.status, 503);
    assert.equal(r.headers.get("Retry-After"), "60");
    assert.deepEqual(await r.json(), { error: "busy" });
    assert.equal(q.calls.length, 1);
    // One network unit for the call, no install or global spend for an answer it didn't get; nobody cooled further.
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 1);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'i:install-0001'").first()).n, 1); // the earlier 200 only
    assert.equal(e.DB.raw.prepare("SELECT n FROM quota WHERE key LIKE 'n:%'").get().n, 2);
    assert.equal((await e.DB.prepare("SELECT cooling_until FROM accounts WHERE label = 'a'").first()).cooling_until, 0);
});

test("without IP_SALT the uncross-checked refusal is still a 503, with no empty D1 batch", async () => {
    const e = env();
    const batch = e.DB.batch.bind(e.DB);
    e.DB.batch = async (s) => { if (!s.length) throw new Error("D1: empty batch"); return batch(s); };
    assert.equal((await handle(mintReq(41, 27), e, qobuz([200, GOOD]), NOW)).status, 200);
    await e.DB.prepare("UPDATE accounts SET cooling_until = ?1 WHERE label = 'b'").bind(NOW + 300).run();
    await e.DB.prepare("UPDATE accounts SET last_used_at = 0 WHERE label = 'a'").run();
    const errors = [];
    const orig = console.error;
    console.error = (x) => errors.push(x);
    let r;
    try { r = await handle(mintReq(42, 27), e, qobuz([200, { ...GOOD, sample: true }]), NOW + 1); } finally { console.error = orig; }
    assert.equal(r.status, 503);
    assert.equal(r.headers.get("Retry-After"), "60");
    assert.deepEqual(errors, []); // answered on purpose, not by the catch-all
});

test("a codeless refusal when every other account is dead (blocked or not) → 404 like a one-account pool, quota once, not cooled", async () => {
    for (const reason of ["USER_BLOCKED", "401"]) {
        const e = env({ IP_SALT: "salt-1" });
        assert.equal((await handle(mintReq(41, 27), e, qobuz([200, GOOD]), NOW)).status, 200); // rows for a and b; a served
        await e.DB.prepare("UPDATE accounts SET state = 'dead', dead_reason = ?1 WHERE label = 'b'").bind(reason).run();
        await e.DB.prepare("UPDATE accounts SET last_used_at = 0 WHERE label = 'a'").run();
        const q = qobuz([200, { ...GOOD, sample: true }], [200, GOOD]);
        const r = await handle(mintReq(42, 27), e, q, NOW + 1);
        assert.equal(r.status, 404, reason);
        assert.deepEqual(await r.json(), { error: "not_available" }, reason);
        assert.equal(q.calls.length, 1, reason);
        assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 2, reason); // the 200 and this 404
        assert.equal((await e.DB.prepare("SELECT cooling_until FROM accounts WHERE label = 'a'").first()).cooling_until, 0, reason);
    }
    // The same pool with b merely cooling still waits for b's second opinion: 503.
    const cooling = env();
    assert.equal((await handle(mintReq(41, 27), cooling, qobuz([200, GOOD]), NOW)).status, 200);
    await cooling.DB.prepare("UPDATE accounts SET cooling_until = ?1 WHERE label = 'b'").bind(NOW + 300).run();
    await cooling.DB.prepare("UPDATE accounts SET last_used_at = 0 WHERE label = 'a'").run();
    assert.equal((await handle(mintReq(42, 27), cooling, qobuz([200, { ...GOOD, sample: true }]), NOW + 1)).status, 503);
});

test("the account cooled by a cross-check sits out the next miss", async () => {
    const e = env(); const q = qobuz([200, { ...GOOD, sample: true }], [200, GOOD], [200, { ...GOOD, url: "https://cdn.example/g.flac?etsp=" + (NOW + 3599) }]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200);
    assert.equal((await handle(mintReq(43, 27), e, q, NOW + 60)).status, 200);
    assert.deepEqual(q.calls.map((c) => c.init.headers["X-User-Auth-Token"]), ["tok-a", "tok-b", "tok-b"]);
});

/** A fake Qobuz that answers by account: tokens in `expired` get a bare preview, the rest a full URL. */
function qobuzByToken(expired) {
    const calls = [];
    const f = async (url, init) => {
        calls.push({ url: String(url), init });
        const tok = init.headers["X-User-Auth-Token"];
        const body = expired.has(tok) ? { ...GOOD, sample: true } : { ...GOOD, url: `https://cdn.example/${calls.length}.flac?etsp=${NOW + 3599}` };
        return new Response(JSON.stringify(body), { status: 200 });
    };
    f.calls = calls;
    return f;
}

test("an account removed from the secret is never picked, though its D1 row lingers", async () => {
    const e = env(); const q = qobuz([200, GOOD], [200, { ...GOOD, url: "https://cdn.example/g.flac?etsp=" + (NOW + 3599) }]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200); // rows for a and b now exist; a served
    e.QOBUZ_ACCOUNTS = JSON.stringify([ACCOUNTS[0]]); // operator drops b from the secret; b's row stays, and b is next in LRU
    assert.equal((await handle(mintReq(43, 27), e, q, NOW + 60)).status, 200);
    assert.deepEqual(q.calls.map((c) => c.init.headers["X-User-Auth-Token"]), ["tok-a", "tok-a"]);
});

test("rotation with half the pool expired: both expired accounts end up cooled and the listener gets FLAC", async () => {
    const four = ["a", "b", "c", "d"].map((l) => ({ label: l, token: "tok-" + l, app_id: "111111111", app_secret: "s-" + l }));
    const e = env({ QOBUZ_ACCOUNTS: JSON.stringify(four) }); const q = qobuzByToken(new Set(["tok-a", "tok-b"]));
    const statuses = [];
    for (let i = 0; i < 20; i++) statuses.push((await handle(mintReq(100 + i, 27), e, q, NOW + i * 10)).status);
    // Cold start: every last_used_at is 0, so the very first miss may pair the two expired accounts.
    assert.ok(statuses.filter((s) => s === 404).length <= 1, String(statuses));
    assert.deepEqual(statuses.slice(-15), Array(15).fill(200));
    for (const l of ["a", "b"]) {
        const row = await e.DB.prepare("SELECT state, cooling_until FROM accounts WHERE label = ?1").bind(l).first();
        assert.equal(row.state, "live", l);
        assert.ok(row.cooling_until > NOW, l);
    }
});

test("refusalNamesTrack: catalog miss and Track/Sample/Format codes name the track; bare refusals and User codes don't", () => {
    for (const r of ["404", "no_url SampleRestrictedByRightHolders", "sample TrackRestrictedByRightHolders", "fmt_5 FormatRestrictedByFormatAvailability"]) {
        assert.equal(refusalNamesTrack(r), true, r);
    }
    // UserUncredentialed: a weak account answering previews (device, 2026-07-04); a User code wins over a Format one.
    for (const r of ["sample", "no_url", "fmt_5", "sample UserUncredentialed", "sample UserUncredentialed,FormatRestrictedByFormatAvailability", undefined]) {
        assert.equal(refusalNamesTrack(r), false, String(r));
    }
});

test("a transient upstream failure costs the caller's network one unit per failed Qobuz call; a refusal before any call costs nothing", async () => {
    const e = env({ IP_SALT: "salt-1" });
    const ip = "2001:db8:aa:bb00::1";
    assert.equal((await handle(mintReq(42, 27, { ip }), e, qobuz([500, ""], [500, ""]), NOW)).status, 503);
    const net = () => e.DB.raw.prepare("SELECT key, n FROM quota WHERE key LIKE 'n:%' OR key LIKE 'w:%' ORDER BY key").all().map((r) => r.n);
    assert.deepEqual(net(), [2, 2]); // two failed calls: the /56's n: and the /48's w:
    assert.equal(e.DB.raw.prepare("SELECT COUNT(*) AS c FROM quota WHERE key = 'global' OR key LIKE 'i:%'").get().c, 0);
    // Both accounts now cool: the next request is refused before any Qobuz call and costs nothing.
    assert.equal((await handle(mintReq(43, 27, { ip }), e, qobuz(), NOW + 10)).status, 503);
    assert.deepEqual(net(), [2, 2]);
    // A transient then a success: one unit for the failure, one for the answer.
    const s = env({ IP_SALT: "salt-1" });
    assert.equal((await handle(mintReq(44, 27), s, qobuz([500, ""], [200, GOOD]), NOW)).status, 200);
    assert.equal(s.DB.raw.prepare("SELECT n FROM quota WHERE key LIKE 'n:%'").get().n, 2);
});

test("a network that keeps triggering transient failures meets its daily cap: 429, and it can no longer cool the pool", async () => {
    const e = env({ IP_SALT: "salt-1", NETWORK_DAILY_CAP: "3" });
    const q = qobuz(...Array(10).fill([500, ""]));
    const statuses = [];
    for (let i = 0; i < 3; i++) statuses.push((await handle(mintReq(42 + i, 27, { install: `cooler-id-${i}`, ts: NOW + i * 400 }), e, q, NOW + i * 400)).status);
    assert.deepEqual(statuses, [503, 503, 429]); // 2 + 2 units ≥ 3
    assert.equal(q.calls.length, 4);
});

test("every account failing transiently → 503 with Retry-After after at most two attempts; both cool; the next request is a 503 with no Qobuz call", async () => {
    const e = env(); const q = qobuz([500, ""], [500, ""], [200, GOOD]);
    const r = await handle(mintReq(42, 27), e, q, NOW);
    assert.equal(r.status, 503);
    assert.equal(r.headers.get("Retry-After"), "60");
    assert.equal(q.calls.length, 2);
    assert.equal((await e.DB.prepare("SELECT COUNT(*) AS n FROM accounts WHERE cooling_until > 0").first()).n, 2);
    assert.equal((await handle(mintReq(43, 27), e, q, NOW + 10)).status, 503);
    assert.equal(q.calls.length, 2);
});

test("global daily cap → 503; per-install cap → 429; per-IP limiter → 429; none reach Qobuz", async () => {
    const q = qobuz([200, GOOD]);
    const g = env({ GLOBAL_DAILY_CAP: "1" });
    await g.DB.batch([bumpQuotaStmt(g.DB, "2026-09-01", "global")]);
    const r1 = await handle(mintReq(42, 27), g, q, NOW);
    assert.equal(r1.status, 503);
    assert.equal(r1.headers.get("Retry-After"), "600");
    const i = env({ INSTALL_DAILY_CAP: "1" });
    await i.DB.batch([bumpQuotaStmt(i.DB, "2026-09-01", "i:install-0001")]);
    assert.equal((await handle(mintReq(42, 27), i, q, NOW)).status, 429);
    const ip = env({ MINT_RL: { limit: async () => ({ success: false }) } });
    assert.equal((await handle(mintReq(42, 27), ip, q, NOW)).status, 429);
    assert.equal(q.calls.length, 0);
});

test("a URL without etsp is served but never cached", async () => {
    const e = env(); const q = qobuz([200, { ...GOOD, url: "https://cdn.example/no-expiry.flac" }], [200, GOOD]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).headers.get("X-Stash-Cache"), "MISS");
    assert.equal(q.calls.length, 2);
});

// ── Streams first: downloads only take what the day's pace can spare ─────────────────────────
// NOW is 17:00 UTC (17/24 of the day); with the default 10% headroom the pace line sits at
// ~80.8% of the global cap, so 600 × 0.808 ≈ 485 used is where downloads start waiting.

async function withGlobalUsed(e, n) {
    for (let i = 0; i < n; i++) await e.DB.batch([bumpQuotaStmt(e.DB, "2026-09-01", "global")]);
}

test("a download ahead of the day's pace waits: 429 paced with a Retry-After, no Qobuz call, no quota", async () => {
    const q = qobuz([200, GOOD]);
    const e = env({ GLOBAL_DAILY_CAP: "100" }); // pace line at 17:00 ≈ 80.8
    await withGlobalUsed(e, 90);
    const r = await handle(mintReq(42, 27, { purpose: "download" }), e, q, NOW);
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { error: "paced" });
    // 90/100 − 0.10 = 0.80 of the day = 19:12 UTC; from 17:00 that is 7920 s, plus a minute.
    assert.equal(r.headers.get("Retry-After"), "7980");
    assert.equal(q.calls.length, 0);
    assert.equal(await e.DB.prepare("SELECT n FROM quota WHERE key = 'i:install-0001'").first(), null);
});

test("a download behind the pace is served; a stream ahead of the pace is served too", async () => {
    const e = env({ GLOBAL_DAILY_CAP: "100" });
    await withGlobalUsed(e, 70);
    assert.equal((await handle(mintReq(42, 27, { purpose: "download" }), e, qobuz([200, GOOD]), NOW)).status, 200);
    const busy = env({ GLOBAL_DAILY_CAP: "100" });
    await withGlobalUsed(busy, 90);
    assert.equal((await handle(mintReq(42, 27, { purpose: "stream" }), busy, qobuz([200, GOOD]), NOW)).status, 200);
    assert.equal((await handle(mintReq(43, 27), busy, qobuz([200, GOOD]), NOW)).status, 200); // no label = stream (old clients)
});

test("a paced download still gets a cached URL: a HIT costs nothing", async () => {
    const e = env({ GLOBAL_DAILY_CAP: "100" });
    assert.equal((await handle(mintReq(42, 27), e, qobuz([200, GOOD]), NOW)).status, 200); // a stream warms the cache
    await withGlobalUsed(e, 90);
    const r = await handle(mintReq(42, 27, { purpose: "download" }), e, qobuz(), NOW);
    assert.equal(r.status, 200);
    assert.equal(r.headers.get("X-Stash-Cache"), "HIT");
});

test("the wait runs until the pace line reaches today's usage; a full pool is still 503", async () => {
    const e = env({ GLOBAL_DAILY_CAP: "100", DOWNLOAD_PACE_HEADROOM_PCT: "0" });
    await withGlobalUsed(e, 99); // 99% of the cap at 17:00: the line reaches it at 23:45:36 — still today
    assert.equal((await handle(mintReq(42, 27, { purpose: "download" }), e, qobuz(), NOW)).headers.get("Retry-After"), String(24336 + 60));
    const full = env({ GLOBAL_DAILY_CAP: "100", DOWNLOAD_PACE_HEADROOM_PCT: "0" });
    await withGlobalUsed(full, 100); // the global cap answers first (503) — pacing never masks it
    assert.equal((await handle(mintReq(42, 27, { purpose: "download" }), full, qobuz(), NOW)).status, 503);
});

// ── Visibility: a salted hash of the caller's IP rides on the install's quota row ─────────
function quotaRows(e) {
    return e.DB.raw.prepare("SELECT key, n, ip FROM quota WHERE key LIKE 'i:%' ORDER BY key").all().map((r) => ({ ...r }));
}

test("with IP_SALT set, each install's quota row carries the same short hash for the same IP; one network row, not one per request", async () => {
    const e = env({ IP_SALT: "salt-1" });
    await handle(mintReq(42, 27, { install: "install-aaaa" }), e, qobuz([200, GOOD]), NOW);
    await handle(mintReq(43, 27, { install: "install-bbbb" }), e, qobuz([200, GOOD]), NOW);
    const rows = quotaRows(e);
    assert.equal(rows.length, 2);
    assert.match(rows[0].ip, /^[0-9a-f]{16}$/);
    assert.equal(rows[0].ip, rows[1].ip); // two installs, one IP → visible as a pair
    assert.notEqual(rows[0].ip, "203.0.113.9"); // never the raw address
    assert.equal(e.DB.raw.prepare("SELECT COUNT(*) AS c FROM quota").get().c, 4); // global + 2 installs + the network's cap row
    assert.equal(e.DB.raw.prepare("SELECT n FROM quota WHERE key = ?").get("n:" + rows[0].ip).n, 2);
});

test("without IP_SALT nothing about the IP is stored", async () => {
    const e = env();
    await handle(mintReq(42, 27), e, qobuz([200, GOOD]), NOW);
    assert.equal(quotaRows(e)[0].ip, null);
    assert.equal(e.DB.raw.prepare("SELECT COUNT(*) AS c FROM quota").get().c, 2); // global + the install: no network row
});

// ── One caller can't take the whole pool (2026-10-11 audit) ─────────────────────────────────

test("networkOf: IPv4 as is; IPv6 by its /56, however it's written", () => {
    assert.equal(networkOf("203.0.113.9"), "203.0.113.9");
    assert.equal(networkOf("::ffff:203.0.113.9"), "::ffff:203.0.113.9"); // IPv4-mapped stays an IPv4 key
    assert.equal(networkOf("2001:db8:aa:bb12:1:2:3:4"), "2001:db8:aa:bb/56");
    assert.equal(networkOf("2001:DB8:AA:BBFF::9"), "2001:db8:aa:bb/56"); // same /56, other /64, other case
    assert.equal(networkOf("2001:db8::1"), "2001:db8:0:0/56");
    assert.notEqual(networkOf("2001:db8:aa:cc00::1"), networkOf("2001:db8:aa:bb00::1"));
    assert.equal(networkOf(""), "?");
});

test("network48Of: an IPv6 caller's /48, however it's written; null for IPv4 and a missing address", () => {
    assert.equal(network48Of("2001:db8:aa:bb12:1:2:3:4"), "2001:db8:aa/48");
    assert.equal(network48Of("2001:DB8:AA:FF00::9"), "2001:db8:aa/48"); // another /56 of the same /48
    assert.equal(network48Of("2001:db8::1"), "2001:db8:0/48");
    assert.notEqual(network48Of("2001:db8:ab::1"), network48Of("2001:db8:aa::1"));
    assert.equal(network48Of("203.0.113.9"), null);
    assert.equal(network48Of("::ffff:203.0.113.9"), null);
    assert.equal(network48Of(""), null);
});

test("a /48's aggregate cap stops a script hopping /56s inside one tunnel-broker /48: 429, no Qobuz call; other /48s and IPv4 unaffected", async () => {
    const e = env({ IP_SALT: "salt-1", NETWORK_DAILY_CAP: "2", NETWORK48_DAILY_CAP: "3" });
    const q = qobuzByToken(new Set());
    // Three different /56s of 2001:db8:aa::/48, one mint each: every /56 is under its own cap of 2.
    for (let i = 0; i < 3; i++) {
        assert.equal((await handle(mintReq(100 + i, 27, { install: `hop-install-${i}`, ip: `2001:db8:aa:${i}100::1` }), e, q, NOW + i)).status, 200);
    }
    const r = await handle(mintReq(200, 27, { install: "hop-install-x", ip: "2001:db8:aa:4400::1" }), e, q, NOW + 10); // a fresh /56, same /48
    assert.equal(r.status, 429);
    assert.equal(r.headers.get("Retry-After"), "3600");
    assert.equal(q.calls.length, 3);
    assert.equal(e.DB.raw.prepare("SELECT n FROM quota WHERE key LIKE 'w:%'").get().n, 3);
    assert.equal((await handle(mintReq(201, 27, { install: "next-door", ip: "2001:db8:ab::1" }), e, q, NOW + 11)).status, 200);
    assert.equal((await handle(mintReq(202, 27, { install: "v4-caller", ip: "198.51.100.7" }), e, q, NOW + 12)).status, 200);
    // IPv4 callers never get a /48 row.
    assert.equal(e.DB.raw.prepare("SELECT COUNT(*) AS c FROM quota WHERE key LIKE 'w:%'").get().c, 2);
});

test("the /48 cap defaults to four network caps", async () => {
    const e = env({ IP_SALT: "salt-1", NETWORK_DAILY_CAP: "1" });
    const q = qobuzByToken(new Set());
    const statuses = [];
    for (let i = 0; i < 5; i++) statuses.push((await handle(mintReq(100 + i, 27, { install: `hop-install-${i}`, ip: `2001:db8:aa:${i}100::1` }), e, q, NOW + i)).status);
    assert.deepEqual(statuses, [200, 200, 200, 200, 429]);
});

test("a /48's free-404 allowance is aggregated too: hopping /56s doesn't multiply it", async () => {
    const e = env({ IP_SALT: "salt-1", NETWORK_FREE_404_DAILY: "2", NETWORK48_FREE_404_DAILY: "3" });
    const q = qobuz(...Array(10).fill([404, ""]));
    for (let i = 0; i < 4; i++) assert.equal((await handle(mintReq(1000 + i, 27, { install: `hop-install-${i}`, ip: `2001:db8:aa:${i}100::1` }), e, q, NOW + i)).status, 404);
    assert.equal(e.DB.raw.prepare("SELECT n FROM quota WHERE key LIKE 'wf:%'").get().n, 3);
    // The fourth, past the /48's allowance, counted like any answer: on its /56's and its /48's caps.
    assert.equal(e.DB.raw.prepare("SELECT n FROM quota WHERE key LIKE 'w:%'").get().n, 1);
    assert.equal(e.DB.raw.prepare("SELECT COUNT(*) AS c FROM quota WHERE key LIKE 'n:%'").get().c, 1);
});

test("without IP_SALT the network caps stay off, and the relay warns once per isolate", async () => {
    const fresh = await import(new URL("../src/index.js?no-salt-warning", import.meta.url));
    const warned = [];
    const orig = console.warn;
    console.warn = (...a) => warned.push(a.join(" "));
    try {
        const e = env({ NETWORK_DAILY_CAP: "1" });
        assert.equal((await fresh.handle(mintReq(42, 27), e, qobuz([200, GOOD]), NOW)).status, 200);
        assert.equal((await fresh.handle(mintReq(43, 27), e, qobuz([200, GOOD]), NOW)).status, 200); // no network cap
    } finally {
        console.warn = orig;
    }
    assert.equal(warned.length, 1);
    assert.match(warned[0], /IP_SALT/);
});

test("the per-IP limiter counts an IPv6 caller by its /56: rotating addresses inside it shares one bucket", async () => {
    const keys = [];
    const e = env({ MINT_RL: { limit: async ({ key }) => { keys.push(key); return { success: true }; } } });
    await handle(mintReq(42, 27, { ip: "2001:db8:aa:bb01::1" }), e, qobuz([200, GOOD]), NOW);
    await handle(mintReq(42, 27, { ip: "2001:db8:aa:bbfe:dead:beef:0:1" }), e, qobuz(), NOW);
    await handle(mintReq(42, 27, { ip: "203.0.113.9" }), e, qobuz(), NOW);
    assert.deepEqual(keys, ["2001:db8:aa:bb/56", "2001:db8:aa:bb/56", "203.0.113.9"]);
});

test("a network's daily cap stops a script rotating install ids (and IPv6 addresses in its /56): 429, no Qobuz call", async () => {
    const e = env({ IP_SALT: "salt-1", NETWORK_DAILY_CAP: "3" });
    const q = qobuzByToken(new Set());
    for (let i = 0; i < 3; i++) {
        const r = await handle(mintReq(100 + i, 27, { install: `rotating-${i}`, ip: `2001:db8:aa:bb${i}0::${i}` }), e, q, NOW + i);
        assert.equal(r.status, 200);
    }
    const r = await handle(mintReq(200, 27, { install: "rotating-x", ip: "2001:db8:aa:bbff::1" }), e, q, NOW + 10);
    assert.equal(r.status, 429);
    assert.equal(r.headers.get("Retry-After"), "3600");
    assert.equal(q.calls.length, 3);
    // Another network, and a cache HIT from the capped one, still work.
    assert.equal((await handle(mintReq(300, 27, { install: "someone-else", ip: "198.51.100.7" }), e, q, NOW + 11)).status, 200);
    assert.equal((await handle(mintReq(100, 27, { install: "rotating-y", ip: "2001:db8:aa:bb00::5" }), e, q, NOW + 12)).headers.get("X-Stash-Cache"), "HIT");
});

test("a Qobuz 404 (an id it doesn't know) spares the network's cap but spends the account's slot and the global cap: it was a real Qobuz call", async () => {
    const e = env({ IP_SALT: "salt-1" });
    const q = qobuz([404, ""], [404, ""]);
    assert.equal((await handle(mintReq(987654321, 27), e, q, NOW)).status, 404);
    assert.equal((await handle(mintReq(987654322, 27), e, q, NOW + 1)).status, 404);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 2);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'i:install-0001'").first()).n, 2);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key LIKE 'nf:%'").first()).n, 2);
    assert.equal(await e.DB.prepare("SELECT n FROM quota WHERE key LIKE 'n:%'").first(), null);
    const accts = (await e.DB.prepare("SELECT label, hour_n, day_n FROM accounts ORDER BY label").all()).results;
    assert.deepEqual(accts, [{ label: "a", hour_n: 1, day_n: 1 }, { label: "b", hour_n: 1, day_n: 1 }]);
});

test("made-up ids are bounded by the account caps and the global cap: once the pool is spent, 404 probes get 503 with no Qobuz call", async () => {
    // Many networks, each inside its own free-404 allowance: before 2026-10-17 nothing bounded this.
    const e = env({ IP_SALT: "salt-1", ACCOUNT_HOURLY_CAP: "2" });
    const q = qobuz(...Array(20).fill([404, ""]));
    const statuses = [];
    for (let i = 0; i < 6; i++) statuses.push((await handle(mintReq(5000 + i, 27, { install: `probe-net-${i}`, ip: `198.51.100.${i + 1}` }), e, q, NOW + i)).status);
    assert.deepEqual(statuses, [404, 404, 404, 404, 503, 503]); // 2 accounts × 2 an hour
    assert.equal(q.calls.length, 4);
    const g = env({ IP_SALT: "salt-1", GLOBAL_DAILY_CAP: "3" });
    const gq = qobuz(...Array(20).fill([404, ""]));
    const gs = [];
    for (let i = 0; i < 5; i++) gs.push((await handle(mintReq(5000 + i, 27, { install: `probe-net-${i}`, ip: `198.51.100.${i + 1}` }), g, gq, NOW + i)).status);
    assert.deepEqual(gs, [404, 404, 404, 503, 503]);
    assert.equal(gq.calls.length, 3);
});

test("random ids can't burn the pool: past its allowance a network's 404s count again, and its daily cap ends them", async () => {
    const e = env({ IP_SALT: "salt-1", GLOBAL_DAILY_CAP: "10", NETWORK_DAILY_CAP: "3", NETWORK_FREE_404_DAILY: "2" });
    const q = qobuz(...Array(20).fill([404, ""]));
    const statuses = [];
    for (let i = 0; i < 8; i++) statuses.push((await handle(mintReq(1000 + i, 27, { install: `junk-id-${i}` }), e, q, NOW + i)).status);
    // 2 free, 3 counted, then the network cap (429). All five reached Qobuz, so all five spent the global cap.
    assert.deepEqual(statuses, [404, 404, 404, 404, 404, 429, 429, 429]);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 5);
    // Everyone else still gets FLAC.
    assert.equal((await handle(mintReq(42, 27, { ip: "198.51.100.7" }), e, qobuz([200, GOOD]), NOW + 20)).status, 200);
});

test("without IP_SALT there is no network tally, so a Qobuz 404 counts as before", async () => {
    const e = env();
    assert.equal((await handle(mintReq(987654321, 27), e, qobuz([404, ""]), NOW)).status, 404);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 1);
});

test("a rights refusal (a real track) still spends the global and network caps: only Qobuz's 404 spares the network", async () => {
    const e = env({ IP_SALT: "salt-1" });
    assert.equal((await handle(mintReq(42, 27), e, qobuz([200, RIGHTS_LOCK]), NOW)).status, 404);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'global'").first()).n, 1);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key LIKE 'n:%'").first()).n, 1);
});

// ── Failures answer the contract's 503, never a bare 500 ───────────────────────────────────

test("a body that stalls after the headers cools that account and the next one serves the request", async () => {
    const e = env();
    let n = 0;
    const q = async () => (n++ === 0
        ? new Response(new ReadableStream({ start(c) { c.error(Object.assign(new Error("t"), { name: "TimeoutError" })); } }), { status: 200 })
        : new Response(JSON.stringify(GOOD), { status: 200 }));
    const r = await handle(mintReq(42, 27), e, q, NOW);
    assert.equal(r.status, 200);
    assert.equal((await e.DB.prepare("SELECT cooling_until FROM accounts WHERE label = 'a'").first()).cooling_until, NOW + 300);
});

test("a D1 error mid-request is a 503 busy with Retry-After, not an uncaught throw", async () => {
    const e = env();
    e.DB = { prepare() { throw new Error("D1_ERROR: database is overloaded"); }, batch: async () => { throw new Error("D1_ERROR"); } };
    const r = await handle(mintReq(42, 27), e, qobuz([200, GOOD]), NOW);
    assert.equal(r.status, 503);
    assert.equal(r.headers.get("Retry-After"), "60");
    assert.deepEqual(await r.json(), { error: "busy" });
    // A write failing after Qobuz answered is the same 503.
    const late = env();
    late.DB.batch = async () => { throw new Error("D1_ERROR"); };
    assert.equal((await handle(mintReq(43, 27), late, qobuz([200, GOOD]), NOW)).status, 503);
});

// ---- Daily lossless counter (QUOTA_DB, shared with the Tidal relay; real SQLite with the shared schema) ----

const RESET = 1788307200; // the UTC midnight after NOW (2026-09-02T00:00:00Z)
const quotaEnv = (over = {}) => env({ QUOTA_DB: fakeD1(QUOTA_MIGRATIONS), ...over });
const usage = (e, subject = "i:install-0001") => e.QUOTA_DB.raw.prepare("SELECT n FROM usage WHERE day = '2026-09-01' AND subject = ?").get(subject)?.n ?? 0;
function quotaReq({ key = KEY, install = "install-0001", ts = NOW, signed = true, msg } = {}) {
    const h = { "CF-Connecting-IP": "203.0.113.9" };
    if (signed) {
        h["X-Stash-Install"] = install;
        h["X-Stash-Ts"] = String(ts);
        h["X-Stash-Auth"] = createHmac("sha256", key).update(msg ?? `${install}:quota:${ts}`).digest("hex");
    }
    return new Request(`${BASE}/v1/quota`, { headers: h });
}

test("without QUOTA_DB the counter is off: answers unchanged, no counter headers", async () => {
    const r = await handle(mintReq(42, 27), env(), qobuz([200, GOOD]), NOW);
    assert.equal(r.status, 200);
    assert.equal(r.headers.get("X-Stash-Lossless"), null);
    assert.equal(r.headers.get("X-Stash-Lossless-Reset"), null);
});

test("a lossless MISS and every cache HIT count one unit for the install, with the counter headers", async () => {
    const e = quotaEnv(); const q = qobuz([200, GOOD]);
    const miss = await handle(mintReq(42, 27), e, q, NOW);
    assert.equal(miss.headers.get("X-Stash-Cache"), "MISS");
    assert.equal(miss.headers.get("X-Stash-Lossless"), "1/300");
    assert.equal(miss.headers.get("X-Stash-Lossless-Reset"), String(RESET));
    const hit = await handle(mintReq(42, 27), e, q, NOW + 5);
    assert.equal(hit.headers.get("X-Stash-Cache"), "HIT");
    assert.equal(hit.headers.get("X-Stash-Lossless"), "2/300");
    const other = await handle(mintReq(42, 27, { install: "install-0002" }), e, q, NOW + 6);
    assert.equal(other.headers.get("X-Stash-Lossless"), "1/300", "per install");
    assert.equal(usage(e), 2);
    assert.equal(usage(e, "i:install-0002"), 1);
});

/** QUOTA_DB whose writes (the counter's INSERT … RETURNING) wait until open(): reads pass straight through. */
function gatedWrites(db) {
    let open;
    const gate = new Promise((r) => { open = r; });
    const prepare = (sql) => {
        const s = db.prepare(sql);
        if (!/^\s*INSERT/i.test(sql)) return s;
        const g = { bind: (...a) => { s.bind(...a); return g; }, first: async () => { await gate; return s.first(); }, all: async () => { await gate; return s.all(); } };
        return g;
    };
    return { db: { ...db, prepare }, open };
}

test("the counter's bump never holds up the answer: it rides on ctx.waitUntil and the header reports used + 1", async () => {
    const e = quotaEnv(); const q = qobuz([200, GOOD]);
    await handle(mintReq(42, 27), e, q, NOW); // one unit already spent
    for (const cache of ["HIT", "MISS"]) {
        const ctx = execCtx();
        const gated = gatedWrites(e.QUOTA_DB);
        const ge = { ...e, QUOTA_DB: gated.db };
        const track = cache === "HIT" ? 42 : 43;
        const fq = qobuz([200, { ...GOOD, url: "https://cdn.example/g.flac?etsp=" + (NOW + 3599) }]);
        const r = await handleWithCtx(mintReq(track, 27), ge, fq, NOW + 1, ctx); // resolves with the write still blocked
        assert.equal(r.status, 200);
        assert.equal(r.headers.get("X-Stash-Cache"), cache);
        assert.equal(r.headers.get("X-Stash-Lossless"), "2/300", cache); // the read said 1; this answer makes 2
        assert.equal(ctx.pending.length, 1, cache);
        assert.equal(usage(e), 1, `${cache}: not written yet`);
        gated.open();
        await ctx.settle();
        assert.equal(usage(e), 2, `${cache}: written after the answer`);
        e.QUOTA_DB.raw.prepare("UPDATE usage SET n = 1").run(); // back to one for the next round
    }
    // A direct caller with no ctx still gets the unit written before handle() returns.
    const direct = await handleWithCtx(mintReq(42, 27), e, qobuz(), NOW + 2);
    assert.equal(direct.headers.get("X-Stash-Lossless"), "2/300");
    assert.equal(usage(e), 2);
});

test("a supporter's deferred bump writes both rows and the header reads <used + 1>/unlimited", async () => {
    const e = quotaEnv({ FREE_LOSSLESS_DAILY: "1" });
    const original = hooks.entitlementOf;
    hooks.entitlementOf = async () => ({ tid: "0123456789abcdef0123456789abcdef" });
    try {
        const ctx = execCtx();
        const r = await handleWithCtx(mintReq(42, 27), e, qobuz([200, GOOD]), NOW, ctx);
        assert.equal(r.headers.get("X-Stash-Lossless"), "1/unlimited");
        await ctx.settle();
        assert.equal(usage(e), 1);
        assert.equal(usage(e, "t:0123456789abcdef0123456789abcdef"), 1);
    } finally { hooks.entitlementOf = original; }
});

test("not counted: Data Saver's format 5, a Qobuz 404, a busy 503, a bad signature", async () => {
    const e = quotaEnv();
    assert.equal((await handle(mintReq(42, 5), e, qobuz(), NOW)).status, 404);
    const miss = await handle(mintReq(43, 27), e, qobuz([404, {}]), NOW);
    assert.equal(miss.status, 404);
    assert.equal(miss.headers.get("X-Stash-Lossless"), null);
    assert.equal((await handle(mintReq(44, 27), e, qobuz([500, ""], [500, ""]), NOW)).status, 503);
    assert.equal((await handle(mintReq(45, 27, { key: "bogus" }), e, qobuz([200, GOOD]), NOW)).status, 401);
    assert.equal(usage(e), 0);
});

test("at FREE_LOSSLESS_DAILY: 429 daily_lossless_cap with Retry-After to midnight UTC, before the cache or Qobuz", async () => {
    const e = quotaEnv({ FREE_LOSSLESS_DAILY: "2" }); const q = qobuz([200, GOOD]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200); // HIT, second unit
    const capped = await handle(mintReq(42, 27), e, q, NOW + 10);
    assert.equal(capped.status, 429);
    assert.deepEqual(await capped.json(), { error: "daily_lossless_cap", used: 2, limit: 2, resetsAt: RESET });
    assert.equal(capped.headers.get("Retry-After"), String(RESET - NOW - 10));
    assert.equal(capped.headers.get("X-Stash-Lossless"), "2/2");
    assert.equal(capped.headers.get("X-Stash-Lossless-Reset"), String(RESET));
    assert.equal((await handle(mintReq(99, 27), e, q, NOW + 11)).status, 429, "a miss too");
    assert.equal(q.calls.length, 1);
    assert.equal(usage(e), 2, "a refused answer costs nothing");
    // Another install is unaffected; the next UTC day starts at 0.
    assert.equal((await handle(mintReq(42, 27, { install: "install-0002" }), e, q, NOW + 12)).status, 200);
    const tomorrow = await handle(mintReq(42, 27, { ts: RESET + 5 }), e, qobuz([200, { ...GOOD, url: "https://cdn.example/f.flac?etsp=" + (RESET + 3600) }]), RESET + 5);
    assert.equal(tomorrow.status, 200);
    assert.equal(tomorrow.headers.get("X-Stash-Lossless"), "1/2");
});

test("the install's old INSTALL_DAILY_CAP still applies alongside the shared counter", async () => {
    const e = quotaEnv({ INSTALL_DAILY_CAP: "1" });
    const q = qobuz([200, GOOD], [200, { ...GOOD, url: "https://cdn.example/g.flac?etsp=" + (NOW + 3599) }]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200);
    const r = await handle(mintReq(43, 27), e, q, NOW + 1);
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { error: "rate_limited" });
});

test("a supporter entitlement skips the cap: served past the limit, header <used>/unlimited", async () => {
    const e = quotaEnv({ FREE_LOSSLESS_DAILY: "1" }); const q = qobuz([200, GOOD]);
    assert.equal((await handle(mintReq(42, 27), e, q, NOW)).status, 200);
    const original = hooks.entitlementOf;
    hooks.entitlementOf = async () => ({ tier: "supporter", tid: "t1" });
    try {
        const r = await handle(mintReq(42, 27), e, q, NOW + 1);
        assert.equal(r.status, 200);
        assert.equal(r.headers.get("X-Stash-Lossless"), "2/unlimited");
        const s = await handle(quotaReq({ ts: NOW + 2 }), e, q, NOW + 2);
        assert.deepEqual(await s.json(), { used: 2, limit: null, resetsAt: RESET, supporter: true });
    } finally { hooks.entitlementOf = original; }
});

test("GET /v1/quota: signed '<install>:quota:<ts>', today's count with the headers, never counted", async () => {
    const e = quotaEnv(); const q = qobuz([200, GOOD]);
    await handle(mintReq(42, 27), e, q, NOW);
    for (let i = 0; i < 2; i++) {
        const r = await handle(quotaReq(), e, q, NOW);
        assert.equal(r.status, 200);
        assert.deepEqual(await r.json(), { used: 1, limit: 300, resetsAt: RESET, supporter: false });
        assert.equal(r.headers.get("X-Stash-Lossless"), "1/300");
        assert.equal(r.headers.get("X-Stash-Lossless-Reset"), String(RESET));
    }
    assert.deepEqual(await (await handle(quotaReq({ install: "install-0009" }), e, q, NOW)).json(), { used: 0, limit: 300, resetsAt: RESET, supporter: false });
    assert.equal(usage(e), 1);
});

test("MIN_APP_BUILD rejects missing, malformed and old mint builds before quota or Qobuz; quota stays open", async () => {
    const e = quotaEnv({ MIN_APP_BUILD: "157" });
    const q = qobuz([200, GOOD]);
    for (const build of [null, "157x", "156"]) {
        const original = mintReq(42, 27);
        const headers = new Headers(original.headers);
        if (build !== null) headers.set("X-Stash-App-Build", build);
        const response = await handle(new Request(original, { headers }), e, q, NOW);
        assert.equal(response.status, 426);
        assert.deepEqual(await response.json(), { error: "app_too_old", min_build: 157 });
    }
    assert.equal(q.calls.length, 0);
    assert.equal(usage(e), 0);

    const current = mintReq(42, 27);
    const currentHeaders = new Headers(current.headers);
    currentHeaders.set("X-Stash-App-Build", "157");
    assert.equal((await handle(new Request(current, { headers: currentHeaders }), e, q, NOW)).status, 200);
    const quota = await handle(quotaReq(), e, q, NOW);
    assert.equal(quota.status, 200);
});

test("GET /v1/quota refuses unsigned, wrong-key, skewed and mint-signed requests (401 + server time)", async () => {
    const e = quotaEnv({ RELAY_KEY: "new", RELAY_KEY_PREV: KEY });
    for (const opts of [{ signed: false }, { key: "bogus" }, { ts: NOW - 301 }, { msg: `install-0001:42:27:${NOW}` }]) {
        const r = await handle(quotaReq(opts), e, qobuz(), NOW);
        assert.equal(r.status, 401, JSON.stringify(opts));
        assert.equal(r.headers.get("X-Stash-Server-Time"), String(NOW));
    }
    assert.equal((await handle(quotaReq({ key: KEY }), e, qobuz(), NOW)).status, 200, "the previous key still works");
});

test("GET /v1/quota: 404 quota_off without QUOTA_DB, behind MINT_RL, 500 without RELAY_KEY", async () => {
    const off = await handle(quotaReq(), env(), qobuz(), NOW);
    assert.equal(off.status, 404);
    assert.deepEqual(await off.json(), { error: "quota_off" });
    const limited = await handle(quotaReq(), quotaEnv({ MINT_RL: { limit: async () => ({ success: false }) } }), qobuz(), NOW);
    assert.equal(limited.status, 429);
    assert.equal((await handle(quotaReq(), quotaEnv({ RELAY_KEY: undefined }), qobuz(), NOW)).status, 500);
});

test("a QUOTA_DB failure never stops a mint: served without counter headers", async () => {
    const e = env({ QUOTA_DB: { prepare: () => { throw new Error("D1 down"); } } });
    const r = await handle(mintReq(42, 27), e, qobuz([200, GOOD]), NOW);
    assert.equal(r.status, 200);
    assert.equal(r.headers.get("X-Stash-Lossless"), null);
});

/** Runs `fn` with console.error captured; returns the lines it logged. */
async function errorsDuring(fn) {
    const lines = [];
    const orig = console.error;
    console.error = (...a) => lines.push(a.map(String).join(" "));
    try { await fn(); } finally { console.error = orig; }
    return lines;
}

test("QUOTA_DB bound but never migrated: mints fail open with the counter off, and it is logged once, not per request", async () => {
    const e = quotaEnv();
    e.QUOTA_DB.raw.exec("DROP TABLE usage; DROP TABLE revoked_tokens");
    const q = qobuzByToken(new Set());
    const lines = await errorsDuring(async () => {
        for (let i = 0; i < 5; i++) {
            const r = await handle(mintReq(100 + i, 27), e, q, NOW + i);
            assert.equal(r.status, 200);
            assert.equal(r.headers.get("X-Stash-Lossless"), null);
        }
        const s = await handle(quotaReq(), e, q, NOW);
        assert.equal(s.status, 404);
        assert.deepEqual(await s.json(), { error: "quota_off" });
    });
    assert.equal(lines.filter((l) => /quota read failed/.test(l)).length, 1, lines.join("\n"));
    assert.equal(lines.filter((l) => /stash-quota has no usage and revoked_tokens table/.test(l)).length, 1, lines.join("\n"));
    assert.equal(lines.length, 2, lines.join("\n"));
});

test("only revoked_tokens missing: free requests are still counted; a supporter's read fails open, logged once", async () => {
    const e = quotaEnv();
    e.QUOTA_DB.raw.exec("DROP TABLE revoked_tokens");
    const q = qobuzByToken(new Set());
    const original = hooks.entitlementOf;
    let lines;
    try {
        lines = await errorsDuring(async () => {
            assert.equal((await handle(mintReq(100, 27), e, q, NOW)).headers.get("X-Stash-Lossless"), "1/300");
            hooks.entitlementOf = async () => ({ tid: "0123456789abcdef0123456789abcdef" });
            for (let i = 1; i <= 3; i++) {
                const r = await handle(mintReq(100 + i, 27), e, q, NOW + i);
                assert.equal(r.status, 200);
                assert.equal(r.headers.get("X-Stash-Lossless"), null, "a supporter read can't run: counter off");
            }
            hooks.entitlementOf = async () => null;
            assert.equal((await handle(mintReq(200, 27), e, q, NOW + 10)).headers.get("X-Stash-Lossless"), "2/300", "free requests still counted");
        });
    } finally { hooks.entitlementOf = original; }
    assert.equal(lines.filter((l) => /quota read failed/.test(l)).length, 1, lines.join("\n"));
    assert.equal(lines.filter((l) => /stash-quota has no revoked_tokens table/.test(l)).length, 1, lines.join("\n"));
    assert.equal(lines.length, 2, lines.join("\n"));
});

test("a transient QUOTA_DB failure with the tables present is not mistaken for missing tables: the next read counts", async () => {
    const e = quotaEnv();
    const real = e.QUOTA_DB.prepare.bind(e.QUOTA_DB);
    let failNext = true;
    e.QUOTA_DB.prepare = (sql) => {
        if (failNext && /FROM usage/.test(sql)) { failNext = false; throw new Error("D1_ERROR: overloaded"); }
        return real(sql);
    };
    const q = qobuzByToken(new Set());
    const lines = await errorsDuring(async () => {
        assert.equal((await handle(mintReq(100, 27), e, q, NOW)).headers.get("X-Stash-Lossless"), null);
        assert.equal((await handle(mintReq(101, 27), e, q, NOW + 1)).headers.get("X-Stash-Lossless"), "1/300");
    });
    assert.deepEqual(lines.filter((l) => /stash-quota has no/.test(l)), []);
});

test("the hourly cron also prunes week-old usage rows", async () => {
    const e = quotaEnv();
    const put = e.QUOTA_DB.raw.prepare("INSERT INTO usage (day, subject, n) VALUES (?, 'i:x', 1)");
    put.run("2000-01-01");
    put.run(new Date().toISOString().slice(0, 10));
    await worker.scheduled({}, e);
    assert.equal(e.QUOTA_DB.raw.prepare("SELECT COUNT(*) AS n FROM usage").get().n, 1);
});

test("the cron's two prunes are independent: a relay-DB failure still prunes usage (and is rethrown), a QUOTA_DB failure still prunes the relay DB", async () => {
    const today = new Date().toISOString().slice(0, 10);
    const errors = [];
    const orig = console.error;
    console.error = (...a) => errors.push(a.join(" "));
    try {
        const e = quotaEnv();
        e.DB.batch = async () => { throw new Error("D1_ERROR: relay DB overloaded"); };
        e.QUOTA_DB.raw.prepare("INSERT INTO usage (day, subject, n) VALUES ('2000-01-01', 'i:x', 1)").run();
        await assert.rejects(worker.scheduled({}, e), /relay DB overloaded/);
        assert.equal(e.QUOTA_DB.raw.prepare("SELECT COUNT(*) AS n FROM usage").get().n, 0, "usage pruned anyway");
        assert.ok(errors.some((m) => /scheduled prune failed: D1_ERROR: relay DB overloaded/.test(m)));

        const q = env({ QUOTA_DB: { prepare: () => { throw new Error("quota D1 down"); } } });
        q.DB.raw.prepare("INSERT INTO quota (day, key, n) VALUES ('2000-01-01', 'global', 1), (?, 'global', 1)").run(today);
        await worker.scheduled({}, q); // pruneUsage logs its own failure and never throws
        assert.equal(q.DB.raw.prepare("SELECT COUNT(*) AS n FROM quota").get().n, 1, "relay DB pruned anyway");
        assert.ok(errors.some((m) => /quota prune failed: quota D1 down/.test(m)));
    } finally { console.error = orig; }
});

// ---- Supporter tokens (X-Stash-Entitlement) end to end, with a test keypair ----

const KEYS = keypair();
const supporter = (over = {}) => token(KEYS.privateKey, { iat: NOW - 60, ...over });
const withToken = (req, t) => { const r = new Request(req); r.headers.set("X-Stash-Entitlement", t); return r; };
const supporterEnv = (over = {}) => quotaEnv({ ENTITLEMENT_PUBLIC_KEY: KEYS.publicKey, FREE_LOSSLESS_DAILY: "1", INSTALL_DAILY_CAP: "1", ...over });
/** Track `id` minted fresh every time (no cache), so every answer is a Qobuz call. */
const fresh = (n) => qobuz(...Array.from({ length: n }, (_, i) => [200, { ...GOOD, url: `https://cdn.example/${i}.flac?etsp=${NOW + 3599}` }]));

test("a valid token: unlimited past FREE_LOSSLESS_DAILY and INSTALL_DAILY_CAP, header <used>/unlimited, both rows counted", async () => {
    const e = supporterEnv(); const t = supporter(); const q = fresh(4);
    for (let i = 1; i <= 4; i++) {
        const r = await handle(withToken(mintReq(100 + i, 27), t), e, q, NOW);
        assert.equal(r.status, 200, `mint ${i}`);
        assert.equal(r.headers.get("X-Stash-Lossless"), `${i}/unlimited`);
    }
    assert.equal(usage(e), 4);
    assert.equal(usage(e, "t:" + tidOf(t)), 4);
    assert.equal((await e.DB.prepare("SELECT n FROM quota WHERE key = 'i:install-0001'").first()).n, 4, "the relay's install row still counts");
    // Without the token the same install is over both caps again.
    const plain = await handle(mintReq(200, 27), e, fresh(1), NOW);
    assert.equal(plain.status, 429);
    assert.equal((await plain.json()).error, "daily_lossless_cap");
});

test("a bad token is never an error: bad signature, another key, garbage, wrong tier all get the normal quota", async () => {
    const other = keypair();
    const good = supporter();
    const [head, sig] = good.split(".");
    const flipped = Buffer.from(sig, "base64url"); flipped[5] ^= 1;
    for (const t of [`${head}.${flipped.toString("base64url")}`, token(other.privateKey, { iat: NOW - 60 }), "garbage", supporter({ tier: "free" })]) {
        const e = supporterEnv({ INSTALL_DAILY_CAP: "100" });
        const first = await handle(withToken(mintReq(42, 27), t), e, qobuz([200, GOOD]), NOW);
        assert.equal(first.status, 200, t);
        assert.equal(first.headers.get("X-Stash-Lossless"), "1/1", t);
        const second = await handle(withToken(mintReq(42, 27), t), e, qobuz(), NOW);
        assert.equal(second.status, 429, t);
        assert.equal((await second.json()).error, "daily_lossless_cap", t);
    }
});

test("a revoked tid gets the normal quota", async () => {
    const e = supporterEnv({ INSTALL_DAILY_CAP: "100" }); const t = supporter(); const q = fresh(2);
    assert.equal((await handle(withToken(mintReq(42, 27), t), e, q, NOW)).status, 200);
    e.QUOTA_DB.raw.prepare("INSERT INTO revoked_tokens (tid, revoked_at, reason) VALUES (?, ?, 'shared publicly')").run(tidOf(t), NOW);
    const r = await handle(withToken(mintReq(43, 27), t), e, q, NOW + 1);
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { error: "daily_lossless_cap", used: 1, limit: 1, resetsAt: RESET });
    const s = await handle(withToken(quotaReq({ ts: NOW + 2 }), t), e, q, NOW + 2);
    assert.deepEqual(await s.json(), { used: 1, limit: 1, resetsAt: RESET, supporter: false });
});

test("SUPPORTER_DAILY_CAP: at the token's ceiling the install is back on its free 500 and the relay's install cap", async () => {
    const e = supporterEnv({ SUPPORTER_DAILY_CAP: "3", FREE_LOSSLESS_DAILY: "5", INSTALL_DAILY_CAP: "4" }); const t = supporter(); const q = fresh(5);
    for (let i = 1; i <= 3; i++) assert.equal((await handle(withToken(mintReq(100 + i, 27), t), e, q, NOW)).headers.get("X-Stash-Lossless"), `${i}/unlimited`);
    const asFree = await handle(withToken(mintReq(104, 27), t), e, q, NOW);
    assert.equal(asFree.status, 200);
    assert.equal(asFree.headers.get("X-Stash-Lossless"), "4/5", "the install's own count and the free limit");
    assert.equal(usage(e, "t:" + tidOf(t)), 3, "a free answer leaves the token's row alone");
    const capped = await handle(withToken(mintReq(105, 27), t), e, q, NOW);
    assert.equal(capped.status, 429);
    assert.deepEqual(await capped.json(), { error: "rate_limited" }, "INSTALL_DAILY_CAP applies again");
    assert.deepEqual(await (await handle(withToken(quotaReq(), t), e, q, NOW)).json(), { used: 4, limit: 5, resetsAt: RESET, supporter: false });
});

test("supporters still meet the network cap", async () => {
    const e = supporterEnv({ IP_SALT: "salt", NETWORK_DAILY_CAP: "2" }); const t = supporter(); const q = fresh(3);
    assert.equal((await handle(withToken(mintReq(101, 27), t), e, q, NOW)).status, 200);
    assert.equal((await handle(withToken(mintReq(102, 27), t), e, q, NOW)).status, 200);
    const r = await handle(withToken(mintReq(103, 27), t), e, q, NOW);
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { error: "rate_limited" });
});

test("GET /v1/quota with a valid token: supporter true, limit null, header <used>/unlimited", async () => {
    const e = supporterEnv(); const t = supporter();
    await handle(withToken(mintReq(42, 27), t), e, qobuz([200, GOOD]), NOW);
    const r = await handle(withToken(quotaReq(), t), e, qobuz(), NOW);
    assert.equal(r.status, 200);
    assert.deepEqual(await r.json(), { used: 1, limit: null, resetsAt: RESET, supporter: true });
    assert.equal(r.headers.get("X-Stash-Lossless"), "1/unlimited");
    assert.deepEqual(await (await handle(quotaReq(), e, qobuz(), NOW)).json(), { used: 1, limit: 1, resetsAt: RESET, supporter: false }, "without the token");
    assert.equal(usage(e), 1);
});
