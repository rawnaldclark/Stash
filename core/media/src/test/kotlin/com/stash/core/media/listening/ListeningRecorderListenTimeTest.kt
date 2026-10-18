package com.stash.core.media.listening

import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.listening.ListeningDay
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.StreamRoutingResult
import com.stash.core.media.StreamingHaltedEvent
import com.stash.core.model.PlaybackSource
import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * v59: the recorder stamps each listen's local day and minute, and writes its real listening time
 * (`listened_ms`, wall time, pauses excluded) when the session ends and on each pause.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningRecorderListenTimeTest {

    private class FakePlayerRepository(initial: PlayerState) : PlayerRepository {
        private val stateFlow = MutableStateFlow(initial)
        private val positionFlow = MutableStateFlow(0L)
        private val speedFlow = MutableStateFlow(1f)

        override val playerState: StateFlow<PlayerState> get() = stateFlow
        override val currentPosition: Flow<Long> get() = positionFlow
        override val streamingHaltedEvents: SharedFlow<StreamingHaltedEvent> =
            MutableSharedFlow<StreamingHaltedEvent>().asSharedFlow()
        override val userMessages: SharedFlow<String> = MutableSharedFlow<String>().asSharedFlow()

        fun setState(state: PlayerState) { stateFlow.value = state }
        fun setPosition(positionMs: Long) { positionFlow.value = positionMs }

        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun skipNext() = Unit
        override suspend fun skipPrevious() = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun setQueue(tracks: List<Track>, startIndex: Int, source: PlaybackSource) = Unit
        override fun resumeLastQueue() = Unit
        override suspend fun shuffleLibrary(): Boolean = false
        override suspend fun startRadio(seed: com.stash.core.data.radio.RadioSeed, keepCurrent: Boolean) =
            com.stash.core.model.RadioStartResult.NoStation
        override fun stopRadio() = Unit
        override val radioSeedLabel: StateFlow<String?> = MutableStateFlow(null)
        override suspend fun addNext(track: Track) = false
        override suspend fun addToQueue(track: Track) = false
        override suspend fun addToQueue(tracks: List<Track>) = false
        override suspend fun toggleShuffle() = Unit
        override suspend fun cycleRepeatMode() = Unit
        override suspend fun removeFromQueue(index: Int) = Unit
        override suspend fun insertInQueue(index: Int, track: Track) = false
        override suspend fun moveInQueue(from: Int, to: Int) = Unit
        override suspend fun skipToQueueIndex(index: Int) = Unit
        override suspend fun playTrack(track: Track) = StreamRoutingResult.NotAvailable
        override suspend fun playFromStream(item: TrackItem) = StreamRoutingResult.NotAvailable
        override fun setVolume(volume: Float) = Unit
        override val playbackSpeed: StateFlow<Float> get() = speedFlow
        override fun setPlaybackSpeed(speed: Float) { speedFlow.value = speed }
    }

    /** 180 s: the listen counts after 90 s of playing. */
    private val trackA = Track(id = 1L, title = "A", artist = "X", album = "", durationMs = 180_000)
    private val trackB = Track(id = 2L, title = "B", artist = "Y", album = "", durationMs = 180_000)
    private val kolkata = ZoneId.of("Asia/Kolkata")

    private fun passthroughMusicRepository(): MusicRepository =
        mockk<MusicRepository>().also { repository ->
            coEvery { repository.ensureTrackPersisted(any()) } coAnswers { firstArg<Track>().id }
        }

    private fun TestScope.recorder(
        player: FakePlayerRepository,
        dao: ListeningEventDao,
        backfill: suspend () -> Unit = {},
    ) = ListeningRecorder(
        playerRepository = player,
        musicRepository = passthroughMusicRepository(),
        listeningEventDao = dao,
        trackSkipEventDao = mockk(relaxed = true),
        scrobbler = mockk(relaxed = true),
        listenSinks = mockk(relaxed = true),
        scope = backgroundScope,
        nowMs = { testScheduler.currentTime },
        zone = { kolkata },
        backfillListeningDays = backfill,
    )

    private fun playing(track: Track, repeat: RepeatMode = RepeatMode.OFF) =
        PlayerState(isPlaying = true, currentTrack = track, positionMs = 0, repeatMode = repeat)

    private fun paused(track: Track) = PlayerState(isPlaying = false, currentTrack = track, positionMs = 0)

    @Test fun `a listen is stamped with its local day and minute and its time is written on the next song`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val dao = mockk<ListeningEventDao>(relaxed = true)
        val listen = slot<ListeningEventEntity>()
        coEvery { dao.recordCompletedListen(capture(listen)) } returns 55L
        recorder(player, dao).start()
        runCurrent()

        advanceTimeBy(150_000) // the listen is recorded at 90 s; the song keeps playing
        runCurrent()
        coVerify(exactly = 1) { dao.recordCompletedListen(any()) }
        coVerify(exactly = 0) { dao.setListenedMs(any(), any()) }
        val stamp = ListeningDay.localStamp(listen.captured.startedAt, kolkata)
        assertEquals(stamp.day, listen.captured.localDay)
        assertEquals(stamp.minute, listen.captured.localMinute)
        assertNull("still playing when the row is written", listen.captured.listenedMs)

        player.setState(playing(trackB))
        runCurrent()
        coVerify(exactly = 1) { dao.setListenedMs(55L, 150_000L) }
    }

    @Test fun `pauses are not listening time, and a pause after the listen writes the time so far`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val dao = mockk<ListeningEventDao>(relaxed = true)
        coEvery { dao.recordCompletedListen(any()) } returns 55L
        recorder(player, dao).start()
        runCurrent()

        advanceTimeBy(60_000); runCurrent()
        player.setState(paused(trackA)); runCurrent() // nothing recorded yet: no write
        advanceTimeBy(300_000); runCurrent()
        player.setState(playing(trackA)); runCurrent()
        advanceTimeBy(40_000); runCurrent() // 100 s played: recorded at 90 s
        player.setState(paused(trackA)); runCurrent()
        advanceTimeBy(1_000_000); runCurrent()
        player.setState(playing(trackA)); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        player.setState(playing(trackB)); runCurrent()

        coVerifyOrder {
            dao.recordCompletedListen(any())
            dao.setListenedMs(55L, 100_000L)
            dao.setListenedMs(55L, 130_000L)
        }
        coVerify(exactly = 2) { dao.setListenedMs(any(), any()) }
    }

    @Test fun `a repeat-one restart ends the listen and starts a new one`() = runTest {
        val player = FakePlayerRepository(playing(trackA, RepeatMode.ONE))
        val dao = mockk<ListeningEventDao>(relaxed = true)
        coEvery { dao.recordCompletedListen(any()) } returnsMany listOf(55L, 56L)
        recorder(player, dao).start()
        runCurrent()

        advanceTimeBy(120_000); runCurrent()
        player.setPosition(119_000L); runCurrent()
        player.setPosition(500L); runCurrent() // looped
        coVerify(exactly = 1) { dao.setListenedMs(55L, 120_000L) }

        advanceTimeBy(100_000); runCurrent()
        player.setState(playing(trackB)); runCurrent()
        coVerify(exactly = 2) { dao.recordCompletedListen(any()) }
        coVerify(exactly = 1) { dao.setListenedMs(56L, 100_000L) }
    }

    @Test fun `a pause during a slow repeat-one write arms no countdown and records nothing unheard`() = runTest {
        val player = FakePlayerRepository(playing(trackA, RepeatMode.ONE))
        val dao = mockk<ListeningEventDao>(relaxed = true)
        coEvery { dao.recordCompletedListen(any()) } returns 55L
        val slowWrite = CompletableDeferred<Unit>()
        coEvery { dao.setListenedMs(any(), any()) } coAnswers { slowWrite.await() }
        recorder(player, dao).start()
        runCurrent()

        advanceTimeBy(120_000); runCurrent() // listen 55 recorded at 90 s
        player.setPosition(119_000L); runCurrent()
        player.setPosition(500L); runCurrent() // looped: the final write of 55 is in flight
        player.setState(paused(trackA).copy(repeatMode = RepeatMode.ONE)); runCurrent() // paused meanwhile
        slowWrite.complete(Unit); runCurrent()

        advanceTimeBy(1_000_000); runCurrent() // far past a second threshold, still paused
        coVerify(exactly = 1) { dao.recordCompletedListen(any()) }
        coVerify(exactly = 1) { dao.setListenedMs(55L, 120_000L) }
    }

    @Test fun `rebuffers shorter than 5 s of new listening are not written until the listen ends`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val dao = mockk<ListeningEventDao>(relaxed = true)
        coEvery { dao.recordCompletedListen(any()) } returns 55L
        recorder(player, dao).start()
        runCurrent()

        advanceTimeBy(100_000); runCurrent() // recorded at 90 s
        player.setState(paused(trackA)); runCurrent() // first write: 100 s
        repeat(4) { // four stalls, 2 s of playing between them
            player.setState(playing(trackA)); runCurrent()
            advanceTimeBy(2_000); runCurrent()
            player.setState(paused(trackA)); runCurrent()
        }
        // Stalls at 102, 104, 106, 108 s: only 106 is 5 s or more past the last write (100).
        player.setState(playing(trackA)); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        player.setState(playing(trackB)); runCurrent() // final: always written

        coVerifyOrder {
            dao.setListenedMs(55L, 100_000L)
            dao.setListenedMs(55L, 106_000L)
            dao.setListenedMs(55L, 109_000L)
        }
        coVerify(exactly = 3) { dao.setListenedMs(any(), any()) }
    }

    @Test fun `after a pause the row holds the time so far until the listen ends`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val dao = mockk<ListeningEventDao>(relaxed = true)
        coEvery { dao.recordCompletedListen(any()) } returns 55L
        recorder(player, dao).start()
        runCurrent()
        advanceTimeBy(100_000); runCurrent()
        player.setState(paused(trackA)); runCurrent()
        player.setState(playing(trackA)); runCurrent()
        advanceTimeBy(50_000); runCurrent()
        // The app dying here leaves 100 s: a lower bound, and above the 90 s it took to count.
        coVerify(exactly = 1) { dao.setListenedMs(55L, 100_000L) }
        coVerify(exactly = 1) { dao.setListenedMs(any(), any()) }
    }

    @Test fun `a skip writes no listening time`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val dao = mockk<ListeningEventDao>(relaxed = true)
        recorder(player, dao).start()
        runCurrent()
        advanceTimeBy(20_000); runCurrent()
        player.setState(playing(trackB)); runCurrent()
        advanceUntilIdle()
        coVerify(exactly = 0) { dao.setListenedMs(any(), any()) }
    }

    @Test fun `a song changed while its row is being written still gets its final time`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val dao = mockk<ListeningEventDao>(relaxed = true)
        val gate = CompletableDeferred<Unit>()
        coEvery { dao.recordCompletedListen(any()) } coAnswers { gate.await(); 55L }
        recorder(player, dao).start()
        runCurrent()

        advanceTimeBy(95_000); runCurrent() // the write is in flight
        player.setState(playing(trackB)); runCurrent()
        coVerify(exactly = 0) { dao.setListenedMs(any(), any()) }
        gate.complete(Unit)
        runCurrent()
        coVerify(exactly = 1) { dao.setListenedMs(55L, 95_000L) }
    }

    @Test fun `listening time is wall time at 2x`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        player.setPlaybackSpeed(2f)
        val dao = mockk<ListeningEventDao>(relaxed = true)
        coEvery { dao.recordCompletedListen(any()) } returns 55L
        recorder(player, dao).start()
        runCurrent()
        advanceTimeBy(60_000); runCurrent() // 90 s of the song in 45 s: recorded
        coVerify(exactly = 1) { dao.recordCompletedListen(any()) }
        player.setState(playing(trackB)); runCurrent()
        coVerify(exactly = 1) { dao.setListenedMs(55L, 60_000L) }
    }

    @Test fun `start runs the day backfill without holding up listening`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val dao = mockk<ListeningEventDao>(relaxed = true)
        coEvery { dao.recordCompletedListen(any()) } returns 55L
        val backfillGate = CompletableDeferred<Unit>()
        var backfillStarted = false
        recorder(player, dao) { backfillStarted = true; backfillGate.await() }.start()
        runCurrent()
        advanceTimeBy(95_000); runCurrent()
        assertTrue(backfillStarted)
        coVerify(exactly = 1) { dao.recordCompletedListen(any()) }
        backfillGate.complete(Unit)
    }
}
