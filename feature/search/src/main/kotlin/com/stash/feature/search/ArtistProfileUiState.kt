package com.stash.feature.search

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.TrackSummary

/**
 * Immutable hero-card state for the Artist Profile screen.
 *
 * Hydrated from nav args on the first frame (so the hero paints before the
 * cache emits) and then replaced verbatim from [com.stash.data.ytmusic.model.ArtistProfile]
 * once a [com.stash.core.data.cache.CachedProfile] emission arrives.
 */
data class HeroState(
    val name: String,
    val avatarUrl: String?,
    val subscribersText: String?,
)

/**
 * Load state for the Artist Profile screen.
 *
 * `Loading` only covers the very-first paint before any cache emission.
 * Once a [com.stash.core.data.cache.CachedProfile] has been seen the status
 * flips to either [Fresh] or [Stale]; [Stale] keeps rendering cached data
 * even on refresh failure (refresh failure surfaces via `userMessages`).
 */
sealed interface ArtistProfileStatus {
    data object Loading : ArtistProfileStatus
    data object Fresh : ArtistProfileStatus
    data object Stale : ArtistProfileStatus
    data class Error(val message: String) : ArtistProfileStatus
}

/**
 * Full UI state surfaced by [ArtistProfileViewModel] to [ArtistProfileScreen].
 *
 * Hero populates from nav args immediately; the shelves (popular / albums /
 * singles / related) fill in once the cache emits.
 *
 * Shrunk in the Album Discovery phase-1 migration: the previous
 * `downloadingIds` / `downloadedIds` / `previewLoading` fields moved onto
 * [com.stash.core.media.actions.TrackActionsDelegate] so they can be shared
 * with `SearchViewModel` (and, next, `AlbumDiscoveryViewModel`). The screen
 * now reads those flags straight from `vm.delegate.*`.
 */
data class ArtistProfileUiState(
    val hero: HeroState,
    val popular: List<TrackSummary> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val singles: List<AlbumSummary> = emptyList(),
    val related: List<ArtistSummary> = emptyList(),
    val status: ArtistProfileStatus = ArtistProfileStatus.Loading,
)
