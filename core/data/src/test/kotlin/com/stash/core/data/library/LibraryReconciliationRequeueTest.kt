package com.stash.core.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.auth.TokenManager
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Only a sync requeues songs (#532). Library Health's Verify (a reconcile with no sync
 * run) filed rows outside any sync, which nothing but the background drain took: it
 * downloaded them unasked at every app start, Stream-only included. They also hid
 * their songs from the next Download-mode sync's own requeue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LibraryReconciliationRequeueTest {

    private lateinit var db: StashDatabase

    // No service connected: the requeue then looks at BOTH-source songs only.
    private val tokens = mockk<TokenManager> { coEvery { isAuthenticated(any()) } returns false }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() { db.close() }

    @Test fun `Verify queues nothing`() = runTest {
        val song = missingSong()

        val result = reconciler().reconcile()

        assertEquals(0, result.unqueuedRequeued)
        assertNull(db.downloadQueueDao().getByTrackId(song))
    }

    @Test fun `a sync's reconcile still requeues, with its own sync id`() = runTest {
        val song = missingSong()
        val run = db.syncHistoryDao().insert(SyncHistoryEntity())

        val result = reconciler().reconcile(syncId = run)

        assertEquals(1, result.unqueuedRequeued)
        assertEquals(run, db.downloadQueueDao().getByTrackId(song)!!.syncId)
    }

    @Test fun `reconcile clears a leftover, so the sync requeues its song in the same pass`() = runTest {
        val song = missingSong()
        // What Verify used to file: no sync run, no search query, not asked for.
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = song))
        val run = db.syncHistoryDao().insert(SyncHistoryEntity())

        val result = reconciler().reconcile(syncId = run)

        assertEquals(1, result.orphansSwept)
        assertEquals(1, result.unqueuedRequeued)
        assertEquals(run, db.downloadQueueDao().getByTrackId(song)!!.syncId)
        assertEquals(
            listOf(DownloadStatus.PENDING.name to 1),
            db.downloadQueueDao().getStatusCounts().map { it.status to it.count },
        )
    }

    private fun reconciler() = LibraryReconciliationUseCase(db.downloadQueueDao(), db.trackDao(), tokens)

    /** An undownloaded song in a synced playlist with no download row: what the requeue looks for. */
    private suspend fun missingSong(): Long {
        val playlist = db.playlistDao().insert(
            PlaylistEntity(
                name = "Synced", source = MusicSource.BOTH, sourceId = "custom_synced",
                type = PlaylistType.CUSTOM, syncEnabled = true,
            )
        )
        val song = db.trackDao().insert(
            TrackEntity(
                title = "Missing", artist = "A", durationMs = 1000L, source = MusicSource.BOTH,
                canonicalTitle = "missing", canonicalArtist = "a",
            )
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlist, trackId = song, position = 0))
        return song
    }
}
