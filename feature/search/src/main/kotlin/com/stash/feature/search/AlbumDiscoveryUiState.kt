package com.stash.feature.search

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.TrackSummary

/**
 * Hero-card fields for the Album Discovery screen.
 *
 * [title], [artist], [thumbnailUrl], and [year] are initially hydrated from the
 * five nav args so the first frame after navigation paints a cover + title
 * (see [AlbumDiscoveryViewModel]'s `init`). Once the cache resolves the full
 * [com.stash.data.ytmusic.model.AlbumDetail], [trackCount] and
 * [totalDurationMs] get populated and the hero re-renders with the derived
 * subtitle ("2005 · 12 tracks · 48 min").
 */
data class AlbumHeroState(
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val year: String?,
    val trackCount: Int,
    val totalDurationMs: Long,
)

/**
 * Lifecycle states for an Album Discovery screen load.
 *
 *  - [Loading] — initial state; hero shows nav-arg values, tracklist empty.
 *  - [Fresh] — [com.stash.core.data.cache.AlbumCache.get] returned a detail
 *    and the VM has folded it into state.
 *  - [Error] — cache threw (cold miss + network failure). Screen renders an
 *    error card with a Retry button that flips back to [Loading].
 */
sealed interface AlbumDiscoveryStatus {
    data object Loading : AlbumDiscoveryStatus
    data object Fresh : AlbumDiscoveryStatus
    data class Error(val message: String) : AlbumDiscoveryStatus
}

/**
 * Full UI state for the Album Discovery screen.
 *
 * @property hero Always non-null — seeded from nav args, updated when the
 *   cache resolves.
 * @property tracks The album's full tracklist in InnerTube order. Empty until
 *   [status] is [AlbumDiscoveryStatus.Fresh].
 * @property moreByArtist "More by this artist" shelf rendered at the bottom
 *   of the screen. May be empty for compilations.
 * @property status See [AlbumDiscoveryStatus].
 * @property showDownloadConfirm When true, the screen shows the "Download
 *   all" confirmation dialog. Flipped by
 *   [AlbumDiscoveryViewModel.onDownloadAllClicked] /
 *   [AlbumDiscoveryViewModel.onDownloadAllDismissed] /
 *   [AlbumDiscoveryViewModel.onDownloadAllConfirmed].
 * @property downloadConfirmQueue Snapshot of non-downloaded tracks captured
 *   the moment the user taps "Download all". The VM uses this snapshot (not
 *   a re-read of [tracks] minus `delegate.downloadedIds`) when the user
 *   confirms so a mid-dialog individual-track download doesn't accidentally
 *   drop a track from the batch.
 */
data class AlbumDiscoveryUiState(
    val hero: AlbumHeroState,
    val tracks: List<TrackSummary> = emptyList(),
    val moreByArtist: List<AlbumSummary> = emptyList(),
    val status: AlbumDiscoveryStatus = AlbumDiscoveryStatus.Loading,
    val showDownloadConfirm: Boolean = false,
    val downloadConfirmQueue: List<TrackSummary> = emptyList(),
)
