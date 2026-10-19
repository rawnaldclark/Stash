package com.stash.feature.nowplaying

import android.content.Context
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.DownloadStatus
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.data.lyrics.LyricsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The download status a queue row reports maps to what the ring and the message do. */
class DownloadWatchTest {

    @Test fun `waiting and downloading keep the ring turning`() {
        assertEquals(DownloadWatch.RUNNING, downloadWatchOf(DownloadStatus.PENDING))
        assertEquals(DownloadWatch.RUNNING, downloadWatchOf(DownloadStatus.IN_PROGRESS))
    }

    @Test fun `every settled status stops it`() {
        assertEquals(DownloadWatch.DONE, downloadWatchOf(DownloadStatus.COMPLETED))
        assertEquals(DownloadWatch.FAILED, downloadWatchOf(DownloadStatus.FAILED))
        assertEquals(DownloadWatch.CANCELLED, downloadWatchOf(DownloadStatus.SKIPPED))
        assertEquals(DownloadWatch.WAITING_FOR_LOSSLESS, downloadWatchOf(DownloadStatus.WAITING_FOR_LOSSLESS))
        assertEquals(DownloadWatch.GONE, downloadWatchOf(null))
    }

    @Test fun `only a download that stopped without its file says so`() {
        assertEquals("Couldn't download 'Song'.", downloadWatchMessage(DownloadWatch.FAILED, "Song"))
        assertEquals("Waiting for a lossless copy of 'Song'.", downloadWatchMessage(DownloadWatch.WAITING_FOR_LOSSLESS, "Song"))
        assertEquals("Download of 'Song' cancelled.", downloadWatchMessage(DownloadWatch.CANCELLED, "Song"))
        assertNull(downloadWatchMessage(DownloadWatch.DONE, "Song"))
        assertNull(downloadWatchMessage(DownloadWatch.GONE, "Song"))
        assertNull(downloadWatchMessage(DownloadWatch.RUNNING, "Song"))
    }

    @Test fun `a waiting download says why, from its row's reason`() {
        val waiting = DownloadWatch.WAITING_FOR_LOSSLESS
        assertEquals("'Song' isn't available as AAC yet.", downloadWatchMessage(waiting, "Song", com.stash.core.model.DownloadWaitReason.AAC))
        assertEquals("'Song' is waiting for the daily reset.", downloadWatchMessage(waiting, "Song", com.stash.core.model.DownloadWaitReason.RESET))
        assertEquals("Tidal is busy. Trying 'Song' again shortly.", downloadWatchMessage(waiting, "Song", com.stash.core.model.DownloadWaitReason.TIDAL_BUSY))
        assertEquals("Waiting for a lossless copy of 'Song'.", downloadWatchMessage(waiting, "Song", null))
    }
}

/**
 * Now Playing's download ring around ⋮ stops as soon as the queue row says the download stopped,
 * and says why, instead of turning for the watchdog's full three minutes and going quiet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingDownloadRingTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val track = Track(id = 42L, title = "Song", artist = "Artist", isDownloaded = false)
    private val row = MutableStateFlow(track)
    private val status = MutableStateFlow<DownloadStatus?>(DownloadStatus.PENDING)

    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { playerState } returns MutableStateFlow(PlayerState(currentTrack = track))
        every { currentPosition } returns MutableStateFlow(0L)
    }
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(any()) } returns row
        every { observeDownloadStatus(42L) } returns status
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
        coEvery { ensureTrackPersisted(any()) } returns 42L
        coEvery { queueDownload(42L) } returns true
    }
    private val likeCoordinator: LikeCoordinator = mockk(relaxed = true) {
        every { mirrorFailures } returns MutableSharedFlow()
    }

    private fun newViewModel(): NowPlayingViewModel = NowPlayingViewModel(
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        likeCoordinator = likeCoordinator,
        losslessUpgrader = mockk<LosslessUpgrader>(relaxed = true),
        lyricsRepository = mockk<LyricsRepository>(relaxed = true),
        lyricsPreference = mockk(relaxed = true),
        nowPlayingPreference = mockk(relaxed = true),
        lyricsSidecarWriter = mockk(relaxed = true),
        appContext = mockk<Context>(relaxed = true),
        sleepTimerController = mockk(relaxed = true),
        ytMusicApiClient = mockk(relaxed = true),
    )

    /** Starts a download of the playing song and returns the messages shown from then on. */
    private fun TestScope.startDownload(vm: NowPlayingViewModel): List<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.userMessages.toList(messages) }
        backgroundScope.launch { vm.isDownloadingCurrent.collect {} }
        runCurrent()
        vm.toggleDownload(track)
        runCurrent()
        assertTrue("the ring turns while the song downloads", vm.isDownloadingCurrent.value)
        return messages
    }

    @Test fun `a failed download stops the ring and says so`() = runTest(dispatcher) {
        val vm = newViewModel()
        val messages = startDownload(vm)

        status.value = DownloadStatus.IN_PROGRESS
        runCurrent()
        assertTrue(vm.isDownloadingCurrent.value)

        status.value = DownloadStatus.FAILED
        runCurrent()

        assertFalse(vm.isDownloadingCurrent.value)
        assertEquals("Couldn't download 'Song'.", messages.last())
    }

    @Test fun `a download held for a lossless copy stops the ring and says so`() = runTest(dispatcher) {
        val vm = newViewModel()
        val messages = startDownload(vm)

        status.value = DownloadStatus.WAITING_FOR_LOSSLESS
        runCurrent()

        assertFalse(vm.isDownloadingCurrent.value)
        assertEquals("Waiting for a lossless copy of 'Song'.", messages.last())
    }

    @Test fun `a finished download stops the ring without a failure message`() = runTest(dispatcher) {
        val vm = newViewModel()
        val messages = startDownload(vm)

        row.value = track.copy(isDownloaded = true, filePath = "/music/song.flac")
        runCurrent()

        assertFalse(vm.isDownloadingCurrent.value)
        assertEquals(listOf("Downloading…"), messages)
    }
}
