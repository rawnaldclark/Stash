package com.stash.core.media.service

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.stash.core.media.streaming.isPutBack
import kotlinx.coroutines.isActive
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

    /** The running fade's [performTransition] `onSwap`, for whichever path promotes the incoming ([promoteIncoming]). */
    private var pendingSwap: ((ExoPlayer) -> Unit)? = null
    fun isTransitioning(): Boolean = transitioning

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
                abandonFocus()
                onFocusPause?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Only what this loss pauses comes back when it ends: a song
                // that was already paused stays paused.
                pausedForFocusLoss = playerA.playWhenReady || (transitioning && playerB.playWhenReady)
                playerA.playWhenReady = false
                playerB.playWhenReady = false
                onFocusPause?.invoke()
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
            // The outgoing ran out before the ramp did: a long fade outlasts the song's last
            // seconds (the ramp counts 50 ms steps, and each runs a little long), a stream ends
            // before its stated length. pauseAtEndOfMediaItems holds it there, and while it held,
            // the session's player was a finished song that read as paused. A play from the
            // notification, a headset, a car or the end of a short focus loss started it again:
            // it moved on into the next song from 0, the song the incoming was already playing,
            // and the hand-back then stopped the incoming. The song restarted. Nothing is left to
            // blend, so the incoming takes over now, before any later listener (the session's)
            // hears of the hold; the ramp finishes its rise.
            if (!playWhenReady && transitioning &&
                reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            ) {
                android.util.Log.i(
                    "Crossfade",
                    "outgoing=${tag(playerA)} ran out mid-fade: promoting incoming=${tag(playerB)} now",
                )
                promoteIncoming()
                return
            }
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
            // During a fade this listener still sits on the OUTGOING player.
            // Any pause of it that leaves the fade running (its run-out is
            // handled above) leaves the incoming audible — abandoning would
            // drop the whole app out of the focus stack while music keeps
            // playing (#251 car-route ghost).
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

        /** The queue changed: if the slot the spare was primed for was put back, the spare goes ([dropSpareIfItsSlotWasPutBack]). */
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) dropSpareIfItsSlotWasPutBack()
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
        // At the master's speed, so the next song doesn't change tempo when it takes over.
        spare.playbackParameters = playerA.playbackParameters
        primedUri = item.localConfiguration?.uri
        spare.setMediaItem(item)
        spare.prepare()
        spare.seekTo(0)
    }

    /**
     * Tuning came on or went off. A player's audio output only notices on a write while it plays,
     * then rebuilds itself (a short re-buffer): for a paused master that is the moment it resumes,
     * for the primed spare the moment its fade starts. A seek resets the audio renderer, which
     * flushes the sink and builds a new output (for the new tuning) as it pre-fills: done now, the
     * idle players rebuild while silent. Not mid-fade: both players are playing then, and rebuild
     * as they play.
     *
     * The seek lands 1 ms from where the player stands, not on it: ExoPlayer drops a seek to its
     * current position (same millisecond, READY or BUFFERING) without resetting anything, and a
     * paused player's position doesn't move. 1 ms back (forward from 0) is inaudible.
     */
    fun rebuildIdleOutputs() {
        if (transitioning || !::playerA.isInitialized || !::playerB.isInitialized) return
        for (p in listOf(playerA, playerB)) {
            if (p.isPlaying || p.mediaItemCount == 0) continue
            val state = p.playbackState
            if (state != Player.STATE_READY && state != Player.STATE_BUFFERING) continue
            val at = p.currentPosition
            android.util.Log.i("Crossfade", "tuning changed: re-seeking idle ${tag(p)} at $at ms to rebuild its output")
            p.seekTo(rebuildSeekTarget(at))
        }
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

    /**
     * True when the spare is primed with [item] (so we don't re-prepare it): the same song, and
     * its slot not put back since. A slot put back to be resolved again (`stash-resolve://` with
     * [isPutBack]: its link came from an add-on that left or was set aside, or a new quality was
     * picked) is another item: priming the spare again builds it a new source, so the fade never
     * plays what the slot no longer holds. Anything else keeps the spare, as it did when only the
     * song was compared: a change of metadata (a cover, the quality stamped), a placeholder the
     * look-ahead swapped for the very URL it resolved to, a slot that now shows its manifest
     * (review-888 m2: re-priming on those fetched the same file again and could miss the fade).
     * A put-back the poll never sees, because the look-ahead filled the slot again first (or the
     * song was paused), lets the spare go as it happens ([dropSpareIfItsSlotWasPutBack]). A slot
     * that became the song's downloaded file is another item too: the fade plays the file, not the
     * stream the spare holds (review-888 R2-n2).
     */
    fun isPreparedFor(item: MediaItem?): Boolean {
        if (item == null || !::playerB.isInitialized || playerB.mediaItemCount == 0) return false
        val primed = playerB.currentMediaItem ?: return false
        if (primed.mediaId != item.mediaId) return false
        val uri = item.localConfiguration?.uri
        if (uri == primedUri) return true
        if (uri?.scheme == "file" || uri?.scheme == "content") return false
        return uri?.isPutBack() != true
    }

    /** The URI the spare was last primed on ([prepareNext]), as its slot gave it. */
    @Volatile private var primedUri: android.net.Uri? = null

    /**
     * The master's queue changed. When the slot the spare was primed for (the next one) now holds a
     * put-back placeholder it wasn't primed on, the spare holds a stream the slot no longer does: an
     * add-on that left or was set aside, or a quality changed. It is let go now, as for a refusal
     * ([dropSpareFor]), and the poll primes it again on the slot as it then stands.
     *
     * Not left to [isPreparedFor] alone: the poll runs only while the master plays, every 250 ms, and
     * the look-ahead may fill the slot again first (a paused song, or an answer within 250 ms). The
     * slot then holds another source's URL, which [isPreparedFor] keeps the spare on, and the fade
     * played the add-on's stream that had just been removed (review-888 R2-M1). Main thread.
     */
    private fun dropSpareIfItsSlotWasPutBack() {
        if (transitioning || !::playerB.isInitialized || playerB.mediaItemCount == 0) return
        val spareId = playerB.currentMediaItem?.mediaId ?: return
        val master = playerA
        val next = master.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return
        val slot = master.getMediaItemAt(next)
        if (slot.mediaId != spareId) return
        val uri = slot.localConfiguration?.uri ?: return
        if (uri != primedUri && uri.isPutBack()) dropSpareFor(spareId)
    }

    /**
     * Lets the spare go when it is primed (and not fading in) on the song [mediaId]: the player
     * refused the add-on stream it opened for it, or its slot was put back
     * ([dropSpareIfItsSlotWasPutBack]). The master and its pause-at-end hold are left
     * alone ([cancelTransition] would clear the hold the sleep timer's end-of-song relies on).
     * True when the spare was let go; the poll primes it again on the next song as its slot stands.
     */
    fun dropSpareFor(mediaId: String): Boolean {
        if (transitioning || !::playerB.isInitialized || playerB.mediaItemCount == 0) return false
        if (playerB.currentMediaItem?.mediaId != mediaId) return false
        android.util.Log.i("Crossfade", "drop spare=${tag(playerB)} item=$mediaId")
        playerB.playWhenReady = false
        playerB.stop()
        playerB.clearMediaItems()
        playerB.volume = 1f
        return true
    }

    /**
     * Runs the crossfade and the late role-swap. [fadeMs] is the ramp length;
     * [onSwap] is invoked with the new master AFTER its queue is in place so the
     * service can `setPlayer` + move its own listeners. No-ops if nothing primed.
     *
     * The swap comes at the end of the ramp, or as soon as the outgoing runs out
     * if that is first (masterFocusListener); the ramp then only finishes the
     * incoming's rise, on what is now the master.
     */
    fun performTransition(fadeMs: Long, onSwap: (ExoPlayer) -> Unit) {
        if (transitioning || !::playerB.isInitialized || playerB.mediaItemCount == 0) return
        // The last fade's rise may still be finishing on what is now the outgoing.
        transitionJob?.cancel()
        transitioning = true
        pendingSwap = onSwap
        // Prevent the outgoing master from auto-advancing to the next item on
        // its own during the fade (we drive the transition manually).
        playerA.pauseAtEndOfMediaItems = true
        // Taken now, not when the job first runs: an early promote swaps playerA and playerB.
        val outgoing = playerA
        val incoming = playerB
        transitionJob = scope.launch {
            android.util.Log.i(
                "Crossfade",
                "transition start: out=${tag(outgoing)} in=${tag(incoming)} fadeMs=$fadeMs",
            )
            incoming.volume = 0f
            incoming.playbackParameters = outgoing.playbackParameters
            incoming.playWhenReady = true
            incoming.play()
            // Wait for the incoming to actually produce audio (bounded).
            var w = 0L
            while (!incoming.isPlaying && w < START_TIMEOUT_MS) { delay(STEP_MS); w += STEP_MS }

            var elapsed = 0L
            while (elapsed < fadeMs) {
                // The outgoing ran out and the incoming took over early
                // (masterFocusListener): it is the master now, and only its rise is left.
                val promoted = playerA === incoming
                // The queue moved under the fade (see incomingIndexIn): hand back
                // now, while the outgoing is still audible, not at the swap.
                if (!promoted && incomingIndexIn(outgoing, incoming) == null) {
                    handBackToOutgoing()
                    return@launch
                }
                val (out, inc) = equalPowerVolumes(elapsed.toFloat() / fadeMs)
                if (!promoted) outgoing.volume = out
                incoming.volume = inc
                // A speed picked mid-fade lands on the outgoing master (the session's player);
                // the incoming follows within a step instead of jumping at the swap.
                val speed = outgoing.playbackParameters
                if (!promoted && incoming.playbackParameters != speed) incoming.playbackParameters = speed
                if (elapsed % 1000L < STEP_MS) {
                    android.util.Log.i(
                        "Crossfade",
                        "ramp t=$elapsed set out=$out in=$inc | read A.vol=${outgoing.volume} B.vol=${incoming.volume} A.playing=${outgoing.isPlaying} B.playing=${incoming.isPlaying} A.state=${outgoing.playbackState} B.state=${incoming.playbackState} promoted=$promoted",
                    )
                }
                if (!promoted && (outgoing.playbackState == Player.STATE_ENDED ||
                        incoming.playbackState == Player.STATE_ENDED)
                ) break
                delay(STEP_MS)
                elapsed += STEP_MS
            }
            incoming.volume = 1f
            if (playerA === incoming) return@launch // promoted early: the rise was all that was left

            outgoing.volume = 0f

            // A cancelTransition() call landing after the fade loop's last delay()
            // has nothing to interrupt below this point (no suspension calls until
            // job completion) — without this check the promote-and-clear tail runs
            // to completion even though the transition was supposed to be aborted,
            // which can leave the wrong player wired to the session mid-user-tap.
            if (!isActive) return@launch

            promoteIncoming()
        }
    }

    /**
     * The late swap: gives the incoming the outgoing's queue, then promotes it to
     * master. At the end of the ramp, or when the outgoing runs out first
     * (masterFocusListener). The incoming's index is re-checked here, and nothing
     * suspends from here on, so no edit can slip in between the check and the
     * transfer; if its song is no longer the outgoing's next, the fade is handed
     * back instead ([handBackToOutgoing]). Main thread.
     */
    private fun promoteIncoming() {
        val outgoing = playerA
        val incoming = playerB
        val incomingIndex = incomingIndexIn(outgoing, incoming)
        if (incomingIndex == null) {
            handBackToOutgoing()
            return
        }
        transferQueue(from = outgoing, to = incoming, incomingIndex = incomingIndex)
        outgoing.removeListener(masterFocusListener)
        incoming.addListener(masterFocusListener)
        incoming.pauseAtEndOfMediaItems = false
        playerA = incoming
        playerB = outgoing
        // Only a player that is meant to play takes focus: requesting it for
        // a paused one would pause whatever app holds focus now, for nothing.
        if (incoming.playWhenReady) requestFocus()
        val onSwap = pendingSwap
        pendingSwap = null
        onSwap?.invoke(playerA)

        outgoing.pauseAtEndOfMediaItems = false
        outgoing.playWhenReady = false
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f
        transitioning = false
    }

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
    private fun transferQueue(from: ExoPlayer, to: ExoPlayer, incomingIndex: Int) {
        val count = from.mediaItemCount
        val idx = incomingIndex
        val history = (0 until idx).map { from.getMediaItemAt(it) }
        val future = ((idx + 1) until count).map { from.getMediaItemAt(it) }
        to.repeatMode = from.repeatMode
        to.shuffleModeEnabled = from.shuffleModeEnabled
        to.playbackParameters = from.playbackParameters
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
     * end (the promote at its run-out found its next song changed, a Play next
     * just before), pauseAtEndOfMediaItems is holding it there paused, and a plain
     * [cancelTransition] would leave silence: it plays on, into that new next
     * song, which nothing has played yet. A user pause or a focus loss
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
        transitionJob?.cancel()
        transitionJob = null
        pendingSwap = null
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

/**
 * Where [CrossfadeEngine.rebuildIdleOutputs] seeks a player standing at [positionMs]: 1 ms back, or
 * 1 ms forward from the start. Never the position itself, which ExoPlayer would skip as a no-op.
 */
internal fun rebuildSeekTarget(positionMs: Long): Long = if (positionMs >= 1L) positionMs - 1L else 1L
