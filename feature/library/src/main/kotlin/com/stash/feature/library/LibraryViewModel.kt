package com.stash.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.files.LocalImportState
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.net.Uri
import javax.inject.Inject

/**
 * Lossless codec tags. Duplicates the canonical set in
 * `com.stash.data.download.lossless.AudioFormat.LOSSLESS_CODECS`
 * to avoid a `:feature:library` → `:data:download` dependency just
 * for a string set.
 */
private val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "ape", "tta", "wv", "aiff")

/** Shared track search predicate — every Library list matches title/artist/album. */
private fun Track.matchesQuery(query: String): Boolean =
    title.lowercase().contains(query) ||
        artist.lowercase().contains(query) ||
        album.lowercase().contains(query)

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
    private val streamingPreference: StreamingPreference,
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

    init {
        // Smart-default: if the user already has lossless tracks, open
        // Library to TRACKS / RECENT / FLAC instead of TRACKS / RECENT
        // / ALL. One-shot snapshot read at cold start; the user's
        // mid-session filter changes are honoured (we never fight back).
        viewModelScope.launch {
            val firstSnapshot = musicRepository.getAllTracks().first()
            val hasLossless = firstSnapshot.any {
                it.fileFormat.lowercase() in LOSSLESS_CODECS
            }
            if (hasLossless && _controls.value.sourceFilter == SourceFilter.ALL) {
                _controls.update { it.copy(sourceFilter = SourceFilter.FLAC) }
            }
        }
    }

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
     * Playlists + recently-downloaded folded into ONE holder so the base
     * `uiState` combine stays within Kotlin's 5-arg typed limit and observes
     * `getAllPlaylists()` exactly once (it's read back out of this holder).
     */
    private val libraryDataFlow = combine(
        musicRepository.getAllPlaylists(),
        musicRepository.getRecentlyAdded(20),
    ) { playlists, recentlyAdded ->
        LibraryData(playlists = playlists, recentlyAdded = recentlyAdded)
    }

    /**
     * Combined UI state that reacts to both data changes and user interactions.
     */
    val uiState: StateFlow<LibraryUiState> = combine(
        _controls,
        musicRepository.getAllTracks(),
        libraryDataFlow,
        musicRepository.getAllArtists(),
        musicRepository.getAllAlbums(),
    ) { controls, allTracks, libraryData, allArtists, allAlbums ->
        DataSnapshot(controls, allTracks, libraryData, allArtists, allAlbums)
    }.combine(authStateFlow) { snapshot, authPair ->
        val controls = snapshot.controls
        val allTracks = snapshot.allTracks
        val libraryData = snapshot.libraryData
        // Playlists tab shows user (CUSTOM) playlists only — mixes and Liked
        // Songs live on Home now, so surfacing them here would double them up.
        val allPlaylists = libraryData.playlists.filter { it.type == PlaylistType.CUSTOM }
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
            // Codec set kept in sync with com.stash.core.ui.components.FlacBadge
            // (and com.stash.data.download.lossless.AudioFormat.LOSSLESS_CODECS).
            // Worth duplicating — short list, short reach across modules.
            SourceFilter.FLAC -> allTracks.filter { it.fileFormat.lowercase() in LOSSLESS_CODECS }
        }

        // -- Apply client-side search filter --
        val filteredTracks = if (query.isEmpty()) sourceFiltered else sourceFiltered.filter {
            it.matchesQuery(query)
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
            SortOrder.DURATION -> filteredTracks.sortedByDescending { it.durationMs }
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
            // Playlists have no duration — fall back to the RECENT ordering.
            SortOrder.DURATION -> filteredPlaylists.sortedByDescending { it.dateAdded }
        }
        // Sort artists/albums — default by track count descending (most tracks first)
        val sortedArtists = when (controls.sortOrder) {
            SortOrder.RECENT -> filteredArtists.sortedByDescending { it.trackCount }
            SortOrder.ALPHABETICAL -> filteredArtists.sortedBy { it.name.lowercase() }
            SortOrder.MOST_PLAYED -> filteredArtists.sortedByDescending { it.trackCount }
            SortOrder.DURATION -> filteredArtists.sortedByDescending { it.totalDurationMs }
        }
        val sortedAlbums = when (controls.sortOrder) {
            SortOrder.RECENT -> filteredAlbums.sortedByDescending { it.trackCount }
            SortOrder.ALPHABETICAL -> filteredAlbums.sortedBy { it.name.lowercase() }
            SortOrder.MOST_PLAYED -> filteredAlbums.sortedByDescending { it.trackCount }
            // Albums carry no duration projection — fall back to track count.
            SortOrder.DURATION -> filteredAlbums.sortedByDescending { it.trackCount }
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
            recentlyAdded = libraryData.recentlyAdded,
            artists = multiTrackArtists,
            singleTrackArtists = singleTrackArtists,
            albums = multiTrackAlbums,
            singleTrackAlbums = singleTrackAlbums,
            isLoading = false,
            spotifyConnected = authPair.first,
            youTubeConnected = authPair.second,
            // Unfiltered library size for the Shuffle hero (independent of the
            // active source/search filter, which only narrows the visible list).
            librarySongCount = allTracks.size,
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

    // ── Liked subcategory (browse + sift likes by origin) ────────────────

    private val _likedFilter = MutableStateFlow(LikedFilter.ALL)
    val likedFilter: StateFlow<LikedFilter> = _likedFilter.asStateFlow()
    fun setLikedFilter(filter: LikedFilter) { _likedFilter.update { filter } }

    private val stashLikedFlow = musicRepository.getPlaylistsByType(PlaylistType.STASH_LIKED)
    private val externalLikedFlow = musicRepository.getPlaylistsByType(PlaylistType.LIKED_SONGS)

    /** Which like-origins actually have songs — drives the sift chips' visibility. */
    val likedSources: StateFlow<Set<LikedFilter>> =
        combine(stashLikedFlow, externalLikedFlow) { stash, external ->
            buildSet {
                if (stash.any { it.trackCount > 0 }) add(LikedFilter.STASH)
                if (external.any { (it.source == MusicSource.SPOTIFY || it.source == MusicSource.BOTH) && it.trackCount > 0 }) {
                    add(LikedFilter.SPOTIFY)
                }
                if (external.any { it.source == MusicSource.YOUTUBE && it.trackCount > 0 }) add(LikedFilter.YOUTUBE)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Liked tracks for the current [likedFilter], de-duped across the liked
     * playlists, then narrowed by the header search query — the Liked tab
     * used to be the one list [ControlState.searchQuery] never reached
     * (issue #293: searching on Liked kept showing the full list).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val likedTracks: StateFlow<List<Track>> =
        combine(stashLikedFlow, externalLikedFlow, _likedFilter) { stash, external, filter ->
            when (filter) {
                LikedFilter.ALL -> stash + external
                LikedFilter.STASH -> stash
                LikedFilter.SPOTIFY -> external.filter { it.source == MusicSource.SPOTIFY || it.source == MusicSource.BOTH }
                LikedFilter.YOUTUBE -> external.filter { it.source == MusicSource.YOUTUBE }
            }
        }.flatMapLatest { playlists ->
            if (playlists.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(playlists.map { musicRepository.getTracksByPlaylist(it.id) }) { arrays ->
                    arrays.flatMap { it.toList() }.distinctBy { it.id }
                }
            }
        }.let { likedFlow ->
            combine(likedFlow, _controls) { tracks, controls ->
                val query = controls.searchQuery.trim().lowercase()
                if (query.isEmpty()) tracks else tracks.filter { it.matchesQuery(query) }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Play the liked list starting at [track] (offline-aware, like the detail screen). */
    fun playLiked(track: Track) {
        viewModelScope.launch {
            val all = likedTracks.value
            val playable = if (streamingPreference.current()) all else all.filter { it.filePath != null }
            if (playable.isEmpty()) return@launch
            val index = playable.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playerRepository.setQueue(playable, index)
        }
    }

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
     * Delete [track] from the library. When [alsoBlacklist] is true the
     * track is kept as a blacklisted tombstone (row retained so future
     * sync identity matches still see it and skip re-downloading); when
     * false the row is removed outright and the track will come back on
     * the next sync if a playlist still references its identity. Matches
     * the Home/Playlist-detail UX — "Delete" vs. "Delete & Block".
     */
    fun deleteTrack(track: Track, alsoBlacklist: Boolean = false) {
        viewModelScope.launch {
            if (alsoBlacklist) {
                musicRepository.blacklistTrack(track.id)
            } else {
                musicRepository.deleteTrack(track)
            }
        }
    }

    // ── Batch (multi-select) actions — Tracks tab ────────────────────────
    // Each wraps the existing single-track path for the multi-select toolbar.
    // Queue uses the batch addToQueue(List) overload (single call); Play Next
    // loops addNext; download/remove/save/delete loop the per-id repo calls.
    //
    // Looped batches isolate per-item failures (one bad item must not abort
    // the rest) and emit a SINGLE roll-up Snackbar. CancellationException is
    // always re-thrown so structured-concurrency cancellation still
    // propagates (project rule). Mirrors PlaylistDetailViewModel's batch path.

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
                    .onSuccess { succeeded++ }
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

    /** Add each of [trackIds] to the playlist identified by [playlistId]. Silent. */
    fun saveSelectedToPlaylist(trackIds: List<Long>, playlistId: Long) {
        viewModelScope.launch {
            trackIds.forEach { id ->
                runCatching { musicRepository.addTrackToPlaylist(id, playlistId) }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
        }
    }

    /** Create a new playlist and add the whole batch of [trackIds] to it. Silent. */
    fun createPlaylistAndAddTracks(name: String, trackIds: List<Long>) {
        viewModelScope.launch {
            val playlistId = musicRepository.createPlaylist(name)
            trackIds.forEach { id ->
                runCatching { musicRepository.addTrackToPlaylist(id, playlistId) }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
        }
    }

    /**
     * Delete each of [tracks] from the library, mirroring the single-track
     * [deleteTrack] (blacklist-tombstone when [alsoBlacklist], hard-delete
     * otherwise). Per-item failures are isolated; emits one roll-up Snackbar.
     */
    fun deleteSelected(tracks: List<Track>, alsoBlacklist: Boolean = false) {
        viewModelScope.launch {
            var deleted = 0
            tracks.forEach { track ->
                runCatching {
                    if (alsoBlacklist) {
                        musicRepository.blacklistTrack(track.id)
                    } else {
                        musicRepository.deleteTrack(track)
                    }
                }.onSuccess { deleted++ }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
            if (deleted > 0) {
                _userMessages.tryEmit("Deleted $deleted ${songs(deleted)}.")
            }
        }
    }

    /** "song" / "songs" for [count]-aware roll-up messages. */
    private fun songs(count: Int): String = if (count == 1) "song" else "songs"

    private val _userMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Snackbar-targeted roll-up messages from the batch (multi-select) actions. */
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    /** User-created playlists for the batch Save to Playlist picker. */
    val userPlaylists: kotlinx.coroutines.flow.Flow<List<Playlist>> =
        musicRepository.getUserCreatedPlaylists()

    // ── Playlist actions ────────────────────────────────────────────────

    /**
     * v0.9.14: Shuffle the entire downloaded library. Replaces the current
     * queue with a freshly-randomised snapshot of every downloaded track and
     * arms the player's auto-grow watcher so playback runs indefinitely
     * without the user having to rebuild a queue every album.
     *
     * Driven by the "Shuffle Library" card at the top of the Library tab —
     * a fix for the v0.9.13 complaint that per-playlist shuffle queues felt
     * like the same 30 songs on repeat with 1700+ tracks downloaded.
     */
    fun shuffleLibrary() {
        viewModelScope.launch {
            playerRepository.shuffleLibrary()
        }
    }

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
    fun deletePlaylist(playlist: Playlist, alsoBlacklist: Boolean = false) {
        viewModelScope.launch {
            playlistImageHelper.deletePlaylistCoverFile(playlist.id)
            musicRepository.deletePlaylistWithCascade(
                playlistId = playlist.id,
                alsoBlacklist = alsoBlacklist,
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

    // ── Playlist create / delete-preview ─────────────────────────────────

    /**
     * Preview counts the UI uses in the delete-confirmation dialog:
     * how many tracks would actually be removed vs. kept due to
     * protected-playlist membership.
     */
    suspend fun previewPlaylistDelete(playlist: Playlist): DeletePreview {
        val tracks = musicRepository.getTracksByPlaylist(playlist.id).first()
        var protected = 0
        for (track in tracks) {
            // isTrackInProtectedPlaylist returns true if the track is in
            // Liked Songs / custom playlists OTHER than [playlist]. We
            // have to do the "other than" filtering here because the DAO
            // query doesn't exclude the source playlist.
            val inProtectedElsewhere = musicRepository.isTrackProtectedExcluding(
                trackId = track.id,
                excludePlaylistId = playlist.id,
            )
            if (inProtectedElsewhere) protected++
        }
        return DeletePreview(
            totalTracks = tracks.size,
            protectedCount = protected,
        )
    }

    private val _lastCascadeSummary =
        kotlinx.coroutines.flow.MutableSharedFlow<com.stash.core.data.repository.MusicRepository.CascadeRemovalSummary>(
            extraBufferCapacity = 1,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    /** One-shot cascade summaries for the delete Snackbar. */
    val lastCascadeSummary: kotlinx.coroutines.flow.SharedFlow<com.stash.core.data.repository.MusicRepository.CascadeRemovalSummary> =
        _lastCascadeSummary.asSharedFlow()

    /** Preview counts shown in the playlist-delete confirmation dialog. */
    data class DeletePreview(
        val totalTracks: Int,
        val protectedCount: Int,
    ) {
        val willDelete: Int get() = totalTracks - protectedCount
    }

    /**
     * Creates a new empty custom playlist with the given [name]. Trims input
     * and no-ops if the trimmed name is blank. The new playlist will appear
     * in the Library Playlists section automatically (Room Flow).
     */
    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            musicRepository.createPlaylist(trimmed)
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
    val activeTab: LibraryTab = LibraryTab.TRACKS,
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
    val libraryData: LibraryData,
    val allArtists: List<com.stash.core.data.db.dao.ArtistSummary>,
    val allAlbums: List<com.stash.core.data.db.dao.AlbumSummary>,
)

/**
 * Internal holder bundling the playlists with the recently-downloaded tracks
 * so the base [combine] treats them as one positional arg (and observes
 * `getAllPlaylists()` exactly once).
 */
private data class LibraryData(
    val playlists: List<Playlist>,
    val recentlyAdded: List<Track>,
)
