package com.stash.core.media.streaming

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sliding-window health monitor for the Kennyy proxy. The
 * The retired SquidCookieAutoRefresher consulted [isHealthy] to decide whether
 * to keep the Squid cookie warm in the background.
 *
 * "Failure" means a proxy-level distress signal (timeout, 5xx, 4xx).
 * "No match" — track legitimately absent from Qobuz catalog — does NOT
 * count as a failure since it's a per-track miss, not a proxy outage.
 *
 * State is transient (process-lifetime). After a restart we start
 * healthy and let the next ~5 resolves re-establish reality.
 */
@Singleton
class KennyyHealthMonitor @Inject constructor() {

    private enum class Outcome { Success, Failure }

    private val window = ArrayDeque<Outcome>(WINDOW_SIZE)
    private val _isHealthy = MutableStateFlow(true)
    val isHealthy: StateFlow<Boolean> = _isHealthy.asStateFlow()

    @Synchronized
    fun recordSuccess() = record(Outcome.Success)

    @Synchronized
    fun recordFailure() = record(Outcome.Failure)

    /** No match for the track in Qobuz catalog. Not a proxy distress signal. */
    fun recordNoMatch() { /* intentionally no-op */ }

    private fun record(outcome: Outcome) {
        window.addLast(outcome)
        if (window.size > WINDOW_SIZE) window.removeFirst()
        val failures = window.count { it == Outcome.Failure }
        _isHealthy.value = failures < FAIL_THRESHOLD
    }

    private companion object {
        const val WINDOW_SIZE = 5
        const val FAIL_THRESHOLD = 3
    }
}
