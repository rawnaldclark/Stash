package com.stash.data.soundcloud

import kotlinx.coroutines.test.runTest
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SoundCloudRefreshTest {
    private val token = "header." + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString("""{"client_id":"test-client","exp":1}""".toByteArray()) + ".signature"
    private fun client(reply: String, code: Int = 200) = SoundCloudClient(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertEquals("https://secure.soundcloud.com/oauth/token", request.url.toString())
        assertNull(request.header("Authorization"))
        val form = request.body as FormBody
        assertEquals("test-client", form.value(0))
        assertEquals("refresh_token", form.value(1))
        assertEquals("old-refresh", form.value(2))
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("fixture")
            .code(code).body(reply.toResponseBody()).build()
    }.build())

    @Test fun `rotation uses the tokens client id and keeps credentials out of URLs and diagnostics`() = runTest {
        val c = client("""{"access_token":"new-access","refresh_token":"new-refresh"}""")
        assertEquals(1000L, c.tokenExpiresAt(token))
        val fresh = c.refreshCredentials(token, "old-refresh")
        assertEquals("new-access", fresh.accessToken)
        assertEquals("new-refresh", fresh.refreshToken)
        assertFalse(fresh.toString().contains("new-access"))
        assertNull(c.tokenExpiresAt("legacy-opaque-token"))
    }

    @Test fun `incomplete rotation responses are rejected`() = runTest {
        try {
            client("""{"access_token":"new-access"}""").refreshCredentials(token, "old-refresh")
            fail("Missing rotation secret accepted")
        } catch (_: SoundCloudException) { }
    }

    @Test fun `refresh redirects are not followed with credentials`() = runTest {
        try {
            client("{}", 302).refreshCredentials(token, "old-refresh")
            fail("Redirect accepted")
        } catch (e: SoundCloudException) { assertEquals(302, e.status) }
    }
}
