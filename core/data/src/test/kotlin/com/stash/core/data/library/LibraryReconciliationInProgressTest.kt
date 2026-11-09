package com.stash.core.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.auth.TokenManager
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sync's reconcile hands back only claims no live job holds. The download run outside the
 * sync (a kept playlist's, a tap's, #474) and the resume run (a sync's own rows, outside a sync)
 * can be mid-song when a sync starts; resetting their row let the sync claim and download the
 * same song at the same time. A claim left by a killed process, of either kind, goes back to
 * waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LibraryReconciliationInProgressTest {

    private lateinit var db: StashDatabase
    private val jobs = DownloadJobRegistry()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `reconcile leaves a song a live job downloads alone, of either partition`() = runTest {
        val syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
        val queue = db.downloadQueueDao()
        val outsideSync = queue.insert(DownloadQueueEntity(trackId = song("Downloading outside the sync"), status = DownloadStatus.IN_PROGRESS))
        val resumeRun = queue.insert(DownloadQueueEntity(trackId = song("Downloading in the resume run"), syncId = syncId, status = DownloadStatus.IN_PROGRESS))
        val live = Job()
        jobs.register(outsideSync, live)
        jobs.register(resumeRun, live)

        try {
            reconcile(syncId)

            assertEquals(DownloadStatus.IN_PROGRESS, queue.getStatusById(outsideSync))
            assertEquals(DownloadStatus.IN_PROGRESS, queue.getStatusById(resumeRun))
        } finally {
            live.cancel()
        }
    }

    @Test fun `reconcile releases a claim no job holds, of either partition`() = runTest {
        val syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
        val queue = db.downloadQueueDao()
        val leftBySync = queue.insert(DownloadQueueEntity(trackId = song("Left by a killed sync"), syncId = syncId, status = DownloadStatus.IN_PROGRESS))
        val leftByDrain = queue.insert(DownloadQueueEntity(trackId = song("Left by a killed drain"), status = DownloadStatus.IN_PROGRESS))

        reconcile(syncId)

        assertEquals(DownloadStatus.PENDING, queue.getStatusById(leftBySync))
        assertEquals(DownloadStatus.PENDING, queue.getStatusById(leftByDrain))
    }

    private suspend fun reconcile(syncId: Long) {
        val tokens = mockk<TokenManager> {
            coEvery { isAuthenticated(any()) } returns false
            coEvery { hasSoundCloudConnection() } returns false
        }
        LibraryReconciliationUseCase(db.downloadQueueDao(), db.trackDao(), tokens, db.playlistDao(), jobs).reconcile(syncId = syncId)
    }

    private suspend fun song(title: String): Long = db.trackDao().insert(
        TrackEntity(
            title = title, artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
            canonicalTitle = title.lowercase(), canonicalArtist = "a",
        )
    )
}
