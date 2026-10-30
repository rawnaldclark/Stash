# SoundCloud implementation

Work on `feat/soundcloud`, based on master including playlist ordering (#742), database 74.
Do not install a database-73 build on either phone. Do not release, merge or deploy this branch.

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
