# Stash Tidal Relay Worker

Separate Cloudflare Worker implementing the Tidal subset of BitChord's relay contract. It uses D1 for OAuth refresh tokens, one-time device grants, playback request caps, and short-lived DASH state. Audio bytes remain on Tidal's CDN; only the DASH fMP4 initialization segment passes through this Worker so its zero FLAC sample-rate field can be patched for Media3.

## Contract

- `GET /health` → `{ "ok": true, "provider": "tidal" }`
- `GET /search?q=...` with `X-Access-Token` → `{ "tracks": [{ "id": "td:<id>", ... }] }`
- `GET /stream/td:<id>?client=media3` with `X-Access-Token` → progressive FLAC URL or a signed DASH MPD URL, with the BitChord response fields.
- Signed `GET /dash/td/<id>.mpd` and `GET /dash/td/<id>/init.mp4` URLs need no header; their HMAC signatures expire after one hour.
- Admin-only `POST /admin/device/start`, then `POST /admin/device/poll` with `{ "user_code": "..." }` starts and completes the Tidal OAuth device flow. These require `X-Access-Token: <ADMIN_TOKEN>`.

Tidal accounts are linked through the device flow; passwords and OAuth tokens are never printed. The pool rotates least-recently-used live accounts, with per-account hourly/daily caps, cooldowns for transient failures, and dead-account quarantine after an invalid-session 401. A lower-than-lossless response is cross-checked against one other account before the first account is cooled; Tidal's `subStatus=4005` is treated as a track/rights refusal and never kills an account. Defaults mirror Qobuz: 621 requests/hour and 11,000/day per account; `TIDAL_ACCOUNT_HOURLY_CAP` and `TIDAL_ACCOUNT_DAILY_CAP` can override them.

Protective limits mirror the Qobuz relay: 34,667 answered `/stream` requests/day shared across the pool, 2,000/day per network, and 40 requests/60 seconds per network for provider/admin routes. The network key is the IPv4 address or IPv6 `/56`, hashed with the private `IP_SALT`; raw IP addresses are not written to D1. `IP_SALT` is required for the daily network cap; without it only the shared global cap and request limiter apply. `/health` is exempt from the request limiter. There is no per-install daily cap: the Tidal app client sends only `X-Access-Token`, not an install identifier. `/search` spends a per-account hourly/daily slot through account selection and is subject to the request-rate limiter, but does not spend global or network daily stream quota. Each `/stream` spends at most one global/network quota unit for a successful response or definitive track-level 404, even if account rotation queried two accounts; upstream failures and `no_account` do not spend daily quota. These daily counters reset at midnight UTC and are pruned hourly.

Run `npm run tidal:login` to add a live account or `npm run tidal:login -- --reserve` to add an account held out of rotation. Promote a reserve in D1 with `UPDATE tidal_accounts SET state='live' WHERE id='<account_id>';`; move a live account back with `state='reserve'`. Reauthorization restores that account to the selected state. An operator can inspect pool health without exposing tokens using `SELECT id,state,hour_n,day_n,last_used_at,cooling_until,dead_reason FROM tidal_accounts;`.

## Deployment

Use Wrangler while authenticated to the Cloudflare account that should own this Worker. Create the database, copy the returned ID to `wrangler.toml`, and apply the migration:

```powershell
npx wrangler d1 create stash-tidal-relay
npx wrangler d1 migrations apply stash-tidal-relay --remote
```

Set a long random `ACCESS_TOKEN`, an unrelated private `ADMIN_TOKEN`, `TIDAL_CLIENT_SECRET`, and random private `IP_SALT` as Wrangler secrets. The Tidal client ID and secret come from the selected [BitChord community implementation](https://github.com/R1Blmmakan/music_addon/blob/main/.env.example), not the TIDAL Developer Portal. Keep these values out of `wrangler.toml` and source control. Apply D1 migrations with `npm run db:migrate`, then deploy and note the `workers.dev` origin, or configure a custom HTTPS hostname and set `PUBLIC_HOST` to that exact origin.

After deploying, set `TIDAL_RELAY_URL` and `TIDAL_ADMIN_TOKEN` in a local shell and run `npm run tidal:login` (append `-- --reserve` to hold an account in reserve). The script prints the verification URL and code, waits while the user approves in Tidal, then polls until authorization completes. It never reads or prints a password or OAuth token. Then verify `/health`, `/search`, and `/stream` with the app access token. Publish `tidal_relay_url` and `tidal_relay_token` using the signed-config publisher only after those checks pass.
