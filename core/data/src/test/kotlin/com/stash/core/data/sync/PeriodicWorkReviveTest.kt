package com.stash.core.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * [enqueueUniquePeriodicWorkReviving]: a periodic job that failed comes back,
 * and a live one is left alone. The reported bug: the daily mix refresh threw
 * once on a fresh install, and UPDATE on every later app start left it FAILED.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PeriodicWorkReviveTest {

    /** A periodic worker that throws, as the mix refresh did on its first run. */
    class ThrowingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = error("UNIQUE constraint failed: playlists.source_id")
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workManager: WorkManager

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test fun `a FAILED periodic job is enqueued again`() = runBlocking {
        failJob()

        val next = request()
        workManager.enqueueUniquePeriodicWorkReviving(NAME, ExistingPeriodicWorkPolicy.UPDATE, next)

        val info = job()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(next.id, info.id)
    }

    @Test fun `a live periodic job keeps its id and its schedule`() = runBlocking {
        val live = request()
        workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, live).result.get()

        workManager.enqueueUniquePeriodicWorkReviving(NAME, ExistingPeriodicWorkPolicy.UPDATE, request())

        val info = job()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(live.id, info.id)
    }

    /** Runs a throwing periodic job once, which leaves it FAILED for good. */
    private fun failJob() {
        val failing = PeriodicWorkRequestBuilder<ThrowingWorker>(1, TimeUnit.DAYS).build()
        workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, failing).result.get()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setPeriodDelayMet(failing.id)
        assertEquals(WorkInfo.State.FAILED, job().state)

        // The bug: re-registering with UPDATE (what every app start did) doesn't bring it back.
        workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request()).result.get()
        assertEquals(WorkInfo.State.FAILED, job().state)
    }

    /** A job that won't run during the test: its first run is an hour away. */
    private fun request(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<ThrowingWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()

    private fun job(): WorkInfo = workManager.getWorkInfosForUniqueWork(NAME).get().single()

    private companion object {
        const val NAME = "revive_test_periodic"
    }
}
