package com.stash.core.data.listen

import android.os.SystemClock
import android.util.Log
import com.stash.core.model.listen.ClientMessage
import com.stash.core.model.listen.RoomProtocol
import com.stash.core.model.share.ShareConfig
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

sealed interface RoomEvent {
    /** The socket is open and `hello` has gone out. */
    data object Connected : RoomEvent
    /** [receivedAt] is [RoomClient]'s clock (elapsedRealtime) when the frame arrived, so a pong's round trip excludes queueing. */
    data class Message(val message: com.stash.core.model.listen.ServerMessage, val receivedAt: Long) : RoomEvent
    /** The socket dropped; a reconnect is scheduled (spec §5: 1, 2, 4, 8, 15 s). */
    data object Reconnecting : RoomEvent
    /** Final: no more reconnects. */
    data class Closed(val reason: CloseReason) : RoomEvent
}

/** [REPLACED]: the room closed us 1000 "replaced" because a newer connection resumed this membership. */
enum class CloseReason { ENDED, FULL, GAVE_UP, REPLACED }

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
 * since the connection was last up (±20% jitter). Final: 404 at the upgrade, 409 on a first join
 * (full), closes 4000/4404 (ended) and 4409 (full), and 1000 "replaced". A 409 on a resume is the
 * room's socket cap, so it retries. Other closes (4408 no hello, 1008/1009 misbehaving, network)
 * reconnect; a drop only resets the backoff if the socket had been up at least 5 s.
 */
internal class RoomClient(
    private val http: OkHttpClient,
    private val url: (resume: Boolean) -> String,
    scope: CoroutineScope,
    private val hello: () -> ClientMessage.Hello,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    private val backoffMs: List<Long> = BACKOFF_MS,
    private val giveUpMs: Long = GIVE_UP_MS,
    private val random: Random = Random.Default,
) : RoomConnection {
    private val channel = Channel<RoomEvent>(Channel.UNLIMITED)
    override val events: Flow<RoomEvent> = channel.receiveAsFlow()

    @Volatile private var socket: WebSocket? = null
    /** The socket [close] is shutting down gracefully, which cancellation must not abort. */
    @Volatile private var leaving: WebSocket? = null
    private val job = scope.launch { loop() }

    override fun send(message: ClientMessage): Boolean = socket?.send(RoomProtocol.encode(message)) ?: false

    override fun close() {
        // Say goodbye first: cancelling the job would otherwise cancel the socket before the frame goes out.
        leaving = socket?.also { it.close(1000, "leave") }
        job.cancel()
        socket = null
        channel.close()
    }

    private sealed interface End {
        /** [healthy] = the socket was up at least 5 s and wasn't thrown out for misbehaving, so the backoff restarts. */
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
                    delay(jittered(backoffMs[minOf(attempt, backoffMs.lastIndex)], random))
                    attempt++
                }
            }
        }
    }

    private suspend fun connectOnce(): End = suspendCancellableCoroutine { cont ->
        val greeting = hello()
        val resume = greeting.resumeToken != null
        var openedAt: Long? = null
        fun healthy() = openedAt?.let { now() - it >= HEALTHY_AFTER_MS } ?: false
        fun finish(end: End) {
            socket = null
            if (cont.isActive) cont.resume(end)
        }
        val ws = http.newWebSocket(
            Request.Builder().url(url(resume)).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "open (resume=$resume)")
                    openedAt = now()
                    webSocket.send(RoomProtocol.encode(greeting)) // hello before anyone else can send
                    socket = webSocket
                    channel.trySend(RoomEvent.Connected)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val receivedAt = now()
                    val message = RoomProtocol.decode(text)
                    if (message == null) {
                        val t = runCatching { RoomProtocol.json.parseToJsonElement(text).jsonObject["t"]?.jsonPrimitive?.content }.getOrNull()
                        Log.w(TAG, "undecodable room message t=$t")
                        return
                    }
                    channel.trySend(RoomEvent.Message(message, receivedAt))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "closed $code $reason")
                    finish(
                        when (code) {
                            4000, 4404 -> End.Final(CloseReason.ENDED)
                            4409 -> End.Final(CloseReason.FULL)
                            // Rate-limited or oversized frame: the room threw us out, so keep the
                            // backoff growing and the give-up clock running rather than retrying at 1 s forever.
                            1008, 1009 -> End.Dropped(healthy = false)
                            // A newer connection resumed our membership; reconnecting would just fight it.
                            1000 if reason == "replaced" -> End.Final(CloseReason.REPLACED)
                            else -> End.Dropped(healthy())
                        },
                    )
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.i(TAG, "failure ${response?.code} ${t.message}")
                    finish(
                        when (response?.code) {
                            404 -> End.Final(CloseReason.ENDED)
                            // On a resume, 409 is the room's temporary socket cap, not "full".
                            409 -> if (resume) End.Dropped(healthy = false) else End.Final(CloseReason.FULL)
                            else -> End.Dropped(healthy()) // 429 at the upgrade never opened, so it backs off
                        },
                    )
                }
            },
        )
        cont.invokeOnCancellation { if (ws !== leaving) ws.cancel() }
    }

    internal companion object {
        private const val TAG = "RoomClient"
        private val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)
        private const val GIVE_UP_MS = 120_000L
        private const val HEALTHY_AFTER_MS = 5_000L

        /** [base] ±20%, so phones dropped together don't all reconnect in the same instant. */
        fun jittered(base: Long, random: Random): Long = (base * (0.8 + 0.4 * random.nextDouble())).toLong()
    }
}
