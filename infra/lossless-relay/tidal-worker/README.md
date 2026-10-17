# Stash Tidal Relay Worker

Separate Cloudflare Worker implementing the Tidal subset of BitChord's relay contract. It uses D1 for OAuth refresh tokens, one-time device grants, playback request caps, and short-lived DASH state. Audio bytes remain on Tidal's CDN; only the DASH fMP4 initialization segment passes through this Worker so its zero FLAC sample-rate field can be patched for Media3.

## Contract

- `GET /health` → `{ "ok": true, "provider": "tidal" }`
- `GET /search?q=...` with `X-Access-Token` → `{ "tracks": [{ "id": "td:<id>", ... }] }`
- `GET /stream/td:<id>?client=media3` with `X-Access-Token` → progressive FLAC URL or a signed DASH MPD URL, with the BitChord response fields. Progressive answers (FLAC and AAC) add `expiresAt` (epoch seconds, from Tidal's signed URL) when it is readable; DASH answers carry their expiry as `exp` in the signed URL. `?aac` is one of `1`, `only` or absent; any other value is treated as absent.
- Signed `GET /dash/td/<id>.mpd` and `GET /dash/td/<id>/init.mp4` URLs need no header; their HMAC signatures (keyed by the `DASH_SIGNING_KEY` secret, see Deployment) expire after one hour.
- Admin-only `POST /admin/device/start`, then `POST /admin/device/poll` with `{ "user_code": "..." }` starts and completes the Tidal OAuth device flow. These require `X-Access-Token: <ADMIN_TOKEN>`.

Tidal accounts are linked through the device flow; passwords and OAuth tokens are never printed. The pool rotates least-recently-used live accounts, with a 100 requests/hour cap during ramp, a later target of 250/hour, and 1,600/day per account. It applies cooldowns for transient failures and retires an account only when Tidal rejects its refresh token (see the 401 rule below). A lower-than-lossless answer is final (no cross-check on another account), and Tidal's `subStatus=4005` is a track/rights refusal (`not_available`) that never kills an account. When no account is available, search and stream return `503 busy`: `Retry-After: 300` when seats are capped or cooling, `Retry-After: 15` when seats are only waiting out their pacing gap.

Protective limits mirror the Qobuz relay: 34,667 upstream-answered `/stream` requests/day shared across the pool, 2,000 answered streams/day per network, 2,000 uncached searches/day per network, 40 requests/60 seconds per network for provider/admin routes, and a 6/60 seconds per-network fair share of the seats. The network key is the IPv4 address or IPv6 `/56`, hashed with the private `IP_SALT`; raw IP addresses are not written to D1. `IP_SALT` is required for the daily network cap; without it only the shared global cap and request limiter apply. `/health` is exempt from the request limiter. There is no per-install daily cap: the Tidal app client sends only `X-Access-Token`, not an install identifier. Daily quotas (table `tidal_daily_quotas`, key per day):

- `s:<network>`: one unit per `/search` that reaches Tidal (cache miss and a seat taken), checked before a seat is taken; cache hits are free. Over `NETWORK_SEARCH_DAILY_CAP` (default 2,000): `429 {"error":"network_daily_cap"}`, `Retry-After: 3600`. It never touches the stream keys.
- `n:<network>`: one unit per answered `/stream` (200 or definitive track-level 404), cache hit or not. Over `NETWORK_DAILY_CAP`: `429 {"error":"network_daily_cap"}`, `Retry-After: 3600`.
- `global`: one unit only per `/stream` answered **from Tidal** (cache miss). It protects Tidal, so it gates only cache misses: over `GLOBAL_DAILY_CAP` a miss gets `503 {"error":"global_daily_cap"}`, `Retry-After: 600`, while cached answers keep being served. Cheap cached requests from many networks therefore cannot exhaust it and switch Tidal off.

Upstream failures, busy answers and `no_account` spend no daily quota. Seat fair share: the whole pool makes about 12 upstream calls/minute (four seats, one call per 20 s each), while `REQUEST_RL` lets one network send 40 requests/minute, so the `UPSTREAM_RL` rate-limit binding (6/60 s, keyed like `REQUEST_RL`) is consulted only when a request is about to take a seat: a search or stream cache miss, and the `?aac=only` hires lookup. Over it, search and stream answer `503 {"error":"busy"}` with `Retry-After: 30`; the hires lookup is skipped instead (`hires` omitted). Without the binding the check is skipped. These daily counters reset at midnight UTC and are pruned hourly.

Run `npm run tidal:login` to add a live account or `npm run tidal:login -- --reserve` to add an account held out of rotation. Promote a reserve in D1 with `UPDATE tidal_accounts SET state='live' WHERE id='<account_id>';`; move a live account back with `state='reserve'`. Reauthorization restores that account to the selected state. An operator can inspect pool health without exposing tokens using `SELECT id,state,hour_n,day_n,last_used_at,cooling_until,dead_reason FROM tidal_accounts;`.

## Upstream budget

One `/stream` cache miss makes **one** upstream `playbackinfopostpaywall` call: `countryCode=TIDAL_COUNTRY_CODE` (default `US`) and `audioquality=HI_RES_LOSSLESS` (`HIGH` for `?aac=only`). Tidal answers with the best quality available up to that level; with this client ID CD-only tracks come back `HIGH` (AAC), which is final (`lossless_unavailable`, or AAC under `?aac=1`). There is no quality/country walk and no second-account retry for a track-level answer; a second seat is tried only after a seat-level failure (failed refresh, Tidal 5xx). Any other upstream 4xx on `/stream` (e.g. 400/403) answers `502 {"error":"upstream_error"}` on the one seat. `?aac=only` adds one `/tracks/<id>` metadata call on a hires-cache miss.

Every upstream call counts in the seat's `hour_n`/`day_n`: `takeAccount` counts the first, `bumpSeat` counts the metadata call and a post-refresh retry, so the caps reflect real Tidal load.

Seat pacing: Tidal's 429 is a short-window burst limit (an idle seat was refused on its 5th call in 14 s, then answered again minutes later) with an empty body and no `Retry-After`, so hourly/daily caps do not prevent it. Every upstream call on a seat (search, playback, hires metadata) waits `TIDAL_SEAT_MIN_GAP_SECONDS` (default 20) since that seat's last call (column `last_call_at`, migration 0008); the hires lookup takes a seat of its own rather than following the playback call. A 429 cools the seat `TIDAL_429_COOLDOWN_SECONDS` (default 120), doubling per repeat strike up to `TIDAL_429_COOLDOWN_MAX_SECONDS` (960); a seat whose last cooldown ended over `TIDAL_429_FORGIVE_SECONDS` (1800) ago starts again at 120. `not_available` answers are cached 6 h (not 5 days), and an upstream search refusal is a 502, never a cached empty result.

The 401 rule: a Tidal 401 with `subStatus=4005` is a track refusal (`not_available`). Any other 401 first triggers a token refresh and one retry in `tidalFetch`; if the retry is still a 401, the seat takes a strike (the same escalating cooldown as a 429, without a 429 event row) and the request answers `503 busy`. A 401 never retires a seat. A seat is retired (`state='dead'`, `dead_reason='refresh_invalid'`) only when `auth.tidal.com` rejects the refresh itself with HTTP 400/401 and `invalid_grant` (that seat's grant is gone); `invalid_client` concerns the shared client credentials, so like any other failure (5xx, network error, refresh-lock contention) it only cools the seat 300 s and logs; a refresh that fails any other way (5xx, network error, refresh-lock contention) cools the seat 300 s. Re-link a retired seat with `npm run tidal:login`.

## Deployment

Use Wrangler while authenticated to the Cloudflare account that should own this Worker. Create the database, copy the returned ID to `wrangler.toml`, and apply the migration:

```powershell
npx wrangler d1 create stash-tidal-relay
npx wrangler d1 migrations apply stash-tidal-relay --remote
```

Set a long random `ACCESS_TOKEN`, an unrelated private `ADMIN_TOKEN`, `TIDAL_CLIENT_SECRET`, random private `IP_SALT`, and a random `DASH_SIGNING_KEY` (at least 32 characters) as Wrangler secrets. `DASH_SIGNING_KEY` signs `/dash` links; `ACCESS_TOKEN` ships in every install, so until the key is set the relay falls back to signing with it and anyone could forge those links. Setting it voids links signed earlier, including DASH answers in the response cache (they expire within an hour; `DELETE FROM tidal_response_cache WHERE response_json LIKE '%/dash/td/%'` clears them at once). The Tidal client ID and secret come from the selected [BitChord community implementation](https://github.com/R1Blmmakan/music_addon/blob/main/.env.example), not the TIDAL Developer Portal. Keep these values out of `wrangler.toml` and source control. Apply D1 migrations with `npm run db:migrate`, then deploy and note the `workers.dev` origin, or configure a custom HTTPS hostname and set `PUBLIC_HOST` to that exact origin.

After deploying, set `TIDAL_RELAY_URL` and `TIDAL_ADMIN_TOKEN` in a local shell and run `npm run tidal:login` (append `-- --reserve` to hold an account in reserve). The script prints the verification URL and code, waits while the user approves in Tidal, then polls until authorization completes. It never reads or prints a password or OAuth token. Then verify `/health`, `/search`, and `/stream` with the app access token. Publish `tidal_relay_url` and `tidal_relay_token` using the signed-config publisher only after those checks pass.

## Web FLAC lane (`?web=1`)

`GET /stream/td:<id>?web=1` answers lossless tracks with URLs on the **tidal-edge** service (Deno Deploy; Tidal's CDN
refuses Cloudflare egress and browser Origins): `{ url: <edge>/t/<token>/manifest.mpd | stream.flac, download:
<edge>/t/<token>/file.flac, codec: "flac", manifest: "dash" | "none", edge: true, expiresAt, bitDepth, sampleRate }`.
The token is base64url(JSON) "." base64url(HMAC-SHA256(EDGE_SECRET, body)) and carries the Tidal URLs / MPD itself
(no D1 write). Needs `wrangler secret put EDGE_SECRET` (same value as the edge) and the var `EDGE_BASE`
(e.g. `https://tidal-edge.<org>.deno.net`). Without both, `?web=1` is ignored and the normal answer is returned.

## `?aac=only` and `hires`

`/stream/<id>?aac=only` (the web player) answers Tidal's 320 kbps AAC and adds
`hires: true|false` = whether the track has a Hi-Res lossless master (the only kind
this client can fetch as FLAC). It is read from `GET /tracks/<id>` on a paced seat
of its own (it counts in that seat's caps and the network's fair share, not in any daily
stream quota) and cached in D1 table `tidal_hires_cache` (migration 0004) for 30 days. D1, not the Cache
API: `cache.put` is a no-op on `*.workers.dev`. If the lookup fails, `hires` is omitted.

## Shared response cache

Search results are shared in D1 for five days by country and a SHA-256 digest of the
trimmed, case-folded, whitespace-collapsed query. Definitive per-track
`lossless_unavailable` and `not_available` answers are cached for five days per stream
mode. Successful stream answers are cached only until 60 seconds before the earliest
expiry exposed by the signed playback URL. Requests still pass the relay's caller and
per-network daily quota checks; a D1 cache hit does not consume a Tidal account slot, the
search quota, the seat fair share or the global stream cap.
Apply with `wrangler d1 migrations apply` before deploying.

## Cautious pool ramp

The restart cap is configured to 100 requests/hour per account with a 1,600/day ceiling.
Only raise the hourly cap to 250 after 3–4 hours without 429s. With the relay unpaused,
the first cache miss starts a shared 10-minute soft-open window; 50% of uncached search
and stream requests get `503 busy` with a five-minute retry. Cache hits continue to be
served. After a later paused interval, reset the one-shot window with
`DELETE FROM tidal_soft_open_state WHERE id=1` before reopening.

Every upstream 429 is logged with the account suffix and its hour/day counters, and is
stored in D1 for 30 days in `tidal_upstream_429_events`. Inspect recent events with:

```sql
SELECT substr(account_id, -4) AS account, endpoint, hour_n, day_n,
       datetime(occurred_at, 'unixepoch') AS occurred_at
FROM tidal_upstream_429_events ORDER BY occurred_at DESC;
```
