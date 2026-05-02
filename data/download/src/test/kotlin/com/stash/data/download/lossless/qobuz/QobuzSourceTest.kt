package com.stash.data.download.lossless.qobuz

import com.stash.data.download.lossless.AggregatorRateLimiter
import com.stash.data.download.lossless.RateLimitState
import com.stash.data.download.lossless.TrackQuery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [QobuzSource] (the squid.wtf-backed implementation).
 *
 * Mocks the API client + rate limiter; verifies the source's contract
 * independently of the network:
 *
 *  - When the rate limiter circuit-breaks the source, it reports disabled.
 *  - Search results below the confidence threshold are skipped.
 *  - ISRC matches always score above the threshold.
 *  - Empty download URLs and 4xx errors are treated as resolve failures
 *    rather than hard exceptions, so the chain falls through cleanly.
 *  - Rate limiter is consulted before every API call and bookkept on
 *    success / 429 / generic failure.
 */
class QobuzSourceTest {

    private val apiClient: QobuzApiClient = mockk()
    private val rateLimiter: AggregatorRateLimiter = mockk(relaxUnitFun = true)

    private fun source() = QobuzSource(apiClient, rateLimiter)

    private fun stubLimiterReady() {
        coEvery { rateLimiter.acquire(QobuzSource.SOURCE_ID) } returns true
        coEvery { rateLimiter.stateOf(QobuzSource.SOURCE_ID) } returns
            RateLimitState(3.0, 0L, false, 0L, 0)
    }

    private fun query(
        artist: String = "Radiohead",
        title: String = "Karma Police",
        isrc: String? = null,
        durationMs: Long? = 261_000L,
    ) = TrackQuery(artist = artist, title = title, isrc = isrc, durationMs = durationMs)

    private fun candidate(
        id: Long = 1L,
        title: String = "Karma Police",
        artist: String = "Radiohead",
        duration: Int = 261,
        isrc: String? = null,
        streamable: Boolean = true,
        bitDepth: Int = 16,
        sampleRate: Float = 44.1f,
    ) = QobuzTrack(
        id = id,
        title = title,
        duration = duration,
        isrc = isrc,
        performer = QobuzPerformer(name = artist),
        streamable = streamable,
        maximumBitDepth = bitDepth,
        maximumSamplingRate = sampleRate,
    )

    private fun download(url: String? = "https://cdn.qobuz/x.flac") = QobuzDownloadData(url = url)

    // ── isEnabled ───────────────────────────────────────────────────────

    @Test fun `isEnabled false when circuit-broken`() = runTest {
        coEvery { rateLimiter.stateOf(QobuzSource.SOURCE_ID) } returns
            RateLimitState(0.0, 0L, isCircuitBroken = true, msUntilUnblock = 60_000, recentFailures = 3)
        assertFalse(source().isEnabled())
    }

    @Test fun `isEnabled true when rate limiter healthy`() = runTest {
        coEvery { rateLimiter.stateOf(QobuzSource.SOURCE_ID) } returns
            RateLimitState(3.0, 0L, false, 0L, 0)
        assertTrue(source().isEnabled())
    }

    // ── resolve happy path ─────────────────────────────────────────────

    @Test fun `resolve returns SourceResult for confident match`() = runTest {
        stubLimiterReady()
        coEvery {
            apiClient.search("Radiohead Karma Police", any(), any())
        } returns QobuzSearchData(tracks = QobuzTrackList(items = listOf(candidate())))
        coEvery {
            apiClient.getFileUrl(1L, QobuzQuality.FLAC_HIRES_192, any())
        } returns download()

        val result = source().resolve(query())

        assertNotNull(result)
        assertEquals(QobuzSource.SOURCE_ID, result!!.sourceId)
        assertEquals("https://cdn.qobuz/x.flac", result.downloadUrl)
        assertEquals("flac", result.format.codec)
        assertEquals(44_100, result.format.sampleRateHz)
        assertEquals(16, result.format.bitsPerSample)
        assertTrue(result.format.isLossless)
        assertEquals("1", result.sourceTrackId)
        assertTrue("confidence ${result.confidence}", result.confidence > 0.5f)
    }

    @Test fun `ISRC match short-circuits to high confidence even with imperfect strings`() = runTest {
        stubLimiterReady()
        coEvery {
            apiClient.search("USRC12345678", any(), any())
        } returns QobuzSearchData(
            tracks = QobuzTrackList(items = listOf(
                candidate(title = "Different Title", artist = "Different Artist", isrc = "USRC12345678"),
            )),
        )
        coEvery { apiClient.getFileUrl(1L, any(), any()) } returns download()

        val result = source().resolve(query(isrc = "USRC12345678"))

        assertNotNull(result)
        assertEquals(0.95f, result!!.confidence, 0.01f)
    }

    // ── resolve failure paths ──────────────────────────────────────────

    @Test fun `resolve null when search returns no tracks`() = runTest {
        stubLimiterReady()
        coEvery { apiClient.search(any(), any(), any()) } returns
            QobuzSearchData(tracks = QobuzTrackList(items = emptyList()))
        assertNull(source().resolve(query()))
    }

    @Test fun `resolve null when no candidate crosses confidence threshold`() = runTest {
        stubLimiterReady()
        coEvery { apiClient.search(any(), any(), any()) } returns
            QobuzSearchData(tracks = QobuzTrackList(items = listOf(
                candidate(title = "Completely Unrelated Song", artist = "Different Band"),
            )))
        assertNull(source().resolve(query()))
    }

    @Test fun `resolve filters out non-streamable candidates`() = runTest {
        stubLimiterReady()
        coEvery { apiClient.search(any(), any(), any()) } returns
            QobuzSearchData(tracks = QobuzTrackList(items = listOf(
                candidate(streamable = false),  // would otherwise match perfectly
            )))
        assertNull(source().resolve(query()))
    }

    @Test fun `resolve null when getFileUrl returns empty url`() = runTest {
        stubLimiterReady()
        coEvery { apiClient.search(any(), any(), any()) } returns
            QobuzSearchData(tracks = QobuzTrackList(items = listOf(candidate())))
        coEvery { apiClient.getFileUrl(any(), any(), any()) } returns download(url = null)
        assertNull(source().resolve(query()))
    }

    @Test fun `resolve null when getFileUrl 403s (region lock)`() = runTest {
        stubLimiterReady()
        coEvery { apiClient.search(any(), any(), any()) } returns
            QobuzSearchData(tracks = QobuzTrackList(items = listOf(candidate())))
        coEvery { apiClient.getFileUrl(any(), any(), any()) } throws
            QobuzApiException(status = 403, message = "region locked")
        assertNull(source().resolve(query()))
    }

    @Test fun `resolve null when rate limiter denies acquire`() = runTest {
        coEvery { rateLimiter.acquire(QobuzSource.SOURCE_ID) } returns false
        coEvery { rateLimiter.stateOf(any()) } returns RateLimitState(0.0, 0L, true, 60_000, 3)
        assertNull(source().resolve(query()))
    }

    // ── Rate limiter bookkeeping ───────────────────────────────────────

    @Test fun `successful API call reports success to rate limiter`() = runTest {
        stubLimiterReady()
        coEvery { apiClient.search(any(), any(), any()) } returns
            QobuzSearchData(tracks = QobuzTrackList(items = listOf(candidate())))
        coEvery { apiClient.getFileUrl(any(), any(), any()) } returns download()

        source().resolve(query())

        coVerify(atLeast = 2) { rateLimiter.reportSuccess(QobuzSource.SOURCE_ID) }
    }

    @Test fun `429 from search reports rateLimited (not generic failure)`() = runTest {
        stubLimiterReady()
        coEvery { apiClient.search(any(), any(), any()) } throws
            QobuzApiException(status = 429, message = "Too Many Requests")

        assertNull(source().resolve(query()))

        coVerify { rateLimiter.reportRateLimited(QobuzSource.SOURCE_ID) }
    }

    @Test fun `non-429 exception from search reports generic failure`() = runTest {
        stubLimiterReady()
        coEvery { apiClient.search(any(), any(), any()) } throws
            QobuzApiException(status = 500, message = "Server error")

        assertNull(source().resolve(query()))

        coVerify { rateLimiter.reportFailure(QobuzSource.SOURCE_ID) }
    }

    // ── Helper functions (test the matching primitives directly) ──────

    @Test fun `normalize lowercases strips parens feat punctuation`() {
        assertEquals(
            "karma police",
            QobuzSource.normalize("Karma Police (Remastered) feat. someone"),
        )
        assertEquals("song name", QobuzSource.normalize("Song Name [Live Version]"))
        assertEquals("dont stop me now", QobuzSource.normalize("Don't Stop Me Now!"))
    }

    @Test fun `jaccard 1 for identical 0 for disjoint`() {
        assertEquals(1.0f, QobuzSource.jaccard("a b c", "a b c"), 0.001f)
        assertEquals(0.0f, QobuzSource.jaccard("a b c", "x y z"), 0.001f)
        // {a,b} ∩ {a,c} = {a} (size 1), union {a,b,c} (size 3) → 1/3
        assertEquals(1f / 3f, QobuzSource.jaccard("a b", "a c"), 0.001f)
        // Half overlap on equal-size sets: {a,b,c,d} ∩ {a,b,e,f} = {a,b}
        // (size 2), union (size 6) → 1/3 (not 0.5 — Jaccard is more
        // strict than overlap coefficient).
        assertEquals(1f / 3f, QobuzSource.jaccard("a b c d", "a b e f"), 0.001f)
    }

    @Test fun `artistSimilarity rewards subset matches with distinctive overlap`() {
        // Spotify expansion vs Qobuz canonical — the case that was
        // silently dropping every Diana Ross track in production.
        val sim = QobuzSource.artistSimilarity(
            "diana ross and the supremes",
            "the supremes",
        )
        // Subset coverage: {the,supremes} ⊂ {diana,ross,and,the,supremes}
        // → 2/2 = 1.0 (smaller set fully contained, "supremes" is distinctive)
        assertEquals(1.0f, sim, 0.001f)
    }

    @Test fun `artistSimilarity rewards single-canonical-artist subset (lead-artist case)`() {
        // Spotify expansion to featuring list; Qobuz indexes lead only.
        val sim = QobuzSource.artistSimilarity(
            "ghostemane shakewell pouya erick the architect",
            "ghostemane",
        )
        // {ghostemane} ⊂ larger set, "ghostemane" length > 3 → 1.0
        assertEquals(1.0f, sim, 0.001f)
    }

    @Test fun `artistSimilarity rejects subset with no distinctive overlap`() {
        // Both share only short tokens (length <= 3) — should NOT
        // flip to subset-coverage. Falls back to jaccard.
        val sim = QobuzSource.artistSimilarity("the air", "the dog")
        // Intersection {the}, union {the,air,dog} → jaccard 1/3.
        // Subset coverage would be 1/2 but is gated off (no distinctive overlap).
        assertEquals(1f / 3f, sim, 0.001f)
    }

    @Test fun `artistSimilarity returns 1 for identical strings`() {
        assertEquals(1.0f, QobuzSource.artistSimilarity("radiohead", "radiohead"), 0.001f)
    }
}
