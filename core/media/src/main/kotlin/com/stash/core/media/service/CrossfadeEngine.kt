package com.stash.core.media.service

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Optional tempo/gain choreography for one AutoMix transition. */
data class CrossfadeSpeedProfile(
    val incomingMultiplier: Float = 1f,
    val outgoingTargetMultiplier: Float = 1f,
    val syncLeadInMs: Long = 0L,
    val incomingPrerollMs: Long = 0L,
    val tempoReturnMs: Long = 0L,
    val blendKind: CrossfadeBlendKind = CrossfadeBlendKind.EQUAL_POWER,
    /** Keep only one deck's low end at a time; see [autoMixBassSwapShelves]. */
    val bassSwap: Boolean = false,
    /** Non-null only for the song-derived beat-loop tempo bridge. */
    val beatLoop: BeatLoopProfile? = null,
)

/**
 * Everything the engine needs to run a beat-loop bridge without re-asking the
 * planner mid-transition. Positions are source positions in their own songs;
 * bridge/final durations are wall-clock at the user's current speed.
 */
data class BeatLoopProfile(
    val loopStartMs: Long,
    val loopEndMs: Long,
    val outgoingBpm: Float,
    val targetBpm: Float,
    val incomingAdjustedBpm: Float,
    val incomingEntryMs: Long,
    val bridgeWallMs: Long,
    val bridgeLoopCycles: Int,
    val finalOverlapLoopCycles: Int = 1,
) {
    val loopDurationMs: Long get() = (loopEndMs - loopStartMs).coerceAtLeast(1L)
    val outgoingBeatMs: Float get() = if (outgoingBpm > 0f) 60_000f / outgoingBpm else 500f
    val incomingBeatMs: Float get() = if (incomingAdjustedBpm > 0f) 60_000f / incomingAdjustedBpm else 500f
}

/**
 * Two-player crossfade engine using the **role-swap** model (the only design
 * that yields a seamless overlap — see docs/superpowers/specs).
 *
 * Two persistent [ExoPlayer]s are kept alive for the session. One is the
 * **master** ([masterPlayer], wired to the MediaSession); the other is the
 * spare. To crossfade onto the next track:
 *  1. [prepareNext] primes the spare with the next item (buffered, paused,
 *     volume 0) — called well ahead of the fade so readiness is never raced.
 *  2. [performTransition] starts the spare, runs an equal-power volume ramp
 *     (master down, spare up), and at the end **swaps roles**: the spare —
 *     which has been playing the incoming track the whole time — *becomes* the
 *     master via [onSwap] (the service calls `MediaSession.setPlayer`). The
 *     incoming player never stops, so there is no decoder hand-off on the
 *     audible signal and no glitch. The outgoing track's queue is transferred
 *     onto the new master first, so the timeline stays consistent.
 *
 * Because the master identity changes, audio focus is managed **manually**
 * here (both players build with `handleAudioFocus = false`): a single focus
 * request covers whichever player is active, mirroring ExoPlayer's built-in
 * behaviour (pause on loss, duck on transient-duck, resume on gain).
 *
 * Scope is auto-advance only: manual skips [cancelTransition] and hard-cut.
 */
@OptIn(UnstableApi::class)
class CrossfadeEngine(
    context: Context,
    private val buildPlayer: () -> ExoPlayer,
    private val scope: CoroutineScope,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private lateinit var playerA: ExoPlayer
    private lateinit var playerB: ExoPlayer

    /** The player currently wired to the MediaSession. */
    val masterPlayer: ExoPlayer get() = playerA

    private var transitionJob: Job? = null
    @Volatile private var transitioning = false
    fun isTransitioning(): Boolean = transitioning

    private var tempoReturnJob: Job? = null
    @Volatile private var tempoReturnBaseline: PlaybackParameters? = null
    @Volatile private var tempoReturnPlayer: ExoPlayer? = null
    fun isTempoReturnActive(): Boolean = tempoReturnJob?.isActive == true
    var onTransitionCancelled: (() -> Unit)? = null
    var onTempoReturnFinished: ((String?) -> Unit)? = null

    /**
     * Sets one player's AutoMix low-shelf cut (dB, <= 0). Wired by the service
     * to that player's own OutputSession; the engine only knows players, not
     * their renderers/output stages.
     */
    var onAutoMixLowShelf: ((ExoPlayer, Float) -> Unit)? = null
    private var activeBaselineParameters: PlaybackParameters? = null
    private var preparedStartMs: Long = 0L
    @Volatile private var deferIncomingStart = false

    /**
     * Lazily-created third deck used only by a beat-loop bridge. It is not
     * part of the role swap: it repeats a clipped two-bar segment of the
     * outgoing song while its tempo is morphed, then it is stopped and the
     * ordinary incoming spare becomes the next master.
     */
    private var loopPlayer: ExoPlayer? = null
    private var preparedLoopMediaId: String? = null
    private var preparedLoopStartMs: Long = -1L
    private var preparedLoopEndMs: Long = -1L
    @Volatile private var beatLoopShouldPlay = false

    // ── Manual audio focus (shared across both players) ──────────────────────
    private var focusRequest: AudioFocusRequest? = null
    private var pausedForFocusLoss = false

    /**
     * Listen Together: focus paused the master (a call, another app, a denied request), or a transient
     * loss ended and the master resumed itself. Needed because these pauses set playWhenReady directly,
     * so the player reports them as PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, indistinguishable from a
     * tap. Main thread (the focus listener has no handler, so it runs on the main looper).
     */
    var onFocusPause: (() -> Unit)? = null
    var onFocusResume: (() -> Unit)? = null

    /** Internal for tests: [AudioFocusRequest]'s listener getter is hidden API. */
    @VisibleForTesting
    internal val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForFocusLoss = false
                // Another app took over for good: end a running fade here. Its
                // wall-clock ramp would otherwise run on to the swap, whose
                // focus request took focus straight back and paused the app
                // the user had just switched to, leaving both silent.
                if (transitioning) cancelTransition()
                playerA.playWhenReady = false
                playerB.playWhenReady = false
                loopPlayer?.playWhenReady = false
                beatLoopShouldPlay = false
                abandonFocus()
                onFocusPause?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Only what this loss pauses comes back when it ends: a song
                // that was already paused stays paused.
                pausedForFocusLoss = playerA.playWhenReady ||
                    (transitioning && (playerB.playWhenReady || beatLoopShouldPlay))
                playerA.playWhenReady = false
                playerB.playWhenReady = false
                loopPlayer?.playWhenReady = false
                onFocusPause?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Duck the audible master; the spare is silent unless mid-fade.
                playerA.volume = DUCK_VOLUME
                if (beatLoopShouldPlay) loopPlayer?.volume = DUCK_VOLUME
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (!transitioning) playerA.volume = 1f
                if (pausedForFocusLoss) {
                    pausedForFocusLoss = false
                    playerA.playWhenReady = true
                    // Mid-fade a transient loss paused BOTH players; the
                    // incoming (spare) is the audible future master — leaving
                    // it paused promotes a dead player at the swap (car nav
                    // prompt during a fade → silence at the seam, #251).
                    if (transitioning && !deferIncomingStart) playerB.playWhenReady = true
                    if (transitioning && beatLoopShouldPlay) loopPlayer?.playWhenReady = true
                    onFocusResume?.invoke()
                } else if (!transitioning && !playerA.playWhenReady) {
                    // Paused during the loss, and the pause stuck (see
                    // onPlayPauseRequested). A paused Stash holds no focus, or
                    // the next transient loss (an assistant, a nav prompt)
                    // would end in a resume nobody asked for.
                    abandonFocus()
                }
            }
        }
    }

    /**
     * A controller played or paused: the app, the notification, a headset or
     * car, the sleep timer. During a transient focus loss (a call) the player
     * is already paused, so a pause changes nothing ExoPlayer would report, and
     * the resume when the call ended undid it: the music came back by itself
     * after the sleep timer had stopped it mid-call. Either command means the
     * listener has taken over, so the loss's own resume is dropped, inside a
     * fade or not. Mid-fade the command lands only on the outgoing master (the
     * session's player), so the fade is handed back to it rather than
     * promoting the paused incoming. Main thread (the session callback).
     */
    fun onPlayPauseRequested() {
        if (!pausedForFocusLoss) return
        pausedForFocusLoss = false
        if (transitioning) cancelTransition()
    }

    /**
     * Requests focus when the master starts; abandons when it stops. Also hands
     * a running fade back to the outgoing master when the user pauses or seeks
     * it (see below). Registered before the service's own listener, so a
     * cancelled fade is already over when that listener re-reads idleness.
     */
    private val masterFocusListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // A pause mid-fade (app, notification, headset, the sleep timer's
            // final pause) lands only on this OUTGOING master, the session's
            // player. The spare isn't wired to the session, so it played on,
            // ramped up and was promoted at the swap with playWhenReady = true:
            // the pause turned into "skip to the next song", and a sleep timer
            // expiring inside a fade left the music on all night. Abort the
            // fade so the pause holds. A transient focus loss sets
            // pausedForFocusLoss first and keeps the fade (both players resume
            // on gain); pauseAtEndOfMediaItems' own hold reports
            // END_OF_MEDIA_ITEM, not USER_REQUEST.
            if (!playWhenReady && transitioning && !pausedForFocusLoss &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
            ) {
                cancelTransition()
            }
            // During a fade this listener still sits on the OUTGOING player,
            // whose pauseAtEndOfMediaItems fires playWhenReady=false if it
            // runs out mid-ramp (streaming durations drift). The incoming is
            // still audible — abandoning would drop the whole app out of the
            // focus stack while music keeps playing (#251 car-route ghost).
            if (playWhenReady) requestFocus()
            else if (!pausedForFocusLoss && !transitioning) abandonFocus()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A seek inside the outgoing song mid-fade ("Previous" more than 3 s
            // in, scrubbing back) raises no media-item transition, so the
            // service's cancel never ran: the fade went on and the song the
            // user just restarted was cut off at the swap.
            if (transitioning && reason == Player.DISCONTINUITY_REASON_SEEK) handBackToOutgoing()
        }
    }

    fun initialize() {
        playerA = buildPlayer()
        playerB = buildPlayer()
        playerA.addListener(masterFocusListener)
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = req
        } else {
            playerA.playWhenReady = false
            onFocusPause?.invoke()
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it); focusRequest = null }
    }

    /** Stable per-instance tag so field logs name WHICH player each stop hit. */
    private fun tag(p: ExoPlayer) = "p" + Integer.toHexString(System.identityHashCode(p))

    private fun setAutoMixLowShelf(player: ExoPlayer, shelfDb: Float) {
        onAutoMixLowShelf?.invoke(player, shelfDb.coerceIn(-24f, 0f))
    }

    /** Prime the spare on [item] (buffered, paused, silent) for an upcoming fade. */
    fun prepareNext(
        item: MediaItem,
        startPositionMs: Long = 0L,
        speedMultiplier: Float = 1f,
        lowShelfDb: Float = 0f,
    ) {
        val spare = playerB
        android.util.Log.i(
            "Crossfade",
            "prepareNext: stop+prime spare=${tag(spare)} (master=${tag(playerA)}) item=${item.mediaId} " +
                "startMs=$startPositionMs speedMultiplier=$speedMultiplier lowShelfDb=$lowShelfDb",
        )
        // Set this before prepare/seek so the spare's DSP chain is already in
        // its bass-removed state when its first audible buffers are rendered.
        setAutoMixLowShelf(spare, lowShelfDb)
        spare.stop(); spare.clearMediaItems(); spare.playWhenReady = false; spare.volume = 0f
        val baseline = tempoReturnBaseline ?: playerA.playbackParameters
        spare.playbackParameters = PlaybackParameters(baseline.speed * speedMultiplier.coerceIn(0.5f, 2f), baseline.pitch)
        preparedStartMs = startPositionMs.coerceAtLeast(0L)
        spare.setMediaItem(item); spare.prepare(); spare.seekTo(preparedStartMs)
    }

    /**
     * Prime the dedicated loop deck with exactly the outgoing song's chosen
     * beat bars. Media3 clipping makes the player's own timeline just that
     * segment, and REPEAT_MODE_ONE wraps it without the service having to
     * seek on a timer at the audible seam.
     */
    fun prepareBeatLoop(item: MediaItem, loopStartMs: Long, loopEndMs: Long) {
        if (loopEndMs <= loopStartMs || loopStartMs < 0L) return
        val loop = loopPlayer ?: buildPlayer().also { loopPlayer = it }
        android.util.Log.i(
            "Crossfade",
            "prepareBeatLoop: loop=${tag(loop)} item=${item.mediaId} rangeMs=$loopStartMs..$loopEndMs",
        )
        setAutoMixLowShelf(loop, 0f)
        loop.stop(); loop.clearMediaItems(); loop.playWhenReady = false; loop.volume = 0f
        loop.repeatMode = Player.REPEAT_MODE_ONE
        if (::playerA.isInitialized) {
            val baseline = tempoReturnBaseline ?: playerA.playbackParameters
            loop.playbackParameters = baseline
        }
        val clipped = item.buildUpon()
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(loopStartMs)
                    .setEndPositionMs(loopEndMs)
                    .build(),
            )
            .build()
        preparedLoopMediaId = item.mediaId
        preparedLoopStartMs = loopStartMs
        preparedLoopEndMs = loopEndMs
        beatLoopShouldPlay = false
        loop.setMediaItem(clipped); loop.prepare(); loop.seekTo(0L)
    }

    fun isBeatLoopPreparedFor(mediaId: String?, loopStartMs: Long, loopEndMs: Long): Boolean {
        val loop = loopPlayer ?: return false
        return mediaId != null && preparedLoopMediaId == mediaId &&
            preparedLoopStartMs == loopStartMs && preparedLoopEndMs == loopEndMs &&
            loop.mediaItemCount > 0
    }

    fun isBeatLoopReady(): Boolean = loopPlayer?.playbackState == Player.STATE_READY

    fun beatLoopBufferedMs(): Long = loopPlayer?.bufferedPosition?.coerceAtLeast(0L) ?: 0L

    /**
     * The loop deck's own timeline duration. The service compares this with
     * the planned clip length before firing: if a media-source path ignored
     * MediaItem clipping, this would be the whole song and the bridge must
     * not start.
     */
    fun beatLoopDurationMs(): Long = loopPlayer?.duration ?: C.TIME_UNSET

    /** Stop and forget a primed/active loop. Safe when no loop was ever built. */
    fun clearBeatLoop() = stopBeatLoop()

    private fun stopBeatLoop() {
        loopPlayer?.let { loop ->
            setAutoMixLowShelf(loop, 0f)
            loop.playWhenReady = false
            loop.stop()
            loop.clearMediaItems()
            loop.repeatMode = Player.REPEAT_MODE_OFF
            loop.volume = 0f
        }
        beatLoopShouldPlay = false
        preparedLoopMediaId = null
        preparedLoopStartMs = -1L
        preparedLoopEndMs = -1L
    }

    /** Whether the spare has buffered enough to start the fade. */
    fun isNextReady(): Boolean =
        ::playerB.isInitialized && playerB.playbackState == Player.STATE_READY

    /** Diagnostics: the spare's current playbackState and primed item. */
    fun spareState(): Int = if (::playerB.isInitialized) playerB.playbackState else -1
    fun spareId(): String? = if (::playerB.isInitialized) playerB.currentMediaItem?.mediaId else null

    /**
     * How much the spare has buffered from its start (it is primed at position
     * 0). Used to gate the fire so we only crossfade into a spare that has at
     * least the fade length ready — a barely-READY spare stalls mid-fade on
     * cold streams.
     */
    fun spareBufferedMs(): Long =
        if (::playerB.isInitialized) playerB.bufferedPosition.coerceAtLeast(0) else 0

    fun spareBufferedAheadMs(): Long =
        if (::playerB.isInitialized) (playerB.bufferedPosition - playerB.currentPosition).coerceAtLeast(0) else 0

    /** True when the spare is primed with [mediaId] (so we don't re-prepare it). */
    fun isPreparedFor(mediaId: String?, startPositionMs: Long? = null): Boolean =
        mediaId != null && ::playerB.isInitialized &&
            playerB.mediaItemCount > 0 && playerB.currentMediaItem?.mediaId == mediaId &&
            (startPositionMs == null || abs(preparedStartMs - startPositionMs) <= 250L)

    fun performTransition(fadeMs: Long, onSwap: (ExoPlayer) -> Unit) {
        performTransition(fadeMs, null, onSwap)
    }

    fun performTransition(fadeMs: Long, speedProfile: CrossfadeSpeedProfile?, onSwap: (ExoPlayer) -> Unit) {
        if (transitioning || !::playerB.isInitialized || playerB.mediaItemCount == 0) return
        cancelTempoReturn(restoreBaseline = true)
        transitioning = true
        playerA.pauseAtEndOfMediaItems = true
        transitionJob = scope.launch {
            val outgoing = playerA
            val incoming = playerB
            var baseline = outgoing.playbackParameters
            activeBaselineParameters = baseline
            val profile = speedProfile
            val leadInMs = profile?.syncLeadInMs?.coerceIn(0L, fadeMs) ?: 0L
            val incomingPrerollMs = profile?.incomingPrerollMs?.coerceIn(0L, leadInMs) ?: 0L
            deferIncomingStart = leadInMs > incomingPrerollMs
            val syncRampMs = when {
                profile == null -> 0L
                leadInMs > 0L -> leadInMs
                abs(profile.outgoingTargetMultiplier - 1f) > SPEED_EPSILON -> minOf(4_000L, fadeMs / 4L)
                else -> 0L
            }
            var lastExpectedOutgoingSpeed = baseline.speed
            val loopProfile = profile?.beatLoop?.takeIf { candidate ->
                isBeatLoopPreparedFor(
                    outgoing.currentMediaItem?.mediaId,
                    candidate.loopStartMs,
                    candidate.loopEndMs,
                )
            }
            val loopDeck = if (loopProfile != null) loopPlayer else null
            var loopStarted = false
            var loopTakeoverElapsedMs: Long? = null
            var previousLoopPositionMs = -1L
            var completedBridgeCycles = 0
            var loopSyncSeeks = 0
            var incomingPhaseBend = 1f
            fun bridgeBpmAt(elapsedMs: Long): Float {
                val lp = loopProfile ?: return 0f
                val takeover = loopTakeoverElapsedMs ?: (fadeMs - lp.bridgeWallMs).coerceAtLeast(0L)
                val progress = if (lp.bridgeWallMs > 0L) {
                    ((elapsedMs - takeover).toFloat() / lp.bridgeWallMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    1f
                }
                return bridgeBpmAtProgress(lp.outgoingBpm, lp.targetBpm, progress)
            }
            fun loopParameters(elapsedMs: Long): PlaybackParameters {
                val lp = loopProfile ?: return baseline
                val multiplier = if (lp.outgoingBpm > 0f) bridgeBpmAt(elapsedMs) / lp.outgoingBpm else 1f
                return PlaybackParameters(
                    (baseline.speed * multiplier).coerceIn(0.25f, 4f),
                    baseline.pitch,
                )
            }
            fun outgoingMultiplier(elapsedMs: Long): Float {
                if (profile == null) return 1f
                if (syncRampMs <= 0L) return profile.outgoingTargetMultiplier
                return 1f + (profile.outgoingTargetMultiplier - 1f) * smoothStep(elapsedMs.toFloat() / syncRampMs.toFloat())
            }
            fun incomingParameters(elapsedMs: Long = fadeMs): PlaybackParameters = when {
                profile == null -> baseline
                loopProfile != null -> {
                    val multiplier = if (loopProfile.incomingAdjustedBpm > 0f) {
                        bridgeBpmAt(elapsedMs) / loopProfile.incomingAdjustedBpm * incomingPhaseBend
                    } else {
                        1f
                    }
                    PlaybackParameters((baseline.speed * multiplier).coerceIn(0.25f, 4f), baseline.pitch)
                }
                else -> PlaybackParameters(baseline.speed * profile.incomingMultiplier, baseline.pitch)
            }
            android.util.Log.i("Crossfade", "transition start: out=${tag(outgoing)} in=${tag(incoming)} fadeMs=$fadeMs profile=$profile")
            setAutoMixLowShelf(outgoing, 0f)
            setAutoMixLowShelf(incoming, if (profile?.bassSwap == true) -20f else 0f)
            incoming.volume = 0f
            incoming.playbackParameters = incomingParameters(0L)
            if (loopProfile != null && loopDeck != null) {
                setAutoMixLowShelf(loopDeck, 0f)
                loopDeck.volume = 0f
                loopDeck.repeatMode = Player.REPEAT_MODE_ONE
                loopDeck.playbackParameters = baseline
                loopDeck.seekTo(0L)
                loopDeck.playWhenReady = true
                loopDeck.play()
                loopStarted = true
                beatLoopShouldPlay = true
            }
            var incomingStarted = false
            fun startIncoming() {
                if (incomingStarted) return
                incomingStarted = true; deferIncomingStart = false; incoming.playWhenReady = true; incoming.play()
            }
            if (leadInMs == 0L) {
                startIncoming()
                var w = 0L
                while (!incoming.isPlaying && w < START_TIMEOUT_MS) { delay(STEP_MS); w += STEP_MS }
            }
            var elapsed = 0L
            while (elapsed < fadeMs) {
                if (incomingIndexIn(outgoing, incoming) == null) { handBackToOutgoing(); return@launch }
                if (loopProfile != null && loopDeck != null && loopStarted) {
                    val loopPositionMs = loopDeck.currentPosition.coerceIn(0L, loopProfile.loopDurationMs)
                    if (previousLoopPositionMs >= 0L &&
                        loopPositionMs + 150L < previousLoopPositionMs &&
                        loopTakeoverElapsedMs != null
                    ) {
                        completedBridgeCycles++
                    }
                    previousLoopPositionMs = loopPositionMs

                    if (loopTakeoverElapsedMs == null) {
                        // The loop deck started silently one segment early.
                        // Keep it on the master's own position inside that
                        // segment, so handing over at the segment boundary
                        // is a continuation, not a second copy of the beat
                        // flamming against the first.
                        val relativeMs = outgoing.currentPosition - loopProfile.loopStartMs
                        val expectedLoopMs = ((relativeMs % loopProfile.loopDurationMs) +
                            loopProfile.loopDurationMs) % loopProfile.loopDurationMs
                        var loopErrorMs = expectedLoopMs - loopPositionMs
                        if (loopErrorMs > loopProfile.loopDurationMs / 2L) {
                            loopErrorMs -= loopProfile.loopDurationMs
                        } else if (loopErrorMs < -loopProfile.loopDurationMs / 2L) {
                            loopErrorMs += loopProfile.loopDurationMs
                        }
                        if (abs(loopErrorMs) > 90L && loopSyncSeeks < 4 &&
                            (elapsed > 100L || loopSyncSeeks == 0)
                        ) {
                            loopDeck.seekTo(expectedLoopMs)
                            previousLoopPositionMs = expectedLoopMs
                            loopSyncSeeks++
                        }
                        val nominalTakeoverMs = (fadeMs - loopProfile.bridgeWallMs).coerceAtLeast(0L)
                        if (outgoing.currentPosition >= loopProfile.loopEndMs - 40L ||
                            elapsed >= nominalTakeoverMs + 750L
                        ) {
                            loopTakeoverElapsedMs = elapsed
                            completedBridgeCycles = 0
                            android.util.Log.i(
                                "Crossfade",
                                "beat loop takeover: elapsed=$elapsed outgoingPos=${outgoing.currentPosition} " +
                                    "loopPos=$loopPositionMs syncSeeks=$loopSyncSeeks",
                            )
                        }
                    }

                    if (loopTakeoverElapsedMs != null) {
                        if (incomingStarted) {
                            // Correct only live drift (decoder/start/output
                            // latency) against the loop the listener is
                            // actually hearing now. The bend is capped in
                            // beatPhaseBendFactor.
                            val loopPhase = loopDeck.currentPosition / loopProfile.outgoingBeatMs
                            val incomingPhase =
                                (incoming.currentPosition - loopProfile.incomingEntryMs) /
                                    loopProfile.incomingBeatMs
                            incomingPhaseBend = beatPhaseBendFactor(
                                circularBeatPhaseError(loopPhase, incomingPhase),
                            )
                        } else {
                            val cyclesBeforeEntry = (
                                loopProfile.bridgeLoopCycles - loopProfile.finalOverlapLoopCycles
                                ).coerceAtLeast(1)
                            val primeBeats = (loopProfile.incomingEntryMs - preparedStartMs)
                                .coerceAtLeast(0L) / loopProfile.incomingBeatMs
                            val beatsLeftInLoop = (loopProfile.loopDurationMs - loopPositionMs)
                                .coerceAtLeast(0L) / loopProfile.outgoingBeatMs
                            val startBeforeEntry = completedBridgeCycles == cyclesBeforeEntry - 1 &&
                                beatsLeftInLoop <= primeBeats + 0.05f
                            val atEntryCycle = primeBeats <= 0.01f &&
                                completedBridgeCycles >= cyclesBeforeEntry
                            if (startBeforeEntry || atEntryCycle) startIncoming()
                        }
                    }
                }
                if (loopProfile != null && loopDeck != null) {
                    // The master stays at the user's own speed; only the
                    // separate loop deck changes tempo. Reading the master
                    // each tick therefore follows a mid-transition user speed
                    // change on all three decks.
                    baseline = outgoing.playbackParameters
                    activeBaselineParameters = baseline
                    lastExpectedOutgoingSpeed = baseline.speed
                    if (loopStarted) {
                        val expectedLoop = loopParameters(elapsed)
                        if (loopDeck.playbackParameters != expectedLoop) {
                            loopDeck.playbackParameters = expectedLoop
                        }
                    }
                    val expectedIncoming = incomingParameters(elapsed)
                    if (incoming.playbackParameters != expectedIncoming) {
                        incoming.playbackParameters = expectedIncoming
                    }
                } else if (profile != null) {
                    val multiplier = outgoingMultiplier(elapsed)
                    val actual = outgoing.playbackParameters
                    if (abs(actual.speed - lastExpectedOutgoingSpeed) > SPEED_EPSILON && multiplier > 0f) {
                        baseline = PlaybackParameters((actual.speed / multiplier).coerceIn(0.1f, 4f), actual.pitch)
                        activeBaselineParameters = baseline
                    }
                    val expectedOutgoing = PlaybackParameters(baseline.speed * multiplier, baseline.pitch)
                    if (outgoing.playbackParameters != expectedOutgoing) outgoing.playbackParameters = expectedOutgoing
                    lastExpectedOutgoingSpeed = expectedOutgoing.speed
                    val expectedIncoming = incomingParameters(elapsed)
                    if (incoming.playbackParameters != expectedIncoming) incoming.playbackParameters = expectedIncoming
                } else {
                    baseline = outgoing.playbackParameters; activeBaselineParameters = baseline; lastExpectedOutgoingSpeed = baseline.speed
                    if (incoming.playbackParameters != baseline) incoming.playbackParameters = baseline
                }
                if (loopProfile == null) {
                    if (elapsed >= leadInMs - incomingPrerollMs) startIncoming()
                } else if (!incomingStarted && elapsed >= leadInMs + 500L) {
                    // Timing fallback only; the loop-phase trigger above is
                    // the normal, musical start.
                    startIncoming()
                }
                val audibleProgress = if (elapsed < leadInMs) null else {
                    ((elapsed - leadInMs).toFloat() / (fadeMs - leadInMs).coerceAtLeast(1L).toFloat())
                        .coerceIn(0f, 1f)
                }
                var outVolume: Float
                var incVolume: Float
                if (audibleProgress == null) {
                    outVolume = 1f; incVolume = 0f
                } else {
                    val volumes = when (profile?.blendKind ?: CrossfadeBlendKind.EQUAL_POWER) {
                        CrossfadeBlendKind.BEAT_LOCK -> autoMixBlendVolumes(audibleProgress)
                        CrossfadeBlendKind.BEAT_LOOP_BRIDGE -> autoMixBeatLoopVolumes(audibleProgress)
                        CrossfadeBlendKind.CLEAN_BLEND -> autoMixCleanBlendVolumes(audibleProgress)
                        CrossfadeBlendKind.PHRASE_CUT -> autoMixPhraseCutVolumes(audibleProgress)
                        CrossfadeBlendKind.EQUAL_POWER -> equalPowerVolumes(audibleProgress)
                    }
                    outVolume = volumes.first; incVolume = volumes.second
                }
                var loopVolume = 0f
                if (loopProfile != null && loopDeck != null) {
                    val takeover = loopTakeoverElapsedMs
                    if (takeover == null) {
                        outVolume = 1f; incVolume = 0f; loopVolume = 0f
                        setAutoMixLowShelf(outgoing, 0f)
                        setAutoMixLowShelf(loopDeck, 0f)
                        setAutoMixLowShelf(incoming, -20f)
                    } else {
                        // A quarter-second hand-off at the exact segment
                        // boundary hides decoder/output differences between
                        // the master and the phase-synced loop copy.
                        val takeoverProgress = ((elapsed - takeover).toFloat() / 250f).coerceIn(0f, 1f)
                        outVolume = 1f - takeoverProgress
                        if (audibleProgress == null) {
                            loopVolume = takeoverProgress; incVolume = 0f
                            setAutoMixLowShelf(outgoing, -20f)
                            setAutoMixLowShelf(loopDeck, 0f)
                            setAutoMixLowShelf(incoming, -20f)
                        } else {
                            val (loopGain, incomingGain) = autoMixBeatLoopVolumes(audibleProgress)
                            loopVolume = loopGain * takeoverProgress; incVolume = incomingGain
                            val (loopShelfDb, inShelfDb) = autoMixBassSwapShelves(audibleProgress)
                            setAutoMixLowShelf(outgoing, -20f)
                            setAutoMixLowShelf(loopDeck, loopShelfDb)
                            setAutoMixLowShelf(incoming, inShelfDb)
                        }
                    }
                    loopDeck.volume = if (loopStarted) loopVolume else 0f
                } else if (profile?.bassSwap == true) {
                    val (outShelfDb, inShelfDb) = autoMixBassSwapShelves(audibleProgress ?: 0f)
                    setAutoMixLowShelf(outgoing, outShelfDb)
                    setAutoMixLowShelf(incoming, inShelfDb)
                }
                outgoing.volume = outVolume; incoming.volume = if (incomingStarted) incVolume else 0f
                val outgoingEndStopsTransition = outgoing.playbackState == Player.STATE_ENDED &&
                    !(loopProfile != null && loopTakeoverElapsedMs != null)
                if (outgoingEndStopsTransition || incoming.playbackState == Player.STATE_ENDED) break
                delay(STEP_MS); elapsed += STEP_MS
            }
            setAutoMixLowShelf(incoming, 0f)
            outgoing.volume = 0f; incoming.volume = 1f; startIncoming()
            if (profile?.beatLoop != null) {
                incomingPhaseBend = 1f
                stopBeatLoop()
            }
            if (!isActive) return@launch
            val incomingIndex = incomingIndexIn(outgoing, incoming)
            if (incomingIndex == null) { handBackToOutgoing(); return@launch }
            transferQueue(outgoing, incoming, incomingIndex, if (profile == null) null else incomingParameters())
            outgoing.removeListener(masterFocusListener); incoming.addListener(masterFocusListener)
            incoming.pauseAtEndOfMediaItems = false; playerA = incoming; playerB = outgoing
            if (incoming.playWhenReady) requestFocus()
            onSwap(playerA)
            outgoing.pauseAtEndOfMediaItems = false; outgoing.playWhenReady = false; outgoing.stop(); outgoing.clearMediaItems()
            setAutoMixLowShelf(outgoing, 0f); setAutoMixLowShelf(incoming, 0f)
            outgoing.volume = 1f; outgoing.playbackParameters = baseline; preparedStartMs = 0L; activeBaselineParameters = null; transitioning = false
            if (profile != null && profile.tempoReturnMs > 0L && abs(profile.incomingMultiplier - 1f) > SPEED_EPSILON) {
                startTempoReturn(incoming, profile.incomingMultiplier, baseline, profile.tempoReturnMs)
            }
        }
    }

    private fun startTempoReturn(player: ExoPlayer, fromMultiplier: Float, baseline: PlaybackParameters, durationMs: Long) {
        tempoReturnJob?.cancel(); tempoReturnPlayer = player
        var home = baseline; tempoReturnBaseline = home
        tempoReturnJob = scope.launch {
            var elapsed = 0L; var lastMultiplier = fromMultiplier; var lastExpectedSpeed = home.speed * fromMultiplier
            while (elapsed < durationMs) {
                delay(STEP_MS); elapsed += STEP_MS
                val actual = player.playbackParameters
                if (abs(actual.speed - lastExpectedSpeed) > SPEED_EPSILON && lastMultiplier > 0f) {
                    home = PlaybackParameters((actual.speed / lastMultiplier).coerceIn(0.1f, 4f), actual.pitch); tempoReturnBaseline = home
                }
                val multiplier = fromMultiplier + (1f - fromMultiplier) * smoothStep(elapsed.toFloat() / durationMs.toFloat())
                val expected = PlaybackParameters(home.speed * multiplier, home.pitch)
                player.playbackParameters = expected; lastMultiplier = multiplier; lastExpectedSpeed = expected.speed
            }
            player.playbackParameters = home
            val finishedId = player.currentMediaItem?.mediaId
            tempoReturnBaseline = null; tempoReturnPlayer = null; tempoReturnJob = null
            onTempoReturnFinished?.invoke(finishedId)
        }
    }

    private fun cancelTempoReturn(restoreBaseline: Boolean) {
        val player = tempoReturnPlayer; val baseline = tempoReturnBaseline
        tempoReturnJob?.cancel(); tempoReturnJob = null; tempoReturnPlayer = null; tempoReturnBaseline = null
        if (restoreBaseline && player != null && baseline != null) player.playbackParameters = baseline
    }

    private fun smoothStep(t: Float): Float { val c = t.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    /**
     * [outgoing]'s timeline index of the song [incoming] is playing, or null
     * when that song is no longer the outgoing's NEXT one.
     *
     * Edits land on the outgoing (the session's player) and raise no item
     * transition, so the fade used to run on regardless: the upcoming song
     * swiped away or evicted mid-fade made the old by-mediaId lookup miss, the
     * transfer returned early, and the swap promoted a ONE-item player whose
     * timeline the repository then persisted over the user's whole queue. A
     * "Play next" inserted mid-fade sits at outgoing + 1 and ended up in the
     * new master's history, never played. Shuffle or repeat changed mid-fade
     * moves "next" the same way. And with duplicates (`[A, B, C, A, D]`,
     * fading C into the second A) the first-match lookup put the new master
     * on the FIRST A, replaying B and C. Reading the outgoing's own next index
     * answers both: it is where the incoming must sit, and if it no longer
     * holds the incoming's song the fade is handed back.
     */
    private fun incomingIndexIn(outgoing: ExoPlayer, incoming: ExoPlayer): Int? {
        val next = outgoing.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return null
        val id = incoming.currentMediaItem?.mediaId ?: return null
        return next.takeIf { outgoing.getMediaItemAt(next).mediaId == id }
    }

    /**
     * Copies [from]'s queue around [to]'s current item so the promoted player
     * has the full timeline (history + future) with the same current index and
     * the position it has been playing, and the same shuffle (play) order.
     * [incomingIndex] is [to]'s song's index in [from] (see [incomingIndexIn]),
     * so it is shuffle-, repeat- and duplicate-safe.
     */
    private fun transferQueue(from: ExoPlayer, to: ExoPlayer, incomingIndex: Int, incomingParameters: PlaybackParameters? = null) {
        val count = from.mediaItemCount
        val idx = incomingIndex
        val history = (0 until idx).map { from.getMediaItemAt(it) }
        val future = ((idx + 1) until count).map { from.getMediaItemAt(it) }
        to.repeatMode = from.repeatMode
        to.shuffleModeEnabled = from.shuffleModeEnabled
        to.playbackParameters = incomingParameters ?: from.playbackParameters
        if (history.isNotEmpty()) to.addMediaItems(0, history) // shifts `to`'s current index up
        if (future.isNotEmpty()) to.addMediaItems(future)
        // Carry the PLAY order over too. Each addMediaItems above lands the inserted indexes at
        // random points of `to`'s fresh one-item shuffle order (DefaultShuffleOrder.cloneAndInsert),
        // so under shuffle the current track sat at a random spot of a brand-new walk after every
        // fade: songs already heard came back, and with repeat off about half the playlist ended up
        // "behind" the current one and never played. A browser handoff's custom order
        // (COMMAND_SET_SHUFFLE_ORDER) was lost at the first fade the same way. The timeline is now
        // index-for-index the outgoing's, so its (immutable) order applies as is; the count check
        // only keeps setShuffleOrder's length precondition from throwing should they ever differ.
        if (to.mediaItemCount == from.mediaItemCount) to.shuffleOrder = from.shuffleOrder
    }

    /**
     * Aborts the fade for a reason that is not a pause (a seek on the outgoing,
     * a queue edit that moved its next song; see [incomingIndexIn]),
     * leaving the outgoing master playing. If the outgoing already reached its
     * end, pauseAtEndOfMediaItems is holding it there paused, and a plain
     * [cancelTransition] would leave silence. A user pause or a focus loss
     * mid-fade cancels the fade itself (or sets [pausedForFocusLoss]), so a
     * paused outgoing here is exactly that hold, and it plays on.
     */
    private fun handBackToOutgoing() {
        val outgoing = playerA
        val heldAtEnd = !outgoing.playWhenReady && !pausedForFocusLoss
        cancelTransition()
        if (heldAtEnd) outgoing.play()
    }

    /** Abort a pending/in-flight fade and restore the master; spare is reset. */
    fun cancelTransition() {
        val wasTransitioning = transitioning
        transitionJob?.cancel(); transitionJob = null; cancelTempoReturn(restoreBaseline = true)
        if (::playerA.isInitialized) {
            setAutoMixLowShelf(playerA, 0f)
            playerA.volume = 1f; playerA.pauseAtEndOfMediaItems = false
            activeBaselineParameters?.let { playerA.playbackParameters = it }
        }
        if (::playerB.isInitialized) {
            setAutoMixLowShelf(playerB, 0f)
            playerB.playWhenReady = false; playerB.stop(); playerB.clearMediaItems(); playerB.volume = 1f
            if (::playerA.isInitialized) playerB.playbackParameters = playerA.playbackParameters
        }
        stopBeatLoop()
        preparedStartMs = 0L; activeBaselineParameters = null; deferIncomingStart = false; transitioning = false
        if (wasTransitioning) onTransitionCancelled?.invoke()
    }

    fun release() {
        transitionJob?.cancel()
        cancelTempoReturn(restoreBaseline = false)
        abandonFocus()
        if (::playerA.isInitialized) {
            playerA.removeListener(masterFocusListener)
            playerA.release()
        }
        if (::playerB.isInitialized) playerB.release()
        loopPlayer?.release()
        loopPlayer = null
    }

    private companion object {
        const val STEP_MS = 50L
        const val START_TIMEOUT_MS = 1000L
        const val DUCK_VOLUME = 0.2f
        const val SPEED_EPSILON = 0.0015f
    }
}
