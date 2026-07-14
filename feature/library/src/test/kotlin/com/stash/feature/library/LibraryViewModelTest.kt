package com.stash.feature.library

import android.content.Context
import com.stash.core.auth.TokenManager
import com.stash.core.auth.model.AuthState
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import com.stash.data.download.files.LocalImportCoordinator
import com.stash.data.download.files.LocalImportState
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins the batch-action contract added in Task 11 of the multi-select redesign
 * for the Library (Tracks tab). Each batch method wraps the existing
 * single-track path — Queue uses the batch [PlayerRepository.addToQueue]
 * overload (single call), Play Next loops [PlayerRepository.addNext], and
 * download/remove/save/delete loop the per-id [MusicRepository] calls with a
 * single roll-up Snackbar and per-item failure isolation.
 *
 * Mirrors the harness in [PlaylistDetailViewModelTest] /
 * [LikedSongsDetailViewModelTest]: StandardTestDispatcher +
 * Dispatchers.setMain/resetMain, runTest{}, mockito-kotlin, runCurrent().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

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
    fun deleteSelected_deletes_each_track_and_emits_rollup() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val tracks = listOf(track(1L), track(2L), track(3L))

        val messages = collectMessages(vm)
        vm.deleteSelected(tracks, alsoBlacklist = false)
        runCurrent()

        tracks.forEach { t -> verify(musicRepo).deleteTrack(t) }
        assertEquals(listOf("Deleted 3 songs."), messages)
    }

    @Test
    fun deleteSelected_with_blacklist_blacklists_each_id() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val tracks = listOf(track(1L), track(2L))

        val messages = collectMessages(vm)
        vm.deleteSelected(tracks, alsoBlacklist = true)
        runCurrent()

        tracks.forEach { t -> verify(musicRepo).blacklistTrack(t.id) }
        assertEquals(listOf("Deleted 2 songs."), messages)
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

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun track(id: Long) = Track(id = id, title = "Track $id", artist = "Artist")

    /** Collects [LibraryViewModel.userMessages] into a list for the test. */
    private fun kotlinx.coroutines.test.TestScope.collectMessages(
        vm: LibraryViewModel,
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
        on { getAllPlaylists() } doReturn flowOf(emptyList())
        on { getAllArtists() } doReturn flowOf(emptyList())
        on { getAllAlbums() } doReturn flowOf(emptyList())
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        onBlocking { queueDownload(any()) } doReturn true
        onBlocking { deleteTrack(any()) } doReturn true
    }

    private fun tokenManagerMock(): TokenManager = mock {
        on { spotifyAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
        on { youTubeAuthState } doReturn MutableStateFlow<AuthState>(AuthState.NotConnected)
    }

    /**
     * Builds a [LibraryViewModel] for tests. All collaborators default to
     * plain mocks with the minimum stubs needed for the VM's `init`/`stateIn`
     * flows to start up without NPEs. Tests that care about a specific
     * collaborator pass in their own configured mock.
     */
    private fun buildVm(
        playerRepository: PlayerRepository = playerRepoMock(),
        musicRepository: MusicRepository = musicRepoMock(),
        tokenManager: TokenManager = tokenManagerMock(),
        playlistImageHelper: PlaylistImageHelper = mock(),
        localImportCoordinator: LocalImportCoordinator = mock {
            on { state } doReturn MutableStateFlow<LocalImportState>(LocalImportState.Idle)
        },
        recipeDao: StashMixRecipeDao = mock {
            on { observeAll() } doReturn flowOf(emptyList())
        },
        discoveryQueueDao: DiscoveryQueueDao = mock {
            on { observeNonFailedCountsByRecipe() } doReturn flowOf(emptyList())
        },
        downloadNetworkPreference: DownloadNetworkPreference = mock(),
        streamingPreference: StreamingPreference = mock(),
        context: Context = mock(),
    ): LibraryViewModel = LibraryViewModel(
        musicRepository = musicRepository,
        playerRepository = playerRepository,
        tokenManager = tokenManager,
        playlistImageHelper = playlistImageHelper,
        localImportCoordinator = localImportCoordinator,
        recipeDao = recipeDao,
        discoveryQueueDao = discoveryQueueDao,
        downloadNetworkPreference = downloadNetworkPreference,
        streamingPreference = streamingPreference,
        context = context,
    )
}
