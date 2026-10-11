package com.stash.core.ui.util

import android.content.Context
import coil3.Canvas
import coil3.Image
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Size
import com.stash.core.common.ArtUrlSizer
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [ArtSizeInterceptor.intercept] end to end against a fake chain: which URL is tried, in what
 * order, and which result is drawn. The URL grammar itself is tested in `ArtUrlSizerTest`.
 */
class ArtSizeInterceptorTest {

    private val context: Context = mockk(relaxed = true)

    private val qobuz600 = "https://static.qobuz.com/images/covers/25/40/0060254794025_600.jpg"
    private val qobuzMax = "https://static.qobuz.com/images/covers/25/40/0060254794025_max.jpg"
    private val lastFm770 = "https://lastfm-img.freetls.fastly.net/i/u/770x0/c9ebed7e766b45dabedaa8ee759b5181.jpg"
    private val lastFmOrig = "https://lastfm-img.freetls.fastly.net/i/u/ar0/c9ebed7e766b45dabedaa8ee759b5181.jpg"

    private class FakeImage(override val width: Int, override val height: Int) : Image {
        override val size: Long = width.toLong() * height * 4
        override val shareable: Boolean = true
        override fun draw(canvas: Canvas) = Unit
    }

    /** Answers each request with what [respond] gives for its data, recording every URL tried. */
    private class FakeChain(
        override val request: ImageRequest,
        override val size: Size,
        private val respond: (ImageRequest) -> ImageResult,
        val tried: MutableList<Any> = mutableListOf(),
    ) : Interceptor.Chain {
        override fun withRequest(request: ImageRequest): Interceptor.Chain = FakeChain(request, size, respond, tried)
        override fun withSize(size: Size): Interceptor.Chain = FakeChain(request, size, respond, tried)
        override suspend fun proceed(): ImageResult {
            tried += request.data
            return respond(request)
        }
    }

    private fun request(url: String, skip: Boolean = false) = ImageRequest.Builder(context)
        .data(url)
        .size(1024)
        .apply { if (skip) extras[ArtSizeInterceptor.SkipSizing] = true }
        .build()

    private fun ok(r: ImageRequest, px: Int) = SuccessResult(image = FakeImage(px, px), request = r)
    private fun fail(r: ImageRequest) = ErrorResult(image = null, request = r, throwable = IOException("offline"))

    private suspend fun run(
        url: String,
        target: Int = 1024,
        skip: Boolean = false,
        cap: Int = ArtUrlSizer.NO_CAP,
        respond: (ImageRequest) -> ImageResult,
    ): Pair<ImageResult, List<Any>> {
        val chain = FakeChain(request(url, skip), Size(target, target), respond)
        val result = ArtSizeInterceptor { cap }.intercept(chain)
        return result to chain.tried
    }

    // ── B1: the stored URL is the floor for availability ──────────────────────────────────────

    @Test
    fun `a sized-up request that fails draws the stored url (offline, 5xx, timeout)`() = runTest {
        val (result, tried) = run(qobuz600) { r -> if (r.data == qobuzMax) fail(r) else ok(r, 600) }
        assertEquals(listOf<Any>(qobuzMax, qobuz600), tried)
        assertTrue(result is SuccessResult)
        assertEquals(qobuz600, result.request.data)
    }

    @Test
    fun `when the stored url fails too, its error is what is drawn`() = runTest {
        val (result, tried) = run(qobuz600) { r -> fail(r) }
        assertEquals(listOf<Any>(qobuzMax, qobuz600), tried)
        assertTrue(result is ErrorResult)
        assertEquals(qobuz600, result.request.data)
    }

    // ── The stored URL is the floor for size ──────────────────────────────────────────────────

    @Test
    fun `a sized-up result smaller than the stored url draws the stored url`() = runTest {
        // Sinnerman: Last.fm's original is 350px, its stored 770x0 the CDN's cleaner upscale.
        val (result, tried) = run(lastFm770) { r -> if (r.data == lastFmOrig) ok(r, 350) else ok(r, 770) }
        assertEquals(listOf<Any>(lastFmOrig, lastFm770), tried)
        assertEquals(770, result.image!!.width)
    }

    @Test
    fun `a sized-up result bigger than the stored url is kept, with one fetch`() = runTest {
        val (result, tried) = run(qobuz600) { r -> ok(r, 1024) }
        assertEquals(listOf<Any>(qobuzMax), tried)
        assertEquals(qobuzMax, result.request.data)
        assertEquals(1024, result.image!!.width)
    }

    @Test
    fun `a lastfm original at least the stored size is kept`() = runTest {
        val (result, tried) = run(lastFm770) { r -> ok(r, 1000) }
        assertEquals(listOf<Any>(lastFmOrig), tried)
        assertEquals(1000, result.image!!.width)
    }

    // ── Requests that are not sized up go straight through ────────────────────────────────────

    @Test
    fun `a stored url big enough for the target is fetched as-is, once`() = runTest {
        val url = "https://lh3.googleusercontent.com/abc=w1024-h1024-l90-rj"
        val (result, tried) = run(url, target = 132) { r -> ok(r, 132) }
        assertEquals(listOf<Any>(url), tried)
        assertTrue(result is SuccessResult)
    }

    @Test
    fun `a failing request that was not sized up is not retried`() = runTest {
        val (result, tried) = run(qobuz600, target = 132) { r -> fail(r) }
        assertEquals(listOf<Any>(qobuz600), tried)
        assertTrue(result is ErrorResult)
    }

    @Test
    fun `SkipSizing draws the url exactly as given`() = runTest {
        val (_, tried) = run(qobuz600, skip = true) { r -> ok(r, 600) }
        assertEquals(listOf<Any>(qobuz600), tried)
    }

    @Test
    fun `the data saver cap keeps qobuz on 600`() = runTest {
        val (_, tried) = run(qobuz600, cap = ArtUrlSizer.DATA_SAVER_MAX_PX) { r -> ok(r, 600) }
        assertEquals(listOf<Any>(qobuz600), tried)
    }

    @Test
    fun `an original-size request has nothing to size to`() = runTest {
        val chain = FakeChain(request(qobuz600), Size.ORIGINAL, { r -> ok(r, 600) })
        ArtSizeInterceptor().intercept(chain)
        assertEquals(listOf<Any>(qobuz600), chain.tried)
    }

    @Test
    fun `non-string data goes straight through`() = runTest {
        val r = ImageRequest.Builder(context).data(42).size(1024).build()
        val chain = FakeChain(r, Size(1024, 1024), { ok(it, 10) })
        val result = ArtSizeInterceptor().intercept(chain)
        assertEquals(listOf<Any>(42), chain.tried)
        assertSame(r, result.request)
    }

    // ── targetPx ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the target is the long side, one known side is enough, original is none`() {
        assertEquals(1024, ArtSizeInterceptor.targetPx(Size(1024, 1024)))
        assertEquals(946, ArtSizeInterceptor.targetPx(Size(946, 300)))
        assertEquals(132, ArtSizeInterceptor.targetPx(Size(Dimension(132), Dimension.Undefined)))
        assertNull(ArtSizeInterceptor.targetPx(Size.ORIGINAL))
    }
}
