package com.stash.feature.search

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import com.stash.data.download.preview.PreviewUrlCache
import com.stash.data.download.preview.PreviewUrlExtractor
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Search screen.
 *
 * Task 9 rewires the search path from a manual `delay → launch` debounce
 * onto [flatMapLatest] driven by a [MutableStateFlow] so a new keystroke
 * cancels the in-flight `searchAll` call without any bookkeeping. The
 * download path is untouched — it still uses [DownloadExecutor] and a
 * per-tap [viewModelScope.launch] so concurrent downloads work.
 *
 * ### Preview retry
 *
 * Preview playback starts on an InnerTube URL (fast, ~1-2 s to extract).
 * If ExoPlayer rejects that URL within 3 s (typically because the URL is
 * n-parameter-throttled harder than the player can tolerate), [onPreviewError]
 * silently retries via yt-dlp's cipher path. The 3 s window guards against
 * retrying on unrelated failures that happen mid-playback — at that point
 * the InnerTube URL has been serving audio for a while and yt-dlp is
 * unlikely to fix whatever the user's network dropped.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel @Inject constructor(
    private val api: YTMusicApiClient,
    private val previewPlayer: PreviewPlayer,
    private val previewUrlExtractor: PreviewUrlExtractor,
    private val previewUrlCache: PreviewUrlCache,
    private val prefetcher: PreviewPrefetcher,
    private val trackDao: TrackDao,
    private val downloadExecutor: DownloadExecutor,
    private val fileOrganizer: FileOrganizer,
    private val qualityPrefs: QualityPreferencesManager,
    private val musicRepository: MusicRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "SearchVM"

        /** Minimum query length before triggering a search. */
        private const val MIN_QUERY_LENGTH = 2

        /** Debounce delay in milliseconds after the user stops typing. */
        private const val DEBOUNCE_MS = 300L

        /**
         * Retry window after `playUrl` — an ExoPlayer error inside this
         * window is treated as an InnerTube URL rejection and triggers
         * the yt-dlp fallback. Errors outside the window are left alone.
         */
        private const val RETRY_WINDOW_MS = 3_000L

        /** Max number of tracks to pre-warm URLs for on a fresh results page. */
        private const val PREFETCH_TOP_N = 6
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Observable preview playback state for the UI to highlight the active row. */
    val previewState: StateFlow<PreviewState> = previewPlayer.previewState

    /**
     * One-shot user-facing messages (snackbars). Buffered so a message emitted
     * before the UI subscribes (e.g. during init crash-paths) isn't dropped.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    /** Drives [flatMapLatest] — every keystroke replaces the value. */
    private val queryFlow = MutableStateFlow("")

    /**
     * Tracks the most-recent `playUrl(videoId)` timestamp so [onPreviewError]
     * can decide whether the error is close enough to playback-start to
     * warrant an automatic yt-dlp retry.
     */
    private var lastPreviewVideoId: String? = null
    private var lastPreviewStartedAt: Long = 0L

    init {
        // Search pipeline — keystrokes → debounce → searchAll → status.
        viewModelScope.launch {
            queryFlow
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                .flatMapLatest { q -> runSearch(q) }
                .collect { status -> _uiState.update { it.copy(status = status) } }
        }

        // Player-error pipeline — route ExoPlayer errors into onPreviewError
        // so the UI layer doesn't need to know about the retry policy.
        viewModelScope.launch {
            previewPlayer.playerErrors.collect { event ->
                onPreviewError(event.videoId, event.error)
            }
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
        emit(SearchStatus.Loading)
        try {
            val results = api.searchAll(query)
            val sections = results.sections
            if (sections.isEmpty()) {
                emit(SearchStatus.Empty)
            } else {
                emit(SearchStatus.Results(sections))
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
     * Cross-reference the visible tracks against the local DB so already-
     * downloaded rows render with the green checkmark instead of the
     * download arrow.
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
        if (videoIds.isEmpty()) return
        val downloaded = videoIds.filter { id ->
            trackDao.findByYoutubeId(id)?.isDownloaded == true
        }.toSet()
        _uiState.update { it.copy(downloadedIds = it.downloadedIds + downloaded) }
    }

    // ------------------------------------------------------------------
    // Download path (unchanged behaviour from Task 8)
    // ------------------------------------------------------------------

    /**
     * Initiates a background download of the given search result.
     *
     * Runs in its own coroutine so the user can continue searching or start
     * additional downloads concurrently. No-ops when the track is already
     * downloading or downloaded.
     */
    fun downloadTrack(item: SearchResultItem) {
        if (item.videoId in _uiState.value.downloadingIds) return
        if (item.videoId in _uiState.value.downloadedIds) return

        _uiState.update { it.copy(downloadingIds = it.downloadingIds + item.videoId) }

        viewModelScope.launch {
            try {
                val url = "https://www.youtube.com/watch?v=${item.videoId}"
                val qualityTier = qualityPrefs.qualityTier.first()
                val qualityArgs = qualityTier.toYtDlpArgs()
                val tempDir = fileOrganizer.getTempDir()
                val tempFilename = "search_${item.videoId}"

                val result = downloadExecutor.download(
                    url = url,
                    outputDir = tempDir,
                    filename = tempFilename,
                    qualityArgs = qualityArgs,
                )

                when (result) {
                    is DownloadResult.Success -> handleDownloadSuccess(result, item)
                    is DownloadResult.YtDlpError -> {
                        Log.e(TAG, "Download failed for ${item.title}: ${result.message.take(100)}")
                        markDownloadFailed(item.videoId)
                    }
                    is DownloadResult.NoOutput -> {
                        Log.e(TAG, "Download produced no output for ${item.title}")
                        markDownloadFailed(item.videoId)
                    }
                    is DownloadResult.Error -> {
                        Log.e(TAG, "Download error for ${item.title}: ${result.message}")
                        markDownloadFailed(item.videoId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error for ${item.title}", e)
                markDownloadFailed(item.videoId)
            }
        }
    }

    private suspend fun handleDownloadSuccess(
        result: DownloadResult.Success,
        item: SearchResultItem,
    ) {
        val finalFile = fileOrganizer.getTrackFile(
            artist = item.artist,
            album = null,
            title = item.title,
            format = result.file.extension,
        )
        result.file.copyTo(finalFile, overwrite = true)
        result.file.delete()

        val track = Track(
            title = item.title,
            artist = item.artist,
            durationMs = (item.durationSeconds * 1000).toLong(),
            source = MusicSource.YOUTUBE,
            youtubeId = item.videoId,
            filePath = finalFile.absolutePath,
            fileSizeBytes = finalFile.length(),
            isDownloaded = true,
            albumArtUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(item.thumbnailUrl),
        )
        musicRepository.insertTrack(track)

        _uiState.update {
            it.copy(
                downloadingIds = it.downloadingIds - item.videoId,
                downloadedIds = it.downloadedIds + item.videoId,
            )
        }
    }

    private fun markDownloadFailed(videoId: String) {
        _uiState.update { it.copy(downloadingIds = it.downloadingIds - videoId) }
    }

    // ------------------------------------------------------------------
    // Audio preview
    // ------------------------------------------------------------------

    /**
     * Starts an audio preview for [videoId].
     *
     * Hits the shared [PreviewUrlCache] first — if the prefetcher already
     * warmed the URL, playback starts immediately. Otherwise falls through
     * to the full [PreviewUrlExtractor] race (InnerTube vs yt-dlp).
     */
    fun previewTrack(videoId: String) {
        previewPlayer.stop()
        viewModelScope.launch {
            _uiState.update { it.copy(previewLoading = videoId) }
            try {
                val url = previewUrlCache[videoId]
                    ?: previewUrlExtractor.extractStreamUrl(videoId).also {
                        previewUrlCache[videoId] = it
                    }
                // Record BEFORE playUrl so an immediate onPlayerError (which
                // can fire synchronously for a malformed URL) still sees the
                // correct "most recent preview" state.
                lastPreviewVideoId = videoId
                lastPreviewStartedAt = SystemClock.elapsedRealtime()
                previewPlayer.playUrl(videoId, url)
                _uiState.update { it.copy(previewLoading = null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Preview failed for videoId=$videoId", e)
                _uiState.update { it.copy(previewLoading = null) }
                _userMessages.emit("Couldn't load preview.")
                previewPlayer.stop()
            }
        }
    }

    /**
     * ExoPlayer error handler — invoked by the [previewPlayer.playerErrors]
     * collector in [init] and by unit tests that want to drive the retry
     * path directly.
     *
     * Retries via yt-dlp IFF all of:
     *  - The error is an IO-class [PlaybackException] code.
     *  - It fired within [RETRY_WINDOW_MS] of the most recent `playUrl` for
     *    the same [videoId] — playback never went ready before failing.
     *
     * If the retry extraction also fails we surface a snackbar so the user
     * knows preview isn't going to recover on its own.
     */
    fun onPreviewError(videoId: String, error: PlaybackException) {
        if (!isIoError(error)) return
        if (videoId != lastPreviewVideoId) return
        val elapsed = SystemClock.elapsedRealtime() - lastPreviewStartedAt
        if (elapsed > RETRY_WINDOW_MS) return

        viewModelScope.launch {
            _uiState.update { it.copy(previewLoading = videoId) }
            try {
                val retryUrl = previewUrlExtractor.extractViaYtDlpForRetry(videoId)
                previewUrlCache[videoId] = retryUrl
                previewPlayer.playUrl(videoId, retryUrl)
                _uiState.update { it.copy(previewLoading = null) }
                Log.d(TAG, "yt-dlp retry SUCCESS for $videoId after InnerTube error $error")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "yt-dlp retry FAILED for $videoId", t)
                _uiState.update { it.copy(previewLoading = null) }
                _userMessages.emit("Couldn't load preview.")
            }
        }
    }

    /**
     * Treat all IO_* error codes as "InnerTube URL rejected" — spec §9.3
     * deliberately broadens this beyond `ERROR_CODE_IO_UNSPECIFIED` so
     * variants like IO_NETWORK_CONNECTION_FAILED or IO_BAD_HTTP_STATUS
     * also get a yt-dlp retry. IO codes are in the 2000-2999 range per
     * media3's documented error-code contract
     * (see [PlaybackException.ErrorCode]).
     */
    private fun isIoError(error: PlaybackException): Boolean =
        error.errorCode in 2000..2999

    /** Stops the current audio preview, if any. */
    fun stopPreview() {
        previewPlayer.stop()
        _uiState.update { it.copy(previewLoading = null) }
    }

    override fun onCleared() {
        super.onCleared()
        previewPlayer.stop()
    }
}

/**
 * Adapter: [TrackSummary] → [SearchResultItem].
 *
 * Bridges the new sectioned model to the still-existing [SearchResultItem]-
 * based download + [PreviewDownloadRow] call sites. Kept as a top-level
 * extension so it's trivially testable and importable from
 * [SearchScreen].
 */
internal fun TrackSummary.toSearchResultItem() = SearchResultItem(
    videoId = videoId,
    title = title,
    artist = artist,
    durationSeconds = durationSeconds,
    thumbnailUrl = thumbnailUrl,
)
