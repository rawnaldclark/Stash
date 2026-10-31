# SoundCloud implementation

Work on `feat/soundcloud`, based on master including playlist ordering (#742), database 74.
Do not install a database-73 build on either phone. Do not release, merge or deploy this branch.

## Current implementation and remaining gate

The initial phase-one implementation and owner-requested expansion to account-created playlists
and personalized Home mixes are implemented. PR #744 remains draft for the remaining gates below.

- `data/soundcloud` resolves public links, profiles, likes, playlists and stream transcodings.
  Requests, pagination and retries are bounded; cancellation propagates. Credentials are sent
  only to trusted API endpoints. An opt-in live test resolved a real public upload and read its
  HLS manifest successfully, including the current SoundCloud playback CDN.
- Encrypted account storage supports OAuth or a public profile with no token. Connection attempts
  are cancellable; account generations prevent stale fetches/diffs from writing after disconnect
  or switching accounts. The connection sheet uses existing account components, supports public
  profile URLs, restricts sign-in navigation and clears stale OAuth cookies before login.
- Migration 74 to 75 stores exact upload IDs, permalinks and preview flags on tracks and snapshots,
  plus connection IDs on playlist snapshots. Migration tests preserve existing files and custom
  order. Exact IDs survive playback, Android Auto and saving a queued song after its row disappears.
- Library sync is wired through PlaylistFetchWorker and DiffWorker: likes, account-created
  playlists, and the personalized Mixed for shelf. Likes and owned playlists use the account
  mode (default Accumulate); rotating mixes refresh. Custom order survives all modes.
  Fetches finish before snapshots are published; both refresh and accumulate preserve custom order.
  Exact uploads remain distinct. Verified ISRC reuse retains existing files but rejects cloud-only,
  flagged and dismissed candidates. No title-based substitute is allowed.
- Dedicated HLS playback is wired for the phone and Android Auto. Download-mode gates cover every
  open; usable local files win. Expiring root manifests can refresh once. Failed segment
  authorization invalidates the cached URL and fails that load; a new playback attempt is required.
  Seamless recovery after a long pause is not implemented or claimed.
- Full-track downloads use the canonical SoundCloud permalink and OAuth through yt-dlp, then
  existing file validation and commit. Preview metadata, resolved preview formats and truncated
  duration are rejected. Temporary files are cleaned up on failure/cancellation. Background queue
  partitioning recognizes SoundCloud IDs even when an ISRC-reused row started on another service.
- Public track/set/mix/profile/likes/short links reach the existing paste/share entry points. Re-paste
  appends missing songs, keeps exact upload identity and honors the Download toggle for full tracks.
  The Library Liked filter includes SoundCloud. Shared rows and Now Playing show a separate Preview
  badge; preview download and wrong-recording actions are hidden.
- The web player protocol is unchanged. SoundCloud-only rows are phone-only in mirroring and
  excluded from web export rather than converted into title matches. Full Room backups retain them.

The account lock surrounds short database commits, never network work. Public-profile mode is
connected but not OAuth-authenticated; worker and queue gates use the session/connection state.
SoundCloud identity takes precedence over a reused row's original source in download routing,
matching exclusions and cancellation. Keep these rules when extending this integration.

## Scope

Connect a SoundCloud account using its WebView `oauth_token`, or use a public profile's likes.
Sync liked tracks, account-created playlists and personalized Home mixes; stream the actual
SoundCloud recording, download full tracks, and import track/set/mix/likes/profile/short links.
Mark previews and never download them. No SoundCloud search, reposts, general Home recommendations,
web player changes, or title-based replacement recordings.

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

## Validation and phone checklist

Affected JVM tests and full debug assembly pass, including client/security fixtures, account races,
DB migration, likes worker entry points, revision skips, custom-order sync, exact-ID/ISRC behavior,
HLS selection and manifest recovery, Download mode, Android Auto metadata, native-download guards,
link re-paste, queue source partitioning, web export exclusions, settings cancellation and UI actions.
The live public-API/HLS test is opt-in with `STASH_SOUNDCLOUD_LIVE=1`; ordinary CI skips it.
It prints no tokens or signed URLs. This proves live metadata/manifest access, not Android playback
or yt-dlp/FFmpeg execution.

Before marking the PR ready, on a phone:

- Visually check the SoundCloud account sheet, progress, errors and Preview badges.
- Connect a public profile, cancel a connection, disconnect and switch accounts during sync.
- Sign in with the owner's account; verify private likes and reconnect after process death.
- Sync in refresh and accumulate modes; preserve custom order and existing verified downloaded files.
- Stream a full track and a Go+ preview; check local-file preference, seek and long pause.
  Segment expiry currently requires another playback attempt; verify it fails without substitution.
- Download a full track using the Android yt-dlp/FFmpeg runtime; reject a preview and partial file.
  Cancel mid-download, retry, disconnect, and test low storage and airplane/Download mode.
- Paste/share track, set, profile/likes and short links; re-paste without duplicates, including while
  offline; verify Download toggle and exact same-title upload separation.
- Round-trip a full backup and confirm web mirroring never substitutes a title-matched recording.

A fresh debug APK may be installed on the existing DB74 phones after a successful build. Installing,
logcat and screenshots are allowed; ask the owner before driving UI or changing app data. No release,
merge, destructive install or relay changes are authorized.

Latest verification (2026-10-30): the final persistence/import/diff run passed 58 tests;
`:app:assembleDebug --max-workers=3` exited 0. The fresh APK was 191,571,550 bytes and was
installed successfully with `adb -s 192.168.137.230:5555 install -r` on the verified Pixel 5.
The owner subsequently authorized Pixel 5 UI navigation. PR #744 remains draft until the
remaining device checks below are complete.

## Pixel 5 verification (2026-10-30)

Verified through the real UI and worker/runtime results:

- Public profile `soundcloud.com/forss` connects; the account card shows Connected as Forss.
- Sync now fetches 199 liked tracks. Source management shows one SoundCloud Liked playlist with
  199 songs. Library's SoundCloud Liked filter shows the synced uploads.
- Warm by Joey Pecoraro streams: Android media session reports PLAYING with advancing position
  and buffered media. A Phil Collins Go+ preview plays a roughly 30-second clip with a separate
  Preview badge in shared rows and Now Playing. Playback was paused after testing.
- Downloading Warm through the row's Download action runs the Android yt-dlp path with exit 0;
  TrackDownloadWorker reports success, and the Library row shows Downloaded after refresh.
- Pasting `soundcloud.com/forss/flickermood` through Library + resolves the correct upload.
  With Download off, import adds one song to Songs from links. Re-pasting and adding again says
  Already all in your library; it does not add a second occurrence.

Phone QA caught and fixed missing first-run SoundCloud connection entry, stale Not connected
Settings summary, a downloaded-song count limited to Spotify/YouTube, and incorrect imported
playlist source badges. Both follow-up full debug builds exited 0; visual rechecks continue.

Private OAuth login and authenticated likes are now verified in the follow-up below. These checks
do not establish Go+ subscriber access, Android Auto behavior, long-pause recovery, low-storage
cleanup, or full backup round-trip on the device. Keep these gates explicit rather than inferring them from unit tests.

Additional device results:
- In Download mode, the SoundCloud Liked filter contains only downloaded Warm; undownloaded
  uploads/previews disappear. Warm plays successfully; Now Playing shows local M4A at 160 kbps,
  with no streaming indicator. Restored the original Stream-only preference after the test.
- The Settings hub now correctly shows SoundCloud as connected after returning from Accounts.
- Installed the source-badge follow-up APK successfully (build exit 0, fresh output at 18:04).

- Verified the corrected imported-playlist header visually: orange SoundCloud badge, one track,
  three minutes. Cards omit the generic BOTH badge for locally imported playlists; detail uses
  actual track sources. The follow-up full debug build passed.
- Disconnecting the test public profile returns the account card to Not connected. The local
  downloaded file and imported playlist are retained. Playback is paused.

- Fresh-install recheck shows Connect SoundCloud on Sync's first-run screen; it opens Accounts.
  The layout was checked visually on the Pixel 5. Final card-label build exited 0 and installed
  successfully; no database wipe or destructive install was used.

## OAuth return regression (2026-10-30)

The owner completed sign-in, but the embedded mobile site stayed open. Runtime inspection of
cookie names/scope confirmed oauth_token was host-only on m.soundcloud.com; the old code read
only soundcloud.com. No credential values were printed or written to a diagnostic file. Using
that session in memory, both SoundCloud's session-validation POST and authenticated profile GET
returned HTTP 200. This established a cookie-capture bug rather than invalid credentials.

SoundCloudLoginCookies now reads the active trusted origin first, retains root-cookie fallback,
and refuses credential reads on foreign/insecure pages. Login cleanup includes host-only cookies
for mobile/www/secure as well as the root domain cookie. Six regression tests cover mobile scope,
precedence, fallback, hostile pages, blank/incorrect cookie names and cookie removal scope.
All nine SoundCloud settings tests passed and the full APK build exited 0. Installed the fresh
fixed APK on the Pixel 5. Reopening login reused the existing browser session, closed the
WebView automatically and displayed the owner's connected account. No second manual login was needed.

Authenticated follow-up: Sync now completed with 140 liked tracks for the owner's account.
Force-stopped and restarted the app after sync; Accounts still showed the owner connected.
The phone is left in Accounts with playback paused and the original Stream-only preference.

## Account playlists and personalized mixes (owner scope expansion)

Use the account's existing Playlists / Mixes / Liked tabs, without a new settings page.
Own playlist inventory comes from the paged users/{id}/playlists endpoint; OAuth includes private
owned playlists, while public-profile mode can fetch public owned playlists. Only the matching
owner is accepted. Personalized mixes require OAuth: read the Mixed for shelf
(soundcloud:selections:your-moods), require made_for.id to match the connected account, and ignore
stations, charts and unrelated recommendations. Home discovery is capped at five pages; every
selected mix is fetched/hydrated completely before membership reconciliation.

Own playlists and mix source IDs contain both account ID and remote identity. Reconnecting to
a different account cannot make its lists adopt the previous account's snapshots. A failed or
cancelled playlist fetch never publishes incomplete membership. Sync never deactivates a whole
SoundCloud playlist just because it disappears from the discovery shelf or a capped listing.
Owned playlists keep account refresh/accumulate behavior; mixes refresh through the existing
DAILY_MIX path. Both preserve the custom order overlay and append arrivals. No new migration or
Stack control is required for this expansion.

System-playlist links under /discover/sets/... accept the service's colon-delimited IDs and resolve
through the existing paste/share importer. Normal track and user-created set links keep their
existing behavior. Re-paste remains idempotent. Hydrate track stubs in batches of 50, restore source
order afterward, deduplicate repeated upload IDs, and fail closed on silently missing IDs or a
truncated track array. A blocked stub fails that playlist fetch, preserving its existing membership
and downloaded files: a region/subscription block is not evidence that a song was removed.
Other healthy playlists continue; no replacement recording is chosen.

Live API inspection found one personalized 30-track mix on the owner's account and no owned
playlists. The batch track endpoint returned all 30 expected IDs. No credential values were
printed or saved. An empty owned-playlist inventory is valid; fixture tests cover nonempty/paged
inventories and private-account worker wiring. Device sync and mix-link preview were verified on the Pixel 5 as described below.

Failure isolation uses one complete playlist per transaction and keeps only that playlist's hydrated
tracks in memory. The worker records both successful list counts and per-list failure counts, so
healthy snapshots still reach DiffWorker when another list or Likes fails. The owned-list inventory
must finish paging before details are fetched. Cancellation propagates, and every commit rechecks
the account generation. Tests cover failed, cancelled and stale fetches plus partial success.

Expansion verification:
- Focused client run: 27 tests passed (the opt-in live smoke test was skipped).
- Regression runs: 42 DiffWorker, 6 SoundCloud link-import, 31 SyncViewModel, 8 playlist-fetcher,
  26 real fetch-worker/revision and 8 Home-classifier tests passed. This includes custom order,
  both account modes, mix refresh, failure isolation, account switching/cancellation, mix links,
  discovery preference and Home rail placement. Full debug assembly exited 0.
- Installed the fresh APK on the Pixel 5 without wiping data; the existing OAuth account remained
  connected. Sync brought in Your Mix 1 with 30 songs, alongside the owner's 140 liked tracks.
- SoundCloud management visibly shows Playlists 0 / Mixes 1 / Liked. The owner currently has no
  created playlists to check live; nonempty and paged owned inventories are covered by fixtures.
- Pasting the actual /discover/sets/your-moods:... link through Library + opens Your Mix 1 with
  30 songs and the existing Download toggle. No duplicate local copy was added during this check.
- The existing shared Discover mixes automatically preference now gates SoundCloud discovery.
  It leaves owned-playlist and Likes sync enabled. Turning it off keeps already imported mixes.
- Home classifies account-scoped SoundCloud mixes as Made for you by identity, independently of
  their localized title; user-created playlists remain regular playlists.

Final device recheck: installed the fresh final debug APK (193,080,116 bytes, build exit 0).
Home visibly places Your Mix 1 under Made for you. Opening that card shows SoundCloud / 30 tracks;
Play All starts the correct first upload, and Android's media session reports PLAYING with an
advancing position and queue size 30. Paused playback afterward. The phone is left on the native
Your Mix 1 detail screen; the account is connected and Stream-only mode is unchanged.

## Artwork quality follow-up (2026-10-31)

Spotify track, playlist-library, playlist-track, mix-search and Home-mix parsers now choose the
widest usable HTTPS source rather than the first entry. Public SpotifyArtwork keeps that selection
consistent without adding cross-file internal calls. Existing Spotify album-CDN sizing/fallback
remains in place. Stored Spotify metadata picks up the corrected source on its next sync.

SoundCloud's -large cover is only 100x100. Live CDN checks returned the same image at 500x500
and 1080x1080 (larger byte payloads too). Shared ArtUrlSizer now requests those variants according
to display size, on exact SoundCloud image hosts, without downgrading larger URLs. Small rows
keep their original URL; caps and existing network-failure fallback remain effective. This repairs
existing SoundCloud covers at load time without database changes, re-sync or cache deletion.

Verification: all 84 Spotify module tests and 45 focused shared artwork-sizing tests passed.
Full debug assembly exited 0; fresh APK installed successfully on Pixel 5. Visually inspected the existing mix cover and Sync fan. A later original-image probe below
established that Your Mix 1 itself has only a 500px source; requesting 1080px cannot add detail.
Spotify is not connected on the Pixel 5, so fresh Spotify sync/artwork was not claimed as device
verified. The phone remains on the mix detail page with playback paused.

## Playback, Likes visibility and source-art investigation (2026-10-31)

Reproduced SoundCloud failures during upload resolution on the Pixel 5. The stored/browser OAuth
JWT had expired; the account still appeared connected because credentials existed. Added automatic
rotation through the fixed secure SoundCloud OAuth endpoint using the token's own client ID.
Persist access/refresh credentials atomically in the encrypted session, preserve connectionId, verify
the returned user, serialize concurrent rotations, and finish a bounded in-flight rotation even when
playback is cancelled. An existing installation can adopt a browser refresh cookie only when its
access cookie exactly matches the connected native token. Browser rotation is kept in step. No
credentials are logged, stored in fixtures or included in exception diagnostics.

Sync renews credentials before capturing its account snapshot; failures leave membership intact.
Library Likes now excludes retained SoundCloud playlists belonging to another account while an
account is connected. The earlier public-profile test's retained likes had been leading the merged
list. No old tracks or files were deleted. Sync management cover/title rows now open Playlist Detail;
its header uses real membership counts when lists are empty or hidden instead of always claiming
All in your library in streaming mode.

Device verification: expired session renewed without another sign-in; introduction by XXXTENTACION
played with advancing position and buffered media. After force-stop/restart it played again, proving
the renewed native session survived. A fresh sync completed; Library -> Liked -> SoundCloud shows
the owner's songs, and tapping SoundCloud Liked in Sync opens the 140-track playlist. Playback paused.
Full auth suite: 31 passed. SoundCloud client suite: 30 passed, one opt-in smoke skipped. Library VM:
25 passed. Fetch worker/revision suite: 26 passed. Full debug APK build exited 0 and installed.

The owner clarified that the playback report referred to SoundCloud, not Spotify. No Spotify
playback reproduction is outstanding. Idioteque by Radiohead and Pink + White by Frank Ocean
also played through the existing Spotify/YouTube library path during the earlier investigation.

Your Mix 1 source-art probe: t500x500 returns 500x500, 22,298 bytes; original returns that same
500x500 image and byte count. t1080x1080 returns an upscaled 1080x1080, 65,441 bytes. The service
has not supplied more original detail for this cover. Keep the authentic service cover; requesting
larger dimensions is not evidence of sharper source content. This corrects the earlier implication
that every 1080px response necessarily improves this specific cover.

## SoundCloud download follow-up (2026-10-31)

On Pixel 5 with the renewed session, downloaded Earl Sweatshirt - Balance by skyfish_ from the
existing SoundCloud playlist menu. TrackDownloadWorker completed successfully: full hls_aac_160k
audio, 1,596,316-byte M4A. Library Songs displayed its Downloaded badge and Recently Downloaded card.
Switched to Download mode, selected that downloaded row, and verified the active media session
reported PLAYING with advancing position and the full 79,088 ms buffered. Download mode also hid
the current undownloaded SoundCloud Likes. Paused playback and restored the owner's Stream only
setting afterward. Kept the downloaded song; no existing downloads or playlists were removed.

## Link and interruption checks (2026-10-31)

- Flickermood re-paste reports Already all in your library; its existing Songs from links membership
  is reused. The import's default Download-on setting downloaded the existing song successfully.
- Imported the authenticated Your Mix 1 system-playlist URL into its separate local link copy:
  30 members. Re-pasting offers Add what's new and reports Already all in your library after adding.
  A later run explicitly verified the switch's checked=false state before re-adding; the local copy
  retains keep_offline=false. Initial label taps did not toggle the switch and started downloads;
  stopped that playlist's Download button and retained completed files. No existing songs were deleted.
- Force-stopped during PlaylistFetchWorker. On restart WorkManager retried fetch, then DiffWorker
  and SyncFinalizeWorker succeeded: two synced playlists, zero new tracks and zero failures.
- Added introduction through its exact upload link with Download on and force-stopped during its
  native download. Startup reset one stale IN_PROGRESS row to PENDING, retried it and saved the full
  2,080,089-byte M4A. An earlier interrupted mix download left a 14 MB .part/.ytdl pair in cache;
  FileOrganizer already sweeps files older than 24 hours once per process on temp-directory access.
  Recovery does not reuse a previous attempt's partial file.
- Found and removed duplicate synced-ago text in the Sync hero: librarySummary now supplies only
  counts; SyncedHero owns the single timestamp. Full debug build succeeded; fresh APK is 193,083,237
  bytes (2026-10-31 04:15:21), installed successfully on Pixel 5.
- Android SEND invocation was blocked by tool execution policy; share-receiver device verification
  remains open. Paste entry points above were exercised through the real UI.

Remaining device gates: Go+ subscriber access, low storage, long-pause/seek
expiry, shared-link entry and full backup/mirroring round-trip. Keep the PR draft; do not manufacture
low storage or restore over the owner's library just to close a checklist.

Owner decision: Android Auto device testing is deferred and is not a release gate for this PR.
Its implementation remains in place; no hardware verification is claimed.

## Backup, mirroring and seek checks (2026-10-31)

Found and fixed SoundCloud account playlists being marked web-editable: RoomMirrorLibrary.isSynced
now includes SOUNDCLOUD, so service-managed playlists mirror phone to web read-only just like
Spotify/YouTube. Local custom_link copies remain editable. Added real Room tests for the chooser
and playlist read paths, and for SoundCloud uploads carrying phoneOnly=true without Spotify/YouTube
substitute IDs in mirrored playlist and Likes output. The web wire format is unchanged.

Extended the real backup ZIP/import test to verify upload permalink, Preview status and source
survive library merge while an exact existing upload retains its downloaded file and a namesake
upload remains separate. No restore was performed over the owner's phone library.

Verification: DatabaseBackupMergeTest 24 passed; WebLibraryExporterTest 19 passed;
RoomMirrorLibraryTest 17 passed; MirrorEngineTest 47 passed. Full debug assembly succeeded.
On Pixel 5, Riot streamed through its end into King Of The Dead. Forward seek to about 156 seconds
and backward seek to about 17 seconds both reported PLAYING on the same undownloaded upload.
Paused afterward. Long-pause authorization expiry and a full on-device backup/mirror round-trip
remain separate from these checks; Android Auto is deferred by the owner.
