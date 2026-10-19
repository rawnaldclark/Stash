package com.stash.core.media

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide sleep timer (fork issue ParaliyzedEvo/Stash#26): counts down and
 * pauses playback, or pauses when the current track finishes. Singleton so
 * the countdown survives leaving Now Playing; it does NOT survive process
 * death (an armed timer dies with the app, which is fine — if the process is
 * gone, nothing is playing).
 *
 * Pause, not stop: the user wakes up and resumes exactly where sleep took
 * them.
 */
@Singleton
class SleepTimerController @VisibleForTesting internal constructor(
    private val playerRepository: PlayerRepository,
    private val scope: CoroutineScope,
    /**
     * Runs when the timer puts the music to sleep, after the pause. Ends an open Recap story
     * without giving its paused music back ([com.stash.core.media.recap.RecapAudio.end] with
     * `restoreMain = false`): during a story the main player is already paused, so the pause
     * alone did nothing, and closing the story later would have started the music again for
     * the rest of the night. It also stops the story's own song.
     */
    private val onSleep: suspend () -> Unit = {},
    /** Wall clock, the one [State.Countdown.endsAtMs] (and the UI's "about N min") is read on. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    @Inject
    constructor(
        playerRepository: PlayerRepository,
        recapAudio: dagger.Lazy<com.stash.core.media.recap.RecapAudio>,
    ) : this(
        playerRepository = playerRepository,
        onSleep = { recapAudio.get().end(restoreMain = false) },
        // Main, not Default: the fade ends in playerRepository.pause(), and a
        // MediaController may only be touched on its application (main)
        // thread — from a Default worker Media3 throws and the process dies
        // the moment the timer fires. The loops here are a delay and a
        // position collect: nothing that blocks the main thread.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    sealed interface State {
        data object Off : State
        data class Countdown(val endsAtMs: Long) : State
        data object EndOfTrack : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun startMinutes(minutes: Int, fadeOutMs: Long = DEFAULT_FADE_MS) {
        require(minutes > 0)
        job?.cancel()
        val durationMs = minutes * 60_000L
        val endsAtMs = nowMs() + durationMs
        _state.value = State.Countdown(endsAtMs)
        job = scope.launch {
            val fadeMs = fadeOutMs.coerceAtMost(durationMs)
            // Waits on the wall-clock deadline, re-read at least every
            // CLOCK_CHECK_MS, not one long delay(): delay() runs on the
            // monotonic clock, which stands still while a paused device
            // dozes, so the timer fired that much later than the "about N
            // min" the UI computes from the wall clock.
            while (true) {
                val untilFadeMs = endsAtMs - fadeMs - nowMs()
                if (untilFadeMs <= 0) break
                delay(untilFadeMs.coerceAtMost(CLOCK_CHECK_MS))
            }
            // Woken past the fade's start: fade over what is left of it.
            fadeOutAndPause(fadeMs.coerceAtMost((endsAtMs - nowMs()).coerceAtLeast(0)))
            _state.value = State.Off
        }
    }

    fun stopAtEndOfTrack(fadeOutMs: Long = DEFAULT_FADE_MS) {
        job?.cancel()
        _state.value = State.EndOfTrack
        job = scope.launch {
            val startTrackId = playerRepository.playerState.value.currentTrack?.id
            playerRepository.currentPosition.collect { positionMs ->
                val current = playerRepository.playerState.value
                if (current.currentTrack?.id != startTrackId) {
                    // user skipped away — disarm without pausing
                    _state.value = State.Off
                    this.coroutineContext.job.cancel()
                    return@collect
                }
                // The fade runs on the wall clock and the song at the chosen speed: at 2x the last
                // 7 s of it are over in 3.5 s, and a fade timed in song time lets the next one start.
                val remaining = mediaToWallMs(current.durationMs - positionMs, playerRepository.playbackSpeed.value)
                if (current.durationMs > 0 && remaining <= fadeOutMs) {
                    fadeOutAndPause(remaining.coerceAtLeast(0))
                    _state.value = State.Off
                    this.coroutineContext.job.cancel()
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        playerRepository.setVolume(1f) // in case we cancelled mid-fade
        _state.value = State.Off
    }

    private suspend fun fadeOutAndPause(fadeMs: Long) {
        if (fadeMs <= 0) {
            playerRepository.pause()
            onSleep()
            return
        }
        val steps = 20
        val stepDelay = fadeMs / steps
        try {
            // steps delays (i = steps..1), NOT steps+1: `downTo 0` ran one extra
            // delay and overshot fadeMs by a full step, so pause fired late.
            for (i in steps downTo 1) {
                playerRepository.setVolume(i / steps.toFloat())
                delay(stepDelay)
            }
            playerRepository.pause()
            onSleep()
        } finally {
            // Restore for next playback, and also when the fade is cut short:
            // re-arming mid-fade ("+15 min" or "end of track" once the music
            // audibly dips) cancels this job, and only cancel() used to put
            // the volume back, so playback went on at ~40% until a later
            // timer completed.
            playerRepository.setVolume(1f)
        }
    }

    companion object {
        private const val DEFAULT_FADE_MS = 7_000L

        /** Longest single wait before the countdown re-reads the wall clock. */
        private const val CLOCK_CHECK_MS = 60_000L
    }
}
