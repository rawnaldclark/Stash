package com.stash.core.data.youtube

import com.stash.core.auth.youtube.YouTubeCookieHelper
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch-history ping carries the user's YouTube cookies and a SAPISIDHASH
 * header, and its URL comes from the /player response. Only an https URL on
 * youtube.com (or a subdomain), on the default port and without user info,
 * may receive it; for anything else no request is made at all.
 */
class OkHttpPingSubmitterTest {

    /** Every request the client was asked to send. Nothing goes to the network. */
    private val sent = mutableListOf<Request>()

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            sent += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .body(ByteArray(0).toResponseBody(null))
                .build()
        }
        .build()

    private val submitter = OkHttpPingSubmitter(client, YouTubeCookieHelper())

    private val cookies = "SAPISID=abc123; LOGIN_INFO=xyz"

    @Test
    fun `a YouTube stats URL gets the ping, with the credentials and the playback params`() = runTest {
        val allowed = listOf(
            "https://s.youtube.com/api/stats/playback?docid=dQw4w9WgXcQ&ns=yt",
            "https://music.youtube.com/api/stats/playback?docid=dQw4w9WgXcQ",
            "https://www.youtube.com/api/stats/playback?docid=dQw4w9WgXcQ",
            "https://youtube.com/api/stats/playback?docid=dQw4w9WgXcQ",
            "https://S.YOUTUBE.COM/api/stats/playback?docid=dQw4w9WgXcQ",
            "https://s.youtube.com:443/api/stats/playback?docid=dQw4w9WgXcQ",
        )

        allowed.forEach { assertEquals(204, submitter.submit(it, cookies, "abc123")) }

        assertEquals(allowed.size, sent.size)
        sent.forEach { request ->
            assertTrue(request.header("Authorization").orEmpty().startsWith("SAPISIDHASH "))
            assertEquals(cookies, request.header("Cookie"))
            assertEquals("dQw4w9WgXcQ", request.url.queryParameter("docid"))
            assertEquals("2", request.url.queryParameter("ver"))
            assertEquals("WEB_REMIX", request.url.queryParameter("c"))
            assertEquals(16, request.url.queryParameter("cpn").orEmpty().length)
        }
    }

    @Test
    fun `any other URL gets no request at all`() = runTest {
        val refused = listOf(
            "https://other.example/api/stats/playback?docid=dQw4w9WgXcQ",
            "http://s.youtube.com/api/stats/playback?docid=dQw4w9WgXcQ",
            "https://s.youtube.com.other.example/api/stats/playback",
            "https://youtube.com.other.example/api/stats/playback",
            "https://notyoutube.com/api/stats/playback",
            "https://other.example\\@s.youtube.com/api/stats/playback",
            "https://other.example\\.s.youtube.com/api/stats/playback",
            "https://user@s.youtube.com/api/stats/playback",
            "https://user:pass@s.youtube.com/api/stats/playback",
            "https://s.youtube.com:8443/api/stats/playback",
            "https://youtubei.googleapis.com/api/stats/playback?docid=dQw4w9WgXcQ",
            "https://127.0.0.1/api/stats/playback",
            "ftp://s.youtube.com/api/stats/playback",
            "//s.youtube.com/api/stats/playback",
            "s.youtube.com/api/stats/playback",
            "",
        )

        refused.forEach { url ->
            val result = runCatching { submitter.submit(url, cookies, "abc123") }
            assertTrue("'$url' must be refused, got $result", result.isFailure)
        }

        assertTrue("nothing may be sent, but sent to ${sent.map { it.url }}", sent.isEmpty())
    }

    @Test
    fun `the ping never follows a redirect`() {
        // The credentials go only to the URL that was checked.
        assertFalse(submitter.client.followRedirects)
        assertFalse(submitter.client.followSslRedirects)
        // The shared client's interceptors (and its pool) still apply.
        assertEquals(client.interceptors, submitter.client.interceptors)
    }
}
