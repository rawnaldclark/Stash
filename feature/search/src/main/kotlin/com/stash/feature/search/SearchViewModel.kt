package com.stash.feature.search

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.common.perf.PerfLog
import com.stash.core.media.actions.TrackActionsDelegate
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
     * One-shot user-facing messages (snackbars). Buffered so a message emitted
     * before the UI subscribes (e.g. during init crash-paths) isn't dropped.
     *
     * The screen merges this with [TrackActionsDelegate.userMessages] so
     * preview/download errors surface through the same snackbar host.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

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
 * Adapter: [TrackSummary] → [com.stash.core.media.actions.TrackItem].
 *
 * Used at download call sites in the screen — the delegate's `downloadTrack`
 * takes a [com.stash.core.media.actions.TrackItem] (the minimal identity
 * needed to kick off a yt-dlp download). Kept next to [toSearchResultItem]
 * so the two adapters are easy to compare.
 */
internal fun TrackSummary.toTrackItem() = com.stash.core.media.actions.TrackItem(
    videoId = videoId,
    title = title,
    artist = artist,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
)
