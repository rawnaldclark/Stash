package com.stash.feature.sync

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.media.preview.PreviewErrorEvent
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.DownloadStatus
import com.stash.core.model.QualityTier
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.SwapCoordinator
import com.stash.data.download.files.SwapOutcome
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.download.ytdlp.YtDlpSearchResult
import com.stash.data.ytmusic.SearchUnavailableException
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.SearchAllResults
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TrackSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * "Find it yourself" on Failed Matches (#19), the view model half: a song's
 * finder searches its artist and title on YouTube Music (the Search tab's own
 * search) and all of YouTube, shows YouTube Music's rows first, and a pick goes
 * through the same approve (unmatched) or swap (flagged) as an automatic match.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailedMatchesManualSearchTest {

    private val musicRepository: MusicRepository = mockk(relaxed = true)
    private val previewPlayer: PreviewPlayer = mockk(relaxed = true)
    private val previewUrlExtractor: PreviewUrlExtractor = mockk(relaxed = true)
    private val searchExecutor: HybridSearchExecutor = mockk(relaxed = true)
    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true)
    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true)
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)
    private val swapCoordinator: SwapCoordinator = mockk(relaxed = true)
    private val blocklistGuard = mockk<BlocklistGuard>(relaxed = true)
    private val trackIdentityEvents = mockk<TrackIdentityEvents>(relaxed = true)
    private val albumMatchExecutor = mockk<AlbumMatchExecutor>(relaxed = true)
    private val ytMusicApi = mockk<YTMusicApiClient>()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { previewPlayer.playerErrors } returns MutableSharedFlow<PreviewErrorEvent>()
        every { previewPlayer.previewState } returns MutableStateFlow(PreviewState.Idle)
        every { swapCoordinator.outcomes } returns MutableSharedFlow<SwapOutcome>()
        every { swapCoordinator.running } returns MutableStateFlow(emptySet())
        every { swapCoordinator.swap(any(), any()) } returns true
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        coEvery { blocklistGuard.isBlockedByTrackId(any()) } returns false
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns
            DownloadResult.Error("test-stop")
        coEvery { ytMusicApi.searchAll(any()) } returns SearchAllResults(emptyList())
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns emptyList()
        coEvery { searchExecutor.search(any(), any()) } returns emptyList()
        coEvery { musicRepository.findByYoutubeIds(any()) } returns emptyList()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private val song = "song1xxxxxx"
    private val live = "live1xxxxxx"
    private val upload = "upload1xxxx"
    private val wrong = "wrongxxxxxx"

    private fun music(videoId: String) = TrackSummary(
        videoId = videoId,
        title = "Get Lucky",
        artist = "Daft Punk",
        album = "Random Access Memories",
        durationSeconds = 369.0,
        thumbnailUrl = null,
        musicVideoType = "ATV",
    )

    private fun answer(vararg videoIds: String) =
        SearchAllResults(listOf(SearchResultSection.Songs(videoIds.map { music(it) })))

    private fun youTube(videoId: String) =
        YtDlpSearchResult(id = videoId, title = "Get Lucky (Live)", uploader = "Fan", channel = "Fan", duration = 300.0)

    private fun unmatched(trackId: Long = 1L, title: String = "Get Lucky") = UnmatchedTrackView(
        id = trackId + 10, trackId = trackId, title = title, artist = "Daft Punk",
        albumArtUrl = null, createdAt = 0L, rejectedVideoId = null,
        searchQuery = "Daft Punk - $title",
    )

    private fun flagged() = TrackEntity(
        id = 7L,
        title = "Lacrymosa",
        artist = "Evanescence",
        album = "Synthesis",
        durationMs = 230_000L,
        youtubeId = wrong,
        matchFlagged = true,
        isDownloaded = true,
        filePath = "/music/evanescence/synthesis/lacrymosa.opus",
    )

    private fun TestScope.makeVm(
        tracks: List<UnmatchedTrackView> = listOf(unmatched()),
        flaggedTracks: List<TrackEntity> = emptyList(),
        flaggedFlow: Flow<List<TrackEntity>> = flowOf(flaggedTracks),
    ): FailedMatchesViewModel {
        every { musicRepository.getUnmatchedTracks() } returns flowOf(tracks)
        every { musicRepository.getFlaggedTracks() } returns flaggedFlow
        val vm = FailedMatchesViewModel(
            musicRepository, previewPlayer, previewUrlExtractor, searchExecutor,
            downloadExecutor, fileOrganizer, qualityPrefs, trackDao,
            downloadQueueDao, swapCoordinator, blocklistGuard,
            mockk(relaxed = true) { every { acceptDownloadOrDelete(any()) } returns true },
            trackIdentityEvents, albumMatchExecutor, ytMusicApi,
        )
        // The screen keeps uiState collected; the finder reads the rows from it.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun FailedMatchesViewModel.shownIds() = manualSearch.value!!.results.map { it.candidate.videoId }

    @Test fun `a song's finder searches its artist and title on YouTube Music and all of YouTube`() = runTest {
        coEvery { ytMusicApi.searchAll("Daft Punk Get Lucky") } returns answer(song)
        coEvery { searchExecutor.searchYtDlpDirect("Daft Punk Get Lucky", 10) } returns listOf(youTube(upload))
        val vm = makeVm()

        vm.toggleManualSearch(trackId = 1L, artist = "Daft Punk", title = "Get Lucky")
        advanceUntilIdle()

        val open = vm.manualSearch.value!!
        assertEquals(1L, open.trackId)
        assertEquals("Daft Punk Get Lucky", open.query)
        assertEquals(listOf(song, upload), vm.shownIds())
        assertFalse(open.searchingMusic)
        assertFalse(open.searchingYouTube)
        assertFalse(open.failed)
    }

    @Test fun `YouTube Music's rows show while all of YouTube is still searching`() = runTest {
        val youTubeAnswer = CompletableDeferred<List<YtDlpSearchResult>>()
        coEvery { ytMusicApi.searchAll(any()) } returns answer(song)
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } coAnswers { youTubeAnswer.await() }
        val vm = makeVm()

        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        runCurrent()
        assertEquals(listOf(song), vm.shownIds())
        assertTrue(vm.manualSearch.value!!.searchingYouTube)

        youTubeAnswer.complete(listOf(youTube(upload)))
        runCurrent()
        assertEquals(listOf(song, upload), vm.shownIds())
        assertFalse(vm.manualSearch.value!!.searchingYouTube)
    }

    @Test fun `a newer search is never overwritten by an older search's late answer`() = runTest {
        val firstAnswer = CompletableDeferred<SearchAllResults>()
        coEvery { ytMusicApi.searchAll("Daft Punk Get Lucky") } coAnswers { firstAnswer.await() }
        coEvery { ytMusicApi.searchAll("get lucky live") } returns answer(live)
        val vm = makeVm()

        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        vm.searchManually("get lucky live")
        runCurrent()
        firstAnswer.complete(answer(song))
        advanceUntilIdle()

        assertEquals("get lucky live", vm.manualSearch.value!!.query)
        assertEquals(listOf(live), vm.shownIds())
    }

    @Test fun `a flagged song's finder never offers the video it was flagged for`() = runTest {
        coEvery { ytMusicApi.searchAll(any()) } returns answer(wrong, song)
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns listOf(youTube(wrong))
        val vm = makeVm(tracks = emptyList(), flaggedTracks = listOf(flagged()))

        vm.toggleManualSearch(7L, "Evanescence", "Lacrymosa")
        advanceUntilIdle()

        assertEquals(listOf(song), vm.shownIds())
    }

    // -- Opened from Now Playing ("Find a better match") ----------------------

    @Test fun `Now Playing's jump opens the flagged song's finder, searched for it`() = runTest {
        coEvery { ytMusicApi.searchAll("Evanescence Lacrymosa") } returns answer(wrong, song)
        val vm = makeVm(tracks = listOf(unmatched()), flaggedTracks = listOf(flagged()))
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect(messages::add) }

        vm.openFinderFor(7L)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), messages)

        val open = vm.manualSearch.value!!
        assertEquals(7L, open.trackId)
        assertEquals("Evanescence Lacrymosa", open.query)
        // Searched straight away, and never offering the video it was flagged for.
        assertEquals(listOf(song), vm.shownIds())
    }

    @Test fun `the jump waits for the just-flagged song to reach the list`() = runTest {
        val flaggedNow = MutableStateFlow<List<TrackEntity>>(emptyList())
        val vm = makeVm(tracks = listOf(unmatched()), flaggedFlow = flaggedNow)

        vm.openFinderFor(7L)
        runCurrent()
        assertNull(vm.manualSearch.value)

        flaggedNow.value = listOf(flagged())
        runCurrent()
        assertEquals(7L, vm.manualSearch.value?.trackId)
    }

    @Test fun `a song that never reaches the list opens nothing and says so`() = runTest {
        val vm = makeVm(tracks = listOf(unmatched()))
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.userMessages.collect(messages::add) }

        vm.openFinderFor(99L)
        advanceTimeBy(FailedMatchesViewModel.FOCUS_WAIT_MS - 1)
        runCurrent()
        assertEquals(emptyList<String>(), messages)

        advanceTimeBy(2)
        runCurrent()

        assertNull(vm.manualSearch.value)
        coVerify(exactly = 0) { ytMusicApi.searchAll(any()) }
        assertEquals(listOf(FailedMatchesViewModel.FOCUS_NOT_FOUND_MESSAGE), messages)
    }

    @Test fun `the jump never closes a finder already open on the song`() = runTest {
        val vm = makeVm(tracks = emptyList(), flaggedTracks = listOf(flagged()))
        vm.toggleManualSearch(7L, "Evanescence", "Lacrymosa")
        vm.searchManually("lacrymosa synthesis")

        vm.openFinderFor(7L)
        advanceUntilIdle()

        assertEquals(7L, vm.manualSearch.value?.trackId)
        // Left as the listener had it, words and all.
        assertEquals("lacrymosa synthesis", vm.manualSearch.value?.query)
    }

    @Test fun `a finder the listener opened while the jump waited stays theirs`() = runTest {
        val flaggedNow = MutableStateFlow<List<TrackEntity>>(emptyList())
        val vm = makeVm(tracks = listOf(unmatched(1L)), flaggedFlow = flaggedNow)

        vm.openFinderFor(7L)
        runCurrent()
        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        flaggedNow.value = listOf(flagged())
        advanceUntilIdle()

        assertEquals(1L, vm.manualSearch.value?.trackId)
    }

    @Test fun `the jump to an unmatched song searches its artist and title`() = runTest {
        val vm = makeVm(tracks = listOf(unmatched(1L), unmatched(2L, title = "Instant Crush")))

        vm.openFinderFor(2L)
        advanceUntilIdle()

        assertEquals(2L, vm.manualSearch.value?.trackId)
        assertEquals("Daft Punk Instant Crush", vm.manualSearch.value?.query)
    }

    @Test fun `tapping the open song closes its finder, tapping another moves it`() = runTest {
        val vm = makeVm(tracks = listOf(unmatched(1L), unmatched(2L, title = "Instant Crush")))

        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        assertEquals(1L, vm.manualSearch.value?.trackId)
        vm.toggleManualSearch(2L, "Daft Punk", "Instant Crush")
        assertEquals("Daft Punk Instant Crush", vm.manualSearch.value?.query)
        vm.toggleManualSearch(2L, "Daft Punk", "Instant Crush")
        assertNull(vm.manualSearch.value)
    }

    @Test fun `picking a result for an unmatched song approves it as an automatic match is approved`() = runTest {
        coEvery { ytMusicApi.searchAll(any()) } returns answer(song)
        val vm = makeVm()
        vm.approvedDownloadScope = backgroundScope
        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        advanceUntilIdle()

        vm.pickManualMatch(vm.manualSearch.value!!.results.single().candidate)
        advanceUntilIdle()
        runCurrent()

        coVerify(exactly = 1) { trackDao.updateYoutubeId(1L, song) }
        coVerify(exactly = 1) { downloadQueueDao.updateStatus(11L, DownloadStatus.COMPLETED, any(), any(), any(), any()) }
        coVerify(exactly = 1) {
            downloadExecutor.download("https://www.youtube.com/watch?v=$song", any(), "approve_$song", any(), any())
        }
        assertNull(vm.manualSearch.value)
    }

    @Test fun `picking a result for a flagged song swaps it in`() = runTest {
        coEvery { ytMusicApi.searchAll(any()) } returns answer(song)
        val vm = makeVm(tracks = emptyList(), flaggedTracks = listOf(flagged()))
        vm.toggleManualSearch(7L, "Evanescence", "Lacrymosa")
        advanceUntilIdle()

        vm.pickManualMatch(vm.manualSearch.value!!.results.single().candidate)
        advanceUntilIdle()

        verify(exactly = 1) { swapCoordinator.swap(7L, song) }
        assertNull(vm.manualSearch.value)
    }

    @Test fun `no answer from YouTube Music and nothing from YouTube reads as couldn't search`() = runTest {
        coEvery { ytMusicApi.searchAll(any()) } throws SearchUnavailableException(-1)
        val vm = makeVm()

        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        advanceUntilIdle()

        val open = vm.manualSearch.value!!
        assertTrue(open.foundNothing)
        assertTrue(open.failed)
    }

    @Test fun `an answer with nothing in it reads as nothing found`() = runTest {
        val vm = makeVm()

        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        advanceUntilIdle()

        val open = vm.manualSearch.value!!
        assertTrue(open.foundNothing)
        assertFalse(open.failed)
    }

    @Test fun `closing the finder stops the preview of one of its results`() = runTest {
        val previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
        every { previewPlayer.previewState } returns previewState
        coEvery { ytMusicApi.searchAll(any()) } returns answer(song)
        val vm = makeVm()
        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        advanceUntilIdle()
        previewState.value = PreviewState.Playing(song)

        vm.closeManualSearch()

        verify { previewPlayer.stopIfCurrent(any()) }
        assertNull(vm.manualSearch.value)
    }

    /** Its row and stop button go with the old results; it would play to the end. */
    @Test fun `searching again stops the preview of a result the new search replaces`() = runTest {
        val previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
        every { previewPlayer.previewState } returns previewState
        coEvery { ytMusicApi.searchAll("Daft Punk Get Lucky") } returns answer(song)
        coEvery { ytMusicApi.searchAll("get lucky live") } returns answer(live)
        val vm = makeVm()
        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        advanceUntilIdle()
        previewState.value = PreviewState.Playing(song)

        vm.searchManually("get lucky live")
        advanceUntilIdle()

        verify(exactly = 1) { previewPlayer.stopIfCurrent(any()) }
        assertEquals(listOf(live), vm.shownIds())
    }

    @Test fun `searching an empty box stops the preview of a result too`() = runTest {
        val previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
        every { previewPlayer.previewState } returns previewState
        coEvery { ytMusicApi.searchAll(any()) } returns answer(song)
        val vm = makeVm()
        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        advanceUntilIdle()
        previewState.value = PreviewState.Playing(song)

        vm.searchManually("  ")

        verify(exactly = 1) { previewPlayer.stopIfCurrent(any()) }
        assertTrue(vm.manualSearch.value!!.results.isEmpty())
    }

    /**
     * The finder is its own thing: opening it and searching leave the songs'
     * automatic matches as they were, and a preview of one (its row keeps its
     * stop button) plays on when the finder's rows change.
     */
    @Test fun `the finder leaves the automatic matches and their previews alone`() = runTest {
        val auto = "autoxxxxxxx"
        coEvery { searchExecutor.search(any(), any()) } returns
            listOf(YtDlpSearchResult(id = auto, title = "Get Lucky", duration = 369.0))
        coEvery { ytMusicApi.searchAll(any()) } returns answer(auto, song)
        val previewState = MutableStateFlow<PreviewState>(PreviewState.Idle)
        every { previewPlayer.previewState } returns previewState
        val vm = makeVm()
        vm.resync()
        advanceUntilIdle()
        val automatic = vm.uiState.value.resyncCandidates
        assertEquals(auto, automatic[1L]?.videoId)
        previewState.value = PreviewState.Playing(auto)

        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        advanceUntilIdle()
        vm.searchManually("get lucky live")
        advanceUntilIdle()
        vm.closeManualSearch()

        assertEquals(automatic, vm.uiState.value.resyncCandidates)
        verify(exactly = 0) { previewPlayer.stopIfCurrent(any()) }
    }

    @Test fun `a song you find yourself is recorded as your pick`() = runTest {
        coEvery { ytMusicApi.searchAll(any()) } returns answer(song)
        val vm = makeVm()
        vm.approvedDownloadScope = backgroundScope
        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        advanceUntilIdle()

        vm.pickManualMatch(vm.manualSearch.value!!.results.single().candidate)
        advanceUntilIdle()

        coVerify(exactly = 1) { trackDao.markMatchPicked(1L, any()) }
    }

    @Test fun `an automatic match approved on its row is not recorded as a pick`() = runTest {
        val vm = makeVm()
        vm.approvedDownloadScope = backgroundScope

        vm.approveMatch(
            1L, 11L,
            ResyncCandidate(videoId = song, title = "Get Lucky", artist = "Daft Punk", thumbnailUrl = null, durationSeconds = 369.0),
        )
        advanceUntilIdle()

        coVerify(exactly = 1) { trackDao.updateYoutubeId(1L, song) }
        coVerify(exactly = 0) { trackDao.markMatchPicked(any(), any()) }
    }

    /** tracks.youtube_id is UNIQUE: picking one could only fail with "already linked" (#531). */
    @Test fun `videos other songs already have are left out`() = runTest {
        coEvery { ytMusicApi.searchAll(any()) } returns answer(song, live)
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns listOf(youTube(upload))
        coEvery { musicRepository.findByYoutubeIds(any()) } returns listOf(
            com.stash.core.model.Track(id = 99L, title = "Get Lucky (Live)", artist = "Daft Punk", youtubeId = live),
            com.stash.core.model.Track(id = 98L, title = "Fan upload", artist = "Fan", youtubeId = upload),
            // The song's own row having the video doesn't count.
            com.stash.core.model.Track(id = 1L, title = "Get Lucky", artist = "Daft Punk", youtubeId = song),
        )
        val vm = makeVm()

        vm.toggleManualSearch(1L, "Daft Punk", "Get Lucky")
        advanceUntilIdle()

        assertEquals(listOf(song), vm.shownIds())
    }
}
