package com.stash.feature.nowplaying

import android.content.Context
import app.cash.turbine.test
import app.cash.turbine.testIn
import app.cash.turbine.turbineScope
import com.stash.core.data.lossless.LosslessUpgrader
import com.stash.core.data.prefs.NowPlayingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.LikeCoordinator
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.core.model.UpgradeResult
import com.stash.data.lyrics.LyricsRepository
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.ArtistSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.slot
import io.mockk.verify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * v0.9.18 Task 3 — first tests in :feature:nowplaying.
 *
 * Two test classes, two concerns:
 *  - [NowPlayingViewModelSnackbarCopyTest] pins the pure
 *    [snackbarCopyFor] helper, which maps every UpgradeResult variant
 *    to its user-facing snackbar string.
 *  - [NowPlayingViewModelFindInFlacTest] exercises the
 *    [NowPlayingViewModel.findInFlacForCurrentTrack] action end-to-end,
 *    asserting the "Looking for FLAC…" → result-snackbar emission
 *    sequence and the no-op-when-already-FLAC guard.
 *
 * Snackbar emissions go through [NowPlayingViewModel.userMessages],
 * a `MutableSharedFlow<String>` with `extraBufferCapacity = 1` and
 * `BufferOverflow.DROP_OLDEST`. Two `tryEmit` calls in a row therefore
 * race the collector — without an active subscriber draining the
 * buffer between emits, the first message is dropped. Turbine's
 * `awaitItem()` parks on the channel between emits and gives us the
 * full transcript.
 */
class NowPlayingViewModelSnackbarCopyTest {

    @Test fun `Upgraded maps to "Upgraded to FLAC"`() {
        assertEquals("Upgraded to FLAC", snackbarCopyFor(UpgradeResult.Upgraded))
    }

    @Test fun `NoMatch maps to "No lossless match found"`() {
        assertEquals("No lossless match found", snackbarCopyFor(UpgradeResult.NoMatch))
    }

    @Test fun `Error maps to "Couldn't check lossless sources"`() {
        assertEquals("Couldn't check lossless sources", snackbarCopyFor(UpgradeResult.Error))
    }
}

/**
 * Pins [overlayDisplayTrack] — the codec-badge overlay decision. A
 * streaming MediaItem carries the real served format (codec/bit-depth) in
 * its extras; the displayed Track must adopt it instead of the stale Room
 * `file_format` ("opus") that synced-but-not-downloaded rows keep forever.
 *
 * The bug this guards against: antra plays its lossless FLAC from a LOCAL
 * cache file (`file://`), so the player's `isStreaming` flag — which is
 * `true` only for http(s) URIs — is false, and the overlay was skipped,
 * leaving a real 24-bit FLAC mislabeled "OPUS". The fix keys the overlay
 * on the MediaItem actually carrying a stream origin, not the URI scheme.
 */
class NowPlayingCodecOverlayTest {

    private fun streamTrack(
        id: Long = 1L,
        fileFormat: String,
        origin: String?,
        bits: Int? = null,
        youtubeId: String? = null,
    ) = Track(
        id = id,
        title = "t",
        artist = "a",
        fileFormat = fileFormat,
        bitsPerSample = bits,
        streamOrigin = origin,
        youtubeId = youtubeId,
    )

    @Test fun `antra file-stream overlays FLAC even though isStreaming is false`() {
        // antra serves a file:// FLAC, so the player reports isStreaming=false.
        val base = streamTrack(fileFormat = "opus", origin = null) // stale Room row
        val streamFormat = streamTrack(fileFormat = "flac", origin = "antra", bits = 24)

        val result = overlayDisplayTrack(
            isHttpStreaming = false,
            streamFormat = streamFormat,
            baseTrack = base,
        )

        assertEquals("flac", result?.fileFormat)
        assertEquals(24, result?.bitsPerSample)
        assertEquals("antra", result?.streamOrigin)
    }

    @Test fun `http stream still overlays FLAC`() {
        val base = streamTrack(fileFormat = "opus", origin = null)
        val streamFormat = streamTrack(fileFormat = "flac", origin = "kennyy", bits = 24)

        val result = overlayDisplayTrack(
            isHttpStreaming = true,
            streamFormat = streamFormat,
            baseTrack = base,
        )

        assertEquals("flac", result?.fileFormat)
    }

    @Test fun `downloaded track is not overlaid (no stream origin, not http)`() {
        // A local downloaded FLAC: the active MediaItem carries no stream
        // codec, so streamFormat defaults to opus/no-origin. Must keep the
        // Room row's real format untouched.
        val base = streamTrack(id = 5L, fileFormat = "flac", origin = null)
        val streamFormat = streamTrack(id = 5L, fileFormat = "opus", origin = null)

        val result = overlayDisplayTrack(
            isHttpStreaming = false,
            streamFormat = streamFormat,
            baseTrack = base,
        )

        assertEquals("flac", result?.fileFormat) // unchanged base, no overlay
        assertEquals(base, result)
    }

    @Test fun `no overlay when stream format itself is opus`() {
        // A YouTube-fallback stream genuinely IS opus — don't fabricate FLAC.
        val base = streamTrack(fileFormat = "opus", origin = null)
        val streamFormat = streamTrack(fileFormat = "opus", origin = "youtube")

        val result = overlayDisplayTrack(
            isHttpStreaming = true,
            streamFormat = streamFormat,
            baseTrack = base,
        )

        assertEquals("opus", result?.fileFormat)
    }

    @Test fun `null base track returns null`() {
        assertEquals(
            null,
            overlayDisplayTrack(
                isHttpStreaming = false,
                streamFormat = streamTrack(fileFormat = "flac", origin = "antra"),
                baseTrack = null,
            ),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelFindInFlacTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val playerStateFlow = MutableStateFlow(PlayerState())
    private val positionFlow = MutableStateFlow(0L)

    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { playerState } returns playerStateFlow
        every { currentPosition } returns positionFlow
    }
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(any()) } returns flowOf(null)
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
    }
    private val likeCoordinator: LikeCoordinator = mockk(relaxed = true) {
        every { mirrorFailures } returns MutableSharedFlow()
    }
    private val upgrader: LosslessUpgrader = mockk()
    // v0.9.36 Task 12 — Now Playing now depends on the lyrics repository
    // (for the lyrics sheet) and an application Context (used as a
    // sink for WorkManager.getInstance on the priority-fetch path).
    // These tests don't exercise either surface, so a relaxed mock for
    // both is enough to satisfy the constructor.
    private val lyricsRepository: LyricsRepository = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)

    private fun newViewModel(
        nowPlayingPreference: NowPlayingPreference = mockk(relaxed = true),
    ): NowPlayingViewModel = NowPlayingViewModel(
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        likeCoordinator = likeCoordinator,
        losslessUpgrader = upgrader,
        lyricsRepository = lyricsRepository,
        lyricsPreference = mockk(relaxed = true),
        nowPlayingPreference = nowPlayingPreference,
        lyricsSidecarWriter = mockk(relaxed = true),
        appContext = appContext,
        ytMusicApiClient = mockk(relaxed = true),
    )

    private val nonFlacTrack = Track(
        id = 42L,
        title = "song",
        artist = "artist",
        fileFormat = "opus",
    )
    private val flacTrack = nonFlacTrack.copy(id = 99L, fileFormat = "flac")

    @Test fun `cold persisted false stays unloaded until preference emits`() = runTest(dispatcher) {
        val releasePreference = CompletableDeferred<Unit>()
        val preference = mockk<NowPlayingPreference> {
            every { ambientAnimationEnabled } returns flow {
                releasePreference.await()
                emit(false)
            }
        }
        val vm = newViewModel(preference)

        vm.ambientAnimationEnabled.test {
            assertEquals(null, awaitItem())
            releasePreference.complete(Unit)
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The action's body emits "Looking for FLAC…", calls `upgradeToLossless`,
     * then emits the result. Because [NowPlayingViewModel.userMessages] is a
     * `MutableSharedFlow` with `extraBufferCapacity = 1` and `DROP_OLDEST`,
     * these two `tryEmit` calls must be separated by a real suspension —
     * otherwise the second one races the collector and overwrites the first.
     *
     * In production this naturally happens: `LosslessUpgrader.upgradeToLossless`
     * does network I/O and DB work, so the coroutine yields well before the
     * second emit. In the JVM test we stub the call with `coAnswers { yield() }`
     * to reproduce that yield without modeling the full pipeline.
     */
    private fun stubUpgrade(result: UpgradeResult) {
        coEvery { upgrader.upgradeToLossless(any()) } coAnswers {
            yield()
            result
        }
    }

    @Test fun `findInFlacForCurrentTrack happy path emits looking then upgraded`() = runTest(dispatcher) {
        stubUpgrade(UpgradeResult.Upgraded)
        playerStateFlow.value = playerStateFlow.value.copy(currentTrack = nonFlacTrack)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.userMessages.test {
            vm.findInFlacForCurrentTrack()
            assertEquals("Looking for FLAC\u2026", awaitItem())
            assertEquals("Upgraded to FLAC", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `findInFlacForCurrentTrack no-match path`() = runTest(dispatcher) {
        stubUpgrade(UpgradeResult.NoMatch)
        playerStateFlow.value = playerStateFlow.value.copy(currentTrack = nonFlacTrack)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.userMessages.test {
            vm.findInFlacForCurrentTrack()
            assertEquals("Looking for FLAC\u2026", awaitItem())
            assertEquals("No lossless match found", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `findInFlacForCurrentTrack error path`() = runTest(dispatcher) {
        stubUpgrade(UpgradeResult.Error)
        playerStateFlow.value = playerStateFlow.value.copy(currentTrack = nonFlacTrack)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.userMessages.test {
            vm.findInFlacForCurrentTrack()
            assertEquals("Looking for FLAC\u2026", awaitItem())
            assertEquals("Couldn't check lossless sources", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `findInFlacForCurrentTrack no-op when current track is FLAC`() = runTest(dispatcher) {
        playerStateFlow.value = playerStateFlow.value.copy(currentTrack = flacTrack)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.userMessages.test {
            vm.findInFlacForCurrentTrack()
            advanceUntilIdle()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { upgrader.upgradeToLossless(any()) }
    }
}

/**
 * v0.9.52: the heart routes through LikeCoordinator (local + optional
 * external mirroring) instead of the local-only repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelLikeRoutingTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val playerStateFlow = MutableStateFlow(PlayerState())
    private val positionFlow = MutableStateFlow(0L)

    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { playerState } returns playerStateFlow
        every { currentPosition } returns positionFlow
    }
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(any()) } returns flowOf(null)
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
    }
    private val likeCoordinator: LikeCoordinator = mockk(relaxed = true) {
        every { mirrorFailures } returns MutableSharedFlow()
    }
    private val upgrader: LosslessUpgrader = mockk(relaxed = true)
    private val lyricsRepository: LyricsRepository = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)

    private fun newViewModel(): NowPlayingViewModel = NowPlayingViewModel(
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        likeCoordinator = likeCoordinator,
        losslessUpgrader = upgrader,
        lyricsRepository = lyricsRepository,
        lyricsPreference = mockk(relaxed = true),
        nowPlayingPreference = mockk(relaxed = true),
        lyricsSidecarWriter = mockk(relaxed = true),
        appContext = appContext,
        ytMusicApiClient = mockk(relaxed = true),
    )

    private val unlikedTrack = Track(id = 42L, title = "song", artist = "artist", stashLikedAt = null)
    private val likedTrack = unlikedTrack.copy(stashLikedAt = 123L)

    @Test fun `onLikeTap routes through LikeCoordinator with resolved id and new state`() =
        runTest(dispatcher) {
            coEvery { musicRepository.ensureTrackPersisted(any()) } returns 42L
            playerStateFlow.value = playerStateFlow.value.copy(currentTrack = unlikedTrack)
            val vm = newViewModel()
            advanceUntilIdle()

            vm.onLikeTap()
            advanceUntilIdle()

            coVerify { likeCoordinator.setLiked(42L, true) }
        }

    @Test fun `onLikeTap on a liked track requests un-like`() = runTest(dispatcher) {
        coEvery { musicRepository.ensureTrackPersisted(any()) } returns 42L
        playerStateFlow.value = playerStateFlow.value.copy(currentTrack = likedTrack)
        val vm = newViewModel()
        advanceUntilIdle()

        vm.onLikeTap()
        advanceUntilIdle()

        coVerify { likeCoordinator.setLiked(42L, false) }
    }
}

/**
 * Tap on the Now Playing track block → resolve the playing artist's NAME to
 * a YT browseId and emit a one-shot [ArtistNavTarget] carrying the current
 * album as `focusAlbum`. Resolve miss/failure → snackbar, no nav. No-op when
 * nothing is playing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTrackTapTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val playerStateFlow = MutableStateFlow(PlayerState())
    private val positionFlow = MutableStateFlow(0L)

    private val playerRepository: PlayerRepository = mockk(relaxed = true) {
        every { playerState } returns playerStateFlow
        every { currentPosition } returns positionFlow
    }
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        every { observeTrackById(any()) } returns flowOf(null)
        every { getUserCreatedPlaylists() } returns flowOf(emptyList())
    }
    private val likeCoordinator: LikeCoordinator = mockk(relaxed = true) {
        every { mirrorFailures } returns MutableSharedFlow()
    }
    private val upgrader: LosslessUpgrader = mockk(relaxed = true)
    private val lyricsRepository: LyricsRepository = mockk(relaxed = true)
    private val appContext: Context = mockk(relaxed = true)

    private fun newViewModel(api: YTMusicApiClient): NowPlayingViewModel = NowPlayingViewModel(
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        likeCoordinator = likeCoordinator,
        losslessUpgrader = upgrader,
        lyricsRepository = lyricsRepository,
        lyricsPreference = mockk(relaxed = true),
        nowPlayingPreference = mockk(relaxed = true),
        lyricsSidecarWriter = mockk(relaxed = true),
        appContext = appContext,
        ytMusicApiClient = api,
    )

    @Test fun `startRadioFromCurrent seeds a song radio from the playing track`() = runTest(dispatcher) {
        // A youtubeId-bearing track makes liveTrackFlow fall through to a
        // by-youtubeId Room lookup; stub it so the combine can populate currentTrack.
        every { musicRepository.observeTrackByYoutubeId(any()) } returns flowOf(null)
        playerStateFlow.value = playerStateFlow.value.copy(
            currentTrack = Track(id = 7L, title = "song", artist = "artist", youtubeId = "vid42"),
        )
        val vm = newViewModel(mockk(relaxed = true))
        advanceUntilIdle()

        vm.startRadioFromCurrent()
        advanceUntilIdle()

        val seedSlot = slot<com.stash.core.data.radio.RadioSeed>()
        // keepCurrent MUST be true — the seed is the currently-playing track, so
        // the player keeps it playing instead of restarting it.
        coVerify { playerRepository.startRadio(capture(seedSlot), keepCurrent = true) }
        val seed = seedSlot.captured as com.stash.core.data.radio.RadioSeed.Song
        assertEquals("song", seed.title)
        assertEquals("artist", seed.artist)
        assertEquals("vid42", seed.ytVideoId)
    }

    @Test fun `stopRadio delegates to the player`() = runTest(dispatcher) {
        val vm = newViewModel(mockk(relaxed = true))
        vm.stopRadio()
        verify { playerRepository.stopRadio() }
    }

    @Test fun `onTrackInfoTapped emits nav target with focusAlbum on resolve success`() =
        runTest(dispatcher) {
            val api = mockk<YTMusicApiClient>()
            coEvery { api.resolveArtist(any()) } returns
                ArtistSummary(id = "UC123", name = "My Bloody Valentine", avatarUrl = "http://a")
            playerStateFlow.value = playerStateFlow.value.copy(
                currentTrack = Track(id = 1L, title = "Sometimes", artist = "My Bloody Valentine", album = "Loveless"),
            )
            val vm = newViewModel(api)
            advanceUntilIdle()

            vm.artistNavEvents.test {
                vm.onTrackInfoTapped()
                val target = awaitItem()
                assertEquals("UC123", target.artistId)
                assertEquals("My Bloody Valentine", target.name)
                assertEquals("Loveless", target.focusAlbum)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test fun `onTrackInfoTapped emits toast and no nav on null resolve`() =
        runTest(dispatcher) {
            val api = mockk<YTMusicApiClient>()
            coEvery { api.resolveArtist(any()) } returns null
            playerStateFlow.value = playerStateFlow.value.copy(
                currentTrack = Track(id = 1L, title = "x", artist = "Nobody", album = ""),
            )
            val vm = newViewModel(api)
            advanceUntilIdle()

            // Assert BOTH halves of this test's name: the toast fires AND no
            // artistNavEvent is emitted on a failed resolve.
            turbineScope {
                val nav = vm.artistNavEvents.testIn(backgroundScope)
                val msgs = vm.userMessages.testIn(backgroundScope)
                vm.onTrackInfoTapped()
                assertEquals("Couldn't find this artist", msgs.awaitItem())
                nav.expectNoEvents()
                nav.cancel()
                msgs.cancel()
            }
        }

    @Test fun `onTrackInfoTapped is a no-op when nothing is playing`() =
        runTest(dispatcher) {
            val api = mockk<YTMusicApiClient>(relaxed = true)
            val vm = newViewModel(api)
            advanceUntilIdle()

            vm.onTrackInfoTapped()
            advanceUntilIdle()

            coVerify(exactly = 0) { api.resolveArtist(any()) }
        }
}
