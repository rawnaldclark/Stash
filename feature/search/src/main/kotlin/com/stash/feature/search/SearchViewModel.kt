package com.stash.feature.search

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.common.perf.PerfLog
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.StreamRoutingResult
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.preview.LosslessUrlPrefetcher
import com.stash.core.model.Playlist
import com.stash.core.model.TrackItem
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import com.stash.data.ytmusic.model.TrackSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Search screen.
 *
 * Task 9 rewired the search path from a manual `delay → launch` debounce
 * onto [flatMapLatest] driven by a [MutableStateFlow] so a new keystroke
 * cancels the in-flight `searchAll` call without any bookkeeping.
 *
 * The preview and download paths — plus their retry policy and error
 * snackbars — were extracted to [TrackActionsDelegate] in the Album
 * Discovery phase-1 migration so `ArtistProfileViewModel` (and, next,
 * `AlbumDiscoveryViewModel`) can share them. This VM owns only the
 * search-query pipeline; everything per-track is read off
 * `viewModel.delegate.*` in the screen.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel @Inject constructor(
    private val api: YTMusicApiClient,
    private val prefetcher: PreviewPrefetcher,
    val delegate: TrackActionsDelegate,
    val losslessPrefetcher: LosslessUrlPrefetcher,
    private val playerRepository: PlayerRepository,
    private val streamingPreference: StreamingPreference,
    private val recentSearchesStore: RecentSearchesStore,
) : ViewModel() {

    companion object {
        private const val TAG = "SearchVM"

        /** Minimum query length before triggering a search. */
        private const val MIN_QUERY_LENGTH = 2

        /** Debounce delay in milliseconds after the user stops typing. */
        private const val DEBOUNCE_MS = 300L

        /** Max number of tracks to pre-warm URLs for on a fresh results page. */
        private const val PREFETCH_TOP_N = 6
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /**
     * Synthetic id of the track currently being resolved-on-tap. Set when
     * `onResultTap` enters the streaming branch and cleared in a `finally`
     * so success, snackbar refusal, cancellation, and any throw all reset
     * the spinner. The screen pairs this with `PlayerRepository
     * .resolvingTrackVideoId` to render a row-level spinner during the
     * resolve-then-play hand-off.
     */
    private val _tappedTrackId = MutableStateFlow<Long?>(null)
    val tappedTrackId: StateFlow<Long?> = _tappedTrackId.asStateFlow()

    /**
     * One-shot user-facing messages (snackbars). Buffered so a message emitted
     * before the UI subscribes (e.g. during init crash-paths) isn't dropped.
     *
     * The screen merges this with [TrackActionsDelegate.userMessages] so
     * preview/download errors surface through the same snackbar host.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    // Add-to-playlist picker: the item awaiting a playlist choice (null = sheet closed).
    private val _playlistSheetItem = MutableStateFlow<TrackItem?>(null)
    val playlistSheetItem: StateFlow<TrackItem?> = _playlistSheetItem.asStateFlow()

    val userPlaylists: StateFlow<List<Playlist>> =
        delegate.userPlaylists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** youtubeId of the currently-playing track, for the SongRow now-playing indicator. */
    val currentPlayingYoutubeId: StateFlow<String?> =
        playerRepository.playerState
            .map { it.currentTrack?.youtubeId }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    fun onPlayNext(item: TrackItem) {
        recordTrack(item)
        delegate.playNext(item)
    }
    fun onStartRadio(item: TrackItem) {
        recordTrack(item)
        delegate.startRadio(item)
    }
    fun onAddToQueue(item: TrackItem) {
        recordTrack(item)
        delegate.addToQueue(item)
    }
    fun onDownload(item: TrackItem) {
        recordTrack(item)
        delegate.downloadTrack(item)
    }
    fun onRequestAddToPlaylist(item: TrackItem) {
        recordTrack(item)
        _playlistSheetItem.value = item
    }
    fun onDismissPlaylistSheet() { _playlistSheetItem.value = null }
    fun onSaveToPlaylist(playlistId: Long) {
        _playlistSheetItem.value?.let { delegate.addToPlaylist(it, playlistId) }
        _playlistSheetItem.value = null
    }
    fun onCreatePlaylistAndAdd(name: String) {
        _playlistSheetItem.value?.let { delegate.createPlaylistAndAdd(it, name) }
        _playlistSheetItem.value = null
    }

    /** Recent searches (most-recent-first), shown in the empty state. */
    val recentSearches: StateFlow<List<RecentSearch>> =
        recentSearchesStore.recent.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Record the current query as a recent search. Call on COMMITTED searches
     * only — the keyboard Search action or a result interaction — NOT on
     * every keystroke (that would save every prefix: "bea", "beat", "beatl"…).
     */
    fun onSearchCommitted() {
        val q = _uiState.value.query.trim()
        if (q.isNotEmpty()) {
            viewModelScope.launch {
                recentSearchesStore.record(RecentSearch(RecentSearch.Type.QUERY, q))
            }
        }
    }

    /**
     * The user opened an artist profile from results — record the ARTIST
     * itself (name + avatar + browse id) so the recents row shows the face
     * and taps navigate straight back. This was the missing commit: artist
     * taps are pure navigation, so "search artist → open profile → back"
     * saved nothing while track taps and the keyboard Search key did.
     */
    fun onArtistOpened(id: String, name: String, avatarUrl: String?) {
        viewModelScope.launch {
            recentSearchesStore.record(
                RecentSearch(
                    type = RecentSearch.Type.ARTIST,
                    text = name,
                    thumbnailUrl = avatarUrl,
                    artistId = id,
                ),
            )
        }
    }

    /** A track row was engaged (tap/download/queue/playlist) — record it with art. */
    private fun recordTrack(item: TrackItem) {
        viewModelScope.launch {
            recentSearchesStore.record(
                RecentSearch(
                    type = RecentSearch.Type.TRACK,
                    text = item.title,
                    subtitle = item.artist,
                    thumbnailUrl = item.thumbnailUrl,
                ),
            )
        }
    }

    /** Re-run a tapped recent search (ARTIST entries navigate in the screen). */
    fun onRecentSearchTapped(entry: RecentSearch) {
        // Move it back to the front regardless of type.
        viewModelScope.launch { recentSearchesStore.record(entry) }
        if (entry.type != RecentSearch.Type.ARTIST) {
            onQueryChanged(entry.searchText)
        }
    }

    fun removeRecentSearch(entry: RecentSearch) {
        viewModelScope.launch { recentSearchesStore.remove(entry.text) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { recentSearchesStore.clear() }
    }

    /** Drives [flatMapLatest] — every keystroke replaces the value. */
    private val queryFlow = MutableStateFlow("")

    init {
        // Must happen before any downloadTrack/previewTrack call — the delegate
        // reads `scope()` lazily and throws if invoked before binding.
        delegate.bindToScope(viewModelScope)

        // Search pipeline — keystrokes → debounce → searchAll → status.
        viewModelScope.launch {
            queryFlow
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .flatMapLatest { q -> runSearch(q) }
                .collect { status -> _uiState.update { it.copy(status = status) } }
        }
    }

    /**
     * Builds a cold [kotlinx.coroutines.flow.Flow] that runs one `searchAll`
     * call and emits the resulting [SearchStatus]. Kept as a cold flow
     * (not a `suspend` function) so [flatMapLatest] can cancel it cleanly
     * when the user types another keystroke.
     */
    private fun runSearch(query: String) = flow {
        // Any new keystroke invalidates the previous query's prefetch work.
        // PreviewPrefetcher launches on its own SupervisorJob scope so
        // flatMapLatest's cancel does NOT tear down in-flight prefetches;
        // we have to cancel them explicitly or they burn CPU/bandwidth
        // resolving URLs the user no longer cares about.
        prefetcher.cancelAll()
        if (query.length < MIN_QUERY_LENGTH) {
            emit(SearchStatus.Idle)
            return@flow
        }
        // Captured BEFORE the skeleton emit so both bookends measure from the
        // same keystroke-cancelled-then-debounced moment. Spec §4.1 latency
        // targets are tap → visible, so wall-clock from flow start is correct.
        val t0 = SystemClock.elapsedRealtime()
        emit(SearchStatus.Loading)
        PerfLog.d { "Search skeleton at ${SystemClock.elapsedRealtime() - t0}ms" }
        try {
            val results = api.searchAll(query)
            val sections = results.sections
            if (sections.isEmpty()) {
                emit(SearchStatus.Empty)
            } else {
                emit(SearchStatus.Results(sections))
                PerfLog.d {
                    "Search first-results at ${SystemClock.elapsedRealtime() - t0}ms (q=$query)"
                }
                prefetchTopN(sections)
                refreshDownloadedIds(sections)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "search failed for '$query'", t)
            _userMessages.emit("Search failed — please try again.")
            emit(SearchStatus.Error(t.message ?: "Search failed"))
        }
    }

    /** Called whenever the search text field value changes. */
    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
        queryFlow.value = query
    }

    /**
     * Task 20 — Search-tab tap action.
     *
     * When the master streaming pref is **on**, tap streams the full
     * track via [PlayerRepository.playFromStream]. The routing decision
     * tree inside the repo handles "no connectivity", "cellular refused",
     * etc., returning a [StreamRoutingResult] variant for refusal paths;
     * we surface those as snackbars on [userMessages] so the existing
     * search-screen snackbar host renders them.
     *
     * When streaming is **off**, the existing preview-on-tap behavior is
     * preserved: [TrackActionsDelegate.previewTrack] plays a ~30s clip.
     * This keeps the search tab usable in pure download-and-play mode.
     *
     * Snackbar copy matches the strings called out in the
     * [StreamRoutingResult] KDoc so all surfaces (Library, Search) read
     * identically when a stream-routing refusal fires.
     */
    fun onResultTap(item: TrackItem) {
        // Tapping a result means the search was useful — record the TRACK
        // itself (title + artist + thumbnail), not just the raw query text.
        recordTrack(item)
        viewModelScope.launch {
            _tappedTrackId.value = item.syntheticId()
            try {
                if (streamingPreference.current()) {
                    val result = playerRepository.playFromStream(item)
                    when (result) {
                        is StreamRoutingResult.Item -> Unit // playback started by the repo
                        StreamRoutingResult.Deduped -> Unit // earlier tap is handling it
                        StreamRoutingResult.NotAvailable ->
                            _userMessages.emit("Couldn't stream this track.")
                        StreamRoutingResult.OfflineMode ->
                            _userMessages.emit("Turn on Online mode to stream this track.")
                        StreamRoutingResult.CellularRefused ->
                            _userMessages.emit("Streaming on cellular is off in Settings.")
                        StreamRoutingResult.NoConnectivity ->
                            _userMessages.emit("You're offline — can't stream this track.")
                    }
                } else {
                    delegate.previewTrack(item)
                }
            } finally {
                _tappedTrackId.value = null
            }
        }
    }

    /**
     * Scroll-driven prefetch entry point. The screen hands over the set of
     * video ids currently rendered by the Songs section; we forward to the
     * [PreviewPrefetcher], which dedupes against the shared
     * [com.stash.data.download.preview.PreviewUrlCache] so already-warmed
     * ids cost nothing. Complements [prefetchTopN], which only pre-warms
     * the first [PREFETCH_TOP_N] ids on a fresh results page — this keeps
     * rows the user scrolls into warm as well.
     */
    fun prefetchVisible(videoIds: List<String>) {
        if (videoIds.isEmpty()) return
        prefetcher.prefetch(videoIds.distinct())
    }

    /**
     * Kicks the [PreviewPrefetcher] for the first [PREFETCH_TOP_N] tracks
     * across the Top-result (if a track) and Songs sections so a preview
     * tap on any of them hits a warm URL cache. Safe to call repeatedly —
     * the prefetcher de-dupes against the shared cache.
     */
    private fun prefetchTopN(sections: List<SearchResultSection>) {
        val ids = mutableListOf<String>()
        sections.forEach { section ->
            when (section) {
                is SearchResultSection.Top -> (section.item as? TopResultItem.TrackTop)
                    ?.track?.videoId?.let { ids.add(it) }
                is SearchResultSection.Songs -> ids.addAll(section.tracks.map { it.videoId })
                else -> Unit
            }
        }
        if (ids.isEmpty()) return
        prefetcher.prefetch(ids.take(PREFETCH_TOP_N).distinct())
    }

    /**
     * Collects the visible video ids across Songs + TrackTop sections and
     * hands them to the delegate's DB cross-reference so already-downloaded
     * rows render with the green checkmark instead of the download arrow.
     */
    private suspend fun refreshDownloadedIds(sections: List<SearchResultSection>) {
        val videoIds = sections.flatMap { section ->
            when (section) {
                is SearchResultSection.Songs -> section.tracks.map { it.videoId }
                is SearchResultSection.Top -> (section.item as? TopResultItem.TrackTop)
                    ?.track?.videoId?.let { listOf(it) } ?: emptyList()
                else -> emptyList()
            }
        }
        delegate.refreshDownloadedIds(videoIds)
    }

    override fun onCleared() {
        super.onCleared()
        delegate.onOwnerCleared()
    }
}

/**
 * Adapter: [TrackSummary] → [SearchResultItem].
 *
 * Bridges the new sectioned model to the still-existing [SearchResultItem]-
 * based [PreviewDownloadRow] composable. Kept as a top-level extension so
 * it's trivially testable and importable from [SearchScreen].
 */
internal fun TrackSummary.toSearchResultItem() = SearchResultItem(
    videoId = videoId,
    title = title,
    artist = artist,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
)

/**
 * Adapter: [TrackSummary] → [com.stash.core.model.TrackItem].
 *
 * Used at download call sites in the screen — the delegate's `downloadTrack`
 * takes a [com.stash.core.model.TrackItem] (the minimal identity
 * needed to kick off a yt-dlp download). Kept next to [toSearchResultItem]
 * so the two adapters are easy to compare.
 */
internal fun TrackSummary.toTrackItem() = com.stash.core.model.TrackItem(
    videoId = videoId,
    title = title,
    artist = artist,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
    album = album,
)

/**
 * Adapter: [SearchResultItem] → [com.stash.core.model.TrackItem].
 *
 * [PopularTracksSection]'s `onDownload` callback surfaces the row as a
 * [SearchResultItem] (since the composable converts internally via
 * [toSearchResultItem] to feed [PreviewDownloadRow]). Screens that route
 * that callback into `delegate.downloadTrack` use this one-liner.
 */
internal fun SearchResultItem.toTrackItem() = com.stash.core.model.TrackItem(
    videoId = videoId,
    title = title,
    artist = artist,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
)
