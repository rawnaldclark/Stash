package com.stash.feature.settings.spotify

import com.google.common.truth.Truth.assertThat
import com.stash.core.auth.spotify.SpotifyAccounts
import com.stash.core.auth.model.AuthState
import com.stash.core.auth.model.SpotifyCookieCheck
import com.stash.core.auth.model.UserInfo
import com.stash.core.auth.model.ValidatedSpotifySignIn
import com.stash.core.auth.spotify.SpotifyAccount
import com.stash.core.auth.spotify.SpotifyProfile
import com.stash.core.data.db.dao.SpotifyAccountAddition
import com.stash.core.data.db.dao.SpotifyAccountDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.SpotifyAccountEntity
import com.stash.core.data.sync.SpotifyAccountRemoval
import com.stash.core.data.sync.SpotifyExtraAccountRemover
import com.stash.core.data.sync.SyncPreferences
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncScheduler
import com.stash.feature.settings.components.SpotifyWebSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Settings › Accounts › the other Spotify accounts (plan 2026-11-04 §3, Wave 3): adding one,
 * signing one in again, removing one, and its switches and name. Nothing here may add a second
 * row for one account, keep a row whose sign-in didn't save, or take the connected account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyAccountsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val auth = MutableStateFlow<AuthState>(AuthState.Connected(UserInfo(id = "rawn", displayName = "Rawn")))
    private val rows = MutableStateFlow<List<SpotifyAccountEntity>>(emptyList())

    private val tokenManager = mockk<SpotifyAccounts>(relaxed = true) {
        every { spotifyAuthState } returns auth
        coEvery { hasSpotifyExtraSignIn(any()) } returns true
    }
    private val accountDao = mockk<SpotifyAccountDao>(relaxed = true) {
        every { observeAll() } returns rows
        coEvery { findByAccount(any()) } returns null
        coEvery { getById(any()) } returns null
        coEvery { count() } returns 0
    }
    private val remover = mockk<SpotifyExtraAccountRemover>(relaxed = true)
    private val syncScheduler = mockk<SyncScheduler>(relaxed = true)
    /** Whether the first-sync question was answered (a sync has run). */
    private var firstSyncAsked = true
    private val syncPreferences = mockk<SyncPreferencesManager>(relaxed = true) {
        every { arrivalChosen } answers { flowOf(firstSyncAsked) }
        every { preferences } returns flowOf(SyncPreferences(wifiOnly = false))
    }
    private val syncHistoryDao = mockk<SyncHistoryDao>(relaxed = true) {
        coEvery { getLatest() } returns null
    }
    private val webSession = mockk<SpotifyWebSession>(relaxed = true)

    private fun newVm() = SpotifyAccountsViewModel(
        spotifyAccounts = tokenManager,
        accountDao = accountDao,
        remover = remover,
        syncScheduler = syncScheduler,
        syncPreferences = syncPreferences,
        syncHistoryDao = syncHistoryDao,
        webSession = webSession,
    )

    private fun row(id: Long, userId: String, label: String, signedOutAt: Long? = null) =
        SpotifyAccountEntity(id = id, userId = userId, label = label, addedAt = id, signedOutAt = signedOutAt)

    /** A cookie that signs in as [userId] (null: Spotify didn't say whose it is). */
    private fun signsInAs(userId: String?, displayName: String = "Sam", isMain: Boolean? = false): SpotifyCookieCheck.Valid {
        val signIn = mockk<ValidatedSpotifySignIn> {
            every { account } returns userId?.let { SpotifyProfile(it, displayName) }
        }
        coEvery { tokenManager.validateSpotifyCookie(any()) } returns SpotifyCookieCheck.Valid(signIn, isMain)
        return SpotifyCookieCheck.Valid(signIn, isMain)
    }

    /**
     * Every notice from here on. Collected unconfined: subscribed at once and handed each notice as
     * it is sent, since advanceUntilIdle stops once only background work is left.
     */
    private fun TestScope.noticesOf(vm: SpotifyAccountsViewModel): List<OtherAccountNotice> {
        val seen = mutableListOf<OtherAccountNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { seen += it } }
        return seen
    }

    private fun TestScope.add(vm: SpotifyAccountsViewModel, cookie: String = "cookie") {
        vm.startAdd()
        vm.onCookieCaptured(cookie)
        advanceUntilIdle()
    }

    // -- Adding ------------------------------------------------------------------------------

    @Test fun `adding the connected account is refused, and nothing is saved`() = runTest {
        signsInAs("rawn", "Rawn", isMain = true)
        val vm = newVm()

        add(vm)

        assertThat(vm.state.value.refusal).isEqualTo(OtherAccountRefusal.AlreadyConnected("Rawn"))
        assertThat(vm.state.value.signIn).isNull()
        coVerify(exactly = 0) { accountDao.add(any(), any(), any()) }
        coVerify(exactly = 0) { tokenManager.saveSpotifyExtra(any(), any()) }
        coVerify(exactly = 0) { tokenManager.connectSpotifyWithCookie(any(), any()) }
        // The WebView keeps no account, a refused one included.
        verify(exactly = 1) { webSession.clear() }
    }

    @Test fun `with the connected account's identity unknown, an add is refused, and nothing is saved`() = runTest {
        // An older install never recorded whose the connected sign-in is, and Spotify couldn't say
        // in time: this could be the connected account itself ("Continue with Google").
        signsInAs("rawn", "Rawn", isMain = null)
        val vm = newVm()

        add(vm)

        assertThat(vm.state.value.refusal).isEqualTo(OtherAccountRefusal.MainUnchecked("Rawn"))
        coVerify(exactly = 0) { accountDao.add(any(), any(), any()) }
        coVerify(exactly = 0) { tokenManager.saveSpotifyExtra(any(), any()) }
        verify(exactly = 1) { webSession.clear() }
    }

    @Test fun `with no account connected, there is nothing to confuse an unchecked add with`() = runTest {
        auth.value = AuthState.NotConnected
        signsInAs("sam", isMain = null)
        coEvery { accountDao.add(any(), any(), any()) } returns SpotifyAccountAddition(row(7, "sam", "Sam"), added = true)
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } returns true
        val vm = newVm()

        add(vm)

        assertThat(vm.state.value.refusal).isNull()
        assertThat(vm.state.value.naming).isEqualTo(OtherAccountNaming(7, "Sam"))
    }

    @Test fun `an account Spotify won't name is refused, and nothing is saved`() = runTest {
        signsInAs(null)
        val vm = newVm()

        add(vm)

        assertThat(vm.state.value.refusal).isEqualTo(OtherAccountRefusal.Unidentified)
        coVerify(exactly = 0) { accountDao.add(any(), any(), any()) }
        coVerify(exactly = 0) { tokenManager.saveSpotifyExtra(any(), any()) }
    }

    @Test fun `an account added already is signed in again, with no second row`() = runTest {
        val check = signsInAs("sam")
        coEvery { accountDao.findByAccount("sam") } returns row(4, "sam", "Sam", signedOutAt = 1_700_000_000_000)
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } returns true
        val vm = newVm()
        val notices = noticesOf(vm)

        add(vm)

        coVerify(exactly = 0) { accountDao.add(any(), any(), any()) }
        coVerify(exactly = 1) { tokenManager.saveSpotifyExtra(SpotifyAccount(4, "sam"), check.signIn) }
        coVerify(exactly = 1) { accountDao.updateSignedOutAt(4, null) }
        assertThat(notices).containsExactly(OtherAccountNotice.SignedInAgain("Sam"))
        assertThat(vm.state.value.naming).isNull()
    }

    @Test fun `a new account is added, then asked for its name, pre-filled with Spotify's`() = runTest {
        val check = signsInAs("sam", "Sam Smith")
        coEvery { accountDao.add("sam", "Sam Smith", any()) } returns SpotifyAccountAddition(row(7, "sam", "Sam Smith"), added = true)
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } returns true
        val vm = newVm()

        add(vm)

        coVerify(exactly = 1) { tokenManager.saveSpotifyExtra(SpotifyAccount(7, "sam"), check.signIn) }
        coVerify(exactly = 1) { accountDao.updateSignedOutAt(7, null) }
        coVerify(exactly = 0) { accountDao.delete(any()) }
        assertThat(vm.state.value.naming).isEqualTo(OtherAccountNaming(7, "Sam Smith"))
        assertThat(vm.state.value.showWebLogin).isFalse()
        assertThat(vm.state.value.showPasteDialog).isFalse()
        verify(exactly = 1) { webSession.clear() }
    }

    @Test fun `a sign-in that won't save takes its new row back out`() = runTest {
        signsInAs("sam")
        coEvery { accountDao.add(any(), any(), any()) } returns SpotifyAccountAddition(row(7, "sam", "Sam"), added = true)
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } throws IllegalStateException("Keystore")
        val vm = newVm()

        add(vm)

        coVerify(exactly = 1) { accountDao.delete(7) }
        assertThat(vm.state.value.error).isEqualTo(OtherAccountSignInError.NOT_SAVED)
        assertThat(vm.state.value.naming).isNull()
    }

    @Test fun `a row the add found already, rather than added, stays when its sign-in won't save`() = runTest {
        // Added a moment ago elsewhere: not there when looked up, there when the add ran.
        signsInAs("sam")
        coEvery { accountDao.add(any(), any(), any()) } returns SpotifyAccountAddition(row(4, "sam", "Sam"), added = false)
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } throws IllegalStateException("Keystore")
        val vm = newVm()

        add(vm)

        coVerify(exactly = 0) { accountDao.delete(any()) }
        assertThat(vm.state.value.error).isEqualTo(OtherAccountSignInError.NOT_SAVED)
    }

    @Test fun `a row the add found already is signed in again, not named as new`() = runTest {
        signsInAs("sam")
        coEvery { accountDao.add(any(), any(), any()) } returns SpotifyAccountAddition(row(4, "sam", "Sam"), added = false)
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } returns true
        val vm = newVm()
        val notices = noticesOf(vm)

        add(vm)

        assertThat(notices).containsExactly(OtherAccountNotice.SignedInAgain("Sam"))
        assertThat(vm.state.value.naming).isNull()
    }

    @Test fun `a sign-in the store refuses takes its new row back out and says it's the connected account`() = runTest {
        signsInAs("sam")
        coEvery { accountDao.add(any(), any(), any()) } returns SpotifyAccountAddition(row(7, "sam", "Sam"), added = true)
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } returns false
        val vm = newVm()

        add(vm)

        coVerify(exactly = 1) { accountDao.delete(7) }
        assertThat(vm.state.value.refusal).isEqualTo(OtherAccountRefusal.AlreadyConnected("Rawn"))
    }

    @Test fun `an existing row whose sign-in won't save stays`() = runTest {
        signsInAs("sam")
        coEvery { accountDao.findByAccount("sam") } returns row(4, "sam", "Sam")
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } throws IllegalStateException("Keystore")
        val vm = newVm()

        add(vm)

        coVerify(exactly = 0) { accountDao.delete(any()) }
        assertThat(vm.state.value.error).isEqualTo(OtherAccountSignInError.NOT_SAVED)
    }

    @Test fun `a cookie that doesn't sign in shows an error, and nothing is saved`() = runTest {
        coEvery { tokenManager.validateSpotifyCookie(any()) } returns SpotifyCookieCheck.Invalid
        val vm = newVm()

        add(vm)

        assertThat(vm.state.value.error).isEqualTo(OtherAccountSignInError.INVALID)
        assertThat(vm.state.value.showPasteDialog).isTrue()
        coVerify(exactly = 0) { accountDao.add(any(), any(), any()) }
        verify(exactly = 1) { webSession.clear() }
    }

    @Test fun `at the limit an add is refused, and nothing is saved`() = runTest {
        signsInAs("sam")
        coEvery { accountDao.count() } returns 3
        val vm = newVm()

        add(vm)

        assertThat(vm.state.value.refusal).isEqualTo(OtherAccountRefusal.Full)
        coVerify(exactly = 0) { accountDao.add(any(), any(), any()) }
    }

    @Test fun `the add row shows with a connected account and room, never at the limit`() = runTest {
        val vm = newVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.canAdd.collect {} }

        rows.value = listOf(row(2, "a", "A"), row(3, "b", "B"))
        advanceUntilIdle()
        assertThat(vm.canAdd.value).isTrue()

        rows.value = listOf(row(2, "a", "A"), row(3, "b", "B"), row(4, "c", "C"))
        advanceUntilIdle()
        assertThat(vm.canAdd.value).isFalse()

        rows.value = emptyList()
        auth.value = AuthState.NotConnected
        advanceUntilIdle()
        assertThat(vm.canAdd.value).isFalse()
    }

    @Test fun `Cancel drops a sign-in still being checked, and nothing is saved`() = runTest {
        var dropped = false
        coEvery { tokenManager.validateSpotifyCookie(any()) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                dropped = true
            }
        }
        val vm = newVm()
        add(vm)

        vm.cancelSignIn()
        advanceUntilIdle()

        assertThat(dropped).isTrue()
        assertThat(vm.state.value.signIn).isNull()
        coVerify(exactly = 0) { accountDao.add(any(), any(), any()) }
    }

    @Test fun `a cookie the sign-in screen hands over after Cancel opens nothing and checks nothing`() = runTest {
        val vm = newVm()
        vm.startAdd()
        vm.cancelSignIn()

        // A page load that finished just after Cancel.
        vm.onCookieCaptured("late-cookie")
        advanceUntilIdle()

        assertThat(vm.state.value.showPasteDialog).isFalse()
        assertThat(vm.state.value.showWebLogin).isFalse()
        assertThat(vm.state.value.signIn).isNull()
        coVerify(exactly = 0) { tokenManager.validateSpotifyCookie(any()) }
    }

    // -- Signing in again --------------------------------------------------------------------

    @Test fun `signing in again as a different account is refused, and nothing is saved`() = runTest {
        coEvery { accountDao.getById(4) } returns row(4, "sam", "Sam", signedOutAt = 1)
        signsInAs("alex", "Alex")
        val vm = newVm()

        vm.startSignInAgain(4)
        vm.onCookieCaptured("cookie")
        advanceUntilIdle()

        assertThat(vm.state.value.refusal).isEqualTo(OtherAccountRefusal.DifferentAccount("Sam"))
        coVerify(exactly = 0) { tokenManager.saveSpotifyExtra(any(), any()) }
        coVerify(exactly = 0) { accountDao.updateSignedOutAt(any(), any()) }
    }

    @Test fun `Sign in again from an account's sheet closes the sheet, so it can't cover the check`() = runTest {
        coEvery { accountDao.getById(4) } returns row(4, "sam", "Sam", signedOutAt = 1)
        coEvery { tokenManager.validateSpotifyCookie(any()) } coAnswers { awaitCancellation() }
        val vm = newVm()
        vm.openAccount(4)

        vm.startSignInAgain(4)
        assertThat(vm.state.value.openAccountId).isNull()

        // The check under way, in the paste dialog: still no sheet.
        vm.onCookieCaptured("cookie")
        advanceUntilIdle()
        assertThat(vm.state.value.showPasteDialog).isTrue()
        assertThat(vm.state.value.openAccountId).isNull()
        vm.cancelSignIn()
    }

    @Test fun `signing in again as an account that turns out to be the connected one is refused`() = runTest {
        coEvery { accountDao.getById(4) } returns row(4, "sam", "Sam", signedOutAt = 1)
        signsInAs("sam", isMain = true)
        val vm = newVm()

        vm.startSignInAgain(4)
        vm.onCookieCaptured("cookie")
        advanceUntilIdle()

        assertThat(vm.state.value.refusal).isEqualTo(OtherAccountRefusal.AlreadyConnected("Rawn"))
        coVerify(exactly = 0) { tokenManager.saveSpotifyExtra(any(), any()) }
        coVerify(exactly = 0) { accountDao.updateSignedOutAt(any(), any()) }
    }

    @Test fun `signing in again unchecked against the connected account is refused`() = runTest {
        coEvery { accountDao.getById(4) } returns row(4, "sam", "Sam", signedOutAt = 1)
        signsInAs("sam", isMain = null)
        val vm = newVm()

        vm.startSignInAgain(4)
        vm.onCookieCaptured("cookie")
        advanceUntilIdle()

        assertThat(vm.state.value.refusal).isEqualTo(OtherAccountRefusal.MainUnchecked("Rawn"))
        coVerify(exactly = 0) { tokenManager.saveSpotifyExtra(any(), any()) }
    }

    @Test fun `signing in again as the same account saves it and clears its signed-out mark`() = runTest {
        coEvery { accountDao.getById(4) } returns row(4, "sam", "Sam", signedOutAt = 1)
        // Spotify's answer in another shape: still the same account.
        val check = signsInAs("spotify:user:SAM")
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } returns true
        val vm = newVm()
        val notices = noticesOf(vm)

        vm.startSignInAgain(4)
        vm.onCookieCaptured("cookie")
        advanceUntilIdle()

        coVerify(exactly = 1) { tokenManager.saveSpotifyExtra(SpotifyAccount(4, "sam"), check.signIn) }
        coVerify(exactly = 1) { accountDao.updateSignedOutAt(4, null) }
        assertThat(notices).containsExactly(OtherAccountNotice.SignedInAgain("Sam"))
        coVerify(exactly = 0) { accountDao.delete(any()) }
    }

    @Test fun `an account asks to be signed in again when signed out or with no sign-in on this phone`() = runTest {
        rows.value = listOf(row(2, "a", "A", signedOutAt = 1), row(3, "b", "B"), row(4, "c", "C"))
        coEvery { tokenManager.hasSpotifyExtraSignIn(SpotifyAccount(3, "b")) } returns false
        val vm = newVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.accounts.collect {} }
        advanceUntilIdle()

        assertThat(vm.accounts.value.map { it.id to it.needsSignIn }).containsExactly(2L to true, 3L to true, 4L to false).inOrder()
    }

    @Test fun `the other accounts' names are known before any sign-in is read, for Disconnect's confirm`() = runTest {
        rows.value = listOf(row(2, "a", "Alex"), row(3, "b", "Sam"))
        // Reading the sign-ins hasn't answered yet: the cards wait, the names don't.
        coEvery { tokenManager.hasSpotifyExtraSignIn(any()) } coAnswers { awaitCancellation() }
        val vm = newVm()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.accounts.collect {} }
        advanceUntilIdle()

        assertThat(vm.otherAccountNames.value).containsExactly("Alex", "Sam").inOrder()
        assertThat(vm.accounts.value).isEmpty()
    }

    // -- Name, then "Sync now?" -------------------------------------------------------------

    @Test fun `a typed name is saved, then Sync now is offered`() = runTest {
        signsInAs("sam")
        coEvery { accountDao.add(any(), any(), any()) } returns SpotifyAccountAddition(row(7, "sam", "Sam"), added = true)
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } returns true
        val vm = newVm()
        add(vm)

        vm.confirmName("  Work   phone ")
        advanceUntilIdle()

        coVerify(exactly = 1) { accountDao.updateLabel(7, "Work phone") }
        assertThat(vm.state.value.offerSyncNow).isEqualTo("Work phone")

        val notices = noticesOf(vm)
        advanceUntilIdle()
        vm.syncNow()
        advanceUntilIdle()

        coVerify(exactly = 1) { syncScheduler.triggerManualSync(false) }
        assertThat(notices).containsExactly(OtherAccountNotice.Syncing)
        assertThat(vm.state.value.offerSyncNow).isNull()
    }

    @Test fun `Skip keeps Spotify's name`() = runTest {
        signsInAs("sam")
        coEvery { accountDao.add(any(), any(), any()) } returns SpotifyAccountAddition(row(7, "sam", "Sam"), added = true)
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } returns true
        val vm = newVm()
        add(vm)

        vm.skipName()
        advanceUntilIdle()

        coVerify(exactly = 0) { accountDao.updateLabel(any(), any()) }
        assertThat(vm.state.value.offerSyncNow).isEqualTo("Sam")
    }

    @Test fun `before the first sync, Sync now isn't offered - that sync brings it`() = runTest {
        firstSyncAsked = false
        signsInAs("sam")
        coEvery { accountDao.add(any(), any(), any()) } returns SpotifyAccountAddition(row(7, "sam", "Sam"), added = true)
        coEvery { tokenManager.saveSpotifyExtra(any(), any()) } returns true
        val vm = newVm()
        val notices = noticesOf(vm)
        add(vm)

        vm.skipName()
        advanceUntilIdle()

        assertThat(vm.state.value.offerSyncNow).isNull()
        assertThat(notices).containsExactly(OtherAccountNotice.AddedForFirstSync("Sam"))
        coVerify(exactly = 0) { syncScheduler.triggerManualSync(any()) }
    }

    // -- Switches and rename ------------------------------------------------------------------

    @Test fun `each switch is saved to its own column`() = runTest {
        val vm = newVm()

        vm.setSyncPlaylists(4, false)
        vm.setSyncLiked(4, false)
        vm.setSyncAlbums(4, false)
        vm.setSyncMixes(4, true)
        advanceUntilIdle()

        coVerify(exactly = 1) { accountDao.setSyncPlaylists(4, false) }
        coVerify(exactly = 1) { accountDao.setSyncLiked(4, false) }
        coVerify(exactly = 1) { accountDao.setSyncAlbums(4, false) }
        coVerify(exactly = 1) { accountDao.setSyncMixes(4, true) }
    }

    @Test fun `a switch that won't save doesn't crash Settings`() = runTest {
        coEvery { accountDao.setSyncMixes(any(), any()) } throws IllegalStateException("disk full")
        val vm = newVm()

        vm.setSyncMixes(4, true)
        advanceUntilIdle()

        coVerify(exactly = 1) { accountDao.setSyncMixes(4, true) }
    }

    @Test fun `a rename is saved tidied, and a blank one changes nothing`() = runTest {
        val vm = newVm()

        vm.startRename(4)
        vm.rename(4, "  Sam \t at  work ")
        vm.rename(4, "   ")
        advanceUntilIdle()

        coVerify(exactly = 1) { accountDao.updateLabel(4, "Sam at work") }
        coVerify(exactly = 1) { accountDao.updateLabel(any(), any()) }
        assertThat(vm.state.value.renamingId).isNull()
    }

    @Test fun `a long name is cut to fit`() {
        assertThat(SpotifyAccountsViewModel.cleanName("x".repeat(60))).hasLength(SpotifyAccountsViewModel.MAX_NAME_LENGTH)
    }

    // -- Removing -----------------------------------------------------------------------------

    private fun TestScope.remove(result: SpotifyAccountRemoval?): List<OtherAccountNotice> {
        coEvery { accountDao.getById(4) } returns row(4, "sam", "Sam")
        if (result == null) {
            coEvery { remover.remove(4) } throws IllegalStateException("disk full")
        } else {
            coEvery { remover.remove(4) } returns result
        }
        val vm = newVm()
        val notices = noticesOf(vm)
        vm.openAccount(4)
        vm.requestRemove(4)
        vm.confirmRemove()
        advanceUntilIdle()
        assertThat(vm.state.value.openAccountId).isNull()
        assertThat(vm.state.value.confirmRemoveId).isNull()
        coVerify(exactly = 1) { remover.remove(4) }
        return notices
    }

    @Test fun `Remove that works says so`() = runTest {
        assertThat(remove(SpotifyAccountRemoval.Removed(hiddenRows = 12))).containsExactly(OtherAccountNotice.Removed("Sam"))
    }

    @Test fun `Remove of an account already gone says so`() = runTest {
        assertThat(remove(SpotifyAccountRemoval.NotFound)).containsExactly(OtherAccountNotice.AlreadyRemoved)
    }

    @Test fun `Remove whose sign-in won't be forgotten removes nothing and says so`() = runTest {
        assertThat(remove(SpotifyAccountRemoval.SignInNotForgotten)).containsExactly(OtherAccountNotice.NotRemoved("Sam"))
    }

    @Test fun `Remove that fails part way says to try again`() = runTest {
        assertThat(remove(null)).containsExactly(OtherAccountNotice.RemoveUnfinished("Sam"))
    }

    @Test fun `a confirmed Remove finishes even if the screen closes meanwhile`() = runTest {
        coEvery { accountDao.getById(4) } returns row(4, "sam", "Sam")
        val gate = CompletableDeferred<Unit>()
        var finished = false
        coEvery { remover.remove(4) } coAnswers {
            gate.await()
            finished = true
            SpotifyAccountRemoval.Removed(hiddenRows = 3)
        }
        val vm = newVm()
        vm.requestRemove(4)
        vm.confirmRemove()
        advanceUntilIdle()

        // The Accounts screen left for good, mid-removal.
        vm.viewModelScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(finished).isTrue()
    }

    @Test fun `Cancel on the Remove confirm removes nothing`() = runTest {
        val vm = newVm()

        vm.requestRemove(4)
        vm.cancelRemove()
        vm.confirmRemove()
        advanceUntilIdle()

        coVerify(exactly = 0) { remover.remove(any()) }
    }
}
