package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Verifies that [InnerTubeClient.executeRequestWithStatus] surfaces the HTTP
 * status code so the continuation-retry policy can distinguish 4xx (no retry)
 * from 5xx/network (retry).
 *
 * The wrapper [InnerTubeClient.executeRequest] continues to drop the status
 * code, preserving its existing callers' contracts.
 */
class InnerTubeClientStatusTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun client(): InnerTubeClient {
        val token = mock<TokenManager>()
        val cookies = mock<YouTubeCookieHelper>()
        runBlocking { whenever(token.getYouTubeCookie()).thenReturn(null) }
        return InnerTubeClient(OkHttpClient(), token, cookies)
    }

    @Test fun `200 OK exposes status 200 and body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        val outcome = client().executeRequestWithStatusForTest(server.url("/x").toString())
        assertEquals(200, outcome.statusCode)
        assertNotNull(outcome.body)
    }

    @Test fun `404 exposes status 404 and null body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val outcome = client().executeRequestWithStatusForTest(server.url("/x").toString())
        assertEquals(404, outcome.statusCode)
        assertNull(outcome.body)
    }

    @Test fun `503 exposes status 503 and null body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val outcome = client().executeRequestWithStatusForTest(server.url("/x").toString())
        assertEquals(503, outcome.statusCode)
        assertNull(outcome.body)
    }
}
