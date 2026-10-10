# stash-sync

Links Stash for Android with Stash on the web: QR pairing, the mirror log and snapshot, the handoff slots (`now`, `queue`), the shared mirror config and one-off sends. Spec: `docs/superpowers/specs/2026-10-10-link-sync-handoff-design.md` (§5 crypto and pairing, §6 this Worker). Everything a device stores here is end-to-end encrypted; the Worker sees sizes, times and random device ids, and keeps no IP addresses.

- Host: `https://sync.stashfm.app` only (custom domain on the stashfm.app zone, main account; `workers_dev = false`).
- Callers: the phone directly; the browser **only** through the player Worker (`play.stashfm.app/api/sync/*`, `player/worker/sync.ts`), which adds `X-Stash-Player-Key` (secret `PLAYER_KEY`), `X-Stash-Client-IP` and `X-Stash-Session` (a hash of the signed-in session). Routes marked *player* refuse anything else, so a pairing code can only be opened from a signed-in player session.
- Durable Objects (SQLite, migration `v1`): `SyncSpace` (`idFromName(spaceId)`, binding `SPACES`), `PairSlot` (`idFromName(pairId)`, binding `PAIRS`) and `Quota` (`idFromName("s:" + session hash)` or `"ip:" + address`, binding `QUOTAS`: what a ratelimits binding can't count). They are thin shells: the logic is pure, in `src/space.js`, `src/pair.js` and `src/quota.js`, with an injectable clock. Token hashes are compared with `crypto.subtle.timingSafeEqual`; pair ids are 16 bytes from `crypto.getRandomValues` (the Worker mints no other ids). The Worker calls them through their `fetch` handler (`rpc()` in `src/http.js`), not native RPC, so the classes stay plain and `node --test` can load them.
- Every answer is `Cache-Control: no-store`, JSON, with `X-Stash-Server-Time` (ms). Every id is in the path; a request with a query string is refused (the zone's cache ignores query strings). No Cache API anywhere.
- Errors: `{ "error": { "code", "message" } }`. Codes: `bad_request` 400 · `unauthorized` 401 (no or malformed device header) · `revoked` 401 (not in this space, whether or not the space exists: delete local sync state) · `forbidden` 403 (also a browser's token without the player) · `not_found` 404 · `no_key` 404 · `expired` 410 · `used` / `pending` / `exists` / `member` / `full` / `epoch` / `devices_changed` / `rotation_due` / `compact` / `stale` / `snapshot` / `inbox_full` 409 · `changed` 412 · `too_large` / `space_full` 413 · `rate_limited` / `daily_limit` 429 (with `Retry-After`) · `unavailable` 503.

## Wire shapes (what the server checks)

| Thing | Shape |
|---|---|
| Device id, send id | 8–64 base64url characters (`d_8f3k2m9q…`) |
| Space id | `s_` + 16 random bytes in strict base64url, minted by the phone (checked exactly at creation; routes accept 22–64 characters) |
| Pair id | 22 base64url characters, minted by the server |
| Device token | 32 random bytes, base64url (43 characters); sent as `Authorization: Stash-Device <deviceId>:<token>` |
| `tokenHash` | base64url(SHA-256(the token's 32 bytes)), 43 characters (sync-v1); the only thing stored; compared in constant time |
| `pub`, `phonePub` | P-256 public key, uncompressed (65 bytes, 0x04 first), base64url (87 characters) |
| Box (`labelCt`, pairing `ct`) | sync-v1 envelope `{ e, n, c }`: epoch (0 for pairing messages), 12-byte nonce (16 chars), ciphertext+tag (≥ 22 chars), base64url |
| Key envelope (`rotate`) | `{ e, n, c, p }`: `e` = the new epoch, `p` the sender's ephemeral P-256 key |
| Envelope (`env`) | `{ e, n, c }` whose `e` must equal the space's current epoch (else `409 epoch`) |

Unknown fields are dropped; every stored value is rebuilt from the fields above. The formats are `player/docs/sync-v1.md`'s (crypto vectors in `player/src/lib/sync/fixtures/crypto-vectors.json`; the token-hash vector is tested here).

## Routes

`*player*` = only with a valid `X-Stash-Player-Key`. *member* = `Authorization: Stash-Device …` of a device in the space.

| Method and path | Who | Body → answer |
|---|---|---|
| `POST /v1/pair` | player | `{ device: { id, tokenHash, pub, labelCt } }` → 201 `{ pairId, expiresAt }` (3 minutes) |
| `GET /v1/pair/{pairId}` | player + browser's token | long-poll ≤ 25 s: 200 `{ phonePub, ct }`, 204 pending, 410 `expired` |
| `GET /v1/pair/{pairId}/label` | phone | 200 `{ labelCt, expiresAt, browser: { id, pub } }`; 409 `used`; 410 `expired` |
| `POST /v1/pair/{pairId}/answer` | phone | `{ phonePub, ct, device: { id, tokenHash, pub, labelCt } }` → 201; second answer 409 `used` |
| `POST /v1/pair/{pairId}/reply` | player + browser's token | `{ ct }` → 201 (the browser holds the space, §5.1 step 4; sync-v1 sends it after the browser added the phone) |
| `GET /v1/pair/{pairId}/reply` | phone's token | long-poll ≤ 25 s: 200 `{ ct }`, 204 pending |
| `POST /v1/spaces` | the phone that answered | `{ pairId, spaceId, labels: { <phoneId>: labelCt, <browserId>: labelCt } }` (exactly those two, sealed under the new data key at epoch 1; they replace the pairing-time labels) → 201 `{ spaceId, epoch: 1 }`; both devices come from the slot; the code is burned only once the space exists (`409 exists`, `429` or an error give it back) |
| `GET /v1/spaces/{sid}` | member | `{ spaceId, me, epoch, rotationDue, compactDue, head, snapshot: { uptoSeq, parts, epoch } \| null, devices: [{ id, type, pub, labelCt, addedAt, lastSeenAt }], serverTime }` |
| `DELETE /v1/spaces/{sid}` | member | unlink everything → 204 (all storage and the alarm deleted) |
| `POST /v1/spaces/{sid}/devices` | member (sponsor) | `{ pairId, epoch, envelope?, labelCt }` (`labelCt`: the newcomer's label under the current data key, replacing its pairing-time one) → 201 `{ device, type, epoch }`; adds the *other* device of that code (the browser if the caller answered it, the phone if the caller is its browser). `epoch` = the key epoch the newcomer was given; if the space rotated since, `envelope` (its key envelope for the current epoch) is required, else `409 epoch` `{ error, epoch }` before the code is used. A rotation during the join adds the device and sets `rotationDue`. 1 phone + 4 browsers, checked before the code is burned |
| `PUT /v1/spaces/{sid}/devices/me/label` | member | `{ labelCt }` → 204 |
| `DELETE /v1/spaces/{sid}/devices/{did\|me}` | member | → 204; cut off at once (token row, envelopes, slots, sends); `rotationDue` set; last device out deletes the space |
| `GET /v1/spaces/{sid}/key/{epoch}` | member | `{ epoch, ct }` the caller's key envelope; 404 `no_key`. Envelopes are kept for every epoch the device hasn't fetched (at most the last 32), so a device several rotations behind walks them in order; fetching one drops that device's older ones, except epochs that still seal live data (an old-key log or snapshot not yet compacted) |
| `POST /v1/spaces/{sid}/rotate` | member | `{ epoch: current+1, envelopes: { deviceId: key envelope } (every device, no other), config?: env, labels?: { deviceId: box }, snapshot?: { uptoSeq: head, parts: [env] } }` → `{ epoch }`; another device set → `409 devices_changed` `{ error, epoch, devices: [{ id, type, pub }] }` (retry); without the snapshot inline, `compactDue` until one lands |
| `GET /v1/spaces/{sid}/log/after/{seq}` | member | `{ entries: [{ seq, device, serverAt, env }], head, more }` (≤ 200 entries or 4 MiB); 409 `snapshot` when `seq` is older than the snapshot |
| `POST /v1/spaces/{sid}/log` | member | `{ env }` → 201 `{ seq, serverAt }`; 409 `compact` past 2,000 batches or while `compactDue` |
| `PUT /v1/spaces/{sid}/snapshot/{uptoSeq}/{part}/{count}` | member | `{ env }` → `{ complete }`; the last part swaps snapshots, deletes log rows ≤ `uptoSeq` and clears `compactDue` (while it is set, `uptoSeq` must be the head) |
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
| Per device per UTC day | 3,000 writes (counted on the device's row), 20,000 reads (counted in memory: a flood brake, not an exact quota, since it starts over when the object is evicted). Per device, so one device can't spend the others' budget. **Never refused**: `GET` the space, `GET key`, removing a device, unlinking everything and rotating (they count, and have `SAFE_RL` / `OPEN_RL` instead), so a flooding device can always be seen and removed |
| Sends waiting per device | 8 |
| `PAIR_RL` (2030) | the phone's label/answer/reply calls: 10/min per IP |
| `SAFE_RL` (2031) | removing a device, unlinking everything, rotating: 10/min per presented token |
| `OPEN_RL` (2033) | opening the space and reading a key envelope: 60/min per presented token |

`SAFE_RL` and `OPEN_RL` are keyed on the SHA-256 of the token in the request, never on the device id it claims: ids are known to members, to removed devices and (the browser's) to anyone who saw the QR, so an id key would let a forger fill a real device's bucket. A forged token fills a bucket of its own and is then refused as revoked. Every other key is either the IP (`PAIR_RL`, `API_RL`, the per-IP space quota; the forwarded IP only behind a valid player key) or the player session (only behind a valid player key, hashed by the player from its verified sign-in cookie; the per-session space quota uses the session stored in the slot by that same call).
| `Quota` objects | pairing codes: 6 per player session per 10 minutes; new spaces: 10 per player session and 20 per phone IP per day (a binding's period is at most 60 s, so these live in a small Durable Object per session or IP, deleted a day after its last use) |
| `API_RL` (2032) | every space call and the browser's pairing calls: 240/min per IP (IPv6 by /64; the browser's IP as the player forwards it) |

The ratelimit `namespace_id`s are unique in the account (1001 relay, 2001–2009 share, 2020–2021 mailer).

Accepted limits: `PAIR_RL` and `API_RL` key phones by IP, so phones behind one carrier NAT share a bucket (fine at launch volume; the space routes could add the device id later). The read cap is per awake period, as above. The tests' storage is synchronous: they prove the rules (one answer, one claim, caps re-checked after the cross-object await), not the runtime's input gates.

## Retention (spec §6.5)

`src/space.js`: `DEVICE_IDLE_DAYS = 90`, `SLOT_KEEP_DAYS = 7`, `SEND_KEEP_DAYS = 7`. A daily alarm per space removes devices unseen for 90 days (setting `rotationDue`), deletes the space when no device is left, drops `now`/`queue` slots and sends older than 7 days, and unfinished snapshot uploads older than a day. Reads already hide expired slots and sends, and a device past 90 days is refused (and removed) the moment it calls, even before the alarm. Pairing slots delete themselves 2 minutes after their 3-minute life. `lastSeenAt` is written at most hourly.

While `rotationDue` is set (a device was removed), writes sealed under the current key (`log`, `snapshot`, `slots/*`, `inbox`, `devices/me/label`) get `409 rotation_due`; reads, removals, unlink and `rotate` still work.

Rotation (§5.2): the `now`/`queue` slots and sends go at once (key envelopes of earlier epochs stay until fetched, as above); the config must come re-encrypted in the same call. The log and snapshot under the old key are replaced by a snapshot under the new key: inline in `rotate` when it fits in one request, otherwise the space is `compactDue` and refuses new batches (`409 compact`) until any device uploads a whole-log snapshot under the new key in parts, which deletes the old-key data. Meanwhile remaining devices still hold the old key; a removed one has no token. A space nobody opens again is deleted after 90 days.

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
2. Deploy. `custom_domain = true` makes wrangler create the `sync.stashfm.app` DNS record and certificate; the first deploy applies the `v1` migration (`new_sqlite_classes = ["SyncSpace", "PairSlot", "Quota"]`; stash-sync has never been deployed, so v1 lists all three; after this deploy, migrations are one-way: never edit v1, and add or rename classes only under a new tag) and creates the four ratelimit bindings.

   ```powershell
   cd infra/sync-worker
   npm install
   npm test
   npx wrangler deploy
   ```
   Until step 3, the player's routes answer 503 (closed, not open).
3. The player key: one random value, already generated into `%USERPROFILE%\.stash\secrets\stash-sync-player-key.txt`. Put it on **both** Workers, never print it:

   ```powershell
   (Get-Content -Raw $HOME\.stash\secrets\stash-sync-player-key.txt).Trim() | npx wrangler secret put PLAYER_KEY
   cd ..\..\..\stash-web\player
   (Get-Content -Raw $HOME\.stash\secrets\stash-sync-player-key.txt).Trim() | npx wrangler secret put SYNC_PLAYER_KEY --env production
   ```
   (bash: `npx wrangler secret put PLAYER_KEY < ~/.stash/secrets/stash-sync-player-key.txt`; the file has no newline, and both Workers trim the value anyway.) To rotate it later: write a new value into the file and run both commands again; browsers in the middle of pairing just retry.
4. Deploy the player (`npm run deploy` in `stash-web/player`). Its production env declares `services: [{ binding: "SYNC", service: "stash-sync" }]`, which only resolves once `stash-sync` exists in the same account. If the player ever moves to its own account, drop the binding and set the player var `SYNC_API_URL=https://sync.stashfm.app` instead (same header, public fetch).
5. Check:
   - `curl -si https://sync.stashfm.app/v1/spaces/AAAAAAAAAAAAAAAAAAAAAA` → 401 `unauthorized`, `cache-control: no-store`.
   - The migration created three classes (`SyncSpace`, `PairSlot`, `Quota`) and the bindings `PAIR_RL`, `SAFE_RL`, `OPEN_RL`, `API_RL`.
   - `curl -si -X POST https://sync.stashfm.app/v1/pair -H 'content-type: application/json' -d '{}'` → 403 (no player key).
   - `$env:PLAYER_KEY = (Get-Content -Raw $HOME\.stash\secrets\stash-sync-player-key.txt).Trim(); node scripts/smoke.mjs https://sync.stashfm.app` → "all checks passed" (it deletes the space it made).
   - `curl -s https://<player>/api/sync/pair` without a session → 401/302 from the player's gate.

## Notes against the spec (2026-10-10, updated after review)

- `POST /v1/spaces` takes `{ pairId, spaceId }`: the **phone mints the space id** (sync-v1 §1: it goes into the pairing answer and is the HKDF salt), and the server insists on exactly `s_` + 16 bytes. The two device records come from the slot, never from the client. The code is burned only once the space exists.
- The pairing answer carries the phone's own `device` record (`id`, `tokenHash`, `pub`, `labelCt`); the label read shows the browser's `id` and `pub`, so either sponsor can seal the newcomer a key envelope. `GET …/reply` needs the phone's token and long-polls like the browser's poll; a reply is accepted after the browser has added the phone (sync-v1's order).
- Join carries the newcomer's key `epoch` (and its `envelope` when the space rotated since): retryable `409 epoch` before the code is used.
- `rotate` answers a changed device set with a retryable `409 devices_changed` (current devices and epoch). It can't always carry the snapshot (a request is at most 1 MiB, a snapshot up to 16 MiB): when it doesn't, `compactDue` holds new batches until a whole-log snapshot under the new key lands. `rotate` also re-encrypts the config (required when one exists) and may re-encrypt labels.
- Daily caps are per device, and the safety actions (see the device list, remove, unlink, rotate) are never refused by them; they have `SAFE_RL`.
- "Not in this space" and "no such space" are one answer, `401 revoked` (§12 said `404 gone` for a deleted space).
- A browser's device token works only through the player, so it ends with the player's sign-in.
- Labels set at pairing are encrypted under the pairing secret; the sponsor re-seals them under the data key in the same call that adds the device (`labels` on create, `labelCt` on join), and the rotator re-seals them in `rotate`.
- Blobs are stored as TEXT (the envelope's JSON), the same bytes; `inbox` also records `fromDevice` and the epoch.
