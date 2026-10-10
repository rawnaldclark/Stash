# Link, mirror and continue: Stash for Android ⇄ Stash on the web — design

**Status:** draft for the owner's approval (2026-10-10), updated the same day with the owner's answers to every open question (§15): Workers Paid; the player's sign-in stays the pairing guard at full release; Spotify/YouTube-synced playlists phone → web only; web plays never feed Stash Mixes; the entry lives in Library & Storage; plus a web-only playback heartbeat. Nothing is built until it is approved; then it is planned and implemented for the next release, all three parts shipping together.
**Scope:** (1) linking the Android app with Stash on the web (play.stashfm.app) by QR code, with no Stash account; (2) choosing exactly what a library export or a one-off "send" carries; (3) opt-in, granular mirroring of likes, playlists and plays; (4) playback handoff in both directions.
**Repos:** app = this repo (`MP3APK`, GitHub `rawnaldclark/Stash`); web player = `C:\Users\theno\Projects\stash-web\player` (paths below starting `player/` are there); the new Worker lives in this repo at `infra/sync-worker/`, next to `infra/share-worker/`.
**Builds on:** `player/docs/library-file-v1.md` (the frozen `stash-web-library` v1 file), app PR #558 (`WebLibraryExporter`), `docs/superpowers/specs/2026-09-23-shared-mixes-design.md` (the portable song descriptor), `docs/superpowers/specs/2026-09-24-listen-together-design.md` (Durable Objects, `SessionCatalog`).

---

## 1. Goals, non-goals, decisions

**Goals**
- Link a phone and a browser in under 15 seconds by scanning a QR code. No account, no email, no password.
- Linking alone changes nothing in either library. Two libraries stay two libraries until the user says otherwise.
- Export / send: the user ticks exactly what goes: likes, plays, each playlist.
- Mirroring: off by default; on per kind (likes, plays, chosen playlists), with a direction.
- Handoff: open the other device and tap once to continue the same song, queue and position. Works with mirroring off.
- The server sees only ciphertext plus sizes and times. Losing the phone or unlinking cuts a device off at once.

**Non-goals (this release)**
- Live remote control (Spotify-Connect style), moving playback while it plays, the phone as a remote. The backend is shaped for it (§6.7), nothing more.
- Syncing logins, cookies, sources, download state, files, file paths, settings, EQ, the Community key, shared-mix edit keys.
- Phone-to-phone or browser-to-browser linking without a phone. Streaming audio from the phone to the browser.
- Changing `stash-web-library` v1. Every new field below lives in new, separately versioned sync formats.

| Decision | Choice | Why |
|---|---|---|
| Pairing | Browser shows a QR; the phone scans it. The QR holds a one-time pairing slot id, a 16-byte pairing secret and the browser's ephemeral P-256 public key. The space key travels inside an ECDH-encrypted answer, never in the QR. | A photo of the QR is worthless after use (one answer, 3-minute life) and never contains the key. No third-party crypto on either side: P-256 ECDH, HKDF and AES-GCM are in WebCrypto and the JCA. |
| Shape | One **sync space** = 1 phone + up to 4 browsers. | Home PC, work PC, laptop. A cap bounds storage and abuse. A second phone is out of scope (it would need a phone-shown QR and two Spotify-synced libraries). |
| Who is in charge | No owner: every device in a space is equal. Any device can remove any other; the remover rotates the key. | Covers a lost phone (remove it from the browser) and a lost laptop (remove it from the phone). |
| Server | New Worker `stash-sync`, one SQLite Durable Object per space, on `sync.stashfm.app` (custom domain, main Cloudflare account, `workers_dev = false`). | `*.workers.dev` is blocked on some networks abroad; stashfm.app already works for the share service. Separate from the share Worker: different data, different blast radius, own limits. |
| Browser path | The browser never calls `sync.stashfm.app`. It calls the player's own `/api/sync/*`, which the player Worker forwards over a service binding with a secret header. | Same pattern as `/api/share`. Only signed-in player sessions can open a pairing slot, so the sync service is no open relay. The player keeps its sign-in (the email code) at full release, so this guard stays (owner's decision, 2026-10-10). No CORS. The CSP needs no change (`connect-src 'self' https:` already). |
| Change detection | **Diff, not hooks.** Each side compares its library with the last-synced base, triggered by its own change signal (Room invalidation / Svelte store), debounced 5 s. | One code path catches every writer (heart, Spotify sync, import, playlist editor) instead of touching a dozen call sites. |
| Merge | Likes: per-song last-writer-wins with tombstones, hybrid logical clock. Plays: grow-only set. Playlists: a three-way merge against the last-synced version. Settings of the mirror: one LWW document. | Likes and plays are sets: LWW is exact. Playlists are ordered lists edited rarely: a three-way merge keeps both sides' adds and removes without a sequence CRDT. |
| Identity | The web's `identity.ts` rules everywhere (`descriptorKey`, `sameSong`, `fold`), ported to Kotlin with shared test vectors. | The same song must match on both sides, and the web player's rules are already the file format's rules. |
| Song on the wire | The library-file v1 **Song** object (title, artist, album, durationMs, isrc, spotifyId, refs, artwork), plus one sync-only flag `phoneOnly`. | Both sides already write and read it (`WebLibraryFile.song`, `backup.ts`). |
| Handoff triggers | Both sides publish on track change, play/pause, seek, queue edit (debounced 10 s), app/tab going to background, sleep-timer pause, end of queue. **Web only:** a heartbeat republishes the small `now` state about once a minute while playing. The phone has no periodic timer. The reader extrapolates the position from the server time stamp. | A laptop lid closed mid-song without pausing fires no event a browser can rely on; the heartbeat bounds the loss to about a minute. The phone's playback service publishes reliably on its own events, so it stays timer-free for battery. Nothing is sent while idle. |
| QR scanner | CameraX + ZXing core (`com.google.zxing:core`, Apache-2.0, pure Java). Camera permission asked only when the scanner opens. Plus an App Link: scanning with the phone's own camera app opens Stash directly. | Works without Google Play services (ML Kit's unbundled scanner and Google Code Scanner need GMS; ML Kit's bundled model is closed source and ~2.5 MB per ABI). A QR on a bright monitor is the easy case for ZXing. |
| QR on the web | `qrcode-generator` (MIT, no dependencies) bundled into the player at build time, drawn as inline SVG. | No third-party script at runtime; the CSP stays `script-src 'self'`. |

---

## 2. User flows and copy

Copy is final-draft: short, plain. Each side uses its own settings components (app: `SettingsGroupCard`, `SettingsNavRow`, `SettingsToggleRow`, `SettingsPickerRow`; web: the existing sections of `player/src/routes/settings/+page.svelte`).

### 2.1 Where it lives

- **App:** Settings › Library & Storage gets a row **Link Stash on the web** — "Pick up where you left off, mirror, send your library". It opens the screen described below (owner's decision: not a top-level hub row). The existing *Export for Stash on the web* stays in *Library & Storage › Backup*, near it, and gains the picker (§2.3).
- **Web:** a new Settings section **Your phone**, above *Your library*.

### 2.2 Pairing

**Web, not linked** (Settings › Your phone):
> **Link the Stash app**
> Pick up on your phone what you were playing here, and back. Nothing is shared until you choose.
> [Show code]

**Web, code shown:** a QR (≈220 px, white quiet zone, in a card), under it:
> In Stash on your phone: Settings › Library & Storage › Link Stash on the web.
> *Waiting for your phone…*

The code renews itself every 3 minutes while shown ("New code" button after 3 renewals, so an idle tab stops asking). When the phone answers, the browser asks too, with the same six digits the phone shows:
> **Link Pixel 6?**
> Check that your phone shows **123 456**.
> [Link] [Cancel]

Then: "Linked to **Pixel 6**". Nothing is shared, joined or left before this tap (the code stops someone who photographed the QR).

**App** (Settings › Library & Storage › Link Stash on the web, not linked):
> **Link a browser** — "Open play.stashfm.app › Settings › Your phone"
> [Scan code]

Scan opens a full-screen camera view with a square cutout. Under it: "Point at the code on your computer." First open asks for the camera. Denied: "Stash needs the camera to scan the code. You can also scan it with your camera app." with [Allow camera]. A QR that isn't a Stash code: "That's not a Stash code."

After a scan, a confirm sheet (prevents linking a stranger's browser by scanning their code):
> **Link Chrome on Windows?**
> Your computer will ask you to check **123 456**. It will see what you're playing here, and you can choose to mirror your library with it.
> [Link] [Cancel]

After Link the phone keeps the code on screen ("Waiting for your computer… · **123 456**", also over "already used") until the link completes: the browser can only work the code out once the phone has answered, so the comparison happens on the browser.

Then: "Linked. Nothing is mirrored yet." with [Set up mirroring] / [Done].

The browser's name is made on the web from the user agent ("Chrome on Windows", "Firefox on Mac", "Safari on iPad") and the phone's from `Build.MODEL` ("Pixel 6"). Both can be renamed in the device list (tap the name).

**Already linked phone** scanning a new browser: the browser joins the same space (up to 4 browsers; a fifth: "You've linked 4 browsers. Remove one first."). A browser can only be in one space: a linked browser shows its device list, not a code. A browser left without a phone (its phone was removed) shows a code again; a phone that scans it brings that browser into its own space (the old, phone-less space is deleted when its last device leaves).

**Scanning with the camera app:** the QR is a URL, `https://stashfm.app/link#…` (the fragment never reaches any server). With Stash installed, Android opens it in Stash (verified App Link) and goes straight to the confirm sheet. Without Stash, stashfm.app shows a one-line page: "Open this code with the Stash app."

### 2.3 Choosing what goes (export file and one-off send)

**App, Library & Storage › Export for Stash on the web**, and **Link Stash on the web › Send my library to a browser**, open the same sheet:

> **What to include**
> ☑ Likes · 1,204
> ☑ Plays · your last 5,000
> Playlists · 12 of 14 ›
> [Save as file] [Send to Chrome on Windows]

- "Playlists ›" opens a checklist of the exported playlists (name, song count; a followed shared mix is labelled "Shared mix") with *All* / *None* at the top.
- The last selection is remembered (DataStore). Defaults the first time: everything ticked (today's behaviour).
- *Send to …* appears only when linked; with several browsers it becomes "Send to…" and asks which.
- The file is still `stash-web-library` v1. Unticked parts are written as empty arrays (v1 allows that).

**Web, Settings › Your library › Import from the Stash app**: after the file is read, the same picker shows what it holds ("This file has 1,204 likes, 5,000 plays and 14 playlists"), then [Import]. Web **Send to Pixel 6** sits beside *Save a backup*, same picker, sends the same v1 document.

**Receiving a send** (the receiver checks on open/foreground):
> **Chrome on Windows sent you** 3 playlists and 40 likes.
> [Add to my library] [Not now] [Discard]

Adding merges exactly as importing the file does (adds, never removes). *Not now* keeps it for 7 days.

The app gains an importer for the v1 file too ("Import from Stash on the web" under Backup, file picker), since phone-side receiving needs one anyway.

### 2.4 Mirroring setup

**App, Link Stash on the web › Mirror** (the web has the same block under Your phone):
> **Mirror between your devices**
> Off keeps a separate library on each.
> Likes — Off ▾
> Playlists — Off ▾ · Choose playlists ›
> Plays — Off ▾

Each picker: **Off / Both ways / Phone → web / Web → phone**. The settings belong to the space: changing them on any device changes them for all.

- **Choose playlists** lists the playlists of the device you're on, each with a switch, plus "Mirror new playlists too" (off by default). A playlist switched on appears on every device of the space; switching it off stops mirroring and leaves every copy where it is.
- Playlists that sync from Spotify or YouTube Music can be mirrored **phone → web only** (they show "From your phone" and are read-only on the web), whatever the direction says: an edit on the web would be undone by the next Spotify sync. (Owner's decision, 2026-10-10.)
- One-way means: the sending side's changes are applied on the receiving side; the receiving side's own changes stay local.

**First merge.** Turning a kind on when both sides already have some of it asks once, on the device where it was turned on, with counts from both sides:
> **Your likes: 1,204 on the phone, 212 here**
> ○ **Combine them** — keep both, 1,370 likes everywhere *(preselected)*
> ○ **Use the phone's** — this browser's 212 become the phone's 1,204
> ○ **Use this browser's** — the phone's 1,204 become this browser's 212
> [Turn on]

The two "use" options remove things, so they say how many in red ("Removes 166 likes here": the 212 here less the 46 the phone also has) and need a second tap. One-way kinds offer only "Add the phone's to this browser's" (preselected) and "Replace this browser's with the phone's". A browser joining a space whose mirroring is already on gets the same question for each kind that is on. Likes from Spotify / YouTube Music Liked Songs are never removed from the phone by a "use this browser's": Stash cannot un-like on those services from a mirror (§7.1).

**Deleting a mirrored playlist:**
> **Delete "Night drive"?**
> ○ Everywhere — on all your linked devices
> ○ Only here — and stop mirroring it

### 2.5 Handoff

Each side has one switch, on by default once linked: **Pick up where you left off** — "Share what's playing with your linked devices."

The other device offers a continue card when it opens or comes to the foreground, if (a) a linked device published a state newer than this device's own last playback, (b) it is less than 12 hours old, (c) this device is not playing, (d) the user hasn't dismissed that exact state.

- **App:** a card above the mini player (`StashScaffold`), same glass surface as the mini player:
  > Continue from **Chrome on Windows**
  > [art] **Pink + White** · Frank Ocean · 2:31   [▶] [✕]
- **Web:** the same card above the player bar (`player/src/routes/+layout.svelte`).
- If the other device is still playing: "Playing now on Chrome on Windows" with the extrapolated position.

Tapping ▶ replaces this device's queue with the other's (same order, same index, same shuffle and repeat, the "Playing from" label) and plays from the position. Songs only on the phone (local files without ISRC, Spotify or YouTube ids) are left out on the web, and the card says so once afterwards: "2 songs that are only on your phone were left out." If the current song is one of them, it starts at the next song, position 0.

**The device you left** is not touched: it keeps its queue, paused or still playing. No remote pause in this release.

### 2.6 Unlinking

- App, linked browsers list: "**Chrome on Windows** · Last used today" — tap › Rename / **Remove**: "Remove Chrome on Windows? It stops seeing your phone at once. Its library stays as it is."
- Web, Your phone: "Linked to **Pixel 6** · last seen 2 min ago" with [Unlink], and the other browsers listed with Remove.
- **Unlink everything** (app, bottom of the screen): deletes the space on the server; every device is unlinked.
- Unlinking never deletes anything from a library. A removed device shows once: "Unlinked from Pixel 6."

---

## 3. Track identity

Both sides key a song exactly as `player/src/lib/identity.ts` does:

- `fold(s)`: NFKD, strip combining marks, lowercase, drop a bracketed `feat./ft./featuring` credit, collapse every run of non-letters/digits to one space, trim.
- `descriptorKey`: `isrc:<ISRC upper>` when there is an ISRC, else `fold(title)|fold(artist)`.
- `sameSong(a, b)`: same key; or same `textKey` and at most one side has an ISRC. Two different ISRCs are two recordings.

**App port:** `core:model/.../weblink/SongKey.kt` (`fold`, `descriptorKey`, `textKey`, `sameSong`, `SongIndex`). Kotlin's `Normalizer.Form.NFKD`, `\p{M}` and `\p{L}\p{N}` match JavaScript's `u` flag; Java's `\s` is ASCII-only (and `(?U)\s` differs from JavaScript's on U+FEFF and U+0085), so the port spells JavaScript's whitespace class out. **Shared vectors:** `player/src/lib/sync/fixtures/identity-vectors.json` (fold cases, keys, sameSong pairs, index lookups), copied byte-for-byte into `core/model/src/test/resources/sync/identity-vectors.json`; both test suites run them and pin the file's SHA-256, as the library-file golden file is shared today (`player/docs/sync-v1.md` §9).

**Matching on the phone.** For an incoming song the app looks up its own row: YouTube id, then Spotify id, then ISRC (as `ensureExactTrackPersisted` does), then `sameSong` against an in-memory `SongIndex` of `(id, title, artist, isrc)` for all tracks, built once per merge run (~2 MB for 20k tracks). No match: `ensureExactTrackPersisted` inserts a stream-only row (as Listen Together does). No new column on `tracks`.

**Song wire object** = library-file v1 `Song` (written by `WebLibraryFile.song()` on the app, read by `backup.ts`'s song reader on the web, same limits), plus optional `phoneOnly: true` for a song whose only source is a local file (`MusicSource.LOCAL` and no ISRC, Spotify or YouTube id). Songs without title or artist never travel (as today).

---

## 4. Data model and wire formats

All plaintexts are UTF-8 JSON, gzip-compressed, then encrypted (§5). Every document carries `kind` and `v`; a reader refuses a higher `v` of a kind it knows ("Update Stash to keep syncing") and ignores unknown fields. The contract is written down once in `player/docs/sync-v1.md` (the web repo, frozen like `library-file-v1.md`), with golden ciphertext fixtures in both repos.

### 4.1 Common

```jsonc
// HLC: compared as a tuple; wall = server-corrected ms (§7.4)
"at": [1760083200000, 0, "d_8f3k2m9q"]     // [wallMs, counter, deviceId]
```

### 4.2 Handoff (`slots/now` and `slots/queue`, one each per device)

```jsonc
// stash-now v1 — small, published often (≤ 2 KB)
{ "kind": "stash-now", "v": 1,
  "playing": true, "positionMs": 151000, "rate": 1.0,
  "index": 7, "queueId": "q_5d1e…",          // hash of the queue doc it refers to
  "song": Song,                                // the current song, for the card without fetching the queue
  "shuffle": false, "repeat": "off",          // off | all | one
  "from": "Night drive",                       // "Playing from" label, display only
  "leftOut": 0 }                               // phoneOnly songs in the queue
```

```jsonc
// stash-queue v1 — published only when the queue changes, debounced 10 s
{ "kind": "stash-queue", "v": 1, "id": "q_5d1e…",
  "items": [Song, …],          // play order (what Up next shows), at most 2,000
  "index": 7,                  // index into items
  "offset": 0,                 // items[0] is this position in the full queue (window when > 2,000: 100 before current)
  "original": [3, 0, 1, …] }  // when shuffled: indexes of items in their unshuffled order, so turning shuffle off restores it
```

The server stamps each slot write with its own time (`serverAt`); readers use `serverAt`, never the writer's clock, to order and age states.

### 4.3 Mirroring

**Config** (`slots/config`, shared, LWW on `at`):
```jsonc
{ "kind": "stash-mirror-config", "v": 1, "at": HLC,
  "likes":     { "dir": "off" },                       // off | both | toWeb | toPhone
  "plays":     { "dir": "off" },
  "playlists": { "dir": "off", "ids": ["m_x7…"], "newOnes": false } }
```

**Change batch** (`log`, append-only):
```jsonc
{ "kind": "stash-mirror-ops", "v": 1, "device": "d_8f3k2m9q",
  "ops": [
    { "t": "like", "s": Song, "on": true,  "at": HLC },          // on:false = tombstone
    { "t": "play", "s": Song, "playedAt": 1760083200000 },
    { "t": "clearPlays", "before": 1760083200000, "at": HLC },
    { "t": "pl", "id": "m_x7…", "at": HLC,
      "parent": "h_…",                                          // hash of the version this edit started from
      "name": "Night drive",
      "items": [Song, …],                                        // full list, in order; null = deleted
      "follow": { "id": "Fw12ab34", "version": 3 },             // optional: a followed shared mix
      "ro": true }                                               // optional: synced from Spotify/YTM, read-only off the phone
  ] }
```

**Snapshot** (compaction, chunked): `{ "kind": "stash-mirror-state", "v": 1, "uptoSeq": 812, "likes": [{ s, on, at }…], "plays": [{ s, playedAt, device }…], "playsClearedBefore": t, "playlists": [{ id, name, items, follow?, ro?, at, hash }…] }` (`device`: who played it, so a device reading a snapshot can mark plays from others with their origin). Tombstones older than 90 days are dropped from snapshots (§6.5 makes that safe).

**Send** (`inbox`, one-off): the plaintext *is* a `stash-web-library` v1 document (the picker's selection), so the receiver runs its normal importer.

**Playlist ids.** A mirrored playlist gets a mirror id `m_` + 16 base62 chars, minted by the device that first switches it on. App: `sync_playlist_map(local_playlist_id, mirror_id, base_hash)`; a playlist created from the mirror is `CUSTOM` with `source_id = "custom_sync_<mirror id>"` (the Library's visible-playlists query already shows `custom_%`). Web: `Playlist.mirrorId?` (new optional field).

### 4.4 Local state per device (never synced, never in backups)

- **App:** a separate Room database `stash_sync.db` (not the library DB, so `DatabaseBackupManager` never carries it and a restored backup can never resurrect a link): `sync_space` (space id, epoch, my device id, device token, key material encrypted with the existing Tink/Keystore pattern from `core:auth` `TinkEncryptionManager`), `sync_base` (kind, key, hash, at), `sync_playlist_map`, `sync_outbox` (unacknowledged batches), `sync_seen` (last log seq, dismissed handoff states). `android:allowBackup="false"` stops cloud backup but not a device-to-device transfer at targetSdk 31+, so `data_extraction_rules.xml` excludes `stash_sync.db*`, the Tink keyset prefs and the handoff prefs from both (Phase 3 review S8).
- **Web:** a new IndexedDB store `sync` (`player/src/lib/library/kv.ts` STORES + DB version 2): credentials, the device's non-extractable P-256 private key as a `CryptoKey`, base, outbox, seen.

---

## 5. Crypto

| Item | Choice |
|---|---|
| Space key `K` | 32 random bytes, with an `epoch` number (starts at 1). |
| Data key | `HKDF-SHA256(K, salt = spaceId, info = "stash-sync v1 data")` → AES-256-GCM. |
| Envelope | `{ "e": epoch, "n": <12-byte random nonce>, "c": <ciphertext+tag> }`, base64url. AAD = `stash-sync/1|<spaceId>|<epoch>|<place>` where place is `log`, `snapshot:<uptoSeq>:<part>/<count>`, `config`, `now:<deviceId>`, `queue:<deviceId>`, `inbox:<toDeviceId>:<sendId>:<part>/<count>`, `label:<deviceId>`, `key:<deviceId>` (pairing messages: spaceId `pair`, epoch 0, places `label`, `answer:<pairId>`, `reply:<pairId>`). The server can't move a blob to another place, part or send unnoticed. A snapshot or send is gzipped once and cut into ≤ 768,000-byte slices, each sealed at its own part place (`player/docs/sync-v1.md` §3.3). |
| Device key | Each device makes a long-term P-256 key pair on first link. Web: non-extractable `CryptoKey` in IndexedDB. App: Android Keystore on API 31+ (ECDH in Keystore), otherwise a software key wrapped with the Tink keyset (minSdk 26). Public key registered on the server, and also inside the device's own encrypted label (`stash-label.pub`): other devices trust only the label's copy. Used only for key rotation. |
| Device auth | Each device makes a random 32-byte token; the server stores `SHA-256(token)`. Requests send `Authorization: Stash-Device <deviceId>:<token>`; compared in constant time. Removing a device deletes its row: it is cut off at once. |
| Pairing | §5.1. |
| Rotation | §5.2. |

### 5.1 Pairing protocol

1. **Browser** makes an ephemeral P-256 key pair `eB` and a 16-byte `pairSecret`; calls `POST /api/sync/pair` (gated) with its device id, token hash, device public key, and its label encrypted under `HKDF(pairSecret, salt = empty, info="stash-sync pair label")` (the label's AAD can't name the pair id: the server mints it in this call). Gets `pairId` (128-bit random) and `expiresAt` (3 min).
2. **QR:** `https://stashfm.app/link#1.<pairId>.<pairSecret>.<eB.pub, 65 bytes uncompressed>` in base64url (~170 characters, QR version 9–10 at level M).
3. **Phone** reads the browser label from the slot (decrypts with the QR's secret), shows the confirm sheet. On *Link*: ephemeral `eP`; `Kpair = HKDF(ECDH(eP, eB.pub), salt = pairSecret, info = "stash-sync pair v1|<pairId>|<eB.pub>|<eP.pub>|<browser deviceId>|<browser pub>")`, the browser's id and long-term key taken from its pairing label, so a label swapped in the slot changes the code; posts `eP.pub`, its own device record in clear (`id`, `tokenHash`, `pub`, `labelCt`: the server needs the token hash when the browser is the sponsor) and `AES-GCM(Kpair, { label, deviceId, devicePub, space?: { id, k, epoch } })` to the slot. The **phone mints the space id** with `K` when it creates a space: the id is the data key's HKDF salt and part of every AAD, and it goes into this answer before the server could return one.
4. **Browser** (long-polling the slot) derives `Kpair`, decrypts. The phone always sends a space (its own, or one it minted with `K` before step 3), so the browser now holds `K`; a browser in a phone-less space joins the phone's and leaves its old one (it never hands its key to a phone; Phase 3 review). When the phone minted it, the phone creates it (step 5).
5. **Server** completes membership in one call made by the device that holds the space (the "sponsor"): `POST /v1/spaces { pairId, spaceId }` (new; the server takes both device records from the answered slot and burns the `pairId`) or `POST /v1/spaces/{id}/devices { pairId }` (join; sponsor-authenticated, adds the other device of that code, also burns it). The sponsor is always the phone. Each device then writes its label under the data key (`label:<deviceId>`): the pairing labels are under the pair secret. A slot answers once; a second answer gets `409 used` and the phone shows "This code was already used. Show a new one on your computer." The exact flows: `player/docs/sync-v1.md` §3.4.

**Nothing is kept before the reply.** After its user confirms the code, the browser always posts a `stash-pair-reply` under `Kpair` (with `space` only when it grants its own space). The phone opens it under its own `Kpair`, which binds the browser label it was served, before it joins, creates the space, re-seals a label or pins a key; a label swapped by someone who photographed the QR (with the server's help) makes the reply fail to open, so nothing is joined or pinned. The browser likewise keeps nothing from the answer until its user confirms.

**The pairing code.** Both sides derive six digits from `Kpair` (`HKDF(Kpair, info = "stash-sync pair sas")`) and show them; each side's user confirms before anything is shared. The browser confirms before it uses a granted space, adds a phone to its own space, posts its reply or leaves an old space.

**What this resists.** A photographed QR after use: dead slot, no key in it. A photo used *before* the phone (a race within 3 minutes): the attacker's answer reaches the browser first, but its code differs from anything the user's phone shows (their phone gets "already used"), so the user cancels on the browser; before the code, a browser in a phone-less space would have handed its space key to that attacker, and a browser joining the attacker's space would have left its own. The server: sees public keys and ciphertext only; swapping `eB.pub` is impossible because the QR carries it. Scanning a stranger's code: the confirm sheet names the browser and says what it will see.

### 5.2 Key rotation

On any device removal, the remover (or, if a device removed itself, the next remaining device to connect, told by `rotationDue: true`) mints `K'` with `epoch + 1`, encrypts it to every remaining device's public key (ECIES: ephemeral P-256 ECDH + HKDF + AES-GCM), uploads the envelopes and a fresh snapshot under `K'` in one `POST /rotate`. The server then deletes the old log, snapshot, slots and envelopes. Writes under an old epoch get `409 epoch` (the client fetches its envelope and retries). A removed device already can't read (its token is gone); rotation also protects against a removed device that kept `K` plus a later server-side leak.

- **Proof.** Each key envelope's document carries `members` and an HMAC under a key derived from the **previous** `K` over the space, new epoch, device, new key and members. A device accepts only the next epoch, only as a member, only with a valid proof. The server never holds a `K`, so it can't mint a rotation and hand devices a key it knows.
- **Keys from labels only.** Rotators seal only to public keys found in a `stash-label` they decrypted (pairing label, or `label:<id>` under the data key); a key the server lists that differs, or a device without a sealed label, gets no envelope. So the server can't substitute its own key for a browser's.
- **No writes under a key due to change.** While `rotationDue` is set the server answers writes with `409 rotation_due`; the device rotates first.
- **Pinned keys.** A device's key is pinned the first time it is learned (the code-checked pairing, else the first sealed label); a label with another key for that id is refused ("Key changed: remove it and link it again").
- **No device blocks a rotation.** Sponsors put the newcomer's data-key label in the join call itself, and a rotator removes any listed device it can't seal to (no openable label, or a changed key) before rotating.
- **Threat model.** Protected: a curious or compromised sync server (sees ciphertext, sizes, times; can't read, mint keys or join). A removed device is cut off at once and can't read anything written under `K'`. Not protected: a removed device that kept the old `K` *and* colludes with the server can forge the next rotation and serve it to a device before the real one. If that device accepts it first, the attacker holds that device's key for `e` and can prove every later epoch for it; the symptom is that the device can no longer read the others ("link again"), and re-linking ends it. Closing that window needs per-device signing keys (a later version). The server can also withhold or replay log batches (merges are idempotent, so a device can lag, not diverge) and show a stale handoff card. **Whoever deploys the player serves its JavaScript**, so they could read the browser's keys whatever the protocol does: end-to-end here means against the sync service and its storage, not against the operator of stashfm.app.

---

## 6. Worker `stash-sync` and the Durable Object

`infra/sync-worker/` (JavaScript like the share Worker; pure logic in `src/space.js` with an injectable clock, tested by `node --test`; the DO class `SyncSpace` is a thin storage wrapper, as `ListenRoom` is).

### 6.1 Hosting and config

- Custom domain `sync.stashfm.app`, main Cloudflare account; `workers_dev = false`. One address. No Cache API anywhere; every response `Cache-Control: no-store`. (The stashfm.app zone's cache ignores query strings, so the API also puts every id in the path, never in a query.)
- Durable Object `SyncSpace` (SQLite, `new_sqlite_classes`), one per space: `idFromName(spaceId)`. Pair slots: `PairSlot` DO, `idFromName(pairId)`, alarm deletes it at expiry.
- Secret `PLAYER_KEY`: the player Worker's forwarding header `X-Stash-Player-Key`. Routes marked *player* refuse requests without it.
- Player Worker: a service binding `SYNC` to `stash-sync` (same account today). If the player moves to its own account (DEPLOY.md §1), the binding becomes a public fetch to `sync.stashfm.app` with the same header; nothing else changes.

### 6.2 Endpoints (all JSON; errors `{ "error": { "code", "message" } }` as the share service)

| Method and path | Who | Does |
|---|---|---|
| `POST /v1/pair` | player | Opens a slot: `{ device: { id, tokenHash, pub, labelCt } }` → `{ pairId, expiresAt }`. |
| `GET /v1/pair/{pairId}` | player (browser token) | Long-poll ≤ 25 s: `204` pending, `200 { phonePub, ct }`, `410 expired`. |
| `GET /v1/pair/{pairId}/label` | phone | `{ labelCt }` for the confirm sheet. 10/min per IP. |
| `POST /v1/pair/{pairId}/answer` | phone | `{ phonePub, ct }`; one answer per slot. |
| `POST /v1/pair/{pairId}/reply` and `GET …/reply` | browser / phone | The browser's encrypted reply when it holds the space (§5.1 step 4). |
| `POST /v1/spaces` | phone | Creates a space from an answered pair: `{ pairId, spaceId }` (the phone minted the id; both device records come from the slot) → `{ spaceId, epoch: 1 }`. |
| `POST /v1/spaces/{sid}/devices` | member (sponsor) | Adds a device from a completed pair. Enforces 1 phone + 4 browsers. |
| `GET /v1/spaces/{sid}` | member | `{ devices: [{ id, type, pub, labelCt, addedAt, lastSeenAt }], epoch, rotationDue, head, snapshot: { uptoSeq, parts }, serverTime }`. Updates caller's `lastSeenAt` (at most hourly write). |
| `PUT /v1/spaces/{sid}/devices/me/label` | member | New encrypted label. |
| `DELETE /v1/spaces/{sid}/devices/{did}` | member | Removes a device (self = unlink). Sets `rotationDue`. |
| `DELETE /v1/spaces/{sid}` | member | Unlink everything: `deleteAll()`. |
| `GET /v1/spaces/{sid}/key/{epoch}` | member | The caller's key envelope after a rotation. |
| `POST /v1/spaces/{sid}/rotate` | member | `{ epoch, envelopes: { deviceId: ct }, snapshotParts }` (parts as below). |
| `GET /v1/spaces/{sid}/log/after/{seq}` | member | Up to 200 batches after `seq`: `[{ seq, device, serverAt, env }]`, plus `head`. |
| `POST /v1/spaces/{sid}/log` | member | `{ env }` → `{ seq, serverAt }`. |
| `PUT /v1/spaces/{sid}/snapshot/{uptoSeq}/{part}/{count}` | member | Compaction; when all parts are in, the server swaps snapshots and deletes log rows ≤ `uptoSeq`. |
| `GET /v1/spaces/{sid}/snapshot/{part}` | member | One part. |
| `GET /v1/spaces/{sid}/slots/config` and `PUT` | member | The shared config envelope (`If-Match: <serverAt>` for LWW safety; 412 → re-read and merge). |
| `GET /v1/spaces/{sid}/slots/now` | member | Every device's `now` slot `[{ device, serverAt, env }]` (the card needs only this). |
| `PUT /v1/spaces/{sid}/slots/now` / `…/queue` | member | Writes the caller's own slot (the server names it by the caller's id). |
| `GET /v1/spaces/{sid}/slots/queue/{did}` | member | One device's queue. |
| `PUT /v1/spaces/{sid}/inbox/{to}/{sendId}/{part}/{count}` | member | A one-off send. |
| `GET /v1/spaces/{sid}/inbox` · `GET …/inbox/{sendId}/{part}` · `DELETE …/inbox/{sendId}` | member | The caller's sends. |
| *(reserved)* `GET /v1/spaces/{sid}/ws` | member | Future live control (§6.7). Answers 404 now. |

The player Worker forwards exactly these paths under `/api/sync/…` (an allow-list in `player/worker/sync.ts`), strips cookies and client headers, adds `X-Stash-Player-Key` and `X-Stash-Client-IP` (for rate limiting only), and caps bodies at the same limits before forwarding.

### 6.3 DO storage (SQLite)

`meta(spaceId, createdAt, epoch, rotationDue, bytesUsed, writesToday, day)`, `devices(id, type, tokenHash, pub, labelCt, addedAt, lastSeenAt)`, `envelopes(deviceId, epoch, ct)`, `log(seq INTEGER PRIMARY KEY, deviceId, epoch, serverAt, body BLOB)`, `snapshot(part, count, uptoSeq, epoch, body BLOB)`, `slots(name PRIMARY KEY, deviceId, epoch, serverAt, body BLOB)`, `inbox(sendId, toDevice, part, count, serverAt, body BLOB)`. Each blob ≤ 1 MiB (the platform row limit is 2 MB).

### 6.4 Limits and abuse resistance

| Limit | Value |
|---|---|
| Devices per space | 1 phone + 4 browsers |
| Request body | 1 MiB (log batch, slot, one part) |
| Snapshot / one send | 16 parts × 1 MiB |
| Space total stored | 32 MiB → over: `413 space_full` (client compacts; a send says "Too big to send. Save it as a file instead.") |
| Log | client compacts at 500 batches or 4 MiB; server refuses new batches past 2,000 (`409 compact`) |
| Writes per space per day | 3,000; reads 20,000 (counters in `meta`) |
| Per IP (`[[ratelimits]]`) | pairing label/answer 10/min; space API 240/min |
| Pair slots | created only through the player (a signed-in session); 6 per session per 10 min at the player; 3-minute life; one answer |

No open relay: nothing can be stored without a device token; a device token only comes from a pairing that started in a gated browser session; every space is capped and expires. The DO keeps no IP addresses.

### 6.5 Retention

- A device not seen for **90 days** is removed (alarm), which sets `rotationDue`. This is also why tombstones can be dropped after 90 days: any device that could still need one has been removed and must re-link (re-linking runs the first-merge question again).
- A space with no devices, or none seen for 90 days, is deleted (`deleteAll()`).
- `now`/`queue` slots: dropped 7 days after their `serverAt`. Inbox: 7 days, or when the receiver deletes it.
- Cloudflare keeps 30 days of point-in-time recovery for DO storage, which only the owner could use, and which holds ciphertext only.

### 6.6 Plan and cost (Workers Paid)

The main Cloudflare account is on Workers Paid ($5/month); everything is sized to its Durable Object allowances: 1 million requests/month included (then $0.15 per million), 400,000 GB-s duration, 50 million SQLite rows written and 25 billion read, 5 GB-month storage (then $0.20/GB-month), 10 GB per object.

- **Per active space per day** (a phone and one browser, ~4 hours of web listening): ~20 event publishes an hour plus the web heartbeat (~60 an hour while playing), a few mirror batches, opens and foregrounds: about 600 requests and 700 row writes.
- **1,000 active spaces:** ~18 M requests/month (~$2.60 over the allowance), ~21 M rows written (included), a few GB stored (included).
- **Duration stays near zero:** an idle object that can hibernate isn't billed, so `SyncSpace` never holds timers, open promises or pending fetches between requests (retention runs on the DO alarm). The only long-held request is the pairing long-poll (≤ 25 s, only while a code is on screen), in the short-lived `PairSlot` object.
- The per-space caps in §6.4 are abuse limits, not cost limits: a space at its daily write cap (3,000) costs well under a cent.

### 6.7 Shaped for live control later

The space DO is the natural room: a later `GET …/ws` upgrade uses the WebSocket Hibernation API (as `ListenRoom`), the DO fans out `now` changes the moment they are written (replacing poll-on-foreground), and adds a `command` message type (play/pause/seek/transfer, encrypted like everything else, addressed to a device). Nothing in this design needs to change for that: the slots, auth and envelopes are already per device.

---

## 7. Merge rules

A sync run on a device: pull (`log/after/{seen}`, or the snapshot if `seen` is older than it), apply remote batches in `seq` order, diff local against base, push one batch, update base. Runs are serialised per device (a Mutex on the app; Web Locks `navigator.locks.request('stash-sync')` across tabs on the web). Direction filters at both ends: a device does not push a kind it only receives, and does not apply a kind it only sends.

### 7.1 Likes (LWW element set)

- State per song: `{ on, at }`. Apply a remote op when its `at` is newer than the local record (matched by `sameSong`).
- Local diff: a liked song not in base (or base `on:false`) → `like on:true`; a base `on:true` song no longer liked → `on:false`; every op of a run is stamped `at := tick(now)` (not `likedAt`: that is the device's uncorrected clock, and a stamp older than ops this device already applied would make its own fresh edit lose).
- App apply: `on:true` → `StashLikedPlaylistRepository.add` path (sets `stash_liked_at` and the STASH_LIKED cross-ref) **without** `LikeDestinationDispatcher`: a mirrored like never likes on Spotify or YouTube Music. `on:false` → `clearStashLiked`. A song liked on the phone through a Spotify/YTM Liked Songs playlist stays liked on the phone after an unlike from the web (Stash can't un-like there from a mirror); the phone's base records `on:false` so it doesn't send it back as a like. It is listed as "Liked on Spotify" in the first-merge counts.
- Web apply: `library.like` / `library.unlike` (already `sameSong`-aware; `fillFrom` adds what a like lacked).

### 7.2 Plays (grow-only set)

- Identity: `textKey|playedAt` (the importer's rule). New local plays since base are pushed; remote plays are inserted if not present.
- App inserts a remote play into `listening_events` with `scrobbled = 1`, `yt_scrobbled = 1` and a new nullable column `origin_device` (main DB migration 53 → 54), so it is never sent to Last.fm, ListenBrainz or YouTube history, and the phone never pushes it back.
- **Remote plays never feed Stash Mixes** (owner's decision, 2026-10-10). They show in History and in a later export, nothing else. How:
  - They are written with `ListeningEventDao.insert`, never `recordCompletedListen`, so `tracks.play_count` / `last_played` stay untouched.
  - Every `ListeningEventDao` query that feeds mixes, recommendations or play statistics gets `AND origin_device IS NULL`: `topTracksSince`, `getPlayCountsSince`, `getPlayCountsSinceWithLatest` (`MixGenerator`'s affinity vector), `getCompletionStatsSinceRaw`, `getTrackIdsPlayedSince`, `getTopArtistsSince`, `getPlayedTrackIdsAmongRaw`, `getTopTracksByLocalPlays`, `distinctDaysCompletedFor`, the play-count/last-played subqueries, `backfillMissingTrackStats`, and `MixGenerator`'s recently-played exclusion. The same filter goes on any other reader that ranks taste (`LastFmPersonas`, `LastFmColdStartImporter`'s checks).
  - Queries that only show or export history (`WebLibraryExportDao.recentPlays`, the History screen) include them.
  - A DAO test inserts one local and one remote play of the same track and asserts every mix-feeding query sees only the local one; its comment is the checklist for any query added later.
- On the web, plays from the phone are ordinary History entries; for symmetry `HistRec` gains `origin?: deviceId` and Daily Discover (`player/src/lib/discover/`) ignores entries that have it.
- `clearPlays` (web *Clear history*, app's clear) mirrors as "delete the plays **I** sent before t" when plays mirror both ways: clearing the web's history never touches the phone's own plays. Clearing everyone's is an explicit choice, with the count of what goes: **Clear history?** ○ Only here *(preselected)* ○ Also on Pixel 6 · "Removes 14,212 plays there" (sends `all: true`).
- The web keeps the newest 5,000 (`HISTORY_CAP`); the phone keeps all. Only plays from the last 5,000 on the sending side are pushed on first merge.

### 7.3 Playlists (three-way merge)

Each side keeps, per mirrored playlist, the base version it last agreed on (`base_hash` + the items in `sync_base`).

- Remote `pl` op whose `parent` equals the local current hash → take it (fast-forward).
- Local unchanged since base, remote changed → take remote.
- Both changed → three-way merge of `base`, `local`, `remote`, by song (`sameSong`):
  - a song removed on either side is removed;
  - a song added on either side is kept;
  - order: the side with the newer `at` gives the order; the other side's added songs go right after their nearest preceding neighbour that survives, else at the start (a song with no surviving predecessor was before everything that survives); the same song added on both sides is kept once;
  - name: the side that changed it from the base; when both did, the newer `at`.
  - Duplicates of one song in a playlist are matched by occurrence (first with first).
- `items: null` (delete everywhere) beats concurrent edits only if its `at` is newer; otherwise the playlist survives with the edit.
- Followed shared mixes mirror as the follow (id, version): each side follows the mix itself; their songs are never merged.
- The owner side of a shared mix (`share`, edit key) stays on its device; the mirrored copy elsewhere is a plain playlist.
- `phoneOnly` songs stay in mirrored playlists so both sides keep the same list; the web shows them dimmed ("On your phone") and the engine skips them.
- `ro` playlists (Spotify/YTM-synced) are only ever written by the phone; the web refuses edits to them ("This playlist syncs from Spotify on your phone"). The phone ignores any op for them; a browser takes only the phone's.
- Before merging, two shortcuts: the remote version equals the base (an echo) → keep this device's; this device doesn't have the playlist → take the remote one. After any outcome the new base is the remote version, so the next diff pushes what this device holds beyond it. The full rule table and its vectors: `player/docs/sync-v1.md` §7.3, `merge-vectors.json`.

### 7.4 Clocks

- Every response carries `serverTime`; each device keeps `offset = serverTime − localTime`, where each sample is `serverTime − floor((sent + received) / 2)` and the offset is the lower median of the newest 5 samples, and uses `wall = local + offset` for HLC.
- HLC update: `wall' = max(wall, last.wall, received.wall)`; counter bumps on ties; deviceId breaks the final tie. A device with a wrong clock therefore can't win every conflict.
- An op stamped more than 10 minutes after its log row's `serverAt` is skipped (not clamped: every reader sees the same `serverAt`), so one bad clock or a µs stamp can't win every conflict or drag every clock forward.
- The mirror config is never rolled back: a device ignores a config older (by `at`) than the newest it applied.
- An unpushed local playlist edit carries the `tick` taken when it was made (or, if none was kept, one at merge time), never the base's `at`, so a remote delete can't silently beat a later offline edit.
- Handoff ages and ordering use the server's `serverAt` only.

### 7.5 Turning mirroring on, first merge

The device turning it on fetches the snapshot, computes counts, asks (§2.4), then:
- **Combine:** union by the rules above (likes `on:true` wins over absence; playlists: both kept; a playlist with the same name on both sides is not merged automatically: two playlists, the second named "Night drive (web)").
- **Use the phone's / this browser's:** the chosen side's set is written as the full state with fresh `at`s, and the other side's extras get tombstones. App removals only clear Stash likes (§7.1).
- Then the device writes the config. Other devices see the new config on their next run and, if they hold data of that kind that isn't in the space yet, ask the same question.

---

## 8. Handoff in detail

### 8.1 Publishing

| Trigger | App (`core:media`) | Web |
|---|---|---|
| Track change, play/pause, seek, sleep-timer pause, end of queue | `HandoffPublisher` collecting `PlayerRepository.playerState`, debounced 2 s | engine bus events (`player/src/lib/engine/bus.ts`) |
| Queue edit | queue slot, debounced 10 s (hash unchanged → nothing sent) | same |
| Going to background | `ProcessLifecycleOwner` ON_STOP → publish now | `visibilitychange` hidden and `pagehide` (where the engine already flushes) via `fetch(…, { keepalive: true })` |
| Heartbeat | none (by design: battery; the service's own events are reliable) | while `playing`, republish `now` every 60 s: one interval timer, started on play, cleared on pause/stop/unlink, skipped when an event publish went out in the last 30 s. Browsers throttle hidden-tab timers to about once a minute, which still fits. |

- Only when linked and the handoff switch is on; nothing while idle. A laptop lid closed mid-song: the web's last heartbeat is at most ~60 s old, so the phone offers a position at most about a minute behind (extrapolation stops at the song's end, §8.2). A publish that fails offline is dropped, except the newest state, which is retried once on reconnect if still newest.
- The phone publishes from the playback service's scope (alive while playing). No wake lock of its own, no WorkManager for handoff. Data: `now` ≤ 2 KB; `queue` ≤ ~60 KB compressed for 2,000 songs, only on change.
- Listen Together or Cast sessions: publish as usual (what plays is still what the user is hearing). A Listen Together guest doesn't publish (the queue isn't theirs).

### 8.2 Offering

On app foreground (at most every 30 s) and on web load / tab visible (at most every 30 s): `GET slots/now`, decrypt, pick the newest state from another device that meets §2.5's conditions. Extrapolation when `playing`: `positionMs + (serverTime − serverAt) × rate`; if that passes the song's length, show the published position instead (the device was probably killed).

### 8.3 Restoring

- **Web:** fetch the queue slot, map `Song` → `LibraryItem` (`descriptorKey`, refs as given; drop `phoneOnly`), then a new `PlayerAPI.loadQueue({ items, index, shuffle, original, repeat, from, positionMs, autoplay: true })` in `engine.ts` that reuses the body of the private `restore()` (`q.restore(…)`, clock reset to the position) and then plays: the tap is the user gesture the autoplay policy wants. Songs resolve on the web's sources exactly as a refs-less imported song does; one that fails is skipped by the engine's retry-then-skip.
- **App:** map each `Song` to a `MediaItem` through `SessionCatalog.mediaItemFor` (Listen Together's exact persist: YouTube id, Spotify id, ISRC, else a stream-only row; a matching download plays from the file). Resolve the current song and the next 5 first, start playing, fill the rest in the background (as `setQueue` fills). Shuffle: set the items in the original order and a `DefaultShuffleOrder(shuffledIndices, seed)` that reproduces the play order, so shuffle-off restores the original order on both sides. Then `seekTo(positionMs)` and `setRepeatMode`. The "Playing from" label comes across as text only: a new `PlaybackSource.Handoff(label)` (display only, serialised like the others).
- The restored queue is this device's own from then on (its normal persistence takes over).

---

## 9. App changes by module

- **`core:model`** — `weblink/SongKey.kt` (identity port), `weblink/SyncWire.kt` (serializable `StashNow`, `StashQueue`, `MirrorConfig`, `MirrorOps`, `MirrorState`; `Song` reuses `WebLibraryFile.Song`, which moves here or is aliased), `weblink/Hlc.kt`, `weblink/HandoffTiming.kt`. (Package `weblink`, not `sync`: `core:data`'s `sync` package is the Spotify/YouTube library sync.)
- **`core:data`**
  - The files below live in `weblink/` (not `sync/`, which is the Spotify/YouTube library sync).
  - `weblink/SyncCrypto.kt` — P-256 ECDH, HKDF-SHA256, AES-256-GCM, ECIES envelopes, documents in parts, via the JCA (`KeyAgreement`, `Cipher`, `Mac`); Keystore key on API 31+. No Tink here: its hybrid and keyset formats don't interoperate with WebCrypto and it exposes no raw ECDH/HKDF.
  - `sync/SyncApiClient.kt` — OkHttp to `https://sync.stashfm.app/v1`, typed errors (`revoked`, `gone`, `epoch`, `space_full`, `rate_limited`).
  - `sync/SyncDatabase.kt` — the separate `stash_sync.db` (§4.4).
  - `sync/SyncSpaceRepository.kt` — link/unlink, devices, rotation, labels, config.
  - `sync/MirrorEngine.kt` — diff, merge (§7), apply; pure merge functions in `weblink/merge/` with JVM tests (`merge-vectors.json`, shared with the web).
  - `sync/LibraryChangeSignal.kt` — Room `InvalidationTracker` on `tracks`, `playlists`, `playlist_tracks`, `listening_events`, debounced 5 s → enqueue sync.
  - `sync/MirrorSyncWorker.kt` — WorkManager: one-time on change (network constraint, 10 s debounce, `REPLACE`), periodic every 6 h (network, battery not low), plus on app foreground (at most once a minute). Rethrows `CancellationException` before `catch (Exception)`.
  - `weblibrary/WebLibraryExporter.kt` — `collect(nowMs, generator, selection: ExportSelection)`; `ExportSelection(likes, plays, playlistIds: Set<Long>?)`; `null` = all. The golden test stays byte-identical for "everything".
  - `weblibrary/WebLibraryImporter.kt` (new) — v1 reader with `backup.ts`'s limits and cleaning; additive merge into Room following §7's matching (likes as Stash likes without fan-out; playlists as `custom_web_<id>` CUSTOM playlists, merged by that id; plays as `listening_events` with `origin_device`).
  - Main DB migration 53 → 54: `listening_events.origin_device TEXT NULL`.
- **`core:media`** — `handoff/HandoffPublisher.kt`, `handoff/HandoffRestorer.kt` (uses `SessionCatalog`), `handoff/HandoffOffers.kt` (state for the card); `StashPlaybackService` starts the publisher; `PlayerRepository.restoreHandoff(plan)`.
- **`feature:settings`** — `SettingsWebLinkScreen.kt` + `WebLinkViewModel.kt` (status, devices, rename/remove, mirror pickers, playlist chooser, handoff switch, send, unlink everything); `components/QrScanner.kt` (CameraX `PreviewView` + `ImageAnalysis` → ZXing `QRCodeReader`, permission asked on open); `ExportPickerSheet.kt` shared by export and send; `SettingsLibraryStorageScreen.kt` gets the **Link Stash on the web** row (no new hub row; the Library & Storage subtitle in `SettingsHubSummaries.kt` mentions Stash on the web so Settings search finds it), uses the picker, and adds "Import from Stash on the web".
- **`app`** — manifest: `CAMERA` permission with `<uses-feature android:name="android.hardware.camera.any" android:required="false"/>`; intent filter `https://stashfm.app/link` (autoVerify; the existing `assetlinks.json` already covers the host); navigation to the link confirm sheet; the handoff card and the inbox dialog in `StashScaffold`.
- **Dependencies:** `androidx.camera:camera-camera2/-lifecycle/-view`, `com.google.zxing:core`. No GMS. `androidx.lifecycle:lifecycle-process` if not present.
- **Diagnostics:** a `SyncDiagnosticsContributor` (linked yes/no, device count, last sync time and result, outbox size, mirror config; never ids, keys or labels).

## 10. Web changes by file (`C:\Users\theno\Projects\stash-web\player`)

- `src/lib/sync/crypto.ts` — WebCrypto P-256 ECDH, HKDF, AES-GCM, ECIES; device key generated non-extractable and stored in IDB.
- `src/lib/sync/client.ts` — `fetch('/api/sync/…')`, typed errors, `serverTime` offset.
- `src/lib/sync/pairing.ts` — slot, QR payload, long-poll, reply, device label from `navigator.userAgentData` / UA.
- `src/lib/sync/qr.ts` — wraps `qrcode-generator` (bundled) → SVG string.
- `src/lib/sync/mirror.ts` + `merge.ts` — diff/merge (§7), pure and unit-tested; vectors shared with the app.
- `src/lib/sync/handoff.ts` — publisher (engine bus, visibility, `keepalive`, the 60 s heartbeat while playing), offers store, restore.
- `src/lib/discover/` — ignore history entries with `origin` (§7.2).
- `src/lib/sync/wire.ts` — the formats of §4, readers that rebuild every value (as `backup.ts`).
- `src/lib/identity.ts` — keys unchanged (it only gains exports: `SongLike`, `keyOf`, `isrcOf`); the shared vectors live in `src/lib/sync/fixtures/` (identity, clock, merge, crypto) and run in `src/lib/sync/vectors.test.ts` and `crypto.test.ts`; `scripts/sync-fixtures.mjs` writes the crypto fixture independently of `src/lib/sync/`.
- `src/lib/library/kv.ts` — `sync` store, `openDB(name, 2, …)` upgrade.
- `src/lib/library/library.ts` — `Playlist.mirrorId?`, `LibraryItem.phoneOnly?`; `setPlaylist(id, { name, items })` for merges; `importData(d, selection?)`; a change signal for the mirror (a store version counter).
- `src/lib/api.ts` — `SyncAPI` on `AppAPI` (status, devices, link, unlink, rename, mirror config, send, inbox, offers, `continueFrom`); `PlayerAPI.loadQueue`; `LibraryAPI.importData` selection; fake implementation in `src/lib/fake/`.
- `src/lib/engine/engine.ts` — public `loadQueue` (from `restore()`), skip `phoneOnly` items, publish hooks on the bus.
- `src/lib/app-real.ts` — wire the sync service (lazy chunk, loaded only when linked or when Settings › Your phone opens).
- `src/routes/settings/+page.svelte` — "Your phone" section; the import picker; Send.
- `src/routes/+layout.svelte` — handoff card, inbox prompt, "Unlinked" notice.
- `worker/sync.ts` (new) + `worker/index.ts` — `/api/sync/*` allow-list forwarder (gated like every route), body caps, `X-Stash-Player-Key`; `wrangler.jsonc` production `services: [{ binding: "SYNC", service: "stash-sync" }]` and secret `SYNC_PLAYER_KEY`; `scripts/preflight.mjs` checks the binding.
- `worker/security.ts` — no change: `connect-src 'self' https:` already allows it, and the browser only talks to `'self'`. `Permissions-Policy: camera=()` stays (the web never scans).
- `docs/sync-v1.md` (new, frozen contract) and `docs/library-file-v1.md` — a "Writers" note that unticked parts are written as empty arrays (no format change).

## 11. Privacy and README text

**README › What Stash talks to**, a new item after the share Worker:

> - **The sync Worker** (`sync.stashfm.app`) — a Worker the project runs, used only after you link Stash on the web (Settings › Library & Storage › Link Stash on the web). It stores, end-to-end encrypted with a key that only your linked devices have, what you chose to share between them: what's playing and your queue (when "Pick up where you left off" is on), the likes, plays and playlists you chose to mirror, and anything you send to the other device. The Worker can't read any of it; it sees sizes, times, and a random id per device. Each device checks it when Stash opens and sends to it when what's playing changes or when your mirrored library changes, and every 6 hours in the background while mirroring is on. Logins, files, downloads and settings never go. Your IP address is used for rate limiting and isn't stored. A device that hasn't been seen for 90 days is removed, and a link with no devices left is deleted; what's playing is deleted after 7 days, and a send after 7 days or when it's received. Unlinking cuts a device off at once. Cloudflare's 30-day recovery history holds only the encrypted data.

**`web/src/content/privacy.md`**: the same paragraph under *Sharing, Listen Together and Community* (renamed *Sharing, linking and Listen Together*), the 90-day / 7-day figures added to the header's list of code-derived figures (`DEVICE_IDLE_DAYS`, `SLOT_KEEP_DAYS` in `infra/sync-worker/src/space.js`), and `updated:` bumped. *The short version* gains: "Linking Stash on the web needs no account. What you choose to share between your devices is end-to-end encrypted."

**Web player's privacy note** (its About/Settings text): the browser side of the same paragraph, plus "While something plays, this browser updates what's playing about once a minute." and "The pairing code on screen is single-use and expires in 3 minutes."

## 12. Failure cases

| Case | Behaviour |
|---|---|
| Offline | Mirror changes wait in the outbox (app: `sync_outbox`, WorkManager retries with the network constraint; web: IDB outbox, retried on `online` and next visible). Handoff: only the newest state is retried. The card isn't shown offline. |
| Clock skew | HLC with server offset (§7.4); handoff uses `serverAt`. A phone set an hour ahead can't win every conflict, and its card isn't "newer" by mistake. |
| Conflicting edits | §7: likes by HLC, plays never conflict, playlists three-way merge, config LWW with `If-Match`. |
| Song missing on one side | Mirrored: kept as an item (refs-less songs resolve by words when played; failures are skipped at play time, not deleted). Handoff: skipped by the player. `phoneOnly`: dimmed on the web, left out of a web handoff with the one-line note. |
| Revoked / removed device | Next call gets `401 revoked`: the device deletes its sync state, keeps its library, shows "Unlinked from Pixel 6" once. Space deleted → `404 gone`, same. |
| Key rotated meanwhile | `409 epoch` → fetch envelope, retry. No envelope for me → treated as revoked. |
| Two browsers | Both are members; mirroring reaches all three; the card offers the newest other device. Two tabs of one browser are one device: Web Locks makes one tab the sync runner; whichever tab plays publishes. |
| Pairing code used twice / expired | "This code was already used" / "This code expired. Show a new one." |
| Phone reinstalled or backup restored | Sync state isn't in backups: the phone is unlinked; the browsers show it as last seen long ago; re-link by scanning (the space's browsers remain; a browser whose space still lists the old phone removes it first: "Remove Pixel 6 (old) to link this phone"). |
| Lost phone | Browser › Your phone › Remove Pixel 6 → cut off, key rotated. |
| Space full / log too long | Client compacts (snapshot) and retries; a send that is too big: "Too big to send. Save it as a file instead." |
| A newer format arrives | "Update Stash to keep syncing" (app) / reload prompt (web); nothing is applied, nothing is lost (the log keeps it). |
| Player sign-in session ends on the web | `/api/sync` answers the gate's redirect like every route; the browser stays linked and resumes after sign-in. |
| Worker down | Every surface degrades silently: no card, mirror waits, pairing says "Can't reach Stash right now. Try again in a minute." |
| Mirror apply partially fails (app crash mid-merge) | Apply runs in one Room transaction per batch; `seen` advances only after commit, so a batch is re-applied, and every apply is idempotent. |

## 13. Test plan

**Unit (both repos)**
- Identity vectors: the same JSON passes `vectors.test.ts` and `SyncVectorsTest` (accents, `feat.` brackets, `(Live)` kept, ISRC vs words, two ISRCs, scripts, the whitespace edge cases).
- Merge: LWW likes (ties, tombstones, skewed clocks), plays dedup across ISRC/words keys, playlist three-way merge table tests (add/add, add/remove, reorder/add, delete/edit, duplicates), first-merge Combine/Use-X counts. Same vector file `merge-vectors.json` in both repos.
- Crypto: golden envelopes (fixed key and nonce) decrypt identically on both sides; pairing transcript test with fixed ephemeral keys; ECIES rotation round trip; AAD mismatch rejected.
- Wire readers: oversize, unknown fields, higher `v`, bad Song fields (as `backup.test.ts`).
- Exporter: golden v1 unchanged for "everything"; selection drops exactly the unticked parts; importer round trip (app export → app import is a no-op).
- Worker (`node --test` on `src/space.js`): pair single answer, expiry, device cap, auth (wrong token, removed device), rotation epochs, compaction, limits (413, 409 compact), retention alarms, `serverAt` stamping.
- App: `HandoffPublisher` triggers (fake clock; asserts no periodic publish), `HandoffRestorer` shuffle-order reproduction, `MirrorSyncWorker` cancellation rethrow; Room migration 53 → 54 test; the remote-plays DAO test (§7.2).
- Web: engine `loadQueue` (position, shuffle original, `phoneOnly` skip), Web Locks single runner, heartbeat (fake timers: every 60 s while playing, none when paused, skipped right after an event publish), Daily Discover ignores `origin` plays.

**Integration:** the Worker under `wrangler dev` with a scripted phone (Kotlin JVM test client) and browser (Playwright, `player/e2e`): pair, mirror a like both ways, handoff both ways.

**Two-device manual script** (release build on the Pixel 6, Chrome on the PC; Pixel 5 rig + Firefox for the multi-browser steps; `adb -s` always):
1. Web: Settings › Your phone › Show code. Phone: Settings › Library & Storage › Link Stash on the web › Scan. Confirm sheet names "Chrome on Windows" → Link. Both show each other within 3 s. Neither library changed (count likes/playlists before and after).
2. Photograph the QR, scan it again with the Pixel 5 after step 1 → "already used".
3. Play a 30-song playlist on the PC, shuffle on, skip to song 6, seek to 2:31, pause. Open Stash on the phone → card "Continue from Chrome on Windows · <song> · 2:31". Tap ▶: same song, 2:31 ±1 s, Up next identical, shuffle on; turning shuffle off restores the playlist order.
4. Phone keeps playing; lock it; after 2 songs, open the web tab → card shows the current phone song, position about right. Tap → plays. Phone still playing (expected, no remote control).
5. Queue with a local file on the phone (a sideloaded MP3 without ids) → web handoff leaves it out with the note.
6. Mirroring: likes Both ways with 10 likes on phone, 3 on web (1 shared) → first-merge shows 10/3 → Combine → 12 on both. Unlike one on web → gone on phone after foreground. Like one on phone while web is offline (DevTools offline), re-online → arrives.
7. Playlists: mirror "Night drive" only. Add a song on the web, remove a different song on the phone while the web is offline → both edits survive on both.
8. Plays phone → web: play 3 songs past the counting mark on the phone → appear in web History; confirm they are not re-scrobbled (Last.fm on a test account) and do not come back. Web → phone: play 3 songs on the web → they show in the phone's History, `play_count` unchanged, and a Stash Mix refresh (`StashMixRefreshWorker`) doesn't count them (log the affinity inputs).
8b. Web heartbeat: play on the web, close the laptop lid mid-song without pausing, wait 3 minutes, open Stash on the phone → the card offers a position within about a minute of where the lid closed.
9. Export picker: untick plays and 2 playlists → file has empty `history`, 12 playlists; import on the web with the picker. Send to Chrome: inbox prompt → Add → counts match.
10. Second browser (Firefox): link from the phone; mirror settings arrive; first-merge question for each kind that's on.
11. Remove Firefox from the phone → Firefox shows "Unlinked" on its next call; Chrome keeps syncing (after key rotation, verify epoch bumped in diagnostics).
12. Airplane mode on the phone, change a like, kill the app, reopen online → change syncs (WorkManager).
13. Unlink everything → both sides unlinked, libraries intact; `GET` the space id → 404.
14. Battery: an hour of playback with handoff on vs off, Battery Historian / `dumpsys batterystats`: no wake locks or alarms attributable to sync beyond the network calls.
15. Crypto on real providers: `./gradlew :core:data:connectedDebugAndroidTest --tests '*SyncCryptoAndroidTest*'` (or `-Pandroid.testInstrumentationRunnerArguments.class=com.stash.core.data.weblink.SyncCryptoAndroidTest`) on an API 26 emulator and on the Pixel 6 (API 31+: the Keystore ECDH case runs). The JVM unit tests use SunEC; this proves Conscrypt and the Keystore agree with the fixtures.
16. Pairing code: photograph the browser's QR, answer it from the Pixel 5 first. The Pixel 6 then gets "already used" but keeps showing its code; the browser asks "Link Pixel 5?" with the Pixel 5's code, which differs from the code on the Pixel 6. Cancel on the browser leaves both libraries and spaces as they were.

## 14. Implementation order

Each phase ends merged and green; the release ships after phase 6.

1. **Contracts:** `player/docs/sync-v1.md`, identity and merge vector files, crypto golden fixtures; Kotlin identity port passing the vectors.
2. **Worker:** `infra/sync-worker` (pairing, spaces, devices, log, slots, inbox, rotation, limits, retention) with `node --test`; deploy to `sync.stashfm.app`; player `worker/sync.ts` forwarder + service binding.
3. **Pairing + device management** on both sides (QR, scanner, App Link, confirm, list, rename, remove, unlink everything, rotation). Ships nothing user-visible yet behind a build flag.
4. **Handoff** both directions (publisher, offers, restore, cards). The core promise; device-proven before mirroring starts.
5. **Export picker, import picker, app importer, one-off send + inbox.**
6. **Mirroring** (config, first merge, likes, plays, playlists, compaction, WorkManager), privacy/README text, diagnostics, the manual script end to end.

## 15. Owner decisions (2026-10-10)

No questions remain open.

1. **Plan:** the main Cloudflare account is on Workers Paid; the Worker is sized to Paid allowances (§6.6).
2. **Pairing guard:** the web player keeps its sign-in (the email code) at full release, so a signed-in player session stays the guard on opening a pairing code (§1, §6.4).
3. **Spotify/YouTube-synced playlists:** mirror phone → web only, read-only on the web (§2.4, §7.3).
4. **Web plays on the phone:** mirror into History when the user mirrors plays, but never feed Stash Mixes or play statistics (§7.2).
5. **Placement:** a "Link Stash on the web" row in Settings › Library & Storage, not a top-level hub row (§2.1).
6. **Web heartbeat:** the web player republishes what's playing about once a minute while playing; the phone has no periodic timer (§1, §8.1).

## 16. Changes made while building the contracts (phase 1, 2026-10-10)

`player/docs/sync-v1.md` is the wire contract; it and this spec now agree. What changed from the approved text, and why:

1. **Package `weblink`, not `sync`,** on the app (`core:model` and `core:data`): `core:data`'s `sync` package is the Spotify/YouTube library sync. (`sync/MirrorEngine.kt` and the other later files above move with it when they are built.)
2. **Vector files** live in `player/src/lib/sync/fixtures/` (identity, clock, merge, crypto), with byte-identical copies in `core/model/src/test/resources/sync/` (identity, clock) and `core/data/src/test/resources/sync/` (merge, crypto). Both suites pin each file's SHA-256; `.gitattributes` stops line-ending conversion.
3. **The phone mints the space id** (§5.1, §6.2: `POST /v1/spaces { pairId, spaceId }`): it is the HKDF salt and part of every AAD, and it travels in the pairing answer before the server could return one. The answer carries the phone's device record in clear for the server.
4. **Pairing details** (§5.1): the label key's HKDF salt is empty, and its AAD can't name the pair id (minted in the same call); pairing envelopes use spaceId `pair`, epoch 0, places `label`, `answer:<pairId>`, `reply:<pairId>`; a browser sponsor joins the phone before replying; every device re-writes its label under the data key after joining.
5. **AAD places** (§5) name the part of a snapshot or send (`snapshot:<uptoSeq>:<part>/<count>`, `inbox:<to>:<sendId>:<part>/<count>`) and add `key:<deviceId>`; a multi-part document is one gzip stream cut into ≤ 768,000-byte slices (fits a 1 MiB request in base64url).
6. **Likes diff** (§7.1) stamps `at = tick(now)`, not `likedAt`.
7. **Playlist merge** (§7.3): an addition with no surviving predecessor goes to the **start** (was "the end"); the same song added on both sides is kept once; the name merges three-way (was plain LWW); explicit echo and missing-playlist shortcuts and the base update; read-only ops are taken only from the phone.
8. **Snapshot plays** carry `device` (§4.3) so a device reading a snapshot can set `origin`.
9. **Clock offset** (§7.4) is defined exactly: per-sample midpoint, lower median of the newest 5.
10. **Copy** (§2.4): "Removes 46 likes here" was the shared count; it is 166 (212 − 46).
11. **Crypto review** (2026-10-10, before freezing v1), each now in `player/docs/sync-v1.md`:
    - Rotations are **proven** under the previous key (`stash-key` gains `members` and `proof`), accepted one epoch at a time; device keys are trusted only from **sealed labels** (`stash-label` gains `deviceId` and `pub`; pairing labels sit at `label:<deviceId>`; the answer's label carries the phone's key, `devicePub` is gone). Threat model in §5.2.
    - A six-digit **pairing code** from `Kpair`, confirmed on both screens; the browser confirms before it shares, joins or leaves anything (§2.2, §5.1).
    - `409 rotation_due` blocks writes under a key due to change; the config can't be rolled back; stamps over 10 minutes past `serverAt` are skipped; an unpushed playlist edit's stamp is defined (§5.2, §7.4).
    - *Clear history* clears only the sender's plays unless the user picks "Also on Pixel 6" (§7.2).
    - Readers: gzip must end with the whole output's CRC and length; values are checked by JSON type; writers are pinned by the fixtures; an instrumented test covers Conscrypt and the Keystore (§13 step 15).
12. **Crypto re-review** (2026-10-10): `Kpair` binds the browser's device id and key, so the code covers its label; device keys are pinned per id; the phone keeps its code on screen and the browser does the comparing (copy in §2.2); joins carry the newcomers' data-key labels (`POST /v1/spaces … labels`, `POST …/devices … labelCt`) and a rotator removes devices it can't seal to; gzip must be exactly one member; the threat model now states the forged-rotation window exactly and that whoever serves the player serves its code.
13. **Crypto re-review, R7** (2026-10-10): the browser's reply is posted in every pairing (its `space` is optional) and the phone completes membership, re-seals and pins only after that reply opens under its own `Kpair`. Without it, a QR photographer with the server's help could swap the browser's label before anyone compared the code, and the phone would join and pin the attacker's key (rotations would then leak `K'`). Worker: no route change; optionally refuse the phone's create/join claim until the reply is posted.
14. **Phase 3 review** (2026-10-10), app and web: the answer always carries `space` and the "browser grants its phone-less space" ending is gone (the phone can't tell it apart, and a phone never takes a browser's key; a browser in a phone-less space joins the phone's); one answer and one reply per code (a double tap does nothing); once the reply opens, the phone's create or join runs to completion even if the screen goes, and a fresh space is kept locally before the create so a lost answer or a crash is settled by the next refresh; a config nobody can open is replaced by the default (every kind off) so it can't block a rotation; a passing Keystore failure no longer unlinks (only a key that is definitely gone does, and the phone then removes itself on the server while its token still reads); the camera permission ships in debug builds only until the feature does; `data_extraction_rules.xml` keeps the link out of device-to-device transfers; a pairing link opened from outside the scanner shows a warning on the confirm sheet, and label names lose control, bidi and zero-width characters on both sides; a join is refused when the space rotated twice or more since the answer. `player/docs/sync-v1.md` §3.4, §3.5 and §5.6 carry the contract side.
