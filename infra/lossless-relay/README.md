# Stash Lossless Relay (Cloudflare Worker)

Mints short-lived Qobuz FLAC URLs for the Stash app from a small pool of
operator-owned Qobuz accounts. The device streams from Qobuz's CDN directly;
**no audio ever transits this Worker.** Contract and design:
[`../../docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md`](../../docs/superpowers/specs/2026-08-31-lossless-relay-plan-b-contract.md).

The pool can be supplied through `QOBUZ_ACCOUNTS` and the optional additive
`QOBUZ_ACCOUNTS_EXTRA` Worker secret. The relay combines both arrays; set either
secret without replacing the other.

Sized as a bridge, not the main path: the durable lossless path is the user's
own Qobuz account (free to us, no ceiling). Four accounts serve roughly 65 daily
actives thanks to the shared mint cache; see spec §6.

## Endpoints

- `GET /v1/qobuz/file?track_id=&format_id=`: signed with `X-Stash-Install`, `X-Stash-Ts`,
  `X-Stash-Auth` (HMAC with `RELAY_KEY`) and `X-Stash-Version: 1`; optional
  `X-Stash-Purpose: stream | download`. Answers:
  - `200` a mint (`X-Stash-Cache: HIT | MISS`).
  - `404` not available: Qobuz doesn't know the id, refuses it, or the format isn't lossless.
  - `429` a cap: `rate_limited` (install, network or /48 daily cap, or the per-minute limiter),
    `paced` (a download ahead of the day's pace), or `daily_lossless_cap` (the shared daily lossless
    counter, below); all carry `Retry-After`.
  - `503` busy, with `Retry-After`: the global cap is spent, no account is free, or a refusal could not
    be cross-checked because no second account was free (it used to be a 404 the app kept for good).
  - `401` a bad signature or a timestamp outside ±300 s, with `X-Stash-Server-Time: <epoch seconds>`
    so the app can correct a skewed clock and retry.
- `GET /v1/quota`: today's daily lossless count for the install (below). Signed like a mint, over
  `<install>:quota:<ts>`; behind the same per-minute limiter; never counted.
- `GET /v1/status`: reachability probe.

## Daily lossless counter (shared with the Tidal relay)

Design: [`../../docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md`](../../docs/superpowers/specs/2026-10-17-lossless-daily-counter-design.md).
Every install gets `FREE_LOSSLESS_DAILY` (default 500) lossless answers per UTC day, **Qobuz and Tidal together**:
both relays count in one D1 database, `stash-quota` (binding `QUOTA_DB`, schema and migrate command in
[`../lossless-quota/`](../lossless-quota/README.md)), table `usage(day, subject, n)`, subject `i:<install id>`.
Code: `src/quota.js` (an identical copy lives in `tidal-worker/src/quota.js`).

- **Counts (one unit):** a `200` mint for a lossless format (6, 7, 27), a mint-cache `HIT` included. **Never:** format
  5 (Data Saver, answered 404), any 404, 401, 429, 503, `/v1/quota`, `/v1/status`.
- **Check:** after the signature and the per-minute limiter, before the cache. At the limit:
  `429 {"error":"daily_lossless_cap","used":500,"limit":500,"resetsAt":<epoch s>}` with `Retry-After` = seconds to
  `resetsAt` (the next UTC midnight), no Qobuz call. The app should then skip the lossless rungs until `resetsAt`.
- **Headers** on every counted `200`, the `daily_lossless_cap` 429 and `/v1/quota`:
  `X-Stash-Lossless: <used>/<limit>` (`used` after this answer; `<used>/unlimited` for a supporter) and
  `X-Stash-Lossless-Reset: <epoch s>`. The relay sets no CORS headers, so there is no expose list to extend.
- **`GET /v1/quota`:** headers `X-Stash-Install`, `X-Stash-Ts`, `X-Stash-Auth` =
  `hex(HMAC-SHA256(RELAY_KEY, "<install>:quota:<ts>"))` (key as its UTF-8 string, ±300 s, like a mint). Answers
  `200 {"used":n,"limit":500,"resetsAt":T,"supporter":false}` (`limit: null` for a supporter) with the headers;
  `401` + `X-Stash-Server-Time` for a bad signature; `404 {"error":"quota_off"}` when the counter is off (also what
  an older relay without the route answers: `not_found`).
- **Supporters:** a request may carry `X-Stash-Entitlement: <token>` (mints and `/v1/quota`), the token the tip jar
  issues to a Ko-fi supporter: `base64url(UTF-8 JSON {"tier":"supporter","iat":<epoch s>,"tid":"<32 hex>","v":1})`
  `.` `base64url(Ed25519 signature over the ASCII bytes of the first segment)`, both without padding. The relay
  verifies it with WebCrypto against the var `ENTITLEMENT_PUBLIC_KEY` (standard base64 of the raw 32-byte public key;
  public, in `wrangler.toml`), accepts only tier `supporter`, `v` 1, a 32-hex `tid` and an `iat` at most 300 s ahead
  (no expiry), and caches verified tokens per isolate. A missing, malformed, wrongly signed or revoked token is never
  an error: the request just gets the free quota. For a valid token the free limit and `INSTALL_DAILY_CAP` are
  skipped: headers read `<used>/unlimited` (`used` = the install's own count, which still counts every answer) and
  `/v1/quota` answers `{"supporter":true,"limit":null}`. The network, /48 and global caps still apply to supporters.
- **Per-token ceiling:** every answer under a token also counts in `usage` as subject `t:<tid>`. At
  `SUPPORTER_DAILY_CAP` (default 5000) that day the token is treated as absent: the install's free 500 and its
  `INSTALL_DAILY_CAP` apply again (headers `<used>/500`). This keeps a posted token from being a free pass for everyone.
- **Revocation:** table `revoked_tokens(tid, revoked_at, reason)` in `stash-quota` (migration
  `0002_revoked_tokens.sql`), checked in the same D1 read as the install's count. Revoke a token (takes effect on
  the next request; the tid is in the token's first segment, base64url JSON):

  ```powershell
  npx wrangler d1 execute stash-quota --remote --config infra/lossless-quota/wrangler.toml --command "INSERT INTO revoked_tokens (tid, revoked_at, reason) VALUES ('<32 hex tid>', unixepoch(), 'posted publicly')"
  ```

  Un-revoke: `DELETE FROM revoked_tokens WHERE tid = '<tid>'`. Apply migration 0002 before deploying this relay:
  without the table a supporter's counter read fails and that request is served with the counter off.
- **Off switch:** no `QUOTA_DB` binding → the counter is skipped (one `QUOTA_DB not bound` warning per isolate). A D1
  error on the counter also skips it for that request: the counter never stops a mint. A race can serve one or two
  past the limit.
- `INSTALL_DAILY_CAP` (`rate_limited`, also counts 404s, but not cache hits) stays as this relay's own per-install cap and
  abuse telemetry (supporters under their ceiling skip it); the network, /48, global and account caps are unchanged and
  still apply to everyone.
- The hourly cron prunes `usage` rows older than 7 days.

## Caps and what each answer costs

The relay key ships in the public APK and installs choose their own id, so the install cap only
restrains honest clients. What stops abuse is the per-network caps (they need the `IP_SALT` secret;
without it they are off and each isolate logs `IP_SALT not set` once), the per-account caps and the
global cap.

- **Network** (`n:<tag>`, `NETWORK_DAILY_CAP`): an IPv4 address, or an IPv6 /56.
- **IPv6 /48** (`w:<tag>`, `NETWORK48_DAILY_CAP`, default 4 × the network cap): every /56 of one /48
  together, since a free tunnel-broker /48 holds 256 of them. Checked and charged with the network key;
  IPv4 callers have none.
- **A Qobuz 404** (an id Qobuz doesn't know) was a real upstream call, so it spends the account's
  hourly/daily slot and the global cap. While the network is inside its daily free-404 allowance
  (`nf:<tag>`, `NETWORK_FREE_404_DAILY`; for IPv6 also its /48's `wf:<tag>`,
  `NETWORK48_FREE_404_DAILY`, default 4×) it spares the network's own cap, because honest clients hit
  dead ids. Past the allowance it counts like any answer.
- **A transient upstream failure** (timeout, 5xx, bad body) cools that account for five minutes and
  costs the caller's network one unit per failed call. Anything refused before a Qobuz call costs
  nothing.
- **Dead accounts** are re-tried by the cron after six hours, except a `USER_BLOCKED` one, which stays
  dead until revived by hand: `UPDATE accounts SET state = 'live', dead_reason = '' WHERE label = '…'`.


## Device proof

2026-09-02, Pixel 6 Pro, debug 0.9.100, Qobuz account disconnected first so the
relay was the only lossless path. Cold start logged
`LosslessConfig: lossless config applied: 1 relay(s)`. A search for a track never
played on that phone, then a tap, logged
`QbdlxStreamResolver: resolved id=1340432425 origin=qbdlx expiresInSec=3597` and
`StreamSourceRegistry: qbdlx served 1340432425 'Weird Fishes / Arpeggi'`, while
`wrangler tail` showed `mint track=34129078 fmt=7 acct=acct-1 install=a7ba139d -> ok`.
Now Playing showed **FLAC 24-bit/44.1 kHz** and the full 5:17 track. The search
results page had already spent one mint on its top result before the tap
(`LosslessUrlPrefetcher` pre-resolves rows): browsing costs relay budget, not
only playing, which matters when sizing the caps.
