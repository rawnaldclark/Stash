package com.stash.data.download.lossless

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AggregatorRateLimiter].
 *
 * Tests use a virtual clock backed by [kotlinx.coroutines.test.TestScope.currentTime]
 * so [delay] inside `acquire()` and the rate-limiter's wall-clock arithmetic
 * stay in lockstep — `advanceTimeBy(N)` advances both the test scheduler
 * (unblocking pending delays) and what the limiter sees as "now."
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AggregatorRateLimiterTest {

    @Test
    fun `first acquire succeeds with default burst capacity`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        assertTrue(limiter.acquire("src"))
    }

    @Test
    fun `burst capacity bounds immediate acquires before refill is needed`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(tokensPerSecond = 0.1, burstCapacity = 2.0),
        )
        assertTrue(limiter.acquire("src"))
        assertTrue(limiter.acquire("src"))

        // Bucket exhausted — confirm via state without invoking the wait loop.
        val state = limiter.stateOf("src")
        assertTrue("expected <1.0 tokens, got ${state.tokensAvailable}", state.tokensAvailable < 1.0)
        assertTrue("expected wait > 0, got ${state.msUntilNextToken}", state.msUntilNextToken > 0)
    }

    @Test
    fun `tokens replenish at configured rate`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(tokensPerSecond = 1.0, burstCapacity = 1.0),
        )
        assertTrue(limiter.acquire("src")) // consume the only token

        // 1 token/sec, advance 1 sec → 1 token back.
        advanceTimeBy(1_000)
        val state = limiter.stateOf("src")
        assertTrue("expected >=1.0 tokens, got ${state.tokensAvailable}", state.tokensAvailable >= 1.0)
    }

    @Test
    fun `acquire blocks until tokens refill, then succeeds`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(tokensPerSecond = 1.0, burstCapacity = 1.0),
        )
        assertTrue(limiter.acquire("src")) // consume

        // Kick off a second acquire; it should suspend until the next token.
        var got = false
        val job = launch { got = limiter.acquire("src") }

        // Advance just enough; the recursive acquire must succeed after the wait.
        advanceTimeBy(1_100)
        job.join()
        assertTrue(got)
    }

    @Test
    fun `429 blocks subsequent acquires for backoff duration`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(
                tokensPerSecond = 1.0,
                burstCapacity = 5.0,
                backoff429Ms = 60_000L,
                // Disable circuit breaker for this test by setting threshold high.
                circuitBreakAfter = 99,
            ),
        )
        assertTrue(limiter.acquire("src"))
        limiter.reportRateLimited("src")

        // Blocked — acquire short-circuits to false, doesn't wait.
        assertFalse(limiter.acquire("src"))

        // After backoff expires, acquire works again.
        advanceTimeBy(60_001)
        assertTrue(limiter.acquire("src"))
    }

    @Test
    fun `circuit breaker trips after N consecutive failures`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(
                tokensPerSecond = 10.0,
                burstCapacity = 10.0,
                circuitBreakAfter = 3,
                circuitBreakDurationMs = 60_000L,
            ),
        )
        limiter.reportFailure("src")
        limiter.reportFailure("src")
        // Two failures — still operational.
        assertTrue(limiter.acquire("src"))

        // Third failure trips the breaker.
        limiter.reportFailure("src")
        assertFalse(limiter.acquire("src"))

        // Recovers after configured duration.
        advanceTimeBy(60_001)
        assertTrue(limiter.acquire("src"))
    }

    @Test
    fun `reportSuccess resets the consecutive failure counter`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(
                tokensPerSecond = 10.0,
                burstCapacity = 10.0,
                circuitBreakAfter = 3,
            ),
        )
        limiter.reportFailure("src")
        limiter.reportFailure("src")
        limiter.reportSuccess("src")          // counter back to 0
        limiter.reportFailure("src")
        limiter.reportFailure("src")          // counter at 2 — still below threshold

        assertTrue(limiter.acquire("src"))
        assertEquals(2, limiter.stateOf("src").recentFailures)
    }

    @Test
    fun `stateOf reports unblock countdown for circuit-broken source`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(
                circuitBreakAfter = 1,
                circuitBreakDurationMs = 30_000L,
            ),
        )
        limiter.reportFailure("src")

        val state = limiter.stateOf("src")
        assertTrue(state.isCircuitBroken)
        assertTrue("countdown=${state.msUntilUnblock}", state.msUntilUnblock in 29_000..30_000)
    }

    @Test
    fun `a stated pause blocks without a tripped breaker, a trip is both, and both end with their block`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(
                backoff429Ms = 120_000L,
                circuitBreakAfter = 3,
                circuitBreakDurationMs = 15 * 60_000L,
            ),
        )

        // A busy relay's own Retry-After: blocked for exactly that long, no breaker.
        limiter.reportRateLimited("src", 30_000L)
        limiter.stateOf("src").let {
            assertTrue(it.isCircuitBroken)
            assertFalse(it.breakerTripped)
            assertEquals(30_000L, it.msUntilUnblock)
        }
        advanceTimeBy(30_000L)
        assertFalse(limiter.stateOf("src").isCircuitBroken)

        // Failures in a row (the 429 above counted as one): the breaker trips.
        repeat(2) { limiter.reportFailure("src") }
        limiter.stateOf("src").let {
            assertTrue(it.isCircuitBroken)
            assertTrue(it.breakerTripped)
            assertEquals(15 * 60_000L, it.msUntilUnblock)
        }
        advanceTimeBy(15 * 60_000L)
        limiter.stateOf("src").let {
            assertFalse(it.isCircuitBroken)
            assertFalse(it.breakerTripped)
        }
    }

    @Test
    fun `429 also bumps failure counter for sustained-rate-limit case`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(
                tokensPerSecond = 10.0,
                burstCapacity = 10.0,
                backoff429Ms = 1_000L,
                circuitBreakAfter = 3,
                circuitBreakDurationMs = 60_000L,
            ),
        )
        // Three consecutive 429s — each is a failure, third one trips circuit
        // breaker beyond the per-429 backoff. We test by waiting out the
        // 429 backoff between calls so the only thing keeping us out is the
        // circuit breaker.
        limiter.reportRateLimited("src"); advanceTimeBy(1_500)
        limiter.reportRateLimited("src"); advanceTimeBy(1_500)
        limiter.reportRateLimited("src"); advanceTimeBy(1_500)

        // Circuit breaker should be holding us out beyond the 1.5s wait.
        assertFalse(limiter.acquire("src"))
    }

    @Test
    fun `429s never trip the breaker when rateLimitTripsBreaker is false`() = runTest {
        // For a source whose 429 is an expected concurrency reply (a job is
        // already running) rather than a health signal, 429s must apply the
        // short backoff but never trip the breaker, however many collide.
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "concurrency_capped",
            AggregatorRateLimiter.Config(
                tokensPerSecond = 10.0,
                burstCapacity = 10.0,
                backoff429Ms = 1_000L,
                circuitBreakAfter = 3,
                circuitBreakDurationMs = 60_000L,
                rateLimitTripsBreaker = false,
            ),
        )
        // Far more than circuitBreakAfter consecutive 429s, each past its backoff.
        repeat(8) {
            limiter.reportRateLimited("concurrency_capped")
            advanceTimeBy(1_500)
        }

        // Breaker never tripped — once the last 429 backoff elapses we're usable.
        assertFalse(limiter.stateOf("concurrency_capped").isCircuitBroken)
        assertTrue(limiter.acquire("concurrency_capped"))
    }

    @Test
    fun `independent sources have independent state`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "a",
            AggregatorRateLimiter.Config(circuitBreakAfter = 1, circuitBreakDurationMs = 60_000L),
        )
        limiter.reportFailure("a")
        assertFalse(limiter.acquire("a"))     // a is broken
        assertTrue(limiter.acquire("b"))      // b is fine
    }

    @Test
    fun `manual reset emits source id on circuitResetEvents`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        val emissions = mutableListOf<String>()
        val job = launch { limiter.circuitResetEvents.toList(emissions) }
        advanceUntilIdle() // ensure collector is subscribed before we emit
        limiter.reset("squid_qobuz")
        advanceUntilIdle()
        assertEquals(listOf("squid_qobuz"), emissions)
        job.cancel()
    }

    @Test
    fun `breaker timeout-reset emits source id`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "kennyy_qobuz",
            AggregatorRateLimiter.Config(
                tokensPerSecond = 10.0,
                burstCapacity = 10.0,
                circuitBreakAfter = 3,
                circuitBreakDurationMs = 30 * 60_000L,
            ),
        )
        // Trip the breaker
        repeat(3) { limiter.reportFailure("kennyy_qobuz") }
        // Confirm it's tripped
        assertTrue(limiter.stateOf("kennyy_qobuz").isCircuitBroken)

        val emissions = mutableListOf<String>()
        val job = launch { limiter.circuitResetEvents.toList(emissions) }
        advanceUntilIdle()

        // Wait past the breaker timeout
        advanceTimeBy(30 * 60_000L + 1)
        // The next stateOf() observation crosses the transition (true → false)
        // and emits.
        assertFalse(limiter.stateOf("kennyy_qobuz").isCircuitBroken)
        advanceUntilIdle()
        assertTrue("expected kennyy_qobuz in $emissions", emissions.contains("kennyy_qobuz"))
        job.cancel()
    }

    @Test
    fun `a 429 after the breaker trips does not shorten its block or fire an early reset`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(
                tokensPerSecond = 10.0,
                burstCapacity = 10.0,
                backoff429Ms = 60_000L,
                circuitBreakAfter = 3,
                circuitBreakDurationMs = 10 * 60_000L,
                rateLimitTripsBreaker = false,
            ),
        )
        repeat(3) { limiter.reportFailure("src") }
        val emissions = mutableListOf<String>()
        val job = launch { limiter.circuitResetEvents.toList(emissions) }
        advanceUntilIdle()

        // A request that was already in flight comes back 429.
        limiter.reportRateLimited("src")

        // Past the 429 backoff, well inside the breaker's block.
        advanceTimeBy(60_001)
        assertTrue(limiter.stateOf("src").isCircuitBroken)
        assertFalse(limiter.acquire("src"))
        advanceUntilIdle()
        assertEquals(emptyList<String>(), emissions)
        job.cancel()
    }

    @Test
    fun `breaker timeout-reset emits only once across repeated stateOf observations`() = runTest {
        val limiter = AggregatorRateLimiter().apply { clock = virtualClock() }
        limiter.configure(
            "src",
            AggregatorRateLimiter.Config(
                tokensPerSecond = 10.0,
                burstCapacity = 10.0,
                circuitBreakAfter = 3,
                circuitBreakDurationMs = 60_000L,
            ),
        )
        repeat(3) { limiter.reportFailure("src") }
        val emissions = mutableListOf<String>()
        val job = launch { limiter.circuitResetEvents.toList(emissions) }
        advanceUntilIdle()
        advanceTimeBy(60_001)
        // Multiple observations after the timeout — only the first should emit.
        limiter.stateOf("src")
        limiter.stateOf("src")
        limiter.stateOf("src")
        advanceUntilIdle()
        assertEquals(listOf("src"), emissions)
        job.cancel()
    }

    private fun kotlinx.coroutines.test.TestScope.virtualClock(): AggregatorRateLimiter.Clock {
        // Read currentTime through the explicit `testScheduler` member so
        // we don't depend on the (extension-property) shortcut import,
        // which is fragile across kotlinx-coroutines-test versions.
        val scheduler = this.testScheduler
        return object : AggregatorRateLimiter.Clock {
            override fun nowMs(): Long = scheduler.currentTime
        }
    }
}
