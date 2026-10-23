package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.YouTubeIdentity
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** #664: signed-in requests act as the picked Brand Account profile. */
class InnerTubeClientBrandAccountTest {

    private lateinit var server: MockWebServer
    private val brand = "112233445566778899001"

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client(identity: YouTubeIdentity?, signedIn: Boolean = true): InnerTubeClient {
        val token = mock<TokenManager>()
        val cookies = mock<YouTubeCookieHelper>()
        runBlocking {
            whenever(token.getYouTubeCookie()).thenReturn(if (signedIn) "SAPISID=fake" else null)
            whenever(token.getYouTubeIdentity()).thenReturn(identity)
        }
        whenever(cookies.extractSapiSid(any())).thenReturn("fake")
        whenever(cookies.generateAuthHeader(any())).thenReturn("SAPISIDHASH fake")
        return InnerTubeClient(OkHttpClient(), token, cookies)
    }

    private val body = buildJsonObject {
        putJsonObject("context") { putJsonObject("client") { put("clientName", "WEB_REMIX") } }
        put("browseId", "FEmusic_liked_playlists")
    }

    private suspend fun send(client: InnerTubeClient, path: String = "/browse", asProfile: Boolean = true) {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.executeRequestWithStatus(server.url(path).toString(), body, null, InnerTubeVariant.WEB_REMIX, asProfile)
    }

    @Test fun `a picked brand profile goes on the header, the context and its owner's index`() = runBlocking {
        send(client(YouTubeIdentity(brand, authUser = 1)))
        val r = server.takeRequest()
        assertEquals(brand, r.getHeader("X-Goog-PageId"))
        assertEquals("1", r.getHeader("X-Goog-AuthUser"))
        val sent = r.body.readUtf8()
        assertTrue(sent, sent.contains(""""onBehalfOfUser":"$brand""""))
        assertTrue("the rest of the body survives", sent.contains("FEmusic_liked_playlists"))
    }

    @Test fun `the account's own profile sends what it always did`() = runBlocking {
        send(client(YouTubeIdentity()))
        val r = server.takeRequest()
        assertNull(r.getHeader("X-Goog-PageId"))
        assertEquals("0", r.getHeader("X-Goog-AuthUser"))
        assertFalse(r.body.readUtf8().contains("onBehalfOfUser"))
    }

    @Test fun `a mocked token manager with no identity is the account's own`() = runBlocking {
        send(client(null))
        assertNull(server.takeRequest().getHeader("X-Goog-PageId"))
    }

    @Test fun `player requests never carry the brand id`() = runBlocking {
        send(client(YouTubeIdentity(brand, authUser = 1)), path = "/player")
        val r = server.takeRequest()
        assertNull(r.getHeader("X-Goog-PageId"))
        assertFalse(r.body.readUtf8().contains("onBehalfOfUser"))
    }

    @Test fun `listing the profiles asks as the account itself`() = runBlocking {
        send(client(YouTubeIdentity(brand, authUser = 1)), asProfile = false)
        val r = server.takeRequest()
        assertNull(r.getHeader("X-Goog-PageId"))
        assertEquals("0", r.getHeader("X-Goog-AuthUser"))
    }

    @Test fun `signed out, nothing about a profile is sent`() = runBlocking {
        send(client(YouTubeIdentity(brand, authUser = 1), signedIn = false))
        val r = server.takeRequest()
        assertNull(r.getHeader("X-Goog-PageId"))
        assertFalse(r.body.readUtf8().contains("onBehalfOfUser"))
    }
}
