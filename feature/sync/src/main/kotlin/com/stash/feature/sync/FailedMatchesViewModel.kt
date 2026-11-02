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
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.YouTubeVideoId
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
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.SearchResultSection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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
 * - **Find it yourself** (#19): the listener searches for a song, and the result
 *   they pick goes through the same preview and approve/swap as a resync candidate.
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
    private val ytMusicApi: YTMusicApiClient,
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

        /**
         * How many results "find it yourself" asks all of YouTube for, listed
         * after YouTube Music's own. yt-dlp answers a whole results page in one
         * request either way; this keeps the list short enough to scan.
         */
        private const val MANUAL_YOUTUBE_RESULTS = 10

        /**
         * How long [openFinderFor] waits for the song to reach the lists. Now Playing saves the
         * flag before it opens this screen, so Room's first answer already has it; this only
         * bounds a song that never arrives, which then opens nothing.
         */
        internal const val FOCUS_WAIT_MS = 5_000L

        /** Said when [openFinderFor]'s song never reached the lists. */
        internal const val FOCUS_NOT_FOUND_MESSAGE = "Couldn't find that song in Failed matches"

        private val BRACKETED = Regex("""[\(\[][^)\]]*[\)\]]""")
        private val NOT_A_WORD = Regex("""[^\p{L}\p{N}]+""")
        private val SPACES = Regex("""\s+""")

        /**
         * Approved downloads at once, across every instance of this screen. Each
         * is a yt-dlp (Python) process plus ffmpeg: Approve All on a long list
         * used to start them all together, on devices already short of memory.
         */
        private const val APPROVED_DOWNLOAD_CONCURRENCY = 2

        private val approvedDownloadSlots = Semaphore(APPROVED_DOWNLOAD_CONCURRENCY)

        /**
         * Where approved downloads run: the process, not this ViewModel. The
         * approval hides the row at once, so the natural next step is Back —
         * which cleared the ViewModel and cancelled every download still
         * running or waiting, leaving those tracks never downloaded.
         */
        private val approvedDownloadProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * How the approved downloads are getting on, process-wide like the
         * downloads themselves: leave the screen mid-run and come back, and the
         * banner still counts the same run.
         */
        private val approvedDownloadsState = MutableStateFlow(ApprovedDownloads())
    }

    /** Scope for approved downloads; a `var` only so tests can run them on the test scheduler. */
    internal var approvedDownloadScope: CoroutineScope = approvedDownloadProcessScope

    /**
     * Drives the "Downloading 12 approved songs" banner. Approving hides the
     * row at once, so without it the list silently emptied (often to "All
     * caught up!") while the songs were still downloading in the background.
     */
    internal val approvedDownloads: StateFlow<ApprovedDownloads> = approvedDownloadsState.asStateFlow()

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

    private val _manualSearch = MutableStateFlow<ManualSearch?>(null)

    /**
     * "Find it yourself" (#19): the search open under one song's row, or null.
     * Its own flow, outside [uiState]: it changes on every answer while the
     * rest of the screen stays put. The box's text lives in the screen (typing
     * never waits on a flow); this holds the words last searched for.
     */
    val manualSearch: StateFlow<ManualSearch?> = _manualSearch.asStateFlow()

    private var manualSearchJob: Job? = null

    /** Advanced by every search and close, so a late answer to an older search is dropped. */
    private var manualSearchGeneration = 0L

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
     * download runs in the background, beyond this screen's life and at most
     * [APPROVED_DOWNLOAD_CONCURRENCY] at a time; one that fails puts the row back.
     *
     * @param trackId      Primary key of the track in the tracks table.
     * @param queueEntryId Row ID of the download_queue entry to mark completed.
     * @param candidate    The [ResyncCandidate] the user approved.
     * @param userPicked   The listener found this video themselves ("find it
     *   yourself"): recorded as their pick, as an approved swap is, so the FLAC
     *   sweep and a re-download keep it instead of redoing the artist/title
     *   lookup (#531). An automatic match approved here isn't recorded so; a
     *   swap records both.
     */
    fun approveMatch(trackId: Long, queueEntryId: Long, candidate: ResyncCandidate, userPicked: Boolean = false) {
        viewModelScope.launch {
            // The candidate's id names the temp file and the yt-dlp URL below and
            // is written to the track. It came from a search answer, so one
            // without the YouTube video-id shape goes no further.
            val url = YouTubeVideoId.watchUrl(candidate.videoId) ?: run {
                Log.w(TAG, "approve: candidate for trackId=$trackId isn't a YouTube video id")
                _userMessages.trySend("Can't approve — this match isn't a valid YouTube video.")
                return@launch
            }

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
                if (userPicked) trackDao.markMatchPicked(trackId, System.currentTimeMillis())
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

            // Background download, outliving this screen (see approvedDownloadScope)
            // and taking its turn behind the other approvals. Counted from here
            // until it finishes, however it finishes (see approvedDownloads).
            approvedDownloadsState.update { it.started() }
            approvedDownloadScope.launch {
                var downloaded = false
                try {
                    try {
                        approvedDownloadSlots.withPermit {
                            downloaded = downloadApproved(trackId, url, candidate)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Background download error for ${candidate.title}", e)
                    }
                    if (!downloaded) {
                        // The row was marked COMPLETED up front to hide it. Left so,
                        // nothing would ever download this track (the downloader skips
                        // COMPLETED rows); put it back in Failed Matches instead.
                        try {
                            downloadQueueDao.updateStatus(
                                id = queueEntryId,
                                status = DownloadStatus.FAILED,
                                errorMessage = "Approved match didn't download",
                                failureType = DownloadFailureType.NO_MATCH,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Couldn't put ${candidate.title} back in Failed Matches", e)
                        }
                        _userMessages.trySend("Couldn't download '${candidate.title}'. It's back in the list to try again.")
                    }
                } finally {
                    val after = approvedDownloadsState.updateAndGet { it.finished(downloaded) }
                    approvedDownloadsFinishedMessage(after)?.let { _userMessages.trySend(it) }
                }
            }
        }
    }

    /**
     * Downloads an approved [candidate] for [trackId] and records the file.
     *
     * @return true once the track is marked downloaded; false when yt-dlp
     *   failed or left a too-small file (which is deleted, leaving the track
     *   streamable).
     */
    private suspend fun downloadApproved(trackId: Long, url: String, candidate: ResyncCandidate): Boolean {
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

        if (result !is DownloadResult.Success) {
            Log.w(TAG, "Background download failed for ${candidate.title}: $result")
            return false
        }
        // Reject a too-small "successful" download (failed
        // yt-dlp run leaving a tiny error body): delete it +
        // leave the track not-downloaded (streamable). Checked while
        // it's a temp file: committed first, it could replace the
        // track's own file at the same name, and rejecting it then
        // deleted the file the row records.
        if (!localFileOps.acceptDownloadOrDelete(result.file.absolutePath)) {
            Log.w(TAG, "resync: discarded too-small download for ${candidate.title}: ${result.file.name}")
            return false
        }
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
        trackDao.markAsDownloaded(trackId, committed.filePath, committed.sizeBytes)
        return true
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

    // -- Find it yourself (#19) ---------------------------------------------

    /**
     * Opens "find it yourself" under [trackId]'s row, its box filled with the
     * song's artist and title and searched straight away, or closes it when it
     * is already open there. One is open at a time: opening another song's
     * closes this one.
     */
    fun toggleManualSearch(trackId: Long, artist: String, title: String) {
        val wasOpenHere = _manualSearch.value?.trackId == trackId
        closeManualSearch()
        if (!wasOpenHere) startManualSearch(trackId, manualSearchQuery(artist, title))
    }

    /**
     * Opens "find it yourself" under [trackId]'s row, searched for its artist and title: the
     * jump from Now Playing's "Find a better match" (the screen's `focusTrackId`). The song was
     * flagged a moment before this screen opened, so this waits for it to reach one of the lists
     * (the flagged one, usually), for at most [FOCUS_WAIT_MS]; a song that never arrives opens
     * nothing and says so ([FOCUS_NOT_FOUND_MESSAGE]). Unlike [toggleManualSearch], it never closes or takes over an open finder: one
     * open by then is the listener's own (this screen opened with none).
     */
    fun openFinderFor(trackId: Long) {
        focusJob?.cancel()
        focusJob = viewModelScope.launch {
            val state = withTimeoutOrNull(FOCUS_WAIT_MS) {
                uiState.first { s ->
                    !s.isLoading &&
                        (s.flaggedTracks.any { it.trackId == trackId } || s.tracks.any { it.trackId == trackId })
                }
            } ?: run {
                Log.w(TAG, "find it yourself: trackId=$trackId never reached Failed Matches; nothing opened")
                // Said, not just logged: the listener came here for this song.
                _userMessages.trySend(FOCUS_NOT_FOUND_MESSAGE)
                return@launch
            }
            if (_manualSearch.value != null) return@launch
            // A song in both lists at once is the flagged one, as in pickManualMatch.
            val flagged = state.flaggedTracks.firstOrNull { it.trackId == trackId }
            val (artist, title) = if (flagged != null) {
                flagged.artist to flagged.title
            } else {
                state.tracks.first { it.trackId == trackId }.let { it.artist to it.title }
            }
            startManualSearch(trackId, manualSearchQuery(artist, title))
        }
    }

    /** The wait in [openFinderFor]; a newer jump replaces it. */
    private var focusJob: Job? = null

    /** Searches for [query] in the open finder (the keyboard's Search key). Again for the same words retries. */
    fun searchManually(query: String) {
        val trackId = _manualSearch.value?.trackId ?: return
        startManualSearch(trackId, query)
    }

    /**
     * Picks [candidate] for the song the finder is open under, through exactly
     * what the row's own check mark does for an automatic match: [approveSwap]
     * for a flagged song (it has a file to replace), [approveMatch] for an
     * unmatched one. Then the finder closes.
     */
    fun pickManualMatch(candidate: ResyncCandidate) {
        val trackId = _manualSearch.value?.trackId ?: return
        val state = uiState.value
        // A song in both lists at once is the flagged one, as in resync.
        val flagged = state.flaggedTracks.firstOrNull { it.trackId == trackId }
        val unmatched = state.tracks.firstOrNull { it.trackId == trackId }
        when {
            flagged != null -> approveSwap(flagged, candidate)
            unmatched != null -> approveMatch(unmatched.trackId, unmatched.id, candidate, userPicked = true)
            else -> Log.w(TAG, "find it yourself: trackId=$trackId left the list before the pick")
        }
        closeManualSearch()
    }

    /** Closes the finder, stopping its search and any preview of one of its results. */
    fun closeManualSearch() {
        val open = _manualSearch.value ?: return
        manualSearchJob?.cancel()
        manualSearchJob = null
        ++manualSearchGeneration
        _manualSearch.value = null
        stopPreviewIfAmong(open.results)
    }

    /**
     * Stops a preview of one of [results], whose rows (stop buttons included)
     * are leaving the screen: the finder closing, or a new search replacing
     * them. Left alone, it would play to the end with nothing to stop it. Not
     * when the video is also a song's automatic match: that row's own stop
     * button stays.
     */
    private fun stopPreviewIfAmong(results: List<ManualMatch>) {
        val previewing = activePreviewVideoId
            ?: (previewState.value as? PreviewState.Playing)?.videoId
            ?: return
        if (_resyncCandidates.value.values.any { it.videoId == previewing }) return
        if (results.any { it.candidate.videoId == previewing }) stopPreview()
    }

    /**
     * Searches YouTube Music the way the Search tab does and all of YouTube
     * (yt-dlp) at once. YouTube Music answers in a fraction of a second and its
     * rows show then; a yt-dlp run takes seconds, so its rows are added under
     * them when they come. A flagged song is never offered its current video:
     * that is the one the listener said is wrong. Nor is any song offered a
     * video another song already has (see [ownedByOtherTracks]).
     */
    private fun startManualSearch(trackId: Long, query: String) {
        manualSearchJob?.cancel()
        // The rows on screen give way to this search's (none, for an empty box).
        _manualSearch.value?.let { stopPreviewIfAmong(it.results) }
        val generation = ++manualSearchGeneration
        val words = query.trim()
        if (words.isEmpty()) {
            _manualSearch.value = ManualSearch(trackId = trackId, query = query)
            return
        }
        val exclude = uiState.value.flaggedTracks.firstOrNull { it.trackId == trackId }?.currentYoutubeId
        _manualSearch.value = ManualSearch(
            trackId = trackId,
            query = query,
            searchingMusic = true,
            searchingYouTube = true,
        )
        manualSearchJob = viewModelScope.launch {
            val youTube = async { searchAllOfYouTube(words) }
            val music = searchYouTubeMusic(words)
            val musicRows = manualSearchMatches(music.orEmpty(), emptyList(), exclude)
            val musicOwned = ownedByOtherTracks(trackId, musicRows)
            updateManualSearch(generation) {
                it.copy(
                    results = musicRows.filterNot { row -> row.candidate.videoId in musicOwned },
                    searchingMusic = false,
                )
            }
            val videos = youTube.await()
            val allRows = manualSearchMatches(music.orEmpty(), videos, exclude)
            // YouTube Music's rows were checked above; the same answer for them
            // keeps the rows on screen where they are.
            val owned = musicOwned + ownedByOtherTracks(trackId, allRows.drop(musicRows.size))
            updateManualSearch(generation) {
                it.copy(
                    results = allRows.filterNot { row -> row.candidate.videoId in owned },
                    searchingYouTube = false,
                    failed = music == null && videos.isEmpty(),
                )
            }
        }
    }

    /**
     * The videos in [rows] another song already has. tracks.youtube_id is
     * UNIQUE, so picking one could only fail with "already linked" once the
     * finder had closed, its words gone (#531; resync leaves them out too).
     * A failed lookup leaves nothing out: approve still checks.
     */
    private suspend fun ownedByOtherTracks(trackId: Long, rows: List<ManualMatch>): Set<String> {
        if (rows.isEmpty()) return emptySet()
        return try {
            musicRepository.findByYoutubeIds(rows.map { it.candidate.videoId })
                .filter { it.id != trackId }
                .mapNotNullTo(HashSet()) { it.youtubeId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "find it yourself: couldn't check which videos other songs have: ${e.message}")
            emptySet()
        }
    }

    /** The Search tab's own search; null when YouTube Music didn't answer. */
    private suspend fun searchYouTubeMusic(query: String): List<SearchResultSection>? = try {
        ytMusicApi.searchAll(query).sections
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "find it yourself: YouTube Music didn't answer for '$query': ${e.message}")
        null
    }

    /**
     * All of YouTube, for what YouTube Music doesn't carry. Never throws but
     * for cancellation: a failure inside this async would cancel the YouTube
     * Music half with it.
     */
    private suspend fun searchAllOfYouTube(query: String): List<YtDlpSearchResult> = try {
        searchExecutor.searchYtDlpDirect(query, maxResults = MANUAL_YOUTUBE_RESULTS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "find it yourself: YouTube search failed for '$query': ${e.message}")
        emptyList()
    }

    /** Applies [change] unless a newer search or a close came since [generation]'s search started. */
    private inline fun updateManualSearch(generation: Long, change: (ManualSearch) -> ManualSearch) {
        if (generation != manualSearchGeneration) return
        _manualSearch.update { open -> open?.let(change) }
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
