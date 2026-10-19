import test from "node:test";
import assert from "node:assert/strict";
import { createHmac } from "node:crypto";
import { readFileSync, readdirSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker from "../src/index.js";
import { verifySigned } from "../src/auth.js";
import { hooks, nextResetSec } from "../src/quota.js";
import { keypair, token, tidOf } from "./entitlement-keys.js";

/**
 * The daily lossless counter against real SQLite: the relay's own migrations for DB, and the shared stash-quota
 * schema for QUOTA_DB (test/fixtures/quota_*.sql, copies of infra/lossless-quota/migrations/*.sql, applied in order).
 */
const ACCESS_TOKEN = "test-access-token";
const KEY = "k-secret";
const INSTALL = "install-0001";
const MIGRATIONS = new URL("../migrations/", import.meta.url);
const now = () => Math.floor(Date.now() / 1000);
const today = () => new Date().toISOString().slice(0, 10);

function relayDb() {
  const db = new DatabaseSync(":memory:");
  for (const file of readdirSync(MIGRATIONS).filter((f) => f.endsWith(".sql")).sort()) db.exec(readFileSync(new URL(file, MIGRATIONS), "utf8"));
  return db;
}
function quotaDb() {
  const db = new DatabaseSync(":memory:");
  const dir = new URL("./fixtures/", import.meta.url);
  for (const file of readdirSync(dir).filter((f) => /^quota_\d+.*\.sql$/.test(f)).sort()) db.exec(readFileSync(new URL(file, dir), "utf8"));
  return db;
}
/** A D1-shaped binding over node:sqlite, as in sql.test.js. */
function d1(db) {
  const statement = (sql) => ({
    args: [],
    bind(...args) { this.args = args; return this; },
    async first() { return db.prepare(sql).get(...this.args) ?? null; },
    async run() { db.prepare(sql).run(...this.args); return { success: true }; },
    async all() { return { results: db.prepare(sql).all(...this.args) }; },
  });
  return { prepare: statement, async batch(list) { for (const s of list) await s.run(); return list.map(() => ({ success: true })); } };
}

/** Signed install headers over "<install>:<middle>:<ts>", the way the app signs. */
function signed(middle, { key = KEY, install = INSTALL, ts = now() } = {}) {
  return { "X-Stash-Install": install, "X-Stash-Ts": String(ts), "X-Stash-Auth": createHmac("sha256", key).update(`${install}:${middle}:${ts}`).digest("hex") };
}

function relay(tidal, { seats = 4, quota = true, env: extra = {} } = {}) {
  const db = relayDb();
  for (let i = 0; i < seats; i++) {
    db.prepare("INSERT INTO tidal_accounts(id,access_token,refresh_token,token_expires_at,state,updated_at,last_used_at) VALUES(?,?,?,?,'live',?,?)")
      .run(`seat-${i}`, `token-${i}`, "refresh", now() + 3600, now(), i);
  }
  const qdb = quota ? quotaDb() : null;
  const env = { ACCESS_TOKEN, TIDAL_CLIENT_ID: "id", TIDAL_COUNTRY_CODE: "US", DB: d1(db), RELAY_KEY: KEY, ...(qdb ? { QUOTA_DB: d1(qdb) } : {}), ...extra };
  const calls = [];
  const call = async (path, headers = {}, token = ACCESS_TOKEN) => {
    const original = globalThis.fetch;
    globalThis.fetch = async (url) => { calls.push(String(url)); return tidal(String(url)); };
    try {
      return await worker.fetch(new Request(`https://relay.example${path}`, { headers: { "X-Access-Token": token, ...headers } }), env);
    } finally { globalThis.fetch = original; }
  };
  const used = (subject = `i:${INSTALL}`) => qdb.prepare("SELECT n FROM usage WHERE day=? AND subject=?").get(today(), subject)?.n ?? 0;
  const setUsed = (n, subject = `i:${INSTALL}`) => qdb.prepare("INSERT OR REPLACE INTO usage(day,subject,n) VALUES(?,?,?)").run(today(), subject, n);
  return { env, db, qdb, calls, call, used, setUsed };
}

const bts = (codecs, url) => Buffer.from(JSON.stringify({ codecs, urls: [url] })).toString("base64");
const info = (audioQuality, mime, manifest) => new Response(JSON.stringify({ audioQuality, manifestMimeType: mime, manifest, bitDepth: 24, sampleRate: 96000 }), { status: 200 });
/** FLAC with a readable expiry, so the relay caches the answer (a later request is a cache hit). */
const flac = () => info("LOSSLESS", "application/vnd.tidal.bts", bts("flac", `https://cdn.example/a.flac?token=${now() + 3600}~sig`));
const aacInfo = () => info("HIGH", "application/vnd.tidal.bts", bts("mp4a.40.2", `https://cdn.example/a.m4a?token=${now() + 3600}~sig`));
const MPD = '<MPD><SegmentTemplate initialization="https://sp-ad-fa.audio.tidal.com/init.mp4?token=t" media="https://audio.tidal.com/$Number$" /></MPD>';
const dash = () => info("HI_RES_LOSSLESS", "application/dash+xml", Buffer.from(MPD).toString("base64"));
/** Tidal stub: HIGH (AAC) when asked for HIGH, else `lossless()`. */
const tidal = (lossless = flac) => (url) => (url.includes("audioquality=HIGH") ? aacInfo() : lossless());

test("the Tidal signature is <install>:td:<id>:<ts> and <install>:quota:<ts>, the Qobuz relay's HMAC (fixed vectors)", () => {
  const H = { install: "0f7c3a2e-install", ts: "1700000000" };
  // The same vector the Qobuz relay's auth.test.js accepts for a mint of track 42, format 27.
  assert.equal(verifySigned({ ...H, auth: "fc6ad1b240aa21efc87e1088841bd3b2eac9bb5139d855de9dde6e0729bb0532" }, "42:27", 1700000000, [KEY]), true);
  assert.equal(verifySigned({ ...H, auth: "9fe21916731b9ed9e454c11a8188543de5c2c04520925320a5389f72ee904a2f" }, "td:42", 1700000000, [KEY]), true);
  assert.equal(verifySigned({ ...H, auth: "c9f00efc855cdc0349a1bcb6b5cedcdf78ec50c76570e5c9c250db26bcf2df9d" }, "quota", 1700000000, [KEY]), true);
  assert.equal(verifySigned({ ...H, auth: "9fe21916731b9ed9e454c11a8188543de5c2c04520925320a5389f72ee904a2f" }, "td:42", 1700000301, [KEY]), false, "±300 s");
  assert.equal(verifySigned({ ...H, auth: "9fe21916731b9ed9e454c11a8188543de5c2c04520925320a5389f72ee904a2f" }, "td:43", 1700000000, [KEY]), false);
  assert.equal(verifySigned({}, "td:42", 1700000000, [KEY]), false);
});

test("a signed FLAC stream costs one unit and carries the counter headers; a relay cache hit counts again", async () => {
  const r = relay(tidal());
  const first = await r.call("/stream/td:1", signed("td:1"));
  assert.equal(first.status, 200);
  assert.equal((await first.json()).codec, "flac");
  assert.equal(first.headers.get("X-Stash-Lossless"), "1/500");
  assert.equal(first.headers.get("X-Stash-Lossless-Reset"), String(nextResetSec(now())));
  const hit = await r.call("/stream/1", signed("td:1"));
  assert.equal(hit.headers.get("X-Stash-Lossless"), "2/500", "the bare /stream/<id> form signs td:<id> too");
  assert.equal(r.calls.length, 1, "the second answer came from the relay cache");
  assert.equal(r.used(), 2);
});

test("DASH FLAC and Tidal's AAC (?aac=1 on a CD-only track, ?aac=only) each count one unit; 404s never do", async () => {
  const r = relay((url) => {
    if (url.includes("/tracks/3/")) return dash();
    if (url.includes("/tracks/4/")) return new Response(JSON.stringify({ subStatus: 4005 }), { status: 401 });
    if (url.includes("/tracks/5?")) return new Response(JSON.stringify({ audioQuality: "LOSSLESS" }), { status: 200 });
    return aacInfo(); // tracks 1, 2, 5: CD-only, Tidal answers HIGH
  }, { seats: 8 });
  const d = await r.call("/stream/td:3", signed("td:3"));
  assert.equal((await d.json()).manifest, "dash");
  assert.equal(d.headers.get("X-Stash-Lossless"), "1/500");
  for (const [path, id, status, counter] of [
    ["/stream/td:1?aac=1", 1, 200, "2/500"],
    ["/stream/td:2", 2, 404, null],
    ["/stream/td:4", 4, 404, null],
    ["/stream/td:5?aac=only", 5, 200, "3/500"],
  ]) {
    const res = await r.call(path, signed(`td:${id}`));
    assert.equal(res.status, status, path);
    if (status === 200) assert.equal((await res.json()).codec, "aac", path);
    assert.equal(res.headers.get("X-Stash-Lossless"), counter, path);
  }
  assert.equal(r.used(), 3);
});

test("an AAC answer from the relay cache counts again, like a cached FLAC one", async () => {
  const r = relay(() => aacInfo());
  const first = await r.call("/stream/td:1?aac=only", signed("td:1"));
  assert.equal((await first.json()).codec, "aac");
  const hit = await r.call("/stream/td:1?aac=only", signed("td:1"));
  assert.equal(hit.headers.get("X-Stash-Lossless"), "2/500");
  assert.equal(r.calls.filter((c) => c.includes("playbackinfo")).length, 1, "the second came from the relay cache");
  assert.equal(r.used(), 2);
});

test("at FREE_LOSSLESS_DAILY: 429 daily_lossless_cap with Retry-After to midnight UTC, no seat taken", async () => {
  const r = relay(tidal(), { env: { FREE_LOSSLESS_DAILY: "3" } });
  r.setUsed(3);
  const res = await r.call("/stream/td:1", signed("td:1"));
  assert.equal(res.status, 429);
  const reset = nextResetSec(now());
  assert.deepEqual(await res.json(), { error: "daily_lossless_cap", used: 3, limit: 3, resetsAt: reset });
  assert.ok(Math.abs(Number(res.headers.get("Retry-After")) - (reset - now())) <= 1);
  assert.equal(res.headers.get("X-Stash-Lossless"), "3/3");
  assert.equal(res.headers.get("X-Stash-Lossless-Reset"), String(reset));
  assert.equal(r.calls.length, 0);
  assert.equal(r.db.prepare("SELECT COUNT(*) AS n FROM tidal_accounts WHERE day_n > 0").get().n, 0, "no seat was taken");
  assert.equal(r.used(), 3);
  // Another install still plays lossless.
  assert.equal((await r.call("/stream/td:1", signed("td:1", { install: "install-0002" }))).status, 200);
});

test("over the cap every mode gets the 429, ?aac=1 and ?aac=only included: no uncounted AAC, no Tidal call", async () => {
  const r = relay(tidal(), { env: { FREE_LOSSLESS_DAILY: "2" } });
  r.setUsed(2);
  for (const path of ["/stream/td:1", "/stream/td:1?aac=1", "/stream/td:1?aac=only"]) {
    const res = await r.call(path, signed("td:1"));
    assert.equal(res.status, 429, path);
    assert.deepEqual(await res.json(), { error: "daily_lossless_cap", used: 2, limit: 2, resetsAt: nextResetSec(now()) }, path);
    assert.equal(res.headers.get("X-Stash-Lossless"), "2/2", path);
  }
  assert.equal(r.calls.length, 0, "Tidal was never asked");
  assert.equal(r.used(), 2);
});

test("a warm AAC answer in the relay cache is no way past the cap either", async () => {
  const r = relay(() => aacInfo(), { env: { FREE_LOSSLESS_DAILY: "2" } }); // a CD-only track: Tidal answers HIGH
  const warm = await r.call("/stream/td:1?aac=1", signed("td:1", { install: "install-0002" }));
  assert.equal((await warm.json()).codec, "aac");
  r.setUsed(2);
  const res = await r.call("/stream/td:1?aac=1", signed("td:1"));
  assert.equal(res.status, 429);
  assert.equal(r.used(), 2);
});

test("an unsigned ?aac=only (the web player) is still served, uncounted", async () => {
  const r = relay(() => aacInfo());
  const res = await r.call("/stream/td:1?aac=only");
  assert.equal(res.status, 200);
  assert.equal((await res.json()).codec, "aac");
  assert.equal(res.headers.get("X-Stash-Lossless"), null);
  assert.equal(r.used(), 0);
});

test("an ?aac=only 404 (no AAC of the track) is never charged", async () => {
  const r = relay(() => new Response(JSON.stringify({ subStatus: 4005 }), { status: 401 }), { seats: 8 });
  const res = await r.call("/stream/td:9?aac=only&hires=0", signed("td:9"));
  assert.equal(res.status, 404);
  assert.equal(res.headers.get("X-Stash-Lossless"), null);
  assert.equal(r.used(), 0);
});

test("a warm ?aac=only entry serves ?aac=only&hires=0 with no Tidal call, and it still counts", async () => {
  const r = relay(() => aacInfo());
  assert.equal((await (await r.call("/stream/td:5?aac=only", signed("td:5", { install: "install-0002" }))).json()).codec, "aac");
  const calls = r.calls.length;
  const res = await r.call("/stream/td:5?aac=only&hires=0", signed("td:5"));
  assert.equal((await res.json()).codec, "aac");
  assert.equal(r.calls.length, calls, "served from the warm ?aac=only entry");
  assert.equal(res.headers.get("X-Stash-Lossless"), "1/500");
});

test("?aac=only&hires=0 counts one unit like any AAC answer", async () => {
  const r = relay(() => aacInfo());
  const res = await r.call("/stream/td:2?aac=only&hires=0", signed("td:2"));
  assert.equal((await res.json()).codec, "aac");
  assert.equal(res.headers.get("X-Stash-Lossless"), "1/500");
  assert.equal(r.used(), 1);
});

test("legacy: a request with no install headers streams uncounted, as before", async () => {
  const r = relay(tidal());
  const res = await r.call("/stream/td:1");
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("X-Stash-Lossless"), null);
  assert.equal(r.qdb.prepare("SELECT COUNT(*) AS n FROM usage").get().n, 0);
});

test("REQUIRE_INSTALL=1 refuses a request with no install headers; a signed one plays", async () => {
  const r = relay(tidal(), { env: { REQUIRE_INSTALL: "1" } });
  const res = await r.call("/stream/td:1");
  assert.equal(res.status, 401);
  assert.ok(res.headers.get("X-Stash-Server-Time"));
  assert.equal((await r.call("/stream/td:1", signed("td:1"))).status, 200);
  assert.equal(r.calls.length, 1);
});

/** Install headers that fail verification for /stream/td:1 for a reason other than a skewed clock. */
const FORGED = () => [
  signed("td:1", { key: "bogus" }), signed("td:2"), { "X-Stash-Install": INSTALL }, { ...signed("td:1"), "X-Stash-Install": "someone-else-1" },
  signed("quota"), { ...signed("td:1"), "X-Stash-Auth": "zz" }, signed("td:1", { key: "bogus", ts: now() - 3600 }),
];
const SKEWED = () => [signed("td:1", { ts: now() - 301 }), signed("td:1", { ts: now() + 301 })];

/** Runs `fn` with console.warn captured; returns the warnings. */
async function warnings(fn) {
  const original = console.warn;
  const seen = [];
  console.warn = (...args) => seen.push(args.join(" "));
  try { await fn(); } finally { console.warn = original; }
  return seen;
}

test("REQUIRE_INSTALL=0: a failing install signature (bad key, wrong track, partial, malformed) is served uncounted and logged, redacted", async () => {
  const r = relay(tidal(), { seats: 8 });
  for (const h of FORGED()) {
    const logged = await warnings(async () => {
      const res = await r.call("/stream/td:1", h);
      assert.equal(res.status, 200, JSON.stringify(h));
      assert.equal(res.headers.get("X-Stash-Lossless"), null, JSON.stringify(h));
      assert.equal(res.headers.get("X-Stash-Server-Time"), null, JSON.stringify(h));
    });
    const line = logged.find((l) => l.startsWith("install_signature_failed"));
    assert.ok(line, JSON.stringify(h));
    assert.match(line, /"reason":"(malformed|bad_signature)"/);
    assert.ok(!line.includes(INSTALL) && !line.includes("someone-else-1"), "never the full install id");
    if (h["X-Stash-Auth"]) assert.ok(!line.includes(h["X-Stash-Auth"]), "never the signature");
  }
  assert.equal(r.qdb.prepare("SELECT COUNT(*) AS n FROM usage").get().n, 0, "nothing counted");
});

test("REQUIRE_INSTALL=0: a genuine signature with ts outside ±300 s stays a 401 with X-Stash-Server-Time (the clock self-corrects)", async () => {
  const r = relay(tidal());
  for (const h of SKEWED()) {
    const res = await r.call("/stream/td:1", h);
    assert.equal(res.status, 401, JSON.stringify(h));
    assert.deepEqual(await res.json(), { error: "unauthorized" });
    assert.ok(Math.abs(Number(res.headers.get("X-Stash-Server-Time")) - now()) <= 1);
  }
  assert.equal(r.calls.length, 0);
});

test("REQUIRE_INSTALL=1: every failing install signature is a 401 with X-Stash-Server-Time; RELAY_KEY_PREV still verifies", async () => {
  const r = relay(tidal(), { env: { REQUIRE_INSTALL: "1", RELAY_KEY: "new-key", RELAY_KEY_PREV: KEY } });
  for (const h of [...FORGED(), ...SKEWED()]) {
    const res = await r.call("/stream/td:1", h);
    assert.equal(res.status, 401, JSON.stringify(h));
    assert.deepEqual(await res.json(), { error: "unauthorized" });
    assert.ok(Math.abs(Number(res.headers.get("X-Stash-Server-Time")) - now()) <= 1);
  }
  assert.equal(r.calls.length, 0);
  assert.equal((await r.call("/stream/td:1", signed("td:1"))).status, 200, "the previous key");
  const wrongToken = await r.call("/stream/td:1", signed("td:1"), "wrong-token");
  assert.equal(wrongToken.status, 401);
  assert.equal(wrongToken.headers.get("X-Stash-Server-Time"), null, "the access-token 401 carries no server time");
});

test("without RELAY_KEY install headers are ignored (counter off); with REQUIRE_INSTALL=1 that is a 500", async () => {
  const r = relay(tidal(), { env: { RELAY_KEY: undefined } });
  const res = await r.call("/stream/td:1", signed("td:1", { key: "anything" }));
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("X-Stash-Lossless"), null);
  const q = relay(tidal(), { env: { RELAY_KEY: undefined, REQUIRE_INSTALL: "1" } });
  assert.equal((await q.call("/stream/td:1", signed("td:1"))).status, 500);
});

test("without QUOTA_DB the counter is skipped: signed streams play, uncounted and without headers", async () => {
  const r = relay(tidal(), { quota: false });
  const res = await r.call("/stream/td:1", signed("td:1"));
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("X-Stash-Lossless"), null);
  assert.equal((await r.call("/quota", signed("quota"))).status, 404);
});

test("a supporter entitlement skips the cap: FLAC past the limit, header <used>/unlimited, /quota says so", async () => {
  const r = relay(tidal(), { env: { FREE_LOSSLESS_DAILY: "1" } });
  r.setUsed(1);
  const original = hooks.entitlementOf;
  hooks.entitlementOf = async () => ({ tier: "supporter", tid: "t1" });
  try {
    const res = await r.call("/stream/td:1", signed("td:1"));
    assert.equal(res.status, 200);
    assert.equal((await res.json()).codec, "flac");
    assert.equal(res.headers.get("X-Stash-Lossless"), "2/unlimited");
    assert.deepEqual(await (await r.call("/quota", signed("quota"))).json(), { used: 2, limit: null, resetsAt: nextResetSec(now()), supporter: true });
  } finally { hooks.entitlementOf = original; }
});

test("GET /quota: signed '<install>:quota:<ts>', today's count with the headers, never counted", async () => {
  const r = relay(tidal());
  await r.call("/stream/td:1", signed("td:1"));
  for (let i = 0; i < 2; i++) {
    const res = await r.call("/quota", signed("quota"));
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { used: 1, limit: 500, resetsAt: nextResetSec(now()), supporter: false });
    assert.equal(res.headers.get("X-Stash-Lossless"), "1/500");
    assert.equal(res.headers.get("X-Stash-Lossless-Reset"), String(nextResetSec(now())));
  }
  assert.equal(r.used(), 1);
  assert.equal(r.calls.length, 1);
});

test("GET /quota refuses: no access token, unsigned, a stream signature, a bad key; behind REQUEST_RL", async () => {
  const r = relay(tidal());
  assert.equal((await r.call("/quota", signed("quota"), "wrong-token")).status, 401);
  for (const h of [{}, signed("td:1"), signed("quota", { key: "bogus" })]) {
    const res = await r.call("/quota", h);
    assert.equal(res.status, 401, JSON.stringify(h));
    assert.ok(res.headers.get("X-Stash-Server-Time"));
  }
  r.env.REQUEST_RL = { limit: async () => ({ success: false }) };
  assert.equal((await r.call("/quota", signed("quota"))).status, 429);
  const off = relay(tidal(), { env: { RELAY_KEY: undefined } });
  const res = await off.call("/quota", signed("quota"));
  assert.equal(res.status, 404);
  assert.deepEqual(await res.json(), { error: "quota_off" });
});

test("a QUOTA_DB failure never stops a stream: FLAC served without counter headers", async () => {
  const r = relay(tidal());
  r.env.QUOTA_DB = { prepare: () => { throw new Error("D1 down"); } };
  const res = await r.call("/stream/td:1", signed("td:1"));
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("X-Stash-Lossless"), null);
});

test("a D1 error bumping the stream counters after Tidal answered still returns the answer (and counts the lossless unit)", async () => {
  const r = relay(tidal(), { env: { IP_SALT: "salt" } });
  const db = r.env.DB;
  r.env.DB = { ...db, batch: async () => { throw new Error("D1 write failed"); } };
  const ip = { "CF-Connecting-IP": "203.0.113.9" };
  const errors = [];
  const original = console.error;
  console.error = (...args) => errors.push(args.join(" "));
  try {
    const res = await r.call("/stream/td:1", { ...ip, ...signed("td:1") });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).codec, "flac");
    assert.equal(res.headers.get("X-Stash-Lossless"), "1/500");
  } finally { console.error = original; }
  assert.equal(r.calls.length, 1);
  assert.ok(errors.some((l) => l.startsWith("stream quota bump failed")), errors.join("\n"));
  assert.equal(r.db.prepare("SELECT COUNT(*) AS n FROM tidal_daily_quotas").get().n, 0, "the bump was lost, not the answer");
});

test("a D1 error reading the stream counters admits the stream instead of a 503", async () => {
  const r = relay(tidal(), { env: { IP_SALT: "salt" } });
  const db = r.env.DB;
  r.env.DB = {
    ...db,
    prepare(sql) {
      const s = db.prepare(sql);
      if (sql.includes("FROM tidal_daily_quotas WHERE day = ?1")) s.all = async () => { throw new Error("D1 read failed"); };
      return s;
    },
  };
  const original = console.error;
  console.error = () => {};
  try {
    const res = await r.call("/stream/td:1", { "CF-Connecting-IP": "203.0.113.9", ...signed("td:1") });
    assert.equal(res.status, 200);
    assert.equal((await res.json()).codec, "flac");
  } finally { console.error = original; }
  assert.equal(r.used(), 1);
});

test("the hourly job also prunes week-old usage rows", async () => {
  const r = relay(tidal());
  r.qdb.prepare("INSERT INTO usage(day,subject,n) VALUES('2000-01-01','i:x',1)").run();
  r.setUsed(4);
  await worker.scheduled({}, r.env);
  assert.deepEqual(r.qdb.prepare("SELECT day FROM usage").all().map((x) => x.day), [today()]);
});

// ---- Supporter tokens (X-Stash-Entitlement) end to end, with a test keypair ----

const KEYS = keypair();
const supporterRelay = (env = {}, opts = {}) => relay(tidal(), { seats: 8, ...opts, env: { ENTITLEMENT_PUBLIC_KEY: KEYS.publicKey, FREE_LOSSLESS_DAILY: "1", ...env } });
const withToken = (t, headers) => ({ ...headers, "X-Stash-Entitlement": t });

test("a valid token: FLAC past FREE_LOSSLESS_DAILY, header <used>/unlimited, the install and t:<tid> rows both count", async () => {
  const r = supporterRelay();
  const t = token(KEYS.privateKey);
  for (let i = 1; i <= 3; i++) {
    const res = await r.call(`/stream/td:${i}`, withToken(t, signed(`td:${i}`)));
    assert.equal(res.status, 200, `stream ${i}`);
    assert.equal((await res.json()).codec, "flac");
    assert.equal(res.headers.get("X-Stash-Lossless"), `${i}/unlimited`);
  }
  assert.equal(r.used(), 3);
  assert.equal(r.used(`t:${tidOf(t)}`), 3);
  const plain = await r.call("/stream/td:9", signed("td:9"));
  assert.equal(plain.status, 429, "without the token the install is over its free limit");
});

test("bad signature, another key, garbage and a wrong tier get the normal quota, never an error", async () => {
  const good = token(KEYS.privateKey);
  const [head, sig] = good.split(".");
  const flipped = Buffer.from(sig, "base64url"); flipped[7] ^= 1;
  for (const t of [`${head}.${flipped.toString("base64url")}`, token(keypair().privateKey), "garbage", "x.y", token(KEYS.privateKey, { tier: "pro" })]) {
    const r = supporterRelay();
    const first = await r.call("/stream/td:1", withToken(t, signed("td:1")));
    assert.equal(first.status, 200, t);
    assert.equal(first.headers.get("X-Stash-Lossless"), "1/1", t);
    const second = await r.call("/stream/td:2", withToken(t, signed("td:2")));
    assert.equal(second.status, 429, t);
    assert.equal((await second.json()).error, "daily_lossless_cap", t);
  }
});

test("a revoked tid gets the normal quota, AAC included", async () => {
  const r = supporterRelay();
  const t = token(KEYS.privateKey);
  assert.equal((await r.call("/stream/td:1", withToken(t, signed("td:1")))).status, 200);
  r.qdb.prepare("INSERT INTO revoked_tokens(tid,revoked_at,reason) VALUES(?,?,'posted publicly')").run(tidOf(t), now());
  const res = await r.call("/stream/td:2", withToken(t, signed("td:2")));
  assert.equal(res.status, 429);
  assert.deepEqual(await res.json(), { error: "daily_lossless_cap", used: 1, limit: 1, resetsAt: nextResetSec(now()) });
  const aac = await r.call("/stream/td:3?aac=1", withToken(t, signed("td:3")));
  assert.equal(aac.status, 429);
  assert.equal(aac.headers.get("X-Stash-Lossless"), "1/1");
});

test("SUPPORTER_DAILY_CAP: at the token's ceiling the install is back on its free limit", async () => {
  const r = supporterRelay({ SUPPORTER_DAILY_CAP: "2", FREE_LOSSLESS_DAILY: "3" });
  const t = token(KEYS.privateKey);
  for (let i = 1; i <= 2; i++) assert.equal((await r.call(`/stream/td:${i}`, withToken(t, signed(`td:${i}`)))).headers.get("X-Stash-Lossless"), `${i}/unlimited`);
  const free = await r.call("/stream/td:3", withToken(t, signed("td:3")));
  assert.equal(free.status, 200);
  assert.equal(free.headers.get("X-Stash-Lossless"), "3/3", "the install's own count against the free limit");
  assert.equal(r.used(`t:${tidOf(t)}`), 2, "a free answer leaves the token's row alone");
  const capped = await r.call("/stream/td:4", withToken(t, signed("td:4")));
  assert.equal(capped.status, 429);
  assert.equal((await capped.json()).error, "daily_lossless_cap");
  assert.deepEqual(await (await r.call("/quota", withToken(t, signed("quota")))).json(), { used: 3, limit: 3, resetsAt: nextResetSec(now()), supporter: false });
});

test("supporters still meet the network cap", async () => {
  const r = supporterRelay({ IP_SALT: "salt", NETWORK_DAILY_CAP: "2" });
  const t = token(KEYS.privateKey);
  const ip = { "CF-Connecting-IP": "203.0.113.9" };
  assert.equal((await r.call("/stream/td:1", withToken(t, { ...ip, ...signed("td:1") }))).status, 200);
  assert.equal((await r.call("/stream/td:2", withToken(t, { ...ip, ...signed("td:2") }))).status, 200);
  const res = await r.call("/stream/td:3", withToken(t, { ...ip, ...signed("td:3") }));
  assert.equal(res.status, 429);
  assert.deepEqual(await res.json(), { error: "network_daily_cap" });
});

test("GET /quota with a valid token: supporter true, limit null, header <used>/unlimited", async () => {
  const r = supporterRelay();
  const t = token(KEYS.privateKey);
  await r.call("/stream/td:1", withToken(t, signed("td:1")));
  const res = await r.call("/quota", withToken(t, signed("quota")));
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { used: 1, limit: null, resetsAt: nextResetSec(now()), supporter: true });
  assert.equal(res.headers.get("X-Stash-Lossless"), "1/unlimited");
  assert.deepEqual(await (await r.call("/quota", signed("quota"))).json(), { used: 1, limit: 1, resetsAt: nextResetSec(now()), supporter: false }, "without the token");
});
