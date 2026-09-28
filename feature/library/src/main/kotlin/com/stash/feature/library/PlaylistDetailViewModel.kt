package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.mix.MixBuildState
import com.stash.core.data.mix.mixBuildState
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.BulkPlayAction
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.Playlist
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    private val connectivityMonitor: ConnectivityMonitor,
    private val recipeDao: StashMixRecipeDao,
    private val discoveryQueueDao: DiscoveryQueueDao,
    private val sharedMixRepository: com.stash.core.data.share.SharedMixRepository,
) : ViewModel() {

    /** The playlist ID extracted from the navigation route arguments. */
    private val playlistId: Long = checkNotNull(savedStateHandle.get<Long>("playlistId")) {
        "playlistId is required but was not found in SavedStateHandle"
    }

    /** Home's "Share mix" opens this screen with the share sheet already up. */
    val openShare: Boolean = savedStateHandle.get<Boolean>("openShare") ?: false

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

    /** Holds the one-shot playlist metadata fetched in [init]. */
    private val _playlist = MutableStateFlow<Playlist?>(null)

    /** Non-null when this playlist is a followed mix (spec §6). Declared after [_playlist]: initialisers run in order. */
    data class FollowUi(val readOnly: Boolean, val sharedBy: String?, val downloadOn: Boolean)

    val follow: StateFlow<FollowUi?> = combine(
        sharedMixRepository.observe(playlistId),
        _playlist,
    ) { row, playlist ->
        row?.takeIf { it.role == com.stash.core.data.db.entity.SharedMixEntity.ROLE_FOLLOWER }?.let {
            FollowUi(
                // A REMOVED follow (owner stopped sharing) is an ordinary editable playlist.
                readOnly = it.status == com.stash.core.data.db.entity.SharedMixEntity.STATUS_ACTIVE,
                sharedBy = it.sharedBy,
                downloadOn = playlist?.syncEnabled == true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setFollowDownload(on: Boolean) = viewModelScope.launch {
        if (followAction("change download for") { sharedMixRepository.setDownload(playlistId, on) }) {
            _playlist.value = _playlist.value?.copy(syncEnabled = on)
        } else {
            _userMessages.tryEmit("Couldn't change that. Try again.")
        }
    }

    /**
     * The page's own Download switch (#474); null = this page has none. Imported mixes and
     * playlists, the user's own included, get one. Stash Mixes, Your Downloads and Liked Songs
     * don't, and nor does a read-only followed mix: it keeps its "Download this mix" row.
     * On = kept on the phone, or, in Download mode, already downloading through the Sync tab.
     * Reads the raw follow row, not [follow]: that one starts null and would flash this row.
     */
    val download: StateFlow<Boolean?> = combine(
        _playlist,
        streamingPreference.enabled,
        sharedMixRepository.observe(playlistId),
    ) { playlist, streamOnly, shared ->
        val readOnlyFollow = shared?.role == com.stash.core.data.db.entity.SharedMixEntity.ROLE_FOLLOWER &&
            shared.status == com.stash.core.data.db.entity.SharedMixEntity.STATUS_ACTIVE
        playlist?.takeIf { it.type in DOWNLOAD_SWITCH_TYPES && !readOnlyFollow }
            ?.let { it.keepOffline || (!streamOnly && it.syncEnabled) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The switch follows the playlist row (see init), so success needs no local copy. */
    fun setDownload(on: Boolean) = viewModelScope.launch {
        if (!followAction("change download for") { musicRepository.setPlaylistDownload(playlistId, on) }) {
            _userMessages.tryEmit("Couldn't change that. Try again.")
        }
    }

    /** [onDone] runs only once the follow is really gone. */
    fun unfollow(onDone: () -> Unit) = viewModelScope.launch {
        if (followAction("unfollow") { sharedMixRepository.unfollow(playlistId) }) onDone()
        else _userMessages.tryEmit("Couldn't unfollow this mix. Try again.")
    }

    /** A failed DB/IO call becomes false (and a log line), never a crash. */
    private suspend fun followAction(action: String, block: suspend () -> Unit): Boolean = try {
        block(); true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("PlaylistDetailVM", "$action $playlistId failed", e)
        false
    }

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

    /**
     * Build state for THIS playlist if it's a custom Stash Mix — drives the
     * "Building your mix…" / "Couldn't find tracks" states while a freshly
     * created mix populates. READY for non-mix playlists and built-ins.
     */
    val buildState: StateFlow<MixBuildState> = combine(
        recipeDao.observeAll(),
        discoveryQueueDao.observeNonFailedCountsByRecipe(),
        musicRepository.getTracksByPlaylist(playlistId),
    ) { recipes, counts, tracks ->
        val recipe = recipes.firstOrNull { it.playlistId == playlistId }
            ?: return@combine MixBuildState.READY
        val count = counts.firstOrNull { it.recipeId == recipe.id }?.count ?: 0
        mixBuildState(recipe, trackCount = tracks.size, nonFailedDiscoveryCount = count)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MixBuildState.READY,
    )

    init {
        loadPlaylistMetadata()
        // The header follows the row: a followed mix renamed by its owner's update, both Download switches.
        viewModelScope.launch {
            musicRepository.observePlaylist(playlistId).collect { live ->
                if (live != null) {
                    _playlist.value = _playlist.value?.copy(
                        name = live.name,
                        syncEnabled = live.syncEnabled,
                        keepOffline = live.keepOffline,
                    )
                }
            }
        }
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
     * Sets the playback queue to this playlist and begins playback from the
     * track matching [trackId]. Keyed by id rather than position so the
     * caller never depends on the queue and the list being the same shape.
     */
    fun playTrack(trackId: Long) {
        // ── Stream-only tap guard ──
        // A track with no local audio needs a live connection; without one the
        // player would fail it silently, so bail out early with a Snackbar so
        // the user knows *why* nothing happened. Downloaded tracks always play.
        // (The Download switch is not consulted: it decides what sync writes
        // to disk, never what plays.)
        viewModelScope.launch {
            val tapped = uiState.value.tracks.firstOrNull { it.id == trackId }
            if (tapped != null && tapped.isStreamable && !tapped.isDownloaded) {
                if (!connectivityMonitor.isConnected()) {
                    _userMessages.tryEmit("Online only — connect to play this track")
                    return@launch
                }
            }

            _tappedTrackId.value = trackId
            try {
                val playable = playableTracks()
                if (playable.isEmpty()) return@launch
                val index = playable.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
                playerRepository.setQueue(playable, index)
            } finally {
                _tappedTrackId.value = null
            }
        }
    }

    /**
     * The tracks to enqueue: the whole list. Streaming is always allowed, so a
     * track that is not downloaded resolves when it is reached (a
     * `stash-resolve://` placeholder inside [PlayerRepository.setQueue]).
     * Until 2026-09-17 Offline mode narrowed this to downloaded rows.
     */
    private suspend fun playableTracks(): List<Track> = uiState.value.tracks

    /** Shuffles the whole playlist and begins playback. */
    fun shuffleAll() {
        viewModelScope.launch {
            val playable = playableTracks()
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

    /**
     * Plays the playlist in order starting at the first track. Streaming-mode
     * aware — same filter as [shuffleAll].
     */
    fun playAll() {
        viewModelScope.launch {
            val playable = playableTracks()
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
    /**
     * Queue [trackId] for download. Inserts a row into `download_queue`
     * and kicks the discovery worker — see [MusicRepository.queueDownload].
     */
    fun queueDownload(trackId: Long) {
        viewModelScope.launch {
            val queued = musicRepository.queueDownload(trackId)
            _userMessages.tryEmit(
                if (queued) "Queued for download." else "Couldn't queue download.",
            )
        }
    }

    /**
     * Delete the on-disk file but keep the streamable row. ExoPlayer's
     * open FD keeps any currently-playing audio alive until track end.
     */
    fun removeDownload(trackId: Long) {
        viewModelScope.launch {
            musicRepository.removeDownload(trackId)
            _userMessages.tryEmit("Download removed.")
        }
    }

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

    // ── Batch (multi-select) actions ─────────────────────────────────────
    // Each wraps the existing single-track path for the multi-select toolbar.
    // Queue uses the batch addToQueue(List) overload (single call); Play Next
    // loops addNext; download/remove/save/delete loop the per-id repo calls.
    //
    // Looped batches isolate per-item failures (one bad item must not abort
    // the rest) and emit a SINGLE roll-up Snackbar mirroring the single-track
    // wording. CancellationException is always re-thrown so structured-
    // concurrency cancellation still propagates (project rule).

    /**
     * Insert each of [tracks] after the currently-playing track, in order.
     * Silent — the single-track [playNext] emits no message, so neither does
     * the batch (the toolbar dismissing is feedback enough).
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

    /**
     * Delete each of [tracks] from this playlist via the protected-playlist
     * cascade. If [alsoBlacklist] is set, destroyed tracks are marked
     * never-download-again.
     *
     * Aggregates each track's [MusicRepository.CascadeRemovalSummary] into one
     * roll-up Snackbar, mirroring the single-track [deleteTrackFromPlaylist]
     * wording (including the DOWNLOADS_MIX special-case). Per-item failures are
     * isolated so one bad track doesn't abort the rest.
     */
    fun deleteSelected(tracks: List<Track>, alsoBlacklist: Boolean) {
        viewModelScope.launch {
            val isDownloadsMix = uiState.value.playlist?.type == PlaylistType.DOWNLOADS_MIX
            var removed = 0
            var deleted = 0
            var keptProtected = 0
            var keptElsewhere = 0
            var blacklisted = 0
            tracks.forEach { track ->
                runCatching {
                    musicRepository.removeTrackFromPlaylistAndMaybeDelete(
                        trackId = track.id,
                        fromPlaylistId = playlistId,
                        alsoBlacklist = alsoBlacklist,
                    )
                }.onSuccess { summary ->
                    removed++
                    deleted += summary.deleted
                    keptProtected += summary.keptProtected
                    keptElsewhere += summary.keptElsewhere
                    blacklisted += summary.blacklisted
                }.onFailure { e -> if (e is CancellationException) throw e }
            }
            if (removed == 0) return@launch
            val msg = if (isDownloadsMix) {
                "Deleted $removed ${songs(removed)} from your library."
            } else {
                val base = "Removed $removed ${songs(removed)}"
                when {
                    keptProtected > 0 ->
                        "$base. Kept on disk (also in Liked Songs or a custom playlist)."
                    keptElsewhere > 0 ->
                        "$base. Kept on disk (in other playlists)."
                    blacklisted > 0 ->
                        "$base. Blocked from future syncs."
                    deleted > 0 ->
                        "Deleted $removed ${songs(removed)} from your device."
                    else -> base + "."
                }
            }
            _userMessages.tryEmit(msg)
        }
    }

    /** "song" / "songs" for [count]-aware roll-up messages. */
    private fun songs(count: Int): String = if (count == 1) "song" else "songs"

    private val _userMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** Snackbar-targeted messages (delete confirmation, errors). */
    val userMessages: kotlinx.coroutines.flow.SharedFlow<String> =
        _userMessages.asSharedFlow()

    // The one-time "stopped sharing" notice (spec §6), driven by the live row so it also fires when the
    // follow flips to REMOVED while this screen is open or restored from the back stack. It fires on each
    // rise of notice_pending (distinctUntilChanged drops Room's same-value re-emits); collect is sequential,
    // so a consume in flight is never re-entered, and consumeRemovedNotice clears the flag (null once cleared).
    // Each consume waits for the Snackbar's collector: the flow has no replay, so an emit before it
    // subscribes would be lost after the flag was already cleared. This init sits after [_userMessages].
    init {
        viewModelScope.launch {
            sharedMixRepository.observe(playlistId)
                .map { it?.noticePending == true }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    _userMessages.subscriptionCount.first { it > 0 }
                    followAction("read the stopped-sharing notice for") {
                        sharedMixRepository.consumeRemovedNotice(playlistId)?.let { _userMessages.tryEmit(it) }
                    }
                }
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

    /** Create a new playlist and add the whole batch of [trackIds] to it. */
    fun createPlaylistAndAddTracks(name: String, trackIds: List<Long>) {
        viewModelScope.launch {
            musicRepository.createPlaylistWithTracks(name, trackIds)
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

    private companion object {
        /** Playlist types whose page has a Download switch (#474). */
        val DOWNLOAD_SWITCH_TYPES = setOf(PlaylistType.DAILY_MIX, PlaylistType.CUSTOM)
    }
}
