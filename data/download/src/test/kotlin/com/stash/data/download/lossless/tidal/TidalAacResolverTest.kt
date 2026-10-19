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

    private fun ready(switchOn: Boolean = true, broken: Boolean = false, acquire: Boolean = true) {
        coEvery { prefs.tidalAacFallbackNow() } returns switchOn
        coEvery { client.isConfigured() } returns true
        coEvery { rateLimiter.stateOf(any()) } returns RateLimitState(2.0, 0L, broken, 0L, 0)
        coEvery { rateLimiter.acquire(id) } returns acquire
        coEvery { client.lookup(any(), any(), true) } returns TidalLookup.NeedsRelay
    }

    @Test fun `AAC answer maps to an aac m4a SourceResult at 320 kbps`() = runTest {
        ready()
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } returns TidalLookup.Found(aac)
        val r = resolver.resolve(query)!!
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

    @Test fun `a FLAC answer is ignored - the lossless chain owns FLAC`() = runTest {
        ready()
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } returns TidalLookup.Found(aac.copy(codec = "flac", isDash = true, bitDepth = 24))
        assertThat(resolver.resolve(query)).isNull()
    }

    @Test fun `not configured means no lookup`() = runTest {
        ready()
        coEvery { client.isConfigured() } returns false
        assertThat(resolver.resolve(query)).isNull()
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `rate limited means no lookup`() = runTest {
        ready(acquire = false)
        assertThat(resolver.resolve(query)).isNull()
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `open breaker means no lookup`() = runTest {
        ready(broken = true)
        assertThat(resolver.resolve(query)).isNull()
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `switch off means no AAC request`() = runTest {
        ready(switchOn = false)
        assertThat(resolver.resolve(query)).isNull()
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
    }

    @Test fun `AAC download mode asks aac=only and ignores the Lossless-mode switch`() = runTest {
        ready(switchOn = false)
        coEvery { client.lookup(query, allowAac = true, cacheOnly = true, aacOnly = true) } returns TidalLookup.NeedsRelay
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false, aacOnly = true) } returns TidalLookup.Found(aac)

        val r = resolver.resolve(query, aacOnly = true)!!

        assertThat(r.format.codec).isEqualTo("aac")
        coVerify(exactly = 1) { client.lookup(query, allowAac = true, cacheOnly = false, aacOnly = true) }
        coVerify(exactly = 0) { client.lookup(any(), any(), false, false) }
    }

    @Test fun `a spent day asks nothing, in either mode, and is no failure`() = runTest {
        ready()
        coEvery { client.lookup(any(), any(), true, any()) } returns TidalLookup.Spent(0L)

        assertThat(resolver.resolve(query)).isNull()
        assertThat(resolver.resolve(query, aacOnly = true)).isNull()

        coVerify(exactly = 0) { client.lookup(any(), any(), false, any()) }
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
        coVerify(exactly = 0) { rateLimiter.reportFailure(any()) }
    }

    @Test fun `a thrown resolve counts as a failure and returns null`() = runTest {
        ready()
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } throws IllegalStateException("boom")
        assertThat(resolver.resolve(query)).isNull()
        coVerify { rateLimiter.reportFailure(id) }
    }

    @Test fun `a cached AAC miss costs no token and no request`() = runTest {
        ready()
        coEvery { client.lookup(any(), any(), true) } returns TidalLookup.NoMatch
        assertThat(resolver.resolve(query)).isNull()
        coVerify(exactly = 0) { rateLimiter.acquire(any()) }
        coVerify(exactly = 0) { client.lookup(any(), any(), false) }
    }

    @Test fun `a busy pool reports rate-limited`() = runTest {
        ready()
        coEvery { client.lookup(query, allowAac = true, cacheOnly = false) } returns TidalLookup.Busy(1L)
        assertThat(resolver.resolve(query)).isNull()
        coVerify { rateLimiter.reportRateLimited(id) }
    }
}
