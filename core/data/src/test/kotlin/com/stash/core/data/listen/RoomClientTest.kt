package com.stash.core.data.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.ServerMessage
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomClientTest {
    private val server = MockWebServer()
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val serverClosing: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** The fake clock: tests move it by setting it. */
    private val t = AtomicLong(0)

    @After fun tearDown() { server.shutdown() }

    private fun upgrade(onOpen: (WebSocket) -> Unit = {}) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)
        override fun onMessage(webSocket: WebSocket, text: String) { received += text }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { serverClosing += "$code $reason"; webSocket.close(code, null) }
    })

    private fun client(scope: CoroutineScope, backoffMs: Long = 10L, hello: () -> ClientMessage.Hello) =
        RoomClient(
            http = OkHttpClient(),
            url = { resume -> server.url("/v1/rooms/K7QA2PXM/ws").toString() + if (resume) "?r=1" else "" },
            scope = scope,
            hello = hello,
            now = { t.get() },
            backoffMs = listOf(backoffMs),
        )

    @Test fun `hello goes first, then server messages arrive parsed`() = runBlocking {
        server.enqueue(upgrade(onOpen = { it.send("""{"t":"pong","c":1,"r":2}""") }))
        val c = client(this) { ClientMessage.Hello(name = "Rawn") }
        val events = withTimeout(5_000) { c.events.take(2).toList() }
        assertThat(events).containsExactly(RoomEvent.Connected, RoomEvent.Message(ServerMessage.Pong(1, 2), receivedAt = 0)).inOrder()
        withTimeout(5_000) { while (received.isEmpty()) delay(10) }
        assertThat(received.first()).isEqualTo("""{"t":"hello","name":"Rawn"}""")
        c.close()
    }

    @Test fun `a dropped socket reconnects`() = runBlocking {
        server.enqueue(upgrade(onOpen = { it.close(1001, "going away") }))
        server.enqueue(upgrade())
        val c = client(this) { ClientMessage.Hello() }
        val events = withTimeout(5_000) { c.events.take(3).toList() }
        assertThat(events).containsExactly(RoomEvent.Connected, RoomEvent.Reconnecting, RoomEvent.Connected).inOrder()
        assertThat(server.requestCount).isEqualTo(2)
        c.close()
    }

    @Test fun `a resume token asks for the rejoin route`() = runBlocking {
        server.enqueue(upgrade())
        val c = client(this) { ClientMessage.Hello(resumeToken = "TOK") }
        withTimeout(5_000) { c.events.take(1).toList() }
        assertThat(server.takeRequest().path).isEqualTo("/v1/rooms/K7QA2PXM/ws?r=1")
        c.close()
    }

    @Test fun `404 and 409 at the upgrade are final`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(404))
        val ended = client(this) { ClientMessage.Hello() }
        assertThat(withTimeout(5_000) { ended.events.take(1).toList() }).containsExactly(RoomEvent.Closed(CloseReason.ENDED))
        server.enqueue(MockResponse().setResponseCode(409))
        val full = client(this) { ClientMessage.Hello() }
        assertThat(withTimeout(5_000) { full.events.take(1).toList() }).containsExactly(RoomEvent.Closed(CloseReason.FULL))
    }

    @Test fun `it gives up after two minutes of failed reconnects`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse().setResponseCode(500)) }
        val c = client(this) { ClientMessage.Hello() }
        val events = withTimeout(5_000) { c.events.onEach { if (it == RoomEvent.Reconnecting) t.set(120_000) }.take(2).toList() }
        assertThat(events).containsExactly(RoomEvent.Reconnecting, RoomEvent.Closed(CloseReason.GAVE_UP)).inOrder()
    }

    @Test fun `a rate-limit close keeps backing off instead of counting as a good connection`() = runBlocking {
        repeat(2) { server.enqueue(upgrade(onOpen = { it.close(1008, "too many messages") })) }
        val c = client(this) { ClientMessage.Hello() }
        val events = withTimeout(5_000) { c.events.onEach { if (it == RoomEvent.Reconnecting) t.set(120_000) }.take(4).toList() }
        assertThat(events)
            .containsExactly(RoomEvent.Connected, RoomEvent.Reconnecting, RoomEvent.Connected, RoomEvent.Closed(CloseReason.GAVE_UP))
            .inOrder()
    }

    @Test fun `a drop within 5 s of opening does not reset the give-up clock`() = runBlocking {
        repeat(2) { server.enqueue(upgrade(onOpen = { it.close(1001, "going away") })) }
        val c = client(this) { ClientMessage.Hello() }
        val events = withTimeout(5_000) { c.events.onEach { if (it == RoomEvent.Reconnecting) t.set(120_000) }.take(4).toList() }
        assertThat(events)
            .containsExactly(RoomEvent.Connected, RoomEvent.Reconnecting, RoomEvent.Connected, RoomEvent.Closed(CloseReason.GAVE_UP))
            .inOrder()
    }

    @Test fun `a drop after 5 s up resets the give-up clock`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500)) // down since t = 0
        val serverSide = java.util.concurrent.atomic.AtomicReference<WebSocket?>()
        server.enqueue(upgrade(onOpen = { serverSide.set(it) }))
        server.enqueue(upgrade())
        val c = client(this) { ClientMessage.Hello() }
        var reconnects = 0
        var connects = 0
        val events = withTimeout(5_000) {
            c.events.onEach {
                if (it == RoomEvent.Reconnecting && reconnects++ == 0) t.set(119_000)
                if (it == RoomEvent.Connected && connects++ == 0) {
                    t.set(125_000) // up 6 s
                    while (serverSide.get() == null) delay(10)
                    serverSide.get()!!.close(1001, "going away")
                }
            }.take(4).toList()
        }
        // Dropped at 125 s, but it had been up 6 s, so that is a fresh outage, not the one from t = 0.
        assertThat(events)
            .containsExactly(RoomEvent.Reconnecting, RoomEvent.Connected, RoomEvent.Reconnecting, RoomEvent.Connected)
            .inOrder()
        c.close()
    }

    @Test fun `a replaced close is final`() = runBlocking {
        server.enqueue(upgrade(onOpen = { it.close(1000, "replaced") }))
        server.enqueue(upgrade())
        val c = client(this) { ClientMessage.Hello(resumeToken = "TOK") }
        val events = withTimeout(5_000) { c.events.take(2).toList() }
        assertThat(events).containsExactly(RoomEvent.Connected, RoomEvent.Closed(CloseReason.REPLACED)).inOrder()
        delay(100)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `a reconnect carries the newest resume token`() = runBlocking {
        server.enqueue(upgrade(onOpen = { it.close(1001, "going away") }))
        server.enqueue(upgrade())
        var token: String? = null
        val c = client(this) { ClientMessage.Hello(resumeToken = token) }
        val events = withTimeout(5_000) { c.events.onEach { if (it == RoomEvent.Connected) token = "TOK" }.take(3).toList() }
        assertThat(events).containsExactly(RoomEvent.Connected, RoomEvent.Reconnecting, RoomEvent.Connected).inOrder()
        assertThat(server.takeRequest().path).isEqualTo("/v1/rooms/K7QA2PXM/ws")
        assertThat(server.takeRequest().path).isEqualTo("/v1/rooms/K7QA2PXM/ws?r=1")
        withTimeout(5_000) { while (received.size < 2) delay(10) }
        assertThat(received[1]).isEqualTo("""{"t":"hello","resumeToken":"TOK"}""")
        c.close()
    }

    @Test fun `room closes 4000, 4404 and 4409 are final`() = runBlocking {
        for ((code, reason) in listOf(4000 to CloseReason.ENDED, 4404 to CloseReason.ENDED, 4409 to CloseReason.FULL)) {
            server.enqueue(upgrade(onOpen = { it.close(code, "bye") }))
            val c = client(this) { ClientMessage.Hello() }
            assertThat(withTimeout(5_000) { c.events.take(2).toList() }).containsExactly(RoomEvent.Connected, RoomEvent.Closed(reason)).inOrder()
        }
        delay(100)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `a 429 at the upgrade backs off and retries`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(upgrade())
        val c = client(this) { ClientMessage.Hello() }
        assertThat(withTimeout(5_000) { c.events.take(2).toList() }).containsExactly(RoomEvent.Reconnecting, RoomEvent.Connected).inOrder()
        c.close()
    }

    @Test fun `a 409 on a resume is the socket cap, so it retries`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(upgrade())
        val c = client(this) { ClientMessage.Hello(resumeToken = "TOK") }
        assertThat(withTimeout(5_000) { c.events.take(2).toList() }).containsExactly(RoomEvent.Reconnecting, RoomEvent.Connected).inOrder()
        c.close()
    }

    @Test fun `close says leave and stops reconnecting`() = runBlocking {
        server.enqueue(upgrade())
        server.enqueue(upgrade())
        val c = client(this, backoffMs = 50L) { ClientMessage.Hello() }
        withTimeout(5_000) { c.events.take(1).toList() }
        c.close()
        withTimeout(5_000) { while (serverClosing.isEmpty()) delay(10) }
        assertThat(serverClosing).containsExactly("1000 leave")
        delay(300)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `backoff is jittered within 20 percent`() {
        val random = Random(1)
        repeat(100) { assertThat(RoomClient.jittered(1_000, random)).isIn(com.google.common.collect.Range.closed(800L, 1_200L)) }
    }
}
