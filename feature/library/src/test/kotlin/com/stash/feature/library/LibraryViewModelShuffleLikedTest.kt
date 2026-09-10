package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaybackSource
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking

/**
 * Issue #402: the Library header's shuffle icon shuffled the whole library
 * even while the Liked tab was open. [LibraryViewModel.shuffleLiked] is the
 * Liked-scoped variant the header routes to on that tab — it must queue the
 * current liked list (shuffled, from the top), honoring the same
 * offline-playability narrowing as [LibraryViewModel.playLiked].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelShuffleLikedTest {

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

    private fun track(id: Long, downloaded: Boolean = true) = Track(
        id = id,
        title = "Song $id",
        artist = "Artist",
        filePath = if (downloaded) "/music/$id.opus" else null,
    )

    @Test fun shuffle_liked_queues_the_liked_list_from_the_top() = runTest {
        val playerRepository = playerRepoMock()
        val liked = listOf(track(1), track(2), track(3))
        val vm = buildVm(playerRepository, liked)
        assertThat(vm.likedTracks.first { it.isNotEmpty() }).hasSize(3)

        vm.shuffleLiked()
        advanceUntilIdle()

        val tracksCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepository) {
            setQueue(tracksCaptor.capture(), eq(0), eq(PlaybackSource.Liked("ALL")))
        }
        assertThat(tracksCaptor.firstValue.map { it.id }).containsExactly(1L, 2L, 3L)
    }

    @Test fun shuffle_liked_offline_filters_out_undownloaded_tracks() = runTest {
        val playerRepository = playerRepoMock()
        val liked = listOf(track(1), track(2, downloaded = false), track(3))
        val vm = buildVm(playerRepository, liked)
        assertThat(vm.likedTracks.first { it.isNotEmpty() }).hasSize(3)

        vm.shuffleLiked()
        advanceUntilIdle()

        val tracksCaptor = argumentCaptor<List<Track>>()
        verifyBlocking(playerRepository) {
            setQueue(tracksCaptor.capture(), eq(0), eq(PlaybackSource.Liked("ALL")))
        }
        assertThat(tracksCaptor.firstValue.map { it.id }).containsExactly(1L, 3L)
    }

    @Test fun shuffle_liked_with_nothing_playable_leaves_the_queue_alone() = runTest {
        val playerRepository = playerRepoMock()
        val liked = listOf(track(1, downloaded = false))
        val vm = buildVm(playerRepository, liked)
        assertThat(vm.likedTracks.first { it.isNotEmpty() }).hasSize(1)

        vm.shuffleLiked()
        advanceUntilIdle()

        verifyBlocking(playerRepository, never()) { setQueue(any(), any(), any()) }
    }

    // ── harness (mirrors LibraryViewModelLikedSearchTest) ────────────────

    private fun playerRepoMock(): PlayerRepository = mock {
        on { playerState } doReturn MutableStateFlow(PlayerState())
    }

    private fun buildVm(playerRepository: PlayerRepository, liked: List<Track>): LibraryViewModel {
        val musicRepository: MusicRepository = mock {
            on { getAllTracks() } doReturn flowOf(emptyList())
            on { getAllPlaylists() } doReturn flowOf(emptyList())
            on { getAllArtists() } doReturn flowOf(emptyList())
            on { getAllAlbums() } doReturn flowOf(emptyList())
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
            on { getRecentlyAdded(any()) } doReturn flowOf(emptyList())
            on { getPlaylistsByType(eq(PlaylistType.STASH_LIKED)) } doReturn flowOf(emptyList())
            on { getPlaylistsByType(eq(PlaylistType.LIKED_SONGS)) } doReturn flowOf(listOf(likedPlaylist))
            on { getTracksByPlaylist(eq(42L)) } doReturn flowOf(liked)
        }
        return LibraryViewModel(
            musicRepository = musicRepository,
            playerRepository = playerRepository,
            tokenManager = mock {
                on { spotifyAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
                on { youTubeAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
            },
            playlistImageHelper = mock(),
            localImportCoordinator = mock { on { state } doReturn MutableStateFlow<LocalImportState>(LocalImportState.Idle) },
            streamingPreference = mock {
                on { enabled } doReturn flowOf(false)
                onBlocking { current() } doReturn false
            },
            flacUpgradeEnqueuer = mock(),
            ytMusicApiClient = mock(),
            libraryPreferencesStore = mock {
                onBlocking { getSortOrder() } doReturn SortOrder.RECENT
                onBlocking { getSourceFilter() } doReturn SourceFilter.ALL
            },
            libraryDeepLinkController = com.stash.core.data.navigation.LibraryDeepLinkController(),
            artistImageDao = mock { on { observeAll() } doReturn flowOf(emptyList()) },
        )
    }
}
