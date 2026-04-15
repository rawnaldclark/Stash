package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Artist Detail screen.
 *
 * @property artistName             The artist whose tracks are displayed.
 * @property tracks                 All tracks by this artist in the library.
 * @property isLoading              True while the initial data load is in progress.
 * @property currentlyPlayingTrackId The ID of the currently-playing track, used
 *                                   to highlight the active row.
 */
data class ArtistDetailUiState(
    val artistName: String = "",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
)

/**
 * ViewModel for the Artist Detail screen.
 *
 * Loads tracks by artist name reactively from [MusicRepository] and combines
 * with the current player state from [PlayerRepository] to highlight the
 * active track row.
 *
 * The `artistName` is extracted from the navigation [SavedStateHandle].
 */
@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    /** The artist name extracted from the navigation route arguments. */
    private val artistName: String = checkNotNull(savedStateHandle.get<String>("artistName")) {
        "artistName is required but was not found in SavedStateHandle"
    }

    /**
     * Combined UI state reacting to:
     * 1. Track list changes for this artist (reactive Flow from [MusicRepository])
     * 2. Player state changes (to highlight the currently-playing track)
     */
    val uiState: StateFlow<ArtistDetailUiState> = combine(
        musicRepository.getTracksByArtist(artistName),
        playerRepository.playerState,
    ) { tracks, playerState ->
        ArtistDetailUiState(
            artistName = artistName,
            tracks = tracks,
            isLoading = false,
            currentlyPlayingTrackId = playerState.currentTrack?.id,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ArtistDetailUiState(artistName = artistName),
    )

    // ── Playback actions ────────────────────────────────────────────────

    /**
     * Sets the playback queue to all downloaded tracks by this artist
     * and begins playback from the track matching [trackId].
     */
    fun playTrack(trackId: Long) {
        viewModelScope.launch {
            val downloaded = uiState.value.tracks.filter { it.filePath != null }
            if (downloaded.isEmpty()) return@launch
            val index = downloaded.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
            playerRepository.setQueue(downloaded, index)
        }
    }

    /**
     * Shuffles all downloaded tracks by this artist and begins playback
     * from a random position.
     */
    fun shuffleAll() {
        viewModelScope.launch {
            val downloaded = uiState.value.tracks.filter { it.filePath != null }
            if (downloaded.isEmpty()) return@launch
            val randomIndex = (downloaded.indices).random()
            playerRepository.setQueue(downloaded, randomIndex)
        }
    }

    /** Inserts [track] immediately after the currently-playing track in the queue. */
    fun playNext(track: Track) {
        viewModelScope.launch {
            playerRepository.addNext(track)
        }
    }

    /** Appends [track] to the end of the current playback queue. */
    fun addToQueue(track: Track) {
        viewModelScope.launch {
            playerRepository.addToQueue(track)
        }
    }

    /** Delete a track from the library (file + DB entry). */
    fun deleteTrack(track: Track) {
        viewModelScope.launch {
            musicRepository.deleteTrack(track)
        }
    }

    /** User-created playlists for the Save to Playlist picker. */
    val userPlaylists = musicRepository.getUserCreatedPlaylists()

    /** Save a track to an existing playlist. */
    fun saveTrackToPlaylist(trackId: Long, playlistId: Long) {
        viewModelScope.launch {
            musicRepository.addTrackToPlaylist(trackId, playlistId)
        }
    }

    /** Create a new playlist and immediately add the track to it. */
    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        viewModelScope.launch {
            val playlistId = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(trackId, playlistId)
        }
    }
}
