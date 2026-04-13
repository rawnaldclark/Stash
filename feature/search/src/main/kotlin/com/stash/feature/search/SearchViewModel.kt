package com.stash.feature.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.matching.HybridSearchExecutor
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
) : ViewModel() {

    companion object {
        private const val TAG = "SearchVM"

        /** Minimum query length before triggering a search. */
        private const val MIN_QUERY_LENGTH = 2

        /** Debounce delay in milliseconds after the user stops typing. */
        private const val DEBOUNCE_MS = 500L

        /** Maximum number of search results to request. */
        private const val MAX_RESULTS = 20
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Reference to the current search coroutine so it can be cancelled on new input. */
    private var searchJob: Job? = null

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
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for query: $query", e)
            _uiState.update { it.copy(isSearching = false, error = e.message) }
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
}
