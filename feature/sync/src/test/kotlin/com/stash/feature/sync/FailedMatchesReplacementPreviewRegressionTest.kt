package com.stash.feature.sync

import androidx.media3.common.PlaybackException
import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.Track
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.preview.PreviewErrorEvent
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.SwapCoordinator
import com.stash.data.download.files.SwapOutcome
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.preview.NoFastStreamException
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.download.ytdlp.YtDlpSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for imported issue #5 ("Can't play replacement song").
 *
 * These tests deliberately exercise only public [FailedMatchesViewModel]
 * behavior. In particular, they pin the distinction between speculative
 * fast-only prefetch and a user-initiated full extraction, and drive
 * asynchronous ExoPlayer failures through [PreviewPlayer.playerErrors].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedMatchesReplacementPreviewRegressionTest {

    private val musicRepository: MusicRepository = mockk(relaxed = true)
    private val previewPlayer: PreviewPlayer = mockk(relaxed = true)
    private val previewUrlExtractor: PreviewUrlExtractor = mockk(relaxed = true)
    private val searchExecutor: HybridSearchExecutor = mockk(relaxed = true)
    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true)
    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true)
    private val trackDao = mockk<com.stash.core.data.db.dao.TrackDao>(relaxed = true)
    private val downloadQueueDao = mockk<com.stash.core.data.db.dao.DownloadQueueDao>(relaxed = true)
    private val swapCoordinator: SwapCoordinator = mockk(relaxed = true)
    private val blocklistGuard = mockk<com.stash.core.data.blocklist.BlocklistGuard>(relaxed = true)
    private val localFileOps = mockk<com.stash.core.data.files.LocalFileOps>(relaxed = true)
    private val trackIdentityEvents = mockk<TrackIdentityEvents>(relaxed = true)
    private val albumMatchExecutor = mockk<AlbumMatchExecutor>()

    private lateinit var playerErrors: MutableSharedFlow<PreviewErrorEvent>
    private lateinit var previewState: MutableStateFlow<PreviewState>
    private var requestSequence = 0L
    private var currentRequestId = 0L
    private var nextAttemptId = 0L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        playerErrors = MutableSharedFlow(extraBufferCapacity = 4)
        previewState = MutableStateFlow(PreviewState.Idle)
        requestSequence = 0L
        currentRequestId = 0L
        nextAttemptId = 0L
        every { previewPlayer.playerErrors } returns playerErrors
        every { previewPlayer.previewState } returns previewState
        every { swapCoordinator.outcomes } returns MutableSharedFlow<SwapOutcome>()
        every { swapCoordinator.running } returns MutableStateFlow(emptySet())
        every { swapCoordinator.swap(any(), any()) } returns true
        coEvery { albumMatchExecutor.findTrackInAlbum(any(), any(), any(), any()) } returns null
        every { previewPlayer.claimRequest() } answers {
            currentRequestId = ++requestSequence
            currentRequestId
        }
        every { previewPlayer.isRequestCurrent(any()) } answers {
            firstArg<Long?>() == currentRequestId
        }
        every { previewPlayer.cancelRequest(any()) } answers {
            if (firstArg<Long?>() == currentRequestId) currentRequestId = 0L
            Unit
        }
        every { previewPlayer.playUrlIfClaimed(any(), any(), any(), any()) } answers {
            if (firstArg<Long>() != currentRequestId) {
                null
            } else {
                val attemptId = ++nextAttemptId
                arg<(Long) -> Unit>(3).invoke(attemptId)
                attemptId
            }
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun unmatched() = UnmatchedTrackView(
        id = 1L,
        trackId = 1L,
        title = "Title",
        artist = "Artist",
        albumArtUrl = null,
        createdAt = 0L,
        rejectedVideoId = null,
        searchQuery = "Artist - Title",
    )

    private fun makeVm(
        tracks: List<UnmatchedTrackView> = emptyList(),
        flagged: List<TrackEntity> = emptyList(),
    ): FailedMatchesViewModel {
        every { musicRepository.getUnmatchedTracks() } returns flowOf(tracks)
        every { musicRepository.getFlaggedTracks() } returns flowOf(flagged)
        return FailedMatchesViewModel(
            musicRepository = musicRepository,
            previewPlayer = previewPlayer,
            previewUrlExtractor = previewUrlExtractor,
            searchExecutor = searchExecutor,
            downloadExecutor = downloadExecutor,
            fileOrganizer = fileOrganizer,
            qualityPrefs = qualityPrefs,
            trackDao = trackDao,
            downloadQueueDao = downloadQueueDao,
            swapCoordinator = swapCoordinator,
            blocklistGuard = blocklistGuard,
            localFileOps = localFileOps,
            trackIdentityEvents = trackIdentityEvents,
            albumMatchExecutor = albumMatchExecutor,
        )
    }

    private fun ioPlaybackError() = PlaybackException(
        "preview URL rejected",
        IOException("HTTP 403"),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    private fun decodingPlaybackError() = PlaybackException(
        "decoder rejected stream",
        IllegalStateException("unsupported stream"),
        PlaybackException.ERROR_CODE_DECODING_FAILED,
    )

    @Test
    fun `failed fast-only prefetch is followed by full foreground extraction and playback`() = runTest {
        coEvery { searchExecutor.search(any(), any()) } returns listOf(
            YtDlpSearchResult(id = "replacement", title = "Replacement"),
        )
        coEvery {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = false)
        } throws NoFastStreamException("replacement")
        coEvery {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        } returns "https://audio.example/foreground"

        val vm = makeVm(tracks = listOf(unmatched()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = false)
        }

        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        }
        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(
                any(),
                "replacement",
                "https://audio.example/foreground",
                any(),
            )
        }
    }

    @Test
    fun `an error emitted during play preparation still owns and retries the attempt`() = runTest {
        coEvery {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        } returns "https://audio.example/initial"
        coEvery {
            previewUrlExtractor.extractViaYtDlpForRetry("replacement")
        } returns "https://audio.example/retry"
        every {
            previewPlayer.playUrlIfClaimed(
                any(),
                "replacement",
                "https://audio.example/initial",
                any(),
            )
        } answers {
            val attemptId = ++nextAttemptId
            arg<(Long) -> Unit>(3).invoke(attemptId)
            playerErrors.tryEmit(PreviewErrorEvent("replacement", attemptId, ioPlaybackError()))
            attemptId
        }
        val vm = makeVm()

        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            previewUrlExtractor.extractViaYtDlpForRetry("replacement")
        }
        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(any(), "replacement", "https://audio.example/retry", any())
        }
    }

    @Test
    fun `a second tap while the owned attempt is buffering does not restart playback`() = runTest {
        coEvery {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        } returns "https://audio.example/initial"
        val vm = makeVm()

        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()
        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        }
        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(any(), "replacement", "https://audio.example/initial", any())
        }
        verify(exactly = 1) { previewPlayer.stop() }
    }

    @Test
    fun `the same candidate can restart after another screen preempts its lease`() = runTest {
        coEvery {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        } returns "https://audio.example/initial"
        val vm = makeVm()

        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()
        previewPlayer.claimRequest() // Simulate another screen taking the singleton player.
        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()

        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(
                1L,
                "replacement",
                "https://audio.example/initial",
                any(),
            )
        }
        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(
                3L,
                "replacement",
                "https://audio.example/initial",
                any(),
            )
        }
    }

    @Test
    fun `an IO player failure retries through yt-dlp at most once`() = runTest {
        coEvery {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        } returns "https://audio.example/initial"
        coEvery {
            previewUrlExtractor.extractViaYtDlpForRetry("replacement")
        } returns "https://audio.example/retry"
        val vm = makeVm()

        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()
        playerErrors.emit(PreviewErrorEvent("replacement", 1L, ioPlaybackError()))
        advanceUntilIdle()
        // A buffered duplicate from the failed initial URL must not be
        // attributed to the healthy retry attempt.
        playerErrors.emit(PreviewErrorEvent("replacement", 1L, ioPlaybackError()))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            previewUrlExtractor.extractViaYtDlpForRetry("replacement")
        }
        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(any(), "replacement", "https://audio.example/retry", any())
        }
        verify(exactly = 1) { previewPlayer.stop() }
    }

    @Test
    fun `a stale player error from the previous candidate is ignored`() = runTest {
        coEvery {
            previewUrlExtractor.extractStreamUrl("first", allowYtDlp = true)
        } returns "https://audio.example/first"
        coEvery {
            previewUrlExtractor.extractStreamUrl("second", allowYtDlp = true)
        } returns "https://audio.example/second"
        val vm = makeVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.previewRejectedMatch("first")
        advanceUntilIdle()
        vm.previewRejectedMatch("second")
        advanceUntilIdle()
        playerErrors.emit(PreviewErrorEvent("first", 1L, ioPlaybackError()))
        advanceUntilIdle()

        coVerify(exactly = 0) {
            previewUrlExtractor.extractViaYtDlpForRetry("first")
        }
    }

    @Test
    fun `a superseded cancellation-resistant foreground extraction cannot revive playback`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        coEvery {
            previewUrlExtractor.extractStreamUrl("first", allowYtDlp = true)
        } coAnswers {
            withContext(NonCancellable) { firstGate.await() }
            "https://audio.example/first"
        }
        coEvery {
            previewUrlExtractor.extractStreamUrl("second", allowYtDlp = true)
        } returns "https://audio.example/second"
        val vm = makeVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.previewRejectedMatch("first")
        runCurrent()
        vm.previewRejectedMatch("second")
        runCurrent()
        firstGate.complete(Unit)
        runCurrent()

        verify(exactly = 0) {
            previewPlayer.playUrlIfClaimed(any(), "first", "https://audio.example/first", any())
        }
        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(any(), "second", "https://audio.example/second", any())
        }
        assertNull(vm.uiState.value.previewLoading)
    }

    @Test
    fun `a superseded cancellation-resistant retry cannot revive playback`() = runTest {
        val retryGate = CompletableDeferred<Unit>()
        coEvery {
            previewUrlExtractor.extractStreamUrl("first", allowYtDlp = true)
        } returns "https://audio.example/first"
        coEvery {
            previewUrlExtractor.extractStreamUrl("second", allowYtDlp = true)
        } returns "https://audio.example/second"
        coEvery { previewUrlExtractor.extractViaYtDlpForRetry("first") } coAnswers {
            withContext(NonCancellable) { retryGate.await() }
            "https://audio.example/stale-retry"
        }
        val vm = makeVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.previewRejectedMatch("first")
        runCurrent()
        playerErrors.emit(PreviewErrorEvent("first", 1L, ioPlaybackError()))
        runCurrent()
        vm.previewRejectedMatch("second")
        runCurrent()
        retryGate.complete(Unit)
        runCurrent()

        verify(exactly = 0) {
            previewPlayer.playUrlIfClaimed(
                any(),
                "first",
                "https://audio.example/stale-retry",
                any(),
            )
        }
        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(any(), "second", "https://audio.example/second", any())
        }
        assertNull(vm.uiState.value.previewLoading)
    }

    @Test
    fun `a newer screen claim rejects another screens suspended extraction`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        coEvery {
            previewUrlExtractor.extractStreamUrl("first", allowYtDlp = true)
        } coAnswers {
            firstGate.await()
            "https://audio.example/first"
        }
        coEvery {
            previewUrlExtractor.extractStreamUrl("second", allowYtDlp = true)
        } returns "https://audio.example/second"
        val firstOwner = makeVm()
        val secondOwner = makeVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            firstOwner.uiState.collect {}
        }

        firstOwner.previewRejectedMatch("first")
        runCurrent()
        secondOwner.previewRejectedMatch("second")
        runCurrent()
        firstGate.complete(Unit)
        runCurrent()

        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(1L, "first", "https://audio.example/first", any())
        }
        verify(exactly = 1) {
            previewPlayer.playUrlIfClaimed(2L, "second", "https://audio.example/second", any())
        }
        assertNull(firstOwner.uiState.value.previewLoading)
    }

    @Test
    fun `a failed yt-dlp retry gives visible preview feedback`() = runTest {
        coEvery {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        } returns "https://audio.example/initial"
        coEvery {
            previewUrlExtractor.extractViaYtDlpForRetry("replacement")
        } throws IllegalStateException("yt-dlp failed")
        val vm = makeVm()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.userMessages.collect(messages::add)
        }

        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()
        playerErrors.emit(PreviewErrorEvent("replacement", 1L, ioPlaybackError()))
        advanceUntilIdle()

        assertTrue(
            "a failed player-error retry must tell the user, got $messages",
            messages.any { it.contains("preview", ignoreCase = true) },
        )
        coVerify(exactly = 1) {
            previewUrlExtractor.extractViaYtDlpForRetry("replacement")
        }
    }

    @Test
    fun `an IO failure from the retry attempt is terminal and visible`() = runTest {
        coEvery {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        } returns "https://audio.example/initial"
        coEvery {
            previewUrlExtractor.extractViaYtDlpForRetry("replacement")
        } returns "https://audio.example/retry"
        val vm = makeVm()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.userMessages.collect(messages::add)
        }

        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()
        playerErrors.emit(PreviewErrorEvent("replacement", 1L, ioPlaybackError()))
        advanceUntilIdle()
        playerErrors.emit(PreviewErrorEvent("replacement", 2L, ioPlaybackError()))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            previewUrlExtractor.extractViaYtDlpForRetry("replacement")
        }
        assertTrue(messages.any { it.contains("preview", ignoreCase = true) })
        verify(exactly = 1) { previewPlayer.stop() }
        verify(exactly = 1) { previewPlayer.stopIfCurrent(2L) }
    }

    @Test
    fun `an active non-IO playback failure is terminal and visible`() = runTest {
        coEvery {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        } returns "https://audio.example/initial"
        val vm = makeVm()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.userMessages.collect(messages::add)
        }

        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()
        playerErrors.emit(PreviewErrorEvent("replacement", 1L, decodingPlaybackError()))
        advanceUntilIdle()

        coVerify(exactly = 0) {
            previewUrlExtractor.extractViaYtDlpForRetry(any())
        }
        assertTrue(messages.any { it.contains("preview", ignoreCase = true) })
        verify(exactly = 1) { previewPlayer.stopIfCurrent(1L) }
    }

    @Test
    fun `stopping a stale screen only releases its owned playback attempt`() = runTest {
        coEvery {
            previewUrlExtractor.extractStreamUrl("replacement", allowYtDlp = true)
        } returns "https://audio.example/initial"
        val vm = makeVm()

        vm.previewRejectedMatch("replacement")
        advanceUntilIdle()
        vm.stopPreview()

        verify(exactly = 1) { previewPlayer.stop() }
        verify(exactly = 1) { previewPlayer.stopIfCurrent(1L) }
    }

    @Test
    fun `flagged resync never returns the currently linked youtube id`() = runTest {
        val flagged = TrackEntity(
            id = 7L,
            title = "Wrong Match",
            artist = "Artist",
            youtubeId = "current-video",
            matchFlagged = true,
            isDownloaded = true,
            filePath = "/music/wrong.opus",
        )
        coEvery { searchExecutor.search(any(), any()) } returns listOf(
            YtDlpSearchResult(id = "current-video", title = "Wrong Match"),
        )
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns listOf(
            YtDlpSearchResult(id = "current-video", title = "Wrong Match"),
        )
        val vm = makeVm(flagged = listOf(flagged))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertNull(
            "the current rejected youtubeId must never be surfaced as its own replacement",
            vm.uiState.value.resyncCandidates[flagged.id],
        )
    }

    @Test
    fun `flagged row wins when the same track also appears as unmatched`() = runTest {
        val flagged = TrackEntity(
            id = 1L,
            title = "Wrong Match",
            artist = "Artist",
            youtubeId = "current-video",
            matchFlagged = true,
            isDownloaded = true,
            filePath = "/music/wrong.opus",
        )
        coEvery { searchExecutor.search(any(), any()) } returns listOf(
            YtDlpSearchResult(id = "current-video", title = "Wrong Match"),
        )
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns listOf(
            YtDlpSearchResult(id = "current-video", title = "Wrong Match"),
        )
        val vm = makeVm(tracks = listOf(unmatched()), flagged = listOf(flagged))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertNull(vm.uiState.value.resyncCandidates[flagged.id])
        coVerify(exactly = 1) { searchExecutor.search(any(), any()) }
    }

    /**
     * #531: Evanescence recorded Lacrymosa twice. The other recording's row
     * already owns the top search hit, and tracks.youtube_id is UNIQUE, so
     * offering it could only end in "Can't swap — already linked".
     */
    @Test
    fun `flagged resync skips a video another track already owns`() = runTest {
        val flagged = TrackEntity(
            id = 7L,
            title = "Lacrymosa",
            artist = "Evanescence",
            youtubeId = "wrong-video",
            matchFlagged = true,
            isDownloaded = true,
            filePath = "/music/evanescence/singles/lacrymosa.opus",
        )
        coEvery { searchExecutor.search(any(), any()) } returns listOf(
            YtDlpSearchResult(id = "open-door-video", title = "Lacrymosa"),
            YtDlpSearchResult(id = "synthesis-video", title = "Lacrymosa"),
        )
        coEvery { musicRepository.findByYoutubeIds(any()) } answers {
            firstArg<Collection<String>>()
                .filter { it == "open-door-video" }
                .map { Track(id = 8L, title = "Lacrymosa", artist = "Evanescence", youtubeId = it) }
        }
        val vm = makeVm(flagged = listOf(flagged))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertEquals("synthesis-video", vm.uiState.value.resyncCandidates[flagged.id]?.videoId)
    }

    /**
     * #531: a flagged row's own youtube_id is often NULL (a lossless download
     * never writes one; the YouTube path's write fails on the UNIQUE index
     * when a sibling owns the video), so excluding "its own" video skipped
     * nothing and resync offered the sibling's video again.
     */
    @Test
    fun `a flagged row with no youtube id still skips its sibling's video`() = runTest {
        val flagged = TrackEntity(
            id = 7L,
            title = "Lacrymosa",
            artist = "Evanescence",
            youtubeId = null,
            matchFlagged = true,
            isDownloaded = true,
            filePath = "/music/evanescence/singles/lacrymosa.flac",
        )
        coEvery { searchExecutor.search(any(), any()) } returns listOf(
            YtDlpSearchResult(id = "open-door-video", title = "Lacrymosa"),
        )
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns listOf(
            YtDlpSearchResult(id = "open-door-video", title = "Lacrymosa"),
        )
        coEvery { musicRepository.findByYoutubeIds(any()) } returns listOf(
            Track(id = 8L, title = "Lacrymosa", artist = "Evanescence", youtubeId = "open-door-video"),
        )
        val vm = makeVm(flagged = listOf(flagged))
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect(messages::add) }

        vm.resync()
        advanceUntilIdle()

        assertNull(vm.uiState.value.resyncCandidates[flagged.id])
        assertTrue(
            "nothing usable left must read as no new matches, got $messages",
            messages.any { it.contains("No new matches") },
        )
    }

    /**
     * #531 review: flagging a swapped song again means the pick is wrong too
     * (re-flagging forgets it in the database). Its video must not be offered
     * back even if a pick is still recorded on the row, or the user is handed
     * the same wrong song again.
     */
    @Test
    fun `resync doesn't offer a re-flagged swap its own video again`() = runTest {
        val flagged = TrackEntity(
            id = 7L,
            title = "Lacrymosa",
            artist = "Evanescence",
            youtubeId = "picked-video",
            matchPickedAt = 1_000L,
            matchFlagged = true,
            isDownloaded = true,
            filePath = "/music/evanescence/synthesis/lacrymosa.opus",
        )
        coEvery { searchExecutor.search(any(), any()) } returns listOf(
            YtDlpSearchResult(id = "picked-video", title = "Lacrymosa"),
        )
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns listOf(
            YtDlpSearchResult(id = "picked-video", title = "Lacrymosa"),
        )
        coEvery { musicRepository.findByYoutubeIds(any()) } returns listOf(
            Track(id = 7L, title = "Lacrymosa", artist = "Evanescence", youtubeId = "picked-video"),
        )
        val vm = makeVm(flagged = listOf(flagged))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertNull(vm.uiState.value.resyncCandidates[flagged.id])
    }

    @Test
    fun `approving a row's own video is refused, even after a pick`() = runTest {
        val flagged = TrackEntity(
            id = 7L,
            title = "Lacrymosa",
            artist = "Evanescence",
            youtubeId = "picked-video",
            matchPickedAt = 1_000L,
            matchFlagged = true,
            isDownloaded = true,
            filePath = "/music/evanescence/synthesis/lacrymosa.opus",
        )
        coEvery { trackDao.findByYoutubeId("picked-video") } returns flagged
        val vm = makeVm(flagged = listOf(flagged))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        advanceUntilIdle()
        val row = vm.uiState.value.flaggedTracks.single()
        val candidate = ResyncCandidate(
            videoId = "picked-video",
            title = "Lacrymosa",
            artist = "Evanescence",
            thumbnailUrl = null,
            durationSeconds = 230.0,
        )

        vm.approveSwap(row, candidate)
        advanceUntilIdle()

        verify(exactly = 0) { swapCoordinator.swap(any(), any()) }
    }

    @Test
    fun `approval boundary rejects a self swap`() = runTest {
        val vm = makeVm()
        val row = FlaggedTrackRow(
            trackId = 7L,
            title = "Wrong Match",
            artist = "Artist",
            albumArtUrl = null,
            currentYoutubeId = "current-video",
            currentFilePath = "/music/wrong.opus",
            searchQuery = "Artist - Wrong Match",
        )
        val candidate = ResyncCandidate(
            videoId = "current-video",
            title = "Wrong Match",
            artist = "Artist",
            thumbnailUrl = null,
            durationSeconds = 180.0,
        )

        vm.approveSwap(row, candidate)
        advanceUntilIdle()

        coVerify(exactly = 0) { trackDao.findByYoutubeId(any()) }
        coVerify(exactly = 0) { musicRepository.setMatchFlagged(any(), any()) }
        verify(exactly = 0) {
            swapCoordinator.swap(any(), any())
        }
    }

    /**
     * #531: "Can't swap — 'Lacrymosa' is already linked to Evanescence —
     * Lacrymosa" read like the track blocking itself. Naming the other
     * recording's album shows it is a different track.
     */
    @Test
    fun `the can't-swap message names the album of the track that owns the video`() = runTest {
        coEvery { trackDao.findByYoutubeId("open-door-video") } returns TrackEntity(
            id = 8L,
            title = "Lacrymosa",
            artist = "Evanescence",
            album = "The Open Door",
            youtubeId = "open-door-video",
        )
        val vm = makeVm()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect(messages::add) }
        val row = FlaggedTrackRow(
            trackId = 7L,
            title = "Lacrymosa",
            artist = "Evanescence",
            albumArtUrl = null,
            currentYoutubeId = null,
            currentFilePath = "/music/evanescence/synthesis/lacrymosa.opus",
            searchQuery = "Evanescence - Lacrymosa",
            album = "Synthesis",
        )
        val candidate = ResyncCandidate(
            videoId = "open-door-video",
            title = "Lacrymosa",
            artist = "Evanescence",
            thumbnailUrl = null,
            durationSeconds = 230.0,
        )

        vm.approveSwap(row, candidate)
        advanceUntilIdle()

        assertTrue(
            "got $messages",
            messages.any { it.contains("already linked") && it.contains("The Open Door") },
        )
        coVerify(exactly = 0) { musicRepository.setMatchFlagged(any(), any()) }
        verify(exactly = 0) { swapCoordinator.swap(any(), any()) }
    }
}
