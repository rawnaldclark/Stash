package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.album.SavedAlbum
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * Library › Albums = saved albums only (owner, 2026-11-02): the tab lists the repository's saved
 * albums, the Playlists tab leaves them out, and a card plays and removes its own album.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelAlbumsTest {

    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val repeater = saved(1, "Repeater", dateAdded = 10, downloaded = 11, trackCount = 11)
    private val redMedicine = saved(2, "Red Medicine", dateAdded = 30, downloaded = 9, trackCount = 13)
    private val argument = saved(3, "The Argument", artist = "Fugazi, Ian MacKaye", dateAdded = 20, trackCount = 10)

    private val roadTrip = Playlist(id = 50, name = "Road trip", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM)
    private val albumRow = Playlist(id = 1, name = "Repeater", source = MusicSource.BOTH, sourceId = "album:qobuz:rep", type = PlaylistType.CUSTOM)
    private val syncedAlbumRow = Playlist(id = 2, name = "Red Medicine", source = MusicSource.YOUTUBE, sourceId = "MPREb_red", type = PlaylistType.CUSTOM)

    @Test fun `Albums lists the saved albums, newest saved first, and Playlists leaves them out`() = runTest {
        val vm = buildVm(repo(albums = listOf(repeater, redMedicine, argument), playlists = listOf(roadTrip, albumRow, syncedAlbumRow)))

        val state = vm.uiState.first { !it.isLoading }

        assertThat(state.albums.map { it.playlistId }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(state.albums.map { it.name }).containsExactly("Red Medicine", "The Argument", "Repeater").inOrder()
        assertThat(state.playlists.map { it.id }).containsExactly(50L)
    }

    @Test fun `a card is the album the repository lists, whatever the mode`() = runTest {
        // Download mode's filter (complete albums only) is the repository's: the tab shows its cards as they come.
        val download = buildVm(repo(albums = listOf(repeater)), streaming = false).uiState.first { !it.isLoading }

        assertThat(download.albums.map { it.playlistId }).containsExactly(1L)
        assertThat(download.albums.single().copyIds).containsExactly(1L)
    }

    @Test fun `search finds an album by its artist`() = runTest {
        val vm = buildVm(repo(albums = listOf(repeater, redMedicine, saved(4, "Nevermind", artist = "Nirvana"))))
        vm.setSearchQuery("nirvana")
        advanceUntilIdle()

        val state = vm.uiState.first { !it.isLoading && it.searchQuery == "nirvana" }

        assertThat(state.albums.map { it.name }).containsExactly("Nevermind")
    }

    @Test fun `Remove from Library takes the album's own row out`() = runTest {
        val music = repo(albums = listOf(repeater)).stub { on { observePlaylist(1L) } doReturn flowOf(albumRow) }
        val vm = buildVm(music)

        vm.removeAlbum(vm.uiState.first { !it.isLoading }.albums.single())
        advanceUntilIdle()

        verify(music).removePlaylist(albumRow)
    }

    @Test fun `Remove on a merged card takes every copy, deleting a saved one and hiding a synced one`() = runTest {
        // Red Medicine synced from YouTube Music (2) and saved again from its Qobuz page (1): one card.
        val merged = redMedicine.copy(copyIds = listOf(2L, 1L))
        val music = repo(albums = listOf(merged)).stub {
            on { observePlaylist(1L) } doReturn flowOf(albumRow)
            on { observePlaylist(2L) } doReturn flowOf(syncedAlbumRow)
        }
        val vm = buildVm(music)

        vm.removeAlbum(vm.uiState.first { !it.isLoading }.albums.single())
        advanceUntilIdle()

        verify(music).removePlaylist(albumRow)
        // Deleting the synced copy would bring it back on the next sync: it is hidden instead.
        verify(music).hidePlaylist(2L)
        verify(music, never()).removePlaylist(syncedAlbumRow)
        verify(music, never()).hidePlaylist(1L)
    }

    @Test fun `Remove hides a pasted Spotify album, so the Spotify sync can't bring it back`() = runTest {
        // Saved in Stash from a Spotify link: the same key the Spotify sync files the album under.
        val pasted = Playlist(id = 1, name = "Repeater", source = MusicSource.BOTH, sourceId = "album:spotify:FakeAlb1xxxxxxxxxxxxxx", type = PlaylistType.CUSTOM)
        val music = repo(albums = listOf(repeater)).stub { on { observePlaylist(1L) } doReturn flowOf(pasted) }
        val vm = buildVm(music)

        vm.removeAlbum(vm.uiState.first { !it.isLoading }.albums.single())
        advanceUntilIdle()

        verify(music).hidePlaylist(1L)
        verify(music, never()).removePlaylist(pasted)
    }

    @Test fun `the empty Albums tab says how to fill it`() {
        assertThat(albumsEmptyMessage(downloadMode = false)).contains("Tap Save on any album")
        assertThat(albumsEmptyMessage(downloadMode = true)).contains("once every song is on your phone")
    }

    // ── harness ─────────────────────────────────────────────────────────

    private fun saved(
        id: Long,
        title: String,
        artist: String = "Fugazi",
        dateAdded: Long = id,
        trackCount: Int = 12,
        downloaded: Int = 0,
    ) = SavedAlbum(
        playlistId = id, title = title, artist = artist, artUrl = null, source = MusicSource.BOTH,
        sourceId = "album:qobuz:$id", dateAdded = dateAdded, trackCount = trackCount,
        downloadedCount = downloaded,
    )

    private fun repo(albums: List<SavedAlbum>, playlists: List<Playlist> = emptyList()): MusicRepository = mock {
        on { getAllTracks() } doReturn flowOf(emptyList())
        on { getLibraryTracksIncludingOnline() } doReturn flowOf(emptyList())
        on { getAllPlaylists() } doReturn flowOf(playlists)
        on { getAllArtists() } doReturn flowOf(emptyList())
        on { getLibraryAlbums() } doReturn flowOf(albums)
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        on { getRecentlyAdded(any()) } doReturn flowOf(emptyList())
    }

    private fun buildVm(musicRepository: MusicRepository, streaming: Boolean = true): LibraryViewModel = LibraryViewModel(
        musicRepository = musicRepository,
        playerRepository = mock { on { playerState } doReturn MutableStateFlow(PlayerState()) },
        tokenManager = mock<TokenManager> {
            on { spotifyAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
            on { youTubeAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
        },
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
        sharedMixRepository = mock { on { observeActiveFollowedIds() } doReturn flowOf(emptyList()) },
    )
}
