# Failed Matches Visibility — Design Spec

## Goal

Surface matching failures to users so they can see which songs couldn't be found on YouTube during sync. Only matching failures (algorithm couldn't find a result), not transient errors (network, app backgrounded).

## Scope

- Sync tab card showing unresolved failure count
- Dedicated detail screen listing failed tracks with dismiss action
- Permanent dismissal that survives re-syncs
- Auto-reconciliation when a user manually downloads a failed track via Search

## UX Flow

### Sync Tab Card

A warning-styled card appears on the Sync tab when unresolved matching failures exist:

- Text: "X songs couldn't be matched"
- Taps through to the Failed Matches detail screen
- Disappears when all failures are dismissed or auto-resolved
- Not error-red — uses a softer warning tone (info/amber). These aren't crashes.

### Failed Matches Detail Screen

**Header:** "Unmatched Songs" with count. Subtitle explanation: "These songs couldn't be found on YouTube during sync."

**Track rows:** Each row shows:
- Track title
- Artist
- Dismiss button or swipe action

No playlist name in v1 — adds query complexity for minimal user value. The user cares about which song failed, not which playlist it came from.

**Dismiss flow:** Tapping dismiss opens a confirmation dialog:

> **Stop retrying this song?**
> "Artist — Title" won't be downloaded during future syncs. You can find it manually using Search.
> [Cancel] [Confirm]

On confirm: track marked `matchDismissed = true` on `TrackEntity`. Disappears from the list.

**Empty state:** "All caught up! No unmatched songs."

### Navigation

- New route: `FailedMatchesRoute` in `TopLevelDestination.kt`
- New screen: `FailedMatchesScreen` in `feature/sync/`
- New ViewModel: `FailedMatchesViewModel` in `feature/sync/`
- Sync tab card taps → `navController.navigate(FailedMatchesRoute)`

## Data Layer

### New enum: DownloadFailureType

Add to `core/model/`:

```kotlin
enum class DownloadFailureType {
    NONE,
    NO_MATCH,
    DOWNLOAD_ERROR,
    TIMEOUT,
}
```

This replaces string-matching on `errorMessage` to distinguish failure categories. Type-safe and won't break if error message wording changes.

### DownloadQueueEntity changes

Add column:
- `failureType: DownloadFailureType = NONE`

DB migration: `ALTER TABLE download_queue ADD COLUMN failure_type TEXT NOT NULL DEFAULT 'NONE'`

Room TypeConverter needed for `DownloadFailureType` ↔ `String` (follow existing `PlaylistType` converter pattern in `Converters.kt`).

### TrackEntity changes

Add column:
- `matchDismissed: Boolean = false`

DB migration: `ALTER TABLE tracks ADD COLUMN match_dismissed INTEGER NOT NULL DEFAULT 0`

**Why on TrackEntity, not DownloadQueueEntity:** Queue entries are ephemeral — `DiffWorker` creates new entries each sync for undownloaded tracks. If dismissal lived on the queue entry, a new entry would be created next sync with `dismissed = false`, and the track would reappear in the failed list. Putting it on `TrackEntity` makes it permanent across syncs.

### TrackDownloadWorker changes

Update the outcome handling to set `failureType`:

```kotlin
is TrackDownloadOutcome.Unmatched -> {
    downloadQueueDao.updateStatus(
        id = queueItem.id,
        status = DownloadStatus.FAILED,
        failureType = DownloadFailureType.NO_MATCH,
        errorMessage = "No YouTube match for: ${track.artist} - ${track.title}"
    )
}
is TrackDownloadOutcome.Failed -> {
    downloadQueueDao.updateStatus(
        id = queueItem.id,
        status = DownloadStatus.FAILED,
        failureType = DownloadFailureType.DOWNLOAD_ERROR,
        errorMessage = outcome.error.take(500)
    )
}
```

### DiffWorker changes

When processing a Spotify playlist track and deciding whether to create a download queue entry:

1. If `trackEntity.matchDismissed == true` → skip, do not queue. The user permanently dismissed this track.
2. If `trackEntity.isDownloaded == false` → check for auto-reconciliation (see below) before queuing.

### DAO queries

```kotlin
// DownloadQueueDao
@Query("""
    SELECT dq.*, t.title, t.artist, t.albumArtUrl
    FROM download_queue dq
    INNER JOIN tracks t ON dq.track_id = t.id
    WHERE dq.failure_type = 'NO_MATCH'
      AND dq.status = 'FAILED'
      AND t.match_dismissed = 0
    ORDER BY dq.created_at DESC
""")
fun getUnmatchedTracks(): Flow<List<UnmatchedTrackView>>

// TrackDao
@Query("UPDATE tracks SET match_dismissed = 1 WHERE id = :trackId")
suspend fun dismissMatch(trackId: Long)

// Count for the Sync tab card
@Query("""
    SELECT COUNT(*)
    FROM download_queue dq
    INNER JOIN tracks t ON dq.track_id = t.id
    WHERE dq.failure_type = 'NO_MATCH'
      AND dq.status = 'FAILED'
      AND t.match_dismissed = 0
""")
fun getUnmatchedCount(): Flow<Int>
```

`UnmatchedTrackView` is a Room projection data class:
```kotlin
data class UnmatchedTrackView(
    val id: Long,          // queue entry ID
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val createdAt: Instant,
)
```

## Auto-Reconciliation on Sync

When `DiffWorker` processes a Spotify playlist track that exists locally with `isDownloaded = false`:

1. Query for any existing downloaded track with matching `canonicalTitle` + `canonicalArtist` (case-insensitive). Uses the normalized fields already stored on `TrackEntity`, consistent with how `MatchScorer` normalizes.
2. If a match is found:
   - Link the existing downloaded track to the playlist via `PlaylistTrackCrossRef`
   - Mark any existing `FAILED` queue entry for the original track as `COMPLETED`
   - The failed entry auto-resolves — disappears from the Unmatched Songs screen
3. If no match found: proceed with normal queuing behavior.

**Performance:** One indexed Room query per undownloaded track. Sub-millisecond per query. Negligible vs. network calls that dominate sync time.

**Match criteria:** Exact match on `canonicalTitle` + `canonicalArtist` (both lowercased, already stripped of parenthetical suffixes, remaster tags, etc. by the existing canonicalization in the sync pipeline). This catches "Don't Stop Me Now" matching "Don't Stop Me Now (Remastered 2011)" because both canonicalize to the same string.

## Existing Code Integration

### resetExhaustedRetries()

`TrackDownloadWorker` calls `resetExhaustedRetries()` at sync start to give failed tracks another chance. This must NOT re-enable tracks where `TrackEntity.matchDismissed = true`. Since `DiffWorker` checks `matchDismissed` before creating queue entries, dismissed tracks won't get new queue entries even if old ones are reset. No change needed to `resetExhaustedRetries()` itself — the guard is at the queuing stage.

### Track model mapping

Add `matchDismissed: Boolean = false` to the domain `Track` model and update `TrackEntity.toDomain()` / `Track.toEntity()` mappers.

## Edge Cases

- **Zero failures:** Sync tab card does not appear. No visual noise.
- **All dismissed:** Card disappears. Detail screen shows empty state.
- **Track in multiple playlists fails:** One queue entry per track (not per playlist membership). Shows once in the failed list.
- **Dismissed track gets re-added to a Spotify playlist:** `DiffWorker` sees `matchDismissed = true`, skips queuing. Track stays dismissed.
- **User un-dismisses:** Not supported in v1. If needed later, add an "Undo" path or a settings screen showing dismissed tracks.

## Out of Scope

- Retry button (Search tab handles manual retry)
- Manual match picking / YouTube search within the screen
- Match confidence display
- Per-playlist filtering on the failed list
- Undo/un-dismiss functionality
- Blacklist integration (future feature — `matchDismissed` lays groundwork)
