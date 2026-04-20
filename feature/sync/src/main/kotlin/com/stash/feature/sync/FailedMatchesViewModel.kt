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
import com.stash.data.download.files.SwapCoordinator
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import com.stash.data.download.preview.PreviewUrlExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
 * Lightweight view model for a user-flagged track — the audio downloaded
 * fine, but it's the wrong song. Sits alongside [UnmatchedTrackView] in the
 * Failed Matches screen so the same resync + preview infrastructure can
 * produce replacement candidates.
 *
 * @property trackId          Primary key of the track in the tracks table.
 * @property title            Original Spotify / YouTube metadata title.
 * @property artist           Original metadata artist.
 * @property albumArtUrl      Original album art, used as a visual anchor.
 * @property currentYoutubeId The currently-associated YT video (wrong one).
 * @property currentFilePath  On-disk file to delete when the swap is approved.
 * @property searchQuery      "<artist> - <title>" — what the resync feeds into YT search.
 */
data class FlaggedTrackRow(
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val currentYoutubeId: String?,
    val currentFilePath: String?,
    val searchQuery: String,
)

/**
 * UI state for the Failed Matches screen.
 *
 * @property tracks           Tracks that sync couldn't match on YouTube at all.
 * @property flaggedTracks    Tracks the user marked as "wrong song" from Now Playing.
 * @property isLoading        True while the initial data load is in progress.
 * @property previewLoading   The videoId currently being loaded for preview, or null.
 * @property resyncCandidates Map of trackId -> best candidate found during resync.
 * @property isResyncing      True while a resync operation is running.
 * @property resyncProgress   Human-readable progress string (e.g. "3 of 12").
 */
data class FailedMatchesUiState(
    val tracks: List<UnmatchedTrackView> = emptyList(),
    val flaggedTracks: List<FlaggedTrackRow> = emptyList(),
    val isLoading: Boolean = true,
    val previewLoading: String? = null,
    val resyncCandidates: Map<Long, ResyncCandidate> = emptyMap(),
    val isResyncing: Boolean = false,
    val resyncProgress: String = "",
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
    private val swapCoordinator: SwapCoordinator,
) : ViewModel() {

    companion object {
        private const val TAG = "FailedMatchesVM"

        /** Maximum concurrent YouTube searches during resync. */
        private const val RESYNC_CONCURRENCY = 4

        /** How many resync candidates to pre-extract preview URLs for. */
        private const val PRE_EXTRACT_LIMIT = 10

        /** Max concurrent yt-dlp preview extractions (each is CPU-heavy). */
        private const val PRE_EXTRACT_CONCURRENCY = 2
    }

    /** Observable preview playback state for the UI to highlight the active row. */
    val previewState: StateFlow<PreviewState> = previewPlayer.previewState

    // -- Internal state flows -----------------------------------------------

    private val _previewLoading = MutableStateFlow<String?>(null)
    private val _resyncCandidates = MutableStateFlow<Map<Long, ResyncCandidate>>(emptyMap())
    private val _isResyncing = MutableStateFlow(false)
    private val _resyncProgress = MutableStateFlow("")

    private val _userMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** One-shot user-facing messages (e.g. Snackbar text). */
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    /** Active resync job reference so it can be cancelled on new resync or cleanup. */
    private var resyncJob: Job? = null

    /**
     * Cache of pre-extracted stream URLs, keyed by videoId.
     * Populated in the background after resync completes.
     */
    private val previewUrlCache = mutableMapOf<String, String>()

    /** Active pre-extraction jobs — cancelled when a new resync starts. */
    private var preExtractJobs = mutableListOf<Job>()

    // -- Combined UI state --------------------------------------------------

    /**
     * Flagged tracks pre-mapped to the UI row type so the main
     * [combine] below only needs to know about one shape. Keeps the
     * outer [combine] under its 5-param typed-overload limit.
     */
    private val flaggedRows: Flow<List<FlaggedTrackRow>> =
        musicRepository.getFlaggedTracks().map { entities ->
            entities.map { t ->
                FlaggedTrackRow(
                    trackId = t.id,
                    title = t.title,
                    artist = t.artist,
                    albumArtUrl = t.albumArtUrl,
                    currentYoutubeId = t.youtubeId,
                    currentFilePath = t.filePath,
                    searchQuery = "${t.artist} - ${t.title}",
                )
            }
        }

    val uiState: StateFlow<FailedMatchesUiState> =
        combine(
            musicRepository.getUnmatchedTracks(),
            flaggedRows,
            _previewLoading,
            _resyncCandidates,
            combine(_isResyncing, _resyncProgress) { r, p -> r to p },
        ) { tracks, flagged, loading, candidates, resyncState ->
            FailedMatchesUiState(
                tracks = tracks,
                flaggedTracks = flagged,
                isLoading = false,
                previewLoading = loading,
                resyncCandidates = candidates,
                isResyncing = resyncState.first,
                resyncProgress = resyncState.second,
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

            // Single search pass over BOTH unmatched and user-flagged tracks.
            // Each row becomes a (trackId, searchQuery, rejectedVideoId?)
            // triple so the same semaphored search loop handles both kinds.
            val unmatched = uiState.value.tracks.map {
                Triple(it.trackId, it.searchQuery, it.rejectedVideoId)
            }
            val flagged = uiState.value.flaggedTracks.map {
                Triple(it.trackId, it.searchQuery, it.currentYoutubeId)
            }
            val jobs = unmatched + flagged

            val semaphore = Semaphore(RESYNC_CONCURRENCY)
            val total = jobs.size
            val completed = AtomicInteger(0)

            jobs.map { (trackId, query, excludeVideoId) ->
                launch {
                    semaphore.acquire()
                    try {
                        val results = searchExecutor.search(query, maxResults = 5)
                        // For flagged tracks, skip the currently-associated
                        // (wrong) video — surfacing it as the candidate would
                        // just swap the track with itself.
                        val best = results.firstOrNull { excludeVideoId == null || it.id != excludeVideoId }
                            ?: results.firstOrNull()
                        if (best != null) {
                            _resyncCandidates.update { current ->
                                current + (trackId to ResyncCandidate(
                                    videoId = best.id,
                                    title = best.title,
                                    artist = best.uploader,
                                    thumbnailUrl = best.thumbnail,
                                    durationSeconds = best.duration,
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Resync search failed for '$query': ${e.message}")
                    } finally {
                        semaphore.release()
                        val done = completed.incrementAndGet()
                        _resyncProgress.value = "$done of $total"
                    }
                }
            }.joinAll()

            _isResyncing.value = false

            // Pre-extract stream URLs for instant audio previews
            preExtractStreamUrls(_resyncCandidates.value)
        }
    }

    // -- Pre-extract preview URLs in background --------------------------------

    /**
     * Pre-extracts stream URLs for resync candidates in the background.
     *
     * Runs up to [PRE_EXTRACT_LIMIT] extractions concurrently (limited by
     * [PRE_EXTRACT_CONCURRENCY] semaphore). Extracted URLs are cached in
     * [previewUrlCache] and served instantly when the user taps preview.
     */
    private fun preExtractStreamUrls(candidates: Map<Long, ResyncCandidate>) {
        preExtractJobs.forEach { it.cancel() }
        preExtractJobs.clear()
        previewUrlCache.clear()

        val semaphore = Semaphore(PRE_EXTRACT_CONCURRENCY)
        candidates.values.take(PRE_EXTRACT_LIMIT).forEach { candidate ->
            val job = viewModelScope.launch {
                semaphore.acquire()
                try {
                    val url = previewUrlExtractor.extractStreamUrl(candidate.videoId)
                    previewUrlCache[candidate.videoId] = url
                    Log.d(TAG, "Pre-extracted preview URL for ${candidate.videoId}")
                } catch (e: Exception) {
                    Log.w(TAG, "Pre-extract failed for ${candidate.videoId}: ${e.message}")
                } finally {
                    semaphore.release()
                }
            }
            preExtractJobs.add(job)
        }
    }

    // -- Approve: download a resync candidate and update the DB -------------

    /**
     * Optimistically approves a resync candidate: immediately marks the queue
     * entry as COMPLETED and sets the youtubeId on the track so the row
     * disappears from the reactive [getUnmatchedTracks] Flow. The actual
     * download runs in a fire-and-forget background coroutine.
     *
     * @param trackId      Primary key of the track in the tracks table.
     * @param queueEntryId Row ID of the download_queue entry to mark completed.
     * @param candidate    The [ResyncCandidate] the user approved.
     */
    fun approveMatch(trackId: Long, queueEntryId: Long, candidate: ResyncCandidate) {
        viewModelScope.launch {
            val existing = trackDao.findByYoutubeId(candidate.videoId)
            if (existing != null && existing.id != trackId) {
                _userMessages.tryEmit(
                    "Can't approve \u2014 '${candidate.title}' is already linked to " +
                        "${existing.artist} \u2014 ${existing.title}. Try Dismiss instead.",
                )
                return@launch
            }

            try {
                // Immediately mark as completed — row disappears from reactive Flow
                trackDao.updateYoutubeId(trackId, candidate.videoId)
                downloadQueueDao.updateStatus(
                    id = queueEntryId,
                    status = DownloadStatus.COMPLETED,
                )

                // Remove from resync candidates map
                _resyncCandidates.update { it - trackId }
            } catch (e: Exception) {
                Log.e(TAG, "Approve failed for trackId=$trackId", e)
                _userMessages.tryEmit("Couldn't approve this match. Please try again.")
                return@launch
            }

            // Background download — fire and forget
            launch {
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

                    if (result is DownloadResult.Success) {
                        val track = trackDao.getById(trackId)
                        val artist = track?.artist ?: candidate.artist
                        val title = track?.title ?: candidate.title

                        val committed = fileOrganizer.commitDownload(
                            tempFile = result.file,
                            artist = artist,
                            album = null,
                            title = title,
                            format = result.file.extension,
                        )
                        trackDao.markAsDownloaded(trackId, committed.filePath, committed.sizeBytes)
                    } else {
                        Log.w(TAG, "Background download failed for ${candidate.title}: $result")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Background download error for ${candidate.title}", e)
                }
            }
        }
    }

    // -- Approve All: batch approve every track with a candidate ---------------

    /**
     * Approves all tracks that have a resync candidate. Since [approveMatch]
     * is optimistic, all rows disappear immediately and downloads queue in
     * the background.
     */
    fun approveAll() {
        val tracks = uiState.value.tracks
        val candidates = _resyncCandidates.value

        tracks.forEach { track ->
            val candidate = candidates[track.trackId]
            if (candidate != null) {
                approveMatch(track.trackId, track.id, candidate)
            }
        }
    }

    // -- Approve a swap for a FLAGGED track --------------------------------

    /**
     * Approves a replacement candidate for a user-flagged (wrong-match)
     * track. Deletes the old audio file, kicks a fresh yt-dlp download of
     * the new video in the background, then swaps youtubeId + file_path +
     * file_size + clears the flag so the track disappears from the Failed
     * Matches screen. File IO failures on the old-file delete are swallowed
     * — a stray leftover file is strictly better than a lost swap, and the
     * orphan cleanup pass will eventually catch it.
     */
    fun approveSwap(row: FlaggedTrackRow, candidate: ResyncCandidate) {
        viewModelScope.launch {
            // Guard: another track already owns this videoId — swapping would
            // violate the UNIQUE(youtube_id) constraint and blow up silently.
            val existing = trackDao.findByYoutubeId(candidate.videoId)
            if (existing != null && existing.id != row.trackId) {
                _userMessages.tryEmit(
                    "Can't swap — '${candidate.title}' is already linked to " +
                        "${existing.artist} — ${existing.title}.",
                )
                return@launch
            }

            // Optimistically clear the flag + remove from candidates so the
            // row disappears from the Failed Matches screen immediately.
            try {
                musicRepository.setMatchFlagged(row.trackId, false)
                _resyncCandidates.update { it - row.trackId }
            } catch (e: Exception) {
                Log.e(TAG, "approveSwap pre-download update failed", e)
                _userMessages.tryEmit("Couldn't approve this swap. Please try again.")
                return@launch
            }

            // Hand the download + commit + DB update off to SwapCoordinator's
            // application-scope so it survives the user leaving this screen.
            // Pre-Phase-5b this was an inline `launch {}` on viewModelScope,
            // which got cancelled the instant the user navigated away — they
            // ended up with the DB pointing at a deleted file while the new
            // audio never actually landed.
            swapCoordinator.swap(
                trackId = row.trackId,
                oldFilePath = row.currentFilePath,
                artist = row.artist,
                title = row.title,
                newVideoId = candidate.videoId,
            )
        }
    }

    /**
     * Clear a flag without permanently dismissing the track. Used when the
     * user inspects a flagged track in Failed Matches and decides the
     * original match was actually fine. Unlike [dismissTrack], this does
     * NOT set match_dismissed, so future sync attempts still behave
     * normally for this row.
     */
    fun unflagTrack(trackId: Long) {
        viewModelScope.launch {
            musicRepository.setMatchFlagged(trackId, false)
            _resyncCandidates.update { it - trackId }
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

    /** Dismiss ALL unmatched tracks permanently — never retry any of them. */
    fun dismissAll() {
        viewModelScope.launch {
            uiState.value.tracks.forEach { track ->
                musicRepository.dismissMatch(track.trackId)
            }
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
                // Check cache first — if pre-extraction finished, this is instant
                val url = previewUrlCache[videoId]
                    ?: previewUrlExtractor.extractStreamUrl(videoId).also {
                        previewUrlCache[videoId] = it
                    }
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
        preExtractJobs.forEach { it.cancel() }
        previewUrlCache.clear()
    }
}
