package com.stash.feature.library

import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Playlist
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.files.LocalImportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking

/**
 * The Library sheet's "Show on Home" action: pinning stamps now (pin order
 * = rail order), unpinning writes NULL. The write goes through the
 * repository, never a whole-row update, so sync can't clobber it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelPinToHomeTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun toggle_pins_an_unpinned_playlist_with_a_timestamp() = runTest {
        val musicRepository = repoMock()
        val vm = buildVm(musicRepository)
        val playlist = Playlist(id = 7, name = "Gym", source = MusicSource.SPOTIFY)

        vm.togglePlaylistOnHome(playlist)
        advanceUntilIdle()

        verifyBlocking(musicRepository) {
            setPlaylistPinnedToHome(eq(7L), argThat { this != null && this > 0L })
        }
    }

    @Test fun toggle_unpins_a_pinned_playlist_with_null() = runTest {
        val musicRepository = repoMock()
        val vm = buildVm(musicRepository)
        val playlist = Playlist(
            id = 7, name = "Gym", source = MusicSource.SPOTIFY, pinnedToHomeAt = 123L,
        )

        vm.togglePlaylistOnHome(playlist)
        advanceUntilIdle()

        verifyBlocking(musicRepository) { setPlaylistPinnedToHome(7L, null) }
    }

    @Test fun unfollow_calls_the_repository() = runTest {
        val shared = sharedMock()
        val vm = buildVm(repoMock(), shared)
        vm.unfollowPlaylist(Playlist(id = 7, name = "Gym", source = MusicSource.SPOTIFY))
        advanceUntilIdle()
        verifyBlocking(shared) { unfollow(7L) }
    }

    @Test fun unfollow_that_throws_does_not_propagate() = runTest {
        val shared = sharedMock()
        org.mockito.kotlin.whenever(shared.unfollow(any())).doThrow(RuntimeException("db"))
        val vm = buildVm(repoMock(), shared)
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.userMessages.collect { messages.add(it) } }
        vm.unfollowPlaylist(Playlist(id = 7, name = "Gym", source = MusicSource.SPOTIFY))
        advanceUntilIdle() // an uncaught throw in viewModelScope would fail the test here
        verifyBlocking(shared) { unfollow(7L) }
        org.junit.Assert.assertEquals(listOf("Couldn't unfollow this mix. Try again."), messages)
    }

    private fun sharedMock(): com.stash.core.data.share.SharedMixRepository =
        mock { on { observeActiveFollowedIds() } doReturn flowOf(emptyList()) }

    // ── harness (mirrors LibraryViewModelShuffleLikedTest) ───────────────

    private fun repoMock(): MusicRepository = mock {
        on { getAllTracks() } doReturn flowOf(emptyList())
        on { getAllPlaylists() } doReturn flowOf(emptyList())
        on { getAllArtists() } doReturn flowOf(emptyList())
        on { getLibraryAlbums() } doReturn flowOf(emptyList())
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        on { getRecentlyAdded(any()) } doReturn flowOf(emptyList())
        on { getPlaylistsByType(any()) } doReturn flowOf(emptyList())
    }

    private fun buildVm(
        musicRepository: MusicRepository,
        sharedMixRepository: com.stash.core.data.share.SharedMixRepository = sharedMock(),
    ): LibraryViewModel {
        val playerRepository: PlayerRepository = mock {
            on { playerState } doReturn MutableStateFlow(PlayerState())
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
            sharedMixRepository = sharedMixRepository,
        )
    }
}
