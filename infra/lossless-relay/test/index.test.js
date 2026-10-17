import { test } from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { fakeD1 } from "./fake-d1.js";
import worker, { handle, networkOf, network48Of, refusalNamesTrack } from "../src/index.js";
import { bumpQuotaStmt } from "../src/db.js";

const NOW = 1788282000; // 2026-09-01T17:00:00Z
const KEY = "k-secret";
const BASE = "https://relay.test";
const ACCOUNTS = [
    { label: "a", token: "tok-a", app_id: "111111111", app_secret: "s-a" },
    { label: "b", token: "tok-b", app_id: "111111111", app_secret: "s-b" },
];
const GOOD = { url: "https://cdn.example/f.flac?etsp=" + (NOW + 3599), format_id: 7, bit_depth: 24, sampling_rate: 96 };

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
