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
  - `429` a cap: `rate_limited` (install, network or /48 daily cap, or the per-minute limiter) or
    `paced` (a download ahead of the day's pace); both carry `Retry-After`.
  - `503` busy, with `Retry-After`: the global cap is spent, no account is free, or a refusal could not
    be cross-checked because no second account was free (it used to be a 404 the app kept for good).
  - `401` a bad signature or a timestamp outside ±300 s, with `X-Stash-Server-Time: <epoch seconds>`
    so the app can correct a skewed clock and retry.
- `GET /v1/status`: reachability probe.

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
