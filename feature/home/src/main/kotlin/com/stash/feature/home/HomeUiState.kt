package com.stash.feature.home

import com.stash.core.model.Playlist
import com.stash.core.model.SyncDisplayStatus
import com.stash.core.model.SyncState
import com.stash.core.model.Track

/**
 * UI state for the Home screen, combining all observable data streams
 * into a single immutable snapshot.
 *
 * Liked songs and daily mixes are split by source (Spotify / YouTube) so
 * the UI can render them in source-grouped sections with smart collapse
 * when only one source is connected.
 */
data class HomeUiState(
    val syncStatus: SyncStatusInfo = SyncStatusInfo(),

    /** Spotify daily mixes (e.g. Daily Mix 1, Discover Weekly). */
    val spotifyMixes: List<Playlist> = emptyList(),

    /** YouTube Music mixes (e.g. My Mix 1, Discover Mix, Replay Mix). */
    val youtubeMixes: List<Playlist> = emptyList(),

    /** Recently downloaded tracks across all sources. */
    val recentlyAdded: List<Track> = emptyList(),

    /** Spotify liked-songs playlists (usually one — "Liked Songs"). */
    val spotifyLikedPlaylists: List<Playlist> = emptyList(),

    /** YouTube liked-songs playlists (usually one — "Liked Music"). */
    val youtubeLikedPlaylists: List<Playlist> = emptyList(),

    /** Combined Spotify liked-songs track count (sum of playlist metadata). */
    val spotifyLikedCount: Int = 0,

    /** Combined YouTube liked-songs track count (sum of playlist metadata). */
    val youtubeLikedCount: Int = 0,

    val totalTracks: Int = 0,
    val totalStorageBytes: Long = 0,

    /** Custom (non-mix, non-liked) playlists shown in the grid. */
    val playlists: List<Playlist> = emptyList(),

    val isLoading: Boolean = true,
    val spotifyConnected: Boolean = false,
    val youTubeConnected: Boolean = false,
    val hasEverSynced: Boolean = false,
) {
    /** Total liked songs across both sources. */
    val totalLikedCount: Int get() = spotifyLikedCount + youtubeLikedCount

    /** True when either source has a liked-songs playlist (regardless of track count). */
    val hasAnyLikedSongs: Boolean
        get() = spotifyLikedPlaylists.isNotEmpty() || youtubeLikedPlaylists.isNotEmpty()

    /** True when both sources have liked-songs playlists (used to decide whether to show source chips). */
    val hasBothLikedSources: Boolean
        get() = spotifyLikedPlaylists.isNotEmpty() && youtubeLikedPlaylists.isNotEmpty()

    /** True when both sources have daily mixes (used to decide whether to group mix rows). */
    val hasBothMixSources: Boolean
        get() = spotifyMixes.isNotEmpty() && youtubeMixes.isNotEmpty()

    /**
     * Identifies the single contributing source when only one has liked songs.
     * Returns null when both sources contribute or neither does.
     */
    val singleLikedSource: com.stash.core.model.MusicSource?
        get() = when {
            spotifyLikedPlaylists.isNotEmpty() && youtubeLikedPlaylists.isEmpty() ->
                com.stash.core.model.MusicSource.SPOTIFY
            youtubeLikedPlaylists.isNotEmpty() && spotifyLikedPlaylists.isEmpty() ->
                com.stash.core.model.MusicSource.YOUTUBE
            else -> null
        }
}

/**
 * Summarised sync status information displayed in the sync status card.
 */
data class SyncStatusInfo(
    val lastSyncTime: Long? = null,
    val nextSyncTime: Long? = null,
    val totalTracks: Int = 0,
    val spotifyTracks: Int = 0,
    val youTubeTracks: Int = 0,
    val totalPlaylists: Int = 0,
    val storageUsedBytes: Long = 0,
    val state: SyncState = SyncState.IDLE,
    /** Richer display-oriented summary of the latest sync outcome. */
    val displayStatus: SyncDisplayStatus = SyncDisplayStatus.Idle,
)
