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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Album Detail screen.
 *
 * @property albumName               The album being displayed.
 * @property artistName              The artist for this album.
 * @property tracks                  Tracks on this album by this artist.
 * @property isLoading               True while the initial data load is in progress.
 * @property currentlyPlayingTrackId The ID of the currently-playing track.
 * @property searchQuery              The active search/filter query string.
 * @property showSearch               True when the search bar is visible.
 */
data class AlbumDetailUiState(
    val albumName: String = "",
    val artistName: String = "",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
    val searchQuery: String = "",
    val showSearch: Boolean = false,
)

/**
 * ViewModel for the Album Detail screen.
 *
 * Loads all tracks and filters by album name and artist name. Combines
 * with the current player state from [PlayerRepository] to highlight
 * the active track row.
 *
 * The `albumName` and `artistName` are extracted from the navigation
 * [SavedStateHandle].
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    /** The album name extracted from the navigation route arguments. */
    private val albumName: String = checkNotNull(savedStateHandle.get<String>("albumName")) {
        "albumName is required but was not found in SavedStateHandle"
    }

    /** The artist name extracted from the navigation route arguments. */
    private val artistName: String = checkNotNull(savedStateHandle.get<String>("artistName")) {
        "artistName is required but was not found in SavedStateHandle"
    }

    private val _searchQuery = MutableStateFlow("")
    private val _showSearch = MutableStateFlow(false)

    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }
    fun clearSearch() { _searchQuery.value = "" }
    fun toggleSearch() {
        _showSearch.value = !_showSearch.value
        if (!_showSearch.value) _searchQuery.value = ""
    }

    /**
     * Filtered track flow: all tracks matching this album + artist combination.
     * Uses case-insensitive matching to be resilient against metadata variations.
     */
    private val albumTracks = musicRepository.getAllTracks().map { allTracks ->
        allTracks.filter {
            it.album.equals(albumName, ignoreCase = true)
                && it.artist.equals(artistName, ignoreCase = true)
        }
    }

    /**
     * Combined UI state reacting to:
     * 1. Filtered album track list (further narrowed by the search query)
     * 2. Player state changes (to highlight the currently-playing track)
     * 3. Search query and search bar visibility
     */
    val uiState: StateFlow<AlbumDetailUiState> = combine(
        albumTracks.withSearchFilter(_searchQuery),
        playerRepository.playerState,
        _searchQuery,
        _showSearch,
    ) { tracks, playerState, query, showSearch ->
        AlbumDetailUiState(
            albumName = albumName,
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
        initialValue = AlbumDetailUiState(albumName = albumName, artistName = artistName),
    )

    // ── Playback actions ────────────────────────────────────────────────

    /**
     * Sets the playback queue to all downloaded tracks on this album
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
     * Shuffles all downloaded tracks on this album and begins playback
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
