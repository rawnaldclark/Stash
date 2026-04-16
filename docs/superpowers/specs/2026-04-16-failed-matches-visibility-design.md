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
- The unmatched count is exposed via `SyncViewModel.uiState` (not a separate subscription) to follow the existing pattern where all Sync tab data flows through `SyncUiState`.

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

On confirm: track marked `matchDismissed = true` on `TrackEntity`, and the existing failed queue entry for this track is deleted (prevents `resetExhaustedRetries` and `getRetryableBySources` from picking it up). Track disappears from the list.

**Empty state:** "All caught up! No unmatched songs."

### Navigation

`FailedMatchesRoute` is declared as a `@Serializable data object` in `TopLevelDestination.kt` (the route registry file) but is NOT added to the `TopLevelDestination` enum — it is not a bottom nav tab. It follows the same pattern as `PlaylistDetailRoute`, `ArtistDetailRoute`, etc.

Wiring:
- `StashNavHost.kt`: add `composable<FailedMatchesRoute> { FailedMatchesScreen(onBack = { navController.popBackStack() }) }`
- `SyncScreen` gets a new `onNavigateToFailedMatches: () -> Unit` parameter
- `StashNavHost.kt`: update `composable<SyncRoute>` to pass `onNavigateToFailedMatches = { navController.navigate(FailedMatchesRoute) }`

Files:
- New route: `FailedMatchesRoute` in `app/.../navigation/TopLevelDestination.kt`
- New screen: `FailedMatchesScreen` in `feature/sync/`
- New ViewModel: `FailedMatchesViewModel` in `feature/sync/`

## Data Layer

### New enum: DownloadFailureType

Add to `core/model/`:

```kotlin
enum class DownloadFailureType {
    NONE,
    NO_MATCH,
    DOWNLOAD_ERROR,
}
```

Only two failure types for now — `NO_MATCH` (matching algorithm) and `DOWNLOAD_ERROR` (yt-dlp/network). No `TIMEOUT` value since `TrackDownloadOutcome` has no timeout-specific variant; timeouts surface as `Failed` with an error message.

Room TypeConverter needed for `DownloadFailureType` ↔ `String` (follow existing `PlaylistType` converter pattern in `Converters.kt`).

### DownloadQueueEntity changes

Add column:
- `failureType: DownloadFailureType = NONE`

DB migration (version 4 → 5): `ALTER TABLE download_queue ADD COLUMN failure_type TEXT NOT NULL DEFAULT 'NONE'`

### TrackEntity changes

Add column:
- `matchDismissed: Boolean = false`

DB migration (version 4 → 5, same migration): `ALTER TABLE tracks ADD COLUMN match_dismissed INTEGER NOT NULL DEFAULT 0`

**Why on TrackEntity, not DownloadQueueEntity:** Queue entries are ephemeral — `DiffWorker` creates new entries each sync for undownloaded tracks. If dismissal lived on the queue entry, a new entry would be created next sync with `dismissed = false`, and the track would reappear in the failed list. Putting it on `TrackEntity` makes it permanent across syncs.

### Database migration

Bump `StashDatabase` version from 4 to 5. Define `MIGRATION_4_5` with both ALTER TABLE statements. Register in `DatabaseModule`'s `addMigrations()` call.

### Track domain model + mappers

Add `matchDismissed: Boolean = false` to the domain `Track` model at `core/model/.../Track.kt`.

Update mappers in `core/data/.../mapper/TrackMapper.kt`:
- `TrackEntity.toDomain()`: map `matchDismissed` field
- `Track.toEntity()`: map `matchDismissed` field

### DownloadQueueDao changes

Extend the existing `updateStatus` query to include `failure_type`:

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

The new `failureType` parameter has a default value so existing call sites (Success case, etc.) don't need changes.

Update `getUnqueuedTrackIds` to exclude dismissed tracks:

```kotlin
// Existing query must add: AND t.match_dismissed = 0
// This prevents dismissed tracks from being re-queued via the TrackDownloadWorker orphan-detection path
```

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

### New DAO queries

```kotlin
// DownloadQueueDao — list for the detail screen
@Query("""
    SELECT dq.id, dq.track_id AS trackId, t.title, t.artist, t.album_art_url AS albumArtUrl, dq.created_at AS createdAt
    FROM download_queue dq
    INNER JOIN tracks t ON dq.track_id = t.id
    WHERE dq.failure_type = 'NO_MATCH'
      AND dq.status = 'FAILED'
      AND t.match_dismissed = 0
    ORDER BY dq.created_at DESC
""")
fun getUnmatchedTracks(): Flow<List<UnmatchedTrackView>>

// DownloadQueueDao — count for the Sync tab card
@Query("""
    SELECT COUNT(*)
    FROM download_queue dq
    INNER JOIN tracks t ON dq.track_id = t.id
    WHERE dq.failure_type = 'NO_MATCH'
      AND dq.status = 'FAILED'
      AND t.match_dismissed = 0
""")
fun getUnmatchedCount(): Flow<Int>

// DownloadQueueDao — delete queue entry on dismiss (prevents retry paths from picking it up)
@Query("DELETE FROM download_queue WHERE track_id = :trackId")
suspend fun deleteByTrackId(trackId: Long)

// TrackDao — mark track as dismissed
@Query("UPDATE tracks SET match_dismissed = 1 WHERE id = :trackId")
suspend fun dismissMatch(trackId: Long)
```

`UnmatchedTrackView` is a Room projection data class:
```kotlin
data class UnmatchedTrackView(
    val id: Long,          // queue entry ID (not used for dismiss — use trackId)
    val trackId: Long,     // track ID (used for dismissMatch call)
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val createdAt: Long,
)
```

## Auto-Reconciliation on Sync

Fires in `DiffWorker` when processing a playlist track where `findExistingTrack()` returns a non-null result (the `existingTrack != null` branch). The trigger condition:

- `existingTrack.isDownloaded == false` (the track entity exists but was never successfully downloaded)
- A **different** `TrackEntity` with the same `canonicalTitle` + `canonicalArtist` exists with `isDownloaded == true` (the user downloaded it manually via Search)

When both conditions are true:
1. Link the downloaded track to the playlist via `PlaylistTrackCrossRef`
2. Mark any existing `FAILED` queue entry for the original track as `COMPLETED`
3. The failed entry auto-resolves — disappears from the Unmatched Songs screen

When the second condition is false (no downloaded canonical match exists):
- Proceed with normal queuing behavior

**Performance:** One indexed Room query per undownloaded track. The `(canonical_title, canonical_artist)` composite index on `TrackEntity` covers this query. Sub-millisecond per lookup. Negligible vs. network calls that dominate sync time.

**Match criteria:** Exact match on `canonicalTitle` + `canonicalArtist` (both lowercased, already stripped of parenthetical suffixes, remaster tags, etc. by the existing canonicalization in the sync pipeline).

## Existing Code Integration

### resetExhaustedRetries() and getRetryableBySources()

On dismiss, the queue entry for the dismissed track is **deleted** (not just flagged). This means:
- `resetExhaustedRetries()` has nothing to reset for dismissed tracks
- `getRetryableBySources()` has no entry to pick up
- No changes needed to either method

The `DiffWorker` guard (`matchDismissed == true` → skip queuing) prevents new entries from being created on future syncs.

### Track model mapping

Add `matchDismissed: Boolean = false` to the domain `Track` model at `core/model/.../Track.kt`. Update both `TrackEntity.toDomain()` and `Track.toEntity()` in the mapper file.

## Edge Cases

- **Zero failures:** Sync tab card does not appear. No visual noise.
- **All dismissed:** Card disappears. Detail screen shows empty state.
- **Track in multiple playlists fails:** One queue entry per track (not per playlist membership). Shows once in the failed list.
- **Dismissed track gets re-added to a Spotify playlist:** `DiffWorker` sees `matchDismissed = true`, skips queuing. Track stays dismissed.
- **User un-dismisses:** Not supported in v1. If needed later, add an "Undo" path or a settings screen showing dismissed tracks.
- **Dismiss then re-sync:** Queue entry deleted + `matchDismissed = true` on track. DiffWorker skips it. No re-queue. No phantom entries.

## Out of Scope

- Retry button (Search tab handles manual retry)
- Manual match picking / YouTube search within the screen
- Match confidence display
- Per-playlist filtering on the failed list
- Undo/un-dismiss functionality
- Blacklist integration (future feature — `matchDismissed` lays groundwork)
