package com.stash.feature.settings.update

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.update.AppUpdater
import com.stash.core.data.update.AvailableUpdate
import com.stash.core.data.update.ReleaseNotes
import com.stash.core.data.update.UpdateState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * When the update sheet comes up by itself (review on #814): Hide stays hidden through a return to the app and edited
 * notes; Later and Skip keep it down; a failure or Android's permission step brings it back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {

    private val release = AvailableUpdate("v0.9.123", "0.9.123", "https://x/Stash.apk", 174_000_000, "Headline.\n\n## What's new\n- **One.** Two.")
    private val available = MutableStateFlow<AvailableUpdate?>(release)
    private val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val asks = MutableStateFlow(0)

    /** What Later, Skip and the stored prefs decide: whether this release may ask by itself. */
    private var prompts = true

    private val updater: AppUpdater = mockk(relaxed = true) {
        every { this@mockk.available } returns this@UpdateViewModelTest.available
        every { this@mockk.state } returns this@UpdateViewModelTest.state
        every { this@mockk.asks } returns this@UpdateViewModelTest.asks
        every { shouldPrompt(any(), any()) } answers { prompts }
        every { later() } answers { prompts = false }
        every { skip() } answers { prompts = false }
        every { update() } answers { this@UpdateViewModelTest.state.value = UpdateState.Downloading(0.1f) }
        every { reset() } answers {
            val s = this@UpdateViewModelTest.state
            if (s.value is UpdateState.Failed || s.value == UpdateState.NeedsPermission) s.value = UpdateState.Idle
        }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `a new release opens the sheet by itself`() {
        assertThat(UpdateViewModel(updater).visible.value).isTrue()
    }

    @Test fun `Hide mid-download stays hidden through a return to the app and edited notes`() {
        val vm = UpdateViewModel(updater)
        vm.update()
        vm.hide()

        vm.onResume()
        available.value = release.copy(notes = "Edited on GitHub.")
        vm.onResume()

        assertThat(vm.visible.value).isFalse()
    }

    @Test fun `a hidden download that fails, or needs Android's permission, comes back by itself`() {
        val vm = UpdateViewModel(updater)
        vm.update()
        vm.hide()

        state.value = UpdateState.NeedsPermission
        assertThat(vm.visible.value).isTrue()

        vm.hide()
        state.value = UpdateState.Failed(UpdateState.Failed.Reason.DOWNLOAD)
        assertThat(vm.visible.value).isTrue()
    }

    @Test fun `Later keeps the sheet down through a return to the app and edited notes`() {
        val vm = UpdateViewModel(updater)
        vm.dismiss()

        vm.onResume()
        available.value = release.copy(notes = "Edited on GitHub.")

        verify { updater.later() }
        assertThat(vm.visible.value).isFalse()
    }

    @Test fun `a day after Later, a return to the app asks again`() {
        val vm = UpdateViewModel(updater)
        vm.dismiss()
        prompts = true // the day has passed

        vm.onResume()

        assertThat(vm.visible.value).isTrue()
    }

    @Test fun `Skip keeps the sheet down, and Check for updates still opens it`() {
        val vm = UpdateViewModel(updater)
        vm.skip()

        vm.onResume()
        available.value = release.copy(notes = "Edited on GitHub.")
        assertThat(vm.visible.value).isFalse()

        asks.value++ // Settings › Check for updates, or the notification
        assertThat(vm.visible.value).isTrue()
    }

    @Test fun `Later at the permission step puts it off and starts over`() {
        val vm = UpdateViewModel(updater)
        vm.update()
        state.value = UpdateState.NeedsPermission

        vm.dismiss()

        verify { updater.later() }
        assertThat(state.value).isEqualTo(UpdateState.Idle)
        assertThat(vm.visible.value).isFalse()
        vm.onResume()
        assertThat(vm.visible.value).isFalse()
    }

    @Test fun `Settings About's light path asks only when a release is stored`() {
        val about = UpdateAskViewModel(updater)
        assertThat(about.ask()).isTrue()
        verify(exactly = 1) { updater.ask() }

        available.value = null
        assertThat(about.ask()).isFalse()
        verify(exactly = 1) { updater.ask() }
    }

    @Test fun `the notes are parsed off the main thread for the sheet`() {
        // Main is this test's thread here (an unconfined test dispatcher): without flowOn the parse would run on it.
        val main = Thread.currentThread()
        val parsedOn = CopyOnWriteArrayList<Thread>()
        val vm = UpdateViewModel(updater) { raw ->
            parsedOn += Thread.currentThread()
            ReleaseNotes.parse(raw)
        }

        val notes = runBlocking { withTimeout(5_000) { vm.notes.first { it != null } } }!!

        assertThat(notes.headline).isEqualTo("Headline.")
        assertThat(notes.highlights.single().title).isEqualTo("One")
        assertThat(parsedOn).isNotEmpty()
        assertThat(parsedOn).doesNotContain(main)
    }

    @Test fun `an install waiting for Android's confirm brings a hidden sheet back, and Install now goes to the updater`() {
        val vm = UpdateViewModel(updater)
        vm.update()
        vm.hide()
        state.value = UpdateState.Installing
        assertThat(vm.visible.value).isFalse()

        // The confirm couldn't open in the background.
        state.value = UpdateState.ReadyToInstall
        assertThat(vm.visible.value).isTrue()

        vm.installNow()
        verify { updater.installNow() }
    }
}
