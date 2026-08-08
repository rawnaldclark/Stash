package com.stash.data.download.lossless

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-source token bucket with exponential backoff and circuit breaker.
 *
 * One shared instance gates every aggregator-bound network call across
 * Stash. This is the dam that stops Stash from accidentally DDoSing
 * community-run services that are themselves running on a single
 * paid Tidal/Qobuz account. The economics: a typical aggregator operator
 * pays ~$10-20/mo for one premium account and serves it to thousands of
 * users via a web frontend. A burst of automated traffic from a third-
 * party app trips the upstream provider's abuse heuristics, the operator's
 * account gets suspended, the service goes down for everyone — and Stash
 * is the proximate cause.
 *
 * Defaults are deliberately conservative: 1 token / 8 seconds per source
 * (~7 requests/minute), burst capacity 3. Tuneable per-source via
 * [configure] for sources that publish higher official rate budgets.
 *
 * Thread-safe via [Mutex].
 */
@Singleton
class AggregatorRateLimiter @Inject constructor() {

    /**
     * Test seam: tests overwrite this with a virtual clock before any
     * [acquire]/[reportSuccess] calls. Production paths leave it on
     * [SystemClock]. Kept off the constructor signature because mixing
     * `@Inject` with a defaulted parameter generates two JVM
     * constructors and Hilt rejects multiple injectable constructors.
     */
    internal var clock: Clock = SystemClock

    /**
     * Per-source mutable bucket state. [tokens] is fractional so refill
     * is smooth across short intervals; [blockedUntil] is the absolute
     * timestamp before which acquire returns false.
     */
    private data class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
        var consecutiveFailures: Int = 0,
        var blockedUntilMs: Long = 0L,
        var totalAcquires: Long = 0L,
        var totalRateLimited: Long = 0L,
        /** Timestamps (ms) of recent 429s. Trimmed to last 60s in reportRateLimited. */
        val rateLimitTimestamps: MutableList<Long> = mutableListOf(),
        /**
         * Latch used to detect the circuit-broken → not-broken transition for
         * [circuitResetEvents]. The actual breaker state is purely temporal
         * (`now < blockedUntilMs`), so without a separate latch we'd either
         * emit on every state read after the timeout or not at all. Set to
         * true whenever the breaker trips, cleared (and the event emitted)
         * the first time a state-reading call observes the timeout has passed.
         */
        var wasCircuitBroken: Boolean = false,
    )

    private val buckets = mutableMapOf<String, Bucket>()
    private val configs = mutableMapOf<String, Config>()
    private val mutex = Mutex()

    private val defaultConfig = Config()

    private val _circuitResetEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /**
     * Read-only stream of source-id emissions when a circuit breaker
     * resets — either by [reset] or by the timeout-driven self-reset
     * observed inside [stateOf] / [acquire]. v0.9.17+ consumers
     * (LosslessRetryScheduler) use this to know when to re-attempt
     * deferred tracks for sources the user can't manually un-stick
     * (kennyy server outages that clear organically).
     *
     * Buffer of 8 with no replay: at most a small handful of resets
     * happen in quick succession (manual reset + timeout-driven reset
     * across 2 sources). Late subscribers don't see past resets;
     * the scheduler subscribes once at app startup and is always live.
     */
    val circuitResetEvents: SharedFlow<String> = _circuitResetEvents.asSharedFlow()

    init {
        // Kennyy operator handles traffic from Monochrome's web UI users at
        // higher rates than the conservative default. Start at 3x the default
        // (1 token / 3s, burst 4); auto-backoff via reportRateLimited will
        // ratchet down if the operator's actual threshold is lower.
        configs["kennyy_qobuz"] = Config(
            tokensPerSecond = 1.0 / 3.0,   // 1 token / 3 seconds
            burstCapacity = 4.0,
            backoff429Ms = 60_000L,        // 1 min pause on 429
            circuitBreakAfter = 5,
            circuitBreakDurationMs = 30 * 60_000L, // 30 min
        )

        // ARCOD (Qobuz-DL proxy via arcod.xyz) runs one operator-paid Qobuz
        // account behind a Supabase-gated job queue, with a per-account ~50
        // downloads/hour cap. Stay deliberately conservative: 1 token / 2s,
        // burst 2. CRITICAL: ARCOD's 429 = "you hit the hourly cap, slow down"
        // — that's NORMAL operation against a quota, NOT a health failure. So
        // `rateLimitTripsBreaker = false`: a 429 applies the backoff but must
        // NOT trip the breaker. (Tripping it disabled ARCOD for 10 min on a
        // real sync, so every track fell through to yt-dlp — verified on-device
        // 2026-06-16.) Only genuine failures (5xx/network via reportFailure)
        // trip the breaker.
        configs["arcod"] = Config(
            tokensPerSecond = 1.0 / 2.0,   // 1 token / 2 seconds
            burstCapacity = 2.0,
            backoff429Ms = 60_000L,        // 1 min pause on 429 (cap hit)
            circuitBreakAfter = 5,
            circuitBreakDurationMs = 10 * 60_000L, // 10 min
            rateLimitTripsBreaker = false,
        )

        // amz.squid.wtf (Amazon Music proxy) — structurally one paid account
        // serving everyone, same DDoS-risk economics as the Qobuz proxies, so
        // deliberately conservative: ~1 request / 2s, tiny burst. A 429 means
        // real over-rate (it counts toward the breaker, default
        // rateLimitTripsBreaker=true), so 5 failures cool the source for 5 min.
        configs["amz"] = Config(
            tokensPerSecond = 1.0 / 2.0,   // ~1 request / 2s
            burstCapacity = 2.0,
            backoff429Ms = 10_000L,        // 10s pause on 429
            circuitBreakAfter = 5,
            circuitBreakDurationMs = 5 * 60_000L, // 5 min
        )

        // qbdlx (direct www.qobuz.com on shared real accounts + rotating token
        // pool). Slowest of the lossless sources: it hits Qobuz directly with
        // signed requests on accounts whose abuse heuristics we must not trip,
        // so 1 token / 3s, burst 2. CRITICAL: a Qobuz 429 = "slow down", NOT a
        // health failure, so `rateLimitTripsBreaker = false` (a 429 applies the
        // 1-min backoff but must not trip the breaker). Genuine failures
        // (5xx/network via reportFailure) still cool the source for 10 min.
        configs["qbdlx_qobuz"] = Config(
            tokensPerSecond = 1.0 / 3.0,   // slow — direct-to-Qobuz on shared real accounts
            burstCapacity = 2.0,
            backoff429Ms = 60_000L,
            circuitBreakAfter = 5,
            circuitBreakDurationMs = 10 * 60_000L,
            rateLimitTripsBreaker = false, // a Qobuz 429 = "slow down", not "broken"
        )

        // JioSaavn is an opportunistic lossy fallback. A small burst keeps a
        // foreground tap responsive, while the steady rate prevents an album
        // sync from hammering the unofficial web endpoint. Any 429 or repeated
        // transport/media-probe failures should cool it quickly so YouTube can
        // take over without adding latency to every track.
        configs["jiosaavn"] = Config(
            tokensPerSecond = 1.0 / 2.0,
            burstCapacity = 3.0,
            backoff429Ms = 60_000L,
            circuitBreakAfter = 3,
            circuitBreakDurationMs = 10 * 60_000L,
        )
    }

    companion object {
        private const val TAG = "AggregatorRateLimiter"
    }

    /**
     * Override defaults for a specific source. Call once at app startup
     * (e.g. from the [LosslessSource] implementation's init or a Hilt
     * module) for sources where the operator publishes higher rate
     * budgets, OR where empirical breakage suggests slower defaults.
     */
    suspend fun configure(sourceId: String, config: Config) {
        mutex.withLock { configs[sourceId] = config }
    }

    /**
     * Block until a token is available for [sourceId], OR return false
     * immediately if the source is currently circuit-broken.
     *
     * Caller pattern:
     * ```
     * if (!rateLimiter.acquire(id)) return null  // circuit-broken, skip
     * try { val response = http.get(url); rateLimiter.reportSuccess(id) }
     * catch (e: Http429) { rateLimiter.reportRateLimited(id); throw e }
     * catch (e: Throwable) { rateLimiter.reportFailure(id); throw e }
     * ```
     *
     * Caller MUST report the outcome — [reportSuccess], [reportRateLimited],
     * or [reportFailure] — or the failure counters won't progress and
     * the circuit breaker won't trip when it should.
     */
    suspend fun acquire(sourceId: String): Boolean {
        // Wait outside the mutex so other sources aren't blocked.
        var waitMs = 0L
        var resetEventId: String? = null
        var earlyResult: Boolean? = null
        mutex.withLock {
            val bucket = bucketFor(sourceId)
            val cfg = configFor(sourceId)
            val now = clock.nowMs()

            // Circuit-broken? Bail without consuming a token.
            if (now < bucket.blockedUntilMs) {
                earlyResult = false
            } else {
                // Detect the breaker timeout-reset transition the same way
                // [stateOf] does. acquire() is the hot path during retries,
                // so consumers expect the reset event whether they observed
                // it via stateOf() or via a successful acquire.
                resetEventId = consumeCircuitResetIfDue(sourceId, bucket, now)

                // Refill since last visit.
                refill(bucket, cfg, now)

                if (bucket.tokens >= 1.0) {
                    bucket.tokens -= 1.0
                    bucket.totalAcquires++
                    earlyResult = true
                } else {
                    // Not enough tokens — compute wait, then loop after sleeping.
                    waitMs = msToNextToken(bucket, cfg)
                }
            }
        }
        resetEventId?.let { _circuitResetEvents.tryEmit(it) }
        earlyResult?.let { return it }

        if (waitMs > 0) delay(waitMs)
        // Re-acquire (same source, fresh state). Recursion is fine here —
        // depth is bounded because tokens accumulate during the delay.
        return acquire(sourceId)
    }

    /** Record a successful response. Resets the failure counter. */
    suspend fun reportSuccess(sourceId: String) {
        mutex.withLock { bucketFor(sourceId).consecutiveFailures = 0 }
    }

    /**
     * Manually clear circuit-breaker + failure state for [sourceId].
     * Used by the Settings UI's "Reset lossless attempts" action when
     * the breaker tripped on a transient outage and the user knows
     * the source is healthy again — skips the 30-min organic timeout.
     *
     * Bucket tokens are also fully refilled so the next call doesn't
     * have to wait on the steady-state refill rate.
     */
    suspend fun reset(sourceId: String) {
        mutex.withLock {
            val bucket = bucketFor(sourceId)
            val cfg = configFor(sourceId)
            bucket.blockedUntilMs = 0L
            bucket.consecutiveFailures = 0
            bucket.tokens = cfg.burstCapacity
            bucket.lastRefillMs = clock.nowMs()
            // Clear the latch so the timeout-driven path doesn't double-emit
            // for the same logical reset event.
            bucket.wasCircuitBroken = false
        }
        // Emit AFTER the state change so subscribers reading stateOf(sourceId)
        // immediately after the emission observe isCircuitBroken = false.
        _circuitResetEvents.tryEmit(sourceId)
    }

    /**
     * Record a 429 / Too Many Requests response. Pauses the source for
     * the configured backoff period — caller-side logic should not retry
     * before then, and [acquire] will return false for that interval.
     */
    suspend fun reportRateLimited(sourceId: String) {
        mutex.withLock {
            val bucket = bucketFor(sourceId)
            val cfg = configFor(sourceId)
            val now = clock.nowMs()
            bucket.blockedUntilMs = now + cfg.backoff429Ms
            bucket.totalRateLimited++

            // Auto-backoff: track 429 timestamps in a rolling 60-second
            // window. If we cross the threshold (5+ in 60s), halve the
            // source's effective rate as a self-tuning measure. The system
            // converges toward the operator's actual threshold rather than
            // hardcoding our guess.
            bucket.rateLimitTimestamps.add(now)
            bucket.rateLimitTimestamps.removeAll { it < now - 60_000L }
            if (bucket.rateLimitTimestamps.size >= 5) {
                val newRate = (cfg.tokensPerSecond / 2.0).coerceAtLeast(1.0 / 60.0) // floor at 1/min
                if (newRate < cfg.tokensPerSecond) {
                    configs[sourceId] = cfg.copy(tokensPerSecond = newRate)
                    Log.i(TAG, "AggregatorRateLimiter: $sourceId 429-rate-limited 5+ times in 60s; halving rate to $newRate tokens/sec")
                    bucket.rateLimitTimestamps.clear()  // reset window after action
                }
            }

            // 429 also counts as a failure for circuit-breaker purposes —
            // if we keep getting rate-limited, something's structurally
            // wrong (wrong API endpoint, banned IP, etc.) and we should
            // fall back harder than just the backoff. EXCEPT for sources
            // whose 429 is an expected concurrency reply (antra's "job
            // already running", absorbed by AntraJobGate) — there a 429 is
            // not a health signal, so it must never trip the breaker.
            if (cfg.rateLimitTripsBreaker) {
                bucket.consecutiveFailures++
                maybeTripCircuitBreaker(bucket, cfg)
            }
        }
    }

    /**
     * Record any non-429 failure (timeout, connection refused, 5xx,
     * unparseable response). Increments the consecutive-failure counter
     * and trips the circuit breaker if it crosses the threshold.
     */
    suspend fun reportFailure(sourceId: String) {
        mutex.withLock {
            val bucket = bucketFor(sourceId)
            val cfg = configFor(sourceId)
            bucket.consecutiveFailures++
            maybeTripCircuitBreaker(bucket, cfg)
        }
    }

    /** Inspect current state. Used by the Settings UI for diagnostics. */
    suspend fun stateOf(sourceId: String): RateLimitState {
        var resetEventId: String? = null
        val state = mutex.withLock {
            val bucket = bucketFor(sourceId)
            val cfg = configFor(sourceId)
            val now = clock.nowMs()
            refill(bucket, cfg, now)
            resetEventId = consumeCircuitResetIfDue(sourceId, bucket, now)
            RateLimitState(
                tokensAvailable = bucket.tokens,
                msUntilNextToken = if (bucket.tokens >= 1.0) 0L else msToNextToken(bucket, cfg),
                isCircuitBroken = now < bucket.blockedUntilMs,
                msUntilUnblock = (bucket.blockedUntilMs - now).coerceAtLeast(0L),
                recentFailures = bucket.consecutiveFailures,
            )
        }
        resetEventId?.let { _circuitResetEvents.tryEmit(it) }
        return state
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun bucketFor(sourceId: String): Bucket =
        buckets.getOrPut(sourceId) {
            Bucket(
                tokens = configFor(sourceId).burstCapacity,
                lastRefillMs = clock.nowMs(),
            )
        }

    private fun configFor(sourceId: String): Config =
        configs[sourceId] ?: defaultConfig

    private fun refill(bucket: Bucket, cfg: Config, now: Long) {
        val elapsedSec = (now - bucket.lastRefillMs) / 1000.0
        if (elapsedSec <= 0) return
        bucket.tokens = (bucket.tokens + elapsedSec * cfg.tokensPerSecond)
            .coerceAtMost(cfg.burstCapacity)
        bucket.lastRefillMs = now
    }

    private fun msToNextToken(bucket: Bucket, cfg: Config): Long {
        val needed = (1.0 - bucket.tokens).coerceAtLeast(0.0)
        return (needed / cfg.tokensPerSecond * 1000.0).toLong().coerceAtLeast(1L)
    }

    private fun maybeTripCircuitBreaker(bucket: Bucket, cfg: Config) {
        if (bucket.consecutiveFailures >= cfg.circuitBreakAfter) {
            bucket.blockedUntilMs = clock.nowMs() + cfg.circuitBreakDurationMs
            // Latch the breaker-tripped state so the timeout-driven path in
            // [stateOf] / [acquire] can detect the eventual transition back.
            bucket.wasCircuitBroken = true
            // Reset counter so we don't re-trip immediately on the next
            // failure if the source is still down. Counter resets organically
            // on first success; on continued failure, we'll re-cross the
            // threshold and re-trip after another N failures.
            bucket.consecutiveFailures = 0
        }
    }

    /**
     * Detect the circuit-broken → not-broken transition and return the
     * source id if this call observed it. Caller must invoke under
     * [mutex] and emit on the returned id AFTER releasing the lock so
     * the SharedFlow buffer drains promptly without holding the mutex.
     *
     * Atomic-CAS pattern: the latch is cleared under the mutex, so under
     * concurrent state-reading calls only one observer sees the transition.
     */
    private fun consumeCircuitResetIfDue(sourceId: String, bucket: Bucket, now: Long): String? {
        if (bucket.wasCircuitBroken && now >= bucket.blockedUntilMs) {
            bucket.wasCircuitBroken = false
            return sourceId
        }
        return null
    }

    /**
     * Per-source rate-limit configuration. All durations in ms.
     *
     * @property tokensPerSecond Steady-state rate. 0.125 = 1 / 8s = ~7/min.
     * @property burstCapacity   Initial bucket size; allows short bursts.
     * @property backoff429Ms    Pause-the-source duration on a 429 reply.
     * @property circuitBreakAfter Consecutive failures before extended block.
     * @property circuitBreakDurationMs Length of the extended block.
     * @property rateLimitTripsBreaker Whether a 429 counts toward the
     *   circuit breaker. True (default) for the Qobuz proxies, where a 429
     *   means real over-rate. False for sources whose 429 is a structural
     *   concurrency reply (antra: "a job is already running") that the job
     *   gate already absorbs — there a 429 applies only the short backoff and
     *   must never trip the breaker, however many collide.
     */
    data class Config(
        val tokensPerSecond: Double = 0.125,
        val burstCapacity: Double = 3.0,
        val backoff429Ms: Long = 5 * 60_000L,
        val circuitBreakAfter: Int = 3,
        val circuitBreakDurationMs: Long = 30 * 60_000L,
        val rateLimitTripsBreaker: Boolean = true,
    )

    /** Indirection so tests can inject a virtual clock. */
    interface Clock {
        fun nowMs(): Long
    }

    object SystemClock : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
}
