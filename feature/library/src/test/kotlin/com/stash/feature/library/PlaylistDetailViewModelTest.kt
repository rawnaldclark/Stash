package com.stash.feature.library

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.PlayerRepository
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.model.PlayerState
import com.stash.core.model.Track
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
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Pins the batch-action contract added in Task 5 of the multi-select redesign:
 * each batch method wraps the existing single-track path — Queue uses the batch
 * [PlayerRepository.addToQueue] overload (single call), Play Next loops
 * [PlayerRepository.addNext], and download/remove/save/delete loop the
 * per-id [MusicRepository] calls.
 *
 * Mirrors the harness in [LikedSongsDetailViewModelTest]: StandardTestDispatcher
 * + Dispatchers.setMain/resetMain, runTest{}, mockito-kotlin, runCurrent().
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `an active follow exposes read-only state with the sharer's name`() = runTest {
        val shared = mock<com.stash.core.data.share.SharedMixRepository> {
            on { observe(any()) } doReturn flowOf(
                com.stash.core.data.db.entity.SharedMixEntity(
                    1, "Kx7Qa2pL", com.stash.core.data.db.entity.SharedMixEntity.ROLE_FOLLOWER, name = "Ambient", sharedBy = "Rawn",
                ),
            )
        }
        val vm = buildVm(sharedMixRepository = shared)
        backgroundScope.launch { vm.follow.collect {} }
        runCurrent()
        val f = checkNotNull(vm.follow.value)
        assertEquals(true, f.readOnly)
        assertEquals("Rawn", f.sharedBy)
    }

    @Test fun `a follow whose owner stopped sharing is an ordinary editable playlist`() = runTest {
        val shared = mock<com.stash.core.data.share.SharedMixRepository> {
            on { observe(any()) } doReturn flowOf(
                com.stash.core.data.db.entity.SharedMixEntity(
                    1, "Kx7Qa2pL", com.stash.core.data.db.entity.SharedMixEntity.ROLE_FOLLOWER, name = "Ambient",
                    status = com.stash.core.data.db.entity.SharedMixEntity.STATUS_REMOVED,
                ),
            )
        }
        val vm = buildVm(sharedMixRepository = shared)
        backgroundScope.launch { vm.follow.collect {} }
        runCurrent()
        assertEquals(false, checkNotNull(vm.follow.value).readOnly)
    }

    @Test fun `a rename in the database reaches the header`() = runTest {
        val live = MutableStateFlow<com.stash.core.model.Playlist?>(null)
        val music = musicRepoMock().stub {
            on { observePlaylist(any()) } doReturn live
            onBlocking { getPlaylistWithTracks(1L) } doReturn
                com.stash.core.model.Playlist(id = 1L, name = "Ambient", source = com.stash.core.model.MusicSource.BOTH)
        }
        val vm = buildVm(musicRepository = music)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertEquals("Ambient", vm.uiState.value.playlist?.name)
        live.value = com.stash.core.model.Playlist(id = 1L, name = "Sleep", source = com.stash.core.model.MusicSource.BOTH, syncEnabled = true)
        runCurrent()
        assertEquals("Sleep", vm.uiState.value.playlist?.name)
        assertEquals(true, vm.uiState.value.playlist?.syncEnabled)
    }

    @Test fun `the stopped-sharing notice shows once when the follow flips while the screen is open`() = runTest {
        val row = MutableStateFlow(
            com.stash.core.data.db.entity.SharedMixEntity(1, "Kx7Qa2pL", com.stash.core.data.db.entity.SharedMixEntity.ROLE_FOLLOWER, name = "Ambient"),
        )
        val shared = mock<com.stash.core.data.share.SharedMixRepository> {
            on { observe(any()) } doReturn row
            onBlocking { consumeRemovedNotice(1L) } doReturn "Rawn stopped sharing this mix. You keep your copy."
        }
        val vm = buildVm(sharedMixRepository = shared)
        val messages = collectMessages(vm)
        assertEquals(emptyList<String>(), messages)
        val removed = row.value.copy(status = com.stash.core.data.db.entity.SharedMixEntity.STATUS_REMOVED, noticePending = true)
        row.value = removed
        runCurrent()
        row.value = removed.copy(missingCount = 1) // an unrelated re-emit with the flag still set
        runCurrent()
        assertEquals(listOf("Rawn stopped sharing this mix. You keep your copy."), messages)
        org.mockito.kotlin.verifyBlocking(shared, org.mockito.kotlin.times(1)) { consumeRemovedNotice(1L) }
    }

    @Test fun `turning Download this mix on writes it through the repository`() = runTest {
        val shared = mock<com.stash.core.data.share.SharedMixRepository> { on { observe(any()) } doReturn flowOf(null) }
        val vm = buildVm(sharedMixRepository = shared)
        vm.setFollowDownload(true)
        runCurrent()
        org.mockito.kotlin.verifyBlocking(shared) { setDownload(1L, true) }
    }

    // ── The page's Download switch (#474) ──────────────────────────────

    @Test fun `stream-only mode shows the switch on only for a kept playlist`() = runTest {
        assertEquals(false, downloadState(playlist(syncEnabled = true), streamOnly = true))
        assertEquals(true, downloadState(playlist(keepOffline = true), streamOnly = true))
    }

    @Test fun `download mode also shows on for a playlist the Sync tab downloads`() = runTest {
        assertEquals(true, downloadState(playlist(syncEnabled = true), streamOnly = false))
        assertEquals(true, downloadState(playlist(keepOffline = true), streamOnly = false))
        assertEquals(false, downloadState(playlist(), streamOnly = false))
    }

    @Test fun `imported mixes and playlists get the switch, system mixes do not`() = runTest {
        assertEquals(false, downloadState(playlist(type = com.stash.core.model.PlaylistType.DAILY_MIX)))
        assertEquals(false, downloadState(playlist(type = com.stash.core.model.PlaylistType.CUSTOM)))
        assertEquals(null, downloadState(playlist(type = com.stash.core.model.PlaylistType.STASH_MIX)))
        assertEquals(null, downloadState(playlist(type = com.stash.core.model.PlaylistType.DOWNLOADS_MIX)))
        assertEquals(null, downloadState(playlist(type = com.stash.core.model.PlaylistType.LIKED_SONGS)))
    }

    @Test fun `a read-only followed mix keeps only its own switch`() = runTest {
        val follower = com.stash.core.data.db.entity.SharedMixEntity(
            1, "Kx7Qa2pL", com.stash.core.data.db.entity.SharedMixEntity.ROLE_FOLLOWER, name = "Ambient",
        )
        assertEquals(null, downloadState(playlist(), shared = follower))
        // Once the owner stops sharing it is an ordinary playlist, and gets the page's switch.
        val removed = follower.copy(status = com.stash.core.data.db.entity.SharedMixEntity.STATUS_REMOVED)
        assertEquals(false, downloadState(playlist(), shared = removed))
    }

    @Test fun `the switch follows the playlist row live`() = runTest {
        val live = MutableStateFlow<com.stash.core.model.Playlist?>(null)
        val music = musicRepoMock().stub {
            on { observePlaylist(any()) } doReturn live
            onBlocking { getPlaylistWithTracks(1L) } doReturn playlist()
        }
        val vm = buildVm(musicRepository = music)
        backgroundScope.launch { vm.download.collect {} }
        runCurrent()
        assertEquals(false, vm.download.value)
        live.value = playlist(keepOffline = true)
        runCurrent()
        assertEquals(true, vm.download.value)
    }

    @Test fun `toggling the switch writes it through the repository`() = runTest {
        val music = musicRepoMock()
        val vm = buildVm(musicRepository = music)
        vm.setDownload(true)
        vm.setDownload(false)
        runCurrent()
        org.mockito.kotlin.verifyBlocking(music) { setPlaylistDownload(1L, true) }
        org.mockito.kotlin.verifyBlocking(music) { setPlaylistDownload(1L, false) }
    }

    @Test fun `a failed toggle says so`() = runTest {
        val music = musicRepoMock().stub {
            onBlocking { setPlaylistDownload(any(), any()) } doThrow RuntimeException("db")
        }
        val vm = buildVm(musicRepository = music)
        val messages = collectMessages(vm)
        vm.setDownload(true)
        runCurrent()
        assertEquals(listOf("Couldn't change that. Try again."), messages)
    }

    private fun playlist(
        type: com.stash.core.model.PlaylistType = com.stash.core.model.PlaylistType.CUSTOM,
        syncEnabled: Boolean = false,
        keepOffline: Boolean = false,
    ) = com.stash.core.model.Playlist(
        id = 1L, name = "Release Radar", source = com.stash.core.model.MusicSource.SPOTIFY,
        type = type, syncEnabled = syncEnabled, keepOffline = keepOffline,
    )

    /** The page's switch for [playlist] in the given mode: null = no switch. */
    private fun kotlinx.coroutines.test.TestScope.downloadState(
        playlist: com.stash.core.model.Playlist,
        streamOnly: Boolean = true,
        shared: com.stash.core.data.db.entity.SharedMixEntity? = null,
    ): Boolean? {
        val music = musicRepoMock().stub { onBlocking { getPlaylistWithTracks(1L) } doReturn playlist }
        val vm = buildVm(
            musicRepository = music,
            streamingPreference = mock {
                onBlocking { current() } doReturn streamOnly
                on { enabled } doReturn flowOf(streamOnly)
            },
            sharedMixRepository = mock { on { observe(any()) } doReturn flowOf(shared) },
        )
        backgroundScope.launch { vm.download.collect {} }
        runCurrent()
        return vm.download.value
    }

    @Test fun `unfollow runs onDone only when it succeeds`() = runTest {
        val shared = mock<com.stash.core.data.share.SharedMixRepository> { on { observe(any()) } doReturn flowOf(null) }
        val vm = buildVm(sharedMixRepository = shared)
        var done = 0
        vm.unfollow { done++ }
        runCurrent()
        assertEquals(1, done)
        org.mockito.kotlin.verifyBlocking(shared) { unfollow(1L) }

        val failing = mock<com.stash.core.data.share.SharedMixRepository> {
            on { observe(any()) } doReturn flowOf(null)
            onBlocking { unfollow(any()) } doThrow RuntimeException("db")
        }
        val vm2 = buildVm(sharedMixRepository = failing)
        val messages = collectMessages(vm2)
        vm2.unfollow { done++ }
        runCurrent()
        assertEquals(1, done)
        assertEquals(listOf("Couldn't unfollow this mix. Try again."), messages)
    }

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
    fun downloadSelected_queues_each_id() = runTest {
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
    fun removeDownloadsForSelected_removes_each_id() = runTest {
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

        // The playlist is created exactly once with the given name…
        verify(musicRepo).createPlaylistWithTracks("My Mix", ids)
    }

    @Test
    fun deleteSelected_removes_each_from_playlist_and_emits_rollup() = runTest {
        val musicRepo = musicRepoMock()
        val vm = buildVm(musicRepository = musicRepo)
        val tracks = listOf(track(1L), track(2L), track(3L))

        val messages = collectMessages(vm)
        vm.deleteSelected(tracks, alsoBlacklist = false)
        runCurrent()

        tracks.forEach { t ->
            verify(musicRepo).removeTrackFromPlaylistAndMaybeDelete(
                trackId = eq(t.id),
                fromPlaylistId = any(),
                alsoBlacklist = eq(false),
            )
        }
        assertEquals(listOf("Removed 3 songs."), messages)
    }

    @Test
    fun deleteSelected_with_blacklist_emits_blocked_rollup() = runTest {
        val musicRepo = musicRepoMock()
        // Every cascade reports a blacklisted destroy.
        whenever(
            musicRepo.removeTrackFromPlaylistAndMaybeDelete(any(), any(), eq(true)),
        ).thenReturn(
            MusicRepository.CascadeRemovalSummary(
                deleted = 1, keptProtected = 0, keptElsewhere = 0, blacklisted = 1,
            ),
        )
        val vm = buildVm(musicRepository = musicRepo)
        val tracks = listOf(track(1L), track(2L))

        val messages = collectMessages(vm)
        vm.deleteSelected(tracks, alsoBlacklist = true)
        runCurrent()

        tracks.forEach { t ->
            verify(musicRepo).removeTrackFromPlaylistAndMaybeDelete(
                trackId = eq(t.id),
                fromPlaylistId = any(),
                alsoBlacklist = eq(true),
            )
        }
        assertEquals(listOf("Removed 2 songs. Blocked from future syncs."), messages)
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
    fun queueDownload_reports_when_single_item_was_not_queued() = runTest {
        val musicRepo = musicRepoMock()
        whenever(musicRepo.queueDownload(eq(1L))).thenReturn(false)
        val vm = buildVm(musicRepository = musicRepo)

        val messages = collectMessages(vm)
        vm.queueDownload(1L)
        runCurrent()

        assertEquals(listOf("Couldn't queue download."), messages)
    }

    @Test
    fun downloadSelected_rethrows_cancellation_and_aborts_batch() = runTest {
        val musicRepo = musicRepoMock()
        // The FIRST item's repo call is cancelled. The batch methods re-throw
        // CancellationException (instead of swallowing it as a per-item failure),
        // so the loop must abort: later items are never attempted and no roll-up
        // is emitted. A swallowed cancellation would continue the loop and emit
        // "Queued N songs for download." — this test pins the re-throw branch.
        whenever(musicRepo.queueDownload(eq(1L)))
            .thenThrow(kotlinx.coroutines.CancellationException("cancelled"))
        val vm = buildVm(musicRepository = musicRepo)
        val ids = listOf(1L, 2L, 3L)

        val messages = collectMessages(vm)
        vm.downloadSelected(ids)
        runCurrent()

        // First item was attempted (and cancelled); the later items were NOT,
        // proving cancellation propagated rather than being absorbed.
        verify(musicRepo).queueDownload(1L)
        verify(musicRepo, org.mockito.kotlin.never()).queueDownload(2L)
        verify(musicRepo, org.mockito.kotlin.never()).queueDownload(3L)
        // No roll-up message: the batch aborted before any emit.
        assertEquals(emptyList<String>(), messages)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun track(id: Long) = Track(id = id, title = "Track $id", artist = "Artist")

    /**
     * Collects [PlaylistDetailViewModel.userMessages] into a list for the life
     * of the test. Mirrors the flow-collection harness in
     * [LikedSongsDetailViewModelTest] / [MixOfflineTapGuardTest].
     */
    private fun kotlinx.coroutines.test.TestScope.collectMessages(
        vm: PlaylistDetailViewModel,
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
        on { getTracksByPlaylist(any()) } doReturn flowOf(emptyList())
        on { observePlaylist(any()) } doReturn flowOf(null)
        on { getUserCreatedPlaylists() } doReturn flowOf(emptyList())
        onBlocking { queueDownload(any()) } doReturn true
        onBlocking {
            removeTrackFromPlaylistAndMaybeDelete(any(), any(), any())
        } doReturn MusicRepository.CascadeRemovalSummary(
            deleted = 0, keptProtected = 0, keptElsewhere = 0, blacklisted = 0,
        )
    }

    /**
     * Builds a [PlaylistDetailViewModel] for tests. Collaborators default to
     * plain mocks with the minimum stubs needed for the VM's eager
     * `userPlaylists` field and `init`/`stateIn` flows to start up without
     * NPEs. Tests pass in their own configured repo mock when they verify
     * against it.
     */
    private fun buildVm(
        playerRepository: PlayerRepository = playerRepoMock(),
        musicRepository: MusicRepository = musicRepoMock(),
        playlistImageHelper: PlaylistImageHelper = mock(),
        streamingPreference: StreamingPreference = mock {
            onBlocking { current() } doReturn true
            on { enabled } doReturn flowOf(true)
        },
        connectivityMonitor: ConnectivityMonitor = mock(),
        recipeDao: StashMixRecipeDao = mock {
            on { observeAll() } doReturn flowOf(emptyList())
        },
        discoveryQueueDao: DiscoveryQueueDao = mock {
            on { observeNonFailedCountsByRecipe() } doReturn flowOf(emptyList())
        },
        savedStateHandle: SavedStateHandle = SavedStateHandle(mapOf("playlistId" to 1L)),
        sharedMixRepository: com.stash.core.data.share.SharedMixRepository = mock {
            on { observe(any()) } doReturn flowOf(null)
        },
    ): PlaylistDetailViewModel = PlaylistDetailViewModel(
        savedStateHandle = savedStateHandle,
        musicRepository = musicRepository,
        playerRepository = playerRepository,
        playlistImageHelper = playlistImageHelper,
        streamingPreference = streamingPreference,
        connectivityMonitor = connectivityMonitor,
        recipeDao = recipeDao,
        discoveryQueueDao = discoveryQueueDao,
        sharedMixRepository = sharedMixRepository,
    )
}
