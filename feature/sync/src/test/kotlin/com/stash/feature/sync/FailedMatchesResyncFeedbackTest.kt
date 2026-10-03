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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    private val runningSwaps = MutableStateFlow<Set<Long>>(emptySet())

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { previewPlayer.playerErrors } returns MutableSharedFlow<PreviewErrorEvent>()
        every { previewPlayer.previewState } returns MutableStateFlow(PreviewState.Idle)
        every { swapCoordinator.outcomes } returns swapOutcomes
        // Like the coordinator: one swap per track, tracked in `running`.
        every { swapCoordinator.running } returns runningSwaps
        every { swapCoordinator.swap(any(), any()) } answers {
            val trackId = firstArg<Long>()
            if (trackId in runningSwaps.value) {
                false
            } else {
                runningSwaps.update { it + trackId }
                true
            }
        }
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

    /** What the coordinator does when a swap ends: stop tracking it, then report. */
    private suspend fun finish(outcome: SwapOutcome) {
        runningSwaps.update { it - outcome.trackId }
        swapOutcomes.emit(outcome)
    }

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

        finish(SwapOutcome.DownloadFailed(trackId = 7L, newVideoId = "right-video", title = "Lacrymosa"))
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

        finish(SwapOutcome.Swapped(trackId = 7L, newVideoId = "right-video", title = "Lacrymosa"))
        advanceUntilIdle()

        assertTrue("got $messages", messages.any { it.startsWith("Swapped") && it.contains("Lacrymosa") })
        assertNull(vm.uiState.value.resyncCandidates[7L])
    }

    @Test fun `a swap whose video another track took says which one and drops the candidate`() = runTest {
        val (vm, messages) = approveFlaggedSwap()

        finish(
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
        finish(SwapOutcome.DownloadFailed(trackId = 7L, newVideoId = "right-video", title = "Lacrymosa"))
        advanceUntilIdle()

        val row = vm.uiState.value.flaggedTracks.single()
        val candidate = vm.uiState.value.resyncCandidates.getValue(7L)
        vm.approveSwap(row, candidate)
        advanceUntilIdle()

        verify(exactly = 2) { swapCoordinator.swap(7L, "right-video") }
    }

    // -- #531 review: the row stays put until the swap is done ---------------

    @Test fun `approving a swap keeps the row flagged, shows it swapping and keeps the replacement`() = runTest {
        val (vm, _) = approveFlaggedSwap()

        // Clearing the flag first made the row vanish (or the screen read "All
        // caught up!") and pop back on failure; if the app died mid-download
        // the track was lost with its wrong audio and no word.
        coVerify(exactly = 0) { musicRepository.setMatchFlagged(any(), any()) }
        assertTrue(7L in vm.uiState.value.swappingTrackIds)
        assertEquals("right-video", vm.uiState.value.resyncCandidates[7L]?.videoId)
    }

    @Test fun `the row stops showing swapping once the swap reports back`() = runTest {
        val (vm, _) = approveFlaggedSwap()
        assertTrue(7L in vm.uiState.value.swappingTrackIds)

        finish(SwapOutcome.DownloadFailed(trackId = 7L, newVideoId = "right-video", title = "Lacrymosa"))
        advanceUntilIdle()

        assertTrue(vm.uiState.value.swappingTrackIds.isEmpty())
    }

    @Test fun `every outcome message is shown, even while a snackbar is up`() = runTest {
        val vm = makeVm(tracks = emptyList())
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        // Like the screen: each message holds the snackbar for a few seconds.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.userMessages.collect {
                messages.add(it)
                delay(4_000)
            }
        }

        swapOutcomes.emit(SwapOutcome.DownloadFailed(trackId = 7L, newVideoId = "a", title = "Lacrymosa"))
        swapOutcomes.emit(SwapOutcome.Swapped(trackId = 8L, newVideoId = "b", title = "Your Star"))
        swapOutcomes.emit(SwapOutcome.Swapped(trackId = 9L, newVideoId = "c", title = "My Immortal"))
        // advanceUntilIdle() doesn't wait on background work; let three
        // snackbars' worth of time pass.
        advanceTimeBy(15_000)
        runCurrent()

        assertEquals("got $messages", 3, messages.size)
        assertTrue(messages[0].contains("Couldn't download the replacement for 'Lacrymosa'"))
        assertTrue(messages[1].contains("Your Star"))
        assertTrue(messages[2].contains("My Immortal"))
    }

    @Test fun `a failed save says to check storage, not to download again`() = runTest {
        val (vm, messages) = approveFlaggedSwap()

        finish(SwapOutcome.SaveFailed(trackId = 7L, newVideoId = "right-video", title = "Lacrymosa"))
        advanceUntilIdle()

        val message = messages.single()
        assertTrue(message, message.contains("Couldn't save the replacement for 'Lacrymosa'"))
        assertTrue(message, message.contains("storage"))
        assertEquals("right-video", vm.uiState.value.resyncCandidates[7L]?.videoId)
    }

    // -- #531 review: the album-named search is title-checked ----------------

    @Test fun `the album-named search only takes a result with the song's title`() = runTest {
        coEvery { searchExecutor.search(match { it.contains("Synthesis") }, any()) } returns listOf(
            YtDlpSearchResult(id = "other-song", title = "Bring Me to Life", album = "Synthesis"),
            YtDlpSearchResult(id = "right", title = "Lacrymosa (Synthesis)", album = "Synthesis"),
        )
        val vm = makeVm(tracks = emptyList(), flagged = listOf(flaggedLacrymosa()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertEquals("right", vm.uiState.value.resyncCandidates[7L]?.videoId)
    }

    @Test fun `the album-named search prefers a result on the track's album`() = runTest {
        coEvery { searchExecutor.search(match { it.contains("Synthesis") }, any()) } returns listOf(
            YtDlpSearchResult(id = "live", title = "Lacrymosa", album = "Live from Rome"),
            YtDlpSearchResult(id = "synthesis", title = "Lacrymosa", album = "Synthesis"),
        )
        val vm = makeVm(tracks = emptyList(), flagged = listOf(flaggedLacrymosa()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertEquals("synthesis", vm.uiState.value.resyncCandidates[7L]?.videoId)
    }

    @Test fun `with no album-named result titled like the song, the plain search decides`() = runTest {
        coEvery { searchExecutor.search(match { it.contains("Synthesis") }, any()) } returns listOf(
            YtDlpSearchResult(id = "other-song", title = "Bring Me to Life", album = "Synthesis"),
        )
        coEvery { searchExecutor.search("Evanescence - Lacrymosa", any()) } returns listOf(
            YtDlpSearchResult(id = "plain-hit", title = "Lacrymosa"),
        )
        val vm = makeVm(tracks = emptyList(), flagged = listOf(flaggedLacrymosa()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertEquals("plain-hit", vm.uiState.value.resyncCandidates[7L]?.videoId)
    }

    // -- #531 review: the coordinator owns "one swap per track" ------------------

    @Test fun `a re-created screen shows a running swap and won't start a second one`() = runTest {
        // The user approved, left the screen and came back: the swap still runs.
        runningSwaps.value = setOf(7L)
        coEvery { searchExecutor.search(any(), any()) } returns
            listOf(YtDlpSearchResult(id = "right-video", title = "Lacrymosa"))
        val vm = makeVm(tracks = emptyList(), flagged = listOf(flaggedLacrymosa()))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        vm.resync()
        advanceUntilIdle()

        assertTrue(7L in vm.uiState.value.swappingTrackIds)
        val row = vm.uiState.value.flaggedTracks.single()
        vm.approveSwap(row, vm.uiState.value.resyncCandidates.getValue(7L))
        advanceUntilIdle()

        verify(exactly = 0) { swapCoordinator.swap(any(), any()) }
    }

    @Test fun `an outcome for another video doesn't bring back this candidate`() = runTest {
        val (vm, _) = approveFlaggedSwap()
        // A resync while the swap runs finds nothing for the row.
        coEvery { searchExecutor.search(any(), any()) } returns emptyList()
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns emptyList()
        vm.resync()
        advanceUntilIdle()

        finish(SwapOutcome.DownloadFailed(trackId = 7L, newVideoId = "other-video", title = "Lacrymosa"))
        advanceUntilIdle()

        assertNull(vm.uiState.value.resyncCandidates[7L])
    }

    // -- #531 review: an edition suffix doesn't hide the song --------------------

    @Test fun `an edition suffix on the song's title doesn't hide its plain-titled result`() = runTest {
        val remastered = flaggedLacrymosa().copy(title = "Lacrymosa - Remastered 2011")
        coEvery { searchExecutor.search(match { it.contains("Synthesis") }, any()) } returns listOf(
            YtDlpSearchResult(id = "right", title = "Lacrymosa", album = "Synthesis"),
        )
        coEvery { searchExecutor.search(match { !it.contains("Synthesis") }, any()) } returns emptyList()
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns emptyList()
        val vm = makeVm(tracks = emptyList(), flagged = listOf(remastered))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }

        vm.resync()
        advanceUntilIdle()

        assertEquals("right", vm.uiState.value.resyncCandidates[7L]?.videoId)
    }

    @Test fun `unflagging a row that is swapping does nothing`() = runTest {
        val (vm, _) = approveFlaggedSwap()

        vm.unflagTrack(7L)
        advanceUntilIdle()

        // Unflagging mid-swap made the startup repair treat a killed swap as
        // finished and delete the audio the user chose to keep.
        coVerify(exactly = 0) { musicRepository.setMatchFlagged(any(), any()) }
    }
}
