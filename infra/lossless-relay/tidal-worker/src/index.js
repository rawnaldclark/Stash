import { createHmac } from "node:crypto";
import { verifySigned } from "./auth.js";
import { readStanding, spendLossless, losslessHeaders, capAnswer, quotaBody, pruneUsage } from "./quota.js";

const API = "https://api.tidal.com/v1";
const AUTH = "https://auth.tidal.com/v1/oauth2";
const UA = "okhttp/5.3.2";
const SCOPE = "r_usr+w_usr+w_sub";
const HIRES_TTL = 30 * 86400; // a track's master quality rarely changes; recheck monthly
const RESPONSE_CACHE_TTL = 5 * 86400;
// not_available (Tidal 404 / 4005) is kept short: during a 429 storm Tidal answered these for
// tracks it does have, and a 5-day answer hid them from every install.
const NOT_AVAILABLE_TTL = 6 * 3600;
const STREAM_CACHE_SAFETY = 60;

export default {
  async scheduled(_event, env) {
    const cutoff = new Date((now() - 7 * 86400) * 1000).toISOString().slice(0, 10);
    await env.DB.batch([
      env.DB.prepare("DELETE FROM tidal_daily_quotas WHERE day < ?").bind(cutoff),
      env.DB.prepare("DELETE FROM tidal_dash_manifests WHERE expires_at <= ?").bind(now()),
      env.DB.prepare("DELETE FROM tidal_hires_cache WHERE checked_at <= ?").bind(now() - HIRES_TTL),
      env.DB.prepare("DELETE FROM tidal_response_cache WHERE expires_at <= ?").bind(now()),
      env.DB.prepare("DELETE FROM tidal_upstream_429_events WHERE occurred_at < ?").bind(now() - 30 * 86400),
      env.DB.prepare("DELETE FROM tidal_device_auth WHERE expires_at <= ?").bind(now()),
    ]);
    await pruneUsage(env, now()); // the shared daily lossless counter; never throws
  },
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "GET" && request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
    try {
      if (url.pathname === "/health" && request.method === "GET") return json({ ok: true, provider: "tidal" });
      if (env.REQUEST_RL) {
        const { success } = await env.REQUEST_RL.limit({ key: networkOf(request.headers.get("CF-Connecting-IP")) });
        if (!success) return json({ error: "rate_limited" }, 429, { "Retry-After": "60" });
      }
      if (url.pathname === "/admin/device/start" && request.method === "POST") return startDeviceAuth(request, env);
      if (url.pathname === "/admin/device/poll" && request.method === "POST") return pollDeviceAuth(request, env);
      if (url.pathname === "/search" && request.method === "GET") {
        if (!authorized(request, env.ACCESS_TOKEN)) return unauthorized();
        if (env.TIDAL_RELAY_PAUSED === "1") return paused();
        return await search(url, request, env);
      }
      if (url.pathname === "/quota" && request.method === "GET") {
        if (!authorized(request, env.ACCESS_TOKEN)) return unauthorized();
        return await quotaRoute(request, env);
      }
      const path = decodeURIComponent(url.pathname);
      const streamMatch = path.match(/^\/stream\/(?:td:)?(\d+)$/);
      if (streamMatch && request.method === "GET") {
        if (!authorized(request, env.ACCESS_TOKEN)) return unauthorized();
        if (env.TIDAL_RELAY_PAUSED === "1") return paused();
        const caller = installOf(request, env, `td:${streamMatch[1]}`);
        if (caller.denial) return caller.denial;
        const quota = await admitStream(request, env);
        if (quota.denial) return quota.denial;
        // The daily lossless counter, shared with the Qobuz relay. ?aac=only is never lossless: never capped or counted.
        let aac = aacMode(url);
        const standing = caller.subject && aac !== "only" ? await readStanding(request, env, caller.subject, now()) : null;
        if (standing?.over) {
          // Over the cap, ?aac=1 (the app's streaming path) still plays: Tidal's AAC, uncounted. Anything else is the 429.
          if (aac !== "1") { const cap = capAnswer(standing, now()); return json(cap.body, cap.status, cap.headers); }
          aac = "cap";
        }
        const { response, upstream } = await stream(streamMatch[1], request, env, quota, aac);
        // Like Qobuz's answered-mint quota: successful streams and definitive
        // track misses count once per caller request; upstream/account failures do not.
        if (response.status === 200 || response.status === 404) await spendStream(request, env, quota, upstream);
        if (standing) await meterLossless(response, standing, aac === "cap");
        return response;
      }
      const dashMatch = path.match(/^\/dash\/td\/(\d+)(?:\.mpd|\/init\.mp4)$/);
      if (dashMatch && request.method === "GET") return dash(request, dashMatch[1], env, url.pathname.endsWith("/init.mp4"));
      return json({ error: "not_found" }, 404);
    } catch (e) {
      console.error(`request failed: ${e?.name || "Error"}`);
      return json({ error: "upstream_unavailable" }, 503, { "Retry-After": "30" });
    }
  },
};

function paused() {
  return json({ error: "tidal_relay_paused" }, 503, { "Retry-After": "600" });
}
/**
 * 503 busy. Same body for every cause so clients need no change; only Retry-After differs:
 * 300 s when seats are capped/cooling, a short value when seats are only waiting out their gap.
 */
function busy(retryAfter = 300) {
  return json({ error: "busy" }, 503, { "Retry-After": String(retryAfter) });
}
const PACING_RETRY_AFTER = 15;

/** The busy answer for "takeAccount found no seat". */
async function noSeat(env) {
  return busy(await onlyPacingBlocks(env) ? PACING_RETRY_AFTER : 300);
}

async function startDeviceAuth(request, env) {
  if (!authorized(request, env.ADMIN_TOKEN)) return unauthorized();
  const form = new URLSearchParams({ client_id: env.TIDAL_CLIENT_ID, scope: SCOPE });
  const r = await fetch(`${AUTH}/device_authorization`, { method: "POST", headers: oauthHeaders(), body: form });
  if (!r.ok) return json({ error: "device_authorization_failed", status: r.status }, 502);
  const d = await r.json();
  const deviceCode = d.deviceCode || d.device_code;
  const userCode = d.userCode || d.user_code;
  const expiresIn = Number(d.expiresIn || d.expires_in || 300);
  const interval = Math.max(Number(d.interval || 5), 4);
  if (!deviceCode || !userCode) return json({ error: "invalid_device_authorization" }, 502);
  const requestedState = await request.clone().json().catch(() => ({}));
  const accountState = requestedState.account_state === "reserve" ? "reserve" : "live";
  await env.DB.prepare("INSERT OR REPLACE INTO tidal_device_auth(device_code,user_code,expires_at,poll_interval,next_poll_at,account_state) VALUES(?,?,?,?,?,?)")
    .bind(deviceCode, userCode, now() + expiresIn, interval, now(), accountState).run();
  return json({ verification_uri: `https://link.tidal.com/${encodeURIComponent(userCode)}`, user_code: userCode, expires_in: expiresIn, interval });
}

async function pollDeviceAuth(request, env) {
  if (!authorized(request, env.ADMIN_TOKEN)) return unauthorized();
  const body = await request.json().catch(() => ({}));
  const code = String(body.user_code || "");
  if (!code) return json({ error: "bad_request" }, 400);
  const pending = await env.DB.prepare("SELECT * FROM tidal_device_auth WHERE user_code=? AND expires_at>? LIMIT 1").bind(code, now()).first();
  if (!pending) return json({ status: "expired" }, 404);
  if (pending.next_poll_at > now()) return json({ status: "pending", retry_after: pending.next_poll_at - now() }, 202);
  await env.DB.prepare("UPDATE tidal_device_auth SET next_poll_at=? WHERE device_code=?").bind(now() + pending.poll_interval, pending.device_code).run();
  const form = new URLSearchParams({ client_id: env.TIDAL_CLIENT_ID, device_code: pending.device_code, grant_type: "urn:ietf:params:oauth:grant-type:device_code", scope: SCOPE });
  if (env.TIDAL_CLIENT_SECRET) form.set("client_secret", env.TIDAL_CLIENT_SECRET);
  const r = await fetch(`${AUTH}/token`, { method: "POST", headers: oauthHeaders(), body: form });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) {
    if (["authorization_pending", "slow_down"].includes(data.error)) return json({ status: "pending", retry_after: pending.poll_interval }, 202);
    if (["expired_token", "invalid_grant"].includes(data.error)) {
      await env.DB.prepare("DELETE FROM tidal_device_auth WHERE device_code=?").bind(pending.device_code).run();
      return json({ status: "expired" }, 410);
    }
    return json({ status: "pending", retry_after: pending.poll_interval }, 202);
  }
  const userId = String(data.user?.userId || data.user?.id || data.user_id || crypto.randomUUID());
  const lifetime = Number(data.expires_in || 604800);
  await env.DB.prepare(`INSERT INTO tidal_accounts(id,access_token,refresh_token,token_expires_at,state,updated_at)
    VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET access_token=excluded.access_token,refresh_token=excluded.refresh_token,
    token_expires_at=excluded.token_expires_at,state=excluded.state,dead_reason='',cooling_until=0,
    generation=tidal_accounts.generation+1,updated_at=excluded.updated_at`)
    .bind(userId, data.access_token, data.refresh_token || "", now() + lifetime, pending.account_state || "live", now()).run();
  await env.DB.prepare("DELETE FROM tidal_device_auth WHERE device_code=?").bind(pending.device_code).run();
  return json({ status: "authorized", account_id: userId });
}

async function search(url, request, env) {
  const query = normaliseQuery(url.searchParams.get("q") || "");
  if (!query) return json({ tracks: [] });
  const country = env.TIDAL_COUNTRY_CODE || "US";
  const key = `search:${country}:${await cacheDigest(query)}`;
  const cached = await cachedJson(env, key);
  if (cached) return cached;
  // Cache hits are free; a miss is the only search that costs Tidal, so only a miss is metered.
  const quota = await admitSearch(request, env);
  if (quota.denial) return quota.denial;
  if (await softOpenBlocks(env)) return busy();
  if (!await upstreamAllowed(request, env)) return busy(UPSTREAM_RL_RETRY_AFTER);
  const response = await searchUncached(query, country, request, env, () => spendSearch(env, quota));
  if (response.status === 200) await cacheJson(env, key, response, RESPONSE_CACHE_TTL);
  return response;
}

/**
 * Per-network daily search quota (key s:<networkTag>). Uncached searches used to spend no
 * daily quota at all, so one network could keep every seat busy; this bounds it like streams.
 * Without IP_SALT there is no network tag and only the rate limiters apply.
 */
async function admitSearch(request, env) {
  const day = new Date().toISOString().slice(0, 10);
  const tag = networkTag(env, networkOf(request.headers.get("CF-Connecting-IP")));
  if (!tag) return { day, tag };
  const used = await dailyUsed(env, day, [`s:${tag}`]);
  if ((used[`s:${tag}`] || 0) >= positive(env.NETWORK_SEARCH_DAILY_CAP, 2000)) {
    return { denial: json({ error: "network_daily_cap" }, 429, { "Retry-After": "3600" }) };
  }
  return { day, tag };
}

async function spendSearch(env, quota) {
  if (quota.tag) await bumpDailyQuotaStmt(env.DB, quota.day, `s:${quota.tag}`).run();
}

const UPSTREAM_RL_RETRY_AFTER = 30;

/**
 * Fair share of the seats: the pool makes ~12 upstream calls/min in total while REQUEST_RL lets
 * one network send 40 requests/min, so UPSTREAM_RL (keyed like REQUEST_RL) bounds how many of a
 * network's requests may take a seat. Consulted only on the way to a seat; no binding = no limit.
 */
async function upstreamAllowed(request, env) {
  if (!env.UPSTREAM_RL) return true;
  const { success } = await env.UPSTREAM_RL.limit({ key: networkOf(request.headers.get("CF-Connecting-IP")) });
  return success;
}

/** @param onSeat called once, when the first seat is taken: the search now reaches Tidal. */
async function searchUncached(query, cc, request, env, onSeat = async () => {}) {
  let data;
  let charged = false;
  for (let attempt = 0; attempt < 2; attempt++) {
    const selected = await takeAccount(env);
    if (!selected) return noSeat(env);
    if (!charged) { charged = true; await onSeat(); }
    const account = await refreshIfNeeded(selected, env);
    if (!account) { await coolAccount(env, selected.id, 300); continue; }
    const r = await tidalFetch(`/search/tracks?query=${encodeURIComponent(query)}&limit=30&offset=0&countryCode=${cc}`, account, env);
    if (r.status === 429) {
      await onUpstream429(env, selected, "search");
      return busy();
    }
    if (r.status === 401) {
      const denial = await r.clone().json().catch(() => ({}));
      if (denial.subStatus === 4005) return searchError();
      await onSessionRefused(env, selected, "search");
      return busy();
    }
    if (r.status >= 500) { await coolAccount(env, selected.id, 300); continue; }
    // Never a 200: search() caches 200s for 5 days and the app files an empty answer as
    // "not on Tidal" for 14, so an upstream refusal must not read as "no results".
    if (!r.ok) return searchError();
    data = await r.json();
    break;
  }
  if (!data) return json({ tracks: [] }, 503);
  const tracks = (data.items || []).filter((t) => t && t.id != null && t.type !== "video").map((t) => {
    const tags = t.mediaMetadata?.tags || [];
    const quality = t.audioQuality === "HI_RES_LOSSLESS" || tags.includes("HI_RES_LOSSLESS") ? "HI_RES_LOSSLESS"
      : t.audioQuality === "LOSSLESS" || tags.includes("LOSSLESS") ? "LOSSLESS" : "HIGH";
    const cover = t.album?.cover;
    const artists = [t.artist?.name, ...(t.artists || []).map((a) => a.name)].filter((v, i, a) => v && a.indexOf(v) === i);
    const title = `${t.title || ""}${t.version ? ` (${t.version})` : ""}`;
    const artwork = cover ? `https://resources.tidal.com/images/${cover.replaceAll("-", "/")}/1280x1280.jpg` : null;
    return { id: `td:${t.id}`, title, artist: artists.join(", "), album: t.album?.title || "", duration: Math.round(Number(t.duration) || 0), artwork, artworkURL: artwork, format: "flac", audioQuality: quality, bitrate: quality === "HI_RES_LOSSLESS" ? 9216 : quality === "LOSSLESS" ? 1411 : 320, isrc: t.isrc || null };
  });
  return json({ tracks });
}

/** An upstream search refusal: uncached here, and a transient error (not a miss) to the app. */
function searchError() { return json({ error: "upstream_error" }, 502); }

/**
 * @returns {{response: Response, upstream: boolean}} upstream = the answer cost Tidal a call
 * (a cache miss), the only kind the global daily cap is charged for.
 */
async function stream(trackId, request, env, quota = {}, aac = aacMode(new URL(request.url))) {
  const url = new URL(request.url);
  const web = url.searchParams.get("web") === "1" ? "web" : "app";
  const key = `stream:${env.TIDAL_COUNTRY_CODE || "US"}:${trackId}:aac=${aac}:web=${web}`;
  const cached = await cachedJson(env, key);
  if (cached) return { response: cached, upstream: false };
  if (quota.globalFull) return { response: globalCapped(), upstream: false };
  if (await softOpenBlocks(env)) return { response: busy(), upstream: false };
  if (!await upstreamAllowed(request, env)) return { response: busy(UPSTREAM_RL_RETRY_AFTER), upstream: false };
  const response = await streamUncached(trackId, request, env, aac);
  let body;
  try { body = await response.clone().json(); } catch { return { response, upstream: true }; }
  if (response.status === 404 && body.error === "lossless_unavailable") {
    await cacheJson(env, key, response, RESPONSE_CACHE_TTL);
  } else if (response.status === 404 && body.error === "not_available") {
    await cacheJson(env, key, response, NOT_AVAILABLE_TTL);
  } else if (response.status === 200) {
    const expiresAt = streamAnswerExpiry(body);
    const ttl = expiresAt - now() - STREAM_CACHE_SAFETY;
    if (ttl > 0) await cacheJson(env, key, response, ttl);
  }
  return { response, upstream: true };
}

/**
 * ?aac as one of "", "1", "only": anything else is "", so callers cannot mint unbounded cache namespaces.
 * The relay itself adds a fourth mode, "cap": ?aac=1 past the daily lossless cap (AAC like "only", without hires).
 */
function aacMode(url) {
  const aac = url.searchParams.get("aac");
  return aac === "1" || aac === "only" ? aac : "";
}

async function streamUncached(trackId, request, env, aac = aacMode(new URL(request.url))) {
  // ?aac=1: when no account can play the track losslessly, answer with Tidal's AAC
  // stream (labelled codec "aac") instead of lossless_unavailable. Opt-in so the
  // download path, which must never save AAC as FLAC, keeps the lossless-only answer.
  // ?aac=only (the web player): ask Tidal for its 320 kbps AAC straight away, for any
  // track. A browser can only play Tidal's progressive file; Hi-Res comes as DASH.
  // "cap" (?aac=1 past the daily lossless cap) asks for AAC straight away too, but skips the web player's hires lookup.
  const aacOnly = aac === "only" || aac === "cap";
  const allowAac = aac === "1" || aacOnly;
  // Upstream budget: one playbackinfo call per seat taken. A second seat is only tried
  // after a seat-level failure (failed refresh, Tidal 5xx), never for a
  // track-level answer: with this client ID CD-only tracks always come back HIGH (AAC),
  // and a 4005 is a track/rights refusal, so asking another seat just burns budget.
  for (let attempt = 0; attempt < 2; attempt++) {
    const account = await takeAccount(env);
    if (!account) return noSeat(env);
    const fresh = await refreshIfNeeded(account, env);
    if (!fresh) {
      await coolAccount(env, account.id, 300);
      continue;
    }
    const data = await playbackInfo(trackId, fresh, env, aacOnly ? "HIGH" : "HI_RES_LOSSLESS");
    if (data.status === 401) {
      const denial = await data.clone().json().catch(() => ({}));
      if (denial.subStatus === 4005) return json({ error: "not_available" }, 404);
      await onSessionRefused(env, account, "playbackinfo");
      return busy();
    }
    if (!data.ok) {
      if (data.status === 429) {
        await onUpstream429(env, account, "playbackinfo");
        return busy();
      }
      if (data.status === 404) return json({ error: "not_available" }, 404);
      // Any other 4xx is Tidal refusing this request, not this seat: another seat gets the same.
      if (data.status < 500) return json({ error: "upstream_error" }, 502);
      await coolAccount(env, account.id, 300);
      continue;
    }
    const info = await data.json();
    if (aacOnly) return aacInfo(info, aac === "only" ? await trackHires(trackId, env, request) : undefined);
    if (!["LOSSLESS", "HI_RES_LOSSLESS"].includes(info.audioQuality)) {
      // The normal answer for a CD-only track with this client, not a seat problem.
      return allowAac ? aacInfo(info) : json({ error: "lossless_unavailable" }, 404);
    }
    const web = webEdge(request, env);
    if (web) return await edgeInfo(trackId, info, env, web);
    return await streamInfo(trackId, request, env, info, allowAac);
  }
  return json({ error: "upstream_unavailable" }, 503);
}

/**
 * After takeAccount found no seat: true when some seat is free under its caps and
 * cooling but was refused only by its pacing gap, so a short retry will work.
 */
async function onlyPacingBlocks(env) {
  try {
    const { hour, day, hourly, daily } = capWindow(env);
    const row = await env.DB.prepare(`SELECT COUNT(*) AS n FROM tidal_accounts WHERE state='live' AND cooling_until<=?3
      AND (hour_key!=?1 OR hour_n<?4) AND (day_key!=?2 OR day_n<?5)`).bind(hour, day, now(), hourly, daily).first();
    return Number(row?.n) > 0;
  } catch { return false; }
}

function normaliseQuery(query) { return query.trim().replace(/\s+/gu, " ").toLocaleLowerCase("en-US"); }

async function cacheDigest(value) {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function cachedJson(env, key) {
  try {
    const row = await env.DB.prepare("SELECT response_json FROM tidal_response_cache WHERE cache_key=? AND expires_at>? LIMIT 1")
      .bind(key, now()).first();
    if (!row) return null;
    const entry = JSON.parse(row.response_json);
    if (!entry || !Number.isInteger(entry.status) || !entry.body) return null;
    return json(entry.body, entry.status);
  } catch { return null; }
}

async function cacheJson(env, key, response, ttl) {
  try {
    const body = await response.clone().json();
    await env.DB.prepare("INSERT OR REPLACE INTO tidal_response_cache(cache_key,response_json,expires_at) VALUES(?,?,?)")
      .bind(key, JSON.stringify({ status: response.status, body }), now() + Math.floor(ttl)).run();
  } catch { /* cache failures must never fail a relay request */ }
}

async function softOpenBlocks(env) {
  const percent = Number.parseInt(env.TIDAL_SOFT_OPEN_PERCENT, 10);
  if (!Number.isInteger(percent) || percent <= 0) return false;
  const startedAt = now();
  const seconds = positive(env.TIDAL_SOFT_OPEN_SECONDS, 600);
  try {
    await env.DB.prepare("INSERT OR IGNORE INTO tidal_soft_open_state(id,started_at) VALUES(1,?)").bind(startedAt).run();
    const state = await env.DB.prepare("SELECT started_at FROM tidal_soft_open_state WHERE id=1").first();
    return Boolean(state && state.started_at + seconds > now() && Math.random() * 100 < Math.min(percent, 100));
  } catch { return false; }
}

/** A Tidal 429 on a seat: log it (event row + warning) and strike the seat. */
async function onUpstream429(env, account, endpoint) {
  await recordUpstream429(env, account, endpoint);
  await strikeSeat(env, account);
}

/**
 * A non-4005 401 that survived tidalFetch's refresh + retry. A strike, never a kill: the session
 * was just refreshed (or could not be), so this is a Tidal auth blip, and killing on it retired
 * the whole pool in a few requests. Only a refresh Tidal rejects as invalid retires a seat.
 */
async function onSessionRefused(env, account, endpoint) {
  console.warn(`tidal_session_refused ${JSON.stringify({ account: String(account.id).slice(-4), endpoint })}`);
  await strikeSeat(env, account);
}

/**
 * Cools a seat for an escalating spell. Tidal's 429 is a short burst limit with no Retry-After,
 * so the first strike rests the seat TIDAL_429_COOLDOWN_SECONDS (default 120); each strike while
 * still "hot" doubles it, up to TIDAL_429_COOLDOWN_MAX_SECONDS (default 960). A seat whose last
 * cooldown ended more than TIDAL_429_FORGIVE_SECONDS (default 1800) ago starts again at strike 1.
 */
async function strikeSeat(env, account) {
  const t = now();
  await env.DB.prepare(`UPDATE tidal_accounts SET
      strikes=CASE WHEN cooling_until<?1-?4 THEN 1 ELSE strikes+1 END,
      cooling_until=?1+MIN(?2*(1<<CASE WHEN cooling_until<?1-?4 THEN 0 ELSE MIN(strikes,8) END),?3)
    WHERE id=?5 AND state='live'`)
    .bind(t, positive(env.TIDAL_429_COOLDOWN_SECONDS, 120), positive(env.TIDAL_429_COOLDOWN_MAX_SECONDS, 960),
      positive(env.TIDAL_429_FORGIVE_SECONDS, 1800), account.id).run();
}

async function recordUpstream429(env, account, endpoint) {
  const event = {
    account: String(account.id).slice(-4),
    endpoint,
    status: 429,
    hour_n: Number(account.hour_n) || 0,
    day_n: Number(account.day_n) || 0,
  };
  console.warn(`tidal_upstream_429 ${JSON.stringify(event)}`);
  await env.DB.prepare("INSERT INTO tidal_upstream_429_events(account_id,endpoint,hour_n,day_n,occurred_at) VALUES(?,?,?,?,?)")
    .bind(String(account.id), endpoint, event.hour_n, event.day_n, now()).run().catch(() => {});
}

function streamAnswerExpiry(body) {
  let expiry = Number(body.expiresAt) || 0;
  const url = typeof body.url === "string" ? body.url : "";
  try {
    const parsed = new URL(url);
    const paramExpiry = Number(parsed.searchParams.get("exp")) || 0;
    if (paramExpiry) expiry = expiry ? Math.min(expiry, paramExpiry) : paramExpiry;
    const tidalTokenExpiry = /^(\d{10})~/.exec(parsed.searchParams.get("token") || "");
    if (tidalTokenExpiry) {
      const tokenExpiry = Number(tidalTokenExpiry[1]);
      expiry = expiry ? Math.min(expiry, tokenExpiry) : tokenExpiry;
    }
  } catch { /* URLs without readable expiry are not cached */ }
  return expiry;
}

function earliestTidalExpiry(manifest) {
  let expiry = 0;
  for (const match of manifest.matchAll(/[?&]token=(\d{10})~/gu)) {
    const candidate = Number(match[1]);
    if (candidate > now() && (!expiry || candidate < expiry)) expiry = candidate;
  }
  return expiry;
}

/**
 * Whether a Tidal track has a Hi-Res lossless master: the only kind this client can
 * actually fetch as FLAC (CD-quality tracks come back as AAC). Cached in D1 for 30 days.
 * A cache miss costs one metadata call on a seat of its own (paced like any call; never
 * straight after the playback call on the same seat, which is a burst). The daily stream
 * quota is untouched. No free seat → unknown, and the next play asks again.
 * @returns {Promise<boolean|undefined>} undefined when unknown; never throws.
 */
async function trackHires(trackId, env, request) {
  try {
    const row = await env.DB.prepare("SELECT hires FROM tidal_hires_cache WHERE track_id=? AND checked_at>? LIMIT 1")
      .bind(String(trackId), now() - HIRES_TTL).first();
    if (row) return row.hires === 1;
    // Over the fair share: hires stays unknown rather than refusing a stream already answered.
    if (!await upstreamAllowed(request, env)) return undefined;
    const seat = await takeAccount(env);
    if (!seat) return undefined;
    const account = await refreshIfNeeded(seat, env);
    if (!account) return undefined;
    const cc = encodeURIComponent(env.TIDAL_COUNTRY_CODE || "US");
    const r = await tidalFetch(`/tracks/${trackId}?countryCode=${cc}`, account, env);
    if (r.status === 429) {
      await onUpstream429(env, seat, "metadata");
      return undefined;
    }
    if (r.status === 401 && (await r.clone().json().catch(() => ({}))).subStatus !== 4005) {
      await onSessionRefused(env, seat, "metadata");
      return undefined;
    }
    if (!r.ok) return undefined;
    const track = await r.json();
    const tags = track?.mediaMetadata?.tags;
    if (!Array.isArray(tags) && typeof track?.audioQuality !== "string") return undefined;
    const hires = (Array.isArray(tags) && tags.includes("HIRES_LOSSLESS"))
      || ["HI_RES_LOSSLESS", "HI_RES"].includes(track.audioQuality);
    await env.DB.prepare("INSERT OR REPLACE INTO tidal_hires_cache(track_id,hires,checked_at) VALUES(?,?,?)")
      .bind(String(trackId), hires ? 1 : 0, now()).run().catch(() => {});
    return hires;
  } catch {
    return undefined; // metadata is a nicety: never block playback on it
  }
}

/** Tidal's progressive AAC stream from a below-lossless playbackinfo answer, or lossless_unavailable. */
function aacInfo(info, hires) {
  if (!String(info.manifestMimeType || "").toLowerCase().includes("bts") || !info.manifest) return json({ error: "lossless_unavailable" }, 404);
  const bts = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(info.manifest), (c) => c.charCodeAt(0))));
  const codecs = String(bts.codecs || "").toLowerCase();
  const audioUrl = bts.urls?.[0];
  if (!/mp4a|aac/.test(codecs) || !audioUrl?.startsWith("https://")) return json({ error: "lossless_unavailable" }, 404);
  return json({ url: audioUrl, codec: "aac", codecs, container: "mp4", manifest: "none", audioQuality: info.audioQuality, bitDepth: 0, sampleRate: 44100, bitrate: 320, ...urlExpiry(audioUrl), ...(typeof hires === "boolean" ? { hires } : {}) });
}

/**
 * { expiresAt } (epoch seconds) for a progressive Tidal URL, read the way streamAnswerExpiry
 * reads it (token=<unix>~ / exp=), so the app knows when to re-ask; {} when it is unreadable.
 */
function urlExpiry(audioUrl) {
  const expiresAt = streamAnswerExpiry({ url: audioUrl });
  return expiresAt > 0 ? { expiresAt } : {};
}

async function streamInfo(trackId, request, env, info, allowAac = false) {
  const encoded = info.manifest;
  if (!encoded) return json({ error: "manifest_missing" }, 404);
  const bytes = Uint8Array.from(atob(encoded), (c) => c.charCodeAt(0));
  const mime = String(info.manifestMimeType || "").toLowerCase();
  const bitDepth = Number(info.bitDepth) || 16;
  const sampleRate = Number(info.sampleRate) || 44100;
  if (mime.includes("bts")) {
    const bts = JSON.parse(new TextDecoder().decode(bytes));
    const codecs = String(bts.codecs || "").toLowerCase();
    const mtype = String(bts.mimeType || "").toLowerCase();
    // Tidal can label a playbackinfo LOSSLESS while the BTS file inside is AAC.
    if (/opus|mp4a|aac|mp3|he-aac/.test(codecs) || !(codecs.includes("flac") || mtype.includes("flac") || !codecs)) {
      return allowAac ? aacInfo(info) : json({ error: "lossless_unavailable" }, 404);
    }
    const audioUrl = bts.urls?.[0];
    if (!audioUrl?.startsWith("https://")) return json({ error: "invalid_media_url" }, 502);
    return json({ url: audioUrl, codec: "flac", container: audioUrl.toLowerCase().includes(".mp4") ? "mp4" : "flac", manifest: "none", bitDepth, sampleRate, bitrate: 1411, ...urlExpiry(audioUrl) });
  }
  if (mime.includes("dash")) {
    let xml = new TextDecoder().decode(bytes);
    const init = /initialization="([^"]+)"/.exec(xml)?.[1];
    if (!init || !init.startsWith("https://")) return json({ error: "invalid_dash_manifest" }, 502);
    const manifestExpiry = earliestTidalExpiry(xml);
    const exp = Math.min(now() + 3600, manifestExpiry ? manifestExpiry - 30 : Number.MAX_SAFE_INTEGER);
    if (exp <= now() + STREAM_CACHE_SAFETY) return json({ error: "manifest_expired" }, 503);
    await env.DB.prepare(`INSERT OR REPLACE INTO tidal_dash_manifests(track_id,manifest_xml,init_url,expires_at) VALUES(?,?,?,?)`)
      .bind(trackId, xml, init, exp).run();
    const origin = (env.PUBLIC_HOST || new URL(request.url).origin).replace(/\/$/, "");
    const mpdPath = `/dash/td/${trackId}.mpd`;
    const signedMpd = `${origin}${mpdPath}?exp=${exp}&sig=${await signature(dashKey(env), mpdPath, exp)}`;
    return json({ url: signedMpd, codec: "flac", container: "fmp4", manifest: "dash", bitDepth, sampleRate, bitrate: Math.max(1411, Math.floor(sampleRate * bitDepth * 2 / 1000)) });
  }
  return json({ error: "unsupported_manifest" }, 404);
}

// ?web=1 (the web player's FLAC lane): Tidal's CDN refuses Cloudflare egress and browsers with a foreign Origin, so the
// web gets the FLAC through the tidal-edge service (Deno Deploy), which Tidal does serve. The relay signs an HMAC token
// (EDGE_SECRET, shared with the edge) naming exactly the Tidal URLs it would otherwise hand out; the edge only fetches
// those. No D1 write: the token carries the MPD. Without EDGE_SECRET/EDGE_BASE, ?web=1 is ignored (normal answer).
const EDGE_TTL_S = 3500;

/** The edge origin when ?web=1 is asked for and the edge is configured, else null. */
function webEdge(request, env) {
  if (new URL(request.url).searchParams.get("web") !== "1") return null;
  if (!env.EDGE_SECRET || env.EDGE_SECRET.length < 32 || !env.EDGE_BASE) return null;
  try {
    const u = new URL(env.EDGE_BASE);
    const local = u.hostname === "127.0.0.1" || u.hostname === "localhost";
    if (u.protocol !== "https:" && !(local && u.protocol === "http:")) return null;
    return u.origin;
  } catch { return null; }
}

/** Earliest of now+EDGE_TTL_S and the expiry in Tidal's own signed URL (token=<unix>~...), when readable. */
function edgeExpiry(tidalUrl) {
  let exp = now() + EDGE_TTL_S;
  try {
    const m = /^(\d{10})~/.exec(new URL(tidalUrl).searchParams.get("token") || "");
    if (m && Number(m[1]) > now() + 60) exp = Math.min(exp, Number(m[1]) - 30);
  } catch { /* keep the default */ }
  return exp;
}

function b64url(bytes) { let s = ""; for (const b of bytes) s += String.fromCharCode(b); return btoa(s).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, ""); }

/** Same format the edge verifies: base64url(JSON) "." base64url(HMAC-SHA256(secret, base64url(JSON))). */
export async function edgeToken(secret, payload) {
  const body = b64url(new TextEncoder().encode(JSON.stringify(payload)));
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return `${body}.${b64url(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(body))))}`;
}

/** A lossless playbackinfo answer as edge URLs: DASH -> manifest.mpd + file.flac; progressive native FLAC -> stream.flac + file.flac. */
async function edgeInfo(trackId, info, env, edge) {
  if (!info.manifest) return json({ error: "manifest_missing" }, 404);
  const text = new TextDecoder().decode(Uint8Array.from(atob(info.manifest), (c) => c.charCodeAt(0)));
  const mime = String(info.manifestMimeType || "").toLowerCase();
  const bitDepth = Number(info.bitDepth) || 16;
  const sampleRate = Number(info.sampleRate) || 44100;
  const fn = `td-${trackId}`;
  let payload, kind;
  if (mime.includes("dash")) {
    const init = /initialization="([^"]+)"/.exec(text)?.[1];
    if (!init?.startsWith("https://")) return json({ error: "invalid_dash_manifest" }, 502);
    payload = { v: 1, k: "d", exp: edgeExpiry(init.replaceAll("&amp;", "&")), x: text, fn };
    kind = "dash";
  } else if (mime.includes("bts")) {
    const bts = JSON.parse(text);
    const codecs = String(bts.codecs || "").toLowerCase();
    const audioUrl = bts.urls?.[0];
    if (/opus|mp4a|aac|mp3/.test(codecs) || !audioUrl?.startsWith("https://")) return json({ error: "lossless_unavailable" }, 404);
    // The edge's file.flac / stream.flac pass the file through as FLAC: only a native .flac file qualifies.
    if (new URL(audioUrl).pathname.toLowerCase().includes(".mp4")) return json({ error: "lossless_unavailable" }, 404);
    payload = { v: 1, k: "p", exp: edgeExpiry(audioUrl), u: audioUrl, fn };
    kind = "none";
  } else return json({ error: "unsupported_manifest" }, 404);
  const token = await edgeToken(env.EDGE_SECRET, payload);
  const t = `${edge}/t/${token}`;
  return json({
    url: kind === "dash" ? `${t}/manifest.mpd` : `${t}/stream.flac`,
    download: `${t}/file.flac`,
    codec: "flac", container: kind === "dash" ? "fmp4" : "flac", manifest: kind, edge: true,
    expiresAt: payload.exp, audioQuality: info.audioQuality, bitDepth, sampleRate,
    bitrate: Math.max(1411, Math.floor(sampleRate * bitDepth * 2 / 1000)),
  });
}

/**
 * HMAC key for /dash links. ACCESS_TOKEN ships in every install (effectively public), so links
 * signed with it can be forged; DASH_SIGNING_KEY (secret, >= 32 chars) replaces it. The fallback
 * keeps a deploy made before the secret exists working; links signed earlier stop verifying
 * once it is set (they last at most an hour).
 */
function dashKey(env) {
  return typeof env.DASH_SIGNING_KEY === "string" && env.DASH_SIGNING_KEY.length >= 32 ? env.DASH_SIGNING_KEY : env.ACCESS_TOKEN;
}

async function dash(request, trackId, env, isInit) {
  const url = new URL(request.url);
  if (!await validSignature(dashKey(env), url.pathname, url.searchParams.get("exp"), url.searchParams.get("sig"))) return unauthorized();
  const row = await env.DB.prepare("SELECT manifest_xml,init_url,expires_at FROM tidal_dash_manifests WHERE track_id=? AND expires_at>? LIMIT 1").bind(trackId, now()).first();
  if (!row) return json({ error: "manifest_expired" }, 404);
  if (isInit) {
    const initUrl = row.init_url.replaceAll("&amp;", "&");
    const r = await fetch(initUrl, { headers: { "User-Agent": UA }, redirect: "follow" });
    if (!r.ok) {
      // Host + status only: the init URL carries Tidal's signed token.
      console.log(`init_unavailable track=${trackId} host=${new URL(initUrl).host} status=${r.status} body=${(await r.text().catch(() => "")).slice(0, 200)}`);
      return json({ error: "init_unavailable" }, 502);
    }
    const raw = new Uint8Array(await r.arrayBuffer());
    const flac = indexOfBytes(raw, [0x66, 0x4c, 0x61, 0x43]);
    if (flac >= 0 && raw.length >= flac + 32 && raw.slice(flac + 28, flac + 32).every((b) => b === 0)) raw.set([0xac, 0x44, 0x00, 0x00], flac + 28);
    return new Response(raw, { headers: { "content-type": "audio/mp4", "content-length": String(raw.length), "cache-control": "private, max-age=300", "access-control-allow-origin": "*" } });
  }
  // Tidal's CDN answers 403 to Cloudflare egress, so the Worker cannot proxy the
  // init segment: keep Tidal's own URL in the MPD and let the player fetch it,
  // like every media segment. Set PROXY_DASH_INIT=1 to restore the proxied path.
  if (env.PROXY_DASH_INIT !== "1") {
    return new Response(row.manifest_xml, { headers: { "content-type": "application/dash+xml", "cache-control": "no-store", "access-control-allow-origin": "*" } });
  }
  const initPath = `/dash/td/${trackId}/init.mp4`;
  const exp = Math.min(row.expires_at, now() + 3600);
  const origin = (env.PUBLIC_HOST || new URL(request.url).origin).replace(/\/$/, "");
  const initUrl = `${origin}${initPath}?exp=${exp}&sig=${await signature(dashKey(env), initPath, exp)}`;
  const xml = row.manifest_xml.replace(/initialization="[^"]+"/, `initialization="${initUrl.replaceAll("&", "&amp;")}"`);
  return new Response(xml, { headers: { "content-type": "application/dash+xml", "cache-control": "no-store", "access-control-allow-origin": "*" } });
}

/**
 * Exactly ONE upstream playbackinfo call: the configured country, the requested quality.
 * Tidal answers with the best quality available up to the requested level, so asking
 * HI_RES_LOSSLESS covers hi-res and (where this client gets it) CD FLAC; a HIGH answer
 * is final. The old quality x country walk cost up to 6 calls per stream for CD-only
 * tracks, which this client never receives as FLAC, and drew Tidal 429s.
 */
async function playbackInfo(trackId, account, env, quality) {
  const p = new URLSearchParams({ countryCode: env.TIDAL_COUNTRY_CODE || "US", audioquality: quality, playbackmode: "STREAM", assetpresentation: "FULL" });
  const r = await tidalFetch(`/tracks/${trackId}/playbackinfopostpaywall?${p}`, account, env);
  if (!r.ok && r.status !== 429) { // a 429 is logged by the caller's onUpstream429
    // Redacted: last 4 of the seat id, status and Tidal's user message only.
    const details = await r.clone().json().catch(() => ({}));
    const userMessage = typeof details.userMessage === "string" ? details.userMessage.replace(/[\r\n]/g, " ").slice(0, 240) : null;
    console.warn(`tidal_playbackinfo ${JSON.stringify({ account: String(account.id).slice(-4), status: r.status, subStatus: details.subStatus ?? null, userMessage })}`);
  }
  return r;
}

function capWindow(env) {
  return {
    hour: new Date().toISOString().slice(0, 13),
    day: new Date().toISOString().slice(0, 10),
    hourly: positive(env.TIDAL_ACCOUNT_HOURLY_CAP, 250),
    daily: positive(env.TIDAL_ACCOUNT_DAILY_CAP, 1600),
  };
}

/**
 * Takes the least-recently-used live seat under its caps and counts one upstream call
 * against it. Every caller (search, playback, metadata) is paced alike: the seat must have
 * made no call in the last TIDAL_SEAT_MIN_GAP_SECONDS (default 20), and last_call_at is
 * stamped. Tidal's 429 is a burst limit (5 calls in 14 s tripped an idle seat), so the gap,
 * not the hourly/daily caps, is what keeps a seat under it.
 */
async function takeAccount(env) {
  const { hour, day, hourly, daily } = capWindow(env);
  const t = now();
  const gapCutoff = t - positive(env.TIDAL_SEAT_MIN_GAP_SECONDS, 20);
  return await env.DB.prepare(`UPDATE tidal_accounts SET
      hour_n=CASE WHEN hour_key=?1 THEN hour_n+1 ELSE 1 END,hour_key=?1,
      day_n=CASE WHEN day_key=?2 THEN day_n+1 ELSE 1 END,day_key=?2,last_used_at=?3,last_call_at=?3
    WHERE id=(SELECT id FROM tidal_accounts WHERE state='live' AND cooling_until<=?3 AND (hour_key!=?1 OR hour_n<?4) AND (day_key!=?2 OR day_n<?5)
      AND last_call_at<=?6 ORDER BY last_used_at,id LIMIT 1)
    RETURNING *`).bind(hour, day, t, hourly, daily, gapCutoff).first();
}

/**
 * Counts one extra upstream Tidal call against a seat already taken (takeAccount counts
 * the first): the post-refresh retry in tidalFetch. Keeps hour_n/day_n equal to real
 * upstream load and restarts the seat's gap. Never throws.
 */
async function bumpSeat(env, id) {
  const { hour, day } = capWindow(env);
  await env.DB.prepare(`UPDATE tidal_accounts SET
      hour_n=CASE WHEN hour_key=?1 THEN hour_n+1 ELSE 1 END,hour_key=?1,
      day_n=CASE WHEN day_key=?2 THEN day_n+1 ELSE 1 END,day_key=?2,last_call_at=?4
    WHERE id=?3`).bind(hour, day, id, now()).run().catch(() => {});
}

/**
 * Per-network cap for every answered stream (cache hit or not). The global cap protects Tidal,
 * so it only gates cache misses (stream() checks quota.globalFull): cheap cached answers from
 * many networks must not be able to exhaust it and switch Tidal off for everyone.
 */
async function admitStream(request, env) {
  const day = new Date().toISOString().slice(0, 10);
  const network = networkOf(request.headers.get("CF-Connecting-IP"));
  const tag = networkTag(env, network);
  const keys = ["global", ...(tag ? [`n:${tag}`] : [])];
  const used = await dailyUsed(env, day, keys);
  const globalCap = positive(env.GLOBAL_DAILY_CAP, 34667);
  const networkCap = positive(env.NETWORK_DAILY_CAP, 2000);
  if (tag && (used[`n:${tag}`] || 0) >= networkCap) {
    return { denial: json({ error: "network_daily_cap" }, 429, { "Retry-After": "3600" }) };
  }
  return { day, tag, globalFull: (used.global || 0) >= globalCap };
}

/**
 * The verified install behind /stream and /quota. The app signs X-Stash-Install/-Ts/-Auth with the Qobuz
 * relay's key (secrets RELAY_KEY / RELAY_KEY_PREV, same values) over "<install>:<middle>:<ts>".
 * @returns {{subject: string}|{denial: Response}|{none: "legacy"|"no_key"}} legacy = no install headers at all
 * (served uncounted, as before, unless REQUIRE_INSTALL=1); no_key = RELAY_KEY unset, so headers can't be
 * checked and the counter is off. A bad or forged signature is always a 401 with X-Stash-Server-Time.
 */
function installOf(request, env, middle) {
  const h = (name) => request.headers.get(name) || "";
  const install = h("X-Stash-Install"), ts = h("X-Stash-Ts"), auth = h("X-Stash-Auth");
  const requireInstall = env.REQUIRE_INSTALL === "1";
  if (!install && !ts && !auth) return requireInstall ? { denial: installUnauthorized() } : { none: "legacy" };
  if (!env.RELAY_KEY) {
    if (requireInstall) return { denial: json({ error: "server_misconfigured", message: "RELAY_KEY not set" }, 500) };
    // Lets the app ship the headers before the secret exists; the counter just stays off until it does.
    if (!warnedNoRelayKey) { warnedNoRelayKey = true; console.warn("RELAY_KEY not set: install headers ignored, daily lossless counter OFF"); }
    return { none: "no_key" };
  }
  if (!verifySigned({ install, ts, auth }, middle, now(), [env.RELAY_KEY, env.RELAY_KEY_PREV])) return { denial: installUnauthorized() };
  return { subject: `i:${install}` };
}
let warnedNoRelayKey = false;

/** Same body as the access-token 401; X-Stash-Server-Time (only here) tells the app it is the install signature, and lets a skewed clock correct itself. */
function installUnauthorized() { return json({ error: "unauthorized" }, 401, { "X-Stash-Server-Time": String(now()) }); }

/**
 * After a /stream answer: a 200 with codec "flac" (progressive, DASH or edge, a relay cache hit included) costs
 * one unit and carries the counter headers. AAC, 404s and errors are free. `fallback` (?aac=1 past the cap):
 * the AAC answer carries the headers, uncounted, so the app sees it is spent.
 */
async function meterLossless(response, standing, fallback) {
  if (response.status !== 200) return;
  if (!fallback) {
    const body = await response.clone().json().catch(() => ({}));
    if (body.codec !== "flac") return;
    await spendLossless(standing);
  }
  for (const [k, v] of Object.entries(losslessHeaders(standing))) response.headers.set(k, v);
}

/**
 * GET /quota: where the install stands today, for the app's cold start. Signed over "<install>:quota:<ts>",
 * behind REQUEST_RL like every route, never counted. 404 quota_off when the counter is off (no QUOTA_DB or no
 * RELAY_KEY): the status an older relay without the route gives too.
 */
async function quotaRoute(request, env) {
  const caller = installOf(request, env, "quota");
  if (caller.denial) return caller.denial;
  if (caller.none === "legacy") return installUnauthorized();
  const standing = caller.subject ? await readStanding(request, env, caller.subject, now()) : null;
  if (!standing) return json({ error: "quota_off" }, 404);
  return json(quotaBody(standing), 200, losslessHeaders(standing));
}

function globalCapped() { return json({ error: "global_daily_cap" }, 503, { "Retry-After": "600" }); }

/** Network key for every answered stream; `global` only when the answer came from Tidal. */
async function spendStream(request, env, quota, upstream) {
  const tag = quota.tag || networkTag(env, networkOf(request.headers.get("CF-Connecting-IP")));
  const statements = upstream ? [bumpDailyQuotaStmt(env.DB, quota.day, "global")] : [];
  if (tag) statements.push(bumpDailyQuotaStmt(env.DB, quota.day, `n:${tag}`));
  if (statements.length) await env.DB.batch(statements);
}

/** Today's counters for `keys` in tidal_daily_quotas, as { key: n } (absent = 0). */
async function dailyUsed(env, day, keys) {
  const { results = [] } = await env.DB.prepare(
    "SELECT key, n FROM tidal_daily_quotas WHERE day = ?1 AND key IN (SELECT value FROM json_each(?2))",
  ).bind(day, JSON.stringify(keys)).all();
  return Object.fromEntries(results.map((row) => [row.key, row.n]));
}

function bumpDailyQuotaStmt(db, day, key) {
  return db.prepare(
    "INSERT INTO tidal_daily_quotas (day, key, n) VALUES (?1, ?2, 1) ON CONFLICT(day, key) DO UPDATE SET n = n + 1",
  ).bind(day, key);
}

export function networkOf(addr) {
  if (!addr) return "?";
  if (!addr.includes(":") || addr.includes(".")) return addr;
  const [head, tail] = addr.toLowerCase().split("::");
  const left = head ? head.split(":") : [];
  const right = tail ? tail.split(":") : [];
  const groups = tail === undefined ? left : [...left, ...Array(Math.max(0, 8 - left.length - right.length)).fill("0"), ...right];
  const g = [0, 1, 2, 3].map((i) => parseInt(groups[i] || "0", 16) || 0);
  return `${g[0].toString(16)}:${g[1].toString(16)}:${g[2].toString(16)}:${(g[3] >> 8).toString(16)}/56`;
}

export function networkTag(env, network) {
  return env.IP_SALT && network !== "?"
    ? createHmac("sha256", env.IP_SALT).update(network).digest("hex").slice(0, 16)
    : null;
}

async function coolAccount(env, id, seconds) {
  await env.DB.prepare("UPDATE tidal_accounts SET cooling_until=? WHERE id=? AND state='live'").bind(now() + seconds, id).run();
}

async function killAccount(env, id, reason) {
  await env.DB.prepare("UPDATE tidal_accounts SET state='dead',dead_reason=? WHERE id=?").bind(reason, id).run();
}

async function refreshAccount(account, env) {
  if (!account.refresh_token) return null;
  const claimed = await env.DB.prepare(`UPDATE tidal_accounts SET refresh_lock_until=? WHERE id=? AND generation=? AND refresh_lock_until<=? RETURNING *`)
    .bind(now() + 30, account.id, account.generation, now()).first();
  if (!claimed) {
    for (let i = 0; i < 10; i++) {
      await new Promise((resolve) => setTimeout(resolve, 100));
      const latest = await env.DB.prepare("SELECT * FROM tidal_accounts WHERE id=?").bind(account.id).first();
      if (!latest) return null;
      if (latest.generation !== account.generation && latest.access_token) return latest;
    }
    return null;
  }
  const form = new URLSearchParams({ client_id: env.TIDAL_CLIENT_ID, refresh_token: claimed.refresh_token, grant_type: "refresh_token", scope: SCOPE });
  if (env.TIDAL_CLIENT_SECRET) form.set("client_secret", env.TIDAL_CLIENT_SECRET);
  const r = await fetch(`${AUTH}/token`, { method: "POST", headers: oauthHeaders(), body: form }).catch(() => null);
  if (!r?.ok) {
    await env.DB.prepare("UPDATE tidal_accounts SET refresh_lock_until=0 WHERE id=? AND generation=?").bind(claimed.id, claimed.generation).run();
    // Only Tidal's verdict on THIS seat's grant retires it; 5xx/network failures are cooled by the caller.
    // invalid_client is about the shared client credentials, the same for every seat: retiring on it would
    // take the whole pool down one seat at a time, so it only cools (and logs) like a transient failure.
    const error = r && (r.status === 400 || r.status === 401) ? (await r.json().catch(() => ({}))).error : null;
    if (error === "invalid_grant") await killAccount(env, claimed.id, "refresh_invalid");
    else if (error === "invalid_client") console.error("tidal refresh: invalid_client (shared client credentials?)");
    return null;
  }
  const d = await r.json();
  await env.DB.prepare(`UPDATE tidal_accounts SET access_token=?,refresh_token=?,token_expires_at=?,generation=generation+1,refresh_lock_until=0,updated_at=? WHERE id=? AND generation=?`)
    .bind(d.access_token, d.refresh_token || claimed.refresh_token, now() + Number(d.expires_in || 604800), now(), claimed.id, claimed.generation).run();
  return { ...claimed, access_token: d.access_token, refresh_token: d.refresh_token || claimed.refresh_token, token_expires_at: now() + Number(d.expires_in || 604800), generation: claimed.generation + 1 };
}

async function refreshIfNeeded(account, env) {
  if (account.token_expires_at > now() + 120) return account;
  return refreshAccount(account, env);
}

async function tidalFetch(path, account, env) {
  const headers = tidalHeaders(account.access_token);
  let r = await fetch(`${API}${path}`, { headers, signal: AbortSignal.timeout(9000) });
  if (r.status === 401 && account.refresh_token) {
    // Tidal subStatus 4005 is an asset/rights refusal, not an expired session.
    const error = await r.clone().json().catch(() => ({}));
    if (error.subStatus === 4005) return r;
    const fresh = await refreshAccount(account, env);
    // Refresh failed (lock contention, 5xx, network, or rejected and the seat retired): a seat-level
    // failure like a Tidal 5xx, so callers cool it / try another seat instead of reading a 401.
    if (!fresh) {
      await coolAccount(env, account.id, 300);
      return json({ error: "refresh_failed" }, 503);
    }
    await bumpSeat(env, account.id); // the retry is a second upstream call on this seat
    r = await fetch(`${API}${path}`, { headers: tidalHeaders(fresh.access_token), signal: AbortSignal.timeout(9000) });
  }
  return r;
}

function oauthHeaders() { return { "User-Agent": UA, Accept: "application/json", "X-Platform": "android", "X-Tidal-Platform": "android", "content-type": "application/x-www-form-urlencoded" }; }
function tidalHeaders(accessToken) {
  const headers = { ...oauthHeaders() };
  delete headers["content-type"];
  if (accessToken?.startsWith("CzET")) headers["x-tidal-token"] = accessToken;
  else headers.authorization = `Bearer ${accessToken}`;
  return headers;
}
function authorized(request, token) { return Boolean(token && request.headers.get("X-Access-Token") && constantTime(request.headers.get("X-Access-Token"), token)); }
function unauthorized() { return json({ error: "unauthorized" }, 401); }
function json(data, status = 200, extra = {}) { return new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extra } }); }
function now() { return Math.floor(Date.now() / 1000); }
function positive(value, fallback) { const n = Number.parseInt(value, 10); return n > 0 ? n : fallback; }
function constantTime(a, b) { if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false; let x = 0; for (let i = 0; i < a.length; i++) x |= a.charCodeAt(i) ^ b.charCodeAt(i); return x === 0; }
async function signature(secret, path, exp) { const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]); const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${path}:${exp}`))); return [...mac].map((b) => b.toString(16).padStart(2, "0")).join(""); }
async function validSignature(secret, path, expRaw, sig) { const exp = Number(expRaw); if (!secret || !Number.isInteger(exp) || exp < now() || exp > now() + 3700 || typeof sig !== "string") return false; return constantTime(await signature(secret, path, exp), sig); }
function indexOfBytes(haystack, needle) { outer: for (let i = 0; i <= haystack.length - needle.length; i++) { for (let j = 0; j < needle.length; j++) if (haystack[i + j] !== needle[j]) continue outer; return i; } return -1; }
