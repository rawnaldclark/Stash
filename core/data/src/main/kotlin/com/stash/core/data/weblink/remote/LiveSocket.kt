package com.stash.core.data.weblink.remote

import android.os.SystemClock
import android.util.Log
import com.stash.core.data.listen.RoomClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

/** What the space's live socket (sync-v1 §5.7, the Worker's `GET /v1/spaces/{sid}/ws`) tells [WebRemote]. */
sealed interface LiveEvent {
    /** Open, and `hello` has gone out. */
    data object Open : LiveEvent

    /** One text frame from the server (`ready`, `peer`, `state`, `epoch`, `error`; `pong` is dropped here). */
    data class Frame(val text: String) : LiveEvent

    /** The socket dropped; a reconnect is on its way (1, 2, 4, 8, 15, 30 s, ±20 %). */
    data object Dropped : LiveEvent

    /**
     * Final: removed (`4401`), refused (`4403`), replaced by a newer socket of this phone (`4409`), out of today's reads (`4429`),
     * or the upgrade itself refused (an HTTP 401/403/404/426: e.g. a Worker without live control yet). No reconnect until the next
     * foreground.
     */
    data class Closed(val code: Int) : LiveEvent
}

/** One live socket. [WebRemote] only sees this, so tests fake it. */
interface LiveConnection {
    val events: Flow<LiveEvent>
    fun send(text: String): Boolean
    fun close()
}

fun interface LiveConnector {
    /** Opens [url] and sends [hello] as the first frame on every (re)connect. */
    fun connect(url: String, hello: String, scope: CoroutineScope): LiveConnection
}

@Singleton
class OkHttpLiveConnector @Inject constructor(okHttpClient: OkHttpClient) : LiveConnector {
    // A socket sits quiet between pings; the shared client's read timeout would kill it. Protocol pings every 20 s (as Listen
    // Together's RoomClient) find a dead connection (a network switch, a NAT that forgot it) and fail it, so it reconnects.
    private val client = okHttpClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()

    override fun connect(url: String, hello: String, scope: CoroutineScope): LiveConnection = LiveSocket(client, url, hello, scope)
}

/**
 * The live socket over OkHttp, after Listen Together's [RoomClient]: `hello` first on every connect, a text `ping` every 30 s
 * while open (the runtime answers `pong` without waking the space's object, and it keeps the server's 90 s idle rule happy:
 * OkHttp's protocol pings don't count there), and reconnects with a backoff until [close]. It lives only while the app is in the
 * foreground ([WebRemote]), so there is no give-up clock.
 */
internal class LiveSocket(
    private val http: OkHttpClient,
    private val url: String,
    private val hello: String,
    private val scope: CoroutineScope,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    private val random: Random = Random.Default,
) : LiveConnection {
    private val channel = Channel<LiveEvent>(Channel.UNLIMITED)
    override val events: Flow<LiveEvent> = channel.receiveAsFlow()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var leaving: WebSocket? = null
    private val job: Job = scope.launch { loop() }

    override fun send(text: String): Boolean = socket?.send(text) ?: false

    override fun close() {
        leaving = socket?.also { it.close(1000, "bye") }
        job.cancel()
        socket = null
        channel.close()
    }

    private suspend fun loop() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val end = connectOnce()
            if (end.finalCode != null) {
                channel.trySend(LiveEvent.Closed(end.finalCode))
                return
            }
            if (end.healthy) attempt = 0
            channel.trySend(LiveEvent.Dropped)
            delay(RoomClient.jittered(BACKOFF_MS[minOf(attempt, BACKOFF_MS.lastIndex)], random))
            attempt++
        }
    }

    private class End(val finalCode: Int? = null, val healthy: Boolean = false)

    private suspend fun connectOnce(): End = suspendCancellableCoroutine { cont ->
        var openedAt: Long? = null
        var pinger: Job? = null
        fun healthy() = openedAt?.let { now() - it >= HEALTHY_AFTER_MS } ?: false
        fun finish(end: End) {
            pinger?.cancel()
            socket = null
            if (cont.isActive) cont.resume(end)
        }
        val ws = http.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openedAt = now()
                    webSocket.send(hello) // before anything else can go out
                    socket = webSocket
                    channel.trySend(LiveEvent.Open)
                    pinger = scope.launch {
                        while (isActive) {
                            delay(PING_MS)
                            if (!webSocket.send("ping")) break // closing or closed: its close ends this socket's turn
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text != "pong") channel.trySend(LiveEvent.Frame(text))
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "closed $code $reason")
                    finish(if (code in FINAL) End(finalCode = code) else End(healthy = code != 1008 && code != 1009 && healthy()))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.i(TAG, "failure ${response?.code} ${t.javaClass.simpleName}")
                    // Refused at the upgrade for good (a Worker without live control answers 404): no retry loop
                    // until the next foreground. A 429 or a network failure backs off and tries again.
                    val code = response?.code
                    finish(if (code != null && code in REFUSED) End(finalCode = code) else End(healthy = healthy()))
                }
            },
        )
        cont.invokeOnCancellation {
            pinger?.cancel()
            if (ws !== leaving) ws.cancel()
        }
    }

    internal companion object {
        private const val TAG = "WebRemote"
        const val PING_MS = 30_000L
        private const val HEALTHY_AFTER_MS = 5_000L
        private val BACKOFF_MS = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)

        /**
         * Removed, refused, replaced, out of today's reads: reconnecting would be refused again (or fight the newer socket); 4429
         * would otherwise retry every 30 s all day.
         */
        val FINAL = setOf(4401, 4403, 4409, 4429)

        /** Upgrade answers that won't change by retrying: no such route (not deployed yet), unauthorized, forbidden, not a socket. */
        val REFUSED = setOf(401, 403, 404, 426)
    }
}
