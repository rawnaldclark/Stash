package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The next song's look-ahead when Tidal answered BUSY ([BusyLookAhead] over [PlayingSongClock]),
 * in virtual time: it waits out the Retry-After (never sooner), once, only while the current song
 * leaves time for it, and asks again. A tapped song never waits.
 *
 * The shape of 11-13 on the P5: the next song is looked ahead about 36 s into a 109 s song (the
 * player buffers a minute ahead), and the relay's seat pacing answers 503 with Retry-After 15.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BusyLookAheadTest {

    private val playing = 1L
    private val next = 2L
    private val wallAtStart = 1_800_000_000_000L

    private val flac = StreamUrl("https://tidal.example/next.flac", Long.MAX_VALUE / 2, codec = "flac", bitsPerSample = 24, origin = "tidal")

    /** The clock and the wall clock both follow the test's virtual time. */
    private class Rig(scope: TestScope, wallAtStart: Long) {
        val clock = PlayingSongClock().apply { elapsedMs = { scope.testScheduler.currentTime } }
        val wall: () -> Long = { wallAtStart + scope.testScheduler.currentTime }
        val lookAhead = BusyLookAhead(clock, wall)

        /** Virtual time of each second ask. */
        val asks = mutableListOf<Long>()
    }

    private fun TestScope.rig() = Rig(this, wallAtStart)

    /** YouTube's Opus, served because Tidal was busy for [busyForMs] more. */
    private fun Rig.busyFallback(busyForMs: Long = 15_000) =
        StreamUrl("https://yt.example/next.webm", Long.MAX_VALUE / 2, codec = "opus", origin = "youtube", losslessBusyUntilMs = wall() + busyForMs)

    private fun Rig.songPlaying(id: Long = playing, atMs: Long = 36_000, lengthMs: Long = 109_000, crossfadeMs: Long = 0) =
        clock.publish(id, atMs, lengthMs, playing = true, speed = 1f, joinLeadMs = crossfadeMs)

    private fun TestScope.askAgainOn(rig: Rig, answer: StreamUrl?): suspend () -> StreamUrl? = {
        rig.asks += testScheduler.currentTime
        answer
    }

    @Test fun `the next song's look-ahead waits out Tidal's Retry-After, asks once more and plays FLAC`() = runTest {
        val rig = rig()
        rig.songPlaying()

        val result = rig.lookAhead.settle(next, rig.busyFallback(), askAgainOn(rig, flac))

        assertThat(result).isEqualTo(flac)
        // Once, and never sooner than the relay asked (15 s): at 15 s plus the grace.
        assertThat(rig.asks).containsExactly(15_000 + BusyLookAhead.GRACE_MS)
    }

    @Test fun `a tapped song plays the fallback at once and Tidal is not asked again`() = runTest {
        val rig = rig()
        rig.songPlaying(id = next, atMs = 0) // the song being resolved IS the one playing

        val first = rig.busyFallback()
        val result = rig.lookAhead.settle(next, first, askAgainOn(rig, flac))

        assertThat(result).isSameInstanceAs(first)
        assertThat(rig.asks).isEmpty()
        assertThat(testScheduler.currentTime).isEqualTo(0)
    }

    @Test fun `nothing known about the playing song is a tap - no wait`() = runTest {
        val rig = rig() // nothing published

        val first = rig.busyFallback()

        assertThat(rig.lookAhead.settle(next, first, askAgainOn(rig, flac))).isSameInstanceAs(first)
        assertThat(rig.asks).isEmpty()
    }

    @Test fun `a wait that would run past the join plays the fallback at once`() = runTest {
        val rig = rig()
        rig.songPlaying(atMs = 100_000) // 9 s left; 15 s busy

        val first = rig.busyFallback()

        assertThat(rig.lookAhead.settle(next, first, askAgainOn(rig, flac))).isSameInstanceAs(first)
        assertThat(rig.asks).isEmpty()
        assertThat(testScheduler.currentTime).isEqualTo(0)
    }

    @Test fun `the crossfade's length comes off the time to spare`() = runTest {
        val rig = rig()
        // 29 s left: room for 15 s of busy plus the ask and the start (28.5 s)...
        rig.songPlaying(atMs = 80_000)
        assertThat(rig.lookAhead.settle(next, rig.busyFallback(), askAgainOn(rig, flac))).isEqualTo(flac)

        // ...but not once a 12 s crossfade starts the next song sooner.
        val rig2 = rig()
        rig2.songPlaying(atMs = 80_000, crossfadeMs = 12_000)
        val first = rig2.busyFallback()
        assertThat(rig2.lookAhead.settle(next, first, askAgainOn(rig2, flac))).isSameInstanceAs(first)
        assertThat(rig2.asks).isEmpty()
    }

    @Test fun `skipping to the song mid-wait stops the wait and plays the fallback at once`() = runTest {
        val rig = rig()
        rig.songPlaying()
        val first = rig.busyFallback()

        val settling = async { rig.lookAhead.settle(next, first, askAgainOn(rig, flac)) }
        advanceTimeBy(5_000)
        rig.songPlaying(id = next, atMs = 0, lengthMs = 213_000) // the listener pressed next
        runCurrent()

        assertThat(settling.isCompleted).isTrue()
        assertThat(settling.await()).isSameInstanceAs(first)
        assertThat(rig.asks).isEmpty()
        assertThat(testScheduler.currentTime).isEqualTo(5_000)
    }

    @Test fun `a seek near the end mid-wait stops the wait`() = runTest {
        val rig = rig()
        rig.songPlaying()
        val first = rig.busyFallback()

        val settling = async { rig.lookAhead.settle(next, first, askAgainOn(rig, flac)) }
        advanceTimeBy(5_000)
        rig.songPlaying(atMs = 100_000) // 9 s left, 10.5 s of the wait to go
        runCurrent()

        assertThat(settling.await()).isSameInstanceAs(first)
        assertThat(rig.asks).isEmpty()
    }

    @Test fun `busy again after the wait - the fallback plays and Tidal was asked only once`() = runTest {
        val rig = rig()
        rig.songPlaying()

        val first = rig.busyFallback()

        assertThat(rig.lookAhead.settle(next, first, askAgainOn(rig, null))).isSameInstanceAs(first)
        assertThat(rig.asks).hasSize(1)
    }

    @Test fun `a fallback for a song with no lossless version is never waited for`() = runTest {
        val rig = rig()
        rig.songPlaying()
        val noLossless = StreamUrl("https://yt.example/next.webm", Long.MAX_VALUE / 2, codec = "opus", origin = "youtube")

        assertThat(rig.lookAhead.settle(next, noLossless, askAgainOn(rig, flac))).isSameInstanceAs(noLossless)
        assertThat(rig.asks).isEmpty()
        assertThat(testScheduler.currentTime).isEqualTo(0)
    }

    // ── PlayingSongClock ──

    @Test fun `the time to the join runs down while the song plays, and stops while it is paused`() = runTest {
        val rig = rig()
        rig.songPlaying(atMs = 36_000, lengthMs = 109_000)
        assertThat(rig.clock.msBeforeJoin(next)).isEqualTo(73_000)
        advanceTimeBy(10_000)
        assertThat(rig.clock.msBeforeJoin(next)).isEqualTo(63_000)

        rig.clock.publish(playing, 46_000, 109_000, playing = false, speed = 1f, joinLeadMs = 0)
        advanceTimeBy(30_000)
        assertThat(rig.clock.msBeforeJoin(next)).isEqualTo(63_000)
    }

    @Test fun `at twice the speed the rest of the song takes half the time`() = runTest {
        val rig = rig()
        rig.clock.publish(playing, 49_000, 109_000, playing = true, speed = 2f, joinLeadMs = 0)

        assertThat(rig.clock.msBeforeJoin(next)).isEqualTo(30_000)
    }

    @Test fun `no time to spare for the playing song itself, or a song of unknown length`() = runTest {
        val rig = rig()
        rig.songPlaying()
        assertThat(rig.clock.msBeforeJoin(playing)).isNull()

        rig.clock.publish(playing, 0, androidx.media3.common.C.TIME_UNSET, playing = true, speed = 1f, joinLeadMs = 0)
        assertThat(rig.clock.msBeforeJoin(next)).isNull()

        rig.clock.clear()
        assertThat(rig.clock.msBeforeJoin(next)).isNull()
    }
}
