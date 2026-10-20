package com.stash.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.common.primaryArtist
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.FlacUpgradeEnqueuer
import com.stash.core.media.PlayerRepository
import com.stash.core.model.AlbumNavTarget
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.files.LocalImportState
import com.stash.core.model.Playlist
import com.stash.core.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOn
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

/**
 * The order every Library list takes under [SortOrder.ALPHABETICAL] (songs and likes by title;
 * playlists, artists and albums by name): case-insensitive, character by character, nothing
 * folded or stripped ("The Beatles" files under T, "Édith Piaf" after Z). The fast-scroll letter
 * bubble ([com.stash.core.ui.components.fastScrollSectionLetter]) reads the first letter of the
 * same name the same way, so its letters run in the list's own order. Change the two together:
 * if this ever folds accents or skips a leading "The", the bubble must too.
 */
internal val alphabeticalOrder: Comparator<String> = String.CASE_INSENSITIVE_ORDER

/** Shared track search predicate — every Library list matches title/artist/album. */
private fun Track.matchesQuery(query: String): Boolean =
    title.lowercase().contains(query) ||
        artist.lowercase().contains(query) ||
        album.lowercase().contains(query)

/**
 * Collapses album rows that describe the SAME album but arrived as separate
 * DAO groups. `getAllAlbums` groups by `(album, album_artist)`, so an album
 * whose tracks disagree on the `album_artist` tag (common in tag-inconsistent
 * FLAC rips) yields two rows that resolve to the same displayed name+artist.
 * The Albums grid keys each card on `"name|artist"`, and two identical keys
 * crash `LazyVerticalGrid` — the Albums tab force-close in issue #244.
 *
 * Merging by case-insensitive (name, artist) guarantees the grid key is
 * unique again and shows one card per album with the combined track count.
 */
internal fun mergeDuplicateAlbums(albums: List<AlbumInfo>): List<AlbumInfo> =
    albums
        .groupBy { it.name.lowercase() to it.artist.lowercase() }
        .map { (_, group) ->
            group.reduce { acc, next ->
                acc.copy(
                    trackCount = acc.trackCount + next.trackCount,
                    artPath = acc.artPath ?: next.artPath,
                    artUrl = acc.artUrl ?: next.artUrl,
                )
            }
        }

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
    private val ytMusicApiClient: com.stash.data.ytmusic.YTMusicApiClient,
    private val flacUpgradeEnqueuer: FlacUpgradeEnqueuer,
    private val libraryPreferencesStore: LibraryPreferencesStore,
    private val libraryDeepLinkController: com.stash.core.data.navigation.LibraryDeepLinkController,
    private val artistImageDao: ArtistImageDao,
    private val sharedMixRepository: com.stash.core.data.share.SharedMixRepository,
    /** Link Stash on the web's mirror (null in tests that don't need it). */
    private val mirror: com.stash.core.data.weblink.mirror.MirrorEngine? = null,
    /** Offline, the artist and album cards play only downloaded songs (null in tests = online). */
    private val connectivityMonitor: com.stash.core.media.streaming.ConnectivityMonitor? = null,
    /** Artists and Albums' "Show" choice in Stream only (null in tests = Everything). */
    private val libraryScopePreference: com.stash.core.data.prefs.LibraryScopePreference? = null,
) : ViewModel() {

    /** The playlist mirrors with Stash on the web: deleting it asks Everywhere / Only here (spec §2.4). */
    fun isMirrored(playlistId: Long): Boolean = mirror?.isMirrored(playlistId) == true

    /** "Everywhere" can be offered: this phone sends playlists and has joined them, or the playlist is read-only (R-S4). */
    fun canDeleteEverywhere(playlistId: Long): Boolean = mirror?.canDeleteEverywhere(playlistId) == true

    /** Active follows are read-only: the long-press sheet offers Unfollow in place of edits. */
    val followedPlaylistIds: StateFlow<Set<Long>> = sharedMixRepository.observeActiveFollowedIds()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun unfollowPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            try {
                sharedMixRepository.unfollow(playlist.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LibraryVM", "unfollow ${playlist.id} failed", e)
                _userMessages.tryEmit("Couldn't unfollow this mix. Try again.")
            }
        }
    }

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
     * The query the SEARCH FIELD binds to — deliberately its own StateFlow, NOT
     * read back off [uiState].
     *
     * [uiState] runs the whole filter/sort pipeline behind
     * `flowOn(Dispatchers.Default)`. A fully-controlled `TextField` bound to a
     * value that round-trips through that can't get the character you just typed
     * back inside the frame, so every recomposition stamps the STALE query over
     * the field: typing lags, characters vanish, and backspace fights you. That
     * regression shipped in v0.9.83 (f56a6e51 moved the pipeline off Main and
     * the field went with it).
     *
     * This flow is updated synchronously by [setSearchQuery] and touches no list
     * work, so the field always renders the keystroke immediately while the
     * filtered lists catch up a frame or two later off-Main. Keep it that way:
     * never route this through the pipeline, and never add operators that
     * dispatch. (Mirrors PlaylistDetailViewModel, which has always done this.)
     */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Guards against the seed-vs-user-action race: if the user calls
    // setSortOrder/setSourceFilter before the DataStore read in init
    // resolves, the seed must not stomp their change back to the
    // persisted (now-stale) value. Set synchronously in the setter —
    // by the time the async read's continuation resumes, this is
    // already true if the user acted first.
    @Volatile private var sortOrderUserSet = false
    @Volatile private var sourceFilterUserSet = false

    init {
        // What mirrors with Stash on the web, for the delete question (no network).
        mirror?.let { m -> viewModelScope.launch { m.load() } }
        // Seed the persisted sort/filter before anything downstream reads
        // _controls. uiState's initialValue still starts at ControlState()'s
        // hardcoded defaults for one frame — same tolerated flash pattern as
        // NowPlayingViewModel.ambientAnimationEnabled starting null.
        viewModelScope.launch {
            val sort = libraryPreferencesStore.getSortOrder()
            val filter = libraryPreferencesStore.getSourceFilter()
            _controls.update {
                it.copy(
                    sortOrder = if (sortOrderUserSet) it.sortOrder else sort,
                    sourceFilter = if (sourceFilterUserSet) it.sourceFilter else filter,
                )
            }
        }
    }

    // ── Single-song "Remove download", with Undo ─────────────────────────

    /**
     * The ⋮ sheet's "Remove download" on a Songs or Liked row: staged, not done, so the screen
     * can offer Undo while the song has already left the downloads lists (see
     * [UndoableDownloadRemoval]). The batch Remove download keeps its confirm dialog instead.
     *
     * Its own app-lifetime scope, not viewModelScope: [onCleared] commits a staged removal when
     * viewModelScope is already cancelled, and a delete cut off between the file and its row
     * would leave the row pointing at a missing file. Instantiated inline like LikeCoordinator's
     * (no ApplicationScope qualifier exists in this codebase). If the process dies inside the
     * Undo window nothing is deleted, which is the safe way to fail.
     */
    private val downloadRemoval = UndoableDownloadRemoval(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        remove = { track ->
            try {
                musicRepository.removeDownload(track.id)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("LibraryVM", "removeDownload ${track.id} failed", e)
                _userMessages.tryEmit("Couldn't remove the download for \"${track.title}\".")
                false
            }
        },
    )

    /** The staged removal the screen's Undo snackbar is for, or null. */
    val pendingDownloadRemoval: StateFlow<Track?> = downloadRemoval.pending

    /** "Remove download" from a row's ⋮ sheet: the song leaves the lists now, its file goes on [commitPendingDownloadRemoval]. */
    fun removeDownloadWithUndo(track: Track) {
        downloadRemoval.stage(track)
    }

    /** Undo: [trackId]'s file stays and the song is back in the lists. */
    fun undoRemoveDownload(trackId: Long) {
        downloadRemoval.undo(trackId)
    }

    /**
     * The Undo is gone (its snackbar timed out or was swiped, or the listener left Library):
     * delete now. A [trackId] commits only that song, so an outdated snackbar can't commit a newer one.
     */
    fun commitPendingDownloadRemoval(trackId: Long? = null) {
        downloadRemoval.commit(trackId)
    }

    override fun onCleared() {
        // Gone for good with an Undo still showing: the removal the listener asked for happens
        // now, in downloadRemoval's own scope.
        downloadRemoval.commit()
        super.onCleared()
    }

    /**
     * The downloaded songs, minus any whose removal is staged or still landing: they leave
     * Songs and [LibraryUiState.librarySongCount] the moment Remove download is tapped, not
     * when the file goes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val downloadedTracks: Flow<List<Track>> =
        combine(
            // Download mode: exactly the old downloads-only query. Stream-only: everything.
            streamingPreference.enabled.flatMapLatest { streaming ->
                if (streaming) musicRepository.getLibraryTracksIncludingOnline()
                else musicRepository.getAllTracks()
            },
            streamingPreference.enabled,
            downloadRemoval.hiddenIds,
        ) { tracks, streaming, hidden ->
            when {
                hidden.isEmpty() -> tracks
                // Stream-only: a staged removal keeps its row and just counts as online, so it
                // only leaves the list under the Downloaded filter (should-fix #1).
                streaming -> tracks.map { if (it.id in hidden) it.copy(isDownloaded = false) else it }
                else -> tracks.filterNot { it.id in hidden }
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
        // Downloads only, deliberately. Library is the offline-first surface —
        // "the music that is actually on this device" — so it stays downloads-only
        // even in Online mode. Recently-added STREAMABLE tracks belong on Home,
        // which is the streaming/discovery surface.
        musicRepository.getRecentlyAdded(20),
        // A song whose download removal is staged leaves the recent rail too.
        downloadRemoval.hiddenIds,
    ) { playlists, recentlyAdded, hidden ->
        LibraryData(
            playlists = playlists,
            recentlyAdded = if (hidden.isEmpty()) recentlyAdded else recentlyAdded.filterNot { it.id in hidden },
        )
    }

    /**
     * [_controls] as the list pipeline sees them: a typed query waits [SEARCH_SETTLE_MS] for the
     * next keystroke (clearing it applies at once), while tab, sort and filter changes pass straight
     * through. Unsettled, "beatles" re-ran the whole regroup/filter/sort over the library seven
     * times, and combine can't cancel a run already started, so the work queued up behind the
     * typing. The field itself never waits: it binds [searchQuery].
     */
    @OptIn(FlowPreview::class)
    private val pipelineControls: Flow<ControlState> = combine(
        _controls.map { it.copy(searchQuery = "") }.distinctUntilChanged(),
        _controls.map { it.searchQuery }
            .distinctUntilChanged()
            .debounce { query -> if (query.isEmpty()) 0L else SEARCH_SETTLE_MS },
    ) { controls, query -> controls.copy(searchQuery = query) }

    /**
     * The Artists tab's cards, each counting what its artist page lists ([buildArtistCards]).
     * Built here, not in the [uiState] pipeline, so typing a search or switching tabs doesn't
     * redo the credit index; and only when the DAO rows actually change (every tracks write
     * re-runs the query, a play count included).
     */
    private val artistCards: Flow<List<ArtistInfo>> = musicRepository.getAllArtists()
        .distinctUntilChanged()
        .map { buildArtistCards(it) }
        .flowOn(Dispatchers.Default)

    /**
     * [pipelineControls] with the Downloaded / Online filters read as All outside
     * Stream-only mode. Those chips only exist in Stream-only, so a saved Online
     * choice would otherwise empty the Songs list in Download mode with no chip to
     * explain it. The saved choice itself is kept, so it comes back with Stream-only.
     */
    private val effectiveControls: Flow<ControlState> = combine(
        pipelineControls,
        streamingPreference.enabled,
    ) { controls, streaming ->
        val availability = controls.sourceFilter == SourceFilter.DOWNLOADED ||
            controls.sourceFilter == SourceFilter.ONLINE
        if (!streaming && availability) controls.copy(sourceFilter = SourceFilter.ALL) else controls
    }

    /**
     * Combined UI state that reacts to both data changes and user interactions.
     */
    val uiState: StateFlow<LibraryUiState> = combine(
        effectiveControls,
        downloadedTracks,
        libraryDataFlow,
        artistCards,
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
        // Artists arrive as cards already (see [artistCards]): one per PRIMARY act, so
        // "Aarne, Toxi$, Big Baby Tape" lands on a single "Aarne" card.
        val artists = allArtists
        val albums = mergeDuplicateAlbums(
            // Album card shows the lead act of a collaboration credit
            // ("Metro Boomin" for "Metro Boomin, Travis Scott"), so the card
            // reads cleanly. Merging still happens on the same display name —
            // a collab album and its lead-act album are one release, not two.
            allAlbums.map { AlbumInfo(it.album, it.artist.primaryArtist(), it.trackCount, it.artPath, it.artUrl) }
        )

        // -- Apply source filter --
        val sourceFiltered = when (controls.sourceFilter) {
            SourceFilter.ALL -> allTracks
            SourceFilter.YOUTUBE -> allTracks.filter { it.source == MusicSource.YOUTUBE }
            SourceFilter.SPOTIFY -> allTracks.filter { it.source == MusicSource.SPOTIFY || it.source == MusicSource.BOTH }
            // Codec set kept in sync with com.stash.core.ui.components.FlacBadge
            // (and com.stash.data.download.lossless.AudioFormat.LOSSLESS_CODECS).
            // Worth duplicating — short list, short reach across modules.
            SourceFilter.FLAC -> allTracks.filter { it.isDownloaded && it.fileFormat.lowercase() in LOSSLESS_CODECS }
            // The batch-upgrade worklist: downloaded but still lossy.
            // A cloud song is the user's own file, never sent to the lossless lookup.
            SourceFilter.NON_FLAC -> allTracks.filter {
                it.isDownloaded && it.fileFormat.lowercase() !in LOSSLESS_CODECS && it.source != MusicSource.CLOUD
            }
            SourceFilter.CLOUD -> allTracks.filter { it.source == MusicSource.CLOUD }
            SourceFilter.DOWNLOADED -> allTracks.filter { it.isDownloaded }
            SourceFilter.ONLINE -> allTracks.filter { !it.isDownloaded }
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
            SortOrder.ALPHABETICAL -> filteredTracks.sortedWith(compareBy(alphabeticalOrder) { it.title })
            SortOrder.MOST_PLAYED -> filteredTracks.sortedByDescending { it.playCount }
            SortOrder.DURATION -> filteredTracks.sortedByDescending { it.durationMs }
        }
        val sortedPlaylists = when (controls.sortOrder) {
            // RECENT uses date_added (stable across syncs) not last_synced
            // — the latter reshuffles the list every sync run. See
            // PlaylistEntity.dateAdded + migration v12→v13 (issue #13).
            SortOrder.RECENT -> filteredPlaylists.sortedByDescending { it.dateAdded }
            SortOrder.ALPHABETICAL -> filteredPlaylists.sortedWith(compareBy(alphabeticalOrder) { it.name })
            // Playlists don't track a per-playlist play_count; use
            // trackCount as the most-relevant "size" signal so this
            // chip produces a visible ordering change instead of a
            // silent no-op.
            SortOrder.MOST_PLAYED -> filteredPlaylists.sortedByDescending { it.trackCount }
            // Playlists have no duration — fall back to the RECENT ordering.
            SortOrder.DURATION -> filteredPlaylists.sortedByDescending { it.dateAdded }
        }.let { sorted ->
            val (pinned, rest) = sorted.partition { it.pinned }
            pinned.sortedBy { it.name.lowercase() } + rest
        }
        // Sort artists/albums — default by track count descending (most tracks first)
        val sortedArtists = when (controls.sortOrder) {
            SortOrder.RECENT -> filteredArtists.sortedByDescending { it.trackCount }
            SortOrder.ALPHABETICAL -> filteredArtists.sortedWith(compareBy(alphabeticalOrder) { it.name })
            SortOrder.MOST_PLAYED -> filteredArtists.sortedByDescending { it.trackCount }
            SortOrder.DURATION -> filteredArtists.sortedByDescending { it.totalDurationMs }
        }
        val sortedAlbums = when (controls.sortOrder) {
            SortOrder.RECENT -> filteredAlbums.sortedByDescending { it.trackCount }
            SortOrder.ALPHABETICAL -> filteredAlbums.sortedWith(compareBy(alphabeticalOrder) { it.name })
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
            hasCloudSongs = allTracks.any { it.source == MusicSource.CLOUD },
        )
    }.combine(playerRepository.playerState) { libraryState, playerState ->
        // Overlay the currently-playing track ID so the UI can highlight it.
        libraryState.copy(
            currentlyPlayingTrackId = playerState.currentTrack?.id,
        )
    }.combine(
        combine(
            streamingPreference.enabled,
            libraryScopePreference?.inLibraryOnly ?: flowOf(false),
        ) { streaming, inLibraryOnly -> streaming to inLibraryOnly },
    ) { libraryState, (streaming, inLibraryOnly) ->
        // The repository applies "In your library" to the artist and album rows in Stream only
        // (MusicRepositoryImpl.libraryScope); this only tells the sheet and the chip.
        libraryState.copy(streamingMode = streaming, libraryOnly = streaming && inLibraryOnly)
    }.combine(artistImageDao.observeAll()) { libraryState, images ->
        // Overlay real artist photos (ArtistImageBackfillWorker output) keyed
        // by the SAME primary-artist name the Artists tab groups by. Artists
        // without a photo keep photoUrl = null → UI falls back to artUrl.
        val photoByName = images.associate { it.artistName to it.imageUrl }
        libraryState.copy(
            artists = libraryState.artists.map { it.copy(photoUrl = photoByName[it.name]) },
            singleTrackArtists = libraryState.singleTrackArtists
                .map { it.copy(photoUrl = photoByName[it.name]) },
        )
    }
        // The whole filter+sort+lowercase pipeline above re-runs on every
        // keystroke AND every Room invalidation; on a >1k-track library that
        // was dropped frames on Main (audit finding). Pure list work — run it
        // on Default and hand only the finished state to the UI.
        .flowOn(Dispatchers.Default)
        .stateIn(
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
                val narrowed = if (query.isEmpty()) tracks else tracks.filter { it.matchesQuery(query) }
                // #455: the merged list arrives as each source's playlist glued end to
                // end in position order, and the sort control never reached it — a song
                // liked today sat in the middle. Sort here, where the list is complete.
                sortLikedTracks(narrowed, controls.sortOrder)
            }
        }
            // Same rationale as uiState: dedupe + search filtering is pure
            // list work — keep it off Main.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Play the liked list starting at [track] (offline-aware, like the detail screen). */
    fun playLiked(track: Track) {
        viewModelScope.launch {
            val all = likedTracks.value
            val playable = all
            if (playable.isEmpty()) return@launch
            val index = playable.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playerRepository.setQueue(
                playable,
                index,
                source = com.stash.core.model.PlaybackSource.Liked(likedFilter.value.name),
            )
        }
    }

    /**
     * Shuffle the liked list and play it from the top — what the header
     * shuffle icon does while the Liked tab is open (issue #402: it used to
     * shuffle the whole library no matter which tab you were on). Scoped to
     * the same list the tab shows (current origin filter + search query),
     * with [playLiked]'s offline-playability narrowing. Shuffles the LIST
     * itself rather than enabling player shuffle mode, matching
     * [AlbumDetailViewModel.shuffleAll].
     */
    fun shuffleLiked() {
        viewModelScope.launch {
            val all = likedTracks.value
            val playable = all
            if (playable.isEmpty()) return@launch
            playerRepository.setQueue(
                playable.shuffled(),
                0,
                source = com.stash.core.model.PlaybackSource.Liked(likedFilter.value.name),
            )
        }
    }

    // ── Public actions ───────────────────────────────────────────────────

    /** Switch the active content tab. */
    fun selectTab(tab: LibraryTab) {
        _controls.update { it.copy(activeTab = tab) }
    }

    /**
     * One-shot deep-link from Home (Liked card). Screen calls this on every
     * entry — not `init` — so repeat taps work on this retained tab ViewModel.
     */
    fun consumeDeepLinkFocus(): com.stash.core.data.navigation.LibraryFocus? =
        libraryDeepLinkController.consume()

    /**
     * Update the search query; filtering is applied reactively.
     *
     * Writes BOTH the fast field-facing flow (rendered this frame) and the
     * control state that drives the off-Main filter pipeline (catches up). See
     * [searchQuery] for why the field must not wait on the pipeline.
     */
    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _controls.update { it.copy(searchQuery = query) }
    }

    /** Change the sort order for every content list. */
    fun setSortOrder(order: SortOrder) {
        sortOrderUserSet = true
        _controls.update { it.copy(sortOrder = order) }
        viewModelScope.launch { libraryPreferencesStore.setSortOrder(order) }
    }

    /** Filter tracks by source (All / YouTube / Spotify). */
    fun setSourceFilter(filter: SourceFilter) {
        sourceFilterUserSet = true
        _controls.update { it.copy(sourceFilter = filter) }
        viewModelScope.launch { libraryPreferencesStore.setSourceFilter(filter) }
    }

    /**
     * Artists and Albums' "Show" choice in Stream only: true = "In your library" (the songs Songs
     * lists), false = "Everything". Saved for every launch; the artist and album pages follow it.
     */
    fun setLibraryOnly(libraryOnly: Boolean) {
        viewModelScope.launch { libraryScopePreference?.setInLibraryOnly(libraryOnly) }
    }

    /**
     * Begin playback by replacing the queue with [allTracks] and starting
     * at the position of [track].
     */
    fun playTrack(track: Track, allTracks: List<Track>) {
        viewModelScope.launch {
            // Streaming is always allowed: every track is playable, and a
            // not-downloaded one streams when it is reached.
            // Download mode: a downloaded tap queues only downloaded rows, so offline playback
            // never walks into online neighbours. Stream-only: every row shown is playable, so
            // the queue is exactly the list shown, whatever row was tapped.
            val streaming = streamingPreference.enabled.first()
            val playable = if (!streaming && track.isDownloaded) {
                allTracks.filter { it.isDownloaded }
            } else {
                allTracks
            }
            val index = playable.indexOfFirst { it.id == track.id }
            if (index >= 0) {
                playerRepository.setQueue(playable, index, source = com.stash.core.model.PlaybackSource.Library)
            } else {
                // The tapped track isn't in the surrounding list (the Recently
                // Added rail is its own query, so a streamable track can be absent
                // from the Songs list). Play it on its own rather than no-op — a
                // tap that does nothing is the bug being fixed here.
                playerRepository.setQueue(listOf(track), 0, source = com.stash.core.model.PlaybackSource.Library)
            }
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

    /** Add each of [trackIds] to the playlist identified by [playlistId]. Silent. */
    fun saveSelectedToPlaylist(trackIds: List<Long>, playlistId: Long) {
        viewModelScope.launch {
            musicRepository.addTracksToPlaylist(trackIds, playlistId)
        }
    }

    /** Create a new playlist and add the whole batch of [trackIds] to it. Silent. */
    fun createPlaylistAndAddTracks(name: String, trackIds: List<Long>) {
        viewModelScope.launch {
            musicRepository.createPlaylistWithTracks(name, trackIds)
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

    /** Kick off the batch FLAC upgrade for the confirmed selection (spec §3). */
    fun upgradeSelectedToFlac(trackIds: List<Long>) {
        viewModelScope.launch {
            runCatching { flacUpgradeEnqueuer.startBatch(trackIds) }
                .onSuccess {
                    _userMessages.tryEmit(
                        "Upgrading ${trackIds.size} ${songs(trackIds.size)} to FLAC — watch the notification.",
                    )
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _userMessages.tryEmit("Couldn't start the FLAC upgrade.")
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
            if (!playerRepository.shuffleLibrary()) {
                _userMessages.tryEmit("Nothing downloaded to shuffle yet — download some songs first.")
            }
        }
    }

    /** Shuffle exactly what Songs shows (Stream-only mode, where the list includes online rows). */
    fun shuffleVisible(tracks: List<Track>) {
        viewModelScope.launch {
            if (tracks.isEmpty()) return@launch
            playerRepository.setQueue(tracks.shuffled(), 0, source = com.stash.core.model.PlaybackSource.Library)
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
                playerRepository.setQueue(
                    downloaded,
                    startIndex = 0,
                    source = com.stash.core.model.PlaybackSource.Playlist(playlist.id, playlist.name),
                )
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
     * live `getLibraryByDateAdded()` Flow mid-iteration, causing its
     * CursorWindow to be recycled underneath the reader and crashing
     * the app with `IllegalStateException: Couldn't read row N, col 0
     * from CursorWindow`. The cascade path invalidates once at commit,
     * so the Flow re-reads from a fresh cursor exactly once. Fixes #14.
     *
     * User-uploaded cover image is a separate filesystem artifact the
     * cascade doesn't know about — delete it here before delegating.
     */
    fun deletePlaylist(playlist: Playlist, alsoBlacklist: Boolean = false, everywhere: Boolean = false) {
        viewModelScope.launch {
            // "Only here": it stops mirroring first, so its copies elsewhere stay; "Everywhere": the next mirror run deletes them.
            mirror?.beforeDelete(playlist.id, everywhere)
            playlistImageHelper.deletePlaylistCoverFile(playlist.id)
            val summary = musicRepository.deletePlaylistWithCascade(
                playlistId = playlist.id,
                alsoBlacklist = alsoBlacklist,
            )
            // Say what really happened: songs that are also in Liked or another playlist stay.
            _userMessages.tryEmit(cascadeSummaryMessage(summary))
        }
    }

    /** Remove playlist from library without deleting its downloaded tracks. */
    fun removePlaylist(playlist: Playlist) {
        viewModelScope.launch {
            mirror?.beforeDelete(playlist.id, everywhere = false) // removing it here never removes it from your other devices
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

    fun togglePlaylistPinned(playlist: Playlist) {
        viewModelScope.launch {
            musicRepository.setPlaylistPinned(playlist.id, !playlist.pinned)
        }
    }

    /** Pin/unpin [playlist] on Home's "Your playlists" rail. */
    fun togglePlaylistOnHome(playlist: Playlist) {
        viewModelScope.launch {
            musicRepository.setPlaylistPinnedToHome(
                playlist.id,
                if (playlist.pinnedToHomeAt == null) System.currentTimeMillis() else null,
            )
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
     * Plays the songs [artistName]'s page lists, from the first: every song in Stream only (those
     * not downloaded stream), the downloaded ones in Download. Offline, only songs on the phone
     * play (see [playableNow]); with none, says it needs a connection.
     */
    fun playArtist(artistName: String) {
        viewModelScope.launch {
            val tracks = playableFromCard(musicRepository.getTracksByArtist(artistName).first(), ARTIST_NEEDS_CONNECTION)
            if (tracks.isEmpty()) return@launch
            playerRepository.setQueue(
                tracks,
                startIndex = 0,
                source = com.stash.core.model.PlaybackSource.Artist(artistName),
            )
        }
    }

    /** Appends the songs [playArtist] would play to the queue, under the same rule. */
    fun addArtistToQueue(artistName: String) {
        viewModelScope.launch {
            val tracks = playableFromCard(musicRepository.getTracksByArtist(artistName).first(), ARTIST_NEEDS_CONNECTION)
            if (tracks.isNotEmpty()) playerRepository.addToQueue(tracks)
        }
    }

    /**
     * [listed] narrowed to what can play now ([playableNow]). Offline with songs listed but none
     * on the phone, it says so with [needsConnection] and returns nothing.
     */
    private fun playableFromCard(listed: List<Track>, needsConnection: String): List<Track> {
        // No monitor only in tests that don't care: treat as online, as before this rule.
        val playable = playableNow(listed, online = connectivityMonitor?.isConnected() ?: true)
        if (playable.isEmpty() && listed.isNotEmpty()) _userMessages.tryEmit(needsConnection)
        return playable
    }

    /**
     * Delete the downloaded songs on [artistName]'s card from disk and DB — exactly the
     * [ArtistInfo.downloadedCount] songs the confirm dialog names (see [artistCardTracks]). Not
     * the card's [ArtistInfo.trackCount]: that counts what the artist page lists, features and
     * (in Stream only) songs that aren't downloaded included.
     *
     * [MusicRepository.getTracksByArtist] is a credit search (any credit, album artist too,
     * downloaded or not): deleting all of it also took "Future, Drake" songs from Future's card,
     * compilations whose album artist is Drake, and every streamable synced row crediting him,
     * cascading them out of playlists and Liked for good. Per-item failures are isolated;
     * emits one roll-up message.
     */
    fun deleteArtist(artistName: String) {
        viewModelScope.launch {
            val tracks = artistCardTracks(musicRepository.getTracksByArtist(artistName).first(), artistName)
            var deleted = 0
            tracks.forEach { track ->
                runCatching { musicRepository.deleteTrack(track) }
                    .onSuccess { if (it) deleted++ }
                    .onFailure { e -> if (e is CancellationException) throw e }
            }
            if (deleted > 0) {
                _userMessages.tryEmit("Deleted $deleted ${songs(deleted)}.")
            }
        }
    }

    // ── Album actions ───────────────────────────────────────────────────

    /**
     * Plays the songs [albumName]'s page lists, from the first: the album's songs crediting
     * [artist] ([MusicRepository.getTracksByAlbum], so a collaboration album plays from its
     * primary-act card), every one in Stream only, the downloaded ones in Download. Offline,
     * only songs on the phone play; with none, says it needs a connection.
     */
    fun playAlbum(albumName: String, artist: String) {
        viewModelScope.launch {
            val tracks = playableFromCard(musicRepository.getTracksByAlbum(albumName, artist).first(), ALBUM_NEEDS_CONNECTION)
            if (tracks.isEmpty()) return@launch
            playerRepository.setQueue(
                tracks,
                startIndex = 0,
                source = com.stash.core.model.PlaybackSource.Album(albumName, artist),
            )
        }
    }

    /** Appends the songs [playAlbum] would play to the queue, under the same rule. */
    fun addAlbumToQueue(albumName: String, artist: String) {
        viewModelScope.launch {
            val tracks = playableFromCard(musicRepository.getTracksByAlbum(albumName, artist).first(), ALBUM_NEEDS_CONNECTION)
            if (tracks.isNotEmpty()) playerRepository.addToQueue(tracks)
        }
    }

    /**
     * One-shot navigation targets emitted when the user taps "View Album"
     * from a track's context sheet. Mirrors NowPlayingViewModel's
     * albumNavEvents — same shape, same reasoning (extraBufferCapacity so
     * a config-change re-subscribe right after emit doesn't drop it).
     */
    private val _albumNavEvents = MutableSharedFlow<com.stash.core.model.AlbumNavTarget>(
    extraBufferCapacity = 1,
    )
    val albumNavEvents: SharedFlow<com.stash.core.model.AlbumNavTarget> =
        _albumNavEvents.asSharedFlow()

    private val _resolvingAlbumTrackId = MutableStateFlow<Long?>(null)
    /** Non-null id of the track whose "View Album" resolve is in flight. */
    val resolvingAlbumTrackId: StateFlow<Long?> = _resolvingAlbumTrackId.asStateFlow()

    /**
     * "View Album" tap from a Library track's context sheet. Resolves
     * [track]'s album via YT Music search and emits a nav target. No-op if
     * a resolve is already running for this track, or the track has no
     * album tag. Opens the real remote album page — deliberately NOT the
     * local Albums tab (which just filters this device's downloaded tracks).
     */
    fun onViewAlbumTapped(track: Track) {
        if (_resolvingAlbumTrackId.value == track.id) return
        if (track.album.isBlank()) {
            _userMessages.tryEmit("This track has no album info")
            return
        }
        val artistName = track.albumArtist.ifBlank { track.artist }
        _resolvingAlbumTrackId.value = track.id
        viewModelScope.launch {
            try {
                val album = ytMusicApiClient.resolveAlbum(track.album, artistName)
                if (album != null) {
                    _albumNavEvents.emit(
                        com.stash.core.model.AlbumNavTarget(
                            albumId = album.id,
                            name = album.title,
                            artUrl = album.thumbnailUrl,
                            artistName = artistName,
                        ),
                    )
                } else {
                    _userMessages.tryEmit("Couldn't find this album")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _userMessages.tryEmit("Couldn't find this album")
            } finally {
                if (_resolvingAlbumTrackId.value == track.id) _resolvingAlbumTrackId.value = null
            }
        }
    }
}

/**
 * Internal holder for user-driven UI controls so they can be combined
 * with the data flows in a single [combine] call.
 */
/**
 * The Liked tab's sort. "Recently added" means recently LIKED here: the newest
 * like on top whichever source it came from (Stash, Spotify, YouTube Music,
 * Last.fm), falling back to the library add date for a row that predates the
 * like timestamps. The other orders mirror the Songs tab.
 */
internal fun sortLikedTracks(tracks: List<Track>, order: SortOrder): List<Track> = when (order) {
    SortOrder.RECENT -> {
        // Tracks with a real like timestamp first, newest like on top. Synced likes
        // carry none (dateAdded is download time, which scrambles them by album), so
        // they keep the incoming order = sync position = Spotify's newest-first.
        val (stamped, synced) = tracks.partition { it.likedAtOrNull() != null }
        stamped.sortedByDescending { it.likedAtOrNull() } + synced
    }
    SortOrder.ALPHABETICAL -> tracks.sortedWith(compareBy(alphabeticalOrder) { it.title })
    SortOrder.MOST_PLAYED -> tracks.sortedByDescending { it.playCount }
    SortOrder.DURATION -> tracks.sortedByDescending { it.durationMs }
}

private fun Track.likedAtOrNull(): Long? =
    listOfNotNull(stashLikedAt, spotifySavedAt, ytMusicSavedAt, lastFmLovedAt).maxOrNull()

/** How long a typed Library query must sit still before the list pipeline re-runs on it. */
private const val SEARCH_SETTLE_MS = 150L

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
    val allArtists: List<ArtistInfo>,
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

/**
 * The songs "Delete All by Artist" removes from a card: DOWNLOADED rows whose credit's primary
 * act is [artistName], out of [candidates] (the artist page's credit search). Mirrors
 * [ArtistInfo.downloadedCount] (see [buildArtistCards]), so the delete touches exactly the songs
 * its confirm dialog names. Never a song that isn't downloaded, whatever the Download switch
 * lists: deleting one would take a synced song out of its playlists and Liked for good.
 */
internal fun artistCardTracks(candidates: List<Track>, artistName: String): List<Track> =
    candidates.filter { it.isDownloaded && it.artist.primaryArtist() == artistName }

/**
 * The snackbar after "Delete Playlist & Songs". The cascade keeps every song that is also in
 * Liked Songs or another playlist, so "deleted N" alone (or the dialog's old "all its tracks will
 * be removed") overstated what went.
 */
internal fun cascadeSummaryMessage(summary: com.stash.core.data.repository.MusicRepository.CascadeRemovalSummary): String {
    val deleted = summary.deleted
    val kept = summary.keptProtected + summary.keptElsewhere
    fun songs(n: Int) = if (n == 1) "1 song" else "$n songs"
    return when {
        deleted > 0 && kept > 0 -> "Deleted ${songs(deleted)}. Kept ${songs(kept)} that are in Liked Songs or other playlists."
        deleted > 0 -> "Deleted ${songs(deleted)}."
        kept > 0 -> "Playlist deleted. Its ${songs(kept)} stay: they're in Liked Songs or other playlists."
        else -> "Playlist deleted."
    }
}
