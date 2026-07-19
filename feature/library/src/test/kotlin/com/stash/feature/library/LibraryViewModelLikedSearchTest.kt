package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock

/**
 * Issue #293: the header search query must narrow the Liked tab too — it
 * was the one Library list the filter never reached.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelLikedSearchTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val likedPlaylist = Playlist(
        id = 42L,
        name = "Liked Songs",
        source = MusicSource.SPOTIFY,
        type = PlaylistType.LIKED_SONGS,
        trackCount = 3,
    )
    private val likedSongs = listOf(
        Track(id = 1L, title = "Terpukau", artist = "Astrid"),
        Track(id = 2L, title = "Creep", artist = "Radiohead"),
        Track(id = 3L, title = "Karma Police", artist = "Radiohead"),
    )

    @Test fun search_query_narrows_the_liked_list() = runTest {
        val vm = buildVm()

        // Unfiltered first, so we know the flow is live before querying.
        assertThat(vm.likedTracks.first { it.isNotEmpty() }).hasSize(3)

        vm.setSearchQuery("terpukau")
        val filtered = vm.likedTracks.first { it.size < 3 }
        assertThat(filtered.map { it.title }).containsExactly("Terpukau")

        vm.setSearchQuery("radiohead")
        val byArtist = vm.likedTracks.first { it.size == 2 }
        assertThat(byArtist.map { it.id }).containsExactly(2L, 3L)
    }

    @Test fun clearing_the_query_restores_the_full_liked_list() = runTest {
        val vm = buildVm()
        vm.setSearchQuery("terpukau")
        assertThat(vm.likedTracks.first { it.size == 1 }).hasSize(1)

        vm.setSearchQuery("")
        assertThat(vm.likedTracks.first { it.size == 3 }).hasSize(3)
    }

    // ── harness (mirrors LibraryViewModelSortTest) ────────────────────────
    private fun buildVm(): LibraryViewModel {
        val musicRepository: MusicRepository = mock {
            on { getAllTracks() } doReturn flowOf(emptyList())
            on { getAllPlaylists() } doReturn flowOf(emptyList())
            on { getAllArtists() } doReturn flowOf(emptyList())
            on { getAllAlbums() } doReturn flowOf(emptyList())
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
            on { getRecentlyAdded(any()) } doReturn flowOf(emptyList())
            on { getPlaylistsByType(eq(PlaylistType.STASH_LIKED)) } doReturn flowOf(emptyList())
            on { getPlaylistsByType(eq(PlaylistType.LIKED_SONGS)) } doReturn flowOf(listOf(likedPlaylist))
            on { getTracksByPlaylist(eq(42L)) } doReturn flowOf(likedSongs)
        }
        return LibraryViewModel(
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
}
