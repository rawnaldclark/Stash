package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
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
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * The Library's single-song Remove download with Undo, wired through the ViewModel: the song
 * leaves Songs (and the song count) the moment it's staged, nothing is deleted until the Undo is
 * gone, Undo really keeps the file, and a commit reaches the repository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelRemoveDownloadUndoTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val nude = Track(id = 1L, title = "Nude", artist = "Radiohead", isDownloaded = true)
    private val reckoner = Track(id = 2L, title = "Reckoner", artist = "Radiohead", isDownloaded = true)

    @Test fun staging_hides_the_song_at_once_and_deletes_nothing() = runTest {
        val repo = musicRepoMock(listOf(nude, reckoner))
        val vm = buildVm(repo)
        vm.uiState.first { !it.isLoading && it.tracks.size == 2 }

        vm.removeDownloadWithUndo(nude)

        val state = vm.uiState.first { !it.isLoading && it.tracks.size == 1 }
        assertThat(state.tracks.map { it.id }).containsExactly(2L)
        assertThat(state.librarySongCount).isEqualTo(1)
        assertThat(vm.pendingDownloadRemoval.value).isEqualTo(nude)
        verify(repo, never()).removeDownload(any())
    }

    @Test fun undo_brings_the_song_back_and_keeps_its_file() = runTest {
        val repo = musicRepoMock(listOf(nude, reckoner))
        val vm = buildVm(repo)
        vm.uiState.first { !it.isLoading && it.tracks.size == 2 }

        vm.removeDownloadWithUndo(nude)
        vm.uiState.first { !it.isLoading && it.tracks.size == 1 }
        vm.undoRemoveDownload(1L)
        vm.commitPendingDownloadRemoval() // leaving the screen afterwards finds nothing staged

        val state = vm.uiState.first { !it.isLoading && it.tracks.size == 2 }
        assertThat(state.tracks.map { it.id }).containsExactly(1L, 2L)
        assertThat(vm.pendingDownloadRemoval.value).isNull()
        verify(repo, never()).removeDownload(any())
    }

    @Test fun commit_removes_the_download() = runTest {
        val repo = musicRepoMock(listOf(nude, reckoner))
        val vm = buildVm(repo)

        vm.removeDownloadWithUndo(nude)
        vm.commitPendingDownloadRemoval(1L)

        // The delete runs on its own IO scope (it must outlive the ViewModel): wait for it.
        verify(repo, Mockito.timeout(5_000L)).removeDownload(1L)
        assertThat(vm.pendingDownloadRemoval.value).isNull()
    }

    // ── harness (mirrors LibraryViewModelSourceFilterTest) ────────────────
    private fun musicRepoMock(allTracks: List<Track>): MusicRepository = mock {
        on { getAllTracks() } doReturn flowOf(allTracks)
        on { getLibraryTracksIncludingOnline() } doReturn flowOf(allTracks)
        on { getAllPlaylists() } doReturn flowOf(emptyList())
        on { getAllArtists() } doReturn flowOf(emptyList())
        on { getLibraryAlbums() } doReturn flowOf(emptyList())
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
        libraryDeepLinkController = com.stash.core.data.navigation.LibraryDeepLinkController(),
        artistImageDao = mock { on { observeAll() } doReturn flowOf(emptyList()) },
        sharedMixRepository = org.mockito.kotlin.mock { on { observeActiveFollowedIds() }.thenReturn(kotlinx.coroutines.flow.flowOf(emptyList())) },
    )
}
