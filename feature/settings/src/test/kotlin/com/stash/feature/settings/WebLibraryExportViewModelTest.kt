package com.stash.feature.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblibrary.ExportCatalog
import com.stash.core.data.weblibrary.ExportPlaylistChoice
import com.stash.core.data.weblibrary.ExportSelection
import com.stash.core.data.weblibrary.WebLibraryExportResult
import com.stash.core.data.weblibrary.WebLibraryExporter
import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblibrary.WebLibraryPickerPrefs
import com.stash.core.data.weblink.WebLinkConfig
import com.stash.core.data.weblink.inbox.SendOutcome
import com.stash.core.data.weblink.inbox.SendTarget
import com.stash.core.data.weblink.inbox.WebLinkInbox
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebLibraryExportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val exporter: WebLibraryExporter = mockk()
    private val uri: Uri = mockk()
    private var remembered = WebLibraryPickerPrefs.Remembered()
    private val prefs: WebLibraryPickerPrefs = mockk {
        every { load() } answers { remembered }
        every { save(any()) } answers { remembered = firstArg() }
    }
    private val inbox: WebLinkInbox = mockk()
    private val chrome = SendTarget("d_chrome00000000", "Chrome on Windows")

    private fun vm(linkEnabled: Boolean = true) = WebLibraryExportViewModel(exporter, prefs, inbox, WebLinkConfig("http://127.0.0.1:8795", linkEnabled))

    private val catalog = ExportCatalog(
        likes = 1_204,
        plays = 5_000,
        playlists = listOf(ExportPlaylistChoice(1, "Night drive", 30, false), ExportPlaylistChoice(2, "Robin's mix", 12, true), ExportPlaylistChoice(3, "Gym", 8, false)),
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { exporter.catalog() } returns catalog
        coEvery { inbox.targets() } returns listOf(chrome)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `an export shows its counts, then closes`() = runTest(dispatcher) {
        val result = WebLibraryExportResult(likes = 3, playlists = 2, plays = 40)
        coEvery { exporter.export(uri, any()) } returns Result.success(result)
        val vm = vm()

        vm.export(uri)
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Working)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Done(result))

        vm.dismiss()
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Idle)
    }

    @Test fun `a failure says so`() = runTest(dispatcher) {
        coEvery { exporter.export(uri, any()) } returns Result.failure(IllegalStateException("no stream"))
        val vm = vm()
        vm.export(uri)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Failed)
    }

    @Test fun `a second tap while writing does nothing, and the dialog can't be closed mid-write`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<WebLibraryExportResult>>()
        coEvery { exporter.export(uri, any()) } coAnswers { gate.await() }
        val vm = vm()

        vm.export(uri)
        advanceUntilIdle()
        vm.export(uri)
        vm.dismiss()
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Working)

        gate.complete(Result.success(WebLibraryExportResult(0, 0, 0)))
        advanceUntilIdle()
        coVerify(exactly = 1) { exporter.export(uri, any()) }
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Done(WebLibraryExportResult(0, 0, 0)))
    }

    @Test fun `the first time everything is ticked, and everything ticked writes today's file`() = runTest(dispatcher) {
        val chosen = slot<ExportSelection>()
        coEvery { exporter.export(uri, capture(chosen)) } returns Result.success(WebLibraryExportResult(0, 0, 0))
        val vm = vm()
        vm.openPicker()
        advanceUntilIdle()
        val pick = vm.picker.value!!.pick
        assertThat(pick.takesLikes && pick.takesPlays).isTrue()
        assertThat(pick.ticked).containsExactly("1", "2", "3")
        assertThat(vm.picker.value!!.targets).containsExactly(chrome)

        vm.chooseFile()
        assertThat(vm.picker.value).isNull()
        vm.export(uri)
        advanceUntilIdle()
        assertThat(chosen.captured).isEqualTo(ExportSelection.ALL)
    }

    @Test fun `the choice is what goes, and it is remembered by what was unticked`() = runTest(dispatcher) {
        val chosen = slot<ExportSelection>()
        coEvery { exporter.export(uri, capture(chosen)) } returns Result.success(WebLibraryExportResult(0, 0, 0))
        val vm = vm()
        vm.openPicker()
        advanceUntilIdle()
        vm.updatePick(vm.picker.value!!.pick.let { it.copy(playsOn = false, ticked = it.ticked - "2") })
        vm.chooseFile()
        vm.export(uri)
        advanceUntilIdle()
        assertThat(chosen.captured).isEqualTo(ExportSelection(likes = true, plays = false, playlistIds = setOf(1L, 3L)))
        assertThat(remembered).isEqualTo(WebLibraryPickerPrefs.Remembered(likes = true, plays = false, unticked = setOf(2L)))

        // Next time: the same choice, and a playlist made since is ticked.
        coEvery { exporter.catalog() } returns catalog.copy(playlists = catalog.playlists + ExportPlaylistChoice(4, "New", 1, false))
        vm.openPicker()
        advanceUntilIdle()
        val again = vm.picker.value!!.pick
        assertThat(again.takesPlays).isFalse()
        assertThat(again.ticked).containsExactly("1", "3", "4")
    }

    @Test fun `send goes to the browser's inbox with the chosen parts, and says where it went`() = runTest(dispatcher) {
        val file = WebLibraryFile(exportedAt = "2026-10-10T00:00:00.000Z", likes = emptyList(), playlists = emptyList(), history = emptyList())
        val chosen = slot<ExportSelection>()
        coEvery { exporter.collect(any(), any(), capture(chosen)) } returns file
        every { exporter.generator() } returns "Stash for Android test"
        every { exporter.text(file) } returns "{}"
        coEvery { inbox.send(chrome.id, "{}") } returns SendOutcome.Sent
        val vm = vm()
        vm.openPicker()
        advanceUntilIdle()
        vm.updatePick(vm.picker.value!!.pick.copy(likesOn = false))
        vm.send(chrome)
        advanceUntilIdle()
        assertThat(chosen.captured.likes).isFalse()
        assertThat(vm.picker.value).isNull()
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Sent("Chrome on Windows", WebLibraryExportResult(0, 0, 0)))
    }

    @Test fun `a send that can't go keeps the picker open with the reason`() = runTest(dispatcher) {
        val file = WebLibraryFile(exportedAt = "x", likes = emptyList(), playlists = emptyList(), history = emptyList())
        coEvery { exporter.collect(any(), any(), any()) } returns file
        every { exporter.generator() } returns "g"
        every { exporter.text(file) } returns "{}"
        coEvery { inbox.send(any(), any()) } returns SendOutcome.Failed(WebLinkInbox.TOO_BIG)
        val vm = vm()
        vm.openPicker()
        advanceUntilIdle()
        vm.send(chrome)
        advanceUntilIdle()
        assertThat(vm.picker.value!!.message).isEqualTo(WebLinkInbox.TOO_BIG)
        assertThat(vm.picker.value!!.sending).isFalse()
    }

    @Test fun `with the link switched off there is nowhere to send`() = runTest(dispatcher) {
        val vm = vm(linkEnabled = false)
        vm.openPicker()
        advanceUntilIdle()
        assertThat(vm.picker.value!!.targets).isEmpty()
        coVerify(exactly = 0) { inbox.targets() }
    }
}
