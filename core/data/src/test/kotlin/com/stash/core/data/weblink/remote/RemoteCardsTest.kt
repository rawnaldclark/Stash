package com.stash.core.data.weblink.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** State → card (spec §6.7): which browser the card shows, its words, the needs-a-click state, staleness and the instant tap. */
class RemoteCardsTest {
    private fun state(
        playing: Boolean = true,
        position: Long = 60_000,
        duration: Long = 240_000,
        title: String? = "Midnight City",
        needsGesture: Boolean = false,
        volume: Double? = 0.6,
        canSeek: Boolean = true,
        sid: String = "Zk3mQ9xL2pRt",
    ) = RemoteState(sid, needsGesture, playing, position, duration, 1.0, volume, canSeek, title, title?.let { "M83" }, "https://i.scdn.co/a.jpg")

    private fun browser(id: String = "d_Chrome0000001", name: String = "Chrome on Windows", st: RemoteState = state(), at: Long = 1_000) =
        BrowserState(id, name, st, at)

    @Test fun `a playing browser becomes the card, with its name, song, cover, volume and where it is`() {
        val c = RemoteCards.pick(listOf(browser()), now = 1_000)!!
        assertThat(c.device).isEqualTo("d_Chrome0000001")
        assertThat(c.status).isEqualTo("Playing on Chrome on Windows")
        assertThat(c.title).isEqualTo("Midnight City")
        assertThat(c.artist).isEqualTo("M83")
        assertThat(c.art).isEqualTo("https://i.scdn.co/a.jpg")
        assertThat(c.volume).isWithin(1e-6f).of(0.6f)
        assertThat(c.canSeek).isTrue()
        assertThat(c.positionAt(1_000)).isEqualTo(60_000)
        assertThat(c.positionAt(31_000)).isEqualTo(90_000) // moves on while it plays
        assertThat(c.positionAt(1_000_000)).isEqualTo(240_000) // never past the end
    }

    @Test fun `only a browser whose caps say drive offers to play this phone's music`() {
        assertThat(RemoteCards.pick(listOf(browser()), now = 1_000)!!.canDrive).isFalse()
        val v2 = browser(st = state().copy(caps = listOf("drive")))
        assertThat(RemoteCards.pick(listOf(v2), now = 1_000)!!.canDrive).isTrue()
    }

    @Test fun `paused, its clock stands still`() {
        val c = RemoteCards.pick(listOf(browser(st = state(playing = false))), now = 1_000)!!
        assertThat(c.status).isEqualTo("Paused on Chrome on Windows")
        assertThat(c.positionAt(61_000)).isEqualTo(60_000)
    }

    @Test fun `a tab nobody clicked asks for one click, naming the browser alone`() {
        val c = RemoteCards.pick(listOf(browser(st = state(playing = false, needsGesture = true))), now = 1_000)!!
        assertThat(c.needsGesture).isTrue()
        assertThat(c.status).isEqualTo("Click the Stash tab once in Chrome")
        assertThat(RemoteCards.appName("Firefox on Mac")).isEqualTo("Firefox")
        assertThat(RemoteCards.appName("Office PC")).isEqualTo("Office PC")
        // A play can't start it, so the card doesn't pretend it did.
        val b = browser(st = state(playing = false, needsGesture = true))
        assertThat(RemoteCards.optimistic(b, RemoteCommand.Play, 2_000).state.playing).isFalse()
    }

    @Test fun `no card for nothing loaded, a stale browser, or nobody`() {
        assertThat(RemoteCards.pick(emptyList(), 0)).isNull()
        assertThat(RemoteCards.pick(listOf(browser(st = state(title = null))), 1_000)).isNull()
        assertThat(RemoteCards.pick(listOf(browser(at = 0)), RemoteCards.STALE_MS)).isNotNull()
        assertThat(RemoteCards.pick(listOf(browser(at = 0)), RemoteCards.STALE_MS + 1)).isNull()
    }

    @Test fun `no card for a tab that said bye, closing or reloading`() {
        assertThat(RemoteCards.pick(listOf(browser(st = state().copy(bye = true))), 1_000)).isNull()
        // Another browser that plays on is still the card.
        val other = browser(id = "d_Firefox000001", name = "Firefox on Mac", st = state(title = "Wait"))
        assertThat(RemoteCards.pick(listOf(browser(st = state().copy(bye = true)), other), 1_000)?.title).isEqualTo("Wait")
    }

    @Test fun `of several browsers, the one playing wins, then the newest`() {
        val paused = browser("d_Firefox000001", "Firefox on Mac", state(playing = false), at = 5_000)
        val playing = browser("d_Chrome0000001", "Chrome on Windows", state(playing = true), at = 1_000)
        assertThat(RemoteCards.pick(listOf(paused, playing), 5_000)!!.device).isEqualTo("d_Chrome0000001")
        val older = browser("d_Chrome0000001", st = state(playing = false), at = 1_000)
        assertThat(RemoteCards.pick(listOf(older, paused), 5_000)!!.device).isEqualTo("d_Firefox000001")
    }

    @Test fun `a seek bar only when the browser can seek a song of known length, no volume slider when it can't set one`() {
        assertThat(RemoteCards.pick(listOf(browser(st = state(canSeek = false))), 1_000)!!.canSeek).isFalse()
        assertThat(RemoteCards.pick(listOf(browser(st = state(duration = 0))), 1_000)!!.canSeek).isFalse()
        assertThat(RemoteCards.pick(listOf(browser(st = state(volume = null))), 1_000)!!.volume).isNull()
    }

    @Test fun `a tap moves the card at once`() {
        val b = browser(at = 1_000)
        val paused = RemoteCards.optimistic(b, RemoteCommand.Pause, 11_000)
        assertThat(paused.state.playing).isFalse()
        assertThat(paused.state.positionMs).isEqualTo(70_000) // stopped where it was
        val played = RemoteCards.optimistic(paused, RemoteCommand.Play, 20_000)
        assertThat(played.state.playing).isTrue()
        assertThat(played.state.positionMs).isEqualTo(70_000)
        assertThat(RemoteCards.optimistic(b, RemoteCommand.Seek(150_000), 2_000).state.positionMs).isEqualTo(150_000)
        assertThat(RemoteCards.optimistic(b, RemoteCommand.Volume(1.4), 2_000).state.volume).isEqualTo(1.0)
        assertThat(RemoteCards.optimistic(b, RemoteCommand.Next, 2_000)).isSameInstanceAs(b) // the next state says what's on
    }
}
