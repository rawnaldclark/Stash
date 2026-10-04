package com.stash.feature.sync

import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.preview.PreviewErrorEvent
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.SwapCoordinator
import com.stash.data.download.files.SwapOutcome
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.core.model.QualityTier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Approving a resync candidate names a temp file and a yt-dlp URL after the
 * candidate's video id and writes the id to the track. A candidate whose id
 * doesn't have the YouTube video-id shape is refused before any of that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedMatchesApproveVideoIdTest {

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
    private val trackIdentityEvents = mockk<TrackIdentityEvents>(relaxed = true)
    private val albumMatchExecutor = mockk<AlbumMatchExecutor>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { previewPlayer.playerErrors } returns MutableSharedFlow<PreviewErrorEvent>()
        every { previewPlayer.previewState } returns MutableStateFlow(PreviewState.Idle)
        every { swapCoordinator.outcomes } returns MutableSharedFlow<SwapOutcome>()
        every { swapCoordinator.running } returns MutableStateFlow(emptySet())
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        coEvery { blocklistGuard.isBlockedByTrackId(any()) } returns false
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns
            DownloadResult.Error("test-stop")
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm(): FailedMatchesViewModel {
        val unmatched = UnmatchedTrackView(
            id = 1L, trackId = 1L, title = "Title", artist = "Artist",
            albumArtUrl = null, createdAt = 0L, rejectedVideoId = null,
            searchQuery = "Artist - Title",
        )
        every { musicRepository.getUnmatchedTracks() } returns flowOf(listOf(unmatched))
        every { musicRepository.getFlaggedTracks() } returns flowOf(emptyList())
        return FailedMatchesViewModel(
            musicRepository, previewPlayer, previewUrlExtractor, searchExecutor,
            downloadExecutor, fileOrganizer, qualityPrefs, trackDao,
            downloadQueueDao, swapCoordinator, blocklistGuard,
            mockk(relaxed = true) { every { acceptDownloadOrDelete(any()) } returns true },
            trackIdentityEvents, albumMatchExecutor,
        )
    }

    private fun candidate(videoId: String) = ResyncCandidate(
        videoId = videoId,
        title = "Title",
        artist = "Artist",
        thumbnailUrl = null,
        durationSeconds = 200.0,
    )

    @Test fun `a candidate whose id isn't a YouTube video id is refused before anything is written`() = runTest {
        val vm = makeVm()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect { messages.add(it) } }

        val malformed = listOf("../x", "a%(title)s", "x\\y", "dQw4w9WgXc", "")
        malformed.forEach { id ->
            vm.approveMatch(trackId = 1L, queueEntryId = 1L, candidate = candidate(id))
        }
        advanceUntilIdle()

        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.updateYoutubeId(any(), any()) }
        coVerify(exactly = 0) { downloadQueueDao.updateStatus(any(), any(), any(), any(), any(), any()) }
        assertTrue("the user is told, got $messages", messages.size == malformed.size)
    }

    @Test fun `a real video id is approved and downloaded under its own name`() = runTest {
        val vm = makeVm()

        vm.approveMatch(trackId = 1L, queueEntryId = 1L, candidate = candidate("dQw4w9WgXcQ"))
        advanceUntilIdle()

        coVerify(exactly = 1) { trackDao.updateYoutubeId(1L, "dQw4w9WgXcQ") }
        coVerify(exactly = 1) {
            downloadExecutor.download(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                any(),
                "approve_dQw4w9WgXcQ",
                any(),
                any(),
            )
        }
    }
}
