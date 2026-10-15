import test from "node:test";
import assert from "node:assert/strict";
import worker, { networkOf, networkTag } from "../src/index.js";

const ACCESS_TOKEN = "test-access-token";
const MPD = '<MPD><SegmentTemplate initialization="https://sp-ad-fa.audio.tidal.com/init.mp4?token=test" media="https://audio.tidal.com/$Number$" /></MPD>';

function fixture({ tidalResponse, account = liveAccount(), accounts = [account] } = {}) {
  const manifests = new Map();
  const calls = [];
  const dbWrites = [];
  const dailyQuotas = new Map();
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
              return accounts[nextAccount++] || null;
            }
            if (sql.includes("SELECT manifest_xml,init_url,expires_at")) return manifests.get(this.args[0]) || null;
            return null;
          },
          async run() {
            dbWrites.push({ sql, args: this.args });
            if (sql.includes("INSERT INTO tidal_daily_quotas")) {
              const [day, key] = this.args;
              const mapKey = `${day}:${key}`;
              dailyQuotas.set(mapKey, (dailyQuotas.get(mapKey) || 0) + 1);
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

  return { env, calls, manifests, fetchTidal, dbWrites, dailyQuotas, accountCaps };
}

function liveAccount() {
  return {
    id: "test-account",
    access_token: "test-tidal-access-token",
    refresh_token: "test-tidal-refresh-token",
    token_expires_at: Math.floor(Date.now() / 1000) + 3600,
    generation: 1,
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

test("per-account cap defaults mirror Qobuz", async () => {
  const f = fixture();
  await withFetch(null, f.fetchTidal, async () => {
    await worker.fetch(authorizedRequest("/search?q=test"), f.env);
  });
  assert.deepEqual(f.accountCaps[0], { hourly: 621, daily: 11000 });
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

test("search spends an account slot but not global or network daily stream quota", async () => {
  const f = fixture();
  f.env.IP_SALT = "test-salt";
  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/search?q=example", "GET", { "CF-Connecting-IP": "192.0.2.15" }), f.env);
    assert.equal(response.status, 200);
  });
  assert.equal(f.accountCaps.length, 1);
  assert.equal(f.dailyQuotas.size, 0);
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

test("stream continues from a downgraded Hi Res response to a Lossless request", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const f = fixture({ tidalResponse: async (url) => {
    const quality = new URL(url).searchParams.get("audioquality");
    return playbackInfo(quality === "HI_RES_LOSSLESS" ? "HIGH" : "LOSSLESS", "application/vnd.tidal.bts", direct);
  } });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321?client=media3"), f.env);
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.codec, "flac");
    assert.equal(body.url, "https://cdn.example/track.flac");
    assert.ok(f.calls.some(({ url }) => new URL(url).searchParams.get("audioquality") === "LOSSLESS"));
  });
});

test("stream rejects a Tidal response below lossless quality", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const f = fixture({ tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", direct) });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "lossless_unavailable" });
    assert.ok(f.calls.length > 1); // retries other quality/region combinations
  });
});

test("two accounts declining the same track are not cooled", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const accounts = [liveAccount(), { ...liveAccount(), id: "second-account", access_token: "second-token" }];
  const f = fixture({ accounts, tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", direct) });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: "lossless_unavailable" });
    assert.equal(f.dbWrites.some(({ sql }) => sql.includes("SET cooling_until")), false);
  });
});

test("a single account is not retried or cooled for a track-level quality refusal", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const f = fixture({ tidalResponse: async () => playbackInfo("HIGH", "application/vnd.tidal.bts", direct) });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 404);
    assert.equal(f.dbWrites.some(({ sql }) => sql.includes("SET cooling_until")), false);
  });
});

test("stream cross-checks a downgraded account against the next account, then cools the refuser", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const accounts = [liveAccount(), { ...liveAccount(), id: "reserve-account", access_token: "reserve-token" }];
  const f = fixture({ accounts, tidalResponse: async (_url, options) => {
    const isReserve = options.headers.authorization === "Bearer reserve-token";
    return playbackInfo(isReserve ? "LOSSLESS" : "HIGH", "application/vnd.tidal.bts", direct);
  } });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 200);
    assert.ok(f.dbWrites.some(({ sql, args }) => sql.includes("cooling_until") && args[1] === "test-account"));
  });
});

test("an invalid-session account is quarantined and the next account serves", async () => {
  const direct = JSON.stringify({ mimeType: "audio/flac", codecs: "flac", urls: ["https://cdn.example/track.flac"] });
  const accounts = [liveAccount(), { ...liveAccount(), id: "reserve-account", access_token: "reserve-token" }];
  const f = fixture({ accounts, tidalResponse: async (_url, options) => {
    if (options.headers.authorization === "Bearer test-tidal-access-token") return new Response("{}", { status: 401 });
    return playbackInfo("LOSSLESS", "application/vnd.tidal.bts", direct);
  } });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 200);
    assert.ok(f.dbWrites.some(({ sql, args }) => sql.includes("state='dead'") && args[1] === "test-account"));
  });
});

test("Tidal subStatus 4005 is a track refusal and never retires the account", async () => {
  const f = fixture({ tidalResponse: async () => new Response(JSON.stringify({ subStatus: 4005 }), { status: 401 }) });

  await withFetch(null, f.fetchTidal, async () => {
    const response = await worker.fetch(authorizedRequest("/stream/td:321"), f.env);
    assert.equal(response.status, 404);
    assert.equal(f.dbWrites.some(({ sql }) => sql.includes("state='dead'")), false);
  });
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
