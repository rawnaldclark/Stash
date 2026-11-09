package com.stash.feature.sync

import com.stash.core.data.db.dao.DownloadManagementRow
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.DownloadCancellationController
import com.stash.core.data.sync.DownloadQueueResumer
import com.stash.core.data.sync.QueueRunState
import com.stash.core.data.sync.ResumeTrigger
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
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val runState = MutableStateFlow<QueueRunState>(QueueRunState.Idle)
    private val online = MutableStateFlow(false)
    private val resumer: DownloadQueueResumer = mockk(relaxed = true)
    private val streaming: StreamingPreference = mockk(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { manager.progress } returns progress
        every { dao.observeActiveDownloadRows() } returns flowOf(emptyList())
        every { dao.observeDownloadHistory(100) } returns flowOf(emptyList())
        every { dao.observeRetryableFailedCount() } returns flowOf(0)
        every { resumer.runState() } returns runState
        every { streaming.enabled } returns online
    }

    private fun newVm() = DownloadManagementViewModel(dao, manager, enqueuer, cancellation, resumer, streaming)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `active rows are partitioned into downloading queued and waiting`() = runTest {
        every { dao.observeActiveDownloadRows() } returns flowOf(
            listOf(
                row(1, DownloadStatus.IN_PROGRESS),
                row(2, DownloadStatus.PENDING),
                row(3, DownloadStatus.WAITING_FOR_LOSSLESS),
            ),
        )
        val vm = newVm()
        val state = vm.uiState.first { !it.isLoading }
        assertEquals(listOf(1L), state.downloading.map { it.queueId })
        assertEquals(listOf(2L), state.queued.map { it.queueId })
        assertEquals(listOf(3L), state.waiting.map { it.queueId })
        assertEquals(null, state.downloading.single().progress)
    }

    @Test fun `each waiting row says why it waits, from its own row`() = runTest {
        every { dao.observeActiveDownloadRows() } returns flowOf(
            listOf(
                row(3, DownloadStatus.WAITING_FOR_LOSSLESS).copy(errorMessage = "wait:aac"),
                row(4, DownloadStatus.WAITING_FOR_LOSSLESS).copy(errorMessage = "wait:tidal_busy"),
                row(5, DownloadStatus.WAITING_FOR_LOSSLESS).copy(errorMessage = "wait:reset"),
                row(6, DownloadStatus.WAITING_FOR_LOSSLESS).copy(errorMessage = "wait:fallback"),
                row(7, DownloadStatus.WAITING_FOR_LOSSLESS), // from before reasons were kept
            ),
        )
        val state = newVm().uiState.first { !it.isLoading }

        assertEquals(
            listOf(
                "Not available as AAC yet",
                "Tidal is busy. Trying again shortly.",
                "Waiting for the daily reset",
                "Waiting for Lossy fallback to be on",
                "Waiting for a lossless source",
            ),
            state.waiting.map { it.phaseLabel },
        )
        assertEquals("Waiting", state.waitingLabel)
        // The reason code is not shown as an error.
        assertEquals(null, state.waiting.first().errorMessage)
    }

    @Test fun `waiting rows that share a reason head their section with it`() = runTest {
        every { dao.observeActiveDownloadRows() } returns flowOf(
            listOf(row(3, DownloadStatus.WAITING_FOR_LOSSLESS).copy(errorMessage = "wait:reset")),
        )
        val state = newVm().uiState.first { !it.isLoading }

        assertEquals("Waiting for the daily reset", state.waitingLabel)
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
        val vm = newVm()
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
        val vm = newVm()
        vm.retry(9)
        coVerify(exactly = 1) { enqueuer.enqueue(9) }
        coVerify(exactly = 1) { resumer.clearHold() }
    }

    @Test fun `cancel delegates to cancellation controller`() = runTest {
        coEvery { cancellation.cancel(7) } returns true
        val vm = newVm()
        vm.cancel(7)
        coVerify(exactly = 1) { cancellation.cancel(7) }
    }

    // ── The queue's status line and its button ─────────────────────────

    @Test fun `the button follows what the queue is doing`() {
        assertEquals(QueueButton.PAUSE, queueButtonFor(QueueRunState.Running, activeCount = 40))
        assertEquals(QueueButton.PAUSE, queueButtonFor(QueueRunState.Scheduled, activeCount = 40))
        assertEquals(QueueButton.DOWNLOAD_NOW, queueButtonFor(QueueRunState.WaitingForWifi, activeCount = 37))
        assertEquals(QueueButton.DOWNLOAD_NOW, queueButtonFor(QueueRunState.RetryAt(1L), activeCount = 37))
        assertEquals(null, queueButtonFor(QueueRunState.WaitingForConnection, activeCount = 37))
        assertEquals(QueueButton.RESUME, queueButtonFor(QueueRunState.Paused, activeCount = 37))
        assertEquals("paused over an empty queue: nothing to resume", null, queueButtonFor(QueueRunState.Paused, activeCount = 0))
        assertEquals(QueueButton.RESUME, queueButtonFor(QueueRunState.StorageFull, activeCount = 37))
        assertEquals("queued with nothing set to run them", QueueButton.RESUME, queueButtonFor(QueueRunState.Idle, activeCount = 37))
        // Nothing queued: nothing to pause or start, but a pause can always be lifted.
        assertEquals(null, queueButtonFor(QueueRunState.Idle, activeCount = 0))
        assertEquals(null, queueButtonFor(QueueRunState.WaitingForWifi, activeCount = 0))
        assertEquals(QueueButton.RESUME, queueButtonFor(QueueRunState.Paused, activeCount = 0))
    }

    @Test fun `the state and the button come from the queue's run state`() = runTest {
        every { dao.observeActiveDownloadRows() } returns flowOf(listOf(row(2, DownloadStatus.PENDING)))
        val vm = newVm()

        runState.value = QueueRunState.WaitingForWifi
        assertEquals(QueueButton.DOWNLOAD_NOW, vm.uiState.first { it.runState == QueueRunState.WaitingForWifi }.queueButton)
        runState.value = QueueRunState.Paused
        assertEquals(QueueButton.RESUME, vm.uiState.first { it.runState == QueueRunState.Paused }.queueButton)
    }

    @Test fun `Resume and Download now start the queue as the user, Pause pauses it`() = runTest {
        val vm = newVm()
        vm.resumeDownloads()
        coVerify(exactly = 1) { resumer.resume(ResumeTrigger.USER, any()) }
        vm.pause()
        coVerify(exactly = 1) { resumer.pause() }
    }

    @Test fun `retry all failed claims the rows and runs them once`() = runTest {
        coEvery { dao.atomicallyClaimAllForRetry() } returns listOf(4L, 5L)
        newVm().retryAllFailed()
        coVerify(exactly = 1) { resumer.retryClaimed(listOf(4L, 5L)) }
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test fun `the retry-all count comes from the database`() = runTest {
        every { dao.observeRetryableFailedCount() } returns flowOf(3)
        assertEquals(3, newVm().uiState.first { !it.isLoading }.retryableFailedCount)
    }

    @Test fun `Online mode lists a sync's songs apart, as waiting for Offline`() = runTest {
        online.value = true
        every { dao.observeActiveDownloadRows() } returns flowOf(
            listOf(
                row(1, DownloadStatus.PENDING).copy(syncId = 9L),
                row(2, DownloadStatus.PENDING),
            ),
        )
        val state = newVm().uiState.first { !it.isLoading }
        assertEquals(listOf(2L), state.queued.map { it.queueId })
        assertEquals(listOf(1L), state.offlineOnly.map { it.queueId })
    }

    @Test fun `Offline mode keeps a sync's songs in Up next`() = runTest {
        every { dao.observeActiveDownloadRows() } returns flowOf(listOf(row(1, DownloadStatus.PENDING).copy(syncId = 9L)))
        val state = newVm().uiState.first { !it.isLoading }
        assertEquals(listOf(1L), state.queued.map { it.queueId })
        assertEquals(emptyList<Long>(), state.offlineOnly.map { it.queueId })
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
