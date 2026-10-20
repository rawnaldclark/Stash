package com.stash.core.data.lastfm

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.LastFmCacheDao
import com.stash.core.data.db.entity.LastFmCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Negative caching: Last.fm's final "not found" is remembered for
 * [LastFmCachedMiss.TTL_MS] under the success key, and a remembered miss costs
 * no request. Transient failures are never remembered. Also: identical
 * concurrent reads share one request, and the proxy is told the app version.
 */
class LastFmApiClientNegativeCacheTest {

    /** In-memory [LastFmCacheDao]. */
    private class FakeCacheDao : LastFmCacheDao {
        val rows = ConcurrentHashMap<String, LastFmCacheEntity>()
        override suspend fun upsert(entity: LastFmCacheEntity) { rows[entity.cacheKey] = entity }
        override suspend fun get(key: String): LastFmCacheEntity? = rows[key]
        override suspend fun prune(olderThanMs: Long) { rows.values.removeIf { it.fetchedAt < olderThanMs } }
        override suspend fun clear() = rows.clear()
    }

    /** Answers every request with [answer] (or throws it), counting and keeping requests. */
    private class Canned(var answer: (Request) -> Pair<Int, String>) : Interceptor {
        val calls = AtomicInteger(0)
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            calls.incrementAndGet()
            synchronized(requests) { requests += req }
            val (code, body) = answer(req)
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private val cache = FakeCacheDao()

    private fun client(canned: Interceptor, proxy: Boolean = true, version: String? = "0.9.118") = LastFmApiClient(
        okHttpClient = OkHttpClient.Builder().addInterceptor(canned).build(),
        credentials = LastFmCredentials(
            apiKey = "key",
            apiSecret = "secret",
            proxyUrl = if (proxy) "https://proxy.example/lastfm" else null,
            appVersion = version,
        ),
        rateLimitGate = LastFmRateLimitGate(),
        cacheDao = cache,
    )

    private val notFound = """{"error":6,"message":"Track not found","links":[]}"""
    private val found = """{"track":{"name":"t","duration":"200000","album":{"image":[{"#text":"https://img/x.png","size":"extralarge"}]}}}"""

    @Test fun `a not-found answer is remembered and the repeat sends nothing`() = runTest {
        val canned = Canned { 200 to notFound }
        val api = client(canned)

        val first = api.getTrackInfo("Unknown Artist", "AUD-20240101-WA0001")
        var cacheHit = false
        val second = api.getTrackInfo("Unknown Artist", "AUD-20240101-WA0001", onCacheHit = { cacheHit = true })

        assertThat(canned.calls.get()).isEqualTo(1)
        val e1 = first.exceptionOrNull() as LastFmApiException
        val e2 = second.exceptionOrNull() as LastFmApiException
        assertThat(e1.code).isEqualTo("6")
        assertThat(e1.fromCache).isFalse()
        assertThat(e2.code).isEqualTo("6")
        assertThat(e2.fromCache).isTrue()
        assertThat(cacheHit).isTrue()
    }

    @Test fun `a not-found sent with a 4xx and error 7 are remembered too`() = runTest {
        val canned = Canned { 404 to notFound }
        val api = client(canned)
        api.getTrackTopTags("a", "deep cut")
        api.getTrackTopTags("a", "deep cut")
        assertThat(canned.calls.get()).isEqualTo(1)

        canned.answer = { 200 to """{"error":7,"message":"Invalid resource specified"}""" }
        api.getArtistTopTags("nobody")
        val again = api.getArtistTopTags("nobody")
        assertThat(canned.calls.get()).isEqualTo(2)
        assertThat((again.exceptionOrNull() as LastFmApiException).code).isEqualTo("7")
    }

    @Test fun `the miss is keyed like a success`() = runTest {
        val api = client(Canned { 200 to notFound })
        api.getTrackInfo("A", "T")

        val params = mapOf("method" to "track.getInfo", "artist" to "A", "track" to "T", "autocorrect" to "1")
        assertThat(cache.rows.keys).containsExactly(lastFmCacheKey(params))
    }

    @Test fun `an empty track getInfo is remembered as a miss and replays as an empty info`() = runTest {
        val canned = Canned { 200 to """{}""" }
        val api = client(canned)

        api.getTrackInfo("A", "T")
        val second = api.getTrackInfo("A", "T")

        assertThat(canned.calls.get()).isEqualTo(1)
        assertThat(second.getOrNull()?.bestImageUrl).isNull()
        assertThat(cache.rows.values.single().json).contains("stash_miss")
    }

    @Test fun `a success is still cached and served`() = runTest {
        val canned = Canned { 200 to found }
        val api = client(canned)

        api.getTrackInfo("A", "T")
        val second = api.getTrackInfo("A", "T").getOrThrow()

        assertThat(canned.calls.get()).isEqualTo(1)
        assertThat(second.bestImageUrl).isEqualTo("https://img/x.png")
    }

    @Test fun `transient failures are never remembered`() = runTest {
        val transient = listOf(
            502 to """{"error":"upstream_error","status":503}""", // proxy's own error, then direct
            500 to "",
            429 to """{"error":29,"message":"Rate Limit Exceeded"}""",
            200 to """{"error":29,"message":"Rate Limit Exceeded"}""",
            200 to """{"error":11,"message":"Service Offline"}""",
            200 to """{"error":16,"message":"Temporarily unavailable"}""",
        )
        for ((code, body) in transient) {
            cache.rows.clear()
            val canned = Canned { code to body }
            // No proxy and a fresh gate each time, so a rate limit can't block the second call locally.
            val api = client(canned, proxy = false)
            api.getTrackInfo("A", "T")
            api.getTrackInfo("A", "T")
            assertThat(cache.rows).isEmpty()
            if (code != 429 && !body.contains("\"error\":29")) {
                assertThat(canned.calls.get()).isEqualTo(2)
            }
        }
    }

    @Test fun `a network failure is not remembered`() = runTest {
        val calls = AtomicInteger(0)
        val failing = Interceptor { calls.incrementAndGet(); throw IOException("no route") }
        val api = client(failing, proxy = false)

        assertThat(api.getTrackInfo("A", "T").isFailure).isTrue()
        assertThat(api.getTrackInfo("A", "T").isFailure).isTrue()

        assertThat(calls.get()).isEqualTo(2)
        assertThat(cache.rows).isEmpty()
    }

    @Test fun `a miss is believed for two weeks, not one, and then asked again`() = runTest {
        val canned = Canned { 200 to notFound }
        val api = client(canned)
        api.getTrackInfo("A", "T")
        val key = cache.rows.keys.single()
        val now = System.currentTimeMillis()

        // Older than the success TTL, inside the miss TTL: still no request.
        cache.rows[key] = cache.rows[key]!!.copy(fetchedAt = now - LastFmApiClient.CACHE_TTL_MS - 60_000)
        api.getTrackInfo("A", "T")
        assertThat(canned.calls.get()).isEqualTo(1)

        // Past the miss TTL: asked again.
        cache.rows[key] = cache.rows[key]!!.copy(fetchedAt = now - LastFmCachedMiss.TTL_MS - 60_000)
        api.getTrackInfo("A", "T")
        assertThat(canned.calls.get()).isEqualTo(2)
    }

    @Test fun `a success past its own TTL is fetched again`() = runTest {
        val canned = Canned { 200 to found }
        val api = client(canned)
        api.getTrackInfo("A", "T")
        val key = cache.rows.keys.single()
        cache.rows[key] = cache.rows[key]!!.copy(fetchedAt = System.currentTimeMillis() - LastFmApiClient.CACHE_TTL_MS - 60_000)

        api.getTrackInfo("A", "T")

        assertThat(canned.calls.get()).isEqualTo(2)
    }

    @Test fun `pruning keeps misses for their whole TTL`() {
        assertThat(LastFmApiClient.CACHE_RETENTION_MS).isAtLeast(LastFmCachedMiss.TTL_MS)
        assertThat(LastFmApiClient.CACHE_RETENTION_MS).isAtLeast(LastFmApiClient.CACHE_TTL_MS)
    }

    @Test fun `the proxy is told the app version and Last fm direct is not`() = runTest {
        val canned = Canned { 200 to found }
        client(canned).getTrackInfo("A", "T")
        assertThat(canned.requests.single().header("X-Stash-Version")).isEqualTo("0.9.118")

        cache.rows.clear()
        val direct = Canned { 200 to found }
        client(direct, proxy = false).getTrackInfo("A", "T")
        assertThat(direct.requests.single().header("X-Stash-Version")).isNull()

        cache.rows.clear()
        val noVersion = Canned { 200 to found }
        client(noVersion, version = null).getTrackInfo("A", "T")
        assertThat(noVersion.requests.single().header("X-Stash-Version")).isNull()
    }

    @Test fun `identical concurrent reads share one request`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val canned = Canned {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            200 to found
        }
        val api = client(canned)

        val first = async(Dispatchers.Default) { api.getTrackInfo("A", "T") }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        val rest = (1..3).map { async(Dispatchers.Default) { api.getTrackInfo("A", "T") } }
        Thread.sleep(300) // let the followers reach the in-flight request
        release.countDown()

        val results = (listOf(first) + rest).awaitAll()
        assertThat(canned.calls.get()).isEqualTo(1)
        results.forEach { assertThat(it.getOrThrow().bestImageUrl).isEqualTo("https://img/x.png") }
    }

    @Test fun `different reads are not coalesced`() = runBlocking {
        val canned = Canned { 200 to found }
        val api = client(canned)

        listOf("T1", "T2", "T3").map { async(Dispatchers.Default) { api.getTrackInfo("A", it) } }.awaitAll()

        assertThat(canned.calls.get()).isEqualTo(3)
    }

    @Test fun `a follower whose leader is cancelled sends its own request`() = runBlocking {
        val entered = CountDownLatch(1)
        val canned = Canned {
            if (entered.count > 0L) {
                entered.countDown()
                Thread.sleep(500) // the leader is cancelled while this blocks
            }
            200 to found
        }
        val api = client(canned)

        val leader = async(Dispatchers.Default) { api.getTrackInfo("A", "T") }
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
        val follower = async(Dispatchers.Default) { api.getTrackInfo("A", "T") }
        Thread.sleep(100)
        leader.cancel()

        assertThat(follower.await().getOrThrow().bestImageUrl).isEqualTo("https://img/x.png")
    }
}
