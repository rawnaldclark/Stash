package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.BulkPlayAction
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
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
 * Lists the album's songs crediting the card's artist
 * ([MusicRepository.getTracksByAlbum]: every song in Stream only, the downloaded
 * ones in Download) and combines them with the current player state from
 * [PlayerRepository] to highlight the active track row.
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
    private val connectivityMonitor: ConnectivityMonitor,
    /** Start radio from a song's menu (null in tests that don't need it). */
    private val radioStarter: com.stash.core.media.RadioStarter? = null,
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

    private val _tappedTrackId = MutableStateFlow<Long?>(null)
    val tappedTrackId: StateFlow<Long?> = _tappedTrackId.asStateFlow()

    private val _bulkPlayInFlight = MutableStateFlow<BulkPlayAction?>(null)
    val bulkPlayInFlight: StateFlow<BulkPlayAction?> = _bulkPlayInFlight.asStateFlow()

    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }
    fun clearSearch() { _searchQuery.value = "" }
    fun toggleSearch() {
        _showSearch.value = !_showSearch.value
        if (!_showSearch.value) _searchQuery.value = ""
    }

    /**
     * The album's songs: album name compared case-insensitively, the artist matched with
     * `matchesArtistCredits` so a collaboration album's tracks ("Metro Boomin, Travis Scott")
     * still belong to the album card that leads with its primary act ("Metro Boomin").
     *
     * Follows the Download switch: every song in Stream only, the downloaded ones in Download.
     * It used to read the Songs feed (downloaded songs only), so in Stream only most of an
     * album was missing. Its own query also spares mapping the whole library per tracks write.
     */
    private val albumTracks = musicRepository.getTracksByAlbum(albumName, artistName)

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
     * Plays [trackId] with the album's other songs queued around it, the way a playlist does:
     * songs that aren't downloaded stream. It used to queue only the downloaded ones and fall
     * back to the first of them, so tapping any other song silently played a different one.
     *
     * Offline, a song that isn't downloaded can't play, so the tap says why instead. A
     * downloaded song then gets only the downloaded ones around it (see [playableNow]).
     */
    fun playTrack(trackId: Long) {
        viewModelScope.launch {
            val tracks = uiState.value.tracks
            val tapped = tracks.firstOrNull { it.id == trackId } ?: return@launch
            val online = connectivityMonitor.isConnected()
            if (!tapped.isOnPhone() && !online) {
                _userMessages.tryEmit(SONG_NEEDS_CONNECTION)
                return@launch
            }
            _tappedTrackId.value = trackId
            try {
                val queue = playableNow(tracks, online)
                val index = queue.indexOfFirst { it.id == trackId }
                if (index < 0) return@launch
                playerRepository.setQueue(queue, index)
            } finally {
                _tappedTrackId.value = null
            }
        }
    }

    /**
     * Shuffles the album's songs and begins playback: every listed song when online, the
     * downloaded ones when offline (the same rule as [playTrack]).
     *
     * Shuffles the LIST itself (not just the start index) — picking a
     * random start index leaves the rest of the album in original order
     * after the first track, which isn't shuffle.
     */
    fun shuffleAll() {
        viewModelScope.launch {
            val listed = uiState.value.tracks
            val playable = playableNow(listed, connectivityMonitor.isConnected())
            if (playable.isEmpty()) {
                // Songs listed, none playable: offline with nothing downloaded.
                if (listed.isNotEmpty()) _userMessages.tryEmit(ALBUM_NEEDS_CONNECTION)
                return@launch
            }
            val shuffled = playable.shuffled()
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

    /** Start radio on a song's menu: a song radio seeded from [track], as every other song menu starts. */
    fun startRadio(track: Track) {
        val starter = radioStarter ?: return
        viewModelScope.launch { starter.fromSong(track)?.let { _userMessages.tryEmit(it) } }
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
    // Album detail has NO delete batch (no deleteSelected) — the toolbar shows
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

    /** Queue a single track for download (album-detail ⋮ menu). */
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
            musicRepository.addTracksToPlaylist(trackIds, playlistId)
        }
    }

    /** Create a new playlist and add the whole batch of [trackIds] to it. */
    fun createPlaylistAndAddTracks(name: String, trackIds: List<Long>) {
        viewModelScope.launch {
            musicRepository.createPlaylistWithTracks(name, trackIds)
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
