package com.stash.core.data.social

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.LikePreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The worker that sends the outbox when the app isn't running: it waits for a connection, backs off exponentially from 30 s,
 * keeps a drain already waiting or running (a new heart never restarts it), retries while likes are left to try, and stops
 * when only parked ones are left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LikeMirrorWorkerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The stand-in drain finishes when this completes: held by the test, so GC can't fail the RUNNING work mid-test. */
    private val neverDone = CompletableDeferred<ListenableWorker.Result>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatcher = mockk<LikeDestinationDispatcher>()
    private val trackDao = mockk<TrackDao> { coEvery { getById(any()) } answers { TrackEntity(id = firstArg(), title = "t", artist = "a", spotifyUri = "spotify:track:x") } }
    private val prefs = mockk<LikePreferences> {
        coEvery { mirrorLikesSpotifyNow() } returns true
        coEvery { mirrorLikesYtMusicNow() } returns false
        coEvery { mirrorLikesLastFmNow() } returns false
    }

    @Before fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork(): Result = neverDone.await()
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
    }

    @After fun tearDown() {
        neverDone.complete(ListenableWorker.Result.success())
        scope.cancel()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun drain(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(LikeMirrorWorker.UNIQUE_WORK_NAME).get()

    private fun spec(info: WorkInfo): WorkSpec =
        WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!

    @Test fun `it waits for a connection and backs off exponentially from 30 seconds`() {
        WorkManagerLikeMirrorScheduler(context).schedule(LikeCoordinator.SAFETY_NET_DELAY_MS)

        val spec = spec(drain().single())
        assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(spec.backoffPolicy).isEqualTo(BackoffPolicy.EXPONENTIAL)
        assertThat(spec.backoffDelayDuration).isEqualTo(LikeMirrorWorker.BACKOFF_SECONDS * 1_000L)
        assertThat(spec.initialDelay).isEqualTo(LikeCoordinator.SAFETY_NET_DELAY_MS)
    }

    @Test fun `a drain already running is kept, never restarted by the next heart`() {
        val scheduler = WorkManagerLikeMirrorScheduler(context)
        scheduler.schedule()
        val running = drain().single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(running.id)
        assertThat(drain().single().state).isEqualTo(WorkInfo.State.RUNNING)

        scheduler.schedule()
        scheduler.schedule(LikeCoordinator.SAFETY_NET_DELAY_MS)

        val after = drain()
        assertThat(after).hasSize(1)
        assertThat(after.single().id).isEqualTo(running.id)
        assertThat(after.single().state).isEqualTo(WorkInfo.State.RUNNING)
    }

    // -- The worker's own answer --------------------------------------------------------------------------------------------

    private fun worker(outbox: LikeOutbox): LikeMirrorWorker {
        val coordinator = LikeCoordinator(
            mockk(relaxed = true), prefs, dispatcher, trackDao, mockk(relaxed = true), outbox, FakeLikeMirrorScheduler(), scope, 0L,
        )
        return TestListenableWorkerBuilder<LikeMirrorWorker>(context)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                        LikeMirrorWorker(appContext, workerParameters, coordinator)
                },
            )
            .build()
    }

    /** A like that already failed once: the in-process sender leaves it to the worker. */
    private fun failedOnce(attempts: Int = 1) = LikeOutbox(
        MemoryLikeOutboxStorage(
            LikeOutboxState(
                nextSeq = 2L,
                ops = listOf(LikeOutboxOp(1L, 7L, Destination.SPOTIFY, liked = true, queuedAt = 0L, attempts = attempts, lastError = LikeMirrorError.REFUSED, lastAttemptAt = 1L)),
            ),
        ),
    )

    @Test fun `it retries while the service keeps saying no`() = runBlocking {
        coEvery { dispatcher.like(any(), any()) } returns mapOf(Destination.SPOTIFY to Result.failure(RuntimeException("down")))
        val outbox = failedOnce()

        assertThat(worker(outbox).doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(outbox.current().single().attempts).isEqualTo(2)
    }

    @Test fun `it is done once the like went through`() = runBlocking {
        coEvery { dispatcher.like(any(), any()) } returns mapOf(Destination.SPOTIFY to Result.success(Unit))
        val outbox = failedOnce()

        assertThat(worker(outbox).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(outbox.current()).isEmpty()
    }

    @Test fun `it stops when only parked likes are left, waiting for tap to retry`() = runBlocking {
        val outbox = failedOnce(attempts = LikeOutbox.MAX_ATTEMPTS)

        assertThat(worker(outbox).doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(outbox.current()).hasSize(1)
    }
}
