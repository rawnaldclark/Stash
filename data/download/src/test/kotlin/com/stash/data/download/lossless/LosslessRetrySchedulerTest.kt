package com.stash.data.download.lossless

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.stash.data.download.lossless.quota.LosslessQuota
import io.mockk.slot
import java.util.UUID
import java.util.concurrent.TimeUnit
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.17 strict-FLAC: tests the reactive eager-bound scheduler that
 * enqueues [LosslessRetryWorker] on each signal that could plausibly turn
 * a previously-null lossless resolution into a non-null one: a breaker
 * reset ([AggregatorRateLimiter.circuitResetEvents]), the daily allowance's
 * reset, a Download quality change and Tidal coming free.
 *
 * The scheduler exposes [LosslessRetryScheduler.scope] and
 * [LosslessRetryScheduler.workManagerProvider] as test seams so the
 * test can pass `runTest`'s `backgroundScope` (for virtual-time control
 * over the booked delays) and a MockK [WorkManager] (instead
 * of routing through `WorkManager.getInstance(context)`, which trips
 * `WorkManagerImpl`'s "not initialised" guard in JVM unit tests).
 *
 * [UnconfinedTestDispatcher] (rather than the default [StandardTestDispatcher])
 * is required because [MutableStateFlow] conflates updates: with
 * StandardTestDispatcher, the collectors don't actually subscribe until
 * `advanceUntilIdle()`, by which point a `value = X` update has already
 * overwritten the initial null and `drop(1)` consumes the value we want
 * to verify. Unconfined runs the launched collectors synchronously at
 * `launchIn` time, so they subscribe before the test mutates the flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LosslessRetrySchedulerTest {

    private val context: Context = mockk(relaxed = true)
    private val workManager: WorkManager = mockk(relaxed = true)
    private val downloadModeFlow = MutableStateFlow(com.stash.core.model.DownloadMode.LOSSLESS)
    private val resetEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val losslessPrefs: LosslessSourcePreferences = mockk {
        every { downloadMode } returns downloadModeFlow
    }
    private val rateLimiter: AggregatorRateLimiter = mockk {
        every { circuitResetEvents } returns resetEvents
    }

    private var now = 1_000_000_000_000L
    private val quota = LosslessQuota.inMemory { now }
    private val tidalRetries = kotlinx.coroutines.flow.MutableSharedFlow<Long>(extraBufferCapacity = 4)
    private val tidalAacResolver: com.stash.data.download.lossless.tidal.TidalAacResolver = mockk {
        every { retryEvents } returns tidalRetries
    }

    private fun newSubject(scope: kotlinx.coroutines.CoroutineScope): LosslessRetryScheduler {
        val subject = LosslessRetryScheduler(
            context = context,
            losslessPrefs = losslessPrefs,
            rateLimiter = rateLimiter,
            quota = quota,
            tidalAacResolver = tidalAacResolver,
        )
        subject.scope = scope
        subject.workManagerProvider = { workManager }
        subject.nowMs = { now }
        subject.start()
        return subject
    }

    /** What WorkManager holds under the Tidal sweep's name when the next booking looks. */
    private fun tidalSweeps(vararg sweeps: WorkInfo) {
        every { workManager.getWorkInfosForUniqueWork(LosslessRetryScheduler.TIDAL_WORK_NAME) } returns
            mockk { every { get() } returns sweeps.toList() }
    }

    /** A Tidal sweep in [state]; an ENQUEUED one is due [dueInMs] from now. */
    private fun sweep(state: WorkInfo.State, dueInMs: Long = 0L) = WorkInfo(
        UUID.randomUUID(), state, emptySet(), Data.EMPTY, Data.EMPTY, 0, 0, Constraints.NONE, 0L, null,
        if (state == WorkInfo.State.ENQUEUED) now + dueInMs else Long.MAX_VALUE,
    )

    /** Every Tidal sweep booking: the policy it went in with, and the request. */
    private fun tidalBookings(): Pair<MutableList<ExistingWorkPolicy>, MutableList<OneTimeWorkRequest>> {
        val policies = mutableListOf<ExistingWorkPolicy>()
        val requests = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(LosslessRetryScheduler.TIDAL_WORK_NAME, capture(policies), capture(requests))
        } returns mockk(relaxed = true)
        return policies to requests
    }

    @Test
    fun `a Download quality change sweeps the waiting rows under the new mode, on its own name`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)
        // Nothing at start: the stored mode's first emission is not a change.
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(LosslessRetryScheduler.MODE_WORK_NAME, any(), any<OneTimeWorkRequest>())
        }

        downloadModeFlow.value = com.stash.core.model.DownloadMode.AAC
        advanceUntilIdle()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                LosslessRetryScheduler.MODE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `Tidal busy for a waiting download books a Tidal-only sweep for when it should be free`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)
        tidalSweeps()
        val (policies, requests) = tidalBookings()

        tidalRetries.emit(now + 60_000)
        advanceUntilIdle()

        assertEquals(listOf(ExistingWorkPolicy.APPEND_OR_REPLACE), policies)
        val request = requests.single()
        assertEquals(60_000L, request.workSpec.initialDelay)
        assertTrue(request.workSpec.input.getBoolean(LosslessRetryWorker.KEY_TIDAL_BUSY_ONLY, false))
        assertFalse(request.workSpec.input.getBoolean(LosslessRetryWorker.KEY_AT_RESET, false))
        verify(exactly = 0) { workManager.enqueueUniqueWork(LosslessRetryWorker.UNIQUE_WORK_NAME, any(), any<OneTimeWorkRequest>()) }
    }

    /** The owner's "they aren't restarting": KEEP counted the running sweep as existing work and dropped the booking. */
    @Test
    fun `a busy answer while the Tidal sweep runs, or is about to, books another after it`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)
        val (policies, _) = tidalBookings()

        tidalSweeps(sweep(WorkInfo.State.RUNNING))
        tidalRetries.emit(now + 30_000)
        advanceUntilIdle()

        // Due within the margin: it may read the waiting rows before this song is written back.
        tidalSweeps(sweep(WorkInfo.State.ENQUEUED, dueInMs = LosslessRetryScheduler.TIDAL_KEEP_MARGIN_MS - 1_000))
        tidalRetries.emit(now + 30_000)
        advanceUntilIdle()

        // State unreadable: booking behind whatever is there costs at most one extra sweep.
        every { workManager.getWorkInfosForUniqueWork(LosslessRetryScheduler.TIDAL_WORK_NAME) } returns
            mockk { every { get() } throws java.util.concurrent.ExecutionException(IllegalStateException("db")) }
        tidalRetries.emit(now + 30_000)
        advanceUntilIdle()

        assertEquals(List(3) { ExistingWorkPolicy.APPEND_OR_REPLACE }, policies)
    }

    @Test
    fun `a Tidal sweep still ahead is kept, so busy answers never chain more than one behind another`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)
        val (policies, _) = tidalBookings()

        // Booked and not due for a while: it re-queues every song that waits before it starts.
        tidalSweeps(sweep(WorkInfo.State.ENQUEUED, dueInMs = 25_000))
        tidalRetries.emit(now + 30_000)
        advanceUntilIdle()

        // One already waits behind a running sweep: it runs its own delay after that one.
        tidalSweeps(sweep(WorkInfo.State.RUNNING), sweep(WorkInfo.State.BLOCKED))
        tidalRetries.emit(now + 30_000)
        advanceUntilIdle()

        assertEquals(List(2) { ExistingWorkPolicy.KEEP }, policies)
    }

    @Test
    fun `circuit-reset event enqueues retry worker`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)

        // SharedFlow has no replay so drop(1) isn't needed here — but
        // we still need the collector subscribed before emit() lands,
        // otherwise tryEmit-style fire-and-forget semantics drop it.
        // Unconfined dispatcher guarantees that subscription has happened
        // by the time newSubject() returns.
        resetEvents.emit("qbdlx_qobuz")
        advanceUntilIdle()

        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                LosslessRetryWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                any<OneTimeWorkRequest>(),
            )
        }
    }

    @Test
    fun `a spent allowance books a re-queue-only sweep just after the reset, under its own name`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)
        val requests = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(LosslessRetryScheduler.RESET_WORK_NAME, ExistingWorkPolicy.KEEP, capture(requests))
        } returns mockk(relaxed = true)

        val resetsAt = now / 1000 + 7200
        quota.onHeaders("499/500", "$resetsAt")
        advanceUntilIdle()
        assertTrue("not spent yet: nothing booked", requests.isEmpty())

        quota.onHeaders("500/500", "$resetsAt")
        advanceUntilIdle()

        // KEEP on its own name: WorkManager keeps the one already waiting.
        assertTrue(requests.isNotEmpty())
        verify(exactly = 0) {
            workManager.enqueueUniqueWork(LosslessRetryWorker.UNIQUE_WORK_NAME, any(), any<OneTimeWorkRequest>())
        }
        requests.forEach { r ->
            assertEquals(TimeUnit.SECONDS.toMillis(7200 + LosslessRetryScheduler.RESET_SLACK_SEC), r.workSpec.initialDelay)
            assertTrue(r.workSpec.input.getBoolean(LosslessRetryWorker.KEY_AT_RESET, false))
            assertEquals(BackoffPolicy.LINEAR, r.workSpec.backoffPolicy)
        }
    }

    @Test
    fun `the same spent day reported again books again, for a sweep that ran too early`() = runTest(UnconfinedTestDispatcher()) {
        newSubject(backgroundScope)
        val requests = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(LosslessRetryScheduler.RESET_WORK_NAME, ExistingWorkPolicy.KEEP, capture(requests))
        } returns mockk(relaxed = true)
        val resetsAt = now / 1000 + 7200
        quota.onHeaders("500/500", "$resetsAt")
        advanceUntilIdle()
        val first = requests.size

        // The sweep has run; a download it re-queued comes back capped for the same day.
        now += 3_600_000
        quota.onCapBody("""{"error":"daily_lossless_cap","used":500,"limit":500,"resetsAt":$resetsAt}""")
        advanceUntilIdle()

        assertEquals(first + 1, requests.size)
        assertEquals(TimeUnit.SECONDS.toMillis(3600 + LosslessRetryScheduler.RESET_SLACK_SEC), requests.last().workSpec.initialDelay)
    }
}
