package com.stash.core.data.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.ServerMessage
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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

    @After fun tearDown() { server.shutdown() }

    private fun upgrade(onOpen: (WebSocket) -> Unit = {}) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onOpen(webSocket)
        override fun onMessage(webSocket: WebSocket, text: String) { received += text }
    })

    private fun client(scope: CoroutineScope, now: () -> Long = { System.currentTimeMillis() }, hello: () -> ClientMessage.Hello) =
        RoomClient(
            http = OkHttpClient(),
            url = { resume -> server.url("/v1/rooms/K7QA2PXM/ws").toString() + if (resume) "?r=1" else "" },
            scope = scope,
            hello = hello,
            now = now,
            backoffMs = listOf(10L),
        )

    @Test fun `hello goes first, then server messages arrive parsed`() = runBlocking {
        server.enqueue(upgrade(onOpen = { it.send("""{"t":"pong","c":1,"r":2}""") }))
        val c = client(this) { ClientMessage.Hello(name = "Rawn") }
        val events = withTimeout(5_000) { c.events.take(2).toList() }
        assertThat(events).containsExactly(RoomEvent.Connected, RoomEvent.Message(ServerMessage.Pong(1, 2))).inOrder()
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
        var t = 0L
        val c = client(this, now = { t.also { t += 61_000 } }) { ClientMessage.Hello() }
        val events = withTimeout(5_000) { c.events.take(2).toList() }
        assertThat(events).containsExactly(RoomEvent.Reconnecting, RoomEvent.Closed(CloseReason.GAVE_UP)).inOrder()
    }

    @Test fun `a rate-limit close keeps backing off instead of counting as a good connection`() = runBlocking {
        repeat(2) { server.enqueue(upgrade(onOpen = { it.close(1008, "too many messages") })) }
        var t = 0L
        val c = client(this, now = { t.also { t += 61_000 } }) { ClientMessage.Hello() }
        val events = withTimeout(5_000) { c.events.take(4).toList() }
        assertThat(events)
            .containsExactly(RoomEvent.Connected, RoomEvent.Reconnecting, RoomEvent.Connected, RoomEvent.Closed(CloseReason.GAVE_UP))
            .inOrder()
    }
}
