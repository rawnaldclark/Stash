package com.stash.feature.record.lastfm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the lengths pass's job asks of WorkManager: the pass at once with no network (the free fill needs none), the lookups'
 * wait by the app's Wi-Fi-only setting (unmetered, or any network, as its syncs and cloud uploads wait), and a change of the
 * setting reaching a pass still waiting, never one running (SyncScheduler `applyWifiOnlyToWaitingSync`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordLengthsWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var work: WorkManager

    /** A pass that runs holds until this completes. */
    private val gate = CompletableDeferred<Unit>()

    @Before fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                object : CoroutineWorker(appContext, workerParameters) {
                    override suspend fun doWork(): Result {
                        gate.await()
                        return Result.success()
                    }
                }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).setWorkerFactory(factory).build(),
        )
        work = WorkManager.getInstance(context)
    }

    @After fun tearDown() {
        gate.complete(Unit)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    /** The pass's jobs not yet finished. */
    private fun pass(): List<WorkInfo> = work.getWorkInfosForUniqueWork("record-lengths").get().filter { !it.state.isFinished }

    @Test fun `a pass asked for runs at once with no network asked of it - the free fill needs none`() {
        RecordLengthsWorker.start(context)
        val job = pass().single()
        assertThat(job.constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
        assertThat(job.state).isEqualTo(WorkInfo.State.RUNNING)
    }

    @Test fun `held for a network, the pass waits by the Wi-Fi-only setting - unmetered, or any network`() {
        RecordLengthsWorker.waitForNetwork(context, wifiOnly = true)
        assertThat(pass().single().constraints.requiredNetworkType).isEqualTo(NetworkType.UNMETERED)
        RecordLengthsWorker.cancel(context)
        RecordLengthsWorker.waitForNetwork(context, wifiOnly = false)
        assertThat(pass().single().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test fun `a wait asked for as a run ends queues behind it, and a pass asked for after takes its place`() {
        RecordLengthsWorker.start(context)
        RecordLengthsWorker.waitForNetwork(context, wifiOnly = true)
        assertThat(pass().map { it.state }).containsExactly(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
        RecordLengthsWorker.start(context)
        assertThat(pass().single().constraints.requiredNetworkType).isEqualTo(NetworkType.NOT_REQUIRED)
    }

    @Test fun `a change of the setting reaches a pass waiting for a network, never one running`() = runBlocking {
        RecordLengthsWorker.waitForNetwork(context, wifiOnly = true)
        val first = pass().single().id
        RecordLengthsWorker.applyWifiOnly(context, wifiOnly = false)
        val now = pass().single()
        assertThat(now.id).isNotEqualTo(first)
        assertThat(now.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        // The same setting again: left as it is
        RecordLengthsWorker.applyWifiOnly(context, wifiOnly = false)
        assertThat(pass().single().id).isEqualTo(now.id)

        // One running (the free fill), its wait queued behind it: replacing it would stop the run, so both stay
        RecordLengthsWorker.start(context)
        RecordLengthsWorker.waitForNetwork(context, wifiOnly = true)
        val before = pass().map { it.id }.toSet()
        RecordLengthsWorker.applyWifiOnly(context, wifiOnly = false)
        assertThat(pass().map { it.id }.toSet()).isEqualTo(before)
    }

    @Test fun `a pass asked for at once is never turned into one waiting for a network`() = runBlocking {
        RecordLengthsWorker.start(context)
        val running = pass().single().id
        RecordLengthsWorker.applyWifiOnly(context, wifiOnly = true)
        assertThat(pass().single().id).isEqualTo(running)
        assertThat(RecordLengthsWorker.waitsByOther(listOf(WorkInfo.State.ENQUEUED to setOf(RecordLengthsWorker.TAG_NOW)), wifiOnly = true)).isFalse()
    }
}
