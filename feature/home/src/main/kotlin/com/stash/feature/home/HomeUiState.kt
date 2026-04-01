package com.stash.feature.home

import com.stash.core.model.Playlist
import com.stash.core.model.SyncState
import com.stash.core.model.Track

/**
 * UI state for the Home screen, combining all observable data streams
 * into a single immutable snapshot.
 */
data class HomeUiState(
    val syncStatus: SyncStatusInfo = SyncStatusInfo(),
    val dailyMixes: List<Playlist> = emptyList(),
    val recentlyAdded: List<Track> = emptyList(),
    val likedSongsCount: Int = 0,
    val totalTracks: Int = 0,
    val totalStorageBytes: Long = 0,
    val playlists: List<Playlist> = emptyList(),
    val isLoading: Boolean = true,
    val spotifyConnected: Boolean = false,
    val youTubeConnected: Boolean = false,
    val hasEverSynced: Boolean = false,
)

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
)
