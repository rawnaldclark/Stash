package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.files.LibrarySizeHolder
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.data.download.lossless.relay.LosslessRelayClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Focused coverage of the lossless Settings wiring: the "no lossless path
 * configured" badge. The rest of this 30-dependency ViewModel is exercised via
 * its Compose screen + the per-pref unit tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val losslessPrefs = mockk<LosslessSourcePreferences>(relaxed = true)
    private val qbdlxStore = mockk<QbdlxCredentialStore>(relaxed = true)
    private val librarySizeHolder = mockk<LibrarySizeHolder>(relaxed = true)
    private val relayClient = mockk<LosslessRelayClient>(relaxed = true)

    private fun newVm(losslessConfigured: Boolean = true) = SettingsViewModel(
        appContext = mockk(relaxed = true),
        tokenManager = mockk(relaxed = true),
        musicRepository = mockk(relaxed = true),
        librarySizeHolder = librarySizeHolder,
        qualityPreference = mockk(relaxed = true),
        themePreference = mockk(relaxed = true),
        storagePreference = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        moveLibraryCoordinator = mockk(relaxed = true),
        reorganizeLibraryCoordinator = mockk(relaxed = true),
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
        losslessAvailability = mockk<LosslessAvailability> {
            every { qbdlxEnabled } returns flowOf(losslessConfigured)
            every { routingRows } returns flowOf(emptyList())
        },
        qobuzAccountConnector = mockk(relaxed = true),
        likePreferences = mockk(relaxed = true),
        trackDao = mockk(relaxed = true),
        settingsDeepLinkController = mockk(relaxed = true),
        crashFileStore = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        crossfadePreference = mockk(relaxed = true),
        databaseBackupManager = mockk(relaxed = true),
        sleepTimerController = mockk(relaxed = true),
        homeDiscoveryPreference = mockk(relaxed = true),
        nowPlayingPreference = mockk(relaxed = true),
        homeSectionsPreference = mockk(relaxed = true),
        listenBrainzPreference = mockk(relaxed = true),
        listenBrainzApiClient = mockk(relaxed = true),
        listenSinkCoordinator = mockk(relaxed = true),
        listenSubmissionDao = mockk(relaxed = true),
        relayClient = relayClient,
    )

    @Test fun `refreshStorageUsage requests a fresh filesystem calculation`() {
        val vm = newVm()

        vm.refreshStorageUsage()

        verify(exactly = 1) { librarySizeHolder.refresh() }
    }

    @Test fun `qbdlxExpired is true when no lossless path is configured`() = runTest {
        val vm = newVm(losslessConfigured = false)
        // WhileSubscribed: the flow only runs while collected.
        val job = launch { vm.qbdlxExpired.collect {} }
        advanceUntilIdle()
        assertThat(vm.qbdlxExpired.value).isTrue()
        job.cancel()
    }

    @Test fun `qbdlxExpired is false once any lossless path is configured`() = runTest {
        val vm = newVm(losslessConfigured = true)
        val job = launch { vm.qbdlxExpired.collect {} }
        advanceUntilIdle()
        assertThat(vm.qbdlxExpired.value).isFalse()
        job.cancel()
    }

    // -- Custom lossless endpoint --------------------------------------------

    @Test fun `committing a valid endpoint stores it normalised`() = runTest {
        val vm = newVm()

        vm.onCustomEndpointCommitted("https://relay.example/")
        advanceUntilIdle()

        coVerify(exactly = 1) { losslessPrefs.setCustomLosslessEndpoint("https://relay.example") }
        assertThat(vm.customEndpointError.value).isNull()
    }

    @Test fun `committing a non-https endpoint errors and stores nothing`() = runTest {
        val vm = newVm()

        vm.onCustomEndpointCommitted("http://x")
        advanceUntilIdle()

        assertThat(vm.customEndpointError.value).isEqualTo("Must be an https:// URL")
        coVerify(exactly = 0) { losslessPrefs.setCustomLosslessEndpoint(any()) }
    }

    @Test fun `committing blank clears the endpoint without an error`() = runTest {
        val vm = newVm()
        vm.onCustomEndpointCommitted("http://x")
        assertThat(vm.customEndpointError.value).isNotNull()

        vm.onCustomEndpointCommitted("")
        advanceUntilIdle()

        assertThat(vm.customEndpointError.value).isNull()
        coVerify(exactly = 1) { losslessPrefs.setCustomLosslessEndpoint(null) }
    }

    @Test fun `testing an endpoint goes IDLE to TESTING to REACHABLE`() = runTest {
        every { losslessPrefs.customLosslessEndpoint } returns flowOf("https://relay.example")
        coEvery { relayClient.probe("https://relay.example") } returns true
        val vm = newVm()
        val job = launch { vm.customEndpoint.collect {} }
        advanceUntilIdle()
        assertThat(vm.customEndpointTest.value).isEqualTo(SettingsViewModel.EndpointTestState.IDLE)

        vm.onTestCustomEndpoint()
        assertThat(vm.customEndpointTest.value).isEqualTo(SettingsViewModel.EndpointTestState.TESTING)
        advanceUntilIdle()

        assertThat(vm.customEndpointTest.value).isEqualTo(SettingsViewModel.EndpointTestState.REACHABLE)
        job.cancel()
    }

    @Test fun `a probe that fails reports UNREACHABLE`() = runTest {
        every { losslessPrefs.customLosslessEndpoint } returns flowOf("https://relay.example")
        coEvery { relayClient.probe("https://relay.example") } returns false
        val vm = newVm()
        val job = launch { vm.customEndpoint.collect {} }
        advanceUntilIdle()

        vm.onTestCustomEndpoint()
        advanceUntilIdle()

        assertThat(vm.customEndpointTest.value).isEqualTo(SettingsViewModel.EndpointTestState.UNREACHABLE)
        job.cancel()
    }

    @Test fun `testing with no endpoint set is a no-op`() = runTest {
        every { losslessPrefs.customLosslessEndpoint } returns flowOf(null)
        val vm = newVm()
        val job = launch { vm.customEndpoint.collect {} }
        advanceUntilIdle()

        vm.onTestCustomEndpoint()
        advanceUntilIdle()

        assertThat(vm.customEndpointTest.value).isEqualTo(SettingsViewModel.EndpointTestState.IDLE)
        coVerify(exactly = 0) { relayClient.probe(any()) }
        job.cancel()
    }

    /**
     * The Test button is disabled while a probe runs but the FIELD is not, and a
     * probe takes up to 8s: without the identity check in `onTestCustomEndpoint`,
     * endpoint A's verdict came back and painted a green "Reachable" beside the
     * endpoint B now on screen — a measurement of a URL the user had replaced.
     */
    @Test fun `a probe answered after the endpoint changed cannot label the new one`() = runTest {
        val stored = MutableStateFlow<String?>("https://a.example")
        every { losslessPrefs.customLosslessEndpoint } returns stored
        coEvery { losslessPrefs.setCustomLosslessEndpoint(any()) } coAnswers {
            stored.value = firstArg<String?>()
        }
        val probeA = CompletableDeferred<Boolean>()
        coEvery { relayClient.probe("https://a.example") } coAnswers { probeA.await() }
        val vm = newVm()
        val job = launch { vm.customEndpoint.collect {} }
        advanceUntilIdle()

        vm.onTestCustomEndpoint()
        advanceUntilIdle()
        assertThat(vm.customEndpointTest.value).isEqualTo(SettingsViewModel.EndpointTestState.TESTING)

        // The user retypes the base while A is still in flight.
        vm.onCustomEndpointCommitted("https://b.example")
        advanceUntilIdle()
        assertThat(vm.customEndpointTest.value).isEqualTo(SettingsViewModel.EndpointTestState.IDLE)

        probeA.complete(true) // A finally answers, with B on screen.
        advanceUntilIdle()

        assertThat(vm.customEndpointTest.value).isEqualTo(SettingsViewModel.EndpointTestState.IDLE)
        job.cancel()
    }
}
