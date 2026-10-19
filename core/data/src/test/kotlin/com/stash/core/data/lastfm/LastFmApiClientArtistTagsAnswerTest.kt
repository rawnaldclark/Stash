package com.stash.core.data.lastfm

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.LastFmCacheDao
import com.stash.core.data.db.entity.LastFmCacheEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

/**
 * `getArtistTopTagsAnswer` says where an answer came from, and rate limiting fails in a way
 * callers can tell apart (ArtistGenreWorker writes nothing for it and doesn't count a cache hit
 * as proof Last.fm is up).
 */
class LastFmApiClientArtistTagsAnswerTest {

    private class Canned(private val code: Int, private val body: String) : Interceptor {
        var calls = 0
        override fun intercept(chain: Interceptor.Chain): Response {
            calls++
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("canned")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private val tagsJson = """{"toptags":{"tag":[{"name":"Hip-Hop","count":100},{"name":"rap","count":50}]}}"""

    private fun client(
        canned: Canned,
        cached: LastFmCacheEntity? = null,
        gate: LastFmRateLimitGate = LastFmRateLimitGate(),
    ): LastFmApiClient {
        val cacheDao = mockk<LastFmCacheDao>(relaxed = true)
        coEvery { cacheDao.get(any()) } returns cached
        return LastFmApiClient(
            okHttpClient = OkHttpClient.Builder().addInterceptor(canned).build(),
            credentials = LastFmCredentials(apiKey = "key", apiSecret = "secret"),
            rateLimitGate = gate,
            cacheDao = cacheDao,
        )
    }

    @Test fun `a network answer is not from the cache`() = runTest {
        val canned = Canned(200, tagsJson)

        val answer = client(canned).getArtistTopTagsAnswer("Drake").getOrThrow()

        assertThat(answer.fromCache).isFalse()
        assertThat(answer.tags.map { it.name }).containsExactly("hip-hop", "rap").inOrder()
        assertThat(canned.calls).isEqualTo(1)
    }

    @Test fun `a fresh cached answer is marked as from the cache and sends nothing`() = runTest {
        val canned = Canned(500, "")
        val cached = LastFmCacheEntity(cacheKey = "k", json = tagsJson, fetchedAt = System.currentTimeMillis())

        val answer = client(canned, cached).getArtistTopTagsAnswer("Drake").getOrThrow()

        assertThat(answer.fromCache).isTrue()
        assertThat(answer.tags).hasSize(2)
        assertThat(canned.calls).isEqualTo(0)
    }

    @Test fun `getArtistTopTags still returns just the tags`() = runTest {
        val tags = client(Canned(200, tagsJson)).getArtistTopTags("Drake").getOrThrow()
        assertThat(tags.map { it.name }).containsExactly("hip-hop", "rap").inOrder()
    }

    @Test fun `HTTP 429 is a rate limit`() = runTest {
        val result = client(Canned(429, "")).getArtistTopTagsAnswer("Drake")

        assertThat(result.exceptionOrNull()).isInstanceOf(LastFmRateLimitedException::class.java)
        assertThat(result.isLastFmRateLimited()).isTrue()
        // Still an IllegalStateException and a transient failure for the older callers.
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(result.isTransientLastFmFailure()).isTrue()
    }

    @Test fun `Last_fm error 29 is a rate limit`() = runTest {
        val result = client(Canned(200, """{"error":29,"message":"Rate limit exceeded"}""")).getArtistTopTagsAnswer("Drake")
        assertThat(result.isLastFmRateLimited()).isTrue()
    }

    @Test fun `every key throttled is a rate limit and sends nothing`() = runTest {
        val gate = LastFmRateLimitGate().apply { recordRateLimited("key", System.currentTimeMillis()) }
        val canned = Canned(200, tagsJson)

        val result = client(canned, gate = gate).getArtistTopTagsAnswer("Drake")

        assertThat(result.isLastFmRateLimited()).isTrue()
        assertThat(canned.calls).isEqualTo(0)
    }

    @Test fun `not found and server errors are not rate limits`() = runTest {
        assertThat(
            client(Canned(200, """{"error":6,"message":"not found"}""")).getArtistTopTagsAnswer("x").isLastFmRateLimited(),
        ).isFalse()
        assertThat(client(Canned(500, "")).getArtistTopTagsAnswer("x").isLastFmRateLimited()).isFalse()
    }
}
