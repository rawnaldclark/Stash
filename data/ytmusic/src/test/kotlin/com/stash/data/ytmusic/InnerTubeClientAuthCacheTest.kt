package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class InnerTubeClientAuthCacheTest {

    private lateinit var server: MockWebServer
    private lateinit var token: TokenManager
    private lateinit var cookies: YouTubeCookieHelper
    private lateinit var client: InnerTubeClient

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        token = mock()
        cookies = mock()
        runBlocking {
            whenever(token.getYouTubeCookie()).thenReturn("SAPISID=fake; __Secure-3PAPISID=fake")
        }
        whenever(cookies.extractSapiSid(any())).thenReturn("fake")
        whenever(cookies.generateAuthHeader(any())).thenReturn("SAPISIDHASH fake")
        client = InnerTubeClient(OkHttpClient(), token, cookies)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    @Test fun `without session, getYouTubeCookie called per request`() {
        runBlocking {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }
            repeat(3) { client.executeRequestWithStatusForTest(server.url("/x").toString()) }
            verify(token, times(3)).getYouTubeCookie()
        }
    }

    @Test fun `inside session, getYouTubeCookie called once`() {
        runBlocking {
            repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }
            client.beginSyncSession()
            try {
                repeat(3) { client.executeRequestWithStatusForTest(server.url("/x").toString()) }
            } finally {
                client.endSyncSession()
            }
            verify(token, times(1)).getYouTubeCookie()
        }
    }

    @Test fun `401 inside session clears cache and next call re-resolves`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            client.beginSyncSession()
            try {
                client.executeRequestWithStatusForTest(server.url("/x").toString()) // 200, populates cache (1 resolve)
                client.executeRequestWithStatusForTest(server.url("/x").toString()) // 401, clears cache
                client.executeRequestWithStatusForTest(server.url("/x").toString()) // 200, re-resolves (2 resolves total)
            } finally {
                client.endSyncSession()
            }
            verify(token, times(2)).getYouTubeCookie()
        }
    }

    @Test fun `endSyncSession clears cache`() {
        runBlocking {
            repeat(2) { server.enqueue(MockResponse().setResponseCode(200).setBody("{}")) }
            client.beginSyncSession()
            client.executeRequestWithStatusForTest(server.url("/x").toString())
            client.endSyncSession()
            client.executeRequestWithStatusForTest(server.url("/x").toString())
            verify(token, times(2)).getYouTubeCookie()
        }
    }
}
