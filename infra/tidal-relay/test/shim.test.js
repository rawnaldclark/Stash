import test from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { readFileSync, readdirSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker, { SEAT_COLUMNS } from "../src/index.js";

/**
 * The cutover shim (REDIRECT_ORIGIN) and the seat copy routes (POST /admin/seats/import, GET /admin/seats).
 * Seat tests run against real SQLite with the real migrations, so "every column" means the schema's columns.
 */
const ACCESS_TOKEN = "test-access-token";
const ADMIN_TOKEN = "test-admin-token-0123456789";
const NEW = "https://stash-addon-tidal.example.workers.dev";
const MIGRATIONS = new URL("../migrations/", import.meta.url);
const now = () => Math.floor(Date.now() / 1000);

function database() {
  const db = new DatabaseSync(":memory:");
  for (const file of readdirSync(MIGRATIONS).filter((f) => f.endsWith(".sql")).sort()) db.exec(readFileSync(new URL(file, MIGRATIONS), "utf8"));
  return db;
}

/** A D1-shaped binding over node:sqlite. batch() is one transaction, as on D1. */
function d1(db) {
  const statement = (sql) => ({
    args: [],
    bind(...args) { this.args = args; return this; },
    async first() { return db.prepare(sql).get(...this.args) ?? null; },
    async run() { db.prepare(sql).run(...this.args); return { success: true }; },
    async all() { return { results: db.prepare(sql).all(...this.args) }; },
  });
  return {
    prepare: statement,
    async batch(list) {
      db.exec("BEGIN");
      try { for (const s of list) await s.run(); db.exec("COMMIT"); } catch (e) { db.exec("ROLLBACK"); throw e; }
      return list.map(() => ({ success: true }));
    },
  };
}

/** A binding that fails the test if anything touches it: the redirect must use neither D1 nor the limiter. */
const untouchable = (what) => new Proxy({}, { get(_, key) { throw new Error(`${what}.${String(key)} touched`); } });

function shimEnv(extra = {}) {
  return { ACCESS_TOKEN, ADMIN_TOKEN, TIDAL_CLIENT_ID: "id", REDIRECT_ORIGIN: NEW, DB: untouchable("DB"), REQUEST_RL: untouchable("REQUEST_RL"), ...extra };
}

const get = (path, env, init = {}) => worker.fetch(new Request(`https://stash-tidal-relay.example.workers.dev${path}`, init), env);

test("REDIRECT_ORIGIN: /search, /stream/* and /quota answer 307 to the same path and query plus via=old, before the limiter and D1", async () => {
  const env = shimEnv();
  const cases = [
    ["/search?q=daft%20punk%20one+more", `${NEW}/search?q=daft%20punk%20one+more&via=old`],
    ["/stream/td:1550546?client=media3&aac=1", `${NEW}/stream/td:1550546?client=media3&aac=1&via=old`],
    ["/stream/td%3A1550546?aac=only&hires=0", `${NEW}/stream/td%3A1550546?aac=only&hires=0&via=old`],
    ["/stream/1550546", `${NEW}/stream/1550546?via=old`],
    ["/quota", `${NEW}/quota?via=old`],
    ["/search", `${NEW}/search?via=old`],
  ];
  for (const [path, location] of cases) {
    const r = await get(path, env, { headers: { "X-Access-Token": ACCESS_TOKEN } });
    assert.equal(r.status, 307, path);
    assert.equal(r.headers.get("location"), location, path);
    assert.equal(r.headers.get("cache-control"), "no-store");
  }
  // No token at all is redirected too: the new side does the refusing.
  assert.equal((await get("/search?q=x", env)).status, 307);
  // 307 (not 301/302) is what keeps the method: a POST is sent on as a POST.
  const post = await get("/stream/td:1", env, { method: "POST" });
  assert.equal(post.status, 307);
  assert.equal(post.headers.get("location"), `${NEW}/stream/td:1?via=old`);
});

test("REDIRECT_ORIGIN keeps /health, /dash/* and /admin/* local; unknown routes are still a local 404", async () => {
  const db = database();
  const env = shimEnv({ DB: d1(db), REQUEST_RL: undefined });
  assert.deepEqual(await (await get("/health", env)).json(), { ok: true, provider: "tidal" });
  const dash = await get("/dash/td/1.mpd?exp=1&sig=x", env);
  assert.equal(dash.status, 401); // served here: a bad signature, not a redirect
  const seats = await get("/admin/seats", env, { headers: { "X-Access-Token": ADMIN_TOKEN } });
  assert.equal(seats.status, 200);
  assert.equal((await get("/nope", env)).status, 404);
  assert.equal((await get("/searchx", env)).status, 404);
});

test("REDIRECT_ORIGIN never loops: a request already carrying via, or a target that is this origin, is served here", async () => {
  const db = database();
  const local = { DB: d1(db), REQUEST_RL: undefined };
  const viaOld = await get("/search?q=&via=old", shimEnv(local), { headers: { "X-Access-Token": ACCESS_TOKEN } });
  assert.equal(viaOld.status, 200);
  assert.deepEqual(await viaOld.json(), { tracks: [] });
  const self = await get("/search?q=", shimEnv({ ...local, REDIRECT_ORIGIN: "https://stash-tidal-relay.example.workers.dev/" }), { headers: { "X-Access-Token": ACCESS_TOKEN } });
  assert.equal(self.status, 200);
});

test("REDIRECT_ORIGIN unset, blank or unusable serves locally; an unusable one is logged once, without its value", async () => {
  const db = database();
  const errors = [];
  const original = console.error;
  console.error = (line) => errors.push(String(line));
  try {
    for (const origin of [undefined, "", "  ", "http://new.example", "https://new.example/v1", "https://user:pw@new.example", "https://new.example?x=1", "not a url"]) {
      const r = await get("/search?q=", shimEnv({ DB: d1(db), REQUEST_RL: undefined, REDIRECT_ORIGIN: origin }), { headers: { "X-Access-Token": ACCESS_TOKEN } });
      assert.equal(r.status, 200, String(origin));
    }
  } finally { console.error = original; }
  const bad = errors.filter((l) => l.includes("REDIRECT_ORIGIN"));
  assert.equal(bad.length, 1);
  assert.ok(!errors.join("\n").includes("new.example"));
});

test("a real HTTP client follows the 307 to another origin with the same method, headers and query", async (t) => {
  const seen = [];
  const target = createServer((req, res) => {
    seen.push({ method: req.method, url: req.url, headers: req.headers });
    res.writeHead(200, { "content-type": "application/json" }).end('{"ok":true}');
  });
  await new Promise((resolve) => target.listen(0, "127.0.0.1", resolve));
  const targetOrigin = `http://127.0.0.1:${target.address().port}`;
  const env = shimEnv({ REDIRECT_ORIGIN: targetOrigin });
  const shim = createServer(async (req, res) => {
    const r = await worker.fetch(new Request(`http://localhost:${shim.address().port}${req.url}`, { method: req.method, headers: req.headers }), env);
    res.writeHead(r.status, Object.fromEntries(r.headers)).end(await r.text());
  });
  await new Promise((resolve) => shim.listen(0, "127.0.0.1", resolve));
  t.after(() => { target.close(); shim.close(); });
  const headers = {
    "X-Access-Token": ACCESS_TOKEN, "X-Stash-Install": "install-1234", "X-Stash-Ts": "1700000000",
    "X-Stash-Auth": "a".repeat(64), "X-Stash-Entitlement": "e30.e30", "X-Stash-App-Build": "170",
  };
  const r = await fetch(`http://localhost:${shim.address().port}/stream/td:42?client=media3&aac=1`, { headers });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true });
  const post = await fetch(`http://localhost:${shim.address().port}/quota`, { method: "POST", headers, body: "x" });
  assert.equal(post.status, 200);
  assert.equal(seen.length, 2);
  assert.equal(seen[0].method, "GET");
  assert.equal(seen[0].url, "/stream/td:42?client=media3&aac=1&via=old");
  assert.equal(seen[1].method, "POST");
  assert.equal(seen[1].url, "/quota?via=old");
  for (const s of seen) {
    for (const [name, value] of Object.entries(headers)) assert.equal(s.headers[name.toLowerCase()], value, name);
  }
});

// ---- Seats: POST /admin/seats/import and GET /admin/seats ----

let seq = 0;
function seatRow(extra = {}) {
  seq++;
  return {
    id: String(100000 + seq), access_token: `tok-access-${seq}-SECRETVALUE`, refresh_token: `tok-refresh-${seq}-SECRETVALUE`,
    token_expires_at: now() + 7 * 86400, state: "live", hour_key: "2026-11-10T09", hour_n: 3 + seq, day_key: "2026-11-10", day_n: 40 + seq,
    last_used_at: now() - 30, generation: 7 + seq, refresh_lock_until: 0, updated_at: now() - 3600, cooling_until: 0, dead_reason: "",
    last_playback_at: now() - 60, last_call_at: now() - 10, strikes: seq % 3, client: seq % 2 ? "device" : "pkce", ...extra,
  };
}
const adminPost = (env, body, token = ADMIN_TOKEN) => worker.fetch(new Request("https://relay.example/admin/seats/import", {
  method: "POST", headers: { "X-Access-Token": token, "content-type": "application/json" }, body: typeof body === "string" ? body : JSON.stringify(body),
}), env);
const adminList = (env, token = ADMIN_TOKEN) => worker.fetch(new Request("https://relay.example/admin/seats", { headers: { "X-Access-Token": token } }), env);
const allSeats = (db) => db.prepare("SELECT * FROM tidal_accounts ORDER BY id").all().map((r) => ({ ...r }));
const seatEnv = (db) => ({ ACCESS_TOKEN, ADMIN_TOKEN, DB: d1(db) });

test("SEAT_COLUMNS is exactly the schema's columns", () => {
  const db = database();
  assert.deepEqual(db.prepare("PRAGMA table_info(tidal_accounts)").all().map((c) => c.name).sort(), [...SEAT_COLUMNS].sort());
});

test("import copies every column exactly; a second import updates in place; the answer is counts only", async () => {
  const db = database();
  const env = seatEnv(db);
  const seats = [seatRow(), seatRow({ state: "reserve" }), seatRow({ state: "dead", dead_reason: "refresh_invalid", access_token: "", refresh_token: "" })];
  const logs = [];
  const original = console.log;
  console.log = (line) => logs.push(String(line));
  let first, second;
  try {
    first = await adminPost(env, { seats });
    seats[0].hour_n = 99; seats[0].access_token = "tok-access-rotated-SECRETVALUE";
    second = await adminPost(env, { seats: [seats[0], seatRow()] });
  } finally { console.log = original; }
  assert.equal(first.status, 200);
  const firstText = await first.clone().text();
  assert.deepEqual(await first.json(), { status: "ok", imported: 3, inserted: 3, updated: 0 });
  assert.deepEqual(await second.json(), { status: "ok", imported: 2, inserted: 1, updated: 1 });
  const rows = allSeats(db);
  assert.equal(rows.length, 4);
  assert.deepEqual(rows.find((r) => r.id === seats[0].id), seats[0]);
  assert.deepEqual(rows.find((r) => r.id === seats[1].id), seats[1]);
  assert.deepEqual(rows.find((r) => r.id === seats[2].id), seats[2]);
  for (const text of [firstText, logs.join("\n")]) assert.ok(!text.includes("SECRETVALUE"), "no token in answers or logs");
});

test("import refuses without ADMIN_TOKEN (the access token is not enough), and with a bad body, naming the field only", async () => {
  const db = database();
  const env = seatEnv(db);
  assert.equal((await adminPost(env, { seats: [seatRow()] }, ACCESS_TOKEN)).status, 401);
  assert.equal((await adminPost({ ...env, ADMIN_TOKEN: undefined }, { seats: [seatRow()] }, "")).status, 401);
  const refusals = [
    ["not json", undefined],
    [{}, undefined],
    [{ seats: {} }, undefined],
    [{ seats: [], extra: 1 }, undefined],
    [{ seats: [seatRow({ state: "zombie" })] }, "seats[0].state"],
    [{ seats: [seatRow(), seatRow({ client: "web" })] }, "seats[1].client"],
    [{ seats: [seatRow({ hour_n: -1 })] }, "seats[0].hour_n"],
    [{ seats: [seatRow({ generation: 1.5 })] }, "seats[0].generation"],
    [{ seats: [seatRow({ id: "1 OR 1=1" })] }, "seats[0].id"],
    [{ seats: [seatRow({ access_token: "has SECRETVALUE space" })] }, "seats[0].access_token"],
    [{ seats: [seatRow({ surprise: "x" })] }, "seats[0].surprise"],
    [{ seats: [(({ strikes, ...rest }) => rest)(seatRow())] }, "seats[0].strikes"],
    [{ seats: [seatRow({ day_key: "yesterday" })] }, "seats[0].day_key"],
  ];
  for (const [body, field] of refusals) {
    const r = await adminPost(env, body);
    assert.equal(r.status, 400, JSON.stringify(field));
    const text = await r.text();
    if (field) assert.equal(JSON.parse(text).message, `invalid ${field}`);
    assert.ok(!text.includes("SECRETVALUE"));
  }
  const dupe = seatRow();
  assert.equal((await adminPost(env, { seats: [dupe, { ...dupe }] })).status, 400);
  assert.equal(allSeats(db).length, 0, "nothing written by a refused import");
  assert.equal((await adminPost(env, "x".repeat(2 * 1024 * 1024 + 1))).status, 400);
});

test("import is all or nothing: a seat the database refuses rolls the whole batch back", async () => {
  const db = database();
  const env = seatEnv(db);
  db.exec("CREATE TRIGGER refuse BEFORE INSERT ON tidal_accounts WHEN NEW.id = '999999' BEGIN SELECT RAISE(ABORT, 'no'); END");
  const r = await adminPost(env, { seats: [seatRow(), seatRow({ id: "999999" })] }).catch((e) => e);
  assert.ok(r instanceof Error || r.status >= 500);
  assert.equal(allSeats(db).length, 0);
});

test("GET /admin/seats lists every column but the tokens, with has_* flags and counts; never a token", async () => {
  const db = database();
  const env = seatEnv(db);
  const seats = [seatRow(), seatRow({ state: "reserve" }), seatRow({ state: "dead", access_token: "", refresh_token: "" })];
  await adminPost(env, { seats });
  assert.equal((await adminList(env, ACCESS_TOKEN)).status, 401);
  const r = await adminList(env);
  assert.equal(r.status, 200);
  const text = await r.clone().text();
  assert.ok(!text.includes("SECRETVALUE") && !text.includes("tok-"), "no token value");
  const body = await r.json();
  assert.deepEqual(body.counts, { live: 1, reserve: 1, dead: 1 });
  assert.ok(Number.isInteger(body.now));
  const expectedKeys = [...SEAT_COLUMNS.filter((c) => c !== "access_token" && c !== "refresh_token"), "has_access_token", "has_refresh_token"].sort();
  for (const seat of body.seats) assert.deepEqual(Object.keys(seat).sort(), expectedKeys);
  const byId = Object.fromEntries(body.seats.map((s) => [s.id, s]));
  for (const s of seats) {
    const listed = byId[s.id];
    for (const c of SEAT_COLUMNS) if (c !== "access_token" && c !== "refresh_token") assert.equal(listed[c], s[c], c);
    assert.equal(listed.has_access_token, s.access_token !== "");
    assert.equal(listed.has_refresh_token, s.refresh_token !== "");
  }
});

test("round trip: old -> new -> old through the import route gives identical rows, and the copy plays", async () => {
  const oldDb = database(), newDb = database();
  const seats = [seatRow(), seatRow({ client: "pkce" }), seatRow({ state: "reserve" })];
  const insert = oldDb.prepare(`INSERT INTO tidal_accounts(${SEAT_COLUMNS.join(",")}) VALUES(${SEAT_COLUMNS.map(() => "?").join(",")})`);
  for (const s of seats) insert.run(...SEAT_COLUMNS.map((c) => s[c]));
  const before = allSeats(oldDb);
  // What move-seats.mjs does: read the rows (wrangler d1 execute --json), send them to the other side's import route.
  assert.equal((await adminPost(seatEnv(newDb), { seats: allSeats(oldDb) })).status, 200);
  assert.deepEqual(allSeats(newDb), before);
  oldDb.exec("DELETE FROM tidal_accounts");
  assert.equal((await adminPost(seatEnv(oldDb), { seats: allSeats(newDb) })).status, 200);
  assert.deepEqual(allSeats(oldDb), before);
  // The copied live seats are usable: a search on the new side takes one of them.
  newDb.prepare("UPDATE tidal_accounts SET last_call_at=0").run();
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ items: [] }), { status: 200 });
  try {
    const r = await worker.fetch(new Request("https://new.example/search?q=one", { headers: { "X-Access-Token": ACCESS_TOKEN } }), { ACCESS_TOKEN, TIDAL_CLIENT_ID: "id", DB: d1(newDb) });
    assert.equal(r.status, 200);
  } finally { globalThis.fetch = original; }
});
