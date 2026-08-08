package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
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

/** NON_FLAC source filter — the batch-upgrade worklist (spec 2026-07-22 §3). */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelSourceFilterTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val downloadedOpus =
        Track(id = 1L, title = "A", artist = "X", isDownloaded = true, fileFormat = "opus")
    private val downloadedFlac =
        Track(id = 2L, title = "B", artist = "X", isDownloaded = true, fileFormat = "flac")
    private val streamOnlyOpus =
        Track(id = 3L, title = "C", artist = "X", isDownloaded = false, fileFormat = "opus")

    @Test fun non_flac_keeps_only_downloaded_lossy_tracks() = runTest {
        val vm = buildVm(musicRepoMock(listOf(downloadedOpus, downloadedFlac, streamOnlyOpus)))
        vm.setSourceFilter(SourceFilter.NON_FLAC)

        val state = vm.uiState.first { !it.isLoading && it.sourceFilter == SourceFilter.NON_FLAC }

        assertThat(state.tracks.map { it.id }).containsExactly(1L)
    }

    // ── harness (mirrors LibraryViewModelSortTest) ────────────────────────
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
        tokenManager = mock<TokenManager> {
            on { spotifyAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
            on { youTubeAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
        },
        playlistImageHelper = mock(),
        localImportCoordinator = mock { on { state } doReturn MutableStateFlow<LocalImportState>(LocalImportState.Idle) },
        streamingPreference = mock { on { enabled } doReturn flowOf(false) },
        flacUpgradeEnqueuer = mock(),
        ytMusicApiClient = mock(),
        libraryPreferencesStore = mock {
            onBlocking { getSortOrder() } doReturn SortOrder.RECENT
            onBlocking { getSourceFilter() } doReturn SourceFilter.ALL
        },
        artistImageDao = mock { on { observeAll() } doReturn flowOf(emptyList()) },
    )
}
