import { createHmac } from "node:crypto";

const API = "https://api.tidal.com/v1";
const AUTH = "https://auth.tidal.com/v1/oauth2";
const UA = "okhttp/5.3.2";
const SCOPE = "r_usr+w_usr+w_sub";

export default {
  async scheduled(_event, env) {
    const cutoff = new Date((now() - 7 * 86400) * 1000).toISOString().slice(0, 10);
    await env.DB.batch([
      env.DB.prepare("DELETE FROM tidal_daily_quotas WHERE day < ?").bind(cutoff),
      env.DB.prepare("DELETE FROM tidal_dash_manifests WHERE expires_at <= ?").bind(now()),
    ]);
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
        return await search(url, request, env);
      }
      const path = decodeURIComponent(url.pathname);
      const streamMatch = path.match(/^\/stream\/(?:td:)?(\d+)$/);
      if (streamMatch && request.method === "GET") {
        if (!authorized(request, env.ACCESS_TOKEN)) return unauthorized();
        const quota = await admitStream(request, env);
        if (quota.denial) return quota.denial;
        const response = await stream(streamMatch[1], request, env);
        // Like Qobuz's answered-mint quota: successful streams and definitive
        // track misses count once per caller request; upstream/account failures do not.
        if (response.status === 200 || response.status === 404) await spendStream(request, env, quota);
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
  const query = (url.searchParams.get("q") || "").trim();
  if (!query) return json({ tracks: [] });
  const cc = env.TIDAL_COUNTRY_CODE || "US";
  let data;
  for (let attempt = 0; attempt < 2; attempt++) {
    const selected = await takeAccount(env);
    if (!selected) return json({ tracks: [] }, 503);
    const account = await refreshIfNeeded(selected, env);
    if (!account) { await coolAccount(env, selected.id, 300); continue; }
    const r = await tidalFetch(`/search/tracks?query=${encodeURIComponent(query)}&limit=30&offset=0&countryCode=${cc}`, account, env);
    if (r.status === 401) {
      const denial = await r.clone().json().catch(() => ({}));
      if (denial.subStatus !== 4005) { await killAccount(env, selected.id, "401"); continue; }
      return json({ tracks: [] });
    }
    if (r.status >= 500) { await coolAccount(env, selected.id, 300); continue; }
    if (!r.ok) return json({ tracks: [] });
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

async function stream(trackId, request, env) {
  // ?aac=1: when no account can play the track losslessly, answer with Tidal's AAC
  // stream (labelled codec "aac") instead of lossless_unavailable. Opt-in so the
  // download path, which must never save AAC as FLAC, keeps the lossless-only answer.
  // ?aac=only (the web player): ask Tidal for its 320 kbps AAC straight away, for any
  // track. A browser can only play Tidal's progressive file; Hi-Res comes as DASH.
  const aacMode = new URL(request.url).searchParams.get("aac");
  const aacOnly = aacMode === "only";
  const allowAac = aacMode === "1" || aacOnly;
  let refusedBy = null;
  let lossyInfo = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    const account = await takeAccount(env, refusedBy);
    if (!account) {
      if (allowAac && lossyInfo) return aacInfo(lossyInfo);
      return json({ error: refusedBy ? "lossless_unavailable" : "no_account" }, refusedBy ? 404 : 503);
    }
    const fresh = await refreshIfNeeded(account, env);
    if (!fresh) {
      await coolAccount(env, account.id, 300);
      continue;
    }
    const data = await playbackInfo(trackId, fresh, env, aacOnly ? ["HIGH"] : undefined);
    if (data.status === 401) {
      const denial = await data.clone().json().catch(() => ({}));
      if (denial.subStatus === 4005) return json({ error: "not_available" }, 404);
      await killAccount(env, account.id, "401");
      continue;
    }
    if (!data.ok) {
      if (data.status >= 500) await coolAccount(env, account.id, 300);
      if (data.status === 404) return json({ error: "not_available" }, 404);
      continue;
    }
    const info = await data.json();
    if (aacOnly) return aacInfo(info);
    if (!["LOSSLESS", "HI_RES_LOSSLESS"].includes(info.audioQuality)) {
      lossyInfo = info;
      if (attempt === 0) { refusedBy = account.id; continue; }
      if (allowAac) return aacInfo(info);
      return json({ error: "lossless_unavailable" }, 404);
    }
    if (refusedBy) await coolAccount(env, refusedBy, 3600);
    return await streamInfo(trackId, request, env, info, allowAac);
  }
  return json({ error: refusedBy ? "lossless_unavailable" : "upstream_unavailable" }, refusedBy ? 404 : 503);
}

/** Tidal's progressive AAC stream from a below-lossless playbackinfo answer, or lossless_unavailable. */
function aacInfo(info) {
  if (!String(info.manifestMimeType || "").toLowerCase().includes("bts") || !info.manifest) return json({ error: "lossless_unavailable" }, 404);
  const bts = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(info.manifest), (c) => c.charCodeAt(0))));
  const codecs = String(bts.codecs || "").toLowerCase();
  const audioUrl = bts.urls?.[0];
  if (!/mp4a|aac/.test(codecs) || !audioUrl?.startsWith("https://")) return json({ error: "lossless_unavailable" }, 404);
  return json({ url: audioUrl, codec: "aac", codecs, container: "mp4", manifest: "none", audioQuality: info.audioQuality, bitDepth: 0, sampleRate: 44100, bitrate: 320 });
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
    return json({ url: audioUrl, codec: "flac", container: audioUrl.toLowerCase().includes(".mp4") ? "mp4" : "flac", manifest: "none", bitDepth, sampleRate, bitrate: 1411 });
  }
  if (mime.includes("dash")) {
    let xml = new TextDecoder().decode(bytes);
    const init = /initialization="([^"]+)"/.exec(xml)?.[1];
    if (!init || !init.startsWith("https://")) return json({ error: "invalid_dash_manifest" }, 502);
    const exp = now() + 3600;
    await env.DB.prepare(`INSERT OR REPLACE INTO tidal_dash_manifests(track_id,manifest_xml,init_url,expires_at) VALUES(?,?,?,?)`)
      .bind(trackId, xml, init, exp).run();
    const origin = (env.PUBLIC_HOST || new URL(request.url).origin).replace(/\/$/, "");
    const mpdPath = `/dash/td/${trackId}.mpd`;
    const signedMpd = `${origin}${mpdPath}?exp=${exp}&sig=${await signature(env.ACCESS_TOKEN, mpdPath, exp)}`;
    return json({ url: signedMpd, codec: "flac", container: "fmp4", manifest: "dash", bitDepth, sampleRate, bitrate: Math.max(1411, Math.floor(sampleRate * bitDepth * 2 / 1000)) });
  }
  return json({ error: "unsupported_manifest" }, 404);
}

async function dash(request, trackId, env, isInit) {
  const url = new URL(request.url);
  if (!await validSignature(env.ACCESS_TOKEN, url.pathname, url.searchParams.get("exp"), url.searchParams.get("sig"))) return unauthorized();
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
  const initUrl = `${origin}${initPath}?exp=${exp}&sig=${await signature(env.ACCESS_TOKEN, initPath, exp)}`;
  const xml = row.manifest_xml.replace(/initialization="[^"]+"/, `initialization="${initUrl.replaceAll("&", "&amp;")}"`);
  return new Response(xml, { headers: { "content-type": "application/dash+xml", "cache-control": "no-store", "access-control-allow-origin": "*" } });
}

async function playbackInfo(trackId, account, env, qualities = ["HI_RES_LOSSLESS", "LOSSLESS"]) {
  let fallback = null;
  for (const quality of qualities) {
    for (const cc of unique([env.TIDAL_COUNTRY_CODE || "US", "ID", "GB", "US"])) {
      const p = new URLSearchParams({ countryCode: cc, audioquality: quality, playbackmode: "STREAM", assetpresentation: "FULL" });
      const r = await tidalFetch(`/tracks/${trackId}/playbackinfopostpaywall?${p}`, account, env);
      if (r.status === 401) return r;
      if (!r.ok) continue;
      if (quality === "HIGH") return r; // AAC asked for: the first answer is the one
      // Some Tidal regions return HTTP 200 with a lower quality than requested.
      // Keep searching for FLAC before giving up; otherwise a successful HIGH
      // response to HI_RES_LOSSLESS prevents the explicit LOSSLESS request.
      const info = await r.clone().json().catch(() => null);
      if (["LOSSLESS", "HI_RES_LOSSLESS"].includes(info?.audioQuality)) return r;
      fallback = r;
    }
  }
  return fallback || new Response("", { status: 404 });
}

async function takeAccount(env, excludeId = null) {
  const hour = new Date().toISOString().slice(0, 13);
  const day = new Date().toISOString().slice(0, 10);
  const hourly = positive(env.TIDAL_ACCOUNT_HOURLY_CAP, 621);
  const daily = positive(env.TIDAL_ACCOUNT_DAILY_CAP, 11000);
  return await env.DB.prepare(`UPDATE tidal_accounts SET
      hour_n=CASE WHEN hour_key=?1 THEN hour_n+1 ELSE 1 END,hour_key=?1,
      day_n=CASE WHEN day_key=?2 THEN day_n+1 ELSE 1 END,day_key=?2,last_used_at=?3
    WHERE id=(SELECT id FROM tidal_accounts WHERE state='live' AND cooling_until<=?3 AND (hour_key!=?1 OR hour_n<?4) AND (day_key!=?2 OR day_n<?5) AND id IS NOT ?6 ORDER BY last_used_at,id LIMIT 1)
    RETURNING *`).bind(hour, day, now(), hourly, daily, excludeId).first();
}

async function admitStream(request, env) {
  const day = new Date().toISOString().slice(0, 10);
  const network = networkOf(request.headers.get("CF-Connecting-IP"));
  const tag = networkTag(env, network);
  const keys = ["global", ...(tag ? [`n:${tag}`] : [])];
  const { results = [] } = await env.DB.prepare(
    "SELECT key, n FROM tidal_daily_quotas WHERE day = ?1 AND key IN (SELECT value FROM json_each(?2))",
  ).bind(day, JSON.stringify(keys)).all();
  const used = Object.fromEntries(results.map((row) => [row.key, row.n]));
  const globalCap = positive(env.GLOBAL_DAILY_CAP, 34667);
  const networkCap = positive(env.NETWORK_DAILY_CAP, 2000);
  if ((used.global || 0) >= globalCap) {
    return { denial: json({ error: "global_daily_cap" }, 503, { "Retry-After": "600" }) };
  }
  if (tag && (used[`n:${tag}`] || 0) >= networkCap) {
    return { denial: json({ error: "network_daily_cap" }, 429, { "Retry-After": "3600" }) };
  }
  return { day, tag };
}

async function spendStream(request, env, quota) {
  const tag = quota.tag || networkTag(env, networkOf(request.headers.get("CF-Connecting-IP")));
  const statements = [bumpDailyQuotaStmt(env.DB, quota.day, "global")];
  if (tag) statements.push(bumpDailyQuotaStmt(env.DB, quota.day, `n:${tag}`));
  await env.DB.batch(statements);
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
  const r = await fetch(`${AUTH}/token`, { method: "POST", headers: oauthHeaders(), body: form });
  if (!r.ok) {
    await env.DB.prepare("UPDATE tidal_accounts SET refresh_lock_until=0 WHERE id=? AND generation=?").bind(claimed.id, claimed.generation).run();
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
    if (fresh) r = await fetch(`${API}${path}`, { headers: tidalHeaders(fresh.access_token), signal: AbortSignal.timeout(9000) });
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
function unique(values) { return [...new Set(values)]; }
function constantTime(a, b) { if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false; let x = 0; for (let i = 0; i < a.length; i++) x |= a.charCodeAt(i) ^ b.charCodeAt(i); return x === 0; }
async function signature(secret, path, exp) { const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]); const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`${path}:${exp}`))); return [...mac].map((b) => b.toString(16).padStart(2, "0")).join(""); }
async function validSignature(secret, path, expRaw, sig) { const exp = Number(expRaw); if (!secret || !Number.isInteger(exp) || exp < now() || exp > now() + 3700 || typeof sig !== "string") return false; return constantTime(await signature(secret, path, exp), sig); }
function indexOfBytes(haystack, needle) { outer: for (let i = 0; i <= haystack.length - needle.length; i++) { for (let j = 0; j < needle.length; j++) if (haystack[i + j] !== needle[j]) continue outer; return i; } return -1; }
