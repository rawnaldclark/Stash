package com.stash.data.download

import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple exponential backoff utility for handling YouTube rate limits.
 *
 * Tracks consecutive failures and applies progressively longer delays:
 * - 1st rate limit: 30 seconds
 * - 2nd: 60 seconds
 * - 3rd: 120 seconds
 * - 4th+: 300 seconds (5 minutes)
 *
 * Non-rate-limit failures use a shorter 5-second cooldown.
 * Call [onSuccess] after a successful operation to reset the counter.
 */
@Singleton
class RateLimitHandler @Inject constructor() {
    private var consecutiveFailures = 0

    /** Resets the failure counter after a successful operation. */
    suspend fun onSuccess() {
        consecutiveFailures = 0
    }

    /** Suspends the coroutine for an exponentially increasing duration on rate limits. */
    suspend fun onRateLimited() {
        consecutiveFailures++
        val delayMs = when (consecutiveFailures) {
            1 -> 30_000L
            2 -> 60_000L
            3 -> 120_000L
            else -> 300_000L
        }
        delay(delayMs)
    }

    /** Suspends briefly after a non-rate-limit failure and increments the counter. */
    suspend fun onFailure() {
        consecutiveFailures++
        delay(5_000L)
    }
}
