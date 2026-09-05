package com.stash.core.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.C
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
import com.stash.core.model.RadioStartResult
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val radioGenerator: com.stash.core.data.radio.RadioStationGenerator,
    private val trackIdentityEvents: com.stash.core.data.sync.TrackIdentityEvents,
    private val playbackSessionBus: PlaybackSessionBus,
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
        // Then, if that connect found an empty player, put the persisted last
        // session on screen as a paused ghost (#462) — same shape the idle-stop
        // handshake leaves behind — so the mini player has something to show and
        // a play button that reaches resumeLastQueue().
        scope.launch {
            ensureController()
            seedGhostFromPersistedSession()
        }

        // Idle-stop handshake. The service flips this false just before it
        // stops itself: release the controller so OUR binding doesn't keep
        // the stopped service (and the whole process) alive. It flips true
        // in the service's onCreate: reconnect so state observation works
        // for playback the app didn't start (media button / Bluetooth) —
        // the deafness that killed the previous repo-side idle design.
        // The last _playerState snapshot is deliberately kept on release so
        // the mini player still shows the paused track; play() already
        // rebuilds from the persisted queue when the timeline comes back
        // empty.
        scope.launch {
            playbackSessionBus.sessionAlive.collect { alive -> onSessionAliveChanged(alive) }
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

        // A track's youtubeId was swapped (resync approval, wrong-match
        // swap, OMV→ATV canonicalization) — any StreamUrl cached under
        // this id was resolved against the OLD identity and must not be
        // served for the new one.
        scope.launch {
            trackIdentityEvents.changes.collect { trackId ->
                streamUrlCache.invalidate(trackId)
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
                    // chain (qbdlx/arcod FLAC) so the common auto-advance never
                    // falls back to the cold LazyResolvingDataSource path.
                    prefetchNextTrack()
                }
        }
    }

    private val _playerState = MutableStateFlow(PlayerState())

    /**
     * #462: true while [_playerState] shows a paused "ghost" of the persisted last
     * session on a player that holds NO timeline (a cold start after process
     * death). The idle-stop design already keeps the last state on screen when only
     * the SERVICE dies, and [play] rebuilds the queue from [PlaybackStateStore] when
     * the timeline comes back empty; a cold start had no state to keep. Cleared the
     * moment the controller reports real items, or when the ghost's track is deleted.
     */
    @Volatile private var ghostSession = false
    override val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    /**
     * Playback position ticker: 250 ms while playing, and SILENT while
     * paused — see [positionTicker] for why, and for the tests that pin it.
     *
     * ONE shared ticker (audit finding): the old cold flow ran a private
     * 4 Hz Main-thread poll PER collector — every retained NowPlayingViewModel
     * (activity-scoped MiniPlayer + nav entries; a heap dump showed 6 live)
     * plus ListeningRecorder each span their own timer, forever, even while
     * paused. shareIn collapses them to one.
     *
     * That collapse was not enough on its own: [ListeningRecorder] subscribes
     * from a process-scoped coroutine, so `WhileSubscribed` never dropped to
     * zero subscribers and the loop kept polling for the life of the process.
     * Gating the poll on `isPlaying` inside the flow fixes every collector at
     * once, whatever their scope — measured at 56 mAh/5h49m of cached-state
     * drain before the fix.
     */
    override val currentPosition: Flow<Long> = positionTicker(
        playerState = _playerState,
        pollIntervalMs = POSITION_UPDATE_INTERVAL_MS,
        readPositionMs = { controllerDeferred?.currentPosition ?: 0L },
    )
        .flowOn(Dispatchers.Main)
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)

    /** Cached [MediaController] instance; null until [ensureController] succeeds.
     * Internal as a test seam: gate/queue tests inject a mock controller here. */
    @Volatile
    internal var controllerDeferred: MediaController? = null

    /** Serialises controller construction — see [ensureController]. */
    private val controllerBuildMutex = Mutex()

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
    private var lastSavedRepeatMode: RepeatMode? = null
    private var lastSavedSource: com.stash.core.model.PlaybackSource? = null

    /** The current queue's playing-from context. Mirrors [currentQueueTracks] —
     *  every path that replaces the queue must also set this. */
    @Volatile
    private var currentSource: com.stash.core.model.PlaybackSource =
        com.stash.core.model.PlaybackSource.Unknown

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
        val controller = ensureController() ?: return
        // An empty timeline means the service died and took ExoPlayer's queue
        // with it — the OS reclaims background services routinely. play() on
        // an empty player is a silent no-op, i.e. a dead play button; rebuild
        // from the persisted queue instead. resumeLastQueue ends in
        // prepare() + play(), so playback still starts.
        if (controller.mediaItemCount == 0) {
            resumeLastQueue()
            return
        }
        controller.play()
    }

    override suspend fun pause() {
        ensureController()?.pause()
    }

    override fun setVolume(volume: Float) {
        // Not suspend — SleepTimerController's fade loop calls this every
        // ~350ms and can't afford a full ensureController() connect-await
        // per tick. Read the already-connected controller directly; volume
        // is a Player/MediaController property, main-thread-affine, so hop
        // there explicitly since callers (SleepTimerController) run on
        // Dispatchers.Default.
        scope.launch {
            controllerDeferred?.volume = volume.coerceIn(0f, 1f)
        }
    }

    override suspend fun skipNext() {
        cascadeGuard.onUserTransport()
        val controller = ensureController() ?: return
        // Full timeline: ExoPlayer sees the whole queue, so the native seek is
        // always correct (shuffle order, repeat-all wraparound included).
        // hasNextMediaItem() is false only at the true end with repeat off —
        // the correct no-op.
        if (!controller.hasNextMediaItem()) return
        val nextIndex = controller.nextMediaItemIndex
        val nextTrack = queueTrackAtTimelineIndex(controller, nextIndex)
        if (nextTrack != null) {
            // Sequential transport must never pin behind a stale offline row.
            // When preparation rejects it, retain native navigation so the
            // existing local-error recovery can advance to the next valid item.
            prepareExplicitTimelineTarget(controller, nextIndex, nextTrack)
        }
        controller.seekToNextMediaItem()
    }

    override suspend fun skipPrevious() {
        cascadeGuard.onUserTransport()
        val controller = ensureController() ?: return
        if (!controller.hasPreviousMediaItem()) return

        // Do not enter a stale offline item and rely on forward-only error
        // recovery: A -> stale B -> current C would otherwise bounce back to C
        // forever. Walk Media3's own previous-window order so shuffle and
        // repeat-all remain correct, and stop if a cycle contains no playable
        // predecessor.
        val timeline = controller.currentTimeline
        val navigationRepeatMode = if (controller.repeatMode == Player.REPEAT_MODE_ONE) {
            Player.REPEAT_MODE_OFF
        } else {
            controller.repeatMode
        }
        // Seed with the origin so repeat-all cannot wrap around and select the
        // currently playing item as its own predecessor.
        val visited = mutableSetOf(controller.currentMediaItemIndex)
        var candidate = controller.previousMediaItemIndex
        while (candidate != C.INDEX_UNSET && visited.add(candidate)) {
            val candidateTrack = queueTrackAtTimelineIndex(controller, candidate)
            if (
                candidateTrack == null ||
                prepareExplicitTimelineTarget(
                    controller = controller,
                    timelineIndex = candidate,
                    target = candidateTrack,
                    reportUnavailable = false,
                )
            ) {
                controller.seekToDefaultPosition(candidate)
                return
            }
            candidate = timeline.getPreviousWindowIndex(
                candidate,
                navigationRepeatMode,
                controller.shuffleModeEnabled,
            )
        }
        _userMessages.tryEmit("No earlier song is available offline right now.")
    }

    override suspend fun seekTo(positionMs: Long) {
        cascadeGuard.onUserTransport()
        ensureController()?.seekTo(positionMs)
    }

    override suspend fun setQueue(
        tracks: List<Track>,
        startIndex: Int,
        source: com.stash.core.model.PlaybackSource,
    ) = setQueueInternal(tracks, startIndex, startPositionMs = 0L, source = source)

    /**
     * Backing implementation for [setQueue] that also accepts a start
     * position. Kept private (not on the interface) so the public
     * [setQueue] signature — and every test that stubs/verifies it with
     * argument matchers — stays unchanged apart from the new [source]
     * parameter. [resumeLastQueue] uses this to continue from the saved
     * position AND the saved source.
     */
    private suspend fun setQueueInternal(
        tracks: List<Track>,
        startIndex: Int,
        startPositionMs: Long,
        source: com.stash.core.model.PlaybackSource = com.stash.core.model.PlaybackSource.Unknown,
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
        currentSource = source

        val controller = ensureController() ?: return
        if (tracks.isEmpty()) return

        val streamingOn = streamingPreference.current()
        val safeStart = startIndex.coerceIn(0, tracks.size - 1)

        // Full timeline: EVERY playable track becomes a MediaItem now.
        // Downloaded → file://; stream → stash-resolve:// placeholder resolved
        // just-in-time by LazyResolvingDataSource at open(). Native next/prev/
        // repeat/shuffle are correct because ExoPlayer sees the whole queue.
        // Only the selected track performs a live disk check on this critical
        // path. Tail downloads use persisted size/path metadata and are opened
        // lazily; local playback errors already skip safely when encountered.
        // Construction stays on IO for the selected SAF probe and the large
        // allocation, but tap-to-audio no longer scales with filesystem work.
        val builtItems = withContext(Dispatchers.IO) {
            tracks.mapIndexedNotNull { index, track ->
                track.toInitialQueueMediaItem(
                    streamingEnabled = streamingOn,
                    validateLocalFile = index == safeStart,
                )?.let { track to it }
            }
        }
        val streamingAtMutation = streamingPreference.current()
        val acceptedItems = if (streamingAtMutation) {
            builtItems
        } else {
            builtItems.filter { (_, item) -> item.isUsableOfflineQueueItem() }
        }
        val playable = acceptedItems.map { it.first }
        val items = acceptedItems.map { it.second }
        if (items.isEmpty()) {
            _userMessages.tryEmit("Nothing in this queue is playable right now.")
            return
        }
        // startIndex maps through the playable filter by track id.
        val startId = tracks[safeStart].id
        val startInPlayable = playable.indexOfFirst { it.id == startId }
        if (startInPlayable < 0) {
            _userMessages.tryEmit("That song isn't available offline right now.")
            // Don't fall back to track 0 — bail rather than silently substitute.
            return
        }

        currentQueueTracks = playable
        controller.setMediaItems(items, startInPlayable, startPositionMs)
        controller.prepare()
        controller.play()

        Log.i(TAG, "setQueue: full timeline, ${items.size} items, start=$startInPlayable")

        // Warm the next-up URL so auto-advance never waits on a cold resolve
        // (the placeholder path is the cold-jump fallback, not the happy path).
        scope.launch { prefetchNextTrack() }
    }

    /**
     * #462: on a cold start with nothing loaded, show the persisted last session
     * paused at its saved position. State only — no setMediaItems, no prepare, no
     * network, no budget spent: a stream placeholder resolves only at prepare, and
     * [play] rebuilds the real queue through [resumeLastQueue] when the timeline is
     * empty. Skipped when the player already holds a timeline (Android Auto or a
     * media button started playback before the app came up) or when there is
     * nothing persisted.
     */
    internal suspend fun seedGhostFromPersistedSession() {
        if (_playerState.value.currentTrack != null) return
        if ((controllerDeferred?.mediaItemCount ?: 0) > 0) return
        val plan = playbackResumer.buildResumePlan() ?: return
        val tracks = plan.tracks.map { it.toDomain() }
        if (tracks.isEmpty()) return
        val index = plan.startIndex.coerceIn(0, tracks.size - 1)
        val current = tracks[index]
        // Re-check after the suspension: a tap may have loaded a real queue meanwhile.
        if (_playerState.value.currentTrack != null || (controllerDeferred?.mediaItemCount ?: 0) > 0) return
        ghostSession = true
        _playerState.value = PlayerState(
            currentTrack = current,
            isPlaying = false,
            positionMs = plan.positionMs.coerceAtLeast(0L),
            durationMs = current.durationMs,
            isShuffleEnabled = plan.isShuffled,
            repeatMode = plan.repeatMode,
            queue = tracks,
            currentIndex = index,
            isStreaming = !current.isDownloaded,
            source = plan.source,
        )
        Log.i(TAG, "cold start: showing last session paused (${tracks.size} tracks, index=$index)")
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
                val controller = ensureController()
                controller?.shuffleModeEnabled = plan.isShuffled
                controller?.repeatMode = plan.repeatMode.toPlayerRepeatMode()
                setQueueInternal(tracks, plan.startIndex, plan.positionMs, plan.source)
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
     * Eager-resolve Media3's actual next-up track (including shuffle order) and
     * refresh its existing timeline slot before ExoPlayer auto-advances.
     *
     * Skips when:
     *  - There is no next track (current is last).
     *  - The next track has a usable downloaded file (no resolve needed).
     *  - The cache already has a fresh entry (expires in >60s).
     *  - The next track isn't streamable.
     *  - Streaming pref is off.
     *
     * Failures are logged and swallowed — the original (possibly stale)
     * MediaItem stays in place and [RefreshingDataSourceFactory] handles
     * any 403 at playback time, exactly as before this prefetch existed.
     */
    internal suspend fun prefetchNextTrack() {
        val controller = controllerDeferred ?: return
        val tracks = currentQueueTracks
        val nextTimelineIndex = controller.nextMediaItemIndex
        if (nextTimelineIndex !in 0 until controller.mediaItemCount) return
        val nextId = controller.getMediaItemAt(nextTimelineIndex)
            .mediaMetadata.extras?.getLong(EXTRA_TRACK_ID, -1L) ?: return
        val next = tracks.firstOrNull { it.id == nextId } ?: return
        val nextLocalPath = next.filePath
        val hasRecordedDownload = next.isDownloaded && !nextLocalPath.isNullOrBlank()
        val knownTooSmall = next.fileSizeBytes in 1 until StashConstants.MIN_PLAYABLE_LOCAL_BYTES
        if (hasRecordedDownload && !knownTooSmall) {
            // Full-library startup intentionally validates only the selected
            // item. Validate the immediate successor here, after playback has
            // started, so a stale tail path can still fall back to streaming
            // without restoring an O(queue-size) disk/SAF scan on tap.
            val hasUsableLocal = withContext(Dispatchers.IO) {
                filePathExistsOnDisk(nextLocalPath)
            }
            if (hasUsableLocal) return
        }
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
            refreshControllerMediaItem(controller, nextTimelineIndex, next, cached)
            return
        }

        // In-flight dedup. Both the current-index watcher and setQueue's
        // explicit kick can fire for the same next-up, and
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
            streamResolver.resolve(entity, allowYouTube = true, allowYtDlp = true)
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
        refreshControllerMediaItem(controller, nextTimelineIndex, next, resolved)
    }

    /**
     * Swap the exact [timelineIndex] chosen by Media3 to the freshly-[resolved]
     * stream after verifying it still holds [next]. This avoids refreshing an
     * earlier duplicate of the same track id. The slot is usually a
     * stash-resolve:// placeholder (or a stale earlier resolve), and
     * the prefetch upgrades it to the real lossless URL; without refreshing
     * [EXTRA_STREAM_ORIGIN]/codec/bit-depth Now Playing would keep showing the
     * stale badge even though the audio is now FLAC. Preserves mediaId and the
     * rest of the metadata. Returns `false` if the timeline changed while the
     * resolve was in flight; the URL remains cached for lazy playback.
     */
    private fun refreshControllerMediaItem(
        controller: MediaController,
        timelineIndex: Int,
        next: Track,
        resolved: StreamUrl,
    ): Boolean {
        if (timelineIndex !in 0 until controller.mediaItemCount) return false
        val item = controller.getMediaItemAt(timelineIndex)
        val itemTrackId = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID) ?: return false
        if (itemTrackId != next.id) return false
        val newExtras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            resolved.codec?.let { putString(EXTRA_STREAM_CODEC, it) }
            resolved.bitsPerSample?.let { putInt(EXTRA_STREAM_BIT_DEPTH, it) }
            resolved.sampleRateHz?.let { putInt(EXTRA_STREAM_SAMPLE_RATE, it) }
            resolved.bitrateKbps?.let { putInt(EXTRA_STREAM_BITRATE, it) }
            resolved.origin?.let { putString(EXTRA_STREAM_ORIGIN, it) }
        }
        // #336 follow-up: upgrade artwork together with the stream metadata so
        // Now Playing cannot keep a stale video thumbnail after prefetch.
        val currentArt = item.mediaMetadata.artworkUri?.toString()
        val artUpgrade = resolved.coverArtUrl?.takeIf {
            it != currentArt &&
                (currentArt.isNullOrBlank() ||
                    com.stash.core.common.ArtUrlUpgrader.isYouTubeVideoThumbnail(currentArt))
        }
        val refreshedMeta = item.mediaMetadata.buildUpon().setExtras(newExtras)
        artUpgrade?.let { art ->
            refreshedMeta.setArtworkUri(Uri.parse(art))
            persistArtUpgradeAsync(next.id, art)
        }
        val refreshed = item.buildUpon()
            .setUri(resolved.url)
            .setMediaMetadata(refreshedMeta.build())
            .build()
        controller.replaceMediaItem(timelineIndex, refreshed)
        return true
    }

    /**
     * Guarded, fire-and-forget write of an upgraded album-art URL to the
     * track row (#336). The row is re-checked inside the write — only
     * blank or YouTube-video-thumbnail art is replaced — so a stale
     * session item can never clobber an already-good cover. Shared by the
     * prefetch refresh above and [maybeStampCurrentItemQuality].
     */
    private fun persistArtUpgradeAsync(trackId: Long, art: String) {
        scope.launch {
            runCatching {
                val rowArt = trackDao.getById(trackId)?.albumArtUrl
                val rowNeedsUpgrade = rowArt.isNullOrBlank() ||
                    com.stash.core.common.ArtUrlUpgrader.isYouTubeVideoThumbnail(rowArt)
                if (rowNeedsUpgrade && rowArt != art) {
                    trackDao.updateAlbumArtUrl(trackId, art)
                }
            }
        }
    }

    override suspend fun shuffleLibrary(): Boolean {
        ensureController() ?: return false
        // #332: Library is downloaded-only BY DESIGN, and so is its shuffle.
        // The fix here is the failure mode: the old silent return on an
        // empty pool read as a dead/stuck button — the caller now gets a
        // Boolean so the Library screen can say why nothing happened.
        // Routing through setQueueInternal (instead of the old hand-rolled
        // toMediaItem+setMediaItems) keeps the logical-queue bookkeeping in
        // one place.
        val pool = musicRepository.getAllDownloadedTracks()
        if (pool.isEmpty()) return false

        val shuffled = pool.shuffled()
        librarySnapshot = shuffled
        libraryShuffleActive = true
        // Mutually exclusive with radio: shuffling the library ends any station.
        radioActive = false
        radioSession = null
        _radioSeedLabel.value = null
        // setQueueInternal owns the logical-queue bookkeeping and playback
        // start; the pre-shuffled list IS the playback order, so Media3's
        // shuffleModeEnabled stays untouched as before.
        setQueueInternal(
            shuffled,
            startIndex = 0,
            startPositionMs = 0L,
            source = com.stash.core.model.PlaybackSource.Library,
        )
        return true
    }

    override suspend fun startRadio(
        seed: com.stash.core.data.radio.RadioSeed,
        keepCurrent: Boolean,
    ): RadioStartResult {
        if (!streamingPreference.current()) return RadioStartResult.StreamingOff
        val controller = ensureController() ?: return RadioStartResult.PlayerNotReady
        val (session, firstBatch) = radioGenerator.start(seed)
        if (firstBatch.isEmpty()) return RadioStartResult.NoStation
        // Re-check streaming AFTER generation (#353): radioGenerator.start() can
        // take seconds over the network, and the user may toggle Offline in that
        // window. Arming a streaming-only station while offline would just fail
        // to play, so bail — consistent with the queue's Online/Offline contract.
        if (!streamingPreference.current()) return RadioStartResult.StreamingOff
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
        val seedLabel = when (seed) {
            is com.stash.core.data.radio.RadioSeed.Artist -> seed.name
            is com.stash.core.data.radio.RadioSeed.Song -> seed.title
        }
        _radioSeedLabel.value = seedLabel
        currentSource = com.stash.core.model.PlaybackSource.Radio(seedLabel)
        return RadioStartResult.Started
    }

    override fun stopRadio() {
        radioActive = false
        radioSession = null
        _radioSeedLabel.value = null
        currentSource = com.stash.core.model.PlaybackSource.Unknown
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
            if (!streamingPreference.current()) {
                stopRadio()
                return
            }
            val controller = controllerDeferred ?: return
            val session = radioSession ?: return
            val batch = radioGenerator.nextBatch(session)
            if (batch.isEmpty()) return
            if (!radioActive || !streamingPreference.current()) {
                stopRadio()
                return
            }
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

    /**
     * Queue identity is also the Media3 mediaId and StreamUrlCache key.
     * Native Qobuz rows reach per-track actions without a video id, which
     * previously mapped every one of them to id=0. Persist those transient
     * rows before queueing so different songs cannot share one cached URL.
     */
    private suspend fun Track.withQueueIdentity(): Track =
        if (id != 0L) this else copy(id = musicRepository.ensureTrackPersisted(this))

    /**
     * Applies the queue's Online/Offline contract at the repository boundary.
     * Offline entries must have a real, usable local file; a stale Room path
     * must not fall through to a stash-resolve placeholder and hit the network.
     */
    private fun Track.isPlayableForQueueAppend(streamingEnabled: Boolean): Boolean {
        val localPath = filePath
        return if (streamingEnabled) {
            !isUnavailableForDisplay
        } else {
            isDownloaded &&
                !localPath.isNullOrBlank() &&
                filePathExistsOnDisk(localPath)
        }
    }

    private fun MediaItem.isUsableOfflineQueueItem(): Boolean {
        val scheme = localConfiguration?.uri?.scheme
        return scheme == "file" || scheme == "content"
    }

    override suspend fun addNext(track: Track): Boolean {
        val controller = ensureController() ?: return false
        val playable = track.takeIf {
            it.isPlayableForQueueAppend(streamingPreference.current())
        }
        if (playable == null) {
            _userMessages.tryEmit("Nothing in this queue is playable right now.")
            return false
        }
        val queueTrack = playable.withQueueIdentity()
        if (!queueTrack.isPlayableForQueueAppend(streamingPreference.current())) {
            _userMessages.tryEmit("Nothing in this queue is playable right now.")
            return false
        }
        val wasEmpty = controller.mediaItemCount == 0
        // Instant add: stream tracks enter as stash-resolve:// placeholders
        // (resolved at play time), so the queue grows immediately instead of
        // waiting out a slow resolve.
        val insertIndex = controller.currentMediaItemIndex + 1
        controller.addMediaItem(insertIndex, queueTrack.toQueueMediaItem())
        // Mirror into the logical queue right after the playing track's
        // logical position (falling back to append) so the queue sheet
        // shows the Play-Next insert where it will actually play.
        val currentId = controller.currentMediaItem?.mediaMetadata?.extras
            ?.getLong(EXTRA_TRACK_ID, -1L) ?: -1L
        val logicalPos = currentQueueTracks.indexOfFirst { it.id == currentId }
        currentQueueTracks = when {
            wasEmpty -> listOf(queueTrack)
            logicalPos >= 0 -> currentQueueTracks.toMutableList()
                .apply { add(logicalPos + 1, queueTrack) }
            else -> currentQueueTracks + queueTrack
        }
        // If the queue was empty, the user tapped "Play next" with nothing
        // playing — they expect the song to actually start, not just sit
        // silently in a queue they can't see. Prepare and play.
        if (wasEmpty) {
            controller.prepare()
            controller.play()
        }
        return true
    }

    override suspend fun addToQueue(track: Track): Boolean {
        val controller = ensureController() ?: return false
        val playable = track.takeIf {
            it.isPlayableForQueueAppend(streamingPreference.current())
        }
        if (playable == null) {
            _userMessages.tryEmit("Nothing in this queue is playable right now.")
            return false
        }
        val queueTrack = playable.withQueueIdentity()
        if (!queueTrack.isPlayableForQueueAppend(streamingPreference.current())) {
            _userMessages.tryEmit("Nothing in this queue is playable right now.")
            return false
        }
        val wasEmpty = controller.mediaItemCount == 0
        controller.addMediaItem(queueTrack.toQueueMediaItem())
        currentQueueTracks = if (wasEmpty) listOf(queueTrack) else currentQueueTracks + queueTrack
        if (wasEmpty) {
            controller.prepare()
            controller.play()
        }
        return true
    }

    override suspend fun addToQueue(tracks: List<Track>): Boolean {
        if (tracks.isEmpty()) return false
        val controller = ensureController() ?: return false
        val streamingEnabled = streamingPreference.current()
        val playable = tracks.filter { it.isPlayableForQueueAppend(streamingEnabled) }
        if (playable.isEmpty()) {
            _userMessages.tryEmit("Nothing in this queue is playable right now.")
            return false
        }
        val persistedTracks = playable.map { it.withQueueIdentity() }
        val builtItems = withContext(Dispatchers.IO) {
            persistedTracks.map { it to it.toQueueMediaItem() }
        }
        val streamingAtMutation = streamingPreference.current()
        val acceptedItems = if (streamingAtMutation) {
            builtItems
        } else {
            builtItems.filter { (_, item) -> item.isUsableOfflineQueueItem() }
        }
        if (acceptedItems.isEmpty()) {
            _userMessages.tryEmit("Nothing in this queue is playable right now.")
            return false
        }
        val queueTracks = acceptedItems.map { it.first }
        val items = acceptedItems.map { it.second }
        val wasEmpty = controller.mediaItemCount == 0
        // Instant batch add: every track becomes a MediaItem now (placeholders
        // for streams), preserving tap order — no resolve fan-out, no dropped
        // rows, "Added N tracks" is finally literally true.
        currentQueueTracks = if (wasEmpty) queueTracks else currentQueueTracks + queueTracks
        controller.addMediaItems(items)
        if (wasEmpty) {
            controller.prepare()
            controller.play()
        }
        return true
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

    private fun queueTrackAtTimelineIndex(
        controller: MediaController,
        timelineIndex: Int,
    ): Track? {
        if (timelineIndex !in 0 until controller.mediaItemCount) return null
        val trackId = controller.getMediaItemAt(timelineIndex)
            .mediaMetadata.extras?.getLong(EXTRA_TRACK_ID, -1L) ?: return null
        return currentQueueTracks.firstOrNull { it.id == trackId }
    }

    /**
     * Validate one explicitly requested timeline target (Next, Previous, or a
     * queue-sheet jump). Tail downloads are intentionally not checked while a
     * large queue is constructed; this is their just-in-time guard. A stale
     * local item becomes a lazy stream placeholder online and is rejected
     * offline, without scanning or rebuilding the rest of the queue.
     */
    private suspend fun prepareExplicitTimelineTarget(
        controller: MediaController,
        timelineIndex: Int,
        target: Track,
        reportUnavailable: Boolean = true,
    ): Boolean {
        val localPath = target.filePath
        val hasRecordedDownload = target.isDownloaded && !localPath.isNullOrBlank()
        if (!hasRecordedDownload) return true

        val knownTooSmall =
            target.fileSizeBytes in 1 until StashConstants.MIN_PLAYABLE_LOCAL_BYTES
        val hasUsableLocal = !knownTooSmall && withContext(Dispatchers.IO) {
            filePathExistsOnDisk(localPath)
        }
        if (hasUsableLocal) return true

        if (!streamingPreference.current() || target.isUnavailableForDisplay) {
            if (reportUnavailable) {
                _userMessages.tryEmit("That song isn't available offline right now.")
            }
            return false
        }
        controller.replaceMediaItem(timelineIndex, target.toStreamPlaceholderMediaItem())
        return true
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
                if (prepareExplicitTimelineTarget(controller, timelineIdx, target)) {
                    controller.seekToDefaultPosition(timelineIdx)
                }
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
            val target = queueTrackAtTimelineIndex(controller, index)
            if (target == null || prepareExplicitTimelineTarget(controller, index, target)) {
                controller.seekToDefaultPosition(index)
            }
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
        // The ghost is state only — a deleted ghost track must not linger on screen.
        if (ghostSession && _playerState.value.currentTrack?.id == deletedTrackId) {
            ghostSession = false
            _playerState.value = PlayerState()
        }
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
            currentSource = com.stash.core.model.PlaybackSource.Unknown
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
            currentSource = com.stash.core.model.PlaybackSource.Search
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
     *
     * Single-flight behind [controllerBuildMutex]: the init-time connect, the
     * session-alive handshake and any early command can all race here, and
     * two winners would mean two live controllers — the loser's binding is
     * never released, which would pin an idle-stopped service (and the whole
     * process) alive forever. A cached-but-disconnected controller (service
     * stopped between builds) is released and rebuilt rather than returned.
     */
    private suspend fun ensureController(): MediaController? {
        controllerDeferred?.let { if (it.isConnected) return it }

        return controllerBuildMutex.withLock {
            controllerDeferred?.let {
                if (it.isConnected) return it
                it.release()
                controllerDeferred = null
            }
            try {
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
    }

    /**
     * Idle-stop handshake (see [PlaybackSessionBus]). `false` → the service
     * is about to stop itself: release our controller so the binding doesn't
     * keep it alive, but KEEP the last [_playerState] snapshot so the UI
     * still shows the paused track. `true` → the service is up (possibly
     * started by a media button while we held no controller): reconnect so
     * state observation and listen-recording resume.
     */
    internal suspend fun onSessionAliveChanged(alive: Boolean) {
        if (alive) {
            ensureController()
        } else {
            controllerDeferred?.release()
            controllerDeferred = null
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

        /**
         * Seeks (and repeat-one wraparounds, and auto-advances) move the
         * position without any other event firing. While PLAYING the ticker
         * would catch it within 250 ms anyway; while PAUSED nothing else
         * does, which is the only reason the old ticker kept polling at 1 Hz
         * forever. Refreshing state here means a seek-while-paused lands
         * immediately instead of up to a second later, and lets the ticker
         * stay silent whenever playback is stopped.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            controllerDeferred?.let { updateState(it) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                cascadeGuard.onPlaybackStarted()
            }
            controllerDeferred?.let { updateState(it) }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            controllerDeferred?.let {
                updateState(it)
                scope.launch { prefetchNextTrack() }
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            controllerDeferred?.let {
                updateState(it)
                scope.launch { prefetchNextTrack() }
            }
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
                    // A downloaded/local file failed to open or decode. Retry
                    // that same track as a stream when online; otherwise use the
                    // established per-track skip. This is not a backend outage,
                    // so the local failure itself does not arm the cascade.
                    Log.w(
                        TAG,
                        "onPlayerError: '$failingTitle' code=${error.errorCode} " +
                            "(${error.errorCodeName}) — stream-fallback-or-skip (local)",
                        error,
                    )
                    if (controller == null) return
                    if (current == null) {
                        controller.recoverOrStop()
                        return
                    }
                    val failedIndex = controller.currentMediaItemIndex
                    val failedId = current.mediaMetadata.extras
                        ?.getLong(EXTRA_TRACK_ID, -1L)
                    scope.launch {
                        val recovered = recoverLocalFailureAsStream(
                            controller = controller,
                            failedItem = current,
                            failedIndex = failedIndex,
                        )
                        if (!recovered) {
                            val activeId = controller.currentMediaItem?.mediaMetadata?.extras
                                ?.getLong(EXTRA_TRACK_ID, -1L)
                            if (
                                controller.currentMediaItemIndex == failedIndex &&
                                (failedId == null || activeId == failedId)
                            ) {
                                controller.recoverOrStop()
                            }
                        }
                    }
                }
                PlaybackErrorPolicy.STREAMING_CASCADE -> {
                    // A streamed item failed — 403/network OR a 200 that served
                    // empty/malformed bytes. Both are stream-source failures, so
                    // they go through the cascade guard: recover (skip) until the
                    // threshold, then HALT (pause + notify) instead of skip-storming
                    // the queue. `origin` is logged so a diagnostics capture reveals
                    // which source (kennyy/squid/youtube) served the bad URL.
                    val verdict = cascadeGuard.onError(current?.mediaId)
                    Log.w(
                        TAG,
                        "onPlayerError: '$failingTitle' code=${error.errorCode} " +
                            "(${error.errorCodeName}) streaming origin=$streamOrigin — verdict=$verdict",
                        error,
                    )
                    when (val v = verdict) {
                        // Transient stream failure (mobile-network stall, dropped
                        // CDN connection, expired URL): re-prepare the SAME item
                        // at its current position before giving up on it. The
                        // guard grants one retry per item, so a genuinely-dead
                        // URL falls through to Recover on its next error.
                        //
                        // Evict the cached StreamUrl FIRST or the "retry" is not
                        // one. A URL can 403 BEFORE its own expiresAtMs — a
                        // PO-token/session-bound YouTube URL dying early — and
                        // StreamUrlCache only expires on time, so
                        // LazyResolvingDataSource.open() (:110, cache-before-
                        // resolve) hands retryCurrentItem() the exact same dead
                        // URL. It 403s instantly and the guard escalates to
                        // Recover on what was never a real second attempt.
                        //
                        // Sentinel is 0, and the test is `!= 0L`, NOT `> 0L`:
                        // radio and search-surface tracks carry synthetic ids of
                        // videoId.hashCode(), which are negative about half the
                        // time — and those are exactly the streamed rows most
                        // likely to hit this path. A `> 0L` guard would skip the
                        // eviction for them, i.e. no-op on its own target
                        // population. maybeStampCurrentItemQuality (:2045) records
                        // this codebase making that precise mistake once already.
                        //
                        // Scope, so the next reader doesn't over-trust it: this
                        // only helps `stash-resolve://` placeholder items. Once
                        // the prefetch upgrades a slot, refreshControllerMediaItem
                        // bakes the real URL into the MediaItem URI and the cache
                        // is never consulted — YouTube slots then self-heal via
                        // RefreshingDataSource, while qbdlx/arcod slots get
                        // nothing. Closing that is a separate change.
                        StreamErrorCascadeGuard.Verdict.RetrySameItem -> {
                            current?.mediaMetadata?.extras
                                ?.getLong(EXTRA_TRACK_ID, 0L)
                                ?.takeIf { it != 0L }
                                ?.let { streamUrlCache.invalidate(it) }
                            controller?.retryCurrentItem()
                        }
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

    /**
     * Retry a failed local queue item as a lazy stream. This listener-level
     * fallback covers auto-advance and transport commands issued directly by
     * notification, Bluetooth, or Android Auto controllers, which do not pass
     * through the repository's explicit navigation guards.
     */
    internal suspend fun recoverLocalFailureAsStream(
        controller: MediaController,
        failedItem: MediaItem,
        failedIndex: Int,
    ): Boolean {
        if (!connectivity.isConnected() || !streamingPreference.current()) return false
        if (connectivity.isCellular() && !streamingPreference.streamOnCellular.first()) return false
        val failedId = failedItem.mediaMetadata.extras
            ?.getLong(EXTRA_TRACK_ID, -1L) ?: return false
        val mediaTrackId = failedItem.mediaId.toLongOrNull() ?: return false
        if (mediaTrackId != failedId) return false
        val track = currentQueueTracks.firstOrNull { it.id == failedId }
            ?: failedId.takeIf { it > 0L }
                ?.let { id ->
                    // Android Auto and service-resumed queues are built by the
                    // playback service and therefore do not populate the
                    // repository's logical queue. Resolve their trusted Room
                    // row by the identity carried in service metadata.
                    trackDao.getById(id)?.toDomain()
                }
            ?: return false
        if (track.isUnavailableForDisplay) return false

        if (failedIndex !in 0 until controller.mediaItemCount) return false
        val slotItem = controller.getMediaItemAt(failedIndex)
        val slotId = slotItem.mediaMetadata.extras
            ?.getLong(EXTRA_TRACK_ID, -1L) ?: return false
        if (slotId != failedId) return false

        // Preference and DAO reads above suspend. Revalidate the active item
        // immediately before mutating the timeline so a late fallback cannot
        // restart playback after the user has navigated elsewhere.
        val activeItem = controller.currentMediaItem ?: return false
        val activeId = activeItem.mediaMetadata.extras
            ?.getLong(EXTRA_TRACK_ID, -1L) ?: return false
        if (
            controller.currentMediaItemIndex != failedIndex ||
            activeItem.mediaId != failedItem.mediaId ||
            activeId != failedId ||
            activeItem.localConfiguration?.uri != failedItem.localConfiguration?.uri
        ) {
            return false
        }

        val placeholder = stashResolveUri(
            trackId = track.id,
            youtubeId = track.youtubeId,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            isrc = track.isrc,
        )
        val replacement = failedItem.buildUpon().setUri(placeholder).build()
        controller.replaceMediaItem(failedIndex, replacement)
        controller.prepare()
        controller.play()
        return true
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
     * Re-prepare the CURRENT item after a transient stream error, resuming at the
     * position it died at rather than restarting or skipping. After a
     * PlaybackException the player is IDLE; [seekTo] fixes the resume point and
     * [prepare] re-attempts the source — for a `stash-resolve://` placeholder that
     * re-runs the lazy resolver, so an expired URL is re-fetched, while a plain
     * transient stall just re-opens the same still-valid URL.
     *
     * Position is read BEFORE prepare because an error can reset currentPosition
     * to 0 on some devices; coerced to a valid range so a bogus value can't throw.
     */
    private fun MediaController.retryCurrentItem() {
        val resumeAt = currentPosition.coerceAtLeast(0L)
        val index = currentMediaItemIndex
        if (index in 0 until mediaItemCount) {
            seekTo(index, resumeAt)
        }
        prepare()
        play()
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
    /**
     * Last track id whose media item was stamped. Guards the artwork upgrade
     * against re-firing on every state refresh — see the note inside
     * [maybeStampCurrentItemQuality] about the read-back never showing the
     * write.
     */
    @Volatile private var lastStampedTrackId: Long = 0L

    internal fun maybeStampCurrentItemQuality(controller: MediaController) {
        val item = controller.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: run {
            Log.d(STAMP_TAG, "skip: current item has no extras")
            return
        }
        if (extras.getString(EXTRA_STREAM_CODEC) != null) return // already stamped
        // Sentinel 0 = absent. Radio/search-synthetic ids are videoId.hashCode(),
        // which is frequently NEGATIVE — the old `<= 0L` guard skipped the stamp
        // for those, so the badge stayed "opus" and the art stayed the low-res
        // placeholder. Allow any non-zero id.
        val trackId = extras.getLong(EXTRA_TRACK_ID, 0L)
        if (trackId == 0L) {
            Log.d(STAMP_TAG, "skip: no track id in extras")
            return
        }
        // The self-disarm this function's KDoc promises ("once the codec extra
        // is present it no-ops") DOES NOT HOLD: the extras written by
        // replaceMediaItem do not survive the session round-trip, so the
        // read-back never shows the codec just written and the guard above
        // never trips. Measured on-device 2026-08-23: **1093 stamps for ONE
        // track in 45 s**, each an IPC that fires onTimelineChanged and
        // re-enters updateState — a feedback loop, not a no-op.
        //
        // Remember the id instead, so the artwork upgrade below (which DOES
        // stick, because #336 persists it to Room) runs once per track rather
        // than on every 250 ms position tick. The badge no longer depends on
        // this landing at all — see [withLiveStreamQuality].
        if (trackId == lastStampedTrackId) return
        // Every exit here used to be silent, which is why "Now Playing shows the
        // Room default instead of what actually streamed" was undiagnosable from
        // a log: the badge looked plausible and nothing said the stamp had been
        // skipped or why.
        val stream = streamUrlCache.get(trackId) ?: run {
            // Downloaded rows legitimately have nothing to stamp. For a STREAMED
            // track this is the bug signature: the resolve populated the cache
            // (LazyResolvingDataSource line ~140) but no player event re-ran
            // updateState afterwards, so the badge keeps the Room default.
            Log.d(STAMP_TAG, "skip: nothing cached for track $trackId (downloaded, or resolve hasn't landed yet)")
            return
        }
        if (stream.codec == null && stream.origin == null && stream.coverArtUrl == null) {
            Log.d(STAMP_TAG, "skip: cached stream for $trackId carries no codec/origin/art")
            return
        }
        Log.i(STAMP_TAG, "stamping track $trackId codec=${stream.codec} origin=${stream.origin}")
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
        betterArt?.let { art ->
            metaBuilder.setArtworkUri(Uri.parse(art))
            // #336: persist the upgrade — this stamp used to die with the
            // session, so Library/queue/playlist rows kept the video
            // thumbnail forever on queue-driven playback (mixes resolve via
            // prefetch/LazyResolvingDataSource, which never hit routeStream's
            // on-stream art swap).
            persistArtUpgradeAsync(trackId, art)
        }
        val stamped = item.buildUpon()
            .setMediaMetadata(metaBuilder.build())
            .build()
        lastStampedTrackId = trackId
        controller.replaceMediaItem(controller.currentMediaItemIndex, stamped)
    }

    /**
     * Overlays the live stream metadata from [streamUrlCache] onto [track] when
     * the media item's extras didn't carry it.
     *
     * [maybeStampCurrentItemQuality] writes codec/origin into the item's extras,
     * but that write does not survive the session round-trip — the same "stamp
     * dies with the session" that #336 worked around for ARTWORK by persisting
     * to Room. Codec and origin never got that workaround, so Now Playing fell
     * back to [Track.fileFormat]'s Room default (`"opus"`, which every
     * streaming-only row carries) and never rendered the "via YT" / "via
     * JioSaavn" origin at all — a badge that silently described the database
     * instead of the audio.
     *
     * Reading the cache here makes the badge independent of whether the
     * round-trip sticks. Cheap: one LRU lookup per state refresh, and it
     * short-circuits the moment the extras DO carry an origin.
     */
    private fun withLiveStreamQuality(track: Track, item: MediaItem?): Track {
        if (track.streamOrigin != null) return track // extras already carried it
        val trackId = item?.mediaMetadata?.extras?.getLong(EXTRA_TRACK_ID, 0L) ?: 0L
        if (trackId == 0L) return track
        // A downloaded row has no cache entry and must keep Room's truth — its
        // codec comes from the file on disk, which is more authoritative than
        // anything a resolver reported.
        val stream = streamUrlCache.get(trackId) ?: return track
        return track.copy(
            fileFormat = stream.codec ?: track.fileFormat,
            bitsPerSample = stream.bitsPerSample ?: track.bitsPerSample,
            sampleRateHz = stream.sampleRateHz ?: track.sampleRateHz,
            qualityKbps = stream.bitrateKbps ?: track.qualityKbps,
            streamOrigin = stream.origin ?: track.streamOrigin,
        )
    }

    internal fun updateState(controller: MediaController) {
        // A ghost survives refreshes from an empty player (idle events, reconnects);
        // the first refresh that carries real items retires it.
        if (ghostSession) {
            if (controller.mediaItemCount == 0) return
            ghostSession = false
        }
        // Artwork upgrade for the playing track: see [maybeStampCurrentItemQuality].
        // It is now id-guarded so it runs once per track — its extras write does
        // NOT survive the session round-trip, so it can never self-disarm on the
        // read-back the way its KDoc originally assumed.
        maybeStampCurrentItemQuality(controller)

        val currentItem = controller.currentMediaItem
        // Badge from the live resolve, not from extras that may not have stuck.
        val track = currentItem?.toTrack()?.let { withLiveStreamQuality(it, currentItem) }
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
            source = currentSource,
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
        if (queueIds != lastSavedQueueIds ||
            newState.isShuffleEnabled != lastSavedShuffle ||
            newState.repeatMode != lastSavedRepeatMode ||
            newState.source != lastSavedSource
        ) {
            lastSavedQueueIds = queueIds
            lastSavedShuffle = newState.isShuffleEnabled
            lastSavedRepeatMode = newState.repeatMode
            lastSavedSource = newState.source
            if (queueIds.isNotEmpty()) {
                scope.launch {
                    playbackStateStore.saveQueue(
                        queueIds,
                        newState.isShuffleEnabled,
                        newState.repeatMode,
                        newState.source,
                    )
                }
            }
        }
    }

    // ---- Mappers ----

    companion object {
        private const val TAG = "StashPlayer"

        /**
         * Separate tag for the Now Playing quality stamp so its (chatty,
         * per-state-refresh) diagnostics can be filtered independently of
         * playback errors on the shared [TAG].
         */
        private const val STAMP_TAG = "StashQualityStamp"
        private const val POSITION_UPDATE_INTERVAL_MS = 250L

        /** Auto-grow fires once the remaining queue tail drops below this many tracks. */
        private const val RADIO_GROW_THRESHOLD = 5
        private const val LIBRARY_SHUFFLE_GROW_THRESHOLD = 5

        /** How many tracks each grow appends. Big enough to outpace a fast-skipping user. */
        private const val LIBRARY_SHUFFLE_GROW_BATCH = 50

        /** Refresh prefetch if cached URL has less than this margin remaining. */
        private const val PREFETCH_FRESH_THRESHOLD_MS = 60_000L
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
        return toStreamPlaceholderMediaItem()
    }

    /**
     * Queue-time item used by [setQueueInternal]. The selected item is verified
     * against the filesystem so a tap can never silently substitute another
     * track. Tail items deliberately trust persisted download metadata: their
     * files are opened lazily by ExoPlayer, whose local-error policy skips a
     * stale entry without crashing or draining a streaming backend.
     */
    private fun Track.toInitialQueueMediaItem(
        streamingEnabled: Boolean,
        validateLocalFile: Boolean,
    ): MediaItem? {
        val localPath = filePath
        val hasRecordedDownload = isDownloaded && !localPath.isNullOrBlank()
        val knownTooSmall = fileSizeBytes in 1 until StashConstants.MIN_PLAYABLE_LOCAL_BYTES
        val hasUsableLocal = hasRecordedDownload && !knownTooSmall &&
            (!validateLocalFile || filePathExistsOnDisk(localPath))

        return when {
            hasUsableLocal -> toMediaItem()
            !streamingEnabled || isUnavailableForDisplay -> null
            else -> toStreamPlaceholderMediaItem()
        }
    }

    private fun Track.toStreamPlaceholderMediaItem(): MediaItem {
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

/**
 * Playback-position stream: polls the player [pollIntervalMs] apart WHILE
 * PLAYING, and not at all otherwise.
 *
 * A paused player's position only moves when the user seeks, and a seek
 * arrives as a [PlayerState] update (see `onPositionDiscontinuity`), so
 * there is nothing for a paused poll to discover. The previous version
 * polled forever regardless — a `while (true)` loop waking the main thread
 * every second for the entire life of the process, long after playback
 * stopped. `distinctUntilChanged` hid it from consumers (the repeated
 * positions were identical), so it cost battery without ever showing up as
 * an emission. Hence the unit tests count polls, not emissions.
 *
 * Top-level + `internal` so the test can exercise it on virtual time
 * without booting the repository (same idea as [shouldPersistPosition]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun positionTicker(
    playerState: Flow<PlayerState>,
    pollIntervalMs: Long,
    readPositionMs: () -> Long,
): Flow<Long> = playerState
    .map { it.isPlaying to it.positionMs }
    .distinctUntilChanged()
    .flatMapLatest { (isPlaying, statePositionMs) ->
        if (isPlaying) {
            flow {
                while (true) {
                    emit(readPositionMs())
                    delay(pollIntervalMs)
                }
            }
        } else {
            flowOf(statePositionMs)
        }
    }
    .distinctUntilChanged()

/** Inverse of the existing `Int.toRepeatMode()` mapper — used only to
 *  restore Media3's repeat mode from a persisted [RepeatMode] on resume. */
internal fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
}
