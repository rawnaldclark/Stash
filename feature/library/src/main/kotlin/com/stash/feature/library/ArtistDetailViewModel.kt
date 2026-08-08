package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.BulkPlayAction
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Track
import com.stash.core.ui.util.withSearchFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val photoUrl: String? = null,
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
    private val artistImageDao: ArtistImageDao,
) : ViewModel() {

    /** The artist name extracted from the navigation route arguments. */
    private val artistName: String = checkNotNull(savedStateHandle.get<String>("artistName")) {
        "artistName is required but was not found in SavedStateHandle"
    }

    private val _searchQuery = MutableStateFlow("")
    private val _showSearch = MutableStateFlow(false)

    private val _tappedTrackId = MutableStateFlow<Long?>(null)
    val tappedTrackId: StateFlow<Long?> = _tappedTrackId.asStateFlow()

    private val _bulkPlayInFlight = MutableStateFlow<BulkPlayAction?>(null)
    val bulkPlayInFlight: StateFlow<BulkPlayAction?> = _bulkPlayInFlight.asStateFlow()

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
        artistImageDao.observeAll(),
    ) { tracks, playerState, query, showSearch, images ->
        // Photo cache is keyed by the SAME primary-artist name the Library
        // Artists tab groups by, so a case-insensitive name lookup lines the
        // header up with the grid photo regardless of which entry point
        // (grid, album hero, Now Playing) navigated here.
        val photoUrl = images.firstOrNull {
            it.artistName.equals(artistName, ignoreCase = true)
        }?.imageUrl
        ArtistDetailUiState(
            artistName = artistName,
            tracks = tracks,
            isLoading = false,
            currentlyPlayingTrackId = playerState.currentTrack?.id,
            searchQuery = query,
            showSearch = showSearch,
            photoUrl = photoUrl,
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
            _tappedTrackId.value = trackId
            try {
                val downloaded = uiState.value.tracks.filter { it.filePath != null }
                if (downloaded.isEmpty()) return@launch
                val index = downloaded.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
                playerRepository.setQueue(downloaded, index)
            } finally {
                _tappedTrackId.value = null
            }
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
            val shuffled = downloaded.shuffled()
            _tappedTrackId.value = shuffled[0].id
            _bulkPlayInFlight.value = BulkPlayAction.SHUFFLE_ALL
            try {
                playerRepository.setQueue(shuffled, 0)
            } finally {
                _tappedTrackId.value = null
                _bulkPlayInFlight.compareAndSet(BulkPlayAction.SHUFFLE_ALL, null)
            }
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

    // ── Batch (multi-select) actions ─────────────────────────────────────
    // Mirrors LikedSongsDetailViewModel / PlaylistDetailViewModel: each batch
    // wraps the existing single-track repo path for the multi-select toolbar.
    // Queue uses the batch addToQueue(List) overload (single call); Play Next
    // loops addNext; download/remove/save loop the per-id repo calls.
    //
    // Artist detail has NO delete batch (no deleteSelected) — the toolbar shows
    // only Play next / Add to queue / Add to playlist / Download.
    //
    // Looped batches isolate per-item failures (one bad item must not abort
    // the rest) and emit a SINGLE roll-up Snackbar. CancellationException is
    // always re-thrown so structured-concurrency cancellation still propagates.

    /**
     * Insert each of [tracks] after the currently-playing track, in order.
     * Silent — the single-track [playNext] emits no message.
     */
    fun playSelectedNext(tracks: List<Track>) {
        viewModelScope.launch {
            tracks.forEach {
                runCatching { playerRepository.addNext(it) }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
        }
    }

    /**
     * Append [tracks] to the queue via the batch overload (single call).
     * Silent — the single-track [addToQueue] emits no message.
     */
    fun addSelectedToQueue(tracks: List<Track>) {
        viewModelScope.launch {
            playerRepository.addToQueue(tracks)
        }
    }

    /** Queue each of [trackIds] for download. Emits one roll-up Snackbar. */
    fun downloadSelected(trackIds: List<Long>) {
        viewModelScope.launch {
            var succeeded = 0
            trackIds.forEach { id ->
                runCatching { musicRepository.queueDownload(id) }
                    .onSuccess { queued -> if (queued) succeeded++ }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
            if (succeeded > 0) {
                _userMessages.tryEmit("Queued $succeeded ${songs(succeeded)} for download.")
            }
        }
    }

    /**
     * Remove the on-disk file for each of [trackIds], keeping streamable rows.
     * Emits one roll-up Snackbar.
     */
    fun removeDownloadsForSelected(trackIds: List<Long>) {
        viewModelScope.launch {
            var succeeded = 0
            trackIds.forEach { id ->
                runCatching { musicRepository.removeDownload(id) }
                    .onSuccess { succeeded++ }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
            if (succeeded > 0) {
                _userMessages.tryEmit("Removed downloads for $succeeded ${songs(succeeded)}.")
            }
        }
    }

    /** Queue a single track for download (artist-detail ⋮ menu). */
    fun queueDownload(trackId: Long) {
        viewModelScope.launch {
            musicRepository.queueDownload(trackId)
            _userMessages.tryEmit("Queued for download.")
        }
    }

    /** Remove a single track's on-disk file, keeping the streamable row. */
    fun removeDownload(trackId: Long) {
        viewModelScope.launch {
            musicRepository.removeDownload(trackId)
            _userMessages.tryEmit("Download removed.")
        }
    }

    /** Add each of [trackIds] to the playlist identified by [playlistId]. */
    fun saveSelectedToPlaylist(trackIds: List<Long>, playlistId: Long) {
        viewModelScope.launch {
            trackIds.forEach { id ->
                runCatching { musicRepository.addTrackToPlaylist(id, playlistId) }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
        }
    }

    /** Create a new playlist and add the whole batch of [trackIds] to it. */
    fun createPlaylistAndAddTracks(name: String, trackIds: List<Long>) {
        viewModelScope.launch {
            val id = musicRepository.createPlaylist(name)
            trackIds.forEach { trackId ->
                runCatching { musicRepository.addTrackToPlaylist(trackId, id) }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
        }
    }

    /** "song" / "songs" for [count]-aware roll-up messages. */
    private fun songs(count: Int): String = if (count == 1) "song" else "songs"

    private val _userMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** Snackbar-targeted roll-up messages from the batch actions. */
    val userMessages: kotlinx.coroutines.flow.SharedFlow<String> =
        _userMessages.asSharedFlow()
}
