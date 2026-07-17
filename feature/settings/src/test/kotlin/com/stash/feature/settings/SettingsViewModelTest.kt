package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.files.LibrarySizeHolder
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Focused coverage of the qbdlx Settings wiring (Phase 8): the enable toggle,
 * the paste-token field, and the all-dead "expired" badge. The rest of this
 * 30-dependency ViewModel is exercised via its Compose screen + the per-pref
 * unit tests; here we only prove the qbdlx delegations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val qbdlxEnabledFlow = MutableStateFlow(true)
    private val losslessPrefs = mockk<LosslessSourcePreferences>(relaxed = true).also {
        every { it.qbdlxEnabled } returns qbdlxEnabledFlow
    }
    private val qbdlxStore = mockk<QbdlxCredentialStore>(relaxed = true).also {
        coEvery { it.allDead() } returns false
    }
    private val librarySizeHolder = mockk<LibrarySizeHolder>(relaxed = true)

    private fun newVm() = SettingsViewModel(
        appContext = mockk(relaxed = true),
        tokenManager = mockk(relaxed = true),
        musicRepository = mockk(relaxed = true),
        librarySizeHolder = librarySizeHolder,
        qualityPreference = mockk(relaxed = true),
        themePreference = mockk(relaxed = true),
        storagePreference = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        moveLibraryCoordinator = mockk(relaxed = true),
        youTubeCookieHelper = mockk(relaxed = true),
        lastFmApiClient = mockk(relaxed = true),
        lastFmSessionPreference = mockk(relaxed = true),
        lastFmCredentials = mockk(relaxed = true),
        listeningEventDao = mockk(relaxed = true),
        lastFmScrobbler = mockk(relaxed = true),
        youTubeHistoryPreference = mockk(relaxed = true),
        stashMixPreference = mockk(relaxed = true),
        youTubeHistoryScrobbler = mockk(relaxed = true),
        youTubeScrobblerState = mockk(relaxed = true),
        losslessPrefs = losslessPrefs,
        streamingQualityPrefs = mockk(relaxed = true),
        losslessRateLimiter = mockk(relaxed = true),
        qobuzSource = mockk(relaxed = true),
        arcodCredentialStore = mockk(relaxed = true),
        qbdlxCredentialStore = qbdlxStore,
        likePreferences = mockk(relaxed = true),
        trackDao = mockk(relaxed = true),
        settingsDeepLinkController = mockk(relaxed = true),
        crashFileStore = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        crossfadePreference = mockk(relaxed = true),
        databaseBackupManager = mockk(relaxed = true),
    )

    @Test fun `onQbdlxEnabledChange persists via setQbdlxEnabled`() = runTest {
        val vm = newVm()
        vm.onQbdlxEnabledChange(false)
        advanceUntilIdle()
        coVerify { losslessPrefs.setQbdlxEnabled(false) }
    }

    @Test fun `refreshStorageUsage requests a fresh filesystem calculation`() {
        val vm = newVm()

        vm.refreshStorageUsage()

        verify(exactly = 1) { librarySizeHolder.refresh() }
    }

    @Test fun `onQbdlxTokenPaste stores the pasted token`() = runTest {
        val vm = newVm()
        vm.onQbdlxTokenPaste("tok-123")
        advanceUntilIdle()
        coVerify { qbdlxStore.setPastedToken("tok-123") }
    }

    @Test fun `onQbdlxTokenPaste with blank clears the pasted token`() = runTest {
        val vm = newVm()
        vm.onQbdlxTokenPaste("   ")
        advanceUntilIdle()
        coVerify { qbdlxStore.setPastedToken(null) }
    }

    @Test fun `qbdlxExpired reflects allDead after a paste`() = runTest {
        coEvery { qbdlxStore.allDead() } returns true
        val vm = newVm()
        vm.onQbdlxTokenPaste("dead")
        advanceUntilIdle()
        assertThat(vm.qbdlxExpired.value).isTrue()
    }
}
