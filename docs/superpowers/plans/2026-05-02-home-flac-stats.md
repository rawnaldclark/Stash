# Home Sync Card — FLAC Count + Storage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add FLAC track count + FLAC storage as inline sub-text under the Tracks and Storage `StatItem`s on the Home sync card. Numbers come from new Room aggregate queries filtered on `file_format = 'flac'`.

**Architecture:** Pure additive change across three layers. Two new DAO queries (`getFlacCount`, `getFlacStorageBytes`) → two new repository methods (one-line passthroughs) → `HomeViewModel.sourceCountsFlow` expanded from a 2-flow Pair to a 4-flow `SourceCounts` data class → two new `SyncStatusInfo` fields → `StatItem` accepts an optional `subValue` parameter and renders the FLAC sub-text only when the values are > 0.

**Tech Stack:** Kotlin, Android, Room (existing DB), Hilt-injected `MusicRepository`, Jetpack Compose Material3, kotlinx-coroutines `Flow.combine`.

**Spec:** `docs/superpowers/specs/2026-05-02-home-flac-stats-design.md`

---

## Pre-flight

This work piggybacks on the in-progress `feat/squid-webview-captcha` branch (lossless feature is what makes FLAC tracks exist). **No worktree** — single-feature change layered on the captcha branch.

- [ ] **Confirm branch + clean-enough state**

```bash
cd C:/Users/theno/Projects/MP3APK
git branch --show-current
git status --short feature/home/ core/data/
```

Expected:
- Current branch: `feat/squid-webview-captcha`
- `feature/home/` and `core/data/` may show pre-existing dirty files from in-progress captcha work. **Do not touch those.** This plan only modifies the specific files listed in each task.

- [ ] **Confirm spec is committed**

```bash
cd C:/Users/theno/Projects/MP3APK && git log --oneline -5 docs/superpowers/specs/2026-05-02-home-flac-stats-design.md
```

Expected: at least the commits `4ef6506` (initial spec) and `c9a2e6b` (case-sensitivity note).

---

## Task 1: DAO + repository plumbing

Two new Flow-returning queries on `TrackDao`, exposed through `MusicRepository`. The existing project doesn't unit-test `TrackDao` queries (no `TrackDaoTest.kt` exists in `core/data/src/test/` or `androidTest/`), so this task ships without unit tests, matching the existing convention. The SQL is one-line variants of the existing aggregate patterns at `TrackDao.kt:444` and `:448` — visual code review catches any typo, and Task 4's device acceptance verifies the numbers.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt`

- [ ] **Step 1: Add the DAO queries**

Open `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`. Find the existing `getTotalStorageBytes()` query at line 448. Insert these two new queries directly after it (before the `getSpotifyDownloadedCount()` query at line 451):

```kotlin
/** Count of downloaded FLAC tracks (reactive). */
@Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1 AND file_format = 'flac'")
fun getFlacCount(): Flow<Int>

/** Sum of file sizes (bytes) for downloaded FLAC tracks (reactive). */
@Query("SELECT COALESCE(SUM(file_size_bytes), 0) FROM tracks WHERE is_downloaded = 1 AND file_format = 'flac'")
fun getFlacStorageBytes(): Flow<Long>
```

The exact SQL: `is_downloaded = 1` matches every other aggregate in the file; `file_format = 'flac'` matches the lowercase string convention used at `TrackDao.kt:357`; `COALESCE(SUM, 0)` handles the empty-result case so the consumer sees `0L` not `null`.

- [ ] **Step 2: Add the interface methods**

Open `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt`. Find the existing `getTotalStorageBytes(): Flow<Long>` declaration at line 74. Add two new methods directly after it (preserving the same KDoc tone as adjacent methods):

```kotlin
/** Count of downloaded FLAC tracks (reactive). */
fun getFlacTrackCount(): Flow<Int>

/** Sum of file sizes (bytes) for downloaded FLAC tracks (reactive). */
fun getFlacStorageBytes(): Flow<Long>
```

- [ ] **Step 3: Add the implementation passthroughs**

Open `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt`. Find the existing `override fun getTotalStorageBytes()` at line 186. Insert two new overrides directly after it (before `getSpotifyDownloadedCount` at line 189):

```kotlin
override fun getFlacTrackCount(): Flow<Int> =
    trackDao.getFlacCount()

override fun getFlacStorageBytes(): Flow<Long> =
    trackDao.getFlacStorageBytes()
```

- [ ] **Step 4: Build the data module to verify compile**

```bash
cd C:/Users/theno/Projects/MP3APK && ./gradlew :core:data:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the existing data module tests for regression check**

```bash
cd C:/Users/theno/Projects/MP3APK && ./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. The pre-existing baseline test failures (e.g. `YtLibraryCanonicalizerTest` per memory) are tolerated; nothing **new** should fail.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt \
        core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt && \
git commit -m "feat(data): TrackDao FLAC count + storage queries"
```

---

## Task 2: ViewModel + UiState

Expand `HomeUiState.SyncStatusInfo` with two FLAC fields, then re-bundle `HomeViewModel.sourceCountsFlow` from a 2-flow `Pair` into a 4-flow `SourceCounts` data class so the new repository flows reach the UI state. The top-level `combine(...)` at `HomeViewModel.kt:134` keeps its 5-input arity (per the existing comment at `HomeViewModel.kt:69-71` warning about typed combine arity).

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`

- [ ] **Step 1: Add FLAC fields to `SyncStatusInfo`**

Open `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt`. Find the `SyncStatusInfo` data class at line 110-121. Insert two new fields between `storageUsedBytes` and `state`:

```kotlin
data class SyncStatusInfo(
    val lastSyncTime: Long? = null,
    val nextSyncTime: Long? = null,
    val totalTracks: Int = 0,
    val spotifyTracks: Int = 0,
    val youTubeTracks: Int = 0,
    val totalPlaylists: Int = 0,
    val storageUsedBytes: Long = 0,
    /** Count of downloaded FLAC tracks. Subset of [totalTracks]. */
    val flacTracks: Int = 0,
    /** Sum of file sizes for downloaded FLAC tracks. Subset of [storageUsedBytes]. */
    val flacStorageBytes: Long = 0,
    val state: SyncState = SyncState.IDLE,
    val displayStatus: SyncDisplayStatus = SyncDisplayStatus.Idle,
)
```

The default `0` / `0L` values mean any existing code that constructs `SyncStatusInfo()` without setting these stays compiling.

- [ ] **Step 2: Replace `Pair` with `SourceCounts` in HomeViewModel**

Open `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`. Find `sourceCountsFlow` at line 81-84. Currently:

```kotlin
private val sourceCountsFlow = combine(
    musicRepository.getSpotifyDownloadedCount(),
    musicRepository.getYouTubeDownloadedCount(),
) { spotify, youtube -> Pair(spotify, youtube) }
```

Replace this block with the new 4-flow combine + `SourceCounts` data class:

```kotlin
private val sourceCountsFlow = combine(
    musicRepository.getSpotifyDownloadedCount(),
    musicRepository.getYouTubeDownloadedCount(),
    musicRepository.getFlacTrackCount(),
    musicRepository.getFlacStorageBytes(),
) { spotify, youtube, flac, flacBytes ->
    SourceCounts(
        spotify = spotify,
        youtube = youtube,
        flac = flac,
        flacBytes = flacBytes,
    )
}
```

Then add the `SourceCounts` data class definition. Find the existing `private data class MusicData(...)` declaration (around line 418) and add the new class right next to it:

```kotlin
/**
 * Bundled counts/sizes that flow into [HomeUiState.syncStatus]. Pre-computed
 * here so the top-level uiState combine stays at ≤5 inputs (the typed
 * [combine] arity ceiling — see comment on [musicDataFlow]).
 */
private data class SourceCounts(
    val spotify: Int,
    val youtube: Int,
    val flac: Int,
    val flacBytes: Long,
)
```

- [ ] **Step 3: Update the top-level combine consumer**

In the same file, find the `HomeUiState(syncStatus = syncStatus.copy(...))` block at lines 169-176. Currently:

```kotlin
syncStatus = syncStatus.copy(
    totalTracks = musicData.trackCount,
    spotifyTracks = sourceCounts.first,
    youTubeTracks = sourceCounts.second,
    totalPlaylists = musicData.playlists.size,
    storageUsedBytes = musicData.storageBytes,
),
```

Replace with the named-property accesses plus the two new FLAC fields:

```kotlin
syncStatus = syncStatus.copy(
    totalTracks = musicData.trackCount,
    spotifyTracks = sourceCounts.spotify,
    youTubeTracks = sourceCounts.youtube,
    totalPlaylists = musicData.playlists.size,
    storageUsedBytes = musicData.storageBytes,
    flacTracks = sourceCounts.flac,
    flacStorageBytes = sourceCounts.flacBytes,
),
```

(`sourceCounts.first` → `sourceCounts.spotify`; `sourceCounts.second` → `sourceCounts.youtube`. These are the only consumer references — the `Pair` is gone.)

- [ ] **Step 4: Build the home module + run any existing tests**

```bash
cd C:/Users/theno/Projects/MP3APK && ./gradlew :feature:home:assembleDebug :feature:home:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. If `feature:home` has no test sources, `:testDebugUnitTest` will report `NO-SOURCE` — that's fine.

- [ ] **Step 5: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt && \
git commit -m "feat(home): wire FLAC count + storage into SyncStatusInfo"
```

---

## Task 3: HomeScreen UI

`StatItem` gets an optional `subValue: String?` parameter; the sync card's Tracks and Storage cells pass FLAC sub-text when the values are > 0.

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt`

- [ ] **Step 1: Extend the `StatItem` composable**

Open `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt`. Find the `StatItem` composable at line 693-706. Replace its signature and body with:

```kotlin
@Composable
private fun StatItem(label: String, value: String, subValue: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (subValue != null) {
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
```

The `if (subValue != null)` block is what gates rendering. Stat items called without `subValue` (Spotify, YouTube, and any other future caller) keep their exact current layout because the parameter defaults to `null`.

- [ ] **Step 2: Wire FLAC sub-text on Tracks + Storage**

In the same file, find the `Row` of four `StatItem`s at lines 608-628. Update the Tracks (line 612-615) and Storage (line 624-627) calls to pass the new `subValue`:

```kotlin
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    StatItem(
        label = "Tracks",
        value = syncStatus.totalTracks.toString(),
        subValue = if (syncStatus.flacTracks > 0) "${syncStatus.flacTracks} FLAC" else null,
    )
    StatItem(
        label = "Spotify",
        value = syncStatus.spotifyTracks.toString(),
    )
    StatItem(
        label = "YouTube",
        value = syncStatus.youTubeTracks.toString(),
    )
    StatItem(
        label = "Storage",
        value = formatBytes(syncStatus.storageUsedBytes),
        subValue = if (syncStatus.flacStorageBytes > 0) "${formatBytes(syncStatus.flacStorageBytes)} FLAC" else null,
    )
}
```

The `formatBytes` helper is already in scope (it's defined later in the same file around line 1710 and used at line 626).

- [ ] **Step 3: Build the app**

```bash
cd C:/Users/theno/Projects/MP3APK && ./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt && \
git commit -m "feat(home): show FLAC count + storage as inline sub-text on sync card"
```

---

## Task 4: Device acceptance

Memory `feedback_install_after_fix.md`: compile-pass isn't enough — install and verify on device.

- [ ] **Step 1: Install on device**

```bash
cd C:/Users/theno/Projects/MP3APK && ./gradlew :app:installDebug
```

Expected: `Installed on 1 device.`

- [ ] **Step 2: Run the manual acceptance flow**

Open the app → Home tab → scroll to the sync status card.

1. **Pre-FLAC state (no FLAC tracks downloaded yet):** the sync card shows the existing 4-column layout: Tracks, Spotify, YouTube, Storage. **No** sub-text below Tracks or Storage. Visual height matches the previous build exactly.

2. **After downloading at least one FLAC track** (turn on Lossless in Settings, find a track that has a Qobuz match, download it):
   - Below the Tracks count, a new line reads `"X FLAC"` (where X is the count of FLAC tracks).
   - Below the Storage size, a new line reads `"Y.Y GB FLAC"` (or MB depending on size).
   - The Spotify and YouTube columns remain unchanged (no sub-text).

3. **Reactivity check:** with at least one FLAC track present, delete that track from the library. The FLAC count and FLAC storage decrement on the sync card without manually refreshing the screen. (Compose recomposition driven by the Room Flow.)

4. **Zero-state regression:** delete every FLAC track. The sub-text disappears entirely; the card returns to the pre-FLAC layout from step 1.

5. **Format-string sanity:** if the FLAC storage is, say, 8.5 GB, the sub-text reads `"8.5 GB FLAC"` — same `formatBytes` precision as the parent Storage value. If it's 250 MB, the sub-text reads `"250 MB FLAC"`.

6. **Number-of-rows sanity:** with FLAC active, the sync card grows by one line of `labelSmall` typography (~14sp), and the four columns retain their balance. No layout overflow on a Pixel 6 Pro width (≈360dp content).

- [ ] **Step 3: If anything looks wrong**

Run `adb logcat -d -v time | grep -iE "stash|flac|home" | tail -100` to inspect.

If the FLAC count is wrong (over- or under-counts vs. what you expect), inspect the DB directly:

```bash
adb shell "run-as com.stash.app.debug sqlite3 /data/data/com.stash.app.debug/databases/stash-db 'SELECT file_format, COUNT(*), SUM(file_size_bytes) FROM tracks WHERE is_downloaded=1 GROUP BY file_format'"
```

(Adjust DB filename if it differs.) Compare the SQL output against the UI to localize whether the bug is in the query or the wiring.

- [ ] **Step 4: Empty commit for the record (optional)**

```bash
cd C:/Users/theno/Projects/MP3APK && \
git commit --allow-empty -m "test: device acceptance — Home FLAC count + storage display"
```

Skip if you'd rather keep the history flat. Useful only as a `git log` checkpoint marker.

---

## Skills reference

- @superpowers:verification-before-completion — before claiming done; do not skip Task 4's device install + manual flow

## Risks / rollback

- **Rollback:** revert the three feature commits in reverse order (UI → VM → DAO). DataStore + downloaded files unaffected — this was a pure read-side display change.
- **Risk: SQL filter case-mismatch.** Spec §Risks calls this out. If a writer ever inserts `'FLAC'` (uppercase), the filter misses. Verified by reading `TrackDao.setFormatAndQuality` callers that the lossless pipeline writes `"flac"` (lowercase). If a regression reports zero FLAC despite known FLAC tracks, swap the DAO predicate to `LOWER(file_format) = 'flac'` as a quick fix.
- **Risk: undercount due to legacy `'opus'` defaults.** Spec §Accuracy calls this out — pre-feature tracks default to `file_format = 'opus'`. New downloads are correct; legacy tracks may be misidentified. Out of scope to fix here. If the user reports this, propose a one-time scan/fixup spec separately.
- **Risk: layout overflow on small screens.** The new sub-text adds one `labelSmall` line under Tracks + Storage. Manual verification on a Pixel 6 Pro (Task 4 step 6); if it overflows on smaller widths, switch to a single-column FLAC summary line below the row.
- **Risk: Compose recomposition cost from now-bigger `sourceCountsFlow`.** Negligible — the flow re-emits only when underlying Room queries change, and the resulting `SourceCounts` is a tiny data class.
