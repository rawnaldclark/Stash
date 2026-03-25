package com.stash.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncState
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Home screen. Collects playlist, track, and sync data
 * from [MusicRepository] and combines them into a single [HomeUiState].
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    /** Holds the latest sync status information, updated once on init. */
    private val _syncStatus = MutableStateFlow(SyncStatusInfo())

    val uiState: StateFlow<HomeUiState> = combine(
        musicRepository.getAllPlaylists(),
        musicRepository.getRecentlyAdded(20),
        musicRepository.getTrackCount(),
        musicRepository.getTotalStorageBytes(),
        _syncStatus,
    ) { playlists, recentlyAdded, trackCount, storageBytes, syncStatus ->
        val dailyMixes = playlists.filter { it.type == PlaylistType.DAILY_MIX }
        val likedSongs = playlists.filter { it.type == PlaylistType.LIKED_SONGS }
        val likedCount = likedSongs.sumOf { it.trackCount }
        val otherPlaylists = playlists.filter { it.type == PlaylistType.CUSTOM }

        HomeUiState(
            syncStatus = syncStatus.copy(
                totalTracks = trackCount,
                totalPlaylists = playlists.size,
                storageUsedBytes = storageBytes,
            ),
            dailyMixes = dailyMixes,
            recentlyAdded = recentlyAdded,
            likedSongsCount = likedCount,
            totalTracks = trackCount,
            totalStorageBytes = storageBytes,
            playlists = otherPlaylists,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    init {
        viewModelScope.launch {
            val latestSync = musicRepository.getLatestSync()
            if (latestSync != null) {
                _syncStatus.value = SyncStatusInfo(
                    lastSyncTime = latestSync.startedAt.toEpochMilli(),
                    nextSyncTime = latestSync.completedAt?.toEpochMilli()?.plus(6 * 3_600_000L),
                    state = latestSync.status,
                )
            }
        }
    }

    /**
     * Begins playback of the given track list starting at [index].
     */
    fun playTrack(tracks: List<Track>, index: Int) {
        viewModelScope.launch {
            playerRepository.setQueue(tracks, index)
        }
    }
}
