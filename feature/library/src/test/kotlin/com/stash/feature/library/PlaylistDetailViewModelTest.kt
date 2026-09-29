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
import kotlinx.coroutines.test.advanceTimeBy
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

    // ── The page's Download button (#474) ──────────────────────────────

    @Test fun `stream-only mode shows the button on only for a kept playlist`() = runTest {
        assertEquals(false, downloadButton(playlist(syncEnabled = true), streamOnly = true)?.on)
        assertEquals(true, downloadButton(playlist(keepOffline = true), streamOnly = true)?.on)
    }

    @Test fun `download mode also shows on for a playlist the Sync tab downloads`() = runTest {
        assertEquals(true, downloadButton(playlist(syncEnabled = true), streamOnly = false)?.on)
        assertEquals(true, downloadButton(playlist(keepOffline = true), streamOnly = false)?.on)
        assertEquals(false, downloadButton(playlist(), streamOnly = false)?.on)
    }

    @Test fun `imported mixes and playlists get the button, system mixes do not`() = runTest {
        assertEquals(false, downloadButton(playlist(type = com.stash.core.model.PlaylistType.DAILY_MIX))?.on)
        assertEquals(false, downloadButton(playlist(type = com.stash.core.model.PlaylistType.CUSTOM))?.on)
        assertEquals(null, downloadButton(playlist(type = com.stash.core.model.PlaylistType.STASH_MIX)))
        assertEquals(null, downloadButton(playlist(type = com.stash.core.model.PlaylistType.DOWNLOADS_MIX)))
        assertEquals(null, downloadButton(playlist(type = com.stash.core.model.PlaylistType.LIKED_SONGS)))
    }

    @Test fun `a read-only followed mix has its button in the follow block, on with Download this mix`() = runTest {
        val follower = com.stash.core.data.db.entity.SharedMixEntity(
            1, "Kx7Qa2pL", com.stash.core.data.db.entity.SharedMixEntity.ROLE_FOLLOWER, name = "Ambient",
        )
        assertEquals(DownloadButtonState(on = false, downloaded = 0, total = 0, followed = true), downloadButton(playlist(), shared = follower))
        // Its switch is sync_enabled, in Stream-only mode too.
        assertEquals(true, downloadButton(playlist(syncEnabled = true), streamOnly = true, shared = follower)?.on)
        // Once the owner stops sharing it is an ordinary playlist, with the page's own button.
        val removed = follower.copy(status = com.stash.core.data.db.entity.SharedMixEntity.STATUS_REMOVED)
        assertEquals(DownloadButtonState(on = false, downloaded = 0, total = 0, followed = false), downloadButton(playlist(), shared = removed))
    }

    @Test fun `the button says Download, then Downloading n of m, then Downloaded`() = runTest {
        val someMissing = listOf(track(1L, downloaded = true), track(2L), track(3L))
        assertEquals("Download", downloadButton(playlist(), tracks = someMissing)?.label)

        val downloading = checkNotNull(downloadButton(playlist(keepOffline = true), tracks = someMissing))
        assertEquals(DownloadButtonState(on = true, downloaded = 1, total = 3), downloading)
        assertEquals(false, downloading.complete)
        assertEquals("Downloading 1 of 3", downloading.label)

        val allThere = listOf(track(1L, downloaded = true), track(2L, downloaded = true))
        val done = checkNotNull(downloadButton(playlist(keepOffline = true), tracks = allThere))
        assertEquals(true, done.complete)
        assertEquals("Downloaded", done.label)
    }

    @Test fun `a song that won't download leaves the count, so a finished playlist says Downloaded`() = runTest {
        // 12 songs: 11 downloaded, 1 given up (failed, cancelled or its match dismissed).
        val tracks = (1L..12L).map { track(it, downloaded = it <= 11) }
        val button = checkNotNull(downloadButton(playlist(keepOffline = true), tracks = tracks, givenUp = listOf(12L)))
        assertEquals(DownloadButtonState(on = true, downloaded = 11, total = 11), button)
        assertEquals("Downloaded", button.label)
    }

    @Test fun `a given-up song leaves the total, a pending one still counts`() = runTest {
        // 12 songs: 10 downloaded, 1 given up, 1 pending (say, waiting for Wi-Fi).
        val tracks = (1L..12L).map { track(it, downloaded = it <= 10) }
        val button = checkNotNull(downloadButton(playlist(keepOffline = true), tracks = tracks, givenUp = listOf(11L)))
        assertEquals("Downloading 10 of 11", button.label)
    }

    @Test fun `the counts cover the whole playlist while a search filter is active`() = runTest {
        val music = musicRepoMock().stub {
            on { getTracksByPlaylist(any()) } doReturn flowOf(listOf(track(1L, downloaded = true), track(2L), track(3L)))
            onBlocking { getPlaylistWithTracks(1L) } doReturn playlist(keepOffline = true)
        }
        val vm = buildVm(musicRepository = music)
        backgroundScope.launch { vm.uiState.collect {} }
        backgroundScope.launch { vm.downloadButton.collect {} }
        vm.toggleSearch()
        vm.onSearchQueryChanged("Track 2")
        advanceTimeBy(1_000) // past the filter's debounce
        runCurrent()
        assertEquals(listOf(2L), vm.uiState.value.tracks.map { it.id }) // the list is filtered...
        assertEquals(DownloadButtonState(on = true, downloaded = 1, total = 3), vm.downloadButton.value) // ...the button is not
    }

    @Test fun `a tap turns a playlist's download on or off, and says what happened`() = runTest {
        val someMissing = listOf(track(1L, downloaded = true), track(2L), track(3L))
        val allThere = listOf(track(1L, downloaded = true))
        // Off: on, with the number of songs still to come. On: off, keeping what's downloaded.
        assertEquals(true to "Downloading 2 songs", tapPlaylist(playlist(), someMissing))
        assertEquals(false to "Stopped. Downloaded songs stay on your phone.", tapPlaylist(playlist(keepOffline = true), someMissing))
        assertEquals(true to "Every song is already on your phone.", tapPlaylist(playlist(), allThere))
    }

    @Test fun `a tap on a followed mix's button goes through the shared-mix repository`() = runTest {
        val follower = com.stash.core.data.db.entity.SharedMixEntity(
            1, "Kx7Qa2pL", com.stash.core.data.db.entity.SharedMixEntity.ROLE_FOLLOWER, name = "Ambient",
        )
        for (wasOn in listOf(false, true)) {
            val shared = mock<com.stash.core.data.share.SharedMixRepository> { on { observe(any()) } doReturn flowOf(follower) }
            val music = musicRepoMock().stub { onBlocking { getPlaylistWithTracks(1L) } doReturn playlist(syncEnabled = wasOn) }
            val vm = buildVm(musicRepository = music, sharedMixRepository = shared)
            backgroundScope.launch { vm.downloadButton.collect {} }
            runCurrent()
            vm.toggleDownload()
            runCurrent()
            org.mockito.kotlin.verifyBlocking(shared) { setDownload(1L, !wasOn) }
            org.mockito.kotlin.verifyBlocking(music, org.mockito.kotlin.never()) { setPlaylistDownload(any(), any()) }
        }
    }

    @Test fun `the button follows the playlist row live`() = runTest {
        val live = MutableStateFlow<com.stash.core.model.Playlist?>(null)
        val music = musicRepoMock().stub {
            on { observePlaylist(any()) } doReturn live
            onBlocking { getPlaylistWithTracks(1L) } doReturn playlist()
        }
        val vm = buildVm(musicRepository = music)
        backgroundScope.launch { vm.downloadButton.collect {} }
        runCurrent()
        assertEquals(false, vm.downloadButton.value?.on)
        live.value = playlist(keepOffline = true)
        runCurrent()
        assertEquals(true, vm.downloadButton.value?.on)
    }

    @Test fun `a failed tap says so`() = runTest {
        val music = musicRepoMock().stub {
            onBlocking { getPlaylistWithTracks(1L) } doReturn playlist()
            onBlocking { setPlaylistDownload(any(), any()) } doThrow RuntimeException("db")
        }
        val vm = buildVm(musicRepository = music)
        backgroundScope.launch { vm.downloadButton.collect {} }
        val messages = collectMessages(vm)
        vm.toggleDownload()
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

    /**
     * The page's button for [playlist] holding [tracks], in the given mode: null = no button.
     * [givenUp]: the songs that won't download unless the user acts (failed, cancelled, dismissed).
     */
    private fun kotlinx.coroutines.test.TestScope.downloadButton(
        playlist: com.stash.core.model.Playlist,
        streamOnly: Boolean = true,
        shared: com.stash.core.data.db.entity.SharedMixEntity? = null,
        tracks: List<Track> = emptyList(),
        givenUp: List<Long> = emptyList(),
    ): DownloadButtonState? {
        val music = musicRepoMock().stub {
            onBlocking { getPlaylistWithTracks(1L) } doReturn playlist
            on { getTracksByPlaylist(any()) } doReturn flowOf(tracks)
        }
        val vm = buildVm(
            musicRepository = music,
            streamingPreference = mock {
                onBlocking { current() } doReturn streamOnly
                on { enabled } doReturn flowOf(streamOnly)
            },
            sharedMixRepository = mock { on { observe(any()) } doReturn flowOf(shared) },
            downloadQueueDao = mock { on { observeGivenUpTrackIds(1L) } doReturn flowOf(givenUp) },
        )
        backgroundScope.launch { vm.downloadButton.collect {} }
        runCurrent()
        return vm.downloadButton.value
    }

    /** One tap on [playlist]'s button: the value it wrote through setPlaylistDownload, and the message. */
    private fun kotlinx.coroutines.test.TestScope.tapPlaylist(
        playlist: com.stash.core.model.Playlist,
        tracks: List<Track>,
    ): Pair<Boolean, String> {
        val music = musicRepoMock().stub {
            onBlocking { getPlaylistWithTracks(1L) } doReturn playlist
            on { getTracksByPlaylist(any()) } doReturn flowOf(tracks)
        }
        val vm = buildVm(musicRepository = music)
        backgroundScope.launch { vm.downloadButton.collect {} }
        val messages = collectMessages(vm)
        vm.toggleDownload()
        runCurrent()
        val written = org.mockito.kotlin.argumentCaptor<Boolean>()
        org.mockito.kotlin.verifyBlocking(music) { setPlaylistDownload(eq(1L), written.capture()) }
        return written.allValues.single() to messages.single()
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

    private fun track(id: Long, downloaded: Boolean = false) =
        Track(id = id, title = "Track $id", artist = "Artist", isDownloaded = downloaded)

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
        downloadQueueDao: com.stash.core.data.db.dao.DownloadQueueDao = mock {
            on { observeGivenUpTrackIds(any()) } doReturn flowOf(emptyList())
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
        downloadQueueDao = downloadQueueDao,
    )
}
