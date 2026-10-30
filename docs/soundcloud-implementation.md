# SoundCloud implementation

Work on `feat/soundcloud`, based on master including playlist ordering (#742), database 74.
Do not install a database-73 build on either phone. Do not release, merge or deploy this branch.

## Current integration boundary

- Client foundation: implemented, 12 fixture tests pass.
- Account storage: implemented through TokenManager and the existing encrypted store. Public
  profiles have null credentials. Latest connection intent wins; disconnect invalidates pending
  validations. The complete core/auth suite passes (24 tests, including seven SoundCloud tests).
- Database identity: migration 74 to 75 adds exact SoundCloud identity, permalink, and preview
  status on tracks and track snapshots. Playlist snapshots carry a connection ID so a future diff
  can reject results from a previous account connection. Domain/Library mappings retain the fields.
- Source enum and sync worker entry points are implemented. A connected public profile passes
  the worker's connection gate without pretending to have an OAuth credential. Full likes are
  published transactionally, and the diff checks the connection ID before any playlist writes.
- SoundCloud has its own sync mode (default Accumulate). Exact upload IDs are batched, verified
  ISRCs can reuse an existing recording/file, and loose title matches never replace an upload.
- Playback now has a dedicated SoundCloud resolver, explicit HLS source selection, preview
  metadata, a bounded URL cache lifetime, and manifest-only URL refresh. Phone and Android Auto
  placeholders select the source after resolution; usable local files still win. Download-mode
  gates cover manifests and segments. Cached URLs from other services cannot bypass SoundCloud
  routing, and recap cards cannot substitute a YouTube title match.
- Dedicated downloading, preview controls and phone connection controls remain unwired. Guards
  reject generic downloading and lossless upgrades for SoundCloud rows; the diff does not enqueue
  them. Keep the PR draft and do not install this slice.

The sync implementation covers both PlaylistFetchWorker's initial "no authenticated services"
gate and its auth-probe short circuit: a public SoundCloud profile is connected but has no OAuth
token, so `isAuthenticated(SOUNDCLOUD)` intentionally returns false. Read `soundCloudSession`
instead. Fetch all pages before writing a snapshot, then use `withCurrentSession(connectionId)`
around its short database transaction. DiffWorker must check the same connection ID under that
guard **before findOrCreatePlaylist**, and keep the guard through the membership transaction.
Do not hold the account lock while fetching network data. Give SoundCloud its own sync mode;
the existing diff's fallback to Spotify's account mode must not apply to it.

MusicSource.SOUNDCLOUD is present with temporary routing guards. Audit exhaustive source branches,
title matching, lossless upgrade/sweep queries, stream registry, and DownloadManager before
enabling row creation. The shared word-matching rule now refuses SoundCloud/unlinked pairs;
SoundCloud-to-existing-recording reuse must use verified ISRC identity explicitly.

Playback integration also requires HLS support. `StreamUrl` currently marks DASH only, and
`StashMediaSourceFactory` selects a DASH child or the progressive lazy data source. Add an
explicit HLS marker, the matching Media3 HLS dependency and media source, and route cached and
fresh resolutions through that child. Do not send SoundCloud manifests into the progressive
byte pipe. Cover the initial resolve, cached URL, expired URL refresh, local-file preference,
and Download-mode rejection. Check whether other StreamUrl consumers (recap and preview)
need the same routing before allowing them to consume SoundCloud streams.

## Scope

Connect a SoundCloud account using its WebView `oauth_token`, or use a public profile's likes.
Sync liked tracks, stream the actual SoundCloud recording, download full tracks, and import
track/set/likes/profile/short links. Mark previews and never download them. No SoundCloud search,
reposts, automatic user-playlist sync, web player changes, or title-based replacement recordings.

## Build sequence and acceptance gates

1. **Client.** `data/soundcloud`: scrape/cache/refresh the public client ID, validate OAuth with
   the session POST, fetch profile/likes/sets/tracks, and resolve non-DRM transcodings. Bound
   requests and retries; honor Retry-After, propagate cancellation, validate redirect and paging
   hosts before sending credentials. Never return a partial likes collection as complete.
   Test fixtures cover expired client IDs, paging, rate limits, previews, and hostile URLs.
2. **Identity and persistence.** Add `MusicSource.SOUNDCLOUD`, nullable indexed SoundCloud track
   identity and permalink, and preview state to tracks and remote track snapshots. Use migration
   74 to 75 if still free, with schema and migration test. Audit every source switch and source
   SQL predicate. A SoundCloud-only row must never enter YouTube matching or lossless title search.
   Same ISRC may reuse a verified recording; keep its existing downloaded file. Conflicting
   SoundCloud IDs must stay distinct. Metadata/file improvements preserve playlist membership
   and the custom ordering overlay.
3. **Connection and likes sync.** Store credentials through core/auth encrypted storage and
   TokenManager. Add a SoundCloud account row to the existing Sync connection design and expose
   disconnect. Validate tokens before storing; public-profile mode stores no fake credentials.
   Integrate PlaylistFetchWorker and DiffWorker, using a stable per-account liked-playlist ID.
   Only complete inventories may remove membership in refresh mode; accumulate retains songs.
   Account switching/disconnect must not let an old in-flight fetch overwrite the new account.
4. **Playback and download.** Route SoundCloud identity before lossless/YouTube fallback in
   StreamSourceRegistry. Use expiring stream cache entries and fresh transcoding resolution.
   Keep DownloadModeGate authoritative. DownloadManager must branch before YouTube URL rebuilding,
   use the permalink and OAuth credentials with yt-dlp, then existing validation and file commit.
   Check preview metadata and resolved media before starting; do not accept a truncated preview
   as a complete download. Never log credentials or embed them in persistent queue URLs.
5. **Links and visible behavior.** Extend MusicLinks/LinkImporter, preserving re-paste deduplication
   and the Download toggle. Identity must survive the web-import intermediary without making a
   new web protocol. Add the source to Library's Liked filter. Show Preview in shared rows and Now
   Playing and disable download actions for previews. Do not expose controls until their real
   entry points are wired.
6. **End-to-end gate.** Run affected unit tests and assembleDebug with three workers, one Gradle
   invocation at a time. Verify successful exit and fresh APK. Install explicitly by device serial;
   ask before driving the UI or changing device data. Check connect/disconnect, public likes,
   sync refresh/accumulate, mixed-source reuse, preview streaming, full-track download, airplane
   mode, link re-paste, process death, and custom playlist order. Open a PR with remaining device
   checks stated accurately. No release or merge.

## Phone design

Use existing Sync account rows, buttons, typography, spacing, and connection sheet.

```text
Sync
  SoundCloud                         Connect
  Your liked tracks

SoundCloud
  [ Connect SoundCloud ]

  Or use a public profile
  [ Profile URL                    ]
  [ Use public likes ]

Connected account
  SoundCloud                      Disconnect
  Account name
  Liked songs                         [sync]

Song row
  [art] Track title                  Preview
        Artist
```

After sign-in, close the sheet and return to the account row. Use existing progress/error patterns.
No new settings page. Preview state is visible before playback or download, not only after failure.

## Parked work from Claude's general handoff

Web sleeve flip/browser checks, web dependency repair, relay PRs, playlist-order web/backup/undo
follow-ups, and release preparation are separate work. Do not alter them during SoundCloud work.
Do not build a combined custom order for Liked Songs. Do not fix contributor AutoMix #720.

Integration verification: the complete `:app:assembleDebug --max-workers=3` build passed on
2026-10-30, with Gradle exit 0 and a fresh `app/build/outputs/apk/debug/app-debug.apk`
(187,297,340 bytes). This verifies the current client/account/database integration across the
app; it does not verify SoundCloud playback or likes sync, which remain unwired. No installation.

Likes-sync validation: 66 selected tests passed (SoundCloudLikesFetcherTest, DiffWorkerTest,
PlaylistFetchWorkerRevisionSkipTest), including the real public-profile worker entry point,
failed/stale fetch rejection, both modes with a custom order, distinct upload identities, and
ISRC reuse retaining the existing file. The full assembleDebug build passed in the same Gradle
invocation. No phone install or live-account check. Dedicated playback/download controls remain
pending, so keep PR #744 draft.


Playback recovery boundary: an expired root manifest may be re-resolved once, provided the
codec and full/preview status stay the same. Segment authorization failures invalidate the
cached URL and fail that load; they do not transparently remap segment URLs or splice playlist
bytes into an audio segment. A new playback attempt is required. Live SoundCloud playback,
long-pause/seek expiry behavior, and release readiness still require device checks.

The selected playback run passed 81 tests: direct SoundCloud routing/cancellation/preview state,
manifest refresh, cached-source identity, cold and cached HLS source selection, local-file
preference, Download mode, and recap exclusions. Follow-up tests cover the actual HLS factory,
existing player streaming, and Android Auto placeholder construction.

Final playback validation: 118 distinct tests passed across the focused playback and regression
runs, including the refreshed-manifest URI case. The latest full debug APK build exited 0.
No device installation or live-account playback validation was performed. Dedicated SoundCloud
streaming is implemented; downloading, connection controls, link import and preview labels remain.
