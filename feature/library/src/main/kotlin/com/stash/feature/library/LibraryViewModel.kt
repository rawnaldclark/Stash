package com.stash.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Library screen.
 *
 * Collects tracks, playlists, artists, and albums from [MusicRepository],
 * applies client-side search filtering and sort ordering, and exposes a
 * single [LibraryUiState] stream for the UI layer.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    /** Local UI controls: tab, search query, and sort order. */
    private val _controls = MutableStateFlow(ControlState())

    /**
     * Combined UI state that reacts to both data changes and user interactions.
     */
    val uiState: StateFlow<LibraryUiState> = combine(
        _controls,
        musicRepository.getAllTracks(),
        musicRepository.getAllPlaylists(),
        musicRepository.getAllArtists(),
        musicRepository.getAllAlbums(),
    ) { controls, allTracks, allPlaylists, allArtists, allAlbums ->

        val query = controls.searchQuery.trim().lowercase()

        // -- Map DAO projections to UI models --
        val artists = allArtists.map { ArtistInfo(it.artist, it.trackCount, it.totalDurationMs) }
        val albums = allAlbums.map { AlbumInfo(it.album, it.artist, it.trackCount, it.artPath) }

        // -- Apply client-side search filter --
        val filteredTracks = if (query.isEmpty()) allTracks else allTracks.filter {
            it.title.lowercase().contains(query)
                    || it.artist.lowercase().contains(query)
                    || it.album.lowercase().contains(query)
        }
        val filteredPlaylists = if (query.isEmpty()) allPlaylists else allPlaylists.filter {
            it.name.lowercase().contains(query)
        }
        val filteredArtists = if (query.isEmpty()) artists else artists.filter {
            it.name.lowercase().contains(query)
        }
        val filteredAlbums = if (query.isEmpty()) albums else albums.filter {
            it.name.lowercase().contains(query)
                    || it.artist.lowercase().contains(query)
        }

        // -- Apply sort order --
        val sortedTracks = when (controls.sortOrder) {
            SortOrder.RECENT -> filteredTracks.sortedByDescending { it.dateAdded }
            SortOrder.ALPHABETICAL -> filteredTracks.sortedBy { it.title.lowercase() }
            SortOrder.MOST_PLAYED -> filteredTracks.sortedByDescending { it.playCount }
        }
        val sortedPlaylists = when (controls.sortOrder) {
            SortOrder.RECENT -> filteredPlaylists.sortedByDescending { it.lastSynced ?: 0L }
            SortOrder.ALPHABETICAL -> filteredPlaylists.sortedBy { it.name.lowercase() }
            SortOrder.MOST_PLAYED -> filteredPlaylists // no play-count on playlists
        }
        val sortedArtists = when (controls.sortOrder) {
            SortOrder.RECENT -> filteredArtists
            SortOrder.ALPHABETICAL -> filteredArtists.sortedBy { it.name.lowercase() }
            SortOrder.MOST_PLAYED -> filteredArtists.sortedByDescending { it.trackCount }
        }
        val sortedAlbums = when (controls.sortOrder) {
            SortOrder.RECENT -> filteredAlbums
            SortOrder.ALPHABETICAL -> filteredAlbums.sortedBy { it.name.lowercase() }
            SortOrder.MOST_PLAYED -> filteredAlbums.sortedByDescending { it.trackCount }
        }

        LibraryUiState(
            activeTab = controls.activeTab,
            searchQuery = controls.searchQuery,
            sortOrder = controls.sortOrder,
            tracks = sortedTracks,
            playlists = sortedPlaylists,
            artists = sortedArtists,
            albums = sortedAlbums,
            isLoading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    // ── Public actions ───────────────────────────────────────────────────

    /** Switch the active content tab. */
    fun selectTab(tab: LibraryTab) {
        _controls.update { it.copy(activeTab = tab) }
    }

    /** Update the search query; filtering is applied reactively. */
    fun setSearchQuery(query: String) {
        _controls.update { it.copy(searchQuery = query) }
    }

    /** Change the sort order for every content list. */
    fun setSortOrder(order: SortOrder) {
        _controls.update { it.copy(sortOrder = order) }
    }

    /**
     * Begin playback by replacing the queue with [allTracks] and starting
     * at the position of [track].
     */
    fun playTrack(track: Track, allTracks: List<Track>) {
        viewModelScope.launch {
            val index = allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playerRepository.setQueue(allTracks, index)
        }
    }
}

/**
 * Internal holder for user-driven UI controls so they can be combined
 * with the data flows in a single [combine] call.
 */
private data class ControlState(
    val activeTab: LibraryTab = LibraryTab.PLAYLISTS,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.RECENT,
)
