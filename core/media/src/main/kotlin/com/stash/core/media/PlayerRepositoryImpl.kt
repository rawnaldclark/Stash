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
import com.stash.core.media.streaming.stashResolveUri
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
    private val castStateHolder: CastStateHolder,
    private val radioGenerator: com.stash.core.data.radio.RadioStationGenerator,
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

    /**
     * Last-known queue/timeline sizes, mirrored out of [updateState] for
     * [CrashDiagnostics]. Plain volatiles (not controller reads) because the
     * crash handler samples them from an arbitrary thread with the heap
     * possibly exhausted — MediaController is main-thread-only.
     */
    @Volatile private var lastKnownQueueSize = 0
    @Volatile private var lastKnownTimelineSize = 0

    // savePosition gate state — see the comment at the persist site in
    // [updateState]. Main-thread only (updateState runs on Main).
    private var lastSavedPosTrackId = Long.MIN_VALUE
    private var lastSavedPosQueueIndex = -1
    private var lastSavedPositionMs = Long.MIN_VALUE / 2
    private var lastObservedIsPlaying = false

    init {
        // OOM triage (#238/#239): stamp player scale into every crash report.
        com.stash.core.data.diagnostics.CrashDiagnostics.register("player") {
            "queue=$lastKnownQueueSize timeline=$lastKnownTimelineSize"
        }

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

        // Radio auto-grow watcher. Mirrors the library-shuffle grower: when a
        // station is armed and the queue nears the tail, append the next batch.
        scope.launch {
            playerState.collect { state ->
                if (!radioActive) return@collect
                val remaining = state.queue.size - state.currentIndex - 1
                if (remaining in 0 until RADIO_GROW_THRESHOLD) growRadio()
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
                .collect { idx ->
                    // Quality layer: upgrade the immediate next-up via the full
                    // chain (arcod/amz FLAC) so the common auto-advance never
                    // falls back to the cold LazyResolvingDataSource path.
                    prefetchNextTrack(idx)
                }
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

    /** Radio station state. `radioActive` arms the radio grow watcher; the
     *  session holds the generator's cursor/no-repeat state. Mutually exclusive
     *  with library shuffle — startRadio disarms shuffle and vice-versa. */
    @Volatile
    private var radioActive: Boolean = false

    @Volatile
    private var radioSession: com.stash.core.data.radio.RadioSession? = null

    private val _radioSeedLabel = MutableStateFlow<String?>(null)
    override val radioSeedLabel: StateFlow<String?> = _radioSeedLabel.asStateFlow()

    private val radioGrowMutex = Mutex()

    /**
     * The LOGICAL playback queue — the user-intended track order. Since the
     * full-timeline migration (2026-07-04) the controller timeline mirrors
     * this list 1:1 (every playable track gets a MediaItem at queue time;
     * stream tracks as stash-resolve:// placeholders), so it survives mainly
     * as the Track-typed source for the prefetch watcher, the queue display
     * ([QueueDisplay.compute]), and the logical-index queue ops
     * ([skipToQueueIndex]/[removeFromQueue]/[moveInQueue]).
     *
     * Every queue mutation path must keep it in sync: [setQueue] (replace),
     * [shuffleLibrary] (replace), [growLibraryShuffle] (append), [addNext]
     * (insert), [addToQueue] (append), [evictTrackFromQueue] (remove),
     * [playTrack]/[playFromStreamInner] (single/clear). A missed path
     * degrades gracefully: the display falls back to the timeline whenever
     * the playing track isn't in this list.
     */
    @Volatile
    // internal (not private) as a test seam — skip tests drive the logical
    // queue directly to exercise the timeline-frontier routing.
    internal var currentQueueTracks: List<Track> = emptyList()

    /** Serializes auto-grow operations so multiple state updates can't fan out. */
    private val growMutex = Mutex()

    /**
     * Cached Track view of the controller timeline, rebuilt in [updateState]
     * only when [timelineDirty] is set by `onTimelineChanged` (or the count
     * drifts). Keeps per-event state refreshes O(1) in queue size now that
     * the full timeline holds every queue track.
     */
    private var cachedTimelineQueue: List<Track> = emptyList()

    @Volatile
    private var timelineDirty: Boolean = true

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
     *   - "Loading stream on mobile data..." — a cellular stream resolve has taken long
     *     enough that the tap needs explicit feedback.
     *   - "Couldn't play this track right now." — [setQueue]'s tapped
     *     track failed every resolver, surfaced so the user knows the
     *     tap was received but the track is genuinely unavailable.
     *   - "End of offline Mix" — the auto-advance silent-skip walked off
     *     the end of the queue trying to find a playable item while the
     *     device was offline (v0.9.37).
     * Collected by the app scaffold for global playback snackbars and by
     * Now Playing for the full-player surface.
     */
    override val userMessages: kotlinx.coroutines.flow.SharedFlow<String> =
        _userMessages.asSharedFlow()

    /**
     * Track ids with a next-up prefetch resolve currently in flight. Dedups
     * the three prefetchNextTrack call sites so a single advance can't fan out
     * concurrent identical resolves (quota-burns a job-based source like
     * arcod). A [java.util.concurrent.ConcurrentHashMap]-backed set so adds are
     * atomic across the resolves running on the scope's dispatcher.
     */
    private val prefetchInFlight: MutableSet<Long> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

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
        val controller = ensureController() ?: return
        // Full timeline: ExoPlayer sees the whole queue, so the native seek is
        // always correct (shuffle order, repeat-all wraparound included).
        // hasNextMediaItem() is false only at the true end with repeat off —
        // the correct no-op.
        if (controller.hasNextMediaItem()) controller.seekToNextMediaItem()
    }

    override suspend fun skipPrevious() {
        cascadeGuard.onUserTransport()
        val controller = ensureController() ?: return
        if (controller.hasPreviousMediaItem()) controller.seekToPreviousMediaItem()
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
    private suspend fun setQueueInternal(
        tracks: List<Track>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        // Any explicit setQueue (playlist tap, single-song play, etc.) leaves
        // library-shuffle mode behind. Snapshot is cleared so a stale Track
        // list doesn't grow back into a different queue later. A radio station
        // also ends (the user picked something else).
        libraryShuffleActive = false
        librarySnapshot = emptyList()
        radioActive = false
        radioSession = null
        _radioSeedLabel.value = null

        val controller = ensureController() ?: return
        if (tracks.isEmpty()) return

        val streamingOn = streamingPreference.current()
        val safeStart = startIndex.coerceIn(0, tracks.size - 1)

        // Full timeline: EVERY playable track becomes a MediaItem now.
        // Downloaded → file://; stream → stash-resolve:// placeholder resolved
        // just-in-time by LazyResolvingDataSource at open(). Native next/prev/
        // repeat/shuffle are correct because ExoPlayer sees the whole queue.
        // Item building does per-track disk checks (filePathExistsOnDisk), so
        // it runs off the main thread; a 2.6k-track queue must not jank the UI.
        val playable = tracks.filter { track ->
            track.isDownloaded || (streamingOn && !track.isUnavailableForDisplay)
        }
        val items = withContext(Dispatchers.IO) {
            playable.map { it.toQueueMediaItem() }
        }
        if (items.isEmpty()) {
            _userMessages.tryEmit("Nothing in this queue is playable right now.")
        val semaphore = Semaphore(STREAM_RESOLVE_PARALLELISM)
        // Record this call's epoch so the resolve below can refuse to
        // apply its result if a newer setQueue has come in meanwhile.
        val myEpoch = ++setQueueEpoch
        val tappedTrack = tracks[safeStart]

        // Optimistic loading state. With an idle player the mini player is
        // hidden, so showing the tapped track + spinner is the only feedback
        // that the tap registered (a yt-dlp resolve takes ~11 s; arcod/amz can
        // be 30-50 s on a slow link). When a track IS loaded, an arbitrary tap
        // does NOT swap the display (that would turn the play/pause button into
        // a disabled spinner mid-song, hijacking still-playing audio).
        //
        // [optimisticDisplay] = true overrides that for an explicit forward/back
        // NAVIGATION (skip): the user asked to leave the current track, so we
        // immediately pause it, swap Now Playing to the target, and show the
        // spinner while it resolves — the skip feels instant even when the
        // resolve is slow. See [navigateToLogical].
        if (controller.currentMediaItem == null || optimisticDisplay) {
            if (optimisticDisplay) controller.pause()
            _playerState.value = _playerState.value.copy(
                currentTrack = tappedTrack,
                isPlaying = false,
                isBuffering = true,
            )
            // Keep the spinner alive across updateState() calls for the whole
            // resolve — see [tapResolveEpoch].
            tapResolveEpoch = myEpoch
        }

        val localPath = tappedTrack.filePath
        val tappedTrackHasPlayableLocal =
                tappedTrack.isDownloaded &&
                !localPath.isNullOrBlank() &&
                filePathExistsOnDisk(localPath)
        val tappedTrackNeedsStream =
            streamingOn && !tappedTrackHasPlayableLocal
        val resolvePending = AtomicBoolean(tappedTrackNeedsStream)
        if (tappedTrackNeedsStream) {
            scope.launch {
                delay(STREAM_LOADING_MESSAGE_DELAY_MS)
                if (resolvePending.get() && myEpoch == setQueueEpoch && connectivity.isCellular()) {
                    _userMessages.tryEmit("Loading stream on mobile data...")
                }
            }
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
        val startResult = try {
            resolveTrackToRoutingResult(
                tappedTrack,
                semaphore,
                streamingOn,
                allowYouTube = true,
                allowYtDlp = true,
            )
        } finally {
            resolvePending.set(false)
        }
        val startItem = (startResult as? StreamRoutingResult.Item)?.mediaItem

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
            userMessageFor(startResult)?.let { _userMessages.tryEmit(it) }
                ?: _userMessages.tryEmit("Couldn't play this track right now.")
            // Clear the optimistic spinner — nothing is going to play.
            tapResolveEpoch = -1L
            _playerState.value = _playerState.value.copy(isBuffering = false)
            return
        }
        // startIndex maps through the playable filter by track id.
        val startId = tracks[safeStart].id
        val startInPlayable = playable.indexOfFirst { it.id == startId }.coerceAtLeast(0)

        currentQueueTracks = playable
        controller.setMediaItems(items, startInPlayable, startPositionMs)
        controller.prepare()
        controller.play()

        Log.i(TAG, "setQueue: full timeline, ${items.size} items, start=$startInPlayable")

        // Warm the next-up URL so auto-advance never waits on a cold resolve
        // (the placeholder path is the cold-jump fallback, not the happy path).
        scope.launch { prefetchNextTrack(controller.currentMediaItemIndex) }
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

        // Fresh-cache check — avoid redundant resolve work when the URL is
        // good. Still upgrade the timeline slot before returning: the URL may
        // have been cached by LazyResolvingDataSource (cold jump), which can't
        // touch item metadata, so the placeholder would keep its unstamped
        // extras and Now Playing would read the "opus" fallback for it.
        val cached = streamUrlCache.get(next.id)
        val nowMs = System.currentTimeMillis()
        if (cached != null && cached.expiresAtMs > nowMs + PREFETCH_FRESH_THRESHOLD_MS) {
            refreshControllerMediaItem(controller, next, cached)
            return
        }

        // In-flight dedup. Three call sites fire prefetchNextTrack (the
        // currentIndex watcher + the two explicit kicks in setQueue/fill), and
        // the fresh-cache check above can't dedup CONCURRENT resolves of the
        // same next-up (none has cached yet). Without this guard a single
        // advance fans out up to 3 identical resolves — for a slow, job-based,
        // 50/hr-capped source like arcod that's 3 render jobs and 3× the quota
        // burn (verified on-device 2026-06-21). Claim the id; a racing call for
        // the same next-up returns immediately and lets the winner cache it.
        if (!prefetchInFlight.add(next.id)) return

        val t0 = System.currentTimeMillis()
        Log.d("LATDIAG", "prefetch-next-start id=${next.id} youtubeId=${next.youtubeId}")
        val entity = trackDao.getById(next.id) ?: next.toEntity()
        val resolved = try {
            // Full-fat resolve (allowYtDlp = true) for the single next-up track
            // during active playback, so auto-advance stays seamless during a
            // Qobuz-proxy outage even if it falls through to the YouTube path.
            if (connectivity.isCellular()) {
                streamResolver.resolve(entity, allowYouTube = true, allowYtDlp = true, preferFastStartup = true)
            } else {
                streamResolver.resolve(entity, allowYouTube = true, allowYtDlp = true)
            }
        } catch (ce: CancellationException) {
            prefetchInFlight.remove(next.id)
            throw ce
        } catch (e: Exception) {
            prefetchInFlight.remove(next.id)
            Log.w(TAG, "prefetch-next failed for id=${next.id}: ${e.message}")
            Log.d("LATDIAG", "prefetch-next-end id=${next.id} dt=${System.currentTimeMillis() - t0}ms outcome=throw:${e.javaClass.simpleName}")
            return
        }
        prefetchInFlight.remove(next.id)
        if (resolved == null) {
            Log.d("LATDIAG", "prefetch-next-end id=${next.id} dt=${System.currentTimeMillis() - t0}ms outcome=null")
            return
        }
        streamUrlCache.put(next.id, resolved)
        Log.d("LATDIAG", "prefetch-next-end id=${next.id} dt=${System.currentTimeMillis() - t0}ms outcome=url expiresAt=${resolved.expiresAtMs}")

        // Upgrade the next-up's timeline slot in place: the full-timeline queue
        // seeds every stream track as a stash-resolve:// placeholder, and this
        // swap gives it a real resolved URL + quality extras BEFORE ExoPlayer
        // reaches it — so the common auto-advance never blocks on the cold
        // LazyResolvingDataSource resolve (that path is the cold-jump fallback;
        // see docs/superpowers/plans/2026-07-04-full-timeline-lazy-resolve.md).
        // The URL is also in StreamUrlCache, so even an unswapped placeholder
        // opens instantly on a cache hit.
        refreshControllerMediaItem(controller, next, resolved)
    }

    /**
     * Swap the timeline slot matching [next] in place to the freshly-[resolved]
     * stream. Updates the URI AND the quality/origin extras — the slot is
     * usually a stash-resolve:// placeholder (or a stale earlier resolve), and
     * the prefetch upgrades it to the real lossless URL; without refreshing
     * [EXTRA_STREAM_ORIGIN]/codec/bit-depth Now Playing would keep showing the
     * stale badge even though the audio is now FLAC. Preserves mediaId and the
     * rest of the metadata. Returns `true` if the slot was found and refreshed;
     * `false` only when [next] isn't in the timeline (shouldn't happen with the
     * full timeline — the URL is still cached, so the placeholder plays fine).
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
                val newExtras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                    resolved.codec?.let { putString(EXTRA_STREAM_CODEC, it) }
                    resolved.bitsPerSample?.let { putInt(EXTRA_STREAM_BIT_DEPTH, it) }
                    resolved.sampleRateHz?.let { putInt(EXTRA_STREAM_SAMPLE_RATE, it) }
                    resolved.bitrateKbps?.let { putInt(EXTRA_STREAM_BITRATE, it) }
                    resolved.origin?.let { putString(EXTRA_STREAM_ORIGIN, it) }
                }
                val refreshed = item.buildUpon()
                    .setUri(resolved.url)
                    .setMediaMetadata(
                        item.mediaMetadata.buildUpon().setExtras(newExtras).build(),
                    )
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
        return (resolveTrackToRoutingResult(
            track = track,
            semaphore = semaphore,
            streamingOn = streamingOn,
            allowYouTube = allowYouTube,
            allowYtDlp = allowYtDlp,
        ) as? StreamRoutingResult.Item)?.mediaItem
    }

    private suspend fun resolveTrackToRoutingResult(
        track: Track,
        semaphore: Semaphore,
        streamingOn: Boolean,
        allowYouTube: Boolean = true,
        allowYtDlp: Boolean = true,
    ): StreamRoutingResult {
        val localPath = track.filePath
        if (track.isDownloaded && !localPath.isNullOrBlank() && filePathExistsOnDisk(localPath)) {
            return StreamRoutingResult.Item(track.toMediaItem())
        }
        if (!streamingOn) return StreamRoutingResult.OfflineMode

        return semaphore.withPermit {
            val entity = trackDao.getById(track.id) ?: track.toEntity()
            buildMediaItemForTrack(
                entity,
                allowYouTube = allowYouTube,
                allowYtDlp = allowYtDlp,
            )
        }
    }

    private fun userMessageFor(result: StreamRoutingResult): String? =
        when (result) {
            is StreamRoutingResult.Item -> null
            StreamRoutingResult.Deduped -> null
            StreamRoutingResult.NotAvailable -> "Couldn't find this track."
            StreamRoutingResult.OfflineMode -> "Turn on Online mode to stream this track."
            StreamRoutingResult.CellularRefused -> "Streaming on cellular is off in Settings."
            StreamRoutingResult.NoConnectivity -> "You're offline — can't stream this track."
        }

    override suspend fun shuffleLibrary() {
        val controller = ensureController() ?: return
        val all = musicRepository.getAllDownloadedTracks()
        if (all.isEmpty()) return

        val shuffled = all.shuffled()
        librarySnapshot = shuffled
        libraryShuffleActive = true
        // Mutually exclusive with radio: shuffling the library ends any station.
        radioActive = false
        radioSession = null
        _radioSeedLabel.value = null
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

    override suspend fun startRadio(
        seed: com.stash.core.data.radio.RadioSeed,
        keepCurrent: Boolean,
    ): Boolean {
        if (!streamingPreference.current()) return false
        val controller = ensureController() ?: return false
        val (session, firstBatch) = radioGenerator.start(seed)
        if (firstBatch.isEmpty()) return false
        radioSession = session
        radioActive = true
        // Only ONE grower may run: startRadio bypasses setQueueInternal (which is
        // what normally disarms library shuffle), so disarm it here explicitly —
        // otherwise both watchers append as the queue drains and library tracks
        // pollute the station.
        libraryShuffleActive = false
        librarySnapshot = emptyList()

        // Radio tracks are STREAMING tracks (no filePath) — they must become
        // stash-resolve:// placeholders (toQueueMediaItem), NOT bare toMediaItem()
        // which sets a null URI and makes Media3's DefaultMediaSourceFactory NPE
        // on the missing localConfiguration. (shuffleLibrary can use toMediaItem
        // because it only ever queues downloaded, file://-backed tracks.)
        //
        // Seamless start-from-current (Now Playing "Start radio"): keepCurrent is
        // set BY THE CALLER because it knows the seed is the playing track — we do
        // NOT infer it by matching videoIds (the playing item and the freshly-
        // resolved seed track often have different/absent youtubeIds because they
        // came through different resolution paths, which is why matching failed).
        // Identify the seed inside firstBatch by normalized title|artist (the seed
        // Song carries both), drop that one, and splice the discoveries around the
        // still-playing current item instead of tearing the player down.
        val songSeed = seed as? com.stash.core.data.radio.RadioSeed.Song
        if (keepCurrent && songSeed != null && controller.currentMediaItem != null &&
            controller.mediaItemCount > 0
        ) {
            val seedKey = radioIdentity(songSeed.artist, songSeed.title)
            val seedTrack = firstBatch.firstOrNull { radioIdentity(it.artist, it.title) == seedKey }
            val discoveries = firstBatch.filter { it !== seedTrack }
            currentQueueTracks = listOfNotNull(seedTrack) + discoveries
            val curIdx = controller.currentMediaItemIndex
            if (curIdx + 1 < controller.mediaItemCount) {
                controller.removeMediaItems(curIdx + 1, controller.mediaItemCount)
            }
            if (curIdx > 0) controller.removeMediaItems(0, curIdx)
            controller.addMediaItems(discoveries.map { it.toQueueMediaItem() })
            // No prepare/play/seek — the current item keeps playing uninterrupted.
        } else {
            // Fresh station (artist radio, song ⋮ menu on a non-playing row):
            // replace the queue and start from the top.
            currentQueueTracks = firstBatch
            controller.setMediaItems(firstBatch.map { it.toQueueMediaItem() }, 0, 0L)
            controller.prepare()
            controller.play()
        }
        _radioSeedLabel.value = when (seed) {
            is com.stash.core.data.radio.RadioSeed.Artist -> seed.name
            is com.stash.core.data.radio.RadioSeed.Song -> seed.title
        }
        return true
    }

    override fun stopRadio() {
        radioActive = false
        radioSession = null
        _radioSeedLabel.value = null
    }

    /** Normalized track identity for matching the radio seed against the batch —
     *  lowercase, trimmed, whitespace-collapsed `title|artist`. Robust across the
     *  different resolution paths that produce the same song. */
    private fun radioIdentity(artist: String, title: String): String {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
        return norm(title) + "|" + norm(artist)
    }

    /** Append the next generated batch to the station queue. Single-flight via
     *  [radioGrowMutex] so a flurry of state emissions can't fan out into
     *  concurrent grows. Internal as a test seam (the watcher that fires it is
     *  driven by the private _playerState listener, device-verified). */
    internal suspend fun growRadio() {
        radioGrowMutex.withLock {
            if (!radioActive) return
            val controller = controllerDeferred ?: return
            val session = radioSession ?: return
            val batch = radioGenerator.nextBatch(session)
            if (batch.isEmpty()) return
            // Streaming tracks → stash-resolve:// placeholders (see startRadio).
            controller.addMediaItems(batch.map { it.toQueueMediaItem() })
            currentQueueTracks = currentQueueTracks + batch
        }
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
        // Instant add: stream tracks enter as stash-resolve:// placeholders
        // (resolved at play time), so the queue grows immediately instead of
        // waiting out a slow resolve.
        val insertIndex = controller.currentMediaItemIndex + 1
        controller.addMediaItem(insertIndex, track.toQueueMediaItem())
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
        controller.addMediaItem(track.toQueueMediaItem())
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
        // Instant batch add: every track becomes a MediaItem now (placeholders
        // for streams), preserving tap order — no resolve fan-out, no dropped
        // rows, "Added N tracks" is finally literally true.
        val items = withContext(Dispatchers.IO) { tracks.map { it.toQueueMediaItem() } }
        currentQueueTracks = if (wasEmpty) tracks else currentQueueTracks + tracks
        controller.addMediaItems(items)
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
        val stream = cached ?: if (connectivity.isCellular()) {
            streamResolver.resolve(
                track,
                allowYouTube = allowYouTube,
                allowYtDlp = allowYtDlp,
                preferFastStartup = true,
            )
        } else {
            streamResolver.resolve(
                track,
                allowYouTube = allowYouTube,
                allowYtDlp = allowYtDlp,
            )
        }?.also { resolved ->
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
            // Adds/removes/moves AND the prefetch's replaceMediaItem land here —
            // invalidate the cached snapshot so updateState rebuilds it once.
            timelineDirty = true
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
     * `true` while the active track is loading from the user's point of view.
     * Purely the controller's own buffering now: tapped-track URL resolution
     * happens inside [LazyResolvingDataSource] while ExoPlayer sits in
     * STATE_BUFFERING, so no separate resolve-in-flight flag exists.
     */
    internal fun computeIsBuffering(controllerBuffering: Boolean): Boolean =
        controllerBuffering

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

    /**
     * Stamps the CURRENTLY-PLAYING item's metadata with the stream-quality
     * extras from [streamUrlCache] once a resolve has cached them. Full-
     * timeline placeholders enter the timeline WITHOUT codec/bit-depth/origin
     * extras (those only exist after a resolve), and [LazyResolvingDataSource]
     * resolves in the DataSource layer where item metadata can't be touched —
     * without this, Now Playing shows the "opus" fallback and no streaming
     * indicator for the playing track. Metadata-only replace with the URI left
     * untouched, which Media3 applies in place without interrupting playback.
     * Runs on every state refresh but is O(1) and self-disarming: once the
     * codec extra is present (or there's nothing cached to stamp) it no-ops.
     * Internal as a test seam.
     */
    internal fun maybeStampCurrentItemQuality(controller: MediaController) {
        val item = controller.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        if (extras.getString(EXTRA_STREAM_CODEC) != null) return // already stamped
        // Sentinel 0 = absent. Radio/search-synthetic ids are videoId.hashCode(),
        // which is frequently NEGATIVE — the old `<= 0L` guard skipped the stamp
        // for those, so the badge stayed "opus" and the art stayed the low-res
        // placeholder. Allow any non-zero id.
        val trackId = extras.getLong(EXTRA_TRACK_ID, 0L)
        if (trackId == 0L) return
        val stream = streamUrlCache.get(trackId) ?: return // downloaded/unresolved: nothing to stamp
        if (stream.codec == null && stream.origin == null && stream.coverArtUrl == null) return
        val newExtras = Bundle(extras).apply {
            stream.codec?.let { putString(EXTRA_STREAM_CODEC, it) }
            stream.bitsPerSample?.let { putInt(EXTRA_STREAM_BIT_DEPTH, it) }
            stream.sampleRateHz?.let { putInt(EXTRA_STREAM_SAMPLE_RATE, it) }
            stream.bitrateKbps?.let { putInt(EXTRA_STREAM_BITRATE, it) }
            stream.origin?.let { putString(EXTRA_STREAM_ORIGIN, it) }
        }
        // Upgrade the artwork to the source's square cover (e.g. the high-res
        // Qobuz cover) when the current art is a blank/low-res YouTube video
        // thumbnail — those are letterboxed/soft; the resolved cover is square.
        val currentArt = item.mediaMetadata.artworkUri?.toString()
        val betterArt = stream.coverArtUrl?.takeIf {
            it != currentArt &&
                (currentArt.isNullOrBlank() ||
                    com.stash.core.common.ArtUrlUpgrader.isYouTubeVideoThumbnail(currentArt))
        }
        val metaBuilder = item.mediaMetadata.buildUpon().setExtras(newExtras)
        betterArt?.let { metaBuilder.setArtworkUri(Uri.parse(it)) }
        val stamped = item.buildUpon()
            .setMediaMetadata(metaBuilder.build())
            .build()
        controller.replaceMediaItem(controller.currentMediaItemIndex, stamped)
    }

    private fun updateState(controller: MediaController) {
        // Quality badge for the playing track: see [maybeStampCurrentItemQuality].
        // The replace fires onTimelineChanged, which re-runs updateState with
        // the stamped extras — the guard inside makes the second pass a no-op.
        maybeStampCurrentItemQuality(controller)

        val currentItem = controller.currentMediaItem
        val track = currentItem?.toTrack()
        // Timeline snapshot rebuilt only when the timeline actually changes
        // (onTimelineChanged sets the dirty flag). updateState fires on every
        // player event, and with the full timeline holding the whole queue a
        // per-event O(n) rebuild of a 2.6k-item list would jank the main thread.
        if (timelineDirty || cachedTimelineQueue.size != controller.mediaItemCount) {
            cachedTimelineQueue = buildList {
                for (i in 0 until controller.mediaItemCount) {
                    add(controller.getMediaItemAt(i).toTrack())
                }
            }
            timelineDirty = false
        }
        val timelineQueue = cachedTimelineQueue

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
            // STATE_BUFFERING covers normal buffering AND every in-data-source
            // blocking resolve: the 403→yt-dlp re-resolve (RefreshingDataSource)
            // and the placeholder just-in-time resolve (LazyResolvingDataSource)
            // both block the loader thread while the player reports buffering.
            isBuffering = computeIsBuffering(
                controller.playbackState == Player.STATE_BUFFERING,
            ),
        )
        _playerState.value = newState
        lastKnownQueueSize = newState.queue.size
        lastKnownTimelineSize = timelineQueue.size

        // Persist position for resume-on-restart (fire and forget). Gated:
        // updateState fires on EVERY player event, and each DataStore edit is
        // a full read-parse-serialize-fsync cycle — a buffering flap storm
        // (STATE_BUFFERING↔READY on a bad stream) was rewriting the same
        // position dozens of times. Persist only on meaningful change: track/
        // index switch, a pause edge (exact resume position), or ≥5s drift.
        val pauseEdge = lastObservedIsPlaying && !newState.isPlaying
        lastObservedIsPlaying = newState.isPlaying
        if (track != null &&
            shouldPersistPosition(
                trackChanged = track.id != lastSavedPosTrackId,
                indexChanged = newState.currentIndex != lastSavedPosQueueIndex,
                pauseEdge = pauseEdge,
                positionDeltaMs = newState.positionMs - lastSavedPositionMs,
            )
        ) {
            lastSavedPosTrackId = track.id
            lastSavedPosQueueIndex = newState.currentIndex
            lastSavedPositionMs = newState.positionMs
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
        private const val RADIO_GROW_THRESHOLD = 5
        private const val LIBRARY_SHUFFLE_GROW_THRESHOLD = 5

        /** How many tracks each grow appends. Big enough to outpace a fast-skipping user. */
        private const val LIBRARY_SHUFFLE_GROW_BATCH = 50

        /** Refresh prefetch if cached URL has less than this margin remaining. */
        private const val PREFETCH_FRESH_THRESHOLD_MS = 60_000L

        /**
         * Max number of tracks to probe forward when the tapped track fails
         * to resolve and no other track is currently playing. Low to keep
         * the latency acceptable (~7s per probe in the worst-case yt-dlp
         * path). Only applies to the idle-player path; when music IS
         * playing, the tapped-track failure is surfaced as a snackbar and
         * current playback continues undisturbed.
         */
        private const val FORWARD_PROBE_LIMIT = 3

        /** Delay before surfacing foreground stream resolution as user-visible loading. */
        private const val STREAM_LOADING_MESSAGE_DELAY_MS = 1_200L
    }

    /**
     * Queue-time MediaItem: downloaded-and-on-disk → the eager file:// item;
     * otherwise a stash-resolve:// placeholder that [LazyResolvingDataSource]
     * resolves at play time. Carries the SAME identity extras as [toMediaItem]
     * so every downstream consumer (offline silent-skip, scrobbler, likes,
     * resume, prefetch matching) keeps working unchanged.
     */
    private fun Track.toQueueMediaItem(): MediaItem {
        val localPath = filePath
        if (isDownloaded && !localPath.isNullOrBlank() && filePathExistsOnDisk(localPath)) {
            return toMediaItem()
        }
        // Resolver inputs ride the URI's query params so even a track with no
        // Room row (search-surface synthetic id) can resolve at open() time.
        val placeholder = stashResolveUri(
            trackId = id,
            youtubeId = youtubeId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            isrc = isrc,
        )
        return toMediaItem().buildUpon().setUri(placeholder).build()
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

/**
 * Whether a position-persist DataStore write is worth issuing. True on a
 * track/index switch, on a play→pause edge (resume must land exactly where
 * the user paused), or once the position has drifted at least
 * [POSITION_PERSIST_MIN_DELTA_MS] from the last write — everything else
 * (buffering flaps, shuffle/repeat toggles, timeline stamps) re-reports a
 * position the store already has. Top-level + `internal` so the unit test
 * can exercise it without booting the repository.
 */
internal fun shouldPersistPosition(
    trackChanged: Boolean,
    indexChanged: Boolean,
    pauseEdge: Boolean,
    positionDeltaMs: Long,
): Boolean = trackChanged || indexChanged || pauseEdge ||
    kotlin.math.abs(positionDeltaMs) >= POSITION_PERSIST_MIN_DELTA_MS

/** Minimum position drift before an unforced persist write. */
internal const val POSITION_PERSIST_MIN_DELTA_MS = 5_000L

