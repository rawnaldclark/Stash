package com.stash.feature.sync

import com.stash.core.auth.model.AuthState
import com.stash.core.data.sync.DayOfWeekSet
import com.stash.core.data.sync.SyncPhase
import com.stash.core.data.sync.SyncPreferences
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Locks the Refresh-confirm dialog state machine: tapping Refresh on a source
 * that's currently ACCUMULATE opens the confirm dialog (without applying the
 * mode); confirm applies REFRESH to the right source and clears; cancel clears
 * without applying; tapping Refresh when already REFRESH is a no-op.
 *
 * Construction note: SyncViewModel's init eagerly collects ~12 flows. mockk's
 * relaxed Flow stubs throw KotlinNothingValueException when collected, so every
 * eagerly-collected flow is stubbed with a REAL flow in [stubInitFlows]
 * (emptyFlow for plain Flow members, MutableStateFlow for StateFlow members).
 * The WhileSubscribed stateIn flows aren't collected at construction, so their
 * relaxed stubs are fine. Individual tests override prefs.spotifySyncMode to
 * seed the already-REFRESH case before constructing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {

    private val syncScheduler = mockk<com.stash.core.data.sync.SyncScheduler>(relaxed = true)
    private val syncStateManager = mockk<com.stash.core.data.sync.SyncStateManager>(relaxed = true)
    private val prefs = mockk<com.stash.core.data.sync.SyncPreferencesManager>(relaxed = true)
    private val tokenManager = mockk<com.stash.core.auth.TokenManager>(relaxed = true)
    private val syncHistoryDao = mockk<com.stash.core.data.db.dao.SyncHistoryDao>(relaxed = true)
    private val playlistDao = mockk<com.stash.core.data.db.dao.PlaylistDao>(relaxed = true)
    private val syncUndoDao = mockk<com.stash.core.data.db.dao.SyncUndoDao>(relaxed = true)
    // Real instance: it's a plain in-memory holder, so a fake would only
    // restate its behaviour while hiding the flow the VM collects at init.
    private val syncLog = com.stash.core.data.sync.SyncLog()
    private val downloadQueueDao = mockk<com.stash.core.data.db.dao.DownloadQueueDao>(relaxed = true)
    private val musicRepository = mockk<com.stash.core.data.repository.MusicRepository>(relaxed = true)
    private val blocklistGuard = mockk<com.stash.core.data.blocklist.BlocklistGuard>(relaxed = true)
    private val librarySizeHolder = mockk<com.stash.data.download.files.LibrarySizeHolder>(relaxed = true)
    private val streamingPreference = mockk<com.stash.core.data.prefs.StreamingPreference>(relaxed = true)

    /** Stub every flow the VM's init collects eagerly so construction doesn't throw. */
    private fun stubInitFlows() {
        every { syncStateManager.phase } returns MutableStateFlow(SyncPhase.Idle)
        every { prefs.preferences } returns emptyFlow()
        every { prefs.spotifySyncMode } returns emptyFlow()
        every { prefs.youtubeSyncMode } returns emptyFlow()
        every { prefs.youtubeLikedStudioOnly } returns emptyFlow()
        every { prefs.syncDays } returns emptyFlow()
        every { tokenManager.spotifyAuthState } returns MutableStateFlow(AuthState.NotConnected)
        every { tokenManager.youTubeAuthState } returns MutableStateFlow(AuthState.NotConnected)
        every { syncHistoryDao.getRecentSyncs(any()) } returns emptyFlow()
        // init collects the undo point eagerly; a relaxed mock's Flow stub throws
        // KotlinNothingValueException when collected, so give it a real flow.
        every { syncUndoDao.latestPoint() } returns emptyFlow()
        every { playlistDao.getSpotifyPlaylistsForPreferences() } returns emptyFlow()
        every { playlistDao.getYouTubePlaylistsForPreferences() } returns emptyFlow()
        every { playlistDao.getBySource(MusicSource.SOUNDCLOUD) } returns emptyFlow()
        every { musicRepository.getUnmatchedCount() } returns emptyFlow()
        every { musicRepository.getFlaggedCount() } returns emptyFlow()
        every { musicRepository.observeLatestSync() } returns emptyFlow()
        every { musicRepository.getTrackCount() } returns emptyFlow()
        every { musicRepository.getSpotifyDownloadedCount() } returns emptyFlow()
        every { musicRepository.getYouTubeDownloadedCount() } returns emptyFlow()
        every { librarySizeHolder.size } returns MutableStateFlow(mockk(relaxed = true))
        // The first-sync sheet is answered: Sync runs straight away unless a test says otherwise.
        every { prefs.arrivalChosen } returns MutableStateFlow(true)
    }

    private fun newVm(): SyncViewModel = SyncViewModel(
        syncScheduler = syncScheduler,
        syncStateManager = syncStateManager,
        syncPreferencesManager = prefs,
        tokenManager = tokenManager,
        syncHistoryDao = syncHistoryDao,
        playlistDao = playlistDao,
        syncUndoDao = syncUndoDao,
        syncLog = syncLog,
        downloadQueueDao = downloadQueueDao,
        musicRepository = musicRepository,
        blocklistGuard = blocklistGuard,
        librarySizeHolder = librarySizeHolder,
        streamingPreference = streamingPreference,
        lastFmRecommendationSource = mockk(relaxed = true),
        playerRepository = mockk(relaxed = true),
    )

    @Before fun setUp() {
        every { tokenManager.soundCloudSession } returns kotlinx.coroutines.flow.flowOf(null)
        Dispatchers.setMain(UnconfinedTestDispatcher())
        stubInitFlows()
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `requesting Spotify refresh from accumulate opens the dialog without changing mode`() = runTest {
        val vm = newVm()
        vm.onRequestSpotifyRefresh()
        assertEquals(MusicSource.SPOTIFY, vm.uiState.value.pendingRefreshSource)
        coVerify(exactly = 0) { prefs.setSpotifySyncMode(any()) }
    }

    @Test fun `confirming refresh applies REFRESH and clears the dialog`() = runTest {
        val vm = newVm()
        vm.onRequestSpotifyRefresh()
        vm.confirmRefreshMode()
        advanceUntilIdle()
        coVerify { prefs.setSpotifySyncMode(SyncMode.REFRESH) }
        assertNull(vm.uiState.value.pendingRefreshSource)
    }

    @Test fun `cancelling refresh clears the dialog without changing mode`() = runTest {
        val vm = newVm()
        vm.onRequestSpotifyRefresh()
        vm.cancelRefreshMode()
        assertNull(vm.uiState.value.pendingRefreshSource)
        coVerify(exactly = 0) { prefs.setSpotifySyncMode(SyncMode.REFRESH) }
    }

    @Test fun `requesting YouTube refresh from accumulate opens the dialog for YOUTUBE`() = runTest {
        val vm = newVm()
        vm.onRequestYoutubeRefresh()
        assertEquals(MusicSource.YOUTUBE, vm.uiState.value.pendingRefreshSource)
    }

    @Test fun `confirming refresh for YouTube applies REFRESH to youtube`() = runTest {
        val vm = newVm()
        vm.onRequestYoutubeRefresh()
        vm.confirmRefreshMode()
        advanceUntilIdle()
        coVerify { prefs.setYoutubeSyncMode(SyncMode.REFRESH) }
        assertNull(vm.uiState.value.pendingRefreshSource)
    }

    @Test fun `requesting refresh when already REFRESH is a no-op (no dialog)`() = runTest {
        // Seed spotify mode = REFRESH via a hot MutableStateFlow so observeSyncMode
        // collects it during construction (UnconfinedTestDispatcher runs init eagerly).
        every { prefs.spotifySyncMode } returns MutableStateFlow(SyncMode.REFRESH)
        val vm = newVm()
        advanceUntilIdle()
        assertEquals(SyncMode.REFRESH, vm.uiState.value.spotifySyncMode)

        vm.onRequestSpotifyRefresh()
        assertNull(vm.uiState.value.pendingRefreshSource)
    }

    // -- "Shown on Home" pins the mix (2026-09-07) ---------------------------------

    @Test
    fun `showing a mix on Home pins it`() {
        newVm().onToggleHideFromHome(7L, hidden = false)
        coVerify { playlistDao.setHomeVisibility(7L, false, match { it != null }) }
    }

    @Test
    fun `hiding a mix from Home unpins it`() {
        newVm().onToggleHideFromHome(7L, hidden = true)
        coVerify { playlistDao.setHomeVisibility(7L, true, null) }
    }

    // -- The Sync tab's switch decides Home (2026-09-07): sync on pins, sync off unpins --

    @Test
    fun `syncing a playlist pins it to Home`() {
        newVm().onTogglePlaylistSync(7L, enabled = true)
        coVerify { playlistDao.setSyncEnabledAndPin(7L, true, match { it > 0L }) }
    }

    @Test
    fun `turning sync off unpins it`() {
        newVm().onTogglePlaylistSync(7L, enabled = false)
        coVerify { playlistDao.setSyncEnabledAndPin(7L, false, any()) }
    }

    // -- Auto-sync schedule (2026-09-28): Stop keeps it, Auto-sync off ends it ---------

    @Test
    fun `Stop cancels the running sync but keeps the daily schedule`() {
        newVm().onStopSync()
        coVerify { syncScheduler.cancelSync() }
        coVerify(exactly = 0) { syncScheduler.cancelDailySync() }
    }

    @Test
    fun `turning Auto-sync off cancels the daily schedule`() = runTest {
        every { prefs.preferences } returns MutableStateFlow(SyncPreferences(autoSyncEnabled = true))
        val vm = newVm()

        vm.onToggleAutoSync()
        advanceUntilIdle()

        coVerify { prefs.setAutoSyncEnabled(false) }
        coVerify { syncScheduler.cancelDailySync() }
    }

    @Test
    fun `toggling Wi-Fi-only applies the new rule to a scheduled sync still waiting`() = runTest {
        every { prefs.preferences } returns MutableStateFlow(SyncPreferences(wifiOnly = true))
        val vm = newVm()

        vm.onToggleWifiOnly()
        advanceUntilIdle()

        coVerify { prefs.setWifiOnly(false) }
        coVerify { syncScheduler.applyWifiOnlyToWaitingSync(false) }
    }

    // -- "Sync now" offline (2026-10-11): queued until a network returns ----------------

    @Test
    fun `Sync now hands the scheduler the Wi-Fi rule the tab shows`() = runTest {
        every { prefs.preferences } returns MutableStateFlow(SyncPreferences(wifiOnly = false))
        val vm = newVm()

        vm.onSyncNow()
        advanceUntilIdle()

        coVerify { syncScheduler.triggerManualSync(false) }
    }

    @Test
    fun `waiting for a network counts as a sync in progress, so a second tap is ignored`() = runTest {
        every { syncStateManager.phase } returns MutableStateFlow(SyncPhase.WaitingForNetwork)
        val vm = newVm()
        advanceUntilIdle()
        assertEquals(true, vm.uiState.value.isSyncing)

        vm.onSyncNow()
        advanceUntilIdle()

        coVerify(exactly = 0) { syncScheduler.triggerManualSync(any()) }
    }

    // -- Undo last sync (2026-10-11): counts what changed, pauses Auto-sync -----------------

    private val point = com.stash.core.data.db.entity.SyncUndoPointEntity(
        syncId = 9L, createdAtEpochMs = 1_000L, playlistCount = 50, membershipCount = 900,
    )

    @Test
    fun `undo restores, drops a waiting sync, and pauses Auto-sync until Sync now`() = runTest {
        every { prefs.preferences } returns MutableStateFlow(SyncPreferences(autoSyncEnabled = true))
        coEvery { syncUndoDao.latestPointNow() } returns point
        coEvery { syncUndoDao.countChangedPlaylists(9L, 1_000L) } returns 2
        val vm = newVm()

        vm.onUndoLastSync()
        advanceUntilIdle()

        coVerifyOrder {
            syncScheduler.cancelPendingSync()
            syncUndoDao.restore(9L)
            prefs.setAutoSyncPausedAfterUndo(true)
        }
        assertEquals(
            "Undone — 2 playlists are back to how they were before that sync. Auto-sync is paused until you tap Sync.",
            vm.undoResult.value,
        )
    }

    @Test
    fun `a failed undo changes nothing and pauses nothing`() = runTest {
        coEvery { syncUndoDao.latestPointNow() } returns point
        coEvery { syncUndoDao.restore(9L) } throws IllegalStateException("disk I/O")
        val vm = newVm()

        vm.onUndoLastSync()
        advanceUntilIdle()

        assertEquals("Couldn't undo that sync. Nothing was changed.", vm.undoResult.value)
        coVerify(exactly = 0) { prefs.setAutoSyncPausedAfterUndo(any()) }
    }

    @Test
    fun `Sync now resumes Auto-sync paused by an undo`() = runTest {
        every { prefs.preferences } returns MutableStateFlow(SyncPreferences(autoSyncPausedAfterUndo = true))
        val vm = newVm()

        vm.onSyncNow()
        advanceUntilIdle()

        coVerifyOrder {
            prefs.setAutoSyncPausedAfterUndo(false)
            syncScheduler.triggerManualSync(any())
        }
    }

    @Test
    fun `Sync now with nothing paused leaves the pause alone`() = runTest {
        val vm = newVm()

        vm.onSyncNow()
        advanceUntilIdle()

        coVerify(exactly = 0) { prefs.setAutoSyncPausedAfterUndo(any()) }
        coVerify { syncScheduler.triggerManualSync(any()) }
    }

    @Test
    fun `the undo card counts the playlists the last sync changed`() = runTest {
        every { syncUndoDao.latestPoint() } returns MutableStateFlow(point)
        coEvery { syncUndoDao.countChangedPlaylists(9L, 1_000L) } returns 3
        val vm = newVm()
        backgroundScope.launch { vm.undoCard.collect {} }
        advanceUntilIdle()

        assertEquals(UndoCard(syncId = 9L, createdAtEpochMs = 1_000L, changedPlaylists = 3), vm.undoCard.value)
    }

    @Test
    fun `no undo card for a sync that changed nothing`() = runTest {
        every { syncUndoDao.latestPoint() } returns MutableStateFlow(point)
        coEvery { syncUndoDao.countChangedPlaylists(9L, 1_000L) } returns 0
        val vm = newVm()
        backgroundScope.launch { vm.undoCard.collect {} }
        advanceUntilIdle()

        assertNull(vm.undoCard.value)
    }

    @Test
    fun `no undo card while a sync runs`() = runTest {
        every { syncUndoDao.latestPoint() } returns MutableStateFlow(point)
        every { syncStateManager.phase } returns MutableStateFlow(SyncPhase.Diffing(1, 4))
        val vm = newVm()
        backgroundScope.launch { vm.undoCard.collect {} }
        advanceUntilIdle()

        assertNull(vm.undoCard.value)
        coVerify(exactly = 0) { syncUndoDao.countChangedPlaylists(any(), any()) }
    }

    @Test
    fun `changing the days while Auto-sync is on reschedules`() = runTest {
        every { prefs.preferences } returns MutableStateFlow(
            SyncPreferences(autoSyncEnabled = true, syncHour = 7, syncMinute = 30, wifiOnly = false),
        )
        val vm = newVm()

        vm.onSyncDaysChanged(DayOfWeekSet.WEEKENDS.bitmask)
        advanceUntilIdle()

        verify { syncScheduler.scheduleDailySync(7, 30, false, DayOfWeekSet.WEEKENDS) }
    }

    // -- First-sync sheet (Sync tab overhaul, 2026-10-29) --------------------

    /** A new install's first Sync asks how music should arrive, and runs nothing yet. */
    @Test
    fun `a new install's first Sync asks first`() = runTest {
        every { prefs.arrivalChosen } returns MutableStateFlow(false)
        coEvery { syncHistoryDao.getLatest() } returns null
        val vm = newVm()

        vm.onSyncNow()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.showArrivalSheet)
        coVerify(exactly = 0) { syncScheduler.triggerManualSync(any()) }
    }

    /** An upgrading install has synced before: it keeps its mode and switches, and is never asked. */
    @Test
    fun `an install that synced before is never asked`() = runTest {
        every { prefs.arrivalChosen } returns MutableStateFlow(false)
        coEvery { syncHistoryDao.getLatest() } returns mockk(relaxed = true)
        val vm = newVm()

        vm.onSyncNow()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.showArrivalSheet)
        coVerify { prefs.markArrivalChosen() }
        coVerify { syncScheduler.triggerManualSync(any()) }
        coVerify(exactly = 0) { streamingPreference.setEnabled(any()) }
    }

    /** Stream everything: Stream only, new playlists on Home, then the sync, in that order. */
    @Test
    fun `Stream everything switches to Stream only before the sync starts`() = runTest {
        val vm = newVm()

        vm.onArrivalChosen(streamEverything = true)
        advanceUntilIdle()

        coVerifyOrder {
            streamingPreference.setEnabled(true)
            prefs.setArrivalChosen(pinNewPlaylists = true)
            syncScheduler.triggerManualSync(any())
        }
        assertFalse(vm.uiState.value.showArrivalSheet)
        assertNull(vm.uiState.value.openManageFor)
    }

    /** Save to this phone: Download mode, nothing pinned, and the playlists open to pick from. */
    @Test
    fun `Save to this phone picks Download mode and opens the playlists`() = runTest {
        every { tokenManager.spotifyAuthState } returns MutableStateFlow(mockk<AuthState.Connected>(relaxed = true))
        val vm = newVm()

        vm.onArrivalChosen(streamEverything = false)
        advanceUntilIdle()

        coVerifyOrder {
            streamingPreference.setEnabled(false)
            prefs.setArrivalChosen(pinNewPlaylists = false)
            syncScheduler.triggerManualSync(any())
        }
        assertEquals(SyncSource.SPOTIFY, vm.uiState.value.openManageFor)
    }

    @Test fun `SoundCloud public profile is connected and opens its likes for Download mode`() = runTest {
        every { tokenManager.soundCloudSession } returns kotlinx.coroutines.flow.flowOf(
            com.stash.core.auth.soundcloud.SoundCloudSession("7", "Public", connectionId = "current"))
        every { playlistDao.getBySource(MusicSource.SOUNDCLOUD) } returns kotlinx.coroutines.flow.flowOf(emptyList())
        val vm = newVm()
        assertTrue(vm.uiState.value.soundCloudConnected)
        vm.onArrivalChosen(streamEverything = false)
        advanceUntilIdle()
        assertEquals(SyncSource.SOUNDCLOUD, vm.uiState.value.openManageFor)
    }

    /** Save in Stream only keeps the playlist on the phone and leaves its sync switch alone. */
    @Test fun `SoundCloud management includes account playlists and mixes but not another accounts lists`() = runTest {
        every { tokenManager.soundCloudSession } returns kotlinx.coroutines.flow.flowOf(
            com.stash.core.auth.soundcloud.SoundCloudSession("7", "Listener", connectionId = "current"))
        every { playlistDao.getBySource(MusicSource.SOUNDCLOUD) } returns kotlinx.coroutines.flow.flowOf(listOf(
            com.stash.core.data.db.entity.PlaylistEntity(id = 1, name = "My set", sourceId = "soundcloud_playlist_7_11", source = MusicSource.SOUNDCLOUD),
            com.stash.core.data.db.entity.PlaylistEntity(id = 2, name = "Your Mix 1", sourceId = "soundcloud_mix_7_urn", source = MusicSource.SOUNDCLOUD, type = PlaylistType.DAILY_MIX),
            com.stash.core.data.db.entity.PlaylistEntity(id = 3, name = "Another account", sourceId = "soundcloud_playlist_77_11", source = MusicSource.SOUNDCLOUD),
        ))
        assertEquals(listOf(1L, 2L), newVm().uiState.value.soundCloudPlaylists.map { it.id })
    }

    @Test
    fun `Save in Stream only only keeps the playlist on the phone`() = runTest {
        coEvery { streamingPreference.current() } returns true
        val vm = newVm()

        vm.onSavePlaylist(42L, true)
        advanceUntilIdle()

        coVerify { musicRepository.setPlaylistDownload(42L, true) }
        coVerify(exactly = 0) { playlistDao.setSyncEnabledAndPin(any(), any(), any()) }
    }

    /** Save in Download mode also switches the playlist on for sync, as the old switch did. */
    @Test
    fun `Save in Download mode also switches the playlist on for sync`() = runTest {
        coEvery { streamingPreference.current() } returns false
        val vm = newVm()

        vm.onSavePlaylist(42L, true)
        advanceUntilIdle()

        coVerify { playlistDao.setSyncEnabledAndPin(42L, true, any()) }
        coVerify { musicRepository.setPlaylistDownload(42L, true) }
    }
}
