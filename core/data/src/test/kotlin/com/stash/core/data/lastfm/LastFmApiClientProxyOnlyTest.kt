package com.stash.core.data.lastfm

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.LastFmCacheDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * track.getInfo read through the proxy only (Your record's lengths pass asks about whole histories): the proxy's answer or
 * failure is the read's own, never one more read on the app's keys, and with no proxy nothing is sent at all.
 */
class LastFmApiClientProxyOnlyTest {

    private val proxyHost = "proxy.example"

    /** Answers [proxyCode]/[proxyBody] for the proxy and a track with a length directly, counting both. */
    private class Canned(private val proxyHost: String, private val proxyCode: Int, private val proxyBody: String) : Interceptor {
        var proxyCalls = 0
        var directCalls = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val (code, body) = if (req.url.host == proxyHost) {
                proxyCalls++
                proxyCode to proxyBody
            } else {
                directCalls++
                200 to """{"track":{"name":"t","duration":"111000"}}"""
            }
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun client(canned: Canned, proxy: Boolean = true, gate: LastFmRateLimitGate = LastFmRateLimitGate()): LastFmApiClient {
        val cacheDao = mockk<LastFmCacheDao>(relaxed = true)
        coEvery { cacheDao.get(any()) } returns null
        return LastFmApiClient(
            okHttpClient = OkHttpClient.Builder().addInterceptor(canned).build(),
            credentials = LastFmCredentials(apiKey = "key", apiSecret = "secret", proxyUrl = if (proxy) "https://$proxyHost/lastfm" else null),
            rateLimitGate = gate,
            cacheDao = cacheDao,
        )
    }

    @Test fun `a length through the proxy`() = runTest {
        val canned = Canned(proxyHost, 200, """{"track":{"name":"t","duration":"201000"}}""")
        assertThat(client(canned).getTrackInfo("a", "t", proxyOnly = true).getOrNull()?.durationMs).isEqualTo(201_000L)
        assertThat(canned.directCalls).isEqualTo(0)
    }

    @Test fun `a failing proxy fails the read, with no direct fallback`() = runTest {
        for ((code, body) in listOf(502 to """{"error":"upstream_unreachable"}""", 500 to "oops", 200 to """{"error":29,"message":"Rate limit"}""")) {
            val canned = Canned(proxyHost, code, body)
            assertThat(client(canned).getTrackInfo("a", "t", proxyOnly = true).isFailure).isTrue()
            assertThat(canned.proxyCalls).isEqualTo(1)
            assertThat(canned.directCalls).isEqualTo(0)
        }
        // While the same read without the flag still falls back
        val canned = Canned(proxyHost, 502, """{"error":"upstream_unreachable"}""")
        assertThat(client(canned).getTrackInfo("a", "t").getOrNull()?.durationMs).isEqualTo(111_000L)
        assertThat(canned.directCalls).isEqualTo(1)
    }

    @Test fun `a throttled proxy is busy without a request`() = runTest {
        val canned = Canned(proxyHost, 429, "")
        val gate = LastFmRateLimitGate()
        val c = client(canned, gate = gate)
        assertThat(c.getTrackInfo("a", "t", proxyOnly = true).exceptionOrNull()).isInstanceOf(LastFmRateLimitedException::class.java)
        // The 429 opened the proxy's breaker: the next proxy-only read isn't sent
        assertThat(c.getTrackInfo("a", "t2", proxyOnly = true).exceptionOrNull()).isInstanceOf(LastFmRateLimitedException::class.java)
        assertThat(canned.proxyCalls).isEqualTo(1)
        assertThat(canned.directCalls).isEqualTo(0)
    }

    @Test fun `a bulk read's rate limit trips its own breaker, never the one the app's other lookups use`() = runTest {
        val canned = Canned(proxyHost, 429, "")
        val c = client(canned)
        assertThat(c.getTrackInfo("a", "t", proxyOnly = true).exceptionOrNull()).isInstanceOf(LastFmRateLimitedException::class.java)
        // A mix's or a radio's lookup still goes to the proxy (and, throttled there, on to its own fallback)
        c.getTrackInfo("a", "t2")
        assertThat(canned.proxyCalls).isEqualTo(2)
    }

    @Test fun `a bulk read in flight is cancelled with its caller, never waited out`() = runTest {
        val started = java.util.concurrent.CountDownLatch(1)
        var cancelled = false
        val slow = Interceptor { chain ->
            started.countDown()
            // Last.fm taking its time: until the call is cancelled, or 10 s
            val until = System.currentTimeMillis() + 10_000
            while (!chain.call().isCanceled() && System.currentTimeMillis() < until) Thread.sleep(10)
            cancelled = chain.call().isCanceled()
            throw java.io.IOException("Canceled")
        }
        val cacheDao = mockk<LastFmCacheDao>(relaxed = true)
        coEvery { cacheDao.get(any()) } returns null
        val c = LastFmApiClient(
            okHttpClient = OkHttpClient.Builder().addInterceptor(slow).build(),
            credentials = LastFmCredentials(apiKey = "key", apiSecret = "secret", proxyUrl = "https://$proxyHost/lastfm"),
            rateLimitGate = LastFmRateLimitGate(),
            cacheDao = cacheDao,
        )
        val began = System.currentTimeMillis()
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { c.getTrackInfo("a", "t", proxyOnly = true) }
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
        job.cancel()
        job.join()
        assertThat(System.currentTimeMillis() - began).isLessThan(5_000L)
        // The call itself was cancelled (OkHttp's thread let go too)
        val until = System.currentTimeMillis() + 5_000
        while (!cancelled && System.currentTimeMillis() < until) Thread.sleep(10)
        assertThat(cancelled).isTrue()
    }

    @Test fun `no proxy, nothing sent`() = runTest {
        val canned = Canned(proxyHost, 200, "{}")
        assertThat(client(canned, proxy = false).getTrackInfo("a", "t", proxyOnly = true).exceptionOrNull()).isInstanceOf(LastFmProxyMissingException::class.java)
        assertThat(canned.proxyCalls + canned.directCalls).isEqualTo(0)
    }

    @Test fun `a user's own read can't be proxy only`() = runTest {
        val canned = Canned(proxyHost, 200, "{}")
        assertThat(client(canned).getTrackInfo("a", "t", username = "me", proxyOnly = true).exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(canned.proxyCalls + canned.directCalls).isEqualTo(0)
    }
}
