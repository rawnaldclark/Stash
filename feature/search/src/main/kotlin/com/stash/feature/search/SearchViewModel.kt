package com.stash.feature.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the global search screen.
 *
 * Handles debounced YouTube Music searching via [HybridSearchExecutor] and
 * on-demand track downloading via [DownloadExecutor]. Downloaded tracks are
 * organized into the file system by [FileOrganizer] and persisted to the
 * local database via [MusicRepository].
 *
 * Multiple downloads can run concurrently -- each launches its own coroutine.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchExecutor: HybridSearchExecutor,
    private val downloadExecutor: DownloadExecutor,
    private val trackDao: TrackDao,
    private val fileOrganizer: FileOrganizer,
    private val qualityPrefs: QualityPreferencesManager,
    private val musicRepository: MusicRepository,
    private val previewPlayer: PreviewPlayer,
    private val previewUrlExtractor: PreviewUrlExtractor,
) : ViewModel() {

    companion object {
        private const val TAG = "SearchVM"

        /** Minimum query length before triggering a search. */
        private const val MIN_QUERY_LENGTH = 2

        /** Debounce delay in milliseconds after the user stops typing. */
        private const val DEBOUNCE_MS = 500L

        /** Maximum number of search results to request. */
        private const val MAX_RESULTS = 20

        /** How many search results to pre-extract preview URLs for. */
        private const val PRE_EXTRACT_LIMIT = 6

        /** Max concurrent yt-dlp preview extractions (each is CPU-heavy). */
        private const val PRE_EXTRACT_CONCURRENCY = 2
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Observable preview playback state for the UI to highlight the active row. */
    val previewState: StateFlow<PreviewState> = previewPlayer.previewState

    /** Reference to the current search coroutine so it can be cancelled on new input. */
    private var searchJob: Job? = null

    /**
     * Cache of pre-extracted stream URLs, keyed by videoId.
     * Populated in the background as soon as search results arrive.
     * Cleared on each new search.
     */
    private val previewUrlCache = mutableMapOf<String, String>()

    /** Active pre-extraction jobs, keyed by videoId. Can be cancelled on new search. */
    private var preExtractJobs = mutableListOf<Job>()

    /**
     * Called whenever the search text field value changes.
     *
     * Cancels any pending search, updates the query in state, and schedules
     * a new search after the debounce period if the query is long enough.
     */
    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }

        searchJob?.cancel()
        if (query.length < MIN_QUERY_LENGTH) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false, error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            performSearch(query)
        }
    }

    /**
     * Executes the YouTube Music search and cross-references results against
     * the local database to identify already-downloaded tracks.
     */
    private suspend fun performSearch(query: String) {
        _uiState.update { it.copy(isSearching = true, error = null) }
        try {
            val results = searchExecutor.search(query, maxResults = MAX_RESULTS)
            val items = results.map { result ->
                SearchResultItem(
                    videoId = result.id,
                    title = result.title,
                    artist = result.uploader,
                    durationSeconds = result.duration,
                    thumbnailUrl = result.thumbnail,
                )
            }

            // Identify which results are already in the local library
            val downloadedIds = items.mapNotNull { item ->
                val track = trackDao.findByYoutubeId(item.videoId)
                if (track?.isDownloaded == true) item.videoId else null
            }.toSet()

            _uiState.update {
                it.copy(
                    results = items,
                    isSearching = false,
                    downloadedIds = downloadedIds,
                )
            }

            // Pre-extract stream URLs in the background so previews are instant.
            // Uses a semaphore of 2 to avoid overwhelming yt-dlp with parallel calls.
            preExtractStreamUrls(items)
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for query: $query", e)
            _uiState.update { it.copy(isSearching = false, error = e.message) }
        }
    }

    /**
     * Pre-extracts stream URLs for search results in the background.
     *
     * Runs up to [PRE_EXTRACT_LIMIT] extractions concurrently (limited by
     * [PRE_EXTRACT_CONCURRENCY] semaphore). Extracted URLs are cached in
     * [previewUrlCache] and served instantly when the user taps preview.
     *
     * Each extraction takes ~20-35s due to QuickJS-NG cipher solving, but
     * since this runs in the background while the user browses results,
     * the first few URLs are typically ready by the time they tap preview.
     */
    private fun preExtractStreamUrls(items: List<SearchResultItem>) {
        // Cancel any previous pre-extraction jobs and clear stale cache
        preExtractJobs.forEach { it.cancel() }
        preExtractJobs.clear()
        previewUrlCache.clear()

        val semaphore = kotlinx.coroutines.sync.Semaphore(PRE_EXTRACT_CONCURRENCY)

        // Only pre-extract the first N results — user rarely scrolls past these
        items.take(PRE_EXTRACT_LIMIT).forEach { item ->
            val job = viewModelScope.launch {
                semaphore.acquire()
                try {
                    val url = previewUrlExtractor.extractStreamUrl(item.videoId)
                    previewUrlCache[item.videoId] = url
                    Log.d(TAG, "Pre-extracted preview URL for ${item.videoId} (cache size: ${previewUrlCache.size})")
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-extract failed for ${item.videoId}: ${e.message}")
                } finally {
                    semaphore.release()
                }
            }
            preExtractJobs.add(job)
        }
    }

    /**
     * Initiates a background download of the given search result.
     *
     * The download runs in its own coroutine so the user can continue
     * searching or start additional downloads concurrently. UI state is
     * updated to reflect downloading/downloaded status as the operation
     * progresses.
     *
     * No-ops if the track is already downloading or downloaded.
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

    /**
     * Moves the downloaded file to its final organized location and inserts
     * a track record into the database.
     */
    private suspend fun handleDownloadSuccess(result: DownloadResult.Success, item: SearchResultItem) {
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

    /** Removes the video ID from the downloading set after a failure. */
    private fun markDownloadFailed(videoId: String) {
        _uiState.update { it.copy(downloadingIds = it.downloadingIds - videoId) }
    }

    // ------------------------------------------------------------------
    // Audio preview
    // ------------------------------------------------------------------

    /**
     * Starts an audio preview for the given video ID.
     *
     * Stops any currently playing preview first, then extracts a direct stream
     * URL via [PreviewUrlExtractor] and hands it to [PreviewPlayer]. Loading
     * and error states are surfaced through [SearchUiState].
     */
    fun previewTrack(videoId: String) {
        previewPlayer.stop()
        viewModelScope.launch {
            _uiState.update { it.copy(previewLoading = videoId, previewError = null) }
            try {
                // Check cache first — if pre-extraction finished, this is instant
                val url = previewUrlCache[videoId]
                    ?: previewUrlExtractor.extractStreamUrl(videoId).also {
                        previewUrlCache[videoId] = it
                    }
                previewPlayer.playUrl(videoId, url)
                _uiState.update { it.copy(previewLoading = null) }
            } catch (e: Exception) {
                Log.e(TAG, "Preview failed for videoId=$videoId", e)
                _uiState.update { it.copy(previewLoading = null, previewError = "Couldn't load preview") }
                previewPlayer.stop()
            }
        }
    }

    /** Stops the current audio preview, if any. */
    fun stopPreview() {
        previewPlayer.stop()
        _uiState.update { it.copy(previewLoading = null) }
    }

    /** Clears the inline preview error message. */
    fun clearPreviewError() {
        _uiState.update { it.copy(previewError = null) }
    }

    override fun onCleared() {
        super.onCleared()
        previewPlayer.stop()
        preExtractJobs.forEach { it.cancel() }
        previewUrlCache.clear()
    }
}
