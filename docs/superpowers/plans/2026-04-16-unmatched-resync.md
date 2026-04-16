# Unmatched Songs Resync — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Redesign FailedMatchesScreen with a Resync button that re-searches YouTube for each unmatched track, shows candidates with album art and preview, and lets users approve (instant download) or dismiss.

**Architecture:** Resync uses `HybridSearchExecutor` (same as Search tab) to find candidates. Results are stored in ViewModel state (ephemeral). Approve triggers `DownloadExecutor` + file organization + DB updates (same chain as SearchViewModel.downloadTrack). Preview reuses existing `PreviewPlayer` + `PreviewUrlExtractor`. All changes are in `feature/sync/` + one DAO query update.

**Tech Stack:** Jetpack Compose, Hilt, HybridSearchExecutor, DownloadExecutor, FileOrganizer, PreviewPlayer, Room

**Spec:** `docs/superpowers/specs/2026-04-16-unmatched-resync-design.md`

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `core/data/.../db/dao/DownloadQueueDao.kt` | Modify | Add `searchQuery` to `UnmatchedTrackView` + query |
| `feature/sync/.../FailedMatchesViewModel.kt` | **Rewrite** | Add resync, approve, expanded UiState |
| `feature/sync/.../FailedMatchesScreen.kt` | **Rewrite** | Resync button, candidate rows with album art, approve/preview/dismiss |

Only 3 files touched. The heavy lifting is in the ViewModel and Screen rewrites.

---

### Task 1: Add searchQuery to UnmatchedTrackView + query

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt`

- [ ] **Step 1: Update UnmatchedTrackView data class**

Add `searchQuery` field (around line 233-241):

```kotlin
data class UnmatchedTrackView(
    val id: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val createdAt: Long,
    val rejectedVideoId: String?,
    val searchQuery: String,
)
```

- [ ] **Step 2: Update getUnmatchedTracks query**

Add `dq.search_query AS searchQuery` to the SELECT (around line 196-207):

```kotlin
@Query("""
    SELECT dq.id, dq.track_id AS trackId, t.title, t.artist,
           t.album_art_url AS albumArtUrl, dq.created_at AS createdAt,
           dq.rejected_video_id AS rejectedVideoId,
           dq.search_query AS searchQuery
    FROM download_queue dq
    INNER JOIN tracks t ON dq.track_id = t.id
    WHERE dq.failure_type = 'NO_MATCH'
      AND dq.status = 'FAILED'
      AND t.match_dismissed = 0
    ORDER BY dq.created_at DESC
""")
fun getUnmatchedTracks(): Flow<List<UnmatchedTrackView>>
```

- [ ] **Step 3: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/DownloadQueueDao.kt
git commit -m "feat: add searchQuery to UnmatchedTrackView for resync searches"
```

---

### Task 2: Rewrite FailedMatchesViewModel with resync + approve

**Files:**
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesViewModel.kt`

- [ ] **Step 1: Read reference files**

Read these files to understand the patterns to replicate:
- Current `FailedMatchesViewModel.kt` (the file you're rewriting)
- `feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt` — see `downloadTrack()` and `handleDownloadSuccess()` for the download+organize+persist chain
- `data/download/src/main/kotlin/com/stash/data/download/matching/HybridSearchExecutor.kt` — see the `search()` method signature
- `data/download/src/main/kotlin/com/stash/data/download/DownloadExecutor.kt` — see `download()` method signature and `DownloadResult` types
- `data/download/src/main/kotlin/com/stash/data/download/files/FileOrganizer.kt` — see `getTrackFile()` and `getTempDir()` methods

- [ ] **Step 2: Rewrite the ViewModel**

Replace the entire file with the expanded version. Key additions:

**New UiState:**
```kotlin
data class FailedMatchesUiState(
    val tracks: List<UnmatchedTrackView> = emptyList(),
    val isLoading: Boolean = true,
    val previewLoading: String? = null,
    val resyncCandidates: Map<Long, ResyncCandidate> = emptyMap(),
    val isResyncing: Boolean = false,
    val resyncProgress: String = "",
    val approvingIds: Set<Long> = emptySet(),
)

data class ResyncCandidate(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationSeconds: Double,
)
```

**New dependencies** (add to constructor):
```kotlin
private val searchExecutor: HybridSearchExecutor,
private val downloadExecutor: DownloadExecutor,
private val fileOrganizer: FileOrganizer,
private val qualityPrefs: QualityPreferencesManager,
private val trackDao: TrackDao,
private val downloadQueueDao: DownloadQueueDao,
```

**New state flows:**
```kotlin
private val _resyncCandidates = MutableStateFlow<Map<Long, ResyncCandidate>>(emptyMap())
private val _isResyncing = MutableStateFlow(false)
private val _resyncProgress = MutableStateFlow("")
private val _approvingIds = MutableStateFlow<Set<Long>>(emptySet())
```

Update `uiState` combine to include all new flows.

**New methods:**

`fun resync()` — iterate unmatched tracks, search each via `searchExecutor.search(track.searchQuery, maxResults = 5)`, take first result, map to `ResyncCandidate`, store in `_resyncCandidates`. Use `Semaphore(2)` for concurrency. Update `_resyncProgress` after each ("3 of 12"). Cancel previous resync if running.

`fun approveMatch(trackId: Long, candidate: ResyncCandidate)` — full download chain:
1. Add trackId to `_approvingIds`
2. Get quality args from `qualityPrefs.qualityTier.first().toYtDlpArgs()`
3. Call `downloadExecutor.download(url, tempDir, tempFilename, qualityArgs)`
4. On `DownloadResult.Success`:
   - Get final path via `fileOrganizer.getTrackFile(artist, album, title, format)`
   - Copy temp file to final, delete temp
   - `trackDao.markAsDownloaded(trackId, finalPath, fileSize)` — check if this method exists, otherwise update directly
   - `trackDao.updateYoutubeId(trackId, candidate.videoId)` — need to verify this exists or add it
   - `downloadQueueDao.updateStatus(queueEntryId, COMPLETED)`
5. On failure: remove from `_approvingIds`, log error

**Existing methods to keep:** `dismissTrack()`, `previewRejectedMatch()`, `stopPreview()`, `onCleared()`.

**IMPORTANT:** The ViewModel needs to find the queue entry ID for a given trackId to call `updateStatus`. The `UnmatchedTrackView.id` is the queue entry ID — pass it through the approve flow.

- [ ] **Step 3: Add any missing DAO methods**

Check if `TrackDao` has `updateYoutubeId(trackId, youtubeId)`. If not, you need to add it:

```kotlin
@Query("UPDATE tracks SET youtube_id = :youtubeId WHERE id = :trackId")
suspend fun updateYoutubeId(trackId: Long, youtubeId: String)
```

Also check if `TrackDao.markAsDownloaded(trackId, filePath, fileSizeBytes)` exists. It should (used by `TrackDownloadWorker`). Read `TrackDao.kt` to confirm.

- [ ] **Step 4: Commit**

```bash
git add feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesViewModel.kt core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt
git commit -m "feat: add resync + approve logic to FailedMatchesViewModel"
```

---

### Task 3: Rewrite FailedMatchesScreen with resync UI

**Files:**
- Modify: `feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesScreen.kt`

- [ ] **Step 1: Read the current screen + reference files**

Read:
- Current `FailedMatchesScreen.kt`
- `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt` — see `SearchResultRow` for the album art + buttons pattern
- `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt` — see header + LazyColumn pattern

- [ ] **Step 2: Update the header**

The header currently shows "Unmatched Songs" + count + subtitle + back button. Add a Resync button below the subtitle text. When resyncing, show progress text instead of the button label:

```kotlin
Button(
    onClick = { viewModel.resync() },
    enabled = !state.isResyncing,
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
) {
    if (state.isResyncing) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text = "Searching ${state.resyncProgress}...")
    } else {
        Icon(Icons.Default.Refresh, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Resync")
    }
}
```

Add import: `import androidx.compose.material.icons.filled.Refresh`

- [ ] **Step 3: Update UnmatchedTrackRow for candidate display**

The row now has two visual states:
- **Before resync (no candidate):** Original track info + dismiss only (current behavior)
- **After resync (candidate found):** Album art from candidate, original title/artist, candidate title/artist below, preview + approve + dismiss buttons

Update `UnmatchedTrackRow` signature:

```kotlin
@Composable
private fun UnmatchedTrackRow(
    track: UnmatchedTrackView,
    candidate: ResyncCandidate?,
    isPreviewPlaying: Boolean,
    isPreviewLoading: Boolean,
    isApproving: Boolean,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
)
```

Row layout:
- **Album art:** If candidate has thumbnailUrl, show it via AsyncImage (add `coil.compose` dependency to `feature/sync/build.gradle.kts` — `implementation(libs.coil.compose)` and `implementation(libs.coil.network.okhttp)`). Otherwise gradient placeholder.
- **Text column:**
  - Line 1: `track.title` — `track.artist` (original, primary style)
  - Line 2 (if candidate): `candidate.title` — `candidate.artist` (secondary style, onSurfaceVariant color)
  - Line 2 (if no candidate, after resync): "No match found" (muted)
- **Buttons (right side):**
  - Preview: play/stop/loading — only if candidate exists. Uses `candidate.videoId`.
  - Approve: checkmark icon — only if candidate exists and not currently approving. If `isApproving`, show CircularProgressIndicator instead.
  - Dismiss: X icon — always visible.

- [ ] **Step 4: Update the LazyColumn item rendering**

Pass candidate and approve state to each row:

```kotlin
itemsIndexed(
    items = state.tracks,
    key = { _, track -> track.id },
) { index, track ->
    val candidate = state.resyncCandidates[track.trackId]
    val isPreviewPlaying = previewState is PreviewState.Playing &&
        (previewState as PreviewState.Playing).videoId == candidate?.videoId
    val isPreviewLoading = state.previewLoading == candidate?.videoId

    UnmatchedTrackRow(
        track = track,
        candidate = candidate,
        isPreviewPlaying = isPreviewPlaying,
        isPreviewLoading = isPreviewLoading,
        isApproving = track.trackId in state.approvingIds,
        onPreview = { videoId -> viewModel.previewRejectedMatch(videoId) },
        onStopPreview = { viewModel.stopPreview() },
        onApprove = { candidate?.let { viewModel.approveMatch(track.trackId, track.id, it) } },
        onDismiss = { trackToDismiss = track },
    )
    // Divider...
}
```

Note: `approveMatch` needs `trackId` (for TrackEntity update), `queueEntryId` (track.id, for queue status update), and the `candidate`.

- [ ] **Step 5: Add Coil dependency**

In `feature/sync/build.gradle.kts`, add:
```kotlin
implementation(libs.coil.compose)
implementation(libs.coil.network.okhttp)
```

- [ ] **Step 6: Commit**

```bash
git add feature/sync/src/main/kotlin/com/stash/feature/sync/FailedMatchesScreen.kt feature/sync/build.gradle.kts
git commit -m "feat: redesign FailedMatchesScreen with resync, candidate display, and approve"
```

---

### Task 4: Build and verify

- [ ] **Step 1: Build**

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" && ./gradlew --stop 2>&1; sleep 2 && ./gradlew assembleDebug --no-daemon 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Fix compilation errors**

Common issues:
- Missing imports for `HybridSearchExecutor`, `DownloadExecutor`, `FileOrganizer`, `QualityPreferencesManager`, `TrackDao`, `DownloadQueueDao`
- `Semaphore` import: `import kotlinx.coroutines.sync.Semaphore`
- `toYtDlpArgs()` import: `import com.stash.data.download.prefs.toYtDlpArgs`
- `DownloadResult` import: `import com.stash.data.download.DownloadResult`
- `AsyncImage` needs coil dependency in build.gradle.kts
- Smart cast issues with `candidate?.videoId` (use local val)
- `markAsDownloaded` or `updateYoutubeId` method might not exist — add to TrackDao

- [ ] **Step 3: Install and test**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 4: Commit fixes**

```bash
git add -A && git commit -m "fix: resolve compilation issues for unmatched resync feature"
```
