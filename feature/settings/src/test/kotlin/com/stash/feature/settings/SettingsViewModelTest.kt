package com.stash.feature.settings

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.HomeSectionsPreference
import com.stash.core.data.prefs.StartTab
import com.stash.data.download.files.LibrarySizeHolder
import com.stash.data.download.lossless.LosslessAvailability
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.qbdlx.QbdlxCredentialStore
import com.stash.core.data.discord.DiscordRpcCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
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

    private val autoplayRadioPreference = mockk<com.stash.core.data.prefs.AutoplayRadioPreference>(relaxed = true)
    private val homeSectionsPreference = mockk<HomeSectionsPreference>(relaxed = true)
    private val storagePreference = mockk<com.stash.core.data.prefs.StoragePreference>(relaxed = true)

    private val likePreferences = mockk<com.stash.core.data.prefs.LikePreferences>(relaxed = true)

    private var themePreference: com.stash.core.data.prefs.ThemePreference = mockk(relaxed = true)

    private val tokenManager = mockk<com.stash.core.auth.TokenManager>(relaxed = true)
    private val likeCoordinator = mockk<com.stash.core.data.social.LikeCoordinator>(relaxed = true)
    private val syncStateManager = com.stash.core.data.sync.SyncStateManager()
    private var syncPreferences: com.stash.core.data.sync.SyncPreferencesManager = mockk(relaxed = true)
    private val spotifyAccountDao = mockk<com.stash.core.data.db.dao.SpotifyAccountDao>(relaxed = true)
    private val spotifyExtraAccountRemover = mockk<com.stash.core.data.sync.SpotifyExtraAccountRemover>(relaxed = true)

    private fun newVm(losslessConfigured: Boolean = true) = SettingsViewModel(
        appContext = mockk(relaxed = true),
        tokenManager = tokenManager,
        musicRepository = mockk(relaxed = true),
        librarySizeHolder = librarySizeHolder,
        qualityPreference = mockk(relaxed = true),
        themePreference = themePreference,
        storagePreference = storagePreference,
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
        qbdlxCredentialStore = qbdlxStore,
        losslessAvailability = mockk<LosslessAvailability> {
            every { anySourceConfigured } returns flowOf(losslessConfigured)
            every { routingRows } returns flowOf(emptyList())
        },
        qobuzAccountConnector = mockk(relaxed = true),
        likePreferences = likePreferences,
        trackDao = mockk(relaxed = true),
        settingsDeepLinkController = mockk(relaxed = true),
        crashFileStore = mockk(relaxed = true),
        streamingPreference = mockk(relaxed = true),
        crossfadePreference = mockk(relaxed = true),
        autoplayRadioPreference = autoplayRadioPreference,
        headphonePreference = mockk(relaxed = true),
        databaseBackupManager = mockk(relaxed = true),
        sleepTimerController = mockk(relaxed = true),
        homeDiscoveryPreference = mockk(relaxed = true),
        nowPlayingPreference = mockk(relaxed = true),
        homeSectionsPreference = homeSectionsPreference,
        listenBrainzPreference = mockk(relaxed = true),
        listenBrainzApiClient = mockk(relaxed = true),
        listenSinkCoordinator = mockk(relaxed = true),
        listenSubmissionDao = mockk(relaxed = true),
        lastFmRecommendationSource = mockk(relaxed = true),
        discordRpcCoordinator = mockk(relaxed = true),
        syncStateManager = syncStateManager,
        likeCoordinator = likeCoordinator,
        syncPreferences = syncPreferences,
        spotifyAccountDao = spotifyAccountDao,
        spotifyExtraAccountRemover = spotifyExtraAccountRemover,
    )

    // -- Display size (UI scale) ---------------------------------------------

    @Test fun `the UI scale starts at the applied scale, not 1_00, before the store answers`() {
        // A store that hasn't emitted yet: the first frame must still show the real scale.
        themePreference = mockk(relaxed = true) {
            every { uiScaleNow } returns 1.3f
            every { uiScale } returns kotlinx.coroutines.flow.emptyFlow()
        }

        assertThat(newVm().uiScale.value).isEqualTo(1.3f)
    }

    @Test fun `an off-grid snapshot seeds as a value the slider can show`() {
        themePreference = mockk(relaxed = true) {
            every { uiScaleNow } returns 0.5f
            every { uiScale } returns kotlinx.coroutines.flow.emptyFlow()
        }

        assertThat(newVm().uiScale.value).isEqualTo(0.85f)
    }

    @Test fun `the store's value replaces the seed once it lands`() = runTest {
        themePreference = mockk(relaxed = true) {
            every { uiScaleNow } returns 1.3f
            every { uiScale } returns flowOf(1.1f)
        }
        val vm = newVm()
        val job = launch { vm.uiScale.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiScale.value).isEqualTo(1.1f)
        job.cancel()
    }

    @Test fun `committing a scale hands it to the store`() = runTest {
        val vm = newVm()

        vm.onUiScaleChanged(1.25f)
        advanceUntilIdle()

        coVerify(exactly = 1) { themePreference.setUiScale(1.25f) }
    }

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

    // -- The user's own Tidal (BitChord) server ------------------------------

    @Test fun `committing a valid Tidal server stores it normalised`() = runTest {
        val vm = newVm()

        vm.onBitchordEndpointCommitted("https://tidal.example/")
        advanceUntilIdle()

        coVerify(exactly = 1) { losslessPrefs.setBitchordEndpoint("https://tidal.example") }
        assertThat(vm.bitchordEndpointError.value).isNull()
    }

    @Test fun `committing a non-https Tidal server errors and stores nothing`() = runTest {
        val vm = newVm()

        vm.onBitchordEndpointCommitted("http://x")
        advanceUntilIdle()

        assertThat(vm.bitchordEndpointError.value).isEqualTo("Must be an https:// URL")
        coVerify(exactly = 0) { losslessPrefs.setBitchordEndpoint(any()) }
    }

    @Test fun `committing blank clears the Tidal server without an error`() = runTest {
        val vm = newVm()
        vm.onBitchordEndpointCommitted("http://x")
        assertThat(vm.bitchordEndpointError.value).isNotNull()

        vm.onBitchordEndpointCommitted("")
        advanceUntilIdle()

        assertThat(vm.bitchordEndpointError.value).isNull()
        coVerify(exactly = 1) { losslessPrefs.setBitchordEndpoint(null) }
    }

    // -- Autoplay radio toggle (2026-09-07) --------------------------------------------

    @Test
    fun `toggling autoplay radio persists the choice`() = runTest {
        newVm().onAutoplayRadioToggle(true)
        advanceUntilIdle()
        coVerify { autoplayRadioPreference.setEnabled(true) }
    }

    // -- Open Stash on (#428) ------------------------------------------------------------

    @Test
    fun `choosing a start tab stores it and the screen shows it`() = runTest {
        val stored = MutableStateFlow(StartTab.HOME)
        every { homeSectionsPreference.startTab } returns stored
        coEvery { homeSectionsPreference.setStartTab(any()) } coAnswers { stored.value = firstArg() }
        val vm = newVm()
        val job = launch { vm.startTab.collect {} }
        advanceUntilIdle()
        assertThat(vm.startTab.value).isEqualTo(StartTab.HOME)

        vm.onStartTabChanged(StartTab.LIBRARY)
        advanceUntilIdle()

        coVerify(exactly = 1) { homeSectionsPreference.setStartTab(StartTab.LIBRARY) }
        assertThat(vm.startTab.value).isEqualTo(StartTab.LIBRARY)
        job.cancel()
    }

    // -- Home layout: a drag's drop, and TalkBack's one-place moves -----------------------------

    @Test
    fun `a dropped home section is saved once, to the row it was dropped on`() = runTest {
        val vm = newVm()
        vm.onHomeSectionDropped(com.stash.core.data.prefs.HomeSection.RADIOS, to = 0)
        vm.onHomeSectionMoved(com.stash.core.data.prefs.HomeSection.DISCOVER, up = false)
        advanceUntilIdle()
        coVerify(exactly = 1) { homeSectionsPreference.move(com.stash.core.data.prefs.HomeSection.RADIOS, to = 0) }
        coVerify(exactly = 1) { homeSectionsPreference.move(com.stash.core.data.prefs.HomeSection.DISCOVER, up = false) }
    }

    // -- Restore: folder re-grant (A9) ---------------------------------------------------

    /**
     * After a full restore the files are already swapped under the live Room and
     * DataStore singletons. Cancelling the folder picker used to return to
     * Settings with no restart, and backing out of Settings during the
     * "Restarting..." message cancelled the restart with the ViewModel.
     */
    @Test
    fun `cancelling the restore folder re-grant still restarts, even once the screen is gone`() = runTest {
        val vm = newVm()
        var restarts = 0
        vm.restartProcess = { restarts++ }

        vm.onRestoreFolderRegrantResult(null)
        vm.viewModelScope.cancel() // the user leaves Settings during the message
        advanceUntilIdle()

        assertThat(restarts).isEqualTo(1)
    }

    /**
     * The picked folder must never go through the stale in-memory DataStore: its
     * edit would write pre-restore values back over the restored prefs file.
     */
    @Test
    fun `a re-granted folder restarts without writing through the stale storage prefs`() = runTest {
        val vm = newVm()
        var restarts = 0
        vm.restartProcess = { restarts++ }

        vm.onRestoreFolderRegrantResult(mockk<android.net.Uri>(relaxed = true))
        advanceUntilIdle()

        assertThat(restarts).isEqualTo(1)
        coVerify(exactly = 0) { storagePreference.setExternalTreeUri(any()) }
    }

    @Test fun `turning on Last_fm loves needs no unofficial-endpoint warning`() = runTest {
        val vm = newVm()

        vm.onMirrorToggleRequested(com.stash.core.data.social.Destination.LAST_FM, true)
        advanceUntilIdle()

        coVerify { likePreferences.setMirrorLikesLastFm(true) }
        assertThat(vm.uiState.value.pendingMirrorWarning).isNull()
    }

    @Test fun `turning on Spotify likes waits for the warning`() = runTest {
        val vm = newVm()

        vm.onMirrorToggleRequested(com.stash.core.data.social.Destination.SPOTIFY, true)
        advanceUntilIdle()

        coVerify(exactly = 0) { likePreferences.setMirrorLikesSpotify(any()) }
    }

    // -- Spotify account-swap guard ------------------------------------------
    // A sign-in as another Spotify account used to replace the connected one silently.

    private fun spotifySessionExpired() = syncStateManager.onAuthExpiryProbed(
        com.stash.core.data.sync.AuthExpiryState(spotifyExpired = true, youtubeExpired = false),
    )

    @Test fun `a sign-in as another Spotify account is refused with both names, and nothing else moves`() = runTest {
        spotifySessionExpired()
        coEvery { tokenManager.connectSpotifyWithCookie("sam-cookie", "") } returns
            com.stash.core.auth.model.SpotifyConnectResult.DifferentAccount(connectedName = "Rawn", attemptedName = "Sam")
        val vm = newVm()

        // The card's Reconnect: the WebView hands over the cookie it captured.
        vm.onSpotifyWebLoginCookieExtracted("sam-cookie")
        advanceUntilIdle()

        assertThat(vm.spotifyAccountMismatch.value).isEqualTo(SpotifyAccountMismatch("Rawn", "Sam"))
        // Rawn's session still needs Rawn's sign-in: Reconnect stays offered, his likes keep waiting.
        assertThat(syncStateManager.authExpiry.value.spotifyExpired).isTrue()
        coVerify(exactly = 0) { likeCoordinator.retry(any()) }
    }

    @Test fun `two Spotify accounts with one display name - the message doesn't name the other`() = runTest {
        coEvery { tokenManager.connectSpotifyWithCookie(any(), any()) } returns
            com.stash.core.auth.model.SpotifyConnectResult.DifferentAccount(connectedName = "Rawn", attemptedName = " rawn")
        val vm = newVm()

        vm.onConnectSpotifyWithCookie("sam-cookie", "")
        advanceUntilIdle()

        assertThat(vm.spotifyAccountMismatch.value).isEqualTo(SpotifyAccountMismatch("Rawn", attemptedName = null))
    }

    @Test fun `the connected Spotify account signing in again connects as before`() = runTest {
        spotifySessionExpired()
        coEvery { tokenManager.connectSpotifyWithCookie("rawn-cookie", "") } returns
            com.stash.core.auth.model.SpotifyConnectResult.Connected
        val vm = newVm()

        vm.onSpotifyWebLoginCookieExtracted("rawn-cookie")
        advanceUntilIdle()

        assertThat(vm.spotifyAccountMismatch.value).isNull()
        assertThat(syncStateManager.authExpiry.value.spotifyExpired).isFalse()
        coVerify(exactly = 1) { likeCoordinator.retry(com.stash.core.data.social.Destination.SPOTIFY) }
    }

    @Test fun `an invalid Spotify cookie is not an account mismatch`() = runTest {
        spotifySessionExpired()
        coEvery { tokenManager.connectSpotifyWithCookie(any(), any()) } returns
            com.stash.core.auth.model.SpotifyConnectResult.Invalid
        val vm = newVm()

        vm.onConnectSpotifyWithCookie("bad-cookie", "")
        advanceUntilIdle()

        assertThat(vm.spotifyAccountMismatch.value).isNull()
        assertThat(syncStateManager.authExpiry.value.spotifyExpired).isTrue()
        coVerify(exactly = 0) { likeCoordinator.retry(any()) }
    }

    @Test fun `Cancel drops a Spotify sign-in still being checked`() = runTest {
        var dropped = false
        coEvery { tokenManager.connectSpotifyWithCookie(any(), any()) } coAnswers {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                dropped = true
            }
        }
        spotifySessionExpired()
        val vm = newVm()
        vm.onConnectSpotifyWithCookie("rawn-cookie", "")
        advanceUntilIdle()

        vm.onDismissSpotifyCookieDialog()
        advanceUntilIdle()

        assertThat(dropped).isTrue()
        // Nothing connected, so nothing is settled.
        assertThat(syncStateManager.authExpiry.value.spotifyExpired).isTrue()
        assertThat(vm.spotifyAccountMismatch.value).isNull()
        coVerify(exactly = 0) { likeCoordinator.retry(any()) }
    }

    @Test fun `the Disconnect confirmation knows when Spotify syncs in Refresh mode`() = runTest {
        syncPreferences = mockk(relaxed = true) {
            every { spotifySyncMode } returns flowOf(com.stash.core.model.SyncMode.REFRESH)
        }
        val vm = newVm()
        val job = launch { vm.spotifyRefreshMode.collect {} }
        advanceUntilIdle()

        assertThat(vm.spotifyRefreshMode.value).isTrue()
        job.cancel()
    }

    @Test fun `in Accumulate mode the Disconnect confirmation stays as it was`() = runTest {
        syncPreferences = mockk(relaxed = true) {
            every { spotifySyncMode } returns flowOf(com.stash.core.model.SyncMode.ACCUMULATE)
        }
        val vm = newVm()
        val job = launch { vm.spotifyRefreshMode.collect {} }
        advanceUntilIdle()

        assertThat(vm.spotifyRefreshMode.value).isFalse()
        job.cancel()
    }

    @Test fun `dismissing the Spotify account message clears it`() = runTest {
        coEvery { tokenManager.connectSpotifyWithCookie(any(), any()) } returns
            com.stash.core.auth.model.SpotifyConnectResult.DifferentAccount(connectedName = "Rawn", attemptedName = "Sam")
        val vm = newVm()
        vm.onConnectSpotifyWithCookie("sam-cookie", "")
        advanceUntilIdle()

        vm.onDismissSpotifyAccountMismatch()

        assertThat(vm.spotifyAccountMismatch.value).isNull()
    }

    // -- Other Spotify accounts (plan 2026-11-04 §3.4/§3.5) --------------------------------------

    private fun otherAccount(id: Long, userId: String, label: String) =
        com.stash.core.data.db.entity.SpotifyAccountEntity(id = id, userId = userId, label = label, addedAt = id)

    /**
     * A cookie that signs in as [userId] (null: Spotify didn't say whose). [ValidatedSpotifySignIn]
     * can only be made by core:auth.
     */
    private fun signedInAs(userId: String?, isMain: Boolean? = false): com.stash.core.auth.model.SpotifyCookieCheck.Valid {
        val signIn = mockk<com.stash.core.auth.model.ValidatedSpotifySignIn> {
            every { account } returns userId?.let { com.stash.core.auth.spotify.SpotifyProfile(it, it.replaceFirstChar { c -> c.uppercase() }) }
        }
        return com.stash.core.auth.model.SpotifyCookieCheck.Valid(signIn, isMain)
    }

    @Test fun `a Connect as one of the other accounts is refused, and nothing is connected`() = runTest {
        coEvery { spotifyAccountDao.count() } returns 1
        coEvery { tokenManager.validateSpotifyCookie("sam-cookie") } returns signedInAs("sam")
        coEvery { spotifyAccountDao.findByAccount("sam") } returns otherAccount(4, "sam", "Sam")
        val vm = newVm()

        vm.onConnectSpotifyWithCookie("sam-cookie", "")
        advanceUntilIdle()

        assertThat(vm.spotifyAlreadyOtherAccount.value).isEqualTo("Sam")
        coVerify(exactly = 0) { tokenManager.connectSpotifyWithCookie(any(), any()) }
        coVerify(exactly = 0) { likeCoordinator.retry(any()) }

        vm.onDismissSpotifyAlreadyOtherAccount()
        assertThat(vm.spotifyAlreadyOtherAccount.value).isNull()
    }

    @Test fun `with other accounts, a Connect as a new account connects from the one check`() = runTest {
        coEvery { spotifyAccountDao.count() } returns 1
        val check = signedInAs("rawn", isMain = true)
        coEvery { tokenManager.validateSpotifyCookie("rawn-cookie") } returns check
        coEvery { spotifyAccountDao.findByAccount("rawn") } returns null
        coEvery { tokenManager.connectSpotifyWithSignIn(any(), any()) } returns
            com.stash.core.auth.model.SpotifyConnectResult.Connected
        val vm = newVm()

        vm.onConnectSpotifyWithCookie("rawn-cookie", "")
        advanceUntilIdle()

        assertThat(vm.spotifyAlreadyOtherAccount.value).isNull()
        // One token exchange: the check's. A second one (a 429 away from "invalid") is never made.
        coVerify(exactly = 1) { tokenManager.validateSpotifyCookie(any()) }
        coVerify(exactly = 1) { tokenManager.connectSpotifyWithSignIn(check.signIn, "") }
        coVerify(exactly = 0) { tokenManager.connectSpotifyWithCookie(any(), any()) }
        coVerify(exactly = 1) { likeCoordinator.retry(com.stash.core.data.social.Destination.SPOTIFY) }
    }

    @Test fun `with other accounts, a sign-in Spotify didn't name still connects, as the guard lets it`() = runTest {
        coEvery { spotifyAccountDao.count() } returns 1
        val check = signedInAs(null, isMain = null)
        coEvery { tokenManager.validateSpotifyCookie(any()) } returns check
        coEvery { tokenManager.connectSpotifyWithSignIn(any(), any()) } returns
            com.stash.core.auth.model.SpotifyConnectResult.Connected
        val vm = newVm()

        vm.onConnectSpotifyWithCookie("rawn-cookie", "Rawn's phone")
        advanceUntilIdle()

        coVerify(exactly = 0) { spotifyAccountDao.findByAccount(any()) }
        coVerify(exactly = 1) { tokenManager.connectSpotifyWithSignIn(check.signIn, "Rawn's phone") }
        assertThat(vm.spotifyAlreadyOtherAccount.value).isNull()
    }

    @Test fun `with other accounts, the checked sign-in as a different account is still refused`() = runTest {
        coEvery { spotifyAccountDao.count() } returns 1
        coEvery { tokenManager.validateSpotifyCookie(any()) } returns signedInAs("kim", isMain = false)
        coEvery { spotifyAccountDao.findByAccount("kim") } returns null
        coEvery { tokenManager.connectSpotifyWithSignIn(any(), any()) } returns
            com.stash.core.auth.model.SpotifyConnectResult.DifferentAccount(connectedName = "Rawn", attemptedName = "Kim")
        val vm = newVm()

        vm.onConnectSpotifyWithCookie("kim-cookie", "")
        advanceUntilIdle()

        assertThat(vm.spotifyAccountMismatch.value).isEqualTo(SpotifyAccountMismatch("Rawn", "Kim"))
        coVerify(exactly = 0) { likeCoordinator.retry(any()) }
    }

    @Test fun `with no other accounts, the Connect isn't checked first`() = runTest {
        coEvery { spotifyAccountDao.count() } returns 0
        coEvery { tokenManager.connectSpotifyWithCookie(any(), any()) } returns
            com.stash.core.auth.model.SpotifyConnectResult.Connected
        val vm = newVm()

        vm.onConnectSpotifyWithCookie("rawn-cookie", "")
        advanceUntilIdle()

        coVerify(exactly = 0) { tokenManager.validateSpotifyCookie(any()) }
        coVerify(exactly = 1) { tokenManager.connectSpotifyWithCookie("rawn-cookie", "") }
    }

    @Test fun `with other accounts, a cookie that doesn't sign in connects nothing`() = runTest {
        coEvery { spotifyAccountDao.count() } returns 2
        coEvery { tokenManager.validateSpotifyCookie(any()) } returns com.stash.core.auth.model.SpotifyCookieCheck.Invalid
        val vm = newVm()

        vm.onConnectSpotifyWithCookie("bad-cookie", "")
        advanceUntilIdle()

        coVerify(exactly = 0) { tokenManager.connectSpotifyWithCookie(any(), any()) }
        assertThat(vm.spotifyAlreadyOtherAccount.value).isNull()
    }

    @Test fun `Disconnect with other accounts removes each of them, then the connected one`() = runTest {
        coEvery { spotifyAccountDao.getAll() } returns listOf(otherAccount(2, "sam", "Sam"), otherAccount(5, "alex", "Alex"))
        coEvery { spotifyExtraAccountRemover.remove(2) } returns com.stash.core.data.sync.SpotifyAccountRemoval.Removed(3)
        // Removed a moment before (a second tap): counts as removed.
        coEvery { spotifyExtraAccountRemover.remove(5) } returns com.stash.core.data.sync.SpotifyAccountRemoval.NotFound
        val vm = newVm()

        vm.onDisconnectSpotify()
        advanceUntilIdle()

        io.mockk.coVerifyOrder {
            spotifyExtraAccountRemover.remove(2)
            spotifyExtraAccountRemover.remove(5)
            tokenManager.clearAuth(com.stash.core.auth.model.AuthService.SPOTIFY)
        }
        assertThat(vm.spotifyDisconnectStopped.value).isNull()
    }

    @Test fun `with no other accounts, Disconnect is as before`() = runTest {
        coEvery { spotifyAccountDao.getAll() } returns emptyList()
        val vm = newVm()

        vm.onDisconnectSpotify()
        advanceUntilIdle()

        coVerify(exactly = 0) { spotifyExtraAccountRemover.remove(any()) }
        coVerify(exactly = 1) { tokenManager.clearAuth(com.stash.core.auth.model.AuthService.SPOTIFY) }
    }

    @Test fun `Disconnect stops at an other account that won't sign out, the connected one included`() = runTest {
        coEvery { spotifyAccountDao.getAll() } returns listOf(otherAccount(2, "sam", "Sam"), otherAccount(5, "alex", "Alex"))
        coEvery { spotifyExtraAccountRemover.remove(2) } returns com.stash.core.data.sync.SpotifyAccountRemoval.SignInNotForgotten
        val vm = newVm()

        vm.onDisconnectSpotify()
        advanceUntilIdle()

        assertThat(vm.spotifyDisconnectStopped.value).isEqualTo(SpotifyDisconnectStopped("Sam"))
        coVerify(exactly = 0) { spotifyExtraAccountRemover.remove(5) }
        coVerify(exactly = 0) { tokenManager.clearAuth(any()) }

        vm.onDismissSpotifyDisconnectStopped()
        assertThat(vm.spotifyDisconnectStopped.value).isNull()
    }

    @Test fun `Disconnect stops when removing an other account fails part way`() = runTest {
        coEvery { spotifyAccountDao.getAll() } returns listOf(otherAccount(2, "sam", "Sam"))
        coEvery { spotifyExtraAccountRemover.remove(2) } throws IllegalStateException("disk full")
        val vm = newVm()

        vm.onDisconnectSpotify()
        advanceUntilIdle()

        assertThat(vm.spotifyDisconnectStopped.value).isEqualTo(SpotifyDisconnectStopped("Sam"))
        coVerify(exactly = 0) { tokenManager.clearAuth(any()) }
    }

    @Test fun `Disconnect with the accounts unreadable disconnects nothing`() = runTest {
        coEvery { spotifyAccountDao.getAll() } throws IllegalStateException("locked")
        val vm = newVm()

        vm.onDisconnectSpotify()
        advanceUntilIdle()

        assertThat(vm.spotifyDisconnectStopped.value).isEqualTo(SpotifyDisconnectStopped(null))
        coVerify(exactly = 0) { tokenManager.clearAuth(any()) }
    }
}
