package com.stash.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Home screen. Collects playlist, track, sync data,
 * and authentication state from [MusicRepository] and [TokenManager],
 * combining them into a single reactive [HomeUiState].
 *
 * All data sources are Flow-based so the UI updates automatically when:
 * - New tracks/playlists are inserted after a sync
 * - A sync completes and a new history record appears
 * - Spotify or YouTube auth state changes (connect/disconnect)
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val tokenManager: TokenManager,
) : ViewModel() {

    /**
     * Derives [SyncStatusInfo] reactively from the latest sync history record.
     * Emits a default (empty) status when no sync has ever run.
     */
    private val syncStatusFlow = musicRepository.observeLatestSync().map { latestSync ->
        if (latestSync != null) {
            SyncStatusInfo(
                lastSyncTime = latestSync.startedAt.toEpochMilli(),
                nextSyncTime = latestSync.completedAt?.toEpochMilli()?.plus(6 * 3_600_000L),
                state = latestSync.status,
            )
        } else {
            SyncStatusInfo()
        }
    }

    /**
     * Combines the four Room-backed data flows into a single intermediate holder,
     * keeping the top-level combine at 5 or fewer flows for type safety.
     */
    private val musicDataFlow = combine(
        musicRepository.getAllPlaylists(),
        musicRepository.getRecentlyAdded(20),
        musicRepository.getTrackCount(),
        musicRepository.getTotalStorageBytes(),
    ) { playlists, recentlyAdded, trackCount, storageBytes ->
        MusicData(playlists, recentlyAdded, trackCount, storageBytes)
    }

    private val sourceCountsFlow = combine(
        musicRepository.getSpotifyDownloadedCount(),
        musicRepository.getYouTubeDownloadedCount(),
    ) { spotify, youtube -> Pair(spotify, youtube) }

    /**
     * Derives a pair of (spotifyConnected, youTubeConnected) from TokenManager.
     */
    private val authStateFlow = combine(
        tokenManager.spotifyAuthState,
        tokenManager.youTubeAuthState,
    ) { spotify, youtube ->
        AuthInfo(
            spotifyConnected = spotify is AuthState.Connected,
            youTubeConnected = youtube is AuthState.Connected,
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        musicDataFlow,
        syncStatusFlow,
        authStateFlow,
        sourceCountsFlow,
    ) { musicData, syncStatus, authInfo, sourceCounts ->
        val dailyMixes = musicData.playlists.filter { it.type == PlaylistType.DAILY_MIX }
        val likedSongs = musicData.playlists.filter { it.type == PlaylistType.LIKED_SONGS }
        val likedCount = likedSongs.sumOf { it.trackCount }
        val otherPlaylists = musicData.playlists.filter { it.type == PlaylistType.CUSTOM }

        HomeUiState(
            syncStatus = syncStatus.copy(
                totalTracks = musicData.trackCount,
                spotifyTracks = sourceCounts.first,
                youTubeTracks = sourceCounts.second,
                totalPlaylists = musicData.playlists.size,
                storageUsedBytes = musicData.storageBytes,
            ),
            dailyMixes = dailyMixes,
            recentlyAdded = musicData.recentlyAdded,
            likedSongsCount = likedCount,
            totalTracks = musicData.trackCount,
            totalStorageBytes = musicData.storageBytes,
            playlists = otherPlaylists,
            isLoading = false,
            spotifyConnected = authInfo.spotifyConnected,
            youTubeConnected = authInfo.youTubeConnected,
            hasEverSynced = syncStatus.lastSyncTime != null,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState(),
    )

    /**
     * Begins playback of the given track list starting at [index].
     */
    fun playTrack(tracks: List<Track>, index: Int) {
        viewModelScope.launch {
            playerRepository.setQueue(tracks, index)
        }
    }

    /**
     * Loads the downloaded tracks for [playlist] and begins playback from the first track.
     * Only tracks with a non-null [Track.filePath] (i.e. downloaded) are queued.
     */
    fun playPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
            val downloaded = tracks.filter { it.filePath != null }
            if (downloaded.isNotEmpty()) {
                playerRepository.setQueue(downloaded, startIndex = 0)
            }
        }
    }

    /**
     * Loads all downloaded tracks and begins playback from the first track.
     * Only tracks with a non-null [Track.filePath] (i.e. downloaded) are queued.
     */
    fun playLikedSongs() {
        viewModelScope.launch {
            val tracks = musicRepository.getAllTracks().first()
            val downloaded = tracks.filter { it.filePath != null }
            if (downloaded.isNotEmpty()) {
                playerRepository.setQueue(downloaded, startIndex = 0)
            }
        }
    }
}

/**
 * Internal holder for the four music-data Room flows so we can combine
 * them into a single upstream before the top-level combine.
 */
private data class MusicData(
    val playlists: List<Playlist>,
    val recentlyAdded: List<Track>,
    val trackCount: Int,
    val storageBytes: Long,
)

/**
 * Internal holder for auth state so it can participate in the combine
 * as a single flow emission.
 */
private data class AuthInfo(
    val spotifyConnected: Boolean,
    val youTubeConnected: Boolean,
)
