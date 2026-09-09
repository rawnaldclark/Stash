package com.stash.feature.sync

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.model.DownloadStatus
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.SwapCoordinator
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import com.stash.data.download.preview.NoFastStreamException
import com.stash.data.download.preview.PreviewUrlExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
    private val localFileOps: com.stash.core.data.files.LocalFileOps,
    private val trackIdentityEvents: TrackIdentityEvents,
) : ViewModel() {

    companion object {
        private const val TAG = "FailedMatchesVM"

        /** Maximum concurrent YouTube searches during resync. */
        private const val RESYNC_CONCURRENCY = 4

        /** How many resync candidates to pre-extract preview URLs for. */
        private const val PRE_EXTRACT_LIMIT = 10

        /** Max concurrent speculative InnerTube-only preview extractions. */
        private const val PRE_EXTRACT_CONCURRENCY = 2

        private const val PREVIEW_FAILURE_MESSAGE =
            "Couldn't load that preview. Try again, or approve to hear the full track."

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

    /** User-initiated extraction and one-shot fallback jobs. */
    private var previewLoadJob: Job? = null
    private var previewRetryJob: Job? = null

    /**
     * Ownership token for asynchronous preview work. A new tap or explicit stop
     * advances the generation so late extractor/player events cannot revive an
     * older candidate.
     */
    private var previewRequestGeneration = 0L
    private var activePreviewRequestId: Long? = null
    private var activePreviewVideoId: String? = null
    private var activePreviewAttemptId: Long? = null
    private var activePreviewRetried = false

    init {
        viewModelScope.launch {
            previewPlayer.playerErrors.collect { event ->
                onPreviewPlayerError(event.videoId, event.attemptId, event.error)
            }
        }
    }

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
            // Keep whether an excluded result may be surfaced as a last resort:
            // unmatched rows can still preview their rejected candidate, while a
            // flagged row must never be offered its current wrong video again.
            // A track can transiently appear in both repository flows; flagged
            // ownership wins so an unmatched fallback cannot reintroduce the
            // very video the user marked as wrong.
            val flaggedRowsSnapshot = uiState.value.flaggedTracks
            val flaggedTrackIds = flaggedRowsSnapshot.mapTo(mutableSetOf()) { it.trackId }
            val unmatched = uiState.value.tracks
                .filterNot { it.trackId in flaggedTrackIds }
                .map {
                // Auto-requeued tracks (TrackDownloadWorker) get a blank
                // download_queue.search_query. Fall back to "artist - title"
                // — which we already have on the row — so resync can actually
                // search instead of firing a blank query that finds nothing.
                val query = it.searchQuery.ifBlank { "${it.artist} - ${it.title}" }
                ResyncSearch(
                    trackId = it.trackId,
                    query = query,
                    excludeVideoId = it.rejectedVideoId,
                    allowExcludedFallback = true,
                )
            }
            val flagged = flaggedRowsSnapshot.map {
                ResyncSearch(
                    trackId = it.trackId,
                    query = it.searchQuery,
                    excludeVideoId = it.currentYoutubeId,
                    allowExcludedFallback = false,
                )
            }
            val jobs = unmatched + flagged

            val semaphore = Semaphore(RESYNC_CONCURRENCY)
            val total = jobs.size
            val completed = AtomicInteger(0)

            jobs.map { request ->
                launch {
                    semaphore.acquire()
                    try {
                        val results = searchExecutor.search(request.query, maxResults = 5)
                        // For flagged tracks, skip the currently-associated
                        // (wrong) video — surfacing it as the candidate would
                        // just swap the track with itself.
                        var best = results.firstOrNull {
                            request.excludeVideoId == null || it.id != request.excludeVideoId
                        }

                        // #19/#143: search() is InnerTube-first and only falls
                        // back to yt-dlp when YT Music returns *zero* results.
                        // When YT Music returns results but none are usable —
                        // it's empty, or only the rejected/wrong video came back
                        // — broaden to a full-YouTube yt-dlp search, which
                        // surfaces tracks that exist on YouTube but not YouTube
                        // Music (and genuine alternatives to a wrong match).
                        if (best == null) {
                            val direct = searchExecutor.searchYtDlpDirect(request.query, maxResults = 5)
                            best = direct.firstOrNull {
                                request.excludeVideoId == null || it.id != request.excludeVideoId
                            }
                        }

                        // Last resort: surface the top result even if it's the
                        // excluded one, so an unmatched track still gets *a*
                        // candidate to preview rather than nothing.
                        if (best == null && request.allowExcludedFallback) {
                            best = results.firstOrNull()
                        }
                        if (best != null) {
                            _resyncCandidates.update { current ->
                                current + (request.trackId to ResyncCandidate(
                                    videoId = best.id,
                                    title = best.title,
                                    artist = best.uploader,
                                    thumbnailUrl = best.thumbnail,
                                    durationSeconds = best.duration,
                                ))
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Resync search failed for '${request.query}': ${e.message}")
                    } finally {
                        semaphore.release()
                        val done = completed.incrementAndGet()
                        _resyncProgress.value = "$done of $total"
                    }
                }
            }.joinAll()

            _isResyncing.value = false

            // #143: a resync that surfaces no candidates is otherwise
            // indistinguishable from a button that did nothing. Always report
            // the outcome so the user knows the pass actually ran.
            val found = _resyncCandidates.value.size
            _userMessages.tryEmit(
                when (found) {
                    0 -> "No new matches found."
                    1 -> "Found 1 replacement."
                    else -> "Found $found replacements."
                },
            )

            // Pre-extract stream URLs for instant audio previews
            preExtractStreamUrls(_resyncCandidates.value)
        }
    }

    // -- Pre-extract preview URLs in background --------------------------------

    /**
     * Pre-extracts stream URLs for resync candidates in the background.
     *
     * Speculative work is InnerTube-only so it can never occupy the serialized
     * yt-dlp lane needed by a foreground tap. Runs up to [PRE_EXTRACT_LIMIT]
     * extractions concurrently (limited by [PRE_EXTRACT_CONCURRENCY]).
     * Extracted URLs are cached and served instantly on a later tap.
     */
    private fun preExtractStreamUrls(candidates: Map<Long, ResyncCandidate>) {
        cancelPreExtraction()
        previewUrlCache.clear()

        val semaphore = Semaphore(PRE_EXTRACT_CONCURRENCY)
        candidates.values.take(PRE_EXTRACT_LIMIT).forEach { candidate ->
            val job = viewModelScope.launch {
                semaphore.acquire()
                try {
                    val url = previewUrlExtractor.extractStreamUrl(
                        candidate.videoId,
                        allowYtDlp = false,
                    )
                    previewUrlCache[candidate.videoId] = url
                    Log.d(TAG, "Pre-extracted preview URL for ${candidate.videoId}")
                } catch (e: CancellationException) {
                    // Expected when a new resync/tap drops the caller-side
                    // prefetch. Extractor-owned single-flight work may finish.
                    throw e
                } catch (e: NoFastStreamException) {
                    Log.d(TAG, "No fast preview stream for ${candidate.videoId}")
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
            // v0.9.15: Reject blocklisted identities. Approving a match
            // for a track the user already blocked would re-mark it
            // downloaded and resurrect the file.
            if (blocklistGuard.isBlockedByTrackId(trackId)) {
                _userMessages.tryEmit("Can't approve — this track is on your blocklist.")
                return@launch
            }

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
                // Drop any StreamUrl cached under the OLD youtubeId — otherwise
                // playback keeps serving the pre-approval (wrong/stale) URL.
                trackIdentityEvents.emitIdentityChanged(trackId)

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
                            trackId = trackId,
                        )
                        // Reject a too-small "successful" download (failed
                        // yt-dlp run leaving a tiny error body): delete it +
                        // leave the track not-downloaded (streamable).
                        if (localFileOps.acceptDownloadOrDelete(committed.filePath)) {
                            trackDao.markAsDownloaded(trackId, committed.filePath, committed.sizeBytes)
                        } else {
                            Log.w(TAG, "resync: discarded too-small download for ${candidate.title}: ${committed.filePath}")
                        }
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
     * track. [SwapCoordinator] downloads and validates the replacement before
     * atomically changing identity/file state, then removes the old file.
     */
    fun approveSwap(row: FlaggedTrackRow, candidate: ResyncCandidate) {
        viewModelScope.launch {
            // Defense in depth: candidates normally pass the resync exclusion
            // above, but stale UI state or an external caller must not self-swap.
            if (candidate.videoId == row.currentYoutubeId) {
                _userMessages.tryEmit("Choose a different replacement for this track.")
                return@launch
            }

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
        if (
            activePreviewVideoId == videoId &&
            previewPlayer.isRequestCurrent(activePreviewRequestId) &&
            (_previewLoading.value == videoId ||
                activePreviewAttemptId != null ||
                (previewState.value as? PreviewState.Playing)?.videoId == videoId)
        ) {
            return
        }

        previewLoadJob?.cancel()
        previewRetryJob?.cancel()
        previewPlayer.stop()
        val requestId = previewPlayer.claimRequest()

        val generation = ++previewRequestGeneration
        activePreviewRequestId = requestId
        activePreviewVideoId = videoId
        activePreviewAttemptId = null
        activePreviewRetried = false
        _previewLoading.value = videoId

        previewLoadJob = viewModelScope.launch {
            try {
                // Check cache first — if pre-extraction finished, this is instant
                val url = previewUrlCache[videoId] ?: run {
                    // Stop caller-side cache warming. Speculative extraction is
                    // fast-only, while this foreground request may use yt-dlp.
                    cancelPreExtraction()
                    previewUrlExtractor.extractStreamUrl(
                        videoId,
                        allowYtDlp = true,
                    ).also {
                        previewUrlCache[videoId] = it
                    }
                }
                if (!isActivePreview(generation, videoId)) return@launch
                val attemptId = previewPlayer.playUrlIfClaimed(requestId, videoId, url) { attemptId ->
                    if (isActivePreview(generation, videoId)) {
                        activePreviewAttemptId = attemptId
                    }
                }
                if (attemptId == null) {
                    abandonActivePreview(generation, videoId)
                }
            } catch (e: CancellationException) {
                throw e // never report our own cancellation as a preview failure
            } catch (e: Exception) {
                failActivePreview(
                    generation = generation,
                    videoId = videoId,
                    logMessage = "Preview failed for videoId=$videoId",
                    error = e,
                )
            } finally {
                clearPreviewLoading(generation, videoId)
            }
        }
    }

    /**
     * Handles source failures emitted after [PreviewPlayer.playUrl] returns.
     * Only the active preview may retry, and each user request gets one direct
     * yt-dlp fallback so a rejected retry URL cannot loop forever.
     */
    private fun onPreviewPlayerError(
        videoId: String,
        attemptId: Long,
        error: PlaybackException,
    ) {
        if (videoId != activePreviewVideoId || attemptId != activePreviewAttemptId) return

        val generation = previewRequestGeneration
        val requestId = activePreviewRequestId ?: return
        if (!previewPlayer.isRequestCurrent(requestId)) {
            abandonActivePreview(generation, videoId)
            return
        }
        if (!isIoError(error)) {
            failActivePreview(
                generation = generation,
                videoId = videoId,
                logMessage = "Preview playback failed for videoId=$videoId",
                error = error,
            )
            return
        }

        if (activePreviewRetried) {
            failActivePreview(
                generation = generation,
                videoId = videoId,
                logMessage = "yt-dlp retry playback failed for videoId=$videoId",
                error = error,
            )
            return
        }

        // Consume the retry before launching so duplicate error events cannot
        // start parallel yt-dlp work.
        activePreviewRetried = true
        activePreviewAttemptId = null
        _previewLoading.value = videoId
        previewRetryJob = viewModelScope.launch {
            try {
                val retryUrl = previewUrlExtractor.extractViaYtDlpForRetry(videoId)
                if (!isActivePreview(generation, videoId)) return@launch
                previewUrlCache[videoId] = retryUrl
                val retryAttemptId = previewPlayer.playUrlIfClaimed(
                    requestId,
                    videoId,
                    retryUrl,
                ) { attemptId ->
                    if (isActivePreview(generation, videoId)) {
                        activePreviewAttemptId = attemptId
                    }
                }
                if (retryAttemptId == null) {
                    abandonActivePreview(generation, videoId)
                    return@launch
                }
                Log.d(TAG, "yt-dlp preview retry succeeded for videoId=$videoId")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failActivePreview(
                    generation = generation,
                    videoId = videoId,
                    logMessage = "yt-dlp preview retry failed for videoId=$videoId",
                    error = e,
                )
            } finally {
                clearPreviewLoading(generation, videoId)
            }
        }
    }

    private fun isActivePreview(generation: Long, videoId: String): Boolean =
        previewRequestGeneration == generation && activePreviewVideoId == videoId

    private fun clearPreviewLoading(generation: Long, videoId: String) {
        if (isActivePreview(generation, videoId) && _previewLoading.value == videoId) {
            _previewLoading.value = null
        }
    }

    private fun abandonActivePreview(generation: Long, videoId: String) {
        if (!isActivePreview(generation, videoId)) return
        activePreviewRequestId = null
        activePreviewVideoId = null
        activePreviewAttemptId = null
        if (_previewLoading.value == videoId) _previewLoading.value = null
    }

    private fun failActivePreview(
        generation: Long,
        videoId: String,
        logMessage: String,
        error: Throwable,
    ) {
        if (!isActivePreview(generation, videoId)) return
        Log.e(TAG, logMessage, error)
        val ownedRequestId = activePreviewRequestId
        val ownedAttemptId = activePreviewAttemptId
        previewPlayer.cancelRequest(ownedRequestId)
        activePreviewRequestId = null
        activePreviewVideoId = null
        activePreviewAttemptId = null
        if (_previewLoading.value == videoId) _previewLoading.value = null
        previewPlayer.stopIfCurrent(ownedAttemptId)
        _userMessages.tryEmit(PREVIEW_FAILURE_MESSAGE)
    }

    private fun isIoError(error: PlaybackException): Boolean =
        error.errorCode in 2000..2999

    /**
     * Drops caller-side speculative prefetch jobs. Extractor-owned single-flight
     * work intentionally survives caller cancellation, but because speculative
     * work is InnerTube-only it cannot retain the foreground yt-dlp permit.
     */
    private fun cancelPreExtraction() {
        preExtractJobs.forEach { it.cancel() }
        preExtractJobs.clear()
    }

    /** Stops the current audio preview, if any. */
    fun stopPreview() {
        val ownedRequestId = activePreviewRequestId
        val ownedAttemptId = activePreviewAttemptId
        ++previewRequestGeneration
        previewLoadJob?.cancel()
        previewRetryJob?.cancel()
        previewLoadJob = null
        previewRetryJob = null
        previewPlayer.cancelRequest(ownedRequestId)
        activePreviewRequestId = null
        activePreviewVideoId = null
        activePreviewAttemptId = null
        activePreviewRetried = false
        previewPlayer.stopIfCurrent(ownedAttemptId)
        _previewLoading.value = null
    }

    // -- Lifecycle -----------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        stopPreview()
        resyncJob?.cancel()
        cancelPreExtraction()
        previewUrlCache.clear()
    }

    private data class ResyncSearch(
        val trackId: Long,
        val query: String,
        val excludeVideoId: String?,
        val allowExcludedFallback: Boolean,
    )
}
