package com.stash.core.media.recap

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.stash.core.common.constants.StashConstants
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.listen.ListenTogetherController
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The main (MediaSession) player as the Recap story sees it: is it meant to be playing, and
 * pause / play it. Nothing else of the main player is touched — its queue, song, position,
 * shuffle, repeat and "playing from" label stay exactly as they were, because nothing changes them.
 */
interface RecapMainPlayback {
    /** Media3 `playWhenReady`: true while playing or buffering to play. */
    fun isMeantToPlay(): Boolean

    /** A Listen Together room owns the player: the story must not pause the room. */
    fun inListenTogether(): Boolean

    suspend fun pause()

    suspend fun play()
}

/**
 * Story audio for the Recap ("Your October") story: each card plays its song — the #1 song on its
 * reveal card, other top songs under other cards — and when the story closes, whatever was playing
 * before comes back exactly where it was.
 *
 * ### Why a separate player (owner rule: story audio never counts)
 * The songs play on [ExoRecapPreviewEngine], an ExoPlayer of its own, not on the MediaSession
 * player. The main player is only paused at [begin] and played again at [end] (if it was playing).
 * Everything that counts listening — ListeningRecorder (listening_events, play_count, last_played),
 * Last.fm / ListenBrainz scrobbles and now-playing, YouTube Music history, autoplay radio, Recently
 * played, the notification, Discord and handoff — observes the session player, so a story song is
 * invisible to all of them by construction, with no flag for each one to honour. Pausing the main
 * song is an ordinary pause: ListeningRecorder stops that song's countdown and resumes it on play,
 * in the same listen (it never becomes two plays).
 *
 * ### Contract (all calls safe from any thread; work runs on the main thread)
 *  - [begin]: remembers whether the main player was meant to play and pauses it. Idempotent — a
 *    second [begin] keeps the first snapshot (it would otherwise read "paused" and lose the music).
 *  - [play]: fades the current card's song out (~400 ms), then the new one in, starting
 *    [RECAP_DEFAULT_START_FRACTION] of the way through (≥ 25 s left). Offline / cellular-refused /
 *    unavailable → the card is silently quiet. Same song again → no restart.
 *  - [pause] / [resume]: the story's hold-to-pause.
 *  - [end]: fades out, frees the preview player, and plays the main player again only if it was
 *    meant to be playing at [begin] — nothing playing before → nothing playing after. Idempotent.
 *  - [onBackground]: the app left the screen → [end] (the story can't be heard from the home screen).
 *  - Headphones out or another app taking focus for good → the story goes quiet for the rest of
 *    the session, and [end] leaves the main player paused (that's what it would have done itself:
 *    it never resumes onto the speaker after the headphones come out).
 *  - Listen Together active → the story is quiet and the room is left alone.
 *  - Nothing is persisted: if the process dies mid-story, the preview player dies with it and the
 *    main player's saved queue is the one it had before (it was never changed).
 */
@Singleton
class RecapAudio internal constructor(
    private val engine: RecapPreviewEngine,
    private val main: RecapMainPlayback,
    /** Where [track] plays from right now (file probe + connectivity + cellular pref). */
    private val chooseSource: suspend (Track) -> RecapSourceChoice,
    /** Main-thread dispatcher in the app; the test dispatcher in tests. */
    private val mainDispatcher: CoroutineDispatcher,
    /** Total fade length; 0 disables fading (tests of non-fade behaviour). */
    private val fadeMs: Long = DEFAULT_FADE_MS,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        engine: ExoRecapPreviewEngine,
        playerRepository: PlayerRepository,
        listenTogetherController: ListenTogetherController,
        connectivity: ConnectivityMonitor,
        streamingPreference: StreamingPreference,
    ) : this(
        engine = engine,
        main = PlayerRepositoryMainPlayback(playerRepository, listenTogetherController),
        chooseSource = { track ->
            val path = track.filePath
            val localPlayable = !path.isNullOrBlank() &&
                withContext(Dispatchers.IO) { localFileIsPlayable(context, path) }
            val online = connectivity.isConnected()
            recapSourceFor(
                track = track,
                localPlayable = localPlayable,
                online = online,
                streamAllowedOnThisNetwork = !online || !connectivity.isCellular() ||
                    streamingPreference.streamOnCellular.first(),
            )
        },
        mainDispatcher = Dispatchers.Main.immediate,
    )

    /** One story, from [begin] to [end]. Main-thread confined. */
    private class Session(
        /** The main player was meant to play at [begin]; [end] plays it again. */
        val resumeMainOnEnd: Boolean,
        /** Listen Together, or headphones out / focus lost: no more story audio this session. */
        var quiet: Boolean,
    ) {
        /** The card's song: loading or loaded on the preview player (null = this card is quiet). */
        var currentTrackId: Long? = null
        /** A card change is fading the old song out / choosing the new one (not loaded yet). */
        var loadingNext = false
        /** The user is holding the story ([pause]). */
        var held = false
        /** The preview's gain right now, so a fade starts from where the last one stopped. */
        var volume = 0f
    }

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

    /** Serialises [begin] and [end] so a quick close-and-reopen can't snapshot our own pause. */
    private val lifecycleLock = Mutex()

    private var session: Session? = null

    /** The in-flight card change (fade out → choose → load → fade in); replaced by the next [play]. */
    private var switchJob: Job? = null

    private val engineListener = object : RecapPreviewEngine.Listener {
        override fun onInterrupted() {
            val s = session ?: return
            log("interrupted (headphones out or focus lost): story audio off; the main player stays paused")
            s.quiet = true
            switchJob?.cancel()
            s.currentTrackId = null
            engine.stop()
        }

        override fun onError(message: String) {
            val s = session ?: return
            // The old song failing while the next card's song is on its way: that one still plays.
            if (s.loadingNext) return log("outgoing song failed ($message) during a card change; ignored")
            log("song ${s.currentTrackId} failed ($message): this card stays quiet")
            switchJob?.cancel()
            s.currentTrackId = null
            engine.stop()
        }
    }

    /** True between [begin] and [end]. For the UI and tests. */
    val isActive: Boolean get() = session != null

    /**
     * Starts a story: snapshots whether the main player is meant to play and pauses it.
     * A second call before [end] does nothing.
     */
    suspend fun begin(): Unit = withContext(mainDispatcher) {
        lifecycleLock.withLock {
            if (session != null) {
                log("begin: already in a story; keeping the first snapshot")
                return@withLock
            }
            if (main.inListenTogether()) {
                log("begin: Listen Together is on; the story stays quiet and the room is left alone")
                session = Session(resumeMainOnEnd = false, quiet = true)
                return@withLock
            }
            val wasPlaying = main.isMeantToPlay()
            session = Session(resumeMainOnEnd = wasPlaying, quiet = false)
            engine.setListener(engineListener)
            if (wasPlaying) main.pause()
            log("begin: main player was ${if (wasPlaying) "playing (paused it)" else "not playing"}")
        }
    }

    /**
     * Plays [track] for the current card, fading out the previous card's song first.
     * [startFraction] is where in the song to start (clamped so at least 25 s are left).
     * Ignored outside a story, while the story is quiet, and for the song already playing.
     */
    fun play(track: Track, startFraction: Float = RECAP_DEFAULT_START_FRACTION) {
        scope.launch {
            val s = session ?: return@launch log("play ${track.id}: no story open; ignored")
            if (s.quiet) return@launch log("play ${track.id}: story is quiet; ignored")
            if (s.currentTrackId == track.id) return@launch // the same song under the next card: keep going
            switchJob?.cancel()
            s.currentTrackId = track.id
            switchJob = scope.launch { switchTo(s, track, startFraction) }
        }
    }

    private suspend fun switchTo(s: Session, track: Track, startFraction: Float) {
        s.loadingNext = true
        try {
            fadeTo(s, 0f)
            val choice = chooseQuietly(track)
            if (session !== s || s.quiet) return
            s.loadingNext = false
            when (choice) {
                is RecapSourceChoice.Skip -> {
                    log("play ${track.id}: quiet card (${choice.reason})")
                    s.currentTrackId = null
                    engine.stop()
                }
                is RecapSourceChoice.Play -> {
                    val startMs = recapStartPositionMs(track.durationMs, startFraction)
                    log("play ${track.id} '${track.title}' from ${startMs / 1000}s via ${choice.source::class.simpleName}")
                    setVolume(s, 0f)
                    engine.load(choice.source, startMs)
                    if (!s.held) {
                        engine.play()
                        fadeTo(s, 1f)
                    }
                }
            }
        } finally {
            // Cancelled mid-change by a hold-fade or an error path: never leave the flag stuck.
            // (A newer card replaced switchJob and owns the flag now; end() dropped the session.)
            if (session === s && switchJob === currentCoroutineContext()[Job]) s.loadingNext = false
        }
    }

    private suspend fun chooseQuietly(track: Track): RecapSourceChoice =
        try {
            chooseSource(track)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecapSourceChoice.Skip(RecapSkipReason.UNAVAILABLE).also { log("choose ${track.id} failed: ${e.message}") }
        }

    /** Hold-to-pause: the card's song stops where it is. */
    fun pause() {
        scope.launch {
            val s = session ?: return@launch
            s.held = true
            engine.pause()
        }
    }

    /** Let go of the hold: the card's song carries on (or starts, if it loaded while held). */
    fun resume() {
        scope.launch {
            val s = session ?: return@launch
            if (!s.held) return@launch
            s.held = false
            if (s.quiet || s.currentTrackId == null) return@launch
            engine.play()
            // A song that loaded during the hold is still at 0: fade it in. (A card change still
            // in flight does its own fade; a song paused mid-play is already at full gain.)
            if (switchJob?.isActive != true) switchJob = scope.launch { fadeTo(s, 1f) }
        }
    }

    /**
     * Closes the story: fades the song out, frees the preview player, and plays the main player
     * again if (and only if) it was meant to be playing at [begin] and the story wasn't interrupted.
     * A second call does nothing. Not cancellable once started: a half-finished end would leave
     * the user's music paused.
     *
     * The main player's current state is deliberately NOT consulted: right after [begin] the
     * paused state may not have reached PlayerRepository yet, and reading "still playing" there
     * would skip the play and leave the music paused. play() on a playing player is a no-op.
     */
    suspend fun end(): Unit = withContext(mainDispatcher + NonCancellable) {
        lifecycleLock.withLock {
            val s = session ?: return@withLock
            // Closed first: a play() or engine callback arriving during the fade-out is ignored.
            session = null
            switchJob?.cancel()
            switchJob = null
            if (!s.quiet && !s.held && s.currentTrackId != null) fadeTo(s, 0f)
            engine.setListener(null)
            engine.release()
            if (s.resumeMainOnEnd && !s.quiet) {
                main.play()
                log("end: main player playing again where it was")
            } else {
                log("end: main player left as it was (resume=${s.resumeMainOnEnd}, quiet=${s.quiet})")
            }
        }
    }

    /** The app went to the background (ProcessLifecycleOwner ON_STOP): the story is over. */
    fun onBackground() {
        if (session == null) return
        scope.launch { end() }
    }

    private suspend fun fadeTo(s: Session, target: Float) {
        val from = s.volume
        if (fadeMs <= 0L || from == target) {
            setVolume(s, target)
            return
        }
        // Proportional: fading in from half-way takes half the time.
        val steps = (FADE_STEPS * kotlin.math.abs(target - from)).toInt().coerceAtLeast(1)
        val stepMs = (fadeMs / FADE_STEPS).coerceAtLeast(1L)
        for (i in 1..steps) {
            delay(stepMs)
            setVolume(s, from + (target - from) * i / steps)
        }
    }

    private fun setVolume(s: Session, v: Float) {
        s.volume = v
        engine.setVolume(v)
    }

    companion object {
        private const val TAG = "RecapAudio"
        const val DEFAULT_FADE_MS = 400L
        private const val FADE_STEPS = 20
    }
}

/** [RecapMainPlayback] over the app's MediaController wrapper. */
private class PlayerRepositoryMainPlayback(
    private val playerRepository: PlayerRepository,
    private val listenTogether: ListenTogetherController,
) : RecapMainPlayback {
    override fun isMeantToPlay(): Boolean = playerRepository.playerState.value.playWhenReady
    override fun inListenTogether(): Boolean = listenTogether.active.value
    override suspend fun pause() = playerRepository.pause()
    override suspend fun play() = playerRepository.play()
}

/**
 * Whether [path] (plain, `file://` or `content://`) holds a real audio file: present and at least
 * [StashConstants.MIN_PLAYABLE_LOCAL_BYTES] long — the same test as
 * PlayerRepositoryImpl.filePathExistsOnDisk, so a download recorded as complete but holding a
 * yt-dlp error body streams instead of failing. Blocking I/O: call off the main thread.
 */
internal fun localFileIsPlayable(context: Context, path: String): Boolean {
    val bytes = try {
        if (path.startsWith("content://")) {
            DocumentFile.fromSingleUri(context, path.toUri())?.length() ?: 0L
        } else {
            File(if (path.startsWith("file://")) path.toUri().path ?: path.removePrefix("file://") else path).length()
        }
    } catch (e: Exception) {
        0L
    }
    return bytes >= StashConstants.MIN_PLAYABLE_LOCAL_BYTES
}
