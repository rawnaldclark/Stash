package com.stash.feature.search

import app.cash.turbine.test
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import com.stash.core.media.StreamRoutingResult
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.TrackItem
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.SearchAllResults
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * Unit tests for [SearchViewModel] — pin the search-query pipeline.
 *
 * The preview/download paths were extracted to
 * [com.stash.core.media.actions.TrackActionsDelegate] in the Album Discovery
 * phase-1 migration; coverage for cache hits, extractor misses, yt-dlp retry,
 * download success/failure, refresh, etc. lives in `TrackActionsDelegateTest`.
 * This file only exercises the parts still owned by the VM:
 *
 *  1. Blank / below-min-length queries emit [SearchStatus.Idle] without a
 *     [YTMusicApiClient.searchAll] call.
 *  2. A typed-past-the-minimum query triggers `searchAll` after the 300 ms
 *     debounce window.
 *  3. A new keystroke mid-search cancels the previous in-flight call
 *     ([flatMapLatest]).
 *  4. A thrown `searchAll` surfaces a snackbar message on
 *     [SearchViewModel.userMessages].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `blank or short query emits Idle without calling searchAll`() = runTest {
        val api = mock<YTMusicApiClient>()
        val vm = newVm(api = api)

        vm.onQueryChanged("")
        advanceUntilIdle()
        assertEquals(SearchStatus.Idle, vm.uiState.value.status)

        vm.onQueryChanged("a") // below MIN_QUERY_LENGTH (2)
        advanceUntilIdle()
        assertEquals(SearchStatus.Idle, vm.uiState.value.status)

        verify(api, never()).searchAll(any())
    }

    @Test
    fun `onQueryChanged triggers searchAll after debounce`() = runTest {
        val api = mock<YTMusicApiClient>()
        whenever(api.searchAll(eq("abc"))).doReturn(SearchAllResults(emptyList()))
        val vm = newVm(api = api)

        vm.onQueryChanged("abc")
        // Before the debounce, no call should have gone out yet.
        advanceTimeBy(100)
        verify(api, never()).searchAll(any())

        advanceUntilIdle()
        verify(api, atLeastOnce()).searchAll(eq("abc"))
    }

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

    // ------------------------------------------------------------------
    // Task 20: onResultTap — streaming on vs off
    // ------------------------------------------------------------------

    @Test
    fun `onResultTap streaming off calls preview not playFromStream`() = runTest {
        val delegate = stubDelegate()
        val playerRepository = mock<PlayerRepository>()
        val streamingPreference = mock<StreamingPreference> {
            onBlocking { current() } doReturn false
        }
        val vm = newVm(
            delegate = delegate,
            playerRepository = playerRepository,
            streamingPreference = streamingPreference,
        )

        vm.onResultTap(sampleTrack())
        advanceUntilIdle()

        verify(delegate).previewTrack(any())
        verifyBlocking(playerRepository, never()) { playFromStream(any()) }
    }

    @Test
    fun `onResultTap streaming on calls playFromStream not preview`() = runTest {
        val delegate = stubDelegate()
        val playerRepository = mock<PlayerRepository> {
            onBlocking { playFromStream(any()) } doReturn StreamRoutingResult.Item(
                androidx.media3.common.MediaItem.fromUri("https://example/song"),
            )
        }
        val streamingPreference = mock<StreamingPreference> {
            onBlocking { current() } doReturn true
        }
        val vm = newVm(
            delegate = delegate,
            playerRepository = playerRepository,
            streamingPreference = streamingPreference,
        )

        vm.onResultTap(sampleTrack())
        advanceUntilIdle()

        verifyBlocking(playerRepository) { playFromStream(any()) }
        verify(delegate, never()).previewTrack(any())
    }

    @Test
    fun `onResultTap streaming on but no connectivity emits snackbar`() = runTest {
        val playerRepository = mock<PlayerRepository> {
            onBlocking { playFromStream(any()) } doReturn StreamRoutingResult.NoConnectivity
        }
        val streamingPreference = mock<StreamingPreference> {
            onBlocking { current() } doReturn true
        }
        val vm = newVm(
            playerRepository = playerRepository,
            streamingPreference = streamingPreference,
        )

        vm.userMessages.test {
            vm.onResultTap(sampleTrack())
            val msg = awaitItem()
            assertTrue(msg.contains("offline", ignoreCase = true))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onResultTap streaming on but cellular refused emits snackbar`() = runTest {
        val playerRepository = mock<PlayerRepository> {
            onBlocking { playFromStream(any()) } doReturn StreamRoutingResult.CellularRefused
        }
        val streamingPreference = mock<StreamingPreference> {
            onBlocking { current() } doReturn true
        }
        val vm = newVm(
            playerRepository = playerRepository,
            streamingPreference = streamingPreference,
        )

        vm.userMessages.test {
            vm.onResultTap(sampleTrack())
            val msg = awaitItem()
            assertTrue(msg.contains("cellular", ignoreCase = true))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onResultTap streaming on but not available emits stream snackbar`() = runTest {
        val playerRepository = mock<PlayerRepository> {
            onBlocking { playFromStream(any()) } doReturn StreamRoutingResult.NotAvailable
        }
        val streamingPreference = mock<StreamingPreference> {
            onBlocking { current() } doReturn true
        }
        val vm = newVm(
            playerRepository = playerRepository,
            streamingPreference = streamingPreference,
        )

        vm.userMessages.test {
            vm.onResultTap(sampleTrack())
            val msg = awaitItem()
            assertTrue(msg.contains("couldn't stream", ignoreCase = true))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tappedTrackId emits on tap and clears after playFromStream returns`() = runTest {
        val gate = CompletableDeferred<StreamRoutingResult>()
        val playerRepo = mock<PlayerRepository> {
            onBlocking { playFromStream(any()) } doSuspendableAnswer { gate.await() }
        }
        val streamingPref = mock<StreamingPreference> {
            onBlocking { current() } doReturn true
        }
        val vm = newVm(
            playerRepository = playerRepo,
            streamingPreference = streamingPref,
        )

        val emissions = mutableListOf<Long?>()
        val collectJob = backgroundScope.launch {
            vm.tappedTrackId.collect { emissions.add(it) }
        }

        val item = sampleTrack()
        vm.onResultTap(item)
        runCurrent()

        val expectedId = item.videoId.hashCode().toLong()
        assertTrue(expectedId in emissions)

        gate.complete(StreamRoutingResult.Item(mock()))
        runCurrent()
        assertEquals(null, emissions.last())

        collectJob.cancel()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun newVm(
        api: YTMusicApiClient = mock(),
        prefetcher: PreviewPrefetcher = mock(),
        delegate: TrackActionsDelegate = stubDelegate(),
        playerRepository: PlayerRepository = mock(),
        streamingPreference: StreamingPreference = mock {
            onBlocking { current() } doReturn false
        },
        recentSearchesStore: RecentSearchesStore = mock {
            on { recent } doReturn kotlinx.coroutines.flow.flowOf(emptyList())
        },
    ): SearchViewModel = SearchViewModel(
        api = api,
        prefetcher = prefetcher,
        delegate = delegate,
        losslessPrefetcher = mock(),
        playerRepository = playerRepository,
        streamingPreference = streamingPreference,
        recentSearchesStore = recentSearchesStore,
    )

    private fun sampleTrack(): TrackItem = TrackItem(
        videoId = "vid123",
        title = "Hit Song",
        artist = "Hit Artist",
        durationSeconds = 200.0,
        thumbnailUrl = null,
        album = null,
    )

    /**
     * Returns a [TrackActionsDelegate] mock with all flows stubbed to their
     * default initial values. The VM's `init` calls `bindToScope`; we ignore
     * the scope (the mock is a no-op) and trust the delegate's own tests to
     * cover the real bind semantics.
     */
    private fun stubDelegate(): TrackActionsDelegate = mock {
        on { previewState } doReturn
            MutableStateFlow(PreviewState.Idle as PreviewState).asStateFlow()
        on { userMessages } doReturn MutableSharedFlow<String>().asSharedFlow()
        on { downloadingIds } doReturn MutableStateFlow<Set<String>>(emptySet()).asStateFlow()
        on { downloadedIds } doReturn MutableStateFlow<Set<String>>(emptySet()).asStateFlow()
        on { previewLoadingId } doReturn MutableStateFlow<String?>(null).asStateFlow()
        on { userPlaylists } doReturn kotlinx.coroutines.flow.flowOf(emptyList())
    }

    // ------------------------------------------------------------------
    // Recent searches: record on commit, NOT on keystroke
    // ------------------------------------------------------------------

    @Test
    fun `onQueryChanged does not record a recent search`() = runTest {
        val store = mock<RecentSearchesStore> {
            on { recent } doReturn kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val vm = newVm(recentSearchesStore = store)

        vm.onQueryChanged("beatles")
        advanceUntilIdle()

        verifyBlocking(store, never()) { record(any()) }
    }

    @Test
    fun `onSearchCommitted records the trimmed query`() = runTest {
        val store = mock<RecentSearchesStore> {
            on { recent } doReturn kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val vm = newVm(recentSearchesStore = store)

        vm.onQueryChanged("  beatles  ")
        vm.onSearchCommitted()
        advanceUntilIdle()

        verifyBlocking(store) {
            record(RecentSearch(RecentSearch.Type.QUERY, "beatles"))
        }
    }

    @Test
    fun `opening an artist profile records an artist entry with avatar`() = runTest {
        // The reported bug: search an artist, open the profile, back out —
        // nothing was saved, because artist taps were pure navigation.
        val store = mock<RecentSearchesStore> {
            on { recent } doReturn kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val vm = newVm(recentSearchesStore = store)

        vm.onArtistOpened(id = "UC123", name = "Lil Wayne", avatarUrl = "https://img/w.jpg")
        advanceUntilIdle()

        verifyBlocking(store) {
            record(
                RecentSearch(
                    type = RecentSearch.Type.ARTIST,
                    text = "Lil Wayne",
                    thumbnailUrl = "https://img/w.jpg",
                    artistId = "UC123",
                ),
            )
        }
    }

    @Test
    fun `tapping a track result records a track entry with thumbnail`() = runTest {
        val store = mock<RecentSearchesStore> {
            on { recent } doReturn kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val vm = newVm(recentSearchesStore = store)

        vm.onResultTap(sampleTrack().copy(thumbnailUrl = "https://img/t.jpg"))
        advanceUntilIdle()

        verifyBlocking(store) {
            record(
                RecentSearch(
                    type = RecentSearch.Type.TRACK,
                    text = "Hit Song",
                    subtitle = "Hit Artist",
                    thumbnailUrl = "https://img/t.jpg",
                ),
            )
        }
    }

    @Test
    fun `onSearchCommitted with blank query records nothing`() = runTest {
        val store = mock<RecentSearchesStore> {
            on { recent } doReturn kotlinx.coroutines.flow.flowOf(emptyList())
        }
        val vm = newVm(recentSearchesStore = store)

        vm.onQueryChanged("   ")
        vm.onSearchCommitted()
        advanceUntilIdle()

        verifyBlocking(store, never()) { record(any()) }
    }

}
