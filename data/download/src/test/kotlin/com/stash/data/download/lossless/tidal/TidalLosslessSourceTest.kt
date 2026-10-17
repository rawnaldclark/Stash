package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.bitchord.BitchordClient
import com.stash.data.download.lossless.bitchord.BitchordStream
import com.stash.data.download.lossless.bitchord.TidalLookup
import com.stash.data.download.lossless.relay.LosslessDownloadPurpose
import kotlinx.coroutines.withContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TidalLosslessSourceTest {

    private val client: BitchordClient = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)
    private var now = 0L
    private val source = TidalLosslessSource(client, rateLimiter) { now }
    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky")
    private val id = TidalLosslessSource.SOURCE_ID

    private fun state(broken: Boolean = false) {
        coEvery { rateLimiter.stateOf(id) } returns RateLimitState(2.0, 0L, broken, 0L, 0)
        coEvery { client.lookup(any(), any(), true) } returns TidalLookup.NeedsRelay
    }

    private val stream = BitchordStream(
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

    @Test fun `a busy pool reports rate-limited and marks the sweep's search incomplete`() = runTest {
        state()
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.acquire(id) } returns true
        coEvery { client.lookup(query, false, false) } returns TidalLookup.Busy(1L)
        val purpose = LosslessDownloadPurpose(upgradeSweep = true)
        assertThat(withContext(purpose) { source.resolve(query) }).isNull()
        assertThat(purpose.searchIncomplete).isTrue()
        coVerify { rateLimiter.reportRateLimited(id) }
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
}
