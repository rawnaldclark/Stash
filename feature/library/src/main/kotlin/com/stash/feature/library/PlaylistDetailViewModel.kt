package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.ui.util.withSearchFilter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the Playlist Detail screen.
 *
 * @property playlist              The playlist metadata, or null while loading.
 * @property tracks                The ordered list of tracks belonging to this playlist.
 * @property isLoading             True while the initial data load is in progress.
 * @property currentlyPlayingTrackId The ID of the track currently playing, used
 *                                   to highlight the active row in the list.
 */
data class PlaylistDetailUiState(
    val playlist: Playlist? = null,
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = true,
    val currentlyPlayingTrackId: Long? = null,
    val searchQuery: String = "",
    val showSearch: Boolean = false,
)

/**
 * ViewModel for the Playlist Detail screen.
 *
 * Loads the playlist metadata via a one-shot suspend call and its tracks
 * reactively from [MusicRepository]. Combines both with the current player
 * state from [PlayerRepository] to highlight the active track row.
 *
 * The `playlistId` is extracted from the navigation [SavedStateHandle].
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
    private val playlistImageHelper: PlaylistImageHelper,
) : ViewModel() {

    /** The playlist ID extracted from the navigation route arguments. */
    private val playlistId: Long = checkNotNull(savedStateHandle.get<Long>("playlistId")) {
        "playlistId is required but was not found in SavedStateHandle"
    }

    private val _searchQuery = MutableStateFlow("")
    private val _showSearch = MutableStateFlow(false)

    fun onSearchQueryChanged(query: String) { _searchQuery.value = query }
    fun clearSearch() { _searchQuery.value = "" }
    fun toggleSearch() {
        _showSearch.value = !_showSearch.value
        if (!_showSearch.value) _searchQuery.value = ""
    }

    /** Holds the one-shot playlist metadata fetched in [init]. */
    private val _playlist = MutableStateFlow<Playlist?>(null)

    /**
     * Combined UI state that reacts to:
     * 1. Playlist metadata (one-shot load into [_playlist])
     * 2. Track list changes (reactive Flow from [MusicRepository])
     * 3. Player state changes (to highlight the currently-playing track)
     *
     * Uses [SharingStarted.WhileSubscribed] with a 5-second stop timeout to keep
     * the flow alive across configuration changes without leaking resources.
     */
    val uiState: StateFlow<PlaylistDetailUiState> = combine(
        _playlist,
        musicRepository.getTracksByPlaylist(playlistId).withSearchFilter(_searchQuery),
        playerRepository.playerState,
        _searchQuery,
        _showSearch,
    ) { playlist, tracks, playerState, query, showSearch ->
        PlaylistDetailUiState(
            playlist = playlist,
            tracks = tracks,
            isLoading = playlist == null,
            currentlyPlayingTrackId = playerState.currentTrack?.id,
            searchQuery = query,
            showSearch = showSearch,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaylistDetailUiState(),
    )

    init {
        loadPlaylistMetadata()
    }

    // ── Data loading ────────────────────────────────────────────────────

    /**
     * Loads the playlist metadata (name, art, source, etc.) once.
     * The tracks are loaded reactively via the Flow in [uiState],
     * so only the metadata needs a one-shot fetch.
     */
    private fun loadPlaylistMetadata() {
        viewModelScope.launch {
            _playlist.value = musicRepository.getPlaylistWithTracks(playlistId)
        }
    }

    // ── Playback actions ────────────────────────────────────────────────

    /**
     * Sets the playback queue to all downloaded tracks in this playlist
     * and begins playback from the track matching [trackId].
     *
     * Uses the track ID rather than a positional index because the UI
     * shows all tracks (including non-downloaded ones) but the queue
     * only contains downloaded tracks. A positional index from the UI
     * would point to the wrong track after non-downloaded entries are
     * filtered out.
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
     * Shuffles all downloaded tracks in the playlist and begins playback.
     * Shuffles the list itself (not just the start index).
     */
    fun shuffleAll() {
        viewModelScope.launch {
            val downloaded = uiState.value.tracks.filter { it.filePath != null }
            if (downloaded.isEmpty()) return@launch
            playerRepository.setQueue(downloaded.shuffled(), 0)
        }
    }

    /**
     * Inserts [track] immediately after the currently-playing track in
     * the playback queue.
     */
    fun playNext(track: Track) {
        viewModelScope.launch {
            playerRepository.addNext(track)
        }
    }

    /**
     * Appends [track] to the end of the current playback queue.
     */
    fun addToQueue(track: Track) {
        viewModelScope.launch {
            playerRepository.addToQueue(track)
        }
    }

    /**
     * Delete a track using the protected-playlist cascade against the
     * currently-open playlist. If the track is also in Liked Songs or an
     * in-app custom playlist (other than this one), only the membership
     * in THIS playlist is removed — the file stays so the other lists
     * still play. If [alsoBlacklist] is set, destroyed tracks are also
     * marked never-download-again.
     *
     * The returned cascade summary is emitted on [userMessages] as a
     * human-readable string so the detail screen can show a Snackbar.
     */
    fun deleteTrackFromPlaylist(track: Track, alsoBlacklist: Boolean) {
        viewModelScope.launch {
            val isDownloadsMix = uiState.value.playlist?.type == PlaylistType.DOWNLOADS_MIX
            val summary = musicRepository.removeTrackFromPlaylistAndMaybeDelete(
                trackId = track.id,
                fromPlaylistId = playlistId,
                alsoBlacklist = alsoBlacklist,
            )
            val msg = if (isDownloadsMix) {
                "Deleted from your library."
            } else {
                when {
                    summary.keptProtected > 0 ->
                        "Removed from this playlist. Kept on disk (also in Liked Songs or a custom playlist)."
                    summary.keptElsewhere > 0 ->
                        "Removed from this playlist. Kept on disk (in other playlists)."
                    summary.blacklisted > 0 ->
                        "Deleted and blocked from future syncs."
                    summary.deleted > 0 ->
                        "Deleted from your device."
                    else -> "Removed."
                }
            }
            _userMessages.tryEmit(msg)
        }
    }

    private val _userMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** Snackbar-targeted messages (delete confirmation, errors). */
    val userMessages: kotlinx.coroutines.flow.SharedFlow<String> =
        _userMessages.asSharedFlow()

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

    // ── Playlist cover image ───────────────────────────────────────────

    /**
     * Save a user-picked image as the playlist's cover art.
     *
     * After persisting to the DB, also re-emits [_playlist] with the new
     * [artUrl]. The playlist metadata is loaded once via a suspend call
     * (see [loadPlaylistMetadata]) and is not backed by a reactive Flow,
     * so the detail screen would otherwise keep showing the stale artUrl
     * until the user navigates away and back.
     */
    fun setPlaylistImage(playlistId: Long, imageUri: Uri) {
        viewModelScope.launch {
            val artUrl = playlistImageHelper.savePlaylistCoverImage(playlistId, imageUri)
            if (artUrl != null) {
                musicRepository.updatePlaylistArtUrl(playlistId, artUrl)
                _playlist.value = _playlist.value?.copy(artUrl = artUrl)
            }
        }
    }

    /** Remove the custom cover image, reverting to the default placeholder. */
    fun removePlaylistImage(playlistId: Long) {
        viewModelScope.launch {
            playlistImageHelper.deletePlaylistCoverFile(playlistId)
            musicRepository.updatePlaylistArtUrl(playlistId, null)
            _playlist.value = _playlist.value?.copy(artUrl = null)
        }
    }
}
