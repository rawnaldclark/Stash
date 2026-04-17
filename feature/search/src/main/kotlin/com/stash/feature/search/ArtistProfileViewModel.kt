package com.stash.feature.search

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import com.stash.core.common.perf.PerfLog
import com.stash.core.data.cache.ArtistCache
import com.stash.core.data.cache.CachedProfile
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
import com.stash.data.ytmusic.model.ArtistProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Artist Profile screen.
 *
 * Responsibilities (spec §8.3):
 *  - Hydrate the [ArtistProfileUiState.hero] from the three nav args
 *    (`artistId`, `name`, `avatarUrl`) on construction so the first frame
 *    after navigation paints a name + avatar — the < 50 ms hero target.
 *  - Subscribe to [ArtistCache.get] for the full profile and fold each
 *    [CachedProfile] emission into the state, flipping `status` between
 *    [ArtistProfileStatus.Fresh] and [ArtistProfileStatus.Stale].
 *  - Kick [PreviewPrefetcher.prefetch] exactly once with the Popular
 *    `videoId`s on the first emission that has a non-empty Popular list,
 *    so a tap on a Popular row hits a warm preview-URL cache.
 *  - On a [CachedProfile.Stale] with `refreshFailed = true`, emit a
 *    one-shot snackbar message via [userMessages] WITHOUT flipping status
 *    to [ArtistProfileStatus.Error] — the cached data keeps rendering.
 *  - Own the per-row preview + download state that [PopularTracksSection]
 *    renders. [previewTrack] / [stopPreview] / [downloadTrack] /
 *    [onPreviewError] are duplicated verbatim from [SearchViewModel] so
 *    Task 12 ships without a risky refactor; the shared DI graph means
 *    both VMs see the same [PreviewPlayer] + [PreviewUrlCache] singletons.
 */
@HiltViewModel
class ArtistProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val artistCache: ArtistCache,
    private val prefetcher: PreviewPrefetcher,
    private val previewPlayer: PreviewPlayer,
    private val previewUrlExtractor: PreviewUrlExtractor,
    private val previewUrlCache: PreviewUrlCache,
    private val downloadExecutor: DownloadExecutor,
    private val trackDao: TrackDao,
    private val fileOrganizer: FileOrganizer,
    private val qualityPrefs: QualityPreferencesManager,
    private val musicRepository: MusicRepository,
) : ViewModel() {

    private val artistId: String = requireNotNull(savedStateHandle["artistId"]) {
        "SearchArtistRoute requires a non-null artistId nav arg"
    }
    private val initialName: String = savedStateHandle["name"] ?: ""
    private val initialAvatar: String? = savedStateHandle["avatarUrl"]

    private val _uiState = MutableStateFlow(
        ArtistProfileUiState(
            hero = HeroState(
                name = initialName,
                avatarUrl = initialAvatar,
                subscribersText = null,
            ),
            status = ArtistProfileStatus.Loading,
        ),
    )
    val uiState: StateFlow<ArtistProfileUiState> = _uiState.asStateFlow()

    /** Observable preview playback state for the UI to highlight the active row. */
    val previewState: StateFlow<PreviewState> = previewPlayer.previewState

    /**
     * One-shot user-facing messages (snackbars). Uses a [MutableSharedFlow]
     * with a small buffer so rapid emissions during startup aren't dropped
     * when the UI hasn't subscribed yet.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    /**
     * Guards against kicking the prefetcher more than once per screen
     * lifetime — a `Stale -> Fresh` transition should NOT fire prefetch
     * a second time (the first emission already warmed the cache).
     */
    private var prefetchKicked = false

    /**
     * Job running the current [observeCache] subscription. Stored so
     * [retry] can cancel the old subscription before relaunching.
     */
    private var cacheJob: Job? = null

    /**
     * Tracks the most-recent `playUrl(videoId)` timestamp so [onPreviewError]
     * can decide whether the error is close enough to playback-start to
     * warrant an automatic yt-dlp retry.
     */
    private var lastPreviewVideoId: String? = null
    private var lastPreviewStartedAt: Long = 0L

    init {
        // Nav-args hero already painted by the MutableStateFlow seed above —
        // this bookend marks the "first-frame" moment so latency verification
        // can diff skeleton → paint against spec §4.1 <50ms hero target.
        PerfLog.d { "ArtistProfile hero first-frame nav-args (name=$initialName)" }
        cacheJob = viewModelScope.launch { observeCache() }

        // Player-error pipeline — route ExoPlayer errors into onPreviewError
        // so the UI layer doesn't need to know about the retry policy.
        viewModelScope.launch {
            previewPlayer.playerErrors.collect { event ->
                onPreviewError(event.videoId, event.error)
            }
        }
    }

    /**
     * Subscribes to [ArtistCache.get] for [artistId] and folds each emission
     * into the UI state. Extracted into a suspend function so [retry] can
     * re-run the exact same pipeline after a cold-miss failure without
     * duplicating the body.
     */
    private suspend fun observeCache() {
        val t0 = SystemClock.elapsedRealtime()
        artistCache.get(artistId)
            .catch { t ->
                // Cold miss with no cached fallback — the flow throws and
                // would otherwise crash viewModelScope. Flip to Error and
                // let the screen render a message instead. `flow.catch`
                // intentionally does not swallow CancellationException.
                Log.e(TAG, "cache failure for $artistId", t)
                _uiState.value = _uiState.value.copy(
                    status = ArtistProfileStatus.Error(
                        t.message ?: "Something went wrong.",
                    ),
                )
                _userMessages.emit("Couldn't load artist — tap Retry.")
            }
            .collect { cached ->
                when (cached) {
                    is CachedProfile.Fresh -> apply(
                        profile = cached.profile,
                        status = ArtistProfileStatus.Fresh,
                        t0 = t0,
                    )
                    is CachedProfile.Stale -> {
                        apply(
                            profile = cached.profile,
                            status = ArtistProfileStatus.Stale,
                            t0 = t0,
                        )
                        if (cached.refreshFailed) {
                            _userMessages.emit("Couldn't refresh — showing cached.")
                        }
                    }
                }
            }
    }

    /**
     * Re-runs the cache subscription after a cold-miss failure. The screen
     * calls this from its error-card "Retry" button. Flips status back to
     * [ArtistProfileStatus.Loading] before relaunching so the error card
     * disappears while the new subscription is in flight.
     */
    fun retry() {
        cacheJob?.cancel()
        _uiState.update { it.copy(status = ArtistProfileStatus.Loading) }
        cacheJob = viewModelScope.launch { observeCache() }
    }

    /**
     * Fold a freshly-arrived profile into the UI state and kick the preview
     * prefetcher on the first non-empty Popular list we see.
     */
    private fun apply(
        profile: ArtistProfile,
        status: ArtistProfileStatus,
        t0: Long,
    ) {
        _uiState.value = _uiState.value.copy(
            hero = HeroState(
                name = profile.name,
                avatarUrl = profile.avatarUrl,
                subscribersText = profile.subscribersText,
            ),
            popular = profile.popular,
            albums = profile.albums,
            singles = profile.singles,
            related = profile.related,
            status = status,
        )
        if (!prefetchKicked && profile.popular.isNotEmpty()) {
            prefetchKicked = true
            prefetcher.prefetch(profile.popular.map { it.videoId })
            // Cross-reference Popular against the local DB so already-downloaded
            // rows paint with the green checkmark — mirrors SearchViewModel's
            // refreshDownloadedIds path for the sectioned results list.
            viewModelScope.launch { refreshDownloadedIds(profile.popular.map { it.videoId }) }
        }
        PerfLog.d {
            "ArtistProfile paint after ${SystemClock.elapsedRealtime() - t0}ms (status=$status)"
        }
    }

    private suspend fun refreshDownloadedIds(videoIds: List<String>) {
        if (videoIds.isEmpty()) return
        val downloaded = videoIds.filter { id ->
            trackDao.findByYoutubeId(id)?.isDownloaded == true
        }.toSet()
        if (downloaded.isNotEmpty()) {
            _uiState.update { it.copy(downloadedIds = it.downloadedIds + downloaded) }
        }
    }

    // ------------------------------------------------------------------
    // Download path (duplicated verbatim from SearchViewModel — Task 12)
    // ------------------------------------------------------------------

    /**
     * Initiates a background download of the given search result.
     *
     * Runs in its own coroutine so the user can continue browsing or start
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
                val tempFilename = "artist_${item.videoId}"

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
                if (e is CancellationException) throw e
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
    // Audio preview (duplicated verbatim from SearchViewModel — Task 12)
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

    companion object {
        /**
         * Error log tag for cache-failure paths. Latency bookends route
         * through [PerfLog] (tag `Perf`) and do NOT use [TAG].
         */
        private const val TAG = "ArtistProfileVM"

        /**
         * Retry window after `playUrl` — an ExoPlayer error inside this
         * window is treated as an InnerTube URL rejection and triggers
         * the yt-dlp fallback. Errors outside the window are left alone.
         */
        private const val RETRY_WINDOW_MS = 3_000L
    }
}
