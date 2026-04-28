# YT Music Liked Songs — Studio-Only Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single user preference — *Studio recordings only* — that, when ON, filters UGC and PODCAST_EPISODE tracks out of YT Music Liked Songs at sync time. Default OFF.

**Architecture:** New `youtubeLikedStudioOnly: Boolean` key in `SyncPreferencesManager` (DataStore-backed, defaults to false). `PlaylistFetchWorker.fetchAndSnapshotLikedSongs` reads the value via `.first()` once per sync run and applies a pure filter helper to the `paged.tracks` list before inserting snapshot rows. Diagnostic count of filtered tracks is appended to the existing `SyncStepResult.errorMessage` field (used here as an informational annotation alongside the existing partial-fetch annotation pattern). One toggle row added to the YouTube Sync Preferences card in `SyncScreen.kt`.

**Tech Stack:** Kotlin, Android, Hilt, AndroidX DataStore Preferences, Jetpack Compose, kotlinx.coroutines, JUnit 4 + mockito-kotlin.

**Spec:** `docs/superpowers/specs/2026-04-28-liked-songs-studio-only-filter.md`

---

## Pre-flight

The work continues in the existing `feat/yt-sync-pagination` worktree (`.worktrees/yt-sync-pagination`). This branch is already 17 commits ahead of master with the full pagination overhaul + `LM` endpoint fix shipped. The worker call to `getLikedSongs()` and the `MusicVideoType` enum + per-track classification are already in place — this plan only adds the preference + filter + UI on top.

**All subsequent tasks operate in:** `C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination`. Every Bash command must begin with `cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ...`. Read/Edit/Write tools should use absolute paths rooted at that worktree.

- [ ] Verify clean baseline:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest :data:ytmusic:testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] Quick survey: confirm the surface points are where the plan claims:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -n "fetchAndSnapshotLikedSongs\|val likedSongs = paged.tracks" core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt
```

Expected: `private suspend fun fetchAndSnapshotLikedSongs` near line 589, `val likedSongs = paged.tracks` near line 597.

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -n "youtubeSyncMode\|setYoutubeSyncMode" core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt
```

Expected: matches near lines 88-104 and 142-144.

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -n "youtubeSyncMode\|onYoutubeSyncModeChanged\|SyncModeChipRow" feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt
```

Expected: SyncScreen line ~409-413 has the YouTube SyncModeChipRow; SyncViewModel line ~106 has `youtubeSyncMode` on the UI-state class and line ~280 has `onYoutubeSyncModeChanged`.

If any line numbers have drifted, adjust as you go — the plan is robust to small offsets.

---

## Task 1: Add `youtubeLikedStudioOnly` preference to `SyncPreferencesManager`

**Verified facts:**
- `SyncPreferencesManager` lives at `core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt`. Pattern: a `Keys` private object holding `*PreferencesKey` constants, a `SyncPreferences` data class with default values, a single combined `preferences: Flow<SyncPreferences>`, individual per-pref `Flow`s for workers, and `suspend` setters.
- The `youtubeSyncMode: Flow<SyncMode>` precedent (line ~88) is the exact pattern to mirror — workers want a narrow `Flow` they can `.first()` without resolving the whole `SyncPreferences`.
- Adding a key with `?: false` default is back-compat — existing installs read the missing key as `false`, matching "everything syncs by default."

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt`

- [ ] **Step 1: Add the DataStore key**

Locate the `Keys` private object (around line 55-64). Add the new key alongside the existing booleans:

```kotlin
val YOUTUBE_LIKED_STUDIO_ONLY = booleanPreferencesKey("youtube_liked_studio_only")
```

- [ ] **Step 2: Add the field to `SyncPreferences`**

Locate the `SyncPreferences` data class (lines 29-36). Add the new field at the end (with kdoc matching the existing style):

```kotlin
/**
 * When true, the YouTube Music Liked Songs sync filters out UGC,
 * cover, live, and podcast tracks (anything that isn't ATV / OMV /
 * OFFICIAL_SOURCE_MUSIC). Other YT Music content (Home Mixes, custom
 * user playlists) is unaffected. Defaults to false — everything syncs.
 */
val youtubeLikedStudioOnly: Boolean = false,
```

- [ ] **Step 3: Resolve in the combined `preferences` flow**

Locate the `preferences: Flow<SyncPreferences>` block (lines 67-76). Add the new field to the constructor call:

```kotlin
val preferences: Flow<SyncPreferences> = context.syncPrefsDataStore.data.map { prefs ->
    SyncPreferences(
        syncHour = prefs[Keys.SYNC_HOUR] ?: 6,
        syncMinute = prefs[Keys.SYNC_MINUTE] ?: 0,
        autoSyncEnabled = prefs[Keys.AUTO_SYNC] ?: true,
        wifiOnly = prefs[Keys.WIFI_ONLY] ?: true,
        spotifySyncMode = resolveSpotifyMode(prefs),
        youtubeSyncMode = resolveYoutubeMode(prefs),
        youtubeLikedStudioOnly = prefs[Keys.YOUTUBE_LIKED_STUDIO_ONLY] ?: false,
    )
}
```

- [ ] **Step 4: Add a per-pref `Flow` for workers**

Right below the `youtubeSyncMode: Flow<SyncMode>` declaration (around line 88-89), add:

```kotlin
/**
 * Reactive stream of the YT Music Liked Songs studio-only filter.
 * Read once per sync run via `.first()` inside [PlaylistFetchWorker].
 * Default false: everything syncs.
 */
val youtubeLikedStudioOnly: Flow<Boolean> =
    context.syncPrefsDataStore.data.map { it[Keys.YOUTUBE_LIKED_STUDIO_ONLY] ?: false }
```

- [ ] **Step 5: Add the setter**

Right after `setYoutubeSyncMode` (around line 142-144), add:

```kotlin
/** Persist the YT Music Liked Songs studio-only filter. */
suspend fun setYoutubeLikedStudioOnly(enabled: Boolean) {
    context.syncPrefsDataStore.edit { it[Keys.YOUTUBE_LIKED_STUDIO_ONLY] = enabled }
}
```

- [ ] **Step 6: Compile + run existing tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL. No existing tests should break — this is purely additive.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add core/data/src/main/kotlin/com/stash/core/data/sync/SyncPreferencesManager.kt
git commit -m "feat(sync): add youtubeLikedStudioOnly preference

DataStore-backed boolean toggle. Default false (everything syncs as today).
Plumbed via SyncPreferences data class, narrow Flow for worker reads, and
a setter mirroring the youtubeSyncMode pattern."
```

---

## Task 2: Add `filterStudioOnly` helper + unit test

**Verified facts:**
- `MusicVideoType` enum lives at `data/ytmusic/src/main/kotlin/com/stash/data/ytmusic/model/MusicVideoType.kt`. Values: `ATV`, `OMV`, `UGC`, `OFFICIAL_SOURCE_MUSIC`, `PODCAST_EPISODE`. The parser sets the field on `YTMusicTrack` to one of these or `null` (when the InnerTube renderer omits the field).
- The filter helper is pure / synchronous / no I/O — best place is a top-level `internal fun` in `PlaylistFetchWorker.kt` so the worker's unit test can call it directly without scaffolding a full Hilt graph.
- Spec classification table:
  - Include (filter passes): `ATV`, `OMV`, `OFFICIAL_SOURCE_MUSIC`, `null`
  - Filter out: `UGC`, `PODCAST_EPISODE`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorkerStudioFilterTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `core/data/src/test/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorkerStudioFilterTest.kt`:

```kotlin
package com.stash.core.data.sync.workers

import com.stash.data.ytmusic.model.MusicVideoType
import com.stash.data.ytmusic.model.YTMusicTrack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the pure filter helper that backs the
 * "Studio recordings only" Liked Songs preference.
 *
 * Spec rule: include ATV + OMV + OFFICIAL_SOURCE_MUSIC + null; drop UGC + PODCAST_EPISODE.
 */
class PlaylistFetchWorkerStudioFilterTest {

    private fun track(type: MusicVideoType?, id: String): YTMusicTrack =
        YTMusicTrack(videoId = id, title = "T-$id", artists = "A", musicVideoType = type)

    @Test fun `keeps ATV OMV OFFICIAL_SOURCE_MUSIC and null`() {
        val tracks = listOf(
            track(MusicVideoType.ATV, "atv"),
            track(MusicVideoType.OMV, "omv"),
            track(MusicVideoType.OFFICIAL_SOURCE_MUSIC, "osm"),
            track(null, "null"),
        )
        assertEquals(tracks, filterStudioOnly(tracks))
    }

    @Test fun `removes UGC`() {
        val tracks = listOf(
            track(MusicVideoType.ATV, "atv"),
            track(MusicVideoType.UGC, "ugc"),
            track(MusicVideoType.OMV, "omv"),
        )
        val filtered = filterStudioOnly(tracks)
        assertEquals(listOf("atv", "omv"), filtered.map { it.videoId })
    }

    @Test fun `removes PODCAST_EPISODE`() {
        val tracks = listOf(
            track(MusicVideoType.ATV, "atv"),
            track(MusicVideoType.PODCAST_EPISODE, "pod"),
        )
        assertEquals(listOf("atv"), filterStudioOnly(tracks).map { it.videoId })
    }

    @Test fun `mixed bag drops UGC and PODCAST_EPISODE only`() {
        val tracks = listOf(
            track(MusicVideoType.ATV, "1"),
            track(MusicVideoType.UGC, "2"),
            track(MusicVideoType.OMV, "3"),
            track(MusicVideoType.PODCAST_EPISODE, "4"),
            track(MusicVideoType.OFFICIAL_SOURCE_MUSIC, "5"),
            track(null, "6"),
        )
        val filtered = filterStudioOnly(tracks)
        assertEquals(listOf("1", "3", "5", "6"), filtered.map { it.videoId })
    }

    @Test fun `empty input returns empty`() {
        assertEquals(emptyList<YTMusicTrack>(), filterStudioOnly(emptyList()))
    }
}
```

- [ ] **Step 2: Run the test — expect compile failure**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.PlaylistFetchWorkerStudioFilterTest" --console=plain
```

Expected: compile error — `filterStudioOnly` doesn't exist yet.

- [ ] **Step 3: Add the helper**

Open `core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt`. Add a top-level (file-level, outside the class) `internal fun` near the bottom of the file, after the closing brace of `PlaylistFetchWorker`. Keep it top-level so the test can call it without instantiating the worker:

```kotlin
/**
 * Filters out UGC and PODCAST_EPISODE tracks. Used by the
 * "Studio recordings only" Liked Songs preference.
 *
 * Tracks with [MusicVideoType.ATV], [MusicVideoType.OMV],
 * [MusicVideoType.OFFICIAL_SOURCE_MUSIC], or `null` (parser-couldn't-classify)
 * pass through. Null is preserved deliberately so a parser regression
 * doesn't silently drop legitimate studio tracks.
 */
internal fun filterStudioOnly(tracks: List<YTMusicTrack>): List<YTMusicTrack> =
    tracks.filter {
        it.musicVideoType != MusicVideoType.UGC &&
        it.musicVideoType != MusicVideoType.PODCAST_EPISODE
    }
```

Confirm imports: `MusicVideoType` and `YTMusicTrack` are likely already imported by the worker for other reasons; if Kotlin/IntelliJ complains, add:

```kotlin
import com.stash.data.ytmusic.model.MusicVideoType
import com.stash.data.ytmusic.model.YTMusicTrack
```

- [ ] **Step 4: Run the test — expect PASS**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.PlaylistFetchWorkerStudioFilterTest" --console=plain
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Run the full `:core:data` suite — no regressions**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorkerStudioFilterTest.kt
git commit -m "feat(sync): filterStudioOnly helper for Liked Songs UGC/podcast filter

Pure top-level fun. Drops UGC and PODCAST_EPISODE; preserves
ATV, OMV, OFFICIAL_SOURCE_MUSIC, and null (parser-couldn't-classify).
5 unit tests cover each enum value plus the empty-input case."
```

---

## Task 3: Apply the filter in `fetchAndSnapshotLikedSongs`

**Verified facts:**
- `fetchAndSnapshotLikedSongs` lives at `core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt:589-655`.
- The worker constructor already injects what it needs to read prefs — `syncPreferencesManager: SyncPreferencesManager` is one of the AssistedInject parameters used elsewhere in the class. **Confirm by grepping the constructor before editing**; if it isn't present, add it as a `private val syncPreferencesManager: SyncPreferencesManager` constructor parameter alongside the existing dependencies.
- The body of the `Success` branch (lines 595-640) is where the filter slots in: read pref, filter tracks, regenerate the partial-fetch annotation to include the filter count, branch on empty-after-filter, otherwise insert as before.
- Avoid resolving the pref via `.first()` if `syncPreferencesManager` is missing; verify injection state in step 1.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt`

- [ ] **Step 1: Verify `syncPreferencesManager` is already injected**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -n "syncPreferencesManager\|SyncPreferencesManager" core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt | head
```

If you see a constructor parameter `private val syncPreferencesManager: SyncPreferencesManager`, you're set. If not, add it to the `@AssistedInject constructor(...)` parameter list and add the import:

```kotlin
import com.stash.core.data.sync.SyncPreferencesManager
```

(Hilt resolves it automatically — `SyncPreferencesManager` is `@Singleton`.)

- [ ] **Step 2: Add an import for the new pref Flow read**

If not already present, add at the top of the file:

```kotlin
import kotlinx.coroutines.flow.first
```

- [ ] **Step 3: Replace the body of the `Success` branch**

Find the `is SyncResult.Success ->` block inside `fetchAndSnapshotLikedSongs` (lines ~595-641). Replace the entire `is SyncResult.Success -> { ... }` block with:

```kotlin
is SyncResult.Success -> {
    val paged = result.data
    val rawTracks = paged.tracks
    // Spec: DataStore corruption falls back to "everything syncs" rather than
    // aborting this entire sync step. runCatching keeps that fallback explicit
    // even though the outer try/catch would also handle a thrown exception.
    val studioOnly = runCatching {
        syncPreferencesManager.youtubeLikedStudioOnly.first()
    }.getOrDefault(false)
    val likedSongs = if (studioOnly) filterStudioOnly(rawTracks) else rawTracks
    val filteredCount = rawTracks.size - likedSongs.size

    // Build the annotation: partial-fetch reason + filter note (either, both, or neither).
    val partialNote = if (paged.partial) {
        "partial: ${rawTracks.size}/${paged.expectedCount ?: "?"} — ${paged.partialReason}"
    } else null
    val filterNote = if (studioOnly && filteredCount > 0) {
        "filtered $filteredCount UGC/podcast tracks (studio-only mode)"
    } else null
    val combinedNote = listOfNotNull(partialNote, filterNote).joinToString("; ").ifEmpty { null }

    diagnostics.add(
        SyncStepResult(
            "YOUTUBE",
            "getLikedSongs",
            StepStatus.SUCCESS,
            likedSongs.size,
            errorMessage = combinedNote,
        )
    )
    if (paged.partial) {
        Log.w(TAG, "fetchAndSnapshotLikedSongs: liked songs partial — $partialNote")
    }
    if (filterNote != null) {
        Log.d(TAG, "fetchAndSnapshotLikedSongs: $filterNote")
    }

    // Empty-after-filter: don't write a phantom playlist row.
    if (likedSongs.isEmpty()) {
        Log.d(TAG, "fetchAndSnapshotLikedSongs: all ${rawTracks.size} liked songs filtered (studio-only mode)")
        return  // exits the inner block; the outer try/catch around fetchAndSnapshotLikedSongs is unaffected
    }

    val likedPlaylistId = remoteSnapshotDao.insertPlaylistSnapshot(
        RemotePlaylistSnapshotEntity(
            syncId = syncId,
            source = MusicSource.YOUTUBE,
            sourcePlaylistId = "youtube_liked_songs",
            playlistName = "Liked Songs",
            playlistType = PlaylistType.LIKED_SONGS,
            trackCount = likedSongs.size,
            partial = paged.partial,
            expectedCount = paged.expectedCount,
        )
    )

    val trackSnapshots = likedSongs.mapIndexed { position, track ->
        RemoteTrackSnapshotEntity(
            syncId = syncId,
            snapshotPlaylistId = likedPlaylistId,
            title = track.title,
            artist = track.artists,
            album = track.album,
            durationMs = track.durationMs ?: 0L,
            youtubeId = track.videoId,
            albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(track.thumbnailUrl),
            position = position,
        )
    }
    if (trackSnapshots.isNotEmpty()) {
        remoteSnapshotDao.insertTrackSnapshots(trackSnapshots)
    }
}
```

A few things to double-check after pasting:
- The outer `try { ... } catch (e: Exception) { ... }` (around line 593-654) stays unchanged — `return` in step 3's inner block returns from `fetchAndSnapshotLikedSongs` itself, which is what we want for the empty-after-filter case (skip both `insertPlaylistSnapshot` and the `is SyncResult.Empty` / `is SyncResult.Error` branches that follow).
- Existing `is SyncResult.Empty ->` and `is SyncResult.Error ->` branches stay as-is.
- The original step previously appended a single `SyncStepResult` with just the partial-fetch reason in `errorMessage`; the rewrite combines partial + filter notes via `listOfNotNull(...).joinToString("; ")`, matching the existing concatenation pattern used by `verifyExpectedCount` in `YTMusicApiClient`. If both `paged.partial` is true and the filter was applied, the user sees `"partial: ...; filtered N UGC/podcast tracks (studio-only mode)"`.

- [ ] **Step 4: Compile and re-run all tests**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :core:data:testDebugUnitTest :data:ytmusic:testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL. No existing test should fail because:
- `:data:ytmusic` doesn't depend on `:core:data`'s worker — unaffected.
- `:core:data`'s test suite has no `PlaylistFetchWorker` integration test today (we confirmed during the pagination plan).
- The new `PlaylistFetchWorkerStudioFilterTest` from Task 2 still passes (it only tests the helper, not the worker integration).

- [ ] **Step 5: Verify the full app still assembles**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt
git commit -m "feat(sync): wire studio-only filter into fetchAndSnapshotLikedSongs

Reads SyncPreferencesManager.youtubeLikedStudioOnly via .first() once
per sync run. When ON, applies filterStudioOnly to paged.tracks before
inserting snapshots. Diagnostic count of filtered tracks appended to
SyncStepResult.errorMessage alongside any partial-fetch reason
(';'-separated). Empty-after-filter skips the playlist snapshot
insertion entirely so no phantom row lands in the DB."
```

---

## Task 4: Add `youtubeLikedStudioOnly` to `SyncViewModel`

**Verified facts:**
- `SyncViewModel` lives at `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt`. The UI-state class `SyncUiState` (around lines 90-115) already carries `youtubeSyncMode: SyncMode = SyncMode.REFRESH`. The pattern: add field to UI state, add setter on the ViewModel, extend the existing `observeSyncMode()` (or rename to `observeSyncPreferences`) to wire the new pref into state.
- The setter pattern at `onYoutubeSyncModeChanged` (line ~280-282) is the exact pattern to mirror for `onYoutubeLikedStudioOnlyChanged`.

**Files:**
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt`

- [ ] **Step 1: Add the field to `SyncUiState`**

Locate `SyncUiState` (around lines 90-115). After the `youtubeSyncMode` field (line ~106), add:

```kotlin
/**
 * When true, the YT Music Liked Songs sync filters out UGC, cover,
 * live, and podcast tracks. Other YT content is unaffected. Default false.
 */
val youtubeLikedStudioOnly: Boolean = false,
```

- [ ] **Step 2: Add the setter on `SyncViewModel`**

Locate `onYoutubeSyncModeChanged` (around line 280-282). After it, add:

```kotlin
/** Persists the user's choice for the studio-only Liked Songs filter. */
fun onYoutubeLikedStudioOnlyChanged(enabled: Boolean) {
    viewModelScope.launch {
        syncPreferencesManager.setYoutubeLikedStudioOnly(enabled)
    }
}
```

If `viewModelScope` and `launch` aren't already imported, add:

```kotlin
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
```

(They likely already are — used by `onYoutubeSyncModeChanged`.)

- [ ] **Step 3: Observe the new pref**

Locate `observeSyncMode()` (around line 312). Inside the function, after the existing `syncPreferencesManager.youtubeSyncMode.collect` block, add a parallel collect block for the new pref:

```kotlin
viewModelScope.launch {
    syncPreferencesManager.youtubeLikedStudioOnly.collect { enabled ->
        _uiState.update { it.copy(youtubeLikedStudioOnly = enabled) }
    }
}
```

(Match the exact pattern used by the existing collects — if they use a different state-update mechanism, mirror that.)

- [ ] **Step 4: Compile**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :feature:sync:compileDebugKotlin --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt
git commit -m "feat(sync): expose youtubeLikedStudioOnly on SyncViewModel

Mirrors the youtubeSyncMode pattern: field on SyncUiState, setter
delegating to SyncPreferencesManager, observer in observeSyncMode."
```

---

## Task 5: Add the toggle row to `SyncScreen`

**Verified facts:**
- `SyncScreen` lives at `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt`. The YouTube sync card's expanded body is around lines 406-413, where `SyncModeChipRow(mode = uiState.youtubeSyncMode, ...)` renders. The new toggle row goes immediately below that chip row (after the `Spacer` on line ~414), before the playlist filter (`val ytLiked = ...` on line ~416).
- Stash uses Material 3 with the project's `GlassCard`/extended theme (per `feedback_stash_design_system.md`). The toggle row should match existing toggle/switch patterns in the same file. Look for any existing `Switch` or `Checkbox` row in `SyncScreen.kt` and mirror its style; if none exists, use a `Row` with `Text` + Material 3 `Switch`.

**Files:**
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt`

- [ ] **Step 1: Find an existing toggle pattern to copy**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -n "Switch\|Checkbox" feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt
```

If a `Switch(...)` row exists in the same file, copy its layout (colors, padding, text style) into the new row — do not invent a new pattern. If `Switch` is absent from this file, fall back to:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -rn "androidx.compose.material3.Switch" feature --include="*.kt" | head -5
```

Use the closest in-project example as your reference.

- [ ] **Step 2: Insert the toggle row in the YouTube card**

Around line 414 (right after the `Spacer(modifier = Modifier.height(8.dp))` that follows the YT `SyncModeChipRow` and before `val ytLiked = uiState.youTubePlaylists.filter { ... }`), insert:

```kotlin
StudioOnlyToggleRow(
    enabled = uiState.youtubeLikedStudioOnly,
    onChange = viewModel::onYoutubeLikedStudioOnlyChanged,
    accent = accent,
)
Spacer(modifier = Modifier.height(8.dp))
```

Then define the `@Composable` `StudioOnlyToggleRow` near the bottom of the file, alongside `SyncModeChipRow` (around line 1205). Adapt the design to whatever toggle pattern Step 1 surfaced. A safe default that uses Material 3 directly:

```kotlin
@Composable
private fun StudioOnlyToggleRow(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!enabled) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Studio recordings only",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Excludes covers, live recordings, and UGC uploads from your Liked Songs. Other YouTube playlists are unaffected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = accent),
        )
    }
}
```

Add any missing imports — likely:

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
```

- [ ] **Step 3: Compile**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :feature:sync:compileDebugKotlin --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Assemble the full app**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt
git commit -m "feat(sync): Studio recordings only toggle in YT Sync card

Material 3 Switch row beneath the YouTube SyncModeChipRow. Bound to
SyncViewModel.youtubeLikedStudioOnly. Help text explains the scope is
Liked Songs only."
```

---

## Task 6: Verify `SyncStepResult.errorMessage` rendering

**Verified facts:**
- The spec advisory: `errorMessage` is currently surfaced in the Sync History detail screen. The new filter annotation is informational, not an error — the implementation must verify the existing renderer does not present it with red/error styling.

**Files:**
- Read-only inspection: wherever `SyncStepResult.errorMessage` is rendered.

- [ ] **Step 1: Locate the renderer**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && grep -rn "SyncStepResult\|stepResult.errorMessage\|step.errorMessage" --include="*.kt" feature app | head -15
```

- [ ] **Step 2: Read the rendering site(s)**

For each file the grep surfaces, read the relevant block. Specifically verify: when `step.status == StepStatus.SUCCESS` and `step.errorMessage != null`, is the message styled red/error? Or muted/informational?

- [ ] **Step 3: Decide**

Three outcomes:
1. **Already informational on success rows.** No change needed. Skip ahead.
2. **Always rendered as error styling regardless of status.** Acceptable if the impact is minor (the user sees a slightly red "filtered N tracks" — informational, not actually broken). Document the observation as a follow-up; do not block this task.
3. **Always error-styled AND visually problematic.** Add a separate optional `infoMessage: String?` field to `SyncStepResult` (model file `core/model/src/main/kotlin/com/stash/core/model/SyncStepResult.kt`) and route the filter annotation through it. Update the Task 3 worker code to use `infoMessage` for the filter annotation (keep partial-fetch on `errorMessage` for back-compat). Update the renderer to display `infoMessage` with neutral styling.

The default expectation is outcome 1 or 2 — a one-paragraph note in the commit message captures whichever you observed. If it's outcome 3, this becomes a real change with its own commit (described in Step 4).

- [ ] **Step 4: If outcome 3 only — refactor**

Skip if 1 or 2. If you genuinely need the split:

1. Add `val infoMessage: String? = null` to the `SyncStepResult` data class. Default null preserves all existing callers.
2. In Task 3's `Success` branch in `PlaylistFetchWorker`, route `partialNote` to `errorMessage` (existing behavior — partial fetches *are* a soft error) and route `filterNote` to a new `infoMessage` parameter on the `SyncStepResult` constructor.
3. Update the renderer to also display `infoMessage` with neutral styling (e.g., `MaterialTheme.colorScheme.onSurfaceVariant`).
4. Re-run `:app:assembleDebug`.

- [ ] **Step 5: Commit only if changes were made**

If outcome 1 or 2 — no commit, just include the observation in the Task 7 manual-verification notes.
If outcome 3 — commit:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
git add core/model/src/main/kotlin/com/stash/core/model/SyncStepResult.kt \
        core/data/src/main/kotlin/com/stash/core/data/sync/workers/PlaylistFetchWorker.kt \
        feature/<wherever-renderer-is>
git commit -m "feat(sync): split filter annotation onto SyncStepResult.infoMessage

The filter-N-tracks note is informational, not an error. Adding a
separate field (default null, back-compat) and routing it through the
neutral-styled renderer prevents the toggle's filter count from
showing as an error in Sync History."
```

---

## Task 7: Manual on-device verification

**Verified facts:**
- The implementation is testable only end-to-end on a real account because the filter behavior depends on the user's actual Liked Songs mix. Spec verification section gives the exact steps.

**Files:** none (manual).

- [ ] **Step 1: Build and install**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination && ./gradlew :app:installDebug --console=plain
```

Expected: BUILD SUCCESSFUL, "Installed on 1 device."

Memory `feedback_install_after_fix.md`: always `:app:installDebug` after a fix on this project — compile-pass isn't enough.

- [ ] **Step 2: Capture the unfiltered baseline**

Open the Stash debug build. Settings → Sync Preferences → YouTube Music — confirm "Studio recordings only" is OFF. Hit Sync Now. After completion, pull the DB and read the Liked Songs row count:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/yt-sync-pagination
mkdir -p /tmp/stashdb && adb exec-out run-as com.stash.app.debug cat databases/stash.db > /tmp/stashdb/stash.db
sqlite3 /tmp/stashdb/stash.db "SELECT id, track_count FROM remote_playlist_snapshots WHERE source_playlist_id='youtube_liked_songs' AND sync_id=(SELECT MAX(id) FROM sync_history);"
```

Record this number — it's the "everything syncs" baseline (~1700 expected for this developer's library; will differ per user).

- [ ] **Step 3: Flip the toggle, re-sync**

Settings → Sync Preferences → YouTube Music → toggle "Studio recordings only" ON. Hit Sync Now.

- [ ] **Step 4: Verify the filter ran**

```bash
adb logcat -d -s StashSync 2>&1 | grep "studio-only mode"
```

Expected: a `Log.d` line like `fetchAndSnapshotLikedSongs: filtered N UGC/podcast tracks (studio-only mode)` where N > 0 (assuming the user's library has any UGC/podcasts).

If N == 0 or no log line appears at all: the user's library may be 100% studio. Spot-check a few tracks in the YT Music app — if the user has any covers/lives explicitly (search Liked Songs for "live" or "cover"), the filter should have caught them. If not, the test is inconclusive but not failing.

- [ ] **Step 5: Verify the DB reflects the filtered count**

```bash
adb exec-out run-as com.stash.app.debug cat databases/stash.db > /tmp/stashdb/stash.db
sqlite3 /tmp/stashdb/stash.db "SELECT id, track_count FROM remote_playlist_snapshots WHERE source_playlist_id='youtube_liked_songs' AND sync_id=(SELECT MAX(id) FROM sync_history);"
```

Expected: a smaller number than Step 2.

```bash
sqlite3 /tmp/stashdb/stash.db "SELECT title, artist FROM remote_track_snapshots WHERE snapshot_playlist_id=(SELECT id FROM remote_playlist_snapshots WHERE source_playlist_id='youtube_liked_songs' AND sync_id=(SELECT MAX(id) FROM sync_history)) LIMIT 30;"
```

Spot-check: titles should look like official releases (no "(Cover)", "(Live)", "(Tribute)", "(feat. random YouTuber)").

- [ ] **Step 6: Verify the empty-after-filter edge case (best-effort)**

Only doable if you can construct an all-UGC test account, which most users can't. Skip if not applicable; the unit test in Task 2 covers the helper logic directly.

- [ ] **Step 7: Toggle OFF, re-sync, verify count returns**

Toggle "Studio recordings only" OFF. Hit Sync Now. Re-run Step 2's SQL. Expected: count returns to (or near) the baseline from Step 2.

---

## Closing

After Task 7 passes, the branch `feat/yt-sync-pagination` carries:
- The full pagination overhaul (commits `0030379..afdc679`)
- The LM endpoint fix (Fix C)
- The studio-only filter (Tasks 1-5 of this plan, plus optional Task 6 if outcome 3 fires)

Use `superpowers:finishing-a-development-branch` to merge to master, or merge manually if you have a preferred squash/rebase strategy.
