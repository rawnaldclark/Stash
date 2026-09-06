package com.stash.data.ytmusic

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * YouTube blocks clients one policy change at a time (2026-09-06, anonymous,
 * on the Pixel 6: every ANDROID_VR build answered "Sign in to confirm you're
 * not a bot", the Music clients "Please sign in", while VISIONOS served
 * direct URLs). So the audio order carries more than two clients, and the
 * one that last served goes first, so a working day costs one request, not
 * a hunt through the blocked ones.
 */
class InnerTubeClientVariantOrderTest {

    private lateinit var server: MockWebServer
    private lateinit var client: InnerTubeClient

    private val blocked = """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm you're not a bot"}}"""
    private val served = """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[""" +
        """{"mimeType":"audio/webm; codecs=\"opus\"","bitrate":140000,"url":"https://rr1.googlevideo.com/videoplayback?id=1"}]}}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val token: TokenManager = mock()
        val cookies: YouTubeCookieHelper = mock()
        runBlocking { whenever(token.getYouTubeCookie()).thenReturn(null) }
        client = InnerTubeClient(OkHttpClient(), token, cookies).also {
            it.apiBaseOverride = server.url("/youtubei/v1").toString().trimEnd('/')
        }
    }

    @After fun tearDown() { server.shutdown() }

    @Test
    fun `the audio order keeps the Apple fallbacks behind the mobile clients`() {
        val names = InnerTubeClient.AUDIO_VARIANT_ORDER.map { it.name }
        assertThat(names).containsAtLeast("ANDROID_VR", "IOS", "VISIONOS", "IPADOS").inOrder()
    }

    @Test
    fun `the client that last served goes first next time`() = runBlocking {
        // First play: the two mobile clients are blocked, VISIONOS serves.
        server.enqueue(MockResponse().setResponseCode(200).setBody(blocked))
        server.enqueue(MockResponse().setResponseCode(200).setBody(blocked))
        server.enqueue(MockResponse().setResponseCode(200).setBody(served))
        val first = client.playerForAudio("vid1")
        assertThat(first?.response?.get("streamingData")).isNotNull()
        repeat(3) { server.takeRequest() }

        // Second play: VISIONOS is asked first, and one request is all it takes.
        server.enqueue(MockResponse().setResponseCode(200).setBody(served))
        val second = client.playerForAudio("vid2")

        assertThat(second?.response?.get("streamingData")).isNotNull()
        val request = server.takeRequest()
        assertThat(request.getHeader("X-YouTube-Client-Name")).isEqualTo(InnerTubeVariant.VISIONOS.clientNameId)
        assertThat(server.requestCount).isEqualTo(4)
    }
}
