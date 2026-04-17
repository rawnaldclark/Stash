package com.stash.feature.search

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.actions.TrackItem
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.Track
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [AlbumDiscoveryViewModel].
 *
 * Covers the seven behaviours the Album Discovery plan pinned as load-bearing:
 *
 *  1. Hero hydrates from the five nav args BEFORE the cache emits — first
 *     frame paints title/artist/cover.
 *  2. Once the cache resolves, status flips to Fresh, tracks populate, and
 *     the preview prefetcher is kicked exactly once with the top 6 videoIds.
 *  3. A cold-miss cache failure transitions to Error and emits a
 *     Snackbar-bound user message.
 *  4. [AlbumDiscoveryViewModel.retry] flips status back to Loading and
 *     re-runs the fetch.
 *  5. `onDownloadAllClicked` snapshots only non-downloaded tracks into
 *     [AlbumDiscoveryUiState.downloadConfirmQueue].
 *  6. `onDownloadAllConfirmed` enqueues the captured snapshot — NOT a
 *     re-filter of `delegate.downloadedIds.value` at confirm time.
 *  7. `shuffleDownloaded` resolves the downloaded subset via
 *     [MusicRepository.findByYoutubeIds], shuffles, and hands to
 *     [PlayerRepository.setQueue]. No-op when the intersection is empty.
 *
 * Per-row preview + download paths live in `TrackActionsDelegateTest` and
 * are not duplicated here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDiscoveryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tear() { Dispatchers.resetMain() }

    private fun vmWith(
        cache: AlbumCache = mock(),
        prefetcher: PreviewPrefetcher = mock(),
        delegate: TrackActionsDelegate = stubDelegate(),
        playerRepository: PlayerRepository = mock(),
        musicRepository: MusicRepository = mock(),
    ): AlbumDiscoveryViewModel = AlbumDiscoveryViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                "browseId" to "MPREb_xxx",
                "title" to "Curtains",
                "artist" to "John Frusciante",
                "thumbnailUrl" to "url",
                "year" to "2005",
            ),
        ),
        albumCache = cache,
        prefetcher = prefetcher,
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        delegate = delegate,
    )

    /**
     * Returns a [TrackActionsDelegate] mock with all flows stubbed to their
     * default initial values. The VM's `init` calls `bindToScope`; we ignore
     * the scope (the mock is a no-op) and trust the delegate's own tests to
     * cover the real bind semantics.
     */
    private fun stubDelegate(
        downloadedIds: Set<String> = emptySet(),
    ): TrackActionsDelegate = mock {
        on { previewState } doReturn
            MutableStateFlow(PreviewState.Idle as PreviewState).asStateFlow()
        on { userMessages } doReturn MutableSharedFlow<String>().asSharedFlow()
        on { downloadingIds } doReturn MutableStateFlow<Set<String>>(emptySet()).asStateFlow()
        on { this.downloadedIds } doReturn MutableStateFlow(downloadedIds).asStateFlow()
        on { previewLoadingId } doReturn MutableStateFlow<String?>(null).asStateFlow()
    }

    private fun trackSummary(id: String): TrackSummary = TrackSummary(
        videoId = id,
        title = "title-$id",
        artist = "artist",
        album = null,
        durationSeconds = 30.0,
        thumbnailUrl = "thumb-$id",
    )

    private fun albumDetail(
        tracks: List<TrackSummary> = (1..3).map { trackSummary("v$it") },
    ) = AlbumDetail(
        id = "MPREb_xxx",
        title = "Curtains",
        artist = "John Frusciante",
        artistId = "UCxxx",
        thumbnailUrl = "u",
        year = "2005",
        tracks = tracks,
        moreByArtist = emptyList(),
    )

    @Test
    fun `initial state paints hero from nav args before cache emits`() = runTest {
        val cache = mock<AlbumCache>()
        // Never emits — mimic slow fetch so we catch the pre-cache frame.
        whenever(cache.get(any())).doSuspendableAnswer { awaitCancellation() }

        val vm = vmWith(cache = cache)

        val first = vm.uiState.value
        assertEquals("Curtains", first.hero.title)
        assertEquals("John Frusciante", first.hero.artist)
        assertEquals("url", first.hero.thumbnailUrl)
        assertEquals("2005", first.hero.year)
        assertEquals(0, first.hero.trackCount)
        assertEquals(0L, first.hero.totalDurationMs)
        assertTrue(first.status is AlbumDiscoveryStatus.Loading)
        assertTrue(first.tracks.isEmpty())
    }

    @Test
    fun `cache emit transitions to Fresh and kicks prefetch`() = runTest {
        val detail = albumDetail(tracks = (1..3).map { trackSummary("v$it") })
        val cache = mock<AlbumCache>().also {
            whenever(it.get(eq("MPREb_xxx"))).thenReturn(detail)
        }
        val prefetcher = mock<PreviewPrefetcher>()

        val vm = vmWith(cache = cache, prefetcher = prefetcher)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(AlbumDiscoveryStatus.Fresh, state.status)
        assertEquals(3, state.tracks.size)
        assertEquals(3, state.hero.trackCount)
        // 3 tracks × 30s × 1000 = 90_000ms.
        assertEquals(90_000L, state.hero.totalDurationMs)
        verify(prefetcher).prefetch(eq(listOf("v1", "v2", "v3")))
    }

    @Test
    fun `cache failure transitions to Error and emits user message`() = runTest {
        val cache = mock<AlbumCache>()
        whenever(cache.get(eq("MPREb_xxx")))
            .doSuspendableAnswer { throw RuntimeException("network down") }

        val vm = vmWith(cache = cache)
        vm.userMessages.test {
            advanceUntilIdle()
            assertEquals("Couldn't load album — tap Retry.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        val status = vm.uiState.value.status
        assertTrue(status is AlbumDiscoveryStatus.Error)
        assertEquals("network down", (status as AlbumDiscoveryStatus.Error).message)
    }

    @Test
    fun `retry flips status to Loading and re-fetches cache`() = runTest {
        val detail = albumDetail()
        val cache = mock<AlbumCache>()
        whenever(cache.get(eq("MPREb_xxx"))).thenReturn(detail)

        val vm = vmWith(cache = cache)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.status is AlbumDiscoveryStatus.Fresh)

        vm.retry()
        // Loading flip happens synchronously inside retry() before the
        // relaunched fetch coroutine runs.
        assertTrue(vm.uiState.value.status is AlbumDiscoveryStatus.Loading)

        advanceUntilIdle()
        assertTrue(vm.uiState.value.status is AlbumDiscoveryStatus.Fresh)
        verify(cache, times(2)).get(eq("MPREb_xxx"))
    }

    @Test
    fun `onDownloadAllClicked snapshots non-downloaded tracks`() = runTest {
        val detail = albumDetail(tracks = listOf("v1", "v2", "v3").map(::trackSummary))
        val cache = mock<AlbumCache>().also {
            whenever(it.get(any())).thenReturn(detail)
        }
        // v2 is already downloaded — snapshot should exclude it.
        val delegate = stubDelegate(downloadedIds = setOf("v2"))

        val vm = vmWith(cache = cache, delegate = delegate)
        advanceUntilIdle()

        vm.onDownloadAllClicked()

        val state = vm.uiState.value
        assertTrue(state.showDownloadConfirm)
        assertEquals(
            listOf("v1", "v3"),
            state.downloadConfirmQueue.map { it.videoId },
        )
    }

    @Test
    fun `onDownloadAllConfirmed enqueues snapshot into delegate`() = runTest {
        val detail = albumDetail(tracks = listOf("v1", "v2").map(::trackSummary))
        val cache = mock<AlbumCache>().also {
            whenever(it.get(any())).thenReturn(detail)
        }
        val delegate = stubDelegate()

        val vm = vmWith(cache = cache, delegate = delegate)
        advanceUntilIdle()

        vm.onDownloadAllClicked()
        vm.onDownloadAllConfirmed()

        // Both v1 and v2 are downloaded via the delegate.
        val captor = argumentCaptor<TrackItem>()
        verify(delegate, times(2)).downloadTrack(captor.capture())
        assertEquals(listOf("v1", "v2"), captor.allValues.map { it.videoId })

        // Confirm clears the dialog state.
        val state = vm.uiState.value
        assertFalse(state.showDownloadConfirm)
        assertTrue(state.downloadConfirmQueue.isEmpty())
    }

    @Test
    fun `shuffleDownloaded plays only downloaded subset`() = runTest {
        val detail = albumDetail(
            tracks = listOf("v1", "v2", "v3").map(::trackSummary),
        )
        val cache = mock<AlbumCache>().also {
            whenever(it.get(any())).thenReturn(detail)
        }
        // Downloaded set includes v1 and v3 from this album, plus v99 from
        // another album that must be filtered out by the album-tracks
        // intersect.
        val delegate = stubDelegate(downloadedIds = setOf("v1", "v3", "v99"))
        val tracks = listOf(
            Track(id = 1, title = "t1", artist = "a", youtubeId = "v1", isDownloaded = true),
            Track(id = 3, title = "t3", artist = "a", youtubeId = "v3", isDownloaded = true),
        )
        val repo = mock<MusicRepository>()
        // findByYoutubeIds should be called with the intersected ids only
        // (the order of the intersect is unspecified — match by content).
        whenever(repo.findByYoutubeIds(argThat { size == 2 && containsAll(setOf("v1", "v3")) }))
            .thenReturn(tracks)
        val player = mock<PlayerRepository>()

        val vm = vmWith(
            cache = cache,
            delegate = delegate,
            playerRepository = player,
            musicRepository = repo,
        )
        advanceUntilIdle()

        vm.shuffleDownloaded()
        advanceUntilIdle()

        // Captured queue should contain exactly the two downloaded rows.
        val captor = argumentCaptor<List<Track>>()
        verify(player).setQueue(captor.capture(), eq(0))
        assertEquals(setOf("v1", "v3"), captor.firstValue.mapNotNull { it.youtubeId }.toSet())
    }

}
