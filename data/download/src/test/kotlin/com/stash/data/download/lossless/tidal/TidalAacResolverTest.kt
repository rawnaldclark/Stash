package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.bitchord.BitchordClient
import com.stash.data.download.lossless.bitchord.BitchordStream
import com.stash.data.download.lossless.bitchord.TidalLookup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TidalAacResolverTest {

    private val client: BitchordClient = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private val prefs: LosslessSourcePreferences = mockk()
    private val resolver = TidalAacResolver(client, rateLimiter, prefs)
    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky")
    private val id = TidalAacResolver.SOURCE_ID

    private val aac = BitchordStream(
        url = "https://sp-pr-cf.audio.tidal.com/x.mp4",
        isDash = false,
        bitDepth = 0,
        sampleRateHz = 44_100,
        bitrateKbps = 320,
        coverArtUrl = "https://img/cover.jpg",
        expiresAtMs = 0L,
        confidence = 0.95f,
        trackId = "td:7",
        codec = "aac",
    )

    private fun ready(switchOn: Boolean = true, acquire: Boolean = true) {
        coEvery { prefs.tidalAacFallbackNow() } returns switchOn
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.stateOf(any()) } returns RateLimitState(2.0, 0L, false, 0L, 0)
        coEvery { rateLimiter.acquire(id) } returns acquire
        coEvery { client.lookup(any(), any(), true) } returns TidalLookup.NeedsRelay
    }

    /** A blocked Tidal bucket: [blockedMs] long, a tripped breaker's block or only a pause. */
    private fun blockedState(blockedMs: Long, tripped: Boolean) = RateLimitState(
        tokensAvailable = 0.0,
        msUntilNextToken = 0L,
        isCircuitBroken = true,
        msUntilUnblock = blockedMs,
        recentFailures = 0,
        breakerTripped = tripped,
    )

    /** A real limiter (the Tidal buckets' own numbers: 120 s 429 backoff, 15 min breaker) on [now]. */
    private fun realLimiter(now: () -> Long) = AggregatorRateLimiter().apply {
        clock = object : AggregatorRateLimiter.Clock {
            override fun nowMs(): Long = now()
        }
    }

    /** Every time [resolver] announces on retryEvents, for the rest of the test. */
    private fun kotlinx.coroutines.test.TestScope.retryEventsOf(resolver: TidalAacResolver): List<Long> {
        val events = mutableListOf<Long>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            resolver.retryEvents.collect { events += it }
        }
        return events
    }

    @Test fun `AAC answer maps to an aac m4a SourceResult at 320 kbps`() = runTest {
        ready()
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } returns TidalLookup.Found(aac)
        val r = (resolver.resolve(query) as TidalAac.Found).source
        assertThat(r.sourceId).isEqualTo(id)
        assertThat(r.format.codec).isEqualTo("aac")
        assertThat(r.format.fileExtension).isEqualTo("m4a")
        assertThat(r.format.bitrateKbps).isEqualTo(320)
        assertThat(r.format.bitsPerSample).isEqualTo(0)
        assertThat(r.isDash).isFalse()
        assertThat(r.downloadUrl).isEqualTo(aac.url)
        coVerify(exactly = 1) { client.lookup(any(), any(), false) }
        coVerify { rateLimiter.reportSuccess(id) }
    }

    @Test fun `a FLAC answer is no AAC verdict - the lossless chain owns FLAC, the download waits`() = runTest {
        ready()
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } returns TidalLookup.Found(aac.copy(codec = "flac", isDash = true, bitDepth = 24))
        assertThat((resolver.resolve(query) as TidalAac.Unavailable).reason).isEqualTo(TidalAac.Reason.FAILED)
    }

    @Test fun `not configured means no lookup`() = runTest {
        ready()
        coEvery { client.isConfigured() } returns false
        assertThat(resolver.resolve(query)).isEqualTo(TidalAac.NoAac)
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `rate limited means no lookup, and a wait rather than a miss`() = runTest {
        ready(acquire = false)
        assertThat((resolver.resolve(query) as TidalAac.Unavailable).reason).isEqualTo(TidalAac.Reason.RATE_LIMITED)
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `a tap (foreground) skips this device's pacing`() = runTest {
        ready(acquire = false)
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } returns TidalLookup.Found(aac)
        assertThat(resolver.resolve(query, foreground = true)).isInstanceOf(TidalAac.Found::class.java)
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
    }

    @Test fun `a tripped breaker means no lookup, and a long wait that still ends with its block`() = runTest {
        ready()
        resolver.nowMs = { 1_000L }
        val events = retryEventsOf(resolver)
        coEvery { rateLimiter.stateOf(id) } returns blockedState(15 * 60_000L, tripped = true)

        assertThat(resolver.resolve(query))
            .isEqualTo(TidalAac.Unavailable(TidalAac.Reason.FAILED, 1_000L + TidalAacResolver.FAILED_RETRY_MS))

        // Two minutes left on it: looked at again when it ends, not five minutes on.
        coEvery { rateLimiter.stateOf(id) } returns blockedState(2 * 60_000L, tripped = true)
        assertThat(resolver.resolve(query))
            .isEqualTo(TidalAac.Unavailable(TidalAac.Reason.FAILED, 1_000L + 2 * 60_000L))

        assertThat(events).containsExactly(1_000L + TidalAacResolver.FAILED_RETRY_MS, 1_000L + 2 * 60_000L).inOrder()
        coVerify(exactly = 0) { client.lookup(any(), any(), any(), any()) }
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
    }

    /** The phone's log at 01:06: the lossless rung's 29 s pause read as an open breaker, FAILED + 5 min. */
    @Test fun `a bucket only paused for a busy relay is BUSY until the pause ends, announced for the sweep`() = runTest {
        ready()
        resolver.nowMs = { 1_000L }
        val events = retryEventsOf(resolver)
        coEvery { rateLimiter.stateOf(TidalLosslessSource.SOURCE_ID) } returns blockedState(29_000L, tripped = false)

        assertThat(resolver.resolve(query)).isEqualTo(TidalAac.Unavailable(TidalAac.Reason.BUSY, 30_000L))

        assertThat(events).containsExactly(30_000L)
        coVerify(exactly = 0) { client.lookup(any(), any(), any(), any()) }
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
    }

    @Test fun `the later of the two buckets' pauses decides, and a short one still waits the floor`() = runTest {
        ready()
        resolver.nowMs = { 1_000L }
        coEvery { rateLimiter.stateOf(id) } returns blockedState(20_000L, tripped = false)
        coEvery { rateLimiter.stateOf(TidalLosslessSource.SOURCE_ID) } returns blockedState(45_000L, tripped = false)
        assertThat(resolver.resolve(query)).isEqualTo(TidalAac.Unavailable(TidalAac.Reason.BUSY, 46_000L))

        // Never sooner than MIN_RETRY_MS, so a waiting queue is not swept in a tight loop.
        coEvery { rateLimiter.stateOf(id) } returns blockedState(2_000L, tripped = false)
        coEvery { rateLimiter.stateOf(TidalLosslessSource.SOURCE_ID) } returns blockedState(5_000L, tripped = false)
        assertThat(resolver.resolve(query))
            .isEqualTo(TidalAac.Unavailable(TidalAac.Reason.BUSY, 1_000L + TidalAacResolver.MIN_RETRY_MS))
    }

    /**
     * The phone's stall end to end on a real limiter: the lossless rung's lookup comes back busy for
     * 30 s, and the AAC step of the same download waits BUSY for those 30 s (it was FAILED + 5 min,
     * which booked the next sweep five minutes out). Once the pause is over, Tidal is asked again.
     */
    @Test fun `after a 30 s busy the download waits BUSY for 30 s, not FAILED for 5 minutes`() = runTest {
        var now = 1_000_000L
        val limiter = realLimiter { now }
        val lossless = TidalLosslessSource(client, limiter) { now }
        val aacStep = TidalAacResolver(client, limiter, prefs).apply { nowMs = { now } }
        val events = retryEventsOf(aacStep)
        coEvery { prefs.tidalAacFallbackNow() } returns true
        coEvery { client.isConfigured() } returns true
        coEvery { client.lookup(any(), any(), true, any()) } returns TidalLookup.NeedsRelay
        coEvery { client.lookup(query, false, false, false) } returns TidalLookup.Busy(now + 30_000L)

        assertThat(lossless.resolve(query)).isNull()
        assertThat(aacStep.resolve(query)).isEqualTo(TidalAac.Unavailable(TidalAac.Reason.BUSY, 1_030_000L))

        assertThat(events).containsExactly(1_030_000L)
        coVerify(exactly = 0) { client.lookup(any(), true, false, any()) }

        now += 30_000L
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } returns TidalLookup.Found(aac)
        assertThat(aacStep.resolve(query)).isInstanceOf(TidalAac.Found::class.java)
    }

    @Test fun `the AAC lookup's own busy pauses Tidal AAC for the relay's wait, and the next song waits with it`() = runTest {
        var now = 1_000_000L
        val limiter = realLimiter { now }
        val aacStep = TidalAacResolver(client, limiter, prefs).apply { nowMs = { now } }
        coEvery { prefs.tidalAacFallbackNow() } returns true
        coEvery { client.isConfigured() } returns true
        coEvery { client.lookup(any(), any(), true, any()) } returns TidalLookup.NeedsRelay
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } returns TidalLookup.Busy(now + 30_000L)

        assertThat(aacStep.resolve(query)).isEqualTo(TidalAac.Unavailable(TidalAac.Reason.BUSY, 1_030_000L))
        limiter.stateOf(id).let {
            assertThat(it.msUntilUnblock).isEqualTo(30_000L)
            assertThat(it.breakerTripped).isFalse()
        }

        // The next song ten seconds on: no request, and a wait for the same pause (not 120 s, not 5 min).
        now += 10_000L
        val next = TrackQuery(artist = "Daft Punk", title = "One More Time")
        assertThat(aacStep.resolve(next)).isEqualTo(TidalAac.Unavailable(TidalAac.Reason.BUSY, 1_030_000L))
        coVerify(exactly = 0) { client.lookup(next, any(), false, any()) }
    }

    @Test fun `switch off means no AAC request`() = runTest {
        ready(switchOn = false)
        assertThat(resolver.resolve(query)).isEqualTo(TidalAac.NoAac)
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
    }

    @Test fun `AAC download mode asks aac=only and ignores the Lossless-mode switch`() = runTest {
        ready(switchOn = false)
        coEvery { client.lookup(query, allowAac = true, cacheOnly = true, aacOnly = true) } returns TidalLookup.NeedsRelay
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false, aacOnly = true) } returns TidalLookup.Found(aac)

        val r = (resolver.resolve(query, aacOnly = true) as TidalAac.Found).source

        assertThat(r.format.codec).isEqualTo("aac")
        coVerify(exactly = 1) { client.lookup(query, allowAac = true, cacheOnly = false, aacOnly = true) }
        coVerify(exactly = 0) { client.lookup(any(), any(), false, false) }
    }

    @Test fun `a spent day asks nothing, in either mode, and is a wait for the reset, no failure`() = runTest {
        ready()
        coEvery { client.lookup(any(), any(), true, any()) } returns TidalLookup.Spent(5_000L)

        assertThat(resolver.resolve(query)).isEqualTo(TidalAac.Unavailable(TidalAac.Reason.SPENT, 5_000L))
        assertThat(resolver.resolve(query, aacOnly = true)).isEqualTo(TidalAac.Unavailable(TidalAac.Reason.SPENT, 5_000L))

        coVerify(exactly = 0) { client.lookup(any(), any(), false, any()) }
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
        coVerify(exactly = 0) { rateLimiter.reportFailure(any()) }
    }

    @Test fun `a thrown resolve counts as a failure and is a wait`() = runTest {
        ready()
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } throws IllegalStateException("boom")
        assertThat((resolver.resolve(query) as TidalAac.Unavailable).reason).isEqualTo(TidalAac.Reason.FAILED)
        coVerify { rateLimiter.reportFailure(id) }
    }

    @Test fun `a cached AAC miss costs no token and no request`() = runTest {
        ready()
        coEvery { client.lookup(any(), any(), true) } returns TidalLookup.NoMatch
        assertThat(resolver.resolve(query)).isEqualTo(TidalAac.NoAac)
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `a busy pool is a wait until the relay says, announced for the retry sweep`() = runTest {
        ready()
        resolver.nowMs = { 1_000L }
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } returns TidalLookup.Busy(120_000L)
        val events = mutableListOf<Long>()
        val collecting = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            resolver.retryEvents.collect { events += it }
        }

        val r = resolver.resolve(query) as TidalAac.Unavailable

        assertThat(r.reason).isEqualTo(TidalAac.Reason.BUSY)
        assertThat(r.retryAtMs).isEqualTo(120_000L)
        assertThat(events).containsExactly(120_000L)
        // Paused for the relay's own wait, not the configured backoff (the one-argument call).
        coVerify { rateLimiter.reportRateLimited(id, 119_000L) }
        coVerify(exactly = 0) { rateLimiter.reportRateLimited(id) }
        collecting.cancel()
    }

    @Test fun `a busy pool already known costs no request either`() = runTest {
        ready()
        coEvery { client.lookup(any(), any(), true, any()) } returns TidalLookup.Busy(99_000_000L)
        assertThat((resolver.resolve(query) as TidalAac.Unavailable).reason).isEqualTo(TidalAac.Reason.BUSY)
        coVerify(exactly = 0) { client.lookup(any(), any(), false, any()) }
    }
}
