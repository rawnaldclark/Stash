package com.stash.feature.nowplaying

import android.content.Context
import app.cash.turbine.test
import com.stash.core.data.db.entity.LyricsEntity
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.prefs.LyricsPreference
import com.stash.core.data.prefs.LyricsSourcePreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.lyrics.TtmlUpgradeResult
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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingWordSyncedLyricsTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val playerState = MutableStateFlow(PlayerState())
    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { this@mockk.playerState } returns this@NowPlayingWordSyncedLyricsTest.playerState
        every { currentPosition } returns MutableStateFlow(0L)
    }
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(any()) } returns flowOf(null)
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
    }
    private val lyricsRepository: LyricsRepository = mockk(relaxed = true)
    private val lyricsPreference: LyricsPreference = mockk(relaxed = true)
    private val likeCoordinator: LikeCoordinator = mockk(relaxed = true) {
        every { mirrorFailures } returns MutableSharedFlow()
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `double tap is single flight and passes the single-track flags`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<TtmlUpgradeResult>()
        coEvery { lyricsRepository.get(42L) } returns storedLyrics()
        coEvery { lyricsRepository.upgradeToTtml(42L, any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val viewModel = viewModelWithStoredTrack()

        viewModel.userMessages.test {
            viewModel.getWordSyncedLyricsForCurrentTrack()
            viewModel.getWordSyncedLyricsForCurrentTrack()
            entered.await()
            assertEquals(42L, viewModel.upgradingLyricsTrackId.value)
            coVerify(exactly = 1) { lyricsRepository.upgradeToTtml(42L, moveCursor = false, interactive = true) }

            release.complete(TtmlUpgradeResult.UPGRADED)
            assertEquals("Looking for word-synced lyrics\u2026", awaitItem())
            assertEquals("Word-synced lyrics added.", awaitItem())
            assertEquals(null, viewModel.upgradingLyricsTrackId.first { it == null })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `A to B switch keeps busy state and names A`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<TtmlUpgradeResult>()
        coEvery { lyricsRepository.get(42L) } returns storedLyrics()
        coEvery { lyricsRepository.upgradeToTtml(42L, any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
        }
        val viewModel = viewModelWithStoredTrack(title = "Song A")

        viewModel.userMessages.test {
            viewModel.getWordSyncedLyricsForCurrentTrack()
            entered.await()
            every { musicRepository.observeTrackById(84L) } returns flowOf(track(84L, "Song B"))
            playerState.value = playerState.value.copy(currentTrack = track(84L, "Song B"))
            advanceUntilIdle()

            assertEquals(42L, viewModel.upgradingLyricsTrackId.value)
            viewModel.getWordSyncedLyricsForCurrentTrack()
            coVerify(exactly = 0) { lyricsRepository.get(84L) }

            release.complete(TtmlUpgradeResult.UPGRADED)
            assertEquals("Looking for word-synced lyrics\u2026", awaitItem())
            assertEquals("Word-synced lyrics added for 'Song A'.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `each repository result maps to its message`() = runTest(dispatcher) {
        expectResult(TtmlUpgradeResult.UPGRADED, "Word-synced lyrics added.")
        expectResult(TtmlUpgradeResult.NO_TTML, "No word-synced lyrics available for this song.")
        expectResult(TtmlUpgradeResult.NOT_READY, "Word-synced lyrics aren't ready yet. Try again in a minute.")
        expectResult(
            TtmlUpgradeResult.PAUSED,
            "Word-synced lyrics are paused for a few minutes after repeated timeouts.",
        )
        expectResult(TtmlUpgradeResult.RATE_LIMITED, "Apple lyrics are rate-limiting, try again later.")
        expectResult(TtmlUpgradeResult.FAILED, "Couldn't get word-synced lyrics.")
        expectResult(TtmlUpgradeResult.SKIPPED, "Nothing to upgrade for this song.")
    }

    @Test fun `no row and a stamped miss says no lyrics found`() = runTest(dispatcher) {
        coEvery { lyricsRepository.get(42L) } returns null
        every { lyricsRepository.observeFetchedAt(42L) } returns flowOf(0L)
        val viewModel = viewModelWithStoredTrack()

        viewModel.userMessages.test {
            viewModel.getWordSyncedLyricsForCurrentTrack()
            assertEquals("Looking for word-synced lyrics\u2026", awaitItem())
            assertEquals("No lyrics found for this song.", awaitItem())
            coVerify(exactly = 0) { lyricsRepository.upgradeToTtml(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `no row and no stamp says lyrics are not loaded yet`() = runTest(dispatcher) {
        coEvery { lyricsRepository.get(42L) } returns null
        every { lyricsRepository.observeFetchedAt(42L) } returns flowOf(null)
        val viewModel = viewModelWithStoredTrack()

        viewModel.userMessages.test {
            viewModel.getWordSyncedLyricsForCurrentTrack()
            assertEquals("Looking for word-synced lyrics\u2026", awaitItem())
            assertEquals("Lyrics aren't loaded yet, try again from the lyrics view.", awaitItem())
            coVerify(exactly = 0) { lyricsRepository.upgradeToTtml(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `exception reports failure instead of crashing and frees the guard`() = runTest(dispatcher) {
        coEvery { lyricsRepository.get(42L) } returns storedLyrics()
        coEvery { lyricsRepository.upgradeToTtml(42L, any(), any()) } throws IOException("database unavailable")
        val viewModel = viewModelWithStoredTrack()

        viewModel.userMessages.test {
            viewModel.getWordSyncedLyricsForCurrentTrack()
            assertEquals("Looking for word-synced lyrics\u2026", awaitItem())
            assertEquals("Couldn't get word-synced lyrics.", awaitItem())
            assertEquals(null, viewModel.upgradingLyricsTrackId.first { it == null })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `cancellation is preserved without a failure message`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>()
        coEvery { lyricsRepository.get(42L) } returns storedLyrics()
        coEvery { lyricsRepository.upgradeToTtml(42L, any(), any()) } coAnswers {
            entered.complete(Unit)
            throw CancellationException("cancelled")
        }
        val viewModel = viewModelWithStoredTrack()

        viewModel.userMessages.test {
            viewModel.getWordSyncedLyricsForCurrentTrack()
            entered.await()
            assertEquals(null, viewModel.upgradingLyricsTrackId.first { it == null })
            assertEquals("Looking for word-synced lyrics\u2026", awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a stream with no library row does nothing`() = runTest(dispatcher) {
        val viewModel = viewModelWithStoredTrack(stored = false)

        viewModel.userMessages.test {
            viewModel.getWordSyncedLyricsForCurrentTrack()
            expectNoEvents()
            assertEquals(null, viewModel.upgradingLyricsTrackId.value)
            coVerify(exactly = 0) { lyricsRepository.get(any()) }
            coVerify(exactly = 0) { lyricsRepository.upgradeToTtml(any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `LRC-only source preference turns the action off`() = runTest(dispatcher) {
        every { lyricsPreference.sourcePreference } returns flowOf(LyricsSourcePreference.LRC_ONLY)
        val viewModel = viewModelWithStoredTrack()

        // The flow starts at its initial `true`; wait for the preference to land.
        assertFalse(viewModel.appleLyricsEnabled.first { !it })
    }

    // -- helpers --

    /** Runs one tap with [result] from the repository and checks the final toast. */
    private suspend fun TestScope.expectResult(result: TtmlUpgradeResult, message: String) {
        coEvery { lyricsRepository.get(42L) } returns storedLyrics()
        coEvery { lyricsRepository.upgradeToTtml(42L, any(), any()) } returns result
        val viewModel = viewModelWithStoredTrack()
        viewModel.userMessages.test {
            viewModel.getWordSyncedLyricsForCurrentTrack()
            assertEquals("Looking for word-synced lyrics\u2026", awaitItem())
            assertEquals(message, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** [stored]: Room has a row for the playing track (the menu action's precondition). */
    private fun TestScope.viewModelWithStoredTrack(
        title: String = "Song",
        stored: Boolean = true,
    ): NowPlayingViewModel {
        val t = track(42L, title)
        if (stored) every { musicRepository.observeTrackById(42L) } returns flowOf(t)
        playerState.value = playerState.value.copy(currentTrack = t)
        return viewModel().also { advanceUntilIdle() }
    }

    private fun track(id: Long, title: String) = Track(
        id = id,
        title = title,
        artist = "Artist",
        album = "Album",
        isDownloaded = true,
    )

    private fun storedLyrics() = LyricsEntity(
        trackId = 42L,
        plainText = "words",
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
        lyricsPreference = lyricsPreference,
        nowPlayingPreference = mockk(relaxed = true),
        lyricsSidecarWriter = mockk<LyricsSidecarWriter>(),
        appContext = mockk<Context>(relaxed = true),
        sleepTimerController = mockk(relaxed = true),
        ytMusicApiClient = mockk<YTMusicApiClient>(relaxed = true),
    )
}