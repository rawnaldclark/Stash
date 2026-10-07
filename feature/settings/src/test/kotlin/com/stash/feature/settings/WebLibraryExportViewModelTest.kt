package com.stash.feature.settings

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.weblibrary.WebLibraryExportResult
import com.stash.core.data.weblibrary.WebLibraryExporter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `an export shows its counts, then closes`() = runTest(dispatcher) {
        val result = WebLibraryExportResult(likes = 3, playlists = 2, plays = 40)
        coEvery { exporter.export(uri) } returns Result.success(result)
        val vm = WebLibraryExportViewModel(exporter)

        vm.export(uri)
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Working)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Done(result))

        vm.dismiss()
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Idle)
    }

    @Test fun `a failure says so`() = runTest(dispatcher) {
        coEvery { exporter.export(uri) } returns Result.failure(IllegalStateException("no stream"))
        val vm = WebLibraryExportViewModel(exporter)
        vm.export(uri)
        advanceUntilIdle()
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Failed)
    }

    @Test fun `a second tap while writing does nothing, and the dialog can't be closed mid-write`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<WebLibraryExportResult>>()
        coEvery { exporter.export(uri) } coAnswers { gate.await() }
        val vm = WebLibraryExportViewModel(exporter)

        vm.export(uri)
        advanceUntilIdle()
        vm.export(uri)
        vm.dismiss()
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Working)

        gate.complete(Result.success(WebLibraryExportResult(0, 0, 0)))
        advanceUntilIdle()
        coVerify(exactly = 1) { exporter.export(uri) }
        assertThat(vm.state.value).isEqualTo(WebLibraryExportState.Done(WebLibraryExportResult(0, 0, 0)))
    }
}
