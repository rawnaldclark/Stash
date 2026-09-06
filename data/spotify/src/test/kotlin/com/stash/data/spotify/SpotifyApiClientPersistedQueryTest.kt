package com.stash.data.spotify

import com.stash.core.auth.TokenManager
import com.stash.core.auth.spotify.SpotifyAuthConfig
import com.stash.core.auth.spotify.SpotifyAuthManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #471: Spotify rotated the `home` persisted-query hash and the 0.9.102
 * sync silently stopped discovering Daily Mixes and Release Radar. A query
 * answered with `PersistedQueryNotFound` must re-scrape its hash from the
 * web-player bundle, retry once, and keep the fresh hash for the process.
 */
class SpotifyApiClientPersistedQueryTest {

    private val hashesSent = mutableListOf<String>()
    private val variablesSent = mutableListOf<String>()

    private val tokenManager = mockk<TokenManager> {
        coEvery { getSpotifyAccessToken() } returns "access-token"
        coEvery { getSpotifyClientId() } returns "client-id"
    }

    private val authManager = mockk<SpotifyAuthManager> {
        every { getClientVersion() } returns "1.2.3.4.g0"
        coEvery { getClientToken("client-id") } returns "client-token"
        every { getSpT() } returns null
        every { scrapePersistedQueryHash("home", SpotifyAuthConfig.HASH_HOME) } returns FRESH
    }

    /** Pathfinder: the seeded hash is gone, the fresh one answers. */
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val extensions = chain.request().url.queryParameter("extensions").orEmpty()
        val hash = Regex(""""sha256Hash":"([0-9a-f]{64})"""").find(extensions)!!.groupValues[1]
        hashesSent += hash
        variablesSent += chain.request().url.queryParameter("variables").orEmpty()
        val (code, body) = if (hash == FRESH) {
            200 to """{"data":{"home":{"sectionContainer":{"sections":{"items":[]}}}}}"""
        } else {
            400 to """{"errors":[{"message":"PersistedQueryNotFound","extensions":{"code":"PERSISTED_QUERY_NOT_FOUND"}}]}"""
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val client = SpotifyApiClient(http, tokenManager, authManager)

    @Test fun `a rotated hash is re-scraped and the query retried once`() = runBlocking {
        client.getDailyMixes()
        assertEquals(listOf(SpotifyAuthConfig.HASH_HOME, FRESH), hashesSent)
        // The 981dd70a bundle's home query declares this variable; the retry must carry it.
        assertTrue(variablesSent.last().contains("\"includeEpisodeContentRatingsV2\":false"))
    }

    @Test fun `the re-scraped hash is remembered for the next query`() = runBlocking {
        client.getDailyMixes()
        hashesSent.clear()
        client.getDailyMixes()
        assertEquals(listOf(FRESH), hashesSent)
        verify(exactly = 1) { authManager.scrapePersistedQueryHash("home", any()) }
    }

    private companion object {
        const val FRESH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef" // any hash the seed is not
    }
}
