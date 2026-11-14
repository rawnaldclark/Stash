package com.stash.data.download.lossless

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.SingleTrackDownloadEnqueuer
import com.stash.core.model.DownloadMode
import com.stash.core.model.DownloadStatus
import com.stash.core.model.DownloadWaitReason
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.tidal.TidalAacResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Tidal sweep (LosslessRetryScheduler trigger 4) on a real test WorkManager, so its booking
 * is checked against WorkManager's own unique-work rules rather than a mock of them.
 *
 * The owner's "they aren't restarting": a song that waited for a busy Tidal while the Tidal sweep
 * was RUNNING asked for a sweep with KEEP, which counts the running one as existing work and drops
 * the booking. The running sweep had already read the waiting rows, so that song stayed waiting
 * until something else happened to sweep. The real [LosslessRetryWorker] runs here, its DAO held
 * mid-read by the test so the first sweep is RUNNING when the second song asks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LosslessRetryTidalSweepTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Held by the test: the first sweep stays RUNNING, inside its read of the waiting rows. */
    private val firstSweepGate = CompletableDeferred<Unit>()
    private val firstSweepReading = CompletableDeferred<Unit>()
    private val reads = AtomicInteger()

    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true) {
        coEvery { requeueIfStillWaiting(any()) } returns 1
        coEvery { waitingForLosslessTracks(any()) } coAnswers {
            if (reads.getAndIncrement() == 0) {
                firstSweepReading.complete(Unit)
                firstSweepGate.await()
                listOf(waitingForTidal(id = 100L, trackId = 1L)) // song A
            } else {
                listOf(
                    waitingForTidal(id = 200L, trackId = 2L), // song B, written back while it ran
                    // Waits for lossless: a general sweep would look it up, a Tidal sweep leaves it.
                    waitingForTidal(id = 300L, trackId = 3L).copy(errorMessage = DownloadWaitReason.LOSSLESS.code),
                )
            }
        }
    }
    private val trackDao: TrackDao = mockk {
        coEvery { getById(any()) } answers { TrackEntity(id = firstArg(), title = "t", artist = "a") }
    }
    private val registry: LosslessSourceRegistry = mockk()
    private val enqueuer: SingleTrackDownloadEnqueuer = mockk(relaxed = true)
    private val streamingPreference: StreamingPreference = mockk { coEvery { current() } returns false }
    private val quota = LosslessQuota.inMemory { System.currentTimeMillis() }
    private val losslessPrefs: LosslessSourcePreferences = mockk {
        every { downloadMode } returns MutableStateFlow(DownloadMode.LOSSLESS)
        coEvery { downloadModeNow() } returns DownloadMode.LOSSLESS
    }
    private val rateLimiter: AggregatorRateLimiter = mockk {
        every { circuitResetEvents } returns MutableSharedFlow()
    }
    private val tidalRetries = MutableSharedFlow<Long>()
    private val tidalAacResolver: TidalAacResolver = mockk {
        every { retryEvents } returns tidalRetries
    }

    /** Collects the scheduler's signals on the calling thread, so a booking is made by the time emit returns. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                LosslessRetryWorker(
                    appContext = appContext,
                    params = workerParameters,
                    downloadQueueDao = downloadQueueDao,
                    trackDao = trackDao,
                    registry = registry,
                    singleTrackDownloadEnqueuer = enqueuer,
                    streamingPreference = streamingPreference,
                    quota = quota,
                    losslessPrefs = losslessPrefs,
                    tidalAacResolver = tidalAacResolver,
                    handoff = ResolvedSourceHandoff(),
                    cursor = RetrySweepCursor.inMemory(),
                )
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
        LosslessRetryScheduler(context, losslessPrefs, rateLimiter, quota, tidalAacResolver).also {
            it.scope = scope
            it.start()
        }
    }

    @After fun tearDown() {
        firstSweepGate.complete(Unit)
        scope.cancel()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test fun `a song that waits for Tidal while the Tidal sweep runs is re-queued by one booked after it`() = runBlocking<Unit> {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!

        // Song A waited for a busy Tidal: a sweep for when it should be free.
        tidalRetries.emit(System.currentTimeMillis() + 30_000L)
        val first = tidalSweeps().single()
        assertThat(first.state).isEqualTo(WorkInfo.State.ENQUEUED)
        driver.setInitialDelayMet(first.id)
        withTimeout(10_000L) { firstSweepReading.await() }
        assertThat(info(first.id).state).isEqualTo(WorkInfo.State.RUNNING)

        // Song B waits for Tidal while that sweep runs, after it has read the waiting rows.
        tidalRetries.emit(System.currentTimeMillis() + 30_000L)
        val second = tidalSweeps().singleOrNull { it.id != first.id }
        assertThat(second).isNotNull() // KEEP dropped it: the bug
        assertThat(second!!.state).isEqualTo(WorkInfo.State.BLOCKED)

        // A third song now finds one waiting behind the running sweep and keeps it: no chain.
        tidalRetries.emit(System.currentTimeMillis() + 30_000L)
        assertThat(tidalSweeps()).hasSize(2)

        // The first sweep re-queues song A and ends; the second becomes due and re-queues song B.
        firstSweepGate.complete(Unit)
        awaitState(first.id, WorkInfo.State.SUCCEEDED)
        awaitState(second.id, WorkInfo.State.ENQUEUED)
        driver.setInitialDelayMet(second.id)
        awaitState(second.id, WorkInfo.State.SUCCEEDED)

        coVerify(exactly = 1) { enqueuer.enqueue(100L) }
        coVerify(exactly = 1) { enqueuer.enqueue(200L) }
        // Tidal sweeps only re-queue the songs that waited for Tidal: nothing is looked up.
        coVerify(exactly = 0) { enqueuer.enqueue(300L) }
        coVerify(exactly = 0) { registry.resolve(any()) }
        coVerify(exactly = 0) { tidalAacResolver.resolve(any(), any(), any()) }
    }

    private fun waitingForTidal(id: Long, trackId: Long) = DownloadQueueEntity(
        id = id,
        trackId = trackId,
        status = DownloadStatus.WAITING_FOR_LOSSLESS,
        searchQuery = "q",
        errorMessage = DownloadWaitReason.TIDAL_BUSY.code,
    )

    private fun tidalSweeps(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(LosslessRetryScheduler.TIDAL_WORK_NAME).get()

    private fun info(id: UUID): WorkInfo = WorkManager.getInstance(context).getWorkInfoById(id).get()!!

    /**
     * The worker finishes on a coroutine thread, and WorkManager hands the next sweep to its
     * scheduler through the main looper (the test's), so idle it while waiting.
     */
    private fun awaitState(id: UUID, state: WorkInfo.State) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val now = info(id).state
            if (now == state) return
            check(System.currentTimeMillis() < deadline) { "sweep $id is $now, not $state" }
            Thread.sleep(10L)
        }
    }
}
