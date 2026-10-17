import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker from "../src/index.js";

/**
 * The worker against real SQLite with the real migrations: proves the seat-gap and
 * 429-cooldown SQL itself, which the mocked-D1 tests in worker.test.js cannot.
 */
const ACCESS_TOKEN = "test-access-token";
const MIGRATIONS = new URL("../migrations/", import.meta.url);
const now = () => Math.floor(Date.now() / 1000);

function database({ upTo = Infinity, before } = {}) {
  const db = new DatabaseSync(":memory:");
  for (const file of readdirSync(MIGRATIONS).filter((f) => f.endsWith(".sql")).sort()) {
    if (Number.parseInt(file, 10) > upTo) break;
    if (before && file.startsWith("0008")) before(db);
    db.exec(readFileSync(new URL(file, MIGRATIONS), "utf8"));
  }
  return db;
}

/** A D1-shaped binding over node:sqlite (prepare/bind/first/run/all/batch). */
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

function relay(db, tidal) {
  const env = { ACCESS_TOKEN, TIDAL_CLIENT_ID: "id", TIDAL_COUNTRY_CODE: "US", DB: d1(db) };
  const calls = [];
  const call = async (path, headers = {}) => {
    const original = globalThis.fetch;
    globalThis.fetch = async (url) => { calls.push(String(url)); return tidal(String(url)); };
    try {
      return await worker.fetch(new Request(`https://relay.example${path}`, { headers: { "X-Access-Token": ACCESS_TOKEN, ...headers } }), env);
    } finally { globalThis.fetch = original; }
  };
  return { env, calls, call };
}

function addSeat(db, id, extra = {}) {
  db.prepare(`INSERT INTO tidal_accounts(id,access_token,refresh_token,token_expires_at,state,updated_at) VALUES(?,?,?,?,'live',?)`)
    .run(id, `token-${id}`, "refresh", now() + 3600, now());
  for (const [k, v] of Object.entries(extra)) db.prepare(`UPDATE tidal_accounts SET ${k}=? WHERE id=?`).run(v, id);
}
const seat = (db, id) => db.prepare("SELECT * FROM tidal_accounts WHERE id=?").get(id);
const searchOk = () => new Response(JSON.stringify({ items: [] }), { status: 200 });
const throttled = () => new Response("", { status: 429 });

test("real SQL: every call stamps last_call_at and the next call on that seat waits out the gap", async () => {
  const db = database();
  addSeat(db, "a");
  const r = relay(db, searchOk);
  assert.equal((await r.call("/search?q=one")).status, 200);
  const stamped = seat(db, "a").last_call_at;
  assert.ok(stamped >= now() - 1);
  const paced = await r.call("/search?q=two");
  assert.equal(paced.status, 503);
  assert.equal(paced.headers.get("retry-after"), "15");
  db.prepare("UPDATE tidal_accounts SET last_call_at=? WHERE id='a'").run(now() - 21);
  assert.equal((await r.call("/search?q=two")).status, 200);
  assert.equal(r.calls.length, 2);
  assert.equal(seat(db, "a").hour_n, 2);
});

test("real SQL: 429 cooldown escalates 120 → 240 → 480 → 960 (cap) while hot, and is forgiven after 30 min", async () => {
  const db = database();
  addSeat(db, "a");
  const r = relay(db, throttled);
  const strike = async () => {
    db.prepare("UPDATE tidal_accounts SET last_call_at=0 WHERE id='a'").run();
    assert.equal((await r.call(`/search?q=${Math.random()}`)).status, 503);
    const s = seat(db, "a");
    const spell = s.cooling_until - now();
    // The cooldown just ended: the seat is back in the pool, still "hot".
    db.prepare("UPDATE tidal_accounts SET cooling_until=? WHERE id='a'").run(now() - 1);
    return [s.strikes, spell];
  };
  const spells = [];
  for (let i = 0; i < 5; i++) spells.push(await strike());
  assert.deepEqual(spells.map(([n]) => n), [1, 2, 3, 4, 5]);
  for (const [[, got], want] of spells.map((s, i) => [s, [120, 240, 480, 960, 960][i]])) assert.ok(Math.abs(got - want) <= 1, `${got} ≈ ${want}`);

  db.prepare("UPDATE tidal_accounts SET cooling_until=? WHERE id='a'").run(now() - 1801);
  const [strikes, spell] = await strike();
  assert.equal(strikes, 1, "a seat clean for 30 min starts over");
  assert.ok(Math.abs(spell - 120) <= 1);
  assert.equal(db.prepare("SELECT COUNT(*) n FROM tidal_upstream_429_events").get().n, 6, "one event per 429");
});

test("real SQL: a playback 429 takes the same escalating path", async () => {
  const db = database();
  addSeat(db, "a");
  const r = relay(db, throttled);
  assert.equal((await r.call("/stream/td:1")).status, 503);
  const s = seat(db, "a");
  assert.equal(s.strikes, 1);
  assert.ok(Math.abs(s.cooling_until - now() - 120) <= 1);
  assert.equal(db.prepare("SELECT endpoint FROM tidal_upstream_429_events").get().endpoint, "playbackinfo");
});

const playable = () => new Response(JSON.stringify({ audioQuality: "LOSSLESS", manifestMimeType: "application/vnd.tidal.bts",
  manifest: Buffer.from(JSON.stringify({ codecs: "flac", urls: ["https://cdn.example/a.flac"] })).toString("base64") }), { status: 200 });
const isAuth = (url) => url.startsWith("https://auth.tidal.com/");
const refreshed = () => new Response(JSON.stringify({ access_token: "fresh", expires_in: 3600 }), { status: 200 });

test("real SQL: a 401 that survives a successful refresh is an escalating strike, never a kill", async () => {
  const db = database();
  addSeat(db, "a");
  const r = relay(db, (url) => (isAuth(url) ? refreshed() : new Response("{}", { status: 401 })));
  const first = await r.call("/stream/td:1");
  assert.equal(first.status, 503);
  assert.deepEqual(await first.json(), { error: "busy" });
  let s = seat(db, "a");
  assert.equal(s.state, "live");
  assert.equal(s.access_token, "fresh", "the refresh itself was kept");
  assert.equal(s.strikes, 1);
  assert.ok(Math.abs(s.cooling_until - now() - 120) <= 1);
  db.prepare("UPDATE tidal_accounts SET cooling_until=?,last_call_at=0 WHERE id='a'").run(now() - 1);
  assert.equal((await r.call(`/search?q=${Math.random()}`)).status, 503);
  s = seat(db, "a");
  assert.equal(s.state, "live", "two auth blips in a row still leave the seat in the pool");
  assert.equal(s.strikes, 2);
  assert.ok(Math.abs(s.cooling_until - now() - 240) <= 1);
  assert.equal(db.prepare("SELECT COUNT(*) n FROM tidal_upstream_429_events").get().n, 0, "429 events are for real 429s only");
});

test("real SQL: a refresh rejected with invalid_grant retires the seat and the next serves", async () => {
  for (const error of ["invalid_grant"]) {
    const db = database();
    addSeat(db, "a");
    addSeat(db, "b", { last_used_at: 10 });
    const r = relay(db, (url) => {
      if (isAuth(url)) return new Response(JSON.stringify({ error }), { status: 400 });
      return r.calls.filter((u) => !isAuth(u)).length === 1 ? new Response("{}", { status: 401 }) : playable();
    });
    assert.equal((await r.call("/stream/td:1")).status, 200, error);
    assert.equal(seat(db, "a").state, "dead");
    assert.equal(seat(db, "a").dead_reason, "refresh_invalid");
    assert.equal(seat(db, "a").refresh_lock_until, 0);
    assert.equal(seat(db, "b").state, "live");
  }
});

test("real SQL: a refresh failing any other way (5xx, network, other 4xx error, invalid_client) cools 300 s and never kills", async () => {
  for (const auth of [() => new Response("", { status: 503 }), () => { throw new TypeError("network down"); },
    () => new Response(JSON.stringify({ error: "temporarily_unavailable" }), { status: 400 }),
    // invalid_client is the shared client credentials, not this seat: never a reason to retire seats.
    () => new Response(JSON.stringify({ error: "invalid_client" }), { status: 401 })]) {
    const db = database();
    addSeat(db, "a");
    const r = relay(db, (url) => (isAuth(url) ? auth() : new Response("{}", { status: 401 })));
    assert.equal((await r.call("/stream/td:1")).status, 503);
    const s = seat(db, "a");
    assert.equal(s.state, "live");
    assert.equal(s.strikes, 0);
    assert.equal(s.refresh_lock_until, 0);
    assert.ok(Math.abs(s.cooling_until - now() - 300) <= 1);
  }
});

test("real SQL: an expired token whose refresh is rejected as invalid retires the seat before any API call", async () => {
  const db = database();
  addSeat(db, "a", { token_expires_at: 0 });
  const r = relay(db, (url) => (isAuth(url) ? new Response(JSON.stringify({ error: "invalid_grant" }), { status: 401 }) : searchOk()));
  assert.equal((await r.call("/search?q=x")).status, 503);
  assert.equal(seat(db, "a").state, "dead");
  assert.equal(r.calls.filter((u) => !isAuth(u)).length, 0);
});

test("real SQL: uncached searches count against s:<network> and the cap refuses before any seat", async () => {
  const db = database();
  for (const id of ["a", "b", "c"]) addSeat(db, id);
  const r = relay(db, searchOk);
  Object.assign(r.env, { IP_SALT: "salt", NETWORK_SEARCH_DAILY_CAP: "2" });
  const ip = { "CF-Connecting-IP": "198.51.100.7" };
  assert.equal((await r.call("/search?q=one", ip)).status, 200);
  assert.equal((await r.call("/search?q=one", ip)).status, 200, "cache hit");
  assert.equal((await r.call("/search?q=two", ip)).status, 200);
  const over = await r.call("/search?q=three", ip);
  assert.equal(over.status, 429);
  assert.deepEqual(await over.json(), { error: "network_daily_cap" });
  const rows = db.prepare("SELECT key, n FROM tidal_daily_quotas").all();
  assert.equal(rows.length, 1);
  assert.match(rows[0].key, /^s:[0-9a-f]{16}$/);
  assert.equal(rows[0].n, 2);
  assert.equal(r.calls.length, 2);
});

test("real SQL: the hourly job prunes expired device grants and keeps pending ones", async () => {
  const db = database();
  const put = db.prepare("INSERT INTO tidal_device_auth(device_code,user_code,expires_at) VALUES(?,?,?)");
  put.run("old", "OLD", now() - 1);
  put.run("edge", "EDGE", now());
  put.run("live", "LIVE", now() + 300);
  await worker.scheduled({}, { DB: d1(db) });
  assert.deepEqual(db.prepare("SELECT device_code FROM tidal_device_auth").all().map((r) => r.device_code), ["live"]);
});

test("migration 0008 drops cached not_available answers and keeps everything else", () => {
  const db = database({
    before(d) {
      const put = d.prepare("INSERT INTO tidal_response_cache(cache_key,response_json,expires_at) VALUES(?,?,?)");
      put.run("stream:US:1:aac=:web=app", JSON.stringify({ status: 404, body: { error: "not_available" } }), now() + 9999);
      put.run("stream:US:2:aac=:web=app", JSON.stringify({ status: 404, body: { error: "lossless_unavailable" } }), now() + 9999);
      put.run("search:US:abc", JSON.stringify({ status: 200, body: { tracks: [] } }), now() + 9999);
      d.prepare(`INSERT INTO tidal_accounts(id,access_token,refresh_token,token_expires_at,state,updated_at,last_used_at,last_playback_at)
        VALUES('a','t','r',0,'live',0,100,200)`).run();
    },
  });
  assert.deepEqual(db.prepare("SELECT cache_key FROM tidal_response_cache ORDER BY cache_key").all().map((r) => r.cache_key),
    ["search:US:abc", "stream:US:2:aac=:web=app"]);
  assert.equal(seat(db, "a").last_call_at, 200, "seeded from the latest of last_used_at / last_playback_at");
});
