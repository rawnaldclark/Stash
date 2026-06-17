package com.stash.core.media.listening

import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.lastfm.LastFmScrobbler
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackSkipEventEntity
import com.stash.core.media.PlayerRepository
import com.stash.core.media.StreamRoutingResult
import com.stash.core.media.StreamingHaltedEvent
import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the two behaviours the [ListeningRecorder] arbitrates:
 *
 *   - Skip detection (track-change collector): switching tracks before
 *     the threshold fire records a [TrackSkipEventEntity] — this feeds
 *     the skip-rate penalty in MixGenerator, so the coverage stays here.
 *   - Repeat-one loop detection (position collector): a same-track
 *     position reset back to near zero under [RepeatMode.ONE] schedules a
 *     fresh scrobble session so each loop counts as its own play.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningRecorderSkipTest {

    /**
     * Real fake — bypasses mockk for the [PlayerRepository] so the
     * collectors' subscriptions to [playerState] and [currentPosition]
     * are honoured by the underlying StateFlows without any proxy in
     * between.
     */
    private class FakePlayerRepository(
        initial: PlayerState,
    ) : PlayerRepository {
        private val stateFlow = MutableStateFlow(initial)
        private val positionFlow = MutableStateFlow(0L)

        override val playerState: StateFlow<PlayerState> get() = stateFlow
        override val currentPosition: Flow<Long> get() = positionFlow
        override val streamingHaltedEvents: SharedFlow<StreamingHaltedEvent> =
            MutableSharedFlow<StreamingHaltedEvent>().asSharedFlow()
        override val userMessages: SharedFlow<String> =
            MutableSharedFlow<String>().asSharedFlow()

        fun setState(state: PlayerState) { stateFlow.value = state }
        fun setPosition(positionMs: Long) { positionFlow.value = positionMs }

        override suspend fun play() = Unit
        override suspend fun pause() = Unit
        override suspend fun skipNext() = Unit
        override suspend fun skipPrevious() = Unit
        override suspend fun seekTo(positionMs: Long) = Unit
        override suspend fun setQueue(tracks: List<Track>, startIndex: Int) = Unit
        override fun resumeLastQueue() = Unit
        override suspend fun shuffleLibrary() = Unit
        override suspend fun addNext(track: Track) = Unit
        override suspend fun addToQueue(track: Track) = Unit
        override suspend fun addToQueue(tracks: List<Track>) = Unit
        override suspend fun toggleShuffle() = Unit
        override suspend fun cycleRepeatMode() = Unit
        override suspend fun removeFromQueue(index: Int) = Unit
        override suspend fun moveInQueue(from: Int, to: Int) = Unit
        override suspend fun skipToQueueIndex(index: Int) = Unit
        override suspend fun playTrack(track: Track) = StreamRoutingResult.NotAvailable
        override suspend fun playFromStream(item: TrackItem) = StreamRoutingResult.NotAvailable
    }

    private val trackA = Track(
        id = 1L,
        title = "A",
        artist = "X",
        album = "",
        durationMs = 180_000,
    )

    private val trackB = Track(
        id = 2L,
        title = "B",
        artist = "Y",
        album = "",
        durationMs = 180_000,
    )

    @Test
    fun `track id transition before threshold fire records a skip`() = runTest {
        val playerRepo = FakePlayerRepository(
            PlayerState(currentTrack = trackA, positionMs = 5_000),
        )
        val listeningDao = mockk<ListeningEventDao>(relaxed = true)
        val skipDao = mockk<TrackSkipEventDao>(relaxed = true)
        val skipCapture = slot<TrackSkipEventEntity>()
        coEvery { skipDao.insert(capture(skipCapture)) } returns 1L

        val recorder = ListeningRecorder(
            playerRepository = playerRepo,
            listeningEventDao = listeningDao,
            trackSkipEventDao = skipDao,
            scrobbler = mockk(relaxed = true),
            scope = backgroundScope,
        )
        recorder.start()
        // Let the collector consume the initial trackA emission and arm
        // the pending fire BEFORE we transition.
        advanceTimeBy(5_000)
        runCurrent()
        // Less than the 90s threshold for a 180s track — the delay body
        // never runs, so this MUST count as a skip.
        playerRepo.setState(PlayerState(currentTrack = trackB, positionMs = 0))
        runCurrent()
        advanceUntilIdle()

        coVerify(exactly = 1) { skipDao.insert(any()) }
        assertEquals(trackA.id, skipCapture.captured.trackId)
        coVerify(exactly = 0) { listeningDao.insert(any()) }
    }

    @Test
    fun `track id transition after threshold fire records listen but no skip`() = runTest {
        val playerRepo = FakePlayerRepository(
            PlayerState(currentTrack = trackA, positionMs = 0),
        )
        val listeningDao = mockk<ListeningEventDao>(relaxed = true)
        val skipDao = mockk<TrackSkipEventDao>(relaxed = true)
        val listenCapture = slot<ListeningEventEntity>()
        coEvery { listeningDao.insert(capture(listenCapture)) } returns 1L

        val recorder = ListeningRecorder(
            playerRepository = playerRepo,
            listeningEventDao = listeningDao,
            trackSkipEventDao = skipDao,
            scrobbler = mockk(relaxed = true),
            scope = backgroundScope,
        )
        recorder.start()
        // Threshold for a 180s track = min(90_000, 240_000) coerced into
        // [30s, 4min] = 90_000ms. Advance past it so the delay body runs
        // and sets firedFlag = true BEFORE we transition to track B.
        advanceTimeBy(95_000)
        runCurrent()
        playerRepo.setState(PlayerState(currentTrack = trackB, positionMs = 0))
        runCurrent()
        advanceUntilIdle()

        coVerify(exactly = 1) { listeningDao.insert(any()) }
        assertEquals(trackA.id, listenCapture.captured.trackId)
        coVerify(exactly = 0) { skipDao.insert(any()) }
    }

    @Test
    fun `repeat-one loop restart schedules a new scrobble session`() = runTest {
        val playerRepo = FakePlayerRepository(
            PlayerState(currentTrack = trackA, repeatMode = RepeatMode.ONE, positionMs = 0),
        )
        val listeningDao = mockk<ListeningEventDao>(relaxed = true)
        val capture = slot<ListeningEventEntity>()
        coEvery { listeningDao.insert(capture(capture)) } returns 1L

        val recorder = ListeningRecorder(
            playerRepository = playerRepo,
            listeningEventDao = listeningDao,
            trackSkipEventDao = mockk(relaxed = true),
            scrobbler = mockk(relaxed = true),
            scope = backgroundScope,
        )
        recorder.start()
        // Let the collectors consume the initial trackA emission and arm
        // the pending fire BEFORE we loop.
        runCurrent()

        // Simulate playing past 10s then looping back to near zero.
        playerRepo.setPosition(15_000L)
        runCurrent()
        playerRepo.setPosition(500L)
        runCurrent()

        // A new threshold timer should have been scheduled — advance past it.
        advanceTimeBy(95_000)
        runCurrent()
        advanceUntilIdle()

        coVerify(atLeast = 1) { listeningDao.insert(any()) }
        assertEquals(trackA.id, capture.captured.trackId)
    }

    @Test
    fun `seek-back without landing near zero does not trigger new session`() = runTest {
        val playerRepo = FakePlayerRepository(
            PlayerState(currentTrack = trackA, repeatMode = RepeatMode.ONE, positionMs = 0),
        )
        val listeningDao = mockk<ListeningEventDao>(relaxed = true)

        val recorder = ListeningRecorder(
            playerRepository = playerRepo,
            listeningEventDao = listeningDao,
            trackSkipEventDao = mockk(relaxed = true),
            scrobbler = mockk(relaxed = true),
            scope = backgroundScope,
        )
        recorder.start()
        runCurrent()

        // Scrub from 3:00 back to 2:00 — not near zero, should not refire.
        playerRepo.setPosition(180_000L)
        runCurrent()
        playerRepo.setPosition(120_000L)
        runCurrent()

        advanceTimeBy(95_000)
        runCurrent()
        advanceUntilIdle()

        // Only the original session's threshold fire — no extra insert
        // from the scrub-back.
        coVerify(exactly = 1) { listeningDao.insert(any()) }
    }

    @Test
    fun `track change resets position tracking so B does not misfire as loop`() = runTest {
        val playerRepo = FakePlayerRepository(
            PlayerState(currentTrack = trackA, repeatMode = RepeatMode.ONE, positionMs = 0),
        )
        val listeningDao = mockk<ListeningEventDao>(relaxed = true)
        val skipDao = mockk<TrackSkipEventDao>(relaxed = true)
        val capture = slot<ListeningEventEntity>()
        coEvery { listeningDao.insert(capture(capture)) } returns 1L

        val recorder = ListeningRecorder(
            playerRepository = playerRepo,
            listeningEventDao = listeningDao,
            trackSkipEventDao = skipDao,
            scrobbler = mockk(relaxed = true),
            scope = backgroundScope,
        )
        recorder.start()
        runCurrent()

        // Play track A past 10s.
        playerRepo.setPosition(15_000L)
        runCurrent()

        // Switch to track B — Collector 2 must reset lastPositionMs and
        // lastTrackId so the first near-zero position tick for B is NOT
        // mistaken for a repeat-one loop restart.
        playerRepo.setState(
            PlayerState(currentTrack = trackB, repeatMode = RepeatMode.ONE, positionMs = 0),
        )
        playerRepo.setPosition(0L)
        runCurrent()
        playerRepo.setPosition(500L)
        runCurrent()

        advanceTimeBy(95_000)
        runCurrent()
        advanceUntilIdle()

        // Exactly one insert for track B's threshold — no double-schedule
        // from the spurious loop misfire.
        coVerify(exactly = 1) { listeningDao.insert(any()) }
        assertEquals(trackB.id, capture.captured.trackId)
    }
}
