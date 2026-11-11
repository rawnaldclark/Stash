package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlaybackSource
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
import org.mockito.kotlin.verify

/**
 * NON_FLAC source filter — the batch-upgrade worklist (spec 2026-07-22 §3) — plus the
 * Downloaded / Online filters and the Songs-tap queue following the Stream-only switch.
 */
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

    @Test fun downloaded_keeps_only_tracks_on_the_phone() = runTest {
        val vm = buildVm(musicRepoMock(listOf(downloadedOpus, downloadedFlac, streamOnlyOpus)), streaming = true)
        vm.setSourceFilter(SourceFilter.DOWNLOADED)

        val state = vm.uiState.first { !it.isLoading && it.sourceFilter == SourceFilter.DOWNLOADED }

        assertThat(state.tracks.map { it.id }).containsExactly(1L, 2L)
    }

    @Test fun online_keeps_only_tracks_not_downloaded() = runTest {
        val vm = buildVm(musicRepoMock(listOf(downloadedOpus, downloadedFlac, streamOnlyOpus)), streaming = true)
        vm.setSourceFilter(SourceFilter.ONLINE)

        val state = vm.uiState.first { !it.isLoading && it.sourceFilter == SourceFilter.ONLINE }

        assertThat(state.tracks.map { it.id }).containsExactly(3L)
    }

    @Test fun saved_online_filter_reads_as_all_in_download_mode() = runTest {
        // A Stream-only user picked Online, then switched Download mode on: every row is
        // downloaded and the chip is hidden, so the saved filter must not empty the list.
        val vm = buildVm(musicRepoMock(listOf(downloadedOpus, downloadedFlac)), streaming = false)
        vm.setSourceFilter(SourceFilter.ONLINE)

        val state = vm.uiState.first { !it.isLoading && it.tracks.isNotEmpty() }

        assertThat(state.sourceFilter).isEqualTo(SourceFilter.ALL)
        assertThat(state.tracks.map { it.id }).containsExactly(1L, 2L)
    }

    @Test fun stream_only_tap_on_downloaded_row_queues_the_whole_list() = runTest {
        val playerRepo = mock<PlayerRepository> { on { playerState } doReturn MutableStateFlow(PlayerState()) }
        val list = listOf(downloadedOpus, streamOnlyOpus, downloadedFlac)
        val vm = buildVm(musicRepoMock(list), streaming = true, playerRepository = playerRepo)

        vm.playTrack(downloadedFlac, list)
        dispatcher.scheduler.advanceUntilIdle()

        verify(playerRepo).setQueue(list, 2, source = PlaybackSource.Library)
    }

    @Test fun download_mode_tap_on_downloaded_row_queues_only_downloaded_rows() = runTest {
        val playerRepo = mock<PlayerRepository> { on { playerState } doReturn MutableStateFlow(PlayerState()) }
        val list = listOf(downloadedOpus, streamOnlyOpus, downloadedFlac)
        val vm = buildVm(musicRepoMock(list), streaming = false, playerRepository = playerRepo)

        vm.playTrack(downloadedFlac, list)
        dispatcher.scheduler.advanceUntilIdle()

        verify(playerRepo).setQueue(listOf(downloadedOpus, downloadedFlac), 1, source = PlaybackSource.Library)
    }

    // ── harness (mirrors LibraryViewModelSortTest) ────────────────────────
    private fun musicRepoMock(allTracks: List<Track>): MusicRepository = mock {
        on { getAllTracks() } doReturn flowOf(allTracks)
        on { getLibraryTracksIncludingOnline() } doReturn flowOf(allTracks)
        on { getAllPlaylists() } doReturn flowOf(emptyList())
        on { getAllArtists() } doReturn flowOf(emptyList())
        on { getLibraryAlbums() } doReturn flowOf(emptyList())
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        on { getRecentlyAdded(any()) } doReturn flowOf(emptyList())
    }

    private fun buildVm(
        musicRepository: MusicRepository,
        streaming: Boolean = false,
        playerRepository: PlayerRepository = mock { on { playerState } doReturn MutableStateFlow(PlayerState()) },
    ): LibraryViewModel = LibraryViewModel(
        musicRepository = musicRepository,
        playerRepository = playerRepository,
        tokenManager = mock<TokenManager> {
            on { youTubeAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
        },
        spotifyAccounts = mock { on { spotifyAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected) },
        playlistImageHelper = mock(),
        localImportCoordinator = mock { on { state } doReturn MutableStateFlow<LocalImportState>(LocalImportState.Idle) },
        streamingPreference = mock { on { enabled } doReturn flowOf(streaming) },
        flacUpgradeEnqueuer = mock(),
        ytMusicApiClient = mock(),
        libraryPreferencesStore = mock {
            onBlocking { getSortOrder() } doReturn SortOrder.RECENT
            onBlocking { getSourceFilter() } doReturn SourceFilter.ALL
        },
        libraryDeepLinkController = com.stash.core.data.navigation.LibraryDeepLinkController(),
        artistImageDao = mock { on { observeAll() } doReturn flowOf(emptyList()) },
        sharedMixRepository = org.mockito.kotlin.mock { on { observeActiveFollowedIds() }.thenReturn(kotlinx.coroutines.flow.flowOf(emptyList())) },
    )
}
