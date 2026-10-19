package com.stash.core.data.lastfm

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Per-key circuit breaker for Last.fm rate-limit responses (error 29 / HTTP
 * 429). The app spreads read traffic across a pool of API keys; when one key
 * gets throttled, continuing to fire requests at it only prolongs the block,
 * so this breaker fails that key fast — skipping the network — for an
 * exponentially-growing cooldown, while the rest of the pool keeps serving.
 * Each key reopens automatically once its cooldown elapses; a successful
 * response on a key resets its backoff.
 *
 * Time is passed in (`nowMs`) rather than read from the clock so the logic is
 * deterministic and unit-testable.
 */
@Singleton
class LastFmRateLimitGate @Inject constructor() {

    private class KeyState {
        @Volatile var openUntilMs: Long = 0L
        @Volatile var consecutiveHits: Int = 0
    }

    private val states = ConcurrentHashMap<String, KeyState>()

    /** True while [key] is throttled — callers should skip it. */
    fun isOpen(key: String, nowMs: Long): Boolean =
        (states[key]?.openUntilMs ?: 0L) > nowMs

    /** When [key]'s breaker closes again (epoch ms); 0 or a past time when it isn't open. */
    fun openUntil(key: String): Long = states[key]?.openUntilMs ?: 0L

    /**
     * Record a rate-limit response for [key]. Opens that key for a cooldown
     * that doubles with each consecutive hit (base, 2×, 4×, …), capped at
     * [MAX_COOLDOWN_MS]. Other keys are unaffected.
     */
    @Synchronized
    fun recordRateLimited(key: String, nowMs: Long) {
        val state = states.getOrPut(key) { KeyState() }
        state.consecutiveHits++
        val cooldown = (BASE_COOLDOWN_MS.toDouble() * 2.0.pow(state.consecutiveHits - 1))
            .toLong()
            .coerceIn(BASE_COOLDOWN_MS, MAX_COOLDOWN_MS)
        state.openUntilMs = nowMs + cooldown
    }

    /** Record a successful response for [key]: closes it and resets backoff. */
    @Synchronized
    fun recordSuccess(key: String) {
        states[key]?.let {
            it.consecutiveHits = 0
            it.openUntilMs = 0L
        }
    }

    companion object {
        /** Cooldown after the first rate-limit hit on a key. */
        const val BASE_COOLDOWN_MS = 60_000L

        /** Upper bound on the exponential backoff. */
        const val MAX_COOLDOWN_MS = 60L * 60_000L
    }
}
