package com.stash.feature.settings.cloud

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.CloudProvider
import com.stash.data.cloud.CloudLibrary
import com.stash.data.cloud.auth.CloudAuthEvent
import com.stash.data.cloud.upload.CloudUploads
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A sign-in's result reaches every [CloudStorageViewModel] (one per screen: Accounts & Sync, Library & Storage, …), but
 * only the one whose connect() started it acts: says how it went and opens a new account's folder picker. Before, every
 * instance browsed the new drive, and a screen further back popped the picker again when it came back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudStorageViewModelSignInTest {
    private val events = MutableSharedFlow<CloudAuthEvent>(extraBufferCapacity = 8)
    private val cloud: CloudLibrary = mockk(relaxed = true) {
        every { authEvents } returns events
        every { isAvailable } returns true
        every { enabledProviders } returns listOf(CloudProvider.DROPBOX, CloudProvider.ONEDRIVE)
        every { accounts } returns flowOf(emptyList())
        every { signInChanges } returns flowOf(0L)
        every { folders } returns flowOf(emptyList())
        coEvery { browse(any(), any()) } returns emptyList()
    }
    private val uploads: CloudUploads = mockk(relaxed = true)
    private val activity: Context = mockk(relaxed = true)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = CloudStorageViewModel(cloud, uploads, saved)

    private fun connected(provider: CloudProvider = CloudProvider.DROPBOX, isNew: Boolean = true) =
        CloudAuthEvent.Connected(provider, accountId = 7, displayName = "Ray", isNew = isNew)

    @Test fun `two instances and one connects, only that one browses the new account`() {
        val accounts = vm()
        val library = vm()
        accounts.connect(activity, CloudProvider.DROPBOX)

        events.tryEmit(connected())

        assertThat(accounts.browse.value?.accountId).isEqualTo(7L)
        assertThat(library.browse.value).isNull()
        // One listing, for the picker that shows; none behind another screen.
        coVerify(exactly = 1) { cloud.browse(7L, null) }
    }

    @Test fun `the result is handled once, not again by a later event`() {
        val accounts = vm()
        accounts.connect(activity, CloudProvider.DROPBOX)
        events.tryEmit(connected())
        accounts.closeBrowser()

        events.tryEmit(connected())

        assertThat(accounts.browse.value).isNull()
    }

    @Test fun `another provider's result is not this instance's`() {
        val accounts = vm()
        accounts.connect(activity, CloudProvider.DROPBOX)

        events.tryEmit(connected(provider = CloudProvider.ONEDRIVE))
        assertThat(accounts.browse.value).isNull()

        // Still waiting for its own.
        events.tryEmit(connected(provider = CloudProvider.DROPBOX))
        assertThat(accounts.browse.value?.accountId).isEqualTo(7L)
    }

    @Test fun `a failure ends the wait, so a later sign-in elsewhere isn't taken for this one`() {
        val accounts = vm()
        accounts.connect(activity, CloudProvider.DROPBOX)
        events.tryEmit(CloudAuthEvent.Failed(provider = null, message = "That sign-in has expired. Try connecting again."))

        events.tryEmit(connected())

        assertThat(accounts.browse.value).isNull()
    }

    @Test fun `the wait survives Stash being killed while the browser is in front`() {
        val saved = SavedStateHandle()
        val store = ViewModelStore()
        val first = vm(saved)
        store.put("cloud", first)
        first.connect(activity, CloudProvider.DROPBOX)
        // Android destroys the screen while the browser is in front: the old instance stops listening.
        store.clear()

        // A new instance on the saved state Android hands back when the screen is recreated.
        val recreated = vm(saved)
        events.tryEmit(connected())

        assertThat(recreated.browse.value?.accountId).isEqualTo(7L)
    }

    @Test fun `a sign-in that couldn't open leaves nothing waiting`() {
        every { cloud.connect(any(), any()) } throws IllegalStateException("no browser")
        val accounts = vm()
        accounts.connect(activity, CloudProvider.DROPBOX)

        events.tryEmit(connected())

        assertThat(accounts.browse.value).isNull()
    }
}
