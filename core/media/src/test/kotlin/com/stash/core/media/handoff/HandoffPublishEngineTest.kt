package com.stash.core.media.handoff

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblink.handoff.HandoffChannel
import com.stash.core.data.weblink.handoff.NowRead
import com.stash.core.data.weblink.handoff.PublishOutcome
import com.stash.core.data.weblink.handoff.StashNow
import com.stash.core.data.weblink.handoff.StashQueue
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** The publish triggers and debounces of spec §8.1, on virtual time: events only, never a timer. */
@OptIn(ExperimentalCoroutinesApi::class)
class HandoffPublishEngineTest {
    private class FakeChannel : HandoffChannel {
        val sent = mutableListOf<Pair<StashNow, StashQueue?>>()
        val outcomes = ArrayDeque<PublishOutcome>()
        var linked = true

        override suspend fun linked() = linked

        override suspend fun publish(now: StashNow, queue: StashQueue?): PublishOutcome {
            sent += now to queue
            return outcomes.removeFirstOrNull() ?: PublishOutcome.Sent(sent.size.toLong())
        }

        override suspend fun readNow(): NowRead? = null
        override suspend fun readQueue(device: String): StashQueue? = null
    }

    private val songs = (1..10).map { Track(id = it.toLong(), title = "Song $it", artist = "Artist $it") }

    private fun state(index: Int = 0, playing: Boolean = true, position: Long = 0, queue: List<Track> = songs, shuffle: Boolean = false) =
        PlayerState(currentTrack = queue[index], isPlaying = playing, positionMs = position, durationMs = 200_000, queue = queue, currentIndex = index, isShuffleEnabled = shuffle)

    private class Rig(val scope: TestScope, val channel: FakeChannel, val engine: HandoffPublishEngine, val network: CompletableDeferred<Unit>, var allowed: Boolean = true)

    private fun TestScope.rig(): Rig {
        val channel = FakeChannel()
        val network = CompletableDeferred<Unit>()
        lateinit var rig: Rig
        val engine = HandoffPublishEngine(
            scope = backgroundScope,
            channel = channel,
            songs = { q -> q.map { HandoffDocsBuilder.songOf(it) } },
            allowed = { rig.allowed },
            clock = { testScheduler.currentTime },
            rate = { 1.0 },
            awaitNetwork = { network.await() },
            onPlayed = {},
            io = StandardTestDispatcher(testScheduler),
        )
        rig = Rig(this, channel, engine, network)
        return rig
    }

    @Test
    fun `a play event publishes once after the 2 s debounce, with the queue the first time`() = runTest {
        val r = rig()
        r.engine.onState(state())
        advanceTimeBy(1_999)
        runCurrent()
        assertThat(r.channel.sent).isEmpty()
        advanceTimeBy(2)
        runCurrent()
        assertThat(r.channel.sent).hasSize(1)
        assertThat(r.channel.sent[0].first.playing).isTrue()
        assertThat(r.channel.sent[0].second).isNotNull() // the first publish carries the queue
    }

    @Test
    fun `no periodic publish while playing - an hour of the same song sends nothing more`() = runTest {
        val r = rig()
        r.engine.onState(state(position = 0))
        advanceTimeBy(3_000)
        runCurrent()
        assertThat(r.channel.sent).hasSize(1)
        // Buffering refreshes that report the position where it should be are not events.
        for (t in 1..60) {
            advanceTimeBy(60_000)
            r.engine.onState(state(position = testScheduler.currentTime))
        }
        runCurrent()
        assertThat(r.channel.sent).hasSize(1)
    }

    @Test
    fun `rapid skips collapse into one publish of the last song`() = runTest {
        val r = rig()
        r.engine.onState(state(index = 0))
        advanceTimeBy(3_000)
        runCurrent()
        for (i in 1..4) {
            r.engine.onState(state(index = i))
            advanceTimeBy(500)
        }
        advanceTimeBy(2_000)
        runCurrent()
        assertThat(r.channel.sent).hasSize(2)
        assertThat(r.channel.sent.last().first.song!!.title).isEqualTo("Song 5")
    }

    @Test
    fun `pause and seek are events, a play position moving with time is not`() = runTest {
        val r = rig()
        r.engine.onState(state(position = 10_000))
        advanceTimeBy(5_000)
        runCurrent()
        // 5 s later the song is at 15 s: as expected, nothing to say.
        r.engine.onState(state(position = 15_000))
        advanceTimeBy(5_000)
        runCurrent()
        assertThat(r.channel.sent).hasSize(1)
        // A seek to 1:00.
        r.engine.onState(state(position = 60_000))
        advanceTimeBy(2_500)
        runCurrent()
        assertThat(r.channel.sent).hasSize(2)
        // Pause.
        r.engine.onState(state(position = 62_000, playing = false))
        advanceTimeBy(2_500)
        runCurrent()
        assertThat(r.channel.sent).hasSize(3)
        assertThat(r.channel.sent.last().first.playing).isFalse()
        assertThat(r.channel.sent.last().first.positionMs).isEqualTo(62_000)
    }

    @Test
    fun `a queue edit waits 10 s and goes once, and an unchanged queue is not sent again`() = runTest {
        val r = rig()
        r.engine.onState(state())
        advanceTimeBy(3_000)
        runCurrent()
        assertThat(r.channel.sent).hasSize(1)
        val edited = songs.take(8)
        r.engine.onState(state(queue = edited))
        advanceTimeBy(9_000)
        runCurrent()
        assertThat(r.channel.sent).hasSize(1)
        advanceTimeBy(1_500)
        runCurrent()
        assertThat(r.channel.sent).hasSize(2)
        assertThat(r.channel.sent[1].second!!.items).hasSize(8)
        // A song change afterwards: the now goes, the queue (same id) doesn't.
        r.engine.onState(state(index = 1, queue = edited))
        advanceTimeBy(2_500)
        runCurrent()
        assertThat(r.channel.sent).hasSize(3)
        assertThat(r.channel.sent[2].second).isNull()
    }

    @Test
    fun `going to the background publishes at once, a pending queue edit included`() = runTest {
        val r = rig()
        r.engine.onState(state())
        advanceTimeBy(3_000)
        runCurrent()
        r.engine.onState(state(queue = songs.take(5)))
        advanceTimeBy(1_000)
        r.engine.flush()
        runCurrent()
        assertThat(r.channel.sent).hasSize(2)
        assertThat(r.channel.sent[1].second!!.items).hasSize(5)
        // Nothing left pending: the debounces were cancelled.
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(r.channel.sent).hasSize(2)
    }

    @Test
    fun `background while paused with nothing pending sends nothing`() = runTest {
        val r = rig()
        r.engine.onState(state(playing = false))
        advanceTimeBy(3_000)
        runCurrent()
        r.engine.flush()
        runCurrent()
        assertThat(r.channel.sent).hasSize(1)
    }

    @Test
    fun `offline keeps only the newest state and retries it once when the network is back`() = runTest {
        val r = rig()
        r.channel.outcomes += PublishOutcome.Offline
        r.channel.outcomes += PublishOutcome.Offline
        r.engine.onState(state(index = 0))
        advanceTimeBy(3_000)
        runCurrent()
        r.engine.onState(state(index = 2))
        advanceTimeBy(3_000)
        runCurrent()
        assertThat(r.channel.sent).hasSize(2)
        r.network.complete(Unit)
        runCurrent()
        assertThat(r.channel.sent).hasSize(3)
        assertThat(r.channel.sent.last().first.song!!.title).isEqualTo("Song 3")
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(r.channel.sent).hasSize(3)
    }

    @Test
    fun `a 429 holds every write until Retry-After, then sends the newest state once`() = runTest {
        val r = rig()
        r.channel.outcomes += PublishOutcome.RateLimited(30_000)
        r.engine.onState(state(index = 0))
        advanceTimeBy(2_100)
        runCurrent()
        assertThat(r.channel.sent).hasSize(1)
        r.engine.onState(state(index = 1))
        advanceTimeBy(2_100)
        runCurrent()
        assertThat(r.channel.sent).hasSize(1) // held
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(r.channel.sent).hasSize(2)
        assertThat(r.channel.sent.last().first.song!!.title).isEqualTo("Song 2")
    }

    @Test
    fun `nothing is published when switched off or not linked`() = runTest {
        val r = rig()
        r.allowed = false
        r.engine.onState(state())
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(r.channel.sent).isEmpty()
        r.allowed = true
        r.channel.linked = false
        r.engine.onState(state(index = 3))
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(r.channel.sent).isEmpty()
    }

    @Test
    fun `the published position is extrapolated to the moment of sending`() = runTest {
        val r = rig()
        r.engine.onState(state(position = 30_000))
        advanceTimeBy(2_001)
        runCurrent()
        assertThat(r.channel.sent.single().first.positionMs).isIn(com.google.common.collect.Range.closed(32_000L, 32_100L))
    }
}
