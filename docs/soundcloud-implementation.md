# SoundCloud implementation

Work on `feat/soundcloud`, based on master including playlist ordering (#742), database 74.
Do not install a database-73 build on either phone. Do not release, merge or deploy this branch.

## Current implementation and remaining gate

All phase-one code is implemented; PR #744 remains draft until phone verification.

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
- Likes sync is wired through PlaylistFetchWorker and DiffWorker, with its own Accumulate default.
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
- Public track/set/profile/likes/short links reach the existing paste/share entry points. Re-paste
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
- Stream a full track and a Go+ preview; check local-file preference, Android Auto, seek and long pause.
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
No UI navigation, account sign-in or private app-data inspection was performed. UI permission
and the owner's SoundCloud sign-in are the next gate. PR #744 remains draft.
