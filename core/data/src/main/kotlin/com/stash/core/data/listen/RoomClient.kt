package com.stash.core.data.listen

import android.os.SystemClock
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.RoomProtocol
import com.stash.core.model.share.ShareConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface RoomEvent {
    /** The socket is open and `hello` has gone out. */
    data object Connected : RoomEvent
    data class Message(val message: com.stash.core.model.listen.ServerMessage) : RoomEvent
    /** The socket dropped; a reconnect is scheduled (spec §5: 1, 2, 4, 8, 15 s). */
    data object Reconnecting : RoomEvent
    /** Final: no more reconnects. */
    data class Closed(val reason: CloseReason) : RoomEvent
}

enum class CloseReason { ENDED, FULL, GAVE_UP }

/** One room connection. The session only sees this, so tests can fake it. */
interface RoomConnection {
    val events: Flow<RoomEvent>
    fun send(message: ClientMessage): Boolean
    fun close()
}

fun interface RoomConnector {
    /** [hello] runs on every (re)connect, so it can carry the newest resume token. */
    fun connect(code: String, scope: CoroutineScope, hello: () -> ClientMessage.Hello): RoomConnection
}

@Singleton
class OkHttpRoomConnector @Inject constructor(okHttpClient: OkHttpClient) : RoomConnector {
    internal var baseUrl: String = ShareConfig.BASE_URL

    // A room socket can sit quiet for 30 s between pings, and the shared client's 30 s read
    // timeout would kill it. OkHttp's own ping keeps NAT mappings warm.
    private val client = okHttpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun connect(code: String, scope: CoroutineScope, hello: () -> ClientMessage.Hello): RoomConnection =
        RoomClient(client, { resume -> "$baseUrl/v1/rooms/$code/ws" + if (resume) "?r=1" else "" }, scope, hello)
}

/**
 * The Listen Together WebSocket (spec §5): sends `hello` first on every connect, parses room
 * messages, and reconnects with backoff (1, 2, 4, 8, 15, 15… s) until two minutes have passed
 * since the connection was last up. Rejections (404 closed, 409 full, a 4xxx close from the room)
 * are final.
 */
internal class RoomClient(
    private val http: OkHttpClient,
    private val url: (resume: Boolean) -> String,
    scope: CoroutineScope,
    private val hello: () -> ClientMessage.Hello,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    private val backoffMs: List<Long> = BACKOFF_MS,
    private val giveUpMs: Long = GIVE_UP_MS,
) : RoomConnection {
    private val channel = Channel<RoomEvent>(Channel.UNLIMITED)
    override val events: Flow<RoomEvent> = channel.receiveAsFlow()

    @Volatile private var socket: WebSocket? = null
    private val job = scope.launch { loop() }

    override fun send(message: ClientMessage): Boolean = socket?.send(RoomProtocol.encode(message)) ?: false

    override fun close() {
        job.cancel()
        socket?.close(1000, "leave")
        socket = null
        channel.close()
    }

    private sealed interface End {
        /** [healthy] = the socket opened and wasn't thrown out for misbehaving, so the backoff restarts. */
        data class Dropped(val healthy: Boolean) : End
        data class Final(val reason: CloseReason) : End
    }

    private suspend fun loop() {
        var downSince: Long? = null
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            when (val end = connectOnce()) {
                is End.Final -> { channel.trySend(RoomEvent.Closed(end.reason)); return }
                is End.Dropped -> {
                    if (end.healthy) { downSince = null; attempt = 0 }
                    val since = downSince ?: now().also { downSince = it }
                    if (now() - since >= giveUpMs) { channel.trySend(RoomEvent.Closed(CloseReason.GAVE_UP)); return }
                    channel.trySend(RoomEvent.Reconnecting)
                    delay(backoffMs[minOf(attempt, backoffMs.lastIndex)])
                    attempt++
                }
            }
        }
    }

    private suspend fun connectOnce(): End = suspendCancellableCoroutine { cont ->
        val greeting = hello()
        var opened = false
        fun finish(end: End) {
            socket = null
            if (cont.isActive) cont.resume(end)
        }
        val ws = http.newWebSocket(
            Request.Builder().url(url(greeting.resumeToken != null)).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened = true
                    socket = webSocket
                    webSocket.send(RoomProtocol.encode(greeting))
                    channel.trySend(RoomEvent.Connected)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    RoomProtocol.decode(text)?.let { channel.trySend(RoomEvent.Message(it)) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    finish(
                        when (code) {
                            4000, 4404 -> End.Final(CloseReason.ENDED)
                            4409 -> End.Final(CloseReason.FULL)
                            // Rate-limited or oversized frame: the room threw us out, so keep the
                            // backoff growing and the give-up clock running rather than retrying at 1 s forever.
                            1008, 1009 -> End.Dropped(healthy = false)
                            else -> End.Dropped(healthy = opened)
                        },
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(
                        when (response?.code) {
                            404 -> End.Final(CloseReason.ENDED)
                            409 -> End.Final(CloseReason.FULL)
                            else -> End.Dropped(healthy = opened) // 429 at the upgrade never opened, so it backs off
                        },
                    )
                }
            },
        )
        cont.invokeOnCancellation { ws.cancel() }
    }

    private companion object {
        val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        const val GIVE_UP_MS = 120_000L
    }
}
