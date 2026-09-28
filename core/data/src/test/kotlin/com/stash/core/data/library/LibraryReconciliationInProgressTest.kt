package com.stash.core.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.auth.TokenManager
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sync's reconcile resets only the sync's own stale IN_PROGRESS rows (#474). The
 * download run outside the sync (a kept playlist's, a tap's) can be mid-song when
 * a sync starts; resetting its row let the sync claim and download the same song
 * at the same time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LibraryReconciliationInProgressTest {

    private lateinit var db: StashDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `reconcile leaves a song downloading outside the sync alone`() = runTest {
        val downloading = song("Downloading now")
        val leftBySync = song("Left by a killed sync")
        val syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
        val queue = db.downloadQueueDao()
        queue.insert(DownloadQueueEntity(trackId = downloading, status = DownloadStatus.IN_PROGRESS))
        queue.insert(DownloadQueueEntity(trackId = leftBySync, syncId = syncId, status = DownloadStatus.IN_PROGRESS))
        val tokens = mockk<TokenManager> { coEvery { isAuthenticated(any()) } returns false }

        LibraryReconciliationUseCase(queue, db.trackDao(), tokens).reconcile(syncId = syncId)

        assertEquals(DownloadStatus.IN_PROGRESS, queue.getByTrackId(downloading)!!.status)
        assertEquals(DownloadStatus.PENDING, queue.getByTrackId(leftBySync)!!.status)
    }

    private suspend fun song(title: String): Long = db.trackDao().insert(
        TrackEntity(
            title = title, artist = "A", durationMs = 1000L, source = MusicSource.SPOTIFY,
            canonicalTitle = title.lowercase(), canonicalArtist = "a",
        )
    )
}
