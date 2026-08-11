package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.PlayerState
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins the contract introduced in Task 9 of the extract-coalescing redesign:
 * [LikedSongsDetailViewModel.tappedTrackId] emits the tapped track ID before
 * [PlayerRepository.setQueue] is awaited, and clears back to `null` after
 * it returns. The screen layer reads this StateFlow to render an instant
 * spinner without waiting for the YouTube-stream resolver to finish.
 *
 * Uses mockito-kotlin to match the in-repo pattern from
 * `feature/search/.../SearchViewModelTest` — same harness, same idioms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LikedSongsDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }


    @Test
    fun `tappedTrackId emits on playTrack and clears after setQueue returns`() = runTest {
        val gate = CompletableDeferred<Unit>()
        // A single track in a single playlist so that the VM's tracksFlow ->
        // uiState pipeline ends up with a non-empty `tracks` list. Without
        // this, `playTrack`'s `playable.isEmpty()` early-return short-circuits
        // before setQueue is invoked, and the suspend-on-gate part of the
        // spinner contract never gets exercised.
        val track = Track(id = 42L, title = "Hit", artist = "Artist")
        val playlist = Playlist(
            id = 1L, name = "Liked", source = MusicSource.SPOTIFY, type = PlaylistType.LIKED_SONGS,
        )
        val musicRepo = mock<MusicRepository> {
            on { getPlaylistsByType(any()) } doReturn flowOf(listOf(playlist))
            on { getTracksByPlaylist(playlist.id) } doReturn flowOf(listOf(track))
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        }
        val playerRepo = mock<PlayerRepository> {
            on { playerState } doReturn MutableStateFlow(PlayerState())
            onBlocking { setQueue(any(), any(), any()) } doSuspendableAnswer { gate.await() }   
        }
        val vm = buildVm(playerRepository = playerRepo, musicRepository = musicRepo)

        val emissions = mutableListOf<Long?>()
        val collectJob = backgroundScope.launch {
            vm.tappedTrackId.collect { emissions.add(it) }
        }
        // Drive uiState's `stateIn` so `uiState.value.tracks` becomes
        // populated before playTrack reads it. Without this, WhileSubscribed
        // would never have kicked off the collector and the snapshot stays
        // at the initial empty value.
        val uiSub = backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        vm.playTrack(trackId = 42L)
        runCurrent()
        assertThat(emissions).contains(42L)

        gate.complete(Unit)
        runCurrent()
        assertThat(emissions.last()).isNull()

        collectJob.cancel()
        uiSub.cancel()
    }

    // ------------------------------------------------------------------
    // Batch (multi-select) actions — mirror PlaylistDetailViewModelTest
    // ------------------------------------------------------------------

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
    fun deleteSelected_unlikes_each_track_and_emits_rollup() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val tracks = listOf(track(1L), track(2L), track(3L))

        val messages = collectMessages(vm)
        vm.deleteSelected(tracks)
        runCurrent()

        tracks.forEach { t -> verify(musicRepo).deleteTrack(t) }
        assertEquals(listOf("Removed 3 songs from Liked Songs."), messages)
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun track(id: Long) = Track(id = id, title = "Track $id", artist = "Artist")

    /** Collects [LikedSongsDetailViewModel.userMessages] into a list for the test. */
    private fun kotlinx.coroutines.test.TestScope.collectMessages(
        vm: LikedSongsDetailViewModel,
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
        on { getPlaylistsByType(any()) } doReturn flowOf(emptyList())
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        onBlocking { queueDownload(any()) } doReturn true
        onBlocking { deleteTrack(any()) } doReturn true
    }

    /**
     * Builds a [LikedSongsDetailViewModel] for tests. All collaborators
     * default to plain mocks with the minimum stubs needed for the VM's
     * `init`/`stateIn` flows to start up without NPEs. Tests that care
     * about a specific collaborator (here: [PlayerRepository]) pass in
     * their own configured mock.
     */
    private fun buildVm(
        playerRepository: PlayerRepository = mock {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        },
        musicRepository: MusicRepository = mock {
            on { getPlaylistsByType(any()) } doReturn flowOf(emptyList())
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        },
        streamingPreference: StreamingPreference = mock {
            onBlocking { current() } doReturn true
        },
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): LikedSongsDetailViewModel = LikedSongsDetailViewModel(
        savedStateHandle = savedStateHandle,
        musicRepository = musicRepository,
        playerRepository = playerRepository,
        streamingPreference = streamingPreference,
    )
}
