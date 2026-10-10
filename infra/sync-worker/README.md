# stash-sync

Links Stash for Android with Stash on the web: QR pairing, the mirror log and snapshot, the handoff slots (`now`, `queue`), the shared mirror config and one-off sends. Spec: `docs/superpowers/specs/2026-10-10-link-sync-handoff-design.md` (§5 crypto and pairing, §6 this Worker). Everything a device stores here is end-to-end encrypted; the Worker sees sizes, times and random device ids, and keeps no IP addresses.

- Host: `https://sync.stashfm.app` only (custom domain on the stashfm.app zone, main account; `workers_dev = false`).
- Callers: the phone directly; the browser **only** through the player Worker (`play.stashfm.app/api/sync/*`, `player/worker/sync.ts`), which adds `X-Stash-Player-Key` (secret `PLAYER_KEY`), `X-Stash-Client-IP` and `X-Stash-Session` (a hash of the signed-in session). Routes marked *player* refuse anything else, so a pairing code can only be opened from a signed-in player session.
- Durable Objects (SQLite, migration `v1`): `SyncSpace` (`idFromName(spaceId)`, binding `SPACES`) and `PairSlot` (`idFromName(pairId)`, binding `PAIRS`). Both are thin shells: the logic is pure, in `src/space.js` and `src/pair.js`, with an injectable clock. The Worker calls them through their `fetch` handler (`rpc()` in `src/http.js`), not native RPC, so the classes stay plain and `node --test` can load them.
- Every answer is `Cache-Control: no-store`, JSON, with `X-Stash-Server-Time` (ms). Every id is in the path; a request with a query string is refused (the zone's cache ignores query strings). No Cache API anywhere.
- Errors: `{ "error": { "code", "message" } }`. Codes: `bad_request` 400 · `unauthorized` 401 (no or malformed device header) · `revoked` 401 (not a member any more: delete local sync state) · `forbidden` 403 · `gone` 404 (no such space) · `not_found` 404 · `no_key` 404 · `expired` 410 · `used` / `pending` / `exists` / `member` / `full` / `epoch` / `compact` / `stale` / `snapshot` / `inbox_full` 409 · `changed` 412 · `too_large` / `space_full` 413 · `rate_limited` / `daily_limit` 429 (with `Retry-After`) · `unavailable` 503.

## Wire shapes (what the server checks)

| Thing | Shape |
|---|---|
| Device id, send id | 8–64 base64url characters (`d_8f3k2m9q…`) |
| Space id | 22–64 base64url characters, minted by the phone (128 random bits) |
| Pair id | 22 base64url characters, minted by the server |
| Device token | 32 random bytes, base64url (43 characters); sent as `Authorization: Stash-Device <deviceId>:<token>` |
| `tokenHash` | lowercase hex SHA-256 of the token's ASCII; the only thing stored; compared in constant time |
| `pub`, `phonePub` | P-256 public key, uncompressed (65 bytes, 0x04 first), base64url (87 characters) |
| Box (`labelCt`, pairing `ct`, key envelope) | `{ e?, n, c }`: `n` 12-byte nonce (16 chars), `c` ciphertext+tag (≥ 22 chars), base64url |
| Envelope (`env`) | a box whose `e` must equal the space's current epoch (else `409 epoch`) |

Unknown fields are dropped; every stored value is rebuilt from the fields above.

## Routes

`*player*` = only with a valid `X-Stash-Player-Key`. *member* = `Authorization: Stash-Device …` of a device in the space.

| Method and path | Who | Body → answer |
|---|---|---|
| `POST /v1/pair` | player | `{ device: { id, tokenHash, pub, labelCt } }` → 201 `{ pairId, expiresAt }` (3 minutes) |
| `GET /v1/pair/{pairId}` | player + browser's token | long-poll ≤ 25 s: 200 `{ phonePub, ct }`, 204 pending, 410 `expired` |
| `GET /v1/pair/{pairId}/label` | phone | 200 `{ labelCt, expiresAt }`; 409 `used`; 410 `expired` |
| `POST /v1/pair/{pairId}/answer` | phone | `{ phonePub, ct, device: { id, tokenHash, pub, labelCt } }` → 201; second answer 409 `used` |
| `POST /v1/pair/{pairId}/reply` | player + browser's token | `{ ct }` → 201 (the browser holds the space, §5.1 step 4) |
| `GET /v1/pair/{pairId}/reply` | phone's token | long-poll ≤ 25 s: 200 `{ ct }`, 204 pending |
| `POST /v1/spaces` | the phone that answered | `{ pairId, spaceId }` → 201 `{ spaceId, epoch: 1 }`; burns the code; both devices come from the slot |
| `GET /v1/spaces/{sid}` | member | `{ spaceId, me, epoch, rotationDue, head, snapshot: { uptoSeq, parts, epoch } \| null, devices: [{ id, type, pub, labelCt, addedAt, lastSeenAt }], serverTime }` |
| `DELETE /v1/spaces/{sid}` | member | unlink everything → 204 (all storage and the alarm deleted) |
| `POST /v1/spaces/{sid}/devices` | member (sponsor) | `{ pairId }` → 201 `{ device, type }`; adds the *other* device of that code (the browser if the caller answered it, the phone if the caller is its browser); 1 phone + 4 browsers, checked before the code is burned |
| `PUT /v1/spaces/{sid}/devices/me/label` | member | `{ labelCt }` → 204 |
| `DELETE /v1/spaces/{sid}/devices/{did\|me}` | member | → 204; cut off at once (token row, envelopes, slots, sends); `rotationDue` set; last device out deletes the space |
| `GET /v1/spaces/{sid}/key/{epoch}` | member | `{ epoch, ct }` the caller's key envelope; 404 `no_key` |
| `POST /v1/spaces/{sid}/rotate` | member | `{ epoch: current+1, envelopes: { deviceId: box } (every device, no other), config?: env, labels?: { deviceId: box }, snapshot?: { uptoSeq: head, parts: [env] } }` → `{ epoch }` |
| `GET /v1/spaces/{sid}/log/after/{seq}` | member | `{ entries: [{ seq, device, serverAt, env }], head, more }` (≤ 200 entries or 4 MiB); 409 `snapshot` when `seq` is older than the snapshot |
| `POST /v1/spaces/{sid}/log` | member | `{ env }` → 201 `{ seq, serverAt }`; 409 `compact` past 2,000 batches |
| `PUT /v1/spaces/{sid}/snapshot/{uptoSeq}/{part}/{count}` | member | `{ env }` → `{ complete }`; the last part swaps snapshots and deletes log rows ≤ `uptoSeq` |
| `GET /v1/spaces/{sid}/snapshot/{part}` | member | `{ uptoSeq, part, count, epoch, env }` |
| `GET /v1/spaces/{sid}/slots/config` | member | `{ device, serverAt, env }`, 204 when none |
| `PUT /v1/spaces/{sid}/slots/config` | member | `{ env }` + `If-Match: <serverAt read>` (none or `0` = there was none) → `{ serverAt }`; 412 `changed` with `X-Stash-Config-At` |
| `GET /v1/spaces/{sid}/slots/now` | member | `{ slots: [{ device, serverAt, env }] (newest first), serverTime }` |
| `PUT /v1/spaces/{sid}/slots/now` · `…/slots/queue` | member | `{ env }` → `{ serverAt }` (the caller's own slot; `serverAt` strictly increases) |
| `GET /v1/spaces/{sid}/slots/queue/{did}` | member | `{ device, serverAt, env }` |
| `PUT /v1/spaces/{sid}/inbox/{to}/{sendId}/{part}/{count}` | member | `{ env }` → `{ complete }` |
| `GET /v1/spaces/{sid}/inbox` | member | `{ sends: [{ sendId, from, count, bytes, serverAt }] }` (complete sends to the caller) |
| `GET /v1/spaces/{sid}/inbox/{sendId}/{part}` | receiver | `{ sendId, part, count, from, serverAt, env }` |
| `DELETE /v1/spaces/{sid}/inbox/{sendId}` | receiver or sender | → 204 |
| `GET /v1/spaces/{sid}/ws` | — | reserved for live control (§6.7): 404 |

## Limits (spec §6.4)

| Limit | Value |
|---|---|
| Devices per space | 1 phone + 4 browsers |
| Request body | 1 MiB (read as a stream and cut off at the cap) |
| `now` envelope · config · label · pairing message · key envelope | 16 KiB · 64 KiB · 1 KiB · 4 KiB · 1 KiB of ciphertext |
| Snapshot / one send | 16 parts × 1 MiB |
| Space total | 32 MiB (log, snapshot, slots, sends; a snapshot being uploaded doesn't count) → `413 space_full` |
| Log | 2,000 batches → `409 compact` (clients compact at 500 or 4 MiB) |
| Per space per UTC day | 3,000 writes (counted in `meta`), 20,000 reads (counted in memory: a flood keeps the object awake) |
| Sends waiting per device | 8 |
| `PAIR_RL` (2030) | the phone's label/answer/reply calls: 10/min per IP |
| `PAIR_OPEN_RL` (2031) | opening a code: 3/min per player session (a binding's period is at most 60 s, so not "6 per 10 min") |
| `API_RL` (2032) | every space call and the browser's pairing calls: 240/min per IP (IPv6 by /64; the browser's IP as the player forwards it) |

The ratelimit `namespace_id`s are unique in the account (1001 relay, 2001–2009 share, 2020–2021 mailer).

## Retention (spec §6.5)

`src/space.js`: `DEVICE_IDLE_DAYS = 90`, `SLOT_KEEP_DAYS = 7`, `SEND_KEEP_DAYS = 7`. A daily alarm per space removes devices unseen for 90 days (setting `rotationDue`), deletes the space when no device is left, drops `now`/`queue` slots and sends older than 7 days, and unfinished snapshot uploads older than a day. Reads already hide expired slots and sends, and a device past 90 days is refused (and removed) the moment it calls, even before the alarm. Pairing slots delete themselves 2 minutes after their 3-minute life. `lastSeenAt` is written at most hourly.

Rotation (§5.2): the old key envelopes, `now`/`queue` slots and sends go at once; the config must come re-encrypted in the same call. The log and snapshot under the old key stay until a snapshot under the new key is complete (inline in `rotate` when it fits in one request, else uploaded right after with the usual snapshot route); remaining devices still hold the old key, a removed one has no token.

## Tests and local runs

```bash
cd infra/sync-worker
npm test                                     # node --test: pure logic on node:sqlite, the Durable Objects, the HTTP edge
npx wrangler dev --port 8795 --var PLAYER_KEY:dev-key
PLAYER_KEY=dev-key node scripts/smoke.mjs    # links a fake phone and browser, uses every kind of route, unlinks
```

## Deploy (owner)

Order: this Worker first (the player's service binding needs it), then its secret, then the player. Nothing here changes stashfm.app's other routes.

1. Check that the stashfm.app zone has **no** DNS record named `sync` (a custom domain can't take an existing record). Keep Bot Fight Mode, "I'm Under Attack" and any WAF challenge off for `sync.stashfm.app`: the phone can't solve a challenge.
2. Deploy. `custom_domain = true` makes wrangler create the `sync.stashfm.app` DNS record and certificate; the first deploy applies the `v1` migration (`new_sqlite_classes = ["SyncSpace", "PairSlot"]`; migrations are one-way, never rename or delete these classes without a new tag) and creates the three ratelimit bindings.

   ```powershell
   cd infra/sync-worker
   npm install
   npm test
   npx wrangler deploy
   ```
   Until step 3, the player's routes answer 503 (closed, not open).
3. The player key: one random value, already generated into `%USERPROFILE%\.stash\secrets\stash-sync-player-key.txt`. Put it on **both** Workers, never print it:

   ```powershell
   Get-Content -Raw $HOME\.stash\secrets\stash-sync-player-key.txt | npx wrangler secret put PLAYER_KEY
   cd ..\..\..\stash-web\player
   Get-Content -Raw $HOME\.stash\secrets\stash-sync-player-key.txt | npx wrangler secret put SYNC_PLAYER_KEY --env production
   ```
   (bash: `npx wrangler secret put PLAYER_KEY < ~/.stash/secrets/stash-sync-player-key.txt`.) To rotate it later: write a new value into the file and run both commands again; browsers in the middle of pairing just retry.
4. Deploy the player (`npm run deploy` in `stash-web/player`). Its production env declares `services: [{ binding: "SYNC", service: "stash-sync" }]`, which only resolves once `stash-sync` exists in the same account. If the player ever moves to its own account, drop the binding and set the player var `SYNC_API_URL=https://sync.stashfm.app` instead (same header, public fetch).
5. Check:
   - `curl -si https://sync.stashfm.app/v1/spaces/AAAAAAAAAAAAAAAAAAAAAA` → 401 `unauthorized`, `cache-control: no-store`.
   - `curl -si -X POST https://sync.stashfm.app/v1/pair -H 'content-type: application/json' -d '{}'` → 403 (no player key).
   - `$env:PLAYER_KEY = Get-Content -Raw $HOME\.stash\secrets\stash-sync-player-key.txt; node scripts/smoke.mjs https://sync.stashfm.app` → "all checks passed" (it deletes the space it made).
   - `curl -s https://<player>/api/sync/pair` without a session → 401/302 from the player's gate.

## Notes against the spec (2026-10-10)

- `POST /v1/spaces` takes `{ pairId, spaceId }`: the **phone mints the space id** (it goes into the pairing answer before the server could return one, and it is the HKDF salt and part of every AAD); the two device records come from the slot, never from the client.
- The pairing answer carries the phone's own `device` record (`id`, `tokenHash`, `pub`, `labelCt`): when the browser is the sponsor (§5.1 step 4) the server can only add the phone with its token hash. `GET …/reply` needs the phone's token and long-polls like the browser's poll.
- `POST …/rotate` can't always carry the snapshot (a request is at most 1 MiB, a snapshot up to 16 MiB): it is inline when it fits, otherwise uploaded right after; see Retention. `rotate` also re-encrypts the config (required when one exists) and may re-encrypt labels.
- Labels set at pairing are encrypted under the pairing secret; each device should `PUT devices/me/label` under the space key after joining (and the rotator may re-encrypt them in `rotate`).
- Pairing codes per session: 3/min instead of 6 per 10 min (binding periods are 10 or 60 s); the session key comes from the player as `X-Stash-Session`.
- Blobs are stored as TEXT (the envelope's JSON), the same bytes; `inbox` also records `fromDevice` and the epoch.
