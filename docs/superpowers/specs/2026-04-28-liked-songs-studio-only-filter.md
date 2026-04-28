# YT Music Liked Songs — Studio-Only Filter Design

## Problem

YouTube Music's Liked Songs (the `LM` / `VLLM` playlist) mixes two distinct kinds of content:

1. **Studio releases** — ATV (Topic-channel auto-mirror), OMV (Official Music Video), and OFFICIAL_SOURCE_MUSIC (label/artist channel uploads).
2. **Deeper cuts** — UGC (covers, fan uploads, lyric videos, live recordings, mixes from non-artist channels), and PODCAST_EPISODE entries the user thumbed up.

A user with 1700+ Liked Songs may want a focused listening library of only the official versions, while a different user may want everything. The pagination work shipped on `feat/yt-sync-pagination` (commits `0030379..afdc679`, plus the LM-endpoint fix Fix C) now correctly retrieves all tracks; this spec adds a user preference that filters at sync time.

## Goal

A binary user preference — *Studio recordings only* — that, when ON, excludes UGC and PODCAST_EPISODE tracks from the Liked Songs sync. Default OFF (everything syncs as today). Only YT Music Liked Songs are affected; user-curated playlists and Home Mixes pass through unchanged.

## Non-goals

- Filtering Spotify's Liked Songs (Spotify's data has no equivalent classification — its Liked Songs is uniformly studio).
- Filtering custom user playlists or Home Mixes — users may intentionally curate covers/lives in those.
- Persisting the classification on `RemoteTrackSnapshotEntity` so the user can flip the filter without re-syncing — explicitly out of scope per user choice (Option A in brainstorming). Toggling the pref takes effect on the next sync; the existing data is not retroactively reclassified.
- Re-using the new pref to filter the local download queue, the search-results view, or playback. Sync-time filtering is the only behavior change.

## Architecture

### Classification boundary

| `MusicVideoType` | When filter OFF | When filter ON |
|---|---|---|
| `ATV` | include | include |
| `OMV` | include | include |
| `OFFICIAL_SOURCE_MUSIC` | include | include |
| `null` (parser couldn't determine) | include | include |
| `UGC` | include | **filtered out** |
| `PODCAST_EPISODE` | include | **filtered out** |

Rationale for `null` → include: the parser sets `null` when InnerTube's `watchEndpointMusicConfig.musicVideoType` is missing or has a value not in our enum. This is rare in practice (the field is reliably present on Liked Songs items), but when it does occur, dropping the track silently would mask a parser issue and misclassify legitimate studio tracks. Erring on inclusive matches the user's expectation that "studio-only" is about excluding the obvious cover/UGC noise, not aggressively pruning anything uncertain.

### Filter location

Filter at the worker layer in `PlaylistFetchWorker.fetchAndSnapshotLikedSongs`, between `getLikedSongs()` returning `PagedTracks` and the `insertPlaylistSnapshot` / `insertTrackSnapshots` calls.

Rationale:

- `YTMusicApiClient.getLikedSongs()` stays domain-pure: it returns whatever YT Music has, with no awareness of user preferences.
- `PlaylistFetchWorker` already reads user prefs (`SyncMode` per source); adding a second pref read alongside is mechanically symmetrical.
- The worker is also where `SyncStepResult` diagnostics are appended, so the "filtered N tracks" annotation is co-located with the action.

A small private helper inside the worker performs the filter so a unit test can drive it independently:

```kotlin
internal fun filterStudioOnly(tracks: List<YTMusicTrack>): List<YTMusicTrack> =
    tracks.filter {
        it.musicVideoType != MusicVideoType.UGC &&
        it.musicVideoType != MusicVideoType.PODCAST_EPISODE
    }
```

### Preference storage

Extend `SyncPreferencesManager` (`core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt`) with one new key, one field on `SyncPreferences`, one reactive `Flow`, one setter. The DataStore name (`sync_preferences`) is unchanged; default value `false` keeps existing installs at "everything syncs".

```kotlin
// in Keys
val YOUTUBE_LIKED_STUDIO_ONLY = booleanPreferencesKey("youtube_liked_studio_only")

// in SyncPreferences
val youtubeLikedStudioOnly: Boolean = false,

// new flow + setter
val youtubeLikedStudioOnly: Flow<Boolean> =
    context.syncPrefsDataStore.data.map { it[Keys.YOUTUBE_LIKED_STUDIO_ONLY] ?: false }

suspend fun setYoutubeLikedStudioOnly(enabled: Boolean) {
    context.syncPrefsDataStore.edit { it[Keys.YOUTUBE_LIKED_STUDIO_ONLY] = enabled }
}
```

The worker injects `SyncPreferencesManager` (already available — used elsewhere) and reads `youtubeLikedStudioOnly.first()` once at the start of `fetchAndSnapshotLikedSongs` to capture the value for that sync run.

### UI

A single toggle row in the existing **Settings → Sync Preferences → YouTube Music** card, placed beneath the existing **Sync Mode** chip row.

Surface text:

> **Studio recordings only**
> Excludes covers, live recordings, and UGC uploads from your Liked Songs. Other YouTube playlists are unaffected.

Visual treatment: standard Material 3 `SwitchPreference`-equivalent (Stash uses GlassCard + Material 3 — match the existing toggles in this card; do not introduce a new pattern). Help text below in muted secondary-on-surface.

When the user flips the toggle ON, no immediate action is taken. The next sync (manual via Sync Now or scheduled) applies the filter. A subtle one-time hint can be shown beneath the toggle when changed: *"Takes effect on next sync"* — non-blocking; auto-dismisses on dialog close.

### Diagnostics

`SyncStepResult` for the `getLikedSongs` step gains an annotation when the filter ran:

- Filter OFF: existing behavior. `itemCount = total tracks`. `errorMessage = null` (or partial-fetch reason if applicable).
- Filter ON, no partial: `itemCount = post-filter count`, `errorMessage = "filtered N UGC/podcast tracks (studio-only mode)"`.
- Filter ON + partial: `errorMessage` concatenates both reasons, `;`-separated, matching the existing `verifyExpectedCount` concatenation pattern.

Storing the post-filter count (rather than the pre-filter total) on `itemCount` is intentional: that field is "tracks landed in the snapshot", which is the actionable number. The pre-filter total is preserved in `errorMessage` for context.

## Data flow (post-change)

```
SyncPreferencesManager.youtubeLikedStudioOnly (Flow<Boolean>)
                                ↓
            PlaylistFetchWorker.fetchAndSnapshotLikedSongs (.first())
                                ↓
              YTMusicApiClient.getLikedSongs() → PagedTracks
                                ↓
              filterStudioOnly(paged.tracks) if pref true
                                ↓
              insertPlaylistSnapshot + insertTrackSnapshots
                                ↓
              SyncStepResult (itemCount = filtered count;
                              errorMessage carries filtered N if applicable)
```

## Error handling

- If `SyncPreferencesManager.youtubeLikedStudioOnly.first()` throws (DataStore corruption — extremely rare), the catch path inside `fetchAndSnapshotLikedSongs` already wraps the whole block in `try/catch`. On exception, log and proceed with `studioOnly = false` (safe default — sync everything).
- If `paged.tracks` is empty after filtering (user has only UGC liked songs and turned the filter on), the existing `paginated.items.isEmpty()` check inside `getPlaylistTracks` doesn't fire because filtering happens *after* the paginate call. Worker explicitly handles the empty-after-filter case: `if (filteredTracks.isEmpty()) { skip insertPlaylistSnapshot, append SyncStepResult with errorMessage = "all N liked songs filtered (studio-only mode)" }`. No phantom playlist row gets written.
- The filter is pure / synchronous — no I/O, no exceptions of its own to handle.

## Files affected

| File | Change |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt` | new key, field, flow, setter |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt` | inject pref read, filter helper, diagnostic note |
| `core/data/src/test/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorkerStudioFilterTest.kt` | new — see Test plan |
| Settings UI module (path TBD; locate where the existing Sync Preferences card lives) | new toggle row |

No schema migration. No new entity columns. No DAO changes.

## Test plan

### Unit: filter helper

A pure-function test in the same module as `PlaylistFetchWorker`. Input: `List<YTMusicTrack>` with one of each `MusicVideoType` (ATV, OMV, OFFICIAL_SOURCE_MUSIC, UGC, PODCAST_EPISODE) plus one `null`. Output: 4-element list with ATV / OMV / OFFICIAL_SOURCE_MUSIC / null preserved in order; UGC + PODCAST_EPISODE removed.

### Worker-level integration test

Mock `YTMusicApiClient` to return a fixed mixed-type `PagedTracks`. Mock `SyncPreferencesManager.youtubeLikedStudioOnly` to emit `true`. Run `fetchAndSnapshotLikedSongs`. Assertions:

1. `remoteSnapshotDao.insertTrackSnapshots` was called with only the studio-typed tracks.
2. The appended `SyncStepResult.errorMessage` contains `"filtered"` and the count of UGC/PODCAST removed.
3. With pref `false`, no filter applied, `errorMessage` does not mention filtering.

### Empty-after-filter edge case

Mock `getLikedSongs()` to return all-UGC tracks; pref `true`. Assertions:

1. `insertPlaylistSnapshot` is **not** called (no playlist row written for an empty result).
2. `SyncStepResult.errorMessage` matches `"all N liked songs filtered (studio-only mode)"`.

### UI test (snapshot or instrumented)

The Sync Preferences card renders the new row with the right copy and the toggle reflects/persists the DataStore value. Match existing patterns for toggle tests in the settings module if they exist; if not, defer to manual verification.

## Open questions / risks

- **`musicVideoType` parser regression risk.** If a future YT Music API change causes the parser to start returning `null` more aggressively, the filter would silently start letting UGC through (since `null` → include). Mitigation: log a warning during sync if the proportion of `null` types in the Liked Songs result exceeds, say, 10% — this is cheap insurance and makes a regression visible. Surface as a `Log.w` in the worker, not a user-facing diagnostic.
- **YT-side reclassification.** YT Music can change a track's `musicVideoType` (e.g. an artist re-uploads a UGC track to their Topic channel). Users whose filter is ON won't see the change until next sync. This matches the user's stated mental model ("re-sync to bring back") so no special handling needed.
- **Settings UI module location.** The exact file/path for the Sync Preferences card needs to be located during implementation; the spec doesn't specify a path because it varies by feature-module structure. The implementation plan will identify it.

## Verification

After implementation, manual verification on device:

1. Start with filter OFF (default), run sync — expect ~1700 Liked Songs (current count).
2. Open Settings → Sync Preferences → YouTube Music → toggle "Studio recordings only" ON.
3. Run Sync Now.
4. Inspect via `adb`/sqlite the count of `remote_track_snapshots` rows under the Liked Songs `RemotePlaylistSnapshotEntity`. Expect a meaningful drop (probably 30-50% reduction depending on the user's mix).
5. Spot-check that filtered tracks are the obvious covers/lives/podcasts (titles will reveal it).
6. Toggle OFF, sync again, verify count returns to the unfiltered total.
