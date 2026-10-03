package com.stash.feature.sync

import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewErrorEvent
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.SwapCoordinator
import com.stash.data.download.files.SwapOutcome
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.download.ytdlp.YtDlpSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #143 — "resync button does nothing". A resync that finds no candidates is
 * indistinguishable from a dead button unless it tells the user the pass
 * completed. These tests pin the completion feedback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedMatchesResyncFeedbackTest {

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
    private val albumMatchExecutor = mockk<AlbumMatchExecutor>()
    private val swapOutcomes = MutableSharedFlow<SwapOutcome>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { previewPlayer.playerErrors } returns MutableSharedFlow<PreviewErrorEvent>()
        every { previewPlayer.previewState } returns MutableStateFlow(PreviewState.Idle)
        every { swapCoordinator.outcomes } returns swapOutcomes
        coEvery { albumMatchExecutor.findTrackInAlbum(any(), any(), any(), any()) } returns null
        coEvery { musicRepository.findByYoutubeIds(any()) } returns emptyList()
        coEvery { trackDao.findByYoutubeId(any()) } returns null
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun unmatched() = UnmatchedTrackView(
        id = 1L, trackId = 1L, title = "Title", artist = "Artist",
        albumArtUrl = null, createdAt = 0L, rejectedVideoId = null,
        searchQuery = "Artist - Title",
    )

    /** #531: Evanescence recorded Lacrymosa twice; this is the Synthesis one, flagged. */
    private fun flaggedLacrymosa() = TrackEntity(
        id = 7L,
        title = "Lacrymosa",
        artist = "Evanescence",
        album = "Synthesis",
        durationMs = 230_000L,
        youtubeId = "wrong-video",
        matchFlagged = true,
        isDownloaded = true,
        filePath = "/music/evanescence/synthesis/lacrymosa.opus",
    )

    private fun makeVm(
        tracks: List<UnmatchedTrackView> = listOf(unmatched()),
        flagged: List<TrackEntity> = emptyList(),
    ): FailedMatchesViewModel {
        every { musicRepository.getUnmatchedTracks() } returns flowOf(tracks)
        every { musicRepository.getFlaggedTracks() } returns flowOf(flagged)
        return FailedMatchesViewModel(
            musicRepository, previewPlayer, previewUrlExtractor, searchExecutor,
            downloadExecutor, fileOrganizer, qualityPrefs, trackDao,
            downloadQueueDao, swapCoordinator, blocklistGuard,
            mockk(relaxed = true) { every { acceptDownloadOrDelete(any()) } returns true },
            trackIdentityEvents, albumMatchExecutor,
        )
    }

    @Test fun `resync with zero results tells the user no matches were found`() = runTest {
        coEvery { searchExecutor.search(any(), any()) } returns emptyList()
        val vm = makeVm()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect { messages.add(it) } }

        vm.resync()
        advanceUntilIdle()

        assertTrue(
            "expected a 'no matches' completion message, got $messages",
            messages.any { it.contains("no", ignoreCase = true) && it.contains("match", ignoreCase = true) },
        )
    }

    @Test fun `resync that finds a candidate reports how many replacements it found`() = runTest {
        coEvery { searchExecutor.search(any(), any()) } returns
            listOf(YtDlpSearchResult(id = "vid1", title = "Title"))
        val vm = makeVm()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect { messages.add(it) } }

        vm.resync()
        advanceUntilIdle()

        assertTrue(
            "expected a 'found N' completion message, got $messages",
            messages.any { it.contains("1") && it.contains("replacement", ignoreCase = true) },
        )
    }

    @Test fun `resync falls through to full-YouTube search when YT Music has no usable match`() = runTest {
        // #19/#143: some tracks exist on YouTube but not YouTube Music. When the
        // InnerTube (YT Music) pass yields nothing usable, resync must broaden to
        // a yt-dlp full-YouTube search instead of giving up.
        coEvery { searchExecutor.search(any(), any()) } returns emptyList()
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns
            listOf(YtDlpSearchResult(id = "ytOnly", title = "Title"))
        val vm = makeVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertEquals(
            "expected the full-YouTube result to become the candidate",
            "ytOnly",
            vm.uiState.value.resyncCandidates[1L]?.videoId,
        )
    }

    /**
     * #372 — "the Preview button is not working, and is kinda static". Preview
     * failures were caught and only `Log.e`'d, so a failing extraction was
     * indistinguishable from a dead button. Same shape as #143 above: if the
     * user can't see that it ran, it didn't.
     */
    @Test fun `a failed preview tells the user instead of silently doing nothing`() = runTest {
        coEvery { previewUrlExtractor.extractStreamUrl(any(), any()) } throws
            IllegalStateException("no stream")
        val vm = makeVm()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect { messages.add(it) } }

        vm.previewRejectedMatch("vid1")
        advanceUntilIdle()

        assertTrue(
            "a failed preview must surface a message, got $messages",
            messages.any { it.contains("preview", ignoreCase = true) },
        )
    }

    /** The loading flag must clear on failure too, or the row spins forever. */
    @Test fun `a failed preview clears the loading state`() = runTest {
        coEvery { previewUrlExtractor.extractStreamUrl(any(), any()) } throws
            IllegalStateException("no stream")
        val vm = makeVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.previewRejectedMatch("vid1")
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.previewLoading)
    }

    @Test fun `resync derives query from artist and title when the stored search query is blank`() = runTest {
        // Real bug: auto-requeued tracks (TrackDownloadWorker) get an empty
        // download_queue.search_query. Resync trusted that blank string and
        // searched for "" -> InnerTube empty -> yt-dlp throws "must not be
        // blank" -> "can't find a match", even though artist+title are known.
        val blankQueryTrack = UnmatchedTrackView(
            id = 1L, trackId = 1L, title = "In the Aeroplane Over the Sea",
            artist = "Neutral Milk Hotel", albumArtUrl = null, createdAt = 0L,
            rejectedVideoId = null, searchQuery = "",
        )
        val queries = mutableListOf<String>()
        coEvery { searchExecutor.search(capture(queries), any()) } returns
            listOf(YtDlpSearchResult(id = "vid1", title = "x"))
        val vm = makeVm(listOf(blankQueryTrack))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertTrue(
            "resync must search by artist+title, not the blank stored query; queries=$queries",
            queries.any { it == "Neutral Milk Hotel - In the Aeroplane Over the Sea" },
        )
    }

    // -- #531: resync for a flagged track looks for the same recording --------

    @Test fun `flagged resync tries the album's own tracklist first`() = runTest {
        coEvery {
            albumMatchExecutor.findTrackInAlbum("Lacrymosa", "Evanescence", "Synthesis", 230_000L)
        } returns YtDlpSearchResult(id = "synthesis-cut", title = "Lacrymosa")
        coEvery { searchExecutor.search(any(), any()) } returns
            listOf(YtDlpSearchResult(id = "top-hit", title = "Lacrymosa"))
        val vm = makeVm(tracks = emptyList(), flagged = listOf(flaggedLacrymosa()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertEquals("synthesis-cut", vm.uiState.value.resyncCandidates[7L]?.videoId)
        coVerify(exactly = 0) { searchExecutor.search(any(), any()) }
    }

    @Test fun `flagged resync searches with the album when its tracklist has no match`() = runTest {
        val queries = mutableListOf<String>()
        coEvery { searchExecutor.search(capture(queries), any()) } returns
            listOf(YtDlpSearchResult(id = "album-hit", title = "Lacrymosa"))
        val vm = makeVm(tracks = emptyList(), flagged = listOf(flaggedLacrymosa()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertEquals("album-hit", vm.uiState.value.resyncCandidates[7L]?.videoId)
        val first = queries.first()
        assertTrue(
            "the first search must name the album, got $queries",
            first.startsWith("Evanescence - Lacrymosa") && first.contains("Synthesis"),
        )
    }

    @Test fun `flagged resync falls back to the plain artist and title search`() = runTest {
        coEvery { searchExecutor.search(match { it.contains("Synthesis") }, any()) } returns emptyList()
        coEvery { searchExecutor.search("Evanescence - Lacrymosa", any()) } returns
            listOf(YtDlpSearchResult(id = "plain-hit", title = "Lacrymosa"))
        val vm = makeVm(tracks = emptyList(), flagged = listOf(flaggedLacrymosa()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertEquals("plain-hit", vm.uiState.value.resyncCandidates[7L]?.videoId)
        coVerifyOrder {
            searchExecutor.search(match { it.contains("Synthesis") }, any())
            searchExecutor.search("Evanescence - Lacrymosa", any())
        }
    }

    // -- #531: approving a swap always ends with the user knowing how it went --

    /** Resyncs the flagged Lacrymosa to [videoId] and approves that candidate. */
    private fun TestScope.approveFlaggedSwap(
        videoId: String = "right-video",
    ): Pair<FailedMatchesViewModel, MutableList<String>> {
        coEvery { searchExecutor.search(any(), any()) } returns
            listOf(YtDlpSearchResult(id = videoId, title = "Lacrymosa"))
        val vm = makeVm(tracks = emptyList(), flagged = listOf(flaggedLacrymosa()))
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect { messages.add(it) } }
        vm.resync()
        advanceUntilIdle()
        val row = vm.uiState.value.flaggedTracks.single()
        val candidate = vm.uiState.value.resyncCandidates.getValue(7L)
        messages.clear()

        vm.approveSwap(row, candidate)
        advanceUntilIdle()
        return vm to messages
    }

    @Test fun `an approved swap hands the coordinator the track and the video`() = runTest {
        approveFlaggedSwap()

        // The coordinator reads the track's album, file and title itself, so
        // a stale screen snapshot can't steer where the file goes.
        verify { swapCoordinator.swap(7L, "right-video") }
    }

    @Test fun `a failed swap keeps the candidate and tells the user`() = runTest {
        val (vm, messages) = approveFlaggedSwap()

        swapOutcomes.emit(SwapOutcome.Failed(trackId = 7L, newVideoId = "right-video", title = "Lacrymosa"))
        advanceUntilIdle()

        assertEquals(
            "the replacement must still be there to try again",
            "right-video",
            vm.uiState.value.resyncCandidates[7L]?.videoId,
        )
        assertTrue(
            "a failed swap must say so, got $messages",
            messages.any { it.contains("Couldn't download the replacement") && it.contains("try again") },
        )
    }

    @Test fun `a successful swap tells the user it worked`() = runTest {
        val (vm, messages) = approveFlaggedSwap()

        swapOutcomes.emit(SwapOutcome.Swapped(trackId = 7L, newVideoId = "right-video", title = "Lacrymosa"))
        advanceUntilIdle()

        assertTrue("got $messages", messages.any { it.startsWith("Swapped") && it.contains("Lacrymosa") })
        assertNull(vm.uiState.value.resyncCandidates[7L])
    }

    @Test fun `a swap whose video another track took says which one and drops the candidate`() = runTest {
        val (vm, messages) = approveFlaggedSwap()

        swapOutcomes.emit(
            SwapOutcome.AlreadyLinked(
                trackId = 7L,
                newVideoId = "right-video",
                title = "Lacrymosa",
                ownerArtist = "Evanescence",
                ownerTitle = "Lacrymosa",
                ownerAlbum = "The Open Door",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "got $messages",
            messages.any { it.contains("already linked") && it.contains("The Open Door") },
        )
        assertNull(
            "a video another track owns can never be swapped in, so don't offer it again",
            vm.uiState.value.resyncCandidates[7L],
        )
    }

    @Test fun `tapping approve twice starts one swap`() = runTest {
        val (vm, _) = approveFlaggedSwap()
        val row = vm.uiState.value.flaggedTracks.single()
        val sameCandidate = ResyncCandidate(
            videoId = "right-video",
            title = "Lacrymosa",
            artist = "Evanescence",
            thumbnailUrl = null,
            durationSeconds = 230.0,
        )

        // The row only disappears once the flag write lands, so a quick second
        // tap reaches approveSwap with the same candidate.
        vm.approveSwap(row, sameCandidate)
        advanceUntilIdle()

        verify(exactly = 1) { swapCoordinator.swap(any(), any()) }
    }

    @Test fun `a failed swap can be approved again`() = runTest {
        val (vm, _) = approveFlaggedSwap()
        swapOutcomes.emit(SwapOutcome.Failed(trackId = 7L, newVideoId = "right-video", title = "Lacrymosa"))
        advanceUntilIdle()

        val row = vm.uiState.value.flaggedTracks.single()
        val candidate = vm.uiState.value.resyncCandidates.getValue(7L)
        vm.approveSwap(row, candidate)
        advanceUntilIdle()

        verify(exactly = 2) { swapCoordinator.swap(7L, "right-video") }
    }
}
