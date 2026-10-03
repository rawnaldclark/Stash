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
import com.stash.data.download.files.SwapOutcome
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import com.stash.data.download.preview.NoFastStreamException
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.download.ytdlp.YtDlpSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
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
 * @property currentYoutubeId The currently-associated YT video (wrong one). Often
 *                           null: a lossless download never writes one.
 * @property currentFilePath  On-disk file to delete when the swap is approved.
 * @property searchQuery      "<artist> - <title>" — the plain resync search.
 * @property album            The track's album ("" = unknown). Resync looks in this
 *                           album's tracklist and names it in the search, so a song
 *                           recorded twice (an original and a re-recording) finds
 *                           the right recording; the swap files the file under it.
 * @property durationMs       The track's length, for matching within the album.
 */
data class FlaggedTrackRow(
    val trackId: Long,
    val title: String,
    val artist: String,
    val albumArtUrl: String?,
    val currentYoutubeId: String?,
    val currentFilePath: String?,
    val searchQuery: String,
    val album: String = "",
    val durationMs: Long = 0L,
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
 * @property swappingTrackIds Flagged tracks whose approved swap is still running.
 */
data class FailedMatchesUiState(
    val tracks: List<UnmatchedTrackView> = emptyList(),
    val flaggedTracks: List<FlaggedTrackRow> = emptyList(),
    val isLoading: Boolean = true,
    val previewLoading: String? = null,
    val resyncCandidates: Map<Long, ResyncCandidate> = emptyMap(),
    val isResyncing: Boolean = false,
    val resyncProgress: String = "",
    val swappingTrackIds: Set<Long> = emptySet(),
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
    private val albumMatchExecutor: AlbumMatchExecutor,
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

        private val BRACKETED = Regex("""[\(\[][^)\]]*[\)\]]""")
        private val NOT_A_WORD = Regex("""[^\p{L}\p{N}]+""")
        private val SPACES = Regex("""\s+""")

    }

    /** Observable preview playback state for the UI to highlight the active row. */
    val previewState: StateFlow<PreviewState> = previewPlayer.previewState

    // -- Internal state flows -----------------------------------------------

    private val _previewLoading = MutableStateFlow<String?>(null)
    private val _resyncCandidates = MutableStateFlow<Map<Long, ResyncCandidate>>(emptyMap())
    private val _isResyncing = MutableStateFlow(false)
    private val _resyncProgress = MutableStateFlow("")

    /**
     * Queued, not conflated: each snackbar holds the screen for a few seconds,
     * and a buffer of one dropped the older message whenever two more arrived
     * meanwhile, so a "Couldn't download…" could vanish behind another row's
     * "Swapped" (#531 review).
     */
    private val _userMessages = Channel<String>(Channel.UNLIMITED)

    /** One-shot user-facing messages (e.g. Snackbar text), shown one after another. */
    val userMessages: Flow<String> = _userMessages.receiveAsFlow()

    /** Active resync job reference so it can be cancelled on new resync or cleanup. */
    private var resyncJob: Job? = null

    /**
     * Candidates of swaps still running, by trackId: one swap per track at a
     * time, the row shows "Swapping…" meanwhile, and a failed swap keeps its
     * candidate for a one-tap retry (#531). Main-thread only.
     */
    private val _pendingSwaps = MutableStateFlow<Map<Long, ResyncCandidate>>(emptyMap())

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
        viewModelScope.launch {
            swapCoordinator.outcomes.collect { outcome -> onSwapOutcome(outcome) }
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
                    album = t.album,
                    durationMs = t.durationMs,
                )
            }
        }

    val uiState: StateFlow<FailedMatchesUiState> =
        combine(
            musicRepository.getUnmatchedTracks(),
            flaggedRows,
            _previewLoading,
            _resyncCandidates,
            // Swapping: what the coordinator runs (a swap outlives the screen
            // that started it) plus taps still on their way to it.
            combine(_isResyncing, _resyncProgress, _pendingSwaps, swapCoordinator.running) { r, p, pending, running ->
                Triple(r, p, running + pending.keys)
            },
        ) { tracks, flagged, loading, candidates, progress ->
            FailedMatchesUiState(
                tracks = tracks,
                flaggedTracks = flagged,
                isLoading = false,
                previewLoading = loading,
                resyncCandidates = candidates,
                isResyncing = progress.first,
                resyncProgress = progress.second,
                swappingTrackIds = progress.third,
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
                    queries = listOf(query),
                    excludeVideoId = it.rejectedVideoId,
                    allowExcludedFallback = true,
                )
            }
            // #531: a flagged track's wrong file is often the OTHER recording
            // of the same song (Evanescence's Lacrymosa exists on its original
            // album and on Synthesis). "artist - title" alone lands both on
            // the same top hit, so look in the track's own album first, then
            // search with the album named, then plainly.
            val flagged = flaggedRowsSnapshot.map { row ->
                val album = row.album.takeIf { it.isNotBlank() }
                ResyncSearch(
                    trackId = row.trackId,
                    queries = listOf(row.searchQuery),
                    albumQuery = album?.let { "${row.searchQuery} $it" },
                    // Its current video is the wrong one, even if the user
                    // picked it in an earlier swap: flagging again says the
                    // pick is wrong too (#531 review).
                    excludeVideoId = row.currentYoutubeId,
                    allowExcludedFallback = false,
                    albumTarget = album?.let {
                        AlbumTarget(
                            title = row.title,
                            artist = row.artist,
                            album = it,
                            durationMs = row.durationMs,
                        )
                    },
                    skipVideosOwnedByOtherTracks = true,
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
                        val best = findReplacement(request)
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Resync search failed for ${request.queries}: ${e.message}")
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
            _userMessages.trySend(
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

    /**
     * Finds the best replacement for one track, most specific source first:
     *  1. The album's own tracklist (flagged rows with a known album) — sync's
     *     first strategy too (DownloadManager.resolveUrl), and the one that
     *     tells two recordings of a song apart.
     *  2. Each query on YouTube Music: the album-named one (only results with
     *     the song's title, those on the track's album first), then
     *     "artist - title".
     *  3. #19/#143: the same queries on full YouTube. search() is
     *     InnerTube-first and only falls back to yt-dlp when YT Music returns
     *     *zero* results; when everything it returns is unusable (the
     *     rejected/wrong video, or videos other tracks own), a full-YouTube
     *     search surfaces tracks YouTube Music doesn't have and genuine
     *     alternatives to a wrong match.
     *  4. Unmatched rows only: the top result even if it's the excluded one,
     *     so the track still gets *a* candidate to preview rather than nothing.
     */
    private suspend fun findReplacement(request: ResyncSearch): YtDlpSearchResult? {
        request.albumTarget?.let { target ->
            val fromAlbum = albumMatchExecutor.findTrackInAlbum(
                targetTitle = target.title,
                targetArtist = target.artist,
                targetAlbum = target.album,
                targetDurationMs = target.durationMs,
            )
            if (fromAlbum != null) firstUsable(listOf(fromAlbum), request)?.let { return it }
        }

        request.albumQuery?.let { query ->
            val results = searchExecutor.search(query, maxResults = 5)
            firstUsable(thisSong(results, request), request)?.let { return it }
        }
        var topResults: List<YtDlpSearchResult> = emptyList()
        for ((index, query) in request.queries.withIndex()) {
            val results = searchExecutor.search(query, maxResults = 5)
            if (index == 0) topResults = results
            firstUsable(results, request)?.let { return it }
        }

        request.albumQuery?.let { query ->
            val direct = searchExecutor.searchYtDlpDirect(query, maxResults = 5)
            firstUsable(thisSong(direct, request), request)?.let { return it }
        }
        for (query in request.queries) {
            val direct = searchExecutor.searchYtDlpDirect(query, maxResults = 5)
            firstUsable(direct, request)?.let { return it }
        }

        return if (request.allowExcludedFallback) topResults.firstOrNull() else null
    }

    /**
     * Results of the album-named search that carry the song's title, those
     * on the track's album first. Naming the album can rank another song
     * from that album above the one wanted (#531 review). An edition suffix
     * on the track's title ("Song - Remastered 2011") is dropped first:
     * results usually carry the plain title.
     */
    private fun thisSong(results: List<YtDlpSearchResult>, request: ResyncSearch): List<YtDlpSearchResult> {
        val target = request.albumTarget ?: return results
        val wanted = normalized(target.title.substringBefore(" - "))
        val album = normalized(target.album)
        val titled = results.filter { wanted.isEmpty() || " ${normalized(it.title)} ".contains(" $wanted ") }
        val (onAlbum, elsewhere) = titled.partition { normalized(it.album.orEmpty()) == album }
        return onAlbum + elsewhere
    }

    /**
     * Lowercase words with bracketed parts dropped: "Lacrymosa (Synthesis)"
     * and "lacrymosa" compare equal, and a title can be found inside "Lacrymosa
     * - Synthesis Version".
     */
    private fun normalized(text: String): String =
        text.lowercase()
            .replace(BRACKETED, " ")
            .replace(NOT_A_WORD, " ")
            .trim()
            .replace(SPACES, " ")

    /**
     * The first result this track may switch to. Never its own current video
     * (surfacing it would swap the track with itself). For a flagged row, also
     * never a video another track owns: tracks.youtube_id is UNIQUE, so
     * approving one could only fail with "Can't swap — already linked" (#531).
     * The row's own youtube_id is often null, so excluding "its own" video
     * alone skipped nothing.
     */
    private suspend fun firstUsable(
        results: List<YtDlpSearchResult>,
        request: ResyncSearch,
    ): YtDlpSearchResult? {
        val open = results.filter { request.excludeVideoId == null || it.id != request.excludeVideoId }
        if (!request.skipVideosOwnedByOtherTracks || open.isEmpty()) return open.firstOrNull()
        val ownedElsewhere = musicRepository.findByYoutubeIds(open.map { it.id }.distinct())
            .filter { it.id != request.trackId }
            .mapNotNullTo(HashSet()) { it.youtubeId }
        if (ownedElsewhere.isNotEmpty()) {
            Log.d(
                TAG,
                "resync: skipping ${ownedElsewhere.size} video(s) already linked to other " +
                    "tracks for trackId=${request.trackId}",
            )
        }
        return open.firstOrNull { it.id !in ownedElsewhere }
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
                _userMessages.trySend("Can't approve — this track is on your blocklist.")
                return@launch
            }

            val existing = trackDao.findByYoutubeId(candidate.videoId)
            if (existing != null && existing.id != trackId) {
                _userMessages.trySend(
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
                _userMessages.trySend("Couldn't approve this match. Please try again.")
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
     * How it ended comes back through [onSwapOutcome].
     */
    fun approveSwap(row: FlaggedTrackRow, candidate: ResyncCandidate) {
        // One swap per track at a time; the coordinator enforces it, this
        // skips the work for a quick double tap or a row already swapping.
        if (row.trackId in _pendingSwaps.value || row.trackId in swapCoordinator.running.value) return
        _pendingSwaps.update { it + (row.trackId to candidate) }
        viewModelScope.launch {
            var handedOff = false
            try {
                // Defense in depth: candidates normally pass the resync exclusion
                // above, but stale UI state or an external caller must not self-swap.
                if (candidate.videoId == row.currentYoutubeId) {
                    _userMessages.trySend("Choose a different replacement for this track.")
                    return@launch
                }

                // Guard: another track already owns this videoId — swapping would
                // violate the UNIQUE(youtube_id) constraint and blow up silently.
                val existing = trackDao.findByYoutubeId(candidate.videoId)
                if (existing != null && existing.id != row.trackId) {
                    _userMessages.trySend(
                        alreadyLinkedMessage(candidate.title, existing.artist, existing.title, existing.album),
                    )
                    return@launch
                }

                // The row stays flagged, and shows "Swapping…", until the swap
                // is done: the coordinator clears the flag in the same write
                // that records the new file. Clearing it here made the row
                // vanish and pop back on failure, and an app that died
                // mid-download lost the track with its wrong audio.

                // Hand the download + commit + DB update off to SwapCoordinator's
                // application-scope so it survives the user leaving this screen.
                // Pre-Phase-5b this was an inline `launch {}` on viewModelScope,
                // which got cancelled the instant the user navigated away — they
                // ended up with the DB pointing at a deleted file while the new
                // audio never actually landed.
                handedOff = swapCoordinator.swap(trackId = row.trackId, newVideoId = candidate.videoId)
                if (!handedOff) Log.i(TAG, "a swap of trackId=${row.trackId} is already running")
            } finally {
                // Handed off, the entry lives until the swap reports back.
                if (!handedOff) _pendingSwaps.update { it - row.trackId }
            }
        }
    }

    /**
     * Tells the user how a swap ended (#531: a failed swap used to put the row
     * back with no word, so approving looked like it did nothing). A failure
     * that can be retried keeps the candidate; a video that can't be used
     * drops it. Swaps this screen didn't start (it was recreated mid-swap)
     * still get their message.
     */
    private fun onSwapOutcome(outcome: SwapOutcome) {
        // Only this swap's own candidate comes back. The coordinator runs one
        // swap per track, so any pending entry for the track is done either way.
        val candidate = _pendingSwaps.value[outcome.trackId]?.takeIf { it.videoId == outcome.newVideoId }
        _pendingSwaps.update { it - outcome.trackId }
        val song = outcome.title.ifBlank { null }?.let { " for '$it'" } ?: ""
        val message = when (outcome) {
            is SwapOutcome.Swapped -> {
                _resyncCandidates.update { it - outcome.trackId }
                "Swapped — '${outcome.title}' now plays the version you picked."
            }
            is SwapOutcome.DownloadFailed -> {
                keepCandidate(outcome.trackId, candidate)
                "Couldn't download the replacement$song — try again."
            }
            is SwapOutcome.SaveFailed -> {
                keepCandidate(outcome.trackId, candidate)
                "Couldn't save the replacement$song — check your storage, then try again."
            }
            is SwapOutcome.AlreadyLinked -> {
                _resyncCandidates.update { it - outcome.trackId }
                alreadyLinkedMessage(
                    candidateTitle = candidate?.title ?: outcome.title,
                    ownerArtist = outcome.ownerArtist,
                    ownerTitle = outcome.ownerTitle,
                    ownerAlbum = outcome.ownerAlbum,
                )
            }
            is SwapOutcome.Blocked -> {
                _resyncCandidates.update { it - outcome.trackId }
                "Can't swap — this song is on your blocklist."
            }
        }
        _userMessages.trySend(message)
    }

    /**
     * Keeps a failed swap's candidate on its row for a one-tap retry, unless
     * a resync that ran meanwhile found another one.
     */
    private fun keepCandidate(trackId: Long, candidate: ResyncCandidate?) {
        if (candidate == null) return
        _resyncCandidates.update { current ->
            if (trackId in current) current else current + (trackId to candidate)
        }
    }

    /**
     * "Can't swap — 'X' is already linked to Artist — Title (Album)." The
     * album shows the other track is a different recording rather than the
     * track blocking itself (#531: "'Lacrymosa' is already linked to
     * Evanescence — Lacrymosa").
     */
    private fun alreadyLinkedMessage(
        candidateTitle: String,
        ownerArtist: String,
        ownerTitle: String,
        ownerAlbum: String,
    ): String {
        val owner = if (ownerAlbum.isBlank()) {
            "$ownerArtist — $ownerTitle"
        } else {
            "$ownerArtist — $ownerTitle ($ownerAlbum)"
        }
        return "Can't swap — '$candidateTitle' is already linked to $owner."
    }

    /**
     * Clear a flag without permanently dismissing the track. Used when the
     * user inspects a flagged track in Failed Matches and decides the
     * original match was actually fine. Unlike [dismissTrack], this does
     * NOT set match_dismissed, so future sync attempts still behave
     * normally for this row.
     */
    fun unflagTrack(trackId: Long) {
        // Not while it swaps: the swap clears the flag itself when it is done,
        // and unflagging mid-swap made a swap cut short by the app dying look
        // finished to the startup repair (#531 review).
        if (trackId in swapCoordinator.running.value || trackId in _pendingSwaps.value) return
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
        _userMessages.trySend(PREVIEW_FAILURE_MESSAGE)
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
        /** Plain queries. The first one's results back the unmatched last resort. */
        val queries: List<String>,
        /** Flagged rows with a known album: "artist - title album", searched first and title-checked. */
        val albumQuery: String? = null,
        val excludeVideoId: String?,
        val allowExcludedFallback: Boolean,
        /** Flagged rows with a known album: look in that album's tracklist first. */
        val albumTarget: AlbumTarget? = null,
        /** Flagged rows: skip videos other tracks own; they can't be swapped in. */
        val skipVideosOwnedByOtherTracks: Boolean = false,
    )

    private data class AlbumTarget(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
    )
}
