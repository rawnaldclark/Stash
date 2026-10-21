package com.stash.data.lyrics.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.stash.data.download.lyrics.LyricsFetchStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lyrics library walk is user-started only: Settings' re-check enqueues one gentle unique job
 * (KEEP), Cancel stops just that one, and a run the retired automatic pass queued before an
 * update is cancelled on start while user-started runs are left alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WorkManagerLyricsUpgradeTriggerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var trigger: WorkManagerLyricsUpgradeTrigger

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        trigger = WorkManagerLyricsUpgradeTrigger(context)
    }

    /** Closed here, not by the garbage collector in the middle of another test. */
    @After fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun infos(): List<WorkInfo> = runBlocking {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(LyricsTtmlUpgradeWorker.UNIQUE_WORK_NAME).first()
    }

    /**
     * What the retired once-per-release pass enqueued: same unique name and constraints (which also
     * keep the test WorkManager from running it at once), no tag of ours.
     */
    private fun enqueueRetiredAutoRun() {
        val request = OneTimeWorkRequestBuilder<LyricsTtmlUpgradeWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            LyricsTtmlUpgradeWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )   // the test WorkManager's executor is synchronous: enqueued once this returns
    }

    @Test fun `the re-check is one unique, tagged job on unmetered Wi-Fi with the battery not low`() {
        trigger.enqueueLibraryRecheck()

        val info = infos().single()
        assertTrue(LyricsTtmlUpgradeWorker.TAG_RECHECK in info.tags)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresBatteryNotLow())
    }

    @Test fun `a second tap keeps the queued run instead of starting another`() {
        trigger.enqueueLibraryRecheck()
        val first = infos().single().id

        trigger.enqueueLibraryRecheck()

        assertEquals(listOf(first), infos().map { it.id })
    }

    @Test fun `cancel stops the re-check and the row goes back to idle`() = runBlocking {
        trigger.enqueueLibraryRecheck()
        assertEquals(LyricsFetchStatus.Queued, trigger.recheckStatus.first())

        trigger.cancelLibraryRecheck()

        assertEquals(WorkInfo.State.CANCELLED, infos().single().state)
        assertEquals(LyricsFetchStatus.Idle, trigger.recheckStatus.first())
    }

    @Test fun `a run the retired automatic pass queued is cancelled on start`() = runBlocking {
        enqueueRetiredAutoRun()

        trigger.cancelRetiredAutoRun()

        assertEquals(WorkInfo.State.CANCELLED, infos().single().state)
    }

    @Test fun `a user-started re-check survives the start-up cancel`() = runBlocking {
        trigger.enqueueLibraryRecheck()

        trigger.cancelRetiredAutoRun()

        assertEquals(WorkInfo.State.ENQUEUED, infos().single().state)
    }

    @Test fun `the re-check row ignores a Library Health run`() = runBlocking {
        trigger.enqueueManualFetch()

        assertEquals(LyricsFetchStatus.Idle, trigger.recheckStatus.first())
    }
}
