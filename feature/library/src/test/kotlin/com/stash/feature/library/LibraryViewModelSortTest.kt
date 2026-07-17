package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.files.LocalImportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** Task 1: DURATION sort for the Songs list + librarySongCount for the Shuffle hero. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelSortTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val tracks = listOf(
        Track(id = 1L, title = "Short", artist = "X", durationMs = 120_000),
        Track(id = 2L, title = "Long", artist = "X", durationMs = 400_000),
        Track(id = 3L, title = "Mid", artist = "X", durationMs = 240_000),
    )

    @Test fun duration_sort_orders_tracks_longest_first() = runTest {
        val vm = buildVm(musicRepoMock(tracks))
        vm.setSortOrder(SortOrder.DURATION)

        val state = vm.uiState.first { !it.isLoading && it.sortOrder == SortOrder.DURATION }

        assertThat(state.tracks.map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
    }

    @Test fun librarySongCount_is_the_unfiltered_total_regardless_of_source_filter() = runTest {
        val vm = buildVm(musicRepoMock(tracks))
        vm.setSourceFilter(SourceFilter.FLAC)   // narrows the visible list, NOT the hero count

        val state = vm.uiState.first { !it.isLoading && it.sourceFilter == SourceFilter.FLAC }

        assertThat(state.librarySongCount).isEqualTo(3)
    }

    // ── harness (mirrors LibraryViewModelMixTest) ─────────────────────────
    private fun musicRepoMock(allTracks: List<Track>): MusicRepository = mock {
        on { getAllTracks() } doReturn flowOf(allTracks)
        on { getAllPlaylists() } doReturn flowOf(emptyList())
        on { getAllArtists() } doReturn flowOf(emptyList())
        on { getAllAlbums() } doReturn flowOf(emptyList())
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        on { getRecentlyAdded(any()) } doReturn flowOf(emptyList())
    }

    private fun buildVm(musicRepository: MusicRepository): LibraryViewModel = LibraryViewModel(
        musicRepository = musicRepository,
        playerRepository = mock { on { playerState } doReturn MutableStateFlow(PlayerState()) },
        tokenManager = mock {
            on { spotifyAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
            on { youTubeAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
        },
        playlistImageHelper = mock(),
        localImportCoordinator = mock { on { state } doReturn MutableStateFlow<LocalImportState>(LocalImportState.Idle) },
        streamingPreference = mock(),
    )
}
