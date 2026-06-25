package com.stash.data.download.preview

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.data.ytmusic.InnerTubeClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 *  4. The split semaphores cap InnerTube at 8 concurrent and yt-dlp at 1.
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
    fun `full ytdlp extraction preparation uses freshened manager gate`() = runTest {
        val manager: YtDlpManager = mockk()
        coEvery { manager.ensureFreshened() } returns Unit
        val extractor = PreviewUrlExtractor(
            mockk(relaxed = true),
            manager,
            mockk<TokenManager>(relaxed = true),
            mockk<InnerTubeClient>(relaxed = true),
        )

        extractor.prepareYtDlpForExtraction()

        coVerify(exactly = 1) { manager.ensureFreshened() }
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
    fun `ytdlp semaphore caps concurrency at 1`() = runTest {
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
        assertEquals("expected exactly 1 concurrent yt-dlp slot", 1, ytMax.get())
    }

    @Test
    fun extractStreamUrl_coalesces_concurrent_calls_to_same_videoId() = runTest {
        // Hold the underlying race open with a CompletableDeferred so we can
        // observe coalescing while the extract is in-flight.
        val gate = CompletableDeferred<String>()
        val raceInvocations = AtomicInteger(0)
        val extractor = PreviewUrlExtractor(
            context = mockk(relaxed = true),
            ytDlpManager = mockk(relaxed = true),
            tokenManager = mockk(relaxed = true),
            innerTubeClient = mockk(relaxed = true),
        )
        val hooks = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? {
                raceInvocations.incrementAndGet()
                return gate.await()
            }
            override suspend fun ytDlpExtract(id: String): String = error("unreached")
        }

        val a = async { extractor.extractStreamUrlForTest(hooks, "videoA") }
        val b = async { extractor.extractStreamUrlForTest(hooks, "videoA") }
        runCurrent()

        gate.complete("https://result/A")
        val urlA = a.await()
        val urlB = b.await()

        assertThat(urlA).isEqualTo("https://result/A")
        assertThat(urlB).isEqualTo("https://result/A")
        assertThat(raceInvocations.get()).isEqualTo(1)  // coalesced!
    }

    @Test
    fun extractStreamUrl_cancelling_caller_does_not_abort_other_callers() = runTest {
        val gate = CompletableDeferred<String>()
        val extractor = PreviewUrlExtractor(
            context = mockk(relaxed = true),
            ytDlpManager = mockk(relaxed = true),
            tokenManager = mockk(relaxed = true),
            innerTubeClient = mockk(relaxed = true),
        )
        val hooks = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? = gate.await()
            override suspend fun ytDlpExtract(id: String): String = error("unreached")
        }

        val cancellable = async { extractor.extractStreamUrlForTest(hooks, "X") }
        val persistent  = async { extractor.extractStreamUrlForTest(hooks, "X") }
        runCurrent()

        cancellable.cancel(CancellationException("caller A bails"))
        gate.complete("https://result/X")

        // persistent's await() should still complete with the URL even though
        // the OTHER caller cancelled. The race ran on extractorScope, not on
        // either caller's scope, so caller A's death cannot kill the work.
        val result = persistent.await()
        assertThat(result).isEqualTo("https://result/X")
    }

    @Test
    fun extractStreamUrl_failure_propagates_to_all_callers() = runTest {
        val gate = CompletableDeferred<String>()
        val invocations = AtomicInteger(0)
        val extractor = PreviewUrlExtractor(
            context = mockk(relaxed = true),
            ytDlpManager = mockk(relaxed = true),
            tokenManager = mockk(relaxed = true),
            innerTubeClient = mockk(relaxed = true),
        )
        val hooks = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? = null
            override suspend fun ytDlpExtract(id: String): String {
                invocations.incrementAndGet()
                gate.await()
                throw IllegalStateException("simulated yt-dlp failure")
            }
        }

        // Wrap calls in runCatching INSIDE the async so the async's own
        // Deferred completes normally with the Result rather than failing
        // and propagating to runTest's scope. (A failing child async would
        // cancel the parent scope before any outer try/catch sees the
        // exception — structured-concurrency semantics.)
        val a = async { runCatching { extractor.extractStreamUrlForTest(hooks, "X") } }
        val b = async { runCatching { extractor.extractStreamUrlForTest(hooks, "X") } }
        runCurrent()

        gate.complete("ignored")

        val errA = a.await().exceptionOrNull()
        val errB = b.await().exceptionOrNull()
        assertThat(errA).isInstanceOf(IllegalStateException::class.java)
        assertThat(errB).isInstanceOf(IllegalStateException::class.java)
        assertThat(errA?.message).isEqualTo("simulated yt-dlp failure")
        // Coalescing assertion: prove the failure was shared, not produced
        // independently by each caller. Without this check the test passes
        // even if a's Deferred completed before b's coroutine scheduled,
        // letting b start its own fresh extract that also threw the same
        // canned exception (same-message false positive).
        assertThat(invocations.get()).isEqualTo(1)
    }

    @Test
    fun extractStreamUrl_map_entry_clears_on_success() = runTest {
        val extractor = PreviewUrlExtractor(
            context = mockk(relaxed = true),
            ytDlpManager = mockk(relaxed = true),
            tokenManager = mockk(relaxed = true),
            innerTubeClient = mockk(relaxed = true),
        )
        val invocations = AtomicInteger(0)
        val hooks = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? {
                invocations.incrementAndGet()
                return "https://x/${invocations.get()}"
            }
            override suspend fun ytDlpExtract(id: String): String = error("unreached")
        }

        val first = extractor.extractStreamUrlForTest(hooks, "X")
        val second = extractor.extractStreamUrlForTest(hooks, "X")

        assertThat(first).isEqualTo("https://x/1")
        assertThat(second).isEqualTo("https://x/2")
        assertThat(invocations.get()).isEqualTo(2)
    }

    @Test
    fun fastOnly_does_not_invoke_ytdlp_arm() = runTest {
        val extractor = PreviewUrlExtractor(
            context = mockk(relaxed = true),
            ytDlpManager = mockk(relaxed = true),
            tokenManager = mockk(relaxed = true),
            innerTubeClient = mockk(relaxed = true),
        )
        val ytdlpCalled = AtomicBoolean(false)
        val hooks = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? = null
            override suspend fun ytDlpExtract(id: String): String {
                ytdlpCalled.set(true)
                return "https://ytdlp/$id"
            }
        }

        val err = runCatching {
            extractor.extractStreamUrlForTest(hooks, "X", allowYtDlp = false)
        }.exceptionOrNull()

        assertThat(err).isInstanceOf(NoFastStreamException::class.java)
        assertThat(ytdlpCalled.get()).isFalse()
    }

    @Test
    fun fastOnly_returns_innertube_url_when_present() = runTest {
        val extractor = PreviewUrlExtractor(
            context = mockk(relaxed = true),
            ytDlpManager = mockk(relaxed = true),
            tokenManager = mockk(relaxed = true),
            innerTubeClient = mockk(relaxed = true),
        )
        val ytdlpCalled = AtomicBoolean(false)
        val hooks = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? = "it-url"
            override suspend fun ytDlpExtract(id: String): String {
                ytdlpCalled.set(true)
                return "https://ytdlp/$id"
            }
        }

        val url = extractor.extractStreamUrlForTest(hooks, "X", allowYtDlp = false)

        assertThat(url).isEqualTo("it-url")
        assertThat(ytdlpCalled.get()).isFalse()
    }

    @Test
    fun fullRace_still_falls_back_to_ytdlp() = runTest {
        val extractor = PreviewUrlExtractor(
            context = mockk(relaxed = true),
            ytDlpManager = mockk(relaxed = true),
            tokenManager = mockk(relaxed = true),
            innerTubeClient = mockk(relaxed = true),
        )
        val hooks = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? = null
            override suspend fun ytDlpExtract(id: String): String = "yt-url"
        }

        val url = extractor.extractStreamUrlForTest(hooks, "X", allowYtDlp = true)

        assertThat(url).isEqualTo("yt-url")
    }

    @Test
    fun viaYtDlp_bypasses_innertube_entirely() = runTest {
        // The streaming-playback path must NEVER use an InnerTube/iOS URL:
        // those are PO-token-gated to ~1MB and 403 on full playback. This
        // direct path goes straight to yt-dlp (full-range-playable URLs).
        val extractor = PreviewUrlExtractor(
            context = mockk(relaxed = true),
            ytDlpManager = mockk(relaxed = true),
            tokenManager = mockk(relaxed = true),
            innerTubeClient = mockk(relaxed = true),
        )
        val innerTubeCalled = AtomicBoolean(false)
        val hooks = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? {
                innerTubeCalled.set(true)
                return "https://innertube/$id"
            }
            override suspend fun ytDlpExtract(id: String): String = "https://ytdlp/$id"
        }

        val url = extractor.extractStreamUrlViaYtDlpForTest(hooks, "X")

        assertThat(url).isEqualTo("https://ytdlp/X")
        assertThat(innerTubeCalled.get()).isFalse()
    }

    @Test
    fun viaYtDlp_caps_concurrency_at_1() = runTest {
        // The yt-dlp-direct path must hold the shared cap-1 yt-dlp semaphore.
        // extractViaYtDlpForRetry historically bypassed it (only race() took
        // the permit); a second concurrent execute() crashes at the JNI
        // boundary (YoutubeDLException). This locks the cap.
        val extractor = PreviewUrlExtractor(
            context = mockk(relaxed = true),
            ytDlpManager = mockk(relaxed = true),
            tokenManager = mockk(relaxed = true),
            innerTubeClient = mockk(relaxed = true),
        )
        val ytMax = AtomicInteger(0); val ytCur = AtomicInteger(0)
        val hooks = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? = error("unreached")
            override suspend fun ytDlpExtract(id: String): String {
                ytMax.updateAndGet { m -> maxOf(m, ytCur.incrementAndGet()) }
                return try { delay(50); "y" } finally { ytCur.decrementAndGet() }
            }
        }
        coroutineScope {
            (1..10).map { async { extractor.extractStreamUrlViaYtDlpForTest(hooks, "id$it") } }.awaitAll()
        }
        assertEquals("expected exactly 1 concurrent yt-dlp slot", 1, ytMax.get())
    }

    @Test
    fun extractStreamUrl_map_entry_clears_on_failure() = runTest {
        val extractor = PreviewUrlExtractor(
            context = mockk(relaxed = true),
            ytDlpManager = mockk(relaxed = true),
            tokenManager = mockk(relaxed = true),
            innerTubeClient = mockk(relaxed = true),
        )
        val invocations = AtomicInteger(0)
        val hooks = object : PreviewUrlExtractor.TestHooks {
            override suspend fun innerTubeExtract(id: String): String? = null
            override suspend fun ytDlpExtract(id: String): String {
                invocations.incrementAndGet()
                throw IllegalStateException("boom ${invocations.get()}")
            }
        }

        val firstErr = runCatching { extractor.extractStreamUrlForTest(hooks, "X") }.exceptionOrNull()
        val secondErr = runCatching { extractor.extractStreamUrlForTest(hooks, "X") }.exceptionOrNull()

        assertThat(firstErr?.message).isEqualTo("boom 1")
        assertThat(secondErr?.message).isEqualTo("boom 2")
        assertThat(invocations.get()).isEqualTo(2)
    }
}
