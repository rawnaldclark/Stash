package com.stash.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.files.LocalImportState
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.net.Uri
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
    private val playlistImageHelper: PlaylistImageHelper,
    private val localImportCoordinator: LocalImportCoordinator,
) : ViewModel() {

    /** Live progress for "Import from device". Observed by LibraryScreen. */
    val localImportState: StateFlow<LocalImportState> = localImportCoordinator.state

    /** Kick off an import for the URIs picked via the SAF audio picker. */
    fun startLocalImport(uris: List<Uri>) {
        localImportCoordinator.start(uris)
    }

    /** Cancel an in-progress import. Files imported so far stay put. */
    fun cancelLocalImport() {
        localImportCoordinator.cancel()
    }

    /** Dismiss the Done/Error banner, hide the progress strip. */
    fun dismissLocalImport() {
        localImportCoordinator.dismiss()
    }

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
        val artists = allArtists.map { ArtistInfo(it.artist, it.trackCount, it.totalDurationMs, it.artUrl) }
        val albums = allAlbums.map { AlbumInfo(it.album, it.artist, it.trackCount, it.artPath, it.artUrl) }

        // -- Apply source filter --
        val sourceFiltered = when (controls.sourceFilter) {
            SourceFilter.ALL -> allTracks
            SourceFilter.YOUTUBE -> allTracks.filter { it.source == MusicSource.YOUTUBE }
            SourceFilter.SPOTIFY -> allTracks.filter { it.source == MusicSource.SPOTIFY || it.source == MusicSource.BOTH }
        }

        // -- Apply client-side search filter --
        val filteredTracks = if (query.isEmpty()) sourceFiltered else sourceFiltered.filter {
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
            // RECENT uses date_added (stable across syncs) not last_synced
            // — the latter reshuffles the list every sync run. See
            // PlaylistEntity.dateAdded + migration v12→v13 (issue #13).
            SortOrder.RECENT -> filteredPlaylists.sortedByDescending { it.dateAdded }
            SortOrder.ALPHABETICAL -> filteredPlaylists.sortedBy { it.name.lowercase() }
            // Playlists don't track a per-playlist play_count; use
            // trackCount as the most-relevant "size" signal so this
            // chip produces a visible ordering change instead of a
            // silent no-op.
            SortOrder.MOST_PLAYED -> filteredPlaylists.sortedByDescending { it.trackCount }
        }
        // Sort artists/albums — default by track count descending (most tracks first)
        val sortedArtists = when (controls.sortOrder) {
            SortOrder.RECENT -> filteredArtists.sortedByDescending { it.trackCount }
            SortOrder.ALPHABETICAL -> filteredArtists.sortedBy { it.name.lowercase() }
            SortOrder.MOST_PLAYED -> filteredArtists.sortedByDescending { it.trackCount }
        }
        val sortedAlbums = when (controls.sortOrder) {
            SortOrder.RECENT -> filteredAlbums.sortedByDescending { it.trackCount }
            SortOrder.ALPHABETICAL -> filteredAlbums.sortedBy { it.name.lowercase() }
            SortOrder.MOST_PLAYED -> filteredAlbums.sortedByDescending { it.trackCount }
        }

        // Split into multi-track (primary) and single-track (collapsed)
        val multiTrackArtists = sortedArtists.filter { it.trackCount >= 2 }
        val singleTrackArtists = sortedArtists.filter { it.trackCount == 1 }
        val multiTrackAlbums = sortedAlbums.filter { it.trackCount >= 2 }
        val singleTrackAlbums = sortedAlbums.filter { it.trackCount == 1 }

        LibraryUiState(
            activeTab = controls.activeTab,
            searchQuery = controls.searchQuery,
            sortOrder = controls.sortOrder,
            sourceFilter = controls.sourceFilter,
            tracks = sortedTracks,
            playlists = sortedPlaylists,
            artists = multiTrackArtists,
            singleTrackArtists = singleTrackArtists,
            albums = multiTrackAlbums,
            singleTrackAlbums = singleTrackAlbums,
            isLoading = false,
            spotifyConnected = authPair.first,
            youTubeConnected = authPair.second,
        )
    }.combine(playerRepository.playerState) { libraryState, playerState ->
        // Overlay the currently-playing track ID so the UI can highlight it.
        libraryState.copy(
            currentlyPlayingTrackId = playerState.currentTrack?.id,
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

    /** Filter tracks by source (All / YouTube / Spotify). */
    fun setSourceFilter(filter: SourceFilter) {
        _controls.update { it.copy(sourceFilter = filter) }
    }

    /**
     * Begin playback by replacing the queue with [allTracks] and starting
     * at the position of [track].
     */
    fun playTrack(track: Track, allTracks: List<Track>) {
        if (track.filePath == null) return // not downloaded yet
        viewModelScope.launch {
            val downloadedTracks = allTracks.filter { it.filePath != null }
            val index = downloadedTracks.indexOfFirst { it.id == track.id }
            if (index < 0) return@launch // shouldn't happen, but guard against it
            playerRepository.setQueue(downloadedTracks, index)
        }
    }

    /**
     * Insert [track] immediately after the currently-playing track in the queue.
     */
    fun playNext(track: Track) {
        viewModelScope.launch {
            playerRepository.addNext(track)
        }
    }

    /**
     * Append [track] to the end of the current playback queue.
     */
    fun addToQueue(track: Track) {
        viewModelScope.launch {
            playerRepository.addToQueue(track)
        }
    }

    /**
     * Delete [track] from the library: removes the database record and the
     * audio file from disk.
     */
    fun deleteTrack(track: Track) {
        viewModelScope.launch {
            musicRepository.deleteTrack(track)
        }
    }

    // ── Playlist actions ────────────────────────────────────────────────

    /**
     * Load all downloaded tracks for [playlist] and begin playback from the first track.
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
     * Load all downloaded tracks for [playlist] and append each to the playback queue.
     */
    fun addPlaylistToQueue(playlist: Playlist) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
            val downloaded = tracks.filter { it.filePath != null }
            downloaded.forEach { playerRepository.addToQueue(it) }
        }
    }

    /**
     * Delete a playlist + its tracks from the library.
     *
     * Routes through [MusicRepository.deletePlaylistWithCascade] — the
     * same atomic-transaction path Home uses for its long-press "delete
     * playlist and songs" action. The earlier ad-hoc implementation fired
     * N separate `deleteTrack` statements in a loop; each invalidated
     * Room's InvalidationTracker, which retriggered the Library UI's
     * live `getAllByDateAdded()` Flow mid-iteration, causing its
     * CursorWindow to be recycled underneath the reader and crashing
     * the app with `IllegalStateException: Couldn't read row N, col 0
     * from CursorWindow`. The cascade path invalidates once at commit,
     * so the Flow re-reads from a fresh cursor exactly once. Fixes #14.
     *
     * User-uploaded cover image is a separate filesystem artifact the
     * cascade doesn't know about — delete it here before delegating.
     */
    fun deletePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            playlistImageHelper.deletePlaylistCoverFile(playlist.id)
            musicRepository.deletePlaylistWithCascade(
                playlistId = playlist.id,
                alsoBlacklist = false,
            )
        }
    }

    /** Remove playlist from library without deleting its downloaded tracks. */
    fun removePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            musicRepository.removePlaylist(playlist)
        }
    }

    fun setPlaylistImage(playlistId: Long, imageUri: Uri) {
        viewModelScope.launch {
            val artUrl = playlistImageHelper.savePlaylistCoverImage(playlistId, imageUri)
            if (artUrl != null) {
                musicRepository.updatePlaylistArtUrl(playlistId, artUrl)
            }
        }
    }

    fun removePlaylistImage(playlistId: Long) {
        viewModelScope.launch {
            playlistImageHelper.deletePlaylistCoverFile(playlistId)
            musicRepository.updatePlaylistArtUrl(playlistId, null)
        }
    }

    // ── Artist actions ──────────────────────────────────────────────────

    /**
     * Load all downloaded tracks by [artistName] and begin playback from the first track.
     */
    fun playArtist(artistName: String) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByArtist(artistName).first()
            val downloaded = tracks.filter { it.filePath != null }
            if (downloaded.isNotEmpty()) {
                playerRepository.setQueue(downloaded, startIndex = 0)
            }
        }
    }

    /**
     * Load all downloaded tracks by [artistName] and append each to the playback queue.
     */
    fun addArtistToQueue(artistName: String) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByArtist(artistName).first()
            val downloaded = tracks.filter { it.filePath != null }
            downloaded.forEach { playerRepository.addToQueue(it) }
        }
    }

    /** Delete all downloaded tracks by [artistName] from disk and DB. */
    fun deleteArtist(artistName: String) {
        viewModelScope.launch {
            val tracks = musicRepository.getTracksByArtist(artistName).first()
            tracks.forEach { musicRepository.deleteTrack(it) }
        }
    }

    // ── Album actions ───────────────────────────────────────────────────

    /**
     * Load all downloaded tracks matching [albumName] by [artist] and begin playback.
     * Filters from allTracks since there is no dedicated getTracksByAlbum query.
     */
    fun playAlbum(albumName: String, artist: String) {
        viewModelScope.launch {
            val allTracks = musicRepository.getAllTracks().first()
            val downloaded = allTracks.filter {
                it.album.equals(albumName, ignoreCase = true)
                    && it.artist.equals(artist, ignoreCase = true)
                    && it.filePath != null
            }
            if (downloaded.isNotEmpty()) {
                playerRepository.setQueue(downloaded, startIndex = 0)
            }
        }
    }

    /**
     * Load all downloaded tracks matching [albumName] by [artist] and append each to the queue.
     */
    fun addAlbumToQueue(albumName: String, artist: String) {
        viewModelScope.launch {
            val allTracks = musicRepository.getAllTracks().first()
            val downloaded = allTracks.filter {
                it.album.equals(albumName, ignoreCase = true)
                    && it.artist.equals(artist, ignoreCase = true)
                    && it.filePath != null
            }
            downloaded.forEach { playerRepository.addToQueue(it) }
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
    val sourceFilter: SourceFilter = SourceFilter.ALL,
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
