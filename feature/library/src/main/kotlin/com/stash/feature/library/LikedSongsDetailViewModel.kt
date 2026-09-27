package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.BulkPlayAction
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LikedSongsDetailUiState(
    val title: String = "Liked Songs",
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
    val source: MusicSource? = null,
    val searchQuery: String = "",
    val showSearch: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class LikedSongsDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
) : ViewModel() {

    private val sourceFilter: MusicSource? =
        savedStateHandle.get<String>("source")?.let { MusicSource.valueOf(it) }

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

    private val tracksFlow = musicRepository.getPlaylistsByType(PlaylistType.LIKED_SONGS)
        .map { playlists ->
            if (sourceFilter != null) playlists.filter { it.source == sourceFilter }
            else playlists
        }
        .flatMapLatest { playlists ->
            if (playlists.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(playlists.map { musicRepository.getTracksByPlaylist(it.id) }) { arrays ->
                    arrays.flatMap { it.toList() }.distinctBy { it.id }
                }
            }
        }

    val uiState: StateFlow<LikedSongsDetailUiState> = combine(
        tracksFlow.withSearchFilter(_searchQuery),
        playerRepository.playerState,
        _searchQuery,
        _showSearch,
    ) { tracks, playerState, query, showSearch ->
        val title = when (sourceFilter) {
            MusicSource.SPOTIFY -> "Liked Songs \u2022 Spotify"
            MusicSource.YOUTUBE -> "Liked Songs \u2022 YouTube"
            else -> "Liked Songs"
        }
        LikedSongsDetailUiState(
            title = title,
            tracks = tracks,
            isLoading = false,
            currentlyPlayingTrackId = playerState.currentTrack?.id,
            source = sourceFilter,
            searchQuery = query,
            showSearch = showSearch,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LikedSongsDetailUiState(),
    )

    fun playTrack(trackId: Long) {
        viewModelScope.launch {
            _tappedTrackId.value = trackId
            try {
                // Streaming is always allowed: the whole list is playable, and
                // streamable tracks resolve inside setQueue when reached.
                val playable = uiState.value.tracks
                if (playable.isEmpty()) return@launch
                val index = playable.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
                playerRepository.setQueue(playable, index)
            } finally {
                _tappedTrackId.value = null
            }
        }
    }

    fun shuffleAll() {
        viewModelScope.launch {
            val playable = uiState.value.tracks
            if (playable.isEmpty()) return@launch
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

    fun playAll() {
        viewModelScope.launch {
            val playable = uiState.value.tracks
            if (playable.isEmpty()) return@launch
            _tappedTrackId.value = playable[0].id
            _bulkPlayInFlight.value = BulkPlayAction.PLAY_ALL
            try {
                playerRepository.setQueue(playable, 0)
            } finally {
                _tappedTrackId.value = null
                _bulkPlayInFlight.compareAndSet(BulkPlayAction.PLAY_ALL, null)
            }
        }
    }

    fun playNext(track: Track) {
        viewModelScope.launch { playerRepository.addNext(track) }
    }

    fun addToQueue(track: Track) {
        viewModelScope.launch { playerRepository.addToQueue(track) }
    }

    fun deleteTrack(track: Track) {
        viewModelScope.launch { musicRepository.deleteTrack(track) }
    }

    fun queueDownload(trackId: Long) {
        viewModelScope.launch { musicRepository.queueDownload(trackId) }
    }

    fun removeDownload(trackId: Long) {
        viewModelScope.launch { musicRepository.removeDownload(trackId) }
    }

    // ── Batch (multi-select) actions ─────────────────────────────────────
    // Mirrors PlaylistDetailViewModel: each batch wraps the existing single-
    // track repo path for the multi-select toolbar. Queue uses the batch
    // addToQueue(List) overload (single call); Play Next loops addNext;
    // download/remove/save/delete loop the per-id repo calls.
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

    /**
     * Remove each of [tracks] from Liked Songs via the single-track unlike
     * path ([MusicRepository.deleteTrack]). Per-item failures are isolated so
     * one bad track doesn't abort the rest; emits one roll-up Snackbar. The
     * single-track [deleteTrack] is silent, so the batch supplies its own
     * summary message.
     */
    fun deleteSelected(tracks: List<Track>) {
        viewModelScope.launch {
            var removed = 0
            tracks.forEach { track ->
                runCatching { musicRepository.deleteTrack(track) }
                    .onSuccess { removed++ }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
            if (removed > 0) {
                _userMessages.tryEmit("Removed $removed ${songs(removed)} from Liked Songs.")
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

    val userPlaylists = musicRepository.getUserCreatedPlaylists()

    fun saveTrackToPlaylist(trackId: Long, playlistId: Long) {
        viewModelScope.launch { musicRepository.addTrackToPlaylist(trackId, playlistId) }
    }

    fun createPlaylistAndAddTrack(name: String, trackId: Long) {
        viewModelScope.launch {
            val id = musicRepository.createPlaylist(name)
            musicRepository.addTrackToPlaylist(trackId, id)
        }
    }
}
