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
 * Issue #455: the Liked tab showed each like-source's playlist glued end to
 * end in playlist-position order and never sorted, so a song liked today sat
 * "in the middle" — the in-app likes came first, then Spotify's list, each
 * oldest-first. "Recently added" on this tab must mean recently LIKED: the
 * newest like on top, whichever source it came from, falling back to the
 * library add date for a row with no like timestamp. The other sort orders
 * apply too — the control was silently ignored here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelLikedOrderTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val stashLiked = Playlist(
        id = 7L, name = "Liked in Stash", source = MusicSource.SPOTIFY, type = PlaylistType.STASH_LIKED, trackCount = 1,
    )
    private val spotifyLiked = Playlist(
        id = 42L, name = "Liked Songs", source = MusicSource.SPOTIFY, type = PlaylistType.LIKED_SONGS, trackCount = 3,
    )

    // In the library since 1000, liked in Stash at 5000 — the newest like of all.
    private val zebra = Track(id = 1L, title = "Zebra", artist = "A", dateAdded = 1_000L, stashLikedAt = 5_000L)
    // Spotify likes, playlist-position order: Apple then Mango.
    private val apple = Track(id = 2L, title = "Apple", artist = "B", dateAdded = 2_000L, spotifySavedAt = 3_000L)
    private val mango = Track(id = 3L, title = "Mango", artist = "C", dateAdded = 4_000L, spotifySavedAt = 4_500L)
    // No like timestamp at all (older row): the library add date stands in.
    private val plum = Track(id = 4L, title = "Plum", artist = "D", dateAdded = 6_000L)

    @Test fun recent_means_recently_liked_across_sources() = runTest {
        val vm = buildVm()

        val ids = vm.likedTracks.first { it.size == 4 }.map { it.id }

        // Plum 6000 (add date fallback) > Zebra 5000 > Mango 4500 > Apple 3000
        assertThat(ids).containsExactly(4L, 1L, 3L, 2L).inOrder()
    }

    @Test fun the_sort_control_applies_to_the_liked_tab() = runTest {
        val vm = buildVm()
        vm.likedTracks.first { it.size == 4 }

        vm.setSortOrder(SortOrder.ALPHABETICAL)

        val titles = vm.likedTracks.first { it.first().title == "Apple" }.map { it.title }
        assertThat(titles).containsExactly("Apple", "Mango", "Plum", "Zebra").inOrder()
    }

    // ── harness (mirrors LibraryViewModelLikedSearchTest) ─────────────────
    private fun buildVm(): LibraryViewModel {
        val musicRepository: MusicRepository = mock {
            on { getAllTracks() } doReturn flowOf(emptyList())
            on { getAllPlaylists() } doReturn flowOf(emptyList())
            on { getAllArtists() } doReturn flowOf(emptyList())
            on { getAllAlbums() } doReturn flowOf(emptyList())
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
            on { getRecentlyAdded(any()) } doReturn flowOf(emptyList())
            on { getPlaylistsByType(eq(PlaylistType.STASH_LIKED)) } doReturn flowOf(listOf(stashLiked))
            on { getPlaylistsByType(eq(PlaylistType.LIKED_SONGS)) } doReturn flowOf(listOf(spotifyLiked))
            on { getTracksByPlaylist(eq(7L)) } doReturn flowOf(listOf(zebra))
            on { getTracksByPlaylist(eq(42L)) } doReturn flowOf(listOf(apple, mango, plum))
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
            streamingPreference = mock { on { enabled } doReturn flowOf(false) },
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
