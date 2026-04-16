# Unmatched Songs Resync + Manual Approve — Design Spec

## Goal

Redesign the FailedMatchesScreen so users can re-search YouTube for unmatched tracks, preview candidates, and manually approve downloads — all from one screen.

## Scope

- Resync button that re-searches YouTube for each unmatched track
- Each row shows original track info + best YouTube candidate (with album art)
- Preview, approve (instant download), and dismiss per track
- Progress indicator during resync ("Searching 3 of 12...")

## Screen States

### 1. Initial (before resync)

Shows the unmatched track list as-is (title, artist, dismiss button). A prominent "Resync" button at the top of the header below the subtitle text. This is the current behavior plus the resync button.

### 2. Resyncing

Resync button shows a spinner + progress text ("Searching 3 of 12..."). Track list remains visible — rows update in-place as candidates are found. User can still dismiss during resync.

### 3. Results (after resync)

Each row transforms to show the original track info alongside the best YouTube candidate. Three actions per row: preview, approve, dismiss. Rows with no candidate found show "No match found" with only dismiss available.

## Row Layout (after resync)

Each row shows:
- **Left:** Album art thumbnail from YouTube candidate (48dp, rounded 8dp). Gradient placeholder if no candidate or no thumbnail.
- **Top line:** Original Spotify track title + artist (primary text)
- **Second line:** YouTube candidate title + uploader (secondary text, different color to distinguish). Or "No match found" if no candidate.
- **Right buttons:**
  - Preview (play/stop) — only when candidate exists
  - Approve (checkmark) — only when candidate exists. Replaced by download progress spinner while downloading.
  - Dismiss (X) — always available

## Resync Logic

When user taps Resync:

1. ViewModel iterates through all unmatched tracks from `uiState.tracks`
2. For each track, calls `HybridSearchExecutor.search(track.searchQuery, maxResults = 5)`
3. Takes the first result (best by YouTube relevance)
4. Maps to `ResyncCandidate(videoId, title, artist, thumbnailUrl, durationSeconds)`
5. Stores in `resyncCandidates: Map<Long, ResyncCandidate>` keyed by trackId
6. Concurrency: semaphore of 2 concurrent searches
7. Progress: `resyncProgress` updated after each search ("3 of 12")
8. Uses the existing `searchQuery` from `UnmatchedTrackView` (already stored in `DownloadQueueEntity`)

```kotlin
data class ResyncCandidate(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationSeconds: Double,
)
```

Results are ephemeral — not persisted to DB. Leaving and returning to the screen requires a fresh resync.

## Approve Flow

When user taps Approve on a candidate:

1. Add trackId to `approvingIds` set (row shows download progress indicator)
2. Get quality tier from `QualityPreferencesManager`
3. Download via `DownloadExecutor.download()` with `https://www.youtube.com/watch?v={videoId}`, temp dir, quality args
4. On success:
   - Organize file via `FileOrganizer.getTrackFile(artist, album, title, format)`
   - Copy temp file to final path, delete temp
   - Update `TrackEntity`: set `isDownloaded = true`, `filePath`, `fileSizeBytes`, `youtubeId = videoId`
   - Update `DownloadQueueEntity`: set `status = COMPLETED`
   - Row disappears from the list (reactive Flow from `getUnmatchedTracks()` re-emits)
5. On failure:
   - Remove trackId from `approvingIds` (revert to approve button)
   - Log error

Setting `youtubeId` on the TrackEntity is critical — without it, future syncs would see the track as unmatched and re-queue it.

## Preview Flow

Same as existing implementation — uses `PreviewUrlExtractor` + `PreviewPlayer`. Preview button works identically to the Search tab's preview (play/stop toggle, loading spinner during extraction).

## Dismiss Flow

Unchanged from current implementation — confirmation dialog, permanent dismiss via `matchDismissed = true` on TrackEntity + delete queue entry.

## UiState

```kotlin
data class FailedMatchesUiState(
    val tracks: List<UnmatchedTrackView> = emptyList(),
    val isLoading: Boolean = true,
    val previewLoading: String? = null,
    val resyncCandidates: Map<Long, ResyncCandidate> = emptyMap(),  // NEW
    val isResyncing: Boolean = false,                                // NEW
    val resyncProgress: String = "",                                 // NEW — e.g., "3 of 12"
    val approvingIds: Set<Long> = emptySet(),                       // NEW — tracks being downloaded
)
```

## ViewModel Dependencies

```kotlin
@HiltViewModel
class FailedMatchesViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val previewPlayer: PreviewPlayer,
    private val previewUrlExtractor: PreviewUrlExtractor,
    private val searchExecutor: HybridSearchExecutor,
    private val downloadExecutor: DownloadExecutor,
    private val fileOrganizer: FileOrganizer,
    private val qualityPrefs: QualityPreferencesManager,
    private val trackDao: TrackDao,
    private val downloadQueueDao: DownloadQueueDao,
)
```

9 dependencies — justified because the screen performs 4 distinct actions (search, preview, download, dismiss) each requiring different infrastructure.

## Data Layer Changes

### UnmatchedTrackView — add searchQuery

```kotlin
data class UnmatchedTrackView(
    val id: Long,
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val createdAt: Long,
    val rejectedVideoId: String?,
    val searchQuery: String,      // NEW
)
```

Update `getUnmatchedTracks()` query to select `dq.search_query AS searchQuery`.

## Edge Cases

- **Zero search results for a track**: Row shows "No match found", only dismiss available.
- **User taps Approve while download in progress**: No-op (trackId already in approvingIds).
- **Download fails on approve**: Revert to approve button, log error. No toast/dialog (user can retry).
- **User leaves screen during resync**: ViewModel.onCleared() cancels resync coroutines.
- **User leaves screen during approve download**: Download continues in ViewModel scope; if ViewModel is cleared, download is cancelled (acceptable — user can resync again).
- **Resync while previous resync is running**: Cancel previous, start fresh.
- **Track gets dismissed during resync**: Fine — the row disappears via reactive Flow, the resync result for that trackId is ignored.

## Out of Scope

- Batch "Approve All"
- Editing the search query per track
- Persisting resync results across screen visits
- Match confidence score display
- Sorting/filtering the unmatched list
