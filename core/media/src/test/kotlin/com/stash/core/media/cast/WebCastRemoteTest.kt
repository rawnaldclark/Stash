package com.stash.core.media.cast

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.remote.BrowserState
import com.stash.core.data.weblink.remote.RemoteCards
import com.stash.core.data.weblink.remote.RemoteCommand
import com.stash.core.data.weblink.remote.RemoteItem
import com.stash.core.data.weblink.remote.RemotePhase
import com.stash.core.data.weblink.remote.RemoteState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * A linked browser as a cast receiver (Remote v2, spec 2026-11-03 §2.2): its states as [CastStatus], the commands the cast
 * wrapper's calls become, and when a session ends by itself (a tab's bye at once, the 30 s grace for a browser gone without
 * one, the 60 s one for this phone's connection, someone at the browser taking over, a first song never answered).
 */
class WebCastRemoteTest {
    private val sent = mutableListOf<RemoteCommand>()
    private val ends = mutableListOf<WebCastRemote.End>()
    private var heard = 0

    private val song = WireSong("Pink + White", "Frank Ocean", album = "Blonde", isrc = "USUM71607009")
    private val nights = WireSong("Nights", "Frank Ocean")

    private fun media(contentId: String, s: WireSong = song) =
        CastMedia(contentId, "", "", s.title, s.artist, null, null, 0L, song = s, from = "Blonde")

    private fun TestScope.remote() = WebCastRemote(
        device = CHROME,
        name = "Chrome on Windows",
        me = ME,
        send = { sent += it },
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        onEnd = { ends += it },
    ).also { it.addListener { heard++ } }

    private fun TestScope.state(
        iid: String? = null,
        next: String? = null,
        phase: RemotePhase? = RemotePhase.PLAYING,
        playing: Boolean = true,
        positionMs: Long = 10_000,
        driver: String? = ME,
        sid: String = SID,
        volume: Double? = 0.8,
        pendingPlay: Boolean = false,
        needsGesture: Boolean = false,
        lostAt: Long? = null,
        bye: Boolean = false,
    ) = BrowserState(
        CHROME,
        "Chrome on Windows",
        RemoteState(
            sid, needsGesture, playing, positionMs, 200_000, 1.0, volume, true, "Pink + White", "Frank Ocean", null,
            caps = listOf("drive"), driver = driver, iid = iid, next = next, phase = phase, pendingPlay = pendingPlay, bye = bye,
        ),
        receivedAt = testScheduler.currentTime,
        lostAt = lostAt,
    )

    // ── State → CastStatus ─────────────────────────────────────────────

    @Test fun `a driven browser's state is the receiver's status, its iid the content id`() = runTest {
        val r = remote()
        r.load(media("42#1"), 30_000, autoplay = true)
        r.onState(state(iid = "42#1", phase = RemotePhase.LOADING))
        assertThat(r.status.contentId).isEqualTo("42#1")
        assertThat(r.status.playerState).isEqualTo(CastStatus.PlayerState.BUFFERING)
        r.onState(state(iid = "42#1", phase = RemotePhase.PLAYING, positionMs = 31_000))
        assertThat(r.status.playerState).isEqualTo(CastStatus.PlayerState.PLAYING)
        assertThat(r.status.durationMs).isEqualTo(200_000)
        assertThat(r.status.volume).isWithin(1e-6f).of(0.8f)
        assertThat(r.status.canSetVolume).isTrue()
        // The position moves on while it plays, as the card's does.
        advanceTimeBy(4_000)
        assertThat(r.status.positionMs).isEqualTo(35_000)
        r.onState(state(iid = "42#1", phase = RemotePhase.PAUSED, playing = false, positionMs = 36_000))
        assertThat(r.status.playerState).isEqualTo(CastStatus.PlayerState.PAUSED)
        advanceTimeBy(4_000)
        assertThat(r.status.positionMs).isEqualTo(36_000)
        assertThat(heard).isAtLeast(3)
    }

    @Test fun `a play waiting for a click is buffering, ended is finished, failed is an error the queue skips`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1", phase = RemotePhase.PAUSED, playing = false, pendingPlay = true, needsGesture = true))
        assertThat(r.status.playerState).isEqualTo(CastStatus.PlayerState.BUFFERING)
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.NEEDS_CLICK)
        r.onState(state(iid = "42#1", phase = RemotePhase.ENDED, playing = false))
        assertThat(r.status.playerState).isEqualTo(CastStatus.PlayerState.IDLE)
        assertThat(r.status.idleReason).isEqualTo(CastStatus.IdleReason.FINISHED)
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.READY)
        r.onState(state(iid = "42#1", phase = RemotePhase.FAILED, playing = false))
        assertThat(r.status.idleReason).isEqualTo(CastStatus.IdleReason.ERROR)
    }

    @Test fun `what isn't this phone's song is nothing of ours, and a browser with no volume can't be set`() = runTest {
        val r = remote()
        r.onState(state(iid = null, driver = null, volume = null)) // its own music, before our load landed
        assertThat(r.status.contentId).isNull()
        assertThat(r.status.canSetVolume).isFalse()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "99#7")) // an id this phone never handed it
        assertThat(r.status.contentId).isNull()
        assertThat(r.status.playerState).isEqualTo(CastStatus.PlayerState.IDLE)
        assertThat(r.status.canSetVolume).isTrue()
    }

    // ── The wrapper's calls → commands ─────────────────────────────────

    @Test fun `load hands the browser the song by its id, with Playing from`() = runTest {
        val r = remote()
        r.load(media("42#1"), 30_000, autoplay = true)
        assertThat(sent).containsExactly(RemoteCommand.Load(RemoteItem("42#1", song), 30_000, true, "Blonde"))
    }

    @Test fun `queue edits within 300 ms go as one upNext, and a load drops one still waiting`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        sent.clear()
        r.setNext(null)
        r.setNext(media("43#n1", nights))
        advanceTimeBy(WebCastRemote.UP_NEXT_COALESCE_MS - 1)
        runCurrent()
        assertThat(sent).isEmpty()
        advanceTimeBy(2)
        runCurrent()
        assertThat(sent).containsExactly(RemoteCommand.UpNext(listOf(RemoteItem("43#n1", nights))))
        sent.clear()
        r.setNext(media("44#n2", nights))
        r.load(media("50#2"), 0, autoplay = true)
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(sent.filterIsInstance<RemoteCommand.UpNext>()).isEmpty()
        // Cleared: an empty upNext.
        r.setNext(null)
        advanceTimeBy(1_000)
        runCurrent()
        assertThat(sent.last()).isEqualTo(RemoteCommand.UpNext(emptyList()))
    }

    @Test fun `next jumps when the browser holds the queued song, else the wrapper loads it`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        r.setNext(media("43#n1", nights))
        assertThat(r.playNext()).isFalse() // the upNext hasn't even gone out
        advanceTimeBy(WebCastRemote.UP_NEXT_COALESCE_MS + 1)
        runCurrent()
        assertThat(r.playNext()).isFalse() // gone out, but the browser hasn't said it holds it
        r.onState(state(iid = "42#1", next = "43#n1"))
        sent.clear()
        assertThat(r.playNext()).isTrue()
        assertThat(sent).containsExactly(RemoteCommand.Jump("43#n1"))
        r.onState(state(iid = "43#n1"))
        assertThat(r.status.contentId).isEqualTo("43#n1")
    }

    @Test fun `play, pause, seek and volume are v1 commands, shown at once, never v1 next or prev`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        sent.clear()
        r.pause()
        assertThat(r.status.playerState).isEqualTo(CastStatus.PlayerState.PAUSED)
        r.seekTo(90_000)
        assertThat(r.status.positionMs).isEqualTo(90_000)
        r.play()
        r.setVolume(0.25f)
        assertThat(r.status.volume).isWithin(1e-6f).of(0.25f)
        r.setMuted(true)
        r.setMuted(false)
        r.stop()
        assertThat(sent).containsExactly(
            RemoteCommand.Pause, RemoteCommand.Seek(90_000), RemoteCommand.Play, RemoteCommand.Volume(0.25),
            RemoteCommand.Volume(0.0), RemoteCommand.Volume(0.25), RemoteCommand.Stop,
        ).inOrder()
        assertThat(sent.none { it == RemoteCommand.Next || it == RemoteCommand.Prev }).isTrue()
    }

    @Test fun `muted twice, the volume comes back to where it was before the first`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1", volume = 0.6))
        r.setMuted(true)
        r.onState(state(iid = "42#1", volume = 0.0)) // the browser says 0 back
        r.setMuted(true) // the system's mute again
        sent.clear()
        r.setMuted(false)
        assertThat(sent).containsExactly(RemoteCommand.Volume(0.6))
        // A volume set since is the one a later mute comes back to.
        r.setVolume(0.25f)
        r.setMuted(true)
        sent.clear()
        r.setMuted(false)
        assertThat(sent).containsExactly(RemoteCommand.Volume(0.25))
    }

    // ── When it ends by itself ─────────────────────────────────────────

    @Test fun `a browser gone without a bye gets the grace as Reconnecting, and carries on if it comes back`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        sent.clear()
        r.onState(null) // its socket closed (peer off, offline, not in ready)
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.RECONNECTING)
        advanceTimeBy(WebCastRemote.CLOSED_GRACE_MS - 1_000)
        runCurrent()
        // Back on a new socket: a new session id, still driven by this phone, still holding its song.
        r.onState(state(iid = "42#1", sid = "Reconnected01"))
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.READY)
        assertThat(sent).isEmpty() // it has what it should: nothing to say again
        advanceTimeBy(WebCastRemote.CLOSED_GRACE_MS * 2)
        runCurrent()
        assertThat(ends).isEmpty()
        r.onState(null)
        advanceTimeBy(WebCastRemote.CLOSED_GRACE_MS + 1)
        runCurrent()
        assertThat(ends).containsExactly(WebCastRemote.End.CLOSED)
    }

    @Test fun `the grace is 30 s, enough for a tab back 16 and a half s after a 10 s network cut`() = runTest {
        assertThat(WebCastRemote.CLOSED_GRACE_MS).isEqualTo(30_000L)
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        // The device run: a 10 s cut, the tab back 16.5 s after its socket went (past the 15 s this used to be).
        r.onState(null)
        advanceTimeBy(16_500)
        runCurrent()
        assertThat(ends).isEmpty()
        r.onState(state(iid = "42#1", sid = "Reconnected01"))
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.READY)
        // Gone again and not back: it ends at 30 s, not a moment before.
        r.onState(null)
        advanceTimeBy(29_999)
        runCurrent()
        assertThat(ends).isEmpty()
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.RECONNECTING)
        advanceTimeBy(2)
        runCurrent()
        assertThat(ends).containsExactly(WebCastRemote.End.CLOSED)
    }

    @Test fun `a tab that says bye as it closes or reloads ends the session at once, as closed`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1", positionMs = 10_000))
        advanceTimeBy(5_000)
        runCurrent()
        sent.clear()
        // Sealed ahead of time, on pagehide: its position is old and isn't taken.
        r.onState(state(iid = "42#1", positionMs = 2_000, bye = true))
        assertThat(ends).containsExactly(WebCastRemote.End.CLOSED)
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.READY) // no "Reconnecting to Chrome…" first
        assertThat(r.status.positionMs).isEqualTo(15_000)
        assertThat(sent).isEmpty()
        // Its socket closing a moment later (peer off) adds nothing: it ended once, and nothing more goes out.
        r.onState(null)
        r.play()
        advanceTimeBy(WebCastRemote.CLOSED_GRACE_MS * 2)
        runCurrent()
        assertThat(ends).containsExactly(WebCastRemote.End.CLOSED)
        assertThat(sent).isEmpty()
    }

    @Test fun `a bye ends it as closed whatever it names, while starting, away, or after the page took over unseen`() = runTest {
        // Before the first song was answered: closed, not "didn't answer" later.
        val starting = remote()
        starting.load(media("42#1"), 0, autoplay = true)
        starting.onState(state(driver = null, iid = null, bye = true))
        advanceTimeBy(WebCastRemote.NO_ANSWER_MS * 3)
        runCurrent()
        assertThat(ends).containsExactly(WebCastRemote.End.CLOSED)

        // Inside a grace (this phone's socket dropped, then the bye came through): closed at once, not at the grace's end.
        ends.clear()
        val away = remote()
        away.load(media("42#2"), 0, autoplay = true)
        away.onState(state(iid = "42#2"))
        away.onState(state(iid = "42#2", lostAt = testScheduler.currentTime))
        away.onState(state(iid = "42#2", bye = true))
        assertThat(ends).containsExactly(WebCastRemote.End.CLOSED)

        // Its page had picked its own music (a state this phone missed), then the tab closed: it is gone, so closed.
        ends.clear()
        val took = remote()
        took.load(media("42#3"), 0, autoplay = true)
        took.onState(state(iid = "42#3"))
        took.onState(state(driver = null, iid = null, bye = true))
        assertThat(ends).containsExactly(WebCastRemote.End.CLOSED)
    }

    @Test fun `a new session id from the same browser, still naming this phone as driver, is the same session`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        sent.clear()
        // Its socket reconnected (a Wi-Fi blip, a relay restart), or another phone opened Stash: a new session id each time.
        r.onState(state(iid = "42#1", sid = "NewSession01"))
        r.onState(state(iid = "42#1", sid = "NewSession02"))
        assertThat(ends).isEmpty()
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.READY)
        assertThat(r.status.contentId).isEqualTo("42#1")
        assertThat(sent).isEmpty()
        // A reloaded tab names no driver: that ends it, whatever its session id.
        r.onState(state(iid = null, driver = null, sid = "NewSession02"))
        assertThat(ends).containsExactly(WebCastRemote.End.TOOK_OVER)
    }

    @Test fun `what a new session dropped is said again, the latest play or pause, seek and volume, shown at once`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        r.setNext(media("43#n1", nights))
        advanceTimeBy(WebCastRemote.UP_NEXT_COALESCE_MS + 1)
        runCurrent()
        r.onState(state(iid = "42#1", next = "43#n1"))
        // Sealed for the old session: the browser dropped them (and says so with a state of its new one).
        r.pause()
        r.seekTo(90_000)
        r.setVolume(0.5f)
        r.play()
        r.pause()
        sent.clear()
        r.onState(state(iid = "42#1", next = "43#n1", sid = "NewSession01", playing = true, positionMs = 12_000))
        assertThat(sent).containsExactly(RemoteCommand.Seek(90_000), RemoteCommand.Volume(0.5), RemoteCommand.Pause).inOrder()
        assertThat(ends).isEmpty()
        // Not "played from elsewhere": the pause is what this phone said, and shows as such until the browser answers.
        assertThat(r.status.playerState).isEqualTo(CastStatus.PlayerState.PAUSED)
        assertThat(r.status.positionMs).isEqualTo(90_000)
        // Heard in that session, then another session that holds it all: nothing more to say.
        sent.clear()
        r.onState(state(iid = "42#1", next = "43#n1", sid = "NewSession01", playing = false, positionMs = 90_000, volume = 0.5))
        r.onState(state(iid = "42#1", next = "43#n1", sid = "NewSession02", playing = false, positionMs = 90_000, volume = 0.5))
        assertThat(sent).isEmpty()
    }

    @Test fun `a load or an upNext the browser never got goes out again on its new session`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1", positionMs = 50_000))
        // A skip while the browser was reconnecting: the load, then the song after it.
        r.load(media("50#2", nights), 0, autoplay = true)
        r.setNext(media("51#n1"))
        advanceTimeBy(WebCastRemote.UP_NEXT_COALESCE_MS + 1)
        runCurrent()
        sent.clear()
        r.onState(state(iid = "42#1", sid = "NewSession01")) // still the song before: both were dropped
        assertThat(sent).containsExactly(
            RemoteCommand.Load(RemoteItem("50#2", nights), 0, true, "Blonde"),
            RemoteCommand.UpNext(listOf(RemoteItem("51#n1", song))),
        ).inOrder()
        r.onState(state(iid = "50#2", next = "51#n1", sid = "NewSession01"))
        assertThat(r.status.contentId).isEqualTo("50#2")
        // Only the song after it missed: just that.
        r.setNext(media("52#n2", nights))
        advanceTimeBy(WebCastRemote.UP_NEXT_COALESCE_MS + 1)
        runCurrent()
        sent.clear()
        r.onState(state(iid = "50#2", next = "51#n1", sid = "NewSession02"))
        assertThat(sent).containsExactly(RemoteCommand.UpNext(listOf(RemoteItem("52#n2", nights))))
    }

    @Test fun `a browser that moved on to the held song, then reconnected, is not sent back to the song before`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        r.setNext(media("43#n1", nights))
        advanceTimeBy(WebCastRemote.UP_NEXT_COALESCE_MS + 1)
        runCurrent()
        r.onState(state(iid = "42#1", next = "43#n1"))
        r.onState(state(iid = "43#n1", positionMs = 5_000)) // the song ended there: it moved on to the one it held
        sent.clear()
        r.onState(state(iid = "43#n1", sid = "NewSession01", positionMs = 9_000))
        assertThat(sent).isEmpty() // what the phone plays now is B: no load of A
        assertThat(r.status.contentId).isEqualTo("43#n1")

        // The same when it moved on while this phone's connection was down: first heard after the move.
        val again = remote()
        again.load(media("50#1"), 0, autoplay = true)
        again.onState(state(iid = "50#1"))
        again.setNext(media("51#n1", nights))
        advanceTimeBy(WebCastRemote.UP_NEXT_COALESCE_MS + 1)
        runCurrent()
        again.onState(state(iid = "50#1", next = "51#n1"))
        again.onState(state(iid = "50#1", next = "51#n1", lostAt = testScheduler.currentTime))
        sent.clear()
        again.onState(state(iid = "51#n1", sid = "NewSession02", positionMs = 30_000))
        assertThat(sent).isEmpty()
        assertThat(again.status.contentId).isEqualTo("51#n1")
    }

    @Test fun `a queue that shifted while the browser was away doesn't send it back, the song it held is the one queued now`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        r.setNext(media("43#n1", nights))
        advanceTimeBy(WebCastRemote.UP_NEXT_COALESCE_MS + 1)
        runCurrent()
        r.onState(state(iid = "42#1", next = "43#n1"))
        r.onState(null) // gone for a moment
        // A queue edit meanwhile shifted the index: the wrapper queues the same song again under a new id, never received.
        r.setNext(media("43#n2", nights))
        advanceTimeBy(WebCastRemote.UP_NEXT_COALESCE_MS + 1)
        runCurrent()
        sent.clear()
        // Back on a new session, on the song it held (the one before ended while it was away).
        r.onState(state(iid = "43#n1", sid = "Reconnected01", positionMs = 4_000))
        // Not the song before from its end, then this one from 0:00: nothing is loaded.
        assertThat(sent).isEmpty()
        // The wrapper sees the song it queued start, so it moves on without a load of its own.
        assertThat(r.status.contentId).isEqualTo("43#n2")
        assertThat(r.status.playerState).isEqualTo(CastStatus.PlayerState.PLAYING)
        assertThat(r.status.positionMs).isEqualTo(4_000)
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.READY)
        // What it queues next goes out as the song after this one.
        r.setNext(media("44#n3"))
        advanceTimeBy(WebCastRemote.UP_NEXT_COALESCE_MS + 1)
        runCurrent()
        assertThat(sent).containsExactly(RemoteCommand.UpNext(listOf(RemoteItem("44#n3", song))))
    }

    @Test fun `a song the browser should hold but doesn't is loaded where the phone is on it now, not where its load began`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1", positionMs = 100_000))
        advanceTimeBy(5_000)
        sent.clear()
        r.onState(state(iid = null, sid = "NewSession01", playing = false, phase = null)) // driven still, its song gone
        assertThat(sent).containsExactly(RemoteCommand.Load(RemoteItem("42#1", song), 105_000, true, "Blonde"))
    }

    @Test fun `a later song unanswered is sent once more, then an error the queue steps over`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        r.load(media("50#2", nights), 20_000, autoplay = true)
        sent.clear()
        advanceTimeBy(WebCastRemote.NO_ANSWER_MS + 1)
        runCurrent()
        assertThat(sent).containsExactly(RemoteCommand.Load(RemoteItem("50#2", nights), 20_000, true, "Blonde"))
        assertThat(r.status.idleReason).isNotEqualTo(CastStatus.IdleReason.ERROR)
        advanceTimeBy(WebCastRemote.NO_ANSWER_MS)
        runCurrent()
        // The wrapper reads this as a remote error: the queue's recovery skips it, through the cascade guard.
        assertThat(r.status.contentId).isEqualTo("50#2")
        assertThat(r.status.playerState).isEqualTo(CastStatus.PlayerState.IDLE)
        assertThat(r.status.idleReason).isEqualTo(CastStatus.IdleReason.ERROR)
        assertThat(ends).isEmpty()
        // The next song starts afresh, and an answered one never fails.
        r.load(media("51#3"), 0, autoplay = true)
        assertThat(r.status.idleReason).isNotEqualTo(CastStatus.IdleReason.ERROR)
        r.onState(state(iid = "51#3"))
        advanceTimeBy(WebCastRemote.NO_ANSWER_MS * 3)
        runCurrent()
        assertThat(r.status.contentId).isEqualTo("51#3")
        assertThat(r.status.idleReason).isEqualTo(CastStatus.IdleReason.NONE)
    }

    @Test fun `while this phone's connection is down nothing goes unanswered, and what it missed goes out on its return`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        r.onState(state(iid = "42#1", lostAt = testScheduler.currentTime))
        // A skip and a pause from the lock screen meanwhile (WebRemote couldn't send them).
        r.load(media("50#2", nights), 0, autoplay = true)
        r.pause()
        sent.clear()
        advanceTimeBy(WebCastRemote.LOST_GRACE_MS - 5_000)
        runCurrent()
        assertThat(sent).isEmpty()
        assertThat(r.status.idleReason).isNotEqualTo(CastStatus.IdleReason.ERROR)
        // Back: the browser minted a new session as this phone came on, and still plays the song before.
        r.onState(state(iid = "42#1", sid = "NewSession01"))
        assertThat(sent).containsExactly(RemoteCommand.Load(RemoteItem("50#2", nights), 0, false, "Blonde"))
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.READY)
        r.onState(state(iid = "50#2", sid = "NewSession01", playing = false, phase = RemotePhase.PAUSED))
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(ends).isEmpty()
        assertThat(r.status.contentId).isEqualTo("50#2")
    }

    @Test fun `a stale state naming this phone, from a session before, is no take-over when the release lands`() = runTest {
        val r = remote()
        // Picked again right after the service restarted: the browser still names this phone, on a song of the session before.
        r.onState(state(iid = "17#4"))
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = null, driver = null)) // the release the session before sent lands
        assertThat(ends).isEmpty()
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.STARTING)
        r.onState(state(iid = "42#1"))
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.READY)
        r.onState(state(iid = null, driver = null)) // now it is ours: someone there took it
        assertThat(ends).containsExactly(WebCastRemote.End.TOOK_OVER)
    }

    @Test fun `a stop the browser missed is said again while it still plays this phone's song`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        r.stop()
        sent.clear()
        r.onState(state(iid = "42#1", sid = "NewSession01"))
        assertThat(sent).containsExactly(RemoteCommand.Stop)
    }

    @Test fun `a tab silent for a minute counts as gone, then the grace`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        advanceTimeBy(RemoteCards.STALE_MS + 1)
        runCurrent()
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.RECONNECTING)
        assertThat(ends).isEmpty()
        advanceTimeBy(WebCastRemote.CLOSED_GRACE_MS + 1)
        runCurrent()
        assertThat(ends).containsExactly(WebCastRemote.End.CLOSED)
    }

    @Test fun `this phone's connection down gets 60 s from the drop, and the browser plays on meanwhile`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1", positionMs = 10_000))
        val dropAt = testScheduler.currentTime
        r.onState(state(iid = "42#1", positionMs = 10_000, lostAt = dropAt))
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.RECONNECTING)
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(r.status.positionMs).isEqualTo(40_000) // still moving: it holds the song and plays on
        advanceTimeBy(WebCastRemote.LOST_GRACE_MS - 30_000 - 1_000)
        runCurrent()
        assertThat(ends).isEmpty()
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(ends).containsExactly(WebCastRemote.End.LOST)
    }

    @Test fun `someone at the browser picking their own music ends the session, so does a reload that said no bye`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(iid = "42#1"))
        r.onState(state(driver = null, iid = null)) // the page picked its own song: driver dropped
        assertThat(ends).containsExactly(WebCastRemote.End.TOOK_OVER)
        sent.clear()
        r.play() // ended: nothing more goes out
        assertThat(sent).isEmpty()

        ends.clear()
        val again = remote()
        again.load(media("42#2"), 0, autoplay = true)
        again.onState(state(iid = "42#2"))
        again.onState(state(driver = null, iid = null, sid = "ReloadedTab01"))
        assertThat(ends).containsExactly(WebCastRemote.End.TOOK_OVER)
    }

    @Test fun `states before the first load landed are not a take-over`() = runTest {
        val r = remote()
        r.onState(state(driver = null, iid = null)) // still its own music when picked
        r.load(media("42#1"), 0, autoplay = true)
        r.onState(state(driver = null, iid = null))
        assertThat(ends).isEmpty()
        r.onState(state(iid = "42#1"))
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.READY)
    }

    @Test fun `the first song unanswered for 10 s ends it as no answer, answered, it never does`() = runTest {
        val r = remote()
        r.load(media("42#1"), 0, autoplay = true)
        assertThat(r.link.value).isEqualTo(WebCastRemote.Link.STARTING)
        advanceTimeBy(WebCastRemote.NO_ANSWER_MS + 1)
        runCurrent()
        assertThat(ends).containsExactly(WebCastRemote.End.NO_ANSWER)

        ends.clear()
        val answered = remote()
        answered.load(media("42#2"), 0, autoplay = true)
        advanceTimeBy(3_000)
        answered.onState(state(iid = "42#2", phase = RemotePhase.LOADING))
        assertThat(answered.link.value).isEqualTo(WebCastRemote.Link.READY)
        advanceTimeBy(WebCastRemote.NO_ANSWER_MS)
        runCurrent()
        assertThat(ends).isEmpty()
    }

    @Test fun `a content id that isn't a wire id is made one, and still read back`() = runTest {
        val r = remote()
        r.load(media("radio item/7#1"), 0, autoplay = true)
        val id = (sent.single() as RemoteCommand.Load).item.id
        assertThat(id).matches("^[A-Za-z0-9_.:#-]{1,64}$")
        r.onState(state(iid = id))
        assertThat(r.status.contentId).isEqualTo("radio item/7#1")
    }

    private companion object {
        const val CHROME = "d_ChromeWindows01"
        const val ME = "d_P1xel5Ph0ne0001"
        const val SID = "Zk3mQ9xL2pRt"
    }
}
