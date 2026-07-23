package com.stash.core.media

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
) {

    @Inject
    constructor(playerRepository: PlayerRepository) : this(
        playerRepository = playerRepository,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
        _state.value = State.Countdown(System.currentTimeMillis() + durationMs)
        job = scope.launch {
            val preFadeMs = (durationMs - fadeOutMs).coerceAtLeast(0)
            delay(preFadeMs)
            fadeOutAndPause(fadeOutMs.coerceAtMost(durationMs))
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
                val remaining = current.durationMs - positionMs
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
            return
        }
        val steps = 20
        val stepDelay = fadeMs / steps
        for (i in steps downTo 0) {
            playerRepository.setVolume(i / steps.toFloat())
            delay(stepDelay)
        }
        playerRepository.pause()
        playerRepository.setVolume(1f) // restore for next playback
    }

    companion object {
        private const val DEFAULT_FADE_MS = 7_000L
    }
}
