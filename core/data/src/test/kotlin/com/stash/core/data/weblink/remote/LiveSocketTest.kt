package com.stash.core.data.weblink.remote

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.stash.core.data.weblink.WebLinkConfig
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test

/** The live socket over a real OkHttp WebSocket (MockWebServer): hello first, frames through, final closes and refusals. */
class LiveSocketTest {
    private val server = MockWebServer().apply { start() }
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A server end that answers the client's close, as the Worker does, so the server shuts down at once. */
    private object EchoClose : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test fun `hello goes out first, frames come in, pong is swallowed, and 4409 replaced is final`() = runBlocking {
        val received = mutableListOf<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = Unit

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received += text
                    webSocket.send("pong")
                    webSocket.send("""{"t":"ready","me":"d_phone0001","epoch":1,"peers":[]}""")
                    webSocket.close(4409, "replaced")
                }
            }),
        )
        val socket = LiveSocket(http, server.url("/v1/spaces/s_x/ws").toString(), """{"t":"hello"}""", scope)
        val events = withTimeout(10_000) { socket.events.take(3).toList() }
        assertThat(events[0]).isEqualTo(LiveEvent.Open)
        assertThat(events[1]).isEqualTo(LiveEvent.Frame("""{"t":"ready","me":"d_phone0001","epoch":1,"peers":[]}"""))
        assertThat(events[2]).isEqualTo(LiveEvent.Closed(4409))
        assertThat(received.first()).isEqualTo("""{"t":"hello"}""")
        assertThat(server.requestCount).isEqualTo(1) // no reconnect after a final close
        socket.close()
    }

    @Test fun `out of today's reads (4429) is final for this foreground, not a retry every 30 s`() = runBlocking {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    webSocket.close(4429, "daily_limit")
                }
            }),
        )
        val socket = LiveSocket(http, server.url("/v1/spaces/s_x/ws").toString(), "{}", scope)
        val events = withTimeout(10_000) { socket.events.take(2).toList() }
        assertThat(events).containsExactly(LiveEvent.Open, LiveEvent.Closed(4429)).inOrder()
        Thread.sleep(1_500) // longer than the first backoff
        assertThat(server.requestCount).isEqualTo(1)
        socket.close()
    }

    @Test fun `an upgrade the Worker refuses (404 before live control is deployed) is final, not a retry loop`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"not_found","message":"No such route"}}"""))
        val socket = LiveSocket(http, server.url("/v1/spaces/s_x/ws").toString(), "{}", scope)
        val first = withTimeout(10_000) { socket.events.first() }
        assertThat(first).isEqualTo(LiveEvent.Closed(404))
        Thread.sleep(1_500) // longer than the first backoff
        assertThat(server.requestCount).isEqualTo(1)
        socket.close()
    }

    @Test fun `a plain-http Worker's socket opens over the app's TLS-only client, an https one stays TLS only`() = runBlocking<Unit> {
        server.enqueue(MockResponse().withWebSocketUpgrade(EchoClose))
        // The app's shared client allows TLS only (NetworkModule); a debug build points at `wrangler dev` over plain http.
        val tlsOnly = OkHttpClient.Builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).build()
        val local = OkHttpLiveConnector(tlsOnly, WebLinkConfig("http://127.0.0.1:8795", enabled = true))
        val socket = local.connect(server.url("/v1/spaces/s_x/ws").toString(), """{"t":"hello"}""", scope)
        assertThat(withTimeout(10_000) { socket.events.first() }).isEqualTo(LiveEvent.Open)
        socket.close()
        val live = OkHttpLiveConnector(tlsOnly, WebLinkConfig("https://sync.stashfm.app", enabled = true))
        assertThat(live.client.connectionSpecs).containsExactly(ConnectionSpec.MODERN_TLS)
    }

    @Test fun `a drop (a server error at the upgrade) reconnects`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().withWebSocketUpgrade(EchoClose))
        val socket = LiveSocket(http, server.url("/v1/spaces/s_x/ws").toString(), "{}", scope)
        val events = withTimeout(10_000) { socket.events.take(2).toList() }
        assertThat(events).containsExactly(LiveEvent.Dropped, LiveEvent.Open).inOrder()
        assertThat(server.requestCount).isEqualTo(2)
        socket.close()
    }
}
