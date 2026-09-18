package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.db.dao.ArtistImageDao
import com.stash.core.data.db.entity.ArtistImageEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins the batch (multi-select) contract on [ArtistDetailViewModel]. Mirrors
 * [LikedSongsDetailViewModelTest] — same harness, same idioms — but Artist has
 * NO delete batch: the toolbar exposes only Play next / Add to queue /
 * Add to playlist / Download.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtistDetailViewModelTest {

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
    fun uiState_exposes_photoUrl_from_artist_image_cache() = runTest {
        val artistImageDao = mock<ArtistImageDao> {
            on { observeByName(any()) } doReturn flowOf(
                ArtistImageEntity(
                    artistName = "Artist",
                    imageUrl = "https://yt3.example/photo.jpg",
                    attemptedAt = 1L,
                ),
            )
        }
        val vm = buildVm(artistImageDao = artistImageDao)
        // drop(1): skip the WhileSubscribed initialValue (photoUrl = null)
        // and grab the first state the combine actually computes.
        val state = vm.uiState.drop(1).first()

        assertEquals("https://yt3.example/photo.jpg", state.photoUrl)
    }

    @Test
    fun uiState_photoUrl_is_null_when_no_cache_row_exists() = runTest {
        val vm = buildVm()
        val state = vm.uiState.drop(1).first()

        assertNull(state.photoUrl)
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

    /** Collects [ArtistDetailViewModel.userMessages] into a list for the test. */
    private fun kotlinx.coroutines.test.TestScope.collectMessages(
        vm: ArtistDetailViewModel,
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
        on { getTracksByArtist(any()) } doReturn flowOf(emptyList())
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        onBlocking { queueDownload(any()) } doReturn true
    }

    /**
     * Builds an [ArtistDetailViewModel] for tests. Collaborators default to
     * plain mocks with the minimum stubs for the VM's `stateIn` flow to start
     * without NPEs. The `artistName` route arg is supplied via [SavedStateHandle].
     */
    private fun buildVm(
        playerRepository: PlayerRepository = mock {
            on { playerState } doReturn MutableStateFlow(PlayerState())
        },
        musicRepository: MusicRepository = mock {
            on { getTracksByArtist(any()) } doReturn flowOf(emptyList())
            on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        },
        artistImageDao: ArtistImageDao = mock {
            on { observeByName(any()) } doReturn flowOf(null)
        },
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            mapOf("artistName" to "Artist"),
        ),
    ): ArtistDetailViewModel = ArtistDetailViewModel(
        savedStateHandle = savedStateHandle,
        musicRepository = musicRepository,
        playerRepository = playerRepository,
        artistImageDao = artistImageDao,
    )
}
