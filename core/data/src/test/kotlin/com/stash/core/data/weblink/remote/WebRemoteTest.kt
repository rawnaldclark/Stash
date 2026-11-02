package com.stash.core.data.weblink.remote

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.Base64Url
import com.stash.core.data.weblink.FakeSyncServer
import com.stash.core.data.weblink.InMemoryWebLinkStore
import com.stash.core.data.weblink.SyncCrypto
import com.stash.core.data.weblink.SyncEnvelope
import com.stash.core.data.weblink.SyncJson
import com.stash.core.data.weblink.WebLinkConfig
import com.stash.core.data.weblink.WebLinkRepository
import com.stash.core.data.weblink.store.LinkedSpace
import com.stash.core.data.weblink.store.RosterEntry
import java.security.interfaces.ECPublicKey
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The phone as a remote (spec §6.7, sync-v1 §5.7) over a fake socket with the real sync-v1 crypto: when it connects, what it
 * says first, the states it takes (and the ones it refuses), the commands it seals, the instant card, and when the card goes.
 */
class WebRemoteTest {
    private val store = InMemoryWebLinkStore()
    private val repo = WebLinkRepository(FakeSyncServer(), store)
    private val config = WebLinkConfig("http://127.0.0.1:8795", true)
    private val connector = FakeConnector()
    private var clock = 1_000L
    /** The wall clock the command numbers start from (0: numbers are 1, 2, 3...). */
    private var wall = 0L
    private val k = SyncCrypto.randomBytes(32)
    private val spaceId = "s_" + Base64Url.encode(SyncCrypto.randomBytes(16))
    private val key = SyncCrypto.dataKey(k, spaceId)

    private class FakeConn : LiveConnection {
        val ch = Channel<LiveEvent>(Channel.UNLIMITED)
        override val events: Flow<LiveEvent> = ch.receiveAsFlow()
        val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var closed = false
        override fun send(text: String): Boolean = (!closed).also { if (it) sent += text }
        override fun close() {
            closed = true
            ch.close()
        }
    }

    private class FakeConnector : LiveConnector {
        val opened = mutableListOf<Triple<String, String, FakeConn>>()
        override fun connect(url: String, hello: String, scope: CoroutineScope): LiveConnection = FakeConn().also { opened += Triple(url, hello, it) }
    }

    private suspend fun linked(browsers: List<Pair<String, String>> = listOf(CHROME to "Chrome on Windows")) {
        store.createIdentity("Pixel 5")
        store.saveSpace(LinkedSpace(spaceId, 1, k))
        store.saveRoster(browsers.map { (id, name) -> RosterEntry(id, "web", name, SyncCrypto.rawPublicKey(SyncCrypto.newKeyPair().public as ECPublicKey)) })
    }

    private fun TestScope.remote() = WebRemote(store, repo, config, connector, backgroundScope, { clock }, { wall }, WebRemote.TICK_MS)

    /** The commands this phone put on its socket, opened as the browser opens them, in socket order. */
    private fun sentCmds(c: FakeConn = conn): List<RemoteCmd> {
        val me = store.id!!.deviceId
        return c.sent.toList().map { Json.parseToJsonElement(it).jsonObject }.filter { it["t"]!!.jsonPrimitive.content == "cmd" }.map {
            val env = SyncJson.decodeFromJsonElement(SyncEnvelope.serializer(), it["env"]!!)
            RemoteWire.readCmd(SyncCrypto.open(key, spaceId, RemoteWire.cmdPlace(me, it["to"]!!.jsonPrimitive.content), env))!!
        }
    }

    /** The browser's replay guard (web remote.ts CommandGuard): a command runs once, in its session, numbered above the last. */
    private class Guard(var sid: String) {
        var last = 0L
        fun accept(c: RemoteCmd): Boolean = (c.sid == sid && c.n > last).also { if (it) last = c.n }
    }

    private fun stateDoc(playing: Boolean = true, needsGesture: Boolean = false, sid: String = SID, title: String = "Midnight City") =
        """{"kind":"stash-remote-state","v":1,"sid":"$sid","needsGesture":$needsGesture,"playing":$playing,"positionMs":60000,"durationMs":240000,"volume":0.6,"canSeek":true,"song":{"title":"$title","artist":"M83","artwork":[{"url":"https://i.scdn.co/a.jpg"}]}}"""

    /** A browser's state frame, sealed at `remote:<from>` (or [place]). */
    private fun stateFrame(from: String = CHROME, doc: String = stateDoc(), place: String = RemoteWire.statePlace(from)): LiveEvent {
        val env = SyncCrypto.seal(key, spaceId, 1, place, doc)
        return LiveEvent.Frame("""{"t":"state","from":"$from","env":${SyncJson.encodeToString(SyncEnvelope.serializer(), env)}}""")
    }

    private val conn get() = connector.opened.last().third

    @Test fun `no browser in the space, or not linked, means no socket at all`() = runTest {
        val r = remote()
        r.start()
        assertThat(connector.opened).isEmpty()
        store.createIdentity("Pixel 5")
        store.saveSpace(LinkedSpace(spaceId, 1, k))
        r.start()
        assertThat(connector.opened).isEmpty()
    }

    @Test fun `in the foreground with a browser, one socket to the space, hello with this phone's id and token first`() = runTest {
        linked()
        val r = remote()
        r.start()
        r.start() // once
        assertThat(connector.opened).hasSize(1)
        val (url, hello) = connector.opened[0]
        assertThat(url).isEqualTo("http://127.0.0.1:8795/v1/spaces/$spaceId/ws")
        val h = Json.parseToJsonElement(hello).jsonObject
        assertThat(h["t"]!!.jsonPrimitive.content).isEqualTo("hello")
        assertThat(h["device"]!!.jsonPrimitive.content).isEqualTo(store.id!!.deviceId)
        assertThat(Base64Url.decode(h["token"]!!.jsonPrimitive.content)).isEqualTo(store.id!!.token)
        r.stop()
        assertThat(conn.closed).isTrue()
    }

    @Test fun `a verified browser's state becomes the card, a stranger's, a misplaced one and a garbled one are not taken`() = runTest {
        linked()
        val r = remote()
        r.start()
        conn.ch.send(stateFrame(from = "d_Stranger000001"))
        conn.ch.send(stateFrame(place = RemoteWire.statePlace("d_Other00000001"))) // sealed for another place
        conn.ch.send(LiveEvent.Frame("""{"t":"state","from":"$CHROME","env":{"e":1,"n":"AAAAAAAAAAAAAAAA","c":"AAAAAAAAAAAAAAAAAAAAAA"}}"""))
        conn.ch.send(LiveEvent.Frame("not json"))
        runCurrent()
        assertThat(r.card.value).isNull()
        conn.ch.send(stateFrame())
        runCurrent()
        val c = r.card.value!!
        assertThat(c.device).isEqualTo(CHROME)
        assertThat(c.status).isEqualTo("Playing on Chrome on Windows")
        assertThat(c.title).isEqualTo("Midnight City")
        assertThat(c.positionMs).isEqualTo(60_000)
    }

    @Test fun `a command is sealed for that browser and session, numbered, and moves the card at once`() = runTest {
        linked()
        val r = remote()
        r.start()
        conn.ch.send(stateFrame())
        runCurrent()
        clock = 11_000
        assertThat(r.sendNow(RemoteCommand.Pause)).isTrue()
        assertThat(r.sendNow(RemoteCommand.Seek(151_000))).isTrue()
        assertThat(r.sendNow(RemoteCommand.Volume(0.25))).isTrue()
        val frames = conn.sent.map { Json.parseToJsonElement(it).jsonObject }
        assertThat(frames.map { it["t"]!!.jsonPrimitive.content }).containsExactly("cmd", "cmd", "cmd")
        assertThat(frames.map { it["to"]!!.jsonPrimitive.content }.toSet()).containsExactly(CHROME)
        val me = store.id!!.deviceId
        val cmds = frames.map {
            val env = SyncJson.decodeFromJsonElement(SyncEnvelope.serializer(), it["env"]!!)
            RemoteWire.readCmd(SyncCrypto.open(key, spaceId, RemoteWire.cmdPlace(me, CHROME), env))!!
        }
        assertThat(cmds.map { it.sid }.toSet()).containsExactly(SID)
        assertThat(cmds.map { it.n }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(cmds.map { it.command }).containsExactly(RemoteCommand.Pause, RemoteCommand.Seek(151_000), RemoteCommand.Volume(0.25)).inOrder()
        val c = r.card.value!!
        assertThat(c.playing).isFalse()
        assertThat(c.positionMs).isEqualTo(151_000)
        assertThat(c.volume).isWithin(1e-6f).of(0.25f)
        // A new session of that browser (a reload) starts its numbers over.
        conn.ch.send(stateFrame(doc = stateDoc(sid = "NewSession01")))
        runCurrent()
        r.sendNow(RemoteCommand.Play)
        val last = SyncJson.decodeFromJsonElement(SyncEnvelope.serializer(), Json.parseToJsonElement(conn.sent.last()).jsonObject["env"]!!)
        val cmd = RemoteWire.readCmd(SyncCrypto.open(key, spaceId, RemoteWire.cmdPlace(me, CHROME), last))!!
        assertThat(cmd.sid).isEqualTo("NewSession01")
        assertThat(cmd.n).isEqualTo(1)
    }

    @Test fun `the tab needs a click, the card says so and a play leaves it paused`() = runTest {
        linked()
        val r = remote()
        r.start()
        conn.ch.send(stateFrame(doc = stateDoc(playing = false, needsGesture = true)))
        runCurrent()
        assertThat(r.card.value!!.status).isEqualTo("Click the Stash tab once in Chrome")
        r.sendNow(RemoteCommand.Play)
        assertThat(r.card.value!!.playing).isFalse()
        assertThat(r.card.value!!.needsGesture).isTrue()
    }

    @Test fun `the card goes when the browser leaves, goes quiet, is offline, the socket drops, or the app leaves the foreground`() = runTest {
        linked()
        val r = remote()
        r.start()
        conn.ch.send(stateFrame())
        runCurrent()
        conn.ch.send(LiveEvent.Frame("""{"t":"peer","device":"$CHROME","type":"web","on":false}"""))
        runCurrent()
        assertThat(r.card.value).isNull()

        conn.ch.send(stateFrame())
        runCurrent()
        clock += RemoteCards.STALE_MS + 1
        testScheduler.advanceTimeBy(WebRemote.TICK_MS + 1)
        runCurrent()
        assertThat(r.card.value).isNull()

        conn.ch.send(stateFrame())
        runCurrent()
        conn.ch.send(LiveEvent.Frame("""{"t":"error","code":"offline","to":"$CHROME"}"""))
        runCurrent()
        assertThat(r.card.value).isNull()

        conn.ch.send(stateFrame())
        runCurrent()
        conn.ch.send(LiveEvent.Dropped)
        runCurrent()
        assertThat(r.card.value).isNull()

        conn.ch.send(stateFrame())
        runCurrent()
        assertThat(r.card.value).isNotNull()
        r.stop()
        assertThat(r.card.value).isNull()
        assertThat(r.sendNow(RemoteCommand.Pause)).isFalse()
    }

    @Test fun `ready keeps only the browsers whose socket is open`() = runTest {
        linked(listOf(CHROME to "Chrome on Windows", FIREFOX to "Firefox on Mac"))
        val r = remote()
        r.start()
        conn.ch.send(stateFrame(CHROME))
        conn.ch.send(stateFrame(FIREFOX, doc = stateDoc(playing = false, title = "Roygbiv")))
        runCurrent()
        assertThat(r.card.value!!.device).isEqualTo(CHROME) // the one playing
        conn.ch.send(LiveEvent.Frame("""{"t":"ready","me":"x","epoch":1,"peers":[{"device":"$FIREFOX","type":"web"}]}"""))
        runCurrent()
        assertThat(r.card.value!!.device).isEqualTo(FIREFOX)
        assertThat(r.card.value!!.status).isEqualTo("Paused on Firefox on Mac")
    }

    @Test fun `8 commands, background, foreground, the 9th is still accepted by a browser that kept its session`() = runTest {
        linked()
        wall = 1_793_600_000_000L
        val r = remote()
        r.start()
        conn.ch.send(stateFrame())
        runCurrent()
        repeat(8) { assertThat(r.sendNow(RemoteCommand.Seek(it * 1000L))).isTrue() }
        val guard = Guard(SID)
        assertThat(sentCmds().map(guard::accept)).containsExactly(true, true, true, true, true, true, true, true)
        r.stop() // the background: this phone forgets its numbers
        wall += 4_000
        r.start()
        conn.ch.send(stateFrame()) // an older browser that keeps its session when the phone comes back
        runCurrent()
        assertThat(r.sendNow(RemoteCommand.Pause)).isTrue()
        val ninth = sentCmds().single()
        assertThat(ninth.sid).isEqualTo(SID)
        assertThat(guard.accept(ninth)).isTrue()
        // And after a restart (a new process, the same browser session): the same.
        val again = remote()
        again.start()
        conn.ch.send(stateFrame())
        runCurrent()
        wall += 1
        assertThat(again.sendNow(RemoteCommand.Play)).isTrue()
        assertThat(guard.accept(sentCmds().single())).isTrue()
    }

    @Test fun `commands sent at the same time reach the socket in the order of their numbers`() = runTest {
        linked()
        val r = remote()
        r.start()
        conn.ch.send(stateFrame())
        runCurrent()
        withContext(Dispatchers.Default) {
            (1..40).map { i -> async { r.sendNow(RemoteCommand.Volume(i / 40.0)) } }.awaitAll()
        }
        val ns = sentCmds().map { it.n }
        assertThat(ns).hasSize(40)
        assertThat(ns).isInStrictOrder()
    }

    @Test fun `taps go out in the order they happened`() = runTest {
        linked()
        val r = remote()
        r.start()
        conn.ch.send(stateFrame())
        runCurrent()
        r.send(RemoteCommand.Pause)
        r.send(RemoteCommand.Seek(10_000))
        r.send(RemoteCommand.Volume(0.3))
        r.send(RemoteCommand.Next)
        runCurrent()
        assertThat(sentCmds().map { it.command })
            .containsExactly(RemoteCommand.Pause, RemoteCommand.Seek(10_000), RemoteCommand.Volume(0.3), RemoteCommand.Next).inOrder()
        assertThat(sentCmds().map { it.n }).isInStrictOrder()
    }

    @Test fun `the latest of foreground and background wins, so a quick background never leaves a socket open`() = runTest {
        linked()
        val r = remote()
        r.onForeground()
        r.onBackground()
        runCurrent()
        assertThat(connector.opened.all { it.third.closed }).isTrue()
        r.onForeground()
        runCurrent()
        assertThat(connector.opened.count { !it.third.closed }).isEqualTo(1)
        r.onBackground()
        r.onForeground()
        r.onBackground()
        runCurrent()
        assertThat(connector.opened.all { it.third.closed }).isTrue()
        assertThat(r.card.value).isNull()
    }

    private companion object {
        const val CHROME = "d_ChromeWindows01"
        const val FIREFOX = "d_FirefoxMac00001"
        const val SID = "Zk3mQ9xL2pRt"
    }
}
