import test from "node:test";
import assert from "node:assert/strict";
import worker, { networkOf, networkTag } from "../src/index.js";

const ACCESS_TOKEN = "test-access-token";
const MPD = '<MPD><SegmentTemplate initialization="https://sp-ad-fa.audio.tidal.com/init.mp4?token=test" media="https://audio.tidal.com/$Number$" /></MPD>';

/**
 * `pool: true` simulates tidal_accounts selection (caps, cooling, per-seat call gap, LRU)
 * and mutates `accounts` in place; otherwise takeAccount hands out `accounts` in order.
 */
function fixture({ tidalResponse, account = liveAccount(), accounts = [account], pool = false } = {}) {
  const eligible = (a, [hour, day, t, hourly, daily]) => (a.state || "live") === "live" && (a.cooling_until || 0) <= t
    && (a.hour_key !== hour || a.hour_n < hourly) && (a.day_key !== day || a.day_n < daily);
  const bump = (a, hour, day) => {
    a.hour_n = a.hour_key === hour ? a.hour_n + 1 : 1; a.hour_key = hour;
    a.day_n = a.day_key === day ? a.day_n + 1 : 1; a.day_key = day;
  };
  const manifests = new Map();
  const calls = [];
  const dbWrites = [];
  const dailyQuotas = new Map();
  const hiresCache = new Map();
  const responseCache = new Map();
  const upstream429Events = [];
  let softOpenState = null;
  const accountCaps = [];
  let nextAccount = 0;
  const env = {
    ACCESS_TOKEN,
    ADMIN_TOKEN: "test-admin-token",
    TIDAL_CLIENT_ID: "test-client-id",
    TIDAL_COUNTRY_CODE: "US",
    DB: {
      prepare(sql) {
        return {
          args: [],
          bind(...args) { this.args = args; return this; },
          async first() {
            if (sql.includes("hour_n=CASE") && sql.includes("RETURNING *")) {
              accountCaps.push({ hourly: this.args[3], daily: this.args[4] });
              if (!pool) return accounts[nextAccount++] || null;
              const [hour, day, t, , , gapCutoff] = this.args;
              const pick = accounts
                .filter((a) => eligible(a, this.args) && (a.last_call_at || 0) <= gapCutoff)
                .sort((x, y) => (x.last_used_at || 0) - (y.last_used_at || 0))[0];
              if (!pick) return null;
              bump(pick, hour, day);
              pick.last_used_at = t;
              pick.last_call_at = t;
              return { ...pick };
            }
            if (sql.includes("COUNT(*) AS n FROM tidal_accounts")) {
              return pool ? { n: accounts.filter((a) => eligible(a, this.args)).length } : null;
            }
            if (sql.includes("SELECT manifest_xml,init_url,expires_at")) return manifests.get(this.args[0]) || null;
            if (sql.includes("FROM tidal_response_cache")) {
              const hit = responseCache.get(this.args[0]);
              return hit && hit.expires_at > this.args[1] ? { response_json: hit.response_json } : null;
            }
            if (sql.includes("FROM tidal_soft_open_state")) return softOpenState;
            if (sql.includes("FROM tidal_hires_cache")) {
              const hit = hiresCache.get(this.args[0]);
              return hit && hit.checked_at > this.args[1] ? { hires: hit.hires } : null;
            }
            return null;
          },
          async run() {
            dbWrites.push({ sql, args: this.args });
            if (pool && sql.includes("hour_n=CASE") && sql.includes("WHERE id=?3")) {
              const [hour, day, id] = this.args;
              const seat = accounts.find((a) => a.id === id);
              if (seat) bump(seat, hour, day);
            }
            if (sql.includes("INSERT INTO tidal_daily_quotas")) {
              const [day, key] = this.args;
              const mapKey = `${day}:${key}`;
              dailyQuotas.set(mapKey, (dailyQuotas.get(mapKey) || 0) + 1);
            }
            if (sql.includes("INTO tidal_hires_cache")) {
              const [trackId, hires, checkedAt] = this.args;
              hiresCache.set(trackId, { hires, checked_at: checkedAt });
            }
            if (sql.includes("INTO tidal_response_cache")) {
              const [key, responseJson, expiresAt] = this.args;
              responseCache.set(key, { response_json: responseJson, expires_at: expiresAt });
            }
            if (sql.includes("INTO tidal_soft_open_state")) {
              if (!softOpenState) softOpenState = { started_at: this.args[0] };
            }
            if (sql.includes("INTO tidal_upstream_429_events")) {
              const [accountId, endpoint, hourN, dayN, occurredAt] = this.args;
              upstream429Events.push({ account_id: accountId, endpoint, hour_n: hourN, day_n: dayN, occurred_at: occurredAt });
            }
            if (sql.includes("INSERT OR REPLACE INTO tidal_dash_manifests")) {
              const [trackId, manifestXml, initUrl, expiresAt] = this.args;
              manifests.set(trackId, { manifest_xml: manifestXml, init_url: initUrl, expires_at: expiresAt });
            }
            return { success: true };
          },
          async all() {
            if (sql.includes("FROM tidal_daily_quotas")) {
              const [day, keysJson] = this.args;
              const keys = JSON.parse(keysJson);
              return { results: keys.flatMap((key) => {
                const n = dailyQuotas.get(`${day}:${key}`) || 0;
                return n ? [{ key, n }] : [];
              }) };
            }
            return { results: [] };
          },
        };
      },
      async batch(statements) {
        for (const statement of statements) await statement.run();
        return statements.map(() => ({ success: true }));
      },
    },
  };

  const fetchTidal = async (url, options) => {
    calls.push({ url: String(url), options });
    if (tidalResponse) return tidalResponse(String(url), options);
    return new Response(JSON.stringify({ items: [] }), { status: 200 });
  };

  return { env, accounts, calls, manifests, fetchTidal, dbWrites, dailyQuotas, accountCaps, hiresCache, responseCache, upstream429Events, get softOpenState() { return softOpenState; } };
}

function liveAccount() {
  return {
    id: "test-account",
    access_token: "test-tidal-access-token",
    refresh_token: "test-tidal-refresh-token",
    token_expires_at: Math.floor(Date.now() / 1000) + 3600,
    generation: 1,
    hour_n: 1,
    day_n: 1,
  };
}

async function withFetch(t, fetchImpl, action) {
  const original = globalThis.fetch;
  globalThis.fetch = fetchImpl;
  try { return await action(); } finally { globalThis.fetch = original; }
}

function authorizedRequest(path, method = "GET", extraHeaders = {}) {
  return new Request(`https://relay.example${path}`, {
    method,
    headers: { "X-Access-Token": ACCESS_TOKEN, ...extraHeaders },
  });
}

function losslessResponse() {
  return playbackInfo("LOSSLESS", "application/vnd.tidal.bts",
    JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] }));
}

function playbackInfo(audioQuality, manifestMimeType, payload) {
  return new Response(JSON.stringify({
    audioQuality,
    manifestMimeType,
    manifest: Buffer.from(payload).toString("base64"),
    bitDepth: 24,
    sampleRate: 96000,
  }), { status: 200 });
}

test("health and route/auth gates", async () => {
  const f = fixture();
  await withFetch(null, f.fetchTidal, async () => {
    const health = await worker.fetch(new Request("https://relay.example/health"), f.env);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { ok: true, provider: "tidal" });

    const unauthorized = await worker.fetch(new Request("https://relay.example/search?q=test"), f.env);
    assert.equal(unauthorized.status, 401);
    assert.equal(f.calls.length, 0);

    const unknown = await worker.fetch(new Request("https://relay.example/nope"), f.env);
    assert.equal(unknown.status, 404);
  });
});

test("request limiter protects provider routes while health remains available", async () => {
  const f = fixture();
  f.env.REQUEST_RL = { limit: async () => ({ success: false }) };

  await withFetch(null, f.fetchTidal, async () => {
    const limited = await worker.fetch(authorizedRequest("/search?q=test"), f.env);
    assert.equal(limited.status, 429);
    assert.equal(limited.headers.get("retry-after"), "60");

    const health = await worker.fetch(new Request("https://relay.example/health"), f.env);
    assert.equal(health.status, 200);
  });
});

test("request limiter keys IPv6 clients by their /56 network", async () => {
  const f = fixture();
  let limitKey;
  f.env.REQUEST_RL = { limit: async ({ key }) => { limitKey = key; return { success: true }; } };
  await withFetch(null, f.fetchTidal, async () => {
    await worker.fetch(authorizedRequest("/search?q=test", "GET", { "CF-Connecting-IP": "2001:db8:abcd:12:3456::1" }), f.env);
  });
  assert.equal(limitKey, "2001:db8:abcd:0/56");
});

test("per-account cap defaults use the Tidal pool limits", async () => {
  const f = fixture();
  await withFetch(null, f.fetchTidal, async () => {
    await worker.fetch(authorizedRequest("/search?q=test"), f.env);
  });
  assert.deepEqual(f.accountCaps[0], { hourly: 250, daily: 1600 });
});

test("an exhausted pool returns busy with a five-minute retry before contacting Tidal", async () => {
  const f = fixture({ accounts: [] });
  await withFetch(null, f.fetchTidal, async () => {
    for (const path of ["/search?q=example", "/stream/td:321"]) {
      const response = await worker.fetch(authorizedRequest(path), f.env);
      assert.equal(response.status, 503);
      assert.equal(response.headers.get("retry-after"), "300");
      assert.deepEqual(await response.json(), { error: "busy" });
    }
  });
  assert.equal(f.calls.length, 0);
});

test("soft-open sheds the configured share of cache misses for its one-time window", async () => {
  const f = fixture();
  f.env.TIDAL_SOFT_OPEN_PERCENT = "100";
  f.env.TIDAL_SOFT_OPEN_SECONDS = "600";
  await withFetch(null, f.fetchTidal, async () => {
    const first = await worker.fetch(authorizedRequest("/search?q=soft-open"), f.env);
    const second = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    for (const response of [first, second]) {
      assert.equal(response.status, 503);
      assert.equal(response.headers.get("retry-after"), "300");
      assert.deepEqual(await response.json(), { error: "busy" });
    }
  });
  assert.ok(f.softOpenState?.started_at);
  assert.equal(f.calls.length, 0);
  assert.equal(f.accountCaps.length, 0);
});

test("Tidal search 429 cools that account and returns busy without caching an empty result", async () => {
  const f = fixture({ tidalResponse: async () => new Response("{}", { status: 429, headers: { "Retry-After": "60" } }) });
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/search?q=example"), f.env);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "busy" });
    assert.equal(response.headers.get("retry-after"), "300");
  });
  assert.equal(f.calls.length, 1);
  assert.equal(f.responseCache.size, 0);
  assert.ok(f.dbWrites.some(({ sql, args }) => sql.includes("strikes=") && args[1] === 120 && args[2] === 960), "first strike: 120 s, capped at 960");
  assert.deepEqual(f.upstream429Events.map(({ endpoint, hour_n, day_n }) => ({ endpoint, hour_n, day_n })), [{ endpoint: "search", hour_n: 1, day_n: 1 }]);
});

test("Tidal playback-info 429 cools that account and returns busy without caching a track miss", async () => {
  const f = fixture({ tidalResponse: async () => new Response("{}", { status: 429, headers: { "Retry-After": "60" } }) });
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "busy" });
    assert.equal(response.headers.get("retry-after"), "300");
  });
  assert.equal(f.calls.length, 1, "a 429 is not retried across qualities or regions");
  assert.equal(f.responseCache.size, 0);
  assert.ok(f.dbWrites.some(({ sql, args }) => sql.includes("strikes=") && args[1] === 120 && args[2] === 960), "first strike: 120 s, capped at 960");
  assert.deepEqual(f.upstream429Events.map(({ endpoint, hour_n, day_n }) => ({ endpoint, hour_n, day_n })), [{ endpoint: "playbackinfo", hour_n: 1, day_n: 1 }]);
});

test("global daily stream cap allows requests to the cap and blocks the next one", async () => {
  const accounts = Array.from({ length: 4 }, (_, i) => ({ ...liveAccount(), id: `account-${i}` }));
  const f = fixture({ accounts, tidalResponse: async () => losslessResponse() });
  f.env.GLOBAL_DAILY_CAP = "2";
  f.env.NETWORK_DAILY_CAP = "10";
  f.env.IP_SALT = "test-salt";
  const headers = { "CF-Connecting-IP": "192.0.2.15" };

  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:321", "GET", headers), f.env)).status, 200);
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:322", "GET", headers), f.env)).status, 200);
    const limited = await worker.fetch(authorizedRequest("/stream/td:323", "GET", headers), f.env);
    assert.equal(limited.status, 503);
    assert.deepEqual(await limited.json(), { error: "global_daily_cap" });
  });

  const day = new Date().toISOString().slice(0, 10);
  const networkKey = `n:${networkTag(f.env, networkOf(headers["CF-Connecting-IP"]))}`;
  assert.equal(f.dailyQuotas.get(`${day}:global`), 2);
  assert.equal(f.dailyQuotas.get(`${day}:${networkKey}`), 2);
});

test("cache hits never charge or hit the global cap; the network key is charged for every answer", async () => {
  const expiry = Math.floor(Date.now() / 1000) + 1800;
  const cachedFlac = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: [`https://cdn.example/t.flac?token=${expiry}~sig`] });
  const f = fixture({ accounts: [liveAccount(), liveAccount()], tidalResponse: async () => playbackInfo("LOSSLESS", "application/vnd.tidal.bts", cachedFlac) });
  f.env.GLOBAL_DAILY_CAP = "1";
  f.env.NETWORK_DAILY_CAP = "10";
  f.env.IP_SALT = "test-salt";
  const day = new Date().toISOString().slice(0, 10);
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:321", "GET", { "CF-Connecting-IP": "192.0.2.15" }), f.env)).status, 200);
    for (const ip of ["192.0.2.16", "192.0.2.17", "192.0.2.15"]) {
      assert.equal((await worker.fetch(authorizedRequest("/stream/td:321", "GET", { "CF-Connecting-IP": ip }), f.env)).status, 200, `cache hit from ${ip}`);
    }
    const miss = await worker.fetch(authorizedRequest("/stream/td:999", "GET", { "CF-Connecting-IP": "192.0.2.16" }), f.env);
    assert.equal(miss.status, 503);
    assert.deepEqual(await miss.json(), { error: "global_daily_cap" });
  });
  assert.equal(f.calls.length, 1);
  assert.equal(f.accountCaps.length, 1, "the capped miss took no seat");
  assert.equal(f.dailyQuotas.get(`${day}:global`), 1, "only the upstream answer charged global");
  assert.equal(f.dailyQuotas.get(`${day}:n:${networkTag(f.env, networkOf("192.0.2.15"))}`), 2);
  assert.equal(f.dailyQuotas.get(`${day}:n:${networkTag(f.env, networkOf("192.0.2.16"))}`), 1, "the refused miss charged nothing");
});

test("per-network daily cap is salted, shared across installs, and isolates other networks", async () => {
  const accounts = Array.from({ length: 4 }, (_, i) => ({ ...liveAccount(), id: `account-${i}` }));
  const f = fixture({ accounts, tidalResponse: async () => losslessResponse() });
  f.env.GLOBAL_DAILY_CAP = "10";
  f.env.NETWORK_DAILY_CAP = "1";
  f.env.IP_SALT = "test-salt";
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:321", "GET", { "CF-Connecting-IP": "192.0.2.15" }), f.env)).status, 200);
    const sameNetwork = await worker.fetch(authorizedRequest("/stream/td:322", "GET", { "CF-Connecting-IP": "192.0.2.15" }), f.env);
    assert.equal(sameNetwork.status, 429);
    assert.deepEqual(await sameNetwork.json(), { error: "network_daily_cap" });
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:323", "GET", { "CF-Connecting-IP": "192.0.2.16" }), f.env)).status, 200);
  });
  assert.equal([...f.dailyQuotas.keys()].filter((key) => key.endsWith(":global")).length, 1);
  assert.equal([...f.dailyQuotas.values()].reduce((sum, n) => sum + n, 0), 4);
});

test("search spends an account slot and the network search quota, never the stream quotas", async () => {
  const f = fixture();
  f.env.IP_SALT = "test-salt";
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/search?q=example", "GET", { "CF-Connecting-IP": "192.0.2.15" }), f.env);
    assert.equal(response.status, 200);
  });
  assert.equal(f.accountCaps.length, 1);
  const tag = networkTag(f.env, networkOf("192.0.2.15"));
  assert.deepEqual([...f.dailyQuotas.entries()], [[`${new Date().toISOString().slice(0, 10)}:s:${tag}`, 1]]);
});

test("uncached searches are capped per network per day; cache hits are free and other networks unaffected", async () => {
  const f = fixture({ accounts: Array.from({ length: 6 }, (_, i) => ({ ...liveAccount(), id: `s-${i}` })) });
  f.env.IP_SALT = "test-salt";
  f.env.NETWORK_SEARCH_DAILY_CAP = "2";
  const from = (ip) => ({ "CF-Connecting-IP": ip });
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/search?q=one", "GET", from("192.0.2.15")), f.env)).status, 200);
    assert.equal((await worker.fetch(authorizedRequest("/search?q=two", "GET", from("192.0.2.15")), f.env)).status, 200);
    const over = await worker.fetch(authorizedRequest("/search?q=three", "GET", from("192.0.2.15")), f.env);
    assert.equal(over.status, 429);
    assert.equal(over.headers.get("retry-after"), "3600");
    assert.deepEqual(await over.json(), { error: "network_daily_cap" });
    assert.equal((await worker.fetch(authorizedRequest("/search?q=one", "GET", from("192.0.2.15")), f.env)).status, 200, "a cache hit is free");
    assert.equal((await worker.fetch(authorizedRequest("/search?q=three", "GET", from("192.0.2.16")), f.env)).status, 200);
  });
  assert.equal(f.accountCaps.length, 3, "the refused search never took a seat");
  const day = new Date().toISOString().slice(0, 10);
  assert.equal(f.dailyQuotas.get(`${day}:s:${networkTag(f.env, networkOf("192.0.2.15"))}`), 2);
});

test("a search that finds no seat spends no search quota", async () => {
  const f = fixture({ accounts: [] });
  f.env.IP_SALT = "test-salt";
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/search?q=x", "GET", { "CF-Connecting-IP": "192.0.2.15" }), f.env)).status, 503);
  });
  assert.equal(f.dailyQuotas.size, 0);
});

test("UPSTREAM_RL: over a network's fair share a cache miss is busy (Retry-After 30) before any seat; hits and health still answer", async () => {
  const f = fixture({ accounts: [liveAccount(), liveAccount(), liveAccount()], tidalResponse: async (url) => (url.includes("/search/")
    ? new Response(JSON.stringify({ items: [{ id: 1, title: "x" }] }), { status: 200 })
    : losslessResponse()) });
  const keys = [];
  let allow = true;
  f.env.UPSTREAM_RL = { limit: async ({ key }) => { keys.push(key); return { success: allow }; } };
  const ip = { "CF-Connecting-IP": "2001:db8:abcd:12:3456::1" };
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/search?q=cached", "GET", ip), f.env)).status, 200);
    allow = false;
    for (const path of ["/search?q=fresh", "/stream/td:321"]) {
      const r = await worker.fetch(authorizedRequest(path, "GET", ip), f.env);
      assert.equal(r.status, 503, path);
      assert.equal(r.headers.get("retry-after"), "30");
      assert.deepEqual(await r.json(), { error: "busy" });
    }
    assert.equal((await worker.fetch(authorizedRequest("/search?q=cached", "GET", ip), f.env)).status, 200, "a cache hit takes no seat, so it is not limited");
    assert.equal((await worker.fetch(new Request("https://relay.example/health"), f.env)).status, 200);
  });
  assert.equal(f.accountCaps.length, 1);
  assert.equal(f.calls.length, 1);
  assert.deepEqual(keys, ["2001:db8:abcd:0/56", "2001:db8:abcd:0/56", "2001:db8:abcd:0/56"], "keyed by network, consulted only on a cache miss");
});

test("UPSTREAM_RL refusing the hires lookup leaves hires unknown and still answers the stream", async () => {
  let n = 0;
  const f = fixture({ accounts: [liveAccount(), liveAccount()], tidalResponse: aacWithMeta(() => new Response(JSON.stringify({ audioQuality: "HI_RES_LOSSLESS" }), { status: 200 })) });
  f.env.UPSTREAM_RL = { limit: async () => ({ success: n++ === 0 }) }; // the playback call passes, the lookup does not
  await withFetch(null, f.fetchTidal, async () => {
    const r = await worker.fetch(authorizedRequest("/stream/td:42?aac=only"), f.env);
    assert.equal(r.status, 200);
    assert.equal("hires" in await r.json(), false);
  });
  assert.equal(metaCalls(f).length, 0);
  assert.equal(f.accountCaps.length, 1);
});

test("search maps Tidal catalog entries to the relay contract", async () => {
  const f = fixture({ tidalResponse: async () => new Response(JSON.stringify({ items: [{
    id: 321,
    title: "Example",
    artist: { name: "Artist" },
    album: { title: "Album", cover: "abc-def" },
    duration: 180,
    audioQuality: "LOSSLESS",
    isrc: "USABC1234567",
  }] }), { status: 200 }) });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/search?q=example"), f.env);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.tracks[0].id, "td:321");
    assert.equal(body.tracks[0].audioQuality, "LOSSLESS");
    assert.equal(body.tracks[0].format, "flac");
    assert.equal(f.calls.length, 1);
  });
});

test("search cache is shared across normalized query variants for five days", async () => {
  const f = fixture({ tidalResponse: async () => new Response(JSON.stringify({ items: [{ id: 88, title: "Birds" }] }), { status: 200 }) });
  await withFetch(null, f.fetchTidal, async () => {
    const first = await worker.fetch(authorizedRequest("/search?q=%20%20Birds%20%20of%20%20Feather%20"), f.env);
    const second = await worker.fetch(authorizedRequest("/search?q=birds%20of%20feather"), f.env);
    assert.deepEqual(await first.json(), await second.json());
  });
  assert.equal(f.calls.length, 1);
  assert.equal(f.accountCaps.length, 1);
  const [key, entry] = [...f.responseCache.entries()][0];
  assert.match(key, /^search:US:[a-f0-9]{64}$/);
  assert.equal(key.includes("birds"), false, "the normalized query is not stored as a cache key");
  assert.ok(entry.expires_at >= Math.floor(Date.now() / 1000) + 5 * 86400 - 2);
});

test("successful stream answers are shared only until shortly before the Tidal URL expires", async () => {
  const expiry = Math.floor(Date.now() / 1000) + 1800;
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: [`https://cdn.example/track.flac?token=${expiry}~sig`] });
  const f = fixture({ tidalResponse: async (url) => {
    const quality = new URL(url).searchParams.get("audioquality");
    return playbackInfo(quality === "HI_RES_LOSSLESS" ? "HI_RES_LOSSLESS" : "LOSSLESS", "application/vnd.tidal.bts", direct);
  } });
  await withFetch(null, f.fetchTidal, async () => {
    const first = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    const firstBody = await first.json();
    const firstCallCount = f.calls.length;
    const second = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.deepEqual(await second.json(), firstBody);
    assert.equal(f.calls.length, firstCallCount);
  });
  assert.equal(f.accountCaps.length, 1);
  const entry = [...f.responseCache.values()][0];
  assert.ok(entry.expires_at <= expiry - 60);
  assert.ok(entry.expires_at > Math.floor(Date.now() / 1000));
});

test("?aac is normalised to '', '1' or 'only' before the cache key: junk values share the plain namespace", async () => {
  const f = fixture({ accounts: [liveAccount(), liveAccount(), liveAccount()], tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", AAC_BTS) });
  await withFetch(null, f.fetchTidal, async () => {
    for (const q of ["", "?aac=0", "?aac=x1", "?aac=ONLY", "?aac=only1"]) {
      const r = await worker.fetch(authorizedRequest(`/stream/td:5${q}`), f.env);
      assert.deepEqual(await r.json(), { error: "lossless_unavailable" }, q);
    }
  });
  assert.equal(f.calls.length, 1, "every junk ?aac is a cache hit on the plain answer");
  assert.deepEqual([...f.responseCache.keys()], ["stream:US:5:aac=:web=app"]);
});

test("progressive FLAC and AAC answers carry expiresAt from the Tidal URL; omitted when unreadable; DASH keeps its shape", async () => {
  const expiry = Math.floor(Date.now() / 1000) + 1800;
  const bts = (codecs, file) => JSON.stringify({ codecs, urls: [`https://cdn.example/${file}?token=${expiry}~sig`] });
  const cases = [
    ["/stream/td:1", () => playbackInfo("LOSSLESS", "application/vnd.tidal.bts", bts("flac", "a.flac")), expiry],
    ["/stream/td:2?aac=1", () => playbackInfo("HIGH", "application/vnd.tidal.bts", bts("mp4a.40.2", "a.m4a")), expiry],
    ["/stream/td:3?aac=only", () => playbackInfo("HIGH", "application/vnd.tidal.bts", bts("mp4a.40.2", "a.m4a")), expiry],
    ["/stream/td:4", () => losslessResponse(), undefined],
    ["/stream/td:5?aac=1", () => aacResponse(), undefined],
  ];
  for (const [path, tidal, want] of cases) {
    const f = fixture({ accounts: [liveAccount(), liveAccount()], tidalResponse: async (url) => (url.includes("/playbackinfopostpaywall") ? tidal() : new Response("{}", { status: 500 })) });
    await withFetch(null, f.fetchTidal, async () => {
      const body = await (await worker.fetch(authorizedRequest(path), f.env)).json();
      assert.equal(body.expiresAt, want, path);
      if (want === undefined) assert.equal("expiresAt" in body, false, path);
    });
  }
  const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", MPD) });
  await withFetch(null, f.fetchTidal, async () => {
    const body = await (await worker.fetch(authorizedRequest("/stream/td:6"), f.env)).json();
    assert.equal("expiresAt" in body, false, "DASH answers carry their expiry in the signed URL");
  });
});

test("track-level lossless refusals are cached across app requests", async () => {
  const accounts = [liveAccount(), { ...liveAccount(), id: "second-account" }];
  const f = fixture({ accounts, tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", "{}") });
  await withFetch(null, f.fetchTidal, async () => {
    const first = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.deepEqual(await first.json(), { error: "lossless_unavailable" });
    const callsAfterMiss = f.calls.length;
    const second = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.deepEqual(await second.json(), { error: "lossless_unavailable" });
    assert.equal(f.calls.length, callsAfterMiss);
  });
  assert.equal(f.responseCache.size, 1);
  assert.equal(f.accountCaps.length, 1, "the second request spends a relay request quota but takes no Tidal account");
});

const playbackCalls = (f) => f.calls.filter((c) => c.url.includes("/playbackinfopostpaywall"));
const FLAC_BTS = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
const AAC_BTS = JSON.stringify({ mimeType: "audio/mp4", codecs: "mp4a.40.2", urls: ["https://cdn.example/track.m4a"] });

test("a CD-only track (HIGH answer) costs one playbackinfo call, one seat, and is never cross-checked", async () => {
  const accounts = [liveAccount(), { ...liveAccount(), id: "second-account", access_token: "second-token" }];
  const f = fixture({ accounts, tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", AAC_BTS) });
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "lossless_unavailable" });
  });
  assert.equal(f.calls.length, 1);
  const p = new URL(f.calls[0].url).searchParams;
  assert.equal(p.get("audioquality"), "HI_RES_LOSSLESS");
  assert.equal(p.get("countryCode"), "US");
  assert.equal(f.accountCaps.length, 1, "no second seat is taken for a below-lossless answer");
  assert.equal(f.calls.some(({ options }) => options.headers.authorization === "Bearer second-token"), false);
  assert.equal(f.dbWrites.some(({ sql }) => sql.includes("SET cooling_until") || sql.includes("state='dead'")), false);
});

test("?aac=1 answers a CD-only track with AAC from the same single call", async () => {
  const accounts = [liveAccount(), { ...liveAccount(), id: "second-account" }];
  const f = fixture({ accounts, tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", AAC_BTS) });
  await withFetch(null, f.fetchTidal, async () => {
    const body = await (await worker.fetch(authorizedRequest("/stream/td:1550546?aac=1"), f.env)).json();
    assert.equal(body.codec, "aac");
    assert.equal(body.bitrate, 320);
  });
  assert.equal(f.calls.length, 1);
  assert.equal(f.accountCaps.length, 1);
});

test("a hi-res track costs one playbackinfo call", async () => {
  const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", MPD) });
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:654"), f.env);
    assert.equal(response.status, 200);
    assert.equal((await response.json()).manifest, "dash");
  });
  assert.equal(playbackCalls(f).length, 1);
  assert.equal(f.calls.length, 1);
});

test("a LOSSLESS answer to the HI_RES_LOSSLESS request is served as FLAC", async () => {
  const f = fixture({ tidalResponse: async () => playbackInfo("LOSSLESS", "application/vnd.tidal.bts", FLAC_BTS) });
  await withFetch(null, f.fetchTidal, async () => {
    const body = await (await worker.fetch(authorizedRequest("/stream/td:321"), f.env)).json();
    assert.equal(body.codec, "flac");
    assert.equal(body.url, "https://cdn.example/track.flac");
  });
  assert.equal(f.calls.length, 1);
});

test("a 401 whose refresh cannot complete cools that seat (never kills it) and the next account serves", async () => {
  const accounts = [liveAccount(), { ...liveAccount(), id: "reserve-account", access_token: "reserve-token" }];
  const f = fixture({ accounts, tidalResponse: async (_url, options) => {
    if (options.headers.authorization === "Bearer test-tidal-access-token") return new Response("{}", { status: 401 });
    return playbackInfo("LOSSLESS", "application/vnd.tidal.bts", FLAC_BTS);
  } });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 200);
  });
  assert.ok(f.dbWrites.some(({ sql, args }) => sql.includes("SET cooling_until=?") && args[1] === "test-account"));
  assert.equal(f.dbWrites.some(({ sql }) => sql.includes("state='dead'")), false);
});

test("/stream answers another upstream 4xx with 502 upstream_error, on one seat and no quota", async () => {
  for (const status of [400, 403]) {
    const f = fixture({ accounts: [liveAccount(), liveAccount()], tidalResponse: async () => new Response("{}", { status }) });
    f.env.IP_SALT = "test-salt";
    await withFetch(null, f.fetchTidal, async () => {
      const r = await worker.fetch(authorizedRequest("/stream/td:321", "GET", { "CF-Connecting-IP": "192.0.2.15" }), f.env);
      assert.equal(r.status, 502);
      assert.deepEqual(await r.json(), { error: "upstream_error" });
    });
    assert.equal(f.accountCaps.length, 1, `${status}: no second seat`);
    assert.equal(f.dailyQuotas.size, 0);
    assert.equal(f.responseCache.size, 0);
  }
});

test("Tidal subStatus 4005 is a track-level not_available: no second seat, account never retired", async () => {
  const accounts = ["a", "b", "c"].map((id) => ({ ...liveAccount(), id }));
  const f = fixture({ accounts, tidalResponse: async () => new Response(JSON.stringify({ subStatus: 4005 }), { status: 401 }) });

  await withFetch(null, f.fetchTidal, async () => {
    for (const path of ["/stream/td:321", "/stream/td:322?aac=only", "/stream/td:323?aac=1"]) {
      const response = await worker.fetch(authorizedRequest(path), f.env);
      assert.equal(response.status, 404);
      assert.deepEqual(await response.json(), { error: "not_available" });
    }
  });
  assert.equal(f.calls.length, 3, "one upstream call per stream");
  assert.equal(f.accountCaps.length, 3, "one seat per stream: no second-seat retry");
  assert.equal(f.dbWrites.some(({ sql }) => sql.includes("SET cooling_until") || sql.includes("state='dead'")), false);
});

test("the seat gap paces every upstream call: a search right after a stream waits too", async () => {
  const f = fixture({ pool: true, accounts: [liveAccount()], tidalResponse: async (url) => (url.includes("/search/")
    ? new Response(JSON.stringify({ items: [] }), { status: 200 })
    : playbackInfo("LOSSLESS", "application/vnd.tidal.bts", FLAC_BTS)) });
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:1"), f.env)).status, 200);
    const stampedAt = f.accounts[0].last_call_at;
    assert.ok(stampedAt > 0);
    for (const path of ["/stream/td:2", "/search?q=anything"]) {
      const paced = await worker.fetch(authorizedRequest(path), f.env);
      assert.equal(paced.status, 503, path);
      assert.deepEqual(await paced.json(), { error: "busy" });
      assert.equal(paced.headers.get("retry-after"), "15", "gap-busy asks for a short retry");
    }
    f.accounts[0].last_call_at = stampedAt - 21;
    assert.equal((await worker.fetch(authorizedRequest("/search?q=anything"), f.env)).status, 200);
    assert.ok(f.accounts[0].last_call_at >= stampedAt, "search stamps the gap too");
  });
  assert.equal(f.calls.length, 2);
});

test("the gap lets a call through once it has passed, and busy when every seat is inside it", async () => {
  const old = Math.floor(Date.now() / 1000) - 21;
  const accounts = [{ ...liveAccount(), id: "a", last_call_at: old }, { ...liveAccount(), id: "b", last_call_at: old }];
  const f = fixture({ pool: true, accounts, tidalResponse: async () => playbackInfo("LOSSLESS", "application/vnd.tidal.bts", FLAC_BTS) });
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:1"), f.env)).status, 200);
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:2"), f.env)).status, 200);
    const third = await worker.fetch(authorizedRequest("/stream/td:3"), f.env);
    assert.equal(third.status, 503, "both seats inside their gap");
    assert.deepEqual(await third.json(), { error: "busy" });
    assert.equal(third.headers.get("retry-after"), "15");
  });
  assert.ok(f.accounts.every((a) => a.last_call_at > old));
  assert.equal(f.calls.length, 2);
});

test("capped seats keep the five-minute busy, not the pacing retry", async () => {
  const hour = new Date().toISOString().slice(0, 13);
  const f = fixture({ pool: true, accounts: [{ ...liveAccount(), hour_key: hour, hour_n: 250 }] });
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:1"), f.env);
    assert.equal(response.status, 503);
    assert.equal(response.headers.get("retry-after"), "300");
  });
  assert.equal(f.calls.length, 0);
});

test("the hires metadata lookup takes its own paced seat, never a second call on the playback seat", async () => {
  const seats = [{ ...liveAccount(), id: "a", hour_n: 0, day_n: 0 }, { ...liveAccount(), id: "b", hour_n: 0, day_n: 0 }];
  const f = fixture({ pool: true, accounts: seats, tidalResponse: (url) => (url.includes("/playbackinfopostpaywall")
    ? playbackInfo("HIGH", "application/vnd.tidal.bts", AAC_BTS)
    : new Response(JSON.stringify({ mediaMetadata: { tags: ["HIRES_LOSSLESS"] } }), { status: 200 })) });
  await withFetch(null, f.fetchTidal, async () => {
    const body = await (await worker.fetch(authorizedRequest("/stream/td:42?aac=only"), f.env)).json();
    assert.equal(body.codec, "aac");
    assert.equal(body.hires, true);
  });
  assert.equal(f.calls.length, 2);
  assert.deepEqual(f.accounts.map((a) => a.hour_n), [1, 1], "one call per seat");
  assert.equal(new URL(playbackCalls(f)[0].url).searchParams.get("audioquality"), "HIGH");
});

test("with no free seat for the hires lookup, playback still answers and hires is omitted", async () => {
  const f = fixture({ pool: true, accounts: [liveAccount()], tidalResponse: aacWithMeta(() => new Response("{}", { status: 200 })) });
  await withFetch(null, f.fetchTidal, async () => {
    const body = await (await worker.fetch(authorizedRequest("/stream/td:43?aac=only"), f.env)).json();
    assert.equal(body.codec, "aac");
    assert.equal("hires" in body, false);
  });
  assert.equal(f.calls.length, 1);
  assert.equal(f.hiresCache.size, 0);
});

test("an upstream search refusal is a 502 and is never cached as an empty result", async () => {
  for (const tidal of [() => new Response("{}", { status: 403 }), () => new Response(JSON.stringify({ subStatus: 4005 }), { status: 401 })]) {
    const f = fixture({ tidalResponse: async () => tidal() });
    await withFetch(null, f.fetchTidal, async () => {
      const response = await worker.fetch(authorizedRequest("/search?q=example"), f.env);
      assert.equal(response.status, 502);
    });
    assert.equal(f.responseCache.size, 0);
  }
});

test("not_available is cached for hours, lossless_unavailable for days", async () => {
  const t = Math.floor(Date.now() / 1000);
  const f = fixture({ accounts: [liveAccount(), liveAccount()], tidalResponse: async (url) => (url.includes("/tracks/1/")
    ? new Response(JSON.stringify({ subStatus: 4005 }), { status: 401 })
    : playbackInfo("HIGH", "application/vnd.tidal.bts", AAC_BTS)) });
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:1"), f.env)).status, 404);
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:2"), f.env)).status, 404);
  });
  const ttl = (id) => [...f.responseCache.entries()].find(([k]) => k.includes(`:${id}:`))[1].expires_at - t;
  assert.ok(ttl(1) <= 6 * 3600 + 2 && ttl(1) > 6 * 3600 - 5, "not_available: 6 h");
  assert.ok(ttl(2) > 4 * 86400, "lossless_unavailable: 5 days");
});

test("DASH manifest route passes Tidal's init URL through unchanged", async () => {
  const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", MPD) });

  await withFetch(null, f.fetchTidal, async () => {
    const stream = await worker.fetch(authorizedRequest("/stream/td:654?client=media3"), f.env);
    assert.equal(stream.status, 200);
    const streamBody = await stream.json();
    assert.equal(streamBody.manifest, "dash");

    const manifest = await worker.fetch(new Request(streamBody.url), f.env);
    assert.equal(manifest.status, 200);
    assert.equal(manifest.headers.get("content-type"), "application/dash+xml");
    assert.equal(await manifest.text(), MPD);
    assert.equal(f.calls.length, 1); // serving the MPD does not proxy the CDN init segment
  });
});

test("DASH links are signed with DASH_SIGNING_KEY when set; an ACCESS_TOKEN-signed link is refused", async () => {
  const { createHmac } = await import("node:crypto");
  const sign = (key, path, exp) => createHmac("sha256", key).update(`${path}:${exp}`).digest("hex");
  for (const [key, expected] of [["k".repeat(32), "k".repeat(32)], ["too-short", ACCESS_TOKEN], [undefined, ACCESS_TOKEN]]) {
    const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", MPD) });
    if (key) f.env.DASH_SIGNING_KEY = key;
    await withFetch(null, f.fetchTidal, async () => {
      const url = new URL((await (await worker.fetch(authorizedRequest("/stream/td:654"), f.env)).json()).url);
      const exp = url.searchParams.get("exp");
      assert.equal(url.searchParams.get("sig"), sign(expected, url.pathname, exp), `key ${key}`);
      assert.equal((await worker.fetch(new Request(url), f.env)).status, 200);
      if (expected !== ACCESS_TOKEN) {
        const forged = `${url.origin}${url.pathname}?exp=${exp}&sig=${sign(ACCESS_TOKEN, url.pathname, exp)}`;
        assert.equal((await worker.fetch(new Request(forged), f.env)).status, 401);
      }
    });
  }
});

test("DASH stream cache expires before the signed Tidal segment URLs", async () => {
  const tidalExpiry = Math.floor(Date.now() / 1000) + 1200;
  const mpd = `<MPD><SegmentTemplate initialization="https://audio.tidal.com/init.mp4?token=${tidalExpiry}~sig&amp;x=1" media="https://audio.tidal.com/$Number$?token=${tidalExpiry}~sig" /></MPD>`;
  const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", mpd) });
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:655"), f.env);
    const body = await response.json();
    assert.equal(response.status, 200);
    assert.equal(Number(new URL(body.url).searchParams.get("exp")), tidalExpiry - 30);
  });
  const entry = [...f.responseCache.values()][0];
  assert.ok(entry.expires_at <= tidalExpiry - 90);
  assert.ok(entry.expires_at > Math.floor(Date.now() / 1000));
});

const EDGE = { EDGE_SECRET: "e".repeat(64), EDGE_BASE: "https://tidal-edge.example.deno.net" };
const decodeToken = (url) => JSON.parse(Buffer.from(new URL(url).pathname.split("/")[2].split(".")[0], "base64url").toString());

test("?web=1 answers a DASH track with signed edge URLs and writes nothing to D1", async () => {
  const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", MPD) });
  Object.assign(f.env, EDGE);
  await withFetch(null, f.fetchTidal, async () => {
    const r = await worker.fetch(authorizedRequest("/stream/td:654?web=1"), f.env);
    assert.equal(r.status, 200);
    const b = await r.json();
    assert.equal(b.edge, true);
    assert.equal(b.manifest, "dash");
    assert.match(b.url, /^https:\/\/tidal-edge\.example\.deno\.net\/t\/[\w-]+\.[\w-]+\/manifest\.mpd$/);
    assert.match(b.download, /\/file\.flac$/);
    assert.equal(b.sampleRate, 96000);
    const p = decodeToken(b.download);
    assert.equal(p.k, "d");
    assert.equal(p.x, MPD);
    assert.ok(p.exp > Date.now() / 1000 && p.exp <= Date.now() / 1000 + 3500);
    assert.equal(b.expiresAt, p.exp);
    assert.equal(f.dbWrites.filter(({ sql }) => sql.includes("tidal_dash_manifests")).length, 0);
  });
});

test("?web=1 token signature is what the edge verifies (HMAC-SHA256 over the base64url body)", async () => {
  const { edgeToken } = await import("../src/index.js");
  const t = await edgeToken(EDGE.EDGE_SECRET, { v: 1, k: "p", exp: 1, u: "https://x.audio.tidal.com/a.flac" });
  const [body, sig] = t.split(".");
  const { createHmac } = await import("node:crypto");
  assert.equal(sig, createHmac("sha256", EDGE.EDGE_SECRET).update(body).digest("base64url"));
});

test("?web=1 progressive FLAC uses stream.flac; a progressive .mp4 FLAC is refused", async () => {
  const flac = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://sp-pr-cf.audio.tidal.com/a.flac?token=1~x"] });
  const f = fixture({ tidalResponse: async () => playbackInfo("LOSSLESS", "application/vnd.tidal.bts", flac) });
  Object.assign(f.env, EDGE);
  await withFetch(null, f.fetchTidal, async () => {
    const b = await (await worker.fetch(authorizedRequest("/stream/td:7?web=1"), f.env)).json();
    assert.match(b.url, /\/stream\.flac$/);
    assert.equal(decodeToken(b.url).u, "https://sp-pr-cf.audio.tidal.com/a.flac?token=1~x");
  });
  const mp4 = JSON.stringify({ mimeType: "audio/mp4", codecs: "flac", urls: ["https://sp-pr-cf.audio.tidal.com/a.mp4"] });
  const g = fixture({ tidalResponse: async () => playbackInfo("LOSSLESS", "application/vnd.tidal.bts", mp4) });
  Object.assign(g.env, EDGE);
  await withFetch(null, g.fetchTidal, async () => {
    assert.equal((await worker.fetch(authorizedRequest("/stream/td:7?web=1"), g.env)).status, 404);
  });
});

test("?web=1 without an edge configured keeps the normal answer; no ?web keeps it too", async () => {
  const cases = [[{}, "/stream/td:654?web=1"], [EDGE, "/stream/td:654"], [{ EDGE_SECRET: "short", EDGE_BASE: EDGE.EDGE_BASE }, "/stream/td:654?web=1"], [{ ...EDGE, EDGE_BASE: "http://evil.example" }, "/stream/td:654?web=1"]];
  for (const [env, path] of cases) {
    const f = fixture({ tidalResponse: async () => playbackInfo("HI_RES_LOSSLESS", "application/dash+xml", MPD) });
    Object.assign(f.env, env);
    await withFetch(null, f.fetchTidal, async () => {
      const b = await (await worker.fetch(authorizedRequest(path), f.env)).json();
      assert.equal(b.edge, undefined);
      assert.match(b.url, /^https:\/\/relay\.example\/dash\/td\/654\.mpd/);
    });
  }
});

function aacResponse() {
  return playbackInfo("HIGH", "application/vnd.tidal.bts",
    JSON.stringify({ mimeType: "audio/mp4", codecs: "mp4a.40.2", urls: ["https://cdn.example/track.m4a"] }));
}

/** Tidal stub: AAC playbackinfo plus a /tracks/<id> metadata answer from `meta(url)`. */
function aacWithMeta(meta) {
  return (url) => (url.includes("/playbackinfopostpaywall") ? aacResponse() : meta(url));
}
const metaCalls = (f) => f.calls.filter((c) => /\/tracks\/\d+\?/.test(c.url));

test("?aac=only reports hires=true from mediaMetadata HIRES_LOSSLESS, then serves it from the cache", async () => {
  const f = fixture({ accounts: [liveAccount(), liveAccount(), liveAccount()], tidalResponse: aacWithMeta(() => new Response(JSON.stringify({ audioQuality: "LOSSLESS", mediaMetadata: { tags: ["LOSSLESS", "HIRES_LOSSLESS"] } }), { status: 200 })) });
  await withFetch(null, f.fetchTidal, async () => {
    const first = await (await worker.fetch(authorizedRequest("/stream/42?aac=only"), f.env)).json();
    assert.equal(first.codec, "aac");
    assert.equal(first.hires, true);
    assert.equal(metaCalls(f).length, 1);
    assert.match(metaCalls(f)[0].url, /\/tracks\/42\?countryCode=US$/);

    const second = await (await worker.fetch(authorizedRequest("/stream/42?aac=only"), f.env)).json();
    assert.equal(second.hires, true);
    assert.equal(metaCalls(f).length, 1, "cache hit makes no metadata call");
  });
  // One daily stream-quota unit per stream; the metadata lookup adds none.
  assert.equal(f.dailyQuotas.get(`${new Date().toISOString().slice(0, 10)}:global`), 2);
});

test("?aac=only accepts audioQuality HI_RES_LOSSLESS as hires", async () => {
  const f = fixture({ accounts: [liveAccount(), liveAccount()], tidalResponse: aacWithMeta(() => new Response(JSON.stringify({ audioQuality: "HI_RES_LOSSLESS" }), { status: 200 })) });
  await withFetch(null, f.fetchTidal, async () => {
    const body = await (await worker.fetch(authorizedRequest("/stream/7?aac=only"), f.env)).json();
    assert.equal(body.hires, true);
  });
});

test("?aac=only reports hires=false for a CD-quality track and caches the false", async () => {
  const f = fixture({ accounts: [liveAccount(), liveAccount(), liveAccount()], tidalResponse: aacWithMeta(() => new Response(JSON.stringify({ audioQuality: "LOSSLESS", mediaMetadata: { tags: ["LOSSLESS"] } }), { status: 200 })) });
  await withFetch(null, f.fetchTidal, async () => {
    assert.equal((await (await worker.fetch(authorizedRequest("/stream/8?aac=only"), f.env)).json()).hires, false);
    assert.equal((await (await worker.fetch(authorizedRequest("/stream/8?aac=only"), f.env)).json()).hires, false);
    assert.equal(metaCalls(f).length, 1);
  });
  assert.deepEqual(f.hiresCache.get("8").hires, 0);
});

test("?aac=only omits hires when metadata fails, is unparseable, or throws; playback still answers", async () => {
  for (const meta of [
    () => new Response("", { status: 500 }),
    () => new Response("not json", { status: 200 }),
    () => new Response(JSON.stringify({ title: "no quality fields" }), { status: 200 }),
    () => { throw new TypeError("network down"); },
  ]) {
    const f = fixture({ accounts: [liveAccount(), liveAccount()], tidalResponse: aacWithMeta(meta) });
    await withFetch(null, f.fetchTidal, async () => {
      const res = await worker.fetch(authorizedRequest("/stream/9?aac=only"), f.env);
      assert.equal(res.status, 200);
      const body = await res.json();
      assert.equal(body.codec, "aac");
      assert.equal("hires" in body, false);
    });
    assert.equal(f.hiresCache.size, 0, "unknown is never cached");
  }
});

test("only ?aac=only looks up hires; lossless streams make no metadata call", async () => {
  const f = fixture({ tidalResponse: () => losslessResponse() });
  await withFetch(null, f.fetchTidal, async () => {
    const body = await (await worker.fetch(authorizedRequest("/stream/10"), f.env)).json();
    assert.equal("hires" in body, false);
    assert.equal(metaCalls(f).length, 0);
  });
});
