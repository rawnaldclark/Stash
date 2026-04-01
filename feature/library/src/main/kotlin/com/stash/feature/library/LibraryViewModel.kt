package com.stash.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
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
 * Collects tracks, playlists, artists, albums, and auth state from
 * [MusicRepository] and [TokenManager], applies client-side search filtering
 * and sort ordering, and exposes a single [LibraryUiState] stream for the UI.
 *
 * Auth state is included so that empty-state messages can distinguish between
 * "no services connected" and "connected but not yet synced".
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val tokenManager: TokenManager,
) : ViewModel() {

    /** Local UI controls: tab, search query, and sort order. */
    private val _controls = MutableStateFlow(ControlState())

    /**
     * Derives a pair of (spotifyConnected, youTubeConnected) from TokenManager.
     */
    private val authStateFlow = combine(
        tokenManager.spotifyAuthState,
        tokenManager.youTubeAuthState,
    ) { spotify, youtube ->
        Pair(spotify is AuthState.Connected, youtube is AuthState.Connected)
    }

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
        DataSnapshot(controls, allTracks, allPlaylists, allArtists, allAlbums)
    }.combine(authStateFlow) { snapshot, authPair ->
        val controls = snapshot.controls
        val allTracks = snapshot.allTracks
        val allPlaylists = snapshot.allPlaylists
        val allArtists = snapshot.allArtists
        val allAlbums = snapshot.allAlbums

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
            spotifyConnected = authPair.first,
            youTubeConnected = authPair.second,
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
            // Only queue tracks that have been downloaded (have a file on disk).
            // Non-downloaded tracks have null filePath — ExoPlayer can't play them
            // and silently skips to the next item, causing the wrong song to play.
            val downloadedTracks = allTracks.filter { it.filePath != null }
            val index = downloadedTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playerRepository.setQueue(downloadedTracks, index)
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

/**
 * Internal snapshot holder for the 5-flow combine, allowing us to chain
 * a second [combine] with the auth flow while staying within Kotlin's
 * 5-parameter combine limit.
 */
private data class DataSnapshot(
    val controls: ControlState,
    val allTracks: List<Track>,
    val allPlaylists: List<com.stash.core.model.Playlist>,
    val allArtists: List<com.stash.core.data.db.dao.ArtistSummary>,
    val allAlbums: List<com.stash.core.data.db.dao.AlbumSummary>,
)
