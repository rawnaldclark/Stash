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
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.stash.core.common.constants.StashConstants
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.library.TrackSourceRule
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import com.stash.core.media.service.StashPlaybackService
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.media.streaming.YouTubeStreamResolver
import com.stash.core.media.streaming.stashResolveUri
import com.stash.core.media.streaming.STASH_RESOLVE_SCHEME
import com.stash.core.media.streaming.resolvedAgain
import com.stash.core.model.PlayerState
import com.stash.core.model.RadioStartResult
import com.stash.core.model.RepeatMode
import com.stash.core.model.isScannedSong
import com.stash.core.model.isScannedTrackId
import com.stash.core.model.scannedFileExtension
import com.stash.core.model.Track
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_BIT_DEPTH
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_BITRATE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_CODEC
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_DASH
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
    // Off unless Hilt binds the real store: existing tests build the repository by hand.
    private val autoplayRadioPreference: com.stash.core.data.prefs.AutoplayRadioPreference =
        com.stash.core.data.prefs.AutoplayRadioPreference.Off,
    // Listen Together (spec 2026-09-24 §4): while a session owns the player, this class's own
    // automation and persistence stand aside. Null in hand-built tests.
    private val listenTogether: com.stash.core.media.listen.ListenTogetherController? = null,
    // The diagnostics bundle's "Recent playback errors". A fresh, unshared one in hand-built tests.
    private val diagnosticsLog: PlaybackDiagnosticsLog = PlaybackDiagnosticsLog(),
    // Data saver: the next-song look-ahead is skipped where it applies. Null in hand-built tests (never applies).
    private val qualityPolicy: com.stash.core.media.streaming.StreamQualityPolicy? = null,
    // Remote v2 (spec 2026-11-03 §2.3): while a linked browser plays the queue, this phone resolves nothing it won't play.
    // Null in hand-built tests (never a browser).
    private val browserOutputs: com.stash.core.media.cast.BrowserOutputs? = null,
    // The add-ons that are on. When one leaves (removed, switched off, turned off by Stash), every queued song whose
    // link it handed out is resolved again. Null in hand-built tests (nothing is watched).
    private val addonRegistry: com.stash.core.addons.AddonRegistry? = null,
    // The player's check of an add-on's stream: its refusals (the add-on is set aside, its songs resolved again), and
    // which add-on each placeholder's link came from. Null in hand-built tests (only a slot's own origin is read).
    private val addonStreamCheck: com.stash.core.media.streaming.AddonStreamCheck? = null,
) : PlayerRepository {

    /**
     * A linked browser plays the queue (Remote v2): it finds and streams each song itself, so the look-ahead, a tap's resolve
     * and a quality change's re-resolve all stand down. A tapped song becomes a placeholder that resolves only if the music
     * comes back to this phone.
     */
    private val playsOnBrowser: Boolean get() = browserOutputs?.remote?.value != null

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
        com.stash.core.media.streaming.isUsableLocalFile(context, path)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** True while a Listen Together session owns the player; the transport gates (Next, Previous, play) read it. */
    private val inListenTogether: Boolean get() = listenTogether?.active?.value == true

    /**
     * True while the player holds anything but the user's own queue: a session, and after it until
     * [resumeQueue] has put the queue back. The saves and the queue watchers read this one, so the
     * session's song can't be saved over the user's queue in the gap (spec §4). The error auto-skip and
     * prefetch gates read it too: in the gap the player still holds the session's song, not a queue to skip.
     */
    private val listenTogetherOwnsQueue: Boolean
        get() = listenTogether?.let { it.active.value || it.restorePending } == true

    /**
     * Last-known queue/timeline sizes, mirrored out of [updateState] for
     * [CrashDiagnostics]. Plain volatiles (not controller reads) because the
     * crash handler samples them from an arbitrary thread with the heap
     * possibly exhausted — MediaController is main-thread-only.
     */
    @Volatile private var lastKnownQueueSize = 0
    @Volatile private var lastKnownTimelineSize = 0
    @Volatile private var lastKnownOrderSize = 0

    /**
     * A queue bigger than [QUEUE_WINDOW_SIZE] (a whole library: 72,593 songs in the crash reports):
     * its full order, of which the player holds a window. Null when the player holds the whole
     * queue, which is every queue up to that size, exactly as before. Main thread; internal as a
     * test seam. See [slideWindow].
     */
    @Volatile internal var queueWindow: QueueWindow? = null

    /**
     * Songs of the window's order a slide can't read back from the library by id (All Songs files
     * have no `tracks` row): kept for as long as the window lives. Main thread.
     */
    private var windowPinned: Map<Long, Track> = emptyMap()

    /**
     * The window outlived the controller that saw it ([onSessionAliveChanged] false, or a new
     * connection) and nobody has checked the player behind the next one. That player can be the
     * same one (a client still bound, such as Android Auto, kept the service through its idle stop;
     * a play landed mid-stop), a new, empty one, or one holding another queue: [confirmWindow]
     * decides. Until then the window neither slides nor reshuffles. Main thread.
     */
    private var windowUnconfirmed = false

    /** Shuffles a big queue's whole order. Internal: tests seed it. */
    internal var windowRandom: kotlin.random.Random = kotlin.random.Random.Default

    // savePosition gate state — see the comment at the persist site in
    // [updateState]. Main-thread only (updateState runs on Main).
    private var lastSavedPosTrackId = Long.MIN_VALUE
    private var lastSavedPosQueueIndex = -1
    private var lastSavedPositionMs = Long.MIN_VALUE / 2
    private var lastObservedIsPlaying = false

    init {
        // A browser playing the queue shows where it plays from ("Playing from Blonde"), as the handoff says it.
        browserOutputs?.sourceLabel = { currentSource.displayLabel.takeIf { it.isNotBlank() } }

        // OOM triage (#238/#239): stamp player scale into every crash report.
        com.stash.core.data.diagnostics.CrashDiagnostics.register("player") {
            "queue=$lastKnownQueueSize timeline=$lastKnownTimelineSize order=$lastKnownOrderSize"
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

        addonRegistry?.let { registry -> scope.launch { resolveAgainWhenAnAddonLeaves(registry.addons) } }
        addonStreamCheck?.let { check -> scope.launch { resolveAgainWhenAStreamIsRefused(check.refusals) } }

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
        //
        // A sweep emits one id per deleted row: they are evicted in batches, so a big queue's whole
        // order ([queueWindow]) is rebuilt once per batch, not once per song (each a pass over 72k
        // ids on the main thread).
        val deletions = kotlinx.coroutines.channels.Channel<Long>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        scope.launch {
            musicRepository.trackDeletions.collect { deletions.send(it) }
        }
        scope.launch {
            for (first in deletions) {
                val batch = LinkedHashSet<Long>().apply { add(first) }
                // A big queue's eviction is a pass over its whole order, and a sweep emits its ids one
                // at a time between file deletes: gather the sweep first (until it pauses for
                // DELETION_BATCH_QUIET_MS, at most DELETION_BATCH_MAX_MS). An ordinary queue is
                // small, so its evictions stay immediate.
                if (queueWindow != null) {
                    val since = android.os.SystemClock.uptimeMillis()
                    do {
                        delay(DELETION_BATCH_QUIET_MS)
                        var more = false
                        while (true) batch += deletions.tryReceive().getOrNull()?.also { more = true } ?: break
                    } while (more && android.os.SystemClock.uptimeMillis() - since < DELETION_BATCH_MAX_MS)
                } else {
                    while (true) batch += deletions.tryReceive().getOrNull() ?: break
                }
                evictTracksFromQueue(batch)
            }
        }

        // Listen Together ended: put the user's own queue back, paused (spec §6). sessionEnds fires
        // once per session, even one that fails too fast for `active` to be observed as true.
        listenTogether?.let { together ->
            scope.launch {
                together.sessionEnds.collect {
                    // One failed restore must not end the collector: every later session would then never restore.
                    try {
                        resumeQueue(play = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Listen Together: restoring the queue failed", e)
                    }
                }
            }
            // A session runs at the room's pace: the service puts the player back to 1x as it enters
            // (and drift correction owns the speed from then on), so the picker shows Normal again.
            scope.launch {
                together.active.collect { active -> if (active) _playbackSpeed.value = 1f }
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

        // Queue-edge watcher. v0.9.14 built it for the library shuffle: refill the
        // queue with more shuffled library tracks once the user nears the tail
        // (inactive unless shuffleLibrary() armed it; setQueue() disarms it so
        // per-playlist queues stay finite and predictable). It also keeps a big
        // queue's window ([queueWindow]) around the playing song: songs join
        // ahead as playback nears the window's end (or behind, going back) and
        // the far side is let go, so the player never holds more than about
        // QUEUE_WINDOW_SIZE of them.
        scope.launch {
            playerState.collect { state ->
                // Not from the #462 ghost: its queue is a picture of a stopped session, and a grow would
                // load the batch into the empty player as a new queue, saved over the user's.
                if (listenTogetherOwnsQueue || ghostSession) return@collect
                queueWindow?.let { window ->
                    if (windowUnconfirmed) return@collect // confirmWindow decides first
                    // Another controller replaced the queue (Android Auto, a media button's resumption):
                    // window mode ends instead of showing "and 72,000 more" for a queue that isn't this one.
                    val controller = controllerDeferred
                    if (controller != null && controller.mediaItemCount > 0 && !windowInSync(controller, window)) {
                        dropWindow("the player's queue changed under it")
                    } else if (controller != null && controller.shuffleModeEnabled != window.isShuffled) {
                        // Shuffle went on or off and the window doesn't follow yet: a reshuffle that gave
                        // up (playback kept moving under its reads) is tried again with each new state.
                        rewindowForShuffle()
                    } else if (windowNeedsSlide()) {
                        slideWindow()
                    }
                    return@collect
                }
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
                if (!radioActive || listenTogetherOwnsQueue || ghostSession) return@collect // see the grower above
                val remaining = state.queue.size - state.currentIndex - 1
                if (remaining in 0 until RADIO_GROW_THRESHOLD) growRadio()
            }
        }
        // Position save while playing. updateState (the only other writer) runs
        // on player events alone, and a steady hour-long mix raises none after
        // its track starts: a process death mid-track (an OOM kill #238/#239,
        // LMK, force-stop, a crash) resumed near the track's beginning instead
        // of where it was. Every POSITION_PERSIST_INTERVAL_MS while playing the
        // position goes through the same gate; nothing runs while paused (the
        // pause edge already saved the exact spot).
        scope.launch {
            _playerState.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
                while (playing) {
                    delay(POSITION_PERSIST_INTERVAL_MS)
                    persistPositionWhilePlaying()
                }
            }
        }

        // Autoplay radio (user ask, 2026-09-07): with the setting on, the moment
        // the LAST queued track starts, a song radio seeded from it is spliced
        // behind it, so a one-off play never ends in silence. Tried once per
        // track: a station that cannot be built is not retried on every tick.
        scope.launch {
            kotlinx.coroutines.flow.combine(playerState, autoplayRadioPreference.enabled) { state, on -> state to on }
                .collect { (state, on) ->
                    if (listenTogetherOwnsQueue) return@collect
                    if (!shouldAutoplayRadio(on, radioActive, libraryShuffleActive, state, autoplayTriedTrackId)) return@collect
                    val track = state.currentTrack ?: return@collect
                    autoplayTriedTrackId = track.id
                    val result = startRadio(
                        com.stash.core.data.radio.RadioSeed.Song(track.title, track.artist, track.youtubeId),
                        keepCurrent = true,
                    )
                    Log.i(TAG, "autoplay radio from '${track.title}' by ${track.artist}: $result")
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
                    // chain (qbdlx FLAC) so the common auto-advance never
                    // falls back to the cold LazyResolvingDataSource path.
                    prefetchNextTrack()
                }
        }

        watchNextUpDownload()
        watchDataSaver()
    }

    /**
     * Data saver coming into effect or leaving it (the phone moved between Wi-Fi and mobile data,
     * or the switch flipped) while music plays: the policy has already dropped every cached link
     * ([com.stash.core.media.streaming.StreamQualityPolicy.dataSaverChanges]); the next song's
     * slot is lined up again here ([relineNextStream]). The song playing keeps its link to the end.
     */
    private fun watchDataSaver() {
        val policy = qualityPolicy ?: return
        scope.launch {
            policy.dataSaverChanges()
                .flowOn(Dispatchers.IO)
                .drop(1) // the state at start: nothing lined up under another decision yet
                .catch { e -> Log.w(TAG, "Data saver watch stopped: ${e.message}") }
                .collect { dataSaver ->
                    Log.d(TAG, "Data saver ${if (dataSaver) "in effect" else "off"}: lining the next song up again")
                    relineNextStream()
                }
        }
    }

    /**
     * The next song may hold a link resolved under the old decision: a FLAC link the look-ahead
     * swapped in on Wi-Fi, which ExoPlayer starts buffering well before the transition. Its slot
     * goes back to a placeholder, resolved when the song is reached under the new decision, and the
     * look-ahead runs again (it skips itself while Data saver applies, and on Wi-Fi it lines up FLAC
     * again). A file, a placeholder or a cloud song is already right. PR #759's
     * `reresolveNextStream` does the same for a quality picked in Now Playing; once both are in,
     * this becomes a call to it.
     */
    internal suspend fun relineNextStream() {
        if (listenTogetherOwnsQueue) return
        val controller = controllerDeferred ?: return
        val index = controller.nextMediaItemIndex
        // No next song, or it is this one again (a one-song queue on repeat): a new source would restart it.
        if (index !in 0 until controller.mediaItemCount || index == controller.currentMediaItemIndex) return
        val item = controller.getMediaItemAt(index)
        val nextId = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID, -1L) ?: return
        streamUrlCache.invalidate(nextId)
        val scheme = item.localConfiguration?.uri?.scheme
        val queued = currentQueueTracks.firstOrNull { it.id == nextId }
        if ((scheme == "https" || scheme == "http") && queued != null) {
            val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
                listOf(EXTRA_STREAM_CODEC, EXTRA_STREAM_BIT_DEPTH, EXTRA_STREAM_SAMPLE_RATE, EXTRA_STREAM_BITRATE, EXTRA_STREAM_ORIGIN, EXTRA_STREAM_DASH)
                    .forEach(::remove)
            }
            val placeholder = item.buildUpon()
                .setUri(queued.toStreamPlaceholderMediaItem().localConfiguration?.uri)
                .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
                .build()
            replaceMediaItemKeepingShuffle(controller, index, placeholder)
        }
        prefetchNextTrack()
    }

    /**
     * A download that lands while its song waits next in line: the prefetch watcher
     * already ran (and may have swapped a stream in), so run it again; it now finds
     * the row's file and points the slot at it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchNextUpDownload() {
        scope.launch {
            playerState
                .map { it.currentIndex }
                .distinctUntilChanged()
                .flatMapLatest {
                    val nextId = nextUpTrackId() ?: return@flatMapLatest flowOf(false)
                    trackDao.observeById(nextId).map { it?.isDownloaded == true }.distinctUntilChanged().drop(1)
                }
                .catch { e -> Log.w(TAG, "next-up download watch stopped: ${e.message}") }
                .collect { downloaded -> if (downloaded) prefetchNextTrack() }
        }
    }

    /** The track id in the slot Media3 plays next, or null when there is none. */
    private fun nextUpTrackId(): Long? {
        val controller = controllerDeferred ?: return null
        val index = controller.nextMediaItemIndex
        if (index !in 0 until controller.mediaItemCount) return null
        return controller.getMediaItemAt(index).mediaMetadata.extras?.getLong(EXTRA_TRACK_ID, -1L)?.takeIf { it > 0L }
    }

    private val _playerState = MutableStateFlow(PlayerState())

    /**
     * #462: true while [_playerState] shows a paused "ghost" of the last session on
     * a player that holds NO timeline: seeded from the persisted session on a cold
     * start after process death, or kept from the last state when the SERVICE stops
     * ([onSessionAliveChanged]). [play] rebuilds the queue from [PlaybackStateStore]
     * when the timeline comes back empty; the queue sheet's tap, swipe and drag act on
     * the ghost too, and Play next / Add to queue load it first (see [ghostQueueActive]).
     * Cleared the moment the controller reports real items, or when the ghost's track
     * is deleted.
     */
    @Volatile private var ghostSession = false

    /**
     * Number of [setQueueInternal] calls that are still building their MediaItems (an IO hop that
     * scales with the queue). During that window the controller can still hold NO timeline, and a
     * play tap used to read that as "the service died" and call [resumeLastQueue], putting the
     * previous persisted session back over the song the user had just tapped. Internal: test seam.
     */
    internal val queueBuildsInFlight = java.util.concurrent.atomic.AtomicInteger(0)
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
     *
     * [ListeningRecorder] now collects only while repeat-one is on, so with
     * the screen off and repeat off nobody collects and the ticker doesn't
     * run at all. A collector that arrives after such a gap must not start
     * from the position of minutes ago (the recorder would read the jump to
     * the real one as a repeat-one loop): `replayExpirationMillis = 0` drops
     * the last value when the last collector leaves, and the restarted ticker
     * emits the live position at once.
     */
    override val currentPosition: Flow<Long> = positionTicker(
        playerState = _playerState,
        pollIntervalMs = POSITION_UPDATE_INTERVAL_MS,
        readPositionMs = { controllerDeferred?.currentPosition ?: 0L },
    )
        .flowOn(Dispatchers.Main)
        .shareAsPositionTicker(scope)

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

    /** The last track autoplay tried to seed a radio from, so a miss is not retried every tick. */
    @Volatile private var autoplayTriedTrackId: Long? = null

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

    private val _playbackSpeed = MutableStateFlow(1f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val radioGrowMutex = Mutex()

    /** What actually played, for Back under shuffle ([skipPrevious]). Main thread. */
    private val playHistory = PlayHistory()

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
     * (insert), [addToQueue] (append), [evictTracksFromQueue] (remove),
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

    /**
     * #468: while shuffle is on, the published queue is the controller's shuffle walk
     * and this maps each displayed row to its timeline slot (null with shuffle off).
     * Refreshed with every [updateState]; read by the sheet's index-based actions.
     */
    @Volatile private var shuffledDisplayIndices: List<Int>? = null

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
     * concurrent identical resolves (each spends lossless quota). A [java.util.concurrent.ConcurrentHashMap]-backed set so adds are
     * atomic across the resolves running on the scope's dispatcher.
     */
    private val prefetchInFlight: MutableSet<Long> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Bumped by [reresolveNextStream], and when an add-on leaves ([resolveAgainLinksFrom]).
     * A prefetch whose resolve was in flight across a bump may hold the old quality's URL, or the
     * departed add-on's, so it resolves again rather than put that in the next slot.
     */
    @Volatile private var streamQualityChanges = 0

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
        if (controller.mediaItemCount == 0 && !inListenTogether) {
            // A tapped queue is still being built: it ends in prepare() + play() by itself, so
            // there is nothing to resume, and resuming would race it (see [queueBuildsInFlight]).
            if (queueBuildsInFlight.get() > 0) return
            resumeLastQueue()
            return
        }
        controller.play()
    }

    override suspend fun pause() {
        // MediaController is main-thread-only (Media3 checks and throws); a
        // background caller — the sleep timer used to be one — must hop.
        withContext(Dispatchers.Main.immediate) { ensureController()?.pause() }
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

    override fun setPlaybackSpeed(speed: Float) {
        // In a session the speed belongs to the room's clock, and the session player ignores it anyway.
        if (inListenTogether) return
        val clamped = speed.coerceIn(0.1f, 4f)
        _playbackSpeed.value = clamped
        // Main-thread-affine; if nothing is connected, ensureController()
        // applies the stored value when it next connects.
        scope.launch { controllerDeferred?.takeIf { it.isConnected }?.setPlaybackSpeed(clamped) }
    }

    override suspend fun skipNext() {
        cascadeGuard.onUserTransport()
        val controller = ensureController() ?: return
        if (inListenTogether) { controller.seekToNext(); return }
        // Full timeline: ExoPlayer sees the whole queue, so the native seek is
        // always correct (shuffle order, repeat-all wraparound included).
        // hasNextMediaItem() is false only at the true end with repeat off —
        // the correct no-op. A big queue's window can end before the queue
        // does (a tap on the last row of Up next): bring the next songs in first.
        if (queueWindow != null && windowNeedsSlide()) slideWindow()
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

    /** The Back button: past [RESTART_THRESHOLD_MS] it restarts the song, as the notification's and a headset's Previous do. */
    override suspend fun skipPrevious() = skipPrevious(restartPastThreshold = true)

    /** A swipe on the art or the mini player: always another song, never a restart that springs the art back. */
    override suspend fun skipToPreviousSong() = skipPrevious(restartPastThreshold = false)

    private suspend fun skipPrevious(restartPastThreshold: Boolean) {
        cascadeGuard.onUserTransport()
        val controller = ensureController() ?: return
        if (inListenTogether) { controller.seekToPrevious(); return }
        if (restartPastThreshold && controller.currentPosition > RESTART_THRESHOLD_MS) {
            controller.seekTo(0)
            return
        }
        // A big queue's window may start at this song: bring the songs before it in first.
        if (queueWindow != null && windowNeedsSlide()) slideWindow()

        // Under shuffle the walk's previous slot may never have played: go back through what did.
        // Shuffle off, the list is what played, and Back stays the list's previous song.
        if (controller.shuffleModeEnabled) {
            if (backThroughHistory(controller)) return
            // Nothing played before this song in this queue. The button restarts it; a swipe was
            // offered because the sheet lists a song above, so it goes there (the walk's previous).
            if (restartPastThreshold) {
                controller.seekTo(0)
                return
            }
        }
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
        // Not "offline": that's the mode's name (2026-11-01).
        _userMessages.tryEmit("No earlier song can play right now.")
    }

    /**
     * Seeks to the most recent [playHistory] entry still in the queue and playable; false when there
     * is none. The entry stays on the history until the move, which takes it off (a move to the most
     * recent entry is a step back, see [PlayHistory]); gone or unplayable entries are dropped here.
     */
    private suspend fun backThroughHistory(controller: MediaController): Boolean {
        while (true) {
            val entry = playHistory.peek() ?: return false
            val idx = timelineIndexOfEntry(controller, entry)
            if (idx == null || idx == controller.currentMediaItemIndex) {
                playHistory.pop()
                continue
            }
            val target = queueTrackAtTimelineIndex(controller, idx)
            if (target != null &&
                !prepareExplicitTimelineTarget(controller, idx, target, reportUnavailable = false)
            ) {
                playHistory.pop()
                continue
            }
            // Found under another copy ordinal (an earlier copy was removed): record the one we go to.
            historyEntryAt(controller, idx)?.let { if (it != entry) playHistory.replaceLast(it) }
            controller.seekToDefaultPosition(idx)
            return true
        }
    }

    /**
     * Timeline slot of [entry]: the [PlayHistory.Entry.copy]-th copy of its song, else its last
     * copy when fewer remain (removing an earlier copy shifts the ordinals down); null when the
     * song is no longer queued.
     */
    private fun timelineIndexOfEntry(controller: MediaController, entry: PlayHistory.Entry): Int? {
        var seen = 0
        var last: Int? = null
        for (i in 0 until controller.mediaItemCount) {
            if (controller.getMediaItemAt(i).mediaId != entry.mediaId) continue
            if (seen == entry.copy) return i
            seen++
            last = i
        }
        return last
    }

    /** The history entry for timeline slot [index]: its song and copy ordinal. O(index). */
    private fun historyEntryAt(controller: MediaController, index: Int): PlayHistory.Entry? {
        if (index !in 0 until controller.mediaItemCount) return null
        val id = controller.getMediaItemAt(index).mediaId
        var copy = 0
        for (i in 0 until index) if (controller.getMediaItemAt(i).mediaId == id) copy++
        return PlayHistory.Entry(id, copy)
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
    /** Bumped by every [setQueueInternal]; a build that finishes behind a newer one is dropped. */
    private val setQueueGeneration = java.util.concurrent.atomic.AtomicInteger()

    private suspend fun setQueueInternal(
        tracks: List<Track>,
        startIndex: Int,
        startPositionMs: Long,
        source: com.stash.core.model.PlaybackSource = com.stash.core.model.PlaybackSource.Unknown,
        play: Boolean = true,
        restore: Boolean = false,
    ) {
        queueBuildsInFlight.incrementAndGet()
        try {
            setQueueBuilding(tracks, startIndex, startPositionMs, source, play, restore)
        } finally {
            queueBuildsInFlight.decrementAndGet()
        }
    }

    /** The body of [setQueueInternal], run while [queueBuildsInFlight] counts it. */
    private suspend fun setQueueBuilding(
        requested: List<Track>,
        startIndex: Int,
        startPositionMs: Long,
        source: com.stash.core.model.PlaybackSource,
        play: Boolean,
        /** A saved session coming back ([loadPlan], [loadGhost]): the only queue that may take a window back. */
        restore: Boolean,
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
        val generation = setQueueGeneration.incrementAndGet()

        val controller = ensureController() ?: return
        if (requested.isEmpty()) return
        // Read on the main thread (MediaController is main-thread only): a big queue is shuffled
        // over its whole order here, not by Media3 over the window.
        val shuffleOn = controller.shuffleModeEnabled
        val streamOk = streamAllowed()

        // Full timeline up to QUEUE_WINDOW_SIZE playable songs: EVERY playable track becomes a
        // MediaItem now. Downloaded → file://; stream → stash-resolve:// placeholder resolved
        // just-in-time by LazyResolvingDataSource at open(). Native next/prev/repeat/shuffle are
        // correct because ExoPlayer sees the whole queue. Only the selected track performs a live
        // disk check on this critical path. Tail downloads use persisted size/path metadata and
        // are opened lazily; local playback errors already skip safely when encountered.
        //
        // A bigger queue (a whole library) keeps its order as ids ([QueueWindow]) and only the
        // window around the tapped song becomes MediaItems: building all of them is what ran a
        // 72,593-song library out of memory, and the player then sent them all to the session
        // and SystemUI. Everything here runs on IO: the identity check, the playability pass
        // (no disk access but the selected song's) and the items.
        val built = withContext(Dispatchers.IO) {
            // Synthetic stream ids (an artist page's or a YouTube album's songs) become library ids,
            // so the saved queue resumes after the app is closed (withQueueIdentity). A real id is a
            // pure check, no DB. Id 0 is left as it was: those callers persist their own tracks.
            val tracks = requested.map { if (it.id == 0L) it else it.withQueueIdentity() }
            val safeStart = startIndex.coerceIn(0, tracks.size - 1)
            buildInitialQueue(tracks, safeStart, streamOk, shuffleOn)
        }
        // A later setQueue started while this one built (two quick taps, each its own
        // viewModelScope.launch): the first tap's selected-item SAF probe can be the slow one,
        // and without this its build landed last and replaced the song the user tapped second.
        if (generation != setQueueGeneration.get()) {
            Log.i(TAG, "setQueue: superseded by a later one while building, dropped")
            return
        }
        val items = built.items
        if (built.playableCount == 0) {
            _userMessages.tryEmit(if (streamOk) "Nothing in this queue is playable right now." else DOWNLOAD_MODE_MESSAGE)
            return
        }
        if (built.startSlot < 0) {
            _userMessages.tryEmit(
                when {
                    built.startTrack.isScannedSong -> SCANNED_FILE_MISSING_MESSAGE
                    !streamOk -> DOWNLOAD_MODE_MESSAGE
                    // Online, and the player refused it: confirmed to have nowhere to stream from.
                    else -> "That song isn't available right now."
                },
            )
            // Don't fall back to track 0 — bail rather than silently substitute.
            return
        }

        if (!play && listenTogether?.active?.value == true) return // a session started while the items were built
        val hadWindow = queueWindow != null
        // The service stopped under a big queue and this is its [restore] (the saved queue is the
        // window, slot for slot): the window, and with it the whole order, comes back too. Only a
        // restore: any other queue the user starts wins, even one of the same songs.
        val takenBack = queueWindow?.takeIf { restore && windowUnconfirmed && built.window == null && holdsWindow(items, it) }
        val window = built.window ?: takenBack
        currentQueueTracks = built.queueTracks
        queueWindow = window
        windowUnconfirmed = false
        if (takenBack == null) windowPinned = built.pinned
        // A paused restore must not inherit the session's playWhenReady = true: a later prepare (the
        // defensive one in onMediaItemTransition, a media button) would then start the music.
        if (!play) controller.playWhenReady = false
        resetPlayHistory()
        // A big queue's timeline is already in play order (shuffled over the whole list when shuffle
        // is on): Media3 must walk it as it is. Asked before the items go, so it covers them.
        // Asked every time a window is made (the service may be a new one); "off" only when one
        // ends, so ordinary queues send nothing.
        if (window != null || hadWindow) setKeepTimelineOrder(controller, window != null)
        controller.setMediaItems(items, built.startSlot, startPositionMs)
        // After Listen Together the user's queue comes back paused and unprepared, like the cold-start
        // ghost: nothing resolves until they press play (Media3 prepares an idle player on play).
        if (play) {
            controller.prepare()
            controller.play()
        }

        if (window == null) {
            Log.i(TAG, "setQueue: full timeline, ${items.size} items, start=${built.startSlot}")
        } else {
            val how = if (takenBack != null) " (taken back after the service stopped)" else ""
            Log.i(TAG, "setQueue: window of ${items.size} of ${window.total} songs$how, start=${built.startSlot}, shuffled=${window.isShuffled}")
            // Shuffle went on or off while the window was built (its rewindow stood aside for the
            // build): the window follows it now, once this build is no longer in flight.
            if (controller.shuffleModeEnabled != window.isShuffled) scope.launch { rewindowForShuffle() }
        }

        // Warm the next-up URL so auto-advance never waits on a cold resolve
        // (the placeholder path is the cold-jump fallback, not the happy path).
        // Not for a paused restore: nothing resolves until the user presses play.
        if (play) scope.launch { prefetchNextTrack() }
    }

    override suspend fun restoreHandoff(plan: com.stash.core.media.handoff.HandoffQueuePlan): Boolean {
        if (inListenTogether || plan.first.isEmpty()) return false
        val controller = ensureController() ?: return false
        // The first songs play in order; the real shuffle order comes with the rest, once the whole timeline is in.
        controller.shuffleModeEnabled = false
        controller.repeatMode = plan.repeat.toPlayerRepeatMode()
        setQueueInternal(plan.first, startIndex = 0, startPositionMs = plan.positionMs, source = plan.source, play = true)
        if (currentSource !== plan.source) return false
        val mine = currentQueueTracks
        scope.launch {
            val rest = try {
                plan.rest()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "handoff: matching the rest failed", e)
                null
            } ?: return@launch
            val c = controllerDeferred ?: return@launch
            // The user played something else, or edited the queue, while the rest was matched: leave theirs alone.
            if (currentSource !== plan.source || currentQueueTracks !== mine || listenTogetherOwnsQueue) {
                Log.i(TAG, "handoff: the queue changed meanwhile, the rest is dropped")
                return@launch
            }
            // Timeline position of each rest entry (before + first + after), or -1 when the player can't take it.
            val all = rest.before + mine + rest.after
            val streamOk = streamAllowed()
            val built = withContext(Dispatchers.IO) {
                all.mapIndexed { i, t ->
                    if (i >= rest.before.size && i < rest.before.size + mine.size) null
                    else t.toInitialQueueMediaItem(validateLocalFile = false, streamAllowed = streamOk)
                }
            }
            val beforeItems = (rest.before.indices).mapNotNull { built[it] }
            val afterItems = (rest.before.size + mine.size until all.size).mapNotNull { built[it] }
            val slot = IntArray(all.size) { -1 }
            var next = 0
            for (i in all.indices) {
                val inFirst = i >= rest.before.size && i < rest.before.size + mine.size
                if (inFirst || built[i] != null) slot[i] = next++
            }
            if (beforeItems.isNotEmpty()) c.addMediaItems(0, beforeItems)
            if (afterItems.isNotEmpty()) c.addMediaItems(afterItems)
            currentQueueTracks = all.filterIndexed { i, _ -> slot[i] >= 0 }
            val order = rest.playOrder?.map { slot.getOrElse(it) { -1 } }?.filter { it >= 0 }
            if (order != null && order.size == next) {
                c.shuffleModeEnabled = true
                c.sendCustomCommand(
                    SessionCommand(StashPlaybackService.COMMAND_SET_SHUFFLE_ORDER, Bundle.EMPTY),
                    Bundle().apply { putIntArray(StashPlaybackService.EXTRA_SHUFFLE_ORDER, order.toIntArray()) },
                )
            }
            Log.i(TAG, "handoff: queue filled, ${beforeItems.size} before, ${afterItems.size} after, shuffled=${order != null}")
            plan.onFilled(all.size - next)
            prefetchNextTrack()
        }
        return true
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
        scope.launch { resumeQueue(play = true) }
    }

    override suspend fun loadRestoredQueue(): Boolean {
        if (!ghostSession) return false
        resumeQueue(play = false)
        return true
    }

    /** Restores the persisted queue: playing ([resumeLastQueue]) or paused and unprepared (after Listen Together). */
    private suspend fun resumeQueue(play: Boolean) {
        restoreMutex.withLock {
            try {
                // A tap on the #462 ghost loaded the session while this waited: play it, rather than put
                // the saved song back over the one just tapped.
                if (play && !inListenTogether) {
                    val loaded = controllerDeferred?.takeIf { it.isConnected && it.mediaItemCount > 0 }
                    if (loaded != null) {
                        loaded.play()
                        return
                    }
                }
                val plan = playbackResumer.buildResumePlan()
                if (plan != null) {
                    // A new session started while the plan was being built: it owns the player now. Its own
                    // end restores again; the finally below still clears this restore's flag (`active` covers the gap).
                    if (!play && listenTogether?.active?.value == true) return
                    loadPlan(plan, plan.startIndex, plan.positionMs, play)
                    return
                }
                if (!play) return // after a session with nothing saved, there is nothing to put back
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
            } finally {
                // The user's queue is back (or there was none): saving may resume. On every exit, so a throw can't jam the gate.
                if (!play) listenTogether?.restorePending = false
            }
        }
    }

    /** Loads a persisted [plan] into the player with its shuffle, repeat and source, starting at [startIndex]. */
    private suspend fun loadPlan(
        plan: PlaybackResumer.ResumePlan,
        startIndex: Int,
        startPositionMs: Long,
        play: Boolean,
    ) {
        val tracks = plan.tracks.map { it.toDomain() }
        val controller = ensureController()
        controller?.shuffleModeEnabled = plan.isShuffled
        controller?.repeatMode = plan.repeatMode.toPlayerRepeatMode()
        setQueueInternal(tracks, startIndex, startPositionMs, plan.source, play, restore = true)
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
     *  - Data saver applies (the switch on, a metered network): no look-ahead on mobile data.
     *
     * Failures are logged and swallowed — the original (possibly stale)
     * MediaItem stays in place and [RefreshingDataSourceFactory] handles
     * any 403 at playback time, exactly as before this prefetch existed.
     */
    internal suspend fun prefetchNextTrack() {
        if (listenTogetherOwnsQueue) return
        if (playsOnBrowser) return // the browser holds and resolves the next song itself
        val controller = controllerDeferred ?: return
        val tracks = currentQueueTracks
        val nextTimelineIndex = controller.nextMediaItemIndex
        if (nextTimelineIndex !in 0 until controller.mediaItemCount) return
        val nextId = controller.getMediaItemAt(nextTimelineIndex)
            .mediaMetadata.extras?.getLong(EXTRA_TRACK_ID, -1L) ?: return
        // An All Songs file plays from its file or not at all: nothing to resolve or swap in.
        if (isScannedTrackId(nextId)) return
        val queued = tracks.firstOrNull { it.id == nextId } ?: return
        // The queue's copy is as old as the queue: a download that finished since is only
        // in the row. Read it, so the next song plays its new file instead of a stream.
        val row = withContext(Dispatchers.IO) { trackDao.getById(nextId) }
        val next = if (row != null && row.isDownloaded && !queued.isDownloaded) {
            queued.copy(
                isDownloaded = true,
                filePath = row.filePath,
                fileSizeBytes = row.fileSizeBytes,
                fileFormat = row.fileFormat,
            )
        } else {
            queued
        }
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
            if (hasUsableLocal) {
                if (next !== queued) playNextFromFile(controller, nextTimelineIndex, next)
                return
            }
        }
        // Download mode never streams, so there is nothing to warm.
        if (!streamAllowed()) return
        // Only a CONFIRMED-unstreamable row (checked and false) is skipped.
        // Synced-library rows are all "never checked" (isStreamable=false,
        // isStreamableCheckedAt=null) — the bare-flag gate silently killed
        // next-track prefetch for every synced track.
        if (next.isUnavailableForDisplay) return
        // A cloud song's queue item already carries its stash-cloud:// address and resolves its
        // own link at open(); the online resolver below must never see it.
        if (next.source == com.stash.core.model.MusicSource.CLOUD) return

        // Asked BEFORE the cache is read: the policy re-takes its decision, so a link saved
        // under another one (a FLAC link from Wi-Fi, now on mobile data) is dropped first.
        val dataSaver = qualityPolicy?.dataSaverApplies() == true

        // Fresh-cache check — avoid redundant resolve work when the URL is
        // good. Still upgrade the timeline slot before returning: the URL may
        // have been cached by LazyResolvingDataSource (cold jump), which can't
        // touch item metadata, so the placeholder would keep its unstamped
        // extras and Now Playing would read the "opus" fallback for it. Under
        // Data saver the cache serves no lossless link (StreamUrlCache.get), so
        // only a link resolved under Data saver is swapped in: no new bytes.
        val cached = streamUrlCache.get(next.id)?.takeIf {
            (next.soundCloudId == null && next.source != com.stash.core.model.MusicSource.SOUNDCLOUD) || it.origin == "soundcloud"
        }
        val nowMs = System.currentTimeMillis()
        if (cached != null && cached.expiresAtMs > nowMs + PREFETCH_FRESH_THRESHOLD_MS) {
            refreshControllerMediaItem(controller, nextTimelineIndex, next, cached)
            return
        }

        // Data saver on mobile data: no speculative look-ahead. The slot stays a placeholder,
        // which ExoPlayer's own buffering resolves ahead of the transition (LazyResolvingDataSource),
        // so the song still starts on time; a song skipped before then costs nothing. After the
        // fresh-cache upgrade above (no network) and the downloaded-file swap (none either).
        if (dataSaver) {
            Log.d(TAG, "prefetch-next skipped id=${next.id}: Data saver on a metered network")
            return
        }

        // In-flight dedup. Both the current-index watcher and setQueue's
        // explicit kick can fire for the same next-up, and
        // the fresh-cache check above can't dedup CONCURRENT resolves of the
        // same next-up (none has cached yet). Without this guard a single
        // advance fans out up to 3 identical resolves — 3× the quota burn on a
        // capped lossless source (verified on-device 2026-06-21). Claim the id; a racing call for
        // the same next-up returns immediately and lets the winner cache it.
        if (!prefetchInFlight.add(next.id)) return
        val qualityChangesAtStart = streamQualityChanges

        val t0 = System.currentTimeMillis()
        Log.d("LATDIAG", "prefetch-next-start id=${next.id} youtubeId=${next.youtubeId}")
        val entity = row ?: next.toEntity()
        val resolved = try {
            // Full-fat resolve (allowYtDlp = true) for the single next-up track
            // during active playback, so auto-advance stays seamless during a
            // Qobuz-proxy outage even if it falls through to the YouTube path.
            // speculative: a Tidal-only track is left to the real play (the user may skip
            // it); the shared Tidal relay pool is small.
            streamResolver.resolve(entity, allowYouTube = true, allowYtDlp = true, speculative = true)
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
        if (qualityChangesAtStart != streamQualityChanges) {
            // The streaming quality changed while this resolved (reresolveNextStream found it in
            // flight and left it to finish): its answer may be the old quality's. Ask again.
            Log.d("LATDIAG", "prefetch-next-end id=${next.id} outcome=quality-changed; resolving again")
            prefetchNextTrack()
            return
        }
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
     * An add-on that leaves [addons] (removed, switched off, turned off by Stash) is not asked again,
     * and no link it handed out plays again: [resolveAgainLinksFrom]. The song playing plays to its end.
     */
    internal suspend fun resolveAgainWhenAnAddonLeaves(addons: kotlinx.coroutines.flow.Flow<List<com.stash.core.addons.InstalledAddon>>) {
        var on = emptySet<String>()
        addons.collect { list ->
            val now = list.mapTo(HashSet()) { it.id }
            val left = on - now
            on = now
            if (left.isEmpty()) return@collect
            val origins = left.mapTo(HashSet()) { com.stash.core.media.streaming.AddonStreamRung.ORIGIN_PREFIX + it }
            resolveAgainLinksFrom(origins, refusedTrackId = null, why = "is no longer on")
        }
    }

    /**
     * The player refused a stream an add-on answered ([com.stash.core.media.streaming.AddonStreamCheck]):
     * the add-on is set aside, and no link it handed out plays: [resolveAgainLinksFrom]. The refused
     * song itself, if it is the next one read ahead or one further on, is among them; if it is the
     * song playing, [playerListener]'s `onPlayerError` resolves it again.
     */
    internal suspend fun resolveAgainWhenAStreamIsRefused(refusals: kotlinx.coroutines.flow.Flow<com.stash.core.media.streaming.AddonStreamCheck.Refused>) {
        refusals.collect { refused ->
            resolveAgainLinksFrom(setOf(refused.origin), refusedTrackId = refused.trackId, why = "was set aside")
        }
    }

    /**
     * The one path for an add-on whose links must not play any more: it left (removed, switched off,
     * turned off by Stash), or the player's check set it aside (it sent a file that wasn't what it said).
     *
     * Every link one of [origins] handed out is dropped from [streamUrlCache], and every queued song but
     * the one playing whose slot came from one of them is put back as a fresh placeholder: a slot holding
     * the add-on's URL (its origin extra says so), and a placeholder the player opened from it, which keeps
     * no origin of its own but may already be read ahead into the player, a song or two past this one
     * ([com.stash.core.media.streaming.AddonStreamCheck.openedFrom]; #885 item 281). A placeholder put
     * back with the same URI would keep its source and what it loaded, so it gets another
     * ([com.stash.core.media.streaming.resolvedAgain]), and Media3 builds a new source that opens and
     * resolves without the add-on. The next song is then looked up again ([prefetchNextTrack]); the
     * others resolve as they play. [refusedTrackId]'s slot is put back whatever it holds.
     */
    internal suspend fun resolveAgainLinksFrom(origins: Set<String>, refusedTrackId: Long?, why: String) {
        // A look-ahead already under way may be resolving the next song through one of them, its slot
        // still a placeholder with nothing to look at below. Counted as a change, that look-ahead asks
        // again, now without it, rather than put its answer in the slot (review-885 round 2, R2-M1).
        streamQualityChanges++
        origins.forEach { streamUrlCache.invalidateOrigin(it) }
        if (listenTogetherOwnsQueue) return
        if (playsOnBrowser) return // the browser finds and streams each song itself
        try {
            val controller = controllerDeferred ?: return
            val playing = controller.currentMediaItemIndex
            val next = controller.nextMediaItemIndex
            var again = 0
            var nextAgain = false
            for (index in 0 until controller.mediaItemCount) {
                if (index == playing) continue // plays on; a refused one is onPlayerError's
                val item = controller.getMediaItemAt(index)
                val trackId = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID, 0L)?.takeIf { it != 0L } ?: continue
                val from = item.mediaMetadata.extras?.getString(EXTRA_STREAM_ORIGIN) in origins ||
                    trackId == refusedTrackId ||
                    addonStreamCheck?.openedFrom(trackId) in origins
                if (!from) continue
                streamUrlCache.invalidate(trackId)
                val fresh = resolvedAgainItem(item, trackId)
                addonStreamCheck?.forget(trackId)
                // Put back before the player reached it: its refusal will never be an error to read.
                if (trackId == refusedTrackId) addonStreamCheck?.takeRefusal(trackId)
                replaceMediaItemKeepingShuffle(controller, index, fresh)
                again++
                if (index == next) nextAgain = true
            }
            if (again == 0) return
            Log.i(TAG, "$again queued song(s) had a link from an add-on that $why: resolving them again")
            if (nextAgain) prefetchNextTrack()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "queued songs were not resolved again after an add-on $why: ${e.javaClass.simpleName}")
        }
    }

    /**
     * [item] as a fresh `stash-resolve://` placeholder for song [trackId], without the stream stamps
     * of the link it held (they describe a stream it no longer plays), and with a URI the player
     * has not seen for it, so it builds a new source. A slot that was a placeholder keeps its own
     * resolve inputs; one that held a URL gets the queue's placeholder for the song, or one built
     * from the slot's own words for a song outside it (a queue the service built).
     */
    private fun resolvedAgainItem(item: MediaItem, trackId: Long): MediaItem {
        val uri = item.localConfiguration?.uri
        val extras = item.mediaMetadata.extras
        val placeholder = when {
            uri?.scheme == STASH_RESOLVE_SCHEME -> uri.resolvedAgain()
            else -> currentQueueTracks.firstOrNull { it.id == trackId }
                ?.takeIf { it.source != com.stash.core.model.MusicSource.CLOUD }
                ?.toStreamPlaceholderMediaItem()?.localConfiguration?.uri
                ?: stashResolveUri(
                    trackId = trackId,
                    youtubeId = extras?.getString(EXTRA_TRACK_YOUTUBE_ID),
                    title = item.mediaMetadata.title?.toString(),
                    artist = item.mediaMetadata.artist?.toString(),
                    album = item.mediaMetadata.albumTitle?.toString(),
                    durationMs = extras?.getLong(EXTRA_TRACK_DURATION_MS, 0L) ?: 0L,
                )
        }
        val stripped = Bundle(extras ?: Bundle()).apply { streamStampKeys.forEach(::remove) }
        return item.buildUpon()
            .setUri(placeholder)
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(stripped).build())
            .build()
    }

    /**
     * The next song was resolved when this one started, under the old setting: its URL is
     * cached, and usually in its slot too, so a quality change would only have reached the
     * song after it. The slot goes back to a placeholder and the cached URL is dropped, then
     * the prefetch resolves it again under the new setting. Where that prefetch stops short
     * (a speculative resolve never asks the Tidal relay), the placeholder resolves when the
     * song plays, still under the new setting. Either resolve also drops every other cached
     * URL ([com.stash.core.media.streaming.StreamQualityPolicy] sees the change).
     */
    override suspend fun reresolveNextStream() {
        if (listenTogetherOwnsQueue) return
        if (playsOnBrowser) return // nothing streams on this phone while a browser plays
        val controller = controllerDeferred ?: return
        val index = controller.nextMediaItemIndex
        // No next song, or it is this one again (a one-song queue on repeat): a new source would restart it.
        if (index !in 0 until controller.mediaItemCount || index == controller.currentMediaItemIndex) return
        val item = controller.getMediaItemAt(index)
        val nextId = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID, -1L) ?: return
        streamQualityChanges++
        streamUrlCache.invalidate(nextId)
        // Only a slot holding a resolved stream: a file, a placeholder or a cloud song is already right.
        val scheme = item.localConfiguration?.uri?.scheme
        val queued = currentQueueTracks.firstOrNull { it.id == nextId }
        if ((scheme == "https" || scheme == "http") && queued != null) {
            val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply { streamStampKeys.forEach(::remove) }
            val placeholder = item.buildUpon()
                .setUri(queued.toStreamPlaceholderMediaItem().localConfiguration?.uri)
                .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
                .build()
            replaceMediaItemKeepingShuffle(controller, index, placeholder)
        }
        prefetchNextTrack()
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
        // Playback reached the slot while it resolved (a re-resolve late in a song, a short
        // song), or it is the one playing (a one-song queue on repeat): a new source would
        // restart it. The URL stays cached for the next time it is opened.
        if (!isStillNextUp(controller.currentMediaItemIndex, controller.nextMediaItemIndex, timelineIndex)) return false
        val item = controller.getMediaItemAt(timelineIndex)
        val itemTrackId = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID) ?: return false
        if (itemTrackId != next.id) return false
        val newExtras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            resolved.codec?.let { putString(EXTRA_STREAM_CODEC, it) }
            resolved.bitsPerSample?.let { putInt(EXTRA_STREAM_BIT_DEPTH, it) }
            resolved.sampleRateHz?.let { putInt(EXTRA_STREAM_SAMPLE_RATE, it) }
            resolved.bitrateKbps?.let { putInt(EXTRA_STREAM_BITRATE, it) }
            resolved.origin?.let { putString(EXTRA_STREAM_ORIGIN, it) }
            // The slot's URL is a manifest, or no longer one (an earlier resolve's word must not stay).
            if (resolved.isDash) putBoolean(EXTRA_STREAM_DASH, true) else remove(EXTRA_STREAM_DASH)
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
            // A cloud copy keeps the placeholder (its source has the fallback); see buildMediaItemForTrack.
            .apply { if (resolved.origin != com.stash.core.media.streaming.CloudCopyFinder.ORIGIN && resolved.origin != "soundcloud") setUri(resolved.url) }
            .setMediaMetadata(refreshedMeta.build())
            .build()
        replaceMediaItemKeepingShuffle(controller, timelineIndex, refreshed)
        return true
    }

    /**
     * Points the next-up's [timelineIndex] slot at [next]'s downloaded file: its
     * download finished after the queue was built, and the slot still holds a
     * placeholder or a stream an earlier prefetch swapped in. Keeps the slot's
     * metadata (art, extras) minus the stream stamps, which describe a stream it no
     * longer plays, and updates the queue's copy so later reads see the file.
     */
    private fun playNextFromFile(controller: MediaController, timelineIndex: Int, next: Track) {
        if (!isStillNextUp(controller.currentMediaItemIndex, controller.nextMediaItemIndex, timelineIndex)) {
            // Playback moved while the row and file were read (an advance, a tap): the slot may be the one
            // playing now, and swapping its source would restart it mid-song (review on #730).
            Log.d(TAG, "next-up id=${next.id} isn't next any more; leaving its slot alone")
            return
        }
        if (timelineIndex !in 0 until controller.mediaItemCount) return
        val item = controller.getMediaItemAt(timelineIndex)
        if (item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID) != next.id) return
        val fileItem = next.toMediaItem()
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            streamStampKeys.forEach(::remove)
            putBoolean(EXTRA_TRACK_IS_STREAMABLE, false)
        }
        val refreshed = item.buildUpon()
            .setUri(fileItem.localConfiguration?.uri)
            .setRequestMetadata(fileItem.requestMetadata)
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
        replaceMediaItemKeepingShuffle(controller, timelineIndex, refreshed)
        currentQueueTracks = currentQueueTracks.map { if (it.id == next.id) next else it }
        Log.d(TAG, "next-up id=${next.id} downloaded since it was queued; plays its file")
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
        val controller = ensureController() ?: return RadioStartResult.PlayerNotReady
        // A station is songs found online: Download mode plays only what is on the phone.
        if (!streamAllowed()) return RadioStartResult.DownloadMode
        val (session, generated) = radioGenerator.start(seed)
        if (generated.isEmpty()) return RadioStartResult.NoStation
        // Library ids before queueing: the generator's ids are videoId hashes with no row, so a
        // saved radio queue resumed into nothing after the app was closed (withQueueIdentity).
        val firstBatch = generated.map { it.withQueueIdentity() }
        radioSession = session
        radioActive = true
        // Only ONE grower may run: startRadio bypasses setQueueInternal (which is
        // what normally disarms library shuffle), so disarm it here explicitly —
        // otherwise both watchers append as the queue drains and library tracks
        // pollute the station.
        libraryShuffleActive = false
        librarySnapshot = emptyList()
        // A station replaces the queue (or all of it but the playing song): no big queue behind it.
        dropWindow("a radio station replaced the queue")

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
            val seedKey = trackIdentity(songSeed.title, songSeed.artist)
            val seedTrack = firstBatch.firstOrNull { trackIdentity(it.title, it.artist) == seedKey }
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
            resetPlayHistory()
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

    /** Append the next generated batch to the station queue. Single-flight via
     *  [radioGrowMutex] so a flurry of state emissions can't fan out into
     *  concurrent grows. Internal as a test seam (the watcher that fires it is
     *  driven by the private _playerState listener, device-verified). */
    internal suspend fun growRadio() {
        radioGrowMutex.withLock {
            if (!radioActive) return
            // An empty player has no station queue to extend: appending would make the batch a new queue.
            val controller = controllerDeferred?.takeIf { it.mediaItemCount > 0 } ?: return
            val session = radioSession ?: return
            val batch = radioGenerator.nextBatch(session)
            if (batch.isEmpty()) return
            if (!radioActive || !streamAllowed()) {
                stopRadio()
                return
            }
            // Library ids, as in startRadio, so the grown queue resumes too. Streaming tracks →
            // stash-resolve:// placeholders (see startRadio). The logical queue first, as in
            // addNext: the add is reported, and the queue published, during the call.
            val queued = batch.map { it.withQueueIdentity() }
            currentQueueTracks = currentQueueTracks + queued
            controller.addMediaItems(queued.map { it.toQueueMediaItem() })
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
            val controller = controllerDeferred?.takeIf { it.mediaItemCount > 0 } ?: return // as in growRadio
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

            currentQueueTracks = currentQueueTracks + toAppend // first, as in growRadio
            controller.addMediaItems(toAppend.map { it.toMediaItem() })
        }
    }

    // ---- Big queues: the window ----
    //
    // A queue of more than QUEUE_WINDOW_SIZE playable songs keeps its whole order in [queueWindow]
    // as ids, and the player holds a window of it: the timeline IS the window, slot for slot, in
    // play order. Everything that changes the timeline changes the window too, in the same
    // main-thread step, the window first (the controller reports a change during the call, as
    // addNext notes). Songs joining the window are read back from the library by id.

    /** True when the window should slide: it nears its end with more after it, or its start with more before. */
    private fun windowNeedsSlide(): Boolean {
        val window = queueWindow ?: return false
        val controller = controllerDeferred ?: return false
        val cur = controller.currentMediaItemIndex
        if (cur !in 0 until controller.mediaItemCount) return false
        val repeatAll = controller.repeatMode == Player.REPEAT_MODE_ALL
        if (!repeatAll && window.wrapped) return true
        val ahead = window.count - 1 - cur
        return (ahead < QUEUE_WINDOW_LOW_WATER && window.forwardGap(repeatAll) > 0) ||
            (cur < QUEUE_WINDOW_LOW_WATER_BEHIND && window.backwardGap(repeatAll) > 0)
    }

    /**
     * Keeps the big queue's window around the playing song: when fewer than QUEUE_WINDOW_LOW_WATER
     * songs are left after it, the next ones join (read by id, built on IO) and the songs more than
     * QUEUE_WINDOW_BEHIND before it leave; going back, the songs before it join and the far end
     * leaves. Under repeat-all the order is a circle, so the song after the last is the first of the
     * whole list; with repeat-all off a window that ran past the end is cut back. A song that can't
     * be read back (deleted since) or can't play in this mode any more leaves the order.
     * Single-flight with the library shuffle's grows ([growMutex]); a no-op once the player holds
     * something else. Internal: test seam.
     */
    internal suspend fun slideWindow() {
        growMutex.withLock {
            // Bounded: each pass either adds songs or takes unplayable ones out of the order.
            repeat(MAX_SLIDE_PASSES) {
                if (!slideWindowOnce()) return
            }
        }
    }

    /** One step of [slideWindow]; false when there is nothing (more) to do. */
    private suspend fun slideWindowOnce(): Boolean {
        val window = queueWindow ?: return false
        if (listenTogetherOwnsQueue || ghostSession || windowUnconfirmed || queueBuildsInFlight.get() > 0) return false
        val controller = controllerDeferred ?: return false
        if (!windowInSync(controller, window)) {
            dropWindow("the player's queue changed under it")
            return false
        }
        val repeatAll = controller.repeatMode == Player.REPEAT_MODE_ALL
        if (!repeatAll) {
            window.unwrapRange(controller.currentMediaItemIndex)?.let { range ->
                applyWindowDrop(controller, window, range)
                return true
            }
        }
        val cur = controller.currentMediaItemIndex
        if (cur < 0) return false
        val ahead = window.count - 1 - cur
        val forwardGap = window.forwardGap(repeatAll)
        if (ahead < QUEUE_WINDOW_LOW_WATER && forwardGap > 0) {
            // The far back goes first, so the player (and SystemUI's copy of the queue) never holds
            // more than QUEUE_WINDOW_SIZE: adding first briefly made it about 900.
            val drop = (cur - QUEUE_WINDOW_BEHIND).coerceAtLeast(0)
            if (drop > 0) applyWindowDrop(controller, window, 0 until drop)
            val want = minOf(window.forwardGap(repeatAll), QUEUE_WINDOW_SIZE - window.count)
            if (want <= 0) return drop > 0
            val version = window.version
            val loaded = loadWindowSongs(window, window.forwardPositions(want)) ?: return false
            // Revalidate after the IO hop: a new queue, an edit or another controller may have moved on.
            if (queueWindow !== window || windowUnconfirmed || !windowInSync(controller, window)) return false
            // The same window, changed meanwhile (a deletion): the positions read are stale. Again.
            if (window.version != version) return true
            val found = BooleanArray(loaded.size) { loaded[it] != null }
            val songs = loaded.filterNotNull()
            window.admitForward(found)
            if (songs.isNotEmpty()) {
                currentQueueTracks = currentQueueTracks + songs.map { it.first }
                controller.addMediaItems(songs.map { it.second })
            }
            Log.d(TAG, "window: +${songs.size} ahead (${loaded.size - songs.size} gone), -$drop behind, $window")
            return true
        }
        val backwardGap = window.backwardGap(repeatAll)
        if (cur < QUEUE_WINDOW_LOW_WATER_BEHIND && backwardGap > 0) {
            val want = minOf(backwardGap, QUEUE_WINDOW_BEHIND - cur)
            if (want <= 0) return false
            val version = window.version
            val loaded = loadWindowSongs(window, window.backwardPositions(want)) ?: return false
            if (queueWindow !== window || windowUnconfirmed || !windowInSync(controller, window)) return false
            if (window.version != version) return true // see the forward slide
            val found = BooleanArray(loaded.size) { loaded[it] != null }
            val songs = loaded.filterNotNull()
            window.admitBackward(found)
            if (songs.isNotEmpty()) {
                currentQueueTracks = songs.map { it.first } + currentQueueTracks
                controller.addMediaItems(0, songs.map { it.second })
            }
            val over = window.count - QUEUE_WINDOW_SIZE
            if (over > 0) applyWindowDrop(controller, window, (window.count - over) until window.count)
            Log.d(TAG, "window: +${songs.size} behind (${loaded.size - songs.size} gone), $window")
            return true
        }
        return false
    }

    /** The big queue's window when the player still holds it; else null (and window mode ends). */
    private fun activeWindow(controller: MediaController): QueueWindow? {
        val window = queueWindow ?: return null
        if (listenTogetherOwnsQueue || windowUnconfirmed) return null
        if (windowInSync(controller, window)) return window
        dropWindow("the player's queue changed under it")
        return null
    }

    /** An All Songs file can't be read back by id: the window keeps it ([windowPinned]). */
    private fun pinIfNeeded(track: Track) {
        if (track.isScannedSong) windowPinned = windowPinned + (track.id to track)
    }

    /**
     * Appends [tracks] to the end of the big queue's whole order ("Add to queue" plays after
     * everything queued). They join the player too when the window reaches the order's end, as many
     * as its size allows ([itemsFor] builds the first n); the rest come with the window. A window
     * running past the end under repeat-all is cut back first, so the order's end is its end.
     */
    private fun appendToWindow(
        controller: MediaController,
        window: QueueWindow,
        tracks: List<Track>,
        itemsFor: (Int) -> List<MediaItem>,
    ) {
        window.unwrapRange(controller.currentMediaItemIndex)?.let { applyWindowDrop(controller, window, it) }
        val into = if (window.endsAtOrderEnd) minOf(tracks.size, (QUEUE_WINDOW_SIZE - window.count).coerceAtLeast(0)) else 0
        window.appendIds(LongArray(tracks.size) { tracks[it].id }, into)
        tracks.forEach(::pinIfNeeded)
        if (into > 0) {
            currentQueueTracks = currentQueueTracks + tracks.take(into)
            controller.addMediaItems(itemsFor(into))
        } else {
            // Nothing reached the player, so no event will publish the longer queue.
            updateState(controller)
        }
        Log.i(TAG, "window: ${tracks.size} added to the end, $into into the player, $window")
    }

    /**
     * Makes the player's whole queue a big queue's window (start 0, every slot), for an "Add to
     * queue" that would take it past [QUEUE_WINDOW_SIZE]. Only with shuffle off (the timeline is
     * then the play order) and when the logical queue mirrors the timeline; null otherwise, and the
     * add goes in as before.
     */
    private fun windowFromQueue(controller: MediaController): QueueWindow? {
        if (controller.shuffleModeEnabled || listenTogetherOwnsQueue || ghostSession) return null
        val count = controller.mediaItemCount
        val logical = currentQueueTracks
        if (count == 0 || logical.size != count) return null
        for (i in 0 until count) {
            if (controller.getMediaItemAt(i).mediaId != logical[i].id.toString()) return null
        }
        val window = QueueWindow.whole(LongArray(count) { logical[it].id })
        queueWindow = window
        windowPinned = logical.filter { it.isScannedSong }.associateBy { it.id }
        setKeepTimelineOrder(controller, true)
        Log.i(TAG, "window: the $count-song queue becomes a big queue's window")
        return window
    }

    /** Takes timeline [range] (the window's start or its end) out of the window and the player. */
    private fun applyWindowDrop(controller: MediaController, window: QueueWindow, range: IntRange) {
        if (range.isEmpty()) return
        val n = range.last - range.first + 1
        if (range.first == 0) {
            window.dropFront(n)
            currentQueueTracks = currentQueueTracks.drop(n)
        } else {
            window.dropBack(n)
            currentQueueTracks = currentQueueTracks.take(range.first)
        }
        controller.removeMediaItems(range.first, range.last + 1)
    }

    /**
     * Whether the player still holds [window]: same size, and the same songs at its start, at the
     * playing slot and at its end. False once another controller (Android Auto, a media button's
     * resumption) or a path that doesn't know the window replaced the queue.
     */
    private fun windowInSync(controller: MediaController, window: QueueWindow): Boolean {
        val count = controller.mediaItemCount
        if (count == 0 || count != window.count) return false
        val cur = controller.currentMediaItemIndex.coerceIn(0, count - 1)
        return intArrayOf(0, cur, count - 1).all { slot ->
            controller.getMediaItemAt(slot).mediaId == window.idAtSlot(slot).toString()
        }
    }

    /**
     * Leaves window mode ([reason] for the log): the player's queue is no longer the big one. The
     * service is told, when connected, so it stops keeping the timeline order.
     */
    private fun dropWindow(reason: String) {
        if (queueWindow == null) return
        Log.i(TAG, "window: dropped, $reason")
        queueWindow = null
        windowPinned = emptyMap()
        windowUnconfirmed = false
        controllerDeferred?.let { setKeepTimelineOrder(it, false) }
    }

    /**
     * Checks an unconfirmed window ([windowUnconfirmed]) against the player behind [controller]:
     * - still the window (the service lived on): the service is asked again to keep the timeline
     *   order (it let the request go when our last controller disconnected), and the window
     *   follows a shuffle the car or a headset turned on or off meanwhile;
     * - something else (a new service that another controller filled): window mode ends, telling
     *   the service so;
     * - empty (a new service): nothing yet. The ghost's restore loads the saved window, which takes
     *   the whole order back ([setQueueBuilding]); anything else replaces it.
     */
    private fun confirmWindow(controller: MediaController) {
        if (!windowUnconfirmed || listenTogetherOwnsQueue) return
        val window = queueWindow ?: run { windowUnconfirmed = false; return }
        if (controller.mediaItemCount == 0) return
        windowUnconfirmed = false
        if (!holdsWindow(controller, window)) {
            dropWindow("a new connection found another queue")
            return
        }
        Log.i(TAG, "window: the player still holds it after a new connection, $window")
        setKeepTimelineOrder(controller, true)
        if (controller.shuffleModeEnabled != window.isShuffled) scope.launch { rewindowForShuffle() }
    }

    /** [windowInSync], song by song: for taking a window back, where a near miss must not pass. */
    private fun holdsWindow(controller: MediaController, window: QueueWindow): Boolean =
        controller.mediaItemCount == window.count &&
            (0 until window.count).all { controller.getMediaItemAt(it).mediaId == window.idAtSlot(it).toString() }

    /** [holdsWindow] for items about to be set. */
    private fun holdsWindow(items: List<MediaItem>, window: QueueWindow): Boolean =
        items.size == window.count && items.indices.all { items[it].mediaId == window.idAtSlot(it).toString() }

    /**
     * A big queue's timeline is already in play order, shuffle or not: asks the service to keep
     * Media3's shuffle order the identity on every change while [on] (an insert under shuffle
     * otherwise lands at a random place in the walk). The service does it, not this controller: a
     * shuffle order sent from here can arrive before the items it is for (see the service).
     *
     * Sent every time, never cached here: the service's flag goes with it when the service
     * idle-stops and comes back (a new instance) or another controller sets the queue, and a cached
     * "already on" then left a new window walked in Media3's own random order under shuffle.
     */
    private fun setKeepTimelineOrder(controller: MediaController, on: Boolean) {
        controller.sendCustomCommand(
            SessionCommand(StashPlaybackService.COMMAND_KEEP_TIMELINE_ORDER, Bundle.EMPTY),
            Bundle().apply { putBoolean(StashPlaybackService.EXTRA_KEEP_TIMELINE_ORDER, on) },
        )
    }

    /**
     * The songs at play [positions] of [window], read back from the library by id (All Songs files
     * from [windowPinned]) and built as queue items, in order; null for one that is gone or can't
     * play in this mode. Null when the read failed. The window is read here, on the main thread;
     * the rest runs on IO.
     */
    private suspend fun loadWindowSongs(window: QueueWindow, positions: IntArray): List<Pair<Track, MediaItem>?>? {
        if (positions.isEmpty()) return emptyList()
        val ids = LongArray(positions.size) { window.idAtPosition(positions[it]) }
        val pinned = windowPinned
        val streamOk = streamAllowed()
        return try {
            withContext(Dispatchers.IO) {
                val wanted = ids.filter { it !in pinned }.distinct()
                val rows = if (wanted.isEmpty()) emptyMap() else trackDao.getByIds(wanted).associateBy { it.id }
                ids.map { id ->
                    val track = pinned[id] ?: rows[id]?.toDomain() ?: return@map null
                    val kind = track.initialQueueKind(validateLocalFile = false, streamAllowed = streamOk)
                        ?: return@map null
                    track to track.toQueueItemOfKind(kind)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "window: reading ${ids.size} songs failed", e)
            null
        }
    }

    /**
     * Shuffle went on or off (from anywhere: Now Playing, the notification, the car) while a big
     * queue plays. Shuffle is over the whole list: on, the whole order is shuffled with the playing
     * song first; off, the window goes back to the unshuffled list around it. The playing song stays
     * in the player and keeps playing; the songs around it are replaced. Internal: test seam.
     */
    internal suspend fun rewindowForShuffle() {
        growMutex.withLock {
            // Playback may move during the read; then the read is redone around the new song.
            repeat(MAX_SLIDE_PASSES) { if (rewindowOnce()) return }
        }
    }

    /** One try of [rewindowForShuffle]; true when there is nothing (more) to do. */
    private suspend fun rewindowOnce(): Boolean {
        val window = queueWindow ?: return true
        if (listenTogetherOwnsQueue || ghostSession || windowUnconfirmed || queueBuildsInFlight.get() > 0) return true
        val controller = controllerDeferred ?: return true
        val shuffle = controller.shuffleModeEnabled
        if (shuffle == window.isShuffled) return true
        if (!windowInSync(controller, window)) {
            dropWindow("the player's queue changed under it")
            return true
        }
        val cur = controller.currentMediaItemIndex
        if (cur < 0) return true
        val version = window.version
        val target = if (shuffle) window.shuffledFrom(cur, windowRandom) else window.unshuffledFrom(cur)
        val repeatAll = controller.repeatMode == Player.REPEAT_MODE_ALL
        // Shuffle on starts a new order at this song: nothing came before it.
        val behindWant = if (shuffle) 0 else minOf(QUEUE_WINDOW_BEHIND, target.backwardGap(repeatAll))
        val aheadWant = minOf(QUEUE_WINDOW_SIZE - 1 - behindWant, target.forwardGap(repeatAll) - if (repeatAll) behindWant else 0)
            .coerceAtLeast(0)
        val ahead = loadWindowSongs(target, target.forwardPositions(aheadWant)) ?: return true
        val behind = loadWindowSongs(target, target.backwardPositions(behindWant)) ?: return true
        if (queueWindow !== window || windowUnconfirmed || !windowInSync(controller, window) || controller.shuffleModeEnabled != shuffle) {
            return queueWindow !== window || windowUnconfirmed
        }
        // The window changed during the read (a deletion): the new order was made from the old one. Again.
        if (window.version != version) return false
        val curNow = controller.currentMediaItemIndex
        if (curNow != cur) return false // playback moved during the read: again, around the new song
        target.admitForward(BooleanArray(ahead.size) { ahead[it] != null })
        target.admitBackward(BooleanArray(behind.size) { behind[it] != null })
        val aheadSongs = ahead.filterNotNull()
        val behindSongs = behind.filterNotNull()
        val playing = currentQueueTracks.getOrNull(cur) ?: return true
        queueWindow = target
        currentQueueTracks = behindSongs.map { it.first } + playing + aheadSongs.map { it.first }
        controller.replaceMediaItems(cur + 1, controller.mediaItemCount, aheadSongs.map { it.second })
        controller.replaceMediaItems(0, cur, behindSongs.map { it.second })
        setKeepTimelineOrder(controller, true)
        Log.i(TAG, "window: shuffle ${if (shuffle) "on over all ${target.total}" else "off"}, $target")
        scope.launch { prefetchNextTrack() }
        return true
    }

    /**
     * Queue identity is also the Media3 mediaId and StreamUrlCache key.
     * Native Qobuz rows reach per-track actions without a video id, which
     * previously mapped every one of them to id=0. Persist those transient
     * rows before queueing so different songs cannot share one cached URL.
     *
     * A synthetic stream id (`videoId.hashCode().toLong()`: radio, an artist page's or a
     * YouTube album's non-library songs) is persisted too, by the same test as
     * [MusicRepository.ensureTrackPersisted]: the saved queue holds these ids, and the cold-start
     * resume ([PlaybackResumer]) drops every id with no `tracks` row, so a radio queue came
     * back as nothing and a mixed one lost its current song. Search plays take this road since
     * 53814e39. If the save fails the synthetic id is kept: the song still plays, it just
     * won't come back after a restart. All Songs files keep their own ids (never saved).
     */
    private suspend fun Track.withQueueIdentity(): Track {
        val syntheticId = !youtubeId.isNullOrBlank() && id == youtubeId.hashCode().toLong()
        if (id == 0L) return copy(id = musicRepository.ensureTrackPersisted(this))
        if (!syntheticId || isScannedSong) return this
        return runCatching { copy(id = musicRepository.ensureTrackPersisted(this)) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "withQueueIdentity: could not persist '$title' — queued under a transient id", e)
                this
            }
    }

    /**
     * In Stream only a track may join the queue unless it is confirmed unavailable:
     * only connectivity and the cellular setting can refuse it, at resolve time, so a
     * not-yet-downloaded track enters as a stash-resolve:// placeholder. In Download
     * mode it needs a usable file on the phone.
     */
    private fun Track.isPlayableForQueueAppend(streamAllowed: Boolean): Boolean =
        if (streamAllowed) !isUnavailableForDisplay else hasUsableLocalFile()

    /** A downloaded (or All Songs) file that is on the phone and big enough to play. */
    private fun Track.hasUsableLocalFile(): Boolean {
        val localPath = filePath
        return (isDownloaded || isScannedSong) && !localPath.isNullOrBlank() &&
            fileSizeBytes !in 1 until StashConstants.MIN_PLAYABLE_LOCAL_BYTES &&
            filePathExistsOnDisk(localPath)
    }

    /**
     * False in Download mode: the player then plays only what is on the phone, never a
     * stream (owner, 2026-10-29: Download mode is offline again). Stream only streams
     * whatever isn't downloaded.
     */
    private suspend fun streamAllowed(): Boolean = streamingPreference.current()

    /**
     * The controller's shuffle walk (timeline indexes in play order), or null with
     * shuffle off. Read BEFORE an insert, for [placeAppendedInShuffleOrder].
     */
    private fun shuffleWalk(controller: MediaController): List<Int>? {
        val count = controller.mediaItemCount
        if (!controller.shuffleModeEnabled || count == 0) return null
        val timeline = controller.currentTimeline
        return QueueDisplay.walkShuffleOrder(count, timeline.getFirstWindowIndex(true)) {
            timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, true)
        }.takeIf { it.size == count }
    }

    private fun resetPlayHistory() = playHistory.reset()

    /**
     * Records a song change into [playHistory]. Called on every [updateState] (cheap unless the
     * song changed) and on every item transition ([reason]; null from updateState), because which
     * of the two sees a change first depends on the event: an auto-advance reports its position
     * discontinuity, and so an updateState, before its transition.
     *
     * A change is a new media id, or a SEEK/AUTO transition that keeps the id: the same song's
     * next copy (Play next of the playing song, a song queued twice). REPEAT is the same copy again.
     */
    private fun noteCurrentItem(controller: MediaController, reason: Int?) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
        val id = controller.currentMediaItem?.mediaId ?: return
        val previous = playHistory.current
        val sameIdMove = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
        if (previous != null && id == previous.mediaId && !sameIdMove) return
        val entry = historyEntryAt(controller, controller.currentMediaItemIndex) ?: return
        // A queue swapped in by another controller (Android Auto's onSetMediaItems, a media-button
        // resumption) arrives as a playlist change that took the old song away: its history no
        // longer applies. Our own queue replacements reset the history themselves.
        val previousGone = previous != null &&
            (reason == null || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) &&
            (0 until controller.mediaItemCount).none { controller.getMediaItemAt(it).mediaId == previous.mediaId }
        playHistory.moveTo(entry, previousGone)
    }

    override suspend fun learnArtwork(arts: Map<Long, String>) {
        if (arts.isEmpty()) return
        val controller = ensureController() ?: return
        withContext(Dispatchers.Main.immediate) {
            // The rows Up next shows (the logical queue), then the player's items: the playing one is the notification's
            currentQueueTracks = QueueArt.fill(currentQueueTracks, arts)
            val withArt = HashMap<Int, MediaItem>()
            for (i in 0 until controller.mediaItemCount) {
                val item = controller.getMediaItemAt(i)
                val id = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID, -1L)?.takeIf { it > 0 } ?: item.mediaId.toLongOrNull() ?: continue
                val art = arts[id] ?: continue
                if (item.mediaMetadata.artworkUri != null) continue
                // Metadata only: the same media (its URI, its Play next tag), so the player updates it in place (every
                // source here takes a metadata-only change, ResolveFirstMediaSource included): the playing song goes on,
                // the others keep their preparation, the shuffle order is untouched
                withArt[i] = item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtworkUri(Uri.parse(art)).build()).build()
            }
            // Neighbours in one call: each call is a timeline change (and a queue refresh for Android Auto)
            val runs = QueueArt.runs(withArt.keys)
            for (run in runs) controller.replaceMediaItems(run.first, run.last + 1, run.map { withArt.getValue(it) })
            timelineDirty = true
            updateState(controller)
            Log.d(TAG, "learnArtwork: ${arts.size} covers, ${withArt.size} player items in ${runs.size} replaces")
        }
    }

    /** replaceMediaItem, but under shuffle the play order is put back exactly as it was. */
    private fun replaceMediaItemKeepingShuffle(controller: MediaController, index: Int, replacement: MediaItem) {
        // A rebuilt item (a stream placeholder for a vanished download) keeps its Play next tag,
        // or the next Play next would go in ahead of it.
        val item = if (index in 0 until controller.mediaItemCount &&
            PlayNext.isTagged(controller.getMediaItemAt(index)) && !PlayNext.isTagged(replacement)
        ) {
            PlayNext.tag(replacement)
        } else {
            replacement
        }
        // Read BEFORE the replace. An identity walk means the controller's masking timeline
        // isn't the real order yet; re-sending that would unshuffle the queue.
        val walk = shuffleWalk(controller)?.takeUnless { w -> w.withIndex().all { (i, v) -> i == v } }
        controller.replaceMediaItem(index, item)
        if (walk != null) {
            controller.sendCustomCommand(
                SessionCommand(StashPlaybackService.COMMAND_SET_SHUFFLE_ORDER, Bundle.EMPTY),
                Bundle().apply { putIntArray(StashPlaybackService.EXTRA_SHUFFLE_ORDER, walk.toIntArray()) },
            )
        }
    }

    /**
     * Under shuffle, ExoPlayer's DefaultShuffleOrder gives every inserted item a
     * uniformly random slot in the PLAY order (cloneAndInsert), whatever its
     * timeline position: an "Add to queue" played at some arbitrary point. Re-sends
     * the walk with the [count] items appended at timeline [at] placed at its end
     * ([QueueDisplay.walkWithAppended]) through the service's
     * COMMAND_SET_SHUFFLE_ORDER, which checks it against the player's timeline.
     * [walk] is from before the insert. ("Play next" goes through the service in
     * one step instead, see [playNextThroughService].)
     */
    private fun placeAppendedInShuffleOrder(controller: MediaController, walk: List<Int>?, at: Int, count: Int) {
        walk ?: return
        val order = QueueDisplay.walkWithAppended(walk, at, count) ?: return
        controller.sendCustomCommand(
            SessionCommand(StashPlaybackService.COMMAND_SET_SHUFFLE_ORDER, Bundle.EMPTY),
            Bundle().apply { putIntArray(StashPlaybackService.EXTRA_SHUFFLE_ORDER, order.toIntArray()) },
        )
    }

    /**
     * "Play next": first in, first out. A second Play next plays after the first, and a whole
     * selection ("Play selected next", one call per song) plays in the order it was picked. The
     * songs carry the Play next tag ([PlayNext]), and each new one goes after the run of tagged
     * songs right behind the current one, so a jump, a skip or a queue replaced elsewhere can't
     * leave a stale count behind.
     */
    override suspend fun addNext(track: Track): Boolean {
        val controller = ensureController() ?: return false
        val streamOk = streamAllowed()
        val playable = track.takeIf {
            withContext(Dispatchers.IO) { it.isPlayableForQueueAppend(streamOk) }
        }
        if (playable == null) {
            _userMessages.tryEmit(if (streamOk) "Nothing in this queue is playable right now." else DOWNLOAD_MODE_MESSAGE)
            return false
        }
        val queueTrack = playable.withQueueIdentity()
        if (!withContext(Dispatchers.IO) { queueTrack.isPlayableForQueueAppend(streamOk) }) {
            _userMessages.tryEmit(if (streamOk) "Nothing in this queue is playable right now." else DOWNLOAD_MODE_MESSAGE)
            return false
        }
        // Off the main thread: a downloaded track's disk check is a ContentResolver
        // query on a SAF tree, which a slow provider (SD card, cloud-backed) stalls.
        val queueItem = withContext(Dispatchers.IO) { queueTrack.toQueueMediaItem() }
        restoreGhostPaused()
        if (controller.mediaItemCount == 0 && inListenTogether) {
            // A guest whose first song hasn't arrived yet: the room owns the queue, so this is a Play next for it
            // (ListenTogetherPlayer hands it to the session), tagged so it isn't taken for an Add to queue.
            controller.addMediaItem(PlayNext.tag(queueItem))
            return true
        }
        if (controller.mediaItemCount == 0) {
            // The user tapped "Play next" with nothing playing: they expect the song to
            // actually start, not sit silently in a queue they can't see. It is the current
            // song, not a queued one, so it carries no Play next tag.
            dropWindow("the player was empty")
            currentQueueTracks = listOf(queueTrack)
            controller.addMediaItem(queueItem)
            controller.prepare()
            controller.play()
            return true
        }
        // Instant add: stream tracks enter as stash-resolve:// placeholders
        // (resolved at play time), so the queue grows immediately instead of
        // waiting out a slow resolve.
        val tagged = PlayNext.tag(queueItem)
        // A big queue: its timeline is the play order (shuffle included), so the song goes in at
        // the same slot there and in the whole order.
        activeWindow(controller)?.let { window ->
            val slot = PlayNext.plan(controller, walk = null)?.at
                ?: (controller.currentMediaItemIndex + 1).coerceIn(0, window.count)
            window.insertSlot(slot, queueTrack.id)
            pinIfNeeded(queueTrack)
            currentQueueTracks = currentQueueTracks.toMutableList().apply { add(slot, queueTrack) }
            controller.addMediaItem(slot, tagged)
            return true
        }
        if (controller.shuffleModeEnabled && playNextThroughService(controller, tagged, queueTrack)) return true
        // Shuffle off, the timeline is the play order and the controller inserts. The logical
        // queue first: the controller reports the insert, and the queue published from it, during
        // addMediaItem itself, and a paused player may send nothing afterwards to correct a stale one.
        val plan = PlayNext.plan(controller, walk = null)
        if (plan == null) {
            currentQueueTracks = currentQueueTracks + queueTrack
            controller.addMediaItem(tagged)
            return true
        }
        currentQueueTracks = logicalWithPlayNext(controller, queueTrack, plan.afterTrackId, plan.afterCopy)
        controller.addMediaItem(plan.at, tagged)
        return true
    }

    /**
     * Play next under shuffle. Only the session's own player knows the real play order (right
     * after any edit a controller's masking timeline answers an unshuffled one), so the service
     * inserts the song and places it in the walk in one step
     * ([StashPlaybackService.COMMAND_PLAY_NEXT_SHUFFLED], [PlayNext.insert]). It answers which song
     * the new one follows, and the logical queue takes it right after that song too, so turning
     * shuffle off later plays what the sheet shows. False when the service didn't insert (its
     * shuffle was already off, a disconnect): the caller inserts through the controller instead,
     * so the song is never dropped while the toast says "Playing next".
     */
    private suspend fun playNextThroughService(
        controller: MediaController,
        item: MediaItem,
        queueTrack: Track,
    ): Boolean {
        val result = try {
            controller.sendCustomCommand(
                SessionCommand(StashPlaybackService.COMMAND_PLAY_NEXT_SHUFFLED, Bundle.EMPTY),
                Bundle().apply {
                    putBundle(StashPlaybackService.EXTRA_PLAY_NEXT_ITEM, item.toBundleIncludeLocalConfiguration())
                },
            ).await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "play next: the service didn't answer, inserting through the controller", e)
            return false
        }
        if (result.resultCode != SessionResult.RESULT_SUCCESS) {
            Log.w(TAG, "play next: the service refused (${result.resultCode}), inserting through the controller")
            return false
        }
        val after = PlayNext.afterFromResult(result.extras)
        currentQueueTracks = if (after != null) {
            logicalWithPlayNext(controller, queueTrack, after.first, after.second)
        } else {
            currentQueueTracks + queueTrack
        }
        return true
    }

    /**
     * The logical queue with [queueTrack] right after the [afterCopy]-th copy of [afterTrackId],
     * the song it follows in the timeline ([PlayNext.logicalIndexAfter]); else right after the
     * playing song, else at the end (a queue the logical list doesn't hold, see [QueueDisplay]).
     */
    private fun logicalWithPlayNext(
        controller: MediaController,
        queueTrack: Track,
        afterTrackId: Long,
        afterCopy: Int,
    ): List<Track> {
        val logical = currentQueueTracks
        val currentId = controller.currentMediaItem?.mediaMetadata?.extras
            ?.getLong(EXTRA_TRACK_ID, -1L) ?: -1L
        val at = PlayNext.logicalIndexAfter(logical, afterTrackId, afterCopy).takeIf { it >= 0 }
            ?: logical.indexOfFirst { it.id == currentId }.takeIf { it >= 0 }?.plus(1)
            ?: return logical + queueTrack
        return logical.toMutableList().apply { add(at, queueTrack) }
    }

    override suspend fun addToQueue(track: Track): Boolean {
        val controller = ensureController() ?: return false
        val streamOk = streamAllowed()
        val playable = track.takeIf {
            withContext(Dispatchers.IO) { it.isPlayableForQueueAppend(streamOk) }
        }
        if (playable == null) {
            _userMessages.tryEmit(if (streamOk) "Nothing in this queue is playable right now." else DOWNLOAD_MODE_MESSAGE)
            return false
        }
        val queueTrack = playable.withQueueIdentity()
        if (!withContext(Dispatchers.IO) { queueTrack.isPlayableForQueueAppend(streamOk) }) {
            _userMessages.tryEmit(if (streamOk) "Nothing in this queue is playable right now." else DOWNLOAD_MODE_MESSAGE)
            return false
        }
        // Off the main thread: a downloaded track's disk check is a ContentResolver
        // query on a SAF tree, which a slow provider (SD card, cloud-backed) stalls.
        val queueItem = withContext(Dispatchers.IO) { queueTrack.toQueueMediaItem() }
        restoreGhostPaused()
        val wasEmpty = controller.mediaItemCount == 0
        if (wasEmpty) dropWindow("the player was empty")
        // A big queue: after everything in its whole order, in the window only if that reaches the end.
        activeWindow(controller)?.let { window ->
            appendToWindow(controller, window, listOf(queueTrack)) { listOf(queueItem) }
            return true
        }
        // The logical queue first, as in addNext: the add is reported during addMediaItem.
        currentQueueTracks = if (wasEmpty) listOf(queueTrack) else currentQueueTracks + queueTrack
        val walk = if (wasEmpty) null else shuffleWalk(controller)
        val at = controller.mediaItemCount
        controller.addMediaItem(queueItem)
        placeAppendedInShuffleOrder(controller, walk, at, 1)
        if (wasEmpty) {
            controller.prepare()
            controller.play()
        }
        return true
    }

    override suspend fun addToQueue(tracks: List<Track>): Boolean {
        if (tracks.isEmpty()) return false
        val controller = ensureController() ?: return false
        val streamOk = streamAllowed()
        val playable = withContext(Dispatchers.IO) { tracks.filter { it.isPlayableForQueueAppend(streamOk) } }
        if (playable.isEmpty()) {
            _userMessages.tryEmit(if (streamOk) "Nothing in this queue is playable right now." else DOWNLOAD_MODE_MESSAGE)
            return false
        }
        val persistedTracks = playable.map { it.withQueueIdentity() }
        restoreGhostPaused()
        // Nothing playing and more songs than the player holds: that is a new big queue.
        if (controller.mediaItemCount == 0 && persistedTracks.size > QUEUE_WINDOW_SIZE) {
            setQueueInternal(persistedTracks, startIndex = 0, startPositionMs = 0L, source = currentSource)
            return true
        }
        // A big queue (or one this makes bigger than the player holds, shuffle off): the songs go
        // at the end of the whole order, and only as many into the player as its window takes.
        val window = activeWindow(controller)
            ?: if (controller.mediaItemCount + persistedTracks.size > QUEUE_WINDOW_SIZE) windowFromQueue(controller) else null
        if (window != null) {
            val room = QUEUE_WINDOW_SIZE.coerceAtMost(persistedTracks.size)
            val firstItems = withContext(Dispatchers.IO) { persistedTracks.take(room).map { it.toQueueMediaItem() } }
            // The IO hop: the window may have changed; appendToWindow reads it again, and takes at most `room`.
            val live = activeWindow(controller) ?: return false
            appendToWindow(controller, live, persistedTracks) { n -> firstItems.take(n) }
            return true
        }
        val builtItems = withContext(Dispatchers.IO) {
            persistedTracks.map { it to it.toQueueMediaItem() }
        }
        val acceptedItems = builtItems
        if (acceptedItems.isEmpty()) {
            _userMessages.tryEmit("Nothing in this queue is playable right now.")
            return false
        }
        val queueTracks = acceptedItems.map { it.first }
        val items = acceptedItems.map { it.second }
        val wasEmpty = controller.mediaItemCount == 0
        if (wasEmpty) dropWindow("the player was empty")
        // Instant batch add: every track becomes a MediaItem now (placeholders
        // for streams), preserving tap order — no resolve fan-out, no dropped
        // rows, "Added N tracks" is finally literally true.
        currentQueueTracks = if (wasEmpty) queueTracks else currentQueueTracks + queueTracks
        val walk = if (wasEmpty) null else shuffleWalk(controller)
        val at = controller.mediaItemCount
        controller.addMediaItems(items)
        placeAppendedInShuffleOrder(controller, walk, at, items.size)
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
     * local item becomes a lazy stream placeholder when there is a connection
     * and is rejected without one (a placeholder that cannot resolve would
     * only bounce error recovery), without scanning or rebuilding the rest of
     * the queue. The Download switch plays no part.
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

        // An All Songs file is never swapped for an online match: a missing one is just gone.
        if (target.isScannedSong) {
            if (reportUnavailable) _userMessages.tryEmit(SCANNED_FILE_MISSING_MESSAGE)
            return false
        }

        if (!streamAllowed()) {
            if (reportUnavailable) _userMessages.tryEmit(DOWNLOAD_MODE_MESSAGE)
            return false
        }
        if (target.isUnavailableForDisplay || !connectivity.isConnected()) {
            if (reportUnavailable) {
                _userMessages.tryEmit(FILE_MISSING_CANT_STREAM_MESSAGE)
            }
            return false
        }
        replaceMediaItemKeepingShuffle(controller, timelineIndex, target.toStreamPlaceholderMediaItem())
        return true
    }

    /** Timeline slot holding the [copy]-th (0-based) occurrence of [trackId], or -1
     * when the track was dropped by the fast-lane background fill and has no
     * MediaItem yet.
     *
     * By copy, not by id: with a song queued twice (`[A(playing), B, C, A]`)
     * the first match turned a swipe on the second A into removing the PLAYING
     * one, and a tap on it into replaying B and C. The timeline holds a
     * subsequence of the logical queue in the same order, so the k-th copy in
     * the logical queue ([QueueDisplay.copyOrdinal]) is the k-th copy here. */
    private fun timelineIndexOfTrackId(controller: MediaController, trackId: Long, copy: Int): Int {
        var seen = 0
        for (i in 0 until controller.mediaItemCount) {
            val item = controller.getMediaItemAt(i)
            val id = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)
                ?: item.mediaId.toLongOrNull()
            if (id != trackId) continue
            if (seen == copy) return i
            seen++
        }
        return -1
    }

    override suspend fun removeFromQueue(index: Int) {
        if (ghostQueueActive()) {
            removeFromGhostQueue(index)
            return
        }
        val controller = ensureController() ?: return
        // A big queue: the sheet's rows are the window's slots, and the song leaves the whole order too.
        activeWindow(controller)?.let { window ->
            if (index !in 0 until window.count || index == controller.currentMediaItemIndex) return
            window.removeSlot(index)
            currentQueueTracks = currentQueueTracks.filterIndexed { i, _ -> i != index }
            controller.removeMediaItem(index)
            return
        }
        shuffledDisplayIndices?.let { walk ->
            // #468: rows are in shuffle order - map the row back to its timeline slot.
            val slot = walk.getOrNull(index) ?: return
            if (slot in 0 until controller.mediaItemCount) controller.removeMediaItem(slot)
            return
        }
        if (logicalDisplayActive(controller)) {
            val logical = currentQueueTracks
            val target = logical.getOrNull(index) ?: return
            currentQueueTracks = logical.filterIndexed { i, _ -> i != index }
            val timelineIdx =
                timelineIndexOfTrackId(controller, target.id, QueueDisplay.copyOrdinal(logical, index))
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

    /**
     * The queue sheet's Undo: [track] back at display row [index], in whichever index space the
     * sheet is showing, exactly as [removeFromQueue] took it out of it:
     *  - the #462 ghost: its rows and the persisted queue ([insertIntoGhostQueue]);
     *  - shuffle on (#468): a new timeline item where the song sits in the unshuffled queue, and
     *    row [index] of the shuffle walk;
     *  - the logical queue: row [index] of it, and the timeline slot that keeps the timeline a
     *    subsequence of it in order ([QueueDisplay.moveTimelineTarget]);
     *  - else the timeline itself.
     * The item is built as Play next builds one (a downloaded file, else a stream placeholder that
     * resolves when reached), so nothing is resolved now and playback is never touched.
     */
    override suspend fun insertInQueue(index: Int, track: Track): Boolean {
        if (ghostQueueActive()) return insertIntoGhostQueue(index, track)
        val controller = ensureController() ?: return false
        // A session owns the player's queue (the sheet isn't offered there); an empty player has
        // no queue to put a song back into.
        if (listenTogetherOwnsQueue || controller.mediaItemCount == 0) return false
        val streamOk = streamAllowed()
        if (!withContext(Dispatchers.IO) { track.isPlayableForQueueAppend(streamOk) }) return false
        val queueTrack = track.withQueueIdentity()
        // Off the main thread: a downloaded track's disk check is a ContentResolver query on a
        // SAF tree, which a slow provider stalls (as in addNext).
        val queueItem = withContext(Dispatchers.IO) { queueTrack.toQueueMediaItem() }
        val count = controller.mediaItemCount
        if (count == 0) return false // cleared during the IO hop

        // A big queue: back at the same slot of the window, and so of the whole order.
        activeWindow(controller)?.let { window ->
            val at = index.coerceIn(0, window.count)
            window.insertSlot(at, queueTrack.id)
            pinIfNeeded(queueTrack)
            currentQueueTracks = currentQueueTracks.toMutableList().apply { add(at, queueTrack) }
            controller.addMediaItem(at, queueItem)
            return true
        }

        if (shuffledDisplayIndices != null) {
            // The walk from before the insert, read fresh as addNext does.
            val walk = shuffleWalk(controller)
            // Removal under shuffle left the logical (unshuffled) queue alone, so the song's place
            // there says where in the timeline it belongs; a song outside it goes at the end.
            val logical = currentQueueTracks
            val logicalPos = logical.indexOfFirst { it.id == queueTrack.id }
            val at = if (logicalPos >= 0) {
                QueueDisplay.moveTimelineTarget(logical, logicalPos, timelineTrackIds(controller))
            } else {
                count
            }
            controller.addMediaItem(at, queueItem)
            val order = walk?.let { QueueDisplay.walkWithInsertedAt(it, at, 1, index) } ?: return true
            controller.sendCustomCommand(
                SessionCommand(StashPlaybackService.COMMAND_SET_SHUFFLE_ORDER, Bundle.EMPTY),
                Bundle().apply { putIntArray(StashPlaybackService.EXTRA_SHUFFLE_ORDER, order.toIntArray()) },
            )
            return true
        }
        if (logicalDisplayActive(controller)) {
            val logical = currentQueueTracks
            val pos = index.coerceIn(0, logical.size)
            val newLogical = logical.toMutableList().apply { add(pos, queueTrack) }
            val at = QueueDisplay.moveTimelineTarget(newLogical, pos, timelineTrackIds(controller))
            // The logical queue first, as in addNext: the insert is reported, and the queue
            // published from it, during addMediaItem itself.
            currentQueueTracks = newLogical
            controller.addMediaItem(at, queueItem)
            return true
        }
        controller.addMediaItem(index.coerceIn(0, count), queueItem)
        return true
    }

    /** The timeline's track ids in timeline order (the extras' id, else the media id; -1 for neither). */
    private fun timelineTrackIds(controller: MediaController): List<Long> =
        List(controller.mediaItemCount) { i ->
            val item = controller.getMediaItemAt(i)
            item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID) ?: item.mediaId.toLongOrNull() ?: -1L
        }

    override suspend fun moveInQueue(from: Int, to: Int) {
        if (ghostQueueActive()) {
            moveInGhostQueue(from, to)
            return
        }
        val controller = ensureController() ?: return
        // A big queue: the rows are the window's slots; the whole order moves with them. Under
        // shuffle the sheet hides the handle, as below.
        activeWindow(controller)?.let { window ->
            if (controller.shuffleModeEnabled) return
            if (from !in 0 until window.count || to !in 0 until window.count || from == to) return
            window.moveSlot(from, to)
            currentQueueTracks = currentQueueTracks.toMutableList().apply { add(to, removeAt(from)) }
            controller.moveMediaItem(from, to)
            return
        }
        // #468: a MediaController cannot rewrite Media3's shuffle order, so a move
        // under shuffle would not change what plays next. The sheet hides the handle.
        if (shuffledDisplayIndices != null) return
        if (logicalDisplayActive(controller)) {
            val logical = currentQueueTracks
            if (from !in logical.indices || to !in logical.indices || from == to) return
            val mutable = logical.toMutableList()
            val moved = mutable.removeAt(from)
            mutable.add(to, moved)
            currentQueueTracks = mutable

            val timelineFrom =
                timelineIndexOfTrackId(controller, moved.id, QueueDisplay.copyOrdinal(logical, from))
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
        if (ghostQueueActive()) {
            playGhostQueueFrom(index)
            return
        }
        val controller = ensureController() ?: return
        // A big queue: the rows are the window's slots. The window then slides around the new song.
        activeWindow(controller)?.let { window ->
            if (index !in 0 until window.count) return
            val target = currentQueueTracks.getOrNull(index)
            if (target == null || prepareExplicitTimelineTarget(controller, index, target)) {
                controller.seekToDefaultPosition(index)
            }
            return
        }
        shuffledDisplayIndices?.let { walk ->
            // #468: rows are in shuffle order - seek to the timeline slot the row maps to.
            val slot = walk.getOrNull(index) ?: return
            if (slot !in 0 until controller.mediaItemCount) return
            val target = queueTrackAtTimelineIndex(controller, slot)
            if (target == null || prepareExplicitTimelineTarget(controller, slot, target)) {
                controller.seekToDefaultPosition(slot)
            }
            return
        }
        if (logicalDisplayActive(controller)) {
            val target = currentQueueTracks.getOrNull(index) ?: return
            val timelineIdx = timelineIndexOfTrackId(
                controller,
                target.id,
                QueueDisplay.copyOrdinal(currentQueueTracks, index),
            )
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
     * True while the queue sheet lists the #462 ghost over a player with no timeline. The
     * index-based ops above can't reach a row there (they were silent no-ops: a tap closed the
     * sheet and nothing played), so they act on the ghost and on the persisted queue that [play]
     * rebuilds from. Not while Listen Together owns the player, its restore included.
     */
    private fun ghostQueueActive(): Boolean =
        ghostSession && !listenTogetherOwnsQueue && (controllerDeferred?.mediaItemCount ?: 0) == 0

    /**
     * Serialises everything that loads the saved session or rewrites it: [resumeQueue], a ghost
     * tap, Play next or Add to queue, and a ghost edit's save. Two quick swipes must not start from
     * the same saved queue, a tap must rebuild without a row just swiped away, and Play pressed
     * during a tap's rebuild must not load the saved song over the tapped one.
     */
    private val restoreMutex = Mutex()

    /**
     * A tap on the ghost's queue: loads its session ([loadGhost]) from the tapped song's beginning.
     * The screen can't cancel it, so backing out of Now Playing during the reconnect doesn't drop
     * the tap. If the ghost retired meanwhile (Play, a media button), the tap goes to the same song
     * in the live queue instead.
     */
    private suspend fun playGhostQueueFrom(index: Int) {
        val rows = _playerState.value.queue
        val tapped = rows.getOrNull(index) ?: return
        val copy = rows.subList(0, index).count { it.id == tapped.id }
        cascadeGuard.onUserTransport() // a deliberate pick, like Play
        val loaded = withContext(NonCancellable) {
            restoreMutex.withLock { ghostQueueActive() && loadGhost(tapped.id, copy, play = true) }
        }
        if (loaded || listenTogetherOwnsQueue) return
        val live = _playerState.value.queue.map { it.id }.indexOfCopy(tapped.id, copy) ?: return
        skipToQueueIndex(live)
    }

    /**
     * Loads the session the ghost shows into the player: from the [tappedCopy]-th copy of
     * [tappedId] at its beginning, or, with no tap, at the paused song and position. Uses the saved
     * queue when it can rebuild every row on screen: the rebuild [play] does, in the saved order
     * (with shuffle on, a ghost kept from a stopped service lists Media3's walk instead, #468).
     * Otherwise it loads the ghost's own rows, filled in from the library and the logical queue: a
     * radio discovery has no library row, so no saved plan can bring it back, but as a stream
     * placeholder it resolves from its YouTube id, title and artist. Returns false, loading
     * nothing, when the ghost retired while the saved queue was read. Called under [restoreMutex].
     */
    private suspend fun loadGhost(tappedId: Long?, tappedCopy: Int, play: Boolean): Boolean {
        val plan = playbackResumer.buildResumePlan()
        if (!ghostQueueActive()) return false
        val ghost = _playerState.value
        val planIds = plan?.tracks?.map { it.id }.orEmpty()
        val planIdSet = planIds.toHashSet()
        if (plan != null && ghost.queue.all { it.id in planIdSet }) {
            val start = tappedId?.let { planIds.indexOfCopy(it, tappedCopy) ?: planIds.indexOf(it) } ?: plan.startIndex
            loadPlan(plan, start, if (tappedId == null) plan.positionMs else 0L, play)
            return true
        }
        val start = if (tappedId == null) {
            ghost.currentIndex
        } else {
            ghost.queue.map { it.id }.indexOfCopy(tappedId, tappedCopy) ?: return true // the row has gone
        }
        val library = plan?.tracks.orEmpty().associate { it.id to it.toDomain() }
        val logical = currentQueueTracks.associateBy { it.id }
        val controller = ensureController()
        controller?.shuffleModeEnabled = ghost.isShuffleEnabled
        controller?.repeatMode = ghost.repeatMode.toPlayerRepeatMode()
        setQueueInternal(
            tracks = ghost.queue.map { library[it.id] ?: logical[it.id] ?: it },
            startIndex = start,
            startPositionMs = if (tappedId == null) ghost.positionMs else 0L,
            source = ghost.source,
            play = play,
            restore = true,
        )
        return true
    }

    /**
     * Play next and Add to queue on the ghost: load its session paused first, so the song joins
     * the queue on screen instead of starting alone in the empty player, with the next refresh
     * saving that one song over the user's queue. Not cancellable by the screen, like a tap.
     */
    private suspend fun restoreGhostPaused() {
        if (!ghostQueueActive()) return
        withContext(NonCancellable) {
            restoreMutex.withLock { if (ghostQueueActive()) loadGhost(tappedId = null, tappedCopy = 0, play = false) }
        }
    }

    /** Position of the [copy]-th (0-based) occurrence of [id] in these ids, or null when there are fewer copies. */
    private fun List<Long>.indexOfCopy(id: Long, copy: Int): Int? =
        withIndex().filter { it.value == id }.getOrNull(copy)?.index

    /**
     * A swipe on the ghost's queue: the row leaves the screen and the persisted queue, so the next
     * rebuild (a tap, Play, Android Auto) comes back without it. Nothing loads or plays.
     */
    private suspend fun removeFromGhostQueue(index: Int) {
        val ghost = _playerState.value
        val removed = ghost.queue.getOrNull(index) ?: return
        if (index == ghost.currentIndex) return // the paused song stays; the sheet only offers the rows after it
        _playerState.value = ghost.copy(
            queue = ghost.queue.filterIndexed { i, _ -> i != index },
            currentIndex = if (index < ghost.currentIndex) ghost.currentIndex - 1 else ghost.currentIndex,
        )
        // The persisted queue may be in another order (timeline order under a shuffle-walk ghost),
        // so drop the same copy of the song rather than the same position.
        val copy = ghost.queue.subList(0, index).count { it.id == removed.id }
        persistGhostQueue { ids ->
            val at = ids.indexOfCopy(removed.id, copy)
            if (at == null) ids else ids.filterIndexed { i, _ -> i != at }
        }
    }

    /**
     * The queue sheet's Undo on the ghost's queue: [track] back at row [index] (never before the
     * paused song, which the sheet only lists rows after), and back in the persisted queue just
     * before the copy of the song that now follows it, so the next rebuild has it too. Nothing
     * loads or plays.
     */
    private suspend fun insertIntoGhostQueue(index: Int, track: Track): Boolean {
        if (track.isScannedSong) return false // the persisted queue never holds an All Songs file
        val ghost = _playerState.value
        if (ghost.queue.isEmpty()) return false
        val pos = index.coerceAtLeast(ghost.currentIndex + 1).coerceAtMost(ghost.queue.size)
        _playerState.value = ghost.copy(queue = ghost.queue.toMutableList().apply { add(pos, track) })
        // The persisted queue may be in another order (timeline order under a shuffle-walk ghost),
        // so anchor on the same copy of the row that follows, as removeFromGhostQueue does.
        val follower = ghost.queue.getOrNull(pos)
        val followerCopy = follower?.let { f -> ghost.queue.subList(0, pos).count { it.id == f.id } } ?: 0
        persistGhostQueue { ids ->
            val before = follower?.let { ids.indexOfCopy(it.id, followerCopy) }
            ids.toMutableList().apply { if (before != null) add(before, track.id) else add(track.id) }
        }
        return true
    }

    /**
     * A drag on the ghost's queue: reorders the rows and the persisted queue. Nothing loads or
     * plays. Under shuffle a no-op, like the live queue's (#468); the sheet hides the handle there.
     */
    private suspend fun moveInGhostQueue(from: Int, to: Int) {
        val ghost = _playerState.value
        if (ghost.isShuffleEnabled) return
        if (from !in ghost.queue.indices || to !in ghost.queue.indices || from == to) return
        val queue = ghost.queue.toMutableList().apply { add(to, removeAt(from)) }
        val current = ghost.currentIndex
        _playerState.value = ghost.copy(
            queue = queue,
            currentIndex = when {
                from == current -> to
                from < current && to >= current -> current - 1
                from > current && to <= current -> current + 1
                else -> current
            },
        )
        // Shuffle off: the ghost lists the queue in its persisted order, so the new rows are the queue.
        persistGhostQueue { queue.map { it.id } }
    }

    /**
     * Rewrites the persisted queue's ids through [edit], keeping its shuffle, repeat and source.
     * Not cancellable by the screen: the edit is on screen already. Skipped once the ghost has
     * retired, when the live queue's own saves own the store.
     */
    private suspend fun persistGhostQueue(edit: (List<Long>) -> List<Long>) {
        try {
            withContext(NonCancellable) {
                restoreMutex.withLock {
                    if (!ghostQueueActive()) return@withLock
                    val saved = playbackStateStore.getLastPlaybackState() ?: return@withLock
                    if (!ghostQueueActive()) return@withLock // it retired during the read
                    val ids = edit(saved.queueTrackIds)
                    if (ids.isEmpty() || ids == saved.queueTrackIds) return@withLock
                    playbackStateStore.saveQueue(ids, saved.isShuffled, saved.repeatMode, saved.source)
                    // updateState's "already saved" check must compare against what is stored now.
                    lastSavedQueueIds = ids
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The edit is on screen already; failing to keep it must not crash the swipe.
            Log.w(TAG, "ghost queue: saving the edited queue failed", e)
        }
    }

    /** The ghost is state only: a deleted ghost track must not linger on screen. */
    private fun evictFromGhost(deletedIds: Set<Long>) {
        if (!ghostSession) return
        val ghost = _playerState.value
        if (ghost.currentTrack?.id in deletedIds) {
            ghostSession = false
            _playerState.value = PlayerState()
        } else if (ghost.queue.any { it.id in deletedIds }) {
            // Nor in its queue: a tap on the row would find no library row to rebuild from.
            val above = ghost.queue.take(ghost.currentIndex).count { it.id in deletedIds }
            _playerState.value = ghost.copy(
                queue = ghost.queue.filterNot { it.id in deletedIds },
                currentIndex = ghost.currentIndex - above,
            )
        }
    }

    /**
     * Songs deleted from the library leave the ghost and the queue: every copy, in the player or (a
     * big queue) anywhere in the whole order. Without this ExoPlayer's open file handle kept a
     * deleted song playing. A batch costs one pass over the order, however many songs it holds.
     */
    private fun evictTracksFromQueue(deletedIds: Set<Long>) {
        evictFromGhost(deletedIds)
        evictFromQueue(deletedIds)
    }

    /**
     * Removes every copy of [trackIds] from the queue, high to low in the player, a run at a time.
     * A removed playing song makes Media3 go on to the next one (or stop at the end) by itself.
     */
    override fun evictFromQueue(trackIds: Set<Long>) {
        if (trackIds.isEmpty()) return
        // A big queue: every copy leaves the whole order; the window's leave the player below. Also
        // with no controller, while the window waits for the service ([windowUnconfirmed]): the
        // ghost's restore loads the saved queue without them, and only a window without them is
        // that queue again.
        queueWindow?.removeIds(trackIds)
        currentQueueTracks = currentQueueTracks.filterNot { it.id in trackIds }
        val controller = controllerDeferred ?: return
        fun hit(i: Int): Boolean {
            val item = controller.getMediaItemAt(i)
            val id = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID) ?: item.mediaId.toLongOrNull()
            return id != null && id in trackIds
        }
        var removed = false
        var i = controller.mediaItemCount - 1
        while (i >= 0) {
            if (hit(i)) {
                var start = i
                while (start > 0 && hit(start - 1)) start--
                controller.removeMediaItems(start, i + 1) // a run at once: one command, not one per song
                removed = true
                i = start - 1
            } else {
                i--
            }
        }
        // A logical-only eviction fires no controller event: refresh the published queue ourselves.
        if (!removed) updateState(controller)
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
        // A Search play takes the same identity road as a queue addition: ensureTrackPersisted
        // dedupes by video id / Spotify URI / canonical identity and inserts otherwise, so the
        // queue carries a LIBRARY id that every resume path (Resume shortcut, idle-resume,
        // the #462 cold-start ghost) and every per-row bookkeeping (art upgrades, quality
        // stamps, "now playing" markers) can find. It used to be a hash of the video id that
        // lived only in memory: the persisted queue then held an id no row answered to.
        val trackId = runCatching { musicRepository.ensureTrackPersisted(item.toSearchTrack()) }
            .getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "playFromStream: could not persist '${item.title}' — playing under a transient id", e)
                item.videoId.hashCode().toLong()
            }
        // Idempotency guard #1 (already-playing): if the controller's
        // current MediaItem is THIS track and is in an active state,
        // skip. Mirrors the preview path's original guard.
        val targetMediaId = trackId.toString()
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
            return playFromStreamInner(item, trackId)
        } finally {
            synchronized(inFlightStreamingTaps) {
                inFlightStreamingTaps.remove(item.videoId)
            }
        }
    }

    private val inFlightStreamingTaps = mutableSetOf<String>()

    /** The library-shaped view of a Search result, id 0 until [MusicRepository.ensureTrackPersisted] answers. */
    private fun TrackItem.toSearchTrack(): Track = Track(
        id = 0L,
        title = title,
        artist = artist,
        album = album ?: "",
        durationMs = (durationSeconds * 1000).toLong(),
        source = com.stash.core.model.MusicSource.YOUTUBE,
        youtubeId = videoId,
        albumArtUrl = thumbnailUrl,
        isDownloaded = false,
        isStreamable = true,
        musicVideoType = musicVideoType,
        explicit = explicit,
    )

    private suspend fun playFromStreamInner(item: TrackItem, trackId: Long): StreamRoutingResult {

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
        // The library row the tap landed on, when ensureTrackPersisted found one: its art is what
        // the on-stream art swap below judges. The result's own thumbnail is a YouTube video frame,
        // so judging that replaced a good library cover with whatever copy the chain picked.
        val stored = trackDao.getById(trackId)
        val transient = TrackEntity(
            id = trackId,
            title = item.title,
            artist = item.artist,
            album = item.album ?: "",
            durationMs = (item.durationSeconds * 1000).toLong(),
            isDownloaded = false,
            isStreamable = true,
            albumArtUrl = stored?.albumArtUrl?.takeIf { it.isNotBlank() } ?: item.thumbnailUrl,
            // Search results carry a YT videoId — propagate it so the
            // YouTube fallback resolver can extract directly when Qobuz
            // doesn't have the track. Without this the transient row's
            // youtubeId stays null and YouTubeStreamResolver bails.
            youtubeId = item.videoId,
            // A YouTube result, whatever the library row is: the entity's default (SPOTIFY) made
            // the lossless lookups trust the result's length as a song's. A music video's is an
            // edit's (Shook Ones' top card is the 4:25 video; the album cut is 5:26), and Tidal
            // then picked the copy nearest the video.
            source = com.stash.core.model.MusicSource.YOUTUBE,
            musicVideoType = item.musicVideoType,
            explicit = item.explicit ?: stored?.explicit,
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
     * 2. No validated internet → [StreamRoutingResult.NoConnectivity].
     *    Includes airplane mode, captive-portal-not-yet-accepted, and
     *    any other "associated but no real internet" state. Before it, Download
     *    mode → [StreamRoutingResult.DownloadMode]: it plays only what is on the phone.
     * 3. Cellular + cellular pref off → [StreamRoutingResult.CellularRefused].
     *    The user has a data plan they want to protect.
     * 4. URL cache hit → use the cached signed URL.
     * 5. Cache miss → resolve via Kennyy and cache the result. Resolver
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
        // The disk check off the main thread (playTrack runs on the caller's, Main from a
        // viewModelScope): on a SAF tree it is a ContentResolver query a slow provider stalls.
        if (track.isDownloaded && !localPath.isNullOrBlank() &&
            withContext(Dispatchers.IO) { filePathExistsOnDisk(localPath) }
        ) {
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
                            putSoundCloudMetadata(track.toDomain())
                                track.youtubeId?.let { putString(EXTRA_TRACK_YOUTUBE_ID, it) }
                                putTrackOrigin(track.source, track.spotifyUri)
                                if (track.durationMs > 0) putLong(EXTRA_TRACK_DURATION_MS, track.durationMs)
                                // This branch plays the local file, so it is never "stream-only",
                                // whatever the row's is_streamable says: rows first seen via Search,
                                // Discovery, a share or a Stash Mix keep is_streamable=1 after they
                                // download, and offline silent-skip used to skip them all.
                                putBoolean(EXTRA_TRACK_IS_STREAMABLE, false)
                            })
                            .build()
                    )
                    .build()
            )
        }
        if (!streamAllowed()) return StreamRoutingResult.DownloadMode
        if (!connectivity.isConnected()) return StreamRoutingResult.NoConnectivity
        // A browser plays it: it finds the song itself (the cast wrapper hands it the song, not a stream). The placeholder
        // resolves only if the music comes back to this phone, as a queue's tail does.
        if (playsOnBrowser) return StreamRoutingResult.Item(track.toDomain().toStreamPlaceholderMediaItem())
        if (connectivity.isCellular() && !streamingPreference.streamOnCellular.first()) {
            return StreamRoutingResult.CellularRefused
        }
        // A cloud song plays from the user's own cloud through its stash-cloud:// address.
        if (track.source == com.stash.core.model.MusicSource.CLOUD) {
            return StreamRoutingResult.Item(track.toDomain().toStreamPlaceholderMediaItem())
        }

        // Asked BEFORE the cache is read: a changed decision (Data saver now in effect) drops the
        // links saved under the old one, and the cache serves no lossless link under Data saver.
        qualityPolicy?.dataSaverApplies()
        val cached = streamUrlCache.get(track.id)?.takeIf {
            (track.soundCloudId == null && track.source != com.stash.core.model.MusicSource.SOUNDCLOUD) || it.origin == "soundcloud"
        }
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
                // A cloud copy plays through the placeholder, whose source falls back to the stream
                // chain when the copy won't open; the stash-cloud:// address itself has no fallback.
                .setUri(
                    if (stream.origin == com.stash.core.media.streaming.CloudCopyFinder.ORIGIN || stream.origin == "soundcloud") {
                        com.stash.core.media.streaming.stashResolveUri(
                            track.id, track.youtubeId, track.title, track.artist, track.album, track.durationMs, track.isrc,
                        ).let { uri ->
                            if (stream.origin == "soundcloud") uri.buildUpon().appendQueryParameter("sc", "1").build() else uri
                        }
                    } else {
                        Uri.parse(stream.url)
                    },
                )
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
                            putSoundCloudMetadata(track.toDomain())
                            track.youtubeId?.let { putString(EXTRA_TRACK_YOUTUBE_ID, it) }
                            putTrackOrigin(track.source, track.spotifyUri)
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
                            if (stream.isDash) putBoolean(EXTRA_STREAM_DASH, true)
                            if (stream.origin == "soundcloud") putBoolean(EXTRA_SOUNDCLOUD_PREVIEW, stream.isPreview)
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
     * navigated into a specific track — same invariant as [setQueue]. A radio
     * station ends too: left armed, its grow watcher saw a one-song queue near
     * its tail and appended the OLD station behind a song tapped in Search,
     * under a stale "Radio: X" label.
     */
    private suspend fun playSingleMediaItem(mediaItem: MediaItem) {
        libraryShuffleActive = false
        librarySnapshot = emptyList()
        radioActive = false
        radioSession = null
        _radioSeedLabel.value = null
        val controller = ensureController() ?: return
        dropWindow("a single song replaced the queue")
        resetPlayHistory()
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
    /**
     * Reconnects the controller (and so starts the playback service) ahead of
     * a tap: called when the app comes to the foreground. A no-op while
     * connected; after the service's idle self-stop this is the ~1 s the
     * first play would otherwise wait.
     */
    fun warmUp() {
        scope.launch { ensureController() }
    }

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
                // A service that idle-stopped and came back has a fresh 1x player;
                // re-assert the session speed on connect.
                if (_playbackSpeed.value != 1f) controller.setPlaybackSpeed(_playbackSpeed.value)
                controllerDeferred = controller
                // A new connection while a big queue is set: the service behind it may be a new one,
                // so the first sync below checks the player before the window plays on (confirmWindow).
                if (queueWindow != null) windowUnconfirmed = true
                // Sync initial state
                updateState(controller)
                controller
            } catch (e: CancellationException) {
                // await() throws this both when OUR caller was cancelled (a screen
                // closing mid-reconnect), which must propagate: as null the caller
                // ran on into its non-suspending tail (loadPlan set shuffle and
                // repeat, then re-entered), and when the build future itself was
                // cancelled, which is a failed connect like any other.
                currentCoroutineContext().ensureActive()
                null
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
            // A big queue's window is kept, unconfirmed: the service may live on (a client still
            // bound, such as Android Auto, holds it through stopSelf) with the window in its player,
            // its keep-order request gone with our controller, or come back new and empty. The next
            // connection decides (confirmWindow); a restore into a new player takes the window back
            // (setQueueBuilding).
            if (queueWindow != null) windowUnconfirmed = true
            // #462: the kept snapshot becomes a paused ghost, or the reconnect's first
            // (empty) refresh wipes it and the app reopens to "Not Playing".
            if (_playerState.value.currentTrack != null) {
                ghostSession = true
                // No spinner either: a snapshot taken mid-load would keep play disabled.
                _playerState.value = _playerState.value.copy(isPlaying = false, isBuffering = false, playWhenReady = false)
            }
        }
    }

    /** Listener that forwards Media3 player events into [_playerState]. */
    internal val playerListener = object : Player.Listener { // internal: test seam

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val controller = controllerDeferred ?: return
            noteCurrentItem(controller, reason)
            // Safety net: keep the chosen speed across transitions / queue rebuilds. Not in a session,
            // where drift correction nudges the speed on purpose.
            if (!listenTogetherOwnsQueue && controller.playbackParameters.speed != _playbackSpeed.value) {
                controller.setPlaybackSpeed(_playbackSpeed.value)
            }
            // Defense in depth: the existing onPlayerError recovery catches
            // PlaybackException-driven failures, but some failure modes (audio
            // offload sink stalls before we removed offload, plus any future
            // codec/format edge case) can leave the player in STATE_IDLE on the
            // next track WITHOUT firing onPlayerError. The user-visible symptom
            // is "next song appears, play button does nothing." A single
            // prepare() call is a no-op when the player is already READY and
            // rescues the IDLE case automatically.
            // Only when it should be playing: a paused, unprepared restore (after Listen Together) is IDLE on purpose.
            // And only when no error is pending: an item that fails to open (a local file deleted outside the
            // app) lands here IDLE with playerError set, just before onPlayerError. Preparing it again loads
            // it a second time; that second failure arrives after onPlayerError has skipped ahead and is
            // blamed on the next song, which then gets skipped too (or the queue stops if it was the last).
            if (controller.playbackState == Player.STATE_IDLE && controller.currentMediaItem != null &&
                controller.playWhenReady && controller.playerError == null
            ) {
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

        /** The extractor has read the stream: an add-on stream's badge now says what it found ([withPlayedFormat]). */
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
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

        /**
         * A pause while BUFFERING flips playWhenReady without changing isPlaying (already false)
         * or the playback state, so no other callback fires: without this the published
         * [PlayerState.playWhenReady] stayed true and the play/pause button kept showing Pause.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
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
                // A big queue shuffles over its whole order, not Media3 over the window.
                if (queueWindow != null) scope.launch { rewindowForShuffle() }
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
            // Every error lands in the diagnostics bundle with the branch it took: the track id,
            // origin and URI scheme only. Never the title, never the URI: a stash-resolve URI
            // carries the title, a googlevideo one the user's IP.
            val failingId = current?.mediaMetadata?.extras?.getLong(EXTRA_TRACK_ID, 0L)?.takeIf { it != 0L }
            fun note(branch: String) = diagnosticsLog.recordError(failingId, error, branch, streamOrigin, scheme)
            if (listenTogetherOwnsQueue) {
                note("LISTEN_TOGETHER (left to the session)")
                return // the session reports "unavailable" and stays silent (spec §6)
            }
            // A linked browser couldn't play the song (Remote v2 §2.3; it retried it once already, and never skips by itself).
            // Whatever the song's URI scheme on this phone, this is not this phone's file or stream failing: the local-file
            // branch would swap a downloaded song for a stream it resolves here. A plain skip, counted by the cascade guard so a
            // browser that can't play anything can't machine-gun the queue. A Chromecast's errors keep their old path.
            if (error.errorCode == PlaybackException.ERROR_CODE_REMOTE_ERROR && playsOnBrowser) {
                val verdict = cascadeGuard.onError(null) // no same-song retry: the browser retried it already
                note("REMOTE → $verdict")
                Log.w(TAG, "onPlayerError: '$failingTitle' failed on the browser — verdict=$verdict")
                when (val v = verdict) {
                    StreamErrorCascadeGuard.Verdict.RetrySameItem, StreamErrorCascadeGuard.Verdict.Recover -> controller?.recoverOrStop()
                    is StreamErrorCascadeGuard.Verdict.Halt -> {
                        controller?.pause()
                        _streamingHaltedEvents.tryEmit(StreamingHaltedEvent(failingTitle = failingTitle, consecutiveErrorCount = v.consecutiveErrors))
                    }
                }
                return
            }

            // The player refused an add-on's stream for this song before a sound of it played (its
            // check: called lossless and lossy, a video, far too short). Not a failed stream: the
            // add-on is set aside already, and the song plays from the next source in the user's
            // order. Not counted by the cascade guard, and never a same-item retry, which would
            // open the same refused link. If no source is left, that resolve fails like any other.
            if (failingId != null &&
                (com.stash.core.media.streaming.AddonStreamRefusedException.isIn(error) || addonStreamCheck?.takeRefusal(failingId) == true)
            ) {
                note("ADDON_REFUSED (resolved again)")
                Log.i(TAG, "onPlayerError: an add-on's stream for track $failingId was refused; resolving it again")
                if (controller != null && current != null) {
                    val failedIndex = controller.currentMediaItemIndex
                    scope.launch { resolveAgainAfterRefusal(controller, current, failedIndex, failingId) }
                }
                return
            }

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
                    note("LOCAL (stream instead, or skip)")
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
                                if (cascadeGuard.onLocalSkip(controller.mediaItemCount)) {
                                    // Every song in the queue failed in a row: stop
                                    // instead of cycling (see onLocalSkip).
                                    note("LOCAL: every queued file failed, paused")
                                    controller.pause()
                                    _userMessages.tryEmit("None of the songs in this queue can play right now.")
                                } else {
                                    controller.recoverOrStop()
                                }
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
                    note("STREAMING_CASCADE → $verdict")
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
                        // RefreshingDataSource, while qbdlx slots get
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
     * The song at [failedIndex] had its add-on stream refused before a sound of it played: it is
     * put back as a fresh placeholder ([resolvedAgainItem]) and prepared again. Its link is gone
     * from the cache and the add-on is set aside, so the resolve goes to the next source in the
     * user's order. Nothing is done if the player has moved on since (a tap, a skip).
     */
    internal fun resolveAgainAfterRefusal(controller: MediaController, failedItem: MediaItem, failedIndex: Int, trackId: Long) {
        addonStreamCheck?.takeRefusal(trackId)
        addonStreamCheck?.forget(trackId)
        streamUrlCache.invalidate(trackId)
        if (failedIndex !in 0 until controller.mediaItemCount || controller.currentMediaItemIndex != failedIndex) return
        val active = controller.currentMediaItem ?: return
        if (active.mediaId != failedItem.mediaId) return
        replaceMediaItemKeepingShuffle(controller, failedIndex, resolvedAgainItem(active, trackId))
        controller.prepare()
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
        // An All Songs file that fails is skipped like any other unplayable item; it is never
        // retried as a stream, which would play whatever an online search guessed it to be.
        val failedId = failedItem.mediaMetadata.extras
            ?.getLong(EXTRA_TRACK_ID, -1L) ?: return false
        if (isScannedTrackId(failedId)) return false
        // Download mode never streams: a broken download is skipped, not swapped for a stream.
        if (!streamAllowed()) return false
        if (!connectivity.isConnected()) return false
        if (connectivity.isCellular() && !streamingPreference.streamOnCellular.first()) return false
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

        // A downloaded cloud song whose file failed falls back to its cloud copy, never an online match.
        val placeholder = if (track.source == com.stash.core.model.MusicSource.CLOUD) {
            com.stash.core.media.streaming.cloudPlaybackUri(track.cloudUri)
        } else {
            stashResolveUri(
                trackId = track.id,
                youtubeId = track.youtubeId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
                isrc = track.isrc,
            )
        }
        val replacement = failedItem.buildUpon().setUri(
            if (track.soundCloudId != null) placeholder.buildUpon().appendQueryParameter("sc", "1").build() else placeholder,
        ).build()
        replaceMediaItemKeepingShuffle(controller, failedIndex, replacement)
        controller.prepare()
        controller.play()
        return true
    }

    private fun MediaController.recoverOrStop() {
        if (listenTogetherOwnsQueue) return
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
        if (listenTogetherOwnsQueue) return
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
            _userMessages.tryEmit("No internet connection, and the rest of this queue isn't downloaded.")
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
            if (stream.origin == "soundcloud") putBoolean(EXTRA_SOUNDCLOUD_PREVIEW, stream.isPreview)
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
        if (item?.localConfiguration?.uri?.scheme in listOf("file", "content")) return track
        if (track.streamOrigin != null && track.streamOrigin != "soundcloud") return track
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
            soundCloudPreview = if (stream.origin == "soundcloud") stream.isPreview else track.soundCloudPreview,
            isDownloaded = if (stream.origin == "soundcloud") false else track.isDownloaded,
        )
    }

    /**
     * [track]'s quality tags from what the player is decoding, when an add-on answered its stream
     * (review-882 M9; the rule is [com.stash.core.media.streaming.PlayedFormat]). An add-on's word
     * for its own stream is only that: one that says "lossless" over an AAC file was shown as
     * FLAC. Once the extractor has read the stream, the badge says what it found. Read from the
     * controller on every refresh; [Player.Listener.onTracksChanged] asks for one when the
     * tracks become known. Until then nothing of the add-on's word is shown
     * ([com.stash.core.media.streaming.PlayedFormat.beforeDecoding]): a stream the player refuses
     * ([com.stash.core.media.streaming.AddonStreamCheck]) never gets that far.
     */
    internal fun withPlayedFormat(track: Track, controller: MediaController): Track {
        if (!com.stash.core.media.streaming.AddonPlayback.isAddonOrigin(track.streamOrigin)) return track
        val format = controller.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { group -> (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { group.getTrackFormat(it) } }
            ?: return com.stash.core.media.streaming.PlayedFormat.beforeDecoding(track)
        return com.stash.core.media.streaming.PlayedFormat.applyTo(
            track, format.sampleMimeType, format.sampleRate, format.pcmEncoding, format.bitrate,
        )
    }

    internal fun updateState(controller: MediaController) {
        // A ghost survives refreshes from an empty player (idle events, reconnects);
        // the first refresh that carries real items retires it.
        if (ghostSession) {
            if (controller.mediaItemCount == 0) return
            ghostSession = false
        }
        confirmWindow(controller)
        // Artwork upgrade for the playing track: see [maybeStampCurrentItemQuality].
        // It is now id-guarded so it runs once per track — its extras write does
        // NOT survive the session round-trip, so it can never self-disarm on the
        // read-back the way its KDoc originally assumed.
        maybeStampCurrentItemQuality(controller)
        noteCurrentItem(controller, reason = null)

        val currentItem = controller.currentMediaItem
        // Badge from the live resolve, not from extras that may not have stuck.
        val track = currentItem?.toTrack()?.let { withLiveStreamQuality(it, currentItem) }?.let { withPlayedFormat(it, controller) }
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
        // A big queue's window is in play order, shuffle or not (its shuffle is over the whole
        // order, see [rewindowForShuffle]): the timeline is what plays next, as is.
        // The same songs at both ends and at the playing slot, not just the same count: Android
        // Auto hands over exactly 500 songs too.
        val window = queueWindow?.takeIf { !listenTogetherOwnsQueue && windowInSync(controller, it) }
        // #468: with shuffle on, show Media3's play order, not the logical list. The
        // controller's timeline carries the shuffle order, so walk it.
        val shuffledIndices = if (window == null && controller.shuffleModeEnabled && controller.mediaItemCount > 0) {
            val timeline = controller.currentTimeline
            QueueDisplay.walkShuffleOrder(controller.mediaItemCount, timeline.getFirstWindowIndex(true)) {
                timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, true)
            }.takeIf { it.size == controller.mediaItemCount }
        } else {
            null
        }
        shuffledDisplayIndices = shuffledIndices

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
            // In a session the player holds the room's one song: the user's queue, set aside, isn't "up next".
            logicalQueue = if (listenTogetherOwnsQueue) emptyList() else currentQueueTracks,
            currentTrackId = currentTrackId,
            shuffledTimelineIndices = shuffledIndices,
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
            // Until an item is prepared (your queue put back paused after Listen Together) the player doesn't
            // know its length, but the item does. Without this Now Playing read "0:13 / -0:00".
            durationMs = controller.duration.takeIf { it > 0 } ?: track?.durationMs ?: 0L,
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
            // Media3's own play-button rule (Util.shouldShowPlayButton): an IDLE or ENDED
            // player plays nothing whatever playWhenReady says.
            playWhenReady = controller.playWhenReady &&
                controller.playbackState != Player.STATE_IDLE &&
                controller.playbackState != Player.STATE_ENDED,
            source = currentSource,
            shuffleTimelineSlots = display.timelineIndices,
            queueSongsBefore = window?.songsBefore ?: 0,
            queueSongsAfter = window?.songsAfter ?: 0,
            queuePosition = window?.position(controller.currentMediaItemIndex.coerceIn(0, window.count - 1)) ?: -1,
        )
        _playerState.value = newState
        lastKnownQueueSize = newState.queue.size
        lastKnownTimelineSize = timelineQueue.size
        lastKnownOrderSize = queueWindow?.total ?: timelineQueue.size

        if (listenTogetherOwnsQueue) return // the user's queue waits in PlaybackStateStore; never save the session's one song over it

        // Persist position for resume-on-restart (fire and forget). Gated:
        // updateState fires on EVERY player event, and each DataStore edit is
        // a full read-parse-serialize-fsync cycle — a buffering flap storm
        // (STATE_BUFFERING↔READY on a bad stream) was rewriting the same
        // position dozens of times. Persist only on meaningful change: track/
        // index switch, a pause edge (exact resume position), or ≥5s drift.
        val pauseEdge = lastObservedIsPlaying && !newState.isPlaying
        lastObservedIsPlaying = newState.isPlaying
        // An All Songs file has no `tracks` row, so a resume could never find it: its position
        // is not saved (the last library song's saved position stays).
        if (track != null && !track.isScannedSong) {
            savePositionIfDue(track.id, newState.positionMs, newState.currentIndex, pauseEdge)
        }

        // Persist the full queue so Bluetooth/Android Auto resumption can
        // restore it (next/prev working) rather than a single track. Only
        // re-save when the queue contents or shuffle state change — not on
        // every position tick. Persists the LOGICAL queue (newState.queue,
        // same list the saved currentIndex points into) — the raw timeline
        // is a sparse resolved-only subset during streaming and would
        // resume into a queue with most tracks missing.
        // #468 follow-up: with shuffle on, newState.queue is the shuffle WALK (what plays
        // next), not the queue. Persist the timeline order, or a restart would adopt one
        // shuffle as the unshuffled order and shuffle again on top of it.
        // All Songs files are left out of the saved queue (they have no `tracks` row to resume
        // into); the library songs around them are kept, whatever the queue's source.
        val queueIds = savableQueueIds(
            if (shuffledDisplayIndices != null) cachedTimelineQueue.map { it.id } else newState.queue.map { it.id },
        )
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

    /**
     * The gated position write ([shouldPersistPosition]) shared by
     * [updateState] and the periodic save while playing. Main thread.
     */
    private fun savePositionIfDue(trackId: Long, positionMs: Long, queueIndex: Int, pauseEdge: Boolean) {
        if (!shouldPersistPosition(
                trackChanged = trackId != lastSavedPosTrackId,
                indexChanged = queueIndex != lastSavedPosQueueIndex,
                pauseEdge = pauseEdge,
                positionDeltaMs = positionMs - lastSavedPositionMs,
            )
        ) {
            return
        }
        lastSavedPosTrackId = trackId
        lastSavedPosQueueIndex = queueIndex
        lastSavedPositionMs = positionMs
        scope.launch {
            playbackStateStore.savePosition(
                trackId = trackId,
                positionMs = positionMs,
                queueIndex = queueIndex,
            )
        }
    }

    /** One tick of the save-while-playing loop (see init): the live position of the published track. */
    internal fun persistPositionWhilePlaying() {
        if (listenTogetherOwnsQueue) return // as in updateState: never over the user's own queue
        val controller = controllerDeferred?.takeIf { it.isConnected } ?: return
        val state = _playerState.value
        val track = state.currentTrack ?: return
        if (track.isScannedSong) return
        savePositionIfDue(track.id, controller.currentPosition.coerceAtLeast(0), state.currentIndex, pauseEdge = false)
    }

    // ---- Mappers ----

    companion object {
        private const val TAG = "StashPlayer"

        /** The extras that describe the stream a slot plays, for Now Playing's quality line. */
        private val streamStampKeys = listOf(
            EXTRA_STREAM_CODEC, EXTRA_STREAM_BIT_DEPTH, EXTRA_STREAM_SAMPLE_RATE, EXTRA_STREAM_BITRATE, EXTRA_STREAM_ORIGIN, EXTRA_STREAM_DASH,
        )

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

        /** Most steps one window slide or reshuffle takes before waiting for the next player event. */
        private const val MAX_SLIDE_PASSES = 20

        /** See the deletion batcher in init: a sweep that pauses this long has ended. */
        private const val DELETION_BATCH_QUIET_MS = 250L

        /** ... and a long one is evicted in parts this long apart. */
        private const val DELETION_BATCH_MAX_MS = 2_000L

        /** How many tracks each grow appends. Big enough to outpace a fast-skipping user. */
        private const val LIBRARY_SHUFFLE_GROW_BATCH = 50

        /** Refresh prefetch if cached URL has less than this margin remaining. */
        private const val PREFETCH_FRESH_THRESHOLD_MS = 60_000L

        /** Shown when an All Songs file can't be played because it isn't where the scan found it. */
        /** Offline (Download mode in code) refuses a song that isn't on the phone. */
        internal const val DOWNLOAD_MODE_MESSAGE = "Offline plays only your downloads. Switch to Online to play this."

        /** A downloaded song whose file is gone or broken, and it can't stream instead (no connection, or nowhere to stream from). */
        internal const val FILE_MISSING_CANT_STREAM_MESSAGE = "That song's file is missing and it can't stream right now."

        internal const val SCANNED_FILE_MISSING_MESSAGE =
            "That file isn't there anymore. Rescan your folders: Library, then the disk icon at the top."

        /**
         * The Back button restarts a song played past this, as Media3's own seekToPrevious (the
         * notification, a headset, Android Auto) does with its default maxSeekToPreviousPosition.
         */
        private const val RESTART_THRESHOLD_MS = 3_000L
    }

    /**
     * Queue-time MediaItem: downloaded-and-on-disk → the eager file:// item;
     * otherwise a stash-resolve:// placeholder that [LazyResolvingDataSource]
     * resolves at play time. Carries the SAME identity extras as [toMediaItem]
     * so every downstream consumer (offline silent-skip, scrobbler, likes,
     * resume, prefetch matching) keeps working unchanged.
     */
    private fun Track.toQueueMediaItem(): MediaItem {
        // An All Songs file is local-only: if it has gone, the local-error policy skips it.
        if (isScannedSong) return toMediaItem()
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
        validateLocalFile: Boolean,
        streamAllowed: Boolean,
    ): MediaItem? = initialQueueKind(validateLocalFile, streamAllowed)?.let { toQueueItemOfKind(it) }

    /**
     * What [toInitialQueueMediaItem] would build, without building it: its file, a stream
     * placeholder, or nothing (it can't play in this mode). Lets a big queue decide what can play
     * across 70k songs without making 70k MediaItems.
     */
    private fun Track.initialQueueKind(validateLocalFile: Boolean, streamAllowed: Boolean): QueueEntryKind? {
        val localPath = filePath
        val hasRecordedDownload = isDownloaded && !localPath.isNullOrBlank()
        val knownTooSmall = fileSizeBytes in 1 until StashConstants.MIN_PLAYABLE_LOCAL_BYTES
        val hasUsableLocal = hasRecordedDownload && !knownTooSmall &&
            (!validateLocalFile || filePathExistsOnDisk(localPath))

        // An All Songs file plays from its file or not at all: never a stream placeholder,
        // which would resolve an online match by its title and artist.
        if (isScannedSong) return if (hasUsableLocal) QueueEntryKind.FILE else null

        // In Stream only a track without a usable file becomes a stash-resolve://
        // placeholder unless it is confirmed unavailable; Download mode leaves it out.
        return when {
            hasUsableLocal -> QueueEntryKind.FILE
            isUnavailableForDisplay || !streamAllowed -> null
            else -> QueueEntryKind.PLACEHOLDER
        }
    }

    private fun Track.toQueueItemOfKind(kind: QueueEntryKind): MediaItem =
        if (kind == QueueEntryKind.FILE) toMediaItem() else toStreamPlaceholderMediaItem()

    /** What [setQueueBuilding] hands the player. */
    private class InitialQueue(
        /** Songs of the queue that can play in this mode. 0: say so. */
        val playableCount: Int,
        /** The player's items: every playable song, or a big queue's window. Empty when [startSlot] < 0. */
        val items: List<MediaItem>,
        /** The songs of [items], slot for slot: the logical queue. */
        val queueTracks: List<Track>,
        /** The tapped song's slot in [items]; -1 when it can't play (say why, play nothing). */
        val startSlot: Int,
        val startTrack: Track,
        /** A big queue's whole order, or null when [items] is the whole queue. */
        val window: QueueWindow?,
        /** See [windowPinned]. */
        val pinned: Map<Long, Track>,
    )

    /**
     * The queue's playable songs and the player's items for them. Up to [QUEUE_WINDOW_SIZE]
     * playable songs: all of them, as before. More: only the window around [safeStart], shuffled
     * over the whole order when [shuffleOn], the order kept as ids. Only the selected song touches
     * the disk. Runs on IO.
     */
    private fun buildInitialQueue(tracks: List<Track>, safeStart: Int, streamOk: Boolean, shuffleOn: Boolean): InitialQueue {
        val kinds = arrayOfNulls<QueueEntryKind>(tracks.size)
        var playableCount = 0
        for (i in tracks.indices) {
            val kind = tracks[i].initialQueueKind(validateLocalFile = i == safeStart, streamAllowed = streamOk)
            kinds[i] = kind
            if (kind != null) playableCount++
        }
        val playable = IntArray(playableCount)
        var next = 0
        var startInPlayable = -1
        for (i in tracks.indices) {
            if (kinds[i] == null) continue
            if (i == safeStart) startInPlayable = next
            playable[next++] = i
        }
        // startIndex maps through the playable filter by position, not by track
        // id: a song queued twice starts at the copy that was tapped. In Download mode a
        // play from the top (Play, Shuffle, a mix tile) whose first song isn't on the phone
        // starts at the first one that is; a tap on a later song still says why it won't play.
        if (startInPlayable < 0 && !streamOk && safeStart == 0 && playableCount > 0) startInPlayable = 0
        val startTrack = tracks[safeStart]
        if (startInPlayable < 0) {
            return InitialQueue(playableCount, emptyList(), emptyList(), -1, startTrack, null, emptyMap())
        }
        fun itemAt(i: Int) = tracks[i].toQueueItemOfKind(kinds[i]!!)
        if (playableCount <= QUEUE_WINDOW_SIZE) {
            return InitialQueue(
                playableCount = playableCount,
                items = playable.map(::itemAt),
                queueTracks = playable.map { tracks[it] },
                startSlot = startInPlayable,
                startTrack = startTrack,
                window = null,
                pinned = emptyMap(),
            )
        }
        val ids = LongArray(playableCount) { tracks[playable[it]].id }
        val (window, slot) = QueueWindow.create(ids, startInPlayable, shuffleOn, windowRandom)
        val windowIndices = IntArray(window.count) { playable[window.logicalIndexAtSlot(it)] }
        val pinned = HashMap<Long, Track>()
        for (i in playable) if (tracks[i].isScannedSong) pinned[tracks[i].id] = tracks[i]
        return InitialQueue(
            playableCount = playableCount,
            items = windowIndices.map(::itemAt),
            queueTracks = windowIndices.map { tracks[it] },
            startSlot = slot,
            startTrack = startTrack,
            window = window,
            pinned = pinned,
        )
    }

    private fun Track.toStreamPlaceholderMediaItem(): MediaItem {
        // A cloud song streams from the user's own cloud, never from an online match by its words.
        if (source == com.stash.core.model.MusicSource.CLOUD) {
            return toMediaItem().buildUpon().setUri(com.stash.core.media.streaming.cloudPlaybackUri(cloudUri)).build()
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
        return toMediaItem().buildUpon().setUri(
            if (soundCloudId != null) placeholder.buildUpon().appendQueryParameter("sc", "1").build() else placeholder,
        ).build()
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
                putSoundCloudMetadata(this@toMediaItem)
                youtubeId?.let { putString(EXTRA_TRACK_YOUTUBE_ID, it) }
                if (durationMs > 0) putLong(EXTRA_TRACK_DURATION_MS, durationMs)
                // "Stream-only" means no local file (as AutoBrowse computes it): a
                // downloaded row can still carry is_streamable=1 from how it was found.
                putBoolean(
                    EXTRA_TRACK_IS_STREAMABLE,
                    (isStreamable || source == com.stash.core.model.MusicSource.CLOUD) && filePath.isNullOrBlank(),
                )
                // An All Songs file's format comes from its file name, which an opaque SAF
                // document id (`msf:123`) doesn't carry, so it rides along for toTrack().
                if (isScannedSong) putString(EXTRA_SCANNED_FILE_FORMAT, fileFormat)
                // Now Playing rebuilds its Track from these extras: a cloud song must stay one, so
                // its "find FLAC" / "wrong match" actions stay off and its format stays its own.
                putTrackOrigin(source, spotifyUri)
                if (source == com.stash.core.model.MusicSource.CLOUD) {
                    putString(EXTRA_CLOUD_FILE_FORMAT, fileFormat)
                }
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
        val trackId = queueTrackId()
        if (isScannedTrackId(trackId)) return toScannedTrack(trackId)

        // Streaming tracks carry the Qobuz-reported format in extras so the
        // Now Playing screen can show "FLAC · 24-bit/96 kHz" instead of the
        // Room row's default ("opus") that streaming-only library entries
        // inherit. Absent for downloaded tracks — those keep Room's truth.
        val streamCodec = extras?.getString(EXTRA_STREAM_CODEC)
        val streamBitDepth = extras?.getInt(EXTRA_STREAM_BIT_DEPTH, 0)?.takeIf { it > 0 }
        val streamSampleRate = extras?.getInt(EXTRA_STREAM_SAMPLE_RATE, 0)?.takeIf { it > 0 }
        val streamBitrate = extras?.getInt(EXTRA_STREAM_BITRATE, 0)?.takeIf { it > 0 }
        val streamOrigin = extras?.getString(EXTRA_STREAM_ORIGIN)
        val isCloud = extras?.getString(EXTRA_TRACK_SOURCE) == com.stash.core.model.MusicSource.CLOUD.name
        val youtubeId = extras?.getString(EXTRA_TRACK_YOUTUBE_ID) ?: if (trackId == 0L) mediaId else null

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
            soundCloudId = extras?.getString(EXTRA_SOUNDCLOUD_ID),
            soundCloudPermalink = extras?.getString(EXTRA_SOUNDCLOUD_PERMALINK),
            soundCloudPreview = extras?.getBoolean(EXTRA_SOUNDCLOUD_PREVIEW, false) == true,
            isDownloaded = extras?.getBoolean(EXTRA_SOUNDCLOUD_LOCAL, false) == true,
            youtubeId = youtubeId,
            spotifyUri = extras?.getString(EXTRA_TRACK_SPOTIFY_URI),
            source = rebuiltTrackSource(extras, youtubeId),
            cloudUri = localConfiguration?.uri?.toString()?.takeIf { isCloud && it.startsWith(com.stash.core.model.STASH_CLOUD_SCHEME) },
            fileFormat = streamCodec ?: extras?.getString(EXTRA_CLOUD_FILE_FORMAT) ?: "opus",
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

/** How often the position is saved while playing, between player events. */
internal const val POSITION_PERSIST_INTERVAL_MS = 15_000L

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

/**
 * Shares one [positionTicker] among all collectors, running only while someone collects. A new collector gets the last
 * position at once while others are collecting; after a gap with no collector it gets the live position instead (the
 * restarted ticker's first emission), never the one from before the gap: `replayExpirationMillis = 0`. See
 * [PlayerRepositoryImpl.currentPosition]. Top-level + `internal` for [PositionTickerTest].
 */
internal fun Flow<Long>.shareAsPositionTicker(scope: CoroutineScope): SharedFlow<Long> =
    shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

/** Inverse of the existing `Int.toRepeatMode()` mapper — used only to
 *  restore Media3's repeat mode from a persisted [RepeatMode] on resume. */
internal fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
}

/** Normalized track identity for matching a radio seed against its batch —
 *  lowercase, trimmed, whitespace-collapsed `title|artist`. Robust across the
 *  different resolution paths that produce the same song. */
internal fun trackIdentity(title: String, artist: String): String {
    fun norm(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")
    return norm(title) + "|" + norm(artist)
}

/** What a queue entry plays from: its downloaded file, or a stream placeholder resolved when reached. */
private enum class QueueEntryKind { FILE, PLACEHOLDER }

/** MediaMetadata extra: an All Songs file's format (its file extension), see [toScannedTrack]. */
internal const val EXTRA_SCANNED_FILE_FORMAT = "stash_scanned_file_format"

/**
 * A song's stored [com.stash.core.model.MusicSource], on every MediaItem ([putTrackOrigin]), so the
 * Track rebuilt from it keeps its origin: a cloud song stays one, a YouTube stream is never a
 * Spotify song ([TrackSourceRule]).
 */
internal const val EXTRA_TRACK_SOURCE = "stash_track_source"

/** A song's Spotify URI, when it has one: [TrackSourceRule] needs it to keep a Spotify song Spotify's. */
internal const val EXTRA_TRACK_SPOTIFY_URI = "stash_track_spotify_uri"

/**
 * Puts a song's origin on its MediaItem extras: its stored [source] and, when it has one, its
 * [spotifyUri]. [toTrack] reads them back through [TrackSourceRule].
 */
internal fun Bundle.putTrackOrigin(source: com.stash.core.model.MusicSource, spotifyUri: String?) {
    putString(EXTRA_TRACK_SOURCE, source.name)
    spotifyUri?.takeIf { it.isNotBlank() }?.let { putString(EXTRA_TRACK_SPOTIFY_URI, it) }
}

/**
 * The source of the Track rebuilt from a MediaItem's [extras] ([putTrackOrigin]): a cloud song or a
 * SoundCloud upload stays one, and anything else is the item's own source unless its ids contradict
 * it ([TrackSourceRule]). Every stream used to come back as a Spotify song, and a like or a listen
 * then saved a YouTube stream as a green Spotify row.
 *
 * @param youtubeId the rebuilt Track's video id (a Search preview's media id when it has no row).
 */
internal fun rebuiltTrackSource(extras: Bundle?, youtubeId: String?): com.stash.core.model.MusicSource {
    val stored = extras?.getString(EXTRA_TRACK_SOURCE)
    return when {
        stored == com.stash.core.model.MusicSource.CLOUD.name -> com.stash.core.model.MusicSource.CLOUD
        extras?.getString(EXTRA_SOUNDCLOUD_ID) != null -> com.stash.core.model.MusicSource.SOUNDCLOUD
        else -> TrackSourceRule.honestSource(
            claimed = stored?.let { name -> runCatching { com.stash.core.model.MusicSource.valueOf(name) }.getOrNull() },
            spotifyUri = extras?.getString(EXTRA_TRACK_SPOTIFY_URI),
            youtubeId = youtubeId,
        )
    }
}

/** A cloud song's file format, for Now Playing's quality line (its Room row's truth). */
internal const val EXTRA_CLOUD_FILE_FORMAT = "stash_cloud_file_format"

/**
 * The [com.stash.core.model.Track.id] the queue shows for this item (the repository's rebuilt Track): its [EXTRA_TRACK_ID] when
 * it has extras, else its numeric media id, else 0 (a Search preview, whose media id is its video id). The playback service
 * names a browser's phone-only queue rows by it (Remote v2), so the queue and the service mean the same row.
 */
internal fun MediaItem.queueTrackId(): Long = mediaMetadata.extras?.getLong(EXTRA_TRACK_ID) ?: mediaId.toLongOrNull() ?: 0L

/**
 * The [Track] Now Playing and the queue see for an All Songs file. The logical queue doesn't hold
 * scanned ids (QueueDisplay only reads it for ids > 0), so this is the ONLY Track the rest of the
 * app gets for one: it must say LOCAL + downloaded with its real file, or Now Playing shows "OPUS",
 * offers Find in FLAC, and Play next / the handoff treat it as a stream to resolve.
 */
internal fun MediaItem.toScannedTrack(trackId: Long): Track {
    val meta = mediaMetadata
    val extras = meta.extras
    val uri = (requestMetadata.mediaUri ?: localConfiguration?.uri)?.toString()
    val format = extras?.getString(EXTRA_SCANNED_FILE_FORMAT)?.takeIf { it.isNotBlank() }
        ?: uri?.let(::scannedFileExtension)
        ?: "audio"
    return Track(
        id = trackId,
        title = meta.title?.toString() ?: "",
        artist = meta.artist?.toString() ?: "",
        album = meta.albumTitle?.toString() ?: "",
        albumArtUrl = meta.artworkUri?.toString(),
        durationMs = extras?.getLong(EXTRA_TRACK_DURATION_MS, 0L) ?: 0L,
        filePath = uri,
        fileFormat = format,
        source = com.stash.core.model.MusicSource.LOCAL,
        isDownloaded = true,
    )
}

/** The ids of a queue that a resume can find again: every All Songs file is left out. */
internal fun savableQueueIds(ids: List<Long>): List<Long> =
    if (ids.none(::isScannedTrackId)) ids else ids.filterNot(::isScannedTrackId)

/**
 * Whether the slot a prefetch captured at [timelineIndex] is still the next-up and not the one playing, read again right
 * before replacing it: the prefetch suspends for the DB and the file check in between (review on #730).
 */
internal fun isStillNextUp(currentIndex: Int, nextIndex: Int, timelineIndex: Int): Boolean =
    timelineIndex == nextIndex && timelineIndex != currentIndex
