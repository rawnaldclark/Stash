package com.stash.data.spotify

import com.stash.core.auth.TokenManager
import com.stash.core.auth.spotify.SpotifyAuthManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The artist page bounds Spotify's photos (review on #738): an answer that stalls halfway through its body stops when
 * the caller gives up, and the request itself is dropped, instead of the caller waiting out OkHttp's timeouts.
 */
class SpotifyArtistVisualsStallTest {

    private val server = ServerSocket(0)
    private val connectionClosed = CountDownLatch(1)

    /** Answers with headers and half a body, then stalls until the client drops the connection. */
    private val serverThread = thread(isDaemon = true) {
        val socket: Socket = runCatching { server.accept() }.getOrNull() ?: return@thread
        socket.use {
            val input = it.getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().takeIf { b -> b >= 0 }?.toChar() ?: return@thread)
            it.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1000\r\n\r\n{\"data\":".toByteArray())
                flush()
            }
            // Blocks until the client closes its end: -1 (or a reset) means the request was dropped.
            runCatching { while (input.read() >= 0) Unit }
            connectionClosed.countDown()
        }
    }

    private val http = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS) // far past the caller's budget: only cancellation can stop the read
        .addInterceptor { chain ->
            val local = "http://127.0.0.1:${server.localPort}/pathfinder".toHttpUrl()
            chain.proceed(chain.request().newBuilder().url(local).build())
        }
        .build()

    private val client = SpotifyApiClient(
        http,
        mockk<TokenManager> {
            coEvery { getSpotifyAccessToken() } returns "access-token"
            coEvery { getSpotifyClientId() } returns "client-id"
        },
        mockk<SpotifyAuthManager> {
            every { getClientVersion() } returns "1.2.3.4.g0"
            coEvery { getClientToken("client-id") } returns "client-token"
            every { getSpT() } returns null
        },
    )

    @After fun tearDown() {
        server.close()
    }

    @Test fun `a stalled answer stops at the caller's budget and drops the request`() = runBlocking {
        val started = System.nanoTime()
        val visuals = withTimeoutOrNull(500) { client.getArtistVisuals("Beck") }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertNull(visuals)
        assertTrue("returned after $tookMs ms", tookMs < 3_000)
        assertTrue("the server saw the request dropped", connectionClosed.await(5, TimeUnit.SECONDS))
    }
}
