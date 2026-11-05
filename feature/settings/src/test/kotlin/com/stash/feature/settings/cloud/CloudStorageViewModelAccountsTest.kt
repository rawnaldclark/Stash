package com.stash.feature.settings.cloud

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudAccount
import com.stash.data.cloud.CloudLibrary
import com.stash.data.cloud.provider.gdrive.GooglePickOutcome
import com.stash.data.cloud.upload.CloudUploads
import com.stash.data.cloud.upload.UploadStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The Drive cards with more than one Google account: whose status, who is named, what a pick says. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudStorageViewModelAccountsTest {
    private val ron = CloudAccount(1, CloudProvider.GOOGLE_DRIVE, "Ron", "ron@example.com", 1)
    private val sam = CloudAccount(2, CloudProvider.GOOGLE_DRIVE, "Sam", "sam@example.com", 2)
    private val box = CloudAccount(3, CloudProvider.DROPBOX, "Ron", "ron@box.example", 3)

    private val accounts = MutableStateFlow(listOf(ron, sam))
    private val cloud: CloudLibrary = mockk(relaxed = true) {
        every { authEvents } returns MutableSharedFlow()
        every { isAvailable } returns true
        every { enabledProviders } returns listOf(CloudProvider.GOOGLE_DRIVE)
        every { this@mockk.accounts } returns this@CloudStorageViewModelAccountsTest.accounts
        every { signInChanges } returns flowOf(0L)
        every { folders } returns flowOf(emptyList())
        coEvery { disconnectImpact(any()) } returns (3 to 2)
    }
    private val uploads: CloudUploads = mockk(relaxed = true) {
        every { available } returns true
        every { saveAccount } returns flowOf(1L)
        coEvery { saveAccountId() } returns 1L
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = CloudStorageViewModel(cloud, uploads, saved)

    private fun status(saved: Int, full: Boolean = false) =
        UploadStatus(backupOn = false, saved = saved, queued = 0, existing = 0, failed = 0, driveFull = full, running = false, wifiOnly = true)

    private fun added(new: Boolean, added: Int) =
        GooglePickOutcome.Added(accountId = 2, displayName = "Sam", isNewAccount = new, added = added, alreadyThere = 0, email = "sam@example.com")

    @Test fun `each Drive account has its own status`() = runTest {
        every { uploads.status(1L) } returns flowOf(status(saved = 124))
        every { uploads.status(2L) } returns flowOf(status(saved = 3, full = true))
        val vm = vm()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.driveUploads.collect {} }

        assertThat(vm.driveUploads.value[1L]!!.saved).isEqualTo(124)
        assertThat(vm.driveUploads.value[1L]!!.driveFull).isFalse()
        assertThat(vm.driveUploads.value[2L]!!.saved).isEqualTo(3)
        assertThat(vm.driveUploads.value[2L]!!.driveFull).isTrue()
        job.cancel()
    }

    @Test fun `only Google Drive accounts have an upload status`() = runTest {
        accounts.value = listOf(box)
        val vm = vm()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.driveUploads.collect {} }

        assertThat(vm.driveUploads.value).isEmpty()
        job.cancel()
    }

    @Test fun `a build without Google Drive has no upload status`() = runTest {
        every { uploads.available } returns false
        val vm = vm()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.driveUploads.collect {} }

        assertThat(vm.driveUploads.value).isEmpty()
        job.cancel()
    }

    @Test fun `disconnecting with two accounts names the account, and the toast does too`() = runTest {
        val vm = vm()
        val notices = mutableListOf<CloudNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }

        vm.requestDisconnect(sam)

        val pending = vm.pendingDisconnect.value!!
        assertThat(pending.name).isEqualTo("sam@example.com")
        assertThat(pending.removed).isEqualTo(3)
        assertThat(pending.kept).isEqualTo(2)
        // Sam isn't where saves go, and backup isn't on Sam: neither moves.
        assertThat(pending.savesMoveTo).isNull()
        assertThat(pending.backupTurnsOff).isFalse()

        coEvery { cloud.disconnect(2) } returns com.stash.core.data.db.dao.CloudRemoval(3, 2)
        vm.confirmDisconnect()

        coVerify { cloud.disconnect(2) }
        assertThat(notices).containsExactly(CloudNotice.Disconnected("sam@example.com", 2))
        job.cancel()
    }

    @Test fun `a disconnect that fails says so instead of crashing, and a folder too`() = runTest {
        val vm = vm()
        val notices = mutableListOf<CloudNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        coEvery { cloud.disconnect(2) } throws IllegalStateException("database hiccup")
        coEvery { cloud.removeFolder(7) } throws IllegalStateException("database hiccup")

        vm.requestDisconnect(sam)
        vm.confirmDisconnect()
        vm.removeFolder(7)

        assertThat(notices).containsExactly(CloudNotice.DisconnectFailed("sam@example.com"), CloudNotice.FolderRemoveFailed).inOrder()
        job.cancel()
    }

    @Test fun `removing a folder says how many downloads stay`() = runTest {
        val vm = vm()
        val notices = mutableListOf<CloudNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        coEvery { cloud.removeFolder(7) } returns com.stash.core.data.db.dao.CloudRemoval(0, 1)

        vm.removeFolder(7)

        assertThat(notices).containsExactly(CloudNotice.FolderRemoved(1))
        job.cancel()
    }

    @Test fun `disconnecting the only account of a provider says the provider, as before`() = runTest {
        accounts.value = listOf(ron, box)
        val vm = vm()

        vm.requestDisconnect(ron)

        val pending = vm.pendingDisconnect.value!!
        assertThat(pending.name).isNull()
        assertThat(pending.savesMoveTo).isNull()
        assertThat(pending.backupTurnsOff).isFalse()
    }

    @Test fun `disconnecting the account saves go to says where they go next`() = runTest {
        every { uploads.isBackupAccount(1L) } returns true
        val vm = vm()

        vm.requestDisconnect(ron)

        assertThat(vm.pendingDisconnect.value!!.savesMoveTo).isEqualTo("sam@example.com")
        assertThat(vm.pendingDisconnect.value!!.backupTurnsOff).isTrue()
    }

    @Test fun `choosing an account for saves says so`() = runTest {
        val vm = vm()
        val notices = mutableListOf<CloudNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }

        vm.setSaveAccount(sam)

        coVerify { uploads.setSaveAccount(2) }
        assertThat(notices).containsExactly(CloudNotice.SavingTo("sam@example.com"))
        job.cancel()
    }

    @Test fun `Add another that picks an account already here says so instead of pretending to add one`() = runTest {
        coEvery { cloud.completeGooglePick(any()) } returns added(new = false, added = 2)
        val vm = vm()
        val notices = mutableListOf<CloudNotice>()
        val messages = mutableListOf<String>()
        val a = launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        val b = launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }

        vm.startGooglePick(null) {}
        vm.finishGooglePick(null)

        assertThat(notices).containsExactly(CloudNotice.AlreadyConnected("sam@example.com", 2))
        assertThat(messages).isEmpty()
        a.cancel(); b.cancel()
    }

    @Test fun `Add songs on a card keeps its plain message`() = runTest {
        coEvery { cloud.completeGooglePick(any()) } returns added(new = false, added = 2)
        val vm = vm()
        val notices = mutableListOf<CloudNotice>()
        val messages = mutableListOf<String>()
        val a = launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        val b = launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }

        vm.startGooglePick(2) {}
        vm.finishGooglePick(null)

        assertThat(notices).isEmpty()
        assertThat(messages).containsExactly("Adding 2 songs from Google Drive.")
        a.cancel(); b.cancel()
    }

    @Test fun `Add another that really adds an account names it by its email`() = runTest {
        coEvery { cloud.completeGooglePick(any()) } returns added(new = true, added = 1)
        val vm = vm()
        val messages = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }

        vm.startGooglePick(null) {}
        vm.finishGooglePick(null)

        assertThat(messages.single()).startsWith("Google Drive connected as sam@example.com.")
        job.cancel()
    }

    @Test fun `what the picker was opened for survives Stash being killed behind it`() = runTest {
        coEvery { cloud.completeGooglePick(any()) } returns added(new = false, added = 0)
        val saved = SavedStateHandle()
        vm(saved).startGooglePick(null) {}

        val reborn = vm(saved)
        val notices = mutableListOf<CloudNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { reborn.notices.collect { notices += it } }
        reborn.finishGooglePick(null)

        assertThat(notices).containsExactly(CloudNotice.AlreadyConnected("sam@example.com", 0))
        job.cancel()
    }

    @Test fun `a refused account gets its own notice`() = runTest {
        coEvery { cloud.completeGooglePick(any()) } returns GooglePickOutcome.AccountUnidentified
        val vm = vm()
        val notices = mutableListOf<CloudNotice>()
        val messages = mutableListOf<String>()
        val a = launch(UnconfinedTestDispatcher(testScheduler)) { vm.notices.collect { notices += it } }
        val b = launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { messages += it } }

        vm.startGooglePick(null) {}
        vm.finishGooglePick(null)

        assertThat(notices).containsExactly(CloudNotice.AccountUnidentified)
        assertThat(messages).isEmpty()
        a.cancel(); b.cancel()
    }

    @Test fun `each card's buttons act on its own account`() = runTest {
        val vm = vm()

        vm.backUpExistingDownloads(2)
        vm.retryDriveUploads(1)

        coVerify { uploads.backUpExisting(2) }
        coVerify { uploads.resume(1) }
    }
}
