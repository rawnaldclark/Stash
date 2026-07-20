package com.stash.feature.nowplaying

import android.content.Context
import app.cash.turbine.test
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.sidecar.LyricsSidecarWriter
import com.stash.data.ytmusic.YTMusicApiClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingLyricsExportTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val playerState = MutableStateFlow(PlayerState())
    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { this@mockk.playerState } returns this@NowPlayingLyricsExportTest.playerState
        every { currentPosition } returns MutableStateFlow(0L)
    }
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(any()) } returns flowOf(null)
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
    }
    private val lyricsRepository: LyricsRepository = mockk(relaxed = true)
    private val lyricsSidecarWriter: LyricsSidecarWriter = mockk()
    private val likeCoordinator: LikeCoordinator = mockk(relaxed = true) {
        every { mirrorFailures } returns MutableSharedFlow()
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `double tap is stable single flight and reports confirmed write`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { lyricsRepository.get(42L) } returns cachedLyrics()
        coEvery { lyricsSidecarWriter.write(42L, any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val viewModel = viewModelWithDownloadedTrack()

        viewModel.userMessages.test {
            viewModel.exportLyricsForCurrentTrack()
            viewModel.exportLyricsForCurrentTrack()
            entered.await()
            assertEquals(42L, viewModel.exportingLyricsTrackId.value)
            coVerify(exactly = 1) { lyricsSidecarWriter.write(42L, any()) }

            release.complete(Unit)
            assertEquals("Lyrics saved with the song file.", awaitItem())
            assertEquals(null, viewModel.exportingLyricsTrackId.first { it == null })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `A to B switch keeps busy state and names A`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { lyricsRepository.get(42L) } returns cachedLyrics()
        coEvery { lyricsSidecarWriter.write(42L, any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val viewModel = viewModelWithDownloadedTrack(title = "Song A")

        viewModel.userMessages.test {
            viewModel.exportLyricsForCurrentTrack()
            entered.await()
            playerState.value = playerState.value.copy(currentTrack = downloadedTrack(84L, "Song B"))
            advanceUntilIdle()

            assertEquals(42L, viewModel.exportingLyricsTrackId.value)
            viewModel.exportLyricsForCurrentTrack()
            coVerify(exactly = 0) { lyricsRepository.get(84L) }

            release.complete(Unit)
            assertEquals("Lyrics saved with the song file for ‘Song A’.", awaitItem())
            assertEquals(null, viewModel.exportingLyricsTrackId.first { it == null })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `missing cached lyrics reports truthfully`() = runTest(dispatcher) {
        coEvery { lyricsRepository.get(42L) } returns null
        val viewModel = viewModelWithDownloadedTrack()

        viewModel.userMessages.test {
            viewModel.exportLyricsForCurrentTrack()
            assertEquals("No lyrics to save yet.", awaitItem())
            coVerify(exactly = 0) { lyricsSidecarWriter.write(any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `write failure reports truthfully`() = runTest(dispatcher) {
        coEvery { lyricsRepository.get(42L) } returns cachedLyrics()
        coEvery { lyricsSidecarWriter.write(42L, any()) } throws IOException("disk full")
        val viewModel = viewModelWithDownloadedTrack()

        viewModel.userMessages.test {
            viewModel.exportLyricsForCurrentTrack()
            assertEquals("Couldn't save lyrics.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `DAO failure reports write failure truthfully`() = runTest(dispatcher) {
        coEvery { lyricsRepository.get(42L) } throws IOException("database unavailable")
        val viewModel = viewModelWithDownloadedTrack()

        viewModel.userMessages.test {
            viewModel.exportLyricsForCurrentTrack()
            assertEquals("Couldn't save lyrics.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `cancellation is preserved without a failure message`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        coEvery { lyricsRepository.get(42L) } coAnswers {
            entered.complete(Unit)
            throw CancellationException("cancelled")
        }
        val viewModel = viewModelWithDownloadedTrack()

        viewModel.userMessages.test {
            viewModel.exportLyricsForCurrentTrack()
            entered.await()
            assertEquals(null, viewModel.exportingLyricsTrackId.first { it == null })
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun TestScope.viewModelWithDownloadedTrack(title: String = "Song"): NowPlayingViewModel {
        playerState.value = playerState.value.copy(currentTrack = downloadedTrack(42L, title))
        return viewModel().also { advanceUntilIdle() }
    }

    private fun downloadedTrack(id: Long, title: String) = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        isDownloaded = true,
    )

    private fun cachedLyrics() = LyricsEntity(
        trackId = 42L,
        plainText = "cached words",
        syncedLrc = null,
        instrumental = false,
        language = null,
        source = "lrclib",
        sourceLyricsId = "42",
        fetchedAt = 1L,
    )

    private fun viewModel() = NowPlayingViewModel(
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        likeCoordinator = likeCoordinator,
        losslessUpgrader = mockk<LosslessUpgrader>(relaxed = true),
        lyricsRepository = lyricsRepository,
        lyricsPreference = mockk(relaxed = true),
        nowPlayingPreference = mockk(relaxed = true),
        lyricsSidecarWriter = lyricsSidecarWriter,
        appContext = mockk<Context>(relaxed = true),
        ytMusicApiClient = mockk<YTMusicApiClient>(relaxed = true),
    )
}
