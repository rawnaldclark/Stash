package com.stash.core.media.listening

import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.lastfm.LastFmScrobbler
import com.stash.core.data.listen.ListenSinkCoordinator
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.StreamRoutingResult
import com.stash.core.media.StreamingHaltedEvent
import com.stash.core.model.PlaybackSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * History paused (Settings › Library & Storage › Listening history): the recorder writes no listen
 * and no skip, and tells no scrobble destination what's playing (owner decision 6, 2026-10-18).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningRecorderPauseTest {

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

    /** 180 s: a listen counts after 90 s of playing. */
    private val trackA = Track(id = 1L, title = "A", artist = "X", album = "", durationMs = 180_000)
    private val trackB = Track(id = 2L, title = "B", artist = "Y", album = "", durationMs = 180_000)
    private val trackC = Track(id = 3L, title = "C", artist = "Z", album = "", durationMs = 180_000)

    private class Harness(
        val listens: ListeningEventDao,
        val skips: TrackSkipEventDao,
        val scrobbler: LastFmScrobbler,
        val sinks: ListenSinkCoordinator,
    )

    private fun TestScope.start(player: FakePlayerRepository, paused: MutableStateFlow<Boolean>): Harness {
        val music = mockk<MusicRepository>().also { repo ->
            coEvery { repo.ensureTrackPersisted(any()) } coAnswers { firstArg<Track>().id }
        }
        val h = Harness(
            listens = mockk(relaxed = true),
            skips = mockk(relaxed = true),
            scrobbler = mockk(relaxed = true),
            sinks = mockk(relaxed = true),
        )
        ListeningRecorder(
            playerRepository = player,
            musicRepository = music,
            listeningEventDao = h.listens,
            trackSkipEventDao = h.skips,
            scrobbler = h.scrobbler,
            listenSinks = h.sinks,
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
            historyPaused = paused,
        ).start()
        runCurrent()
        return h
    }

    private fun playing(track: Track) = PlayerState(isPlaying = true, currentTrack = track, positionMs = 0)

    @Test fun `while paused a song played through records no listen, no skip and no now-playing`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val h = start(player, MutableStateFlow(true))

        advanceTimeBy(170_000); runCurrent() // well past the 90 s threshold
        player.setState(playing(trackB)); runCurrent()
        advanceTimeBy(5_000); runCurrent() // B is skipped early
        player.setState(playing(trackC)); runCurrent()

        coVerify(exactly = 0) { h.listens.recordCompletedListen(any()) }
        coVerify(exactly = 0) { h.skips.insert(any()) }
        coVerify(exactly = 0) { h.scrobbler.notifyNowPlaying(any(), any(), any()) }
        verify(exactly = 0) { h.sinks.notifyNowPlaying(any()) }
        coVerify(exactly = 0) { h.sinks.onListenRecorded() }
    }

    @Test fun `pausing while a song counts down means it doesn't count`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val paused = MutableStateFlow(false)
        val h = start(player, paused)

        advanceTimeBy(60_000); runCurrent()
        paused.value = true; runCurrent()
        advanceTimeBy(60_000); runCurrent() // the threshold passes while paused
        player.setState(playing(trackB)); runCurrent()

        coVerify(exactly = 0) { h.listens.recordCompletedListen(any()) }
        coVerify(exactly = 0) { h.skips.insert(any()) }
    }

    @Test fun `a song started while paused doesn't start counting at a play edge after history is turned on`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val paused = MutableStateFlow(true)
        val h = start(player, paused)

        advanceTimeBy(20_000); runCurrent()
        paused.value = false; runCurrent()
        // A pause and resume (or a rebuffer) mid-song: the play edge must not start a session.
        player.setState(player.playerState.value.copy(isPlaying = false)); runCurrent()
        player.setState(player.playerState.value.copy(isPlaying = true)); runCurrent()
        advanceTimeBy(150_000); runCurrent() // far past A's 90 s threshold

        coVerify(exactly = 0) { h.listens.recordCompletedListen(any()) }
        coVerify(exactly = 0) { h.scrobbler.notifyNowPlaying(any(), any(), any()) }
        verify(exactly = 0) { h.sinks.notifyNowPlaying(any()) }

        // Moving on: no skip for A, and B counts.
        player.setState(playing(trackB)); runCurrent()
        coVerify(exactly = 0) { h.skips.insert(any()) }
        advanceTimeBy(95_000); runCurrent()
        coVerify(exactly = 1) { h.listens.recordCompletedListen(match { it.trackId == trackB.id }) }
    }

    @Test fun `turned back on, the next song counts as usual`() = runTest {
        val player = FakePlayerRepository(playing(trackA))
        val paused = MutableStateFlow(true)
        val h = start(player, paused)

        advanceTimeBy(100_000); runCurrent()
        paused.value = false; runCurrent()
        advanceTimeBy(60_000); runCurrent()
        // A started while paused: it doesn't start counting mid-song.
        coVerify(exactly = 0) { h.listens.recordCompletedListen(any()) }

        player.setState(playing(trackB)); runCurrent()
        coVerify(exactly = 0) { h.skips.insert(any()) }
        coVerify(exactly = 1) { h.scrobbler.notifyNowPlaying("Y", "B", null) }
        advanceTimeBy(95_000); runCurrent()
        coVerify(exactly = 1) { h.listens.recordCompletedListen(match { it.trackId == trackB.id }) }
    }
}
