package com.stash.data.ytmusic

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import com.stash.data.ytmusic.potoken.PoTokenMinter
import com.stash.data.ytmusic.potoken.PoTokenPair
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * YouTube's GVS PO-token wall: a direct audio URL serves ~1 MB and then 403s
 * unless it carries `pot=<token>` bound to the visitor id the player request
 * was made under. So the client must (1) carry ONE visitor id across player
 * requests, (2) mint tokens against it, and (3) hand the stream token back
 * with the player response so the URL can be stamped before the tail probe.
 */
class InnerTubeClientVisitorTest {

    private lateinit var server: MockWebServer
    private lateinit var token: TokenManager
    private lateinit var cookies: YouTubeCookieHelper

    private val okResponse = """{"responseContext":{"visitorData":"CgtWSVNJVE9S"},""" +
        """"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[""" +
        """{"mimeType":"audio/webm; codecs=\"opus\"","bitrate":140000,""" +
        """"url":"https://rr1.googlevideo.com/videoplayback?id=1","contentLength":"1000"}]}}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        token = mock()
        cookies = mock()
        runBlocking { whenever(token.getYouTubeCookie()).thenReturn(null) }
    }

    @After fun tearDown() { server.shutdown() }

    /** The judge a real play uses: the first direct audio URL, as-is. */
    private val firstDirectUrl: suspend (JsonObject, String?) -> String? = { response, _ ->
        response["streamingData"]?.jsonObject?.get("adaptiveFormats")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
    }

    /** A judge that records the stream token it was offered. */
    private class Recording : suspend (JsonObject, String?) -> String? {
        var offeredPot: String? = "unset"
        override suspend fun invoke(response: JsonObject, streamPot: String?): String? {
            offeredPot = streamPot
            return response["streamingData"]?.jsonObject?.get("adaptiveFormats")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.content
        }
    }

    private fun client(minter: PoTokenMinter = PoTokenMinter.None): InnerTubeClient =
        InnerTubeClient(OkHttpClient(), token, cookies, minter).also {
            it.apiBaseOverride = server.url("/youtubei/v1").toString().trimEnd('/')
        }

    @Test
    fun `the visitor id from one player response is sent on the next request`() = runBlocking {
        val client = client()
        server.enqueue(MockResponse().setResponseCode(200).setBody(okResponse))
        server.enqueue(MockResponse().setResponseCode(200).setBody(okResponse))

        client.player("vid1", InnerTubeVariant.ANDROID_VR)
        val first = server.takeRequest()
        client.player("vid2", InnerTubeVariant.ANDROID_VR)
        val second = server.takeRequest()

        assertThat(first.getHeader("X-Goog-Visitor-Id")).isNull()
        assertThat(second.getHeader("X-Goog-Visitor-Id")).isEqualTo("CgtWSVNJVE9S")
        assertThat(second.body.readUtf8()).contains("\"visitorData\":\"CgtWSVNJVE9S\"")
    }

    @Test
    fun `playerForAudio hands back the session token for the stream url`() = runBlocking {
        val minted = mutableListOf<Pair<String, String>>()
        val minter = PoTokenMinter { videoId, sessionId ->
            minted += videoId to sessionId
            PoTokenPair(playerToken = "PLAYER", sessionToken = "SESSION")
        }
        val client = client(minter)
        client.visitorData = "CgtWSVNJVE9S"
        server.enqueue(MockResponse().setResponseCode(200).setBody(okResponse))

        val judge = Recording()
        val result = client.playerForAudio("vid1", judge)

        assertThat(judge.offeredPot).isEqualTo("SESSION")
        assertThat(result?.url).isEqualTo("https://rr1.googlevideo.com/videoplayback?id=1")
        assertThat(minted).containsExactly("vid1" to "CgtWSVNJVE9S")
        // A mobile client takes no player token in its body; only the URL gets the session token.
        assertThat(server.takeRequest().body.readUtf8()).doesNotContain("serviceIntegrityDimensions")
    }

    @Test
    fun `a token-accepting variant carries the player token in its request`() = runBlocking {
        val client = client()
        client.visitorData = "CgtWSVNJVE9S"
        server.enqueue(MockResponse().setResponseCode(200).setBody(okResponse))

        client.player("vid1", InnerTubeVariant.WEB_REMIX, PoTokenPair(playerToken = "PLAYER", sessionToken = "SESSION"))

        assertThat(server.takeRequest().body.readUtf8())
            .contains("\"serviceIntegrityDimensions\":{\"poToken\":\"PLAYER\"}")
    }

    @Test
    fun `no visitor id means no minting and a token-less response`() = runBlocking {
        val minter = PoTokenMinter { _, _ -> error("must not mint without a visitor id") }
        val client = client(minter)
        server.enqueue(MockResponse().setResponseCode(200).setBody(okResponse))

        val judge = Recording()
        val result = client.playerForAudio("vid1", judge)

        assertThat(judge.offeredPot).isNull()
        assertThat(result?.url).isNotNull()
    }

    @Test
    fun `a minter that fails is a token-less success, never an error`() = runBlocking {
        val minter = PoTokenMinter { _, _ -> null }
        val client = client(minter)
        client.visitorData = "CgtWSVNJVE9S"
        server.enqueue(MockResponse().setResponseCode(200).setBody(okResponse))

        val judge = Recording()
        val result = client.playerForAudio("vid1", judge)

        assertThat(judge.offeredPot).isNull()
        assertThat(result?.url).isNotNull()
    }
}
