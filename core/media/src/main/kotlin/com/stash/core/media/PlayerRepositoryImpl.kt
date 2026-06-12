package com.stash.core.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.stash.core.common.constants.StashConstants
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.media.streaming.YouTubeStreamResolver
import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_BIT_DEPTH
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_BITRATE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_CODEC
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_SAMPLE_RATE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_DURATION_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_IS_STREAMABLE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_YOUTUBE_ID
import com.stash.core.model.TrackItem
import com.stash.core.model.isUnavailableForDisplay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

/**
 * [PlayerRepository] implementation backed by a [MediaController] that connects
 * to [StashPlaybackService].
 *
 * The controller is lazily initialised on first use and re-used for the lifetime
 * of the application process.
 */
@Singleton
class PlayerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackStateStore: PlaybackStateStore,
    private val musicRepository: MusicRepository,
    private val streamingPreference: StreamingPreference,
    private val streamResolver: StreamSourceRegistry,
    private val streamUrlCache: StreamUrlCache,
    private val connectivity: ConnectivityMonitor,
    private val trackDao: TrackDao,
    private val playbackResumer: PlaybackResumer,
) : PlayerRepository {

    /**
     * Visible-for-testing indirection so unit tests can stub out the
     * on-disk check without touching the real filesystem. Handles both plain
     * filesystem paths (via [File]) and SAF-backed external storage URIs
     * (via [DocumentFile]).
     *
     * **Treats a too-small file as NOT a usable download.** A failed download
     * can leave a tiny garbage file behind while the row is still marked
     * `isDownloaded` — e.g. yt-dlp writing a ~274-byte error body into a
     * `.webm` and the pipeline recording it as complete. `exists()` and
     * `length()` are both truthy for it, so the old check played it as a local
     * source; ExoPlayer then reads a few hundred bytes of non-audio and throws
     * ERROR_CODE_PARSING_CONTAINER_MALFORMED, which skip-storms the queue.
     * Requiring at least [StashConstants.MIN_PLAYABLE_LOCAL_BYTES] makes those rows fall
     * through to streaming instead (the registry re-resolves by YouTube id /
     * metadata, independent of the stale `isStreamable` flag). No real music
     * track is anywhere near this small, so there are no false negatives.
     */
    internal var filePathExistsOnDisk: (String) -> Boolean = { path ->
        val bytes = try {
            if (path.startsWith("content://")) {
                DocumentFile.fromSingleUri(context, path.toUri())?.length() ?: 0L
            } else {
                // Handle both plain paths and file:// URIs.
                val plainPath = if (path.startsWith("file://")) {
                    path.toUri().path ?: path.removePrefix("file://")
                } else {
                    path
                }
                File(plainPath).length()
            }
        } catch (e: Exception) {
            0L
        }
        bytes >= StashConstants.MIN_PLAYABLE_LOCAL_BYTES
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        // v0.9.27: Connect the controller immediately on init so we can
        // provide live state even if the app was cold-started while
        // music was already playing (e.g. via Android Auto).
        scope.launch {
            ensureController()
        }

        // Evict deleted tracks from the live queue. Without this, ExoPlayer's
        // open file handle keeps audio playing after the user deletes the
        // song (correct Unix semantics, wrong UX) — see Reddit report from
        // user Superb_Agency_796. Subscribing here means every repo delete
        // entry-point automatically informs the player; future delete methods
        // don't have to remember to call a helper in the ViewModel layer.
        scope.launch {
            musicRepository.trackDeletions.collect { trackId ->
                evictTrackFromQueue(trackId)
            }
        }

        // v0.9.14: Library-shuffle auto-grow watcher. Subscribes to player
        // state and refills the queue with more shuffled library tracks
        // once the user nears the tail. Inactive unless shuffleLibrary()
        // armed it; setQueue() disarms it so per-playlist queues stay
        // finite and predictable.
        scope.launch {
            playerState.collect { state ->
                if (!libraryShuffleActive) return@collect
                val remaining = state.queue.size - state.currentIndex - 1
                if (remaining in 0 until LIBRARY_SHUFFLE_GROW_THRESHOLD) {
                    growLibraryShuffle()
                }
            }
        }

        // Next-track prefetch watcher. Whenever the player advances (currentIndex
        // changes), eagerly resolve currentQueueTracks[currentIndex+1] so its URL
        // is cached + the controller's MediaItem URI is refreshed BEFORE ExoPlayer
        // starts pre-buffering the next track. Eliminates the 5-10s pause that
        // happens when the next track's URL has expired or wasn't covered by
        // background fill (e.g. an iOS-miss straggler skipped because the
        // background fill uses the fast lane allowYtDlp=false, so prefetch
        // re-resolves it here with allowYtDlp=true).
        //
        // Bounded to 1 track ahead — does NOT prefetch idx+2 or further. The
        // reactive design handles skip-ahead: on a skip, the watcher re-fires
        // with the new currentIndex and prefetches the new next-up.
        scope.launch {
            playerState
                .map { it.currentIndex }
                .distinctUntilChanged()
                .collect { idx -> prefetchNextTrack(idx) }
        }
    }

    private val _playerState = MutableStateFlow(PlayerState())
    override val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    /**
     * Emits the playback position every 250 ms while the player is active.
     * Collectors receive 0 when nothing is playing.
     */
    override val currentPosition: Flow<Long> = flow {
        while (true) {
            val controller = controllerDeferred
            emit(controller?.currentPosition ?: 0L)
            delay(POSITION_UPDATE_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.Main)

    /** Cached [MediaController] instance; null until [ensureController] succeeds.
     * Internal as a test seam: gate/queue tests inject a mock controller here. */
    @Volatile
    internal var controllerDeferred: MediaController? = null

    /**
     * v0.9.14: True while a "Shuffle Library" queue is active. Set by
     * [shuffleLibrary], cleared by [setQueue]. Drives the auto-grow watcher.
     */
    @Volatile
    private var libraryShuffleActive: Boolean = false

    /**
     * v0.9.14: Cached snapshot of the user's downloaded library at the moment
     * [shuffleLibrary] was called. Auto-grow appends from this list (minus
     * tracks already queued). Survives app process for as long as library-
     * shuffle stays armed; cleared when leaving via [setQueue].
     */
    @Volatile
    private var librarySnapshot: List<Track> = emptyList()

    /**
     * The LOGICAL playback queue — the user-intended track order, independent
     * of which entries currently have a resolved MediaItem in the controller
     * timeline (the fast-lane background fill silently drops what it can't
     * resolve; antra is excluded from fill entirely).
     *
     * Three consumers:
     *  1. The next-track prefetch watcher, which looks up the next-to-play
     *     Track here so dropped stragglers are still discovered and resolved
     *     just-in-time.
     *  2. The queue display ([updateState] via [QueueDisplay.compute]) — the
     *     sheet shows THIS list, not the sparse timeline.
     *  3. The queue ops ([skipToQueueIndex]/[removeFromQueue]/[moveInQueue]),
     *     whose incoming indices refer to this list when the display is
     *     logical (see [logicalDisplayActive]).
     *
     * Every queue mutation path must keep it in sync: [setQueue] (replace),
     * [shuffleLibrary] (replace), [growLibraryShuffle] (append), [addNext]
     * (insert), [addToQueue] (append), [evictTrackFromQueue] (remove),
     * [playTrack]/[playFromStreamInner] (single/clear). A missed path
     * degrades gracefully: the display falls back to the timeline whenever
     * the playing track isn't in this list.
     */
    @Volatile
    private var currentQueueTracks: List<Track> = emptyList()

    /** Serializes auto-grow operations so multiple state updates can't fan out. */
    private val growMutex = Mutex()

    /**
     * Background coroutine that resolves the rest of the queue while
     * the user is already listening to the first track. Cancelled when
     * [setQueue] is invoked again so a stale fill can't pollute a new
     * playlist's queue.
     */
    @Volatile
    private var queueBuildJob: Job? = null

    /**
     * Monotonic counter incremented on every [setQueue] entry. Used as
     * a race guard for slow resolves: when a foreground resolve finally
     * returns, we check that no newer setQueue has been called in the
     * meantime before applying its result to the controller. Without
     * this, taps on a long-resolving track (e.g. yt-dlp fallback at
     * ~20-60s) would still end up calling `controller.setMediaItems`
     * minutes later, clobbering whatever the user is currently playing.
     */
    @Volatile
    internal var setQueueEpoch: Long = 0L

    /**
     * Epoch of the [setQueue] tapped-track resolve currently in flight, or
     * -1 when none. While it matches [setQueueEpoch], [computeIsBuffering]
     * keeps [PlayerState.isBuffering] true: the previous queue keeps
     * playing during the resolve, and its controller events re-run
     * [updateState] — without this flag they stomp setQueue's optimistic
     * spinner back to false mid-resolve (invisible at yt-dlp's ~11s, but
     * an antra job takes 60-120s and the player looked frozen/broken).
     * Set after the optimistic emit; cleared on the failure path and just
     * before playback starts on success. A superseding setQueue overwrites
     * it with its own epoch, so a stale resolve can't hold the spinner.
     * Internal (with [setQueueEpoch]) as a test seam.
     */
    @Volatile
    internal var tapResolveEpoch: Long = -1L

    /**
     * Last queue id-list + shuffle state persisted to [PlaybackStateStore].
     * Used to avoid rewriting the comma-joined queue string on every 250 ms
     * position tick — the queue only needs re-saving when its contents or
     * shuffle state actually change.
     */
    private var lastSavedQueueIds: List<Long>? = null
    private var lastSavedShuffle: Boolean? = null

    private val _userMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /**
     * Snackbar-targeted messages from playback flow:
     *   - "Couldn't play this track right now." — [setQueue]'s tapped
     *     track failed every resolver, surfaced so the user knows the
     *     tap was received but the track is genuinely unavailable.
     *   - "End of offline Mix" — the auto-advance silent-skip walked off
     *     the end of the queue trying to find a playable item while the
     *     device was offline (v0.9.37).
     * Collected by Now Playing (forwarded into its own user-messages
     * SharedFlow for Toast display) and the playlist detail screen.
     */
    override val userMessages: kotlinx.coroutines.flow.SharedFlow<String> =
        _userMessages.asSharedFlow()

    private val cascadeGuard = StreamErrorCascadeGuard()
    private val _streamingHaltedEvents = MutableSharedFlow<StreamingHaltedEvent>(
        replay = 1,
        extraBufferCapacity = 1,
    )
    override val streamingHaltedEvents: SharedFlow<StreamingHaltedEvent> =
        _streamingHaltedEvents.asSharedFlow()

    // ---- Public API ----

    override suspend fun play() {
        cascadeGuard.onUserTransport()
        ensureController()?.play()
    }

    override suspend fun pause() {
        ensureController()?.pause()
    }

    override suspend fun skipNext() {
        cascadeGuard.onUserTransport()
        ensureController()?.seekToNextMediaItem()
    }

    override suspend fun skipPrevious() {
        cascadeGuard.onUserTransport()
        ensureController()?.seekToPreviousMediaItem()
    }

    override suspend fun seekTo(positionMs: Long) {
        cascadeGuard.onUserTransport()
        ensureController()?.seekTo(positionMs)
    }

    override suspend fun setQueue(tracks: List<Track>, startIndex: Int) =
        setQueueInternal(tracks, startIndex, startPositionMs = 0L)

    /**
     * Backing implementation for [setQueue] that also accepts a start
     * position. Kept private (not on the interface) so the public
     * [setQueue] signature — and every test that stubs/verifies it with
     * argument matchers — stays unchanged. [resumeLastQueue] uses this to
     * continue from the saved position.
     */
    private suspend fun setQueueInternal(tracks: List<Track>, startIndex: Int, startPositionMs: Long) {
        // Any prior background fill belongs to a previous queue; kill it
        // so its addMediaItem calls can't pollute the new one.
        queueBuildJob?.cancel()
        queueBuildJob = null

        // Any explicit setQueue (playlist tap, single-song play, etc.) leaves
        // library-shuffle mode behind. Snapshot is cleared so a stale Track
        // list doesn't grow back into a different queue later.
        libraryShuffleActive = false
        librarySnapshot = emptyList()

        // Snapshot the requested queue early so the next-track prefetch watcher
        // can look up idx+1 even for entries that background fill (fast-lane
        // allowYtDlp=false) silently drops from the controller's timeline. New queue overwrites
        // the old; any prefetch in flight from the previous queue completes
        // harmlessly against the old reference it already captured.
        currentQueueTracks = tracks

        val controller = ensureController() ?: return
        if (tracks.isEmpty()) return

        val streamingOn = streamingPreference.current()
        val safeStart = startIndex.coerceIn(0, tracks.size - 1)
        val semaphore = Semaphore(STREAM_RESOLVE_PARALLELISM)
        // Record this call's epoch so the resolve below can refuse to
        // apply its result if a newer setQueue has come in meanwhile.
        val myEpoch = ++setQueueEpoch
        val tappedTrack = tracks[safeStart]

        // Optimistic loading state — ONLY when nothing is loaded. With an
        // idle player the mini player is hidden, so showing the tapped track
        // + spinner is the only feedback that the tap registered (a yt-dlp
        // resolve takes ~11 s, antra 60-120 s). But when a track IS loaded,
        // swapping the display to the tapped track turns the play/pause
        // button into a disabled spinner for the whole resolve — hijacking
        // control of still-playing audio. In that case the display stays on
        // the current track until the resolved track actually starts
        // (setMediaItems below).
        if (controller.currentMediaItem == null) {
            _playerState.value = _playerState.value.copy(
                currentTrack = tappedTrack,
                isPlaying = false,
                isBuffering = true,
            )
            // Keep the spinner alive across updateState() calls for the whole
            // resolve — see [tapResolveEpoch].
            tapResolveEpoch = myEpoch
        }

        // Resolve ONLY the tapped track. Earlier revisions probed forward
        // through the next few entries looking for *anything* playable,
        // but that has two real-user pathologies: (1) it silently
        // substitutes the track the user actually picked, and worse,
        // (2) when the user is already playing a track from this queue
        // and taps a different one that fails to resolve, the probe
        // falls forward into the currently-playing track and calls
        // setMediaItems on it — restarting it from 0. Better to fail
        // visibly (snackbar + log) than to surprise-restart the user's
        // music. See #75 follow-up.
        val startItem = resolveTrackToMediaItem(
            tappedTrack,
            semaphore,
            streamingOn,
            allowYouTube = true,
            allowYtDlp = true,
        )

        // Race guard: if another setQueue came in while we were
        // resolving (e.g. user tapped a different track during a slow
        // yt-dlp fallback), don't clobber the newer playback intent.
        if (myEpoch != setQueueEpoch) {
            Log.d(
                TAG,
                "setQueue[epoch=$myEpoch]: superseded by newer call (now=$setQueueEpoch); " +
                    "discarding result for track[$safeStart] '${tappedTrack.title}'",
            )
            return
        }

        if (startItem == null) {
            Log.w(
                TAG,
                "setQueue[epoch=$myEpoch]: track[$safeStart] '${tappedTrack.title}' failed to " +
                    "resolve — preserving current playback",
            )
            _userMessages.tryEmit("Couldn't play this track right now.")
            // Clear the optimistic spinner — nothing is going to play.
            tapResolveEpoch = -1L
            _playerState.value = _playerState.value.copy(isBuffering = false)
            return
        }

        Log.i(
            TAG,
            "setQueue[epoch=$myEpoch]: starting playback on track $safeStart; " +
                "${tracks.size - 1} more to resolve in background",
        )

        // Hand the spinner over to real controller state: prepare() puts
        // ExoPlayer into STATE_BUFFERING, which computeIsBuffering passes
        // through, so there is no visible gap.
        tapResolveEpoch = -1L
        controller.setMediaItems(listOf(startItem), /* startIndex = */ 0, startPositionMs)
        controller.prepare()
        controller.play()

        // Kick the next-track prefetch for the tapped track's successor
        // explicitly. The init-block watcher keys on currentIndex CHANGES,
        // and the tapped track sits at timeline index 0 — if the previous
        // queue was also at index 0 (or idle), distinctUntilChanged swallows
        // the emission and the first track's next-up never prefetches.
        // Matters most when the background fill can't pre-resolve anything
        // (Qobuz-proxy outage / antra drill): without this, the timeline
        // holds only the tapped track and playback dies at its end instead
        // of flowing into the prefetched-and-inserted next track.
        scope.launch { prefetchNextTrack(0) }

        // Fill the rest of the queue in the background. Tracks after the
        // start anchor are appended first (skip-next is the common case);
        // tracks before are prepended afterwards so skip-back still works
        // once the fill catches up. Cancellable — see queueBuildJob KDoc.
        //
        // Background-fill uses the InnerTube FAST LANE
        // (allowYouTube=true, allowYtDlp=false). InnerTube resolves in
        // parallel (cap-8) and builds the WHOLE queue in order, so
        // YouTube-only tracks stay in the timeline instead of being
        // dropped (the old allowYouTube=false fill silently skipped every
        // YouTube track, leaving ~2-item queues and skipping all
        // streamable tracks in mixed downloaded+stream mixes). Note the
        // cap-8 here is NOT the Semaphore(STREAM_RESOLVE_PARALLELISM)=16
        // fill semaphore in this file (that is the outer fan-out); the
        // effective InnerTube bottleneck is its own cap-8 extractor
        // semaphore (INNERTUBE_CONCURRENCY) inside PreviewUrlExtractor in
        // another module. Behavior delta: background fill now performs real
        // InnerTube resolution for EVERY streamable track (bounded by that
        // cap-8), whereas the old allowYouTube=false fill resolved zero
        // YouTube tracks — relevant if InnerTube ever rate-limits. Only the
        // SLOW yt-dlp engine is withheld from fill: it has a single
        // serialized extraction slot (cap-1) that must stay free for the
        // foreground tap and the next-up prefetch — a long Liked Songs
        // queue funneling stragglers through it would saturate that slot
        // for minutes and stall the next user-tap. Any iOS-miss track that
        // InnerTube can't resolve is skipped from this batch and recovered
        // by prefetchNextTrack (which runs with allowYtDlp=true), so it is
        // re-resolved just-in-time before it's reached.
        val forward = tracks.subList(safeStart + 1, tracks.size)
        val backward = tracks.subList(0, safeStart)
        queueBuildJob = scope.launch {
            try {
                // allowYtDlp = false: the fill is speculative, so it uses the
                // fast InnerTube engine only — a 15-35s yt-dlp invocation must
                // never sit on the queue's critical path. The tapped track and
                // the single next-up prefetch (prefetchNextTrack /
                // PrefetchOrchestrator) keep allowYtDlp = true.
                fillQueueAppend(controller, forward, semaphore, streamingOn, allowYouTube = true, allowYtDlp = false)
                fillQueuePrepend(controller, backward, semaphore, streamingOn, allowYouTube = true, allowYtDlp = false)
                Log.i(TAG, "setQueue: background fill complete (${tracks.size} tracks)")
                // Kick the next-up prefetch for the FIRST track explicitly. The
                // reactive watcher (playerState.currentIndex.distinctUntilChanged)
                // suppresses the initial idx=0 emission for a freshly-started
                // queue (the state already sits at 0), so without this the first
                // track never gets its next resolved+inserted. This matters when
                // the fast-lane fill (allowYtDlp=false) couldn't resolve the
                // second track (an iOS-miss straggler) and dropped it: prefetch
                // re-resolves it here with allowYtDlp=true and inserts it so the
                // first auto-advance works. Idempotent: a no-op when the next is
                // already present (the common case now that fill is in-order) or
                // not streamable.
                prefetchNextTrack(controller.currentMediaItemIndex)
            } catch (e: CancellationException) {
                // Expected when the user starts a new queue. Don't log as failure.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "setQueue: background fill failed", e)
            }
        }
    }

    override fun resumeLastQueue() {
        // Fire-and-forget on the repository scope so a no-UI trampoline
        // activity can finish immediately while resolution + playback
        // continue. Reuses setQueue, so offline and online queues both work
        // with the same proven resolution + background-fill path.
        scope.launch {
            val plan = playbackResumer.buildResumePlan()
            if (plan != null) {
                val tracks = plan.tracks.map { it.toDomain() }
                ensureController()?.shuffleModeEnabled = plan.isShuffled
                setQueueInternal(tracks, plan.startIndex, plan.positionMs)
                return@launch
            }
            // No persisted queue yet — fall back to the most recently played
            // (or most recently added) single track, matching the service's
            // onPlaybackResumption fallback.
            val fallback = trackDao.getLastPlayedTrack()
                ?: trackDao.getRecentlyAdded(1).first().firstOrNull()
            if (fallback != null) {
                setQueueInternal(listOf(fallback.toDomain()), startIndex = 0, startPositionMs = 0L)
            } else {
                Log.i(TAG, "resumeLastQueue: nothing to resume")
            }
        }
    }

    /**
     * Resolves [tracks] in parallel batches of [BACKGROUND_FILL_BATCH] and
     * appends each batch to the controller's timeline as it completes.
     * Order within the input list is preserved. Failed resolutions are
     * silently dropped — they would only contribute URI-less MediaItems
     * that ExoPlayer can't play anyway.
     */
    private suspend fun fillQueueAppend(
        controller: MediaController,
        tracks: List<Track>,
        semaphore: Semaphore,
        streamingOn: Boolean,
        allowYouTube: Boolean = true,
        allowYtDlp: Boolean = true,
    ) {
        tracks.chunked(BACKGROUND_FILL_BATCH).forEach { batch ->
            if (!currentCoroutineActive()) return
            val resolved = resolveBatchParallel(batch, semaphore, streamingOn, allowYouTube, allowYtDlp)
            if (resolved.isNotEmpty()) controller.addMediaItems(resolved)
        }
    }

    /**
     * Like [fillQueueAppend] but prepends. The input is iterated in reverse
     * batch chunks so the *first* tracks of the original list end up at
     * index 0 of the controller's timeline once fill completes.
     */
    private suspend fun fillQueuePrepend(
        controller: MediaController,
        tracks: List<Track>,
        semaphore: Semaphore,
        streamingOn: Boolean,
        allowYouTube: Boolean = true,
        allowYtDlp: Boolean = true,
    ) {
        // Process from the END of [tracks] backwards in chunks. The chunk
        // closest to the current playback head is processed last so the
        // skip-prev experience improves monotonically.
        val reversed = tracks.asReversed()
        reversed.chunked(BACKGROUND_FILL_BATCH).forEach { batchReversed ->
            if (!currentCoroutineActive()) return
            // Resolve the batch in original (forward) order so the
            // semaphore-bounded async fan-out doesn't reshuffle results.
            val batch = batchReversed.asReversed()
            val resolved = resolveBatchParallel(batch, semaphore, streamingOn, allowYouTube, allowYtDlp)
            if (resolved.isNotEmpty()) controller.addMediaItems(/* index = */ 0, resolved)
        }
    }

    private suspend fun resolveBatchParallel(
        batch: List<Track>,
        semaphore: Semaphore,
        streamingOn: Boolean,
        allowYouTube: Boolean = true,
        allowYtDlp: Boolean = true,
    ): List<MediaItem> = coroutineScope {
        batch.map { track ->
            async(Dispatchers.IO) {
                resolveTrackToMediaItem(track, semaphore, streamingOn, allowYouTube, allowYtDlp)
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Eager-resolve the next-up track (the successor of the currently-playing
     * track in `currentQueueTracks`, matched by identity — see the body) and
     * either refresh its existing timeline slot's URI, or — when it was dropped
     * from the timeline by the fast-lane (`allowYtDlp=false`) background fill
     * (an iOS-miss straggler InnerTube couldn't resolve) — insert it right
     * after the current item so ExoPlayer can auto-advance and Next works.
     *
     * Skips when:
     *  - There is no next track (current is last).
     *  - The next track is downloaded (no resolve needed).
     *  - The cache already has a fresh entry (expires in >60s).
     *  - The next track isn't streamable.
     *  - Streaming pref is off.
     *
     * Failures are logged and swallowed — the original (possibly stale)
     * MediaItem stays in place and [RefreshingDataSourceFactory] handles
     * any 403 at playback time, exactly as before this prefetch existed.
     */
    private suspend fun prefetchNextTrack(currentIndex: Int) {
        val controller = controllerDeferred ?: return
        val tracks = currentQueueTracks
        // Determine the logical next-up from the CURRENTLY-PLAYING track's
        // identity, NOT from [currentIndex]. `currentIndex` is a *timeline*
        // index (controller.currentMediaItemIndex); `currentQueueTracks` is the
        // *logical* queue. setQueue seeds the timeline with only the tapped
        // track at timeline index 0 even when it is currentQueueTracks[K>0], so
        // the two index spaces align only when playback started from track 0.
        // Matching the current item's EXTRA_TRACK_ID into currentQueueTracks
        // keeps "next" correct no matter where in the playlist the user started
        // (and fixes the same latent aliasing in the URI-swap path below).
        val currentId = controller.currentMediaItem
            ?.mediaMetadata?.extras?.getLong(EXTRA_TRACK_ID, -1L) ?: return
        if (currentId <= 0L) return // missing/invalid id — can't locate the next-up
        val currentPos = tracks.indexOfFirst { it.id == currentId }
        val nextIndex = currentPos + 1
        if (currentPos < 0 || nextIndex >= tracks.size) return

        val next = tracks[nextIndex]
        if (next.filePath != null) return
        // Only a CONFIRMED-unstreamable row (checked and false) is skipped.
        // Synced-library rows are all "never checked" (isStreamable=false,
        // isStreamableCheckedAt=null) — the bare-flag gate silently killed
        // next-track prefetch for every synced track.
        if (next.isUnavailableForDisplay) return
        if (!streamingPreference.current()) return

        // Fresh-cache check — avoid redundant work when the URL is good.
        val cached = streamUrlCache.get(next.id)
        val nowMs = System.currentTimeMillis()
        if (cached != null && cached.expiresAtMs > nowMs + PREFETCH_FRESH_THRESHOLD_MS) return

        val t0 = System.currentTimeMillis()
        Log.d("LATDIAG", "prefetch-next-start id=${next.id} youtubeId=${next.youtubeId}")
        val entity = trackDao.getById(next.id) ?: next.toEntity()
        val resolved = try {
            // Full-fat resolve (allowYtDlp = true) for the single next-up track
            // during active playback, so auto-advance stays seamless during a
            // Qobuz-proxy outage even if it falls through to the YouTube path.
            streamResolver.resolve(entity, allowYouTube = true, allowYtDlp = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "prefetch-next failed for id=${next.id}: ${e.message}")
            Log.d("LATDIAG", "prefetch-next-end id=${next.id} dt=${System.currentTimeMillis() - t0}ms outcome=throw:${e.javaClass.simpleName}")
            return
        }
        if (resolved == null) {
            Log.d("LATDIAG", "prefetch-next-end id=${next.id} dt=${System.currentTimeMillis() - t0}ms outcome=null")
            return
        }
        streamUrlCache.put(next.id, resolved)
        Log.d("LATDIAG", "prefetch-next-end id=${next.id} dt=${System.currentTimeMillis() - t0}ms outcome=url expiresAt=${resolved.expiresAtMs}")

        // If the next track is already a slot in the controller's timeline,
        // refresh its URI in place. If it ISN'T — because the fast-lane
        // (allowYtDlp=false) background fill skipped it (an iOS-miss straggler
        // InnerTube couldn't resolve) — the timeline has no next item, so
        // ExoPlayer can't auto-advance and the Next button is a no-op. Insert
        // the next track right after the current item so playback flows. We
        // build a real MediaItem from the now-cached URL (cache hit — no extra
        // yt-dlp slot) with proper EXTRA_TRACK_ID metadata: the proven eager
        // path, NOT the reverted stash-lazy:// placeholder/synthetic-id scheme.
        val swapped = refreshControllerMediaItem(controller, next, resolved)
        if (!swapped) {
            val item = (buildMediaItemForTrack(entity, allowYouTube = true, allowYtDlp = true) as? StreamRoutingResult.Item)?.mediaItem
            if (item != null) insertNextMediaItem(controller, next.id, item)
        }
    }

    /**
     * Swap the URI of the timeline slot matching [next] in place, preserving its
     * mediaId / metadata / extras so listeners observe a pure URI swap. Returns
     * `true` if the slot was found and refreshed, `false` if [next] isn't in the
     * controller's timeline (the caller then inserts it — see [insertNextMediaItem]).
     */
    private fun refreshControllerMediaItem(
        controller: MediaController,
        next: Track,
        resolved: StreamUrl,
    ): Boolean {
        val count = controller.mediaItemCount
        for (i in 0 until count) {
            val item = controller.getMediaItemAt(i)
            val itemTrackId = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID) ?: continue
            if (itemTrackId == next.id) {
                val refreshed = item.buildUpon()
                    .setUri(resolved.url)
                    .build()
                controller.replaceMediaItem(i, refreshed)
                return true
            }
        }
        return false
    }

    /**
     * Insert the freshly-resolved next track right after the current item so
     * ExoPlayer has a next MediaItem to auto-advance to (and Next works). Used
     * only when the track was dropped from the timeline by the fast-lane
     * (allowYtDlp=false) background fill (iOS-miss straggler). Re-scans first so a
     * racing prefetch can't double-insert; the scan + insert run synchronously
     * on the controller thread, so they are atomic. Bounded to one track ahead
     * by the prefetch watcher, so it never floods the yt-dlp extraction slots.
     *
     * Caveat: inserts the LINEAR next track at the current position + 1. With
     * shuffle ON and a sparse (YT-fallback) timeline, ExoPlayer advances in its
     * own shuffle order, which won't match this linear-next — but this branch
     * only runs in the genuine all-streaming-down case where the alternative is
     * a dead single-item timeline, so it's still strictly better than stopping.
     * It never runs on a healthy (full-timeline) lossless queue.
     */
    private fun insertNextMediaItem(controller: MediaController, trackId: Long, item: MediaItem) {
        val count = controller.mediaItemCount
        for (i in 0 until count) {
            val id = controller.getMediaItemAt(i).mediaMetadata.extras?.getLong(EXTRA_TRACK_ID) ?: continue
            if (id == trackId) return // already placed (e.g. by a concurrent prefetch)
        }
        val insertAt = (controller.currentMediaItemIndex + 1).coerceIn(0, count)
        controller.addMediaItem(insertAt, item)
    }

    /**
     * Helper that bridges [isActive] (a CoroutineScope extension) into a
     * plain suspend function context. Returns false once the enclosing
     * job has been cancelled so background-fill loops can bail out.
     */
    private suspend fun currentCoroutineActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    /**
     * Resolves a single [Track] to a Media3 [MediaItem] with a playable URI,
     * or `null` if the track is unplayable in the current mode.
     *
     * - Downloaded tracks: returns the local file:// MediaItem immediately
     *   (no network needed even when streaming is on — local is faster).
     * - Streaming-only tracks: when streaming is enabled, acquires a
     *   [semaphore] permit and resolves via [buildMediaItemForTrack] which
     *   consults the URL cache and falls through to [streamResolver].
     * - Streaming off + no file: returns `null` (dropped from the queue).
     */
    private suspend fun resolveTrackToMediaItem(
        track: Track,
        semaphore: Semaphore,
        streamingOn: Boolean,
        allowYouTube: Boolean = true,
        allowYtDlp: Boolean = true,
    ): MediaItem? {
        val localPath = track.filePath
        if (track.isDownloaded && !localPath.isNullOrBlank() && filePathExistsOnDisk(localPath)) {
            return track.toMediaItem()
        }
        if (!streamingOn) return null

        return semaphore.withPermit {
            val entity = trackDao.getById(track.id) ?: track.toEntity()
            val result = buildMediaItemForTrack(
                entity,
                allowYouTube = allowYouTube,
                allowYtDlp = allowYtDlp,
            )
            (result as? StreamRoutingResult.Item)?.mediaItem
        }
    }

    override suspend fun shuffleLibrary() {
        val controller = ensureController() ?: return
        val all = musicRepository.getAllDownloadedTracks()
        if (all.isEmpty()) return

        val shuffled = all.shuffled()
        librarySnapshot = shuffled
        libraryShuffleActive = true
        // Keep the logical queue in lockstep: all-downloaded tracks resolve
        // 1:1 into the timeline, but a stale logical list from an earlier
        // setQueue would otherwise hijack the queue display whenever the
        // playing track happened to be in it.
        currentQueueTracks = shuffled

        val mediaItems = shuffled.map { it.toMediaItem() }
        controller.setMediaItems(mediaItems, /* startIndex = */ 0, /* startPositionMs = */ 0L)
        // Match user expectation: pressing "Shuffle Library" implies shuffle
        // is on, regardless of the previous toggle state. The Media3 shuffle
        // mode toggles randomized advance order; we already pre-shuffled the
        // queue ourselves, so we leave shuffleModeEnabled alone — the queue
        // we hand to the controller IS the playback order.
        controller.prepare()
        controller.play()
    }

    /**
     * Append the next slice of unused library tracks to the controller's
     * timeline. Mutex-guarded so a flurry of state updates (each track
     * change emits two or three) can't fan out into concurrent grows.
     *
     * Strategy: rebuild the "currently queued" set by reading the controller
     * timeline, take everything from [librarySnapshot] not in that set,
     * shuffle those, append [LIBRARY_SHUFFLE_GROW_BATCH]. If the snapshot is
     * exhausted (whole library is in the queue already), reshuffle the
     * snapshot for a fresh slice — looping is preferable to silence for the
     * "just keep music playing" intent of this entry point.
     */
    private suspend fun growLibraryShuffle() {
        growMutex.withLock {
            val controller = controllerDeferred ?: return
            val snapshot = librarySnapshot
            if (snapshot.isEmpty()) return

            val queuedIds = buildSet {
                for (i in 0 until controller.mediaItemCount) {
                    val id = controller.getMediaItemAt(i).mediaMetadata.extras
                        ?.getLong(EXTRA_TRACK_ID)
                        ?: controller.getMediaItemAt(i).mediaId.toLongOrNull()
                    if (id != null) add(id)
                }
            }

            val unused = snapshot.filterNot { it.id in queuedIds }
            val pool = if (unused.isEmpty()) snapshot else unused
            val toAppend = pool.shuffled().take(LIBRARY_SHUFFLE_GROW_BATCH)
            if (toAppend.isEmpty()) return

            controller.addMediaItems(toAppend.map { it.toMediaItem() })
            currentQueueTracks = currentQueueTracks + toAppend
        }
    }

    override suspend fun addNext(track: Track) {
        val controller = ensureController() ?: return
        val wasEmpty = controller.mediaItemCount == 0
        val streamingOn = streamingPreference.current()
        // Single-track resolve — no parallelism needed, semaphore size 1.
        val semaphore = Semaphore(1)
        val mediaItem = resolveTrackToMediaItem(track, semaphore, streamingOn) ?: return
        val insertIndex = controller.currentMediaItemIndex + 1
        controller.addMediaItem(insertIndex, mediaItem)
        // Mirror into the logical queue right after the playing track's
        // logical position (falling back to append) so the queue sheet
        // shows the Play-Next insert where it will actually play.
        val currentId = controller.currentMediaItem?.mediaMetadata?.extras
            ?.getLong(EXTRA_TRACK_ID, -1L) ?: -1L
        val logicalPos = currentQueueTracks.indexOfFirst { it.id == currentId }
        currentQueueTracks = when {
            wasEmpty -> listOf(track)
            logicalPos >= 0 -> currentQueueTracks.toMutableList()
                .apply { add(logicalPos + 1, track) }
            else -> currentQueueTracks + track
        }
        // If the queue was empty, the user tapped "Play next" with nothing
        // playing — they expect the song to actually start, not just sit
        // silently in a queue they can't see. Prepare and play.
        if (wasEmpty) {
            controller.prepare()
            controller.play()
        }
    }

    override suspend fun addToQueue(track: Track) {
        val controller = ensureController() ?: return
        val wasEmpty = controller.mediaItemCount == 0
        val streamingOn = streamingPreference.current()
        // Single-track resolve — no parallelism needed, semaphore size 1.
        val semaphore = Semaphore(1)
        val mediaItem = resolveTrackToMediaItem(track, semaphore, streamingOn) ?: return
        controller.addMediaItem(mediaItem)
        currentQueueTracks = if (wasEmpty) listOf(track) else currentQueueTracks + track
        if (wasEmpty) {
            controller.prepare()
            controller.play()
        }
    }

    override suspend fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val controller = ensureController() ?: return
        val wasEmpty = controller.mediaItemCount == 0
        val streamingOn = streamingPreference.current()
        val semaphore = Semaphore(STREAM_RESOLVE_PARALLELISM)
        val beforeCount = controller.mediaItemCount
        Log.d(TAG, "addToQueue(batch) start: ${tracks.size} tracks, controller.mediaItemCount=$beforeCount")
        // Parallel-resolve; preserve input order so the user's queue matches
        // the order they tapped Add-to-Queue.
        val resolved = coroutineScope {
            tracks.map { track ->
                async { resolveTrackToMediaItem(track, semaphore, streamingOn) }
            }.awaitAll()
        }.filterNotNull()
        Log.d(TAG, "addToQueue(batch) resolved ${resolved.size}/${tracks.size} tracks")
        // Append ALL requested tracks (not just the resolved subset) to the
        // logical queue — unresolved ones are recovered just-in-time by
        // prefetchNextTrack / skipToQueueIndex, and the sheet should show them.
        currentQueueTracks = if (wasEmpty) tracks else currentQueueTracks + tracks
        if (resolved.isEmpty()) return
        controller.addMediaItems(resolved)
        Log.d(TAG, "addToQueue(batch) after addMediaItems: controller.mediaItemCount=${controller.mediaItemCount}")
        if (wasEmpty) {
            controller.prepare()
            controller.play()
        }
    }

    override suspend fun toggleShuffle() {
        val controller = ensureController() ?: return
        controller.sendCustomCommand(
            SessionCommand(StashPlaybackService.COMMAND_TOGGLE_SHUFFLE, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    override suspend fun cycleRepeatMode() {
        val controller = ensureController() ?: return
        controller.sendCustomCommand(
            SessionCommand(StashPlaybackService.COMMAND_CYCLE_REPEAT, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    /**
     * `true` when the queue the UI displays is the logical queue (see
     * [QueueDisplay.compute]) — in which case the indices arriving from the
     * queue sheet are LOGICAL indices and must be translated to timeline
     * slots by track id. Must mirror the predicate inside [updateState]
     * exactly, or the two index spaces silently diverge.
     */
    private fun logicalDisplayActive(controller: MediaController): Boolean {
        val id = controller.currentMediaItem?.mediaMetadata?.extras
            ?.getLong(EXTRA_TRACK_ID, -1L) ?: -1L
        return id > 0L && currentQueueTracks.any { it.id == id }
    }

    /** Timeline slot holding [trackId], or -1 when the track was dropped by
     * the fast-lane background fill and has no MediaItem yet. */
    private fun timelineIndexOfTrackId(controller: MediaController, trackId: Long): Int {
        for (i in 0 until controller.mediaItemCount) {
            val item = controller.getMediaItemAt(i)
            val id = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)
                ?: item.mediaId.toLongOrNull()
            if (id == trackId) return i
        }
        return -1
    }

    override suspend fun removeFromQueue(index: Int) {
        val controller = ensureController() ?: return
        if (logicalDisplayActive(controller)) {
            val logical = currentQueueTracks
            val target = logical.getOrNull(index) ?: return
            currentQueueTracks = logical.filterIndexed { i, _ -> i != index }
            val timelineIdx = timelineIndexOfTrackId(controller, target.id)
            if (timelineIdx >= 0) {
                controller.removeMediaItem(timelineIdx)
            } else {
                // Track had no timeline slot — no controller event will fire,
                // so push the new logical queue to the UI ourselves.
                updateState(controller)
            }
            return
        }
        if (index in 0 until controller.mediaItemCount) {
            controller.removeMediaItem(index)
        }
    }

    override suspend fun moveInQueue(from: Int, to: Int) {
        val controller = ensureController() ?: return
        if (logicalDisplayActive(controller)) {
            val logical = currentQueueTracks
            if (from !in logical.indices || to !in logical.indices || from == to) return
            val mutable = logical.toMutableList()
            val moved = mutable.removeAt(from)
            mutable.add(to, moved)
            currentQueueTracks = mutable

            val timelineFrom = timelineIndexOfTrackId(controller, moved.id)
            if (timelineFrom >= 0) {
                val timelineIdsWithoutMoved = buildList {
                    for (i in 0 until controller.mediaItemCount) {
                        if (i == timelineFrom) continue
                        val item = controller.getMediaItemAt(i)
                        add(
                            item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)
                                ?: item.mediaId.toLongOrNull() ?: -1L
                        )
                    }
                }
                val timelineTo =
                    QueueDisplay.moveTimelineTarget(mutable, to, timelineIdsWithoutMoved)
                if (timelineFrom != timelineTo) {
                    controller.moveMediaItem(timelineFrom, timelineTo)
                } else {
                    updateState(controller)
                }
            } else {
                updateState(controller)
            }
            return
        }
        val count = controller.mediaItemCount
        if (from in 0 until count && to in 0 until count && from != to) {
            controller.moveMediaItem(from, to)
        }
    }

    override suspend fun skipToQueueIndex(index: Int) {
        val controller = ensureController() ?: return
        if (logicalDisplayActive(controller)) {
            val target = currentQueueTracks.getOrNull(index) ?: return
            val timelineIdx = timelineIndexOfTrackId(controller, target.id)
            if (timelineIdx >= 0) {
                controller.seekToDefaultPosition(timelineIdx)
            } else {
                // The tapped queue row was dropped from the timeline by the
                // fast-lane fill (unresolved stream track). Route through
                // setQueue so the full resolve machinery — slow yt-dlp lane,
                // antra, the supersede race guard, the failure toast — does
                // its job. Same logical queue, new start anchor.
                setQueue(currentQueueTracks, index)
            }
            return
        }
        if (index in 0 until controller.mediaItemCount) {
            controller.seekToDefaultPosition(index)
        }
    }

    /**
     * Called by the MusicRepository.trackDeletions collector. Removes every
     * queue entry whose Media3 extras carry [deletedTrackId]. Operates
     * high-to-low so earlier indices stay valid while the loop runs.
     *
     * If the currently-playing item is removed, Media3 auto-advances to the
     * next queue entry (or stops the player if we've emptied the queue) —
     * no manual `stop()` or `seekToNextMediaItem()` needed.
     *
     * No-op when the controller hasn't been initialised yet (user deleted
     * a track before ever hitting play this session).
     */
    private fun evictTrackFromQueue(deletedTrackId: Long) {
        val controller = controllerDeferred ?: return
        currentQueueTracks = currentQueueTracks.filterNot { it.id == deletedTrackId }
        var removedFromTimeline = false
        for (i in controller.mediaItemCount - 1 downTo 0) {
            val item = controller.getMediaItemAt(i)
            val queuedId = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)
                ?: item.mediaId.toLongOrNull()
            if (queuedId == deletedTrackId) {
                controller.removeMediaItem(i)
                removedFromTimeline = true
            }
        }
        // A logical-only eviction fires no controller event — refresh the
        // published queue ourselves so the sheet drops the deleted track.
        if (!removedFromTimeline) updateState(controller)
    }

    // ---- Streaming routing ----

    override suspend fun playTrack(track: Track): StreamRoutingResult {
        val entity = trackDao.getById(track.id) ?: track.toEntity()
        val result = buildMediaItemForTrack(entity)
        if (result is StreamRoutingResult.Item) {
            // Single-track play replaces the whole queue — the logical queue
            // must follow, or a stale playlist list would keep hijacking the
            // queue display whenever this track happens to be in it.
            currentQueueTracks = listOf(track)
            playSingleMediaItem(result.mediaItem)
        }
        return result
    }

    override suspend fun playFromStream(item: TrackItem): StreamRoutingResult {
        // Idempotency guard #1 (already-playing): if the controller's
        // current MediaItem is THIS track and is in an active state,
        // skip. Mirrors the preview path's original guard.
        val targetMediaId = item.videoId.hashCode().toLong().toString()
        val controller = controllerDeferred
        if (controller != null) {
            val currentId = controller.currentMediaItem?.mediaId
            val state = controller.playbackState
            val activeStates = setOf(Player.STATE_BUFFERING, Player.STATE_READY)
            if (currentId == targetMediaId && state in activeStates) {
                return StreamRoutingResult.Item(controller.currentMediaItem!!)
            }
        }

        // Idempotency guard #2 (in-flight resolve): the user may tap N
        // times before the FIRST resolve has completed — at that point
        // the controller still shows the previous track, so guard #1
        // misses. Track in-flight videoIds in a synchronised set; rapid
        // duplicate taps short-circuit until the original resolve
        // finishes. Without this, 30 rapid taps = 30 separate resolves
        // and 30 setMediaItem calls.
        synchronized(inFlightStreamingTaps) {
            if (item.videoId in inFlightStreamingTaps) {
                return StreamRoutingResult.Deduped
            }
            inFlightStreamingTaps.add(item.videoId)
        }
        try {
            return playFromStreamInner(item)
        } finally {
            synchronized(inFlightStreamingTaps) {
                inFlightStreamingTaps.remove(item.videoId)
            }
        }
    }

    private val inFlightStreamingTaps = mutableSetOf<String>()

    private suspend fun playFromStreamInner(item: TrackItem): StreamRoutingResult {

        // Search-tab tap: no library row yet, so synthesize a transient
        // TrackEntity carrying only the fields buildMediaItemForTrack
        // reads. isDownloaded = false routes us straight into the
        // streaming branch.
        // Synthetic stable ID derived from videoId so the StreamUrlCache key
        // and the MediaItem.mediaId both differ between tracks. The previous
        // id=0L collapsed every search-tap stream onto a single cache key:
        // first tap cached, second tap returned the FIRST track's URL and
        // Media3 no-op'd setMediaItem on the matching mediaId. Repeat taps
        // of the same videoId still hit the cache (intended TTL behaviour).
        val transient = TrackEntity(
            id = item.videoId.hashCode().toLong(),
            title = item.title,
            artist = item.artist,
            album = item.album ?: "",
            durationMs = (item.durationSeconds * 1000).toLong(),
            isDownloaded = false,
            isStreamable = true,
            albumArtUrl = item.thumbnailUrl,
            // Search results carry a YT videoId — propagate it so the
            // YouTube fallback resolver can extract directly when Qobuz
            // doesn't have the track. Without this the transient row's
            // youtubeId stays null and YouTubeStreamResolver bails.
            youtubeId = item.videoId,
        )
        val result = buildMediaItemForTrack(transient)
        if (result is StreamRoutingResult.Item) {
            // Single-item replacement: drop the stale logical queue so the
            // queue display falls back to the (one-item) timeline.
            currentQueueTracks = emptyList()
            playSingleMediaItem(result.mediaItem)
        }
        return result
    }

    /**
     * Streaming-routing decision tree. The ordering matters:
     *
     * 1. Local file present + actually on disk → play it. Cheap, no
     *    network, works in airplane mode. Always preferred even when
     *    streaming is enabled — caching what you already have is free.
     * 2. Streaming pref off → [StreamRoutingResult.OfflineMode]. The
     *    track is theoretically streamable but the user has opted out.
     * 3. No validated internet → [StreamRoutingResult.NoConnectivity].
     *    Includes airplane mode, captive-portal-not-yet-accepted, and
     *    any other "associated but no real internet" state.
     * 4. Cellular + cellular pref off → [StreamRoutingResult.CellularRefused].
     *    The user has a data plan they want to protect.
     * 5. URL cache hit → use the cached signed URL.
     * 6. Cache miss → resolve via Kennyy and cache the result. Resolver
     *    null = no match in the proxy's catalog → [NotAvailable].
     *
     * Note: the `is_streamable` column is no longer consulted here.
     * AvailabilityCheckWorker (which set that flag) was removed; Kennyy
     * is now the sole source of truth on whether a track has a stream URL.
     * If `streamResolver.resolve()` returns null, we surface NotAvailable
     * at step 6 — no need to pre-gate on a stale flag.
     */
    internal suspend fun buildMediaItemForTrack(
        track: TrackEntity,
        allowYouTube: Boolean = true,
        allowYtDlp: Boolean = true,
    ): StreamRoutingResult {
        val localPath = track.filePath
        if (track.isDownloaded && !localPath.isNullOrBlank() && filePathExistsOnDisk(localPath)) {
            val uri = if (localPath.startsWith("/")) Uri.parse("file://$localPath") else Uri.parse(localPath)
            return StreamRoutingResult.Item(
                MediaItem.Builder()
                    .setMediaId(track.id.toString())
                    .setUri(uri)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(track.title)
                            .setArtist(track.artist)
                            .setAlbumTitle(track.album)
                            .setArtworkUri(
                                (track.albumArtPath ?: track.albumArtUrl)?.let { Uri.parse(it) }
                            )
                            .setExtras(Bundle().apply {
                                putLong(EXTRA_TRACK_ID, track.id)
                                track.youtubeId?.let { putString(EXTRA_TRACK_YOUTUBE_ID, it) }
                                if (track.durationMs > 0) putLong(EXTRA_TRACK_DURATION_MS, track.durationMs)
                                putBoolean(EXTRA_TRACK_IS_STREAMABLE, track.isStreamable)
                            })
                            .build()
                    )
                    .build()
            )
        }
        if (!streamingPreference.current()) return StreamRoutingResult.OfflineMode
        if (!connectivity.isConnected()) return StreamRoutingResult.NoConnectivity
        if (connectivity.isCellular() && !streamingPreference.streamOnCellular.first()) {
            return StreamRoutingResult.CellularRefused
        }

        val cached = streamUrlCache.get(track.id)
        val stream = cached ?: streamResolver.resolve(
            track,
            allowYouTube = allowYouTube,
            allowYtDlp = allowYtDlp,
        )?.also { resolved ->
            // Don't poison the shared cache with a PROVISIONAL lossy fallback.
            // The queue-wide background fill resolves with allowYtDlp = false
            // (InnerTube-only, no slow yt-dlp on the critical path). During a
            // kennyy/squid outage that speculative call can fall through to a
            // lossy YouTube URL. Caching it would make the next-up prefetch and
            // a later foreground tap — both of which run with allowYtDlp = true
            // — defer to the cached youtube entry and never re-attempt the
            // Qobuz proxies (which may have recovered), so the user hears AAC
            // when FLAC was available. A YouTube result from a full-fat
            // (allowYtDlp = true) resolve means the track is genuinely
            // lossless-less: cache it. Lossless results (kennyy/squid) always
            // cache — best available regardless of path.
            val provisionalLossyFallback =
                !allowYtDlp && resolved.origin == YouTubeStreamResolver.ORIGIN
            if (!provisionalLossyFallback) {
                streamUrlCache.put(track.id, resolved)
            }
        } ?: return StreamRoutingResult.NotAvailable

        // YouTube *video* thumbnails (i.ytimg.com/vi/...) leak into
        // album_art_url for both YOUTUBE-sourced rows AND for Spotify
        // rows that the sync de-duped against a YT match. Source alone
        // can't tell us which rows have bad art — check the URL itself.
        // We also fill blank rows. Proper YT Music catalog art
        // (lh3.googleusercontent.com) is left alone; Spotify scdn URLs
        // are left alone. Fire-and-forget; never block playback on a
        // cosmetic DB write.
        val betterArt = stream.coverArtUrl
        val currentArt = track.albumArtUrl
        val needsUpgrade = currentArt.isNullOrBlank() ||
            com.stash.core.common.ArtUrlUpgrader.isYouTubeVideoThumbnail(currentArt)
        if (betterArt != null && needsUpgrade && betterArt != currentArt) {
            scope.launch { trackDao.updateAlbumArtUrl(track.id, betterArt) }
        }
        val displayArtUrl = if (betterArt != null && needsUpgrade) {
            betterArt
        } else {
            track.albumArtPath ?: currentArt
        }

        return StreamRoutingResult.Item(
            MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(Uri.parse(stream.url))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .setArtworkUri(
                            displayArtUrl?.let { Uri.parse(it) }
                        )
                        .setExtras(Bundle().apply {
                            putLong(EXTRA_TRACK_ID, track.id)
                            track.youtubeId?.let { putString(EXTRA_TRACK_YOUTUBE_ID, it) }
                            if (track.durationMs > 0) putLong(EXTRA_TRACK_DURATION_MS, track.durationMs)
                            putBoolean(EXTRA_TRACK_IS_STREAMABLE, track.isStreamable)
                            // Surface the actual format Qobuz served so Now Playing
                            // shows "FLAC · 24-bit/96 kHz" instead of the stale Room
                            // default ("opus") that streaming-only rows carry forever.
                            stream.codec?.let { putString(EXTRA_STREAM_CODEC, it) }
                            stream.bitsPerSample?.let { putInt(EXTRA_STREAM_BIT_DEPTH, it) }
                            stream.sampleRateHz?.let { putInt(EXTRA_STREAM_SAMPLE_RATE, it) }
                            stream.bitrateKbps?.let { putInt(EXTRA_STREAM_BITRATE, it) }
                            stream.origin?.let { putString(EXTRA_STREAM_ORIGIN, it) }
                        })
                        .build()
                )
                .build()
        )
    }

    /**
     * Helper used by [playTrack] and [playFromStream]: replace the queue
     * with a single MediaItem and start playback. Mirrors the prepare()
     * + play() pattern used by [setQueue].
     *
     * Also clears the library-shuffle armed state since the user has
     * navigated into a specific track — same invariant as [setQueue].
     */
    private suspend fun playSingleMediaItem(mediaItem: MediaItem) {
        libraryShuffleActive = false
        librarySnapshot = emptyList()
        val controller = ensureController() ?: return
        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.play()
    }

    /**
     * Lossy mapping from the domain [Track] back to a [TrackEntity] for
     * the routing decision tree. Used only when [trackDao.getById] returns
     * null — i.e. the track was resolved from a non-Room source. Carries
     * the routing-relevant fields ([isDownloaded], [filePath],
     * [isStreamable]) and the metadata fields the MediaItem builder reads.
     *
     * [Track] doesn't currently carry [isStreamable]; pessimistically
     * defaults to `false` here, which is safe — if a Track lookup misses
     * Room and isn't already downloaded, treating it as not-streamable
     * surfaces a [NotAvailable] rather than mysteriously falling through.
     */
    private fun Track.toEntity(): TrackEntity = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        filePath = filePath,
        isDownloaded = isDownloaded,
        isStreamable = false,
        albumArtUrl = albumArtUrl,
        albumArtPath = albumArtPath,
        isrc = isrc,
    )

    // ---- Internals ----

    /**
     * Lazily builds and connects a [MediaController] to [StashPlaybackService].
     * Returns the connected controller or null on failure.
     */
    private suspend fun ensureController(): MediaController? {
        controllerDeferred?.let { return it }

        return try {
            val sessionToken = SessionToken(
                context,
                ComponentName(context, StashPlaybackService::class.java),
            )
            val controller = MediaController.Builder(context, sessionToken)
                .buildAsync()
                .await()

            controller.addListener(playerListener)
            controllerDeferred = controller
            // Sync initial state
            updateState(controller)
            controller
        } catch (e: Exception) {
            null
        }
    }

    /** Listener that forwards Media3 player events into [_playerState]. */
    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val controller = controllerDeferred ?: return
            // Defense in depth: the existing onPlayerError recovery catches
            // PlaybackException-driven failures, but some failure modes (audio
            // offload sink stalls before we removed offload, plus any future
            // codec/format edge case) can leave the player in STATE_IDLE on the
            // next track WITHOUT firing onPlayerError. The user-visible symptom
            // is "next song appears, play button does nothing." A single
            // prepare() call is a no-op when the player is already READY and
            // rescues the IDLE case automatically.
            if (controller.playbackState == Player.STATE_IDLE && controller.currentMediaItem != null) {
                Log.w(TAG, "onMediaItemTransition landed in STATE_IDLE — defensive prepare()")
                controller.prepare()
            }
            // v0.9.37 (Mixes Stream-Only Task 6): silent-skip stream-only
            // tracks while offline. Connectivity is dynamic — the user may
            // toggle airplane mid-playback — so we can't filter the queue
            // at build time; instead we observe each transition and skip
            // forward when the now-current item is stream-only but the
            // device can no longer reach it. Naturally tail-recursive
            // through the listener: a single `seekToNextMediaItem` call
            // re-fires `onMediaItemTransition` with the next item, which
            // re-runs this guard until either a playable item is reached
            // or `hasNextMediaItem()` returns false (handled in
            // `maybeSkipOfflineStreamOnly`). No manual loop needed; this
            // matches the existing `recoverOrStop` re-entrancy pattern.
            //
            // Gate on REASON_AUTO so only natural queue advancement triggers
            // the silent-skip. Explicit user skips (REASON_SEEK), repeat-one
            // wraparounds (REASON_REPEAT), and code-driven queue changes
            // (REASON_PLAYLIST_CHANGED) bypass it — those surfaces have
            // their own gating (tap-time guard in Task 5, user intent for
            // repeat, consumer choice for queue mutations).
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                mediaItem?.let { maybeSkipOfflineStreamOnly(controller, it) }
            }
            updateState(controller)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            controllerDeferred?.let { updateState(it) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                cascadeGuard.onPlaybackStarted()
            }
            controllerDeferred?.let { updateState(it) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            controllerDeferred?.let { updateState(it) }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            controllerDeferred?.let { updateState(it) }
        }

        /**
         * Fires whenever the queue itself changes — adds, removes, moves.
         * Without this, addMediaItem / removeMediaItem / moveMediaItem
         * mutate the underlying timeline but the UI's queue view (built
         * from _playerState) never sees the change. Symptom: "I tapped
         * Play Next but the song doesn't appear in the queue."
         */
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            controllerDeferred?.let { updateState(it) }
        }

        /**
         * Auto-recover from playback failures (issue #15).
         *
         * Without this override, ExoPlayer's default behaviour on
         * [PlaybackException] is to drop to `STATE_IDLE` and stay there —
         * the UI sees the auto-advance fire (`onMediaItemTransition`
         * delivers the next track) but playback never actually begins
         * because the player needs `prepare()` to re-enter `STATE_READY`.
         * Symptom: next song appears in Now Playing, play button does
         * nothing, until the user manually skips twice.
         *
         * The recovery pattern below mirrors what a manual "skip next"
         * does under the hood. We log the failing track + reason for
         * triage (often a missing file_path after a backfill swap, a
         * transient streaming hiccup, or a codec edge case), then seek
         * past the broken item and re-prepare. If we're at the end of
         * the queue we stop gracefully rather than loop on errors.
         */
        override fun onPlayerError(error: PlaybackException) {
            val controller = controllerDeferred
            val current = controller?.currentMediaItem
            val failingTitle = current?.mediaMetadata?.title?.toString()
            val scheme = current?.localConfiguration?.uri?.scheme
            val streamOrigin = current?.mediaMetadata?.extras?.getString(EXTRA_STREAM_ORIGIN)

            // The error code alone is NOT enough to decide recovery. A streamed
            // track that gets a 200 serving empty/garbage bytes fails with
            // ERROR_CODE_PARSING_CONTAINER_MALFORMED — a "non-IO" code. The old
            // handler skipped every non-IO error unconditionally, bypassing the
            // cascade guard, so a degraded source machine-gunned the whole queue
            // (hundreds of skip-nexts in seconds). Decide on the item's URI
            // scheme instead: only genuinely-local files are per-track skips.
            when (classifyPlaybackError(scheme)) {
                PlaybackErrorPolicy.LOCAL_SKIP -> {
                    // A downloaded/local file failed to decode (corrupt file or
                    // codec edge case). Per-track: skip it. Not a backend outage,
                    // so it must not arm the streaming cascade.
                    Log.w(
                        TAG,
                        "onPlayerError: '$failingTitle' code=${error.errorCode} " +
                            "(${error.errorCodeName}) — skip-next (local)",
                        error,
                    )
                    controller?.recoverOrStop()
                }
                PlaybackErrorPolicy.STREAMING_CASCADE -> {
                    // A streamed item failed — 403/network OR a 200 that served
                    // empty/malformed bytes. Both are stream-source failures, so
                    // they go through the cascade guard: recover (skip) until the
                    // threshold, then HALT (pause + notify) instead of skip-storming
                    // the queue. `origin` is logged so a diagnostics capture reveals
                    // which source (kennyy/squid/youtube) served the bad URL.
                    val verdict = cascadeGuard.onError()
                    Log.w(
                        TAG,
                        "onPlayerError: '$failingTitle' code=${error.errorCode} " +
                            "(${error.errorCodeName}) streaming origin=$streamOrigin — verdict=$verdict",
                        error,
                    )
                    when (val v = verdict) {
                        StreamErrorCascadeGuard.Verdict.Recover -> controller?.recoverOrStop()
                        is StreamErrorCascadeGuard.Verdict.Halt -> {
                            controller?.pause()
                            _streamingHaltedEvents.tryEmit(
                                StreamingHaltedEvent(
                                    failingTitle = failingTitle,
                                    consecutiveErrorCount = v.consecutiveErrors,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun MediaController.recoverOrStop() {
        if (hasNextMediaItem()) {
            seekToNextMediaItem()
            prepare()
            play()
        } else {
            // End of queue — let the player stop cleanly rather than
            // looping on the same broken item.
            stop()
        }
    }

    /**
     * v0.9.37: silent-skip stream-only tracks that the queue would play
     * but the user can't reach because the device is offline. Invoked
     * from [Player.Listener.onMediaItemTransition] for every queue
     * advance (whether driven by auto-advance, user skip, or a previous
     * silent-skip).
     *
     * **Re-entrancy:** uses Media3's natural tail-recursion through the
     * listener — a single [MediaController.seekToNextMediaItem] call
     * re-fires `onMediaItemTransition`, which re-invokes this function
     * with the new current item. No manual loop, no re-entrancy flag.
     * Mirrors the existing [recoverOrStop] pattern used by `onPlayerError`.
     *
     * **Default safety:** when [EXTRA_TRACK_IS_STREAMABLE] is absent
     * (legacy items, downloaded tracks, items built outside
     * [buildMediaItemForTrack]), `getBoolean(..., false)` returns false
     * and we treat the item as not-streamable → don't skip, let it play.
     * Downloaded items always play regardless of network state.
     *
     * **End of queue:** when no next item exists, the player is paused
     * (rather than [MediaController.stop]ed — pause preserves the queue
     * for when connectivity returns) and a Snackbar is emitted via
     * [_userMessages] so the user understands why the music stopped.
     */
    private fun maybeSkipOfflineStreamOnly(controller: MediaController, item: MediaItem) {
        if (connectivity.isConnected()) return
        val isStreamable = item.mediaMetadata.extras?.getBoolean(EXTRA_TRACK_IS_STREAMABLE, false) == true
        if (!isStreamable) return
        val failingTitle = item.mediaMetadata.title?.toString()
        if (controller.hasNextMediaItem()) {
            Log.i(
                TAG,
                "silent-skip: offline + stream-only '$failingTitle' — advancing to next item",
            )
            controller.seekToNextMediaItem()
        } else {
            Log.i(
                TAG,
                "silent-skip: offline + stream-only '$failingTitle' — end of queue, pausing",
            )
            controller.pause()
            _userMessages.tryEmit("End of offline Mix")
        }
    }

    /**
     * Reads the current state from the [MediaController] and publishes it to
     * [_playerState]. Also persists the current position via [PlaybackStateStore].
     */
    /**
     * `true` while the active track is loading from the user's point of
     * view: ExoPlayer is genuinely buffering, OR a [setQueue] tapped-track
     * resolve is still in flight ([tapResolveEpoch] matches the current
     * [setQueueEpoch] — a stale epoch from a superseded call doesn't count).
     */
    internal fun computeIsBuffering(controllerBuffering: Boolean): Boolean =
        controllerBuffering ||
            (tapResolveEpoch != -1L && tapResolveEpoch == setQueueEpoch)

    /**
     * `true` when the active track is being streamed rather than read from
     * local storage — drives the Now Playing wifi/streaming indicator. An
     * http(s) [scheme] is the obvious case (kennyy/squid/youtube), but a
     * non-null [streamOrigin] also counts: antra plays its FLAC from a
     * `file://` cache file yet is a stream. Downloaded rows play `file://`
     * with NO stream origin, so they correctly read as not-streaming.
     */
    internal fun computeIsStreaming(scheme: String?, streamOrigin: String?): Boolean =
        scheme == "http" || scheme == "https" || streamOrigin != null

    private fun updateState(controller: MediaController) {
        val currentItem = controller.currentMediaItem
        val track = currentItem?.toTrack()
        val timelineQueue = buildList {
            for (i in 0 until controller.mediaItemCount) {
                add(controller.getMediaItemAt(i).toTrack())
            }
        }

        // Display the LOGICAL queue (the Track list handed to setQueue), not
        // the raw timeline. The fast-lane background fill drops stream tracks
        // it can't resolve (antra is excluded from fill entirely), so during
        // antra streaming the timeline is a sparse downloaded-only subset
        // while playback follows the logical queue via prefetch insertion —
        // the timeline made "Up Next" lie. Falls back to the timeline when
        // the current item isn't in the logical queue (single-track play,
        // restored session). See [QueueDisplay].
        val currentTrackId = currentItem?.mediaMetadata?.extras?.getLong(EXTRA_TRACK_ID, -1L)
        val display = QueueDisplay.compute(
            timelineQueue = timelineQueue,
            timelineIndex = controller.currentMediaItemIndex,
            logicalQueue = currentQueueTracks,
            currentTrackId = currentTrackId,
        )

        // Streaming detection: a track is "streaming" when it came from a
        // stream resolver, not purely when its URI is http(s). Kennyy/squid/
        // youtube serve http(s) URLs; antra fetches its lossless FLAC to a
        // LOCAL cache file and plays file://, but is every bit a stream — the
        // EXTRA_STREAM_ORIGIN it carries (set only on stream-resolved items,
        // never on downloaded rows) is the reliable signal. Without it antra
        // rendered like a downloaded track (no wifi/streaming indicator).
        val scheme = currentItem?.localConfiguration?.uri?.scheme
            ?: currentItem?.requestMetadata?.mediaUri?.scheme
        val streamOrigin = currentItem?.mediaMetadata?.extras?.getString(EXTRA_STREAM_ORIGIN)
        val isStreaming = computeIsStreaming(scheme, streamOrigin)

        val newState = PlayerState(
            currentTrack = track,
            isPlaying = controller.isPlaying,
            positionMs = controller.currentPosition.coerceAtLeast(0),
            durationMs = controller.duration.coerceAtLeast(0),
            isShuffleEnabled = controller.shuffleModeEnabled,
            repeatMode = controller.repeatMode.toRepeatMode(),
            queue = display.queue,
            currentIndex = display.currentIndex,
            isStreaming = isStreaming,
            // STATE_BUFFERING covers normal buffering AND the in-data-source
            // 403→yt-dlp re-resolve (RefreshingDataSource blocks the loader
            // thread while a skipped-to YouTube placeholder is recovered).
            // The tapped-track resolve gap (yt-dlp ~11s, antra 60-120s) is
            // covered by the [tapResolveEpoch] term inside computeIsBuffering
            // — setQueue's optimistic emit alone gets stomped by the first
            // controller event from the still-playing previous queue.
            isBuffering = computeIsBuffering(
                controller.playbackState == Player.STATE_BUFFERING,
            ),
        )
        _playerState.value = newState

        // Persist position for resume-on-restart (fire and forget)
        if (track != null) {
            scope.launch {
                playbackStateStore.savePosition(
                    trackId = track.id,
                    positionMs = newState.positionMs,
                    queueIndex = newState.currentIndex,
                )
            }
        }

        // Persist the full queue so Bluetooth/Android Auto resumption can
        // restore it (next/prev working) rather than a single track. Only
        // re-save when the queue contents or shuffle state change — not on
        // every position tick. Persists the LOGICAL queue (newState.queue,
        // same list the saved currentIndex points into) — the raw timeline
        // is a sparse resolved-only subset during streaming and would
        // resume into a queue with most tracks missing.
        val queueIds = newState.queue.map { it.id }
        if (queueIds != lastSavedQueueIds || newState.isShuffleEnabled != lastSavedShuffle) {
            lastSavedQueueIds = queueIds
            lastSavedShuffle = newState.isShuffleEnabled
            if (queueIds.isNotEmpty()) {
                scope.launch {
                    playbackStateStore.saveQueue(queueIds, newState.isShuffleEnabled)
                }
            }
        }
    }

    // ---- Mappers ----

    companion object {
        private const val TAG = "StashPlayer"
        private const val POSITION_UPDATE_INTERVAL_MS = 250L

        /** Auto-grow fires once the remaining queue tail drops below this many tracks. */
        private const val LIBRARY_SHUFFLE_GROW_THRESHOLD = 5

        /** How many tracks each grow appends. Big enough to outpace a fast-skipping user. */
        private const val LIBRARY_SHUFFLE_GROW_BATCH = 50

        /**
         * Max in-flight Kennyy resolves while building a streaming queue.
         * Higher = faster queue-build but risks overwhelming the Qobuz
         * proxy (it's a community resource, not an SLA endpoint). 16 was
         * comfortably handled by a 2.6k-track Liked Songs queue in
         * dogfood testing.
         */
        private const val STREAM_RESOLVE_PARALLELISM = 16

        /**
         * Tracks per background-fill batch. Each batch fires off a
         * parallel resolve fan-out up to [STREAM_RESOLVE_PARALLELISM] in
         * flight. Same value as the resolve cap so one batch saturates
         * the semaphore — keeps proxy pressure consistent.
         */
        private const val BACKGROUND_FILL_BATCH = 16

        /** Refresh prefetch if cached URL has less than this margin remaining. */
        private const val PREFETCH_FRESH_THRESHOLD_MS = 60_000L
    }

    /**
     * Converts a domain [Track] into a Media3 [MediaItem] suitable for ExoPlayer.
     * The local file path (if present) is set as the playback URI; album art is
     * carried as [MediaMetadata.artworkUri].
     */
    private fun Track.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(
                (albumArtPath ?: albumArtUrl)?.toUri()
            )
            .setExtras(Bundle().apply {
                putLong(EXTRA_TRACK_ID, id)
                youtubeId?.let { putString(EXTRA_TRACK_YOUTUBE_ID, it) }
                if (durationMs > 0) putLong(EXTRA_TRACK_DURATION_MS, durationMs)
                putBoolean(EXTRA_TRACK_IS_STREAMABLE, isStreamable)
            })
            .build()

        // Ensure file:// scheme so StashPlaybackService's URI validation passes.
        val fileUri = filePath?.let { path ->
            if (path.startsWith("/")) "file://$path".toUri() else path.toUri()
        }

        val requestMetadata = MediaItem.RequestMetadata.Builder()
            .setMediaUri(fileUri)
            .build()

        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(fileUri)
            .setMediaMetadata(metadata)
            .setRequestMetadata(requestMetadata)
            .build()
    }

    /**
     * Best-effort reconstruction of a [Track] from a [MediaItem]'s metadata.
     * Only the fields carried through Media3 metadata are populated.
     */
    private fun MediaItem.toTrack(): Track {
        val meta = mediaMetadata
        val extras = meta.extras
        // v0.9.27: allow id=0 fallback for non-library tracks (e.g. search
        // previews with videoId strings). This makes the Like button and
        // other actions visible in Now Playing for non-library content.
        val trackId = extras?.getLong(EXTRA_TRACK_ID) ?: mediaId.toLongOrNull() ?: 0L

        // Streaming tracks carry the Qobuz-reported format in extras so the
        // Now Playing screen can show "FLAC · 24-bit/96 kHz" instead of the
        // Room row's default ("opus") that streaming-only library entries
        // inherit. Absent for downloaded tracks — those keep Room's truth.
        val streamCodec = extras?.getString(EXTRA_STREAM_CODEC)
        val streamBitDepth = extras?.getInt(EXTRA_STREAM_BIT_DEPTH, 0)?.takeIf { it > 0 }
        val streamSampleRate = extras?.getInt(EXTRA_STREAM_SAMPLE_RATE, 0)?.takeIf { it > 0 }
        val streamBitrate = extras?.getInt(EXTRA_STREAM_BITRATE, 0)?.takeIf { it > 0 }
        val streamOrigin = extras?.getString(EXTRA_STREAM_ORIGIN)

        return Track(
            id = trackId,
            title = meta.title?.toString() ?: "",
            artist = meta.artist?.toString() ?: "",
            album = meta.albumTitle?.toString() ?: "",
            albumArtUrl = meta.artworkUri?.toString(),
            durationMs = extras?.getLong(EXTRA_TRACK_DURATION_MS, 0L) ?: 0L,
            // For non-library tracks (id=0L), the mediaId is the YouTube
            // videoId. For streaming-engine tracks (synthetic non-zero id),
            // the videoId is carried explicitly in extras so downstream
            // code (Now Playing's like-state observation, ensure-persisted
            // upsert) can resolve identity without a real DB row
            // (issue #105 follow-up).
            youtubeId = extras?.getString(EXTRA_TRACK_YOUTUBE_ID)
                ?: if (trackId == 0L) mediaId else null,
            source = if (trackId == 0L) com.stash.core.model.MusicSource.YOUTUBE else com.stash.core.model.MusicSource.SPOTIFY,
            fileFormat = streamCodec ?: "opus",
            bitsPerSample = streamBitDepth,
            sampleRateHz = streamSampleRate,
            qualityKbps = streamBitrate ?: 0,
            streamOrigin = streamOrigin,
        )
    }
}
