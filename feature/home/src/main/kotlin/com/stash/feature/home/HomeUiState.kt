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

    /**
     * Recipe-generated Stash Mixes. Rotate daily via StashMixRefreshWorker.
     * Rendered in a dedicated Home section above Daily Mixes so users
     * recognize them as "yours" vs. imported.
     */
    val stashMixes: List<Playlist> = emptyList(),

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

    /** Active sort for the Home Playlists grid. Mirrors Library's chips. */
    val playlistSortOrder: PlaylistSortOrder = PlaylistSortOrder.RECENT,

    val isLoading: Boolean = true,
    val spotifyConnected: Boolean = false,
    val youTubeConnected: Boolean = false,
    /**
     * Non-null when Last.fm creds are wired but the user hasn't
     * connected yet AND there are local plays queued waiting to be
     * scrobbled. Drives the Home banner nudging them into Settings.
     */
    val lastFmPrompt: LastFmPromptState? = null,
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

/** Payload for the "connect Last.fm to send plays" banner. */
data class LastFmPromptState(val pendingCount: Int)

/**
 * Sort options for the Home Playlists grid. Deliberately duplicated from
 * the Library module's `SortOrder` to avoid a cross-module dependency for
 * three enum values. If a third surface ever needs the same options, lift
 * to a shared module rather than crossing the feature:library boundary.
 */
enum class PlaylistSortOrder { RECENT, ALPHABETICAL, MOST_PLAYED }

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
