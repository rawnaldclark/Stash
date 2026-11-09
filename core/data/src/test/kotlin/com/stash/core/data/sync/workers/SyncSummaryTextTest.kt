package com.stash.core.data.sync.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.stash.core.data.sync.SyncNotificationManager
import com.stash.core.data.sync.WaitingHold
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sync's summary when songs are left: the download step's KEY_WAITING and KEY_WAITING_HOLD
 * reach the notification, and paused or out of space it says so instead of "will try again
 * later", which they won't until the user does something.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncSummaryTextTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = SyncNotificationManager(context)

    @Test fun `songs left on their own try again later`() {
        assertEquals("Downloaded 37 songs · 3 will try again later · 2 failed", manager.summaryText(37, 2, 3, WaitingHold.NONE))
    }

    @Test fun `paused, they are paused`() {
        assertEquals("Downloaded 1 song · 3 paused", manager.summaryText(1, 0, 3, WaitingHold.PAUSED))
    }

    @Test fun `out of space, they wait for space`() {
        assertEquals("Downloaded 0 songs · 5 waiting for space", manager.summaryText(0, 0, 5, WaitingHold.NO_SPACE))
    }

    @Test fun `the download step's waiting count and hold reach the summary`() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val notifications = mockk<SyncNotificationManager>(relaxed = true)

        TestListenableWorkerBuilder<SyncFinalizeWorker>(context)
            .setInputData(
                workDataOf(
                    TrackDownloadWorker.KEY_SYNC_ID to 1L,
                    TrackDownloadWorker.KEY_DOWNLOADED to 4,
                    TrackDownloadWorker.KEY_WAITING to 3,
                    TrackDownloadWorker.KEY_WAITING_HOLD to WaitingHold.PAUSED.name,
                ),
            )
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SyncFinalizeWorker(
                        appContext, workerParameters,
                        syncHistoryDao = mockk(relaxed = true),
                        remoteSnapshotDao = mockk(relaxed = true),
                        lastFmCacheDao = mockk(relaxed = true),
                        syncStateManager = mockk(relaxed = true),
                        syncNotificationManager = notifications,
                        stashMixRecipeDao = mockk(relaxed = true),
                    )
            })
            .build()
            .doWork()

        coVerify(exactly = 1) {
            notifications.showSummary(newTracks = 4, playlistsChecked = any(), failed = 0, waiting = 3, hold = WaitingHold.PAUSED)
        }
    }
}
