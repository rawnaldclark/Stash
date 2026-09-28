package com.stash.core.data.sync.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.model.SyncTrigger
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sync history row the fetch step writes: it says what started the sync, as
 * SyncScheduler hands it over (every sync used to be recorded MANUAL, the daily
 * ones too), and it comes after closing any row an abandoned sync left open.
 *
 * Runs the worker's no-accounts early return, which writes the row first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistFetchWorkerTriggerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `a scheduled sync is recorded as SCHEDULED`() {
        val input = workDataOf(PlaylistFetchWorker.KEY_TRIGGER to SyncTrigger.SCHEDULED.name)
        assertEquals(SyncTrigger.SCHEDULED, recordedTrigger(input))
    }

    @Test fun `Sync now is recorded as MANUAL`() {
        val input = workDataOf(PlaylistFetchWorker.KEY_TRIGGER to SyncTrigger.MANUAL.name)
        assertEquals(SyncTrigger.MANUAL, recordedTrigger(input))
    }

    @Test fun `a chain queued before the trigger was passed on is recorded as MANUAL`() {
        assertEquals(SyncTrigger.MANUAL, recordedTrigger(Data.EMPTY))
    }

    /** A chain replaced while paused between steps never closes its own row. */
    @Test fun `a sync left open is marked interrupted before the next one's row is written`() {
        val syncHistoryDao = mockk<SyncHistoryDao>(relaxed = true)

        runFetch(Data.EMPTY, syncHistoryDao)

        coVerifyOrder {
            syncHistoryDao.resetStaleSyncs(any())
            syncHistoryDao.insert(any())
        }
    }

    /** Runs the fetch step with [input]; returns the trigger on the history row it writes. */
    private fun recordedTrigger(input: Data): SyncTrigger {
        val row = slot<SyncHistoryEntity>()
        val syncHistoryDao = mockk<SyncHistoryDao>(relaxed = true) {
            coEvery { insert(capture(row)) } returns 1L
        }
        runFetch(input, syncHistoryDao)
        return row.captured.trigger
    }

    private fun runFetch(input: Data, syncHistoryDao: SyncHistoryDao) = runBlocking {
        TestListenableWorkerBuilder<PlaylistFetchWorker>(context)
            .setInputData(input)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = PlaylistFetchWorker(
                    appContext, workerParameters,
                    tokenManager = mockk(relaxed = true), // no accounts: returns right after the row
                    spotifyApiClient = mockk(relaxed = true),
                    ytMusicApiClient = mockk(relaxed = true),
                    innerTubeClient = mockk(relaxed = true),
                    playlistDao = mockk(relaxed = true),
                    syncHistoryDao = syncHistoryDao,
                    remoteSnapshotDao = mockk(relaxed = true),
                    syncStateManager = mockk(relaxed = true),
                    syncLog = mockk(relaxed = true),
                    syncNotificationManager = mockk(relaxed = true),
                    syncPreferencesManager = mockk(relaxed = true),
                    spotifyAuthHealthProbe = mockk(relaxed = true),
                    youtubeAuthHealthProbe = mockk(relaxed = true),
                    streamingPreference = mockk(relaxed = true),
                )
            })
            .build()
            .doWork()
    }
}
