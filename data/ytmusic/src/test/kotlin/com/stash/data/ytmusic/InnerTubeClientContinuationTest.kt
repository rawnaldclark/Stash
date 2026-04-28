package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Verifies the new browse(continuation) overload posts to the right URL
 * with the right query params and an empty-browseId body, then parses the
 * response.
 */
class InnerTubeClientContinuationTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `browse(continuation) appends ctoken and continuation params and omits browseId`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

            val token = mock<TokenManager>()
            val cookies = mock<YouTubeCookieHelper>()
            whenever(token.getYouTubeCookie()).thenReturn(null)

            val client = InnerTubeClient(OkHttpClient(), token, cookies)
            val response = client.browseContinuationForTest(
                continuation = "ABC123",
                baseUrl = server.url("/youtubei/v1/browse").toString().removeSuffix("/youtubei/v1/browse"),
            )

            assertNotNull(response)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            val path = recorded.path ?: ""
            assertTrue("ctoken missing in $path", path.contains("ctoken=ABC123"))
            assertTrue("continuation missing in $path", path.contains("continuation=ABC123"))
            assertTrue("type=next missing in $path", path.contains("type=next"))
            val body = recorded.body.readUtf8()
            assertTrue("browseId should be absent for continuation", !body.contains("\"browseId\""))
            assertTrue("context should be present", body.contains("\"context\""))
        }
}
