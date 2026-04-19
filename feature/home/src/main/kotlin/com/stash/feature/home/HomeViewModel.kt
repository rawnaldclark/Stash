package com.stash.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.toDisplayStatus
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncDisplayStatus
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
                displayStatus = latestSync.toDisplayStatus(),
            )
        } else {
            SyncStatusInfo(displayStatus = SyncDisplayStatus.Idle)
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
        // Stash Mixes — recipe-driven, generated locally. Separate from
        // sync-imported Daily Mixes so the UI can label them distinctly.
        val stashMixes = musicData.playlists.filter { it.type == PlaylistType.STASH_MIX }

        // Split daily mixes by source
        val dailyMixes = musicData.playlists.filter { it.type == PlaylistType.DAILY_MIX }
        val spotifyMixes = dailyMixes.filter { it.source == MusicSource.SPOTIFY }
        val youtubeMixes = dailyMixes.filter { it.source == MusicSource.YOUTUBE }

        // Split liked songs by source
        val likedPlaylists = musicData.playlists.filter { it.type == PlaylistType.LIKED_SONGS }
        val spotifyLikedPlaylists = likedPlaylists.filter { it.source == MusicSource.SPOTIFY }
        val youtubeLikedPlaylists = likedPlaylists.filter { it.source == MusicSource.YOUTUBE }
        val spotifyLikedCount = spotifyLikedPlaylists.sumOf { it.trackCount }
        val youtubeLikedCount = youtubeLikedPlaylists.sumOf { it.trackCount }

        val otherPlaylists = musicData.playlists.filter { it.type == PlaylistType.CUSTOM }

        HomeUiState(
            syncStatus = syncStatus.copy(
                totalTracks = musicData.trackCount,
                spotifyTracks = sourceCounts.first,
                youTubeTracks = sourceCounts.second,
                totalPlaylists = musicData.playlists.size,
                storageUsedBytes = musicData.storageBytes,
            ),
            stashMixes = stashMixes,
            spotifyMixes = spotifyMixes,
            youtubeMixes = youtubeMixes,
            recentlyAdded = musicData.recentlyAdded,
            spotifyLikedPlaylists = spotifyLikedPlaylists,
            youtubeLikedPlaylists = youtubeLikedPlaylists,
            spotifyLikedCount = spotifyLikedCount,
            youtubeLikedCount = youtubeLikedCount,
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
                playerRepository.setQueue(downloaded, startIndex = downloaded.indices.random())
            }
        }
    }

    /**
     * Loads the downloaded tracks for [playlist] and appends each to the playback queue.
     * Only tracks with a non-null [Track.filePath] (i.e. downloaded) are queued.
     */
    fun addPlaylistToQueue(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
            val downloaded = tracks.filter { it.filePath != null }
            downloaded.forEach { playerRepository.addToQueue(it) }
        }
    }

    /**
     * Deletes a playlist using the protected-playlist cascade. Tracks that
     * also belong to Liked Songs or an in-app custom playlist are kept —
     * only their membership in [playlist] is removed. If [alsoBlacklist]
     * is `true`, tracks that WERE deleted are also marked never-download-
     * again, so future syncs skip their identity forever.
     *
     * The [CascadeRemovalSummary] returned via [_lastCascadeSummary] drives
     * the post-delete Snackbar so users see exactly what happened.
     */
    fun deletePlaylistAndSongs(playlist: Playlist, alsoBlacklist: Boolean = false) {
        viewModelScope.launch {
            val summary = musicRepository.deletePlaylistWithCascade(
                playlistId = playlist.id,
                alsoBlacklist = alsoBlacklist,
            )
            _lastCascadeSummary.emit(summary)
        }
    }

    /**
     * Preview counts the UI uses in the delete-confirmation dialog:
     * how many tracks would actually be removed vs. kept due to
     * protected-playlist membership.
     */
    suspend fun previewPlaylistDelete(playlist: Playlist): DeletePreview {
        val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
        var protected = 0
        for (track in tracks) {
            // isTrackInProtectedPlaylist returns true if the track is in
            // Liked Songs / custom playlists OTHER than [playlist]. We
            // have to do the "other than" filtering here because the DAO
            // query doesn't exclude the source playlist.
            val inProtectedElsewhere = musicRepository.isTrackProtectedExcluding(
                trackId = track.id,
                excludePlaylistId = playlist.id,
            )
            if (inProtectedElsewhere) protected++
        }
        return DeletePreview(
            totalTracks = tracks.size,
            protectedCount = protected,
        )
    }

    private val _lastCascadeSummary =
        kotlinx.coroutines.flow.MutableSharedFlow<com.stash.core.data.repository.MusicRepository.CascadeRemovalSummary>(
            extraBufferCapacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    /** One-shot cascade summaries for the delete Snackbar. */
    val lastCascadeSummary: kotlinx.coroutines.flow.SharedFlow<com.stash.core.data.repository.MusicRepository.CascadeRemovalSummary> =
        _lastCascadeSummary.asSharedFlow()

    /** Preview counts shown in the playlist-delete confirmation dialog. */
    data class DeletePreview(
        val totalTracks: Int,
        val protectedCount: Int,
    ) {
        val willDelete: Int get() = totalTracks - protectedCount
    }

    /** Remove playlist from library without deleting its downloaded tracks. */
    fun removePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            musicRepository.removePlaylist(playlist)
        }
    }

    /**
     * Creates a new empty custom playlist with the given [name]. Trims input
     * and no-ops if the trimmed name is blank. The new playlist will appear
     * in the Home Playlists section automatically (Room Flow).
     */
    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            musicRepository.createPlaylist(trimmed)
        }
    }

    /**
     * Plays every downloaded track across every daily mix from the given [source],
     * effectively merging all of that source's mixes into one continuous queue.
     * Passing null plays the combined pool from BOTH sources (Spotify first,
     * then YouTube) with per-track deduplication.
     *
     * Duplicates are removed via [distinctBy] so tracks appearing in multiple
     * mixes are only queued once. Tracks appear in the order their parent
     * playlists are returned by the repository.
     *
     * @param source The source whose mixes to play, or null to combine both.
     */
    fun playAllMixes(source: MusicSource?) {
        viewModelScope.launch {
            val state = uiState.value
            val mixes = when (source) {
                MusicSource.SPOTIFY -> state.spotifyMixes
                MusicSource.YOUTUBE -> state.youtubeMixes
                null -> state.spotifyMixes + state.youtubeMixes
                else -> return@launch
            }
            if (mixes.isEmpty()) return@launch

            val allTracks = mixes
                .flatMap { mix ->
                    musicRepository.getTracksByPlaylist(mix.id).first()
                }
                .filter { it.filePath != null }
                .distinctBy { it.id }

            if (allTracks.isNotEmpty()) {
                playerRepository.setQueue(allTracks, startIndex = allTracks.indices.random())
            }
        }
    }

    /**
     * Loads liked songs from the specified [source] (or both if null) and
     * begins playback. Fetches actual playlist members from the join table
     * rather than all downloaded tracks.
     *
     * **Play order:** When [source] is null, Spotify liked songs are queued
     * first, then YouTube Music liked songs. Within each source, tracks are
     * ordered by the liked-playlist's insertion order. Duplicates (same track
     * ID appearing in both sources) are removed via `distinctBy`, keeping the
     * first occurrence (Spotify wins).
     *
     * @param source Specific source to play from, or null for combined
     *   Spotify + YouTube liked songs.
     */
    fun playLikedSongs(source: MusicSource? = null) {
        viewModelScope.launch {
            val state = uiState.value
            val playlistsToPlay = when (source) {
                MusicSource.SPOTIFY -> state.spotifyLikedPlaylists
                MusicSource.YOUTUBE -> state.youtubeLikedPlaylists
                else -> state.spotifyLikedPlaylists + state.youtubeLikedPlaylists
            }

            if (playlistsToPlay.isEmpty()) return@launch

            // Fetch each liked playlist's tracks in parallel and flatten
            val allTracks = playlistsToPlay
                .flatMap { playlist ->
                    musicRepository.getTracksByPlaylist(playlist.id).first()
                }
                .filter { it.filePath != null }
                .distinctBy { it.id }

            if (allTracks.isNotEmpty()) {
                playerRepository.setQueue(allTracks, startIndex = allTracks.indices.random())
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
