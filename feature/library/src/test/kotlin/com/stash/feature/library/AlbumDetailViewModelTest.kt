package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
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

        ids.forEach { id -> verify(musicRepo).addTrackToPlaylist(id, targetPlaylistId) }
    }

    @Test
    fun createPlaylistAndAddTracks_creates_once_then_adds_each_to_new_id() = runTest {
        val musicRepo = musicRepoMock()
        val newPlaylistId = 99L
        whenever(musicRepo.createPlaylist(eq("My Mix"))).thenReturn(newPlaylistId)
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        vm.createPlaylistAndAddTracks("My Mix", ids)
        runCurrent()

        verify(musicRepo).createPlaylist("My Mix")
        ids.forEach { id -> verify(musicRepo).addTrackToPlaylist(id, newPlaylistId) }
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
    fun albumTracks_include_rows_whose_credits_contain_the_primary_act() = runTest {
        val album = "HEROES & VILLAINS"
        val tracks = listOf(
            Track(1L, "Superhero", "Metro Boomin, Future", album = album),
            Track(2L, "Niagara Falls", "Metro Boomin, Travis Scott, 21 Savage", album = album),
            Track(3L, "Trance", "Metro Boomin, Travis Scott", album = album),
            Track(4L, "Around Me", "Metro Boomin, Don Toliver", album = album),
        )
        // A different album must not leak in.
        val otherAlbum = Track(5L, "Highest in the Room", "Travis Scott", album = "JACKBOYS")
        val musicRepo = musicRepoMockWithTracks(tracks + otherAlbum)

        val vm = buildVm(
            musicRepository = musicRepo,
            savedStateHandle = SavedStateHandle(
                mapOf("albumName" to album, "artistName" to "Metro Boomin"),
            ),
        )

        val state = vm.uiState.first { !it.isLoading }
        assertEquals(listOf(1L, 2L, 3L, 4L), state.tracks.map { it.id })
        // The route's artist is still surfaced in the header for context.
        assertEquals("Metro Boomin", state.artistName)
    }

    @Test
    fun albumTracks_drop_rows_whose_credits_omit_the_primary_act() = runTest {
        // matchesArtistCredits pins on the album card's primary act: a row on
        // the same album credited to a different act is excluded.
        val album = "HEROES & VILLAINS"
        val tracks = listOf(
            Track(1L, "Superhero", "Metro Boomin, Future", album = album),
            Track(2L, "Private Dancer", "Don Toliver", album = album),
        )
        val musicRepo = musicRepoMockWithTracks(tracks)

        val vm = buildVm(
            musicRepository = musicRepo,
            savedStateHandle = SavedStateHandle(
                mapOf("albumName" to album, "artistName" to "Metro Boomin"),
            ),
        )

        val state = vm.uiState.first { !it.isLoading }
        assertEquals(listOf(1L), state.tracks.map { it.id })
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
        on { getAllTracks() } doReturn flowOf(emptyList())
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        onBlocking { queueDownload(any()) } doReturn true
    }

    private fun musicRepoMockWithTracks(tracks: List<Track>): MusicRepository = mock {
        on { getAllTracks() } doReturn flowOf(tracks)
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
            on { getAllTracks() } doReturn flowOf(emptyList())
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        },
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            mapOf("albumName" to "Album", "artistName" to "Artist"),
        ),
    ): AlbumDetailViewModel = AlbumDetailViewModel(
        savedStateHandle = savedStateHandle,
        musicRepository = musicRepository,
        playerRepository = playerRepository,
    )
}
