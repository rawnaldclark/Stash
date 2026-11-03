package com.stash.core.data.weblink.remote

import android.os.SystemClock
import android.util.Log
import com.stash.core.data.weblink.Base64Url
import com.stash.core.data.weblink.SyncCrypto
import com.stash.core.data.weblink.SyncEnvelope
import com.stash.core.data.weblink.SyncJson
import com.stash.core.data.weblink.WebLinkConfig
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.handoff.HandoffWireException
import com.stash.core.data.weblink.store.LinkIdentity
import com.stash.core.data.weblink.store.LinkedSpace
import com.stash.core.data.weblink.store.RosterEntry
import com.stash.core.data.weblink.store.WebLinkStore
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * This phone as a remote for a linked browser (spec 2026-10-10 §6.7; sync-v1 §5.7): play, pause, skip, seek and volume, phone →
 * web only in v1.
 *
 * - **When**: one socket on the space (`wss://sync.stashfm.app/v1/spaces/{sid}/ws`), only while the app is in the foreground
 *   ([onForeground] / [onBackground]) and only when the space has a browser at all. The socket is push: a browser that starts
 *   advertising shows up at once, and nothing polls. In the background there is no socket.
 * - **Follows the roster** while in the foreground ([WebLinkStore.rosterVersion]): a browser linked from the in-app scanner
 *   opens the socket then, not at the next foreground (v0.9.123: the card only showed after a restart); one linked while the
 *   socket is open is heard from at once; removing the last one, or unlinking, closes it.
 * - **What it hears**: `ready`/`peer` (which browsers have their socket open), and each browser's `state`, opened under the data
 *   key at `remote:<browser>`, taken only from browsers whose label this phone verified (its roster). A browser that goes quiet for
 *   a minute is treated as gone ([RemoteCards.STALE_MS]).
 * - **What it says**: commands sealed at `remote-cmd:<this phone>:<browser>`, naming the browser's session (`sid`) with an
 *   increasing number, so a command can't be replayed or land in a later session. They go out one at a time in the order they
 *   were tapped, and each session's numbers start at the wall clock in ms, so after the background or a restart (when this phone
 *   has forgotten its numbers) the browser still sees them rise. The card moves at once ([RemoteCards.optimistic]); the browser's
 *   next state confirms it.
 * - **Keys**: `epoch` (a rotation) or an `epoch` / `rotation_due` error lets [WebLinkRepository.refresh] catch up; `4401` (this
 *   phone was removed) lets it find out and forget the link.
 *
 * Remote v2 (spec 2026-11-03 §2.2): a browser can play this phone's queue, driven through the media session
 * (`core:media` `BrowserOutputs` / `WebCastRemote`). For that:
 * - [pin] keeps the socket open while a browser is the output, in the background too: the one exception to "no socket in the
 *   background". It lives in the foreground media service, as Listen Together's room socket does.
 * - [sendTo] names the browser; [send] stays for the card's.
 * - [live] has every live browser's state (with its `caps`), [linked] the space's verified browsers by name.
 * - While pinned, a dropped socket keeps the driven browser's last state, marked [BrowserState.lostAt], and holds what couldn't go
 *   out until that browser is heard from again; the driver decides how long to wait.
 * - The card leaves out the browser this phone drives (the mini player is its remote). A browser still driven by this phone when
 *   it isn't (the playback service died and came back inside the browser's minute) is told to let go, and plays on.
 */
@Singleton
class WebRemote internal constructor(
    private val store: WebLinkStore,
    private val repo: WebLinkRepository,
    private val config: WebLinkConfig,
    private val connector: LiveConnector,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    private val tickMs: Long,
) {
    @Inject constructor(store: WebLinkStore, repo: WebLinkRepository, config: WebLinkConfig, connector: LiveConnector) :
        this(store, repo, config, connector, CoroutineScope(SupervisorJob() + Dispatchers.IO), { SystemClock.elapsedRealtime() }, System::currentTimeMillis, TICK_MS)

    private val lock = Mutex()
    /** Taking a number, sealing and sending are one step: commands reach the socket in the order of their numbers. */
    private val sendLock = Mutex()
    /** A command and its browser (null: the card's). */
    private class Outgoing(val to: String?, val command: RemoteCommand)

    /** Taps, in the order they happened (each [send] used to start its own coroutine, which could overtake another). */
    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
    /**
     * Whether a socket should be open: set by [onForeground] / [onBackground], acted on by one collector with the roster's
     * changes, the latest winning (a background right after a foreground can't leave a socket open behind a slower start).
     * Null: neither yet.
     */
    private val wanted = MutableStateFlow<Boolean?>(null)
    /** The browser this phone's queue plays on ([pin]): the socket stays open for it, foreground or not. */
    private val pinned = MutableStateFlow<String?>(null)
    private var conn: LiveConnection? = null
    /** The space and this phone the open socket said hello for: a different link needs a socket of its own. */
    private var openFor: Pair<String, String>? = null
    private var jobs: List<Job> = emptyList()
    @Volatile private var me = ""
    /** Verified browsers of the space: id → name. Read again on each roster change, so a browser linked later is heard. */
    private val names = MutableStateFlow<Map<String, String>>(emptyMap())
    private val browsers = MutableStateFlow<Map<String, BrowserState>>(emptyMap())
    /** The last command number per browser session. */
    private val sent = ConcurrentHashMap<String, Long>()
    /** Commands for the pinned browser that couldn't go out while this phone's socket was down, in order (under [sendLock]). */
    private val parked = ArrayDeque<RemoteCommand>()
    /** Browser sessions this phone has told to let go: a state that still names this phone as driver is in flight, not stale. */
    private val released: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val _card = MutableStateFlow<RemoteCard?>(null)

    /** The browser the card shows, or null (none advertises, none is fresh, or the app is in the background). */
    val card: StateFlow<RemoteCard?> = _card.asStateFlow()

    /** Every browser whose socket is open and that has said what it plays, by device id (Remote v2: the "Play on" sheet). */
    val live: StateFlow<Map<String, BrowserState>> = browsers.asStateFlow()

    /** The space's verified browsers, id → name, open tab or not (read while the socket is wanted). */
    val linked: StateFlow<Map<String, String>> = names.asStateFlow()

    /** This phone's device id in the space, or null before the socket first opened. */
    val deviceId: String? get() = me.takeIf { it.isNotEmpty() }

    init {
        scope.launch {
            // In the foreground, each roster change runs start() again: it opens, keeps or closes the socket to match. A pinned
            // browser keeps it open in the background too.
            combine(wanted, pinned, store.rosterVersion) { w, p, _ -> w to p }.collectLatest { (w, p) ->
                when {
                    w == true || p != null -> start()
                    w == false -> stop()
                    else -> Unit
                }
            }
        }
        scope.launch { for (o in outbox) sendNow(o.to, o.command) }
    }

    /**
     * [device] plays this phone's queue (Remote v2): the socket stays open until [unpin], in the background too, and a drop
     * keeps that browser's last state for the driver's grace.
     */
    fun pin(device: String) {
        if (!config.enabled) return
        released.remove(browsers.value[device]?.state?.sid)
        pinned.value = device
    }

    /** The browser no longer plays this phone's queue: the socket follows the foreground again. */
    fun unpin() {
        pinned.value = null
        scope.launch {
            sendLock.withLock { parked.clear() }
            // A lost state kept for the grace is no longer anyone's.
            browsers.update { all -> all.filterValues { it.lostAt == null } }
            publish()
        }
    }

    /**
     * Tells [device] to let go of this phone's queue (`release`), pausing unless [pause] is false. Its states that still name this
     * phone as driver are then in flight, not a reason to tell it again.
     */
    fun release(device: String, pause: Boolean) {
        browsers.value[device]?.state?.sid?.let(released::add)
        sendTo(device, RemoteCommand.Release(pause))
    }

    /**
     * The app came to the foreground (ProcessLifecycleOwner ON_START). From here until [onBackground], the socket follows the
     * roster.
     */
    fun onForeground() {
        if (!config.enabled) return
        wanted.value = true
    }

    /** The app went to the background: the socket closes and the card goes. */
    fun onBackground() {
        wanted.value = false
    }

    /**
     * Brings the socket in line with the link (in the foreground, on each roster change): none without a link or a browser in
     * it, one open otherwise. An open socket stays open when only the browsers changed: their names are read again, and a
     * browser no longer in the roster leaves the card. A store that can't be read just now leaves things as they are.
     */
    internal suspend fun start() = lock.withLock {
        val link = try {
            readLink()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "remote: ${e.javaClass.simpleName}")
            return@withLock
        }
        val web = link?.web.orEmpty()
        names.value = web.associate { it.deviceId to it.displayName }
        if (link == null || web.isEmpty()) return@withLock close() // unlinked, or no browser left: nothing to control, no socket
        val known = names.value
        if (browsers.value.keys.any { it !in known }) {
            browsers.update { all -> all.filterKeys { it in known } }
            publish()
        }
        val target = link.spaceId to link.deviceId
        if (conn != null && openFor == target) return@withLock
        if (conn != null) close() // linked again, to another space or as another device: that socket's hello is stale
        jobs.forEach { it.cancel() } // a socket that closed for good left its collector and tick behind
        jobs = emptyList()
        me = link.deviceId
        val id = link.identity
        val sp = link.space
        val hello = buildJsonObject {
            put("t", "hello")
            put("device", id.deviceId)
            put("token", Base64Url.encode(id.token))
        }.toString()
        val c = connector.connect("${config.baseUrl}/v1/spaces/${sp.spaceId}/ws", hello, scope)
        conn = c
        openFor = target
        jobs = listOf(
            scope.launch { c.events.collect { onEvent(c, it) } },
            // A frozen tab sends nothing more: the card goes once its last state is a minute old.
            scope.launch {
                while (isActive) {
                    delay(tickMs)
                    if (browsers.value.isNotEmpty()) publish()
                }
            },
        )
    }

    internal suspend fun stop() = lock.withLock { close() }

    /** Closes the socket (if any) and clears the card. Under [lock]. */
    private fun close() {
        conn?.close()
        conn = null
        openFor = null
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        sent.clear()
        released.clear()
        browsers.value = emptyMap()
        publish()
    }

    /** This phone's link and the space's verified browsers, or null when it isn't linked. Throws when the store can't be read now. */
    private suspend fun readLink(): Link? {
        val id = store.identity() ?: return null
        val sp = store.space() ?: return null
        return Link(id, sp, store.roster().filter { it.type == "web" })
    }

    private class Link(val identity: LinkIdentity, val space: LinkedSpace, val web: List<RosterEntry>) {
        val deviceId: String get() = identity.deviceId
        val spaceId: String get() = space.spaceId
    }

    /** Sends [command] to the browser on the card (if any) and moves the card at once. Taps go out in the order they happened. */
    fun send(command: RemoteCommand) {
        outbox.trySend(Outgoing(null, command))
    }

    /**
     * Sends [command] to the browser [device] (Remote v2: the one this phone's queue plays on), in order with every other
     * command. One for the pinned browser that can't go out now (this phone's socket is down) waits until that browser is heard
     * from again.
     */
    fun sendTo(device: String, command: RemoteCommand) {
        outbox.trySend(Outgoing(device, command))
    }

    /** Sends [command] to the card's browser now. */
    internal suspend fun sendNow(command: RemoteCommand): Boolean = sendNow(null, command)

    internal suspend fun sendNow(to: String?, command: RemoteCommand): Boolean = sendLock.withLock { sendLocked(to, command) }

    /** Seals and sends [command] to [to] (null: the card's browser). Under [sendLock]. */
    private suspend fun sendLocked(to: String?, command: RemoteCommand): Boolean {
        val target = to ?: _card.value?.device ?: return false
        val forPinned = to != null && target == pinned.value
        val c = conn
        val b = browsers.value[target]
        if (c == null || b == null || b.lostAt != null) return park(forPinned, command)
        val env = guard(null) {
            val sp = store.space() ?: return@guard null
            val key = dataKey(sp, sp.epoch) ?: return@guard null
            // Higher than this session's last, and than anything sent before this phone forgot its numbers (background, restart).
            val n = sent.compute(b.state.sid) { _, last -> maxOf((last ?: 0L) + 1, wallClock()) }!!
            SyncCrypto.seal(key, sp.spaceId, sp.epoch, RemoteWire.cmdPlace(me, target), RemoteWire.cmdJson(RemoteCmd(b.state.sid, n, command)))
        } ?: return false
        val frame = buildJsonObject {
            put("t", "cmd")
            put("to", target)
            put("env", SyncJson.encodeToJsonElement(SyncEnvelope.serializer(), env))
        }.toString()
        if (!c.send(frame)) return park(forPinned, command)
        browsers.update { all -> all[target]?.let { all + (target to RemoteCards.optimistic(it, command, clock())) } ?: all }
        publish()
        return true
    }

    /** Holds a command for the pinned browser until it is heard from again (the newest [MAX_PARKED]); any other is dropped. */
    private fun park(forPinned: Boolean, command: RemoteCommand): Boolean {
        if (!forPinned) return false
        if (parked.size >= MAX_PARKED) parked.removeFirst()
        parked.addLast(command)
        return false
    }

    /** The pinned browser was heard from: what waited for it goes out now, in order. */
    private suspend fun flushParked(device: String) = sendLock.withLock {
        if (device != pinned.value || parked.isEmpty()) return@withLock
        val waiting = parked.toList()
        parked.clear()
        for (command in waiting) sendLocked(device, command)
    }

    // ---------------------------------------------------------------------------------------- frames

    private suspend fun onEvent(c: LiveConnection, e: LiveEvent) {
        if (conn !== c) return
        when (e) {
            LiveEvent.Open -> Unit
            is LiveEvent.Frame -> onFrame(e.text)
            LiveEvent.Dropped -> forgetAll()
            is LiveEvent.Closed -> {
                lock.withLock {
                    if (conn === c) {
                        conn = null
                        openFor = null
                    }
                }
                forgetAll()
                if (e.code == 4401) guard(Unit) { repo.refresh() } // removed: the repository confirms it and forgets the link
            }
        }
    }

    /**
     * This phone's socket went down: every browser is forgotten (its tab may be gone by the time the socket is back), except the
     * one this phone's queue plays on, whose last state stays, marked lost, for the driver's grace.
     */
    private fun forgetAll() {
        val keep = pinned.value
        val now = clock()
        browsers.update { all ->
            val driven = keep?.let(all::get) ?: return@update emptyMap()
            mapOf(driven.device to (if (driven.lostAt == null) driven.copy(lostAt = now) else driven))
        }
        publish()
    }

    private suspend fun onFrame(text: String) {
        val m = try {
            SyncJson.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return
        when (m.str("t")) {
            "ready" -> {
                val live = (m["peers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.takeIf { p -> p.str("type") == "web" }?.str("device") }.toSet()
                browsers.update { all -> all.filterKeys { it in live } }
                publish()
            }
            "peer" -> if (m.str("type") == "web" && (m["on"] as? JsonPrimitive)?.booleanOrNull == false) drop(m.str("device"))
            "state" -> onState(m.str("from"), m["env"])
            "epoch" -> guard(Unit) { repo.refresh() }
            "error" -> when (m.str("code")) {
                "epoch", "rotation_due" -> guard(Unit) { repo.refresh() }
                "offline" -> drop(m.str("to"))
            }
        }
    }

    private suspend fun onState(from: String?, envJson: kotlinx.serialization.json.JsonElement?) {
        val name = from?.let(names.value::get) ?: return // not a browser this phone verified
        val st = guard(null) {
            val env = SyncJson.decodeFromJsonElement(SyncEnvelope.serializer(), envJson ?: return@guard null)
            var sp = store.space() ?: return@guard null
            if (sp.keyFor(env.e) == null && env.e > sp.epoch) {
                repo.refresh() // a rotation this phone hasn't caught up with
                sp = store.space() ?: return@guard null
            }
            val key = dataKey(sp, env.e) ?: return@guard null
            RemoteWire.readState(SyncCrypto.open(key, sp.spaceId, RemoteWire.statePlace(from), env, MAX_PLAIN))
        } ?: return
        browsers.update { it + (from to BrowserState(from, name, st, clock())) }
        publish()
        if (from == pinned.value) {
            flushParked(from)
        } else if (st.driver != null && st.driver == me && released.add(st.sid)) {
            // Still driven by this phone, which no longer drives it: the playback service stopped and came back inside the
            // browser's minute. It lets go and plays on as its own music (spec 2026-11-03 §2.3 "Resume").
            sendNow(from, RemoteCommand.Release(pause = false))
        }
    }

    private fun drop(device: String?) {
        if (device == null || device !in browsers.value) return
        browsers.update { it - device }
        publish()
    }

    private fun publish() {
        _card.value = RemoteCards.pick(browsers.value.values, clock(), driven = pinned.value, me = me.takeIf { it.isNotEmpty() })
    }

    private fun dataKey(sp: LinkedSpace, epoch: Int): ByteArray? = sp.keyFor(epoch)?.let { SyncCrypto.dataKey(it, sp.spaceId) }

    /**
     * Every store, crypto and parse step is guarded: a Keystore hiccup, a frame that doesn't open or a newer format must never
     * crash the app from a socket callback; the frame is simply not taken.
     */
    private suspend inline fun <T> guard(fallback: T, crossinline block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: HandoffWireException) {
        Log.w(TAG, "unreadable remote frame: ${e.message}")
        fallback
    } catch (e: Exception) {
        Log.w(TAG, "remote: ${e.javaClass.simpleName}")
        fallback
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    internal companion object {
        private const val TAG = "WebRemote"

        /** How often the card re-checks freshness while a browser is shown. */
        const val TICK_MS = 5_000L

        /** A state is under 2 KiB of JSON. */
        const val MAX_PLAIN = 64 * 1024

        /** Commands held for the pinned browser while this phone's socket is down (a few taps; the newest win). */
        const val MAX_PARKED = 16
    }
}
