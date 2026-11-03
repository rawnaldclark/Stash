package com.stash.core.media.cast

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.remote.BrowserLink
import com.stash.core.data.weblink.remote.BrowserState
import com.stash.core.data.weblink.remote.RemoteCommand
import com.stash.core.data.weblink.remote.RemoteState
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The browsers "Play on" offers (Remote v2, spec 2026-11-03 §2.2, §3): which can be picked, and how a browser session starts and
 * ends: a browser and a Chromecast exclude each other (the newest pick wins), Listen Together refuses a browser and ends one,
 * "This phone" carries the music on here, a browser gone leaves it here paused and says so once.
 */
class BrowserOutputsTest {
    private class FakeLink : BrowserLink {
        override val live = MutableStateFlow<Map<String, BrowserState>>(emptyMap())
        override val linked = MutableStateFlow<Map<String, String>>(emptyMap())
        override var deviceId: String? = ME
        val calls = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, RemoteCommand>>()
        override fun pin(device: String) { calls += "pin $device" }
        override fun unpin() { calls += "unpin" }
        override fun sendTo(device: String, command: RemoteCommand) { sent += device to command }
        override fun release(device: String, pause: Boolean) { calls += "release $device pause=$pause" }
    }

    private class FakeSpeakers : CastDevices {
        override val connection = MutableStateFlow<CastConnection>(CastConnection.Disconnected)
        override val devices = MutableStateFlow<List<CastDevice>>(emptyList())
        override val remote = MutableStateFlow<CastRemote?>(null)
        var disconnects = 0
        override fun startScan() = Unit
        override fun stopScan() = Unit
        override fun connect(deviceId: String) = Unit
        override fun disconnect() {
            disconnects++
            remote.value = null
        }
    }

    private val link = FakeLink()
    private val speakers = FakeSpeakers()
    private val together = MutableStateFlow(false)
    private val notices = mutableListOf<BrowserNotice>()

    private fun TestScope.outputs() = BrowserOutputs(link, speakers, together, backgroundScope, { testScheduler.currentTime }).also {
        backgroundScope.launch { it.notices.collect { n -> notices += n } }
        runCurrent()
    }

    private fun TestScope.state(
        device: String = CHROME,
        caps: List<String> = listOf("drive"),
        playing: Boolean = false,
        title: String? = null,
        needsGesture: Boolean = false,
        driver: String? = null,
        iid: String? = null,
    ) = BrowserState(
        device,
        link.linked.value[device] ?: device,
        RemoteState(
            "Zk3mQ9xL2pRt", needsGesture, playing, 0, 200_000, 1.0, 1.0, true, title, title?.let { "Frank Ocean" }, null,
            caps = caps, driver = driver, iid = iid,
        ),
        receivedAt = testScheduler.currentTime,
    )

    private fun TestScope.open(vararg devices: String) {
        link.linked.value = devices.associateWith { NAMES.getValue(it) }
        link.live.value = devices.associateWith { state(it) }
    }

    // ── Picking ────────────────────────────────────────────────────────

    @Test fun `a browser whose tab is open and can drive is picked, the socket is pinned to it`() = runTest {
        open(CHROME)
        val o = outputs()

        assertThat(o.connect(CHROME)).isTrue()

        assertThat(o.remote.value).isInstanceOf(WebCastRemote::class.java)
        assertThat(o.session.value?.name).isEqualTo("Chrome on Windows")
        assertThat(o.session.value?.app).isEqualTo("Chrome")
        assertThat(link.calls).containsExactly("pin $CHROME")
        assertThat(o.connect(CHROME)).isTrue() // picked again: nothing changes
        assertThat(link.calls).containsExactly("pin $CHROME")
    }

    @Test fun `no tab open, an older tab, before the socket knows this phone or in Listen Together, it can not be picked`() = runTest {
        link.linked.value = NAMES
        link.live.value = mapOf(SAFARI to state(SAFARI, caps = emptyList()), CHROME to state(CHROME))
        val o = outputs()

        assertThat(o.connect(FIREFOX)).isFalse() // linked, no tab
        assertThat(o.connect(SAFARI)).isFalse() // a tab from before Remote v2
        link.deviceId = null
        assertThat(o.connect(CHROME)).isFalse()
        link.deviceId = ME
        together.value = true
        assertThat(o.connect(CHROME)).isFalse()
        assertThat(o.remote.value).isNull()
        assertThat(link.calls).isEmpty()
    }

    @Test fun `the sheet lists every linked browser with what picking it would do`() = runTest {
        link.linked.value = NAMES + (EDGE to "Edge on Windows")
        link.live.value = mapOf(
            CHROME to state(CHROME),
            SAFARI to state(SAFARI, caps = emptyList()),
            EDGE to state(EDGE, needsGesture = true),
            OPERA to state(OPERA, playing = true, title = "Nights"),
        )
        link.linked.value = link.linked.value + (OPERA to "Opera on Linux")
        val o = outputs()
        val seen = mutableListOf<List<BrowserOutput>>()
        backgroundScope.launch { o.browsers.collect { seen += it } }
        runCurrent()

        val byDevice = seen.last().associateBy { it.device }
        assertThat(byDevice.getValue(CHROME).kind).isEqualTo(BrowserOutput.Kind.READY)
        assertThat(byDevice.getValue(FIREFOX).kind).isEqualTo(BrowserOutput.Kind.NOT_OPEN)
        assertThat(byDevice.getValue(SAFARI).kind).isEqualTo(BrowserOutput.Kind.RELOAD)
        assertThat(byDevice.getValue(EDGE).kind).isEqualTo(BrowserOutput.Kind.NEEDS_CLICK)
        assertThat(byDevice.getValue(OPERA).kind).isEqualTo(BrowserOutput.Kind.PLAYING)
        assertThat(byDevice.getValue(OPERA).title).isEqualTo("Nights")
        assertThat(byDevice.values.filter { it.canPick }.map { it.device }).containsExactly(CHROME, EDGE, OPERA)
        assertThat(o.hasBrowser.value).isTrue()
    }

    @Test fun `a tab silent past the stale window is listed as not open again`() = runTest {
        open(CHROME)
        val o = outputs()
        val seen = mutableListOf<List<BrowserOutput>>()
        backgroundScope.launch { o.browsers.collect { seen += it } }
        runCurrent()
        assertThat(seen.last().single().kind).isEqualTo(BrowserOutput.Kind.READY)

        advanceTimeBy(70_000)
        runCurrent()

        assertThat(seen.last().single().kind).isEqualTo(BrowserOutput.Kind.NOT_OPEN)
    }

    // ── One output at a time ───────────────────────────────────────────

    @Test fun `picking a browser lets a playing speaker go`() = runTest {
        open(CHROME)
        val o = outputs()
        speakers.remote.value = mockk(relaxed = true)
        runCurrent()

        assertThat(o.connect(CHROME)).isTrue()
        runCurrent()

        assertThat(speakers.disconnects).isEqualTo(1)
        assertThat(o.remote.value).isNotNull() // the speaker going doesn't end the browser
    }

    @Test fun `a speaker connecting ends the browser's session, the newest pick wins`() = runTest {
        open(CHROME)
        val o = outputs()
        o.connect(CHROME)

        speakers.remote.value = mockk(relaxed = true)
        runCurrent()

        assertThat(o.remote.value).isNull()
        assertThat(o.session.value).isNull()
        assertThat(link.calls).containsExactly("pin $CHROME", "release $CHROME pause=true", "unpin").inOrder()
        assertThat(o.takeResumeOnPhone()).isFalse()
        assertThat(notices).isEmpty()
    }

    @Test fun `Listen Together ends a browser session, and refuses a new one`() = runTest {
        open(CHROME)
        val o = outputs()
        o.connect(CHROME)

        together.value = true
        runCurrent()

        assertThat(o.remote.value).isNull()
        assertThat(link.calls).containsExactly("pin $CHROME", "release $CHROME pause=true", "unpin").inOrder()
        assertThat(o.connect(CHROME)).isFalse()
    }

    @Test fun `picking another browser lets the first go and plays on the second`() = runTest {
        open(CHROME, FIREFOX)
        val o = outputs()
        o.connect(CHROME)
        val first = o.remote.value

        assertThat(o.connect(FIREFOX)).isTrue()

        assertThat(o.remote.value).isNotSameInstanceAs(first)
        assertThat(o.session.value?.device).isEqualTo(FIREFOX)
        assertThat(link.calls).containsExactly("pin $CHROME", "release $CHROME pause=true", "pin $FIREFOX").inOrder()
    }

    // ── Ending ─────────────────────────────────────────────────────────

    @Test fun `This phone lets the browser go paused and carries the music on here (D2)`() = runTest {
        open(CHROME)
        val o = outputs()
        o.connect(CHROME)

        o.disconnect()

        assertThat(o.remote.value).isNull()
        assertThat(link.calls).containsExactly("pin $CHROME", "release $CHROME pause=true", "unpin").inOrder()
        assertThat(o.takeResumeOnPhone()).isTrue()
        assertThat(o.takeResumeOnPhone()).isFalse() // read once
        assertThat(notices).isEmpty()
    }

    @Test fun `a browser that closed and stayed away ends it paused here, said once`() = runTest {
        open(CHROME)
        val o = outputs()
        o.connect(CHROME)

        link.live.value = emptyMap()
        runCurrent()
        assertThat(o.session.value?.link).isEqualTo(WebCastRemote.Link.RECONNECTING)
        advanceTimeBy(WebCastRemote.CLOSED_GRACE_MS + 1)
        runCurrent()

        assertThat(o.remote.value).isNull()
        assertThat(notices).containsExactly(BrowserNotice.Closed("Chrome on Windows"))
        assertThat(o.takeResumeOnPhone()).isFalse()
        assertThat(link.calls).containsExactly("pin $CHROME", "release $CHROME pause=true", "unpin").inOrder()
    }

    @Test fun `someone at the browser picking their own music ends it, and it isn't told to let go`() = runTest {
        open(CHROME)
        val o = outputs()
        o.connect(CHROME)
        link.live.value = mapOf(CHROME to state(CHROME, playing = true, title = "Pink + White", driver = ME, iid = "42#1"))
        runCurrent()

        link.live.value = mapOf(CHROME to state(CHROME, playing = true, title = "Nights", driver = null))
        runCurrent()

        assertThat(o.remote.value).isNull()
        assertThat(notices).containsExactly(BrowserNotice.OwnMusic("Chrome on Windows"))
        assertThat(link.calls).containsExactly("pin $CHROME", "unpin").inOrder()
        assertThat(o.takeResumeOnPhone()).isFalse()
    }

    @Test fun `a browser that never answered the first song leaves the music here as it was`() = runTest {
        open(CHROME)
        val o = outputs()
        o.connect(CHROME)
        val song = com.stash.core.data.weblink.handoff.WireSong("Pink + White", "Frank Ocean")
        o.remote.value!!.load(CastMedia("42#1", "", "", null, null, null, null, 0, song = song), 0, autoplay = true)

        advanceTimeBy(WebCastRemote.NO_ANSWER_MS + 1)
        runCurrent()

        assertThat(o.remote.value).isNull()
        assertThat(notices).containsExactly(BrowserNotice.NoAnswer("Chrome on Windows"))
        assertThat(o.takeResumeOnPhone()).isTrue() // it stays playing here, if it was
        assertThat(link.sent.map { it.second::class }).contains(RemoteCommand.Load::class)
    }

    @Test fun `skipped songs are said once a session`() = runTest {
        open(CHROME)
        val o = outputs()
        o.noteSkipped(2) // no session: nothing
        o.connect(CHROME)
        o.noteSkipped(2)
        o.noteSkipped(1)
        runCurrent()

        assertThat(notices).containsExactly(BrowserNotice.Skipped(2))
    }

    private companion object {
        const val ME = "d_P1xel5Ph0ne0001"
        const val CHROME = "d_ChromeWindows01"
        const val FIREFOX = "d_FirefoxMac00001"
        const val SAFARI = "d_SafariIpad00001"
        const val EDGE = "d_EdgeWindows0001"
        const val OPERA = "d_OperaLinux00001"
        val NAMES = mapOf(CHROME to "Chrome on Windows", FIREFOX to "Firefox on Mac", SAFARI to "Safari on iPad")
    }
}
