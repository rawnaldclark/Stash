# Plan B — the lossless relay: server-side contract

**Status:** contract fixed (§1-4), design decisions settled 2026-09-01 (§5), implementation plan written 2026-09-01 (`docs/superpowers/plans/2026-09-01-lossless-relay-plan-b-worker.md`).
**Derived from shipped client code, not proposed.** Every requirement below is
already enforced by `LosslessRelayClient` / `LosslessConfigFetcher` on devices
running Plan A1. A relay that violates one of these does not "degrade" — it gets
cooled, skipped, or ignored, usually silently. Read this before writing a line
of server code.

Sources of truth:
- `data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessRelayClient.kt`
- `data/download/src/main/kotlin/com/stash/data/download/lossless/relay/LosslessConfigFetcher.kt`
- `data/download/src/main/kotlin/com/stash/data/download/lossless/qbdlx/QbdlxFileUrlRouter.kt`

---

## 1. The mint endpoint

```
GET {base}/v1/qobuz/file?track_id=<long>&format_id=<int>
X-Stash-Version: 1
Accept: application/json
```

`base` is whatever the config or the user's custom-endpoint field supplies,
already normalised (no trailing slash). The client appends the path verbatim.

### Success — 200

```json
{
  "url": "https://cdn.example/...",
  "format_id": 7,
  "bit_depth": 24,
  "sample_rate": 96000
}
```

Hard requirements, each one enforced:

| Field | Rule | What happens if you get it wrong |
|---|---|---|
| `url` | **must** start with `https://` | plaintext or missing → base cooled **5 min**, treated as sick or MITM'd |
| `sample_rate` | **Hz, not kHz** — you convert Qobuz's kHz, the client never multiplies | 96 instead of 96000 surfaces as a nonsense quality badge |
| `format_id` | omit it and it decodes to `0`, which reads as **region-locked** downstream | client defensively echoes the requested id, but don't rely on that |
| `bit_depth` | plain int | — |
| `url` (again) | **must be the raw Qobuz CDN URL with its `etsp=<unix-seconds>` query parameter intact** | the streaming resolver derives expiry by parsing `[?&]etsp=(\d+)` out of the URL and **rejects any URL without it** (`QbdlxStreamResolver: no_etsp`). The download path does not parse it, so a relay that proxies, rewrites, or shortens URLs breaks **streaming silently while downloads keep working** — an asymmetric failure that is hard to spot. Never wrap the URL. |

Unknown keys are ignored, so the response may carry extra fields safely.

### The three failure statuses — they are not interchangeable

| Status | Meaning | Client behaviour |
|---|---|---|
| **404** | not streamable / region-locked for this relay's accounts | **`NoMatch` ends the router for this track.** Moves to the next lossless *source*, **not** the next relay base — every base fronts the same catalog. No cooldown; the base is considered healthy. |
| **503** | busy / at capacity | base cooled **60 s**, try next base |
| anything else non-2xx | broken | base cooled **5 min**, try next base |
| unreachable, or 200 headers then a stalled body | sick | base cooled **5 min** |

**The 404-vs-503 distinction is the one to get right.** Returning 404 for
"my accounts are rate-limited right now" permanently gives up on that track for
that play attempt instead of failing over. Returning 503 for a genuine catalog
miss makes the client wait 60s and retry something that will never exist.

**Never return 200 with an error payload.** A 200 whose body has no usable
`https://` url cools the base for 5 minutes — the harshest penalty in the table.

### Timeouts

The client's connect/read/call timeouts are **8 seconds** each. A mint that
routinely takes longer is functionally offline: it cools for 5 minutes on every
attempt. Whatever upstream work minting requires must fit inside 8s or be
pre-warmed.

---

## 2. The status endpoint

```
GET {base}/v1/status
X-Stash-Version: 1
```

Used only by the Settings "Test" button. **Any HTTP reply counts as success —
including 404.** It answers "did the user typo the host", not "is the relay
healthy". The client reads no body and does not touch cooldowns. Serving
something meaningful here is optional; being reachable is not. The Worker answers
`200 {"ok":true}`.

---

## 3. The signed config

Devices ship **no relay hostname**. They fetch two files:

```
GET <LOSSLESS_CONFIG_URL>          -> the JSON below
GET <LOSSLESS_CONFIG_URL>.sig      -> base64 ECDSA P-256 (SHA256withECDSA)
                                      over the exact JSON bytes
```

```json
{
  "v": 1,
  "relays": [
    { "base": "https://relay-a.example", "priority": 1 },
    { "base": "https://relay-b.example", "priority": 2 }
  ],
  "updated_at": 1788220800
}
```

The config host is the tipjar Worker: `LOSSLESS_CONFIG_URL =
https://stash-tipjar.rawnaldclark.workers.dev/lossless.json`, the signature at
`…/lossless.json.sig`, both served byte-for-byte from KV and written only by
`infra/lossless-config/scripts/publish-config.mjs` (moved from `infra/lossless-relay/` 2026-11). Chosen over GitHub raw (the relay
key would sit in public git history) and over the relay serving its own config (its
hostname would then be in the APK).

Relays are sorted by `priority` ascending and tried in that order. The device
verifies the signature against `LOSSLESS_CONFIG_PUBKEY` (X.509, base64) baked in
at build time.

### Publishing rules — these bite

1. **`updated_at` must increase monotonically, forever.** The device keeps a
   rollback floor: a config stamped older than the cached one is **rejected
   permanently**. Re-publishing a known-good older file after a bad push does
   **not** work — it needs a fresh, higher `updated_at`.
2. **Signature is over the exact bytes served.** Any re-encoding, whitespace
   normalisation, or CDN transformation between signing and serving breaks
   verification, and the device silently keeps its cached config.
3. **64 KiB cap.** A larger body is rejected. (This cap exists because an
   unbounded read throws an `Error`, not an `Exception`, which escapes the catch
   and crash-loops the app at cold start. Do not remove it.)
4. **A signed `{"relays": []}` is the kill switch** — the clean way to take all
   relays out of service.
5. Serve from a **CDN-fronted static host**. The shared OkHttp client has no
   disk cache, so ETag/Cache-Control only help at the edge. In practice this is
   one fetch per cold start (process death beats the 6h timer).

Failure is fail-safe by design: bad signature or network failure keeps the
cached copy; no cache means no relays, and lossless falls back to the user's own
account or custom endpoint.

### Optional: the Tidal rung (BitChord)

The client supports an optional Tidal rung after Qobuz and before the lossy
sources. Its signed-config fields point to the operator-managed relay. The
Worker rotates a pool of live accounts under per-account hourly and daily caps;
reserve accounts remain out of rotation until promoted. Keep the shared config
token limited to this relay, and size the pool/caps for the expected install base.

For a future managed pool, the optional fields are:

```json
{
  "tidal_relay_url": "https://tidal.example",
  "tidal_relay_token": "<that server's ACCESS_TOKEN>"
}
```

- `tidal_relay_url` — base of the managed BitChord Lossless Relay
  (`R1Blmmakan/music_addon`). **https only**; anything else is dropped at parse and
  the rung stays off. A path prefix is kept, so BitChord's own
  `https://host/<ACCESS_TOKEN>` form also works (then omit the token key).
- `tidal_relay_token` — optional; sent as `X-Access-Token` on `/search` and
  `/stream`. The `/dash/` and `/audio/` URLs the server returns are HMAC-signed
  and need no token.
- Missing `tidal_relay_url` = shared Tidal rung off. Same signature, cache,
  rollback floor and rotation story as `relay_key`.
- A user's own server (Settings › Audio & Quality › Advanced › Custom Tidal
  server) uses only that user's token and takes precedence over a managed relay.
- The shared access token is delivered to every client that fetches this signed
  config. The relay operator owns the account pool and per-account limits; do not
  treat the config token as a Tidal account credential.

---

## 4. The non-negotiable architectural constraint

**Audio bytes must never transit the relay.** It mints a signed CDN URL and gets
out of the way; the device streams from the CDN directly. This is what keeps
bandwidth costs bounded, keeps the relay off the critical path of playback, and
keeps it from being the thing that gets noticed. The client is already built for
this — it hands `url` straight to the media source factory with no auth header,
so the URL must be self-authenticating and short-lived.

---

## 5. Decisions (settled 2026-09-01)

### 5.1 Catalog: **Qobuz only**

The shipped client route `/v1/qobuz/file` speaks Qobuz track ids and stays as-is.
No translation layer, no second route. Tidal is deliberately out of scope — the
DRM-free DASH FLAC work on the parked branch stays parked.

### 5.2 Hosting: **Cloudflare Worker + D1**, not a VPS

Budget direction was "as minimal as possible", and this is the minimal answer
that is also the *reuse* answer: the Cloudflare account, the
`rawnaldclark.workers.dev` subdomain, a KV namespace, and Worker deploy
automation already exist in `infra/tipjar-worker/`. The relay is a second Worker
beside it, not new infrastructure.

Cost: **$0** on free tier (100k requests/day, D1 free tier). Workers Paid at
$5/mo only if those limits are actually hit.

The usual objection to Workers — no long-lived process — does not apply here.
Qobuz minting is stateless HTTP plus MD5 request signing, and `getFileUrl`
returns a CDN URL directly. Audio bytes never transit the relay (§4), so there
is nothing to keep a socket open for. A VPS would buy a box to patch, a bill,
and an attack surface, in exchange for capability this design does not use.

**All hot-path state lives in D1, not KV.** The rotation query in 5.3 wants
ordering and an atomic update, which is SQL's job — but the decisive reason is
the free-tier write limits: **KV allows ~1,000 writes/day on the free plan; D1
allows ~100,000.** A per-mint counter or a mint cache in KV would exhaust the
whole account's KV write budget by mid-morning at 600 mints/day (and the tipjar
Worker shares that budget). KV stays reserved for what it is good at: rarely
written, often read. Tier numbers are from training data — re-check Cloudflare's
current pricing page before committing to them.

### 5.3 Rotation: **least-recently-used with a per-account hourly cap**

Requirement was "minimize any one being favored." One D1 statement satisfies it:
select the live account with the oldest `last_used_at` whose usage this hour is
under `budget_per_hour`, and stamp it in the same write. No counters to keep in
sync, no coordination primitive, correct under concurrent Worker invocations.

An account that errors goes `cooling` with a `cooling_until`; the next request
simply doesn't select it. v1 draws catalog and playback from one pool — the
`role` column stays in the schema so the split can happen later without a
migration.

### 5.4 Abuse gating: **HMAC over the request, with the key delivered in the signed config**

The threat is real and specific: the relay's hostname reaches every device via a
config any client can fetch, so without gating a third party can point their own
client at it and spend the subscriptions.

The decision that matters is **where the key lives**. Not baked into the APK —
that is the same extractable-secret shape Plan C just spent 1,800 deleted lines
removing, and a baked key cannot be changed without a release. Instead the key
ships **inside the existing ECDSA-signed `lossless.json`**, which devices already
fetch and verify on every cold start.

That reuses a channel that is already built, already signed, and already
tamper-proof, and it buys the property that actually matters: **rotation without
a release.** If a key leaks, publish a new config; the old key is dead within one
cold start, and the `{"relays": []}` kill switch is still there as the bigger
hammer.

Request shape:
`X-Stash-Auth: <hex HMAC-SHA256(key, "<install_id>:<track_id>:<format_id>:<unix_ts>")>`
plus `X-Stash-Ts` and `X-Stash-Install`, with the relay rejecting a timestamp
outside ±5 minutes so a captured header is not replayable forever. The install id
is inside the MAC so a captured header cannot be re-used under a different
install's allowance. Per-IP and global daily request caps sit in front of
everything.

**Per-install daily cap (added 2026-09-01).** Because access is free and ungated
(§6.5), the HMAC key is the ONLY access control, and every user's device holds
it. So the Worker also caps **per install**: the client generates a random
install id once, sends it as `X-Stash-Install`, and the Worker enforces
`install_daily_cap = 100` mints/day in **D1** (not KV — see 5.2 on write limits).
100/day is far beyond normal listening, so no legitimate user meets it. The id is
random and carries no PII — it is a rate-limiting bucket, not identity.

**Honest scope of this cap:** the id is *client-chosen*, so a deliberate attacker
simply rotates it. What the cap actually bounds is naive misuse — a copied
script, a fork that forgot to rate-limit itself, one runaway install. Against a
determined party the real defenses remain the per-IP cap, the global daily cap,
and key rotation. Do not describe this cap as containing a leaked key. A cache hit is
served before the per-install check and does not count against it — it spends no Qobuz
request; the per-IP limiter is what bounds a client replaying popular tracks.

**Per-network caps (added 2026-10-11, after an audit).** The per-IP limiter alone did not
stop one client from taking the whole day: it was keyed on the full address (an IPv6 host
rotating through its /64 got a fresh bucket per address), 40/min from one IPv4 address is
57,600 a day against a global cap of 34,667, and a random 1-12 digit track id (Qobuz
answers 404) spent the global cap like a real mint. So:

- Every per-caller limit counts the caller's **network**: an IPv4 address as is, an IPv6
  address by its /56 (the same unit as the share Worker's Community limits). That covers
  the per-IP limiter, the new daily cap below, and the IP column of the abuse report.
- **Per-network daily cap** (`NETWORK_DAILY_CAP`, a few installs' worth so a household or
  a carrier NAT still fits), counted in D1 under a salted hash of the network and enforced
  next to the per-install cap: over it → **429** + `Retry-After`. A cache hit still costs
  nothing.
- **Qobuz's own 404** (an id it has never heard of) spends neither the global cap nor the
  account's hourly/daily slot, while the network has some of its small daily allowance of
  them left (`NETWORK_FREE_404_DAILY`). Past the allowance a 404 counts like any other
  answer, so the network cap still ends a flood of junk ids. It always counts against the
  install's own cap. Region locks and preview-only answers are real tracks and still count.

Both need the `IP_SALT` secret (the relay key is public, so it can't salt anything); without
it they are off and the relay behaves as before.

**Client half BUILT (PR #465, `9e2f2e3f`, 2026-09-01).** `lossless.json` carries an
optional `relay_key`; `LosslessRelayClient` signs only when one is present, and a
config that omits the key disarms it. Exact wire format the relay must verify:

```
X-Stash-Install: <uuid>
X-Stash-Ts:      <unix seconds>
X-Stash-Auth:    hex( HMAC-SHA256( relay_key, "<install_id>:<track_id>:<format_id>:<unix_ts>" ) )
```

Reject with **401** when the MAC fails or `|now - ts| > 300s`. The client treats
401 like any other non-2xx: the base cools 5 minutes. A device with a badly
skewed clock therefore loses the relay 5 minutes at a time; if that shows up in
the field, the fix is the relay echoing a `Date` header and the client correcting
for skew — deferred until observed.

It still has to **ship in a release** before a live relay requires signing; a
relay published to devices that predate it would 401 every mint.

Honest limit: a determined attacker can extract the key from a config fetch, the
same as any client-side secret. This does not make abuse impossible, it makes it
a treadmill that costs them re-extraction on every rotation and costs us one
publish. Per-install registration (relay issues an id + secret on first contact,
revocable individually) is the documented upgrade if that treadmill ever stops
being enough — the wire format above accommodates it without another redesign.

### 5.5 Quota exhaustion: **503, never 404**

Per §1 this is the distinction that silently breaks playback, so it is stated
flatly:

- **A single account exhausted or erroring** → mark it `cooling`, select the next
  account. The client never learns; this is internal.
- **Every account exhausted, or the global daily cap hit** → **503** with
  `Retry-After`. The client cools that base 60s and moves on, which is the
  intended failover.
- **404 is reserved strictly for a genuine catalog miss or region lock** — the
  track does not exist for these accounts. Nothing about capacity, load, or
  billing may ever answer 404, because 404 ends the router for that track
  entirely rather than failing over.

A global daily cap is what bounds cost. Pick the number when the Qobuz account
count is known; enforce it in the same D1 write as 5.3.

### 5.6 Implementation decisions (2026-09-01, from the Worker plan)

Recorded here so the spec and the shipped Worker cannot drift apart. Plan:
`docs/superpowers/plans/2026-09-01-lossless-relay-plan-b-worker.md`.

| Decision | Choice | Why |
|---|---|---|
| Config host (§3) | The tipjar Worker serves `GET /lossless.json` and `/lossless.json.sig` from `STASH_KV` | Already deployed, hostname already disclosed in README, KV is "rarely written, often read", bytes served exactly as stored. GitHub raw would put the relay key in public git history; the relay serving its own config would put the relay hostname in the APK. |
| Where account tokens live | Worker secret `QOBUZ_ACCOUNTS` (JSON array); D1 `accounts` holds rotation state only, keyed by `label` | Secrets are encrypted at rest and `d1 execute` can never print one; re-seeding a token is `wrangler secret put`, never SQL with a token on a command line. |
| Per-install, per-network and per-IP cap status | **429** with `Retry-After` | The client cools a non-2xx/non-503 base for 5 min (spec §1), the right backoff for a runaway install; 503 would have it retry every 60 s. Global exhaustion stays **503** (§5.5). |
| Caps | `[vars]` in `wrangler.toml`: global 600/day, install 100/day, per account 20/h and 200/day | Tunable by redeploy, not code. |
| Attempts per request | 2 accounts max, 3 s upstream timeout each | Two attempts plus the D1 round trips fit inside the client's 8 s budget (§1). A Worker that overran it would turn the intended 503 (60 s cooldown) into a client-side timeout (5 min cooldown). |
| Upstream classification | 401, 403 `USER_BLOCKED`, or a preview body → account **dead** (operator re-logs in); anything else, Qobuz 400 included → account cools 5 min | Mirrors `QbdlxApiClient` exactly. A 400 is more likely our bug than a dead account and must not kill the pool. |
| `/v1/status` body | `{"ok":true}` | The client reads no body (§2). |
| Non-lossless formats (Data Saver's `format_id=5`) | **404**, no Qobuz call; 400 only for malformed input | The relay is lossless-only and the client's router discards any relay format below 6; 404 carries no cooldown, so the phone falls to JioSaavn AAC 320 as Save Data intends. A 400 would cool the relay 5 min for every Save Data user. |

Status the Worker emits, and what the client does with it:

| Worker | Meaning | Client |
|---|---|---|
| 200 | mint (cache HIT or MISS) | streams it |
| 404 | Qobuz answered 404 for the track id, or returned no URL / a lossy format, for these accounts; or a request for a non-lossless format (Data Saver's 5), answered without a Qobuz call | NoMatch — ends the router for the track; Save Data falls to JioSaavn AAC 320 |
| 503 + Retry-After | global cap hit, or every account dead/cooling/capped, or an internal failure mid-request (a D1 error) | cools the base 60 s |
| 429 + Retry-After | per-install or per-network daily cap, or the per-IP limiter (per network since 2026-10-11, §5.4) | cools the base 5 min |
| 401 | signature missing/wrong, or \|skew\| > 300 s | cools the base 5 min |
| 400 | malformed input only: a non-numeric or absent track_id/format_id, or a wrong X-Stash-Version | cools the base 5 min |
| 500 | RELAY_KEY not configured | cools the base 5 min |

---

## 6. Capacity and budget (settled 2026-09-01)

### 6.1 Cloudflare is not the budget

Workers free tier is 100k requests/day; D1 free tier is 100k row-writes/day. A
mint is one select + one update, so both ceilings land in the same place:
**~100,000 mints/day at $0.** This will not be the binding constraint.

### 6.2 Qobuz is the budget, and the currency is requests-per-account

Four accounts is roughly $50/mo (US Studio pricing — re-check before committing).
The money is the easy part. The ceiling is **how many requests one account can
make before it stops looking like a person.**

The precedent is qbdlx: **19 tokens, all `USER_BLOCKED` within about six days**
once a real user base pointed at them. Qobuz publishes no rate limit; it simply
kills accounts that don't behave like listeners. So the cap is a judgement about
plausibility, not a documented number.

A heavy real subscriber plays 100-200 tracks/day. Budgets are set deliberately
inside that envelope:

```
budget_per_hour   = 20     per account
daily_cap         = 200    per account
global_daily_cap  = 200 x live_accounts
accounts_live     = 3, with 1 held in reserve
```

**4 accounts x 200 = 800 mints/day.** At ~40 mints per active user per day (the
device's StreamUrlCache already absorbs repeats within the hour) that is
**~20 daily active users.** Four accounts is PILOT capacity, not launch capacity.
Sizing it as the main lossless path repeats qbdlx exactly.

### 6.3 The mint cache is not optional

Qobuz `getFileUrl` returns a **self-signed, Range-capable CDN URL with no auth
header** — verified on-device with both arcod and qbdlx, where the URL played
with no Bearer attached. The URLs live ~1h (`expiresInSec=3599` observed).

Therefore a mint is **shareable across users** (verified below). Cache it in **D1**
(not KV — write limits, see 5.2) keyed `(track_id, format_id)`. Serve from cache
only while the URL's own `etsp` has **≥ 15 minutes** left; otherwise treat it as a
miss and re-mint. Keying the serve rule to the URL's real remaining life is safer
than a fixed TTL, and it works because the client reads expiry from `etsp` too
(§1), so a cached URL with 20 minutes left is correctly treated as 20 minutes on
the device. A hundred users playing the same track then cost **one** mint, not a
hundred.

**✅ VERIFIED 2026-09-01 (step zero).** Minted on the Pixel 6 over 5G via the
connected Qobuz account (`streaming-qobuz-std.akamaized.net/file?…&etsp=…&hmac=…`),
then fetched from a PC on a different network (Comcast, IPv6) with curl's default
user agent: **`HTTP 206`, 1,024 bytes, `audio/flac`, first bytes `66 4c 61 43` =
`fLaC`.** A HEAD without a Range answered `200`, `Content-Length: 30346548`,
`Accept-Ranges: bytes`. The URL is bound to neither IP, client, nor session, and
is seekable. The shared mint cache is sound and the ~65 figure stands.

Listening is head-weighted (shared playlists, Daily Discovery, popular albums).
At a 70% hit rate the same 800 mints/day serve ~2,600 plays — **~65 daily
actives instead of ~20**, for no extra cost and roughly thirty lines of Worker.
Long-tail listening erodes the rate, so treat it as a range. It is still the
single biggest lever in this design.

### 6.4 Operational rules

- **Hold a reserve account.** Run 3 of 4 live so a `USER_BLOCKED` event is
  headroom rather than an outage.
- **Stagger the signups** over weeks. Four accounts created the same day for the
  same purpose look like what they are.
- **Don't churn trials.** A trial does carry lossless entitlement (device-verified,
  FLAC 24/96), but it expires at 30 days, and repeatedly re-signing up across
  accounts is itself the flagging pattern. Pay for at least two real subscriptions.

### 6.5 The scaling answer is BYO, not more accounts

A user who connects their own Qobuz account costs nothing and has no ceiling, and
that path is already built and device-verified. The relay's honest job is to keep
the app from being dead on first launch and to serve people who will never
subscribe. Sized as a bridge it works; sized as the main path it dies the way
qbdlx died.

**DECIDED 2026-09-01: access is FREE and ungated.** Funding comes from a
**donation goal tracker** (monochrome's pattern) rather than paid access. This
keeps the free-tool posture intact — a goal tracker asks for support without
selling access, which is a materially different position under the project's own
enforcement analysis than gating a pooled-subscription service behind payment.

The already-built Ko-fi supporter tier ([[project_kofi_supporter_tier]],
`feat/kofi-supporter-tier`) is therefore NOT used as a relay gate. The tracker
itself reuses deployed infrastructure: the tipjar Worker already serves
supporters out of `STASH_KV`, so a goal tracker is an addition to it, not new
infrastructure.

Consequence for §5.4: with no entitlement gate, the HMAC key is the only access
control, which is why the per-install daily cap is part of the design rather
than an optimisation.

---

## 7. What is still open

Nothing blocking. Settled 2026-09-01:

- **Access model:** free and ungated, funded by a donation goal tracker (§6.5).
- **Account count:** 4 signed up, **3 live + 1 reserve** → `global_daily_cap = 600`
  mints/day. If only 3 are obtained: 2 live + 1 reserve → 400.

Remaining is execution of the Worker plan:
`docs/superpowers/plans/2026-09-01-lossless-relay-plan-b-worker.md` (Worker, publish
tooling, first signed config, device proof, README disclosure). PR #465 must be merged
before the release secrets `LOSSLESS_CONFIG_URL`/`LOSSLESS_CONFIG_PUBKEY` exist (plan
Task 10 Step 2): until they do, no released device fetches a config at all, so there is
no mixed-population window as long as that order holds.
