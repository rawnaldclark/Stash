package com.stash.feature.search

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.stash.core.data.cache.ArtistCache
import com.stash.core.data.cache.CachedProfile
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [ArtistProfileViewModel].
 *
 * Covers the three behaviours the Phase-7/8 plan pinned as load-bearing:
 *
 *  1. Hero hydrates from nav args BEFORE the cache emits, so the first
 *     frame after navigation paints a name + avatar (< 50 ms hero target).
 *  2. Once Popular resolves, the VM kicks [PreviewPrefetcher] exactly once
 *     with the popular `videoId`s so tapping a row hits a warm cache.
 *  3. A stale-refresh failure surfaces as a Snackbar-bound user message
 *     without flipping the screen into an error state — the cached data
 *     must keep rendering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tear() { Dispatchers.resetMain() }

    private fun vmWith(
        cache: ArtistCache,
        prefetcher: PreviewPrefetcher = mock(),
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

    private fun t(id: String) = TrackSummary(
        videoId = id,
        title = "t",
        artist = "a",
        album = null,
        durationSeconds = 0.0,
        thumbnailUrl = null,
    )
}
