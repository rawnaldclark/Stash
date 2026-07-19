package com.stash.core.media.service

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    // ── Manual audio focus (shared across both players) ──────────────────────
    private var focusRequest: AudioFocusRequest? = null
    private var pausedForFocusLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForFocusLoss = false
                playerA.playWhenReady = false
                playerB.playWhenReady = false
                abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedForFocusLoss = true
                playerA.playWhenReady = false
                playerB.playWhenReady = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Duck the audible master; the spare is silent unless mid-fade.
                playerA.volume = DUCK_VOLUME
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
                    if (transitioning) playerB.playWhenReady = true
                }
            }
        }
    }

    /** Requests focus when the master starts; abandons when it stops. */
    private val masterFocusListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // During a fade this listener still sits on the OUTGOING player,
            // whose pauseAtEndOfMediaItems fires playWhenReady=false if it
            // runs out mid-ramp (streaming durations drift). The incoming is
            // still audible — abandoning would drop the whole app out of the
            // focus stack while music keeps playing (#251 car-route ghost).
            if (playWhenReady) requestFocus()
            else if (!pausedForFocusLoss && !transitioning) abandonFocus()
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
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it); focusRequest = null }
    }

    /** Stable per-instance tag so field logs name WHICH player each stop hit. */
    private fun tag(p: ExoPlayer) = "p" + Integer.toHexString(System.identityHashCode(p))

    /** Prime the spare on [item] (buffered, paused, silent) for an upcoming fade. */
    fun prepareNext(item: MediaItem) {
        val spare = playerB
        android.util.Log.i(
            "Crossfade",
            "prepareNext: stop+prime spare=${tag(spare)} (master=${tag(playerA)}) item=${item.mediaId}",
        )
        spare.stop()
        spare.clearMediaItems()
        spare.playWhenReady = false
        spare.volume = 0f
        spare.setMediaItem(item)
        spare.prepare()
        spare.seekTo(0)
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

    /** True when the spare is primed with [mediaId] (so we don't re-prepare it). */
    fun isPreparedFor(mediaId: String?): Boolean =
        mediaId != null && ::playerB.isInitialized &&
            playerB.mediaItemCount > 0 && playerB.currentMediaItem?.mediaId == mediaId

    /**
     * Runs the crossfade and the late role-swap. [fadeMs] is the ramp length;
     * [onSwap] is invoked with the new master AFTER its queue is in place so the
     * service can `setPlayer` + move its own listeners. No-ops if nothing primed.
     */
    fun performTransition(fadeMs: Long, onSwap: (ExoPlayer) -> Unit) {
        if (transitioning || !::playerB.isInitialized || playerB.mediaItemCount == 0) return
        transitioning = true
        // Prevent the outgoing master from auto-advancing to the next item on
        // its own during the fade (we drive the transition manually).
        playerA.pauseAtEndOfMediaItems = true
        transitionJob = scope.launch {
            val outgoing = playerA
            val incoming = playerB
            android.util.Log.i(
                "Crossfade",
                "transition start: out=${tag(outgoing)} in=${tag(incoming)} fadeMs=$fadeMs",
            )
            incoming.volume = 0f
            incoming.playWhenReady = true
            incoming.play()
            // Wait for the incoming to actually produce audio (bounded).
            var w = 0L
            while (!incoming.isPlaying && w < START_TIMEOUT_MS) { delay(STEP_MS); w += STEP_MS }

            var elapsed = 0L
            while (elapsed < fadeMs) {
                val (out, inc) = equalPowerVolumes(elapsed.toFloat() / fadeMs)
                outgoing.volume = out
                incoming.volume = inc
                if (elapsed % 1000L < STEP_MS) {
                    android.util.Log.i(
                        "Crossfade",
                        "ramp t=$elapsed set out=$out in=$inc | read A.vol=${outgoing.volume} B.vol=${incoming.volume} A.playing=${outgoing.isPlaying} B.playing=${incoming.isPlaying} A.state=${outgoing.playbackState} B.state=${incoming.playbackState}",
                    )
                }
                if (outgoing.playbackState == Player.STATE_ENDED ||
                    incoming.playbackState == Player.STATE_ENDED
                ) break
                delay(STEP_MS)
                elapsed += STEP_MS
            }
            outgoing.volume = 0f
            incoming.volume = 1f

            // Late swap: give the incoming the outgoing's queue, then promote it.
            transferQueue(from = outgoing, to = incoming)
            outgoing.removeListener(masterFocusListener)
            incoming.addListener(masterFocusListener)
            incoming.pauseAtEndOfMediaItems = false
            playerA = incoming
            playerB = outgoing
            // The incoming's playWhenReady=true edge fired while it was a
            // listener-less spare, so no focus request ever ran for it. If
            // focus was lost/abandoned during the ramp, the new master would
            // play focusless forever. Idempotent when focus is already held.
            requestFocus()
            android.util.Log.i(
                "Crossfade",
                "swap: master=${tag(incoming)} spare=${tag(outgoing)} (resetting spare)",
            )
            onSwap(playerA)

            // Reset the old master to a clean spare.
            outgoing.pauseAtEndOfMediaItems = false
            outgoing.playWhenReady = false
            outgoing.stop()
            outgoing.clearMediaItems()
            outgoing.volume = 1f
            transitioning = false
        }
    }

    /**
     * Copies [from]'s queue around [to]'s current item so the promoted player
     * has the full timeline (history + future) with the same current index and
     * the position it has been playing. Matches by mediaId so it is shuffle- and
     * repeat-order safe.
     */
    private fun transferQueue(from: ExoPlayer, to: ExoPlayer) {
        val currentId = to.currentMediaItem?.mediaId ?: return
        val count = from.mediaItemCount
        val idx = (0 until count).firstOrNull { from.getMediaItemAt(it).mediaId == currentId } ?: return
        val history = (0 until idx).map { from.getMediaItemAt(it) }
        val future = ((idx + 1) until count).map { from.getMediaItemAt(it) }
        to.repeatMode = from.repeatMode
        to.shuffleModeEnabled = from.shuffleModeEnabled
        to.playbackParameters = from.playbackParameters
        if (history.isNotEmpty()) to.addMediaItems(0, history) // shifts `to`'s current index up
        if (future.isNotEmpty()) to.addMediaItems(future)
    }

    /** Abort a pending/in-flight fade and restore the master; spare is reset. */
    fun cancelTransition() {
        transitionJob?.cancel()
        transitionJob = null
        if (::playerA.isInitialized && ::playerB.isInitialized) {
            android.util.Log.i(
                "Crossfade",
                "cancel: restore master=${tag(playerA)} stop spare=${tag(playerB)} " +
                    "(wasTransitioning=$transitioning)",
            )
        }
        if (::playerA.isInitialized) {
            playerA.volume = 1f
            playerA.pauseAtEndOfMediaItems = false
        }
        if (::playerB.isInitialized) {
            playerB.playWhenReady = false
            playerB.stop()
            playerB.clearMediaItems()
            playerB.volume = 1f
        }
        transitioning = false
    }

    fun release() {
        transitionJob?.cancel()
        abandonFocus()
        if (::playerA.isInitialized) {
            playerA.removeListener(masterFocusListener)
            playerA.release()
        }
        if (::playerB.isInitialized) playerB.release()
    }

    private companion object {
        const val STEP_MS = 50L
        const val START_TIMEOUT_MS = 1000L
        const val DUCK_VOLUME = 0.2f
    }
}
