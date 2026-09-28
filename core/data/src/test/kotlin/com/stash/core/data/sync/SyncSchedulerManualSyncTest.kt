package com.stash.core.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.stash.core.data.sync.workers.DiffWorker
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Only [SyncScheduler] knows whether the user tapped "Sync now". It tells the
 * diff step, whose kept-playlist sweep (#474) then downloads on any network like
 * the sync's own downloads; a scheduled sync keeps the Sync tab's Wi-Fi rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncSchedulerManualSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    /** Closed here, not by the garbage collector in the middle of another test. */
    @After fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test fun `a manual sync tells the diff step, a scheduled one does not`() {
        val scheduler = SyncScheduler(context, mockk(relaxed = true))

        scheduler.triggerManualSync()
        assertTrue(diffStepInput().getBoolean(DiffWorker.KEY_MANUAL_SYNC, false))

        scheduler.scheduleDailySync(hour = 6, minute = 0) // replaces the manual chain
        assertFalse(diffStepInput().getBoolean(DiffWorker.KEY_MANUAL_SYNC, true))
    }

    /** The live diff step's own input (WorkManager merges the fetch step's output in at run time). */
    private fun diffStepInput(): Data {
        val id = WorkManager.getInstance(context).getWorkInfosByTag("sync_diff").get()
            .single { !it.state.isFinished }.id
        return WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(id.toString())!!.input
    }
}
