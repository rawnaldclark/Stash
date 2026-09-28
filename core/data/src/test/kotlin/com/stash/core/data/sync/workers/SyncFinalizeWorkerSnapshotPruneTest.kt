package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.model.MusicSource
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sync that failed, was stopped or was replaced never reaches [SyncFinalizeWorker], so
 * its snapshot rows (one per track of every playlist it fetched) stayed in the database
 * for good. A finished sync now also drops snapshot rows older than a day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SyncFinalizeWorkerSnapshotPruneTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var dao: RemoteSnapshotDao

    @Before fun setUp() {
        // Finalize queues follow-up work (artist images, shared mixes) through WorkManager.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        dao = db.remoteSnapshotDao()
    }

    @After fun tearDown() {
        db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private suspend fun snapshot(syncId: Long, fetchedAt: Instant, tracks: Int) {
        val playlistId = dao.insertPlaylistSnapshot(
            RemotePlaylistSnapshotEntity(
                syncId = syncId,
                source = MusicSource.SPOTIFY,
                sourcePlaylistId = "pl-$syncId",
                playlistName = "Playlist $syncId",
                fetchedAt = fetchedAt,
            ),
        )
        dao.insertTrackSnapshots(
            (0 until tracks).map {
                RemoteTrackSnapshotEntity(syncId = syncId, snapshotPlaylistId = playlistId, title = "t$it", artist = "a", position = it)
            },
        )
    }

    private fun finalize(syncId: Long): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<SyncFinalizeWorker>(context)
            .setInputData(workDataOf(TrackDownloadWorker.KEY_SYNC_ID to syncId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    SyncFinalizeWorker(
                        appContext, workerParameters,
                        syncHistoryDao = mockk(relaxed = true),
                        remoteSnapshotDao = dao,
                        syncStateManager = mockk(relaxed = true),
                        syncNotificationManager = mockk(relaxed = true),
                    )
            })
            .build()
            .doWork()
    }

    @Test fun `a finished sync also drops the snapshots of an old run that never finished`() = runBlocking {
        val now = Instant.now()
        snapshot(syncId = 1, fetchedAt = now.minusSeconds(3 * 24 * 3600), tracks = 3) // failed three days ago
        snapshot(syncId = 2, fetchedAt = now.minusSeconds(600), tracks = 2)          // this run

        assertEquals(ListenableWorker.Result.success(), finalize(syncId = 2))

        assertEquals(0, dao.getPlaylistSnapshotsBySyncId(1).size)
        assertEquals(0, dao.getTrackSnapshotsBySyncId(1).size)
        assertEquals(0, dao.getTrackSnapshotsBySyncId(2).size)
    }

    @Test fun `a recent run's snapshots stay`() = runBlocking {
        // Only one sync chain runs at a time, but a day is kept anyway: an orphan is old.
        val now = Instant.now()
        snapshot(syncId = 3, fetchedAt = now.minusSeconds(3600), tracks = 2)
        snapshot(syncId = 4, fetchedAt = now.minusSeconds(60), tracks = 1)

        finalize(syncId = 4)

        assertEquals(2, dao.getTrackSnapshotsBySyncId(3).size)
    }
}
