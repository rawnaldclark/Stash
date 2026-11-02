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
    private val redMedicine = saved(2, "Red Medicine", dateAdded = 30, downloaded = 9, downloading = 4, trackCount = 13)
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

    @Test fun `in Download mode a card still downloading says how far it is`() = runTest {
        val download = buildVm(repo(albums = listOf(repeater, redMedicine)), streaming = false).uiState.first { !it.isLoading }
        val stream = buildVm(repo(albums = listOf(repeater, redMedicine)), streaming = true).uiState.first { !it.isLoading }

        assertThat(download.albums.single { it.playlistId == 2L }.onPhoneCount).isEqualTo(9)
        assertThat(download.albums.single { it.playlistId == 1L }.onPhoneCount).isNull()
        assertThat(stream.albums.map { it.onPhoneCount }).containsExactly(null, null)
        assertThat(albumOnPhoneLine(9, 13)).isEqualTo("9 of 13 on this phone")
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
        downloading: Int = 0,
    ) = SavedAlbum(
        playlistId = id, title = title, artist = artist, artUrl = null, source = MusicSource.BOTH,
        sourceId = "album:qobuz:$id", dateAdded = dateAdded, trackCount = trackCount,
        downloadedCount = downloaded, downloadingCount = downloading,
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
