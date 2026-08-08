package com.stash.feature.sync

import com.stash.core.auth.model.AuthState
import com.stash.core.data.sync.SyncPhase
import com.stash.core.model.MusicSource
import com.stash.core.model.SyncMode
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private val playbackModePreference = mockk<com.stash.core.data.prefs.PlaybackModePreference>(relaxed = true)

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
        every { musicRepository.getUnmatchedCount() } returns emptyFlow()
        every { musicRepository.getFlaggedCount() } returns emptyFlow()
        every { musicRepository.observeLatestSync() } returns emptyFlow()
        every { musicRepository.getTrackCount() } returns emptyFlow()
        every { musicRepository.getSpotifyDownloadedCount() } returns emptyFlow()
        every { musicRepository.getYouTubeDownloadedCount() } returns emptyFlow()
        every { librarySizeHolder.size } returns MutableStateFlow(mockk(relaxed = true))
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
        playbackModePreference = playbackModePreference,
    )

    @Before fun setUp() {
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
}
