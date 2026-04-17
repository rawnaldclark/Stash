package com.stash.core.media.actions

import android.os.SystemClock
import android.util.Log
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
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Shared preview + download actions for any screen that renders track rows.
 *
 * Consolidates the preview/download wiring that was previously duplicated across
 * [com.stash.feature.search.SearchViewModel] and
 * [com.stash.feature.search.ArtistProfileViewModel]. A new delegate instance is
 * created per VM ([ViewModelScoped]) so two screens open at once don't share
 * `downloadingIds` / `previewLoading` state; the underlying 8 singletons (player,
 * extractor, executor, etc.) are shared.
 *
 * **Lifecycle contract:** callers must invoke [bindToScope] exactly once in their
 * VM's `init` block before calling any other method. A second [bindToScope] call
 * throws [IllegalStateException]. Flows return their initial empty values until
 * bound; calling any action method before binding throws.
 */
@ViewModelScoped
class TrackActionsDelegate @Inject constructor(
    private val previewPlayer: PreviewPlayer,
    private val previewUrlExtractor: PreviewUrlExtractor,
    private val previewUrlCache: PreviewUrlCache,
    private val downloadExecutor: DownloadExecutor,
    private val trackDao: TrackDao,
    private val fileOrganizer: FileOrganizer,
    private val qualityPrefs: QualityPreferencesManager,
    private val musicRepository: MusicRepository,
) {
    /** Mirrors [PreviewPlayer.previewState] so consumers don't need a second dep. */
    val previewState: StateFlow<PreviewState> = previewPlayer.previewState

    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-shot user-facing messages (snackbars). Buffered so emissions during
     *  init aren't dropped before the UI subscribes. */
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    /** VideoIds currently being downloaded — used by the UI to render spinners. */
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    private val _downloadedIds = MutableStateFlow<Set<String>>(emptySet())
    /** VideoIds already in the local library — used by the UI to show the
     *  green checkmark in place of the download button. */
    val downloadedIds: StateFlow<Set<String>> = _downloadedIds.asStateFlow()

    private val _previewLoadingId = MutableStateFlow<String?>(null)
    /** VideoId whose preview URL is currently being resolved (extractor in
     *  flight). UI shows a row-level spinner for this id. */
    val previewLoadingId: StateFlow<String?> = _previewLoadingId.asStateFlow()

    private var boundScope: CoroutineScope? = null

    /**
     * VideoId passed to the most recent [PreviewPlayer.playUrl] call. Consulted
     * by [onPreviewError] so a late error from a cancelled preview doesn't
     * trigger a spurious yt-dlp retry.
     */
    private var lastPreviewVideoId: String? = null

    /**
     * Wall-clock timestamp of the most recent [PreviewPlayer.playUrl] call.
     * [onPreviewError] only retries if the error fires within [RETRY_WINDOW_MS]
     * of this timestamp.
     */
    private var lastPreviewStartedAt: Long = 0L

    /**
     * Binds the delegate to the owning VM's [scope]. Must be called exactly
     * once during VM init, before any other method. Starts the internal
     * player-error collector on [scope] so structured cancellation cleans it
     * up on `onCleared`.
     *
     * @throws IllegalStateException if called twice on the same instance.
     */
    fun bindToScope(scope: CoroutineScope) {
        check(boundScope == null) { "TrackActionsDelegate.bindToScope called twice" }
        boundScope = scope
        scope.launch {
            previewPlayer.playerErrors.collect { event ->
                onPreviewError(event.videoId, event.error)
            }
        }
    }

    private fun scope(): CoroutineScope =
        checkNotNull(boundScope) { "TrackActionsDelegate used before bindToScope" }

    /**
     * Starts an audio preview for [videoId].
     *
     * Hits the shared [PreviewUrlCache] first — if prefetcher already warmed
     * the URL, playback starts immediately. Otherwise falls through to the
     * full [PreviewUrlExtractor] race (InnerTube vs yt-dlp).
     *
     * The `lastPreviewVideoId` / `lastPreviewStartedAt` bookkeeping MUST be
     * set BEFORE `playUrl` so a synchronous `onPlayerError` (which can fire
     * for a malformed URL) still observes the correct "most recent preview"
     * state.
     */
    fun previewTrack(videoId: String) {
        previewPlayer.stop()
        scope().launch {
            _previewLoadingId.value = videoId
            try {
                val url = previewUrlCache[videoId]
                    ?: previewUrlExtractor.extractStreamUrl(videoId).also {
                        previewUrlCache[videoId] = it
                    }
                lastPreviewVideoId = videoId
                lastPreviewStartedAt = SystemClock.elapsedRealtime()
                previewPlayer.playUrl(videoId, url)
                _previewLoadingId.value = null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Preview failed for videoId=$videoId", e)
                _previewLoadingId.value = null
                _userMessages.emit("Couldn't load preview.")
                previewPlayer.stop()
            }
        }
    }

    /** Stops the current audio preview, if any, and clears the loading flag. */
    fun stopPreview() {
        previewPlayer.stop()
        _previewLoadingId.value = null
    }

    /**
     * ExoPlayer error handler. Invoked automatically by the internal
     * `playerErrors` collector started in [bindToScope], and exposed publicly
     * so tests can drive the retry path directly.
     *
     * Retries via yt-dlp IFF all three hold:
     *  - [error] is an IO-class [PlaybackException] code (2000..2999).
     *  - [videoId] matches [lastPreviewVideoId] (ignores stale late errors).
     *  - It fired within [RETRY_WINDOW_MS] of [lastPreviewStartedAt] — playback
     *    never went ready before failing.
     *
     * On retry failure we surface a snackbar so the user knows preview isn't
     * going to recover on its own.
     */
    fun onPreviewError(videoId: String, error: PlaybackException) {
        if (!isIoError(error)) return
        if (videoId != lastPreviewVideoId) return
        val elapsed = SystemClock.elapsedRealtime() - lastPreviewStartedAt
        if (elapsed > RETRY_WINDOW_MS) return

        scope().launch {
            _previewLoadingId.value = videoId
            try {
                val retryUrl = previewUrlExtractor.extractViaYtDlpForRetry(videoId)
                previewUrlCache[videoId] = retryUrl
                previewPlayer.playUrl(videoId, retryUrl)
                _previewLoadingId.value = null
                Log.d(TAG, "yt-dlp retry SUCCESS for $videoId after InnerTube error $error")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "yt-dlp retry FAILED for $videoId", t)
                _previewLoadingId.value = null
                _userMessages.emit("Couldn't load preview.")
            }
        }
    }

    /**
     * Initiates a background download of [item].
     *
     * Runs in its own coroutine so the user can continue browsing or start
     * additional downloads concurrently. No-ops when the videoId is already
     * in [_downloadingIds] or [_downloadedIds]. The `CancellationException`
     * rethrow in the catch block preserves structured concurrency — without
     * it, a VM cancel would mark the download as failed instead of propagating
     * the cancel.
     */
    fun downloadTrack(item: TrackItem) {
        if (item.videoId in _downloadingIds.value) return
        if (item.videoId in _downloadedIds.value) return
        _downloadingIds.update { it + item.videoId }

        scope().launch {
            try {
                val url = "https://www.youtube.com/watch?v=${item.videoId}"
                val qualityTier = qualityPrefs.qualityTier.first()
                val qualityArgs = qualityTier.toYtDlpArgs()
                val tempDir = fileOrganizer.getTempDir()
                val tempFilename = "actions_${item.videoId}_${UUID.randomUUID()}"

                when (val result = downloadExecutor.download(
                    url = url,
                    outputDir = tempDir,
                    filename = tempFilename,
                    qualityArgs = qualityArgs,
                )) {
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
        item: TrackItem,
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

        _downloadingIds.update { it - item.videoId }
        _downloadedIds.update { it + item.videoId }
    }

    private fun markDownloadFailed(videoId: String) {
        _downloadingIds.update { it - videoId }
    }

    /**
     * Cross-reference [videoIds] against the local DB and update
     * [downloadedIds] so already-downloaded tracks show the green checkmark on
     * screen. Callers supply the list of ids visible on screen (not all ids
     * in the DB) so this stays O(screen-size) not O(library-size).
     */
    suspend fun refreshDownloadedIds(videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        val downloaded = videoIds.filter { id ->
            trackDao.findByYoutubeId(id)?.isDownloaded == true
        }.toSet()
        _downloadedIds.update { it + downloaded }
    }

    /**
     * Called from the owning VM's `onCleared`. Stops any active preview so
     * audio doesn't outlive the screen. [boundScope] auto-cancels via
     * structured concurrency; no need to stop the error collector manually.
     */
    fun onOwnerCleared() {
        previewPlayer.stop()
    }

    /**
     * Treats all IO_* error codes as "InnerTube URL rejected" — spec §9.3
     * deliberately broadens this beyond `ERROR_CODE_IO_UNSPECIFIED` so
     * variants like IO_NETWORK_CONNECTION_FAILED or IO_BAD_HTTP_STATUS also
     * get a yt-dlp retry. IO codes are in the 2000-2999 range per media3's
     * documented error-code contract.
     */
    private fun isIoError(error: PlaybackException): Boolean =
        error.errorCode in 2000..2999

    companion object {
        private const val TAG = "TrackActionsDelegate"

        /**
         * Retry window after `playUrl` — an ExoPlayer error inside this window
         * is treated as an InnerTube URL rejection and triggers the yt-dlp
         * fallback. Errors outside the window are left alone.
         */
        private const val RETRY_WINDOW_MS = 3_000L
    }
}

/**
 * Minimal track identity needed to initiate a download. A light-weight stand-in
 * that both `SearchResultItem` (feature/search) and any future caller (e.g.
 * `AlbumDiscoveryViewModel`) can map to at the call site with a one-liner.
 *
 * Lives here (not in `core/model`) because it's specific to the delegate's
 * download path — other layers work with full [com.stash.core.model.Track]s.
 */
data class TrackItem(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Double,
    val thumbnailUrl: String?,
)
