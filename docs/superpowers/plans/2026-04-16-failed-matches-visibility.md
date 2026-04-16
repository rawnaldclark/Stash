# Failed Matches Visibility — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface matching failures to users via a Sync tab card + detail screen, with permanent dismissal and auto-reconciliation on next sync.

**Architecture:** Add `DownloadFailureType` enum and `failureType` column to `DownloadQueueEntity` to categorize failures type-safely. Add `matchDismissed` to `TrackEntity` for permanent dismissal that survives re-syncs. On dismiss, delete the queue entry to prevent retry paths from resurrecting it. Auto-reconciliation in `DiffWorker` links manually-downloaded tracks to playlists using canonical title/artist matching.

**Tech Stack:** Room (migrations, DAOs, TypeConverters), Hilt ViewModels, Jetpack Compose, Compose Navigation

**Spec:** `docs/superpowers/specs/2026-04-16-failed-matches-visibility-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `core/model/.../DownloadFailureType.kt` | **Create** | Enum: NONE, NO_MATCH, DOWNLOAD_ERROR |
| `core/data/.../db/converter/Converters.kt` | Modify | Add DownloadFailureType TypeConverter |
| `core/data/.../db/entity/DownloadQueueEntity.kt` | Modify | Add `failureType` column |
| `core/data/.../db/entity/TrackEntity.kt` | Modify | Add `matchDismissed` column |
| `core/model/.../Track.kt` | Modify | Add `matchDismissed` field |
| `core/data/.../mapper/TrackMapper.kt` | Modify | Map `matchDismissed` in both directions |
| `core/data/.../db/StashDatabase.kt` | Modify | Version 4→5, add MIGRATION_4_5 |
| `core/data/.../db/dao/DownloadQueueDao.kt` | Modify | Extend `updateStatus`, add queries, fix `getUnqueuedTrackIds` |
| `core/data/.../db/dao/TrackDao.kt` | Modify | Add `dismissMatch` query |
| `core/data/.../sync/workers/TrackDownloadWorker.kt` | Modify | Set `failureType` on outcomes |
| `core/data/.../sync/workers/DiffWorker.kt` | Modify | Guard dismissed tracks, auto-reconciliation |
| `core/data/.../repository/MusicRepository.kt` | Modify | Add unmatched query methods |
| `core/data/.../repository/MusicRepositoryImpl.kt` | Modify | Implement unmatched query methods |
| `feature/sync/.../FailedMatchesViewModel.kt` | **Create** | Load unmatched tracks, dismiss logic |
| `feature/sync/.../FailedMatchesScreen.kt` | **Create** | Detail screen with track list + dismiss |
| `feature/sync/.../SyncViewModel.kt` | Modify | Add unmatchedCount to SyncUiState |
| `feature/sync/.../SyncScreen.kt` | Modify | Add warning card |
| `app/.../navigation/TopLevelDestination.kt` | Modify | Add FailedMatchesRoute |
| `app/.../navigation/StashNavHost.kt` | Modify | Wire route + SyncScreen callback |

---

### Task 1: Data layer — enum, entity columns, migration, TypeConverter

**Files:**
- Create: `core/model/src/main/kotlin/com/stash/core/model/DownloadFailureType.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/converter/Converters.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/DownloadQueueEntity.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt`
- Modify: `core/model/src/main/kotlin/com/stash/core/model/Track.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`

- [ ] **Step 1: Create DownloadFailureType enum**

```kotlin
package com.stash.core.model

enum class DownloadFailureType {
    NONE,
    NO_MATCH,
    DOWNLOAD_ERROR,
}
```

- [ ] **Step 2: Add TypeConverter to Converters.kt**

Add after the existing `DownloadStatus` converter (line ~71):

```kotlin
// ── DownloadFailureType ──────────────────────────────────────────
@TypeConverter
fun downloadFailureTypeToString(value: DownloadFailureType?): String? = value?.name

@TypeConverter
fun stringToDownloadFailureType(value: String?): DownloadFailureType? =
    value?.let { DownloadFailureType.valueOf(it) }
```

- [ ] **Step 3: Add `failureType` to DownloadQueueEntity**

Add after `errorMessage` field (line ~58):

```kotlin
@ColumnInfo(name = "failure_type")
val failureType: DownloadFailureType = DownloadFailureType.NONE,
```

- [ ] **Step 4: Add `matchDismissed` to TrackEntity**

Add after `matchConfidence` field (line ~90):

```kotlin
@ColumnInfo(name = "match_dismissed")
val matchDismissed: Boolean = false,
```

- [ ] **Step 5: Add `matchDismissed` to domain Track model**

Add after `matchConfidence` field (line ~20 in Track.kt):

```kotlin
val matchDismissed: Boolean = false,
```

- [ ] **Step 6: Update TrackMapper**

In `TrackEntity.toDomain()` (after line 32), add:
```kotlin
matchDismissed = matchDismissed,
```

In `Track.toEntity()` (after line 59), add:
```kotlin
matchDismissed = matchDismissed,
```

- [ ] **Step 7: Add MIGRATION_4_5 to StashDatabase**

Bump version from 4 to 5. Add migration:

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download_queue ADD COLUMN failure_type TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE tracks ADD COLUMN match_dismissed INTEGER NOT NULL DEFAULT 0")
    }
}
```

Register in the database builder's `addMigrations()` call.

- [ ] **Step 8: Commit**

```bash
git add core/model/src/main/kotlin/com/stash/core/model/DownloadFailureType.kt core/data/src/main/kotlin/com/stash/core/data/db/converter/Converters.kt core/data/src/main/kotlin/com/stash/core/data/db/entity/DownloadQueueEntity.kt core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt core/model/src/main/kotlin/com/stash/core/model/Track.kt core/data/src/main/kotlin/com/stash/core/data/mapper/TrackMapper.kt core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt
git commit -m "feat: add DownloadFailureType enum, matchDismissed field, and DB migration 4→5"
```

---

### Task 2: DAO queries — extend updateStatus, add unmatched queries, fix getUnqueuedTrackIds

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`

- [ ] **Step 1: Extend `updateStatus` to include `failureType`**

Replace the existing `updateStatus` query (lines 96-110) with:

```kotlin
@Query("""
    UPDATE download_queue
    SET status = :status,
        error_message = :errorMessage,
        completed_at = :completedAt,
        failure_type = :failureType
    WHERE id = :id
""")
suspend fun updateStatus(
    id: Long,
    status: DownloadStatus,
    errorMessage: String? = null,
    completedAt: Long? = null,
    failureType: DownloadFailureType = DownloadFailureType.NONE,
)
```

The `failureType` param has a default so existing Success/other call sites don't need changes.

- [ ] **Step 2: Add unmatched track queries to DownloadQueueDao**

```kotlin
/** Unmatched tracks for the Failed Matches detail screen. */
@Query("""
    SELECT dq.id, dq.track_id AS trackId, t.title, t.artist,
           t.album_art_url AS albumArtUrl, dq.created_at AS createdAt
    FROM download_queue dq
    INNER JOIN tracks t ON dq.track_id = t.id
    WHERE dq.failure_type = 'NO_MATCH'
      AND dq.status = 'FAILED'
      AND t.match_dismissed = 0
    ORDER BY dq.created_at DESC
""")
fun getUnmatchedTracks(): Flow<List<UnmatchedTrackView>>

/** Count of unmatched tracks for the Sync tab card. */
@Query("""
    SELECT COUNT(*)
    FROM download_queue dq
    INNER JOIN tracks t ON dq.track_id = t.id
    WHERE dq.failure_type = 'NO_MATCH'
      AND dq.status = 'FAILED'
      AND t.match_dismissed = 0
""")
fun getUnmatchedCount(): Flow<Int>

/** Delete all queue entries for a track (used on dismiss). */
@Query("DELETE FROM download_queue WHERE track_id = :trackId")
suspend fun deleteByTrackId(trackId: Long)
```

Add the projection data class (in the same file or a separate file):

```kotlin
data class UnmatchedTrackView(
    val id: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val createdAt: Long,
)
```

- [ ] **Step 3: Fix `getUnqueuedTrackIds` to exclude dismissed tracks**

Update the query (lines 178-187) to add `AND t.match_dismissed = 0`:

```kotlin
@Query("""
    SELECT t.id FROM tracks t
    WHERE t.is_downloaded = 0
      AND t.match_dismissed = 0
      AND t.source IN (:sources)
      AND t.id NOT IN (
          SELECT dq.track_id FROM download_queue dq
          WHERE dq.status IN ('PENDING', 'IN_PROGRESS', 'FAILED')
      )
""")
suspend fun getUnqueuedTrackIds(sources: List<String>): List<Long>
```

- [ ] **Step 4: Add `dismissMatch` to TrackDao**

```kotlin
/** Mark a track as permanently dismissed from matching. */
@Query("UPDATE tracks SET match_dismissed = 1 WHERE id = :trackId")
suspend fun dismissMatch(trackId: Long)
```

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt
git commit -m "feat: add unmatched track queries, extend updateStatus with failureType, exclude dismissed from re-queue"
```

---

### Task 3: Worker changes — set failureType on outcomes, guard dismissed tracks in DiffWorker

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/TrackDownloadWorker.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt`

- [ ] **Step 1: Update TrackDownloadWorker outcome handling**

Read `TrackDownloadWorker.kt` fully. Find the outcome `when` block (lines ~223-265). Update the Unmatched and Failed branches to pass `failureType`:

```kotlin
is TrackDownloadOutcome.Unmatched -> {
    val err = "No YouTube match for: ${track.artist} - ${track.title}"
    downloadQueueDao.incrementRetryCount(queueItem.id)
    downloadQueueDao.updateStatus(
        id = queueItem.id,
        status = DownloadStatus.FAILED,
        failureType = DownloadFailureType.NO_MATCH,
        errorMessage = err,
    )
    firstError.compareAndSet(null, err)
    failedCount.incrementAndGet()
}
is TrackDownloadOutcome.Failed -> {
    downloadQueueDao.incrementRetryCount(queueItem.id)
    downloadQueueDao.updateStatus(
        id = queueItem.id,
        status = DownloadStatus.FAILED,
        failureType = DownloadFailureType.DOWNLOAD_ERROR,
        errorMessage = outcome.error.take(500),
    )
    firstError.compareAndSet(null, outcome.error.take(500))
    failedCount.incrementAndGet()
}
```

Add import: `import com.stash.core.model.DownloadFailureType`

- [ ] **Step 2: Guard dismissed tracks in DiffWorker**

Read `DiffWorker.kt` fully. Find the track processing loop (lines ~114-157). In the `existingTrack != null` branch, the current code just calls `ensurePlaylistMembership`. Update it:

```kotlin
if (existingTrack != null) {
    ensurePlaylistMembership(localPlaylist.id, existingTrack.id, trackSnapshot.position)

    // Auto-reconciliation: if this track is undownloaded, check if a
    // manually-downloaded track with the same canonical identity exists
    if (!existingTrack.isDownloaded && !existingTrack.matchDismissed) {
        val canonicalTitle = trackMatcher.canonicalTitle(trackSnapshot.title).lowercase()
        val canonicalArtist = trackMatcher.canonicalArtist(trackSnapshot.artist).lowercase()
        val downloadedMatch = trackDao.findDownloadedByCanonical(canonicalTitle, canonicalArtist)
        if (downloadedMatch != null && downloadedMatch.id != existingTrack.id) {
            // Link the downloaded track to this playlist instead
            ensurePlaylistMembership(localPlaylist.id, downloadedMatch.id, trackSnapshot.position)
            // Resolve the failed queue entry
            downloadQueueDao.updateStatus(
                id = downloadQueueDao.getByTrackId(existingTrack.id)?.id ?: return@let,
                status = DownloadStatus.COMPLETED,
            )
        }
    }
} else {
    // New track path — check dismissal before queuing
    val canonicalTitle = trackMatcher.canonicalTitle(trackSnapshot.title)
    val canonicalArtist = trackMatcher.canonicalArtist(trackSnapshot.artist)
    val newTrack = TrackEntity(...)
    val trackId = trackDao.insert(newTrack)
    ensurePlaylistMembership(localPlaylist.id, trackId, trackSnapshot.position)

    // Only queue if not dismissed
    if (!newTrack.matchDismissed) {
        downloadQueueDao.insert(...)
    }
    newTrackCount++
}
```

**Note:** The implementing agent must read DiffWorker carefully. The auto-reconciliation needs two new DAO queries:
- `TrackDao.findDownloadedByCanonical(canonicalTitle, canonicalArtist)`: Returns a TrackEntity where `is_downloaded = 1 AND LOWER(canonical_title) = :canonicalTitle AND LOWER(canonical_artist) = :canonicalArtist`
- `DownloadQueueDao.getByTrackId(trackId)`: Returns queue entry for a track ID

Add both to their respective DAOs.

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/TrackDownloadWorker.kt core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt
git commit -m "feat: set failureType on download outcomes, guard dismissed tracks, add auto-reconciliation"
```

---

### Task 4: Repository layer — expose unmatched queries

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt`

- [ ] **Step 1: Add interface methods**

```kotlin
/** Unmatched tracks (matching failures, not dismissed). */
fun getUnmatchedTracks(): Flow<List<UnmatchedTrackView>>

/** Count of unmatched tracks. */
fun getUnmatchedCount(): Flow<Int>

/** Dismiss a track from matching — marks TrackEntity and deletes queue entry. */
suspend fun dismissMatch(trackId: Long)
```

- [ ] **Step 2: Implement in MusicRepositoryImpl**

```kotlin
override fun getUnmatchedTracks(): Flow<List<UnmatchedTrackView>> =
    downloadQueueDao.getUnmatchedTracks()

override fun getUnmatchedCount(): Flow<Int> =
    downloadQueueDao.getUnmatchedCount()

override suspend fun dismissMatch(trackId: Long) {
    trackDao.dismissMatch(trackId)
    downloadQueueDao.deleteByTrackId(trackId)
}
```

Note: `MusicRepositoryImpl` needs `downloadQueueDao` injected. Check if it's already there; if not, add it to the constructor.

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt
git commit -m "feat: add unmatched tracks repository methods with dismiss logic"
```

---

### Task 5: FailedMatchesViewModel + FailedMatchesScreen

**Files:**
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesViewModel.kt`
- Create: `feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesScreen.kt`

- [ ] **Step 1: Create FailedMatchesViewModel**

Follow the pattern of other ViewModels (e.g., `PlaylistDetailViewModel`):

```kotlin
package com.stash.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FailedMatchesUiState(
    val tracks: List<UnmatchedTrackView> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class FailedMatchesViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
) : ViewModel() {

    val uiState: StateFlow<FailedMatchesUiState> =
        musicRepository.getUnmatchedTracks()
            .map { tracks ->
                FailedMatchesUiState(
                    tracks = tracks,
                    isLoading = false,
                )
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FailedMatchesUiState(),
            )

    fun dismissTrack(trackId: Long) {
        viewModelScope.launch {
            musicRepository.dismissMatch(trackId)
        }
    }
}
```

- [ ] **Step 2: Create FailedMatchesScreen**

Follow the pattern of other detail screens (PlaylistDetailScreen, LikedSongsDetailScreen). Structure:

- Back button header with "Unmatched Songs" title + count + subtitle explanation
- LazyColumn of track rows (album art, title, artist, dismiss button)
- Dismiss taps show `AlertDialog` confirmation: "Stop retrying this song?" / "Artist — Title won't be downloaded during future syncs. You can find it manually using Search." / [Cancel] [Confirm]
- Empty state: centered "All caught up! No unmatched songs."
- Loading state: centered CircularProgressIndicator

```kotlin
@Composable
fun FailedMatchesScreen(
    onBack: () -> Unit,
    viewModel: FailedMatchesViewModel = hiltViewModel(),
)
```

Use `DetailTrackRow` from `core:ui` is NOT appropriate here — unmatched tracks aren't playable (no file). Instead create a simple custom row: album art (or placeholder) + title + artist + dismiss IconButton (X or trash icon).

- [ ] **Step 3: Commit**

```bash
git add feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesViewModel.kt feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesScreen.kt
git commit -m "feat: add FailedMatchesScreen with dismiss confirmation dialog"
```

---

### Task 6: Sync tab card + SyncViewModel integration

**Files:**
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt`
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt`

- [ ] **Step 1: Add unmatchedCount to SyncUiState**

Add field to `SyncUiState` (line ~63):
```kotlin
val unmatchedCount: Int = 0,
```

- [ ] **Step 2: Observe unmatched count in SyncViewModel**

Add a new observer method (follow the `observeHistory` pattern):

```kotlin
private fun observeUnmatchedCount() {
    viewModelScope.launch {
        musicRepository.getUnmatchedCount().collect { count ->
            _uiState.update { it.copy(unmatchedCount = count) }
        }
    }
}
```

Call it from `init`. SyncViewModel needs `MusicRepository` injected — check if it's already there; if not, add it.

- [ ] **Step 3: Add warning card to SyncScreen**

Add `onNavigateToFailedMatches: () -> Unit = {}` parameter to `SyncScreen`.

In the LazyColumn, add after the SyncActionSection (around line 136) and before Spotify Sync Preferences:

```kotlin
if (uiState.unmatchedCount > 0) {
    item(key = "unmatched") {
        UnmatchedSongsCard(
            count = uiState.unmatchedCount,
            onClick = onNavigateToFailedMatches,
        )
    }
}
```

Create a private `UnmatchedSongsCard` composable — amber/warning styled Surface with rounded corners, showing a warning icon + "X songs couldn't be matched" text + chevron. Follow the existing card styles in SyncScreen.

- [ ] **Step 4: Commit**

```bash
git add feature/sync/src/main/kotlin/com/stash/feature/sync/SyncViewModel.kt feature/sync/src/main/kotlin/com/stash/feature/sync/SyncScreen.kt
git commit -m "feat: add unmatched songs warning card to Sync tab"
```

---

### Task 7: Navigation wiring

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt`
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt`

- [ ] **Step 1: Add route**

In `TopLevelDestination.kt`, add after the last route (after `LikedSongsDetailRoute`):

```kotlin
@Serializable data object FailedMatchesRoute
```

NOT in the `TopLevelDestination` enum — this is a detail screen, not a tab.

- [ ] **Step 2: Wire in StashNavHost**

Add import:
```kotlin
import com.stash.feature.sync.FailedMatchesScreen
```

Update `composable<SyncRoute>` (line 64) to pass the callback:
```kotlin
composable<SyncRoute> {
    SyncScreen(
        onNavigateToFailedMatches = {
            navController.navigate(FailedMatchesRoute)
        },
    )
}
```

Add new composable route (after the existing detail routes):
```kotlin
composable<FailedMatchesRoute> {
    FailedMatchesScreen(
        onBack = { navController.popBackStack() },
    )
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/navigation/TopLevelDestination.kt app/src/main/kotlin/com/stash/app/navigation/StashNavHost.kt
git commit -m "feat: wire FailedMatchesRoute navigation from Sync tab"
```

---

### Task 8: Build and verify

- [ ] **Step 1: Build**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew assembleDebug --no-daemon 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix compilation errors**

Common issues:
- `DownloadQueueDao` import for `DownloadFailureType`
- `MusicRepository` / `DownloadQueueDao` injection in `SyncViewModel` / `MusicRepositoryImpl`
- `UnmatchedTrackView` import in repository/ViewModel
- Migration registration in DatabaseModule
- `Instant` vs `Long` for `createdAt` in `UnmatchedTrackView` (Room stores as Long)

- [ ] **Step 3: Install and test**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew installDebug --no-daemon 2>&1 | tail -10
```

- [ ] **Step 4: Commit fixes (if any)**

```bash
git add -A && git commit -m "fix: resolve compilation issues for failed matches visibility"
```
