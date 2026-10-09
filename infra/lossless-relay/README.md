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
