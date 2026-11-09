package com.stash.feature.sync

import com.stash.core.data.blocklist.BlockSource
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.FailedDownloadRow
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.DownloadQueueResumer
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadFailureType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class FailedDownloadsViewModelTest {

    private val dao: DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val enqueuer: SingleTrackDownloadEnqueuer = mockk(relaxed = true)
    private val guard: BlocklistGuard = mockk(relaxed = true)
    private val resumer: DownloadQueueResumer = mockk(relaxed = true)
    private val vm by lazy {
        // dao.getFailedDownloads() is collected eagerly by the VM constructor's StateFlow.
        // Stub it before constructing the VM in each test so the stateIn pipeline has a source.
        coEvery { dao.getFailedDownloads() } returns flowOf(emptyList())
        FailedDownloadsViewModel(dao, trackDao, enqueuer, guard, resumer)
    }

    @Before fun setUp() {
        // viewModelScope dispatches on Dispatchers.Main; the StateFlow upstream collection
        // only runs once a subscriber attaches. UnconfinedTestDispatcher lets the
        // grouping test's `uiState.first { ... }` see the mapped emission immediately.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `retry only enqueues when atomic claim succeeds`() = runTest {
        coEvery { dao.atomicallyClaimForRetry(42) } returns 1
        vm.retry(42)
        coVerify(exactly = 1) { enqueuer.enqueue(42) }
        // The tap ends a Pause: a paused retry would wait for Resume without saying so.
        coVerify(exactly = 1) { resumer.clearHold() }
    }

    @Test fun `retry does not enqueue when row is no longer FAILED`() = runTest {
        coEvery { dao.atomicallyClaimForRetry(42) } returns 0
        vm.retry(42)
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test fun `retryGroup hands every claimed id to one resume`() = runTest {
        coEvery { dao.atomicallyClaimGroupForRetry(DownloadFailureType.AUTH_EXPIRED) } returns listOf(1L, 2L, 3L)
        vm.retryGroup(DownloadFailureType.AUTH_EXPIRED)
        coVerify(exactly = 1) { resumer.retryClaimed(listOf(1L, 2L, 3L)) }
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test fun `retryAll hands every non-match claimed id to one resume`() = runTest {
        coEvery { dao.atomicallyClaimAllForRetry() } returns listOf(7L, 8L)
        vm.retryAll()
        coVerify(exactly = 1) { resumer.retryClaimed(listOf(7L, 8L)) }
        coVerify(exactly = 0) { enqueuer.enqueue(any()) }
    }

    @Test fun `block resolves track then calls guard with FAILED_DOWNLOADS source`() = runTest {
        val track = mockk<TrackEntity>(relaxed = true)
        coEvery { trackDao.getById(7) } returns track
        vm.block(7)
        coVerify(exactly = 1) { guard.block(track, BlockSource.FAILED_DOWNLOADS) }
    }

    @Test fun `block is no-op when track not found`() = runTest {
        coEvery { trackDao.getById(99) } returns null
        vm.block(99)
        coVerify(exactly = 0) { guard.block(any(), any()) }
    }

    @Test fun `uiState groups rows by failure type in display order and skips empty groups`() = runTest {
        val row1 = mockk<FailedDownloadRow>(relaxed = true).also {
            every { it.failureType } returns DownloadFailureType.NETWORK
        }
        val row2 = mockk<FailedDownloadRow>(relaxed = true).also {
            every { it.failureType } returns DownloadFailureType.AUTH_EXPIRED
        }
        val row3 = mockk<FailedDownloadRow>(relaxed = true).also {
            every { it.failureType } returns DownloadFailureType.NETWORK
        }
        // Stub BEFORE constructing the VM: the VM grabs the Flow reference in its
        // initializer, so we have to win the race against the lazy `vm` builder
        // (which stubs with an empty list as the default).
        coEvery { dao.getFailedDownloads() } returns flowOf(listOf(row1, row2, row3))
        val localVm = FailedDownloadsViewModel(dao, trackDao, enqueuer, guard, resumer)

        // Subscribe to the StateFlow until we see a non-loading state.
        val state = localVm.uiState.first { !it.isLoading }
        assertEquals(2, state.groups.size)
        assertEquals(DownloadFailureType.AUTH_EXPIRED, state.groups[0].type)  // ordering
        assertEquals(DownloadFailureType.NETWORK, state.groups[1].type)
        assertEquals(1, state.groups[0].rows.size)
        assertEquals(2, state.groups[1].rows.size)
        assertEquals(3, state.totalCount)
    }
}
