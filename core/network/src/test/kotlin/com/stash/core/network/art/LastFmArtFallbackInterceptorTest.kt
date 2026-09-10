package com.stash.core.network.art

import okhttp3.Connection
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rung-walk itself: which URLs get tried, in what order, and that a
 * non-404 is handed back untouched. The URL arithmetic lives in
 * `ArtUrlUpgrader.lastFmFallback` and is tested there.
 */
class LastFmArtFallbackInterceptorTest {
    /** Answers each request with the code [codes] gives for its URL. */
    private class FakeChain(
        private val start: Request,
        private val codes: (String) -> Int,
    ) : Interceptor.Chain {
        val tried = mutableListOf<String>()
        val closed = mutableListOf<String>()

        override fun request(): Request = start

        override fun proceed(request: Request): Response {
            val url = request.url.toString()
            tried += url
            val code = codes(url)
            val body = object : okhttp3.ResponseBody() {
                private val delegate = "x".toResponseBody("image/jpeg".toMediaType())
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun source() = delegate.source()
                override fun close() {
                    closed += url
                    delegate.close()
                }
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code == 200) "OK" else "Not Found")
                .body(body)
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): Call = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun readTimeoutMillis() = 0
        override fun writeTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun withReadTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
        override fun withWriteTimeout(timeout: Int, unit: java.util.concurrent.TimeUnit) = this
    }

    private val jpg = "https://lastfm-img.freetls.fastly.net/i/u/770x0/abc.jpg"
    private val png = "https://lastfm-img.freetls.fastly.net/i/u/770x0/abc.png"
    private val stored = "https://lastfm-img.freetls.fastly.net/i/u/300x300/abc.png"

    private fun run(codes: (String) -> Int): FakeChain {
        val chain = FakeChain(Request.Builder().url(jpg).build(), codes)
        LastFmArtFallbackInterceptor().intercept(chain).close()
        return chain
    }

    @Test
    fun `a cover that exists is fetched once`() {
        val chain = run { 200 }
        assertEquals(listOf(jpg), chain.tried)
    }

    @Test
    fun `a missing jpeg steps to the png of the same size`() {
        val chain = run { if (it == jpg) 404 else 200 }
        assertEquals(listOf(jpg, png), chain.tried)
        assertTrue("the 404 body must be closed", jpg in chain.closed)
    }

    @Test
    fun `both 770 variants missing falls all the way back to the stored url`() {
        val chain = run { if (it == stored) 200 else 404 }
        assertEquals(listOf(jpg, png, stored), chain.tried)
    }

    @Test
    fun `a 404 that is not lastfm art is returned as-is`() {
        val yt = "https://i.ytimg.com/vi/abc/hqdefault.jpg"
        val chain = FakeChain(Request.Builder().url(yt).build()) { 404 }
        val response = LastFmArtFallbackInterceptor().intercept(chain)
        assertEquals(listOf(yt), chain.tried)
        assertEquals(404, response.code)
        response.close()
    }
}
