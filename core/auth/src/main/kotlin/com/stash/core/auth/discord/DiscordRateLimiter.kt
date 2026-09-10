package com.stash.core.auth.discord

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response

/**
 * Issue #15 fix (backoff half). Discord's headless-sessions endpoint 429s
 * under quick repeated hits. This is a single shared cooldown (not per-route
 * — headless-sessions is effectively the only hot path here) that every call
 * waits out before firing, fed by whatever rate-limit signal the previous
 * response carried.
 */
class DiscordRateLimiter {
    private val mutex = Mutex()

    @Volatile private var cooldownUntilMs: Long = 0L

    /** Call before every request. */
    suspend fun awaitReady() {
        val wait = cooldownUntilMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
    }

    /**
     * Call after every response. Handles a hard 429 (`Retry-After`, seconds —
     * can be fractional) AND the proactive `X-RateLimit-Remaining: 0` case, so
     * we back off before the NEXT call would 429 rather than always eating one
     * failure first.
     */
    suspend fun observe(response: Response) = mutex.withLock {
        val retryAfter = response.header("Retry-After")
        val remaining = response.header("X-RateLimit-Remaining")
        val resetAfter = response.header("X-RateLimit-Reset-After")

        val waitSeconds = when {
            response.code == 429 && retryAfter != null -> retryAfter.toDoubleOrNull()
            remaining == "0" && resetAfter != null -> resetAfter.toDoubleOrNull()
            else -> null
        } ?: return@withLock

        // Small safety margin — clock drift between us and Discord means
        // firing exactly at the edge risks re-tripping the same limit.
        val until = System.currentTimeMillis() + (waitSeconds * 1000).toLong() + 250L
        if (until > cooldownUntilMs) cooldownUntilMs = until
    }
}