package com.stash.core.data.weblink.mirror

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import com.stash.core.model.weblink.Hlc
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** When the mirror runs (spec §9): WorkManager requests and their constraints, the change signal, and the worker's outcomes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MirrorSchedulingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    private fun infos(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()

    private fun spec(info: WorkInfo) = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!

    @Test fun `a change is one run 10 s later on a network, a burst replaces it, and nothing periodic runs while mirroring is off`() {
        val s = WorkManagerMirrorScheduler(context)
        s.changed()
        s.changed()
        val pending = infos(MirrorSyncWorker.WORK_CHANGE).filter { !it.state.isFinished }
        assertThat(pending).hasSize(1)
        val spec = spec(pending.single())
        assertThat(spec.initialDelay).isEqualTo(10_000L)
        assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(infos(MirrorSyncWorker.WORK_PERIODIC)).isEmpty()
    }

    @Test fun `while mirroring is on, a 6-hour run on a network with a battery that isn't low, cancelled when it goes off`() {
        val s = WorkManagerMirrorScheduler(context)
        s.periodic(true)
        val p = infos(MirrorSyncWorker.WORK_PERIODIC).single()
        val spec = spec(p)
        assertThat(spec.intervalDuration).isEqualTo(6 * 3_600_000L)
        assertThat(spec.constraints.requiresBatteryNotLow()).isTrue()
        assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        s.periodic(false)
        assertThat(infos(MirrorSyncWorker.WORK_PERIODIC).single().state).isEqualTo(WorkInfo.State.CANCELLED)
    }

    // ------------------------------------------------------------------------------------------------ the change signal

    private class Recorder : MirrorScheduler {
        var changed = 0
        var now = 0
        val periodic = mutableListOf<Boolean>()
        override fun changed() { changed++ }
        override fun now() { now++ }
        override fun periodic(on: Boolean) { periodic += on }
    }

    private val on = MirrorStatus(linked = true, config = MirrorConfig(Hlc(1, 0, "d_00000000"), likes = KindConfig(Dir.BOTH)))

    @Test fun `an invalidation burst is one run, and only when the mirrored kinds' digest moved`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val status = MutableStateFlow(on)
        val inv = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var digest = "a"
        val rec = Recorder()
        val t = MirrorTriggers(status, {}, { digest }, inv, rec, scope, { testScheduler.currentTime })
        t.start()
        scope.runCurrent()
        assertThat(rec.periodic.last()).isTrue()

        repeat(5) { inv.emit(Unit) }
        scope.advanceTimeBy(MirrorTriggers.DEBOUNCE_MS + 1)
        assertThat(rec.changed).isEqualTo(1)

        // A play count moving `tracks` but no digest: no run.
        inv.emit(Unit)
        scope.advanceTimeBy(MirrorTriggers.DEBOUNCE_MS + 1)
        assertThat(rec.changed).isEqualTo(1)

        digest = "b"
        inv.emit(Unit)
        scope.advanceTimeBy(MirrorTriggers.DEBOUNCE_MS + 1)
        assertThat(rec.changed).isEqualTo(2)

        // A run's own writes don't ask for another: the digest after a run is the new start.
        status.value = on.copy(busy = true)
        scope.runCurrent()
        digest = "c"
        status.value = on
        scope.runCurrent()
        inv.emit(Unit)
        scope.advanceTimeBy(MirrorTriggers.DEBOUNCE_MS + 1)
        assertThat(rec.changed).isEqualTo(2)
        scope.cancel()
    }

    @Test fun `no background run while nothing mirrors, and the foreground asks at most once a minute while linked`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val status = MutableStateFlow(MirrorStatus(linked = true, config = MirrorConfig.off(Hlc(1, 0, "d_00000000"))))
        val inv = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
        var digest = "a"
        val rec = Recorder()
        val t = MirrorTriggers(status, {}, { digest.also { digest += "x" } }, inv, rec, scope, { testScheduler.currentTime })
        t.start()
        inv.emit(Unit)
        scope.advanceTimeBy(MirrorTriggers.DEBOUNCE_MS + 1)
        t.onForeground()
        scope.runCurrent()
        assertThat(rec.changed).isEqualTo(0)
        assertThat(rec.periodic.last()).isFalse()
        // Linked with every kind off: the foreground still reads the settings (a browser may have turned a kind on).
        assertThat(rec.now).isEqualTo(1)

        status.value = on
        t.onForeground() // within the minute: nothing
        scope.advanceTimeBy(MirrorTriggers.FOREGROUND_GAP_MS)
        t.onForeground()
        t.onForeground()
        scope.runCurrent()
        assertThat(rec.now).isEqualTo(2)

        status.value = MirrorStatus(linked = false)
        scope.advanceTimeBy(MirrorTriggers.FOREGROUND_GAP_MS)
        t.onForeground()
        scope.runCurrent()
        assertThat(rec.now).isEqualTo(2)
        scope.cancel()
    }

    private fun TestScope.cancel() = coroutineContext[kotlinx.coroutines.Job]?.cancel()

    // ------------------------------------------------------------------------------------------------ the worker

    private fun worker(engine: MirrorEngine, attempt: Int = 0) = TestListenableWorkerBuilder<MirrorSyncWorker>(context)
        .setRunAttemptCount(attempt)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                MirrorSyncWorker(appContext, workerParameters, engine)
        })
        .build()

    @Test fun `offline retries with a backoff a few times, and done, unlinked and a failure succeed`() = runTest {
        val engine = mockk<MirrorEngine>()
        coEvery { engine.sync() } returns MirrorRun.Retry("offline")
        assertThat(worker(engine).doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(worker(engine, attempt = MirrorSyncWorker.MAX_RETRIES).doWork()).isEqualTo(ListenableWorker.Result.success())
        coEvery { engine.sync() } returns MirrorRun.Done
        assertThat(worker(engine).doWork()).isEqualTo(ListenableWorker.Result.success())
        coEvery { engine.sync() } returns MirrorRun.NotLinked
        assertThat(worker(engine).doWork()).isEqualTo(ListenableWorker.Result.success())
        coEvery { engine.sync() } returns MirrorRun.Failed("newer")
        assertThat(worker(engine).doWork()).isEqualTo(ListenableWorker.Result.success())
        coEvery { engine.sync() } throws IllegalStateException("boom")
        assertThat(worker(engine).doWork()).isEqualTo(ListenableWorker.Result.retry())
    }

    @Test fun `a cancelled run is rethrown, never swallowed as a failure`() {
        val engine = mockk<MirrorEngine>()
        coEvery { engine.sync() } throws CancellationException("stopped")
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { worker(engine).doWork() } }
    }
}
