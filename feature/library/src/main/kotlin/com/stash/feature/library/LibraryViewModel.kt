package com.stash.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.mix.MixBuildState
import com.stash.core.data.mix.mixBuildState
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.workers.StashDiscoveryWorker
import com.stash.core.data.sync.workers.StashMixRefreshWorker
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.files.LocalImportState
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context
import android.net.Uri
import android.util.Log
import javax.inject.Inject

/**
 * Lossless codec tags. Duplicates the canonical set in
 * `com.stash.data.download.lossless.AudioFormat.LOSSLESS_CODECS`
 * to avoid a `:feature:library` → `:data:download` dependency just
 * for a string set.
 */
private val LOSSLESS_CODECS = setOf("flac", "alac", "wav", "ape", "tta", "wv", "aiff")

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
    private val recipeDao: StashMixRecipeDao,
    private val discoveryQueueDao: DiscoveryQueueDao,
    // Injected now for use by a later task (per-mix refresh from Library cards);
    // unused in the current combine but wired to keep the ctor stable.
    private val downloadNetworkPreference: DownloadNetworkPreference,
    private val streamingPreference: StreamingPreference,
    @ApplicationContext private val context: Context,
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
     * Recipe + discovery flows folded in alongside the playlists so the mix
     * slices (Stash Mixes, Daily-mix source split, Liked) and per-custom-mix
     * build state ride ONE holder — mirrors [HomeViewModel]'s `musicDataFlow`.
     * `getAllPlaylists()` is observed exactly once here (the base `uiState`
     * combine reads playlists back out of this holder), not twice.
     */
    private val libraryMixDataFlow = combine(
        musicRepository.getAllPlaylists(),
        musicRepository.getRecentlyAdded(20),
        // Folded in here (not as a positional arg to the base combine, which is
        // at the 5-arg typed max) so the recipe-derived sets ride alongside the
        // playlists. Builtin ids are a one-shot suspend read wrapped as a flow.
        recipeDao.observeAll(),
        discoveryQueueDao.observeNonFailedCountsByRecipe(),
        flow { emit(recipeDao.getBuiltinPlaylistIds().toSet()) },
    ) { playlists, recentlyAdded, recipes, discoveryCounts, builtinIds ->
        val customRecipes = recipes.filter { !it.isBuiltin && it.playlistId != null }
        val customMixPlaylistIds = customRecipes.mapNotNull { it.playlistId }.toSet()

        // Per-custom-mix build state (Building… / No tracks), shared with Home.
        val trackCounts = playlists.associate { it.id to it.trackCount }
        val discoveryByRecipe = discoveryCounts.associate { it.recipeId to it.count }
        val buildingMixIds = mutableSetOf<Long>()
        val emptyMixIds = mutableSetOf<Long>()
        for (recipe in customRecipes) {
            val playlistId = recipe.playlistId ?: continue
            when (
                mixBuildState(
                    recipe = recipe,
                    trackCount = trackCounts[playlistId] ?: 0,
                    nonFailedDiscoveryCount = discoveryByRecipe[recipe.id] ?: 0,
                )
            ) {
                MixBuildState.BUILDING -> buildingMixIds.add(playlistId)
                MixBuildState.EMPTY -> emptyMixIds.add(playlistId)
                MixBuildState.READY -> Unit
            }
        }

        // Recipe Stash Mixes, minus the builtin Daily Discover (the Home hero).
        // DOWNLOADS_MIX ("Your Downloads") is a hidden protected system playlist
        // (search/artist one-off downloads), not a user-facing mix — never carded.
        val stashMixes = playlists.filter {
            it.type == PlaylistType.STASH_MIX && it.id !in builtinIds
        }
        // DAILY_MIX split by source — ported verbatim from Home.
        val dailyMixes = playlists.filter { it.type == PlaylistType.DAILY_MIX }
        val spotifyMixes = dailyMixes.filter { it.source == MusicSource.SPOTIFY }
        val youtubeMixes = dailyMixes.filter { it.source == MusicSource.YOUTUBE }
        // Liked Songs: the local STASH_LIKED (heart button) AND the external
        // LIKED_SONGS mirror — both surface as the Liked card.
        val likedPlaylists = playlists.filter {
            it.type == PlaylistType.LIKED_SONGS || it.type == PlaylistType.STASH_LIKED
        }

        LibraryMixData(
            playlists = playlists,
            recentlyAdded = recentlyAdded,
            stashMixes = stashMixes,
            spotifyMixes = spotifyMixes,
            youtubeMixes = youtubeMixes,
            likedPlaylists = likedPlaylists,
            customMixPlaylistIds = customMixPlaylistIds,
            buildingMixIds = buildingMixIds,
            emptyMixIds = emptyMixIds,
        )
    }

    /**
     * Combined UI state that reacts to both data changes and user interactions.
     */
    val uiState: StateFlow<LibraryUiState> = combine(
        _controls,
        musicRepository.getAllTracks(),
        libraryMixDataFlow,
        musicRepository.getAllArtists(),
        musicRepository.getAllAlbums(),
    ) { controls, allTracks, mixData, allArtists, allAlbums ->
        DataSnapshot(controls, allTracks, mixData, allArtists, allAlbums)
    }.combine(authStateFlow) { snapshot, authPair ->
        val controls = snapshot.controls
        val allTracks = snapshot.allTracks
        val mixData = snapshot.mixData
        // Playlists tab shows user (CUSTOM) playlists only — mixes and Liked
        // Songs render in the dedicated Mixes group (mixData.stashMixes/…),
        // so surfacing them here too would double them up.
        val allPlaylists = mixData.playlists.filter { it.type == PlaylistType.CUSTOM }
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
            stashMixes = mixData.stashMixes,
            spotifyMixes = mixData.spotifyMixes,
            youtubeMixes = mixData.youtubeMixes,
            likedPlaylists = mixData.likedPlaylists,
            recentlyAdded = mixData.recentlyAdded,
            customMixPlaylistIds = mixData.customMixPlaylistIds,
            buildingMixIds = mixData.buildingMixIds,
            emptyMixIds = mixData.emptyMixIds,
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

    // ── Mix actions (ported from Home) ───────────────────────────────────

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

    /**
     * Manually re-run the Stash Mix refresh worker for a single recipe (the
     * one whose materialized playlist is [playlistId]). Used by the long-
     * press "Refresh this mix" action on Stash Mix cards.
     *
     * Emits snackbar lifecycle messages via [userMessages]: "Refreshing X…"
     * on enqueue, then "Refreshed X" or "Refresh failed" on the worker's
     * terminal WorkInfo state. If the playlist is tagged `STASH_MIX` but no
     * recipe back-links it (data-integrity bug — menu shouldn't have
     * appeared), logs a warning and surfaces a "not linked to a recipe"
     * message instead of silently no-opping.
     */
    fun refreshMix(playlistId: Long) {
        viewModelScope.launch {
            val recipe = recipeDao.findByPlaylistId(playlistId)
            if (recipe == null) {
                // Data-integrity bug: playlist.type == STASH_MIX but no recipe
                // back-links it. Menu shouldn't have appeared. Log + soft-fail.
                Log.w(TAG, "refreshMix: no recipe back-links playlistId=$playlistId")
                _userMessages.tryEmit("Couldn't refresh — this mix isn't linked to a recipe")
                return@launch
            }

            _userMessages.tryEmit("Refreshing ${recipe.name}…")

            // Build the request ourselves so we can capture its id for exact-
            // match WorkInfo filtering below. enqueueUniqueWork uses the same
            // unique name + REPLACE policy as StashMixRefreshWorker.enqueueOneTime,
            // mirroring lines 154-168 of that worker.
            val request = OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(workDataOf(StashMixRefreshWorker.KEY_RECIPE_ID to recipe.id))
                .build()
            val uniqueName = "${StashMixRefreshWorker.ONE_SHOT_WORK_NAME}_${recipe.id}"
            WorkManager.getInstance(context)
                .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)

            // v0.9.20: fire the full discovery pipeline. queueDiscoveryForRecipe
            // inside the mix refresh worker enqueues new Last.fm candidates into
            // discovery_queue PENDING; this trigger processes them right now (subject
            // to user's DownloadNetworkMode pref) instead of waiting up to 24h for
            // the periodic schedule. The chain in StashDiscoveryWorker's tail will
            // fire DiscoveryDownloadWorker, which fires StashMixRefreshWorker again
            // at the end — the mix re-materializes with newly-downloaded survivors
            // without the user lifting another finger.
            val mode = downloadNetworkPreference.current()
            StashDiscoveryWorker.enqueueOneTime(context, mode)

            // Observe the unique-work Flow; filter to OUR enqueued request's id
            // so historical entries from earlier taps (or earlier sessions)
            // don't fire stale "Refreshed" Toasts.
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(uniqueName)
                .firstOrNull { infos ->
                    val ours = infos.firstOrNull { it.id == request.id } ?: return@firstOrNull false
                    when (ours.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            _userMessages.tryEmit("Refreshed ${recipe.name}")
                            true
                        }
                        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                            _userMessages.tryEmit("Refresh failed — try again later")
                            true
                        }
                        else -> false
                    }
                }
        }
    }

    /**
     * Delete a user-built Stash Mix: removes the materialized playlist (via
     * the protected-playlist cascade, NOT blacklisting), then deletes the
     * backing recipe row.
     *
     * Order matters: capture the recipe BEFORE the cascade runs, because
     * `deletePlaylistWithCascade` nulls the recipe's `playlist_id` FK
     * (SET_NULL), after which `findByPlaylistId` would no longer resolve it.
     */
    fun deleteCustomMix(playlist: Playlist) {
        viewModelScope.launch {
            val recipe = recipeDao.findByPlaylistId(playlist.id) // capture BEFORE cascade nulls the FK
            musicRepository.deletePlaylistWithCascade(playlist.id, alsoBlacklist = false)
            recipe?.let { recipeDao.deleteCustom(it.id) }
            _userMessages.tryEmit("Deleted “${playlist.name}”")
        }
    }

    /**
     * If [playlistId] backs a user (non-builtin) recipe whose last refresh
     * is older than [STALE_MIX_MS], kick a refresh. Fire-and-forget from the
     * mix-card tap so opening a stale custom mix transparently freshens it.
     * No-ops for builtin recipes (those refresh on the periodic schedule)
     * and for playlists with no backing recipe.
     */
    fun refreshMixIfStale(playlistId: Long) {
        viewModelScope.launch {
            val r = recipeDao.findByPlaylistId(playlistId) ?: return@launch
            val stale = (r.lastRefreshedAt ?: 0L) < System.currentTimeMillis() - STALE_MIX_MS
            if (!r.isBuiltin && stale) refreshMix(playlistId)
        }
    }

    /**
     * Resolve the recipe id backing [playlistId] asynchronously, invoking
     * [onResult] with the id (or null if no recipe back-links it). Used by
     * the context-sheet Edit action to build the MixBuilder nav arg, since
     * the playlist→recipe mapping isn't carried synchronously in uiState.
     */
    fun editRecipeId(playlistId: Long, onResult: (Long?) -> Unit) {
        viewModelScope.launch {
            onResult(recipeDao.findByPlaylistId(playlistId)?.id)
        }
    }

    /**
     * Plays every downloaded track across every daily mix from the given [source],
     * effectively merging all of that source's mixes into one continuous queue.
     * Passing null plays the combined pool from BOTH sources (Spotify first,
     * then YouTube) with per-track deduplication.
     *
     * Duplicates are removed via [distinctBy] so tracks appearing in multiple
     * mixes are only queued once. Tracks appear in the order their parent
     * playlists are returned by the repository.
     *
     * @param source The source whose mixes to play, or null to combine both.
     */
    fun playAllMixes(source: MusicSource?) {
        viewModelScope.launch {
            val state = uiState.value
            val mixes = when (source) {
                MusicSource.SPOTIFY -> state.spotifyMixes
                MusicSource.YOUTUBE -> state.youtubeMixes
                null -> state.spotifyMixes + state.youtubeMixes
                else -> return@launch
            }
            if (mixes.isEmpty()) return@launch

            val streamingOn = streamingPreference.current()
            val allTracks = mixes
                .flatMap { mix ->
                    musicRepository.getTracksByPlaylist(mix.id).first()
                }
                .let { tracks -> if (streamingOn) tracks else tracks.filter { it.filePath != null } }
                .distinctBy { it.id }

            if (allTracks.isNotEmpty()) {
                playerRepository.setQueue(allTracks, startIndex = 0)
            }
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

    companion object {
        private const val TAG = "LibraryViewModel"
        /** A custom mix older than this (24h) is refreshed on open. */
        private const val STALE_MIX_MS = 24L * 60 * 60 * 1000
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
    val mixData: LibraryMixData,
    val allArtists: List<com.stash.core.data.db.dao.ArtistSummary>,
    val allAlbums: List<com.stash.core.data.db.dao.AlbumSummary>,
)

/**
 * Internal holder bundling the playlists with the recipe/discovery-derived
 * mix slices so the base [combine] treats them as one positional arg (and
 * observes `getAllPlaylists()` exactly once). Mirrors [HomeViewModel]'s
 * `MusicData`.
 */
private data class LibraryMixData(
    val playlists: List<Playlist>,
    val recentlyAdded: List<Track>,
    val stashMixes: List<Playlist>,
    val spotifyMixes: List<Playlist>,
    val youtubeMixes: List<Playlist>,
    val likedPlaylists: List<Playlist>,
    val customMixPlaylistIds: Set<Long>,
    val buildingMixIds: Set<Long>,
    val emptyMixIds: Set<Long>,
)
