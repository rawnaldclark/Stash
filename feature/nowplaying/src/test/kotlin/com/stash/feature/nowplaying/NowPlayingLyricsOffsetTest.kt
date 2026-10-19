package com.stash.feature.nowplaying

import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.data.lyrics.LyricsRepository
import com.stash.feature.nowplaying.ui.LyricsOutput
import com.stash.feature.nowplaying.ui.LyricsOutputOffset
import com.stash.feature.nowplaying.ui.OUTPUT_OFFSET_CAP_MS
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The per-output lyrics offset ("Apply to all songs on <device>"): it shifts the lyrics for every
 * song on that output, on top of the song's own, and leaves other outputs alone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingLyricsOffsetTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val headphones = LyricsOutput(key = "BLUETOOTH:WH-1000XM4", name = "WH-1000XM4")
    private val wired = LyricsOutput(key = "WIRED:Wired headphones", name = "Wired headphones")

    /** The output playing now, and an in-memory offset store. */
    private class FakeOutputs(initial: LyricsOutput?) : LyricsOutputOffsets {
        val current = MutableStateFlow(initial)
        val stored = MutableStateFlow<Map<String, Long>>(emptyMap())
        override val output: Flow<LyricsOutput?> = current
        override fun offsetMs(output: LyricsOutput): Flow<Long> = stored.map { it[output.key] ?: 0L }
        override suspend fun setOffsetMs(output: LyricsOutput, offsetMs: Long) {
            stored.value = stored.value + (output.key to offsetMs)
        }
    }

    private val positionFlow = MutableStateFlow(10_000L)
    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { playerState } returns MutableStateFlow(
            PlayerState(currentTrack = Track(id = 7L, title = "song", artist = "artist"), isPlaying = true, durationMs = 200_000L),
        )
        every { currentPosition } returns positionFlow
    }

    private fun newViewModel(
        outputs: LyricsOutputOffsets,
        musicRepository: MusicRepository = mockk(relaxed = true) {
            every { observeTrackById(any()) } returns flowOf(null)
            every { observeTrackByYoutubeId(any()) } returns flowOf(null)
        },
        lyricsRepository: LyricsRepository = mockk(relaxed = true),
    ): NowPlayingViewModel = NowPlayingViewModel(
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        likeCoordinator = mockk(relaxed = true) { every { mirrorFailures } returns MutableSharedFlow() },
        losslessUpgrader = mockk(relaxed = true),
        lyricsRepository = lyricsRepository,
        lyricsPreference = mockk(relaxed = true),
        nowPlayingPreference = mockk(relaxed = true),
        lyricsSidecarWriter = mockk(relaxed = true),
        appContext = mockk(relaxed = true),
        sleepTimerController = mockk(relaxed = true),
        ytMusicApiClient = mockk(relaxed = true),
        lyricsOutputOffsets = outputs,
    )

    @Test fun `an output's offset shifts the lyrics on it, and only on it`() = runTest(dispatcher) {
        val outputs = FakeOutputs(headphones)
        val vm = newViewModel(outputs)
        backgroundScope.launch { vm.currentPositionMs.collect {} }
        runCurrent()
        assertEquals(10_000L, vm.currentPositionMs.value)

        vm.setLyricsOutputOffsetMs(300L)
        runCurrent()
        assertEquals(mapOf(headphones.key to 300L), outputs.stored.value)
        assertEquals(LyricsOutputOffset(headphones, 300L), vm.lyricsOutputOffset.value)
        assertEquals(9_700L, vm.currentPositionMs.value)

        // Unplugged from the headphones onto wired ones: their offset stays with them.
        outputs.current.value = wired
        runCurrent()
        assertEquals(10_000L, vm.currentPositionMs.value)

        outputs.current.value = headphones
        runCurrent()
        assertEquals(9_700L, vm.currentPositionMs.value)
    }

    @Test fun `an output's offset is held within its cap`() = runTest(dispatcher) {
        val outputs = FakeOutputs(headphones)
        val vm = newViewModel(outputs)
        backgroundScope.launch { vm.currentPositionMs.collect {} }
        runCurrent()

        vm.setLyricsOutputOffsetMs(30_000L)
        runCurrent()
        assertEquals(OUTPUT_OFFSET_CAP_MS, outputs.stored.value[headphones.key])
    }

    @Test fun `tapping a line lands where it is heard, the song's and the output's offsets undone`() = runTest(dispatcher) {
        // A library song with its own +0.5 s, on headphones calibrated to +0.3 s.
        val outputs = FakeOutputs(headphones).apply { stored.value = mapOf(headphones.key to 300L) }
        val vm = newViewModel(
            outputs,
            musicRepository = mockk(relaxed = true) {
                every { observeTrackById(any()) } returns flowOf(Track(id = 7L, title = "song", artist = "artist"))
                every { observeTrackByYoutubeId(any()) } returns flowOf(null)
            },
            lyricsRepository = mockk(relaxed = true) {
                every { observeSyncOffsetMs(7L) } returns flowOf(500L)
            },
        )
        backgroundScope.launch { vm.currentPositionMs.collect {} }
        runCurrent()
        assertEquals(9_200L, vm.currentPositionMs.value)

        vm.onLyricsLineSeek(20_000L)
        runCurrent()
        coVerify { playerRepository.seekTo(20_800L) }
    }

    @Test fun `with no output known there is nothing to set`() = runTest(dispatcher) {
        val outputs = FakeOutputs(null)
        val vm = newViewModel(outputs)
        backgroundScope.launch { vm.currentPositionMs.collect {} }
        runCurrent()

        vm.setLyricsOutputOffsetMs(300L)
        runCurrent()
        assertNull(vm.lyricsOutputOffset.value)
        assertTrue(outputs.stored.value.isEmpty())
        assertEquals(10_000L, vm.currentPositionMs.value)
    }
}
