package com.stash.core.auth.spotify

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Asking Spotify whose account a sign-in is must not hang a sign-in: someone is watching a spinner.
 * So there are limits on every request, the request ends with its caller, and only a sync (never a
 * sign-in) may download the multi-MB web player bundle after a rotated hash.
 */
class SpotifyProfileLookupTest {

    private val rotatedHash = "a".repeat(64)
    private val requests = CopyOnWriteArrayList<String>()

    private val miss = """{"errors":[{"message":"PersistedQueryNotFound"}]}"""
    private val answer = """{"data":{"me":{"profile":{"name":"Rawn","username":"rawnclark"}}}}"""
    private val rawn = SpotifyProfile(userId = "rawnclark", displayName = "Rawn")

    /** Spotify, whose `profileAttributes` hash has rotated: the seed misses, the bundle has the new one. */
    private fun spotify(onQuery: (Call) -> Unit = {}) = SpotifyAuthManager(
        OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            requests += url
            val body = when {
                "pathfinder" in url -> {
                    onQuery(chain.call())
                    if (rotatedHash in url) answer else miss
                }
                "web-player.abc.js" in url -> """new X("profileAttributes","query","$rotatedHash",null)"""
                else -> """<script src="https://cdn.example/web-player/web-player.abc.js"></script>"""
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody())
                .build()
        }.build(),
    )

    private val bundleDownloads get() = requests.count { "/web-player/" in it }

    @Test fun `a sign-in never downloads the bundle over a rotated hash`() = runBlocking {
        val profile = spotify().lookUpProfile("token", "client-token", rescrapeOnMiss = false)

        assertNull(profile)
        assertEquals(0, bundleDownloads)
    }

    @Test fun `a sync downloads the bundle and retries with the current hash`() = runBlocking {
        val profile = spotify().lookUpProfile("token", "client-token", rescrapeOnMiss = true)

        assertEquals(rawn, profile)
        assertEquals(1, bundleDownloads)
    }

    @Test fun `a sign-in uses a hash this process already scraped, without downloading again`() = runBlocking {
        val spotify = spotify()
        spotify.lookUpProfile("token", "client-token", rescrapeOnMiss = true)

        val profile = spotify.lookUpProfile("token", "client-token", rescrapeOnMiss = false)

        assertEquals(rawn, profile)
        assertEquals(1, bundleDownloads)
    }

    @Test(timeout = 10_000)
    fun `a stalled request ends with its caller, and is cancelled`() {
        val stalled = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val spotify = spotify(
            onQuery = {
                call.set(it)
                stalled.await()
            },
        )

        try {
            val profile = runBlocking {
                withTimeoutOrNull(200) { spotify.lookUpProfile("token", "client-token", rescrapeOnMiss = false) }
            }

            assertNull(profile)
            assertTrue("the call was cancelled", call.get().isCanceled())
        } finally {
            stalled.countDown()
        }
    }

    @Test fun `the profile request has one limit for the whole call`() {
        assertEquals(10_000, spotify().profileHttp.callTimeoutMillis)
    }

    @Test fun `the client token request has connect and read limits`() {
        // Opening does no I/O. HttpURLConnection's default for both is 0: wait for ever.
        val connection = spotify().openClientTokenConnection()

        assertEquals(15_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
    }
}
