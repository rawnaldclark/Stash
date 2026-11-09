import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker, { signedUrlExpiry, mpdUrls } from "../src/index.js";

/**
 * Web (PKCE) seats and the ?web=mse lane, against real SQLite with the real migrations, so the seat-kind
 * SQL in takeAccount / onlyPacingBlocks / the admin upsert is exercised as deployed.
 */
const ACCESS_TOKEN = "test-access-token";
const ADMIN_TOKEN = "test-admin-token";
const MIGRATIONS = new URL("../migrations/", import.meta.url);
const now = () => Math.floor(Date.now() / 1000);

function database({ upTo = Infinity, before } = {}) {
  const db = new DatabaseSync(":memory:");
  for (const file of readdirSync(MIGRATIONS).filter((f) => f.endsWith(".sql")).sort()) {
    if (Number.parseInt(file, 10) > upTo) break;
    if (before && file.startsWith("0009")) before(db);
    db.exec(readFileSync(new URL(file, MIGRATIONS), "utf8"));
  }
  return db;
}

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

/** The relay over `db`; `tidal(url, options)` answers every outbound fetch. calls = [{url, options}]. */
function relay(db, tidal) {
  const env = { ACCESS_TOKEN, ADMIN_TOKEN, TIDAL_CLIENT_ID: "device-id", TIDAL_CLIENT_SECRET: "device-secret",
    TIDAL_PKCE_CLIENT_SECRET: "pkce-secret", TIDAL_COUNTRY_CODE: "US", TIDAL_V2_MANIFESTS: "1", DB: d1(db) };
  const calls = [];
  const send = async (request) => {
    const original = globalThis.fetch;
    globalThis.fetch = async (url, options = {}) => { calls.push({ url: String(url), options }); return tidal(String(url), options); };
    try { return await worker.fetch(request, env); } finally { globalThis.fetch = original; }
  };
  const call = (path, headers = {}) => send(new Request(`https://relay.example${path}`, { headers: { "X-Access-Token": ACCESS_TOKEN, ...headers } }));
  const admin = (body, token = ADMIN_TOKEN) => send(new Request("https://relay.example/admin/seats/pkce", {
    method: "POST", headers: { "content-type": "application/json", ...(token ? { "X-Access-Token": token } : {}) }, body: JSON.stringify(body) }));
  return { env, calls, call, admin, api: () => calls.filter((c) => !c.url.startsWith("https://auth.tidal.com/")) };
}

function addSeat(db, id, client = "device", extra = {}) {
  db.prepare(`INSERT INTO tidal_accounts(id,access_token,refresh_token,token_expires_at,state,client,updated_at) VALUES(?,?,?,?,'live',?,?)`)
    .run(id, `token-${id}`, `refresh-${id}`, now() + 3600, client, now());
  for (const [k, v] of Object.entries(extra)) db.prepare(`UPDATE tidal_accounts SET ${k}=? WHERE id=?`).run(v, id);
}
const seat = (db, id) => db.prepare("SELECT * FROM tidal_accounts WHERE id=?").get(id);
const bearer = (c) => c.options?.headers?.authorization;

// ---- Tidal fixtures ----
const CF = "sp-ad-cf.audio.tidal.com";
const FA = "sp-ad-fa.audio.tidal.com";
/** CloudFront's URL-safe base64 of a custom policy expiring at `exp`. */
function cfPolicy(exp, track = 1) {
  const policy = JSON.stringify({ Statement: [{ Resource: `https://${CF}/mediatracks/${track}/*`, Condition: { DateLessThan: { "AWS:EpochTime": exp } } }] });
  return Buffer.from(policy).toString("base64").replaceAll("+", "-").replaceAll("=", "_").replaceAll("/", "~");
}
const signedQuery = (exp) => `Policy=${cfPolicy(exp)}&amp;Signature=abc~def&amp;Key-Pair-Id=K123`;
/** A Tidal-shaped FLAC MPD on `host`, CloudFront-signed until `exp`. */
function mpd({ host = CF, exp = now() + 3600, label = "FLAC", rate = 44100, bits = 16, init, media, base = "" } = {}) {
  const q = signedQuery(exp);
  return `<?xml version="1.0" encoding="UTF-8"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" type="static">${base}<Period><AdaptationSet mimeType="audio/mp4" contentType="audio"><Representation id="${label},${rate},${bits}" codecs="flac" bandwidth="1" audioSamplingRate="${rate}"><SegmentTemplate timescale="${rate}" initialization="${init ?? `https://${host}/mediatracks/1/0.mp4?${q}`}" media="${media ?? `https://${host}/mediatracks/1/$Number$.mp4?${q}`}" startNumber="1"><SegmentTimeline><S d="176128" r="3"/></SegmentTimeline></SegmentTemplate></Representation></AdaptationSet></Period></MPD>`;
}
const v2Answer = ({ formats = ["FLAC"], xml = mpd() } = {}) => new Response(JSON.stringify({ data: { attributes: {
  trackPresentation: "FULL", formats, drmData: null, uri: `data:application/dash+xml;base64,${Buffer.from(xml).toString("base64")}` } } }), { status: 200 });
const v1Dash = (xml, quality = "HI_RES_LOSSLESS", bitDepth = 24, sampleRate = 96000) => new Response(JSON.stringify({
  audioQuality: quality, manifestMimeType: "application/dash+xml", manifest: Buffer.from(xml).toString("base64"), bitDepth, sampleRate }), { status: 200 });
const v1Bts = () => new Response(JSON.stringify({ audioQuality: "LOSSLESS", manifestMimeType: "application/vnd.tidal.bts",
  manifest: Buffer.from(JSON.stringify({ codecs: "flac", urls: ["https://cdn.example/a.flac"] })).toString("base64") }), { status: 200 });
const isV2 = (url) => url.startsWith("https://openapi.tidal.com/v2/trackManifests/");
const isAuth = (url) => url.startsWith("https://auth.tidal.com/");

test("migration 0009: existing seats become device seats", () => {
  const db = database({ before(d) {
    d.prepare(`INSERT INTO tidal_accounts(id,access_token,refresh_token,token_expires_at,state,updated_at) VALUES('old','t','r',0,'live',0)`).run();
  } });
  assert.equal(seat(db, "old").client, "device");
});

test("?web=mse is served by a web seat only, with the exact JSON contract, and cached until 60 s before the signatures expire", async () => {
  const db = database();
  addSeat(db, "dev", "device"); // least recently used: an app stream would take it first
  addSeat(db, "web", "pkce", { last_used_at: 100 });
  const exp = now() + 3500;
  const xml = mpd({ exp });
  const r = relay(db, (url) => (isV2(url) ? v2Answer({ xml }) : new Response("{}", { status: 500 })));
  const res = await r.call("/stream/td:1?web=mse");
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.deepEqual(Object.keys(body).sort(), ["bitDepth", "cdn", "codec", "container", "expiresAt", "hires", "manifest", "mpd", "sampleRate"]);
  assert.deepEqual({ ...body, mpd: undefined }, { codec: "flac", container: "fmp4", manifest: "dash", mpd: undefined, bitDepth: 16, sampleRate: 44100, hires: false, expiresAt: exp, cdn: CF });
  assert.equal(body.mpd, xml, "the MPD is Tidal's, untouched");
  assert.equal(r.api().length, 1);
  assert.ok(isV2(r.api()[0].url), "v2 even though v1 would also answer");
  assert.equal(bearer(r.api()[0]), "Bearer token-web");
  assert.equal(seat(db, "dev").hour_n, 0, "the device seat was never taken");
  assert.equal(seat(db, "web").hour_n, 1);
  const cached = db.prepare("SELECT expires_at FROM tidal_response_cache WHERE cache_key='stream:US:1:aac=:web=mse:v2'").get();
  assert.ok(Math.abs(cached.expires_at - (exp - 60)) <= 1);
  assert.equal(db.prepare("SELECT COUNT(*) n FROM tidal_dash_manifests").get().n, 0, "nothing stored for /dash");
  const again = await r.call("/stream/td:1?web=mse");
  assert.equal((await again.json()).mpd, xml);
  assert.equal(r.api().length, 1, "cache hit: no upstream call");
});

test("?web=mse rotates across live web seats (LRU) under the usual gap, then answers busy with the pacing retry", async () => {
  const db = database();
  addSeat(db, "dev", "device");
  addSeat(db, "209513269", "pkce", { last_used_at: 10 });
  addSeat(db, "209514433", "pkce", { last_used_at: 20 });
  const r = relay(db, (url) => (isV2(url) ? v2Answer() : new Response("{}", { status: 500 })));
  assert.equal((await r.call("/stream/td:1?web=mse")).status, 200);
  assert.equal((await r.call("/stream/td:2?web=mse")).status, 200);
  assert.deepEqual(r.api().map(bearer), ["Bearer token-209513269", "Bearer token-209514433"]);
  const paced = await r.call("/stream/td:3?web=mse");
  assert.equal(paced.status, 503);
  assert.deepEqual(await paced.json(), { error: "busy" });
  assert.equal(paced.headers.get("retry-after"), "15", "both web seats inside their gap; the device seat does not count");
  assert.equal(r.api().length, 2);
  assert.equal(seat(db, "dev").hour_n, 0);
});

test("?web=mse with web seats that are all cooling answers busy with the five-minute retry", async () => {
  const db = database();
  addSeat(db, "dev", "device");
  addSeat(db, "web", "pkce", { cooling_until: now() + 600 });
  const r = relay(db, () => v2Answer());
  const res = await r.call("/stream/td:1?web=mse");
  assert.equal(res.status, 503);
  assert.equal(res.headers.get("retry-after"), "300");
  assert.equal(r.calls.length, 0);
});

test("?web=mse with no live web seat is 404 web_flac_unavailable before any call, uncached; a seat added by the admin route serves next", async () => {
  const db = database();
  addSeat(db, "dev", "device");
  addSeat(db, "gone", "pkce", { state: "dead" });
  const r = relay(db, (url) => (isV2(url) ? v2Answer() : new Response("{}", { status: 500 })));
  const res = await r.call("/stream/td:1?web=mse");
  assert.equal(res.status, 404);
  assert.deepEqual(await res.json(), { error: "web_flac_unavailable" });
  assert.equal(r.calls.length, 0);
  assert.equal(db.prepare("SELECT COUNT(*) n FROM tidal_response_cache").get().n, 0);
  assert.equal((await r.admin({ access_token: "A".repeat(40), refresh_token: "R".repeat(40), expires_in: 86400, user_id: 209513269 })).status, 200);
  const served = await r.call("/stream/td:1?web=mse");
  assert.equal(served.status, 200);
  assert.equal(bearer(r.api()[0]), `Bearer ${"A".repeat(40)}`);
});

test("?web=mse refuses a non-CORS CDN host (sp-ad-fa): 404 web_flac_unavailable, no v1 retry, cached 10 min, logged without URLs", async () => {
  const db = database();
  addSeat(db, "web", "pkce");
  const r = relay(db, (url) => (isV2(url) ? v2Answer({ xml: mpd({ host: FA }) }) : v1Dash(mpd())));
  const lines = [];
  const warn = console.warn;
  console.warn = (l) => lines.push(String(l));
  let res;
  try { res = await r.call("/stream/td:7?web=mse"); } finally { console.warn = warn; }
  assert.equal(res.status, 404);
  assert.deepEqual(await res.json(), { error: "web_flac_unavailable" });
  assert.equal(r.api().length, 1, "v1 on the same seat would get the same host");
  const ttl = db.prepare("SELECT expires_at FROM tidal_response_cache WHERE cache_key='stream:US:7:aac=:web=mse:v2'").get().expires_at - now();
  assert.ok(ttl > 590 && ttl <= 600);
  const line = lines.find((l) => l.startsWith("tidal_web_mse "));
  assert.deepEqual(JSON.parse(line.slice(14)), { track: "7", reason: "non_cors_host", host: FA });
  assert.ok(lines.every((l) => !l.includes("Signature") && !l.includes("token-web")));
});

test("?web=mse refuses foreign hosts, mixed hosts, and relative URLs without an absolute BaseURL; accepts relative ones under a CloudFront BaseURL", async () => {
  const q = signedQuery(now() + 3600);
  const cases = [
    ["foreign host", mpd({ host: "evil.example" }), 404],
    ["mixed hosts", mpd({ media: `https://${FA}/mediatracks/1/$Number$.mp4?${q}` }), 404],
    ["http", mpd({ init: `http://${CF}/mediatracks/1/0.mp4` }), 404],
    ["relative, no BaseURL", mpd({ init: "0.mp4", media: "$Number$.mp4" }), 404],
    ["relative under BaseURL", mpd({ init: `0.mp4?${q}`, media: `$Number$.mp4?${q}`, base: `<BaseURL>https://${CF}/mediatracks/1/?${q}</BaseURL>` }), 200],
  ];
  for (const [name, xml, status] of cases) {
    const db = database();
    addSeat(db, "web", "pkce");
    const r = relay(db, (url) => (isV2(url) ? v2Answer({ xml }) : new Response("{}", { status: 500 })));
    const warn = console.warn;
    console.warn = () => {};
    let res;
    try { res = await r.call("/stream/td:1?web=mse"); } finally { console.warn = warn; }
    assert.equal(res.status, status, name);
    if (status === 404) assert.deepEqual(await res.json(), { error: "web_flac_unavailable" }, name);
  }
});

test("?web=mse falls back to v1 HI_RES_LOSSLESS on the same web seat when v2 has no FLAC; v1 progressive is unusable", async () => {
  const db = database();
  addSeat(db, "web", "pkce");
  const r = relay(db, (url) => (isV2(url) ? v2Answer({ formats: ["AACLC"] }) : v1Dash(mpd({ label: "FLAC_HIRES", rate: 96000, bits: 24 }))));
  const body = await (await r.call("/stream/td:5?web=mse")).json();
  assert.equal(body.codec, "flac");
  assert.equal(body.hires, true);
  assert.equal(body.bitDepth, 24);
  assert.equal(body.sampleRate, 96000);
  assert.equal(r.api().length, 2);
  assert.equal(new URL(r.api()[1].url).searchParams.get("audioquality"), "HI_RES_LOSSLESS");
  assert.ok(r.api().every((c) => bearer(c) === "Bearer token-web"));

  const db2 = database();
  addSeat(db2, "web", "pkce");
  const r2 = relay(db2, (url) => (isV2(url) ? v2Answer({ formats: ["AACLC"] }) : v1Bts()));
  const warn = console.warn;
  console.warn = () => {};
  try {
    const res = await r2.call("/stream/td:6?web=mse");
    assert.equal(res.status, 404);
    assert.deepEqual(await res.json(), { error: "web_flac_unavailable" });
  } finally { console.warn = warn; }
});

test("?web=mse with an already-expired signature is 503 manifest_expired, never cached as a playable answer", async () => {
  const db = database();
  addSeat(db, "web", "pkce");
  const r = relay(db, (url) => (isV2(url) ? v2Answer({ xml: mpd({ exp: now() + 30 }) }) : v1Dash(mpd({ exp: now() + 30 }))));
  const res = await r.call("/stream/td:1?web=mse");
  assert.notEqual(res.status, 200);
  assert.equal(db.prepare("SELECT COUNT(*) n FROM tidal_response_cache WHERE response_json LIKE '%\"mpd\"%'").get().n, 0);
});

test("?web=mse with ?aac is a 400 and costs nothing", async () => {
  const db = database();
  addSeat(db, "web", "pkce");
  const r = relay(db, () => v2Answer());
  for (const q of ["aac=1", "aac=only"]) {
    const res = await r.call(`/stream/td:1?web=mse&${q}`);
    assert.equal(res.status, 400, q);
  }
  assert.equal(r.calls.length, 0);
  assert.equal(seat(db, "web").hour_n, 0);
});

test("app modes prefer device seats; a web seat serves them only when no device seat is free", async () => {
  const db = database();
  addSeat(db, "web", "pkce"); // least recently used, yet device comes first
  addSeat(db, "dev", "device", { last_used_at: 100 });
  const r = relay(db, (url) => (isV2(url) ? v2Answer() : new Response("{}", { status: 500 })));
  assert.equal((await r.call("/stream/td:1")).status, 200);
  assert.equal((await r.call("/stream/td:2")).status, 200, "device seat inside its gap: the web seat is spare capacity");
  assert.deepEqual(r.api().map(bearer), ["Bearer token-dev", "Bearer token-web"]);
  const app = await (await r.call("/stream/td:1")).json();
  assert.match(app.url, /^https:\/\/relay\.example\/dash\/td\/1\.mpd\?/, "the app's answer shape is unchanged (no mpd field)");
  assert.equal(app.mpd, undefined);
});

test("app modes are unchanged with no web seats: device seats only, same cache keys", async () => {
  const db = database();
  addSeat(db, "a", "device");
  addSeat(db, "b", "device", { last_used_at: 50 });
  const r = relay(db, (url) => (isV2(url) ? v2Answer() : url.includes("/search/") ? new Response(JSON.stringify({ items: [] }), { status: 200 }) : new Response("{}", { status: 500 })));
  assert.equal((await r.call("/stream/td:1")).status, 200);
  assert.equal((await r.call("/search?q=x")).status, 200);
  assert.deepEqual(r.api().map(bearer), ["Bearer token-a", "Bearer token-b"]);
  assert.ok(db.prepare("SELECT 1 FROM tidal_response_cache WHERE cache_key='stream:US:1:aac=:web=app:v2'").get());
});

test("a web seat refreshes with python-tidal's PKCE grant (PKCE client id + TIDAL_PKCE_CLIENT_SECRET, no scope); device seats keep theirs", async () => {
  for (const [client, want] of [
    ["pkce", { grant_type: "refresh_token", refresh_token: "refresh-s", client_id: "6BDSRdpK9hqEBTgU", client_secret: "pkce-secret" }],
    ["device", { client_id: "device-id", refresh_token: "refresh-s", grant_type: "refresh_token", scope: "r_usr+w_usr+w_sub", client_secret: "device-secret" }],
  ]) {
    const db = database();
    addSeat(db, "s", client, { token_expires_at: 0 });
    const r = relay(db, (url) => (isAuth(url)
      ? new Response(JSON.stringify({ access_token: "fresh-token", refresh_token: "rotated", expires_in: 3600 }), { status: 200 })
      : v2Answer()));
    assert.equal((await r.call(`/stream/td:1${client === "pkce" ? "?web=mse" : ""}`)).status, 200, client);
    const auth = r.calls.find((c) => isAuth(c.url));
    assert.deepEqual(Object.fromEntries(new URLSearchParams(String(auth.options.body))), want, client);
    assert.equal(bearer(r.api()[0]), "Bearer fresh-token");
    assert.equal(seat(db, "s").refresh_token, "rotated", "Tidal rotates refresh tokens: the new one is kept");
    assert.equal(seat(db, "s").client, client);
  }
});

test("POST /admin/seats/pkce: ADMIN_TOKEN required, body validated, upserts a live web seat, never echoes or logs tokens", async () => {
  const db = database();
  addSeat(db, "209513269", "device", { state: "reserve", strikes: 3, cooling_until: now() + 999 });
  const r = relay(db, () => new Response("{}", { status: 500 }));
  const good = { access_token: "eyJ" + "a".repeat(60), refresh_token: "eyJ" + "b".repeat(60), expires_in: 604800, user_id: "209513269" };
  assert.equal((await r.admin(good, null)).status, 401);
  assert.equal((await r.admin(good, ACCESS_TOKEN)).status, 401, "the app's access token is not an admin token");
  for (const bad of [{}, { ...good, user_id: "abc" }, { ...good, expires_in: 0 }, { ...good, access_token: "short" }, { ...good, refresh_token: undefined }]) {
    assert.equal((await r.admin(bad)).status, 400);
  }
  assert.equal(seat(db, "209513269").client, "device", "rejected calls change nothing");
  const lines = [];
  const log = console.log;
  console.log = (l) => lines.push(String(l));
  let res;
  try { res = await r.admin(good); } finally { console.log = log; }
  assert.equal(res.status, 200);
  const text = await res.text();
  assert.ok(!text.includes(good.access_token) && !text.includes(good.refresh_token));
  assert.deepEqual(JSON.parse(text), { status: "ok", account_id: "209513269", client: "pkce", state: "live", replaced: { client: "device", state: "reserve" } });
  const s = seat(db, "209513269");
  assert.equal(s.client, "pkce");
  assert.equal(s.state, "live");
  assert.equal(s.access_token, good.access_token);
  assert.equal(s.strikes, 0);
  assert.equal(s.cooling_until, 0);
  assert.ok(Math.abs(s.token_expires_at - (now() + 604800)) <= 1);
  assert.ok(lines.every((l) => !l.includes(good.access_token.slice(0, 20))));
  assert.equal((await r.admin({ ...good, user_id: 209514433 })).status, 200, "numeric user_id; a second web seat");
  assert.equal(db.prepare("SELECT COUNT(*) n FROM tidal_accounts WHERE client='pkce' AND state='live'").get().n, 2);
  assert.equal(r.calls.length, 0, "no Tidal call");
});

test("signedUrlExpiry reads CloudFront custom/canned policies and Tidal tokens; mpdUrls unescapes &amp;", () => {
  const exp = 1_800_000_000;
  const u = new URL(`https://${CF}/mediatracks/1/0.mp4?Policy=${cfPolicy(exp)}&Signature=x&Key-Pair-Id=K`);
  assert.equal(signedUrlExpiry(u), exp);
  assert.equal(signedUrlExpiry(new URL(`https://${CF}/a?Expires=${exp}&Signature=x`)), exp);
  assert.equal(signedUrlExpiry(new URL(`https://${FA}/a?token=${exp}~abc`)), exp);
  assert.equal(signedUrlExpiry(new URL(`https://${CF}/a?Policy=!!!&Expires=${exp + 5}`)), exp + 5, "a broken policy falls through");
  assert.equal(signedUrlExpiry(new URL(`https://${CF}/a`)), 0);
  const urls = mpdUrls(mpd({ exp, base: `<BaseURL>https://${CF}/x/</BaseURL>` }));
  assert.equal(urls.length, 3);
  assert.ok(urls.every((x) => !x.value.includes("&amp;")));
  assert.equal(signedUrlExpiry(new URL(urls[1].value)), exp);
});
