package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.relay.LosslessDownloadPurpose
import kotlinx.coroutines.withContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TidalLosslessSourceTest {

    private val client: TidalClient = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private var now = 0L
    private val source = TidalLosslessSource(client, rateLimiter) { now }
    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky")
    private val id = TidalLosslessSource.SOURCE_ID

    private fun state(broken: Boolean = false) {
        coEvery { rateLimiter.stateOf(id) } returns RateLimitState(2.0, 0L, broken, 0L, 0)
        coEvery { client.lookup(any(), any(), true) } returns TidalLookup.NeedsRelay
    }

    private val stream = TidalStream(
        url = "https://relay/dash/td/1.mpd?exp=9&sig=s",
        isDash = true,
        bitDepth = 24,
        sampleRateHz = 96_000,
        bitrateKbps = 0,
        coverArtUrl = "https://img/cover.jpg",
        expiresAtMs = 0L,
        confidence = 0.97f,
        trackId = "td:1",
    )

    @Test fun `a spent day's allowance is an incomplete search with no token spent and no breaker report`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { client.lookup(any(), any(), true) } returns TidalLookup.Spent(1_000L)
        val purpose = LosslessDownloadPurpose()
        assertThat(withContext(purpose) { source.resolve(query) }).isNull()
        assertThat(purpose.searchIncomplete).isTrue()
        assertThat(purpose.pacedRetryAfterSec).isNull()
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `the relay's cap answer reaches neither the breaker nor the miss path`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns true
        coEvery { client.lookup(query, false, false) } returns TidalLookup.Spent(1_000L)
        val purpose = LosslessDownloadPurpose()
        assertThat(withContext(purpose) { source.resolve(query) }).isNull()
        assertThat(purpose.searchIncomplete).isTrue()
        coVerify(exactly = 0) { rateLimiter.reportFailure(any()) }
        coVerify(exactly = 0) { rateLimiter.reportRateLimited(any()) }
        coVerify(exactly = 0) { rateLimiter.reportRateLimited(any(), any()) }
    }

    @Test fun `not configured means disabled and no lookup`() = runTest {
        state()
        coEvery { client.isConfigured() } returns false
        assertThat(source.isEnabled()).isFalse()
        assertThat(source.resolve(query)).isNull()
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `circuit broken means disabled`() = runTest {
        state(broken = true)
        coEvery { client.isConfigured() } returns true
        assertThat(source.isEnabled()).isFalse()
    }

    @Test fun `maps the stream onto a flac SourceResult marked dash`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns true
        coEvery { client.lookup(query, false, false) } returns TidalLookup.Found(stream)

        val r = source.resolve(query)!!

        assertThat(r.sourceId).isEqualTo(id)
        assertThat(r.downloadUrl).isEqualTo(stream.url)
        assertThat(r.isDash).isTrue()
        assertThat(r.format.codec).isEqualTo("flac")
        assertThat(r.format.fileExtension).isEqualTo("flac")
        assertThat(r.format.bitsPerSample).isEqualTo(24)
        assertThat(r.format.sampleRateHz).isEqualTo(96_000)
        assertThat(r.confidence).isEqualTo(0.97f)
        assertThat(r.sourceTrackId).isEqualTo("td:1")
        assertThat(r.coverArtUrl).isEqualTo("https://img/cover.jpg")
        coVerify { rateLimiter.reportSuccess(id) }
    }

    @Test fun `rate-limited background resolve returns null without calling the relay`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns false
        assertThat(source.resolve(query)).isNull()
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `bypass skips the token bucket`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { client.lookup(query, false, false) } returns TidalLookup.Found(stream.copy(isDash = false))
        val r = source.resolve(query, bypassRateLimit = true)!!
        assertThat(r.isDash).isFalse()
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
    }

    @Test fun `a throw counts as a failure`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns true
        coEvery { client.lookup(query, false, false) } throws IllegalStateException("boom")
        assertThat(source.resolve(query)).isNull()
        coVerify { rateLimiter.reportFailure(id) }
    }

    @Test fun `a cached miss spends no token and no request`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { client.lookup(any(), any(), true) } returns TidalLookup.NoMatch
        assertThat(source.resolve(query)).isNull()
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `a busy pool reports rate-limited for the relay's own wait and marks the sweep's search incomplete`() = runTest {
        state()
        now = 1_000L
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns true
        coEvery { client.lookup(query, false, false) } returns TidalLookup.Busy(31_000L)
        val purpose = LosslessDownloadPurpose(upgradeSweep = true)
        assertThat(withContext(purpose) { source.resolve(query) }).isNull()
        assertThat(purpose.searchIncomplete).isTrue()
        coVerify { rateLimiter.reportRateLimited(id, 30_000L) }
        // Not the configured backoff (the one-argument call).
        coVerify(exactly = 0) { rateLimiter.reportRateLimited(id) }
    }

    /** The phone's log at 01:06: the relay asked for 29 s and Tidal sat out the 120 s 429 backoff. */
    @Test fun `a 30 s busy blocks Tidal for 30 s, not the configured 120 s backoff`() = runTest {
        val limiter = AggregatorRateLimiter().apply {
            clock = object : AggregatorRateLimiter.Clock {
                override fun nowMs(): Long = now
            }
        }
        val tidal = TidalLosslessSource(client, limiter) { now }
        now = 1_000_000L
        coEvery { client.isConfigured() } returns true
        coEvery { client.lookup(any(), any(), true) } returns TidalLookup.NeedsRelay
        coEvery { client.lookup(query, false, false) } returns TidalLookup.Busy(now + 30_000L)

        assertThat(tidal.resolve(query)).isNull()

        val paused = limiter.stateOf(id)
        assertThat(paused.isCircuitBroken).isTrue()
        assertThat(paused.breakerTripped).isFalse()
        assertThat(paused.msUntilUnblock).isEqualTo(30_000L)
        // Back the moment the relay's wait is over.
        now += 30_000L
        assertThat(tidal.isEnabled()).isTrue()
    }

    @Test fun `an upgrade sweep spends at most its hourly budget then paces`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns true
        coEvery { client.lookup(any(), false, false) } returns TidalLookup.NoMatch
        repeat(TidalLosslessSource.SWEEP_BUDGET_PER_HOUR) {
            val p = LosslessDownloadPurpose(upgradeSweep = true)
            withContext(p) { source.resolve(query) }
            assertThat(p.pacedRetryAfterSec).isNull()
        }
        val paced = LosslessDownloadPurpose(upgradeSweep = true)
        assertThat(withContext(paced) { source.resolve(query) }).isNull()
        assertThat(paced.pacedRetryAfterSec).isNotNull()
        assertThat(paced.searchIncomplete).isTrue()
        coVerify(exactly = TidalLosslessSource.SWEEP_BUDGET_PER_HOUR) { client.lookup(any(), false, false) }

        // A sync download (no sweep flag) is not held to the sweep budget.
        withContext(LosslessDownloadPurpose()) { source.resolve(query) }
        coVerify(exactly = TidalLosslessSource.SWEEP_BUDGET_PER_HOUR + 1) { client.lookup(any(), false, false) }

        // An hour later the budget is back.
        now += 60 * 60_000L
        val later = LosslessDownloadPurpose(upgradeSweep = true)
        withContext(later) { source.resolve(query) }
        assertThat(later.pacedRetryAfterSec).isNull()
    }

    /** "CD means CD" (decision D15, review-881 M5): the download quality reaches the lookup that asks the server. */
    @Test fun `the download quality decides whether Tidal is asked for CD only`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns true
        coEvery { client.lookup(query, false, false, false, any()) } returns TidalLookup.Found(stream)
        var cd = true
        val source = TidalLosslessSource(client, rateLimiter, { cd }) { now }

        assertThat(source.resolve(query)).isNotNull()
        coVerify(exactly = 1) { client.lookup(query, cdOnly = true) }
        coVerify(exactly = 0) { client.lookup(query, cdOnly = false) }

        cd = false
        assertThat(source.resolve(query)).isNotNull()
        coVerify(exactly = 1) { client.lookup(query, cdOnly = false) }
        // The free look at the cache asks no server, so it carries no quality.
        coVerify(exactly = 2) { client.lookup(query, cacheOnly = true) }
    }
}
