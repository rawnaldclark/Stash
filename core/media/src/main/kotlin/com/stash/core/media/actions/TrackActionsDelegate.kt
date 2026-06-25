package com.stash.core.media.actions

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.PlaybackException
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.media.preview.SearchPreviewMediaSource
import com.stash.core.model.TrackItem
import com.stash.data.download.preview.PreviewUrlCache
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.download.search.SearchDownloadCoordinator
import com.stash.data.download.search.SearchDownloadStatus
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val searchPreviewMediaSource: SearchPreviewMediaSource,
    private val previewUrlExtractor: PreviewUrlExtractor,
    private val previewUrlCache: PreviewUrlCache,
    private val trackDao: TrackDao,
    private val searchDownloadCoordinator: SearchDownloadCoordinator,
    private val playerRepository: com.stash.core.media.PlayerRepository,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
    private val musicRepository: com.stash.core.data.repository.MusicRepository,
    private val blocklistGuard: com.stash.core.data.blocklist.BlocklistGuard,
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

    private val _waitingForLosslessIds = MutableStateFlow<Set<String>>(emptySet())
    /**
     * v0.9.17+: VideoIds whose download was deferred because the lossless
     * registry returned no match AND the user has yt-dlp fallback off. The
     * UI surfaces this with a clock icon (distinct from the red Failed
     * treatment) so the user knows the track is queued for retry, not
     * permanently broken. Cleared when the user taps the row again or the
     * retry succeeds.
     */
    val waitingForLosslessIds: StateFlow<Set<String>> = _waitingForLosslessIds.asStateFlow()

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
     * Starts an audio preview for [track] using the v0.9.12 MediaSource-based
     * happy path first, then falls back to the yt-dlp URL extractor on failure.
     *
     * ### Happy path (new in v0.9.12)
     * [SearchPreviewMediaSource.create] resolves a Qobuz CDN URL (or yt-dlp
     * fallback) and wraps it in a [CacheDataSource]-backed [MediaSource] so that
     * bytes streamed during preview are reused by a subsequent download finalise
     * step — avoiding a second full-file fetch.  [PreviewPlayer.play] consumes
     * the [MediaSource] directly; no URL string is exposed to this layer.
     *
     * ### Error fallback (unchanged from pre-v0.9.12)
     * If the happy path throws, we fall back to [previewUrlExtractor] +
     * [PreviewUrlCache] + [PreviewPlayer.playUrl] — the same URL-only flow
     * used before this rewrite.  [onPreviewError] also retains this fallback
     * for ExoPlayer-level IO failures that fire after [play] returns.
     *
     * ### Bookkeeping
     * [lastPreviewVideoId] / [lastPreviewStartedAt] are recorded BEFORE calling
     * [play] so a synchronous [onPlayerError] (possible for malformed URLs)
     * still observes the correct "most recent preview" state.
     *
     * @param track Full [TrackItem] so that [SearchPreviewMediaSource] can
     *              perform artist+title matching for lossless-source selection.
     */
    fun previewTrack(track: TrackItem) {
        val videoId = track.videoId

        scope().launch {
            // Streaming mode: skip preview entirely. Route to full-track playback
            // via PlayerRepository.playFromStream. Same routing as
            // SearchViewModel.onResultTap, so every surface that calls
            // delegate.previewTrack (SearchScreen, ArtistProfileScreen,
            // AlbumDiscoveryScreen, etc.) behaves identically when streaming
            // is enabled instead of falling back to the 30s preview clip.
            if (streamingPreference.current()) {
                // Drive the row-level spinner while the stream resolves. The
                // resolve can take seconds (worst for amz, which fetch+decrypts
                // a whole FLAC), so without this the row sits silent and looks
                // broken. Mirrors the preview path's _previewLoadingId handling.
                // Idempotency guard: a second tap on the same in-flight row is a
                // no-op (matches the preview path).
                if (_previewLoadingId.value == videoId) return@launch
                _previewLoadingId.value = videoId
                try {
                    val result = playerRepository.playFromStream(track)
                    when (result) {
                        is com.stash.core.media.StreamRoutingResult.Item -> Unit
                        com.stash.core.media.StreamRoutingResult.Deduped -> Unit
                        com.stash.core.media.StreamRoutingResult.NotAvailable ->
                            _userMessages.emit("Couldn't stream this track.")
                        com.stash.core.media.StreamRoutingResult.OfflineMode ->
                            _userMessages.emit("Turn on Online mode to stream this track.")
                        com.stash.core.media.StreamRoutingResult.CellularRefused ->
                            _userMessages.emit("Streaming on cellular is off in Settings.")
                        com.stash.core.media.StreamRoutingResult.NoConnectivity ->
                            _userMessages.emit("You're offline — can't stream this track.")
                    }
                } finally {
                    _previewLoadingId.value = null
                }
                return@launch
            }

            // Preview-mode idempotency guard: if we're already playing — or
            // loading — this same videoId, do nothing. Prevents redundant
            // stop+restart cycles from phantom clicks, double-taps, or any
            // future caller that fires the same id twice within the play
            // window. The Stop button already uses [stopPreview] so legitimate
            // "stop" taps still work; this only swallows spurious "play what's
            // already playing" requests.
            if ((previewState.value as? PreviewState.Playing)?.videoId == videoId) return@launch
            if (_previewLoadingId.value == videoId) return@launch

            previewPlayer.stop()
            val t0 = System.currentTimeMillis()
            _previewLoadingId.value = videoId
            try {
                android.util.Log.d("LATDIAG", "preview-start videoId=$videoId via MediaSource")

                // v0.9.12 happy path: build a CacheDataSource-wrapped MediaSource.
                // create() is suspend; it resolves the upstream URL (Qobuz or yt-dlp)
                // and constructs the source, but does NOT begin network I/O yet —
                // that happens inside ExoPlayer once prepare() is called by play().
                val mediaSource = searchPreviewMediaSource.create(track)

                // Set bookkeeping BEFORE play() so a synchronous onPlayerError
                // (which can fire for a malformed upstream URL before prepare()
                // completes) still sees the correct "most recent preview" state
                // and triggers the onPreviewError retry path correctly.
                lastPreviewVideoId = videoId
                lastPreviewStartedAt = SystemClock.elapsedRealtime()

                previewPlayer.play(videoId, mediaSource)
                _previewLoadingId.value = null

                android.util.Log.d(
                    "LATDIAG",
                    "preview-play videoId=$videoId totalDt=${System.currentTimeMillis() - t0}ms via MediaSource",
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                // Happy-path failure: fall back to the URL-only extractor so the
                // user still gets a preview even when the Qobuz proxy is unavailable.
                Log.w(TAG, "MediaSource path failed for videoId=$videoId (${e.message}), falling back to URL extractor", e)
                android.util.Log.d(
                    "LATDIAG",
                    "preview-mediasource-fail videoId=$videoId totalDt=${System.currentTimeMillis() - t0}ms err=${e.javaClass.simpleName}",
                )

                runCatching {
                    val cacheHit = previewUrlCache[videoId] != null
                    val url = previewUrlCache[videoId]
                        ?: previewUrlExtractor.extractStreamUrl(videoId).also {
                            previewUrlCache[videoId] = it
                        }
                    lastPreviewVideoId = videoId
                    lastPreviewStartedAt = SystemClock.elapsedRealtime()
                    previewPlayer.playUrl(videoId, url)
                    _previewLoadingId.value = null
                    android.util.Log.d(
                        "LATDIAG",
                        "preview-url-fallback-play videoId=$videoId cache=$cacheHit totalDt=${System.currentTimeMillis() - t0}ms",
                    )
                }.onFailure { retryError ->
                    if (retryError is CancellationException) throw retryError
                    Log.e(TAG, "URL-fallback preview also failed for videoId=$videoId", retryError)
                    android.util.Log.d(
                        "LATDIAG",
                        "preview-fail videoId=$videoId totalDt=${System.currentTimeMillis() - t0}ms err=${retryError.javaClass.simpleName}",
                    )
                    _previewLoadingId.value = null
                    _userMessages.emit("Couldn't load preview.")
                    previewPlayer.stop()
                }
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
        val key = item.videoId
        if (key in _downloadingIds.value || key in _downloadedIds.value) return
        _downloadingIds.update { it + key }

        scope().launch {
            try {
                searchDownloadCoordinator.download(item).collect { status ->
                    when (status) {
                        is SearchDownloadStatus.Resolving -> {
                            // Already in _downloadingIds; no UI change needed.
                        }
                        is SearchDownloadStatus.Downloading -> {
                            // Notify the user when falling back to YouTube — the
                            // lossless (Qobuz) path is the expected fast case and
                            // needs no message.
                            if (status.via == SearchDownloadStatus.Source.YOUTUBE) {
                                _userMessages.tryEmit("Downloading via YouTube (slower)…")
                            }
                        }
                        is SearchDownloadStatus.Completed -> {
                            _downloadingIds.update { it - key }
                            _downloadedIds.update { it + key }
                            _waitingForLosslessIds.update { it - key }
                        }
                        is SearchDownloadStatus.Failed -> {
                            _downloadingIds.update { it - key }
                            _userMessages.tryEmit("Download failed: ${status.message}")
                        }
                        is SearchDownloadStatus.WaitingForLossless -> {
                            // v0.9.17 strict-FLAC: lossless unavailable + fallback
                            // off. Don't emit a Failed snackbar — the row keeps
                            // a "waiting" indicator until the retry scheduler
                            // (Task 9) succeeds or the user changes prefs.
                            _downloadingIds.update { it - key }
                            _waitingForLosslessIds.update { it + key }
                            _userMessages.tryEmit("Waiting for lossless source…")
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Preserve structured cancellation — remove the spinner and
                // rethrow so the owning scope sees the cancellation signal.
                _downloadingIds.update { it - key }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "downloadTrack failed for $key", e)
                _downloadingIds.update { it - key }
                _userMessages.tryEmit("Download failed: ${e.message ?: "unknown error"}")
            }
        }
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

    /** Insert [item] right after the current track. */
    fun playNext(item: TrackItem) {
        scope().launch {
            try {
                playerRepository.addNext(item.toDomainTrack())
                _userMessages.tryEmit("Playing next")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "playNext failed for ${item.videoId}", e)
                _userMessages.tryEmit("Couldn't add to queue")
            }
        }
    }

    /** Start a song radio seeded from [item]. Streaming-only: on a false return
     *  (streaming off/offline) we surface a hint instead of a dead tap. */
    fun startRadio(item: TrackItem) {
        scope().launch {
            val started = playerRepository.startRadio(
                com.stash.core.data.radio.RadioSeed.Song(
                    title = item.title, artist = item.artist, ytVideoId = item.videoId,
                ),
            )
            if (!started) _userMessages.tryEmit("Radio needs Online mode — turn on streaming.")
        }
    }

    /** Append [item] to the end of the queue. */
    fun addToQueue(item: TrackItem) {
        scope().launch {
            try {
                playerRepository.addToQueue(item.toDomainTrack())
                _userMessages.tryEmit("Added to queue")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "addToQueue failed for ${item.videoId}", e)
                _userMessages.tryEmit("Couldn't add to queue")
            }
        }
    }

    /**
     * Returns the DB id for [item], inserting a stub row if absent. Runs the
     * BlocklistGuard first (v0.9.15: insert paths that skip the guard resurrect
     * blocked tracks). Returns null when blocked. No download columns are set —
     * this is a streamable library entry, not a downloaded file.
     *
     * Mirrors [SearchDownloadCoordinator.upsertSearchTrack]'s lookup sequence
     * and TrackEntity construction so identity-matching agrees with the
     * download path (same row, no duplicate).
     */
    private suspend fun ensureTrackPersisted(item: TrackItem): Long? {
        if (blocklistGuard.isBlocked(
                artist = item.artist, title = item.title,
                spotifyUri = null, youtubeId = item.videoId,
            )
        ) {
            Log.d(TAG, "addToPlaylist refused blocked: ${item.artist} - ${item.title}")
            return null
        }
        val existing = trackDao.findByYoutubeId(item.videoId)
            ?: trackDao.findByCanonicalIdentity(
                title = canonicalize(item.title),
                artist = canonicalize(item.artist),
            )
        return existing?.id ?: trackDao.insert(
            com.stash.core.data.db.entity.TrackEntity(
                title = item.title,
                artist = item.artist,
                album = item.album.orEmpty(),
                albumArtist = item.albumArtist.orEmpty(),
                youtubeId = item.videoId,
                canonicalTitle = canonicalize(item.title),
                canonicalArtist = canonicalize(item.artist),
                durationMs = (item.durationSeconds * 1000).toLong(),
                source = com.stash.core.model.MusicSource.YOUTUBE,
                albumArtUrl = item.thumbnailUrl,
            )
        )
    }

    /**
     * Normalises a string for DB identity matching. MUST stay byte-identical
     * to [SearchDownloadCoordinator.canonicalize] or the delegate's insert-if-
     * absent lookup disagrees with the download path and creates duplicate rows.
     */
    private fun canonicalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Add [item] to an existing playlist. Inserts a streamable stub if the
     *  track isn't in the library yet (NOT downloaded). */
    fun addToPlaylist(item: TrackItem, playlistId: Long) {
        scope().launch {
            try {
                val trackId = ensureTrackPersisted(item) ?: run {
                    _userMessages.tryEmit("Can't add a blocked track")
                    return@launch
                }
                musicRepository.addTrackToPlaylist(trackId, playlistId)
                _userMessages.tryEmit("Added to playlist")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "addToPlaylist failed for ${item.videoId}", e)
                _userMessages.tryEmit("Couldn't add to playlist")
            }
        }
    }

    /** Create a new playlist named [name] and add [item] to it. */
    fun createPlaylistAndAdd(item: TrackItem, name: String) {
        scope().launch {
            try {
                val trackId = ensureTrackPersisted(item) ?: run {
                    _userMessages.tryEmit("Can't add a blocked track")
                    return@launch
                }
                val playlistId = musicRepository.createPlaylist(name)
                musicRepository.addTrackToPlaylist(trackId, playlistId)
                _userMessages.tryEmit("Added to playlist")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "createPlaylistAndAdd failed for ${item.videoId}", e)
                _userMessages.tryEmit("Couldn't add to playlist")
            }
        }
    }

    /** User-created playlists for the picker. */
    val userPlaylists: kotlinx.coroutines.flow.Flow<List<com.stash.core.model.Playlist>> =
        musicRepository.getUserCreatedPlaylists()

    /**
     * Maps a search-surface [TrackItem] to a domain [Track] for the queue.
     * No existing mapper fits — preview/download pass TrackItem through
     * untouched. Mirrors ArtistProfileViewModel.toDomainTrack: the synthetic
     * id + youtubeId let PlayerRepositoryImpl.resolveTrackToMediaItem's
     * toEntity() fallback stream-resolve a track that isn't in the DB.
     */
    private fun TrackItem.toDomainTrack(): com.stash.core.model.Track =
        com.stash.core.model.Track(
            id = videoId.hashCode().toLong(),
            title = title,
            artist = artist,
            album = album.orEmpty(),
            durationMs = (durationSeconds * 1000).toLong(),
            albumArtUrl = thumbnailUrl,
            youtubeId = videoId,
            source = com.stash.core.model.MusicSource.YOUTUBE,
            isStreamable = true,
        )

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

// TrackItem was defined here until v0.9.12; it now lives in
// com.stash.core.model.TrackItem so that :data:download can reference it
// without creating a circular module dependency with :core:media.
