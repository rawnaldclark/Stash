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

        /** Counting down to [endsAtMs] (epoch millis). */
        data class Countdown(val endsAtMs: Long) : State

        /** Pausing when the currently-playing track changes or ends. */
        data object EndOfTrack : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** Arm (or re-arm) a countdown of [minutes]; replaces any running timer. */
    fun startMinutes(minutes: Int) {
        require(minutes > 0)
        job?.cancel()
        val durationMs = minutes * 60_000L
        _state.value = State.Countdown(System.currentTimeMillis() + durationMs)
        job = scope.launch {
            delay(durationMs)
            playerRepository.pause()
            _state.value = State.Off
        }
    }

    /** Pause when the current track transitions (ends or is skipped). */
    fun stopAtEndOfTrack() {
        job?.cancel()
        _state.value = State.EndOfTrack
        job = scope.launch {
            playerRepository.playerState
                .map { it.currentTrack?.id }
                .distinctUntilChanged()
                .drop(1) // the value at arm time — wait for the NEXT transition
                .first()
            playerRepository.pause()
            _state.value = State.Off
        }
    }

    /** Disarm without touching playback. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = State.Off
    }
}
