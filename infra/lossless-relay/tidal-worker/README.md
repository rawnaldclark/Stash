# Stash Tidal Relay Worker

Separate Cloudflare Worker implementing the Tidal subset of BitChord's relay contract. It uses D1 for OAuth refresh tokens, one-time device grants, playback request caps, and short-lived DASH state. Audio bytes remain on Tidal's CDN; only the DASH fMP4 initialization segment passes through this Worker so its zero FLAC sample-rate field can be patched for Media3.

## Contract

- `GET /health` → `{ "ok": true, "provider": "tidal" }`
- `GET /search?q=...` with `X-Access-Token` → `{ "tracks": [{ "id": "td:<id>", ... }] }`
- `GET /stream/td:<id>?client=media3` with `X-Access-Token` → progressive FLAC URL or a signed DASH MPD URL, with the BitChord response fields. Progressive answers (FLAC and AAC) add `expiresAt` (epoch seconds, from Tidal's signed URL) when it is readable; DASH answers carry their expiry as `exp` in the signed URL. `?aac` is one of `1`, `only` or absent; any other value is treated as absent.
- `GET /quota` with `X-Access-Token` and the signed install headers (below) → today's daily lossless count.
- Signed `GET /dash/td/<id>.mpd` and `GET /dash/td/<id>/init.mp4` URLs need no header; their HMAC signatures (keyed by the `DASH_SIGNING_KEY` secret, see Deployment) expire after one hour.
- Admin-only `POST /admin/device/start`, then `POST /admin/device/poll` with `{ "user_code": "..." }` starts and completes the Tidal OAuth device flow; `POST /admin/seats/pkce` adds a web seat (see `?web=mse` below). These require `X-Access-Token: <ADMIN_TOKEN>`.

Tidal accounts are linked through the device flow; passwords and OAuth tokens are never printed. The pool rotates least-recently-used live accounts, with per-account caps of 200 upstream calls/hour and 3,200/day (`TIDAL_ACCOUNT_HOURLY_CAP` / `TIDAL_ACCOUNT_DAILY_CAP` in `wrangler.toml`; the code falls back to 250 and 1,600 when they are unset). It applies cooldowns for transient failures and retires an account only when Tidal rejects its refresh token (see the 401 rule below). A lower-than-lossless answer is final (no cross-check on another account), and Tidal's `subStatus=4005` is a track/rights refusal (`not_available`) that never kills an account. When no account is available, search and stream return `503 busy`: `Retry-After: 300` when seats are capped or cooling, `Retry-After: 15` when seats are only waiting out their pacing gap.

Protective limits mirror the Qobuz relay: 34,667 upstream-answered `/stream` requests/day shared across the pool, 2,000 answered streams/day per network, 2,000 uncached searches/day per network, 40 requests/60 seconds per network for provider/admin routes, and a 6/60 seconds per-network fair share of the seats. The network key is the IPv4 address or IPv6 `/56`, hashed with the private `IP_SALT`; raw IP addresses are not written to D1. `IP_SALT` is required for the daily network cap; without it only the shared global cap and request limiter apply. `/health` is exempt from the request limiter. Apart from the shared daily lossless counter below (which needs the signed install headers), there is no per-install cap: older Tidal app clients send only `X-Access-Token`. Daily quotas (table `tidal_daily_quotas`, key per day):

- `s:<network>`: one unit per `/search` that reaches Tidal (cache miss and a seat taken), checked before a seat is taken; cache hits are free. Over `NETWORK_SEARCH_DAILY_CAP` (default 2,000): `429 {"error":"network_daily_cap"}`, `Retry-After: 3600`. It never touches the stream keys.
- `n:<network>`: one unit per answered `/stream` (200 or definitive track-level 404), cache hit or not. Over `NETWORK_DAILY_CAP`: `429 {"error":"network_daily_cap"}`, `Retry-After: 3600`.
- `global`: one unit only per `/stream` answered **from Tidal** (cache miss). It protects Tidal, so it gates only cache misses: over `GLOBAL_DAILY_CAP` a miss gets `503 {"error":"global_daily_cap"}`, `Retry-After: 600`, while cached answers keep being served. Cheap cached requests from many networks therefore cannot exhaust it and switch Tidal off.

Upstream failures, busy answers and `no_account` spend no daily quota. A D1 error reading the stream counters admits the request, and one bumping them after an answer only loses that bump; both are logged (`stream quota read failed` / `stream quota bump failed`) and the answer is still returned, never a 503. Seat fair share: the whole pool makes about 12 upstream calls/minute (four seats, one call per 20 s each), while `REQUEST_RL` lets one network send 40 requests/minute, so the `UPSTREAM_RL` rate-limit binding (6/60 s, keyed like `REQUEST_RL`) is consulted only when a request is about to take a seat: a search or stream cache miss, and the `?aac=only` hires lookup. Over it, search and stream answer `503 {"error":"busy"}` with `Retry-After: 30`; the hires lookup is skipped instead (`hires` omitted). Without the binding the check is skipped. These daily counters reset at midnight UTC and are pruned hourly.

Run `npm run tidal:login` to add a live account or `npm run tidal:login -- --reserve` to add an account held out of rotation. Promote a reserve in D1 with `UPDATE tidal_accounts SET state='live' WHERE id='<account_id>';`; move a live account back with `state='reserve'`. Reauthorization restores that account to the selected state. An operator can inspect pool health without exposing tokens using `SELECT id,state,hour_n,day_n,last_used_at,cooling_until,dead_reason FROM tidal_accounts;`.

## Daily lossless counter (shared with the Qobuz relay)

Design: `docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md`. Every install gets `FREE_LOSSLESS_DAILY`
(default 500) lossless answers per UTC day, **Qobuz and Tidal together**: both relays count in one D1 database,
`stash-quota` (binding `QUOTA_DB`, table `usage(day, subject, n)`, subject `i:<install id>`; schema and migrate command
in `infra/lossless-quota/`, copied for the tests to `test/fixtures/quota_*.sql`: keep them equal). Code:
`src/quota.js` (an identical copy of the Qobuz relay's) and `src/auth.js`.

- **Install headers.** The app sends `X-Stash-Install` (its random install id, `[A-Za-z0-9-]{8,64}`), `X-Stash-Ts`
  (epoch seconds) and `X-Stash-Auth` = `hex(HMAC-SHA256(RELAY_KEY, message))`, the key taken as its UTF-8 string,
  exactly as the Qobuz relay's mint signature. Messages: `/stream/td:<id>` and `/stream/<id>` sign
  `<install>:td:<id>:<ts>`; `/quota` signs `<install>:quota:<ts>`. `ts` must be within ±300 s. Verified with the
  `RELAY_KEY` secret and, during a rotation, `RELAY_KEY_PREV`: the **same values as the Qobuz relay's**.
- **401s.** A genuine signature whose `ts` is outside ±300 s (a skewed device clock) is `401 {"error":"unauthorized"}`
  with `X-Stash-Server-Time: <epoch s>`, so the app can correct its clock and retry (the access-token 401 never
  carries that header). While `REQUIRE_INSTALL` is not `"1"` (default `"0"`), any other failing signature (bad HMAC,
  wrong or stale key, partial or malformed headers) is served like a request with **no** install headers: as before,
  uncounted, and logged as `install_signature_failed` (reason and the install id's last 4 characters only). With
  `REQUIRE_INSTALL="1"` every failing signature, and a request with no install headers, is the 401. `/quota` cannot
  be served uncounted, so there any failing signature is the 401.
  Without the `RELAY_KEY` secret the install headers are ignored and the counter is off (a warning per isolate);
  with `REQUIRE_INSTALL="1"` and no key, `/stream` is a 500 `server_misconfigured`.
- **Counts (one unit):** a `200` `/stream` answer with `codec: "flac"` (progressive, DASH or `?web=1` edge) or
  `codec: "aac"` (Tidal's AAC: `?aac=1` on a CD-only track, `?aac=only`), a relay response-cache hit included.
  **Never:** 404s, errors, busy, searches, `/quota`, `/dash`.
- **At the limit:** `429 {"error":"daily_lossless_cap","used":500,"limit":500,"resetsAt":<epoch s>}` with
  `Retry-After` = seconds to `resetsAt` (the next UTC midnight); no seat is taken. Every mode gets it, `?aac=1` and
  `?aac=only` included: past the limit Tidal answers nothing, FLAC or AAC, until the reset.
- **Headers** on every counted answer, the 429 and `/quota`: `X-Stash-Lossless:
  <used>/<limit>` (`used` after this answer; `<used>/unlimited` for a supporter) and `X-Stash-Lossless-Reset: <epoch
  s>`. JSON answers set no CORS headers, so there is no `Access-Control-Expose-Headers` to extend
  (`LOSSLESS_HEADER_NAMES` in `src/quota.js` is ready if that changes).
- **`GET /quota`** → `200 {"used":n,"limit":500,"resetsAt":T,"supporter":false}` (`limit: null` for a supporter)
  with the headers. Needs `X-Access-Token` and the signed headers (missing ones: 401); behind `REQUEST_RL`; never
  counted. `404 {"error":"quota_off"}` without `QUOTA_DB` or `RELAY_KEY` (an older deploy answers `not_found` 404).
- **Supporters:** `/stream` and `/quota` may carry `X-Stash-Entitlement: <token>`, the token the tip jar issues to a
  Ko-fi supporter: `base64url(UTF-8 JSON {"tier":"supporter","iat":<epoch s>,"tid":"<32 hex>","v":1})` `.`
  `base64url(Ed25519 signature over the ASCII bytes of the first segment)`, both without padding. Verified with
  WebCrypto against the var `ENTITLEMENT_PUBLIC_KEY` (standard base64 of the raw 32-byte public key; public, in
  `wrangler.toml`, the same value as the Qobuz relay's). Only tier `supporter`, `v` 1, a 32-hex `tid` and an `iat` at
  most 300 s ahead are accepted (no expiry); verified tokens are cached per isolate. A missing, malformed, wrongly
  signed or revoked token is never an error: the request just gets the free quota. For a valid token the free limit
  is skipped: headers read `<used>/unlimited` (`used` = the install's own count, which still counts every FLAC and
  AAC answer) and `/quota` answers `{"supporter":true,"limit":null}`. The network, global and fair-share limits still
  apply to supporters.
- **Per-token ceiling:** every counted answer (FLAC or AAC) under a token also counts in `usage` as subject
  `t:<tid>`. At `SUPPORTER_DAILY_CAP` (default 5000) that day the token is treated as absent: the install's free
  limit applies again (headers `<used>/500`, then the 429). This keeps a posted token from being a free pass.
- **Revocation:** table `revoked_tokens(tid, revoked_at, reason)` in `stash-quota` (migration
  `infra/lossless-quota/migrations/0002_revoked_tokens.sql`, copied to `test/fixtures/quota_0002_revoked_tokens.sql`),
  checked in the same D1 read as the install's count. Revoke (the tid is in the token's first segment, base64url
  JSON; takes effect on the next request):

  ```powershell
  npx wrangler d1 execute stash-quota --remote --config infra/lossless-quota/wrangler.toml --command "INSERT INTO revoked_tokens (tid, revoked_at, reason) VALUES ('<32 hex tid>', unixepoch(), 'posted publicly')"
  ```

  Un-revoke: `DELETE FROM revoked_tokens WHERE tid = '<tid>'`. Apply migration 0002 before deploying this relay:
  without the table a supporter's counter read fails and that request is served with the counter off.
- **Migrations dir:** the `QUOTA_DB` binding's `migrations_dir` in `wrangler.toml` points at
  `../../lossless-quota/migrations`, which arrives with the Qobuz counter branch (`feat/lossless-quota-qobuz`). It
  only matters to `wrangler d1 migrations` run with this config (`wrangler deploy` does not read it), and it keeps
  such a command from applying this relay's own `migrations/` to `stash-quota`. Migrate `stash-quota` with
  `--config infra/lossless-quota/wrangler.toml` as above; until that branch is merged, run it from a checkout of it.
- **Safety:** no `QUOTA_DB` binding skips the counter (`QUOTA_DB not bound`, once per isolate); a D1 error on the
  counter skips it for that request. The network, global and fair-share limits are unchanged and still apply. A race
  can serve one or two past the limit. The hourly job prunes `usage` rows older than 7 days.

## Upstream budget

One `/stream` cache miss makes **one** upstream `playbackinfopostpaywall` call: `countryCode=TIDAL_COUNTRY_CODE` (default `US`) and `audioquality=HI_RES_LOSSLESS` (`HIGH` for `?aac=only`). Tidal answers with the best quality available up to that level; with this client ID CD-only tracks come back `HIGH` (AAC), which is final (`lossless_unavailable`, or AAC under `?aac=1`). There is no quality/country walk and no second-account retry for a track-level answer; a second seat is tried only after a seat-level failure (failed refresh, Tidal 5xx). Any other upstream 4xx on `/stream` (e.g. 400/403) answers `502 {"error":"upstream_error"}` on the one seat. `?aac=only` adds one `/tracks/<id>` metadata call on a hires-cache miss.

**`TIDAL_V2_MANIFESTS="1"`** (default `"0"`): every lossless mode (all but `?aac=only`) first asks openapi v2 `GET /v2/trackManifests/<id>?formats=FLAC&formats=FLAC_HIRES&manifestType=MPEG_DASH&uriScheme=DATA&adaptive=false&usage=PLAYBACK` on the same seat (same pacing, counting, 401 refresh and 429 strike as playbackinfo). v2 gives this client's seat tokens CD FLAC, which v1 caps at AAC. A clear FLAC/FLAC_HIRES MPD (inline `data:` URI, or an https link on `*.tidal.com`) is served exactly like a v1 hi-res DASH answer (`/dash/td/<id>.mpd`, or edge URLs under `?web=1`), with bits/rate from the Representation id (`FLAC,44100,16`), and writes `tidal_hires_cache`. A v2 answer with no FLAC, `drmData`, a preview, a 404 or anything unparseable falls back to the v1 call above on the same seat (2 calls at most; a v2 429 is busy with no v1 call). Stream cache keys gain `:v2` while it is on, so old 5-day `lossless_unavailable` entries do not hide v2 FLAC; a v1 `lossless_unavailable` after a *transient* v2 failure (5xx, 403, timeout, bad MPD) is cached 6 h, not 5 days. Each stream on this path logs one `tidal_stream {"track","path":"v2"|"v1","reason","formats","bits","rate","status","codec"|"error"}` line.

Every upstream call counts in the seat's `hour_n`/`day_n`: `takeAccount` counts the first, `bumpSeat` counts the metadata call and a post-refresh retry, so the caps reflect real Tidal load.

Seat pacing: Tidal's 429 is a short-window burst limit (an idle seat was refused on its 5th call in 14 s, then answered again minutes later) with an empty body and no `Retry-After`, so hourly/daily caps do not prevent it. Every upstream call on a seat (search, playback, hires metadata) waits `TIDAL_SEAT_MIN_GAP_SECONDS` (default 20) since that seat's last call (column `last_call_at`, migration 0008); the hires lookup takes a seat of its own rather than following the playback call. A 429 cools the seat `TIDAL_429_COOLDOWN_SECONDS` (default 120), doubling per repeat strike up to `TIDAL_429_COOLDOWN_MAX_SECONDS` (960); a seat whose last cooldown ended over `TIDAL_429_FORGIVE_SECONDS` (1800) ago starts again at 120. `not_available` answers are cached 6 h (not 5 days), and an upstream search refusal is a 502, never a cached empty result.

The 401 rule: a Tidal 401 with `subStatus=4005` is a track refusal (`not_available`). Any other 401 first triggers a token refresh and one retry in `tidalFetch`; if the retry is still a 401, the seat takes a strike (the same escalating cooldown as a 429, without a 429 event row) and the request answers `503 busy`. A 401 never retires a seat. A seat is retired (`state='dead'`, `dead_reason='refresh_invalid'`) only when `auth.tidal.com` rejects the refresh itself with HTTP 400/401 and `invalid_grant` (that seat's grant is gone); `invalid_client` concerns the shared client credentials, so like any other failure (5xx, network error, refresh-lock contention) it only cools the seat 300 s and logs; a refresh that fails any other way (5xx, network error, refresh-lock contention) cools the seat 300 s. Re-link a retired seat with `npm run tidal:login`.

## Deployment

Use Wrangler while authenticated to the Cloudflare account that should own this Worker. Create the database, copy the returned ID to `wrangler.toml`, and apply the migration:

```powershell
npx wrangler d1 create stash-tidal-relay
npx wrangler d1 migrations apply stash-tidal-relay --remote
```

Set a long random `ACCESS_TOKEN`, an unrelated private `ADMIN_TOKEN`, `TIDAL_CLIENT_SECRET`, random private `IP_SALT`, and a random `DASH_SIGNING_KEY` (at least 32 characters) as Wrangler secrets. For the daily lossless counter also set `RELAY_KEY` (and `RELAY_KEY_PREV` during a rotation) to the Qobuz relay's values, and apply the shared `stash-quota` migration from the repo root (`npx wrangler d1 migrations apply stash-quota --remote --config infra/lossless-quota/wrangler.toml`). `DASH_SIGNING_KEY` signs `/dash` links; `ACCESS_TOKEN` ships in every install, so until the key is set the relay falls back to signing with it and anyone could forge those links. Setting it voids links signed earlier, including DASH answers in the response cache (they expire within an hour; `DELETE FROM tidal_response_cache WHERE response_json LIKE '%/dash/td/%'` clears them at once). The Tidal client ID and secret come from the selected [BitChord community implementation](https://github.com/R1Blmmakan/music_addon/blob/main/.env.example), not the TIDAL Developer Portal. Keep these values out of `wrangler.toml` and source control. Apply D1 migrations with `npm run db:migrate`, then deploy and note the `workers.dev` origin, or configure a custom HTTPS hostname and set `PUBLIC_HOST` to that exact origin.

After deploying, set `TIDAL_RELAY_URL` and `TIDAL_ADMIN_TOKEN` in a local shell and run `npm run tidal:login` (append `-- --reserve` to hold an account in reserve). The script prints the verification URL and code, waits while the user approves in Tidal, then polls until authorization completes. It never reads or prints a password or OAuth token. Then verify `/health`, `/search`, and `/stream` with the app access token. Publish `tidal_relay_url` and `tidal_relay_token` using the signed-config publisher only after those checks pass.

## Web FLAC lane (`?web=1`)

`GET /stream/td:<id>?web=1` answers lossless tracks with URLs on the **tidal-edge** service (Deno Deploy; Tidal's CDN
refuses Cloudflare egress and browser Origins): `{ url: <edge>/t/<token>/manifest.mpd | stream.flac, download:
<edge>/t/<token>/file.flac, codec: "flac", manifest: "dash" | "none", edge: true, expiresAt, bitDepth, sampleRate }`.
The token is base64url(JSON) "." base64url(HMAC-SHA256(EDGE_SECRET, body)) and carries the Tidal URLs / MPD itself
(no D1 write). Needs `wrangler secret put EDGE_SECRET` (same value as the edge) and the var `EDGE_BASE`
(e.g. `https://tidal-edge.<org>.deno.net`). Without both, `?web=1` is ignored and the normal answer is returned.

## Web seats and the browser MSE lane (`?web=mse`)

Tidal hands out FLAC only as DASH, and the CDN host depends on the OAuth client behind the token. Device-flow seats
(`TIDAL_CLIENT_ID`) get `sp-ad-fa.audio.tidal.com`, which refuses every browser Origin but Tidal's own. Seats linked
through python-tidal's PKCE client (`TIDAL_PKCE_CLIENT_ID`, "web seats", `tidal_accounts.client = 'pkce'`, migration
0009) get the CloudFront host `sp-ad-cf.audio.tidal.com`, which answers any Origin (`Access-Control-Allow-Origin: *`,
Range OK). So a browser can play their FLAC itself through Media Source Extensions, with no proxy.

`GET /stream/td:<id>?web=mse` (lossless only; with `?aac` it is a 400) is served **only by web seats**, least recently
used first, with the usual gap, caps, 429 strikes and quota accounting. It always asks v2 trackManifests first, then
v1 `HI_RES_LOSSLESS` on the same seat, and answers with the raw MPD:
`{ codec: "flac", container: "fmp4", manifest: "dash", mpd: "<MPD xml>", bitDepth, sampleRate, hires, expiresAt, cdn }`.
`expiresAt` is the earliest CloudFront `Policy` `AWS:EpochTime` (or `Expires` / Tidal `token=`) in the MPD's URLs, and
the answer is cached (key `...:web=mse:v2`) until 60 s before it. Every BaseURL / `initialization` / `media` URL must be
https on a `TIDAL_MSE_CDN_HOSTS` host (default the CloudFront one), or the answer is `404 web_flac_unavailable` and
logged as `tidal_web_mse` with the reason and host (cached 10 min). `404 web_flac_unavailable` is also the answer, with
no Tidal call and no cache, when there is no live web seat at all. Web seats all cooling or capped: `503 busy` as
usual. The player falls back to AAC on any of these. Other modes prefer device seats and use a web seat only when no
device seat is free. Plain `?web=1` (tidal-edge) is unchanged.

Web seats refresh like python-tidal's `token_refresh`: `grant_type`, `refresh_token`, the PKCE `client_id` and
`client_secret` = the `TIDAL_PKCE_CLIENT_SECRET` secret (python-tidal's `client_secret_pkce`), no scope. The device
flow cannot create them; add one per account with tokens from a python-tidal PKCE login:

    curl -X POST "$TIDAL_RELAY_URL/admin/seats/pkce" -H "X-Access-Token: $TIDAL_ADMIN_TOKEN" \
      -H "content-type: application/json" \
      -d '{"access_token":"<token>","refresh_token":"<refresh>","expires_in":<seconds>,"user_id":<tidal uid>}'

It upserts the row keyed by the Tidal user id (`client='pkce'`, `state='live'`, strikes and cooling cleared), so an
account already in the pool as a device seat becomes a web seat. Re-linking it through the device flow turns it back.
Token values are never logged or echoed. Apply migration 0009 **before** deploying this code: seat selection reads
the `client` column.

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

Each account is capped at 200 upstream calls/hour and 3,200/day (`TIDAL_ACCOUNT_HOURLY_CAP` /
`TIDAL_ACCOUNT_DAILY_CAP` in `wrangler.toml`). The 20 s seat gap already holds a seat to at most 180 calls/hour,
so the gap, not the hourly cap, is what paces a busy seat. Raise the caps only after several hours without 429s
(`tidal_upstream_429_events` below). With the relay unpaused,
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
