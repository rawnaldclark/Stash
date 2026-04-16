package com.stash.feature.sync

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.DownloadStatus
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import com.stash.data.download.preview.PreviewUrlExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * A single resync candidate representing the best YouTube match found
 * for an unmatched track during the resync operation.
 *
 * @property videoId        YouTube video ID.
 * @property title          Video title as reported by YouTube.
 * @property artist         Uploader/channel name.
 * @property thumbnailUrl   URL for the video thumbnail, if available.
 * @property durationSeconds Video duration in seconds.
 */
data class ResyncCandidate(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationSeconds: Double,
)

/**
 * UI state for the Failed Matches (unmatched songs) screen.
 *
 * @property tracks           List of tracks that could not be matched on YouTube.
 * @property isLoading        True while the initial data load is in progress.
 * @property previewLoading   The videoId currently being loaded for preview, or null.
 * @property resyncCandidates Map of trackId -> best candidate found during resync.
 * @property isResyncing      True while a resync operation is running.
 * @property resyncProgress   Human-readable progress string (e.g. "3 of 12").
 * @property approvingIds     Set of trackIds currently being downloaded via approve.
 */
data class FailedMatchesUiState(
    val tracks: List<UnmatchedTrackView> = emptyList(),
    val isLoading: Boolean = true,
    val previewLoading: String? = null,
    val resyncCandidates: Map<Long, ResyncCandidate> = emptyMap(),
    val isResyncing: Boolean = false,
    val resyncProgress: String = "",
    val approvingIds: Set<Long> = emptySet(),
)

/**
 * ViewModel for the Failed Matches screen.
 *
 * Observes unmatched tracks from the repository and exposes them as a
 * [StateFlow]. Provides:
 * - **Resync**: re-searches YouTube for each unmatched track via [HybridSearchExecutor].
 * - **Approve**: downloads an approved candidate (download -> organize -> update DB).
 * - **Preview**: audio preview for rejected match candidates via [PreviewPlayer].
 * - **Dismiss**: permanently removes a track from future sync retry attempts.
 */
@HiltViewModel
class FailedMatchesViewModel @Inject constructor(
    private val musicRepository: MusicRepository,
    private val previewPlayer: PreviewPlayer,
    private val previewUrlExtractor: PreviewUrlExtractor,
    private val searchExecutor: HybridSearchExecutor,
    private val downloadExecutor: DownloadExecutor,
    private val fileOrganizer: FileOrganizer,
    private val qualityPrefs: QualityPreferencesManager,
    private val trackDao: TrackDao,
    private val downloadQueueDao: DownloadQueueDao,
) : ViewModel() {

    companion object {
        private const val TAG = "FailedMatchesVM"

        /** Maximum concurrent YouTube searches during resync. */
        private const val RESYNC_CONCURRENCY = 2
    }

    /** Observable preview playback state for the UI to highlight the active row. */
    val previewState: StateFlow<PreviewState> = previewPlayer.previewState

    // -- Internal state flows -----------------------------------------------

    private val _previewLoading = MutableStateFlow<String?>(null)
    private val _resyncCandidates = MutableStateFlow<Map<Long, ResyncCandidate>>(emptyMap())
    private val _isResyncing = MutableStateFlow(false)
    private val _resyncProgress = MutableStateFlow("")
    private val _approvingIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Active resync job reference so it can be cancelled on new resync or cleanup. */
    private var resyncJob: Job? = null

    // -- Combined UI state --------------------------------------------------

    val uiState: StateFlow<FailedMatchesUiState> =
        combine(
            musicRepository.getUnmatchedTracks(),
            _previewLoading,
            _resyncCandidates,
            _isResyncing,
            _resyncProgress,
        ) { tracks, loading, candidates, resyncing, progress ->
            FailedMatchesUiState(
                tracks = tracks,
                isLoading = false,
                previewLoading = loading,
                resyncCandidates = candidates,
                isResyncing = resyncing,
                resyncProgress = progress,
                // Read approvingIds directly — combine() supports max 5 typed flows
                approvingIds = _approvingIds.value,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = FailedMatchesUiState(),
        )

    // -- Resync: re-search YouTube for all unmatched tracks -----------------

    /**
     * Launches a resync operation that searches YouTube for each unmatched
     * track using the stored search query. Runs up to [RESYNC_CONCURRENCY]
     * searches in parallel to avoid overwhelming the network/yt-dlp.
     *
     * Cancels any previous resync before starting a new one.
     */
    fun resync() {
        resyncJob?.cancel()
        resyncJob = viewModelScope.launch {
            _resyncCandidates.value = emptyMap()
            _isResyncing.value = true
            _resyncProgress.value = ""

            val tracks = uiState.value.tracks
            val semaphore = Semaphore(RESYNC_CONCURRENCY)
            val total = tracks.size
            val completed = AtomicInteger(0)

            tracks.map { track ->
                launch {
                    semaphore.acquire()
                    try {
                        val results = searchExecutor.search(track.searchQuery, maxResults = 5)
                        val best = results.firstOrNull()
                        if (best != null) {
                            _resyncCandidates.update { current ->
                                current + (track.trackId to ResyncCandidate(
                                    videoId = best.id,
                                    title = best.title,
                                    artist = best.uploader,
                                    thumbnailUrl = best.thumbnail,
                                    durationSeconds = best.duration,
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Resync search failed for '${track.searchQuery}': ${e.message}")
                    } finally {
                        semaphore.release()
                        val done = completed.incrementAndGet()
                        _resyncProgress.value = "$done of $total"
                    }
                }
            }.joinAll()

            _isResyncing.value = false
        }
    }

    // -- Approve: download a resync candidate and update the DB -------------

    /**
     * Downloads the approved resync candidate and updates the database so the
     * track is marked as downloaded and removed from the failed matches list.
     *
     * Follows the same download chain as SearchViewModel: yt-dlp download ->
     * file organization -> DB update.
     *
     * @param trackId      Primary key of the track in the tracks table.
     * @param queueEntryId Row ID of the download_queue entry to mark completed.
     * @param candidate    The [ResyncCandidate] the user approved.
     */
    fun approveMatch(trackId: Long, queueEntryId: Long, candidate: ResyncCandidate) {
        if (trackId in _approvingIds.value) return
        _approvingIds.update { it + trackId }

        viewModelScope.launch {
            try {
                val url = "https://www.youtube.com/watch?v=${candidate.videoId}"
                val qualityTier = qualityPrefs.qualityTier.first()
                val qualityArgs = qualityTier.toYtDlpArgs()
                val tempDir = fileOrganizer.getTempDir()
                val tempFilename = "approve_${candidate.videoId}"

                val result = downloadExecutor.download(
                    url = url,
                    outputDir = tempDir,
                    filename = tempFilename,
                    qualityArgs = qualityArgs,
                )

                when (result) {
                    is DownloadResult.Success -> {
                        // Use the original track metadata for file organization
                        val track = uiState.value.tracks.find { it.trackId == trackId }
                        val artist = track?.artist ?: candidate.artist
                        val title = track?.title ?: candidate.title

                        val finalFile = fileOrganizer.getTrackFile(
                            artist = artist,
                            album = null,
                            title = title,
                            format = result.file.extension,
                        )
                        result.file.copyTo(finalFile, overwrite = true)
                        result.file.delete()

                        // Mark track as downloaded with file info
                        trackDao.markAsDownloaded(trackId, finalFile.absolutePath, finalFile.length())
                        // Set youtubeId so future syncs don't re-queue this track
                        trackDao.updateYoutubeId(trackId, candidate.videoId)
                        // Mark queue entry as completed so it disappears from the list
                        downloadQueueDao.updateStatus(
                            id = queueEntryId,
                            status = DownloadStatus.COMPLETED,
                        )

                        // Remove from approving set (track will vanish from list via Flow)
                        _approvingIds.update { it - trackId }
                        // Remove from resync candidates since it's now downloaded
                        _resyncCandidates.update { it - trackId }
                    }
                    else -> {
                        Log.e(TAG, "Approve download failed for ${candidate.title}: $result")
                        _approvingIds.update { it - trackId }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Approve failed for trackId=$trackId", e)
                _approvingIds.update { it - trackId }
            }
        }
    }

    // -- Dismiss: permanently skip a track ----------------------------------

    /**
     * Marks a track as dismissed so it will no longer be retried during sync.
     *
     * @param trackId The ID of the track to dismiss.
     */
    fun dismissTrack(trackId: Long) {
        viewModelScope.launch {
            musicRepository.dismissMatch(trackId)
        }
    }

    // -- Audio preview ------------------------------------------------------

    /**
     * Starts an audio preview for the closest rejected YouTube match.
     *
     * Stops any currently playing preview first, then extracts a direct stream
     * URL via [PreviewUrlExtractor] and hands it to [PreviewPlayer].
     *
     * @param videoId The YouTube video ID of the rejected candidate.
     */
    fun previewRejectedMatch(videoId: String) {
        previewPlayer.stop()
        viewModelScope.launch {
            _previewLoading.value = videoId
            try {
                val url = previewUrlExtractor.extractStreamUrl(videoId)
                previewPlayer.playUrl(videoId, url)
            } catch (e: Exception) {
                Log.e(TAG, "Preview failed for videoId=$videoId", e)
            }
            _previewLoading.value = null
        }
    }

    /** Stops the current audio preview, if any. */
    fun stopPreview() {
        previewPlayer.stop()
        _previewLoading.value = null
    }

    // -- Lifecycle -----------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        previewPlayer.stop()
        resyncJob?.cancel()
    }
}
