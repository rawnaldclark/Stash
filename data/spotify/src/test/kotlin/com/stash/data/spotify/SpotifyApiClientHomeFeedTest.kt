package com.stash.data.spotify

import com.stash.core.auth.TokenManager
import com.stash.core.auth.spotify.SpotifyAuthManager
import com.stash.core.model.SyncResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #471: the web-player build 981dd70a (2026-09-06) changed the `home`
 * query's playlist cards: `ownerV2.data` now carries only `name` and `uri`
 * ("spotify:user:spotify"), no `username`/`id`. Read straight off a Pixel 6,
 * the feed listed Release Radar, Discover Weekly and Daily Mix 1 and the
 * sync stored none of them — every card was skipped as "no owner data".
 */
class SpotifyApiClientHomeFeedTest {

    private val fixture = this::class.java.classLoader!!
        .getResourceAsStream("fixtures/home_feed_981dd70a.json")!!
        .bufferedReader().use { it.readText() }

    private val tokenManager = mockk<TokenManager> {
        coEvery { getSpotifyAccessToken() } returns "access-token"
        coEvery { getSpotifyClientId() } returns "client-id"
    }

    private val authManager = mockk<SpotifyAuthManager> {
        every { getClientVersion() } returns "1.2.3.4.g0"
        coEvery { getClientToken("client-id") } returns "client-token"
        every { getSpT() } returns null
    }

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
            .body(fixture.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val client = SpotifyApiClient(http, tokenManager, authManager)

    @Test fun `a mix card whose owner is only a uri is still a Spotify mix`() = runBlocking {
        val result = client.getDailyMixes()
        val names = (result as? SyncResult.Success)?.data?.map { it.name }
        assertEquals(listOf("Release Radar", "Discover Weekly", "Daily Mix 1"), names)
    }

    @Test fun `a friend's playlist on the home feed is not a mix`() = runBlocking {
        val result = client.getDailyMixes()
        val names = (result as? SyncResult.Success)?.data?.map { it.name }.orEmpty()
        assertEquals(false, "conundrum ^#" in names)
    }
}
