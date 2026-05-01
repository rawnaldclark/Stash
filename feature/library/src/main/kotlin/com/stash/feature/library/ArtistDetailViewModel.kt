package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import com.stash.core.ui.util.withSearchFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
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
 * @property tracks                 All tracks by this artist in the library,
 *                                  filtered by [searchQuery] when non-empty.
 * @property isLoading              True while the initial data load is in progress.
 * @property currentlyPlayingTrackId The ID of the currently-playing track, used
 *                                   to highlight the active row.
 * @property searchQuery            The active search/filter string.
 * @property showSearch             Whether the search bar is currently visible.
 */
data class ArtistDetailUiState(
    val artistName: String = "",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
    val searchQuery: String = "",
    val showSearch: Boolean = false,
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
@OptIn(FlowPreview::class)
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

    private val _searchQuery = MutableStateFlow("")
    private val _showSearch = MutableStateFlow(false)

    /** Update the live search query; results filter immediately with debounce. */
    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }

    /** Clear the search query without closing the search bar. */
    fun clearSearch() { _searchQuery.value = "" }

    /**
     * Toggle the search bar visibility.
     * Hiding the bar also clears the query so the full track list is restored.
     */
    fun toggleSearch() {
        _showSearch.value = !_showSearch.value
        if (!_showSearch.value) _searchQuery.value = ""
    }

    /**
     * Combined UI state reacting to:
     * 1. Track list changes for this artist (reactive Flow from [MusicRepository]),
     *    filtered through [withSearchFilter] for live search.
     * 2. Player state changes (to highlight the currently-playing track)
     * 3. Search query and visibility state
     */
    val uiState: StateFlow<ArtistDetailUiState> = combine(
        musicRepository.getTracksByArtist(artistName).withSearchFilter(_searchQuery),
        playerRepository.playerState,
        _searchQuery,
        _showSearch,
    ) { tracks, playerState, query, showSearch ->
        ArtistDetailUiState(
            artistName = artistName,
            tracks = tracks,
            isLoading = false,
            currentlyPlayingTrackId = playerState.currentTrack?.id,
            searchQuery = query,
            showSearch = showSearch,
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
     * Shuffles all downloaded tracks by this artist and begins playback.
     * Shuffles the list itself (not just the start index).
     */
    fun shuffleAll() {
        viewModelScope.launch {
            val downloaded = uiState.value.tracks.filter { it.filePath != null }
            if (downloaded.isEmpty()) return@launch
            playerRepository.setQueue(downloaded.shuffled(), 0)
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
