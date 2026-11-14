package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.YouTubeIdentity
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * YouTube keeps every search made with the user's login in their YouTube search
 * history, and Stash searches for every song it matches: with YouTube connected,
 * the search bar of the user's own YouTube app filled with Stash's lookups. A
 * search never needs the login (signed-out users run every one of them), so it
 * goes out signed out; what does need it (library sync, likes) still sends it.
 */
class InnerTubeClientSignedOutSearchTest {

    private lateinit var server: MockWebServer
    private lateinit var token: TokenManager
    private lateinit var client: InnerTubeClient

    /** A Brand Account profile, so a leak of the profile shows up too. */
    private val brand = "112233445566778899001"

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        token = mock()
        val cookies = mock<YouTubeCookieHelper>()
        runBlocking {
            whenever(token.getYouTubeCookie()).thenReturn("SAPISID=fake; LOGIN_INFO=fake")
            whenever(token.getYouTubeIdentity()).thenReturn(YouTubeIdentity(brand, authUser = 1))
        }
        whenever(cookies.extractSapiSid(any())).thenReturn("fake")
        whenever(cookies.generateAuthHeader(any())).thenReturn("SAPISIDHASH fake")
        client = InnerTubeClient(OkHttpClient(), token, cookies).also {
            it.apiBaseOverride = server.url("/youtubei/v1").toString().trimEnd('/')
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun answer(code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody("{}"))

    /** Nothing in [request] that ties it to the user's account. */
    private fun assertSignedOut(request: RecordedRequest) {
        assertNull("cookie", request.getHeader("Cookie"))
        assertNull("SAPISIDHASH", request.getHeader("Authorization"))
        assertNull("session index", request.getHeader("X-Goog-AuthUser"))
        assertNull("Brand profile header", request.getHeader("X-Goog-PageId"))
        val body = request.body.readUtf8()
        assertFalse("Brand profile in the body: $body", body.contains("onBehalfOfUser"))
        // A signed-out web client identifies itself with the public API key instead.
        assertNotNull("public API key", request.requestUrl?.queryParameter("key"))
    }

    /** [request] went out as the signed-in user and the picked profile. */
    private fun assertSignedIn(request: RecordedRequest) {
        assertEquals("SAPISID=fake; LOGIN_INFO=fake", request.getHeader("Cookie"))
        assertEquals("SAPISIDHASH fake", request.getHeader("Authorization"))
        assertEquals("1", request.getHeader("X-Goog-AuthUser"))
        assertNull("no API key on a signed-in request", request.requestUrl?.queryParameter("key"))
    }

    @Test fun `a search goes out signed out though a login is stored`() = runBlocking {
        answer()
        assertNotNull(client.search("Radiohead Creep"))
        val request = server.takeRequest()
        assertTrue(request.path!!, request.path!!.startsWith("/youtubei/v1/search"))
        assertSignedOut(request)
    }

    @Test fun `a search that keeps its status goes out signed out`() = runBlocking {
        answer()
        assertEquals(200, client.searchWithStatus("Bon Iver", params = "artists").statusCode)
        assertSignedOut(server.takeRequest())
    }

    @Test fun `the canonical-id lookup behind matching, radio and scrobbling goes out signed out`() = runBlocking {
        answer()
        // "{}" is an answer with no songs on it: the search answered, signed out.
        assertEquals(CanonicalLookup.NoMatch, client.searchCanonicalLookup("Radiohead", "Creep", explicit = true))
        assertSignedOut(server.takeRequest())
    }

    @Test fun `a search does not even read the stored login`() = runBlocking<Unit> {
        answer()
        client.search("Radiohead Creep")
        verify(token, never()).getYouTubeCookie()
        verify(token, never()).getYouTubeIdentity()
    }

    @Test fun `the library still reads as the user and the picked profile`() = runBlocking {
        answer()
        client.browse("FEmusic_liked_playlists")
        val request = server.takeRequest()
        assertSignedIn(request)
        assertEquals(brand, request.getHeader("X-Goog-PageId"))
        assertTrue(request.body.readUtf8().contains(""""onBehalfOfUser":"$brand""""))
    }

    @Test fun `liking a song still goes out as the user`() = runBlocking {
        answer()
        assertTrue(client.sendLikeActionForTest(server.url("/youtubei/v1/like/like").toString(), "vid"))
        assertSignedIn(server.takeRequest())
    }

    @Test fun `in a sync, searches between library reads stay signed out and the reads stay signed in`() = runBlocking<Unit> {
        repeat(3) { answer() }
        client.beginSyncSession()
        try {
            client.browse("VLLM")
            client.search("Radiohead Creep")
            client.browse("VLLM")
        } finally {
            client.endSyncSession()
        }
        assertSignedIn(server.takeRequest())
        assertSignedOut(server.takeRequest())
        assertSignedIn(server.takeRequest())
        // The session's login was read once, for the first library read.
        verify(token, times(1)).getYouTubeCookie()
    }

    @Test fun `a 401 to a signed-out search leaves the sync's login alone`() = runBlocking<Unit> {
        answer()
        answer(401)
        answer()
        client.beginSyncSession()
        try {
            client.browse("VLLM")
            client.searchWithStatus("Radiohead Creep")
            client.browse("VLLM")
        } finally {
            client.endSyncSession()
        }
        // A 401 on a request that carried no login says nothing about the login.
        verify(token, times(1)).getYouTubeCookie()
    }
}
