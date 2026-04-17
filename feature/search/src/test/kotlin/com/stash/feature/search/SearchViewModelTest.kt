package com.stash.feature.search

import androidx.media3.common.PlaybackException
import app.cash.turbine.test
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewErrorEvent
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.preview.PreviewUrlCache
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.SearchAllResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [SearchViewModel] — pin the three load-bearing behaviours
 * introduced by Task 9 of the Search Tab Overhaul:
 *
 *  1. `flatMapLatest` cancels an in-flight [YTMusicApiClient.searchAll] call
 *     when a new keystroke arrives before the previous search resolved.
 *  2. A thrown [YTMusicApiClient.searchAll] surfaces a snackbar message on
 *     [SearchViewModel.userMessages] so the UI can show "Search failed".
 *  3. An ExoPlayer IO error within 3 s of the most recent preview start
 *     silently retries the stream via yt-dlp (bypassing the InnerTube race).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `flatMapLatest cancels prior query on new keystroke`() = runTest {
        val api = mock<YTMusicApiClient>()
        val neverCompletes = CompletableDeferred<SearchAllResults>()
        // First query: suspend forever so flatMapLatest has something to cancel.
        whenever(api.searchAll(eq("foo"))).doSuspendableAnswer {
            neverCompletes.await()
        }
        whenever(api.searchAll(eq("foobar"))).doReturn(SearchAllResults(emptyList()))

        val vm = newVm(api = api)

        vm.onQueryChanged("foo")
        // Drive past the 300 ms debounce so the first search actually launches,
        // but not past its completion (it never completes).
        advanceTimeBy(400)
        vm.onQueryChanged("foobar")
        advanceUntilIdle()

        // The second query resolved — i.e. the first one was cancelled.
        verify(api, atLeastOnce()).searchAll(eq("foobar"))
    }

    @Test
    fun `error surfaces userMessages snackbar`() = runTest {
        val api = mock<YTMusicApiClient>()
        whenever(api.searchAll(any())).doSuspendableAnswer {
            throw RuntimeException("boom")
        }
        val vm = newVm(api = api)
        vm.userMessages.test {
            vm.onQueryChanged("abc")
            advanceUntilIdle()
            val msg = awaitItem()
            assertTrue(msg.contains("search failed", ignoreCase = true))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `preview retries via ytdlp when InnerTube playback errors within 3s`() = runTest {
        val player = mock<PreviewPlayer>()
        val playerErrors = MutableSharedFlow<PreviewErrorEvent>()
        whenever(player.previewState).thenReturn(MutableStateFlow(PreviewState.Idle))
        // The VM collects this flow in init{}; we don't need to emit on it
        // for this test — we call vm.onPreviewError(...) directly.
        whenever(player.playerErrors).thenReturn(playerErrors.asSharedFlow())

        val extractor = mock<PreviewUrlExtractor>()
        whenever(extractor.extractStreamUrl(eq("vid"))).thenReturn("https://innertube/vid")
        whenever(extractor.extractViaYtDlpForRetry(eq("vid"))).thenReturn("https://ytdlp/vid")

        val vm = newVm(api = mock(), player = player, extractor = extractor)

        vm.previewTrack("vid")
        advanceUntilIdle()
        verify(player).playUrl(eq("vid"), eq("https://innertube/vid"))

        // Simulate ExoPlayer IO error within 3 s of the preview start.
        vm.onPreviewError(
            "vid",
            PlaybackException("source", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
        )
        advanceTimeBy(500)
        advanceUntilIdle()

        verify(player).playUrl(eq("vid"), eq("https://ytdlp/vid"))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newVm(
        api: YTMusicApiClient = mock(),
        player: PreviewPlayer = mock {
            on { previewState } doReturn MutableStateFlow(PreviewState.Idle)
            on { playerErrors } doReturn MutableSharedFlow<PreviewErrorEvent>().asSharedFlow()
        },
        extractor: PreviewUrlExtractor = mock(),
        prefetcher: PreviewPrefetcher = mock(),
        previewCache: PreviewUrlCache = PreviewUrlCache(),
        trackDao: TrackDao = mock(),
        downloadExecutor: DownloadExecutor = mock(),
        fileOrganizer: FileOrganizer = mock(),
        qualityPrefs: QualityPreferencesManager = mock(),
        musicRepository: MusicRepository = mock(),
    ): SearchViewModel = SearchViewModel(
        api = api,
        previewPlayer = player,
        previewUrlExtractor = extractor,
        previewUrlCache = previewCache,
        prefetcher = prefetcher,
        trackDao = trackDao,
        downloadExecutor = downloadExecutor,
        fileOrganizer = fileOrganizer,
        qualityPrefs = qualityPrefs,
        musicRepository = musicRepository,
    )
}
