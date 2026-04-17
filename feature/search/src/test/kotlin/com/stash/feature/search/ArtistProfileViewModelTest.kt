package com.stash.feature.search

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.stash.core.data.cache.ArtistCache
import com.stash.core.data.cache.CachedProfile
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.preview.PreviewPlayer
import com.stash.core.media.preview.PreviewState
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.core.model.QualityTier
import com.stash.data.download.preview.PreviewUrlCache
import com.stash.data.download.preview.PreviewUrlExtractor
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Unit tests for [ArtistProfileViewModel].
 *
 * Covers the five behaviours the Phase-7/8/12 plan pinned as load-bearing:
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
 *  5. [ArtistProfileViewModel.downloadTrack] adds the videoId to
 *     `downloadingIds` optimistically before the executor returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tear() { Dispatchers.resetMain() }

    private fun vmWith(
        cache: ArtistCache,
        prefetcher: PreviewPrefetcher = mock(),
        previewPlayer: PreviewPlayer = mock<PreviewPlayer>().also {
            whenever(it.previewState).thenReturn(MutableStateFlow(PreviewState.Idle))
            whenever(it.playerErrors).thenReturn(MutableSharedFlow())
        },
        previewUrlExtractor: PreviewUrlExtractor = mock(),
        previewUrlCache: PreviewUrlCache = mock(),
        downloadExecutor: DownloadExecutor = mock(),
        trackDao: TrackDao = mock(),
        fileOrganizer: FileOrganizer = mock(),
        qualityPrefs: QualityPreferencesManager = mock<QualityPreferencesManager>().also {
            whenever(it.qualityTier).thenReturn(flowOf(QualityTier.BEST))
        },
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
        prefetcher = prefetcher,
        previewPlayer = previewPlayer,
        previewUrlExtractor = previewUrlExtractor,
        previewUrlCache = previewUrlCache,
        downloadExecutor = downloadExecutor,
        trackDao = trackDao,
        fileOrganizer = fileOrganizer,
        qualityPrefs = qualityPrefs,
        musicRepository = musicRepository,
    )

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
    fun `downloadTrack adds id to downloadingIds optimistically`() = runTest {
        val cache = mock<ArtistCache>()
        whenever(cache.get(any())).thenReturn(flow { /* never emits */ })
        val executor = mock<DownloadExecutor>()
        // Hang the download indefinitely so the optimistic state update is
        // observable before any success/failure bookkeeping fires.
        val hang = CompletableDeferred<DownloadResult>()
        whenever(
            executor.download(any(), any(), any(), any(), any()),
        ).doSuspendableAnswer { hang.await() }
        val fileOrganizer = mock<FileOrganizer>().also {
            whenever(it.getTempDir()).thenReturn(File(System.getProperty("java.io.tmpdir")!!))
        }

        val vm = vmWith(
            cache = cache,
            downloadExecutor = executor,
            fileOrganizer = fileOrganizer,
        )
        val item = SearchResultItem(
            videoId = "v1",
            title = "t",
            artist = "a",
            durationSeconds = 0.0,
            thumbnailUrl = null,
        )
        vm.downloadTrack(item)
        advanceUntilIdle()

        assertTrue("v1" in vm.uiState.value.downloadingIds)
        hang.cancel()
    }

    private fun t(id: String) = TrackSummary(
        videoId = id,
        title = "t",
        artist = "a",
        album = null,
        durationSeconds = 0.0,
        thumbnailUrl = null,
    )
}
