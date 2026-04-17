package com.stash.data.download.preview

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [PreviewUrlExtractor]'s race + split semaphore behaviour.
 *
 * These tests exercise the companion `race()` function via the `raceForTest`
 * hook, so the private `extractViaInnerTube` / `extractViaYtDlp` member
 * methods (which require Android deps + real network) are not invoked.
 *
 * Contract under test:
 *  1. When InnerTube returns a URL first, that URL wins.
 *  2. When InnerTube wins, the in-flight yt-dlp coroutine is cancelled.
 *  3. When InnerTube returns null, yt-dlp's result is returned instead.
 *  4. The split semaphores cap InnerTube at 8 concurrent and yt-dlp at 2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewUrlExtractorTest {

    /** Test-double: adapts two lambdas into the [PreviewUrlExtractor.TestHooks] SPI. */
    private class TestableExtractor(
        val innertube: suspend (String) -> String?,
        val ytdlp: suspend (String) -> String,
    ) : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String) = innertube(id)
        override suspend fun ytDlpExtract(id: String) = ytdlp(id)
    }

    @Test
    fun `race returns innertube URL when innertube wins`() = runTest {
        val hooks = TestableExtractor(
            innertube = { "https://fast/$it" },
            ytdlp = { delay(5_000); "https://slow/$it" },
        )
        val url = PreviewUrlExtractor.raceForTest(hooks, "abc")
        assertEquals("https://fast/abc", url)
    }

    @Test
    fun `race cancels ytdlp when innertube wins`() = runTest {
        val ytDlpCancelled = AtomicBoolean(false)
        val hooks = TestableExtractor(
            innertube = { "https://fast/$it" },
            ytdlp = {
                try {
                    delay(2_000); "https://slow/$it"
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    // Narrow to CancellationException so we don't flag
                    // an unrelated throw as a cancellation signal.
                    ytDlpCancelled.set(true); throw ce
                }
            },
        )
        PreviewUrlExtractor.raceForTest(hooks, "abc")
        // Drain pending tasks on the test scheduler so the cancellation
        // propagates deterministically. runCurrent() is precise; delay()
        // would advance virtual time arbitrarily.
        runCurrent()
        assertTrue(ytDlpCancelled.get())
    }

    @Test
    fun `race falls back to ytdlp when innertube returns null`() = runTest {
        val hooks = TestableExtractor(
            innertube = { null },
            ytdlp = { "https://ytdlp/$it" },
        )
        val url = PreviewUrlExtractor.raceForTest(hooks, "abc")
        assertEquals("https://ytdlp/abc", url)
    }

    @Test
    fun `race falls back to ytdlp when innertube throws`() = runTest {
        // Regression lock: any non-cancellation throw in the innertube
        // extractor (IOException, parse error, etc.) must be rescued
        // inside the async so yt-dlp can deliver. Before the fix, the
        // exception escaped the async, cancelled yt-dlp, and propagated
        // out of coroutineScope.
        val hooks = TestableExtractor(
            innertube = { throw java.io.IOException("boom") },
            ytdlp = { "https://ytdlp/$it" },
        )
        val url = PreviewUrlExtractor.raceForTest(hooks, "abc")
        assertEquals("https://ytdlp/abc", url)
    }

    @Test
    fun `innertube semaphore caps concurrency at 8`() = runTest {
        val itMax = AtomicInteger(0); val itCur = AtomicInteger(0)
        val hooks = TestableExtractor(
            innertube = {
                itMax.updateAndGet { m -> maxOf(m, itCur.incrementAndGet()) }
                try { delay(50); "u" } finally { itCur.decrementAndGet() }
            },
            // Effectively ignored: innertube always wins first, so yt-dlp
            // gets cancelled before its delay elapses.
            ytdlp = { delay(100_000); "y" },
        )
        coroutineScope {
            (1..30).map { async { PreviewUrlExtractor.raceForTest(hooks, "id$it") } }.awaitAll()
        }
        // Assert the *exact* observed cap. 30 concurrent callers saturate
        // the pool, so we should hit exactly 8 — not merely <= 8.
        assertEquals("expected exactly 8 concurrent innertube slots", 8, itMax.get())
    }

    @Test
    fun `ytdlp semaphore caps concurrency at 2`() = runTest {
        val ytMax = AtomicInteger(0); val ytCur = AtomicInteger(0)
        val hooks = TestableExtractor(
            // Return null to force the race to fall back to yt-dlp, so
            // the semaphore under test actually sees load.
            innertube = { null },
            ytdlp = {
                ytMax.updateAndGet { m -> maxOf(m, ytCur.incrementAndGet()) }
                try { delay(50); "y" } finally { ytCur.decrementAndGet() }
            },
        )
        coroutineScope {
            (1..10).map { async { PreviewUrlExtractor.raceForTest(hooks, "id$it") } }.awaitAll()
        }
        assertEquals("expected exactly 2 concurrent yt-dlp slots", 2, ytMax.get())
    }
}
