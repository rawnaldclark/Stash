package com.stash.feature.search

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.stash.core.data.cache.AlbumCache
import com.stash.core.data.cache.ArtistCache
import com.stash.core.data.cache.CachedProfile
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.Track
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [ArtistProfileViewModel].
 *
 * Covers the four behaviours the Phase-7/8/12 plan pinned as load-bearing:
 *
 *  1. Hero hydrates from nav args BEFORE the cache emits, so the first
 *     frame after navigation paints a name + avatar (< 50 ms hero target).
 *  2. Once Popular resolves, the VM kicks [PreviewPrefetcher] exactly once
 *     with the popular `videoId`s so tapping a row hits a warm cache.
 *  3. A stale-refresh failure surfaces as a Snackbar-bound user message
 *     without flipping the screen into an error state — the cached data
 *     must keep rendering.
 *  4. [ArtistProfileViewModel.retry] flips status back to Loading and
 *     re-subscribes to the cache after a cold-miss failure.
 *
 * The preview/download paths were extracted to [TrackActionsDelegate] in the
 * Album Discovery phase-1 migration; coverage for `downloadingIds` /
 * `previewLoadingId` / cache-hit / yt-dlp-retry / success / failure lives in
 * `TrackActionsDelegateTest` and is not duplicated here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tear() { Dispatchers.resetMain() }

    private fun vmWith(
        cache: ArtistCache,
        prefetcher: PreviewPrefetcher = mock(),
        delegate: TrackActionsDelegate = stubDelegate(),
        albumCache: AlbumCache = mock(),
        playerRepository: PlayerRepository = mock(),
        musicRepository: MusicRepository = mock(),
    ): ArtistProfileViewModel = ArtistProfileViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                "artistId" to "UC1",
                "name" to "LocalName",
                "avatarUrl" to "u",
            ),
        ),
        artistCache = cache,
        albumCache = albumCache,
        prefetcher = prefetcher,
        playerRepository = playerRepository,
        musicRepository = musicRepository,
        streamingPreference = mock(),
        delegate = delegate,
        losslessPrefetcher = mock(),
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

    @Test
    fun `initial state paints hero from nav args before cache emits`() = runTest {
        val cache = mock<ArtistCache>()
        whenever(cache.get(any())).thenReturn(flow { /* never emits */ })
        val vm = vmWith(cache)
        vm.uiState.test {
            val first = awaitItem()
            assertEquals("LocalName", first.hero.name)
            assertEquals("u", first.hero.avatarUrl)
            assertTrue(first.status is ArtistProfileStatus.Loading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prefetcher is kicked with popular videoIds`() = runTest {
        val profile = ArtistProfile(
            id = "UC1",
            name = "A",
            avatarUrl = null,
            subscribersText = null,
            popular = listOf(t("v1"), t("v2")),
            albums = emptyList(),
            singles = emptyList(),
            related = emptyList(),
        )
        val cache = mock<ArtistCache>()
        whenever(cache.get(eq("UC1"))).thenReturn(flowOf(CachedProfile.Fresh(profile)))
        val pf = mock<PreviewPrefetcher>()
        vmWith(cache, pf)
        advanceUntilIdle()
        verify(pf).prefetch(eq(listOf("v1", "v2")))
    }

    @Test
    fun `stale refresh failure surfaces userMessage without changing status to Error`() = runTest {
        val cachedProfile = ArtistProfile(
            id = "UC1",
            name = "A",
            avatarUrl = null,
            subscribersText = null,
            popular = emptyList(),
            albums = emptyList(),
            singles = emptyList(),
            related = emptyList(),
        )
        val cache = mock<ArtistCache>()
        whenever(cache.get(eq("UC1"))).thenReturn(
            flow {
                emit(CachedProfile.Stale(cachedProfile))
                emit(CachedProfile.Stale(cachedProfile, refreshFailed = true))
            },
        )
        val vm = vmWith(cache)

        vm.userMessages.test {
            advanceUntilIdle()
            assertEquals("Couldn't refresh — showing cached.", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(vm.uiState.value.status is ArtistProfileStatus.Stale)
    }

    @Test
    fun `retry flips status to Loading and re-subscribes to cache`() = runTest {
        val profile = ArtistProfile(
            id = "UC1",
            name = "A",
            avatarUrl = null,
            subscribersText = null,
            popular = emptyList(),
            albums = emptyList(),
            singles = emptyList(),
            related = emptyList(),
        )
        val cache = mock<ArtistCache>()
        // First subscription emits Fresh; second subscription (after retry)
        // also emits Fresh. We verify the VM calls get() twice — once on
        // init, once on retry() — and that status flips through Loading.
        whenever(cache.get(eq("UC1"))).thenReturn(flowOf(CachedProfile.Fresh(profile)))
        val vm = vmWith(cache)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.status is ArtistProfileStatus.Fresh)

        vm.retry()
        // The Loading flip happens synchronously inside retry() before the
        // cache coroutine gets a chance to run; observe it directly.
        assertTrue(vm.uiState.value.status is ArtistProfileStatus.Loading)

        advanceUntilIdle()
        assertTrue(vm.uiState.value.status is ArtistProfileStatus.Fresh)
        verify(cache, org.mockito.kotlin.times(2)).get(eq("UC1"))
    }

    @Test
    fun `startRadio starts an artist radio seeded with the browseId`() = runTest {
        val profile = ArtistProfile(
            id = "UC1", name = "LocalName", avatarUrl = null, subscribersText = null,
            popular = emptyList(), albums = emptyList(), singles = emptyList(), related = emptyList(),
        )
        val cache = mock<ArtistCache>()
        whenever(cache.get(eq("UC1"))).thenReturn(flowOf(CachedProfile.Fresh(profile)))
        val player = mock<PlayerRepository> {
            onBlocking { startRadio(any(), any()) } doReturn com.stash.core.model.RadioStartResult.Started
        }
        val vm = vmWith(cache = cache, playerRepository = player)
        advanceUntilIdle()

        vm.startRadio()
        advanceUntilIdle()

        val seedCaptor = argumentCaptor<com.stash.core.data.radio.RadioSeed>()
        verify(player).startRadio(seedCaptor.capture(), any())
        val seed = seedCaptor.firstValue as com.stash.core.data.radio.RadioSeed.Artist
        assertEquals("UC1", seed.ytBrowseId)
        assertEquals("LocalName", seed.name)
    }

    @Test
    fun `playArtist sets queue from popular when popular is non-empty`() = runTest {
        val profile = ArtistProfile(
            id = "UC1",
            name = "A",
            avatarUrl = null,
            subscribersText = null,
            popular = listOf(t("v1"), t("v2"), t("v3")),
            albums = emptyList(),
            singles = emptyList(),
            related = emptyList(),
        )
        val cache = mock<ArtistCache>()
        whenever(cache.get(eq("UC1"))).thenReturn(flowOf(CachedProfile.Fresh(profile)))
        val player = mock<PlayerRepository>()
        val vm = vmWith(cache = cache, playerRepository = player)
        advanceUntilIdle()

        vm.playArtist()
        advanceUntilIdle()

        val tracksCaptor = argumentCaptor<List<Track>>()
        val startCaptor = argumentCaptor<Int>()
        verify(player).setQueue(tracksCaptor.capture(), startCaptor.capture(), any())
        assertEquals(listOf("v1", "v2", "v3"), tracksCaptor.firstValue.map { it.youtubeId })
        assertEquals(0, startCaptor.firstValue)
    }

    @Test
    fun `playArtist appends album tracks deduped by videoId`() = runTest {
        val popular = listOf(t("x"))
        val profile = ArtistProfile(
            id = "UC1",
            name = "A",
            avatarUrl = null,
            subscribersText = null,
            popular = popular,
            albums = listOf(
                AlbumSummary(
                    id = "ALB1",
                    title = "Album One",
                    artist = "A",
                    thumbnailUrl = null,
                    year = null,
                ),
            ),
            singles = emptyList(),
            related = emptyList(),
        )
        val cache = mock<ArtistCache>()
        whenever(cache.get(eq("UC1"))).thenReturn(flowOf(CachedProfile.Fresh(profile)))

        val albumDetail = AlbumDetail(
            id = "ALB1",
            title = "Album One",
            artist = "A",
            artistId = "UC1",
            thumbnailUrl = null,
            year = null,
            tracks = listOf(t("b"), t("x"), t("c")),
            moreByArtist = emptyList(),
        )
        val albumCache = mock<AlbumCache>()
        whenever(albumCache.get(eq("ALB1"), any())).thenReturn(albumDetail)

        val player = mock<PlayerRepository>()
        val vm = vmWith(cache = cache, albumCache = albumCache, playerRepository = player)
        advanceUntilIdle()

        vm.playArtist()
        advanceUntilIdle()

        // First call: setQueue with the [x] popular track.
        val initialTracks = argumentCaptor<List<Track>>()
        verify(player).setQueue(initialTracks.capture(), eq(0), any())
        assertEquals(listOf("x"), initialTracks.firstValue.map { it.youtubeId })

        // Second call: addToQueue with album tracks minus the dup ("x"), so [b, c].
        val appendTracks = argumentCaptor<List<Track>>()
        verify(player).addToQueue(appendTracks.capture())
        assertEquals(listOf("b", "c"), appendTracks.firstValue.map { it.youtubeId })
    }

    @Test
    fun `playArtist emits message when all shelves empty`() = runTest {
        val profile = ArtistProfile(
            id = "UC1",
            name = "A",
            avatarUrl = null,
            subscribersText = null,
            popular = emptyList(),
            albums = emptyList(),
            singles = emptyList(),
            related = emptyList(),
        )
        val cache = mock<ArtistCache>()
        whenever(cache.get(eq("UC1"))).thenReturn(flowOf(CachedProfile.Fresh(profile)))
        val player = mock<PlayerRepository>()
        val vm = vmWith(cache = cache, playerRepository = player)
        advanceUntilIdle()

        vm.userMessages.test {
            vm.playArtist()
            advanceUntilIdle()
            assertEquals("No tracks available for this artist", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        verify(player, never()).setQueue(any(), any(), any())
        verify(player, never()).addToQueue(any<List<Track>>())
    }

    @Test
    fun `playArtist cancels prior fill job on second tap`() = runTest {
        val profile = ArtistProfile(
            id = "UC1",
            name = "A",
            avatarUrl = null,
            subscribersText = null,
            popular = listOf(t("p1")),
            albums = listOf(
                AlbumSummary(
                    id = "ALB1",
                    title = "Album One",
                    artist = "A",
                    thumbnailUrl = null,
                    year = null,
                ),
            ),
            singles = emptyList(),
            related = emptyList(),
        )
        val cache = mock<ArtistCache>()
        whenever(cache.get(eq("UC1"))).thenReturn(flowOf(CachedProfile.Fresh(profile)))

        // First albumCache.get suspends forever; the second tap must cancel
        // the first job before it can append anything from the album.
        val gate = CompletableDeferred<AlbumDetail>()
        val albumCache = mock<AlbumCache>()
        whenever(albumCache.get(eq("ALB1"), any())).doSuspendableAnswer { gate.await() }

        val player = mock<PlayerRepository>()
        val vm = vmWith(cache = cache, albumCache = albumCache, playerRepository = player)
        advanceUntilIdle()

        vm.playArtist()
        advanceUntilIdle() // first job is now suspended on albumCache.get
        vm.playArtist()
        advanceUntilIdle() // second job: setQueue, then suspends on the same mock

        // setQueue is called exactly twice (once per playArtist invocation),
        // but addToQueue must never fire because both album fetches are still
        // gated and the prior fill job was cancelled before it could append.
        verify(player, org.mockito.kotlin.times(2)).setQueue(any(), eq(0), any())
        verify(player, never()).addToQueue(any<List<Track>>())
    }

    private fun t(id: String) = TrackSummary(
        videoId = id,
        title = "t",
        artist = "a",
        album = null,
        durationSeconds = 0.0,
        thumbnailUrl = null,
    )

    /** A Qobuz-sourced track: no videoId, distinct titles for the dedup key. */
    private fun qt(title: String) = TrackSummary(
        videoId = "", title = title, artist = "MBV", album = "Loveless",
        durationSeconds = 200.0, thumbnailUrl = null,
    )

    @Test
    fun `playArtist fetches a Qobuz album by its source and queues persisted ids`() = runTest {
        val profile = ArtistProfile(
            id = "UC1", name = "MBV", avatarUrl = null, subscribersText = null,
            popular = emptyList(),
            albums = listOf(
                AlbumSummary(
                    id = "Q1", title = "Loveless", artist = "MBV",
                    thumbnailUrl = null, year = "1991",
                    source = com.stash.data.ytmusic.model.AlbumSource.QOBUZ,
                ),
            ),
            singles = emptyList(), related = emptyList(),
        )
        val cache = mock<ArtistCache>()
        whenever(cache.get(eq("UC1"))).thenReturn(flowOf(CachedProfile.Fresh(profile)))

        val albumCache = mock<AlbumCache>()
        whenever(
            albumCache.get(eq("Q1"), eq(com.stash.data.ytmusic.model.AlbumSource.QOBUZ)),
        ).thenReturn(
            AlbumDetail(
                id = "Q1", title = "Loveless", artist = "MBV", artistId = null,
                thumbnailUrl = null, year = "1991",
                tracks = listOf(qt("Only Shallow"), qt("Loomer")),
                moreByArtist = emptyList(),
            ),
        )
        val musicRepo = mock<MusicRepository>()
        whenever(musicRepo.ensureTrackPersisted(any())).thenReturn(2001L, 2002L)
        val player = mock<PlayerRepository>()

        val vm = vmWith(
            cache = cache, albumCache = albumCache,
            playerRepository = player, musicRepository = musicRepo,
        )
        advanceUntilIdle()
        vm.playArtist()
        advanceUntilIdle()

        // The Qobuz album routed by source, its tracks persisted to real PKs,
        // and queued with null youtubeId (they resolve lossless via qbdlx).
        verify(albumCache).get(eq("Q1"), eq(com.stash.data.ytmusic.model.AlbumSource.QOBUZ))
        val queued = argumentCaptor<List<Track>>()
        verify(player).setQueue(queued.capture(), eq(0), any())
        assertEquals(listOf(2001L, 2002L), queued.firstValue.map { it.id })
        assertTrue(queued.firstValue.all { it.youtubeId == null })
    }
}
