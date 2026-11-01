package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins the batch (multi-select) contract on [AlbumDetailViewModel]. Mirrors
 * [LikedSongsDetailViewModelTest] — same harness, same idioms — but Album has
 * NO delete batch: the toolbar exposes only Play next / Add to queue /
 * Add to playlist / Download.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun playSelectedNext_loops_addNext_per_track() = runTest {
        val playerRepo = playerRepoMock()
        val vm = buildVm(playerRepository = playerRepo)
        val tracks = listOf(track(1L), track(2L), track(3L))

        vm.playSelectedNext(tracks)
        runCurrent()

        tracks.forEach { t -> verify(playerRepo).addNext(t) }
    }

    @Test
    fun addSelectedToQueue_uses_batch_overload() = runTest {
        val playerRepo = playerRepoMock()
        val vm = buildVm(playerRepository = playerRepo)
        val tracks = listOf(track(1L), track(2L))

        vm.addSelectedToQueue(tracks)
        runCurrent()

        // batch overload, single call
        verify(playerRepo).addToQueue(tracks)
    }

    @Test
    fun downloadSelected_queues_each_id_and_emits_rollup() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        val messages = collectMessages(vm)
        vm.downloadSelected(ids)
        runCurrent()

        ids.forEach { id -> verify(musicRepo).queueDownload(id) }
        assertEquals(listOf("Queued 3 songs for download."), messages)
    }

    @Test
    fun removeDownloadsForSelected_removes_each_id_and_emits_rollup() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L)

        val messages = collectMessages(vm)
        vm.removeDownloadsForSelected(ids)
        runCurrent()

        ids.forEach { id -> verify(musicRepo).removeDownload(id) }
        assertEquals(listOf("Removed downloads for 2 songs."), messages)
    }

    @Test
    fun saveSelectedToPlaylist_adds_each_id_to_target() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L)
        val targetPlaylistId = 99L

        vm.saveSelectedToPlaylist(ids, targetPlaylistId)
        runCurrent()

        verify(musicRepo).addTracksToPlaylist(ids, targetPlaylistId)
    }

    @Test
    fun createPlaylistAndAddTracks_creates_once_then_adds_each_to_new_id() = runTest {
        val musicRepo = musicRepoMock()
        val newPlaylistId = 99L
        whenever(musicRepo.createPlaylistWithTracks(eq("My Mix"), any())).thenReturn(newPlaylistId)
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        vm.createPlaylistAndAddTracks("My Mix", ids)
        runCurrent()

        verify(musicRepo).createPlaylistWithTracks("My Mix", ids)
    }

    @Test
    fun downloadSelected_isolates_per_item_failure() = runTest {
        val musicRepo = musicRepoMock()
        // Second item throws; first and third must still be attempted.
        whenever(musicRepo.queueDownload(eq(2L)))
            .thenThrow(RuntimeException("boom"))
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        val messages = collectMessages(vm)
        vm.downloadSelected(ids)
        runCurrent()

        // All three repo calls happened despite the middle one throwing.
        verify(musicRepo).queueDownload(1L)
        verify(musicRepo).queueDownload(2L)
        verify(musicRepo).queueDownload(3L)
        // Roll-up reflects only the two that succeeded.
        assertEquals(listOf("Queued 2 songs for download."), messages)
    }

    @Test
    fun downloadSelected_counts_only_true_queue_results() = runTest {
        val musicRepo = musicRepoMock()
        whenever(musicRepo.queueDownload(eq(2L))).thenReturn(false)
        val vm = buildVm(musicRepository = musicRepo)

        val messages = collectMessages(vm)
        vm.downloadSelected(listOf(1L, 2L, 3L))
        runCurrent()

        assertEquals(listOf("Queued 2 songs for download."), messages)
    }

    @Test
    fun albumTracks_are_the_repository_songs_for_the_route_album_and_artist() = runTest {
        // The credit match (collab rows in, other acts out) and the Download switch live in
        // MusicRepository.getTracksByAlbum (MusicRepositoryLibraryScopeTest): the page lists
        // what it returns for its own route.
        val album = "HEROES & VILLAINS"
        val tracks = listOf(
            Track(1L, "Superhero", "Metro Boomin, Future", album = album),
            Track(2L, "Niagara Falls", "Metro Boomin, Travis Scott, 21 Savage", album = album),
        )
        val musicRepo = musicRepoMockWithTracks(tracks)

        val vm = buildVm(
            musicRepository = musicRepo,
            savedStateHandle = SavedStateHandle(
                mapOf("albumName" to album, "artistName" to "Metro Boomin"),
            ),
        )

        val state = vm.uiState.first { !it.isLoading }
        assertEquals(listOf(1L, 2L), state.tracks.map { it.id })
        verify(musicRepo).getTracksByAlbum(album, "Metro Boomin")
        // The route's artist is still surfaced in the header for context.
        assertEquals("Metro Boomin", state.artistName)
    }

    // ------------------------------------------------------------------
    // Playing from the album page
    // ------------------------------------------------------------------

    /** Songs 1 and 3 are downloaded, 2 and 4 aren't (an album page in Stream only). */
    private val albumSongs = listOf(
        track(1L).copy(filePath = "/music/1.flac", isDownloaded = true),
        track(2L),
        track(3L).copy(filePath = "/music/3.flac", isDownloaded = true),
        track(4L),
    )

    private fun kotlinx.coroutines.test.TestScope.albumVm(
        playerRepo: PlayerRepository,
        online: Boolean,
        songs: List<Track> = albumSongs,
    ): AlbumDetailViewModel {
        val vm = buildVm(
            playerRepository = playerRepo,
            musicRepository = musicRepoMockWithTracks(songs),
            connectivityMonitor = mock { on { isConnected() } doReturn online },
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun online_tapping_a_song_that_isnt_downloaded_plays_that_song_with_every_song_queued() = runTest {
        val playerRepo = playerRepoMock()
        val vm = albumVm(playerRepo, online = true)

        vm.playTrack(2L)
        runCurrent()

        // Index 1 = song 2. It used to fall back to index 0 of the downloaded ones: song 1.
        verify(playerRepo).setQueue(eq(albumSongs), eq(1), any())
    }

    @Test
    fun offline_tapping_a_song_that_isnt_downloaded_says_why_and_plays_nothing() = runTest {
        val playerRepo = playerRepoMock()
        val vm = albumVm(playerRepo, online = false)
        val messages = collectMessages(vm)

        vm.playTrack(4L)
        runCurrent()

        verify(playerRepo, never()).setQueue(any(), any(), any())
        assertEquals(listOf("No internet connection, and this song isn't downloaded."), messages)
    }

    @Test
    fun offline_tapping_a_downloaded_song_queues_only_the_downloaded_ones() = runTest {
        val playerRepo = playerRepoMock()
        val vm = albumVm(playerRepo, online = false)

        vm.playTrack(3L)
        runCurrent()

        verify(playerRepo).setQueue(eq(listOf(albumSongs[0], albumSongs[2])), eq(1), any())
    }

    @Test
    fun online_shuffle_queues_every_song() = runTest {
        val playerRepo = playerRepoMock()
        val vm = albumVm(playerRepo, online = true)

        vm.shuffleAll()
        runCurrent()

        verify(playerRepo).setQueue(argThat { toSet() == albumSongs.toSet() && size == albumSongs.size }, eq(0), any())
    }

    @Test
    fun offline_shuffle_queues_only_the_downloaded_songs() = runTest {
        val playerRepo = playerRepoMock()
        val vm = albumVm(playerRepo, online = false)

        vm.shuffleAll()
        runCurrent()

        verify(playerRepo).setQueue(argThat { toSet() == setOf(albumSongs[0], albumSongs[2]) && size == 2 }, eq(0), any())
    }

    @Test
    fun offline_shuffle_with_nothing_downloaded_says_why() = runTest {
        val playerRepo = playerRepoMock()
        val vm = albumVm(playerRepo, online = false, songs = listOf(track(2L), track(4L)))
        val messages = collectMessages(vm)

        vm.shuffleAll()
        runCurrent()

        verify(playerRepo, never()).setQueue(any(), any(), any())
        assertEquals(listOf("None of this album's songs are downloaded. Connect to play them."), messages)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun track(id: Long) = Track(id = id, title = "Track $id", artist = "Artist")

    /** Collects [AlbumDetailViewModel.userMessages] into a list for the test. */
    private fun kotlinx.coroutines.test.TestScope.collectMessages(
        vm: AlbumDetailViewModel,
    ): List<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch { vm.userMessages.collect { messages.add(it) } }
        runCurrent()
        return messages
    }

    private fun playerRepoMock(): PlayerRepository = mock {
        on { playerState } doReturn MutableStateFlow(PlayerState())
    }

    private fun musicRepoMock(): MusicRepository = mock {
        on { getTracksByAlbum(any(), any()) } doReturn flowOf(emptyList())
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        onBlocking { queueDownload(any()) } doReturn true
    }

    private fun musicRepoMockWithTracks(tracks: List<Track>): MusicRepository = mock {
        on { getTracksByAlbum(any(), any()) } doReturn flowOf(tracks)
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        onBlocking { queueDownload(any()) } doReturn true
    }

    /**
     * Builds an [AlbumDetailViewModel] for tests. Collaborators default to
     * plain mocks with the minimum stubs for the VM's `stateIn` flow to start
     * without NPEs. The `albumName` / `artistName` route args are supplied via
     * [SavedStateHandle].
     */
    private fun buildVm(
        playerRepository: PlayerRepository = mock {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        },
        musicRepository: MusicRepository = mock {
            on { getTracksByAlbum(any(), any()) } doReturn flowOf(emptyList())
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        },
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            mapOf("albumName" to "Album", "artistName" to "Artist"),
        ),
        connectivityMonitor: ConnectivityMonitor = mock { on { isConnected() } doReturn true },
    ): AlbumDetailViewModel = AlbumDetailViewModel(
        savedStateHandle = savedStateHandle,
        musicRepository = musicRepository,
        playerRepository = playerRepository,
        connectivityMonitor = connectivityMonitor,
    )
}
