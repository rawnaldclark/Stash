package com.stash.feature.sync

import com.stash.core.data.db.dao.DownloadManagementRow
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.sync.DownloadCancellationController
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.data.download.DownloadManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadManagementViewModelTest {
    private val dao: DownloadQueueDao = mockk(relaxed = true)
    private val manager: DownloadManager = mockk(relaxed = true)
    private val enqueuer: SingleTrackDownloadEnqueuer = mockk(relaxed = true)
    private val cancellation: DownloadCancellationController = mockk(relaxed = true)
    private val progress = MutableSharedFlow<com.stash.data.download.model.DownloadProgress>()
    private val downloadMode = kotlinx.coroutines.flow.MutableStateFlow(com.stash.core.model.DownloadMode.LOSSLESS)
    private val losslessPrefs: com.stash.data.download.lossless.LosslessSourcePreferences = mockk {
        every { downloadMode } returns this@DownloadManagementViewModelTest.downloadMode
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { manager.progress } returns progress
        every { dao.observeActiveDownloadRows() } returns flowOf(emptyList())
        every { dao.observeDownloadHistory(100) } returns flowOf(emptyList())
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `active rows are partitioned into downloading queued and waiting`() = runTest {
        every { dao.observeActiveDownloadRows() } returns flowOf(
            listOf(
                row(1, DownloadStatus.IN_PROGRESS),
                row(2, DownloadStatus.PENDING),
                row(3, DownloadStatus.WAITING_FOR_LOSSLESS),
            ),
        )
        val vm = DownloadManagementViewModel(dao, manager, enqueuer, cancellation, losslessPrefs)
        val state = vm.uiState.first { !it.isLoading }
        assertEquals(listOf(1L), state.downloading.map { it.queueId })
        assertEquals(listOf(2L), state.queued.map { it.queueId })
        assertEquals(listOf(3L), state.waiting.map { it.queueId })
        assertEquals(null, state.downloading.single().progress)
    }

    @Test fun `in AAC 320 mode a waiting song reads as not available as AAC yet`() = runTest {
        every { dao.observeActiveDownloadRows() } returns flowOf(listOf(row(3, DownloadStatus.WAITING_FOR_LOSSLESS)))
        val vm = DownloadManagementViewModel(dao, manager, enqueuer, cancellation, losslessPrefs)

        assertEquals("Waiting for a lossless source", vm.uiState.first { !it.isLoading }.waiting.single().phaseLabel)

        downloadMode.value = com.stash.core.model.DownloadMode.AAC
        val aac = vm.uiState.first { it.aacMode }
        assertEquals("Not available as AAC yet", aac.waiting.single().phaseLabel)
    }

    @Test fun `every actionable status offers a way out`() = runTest {
        every { dao.observeActiveDownloadRows() } returns flowOf(
            listOf(
                row(1, DownloadStatus.IN_PROGRESS),
                row(2, DownloadStatus.PENDING),
                // Parked with no lossless source in sight — cancellable, or
                // the user is stuck watching it forever.
                row(3, DownloadStatus.WAITING_FOR_LOSSLESS),
            ),
        )
        every { dao.observeDownloadHistory(100) } returns flowOf(
            listOf(
                // Un-cancel: nothing else re-enqueues a SKIPPED row.
                row(4, DownloadStatus.SKIPPED),
                row(5, DownloadStatus.FAILED),
                row(6, DownloadStatus.FAILED, failureType = DownloadFailureType.NO_MATCH),
                row(7, DownloadStatus.COMPLETED),
            ),
        )
        val vm = DownloadManagementViewModel(dao, manager, enqueuer, cancellation, losslessPrefs)
        val state = vm.uiState.first { !it.isLoading }

        assertEquals(
            listOf(
                DownloadManagementAction.CANCEL,
                DownloadManagementAction.CANCEL,
                DownloadManagementAction.CANCEL,
            ),
            state.active.map { it.action },
        )
        assertEquals(
            listOf(
                DownloadManagementAction.RETRY,
                DownloadManagementAction.RETRY,
                // NO_MATCH has its own Failed Matches review flow.
                DownloadManagementAction.NONE,
                DownloadManagementAction.NONE,
            ),
            state.history.map { it.action },
        )
        assertEquals("Cancelled", state.history.first().phaseLabel)
    }

    @Test fun `retry enqueues only after atomic claim`() = runTest {
        coEvery { dao.atomicallyClaimForRetry(9) } returns 1
        val vm = DownloadManagementViewModel(dao, manager, enqueuer, cancellation, losslessPrefs)
        vm.retry(9)
        coVerify(exactly = 1) { enqueuer.enqueue(9) }
    }

    @Test fun `cancel delegates to cancellation controller`() = runTest {
        coEvery { cancellation.cancel(7) } returns true
        val vm = DownloadManagementViewModel(dao, manager, enqueuer, cancellation, losslessPrefs)
        vm.cancel(7)
        coVerify(exactly = 1) { cancellation.cancel(7) }
    }

    private fun row(
        id: Long,
        status: DownloadStatus,
        failureType: DownloadFailureType = DownloadFailureType.NONE,
    ) = DownloadManagementRow(
        queueId = id,
        trackId = id + 100,
        title = "Title $id",
        artist = "Artist",
        album = "Album",
        albumArtUrl = null,
        status = status,
        retryCount = 0,
        errorMessage = null,
        failureType = failureType,
        createdAt = id,
        completedAt = null,
    )
}
