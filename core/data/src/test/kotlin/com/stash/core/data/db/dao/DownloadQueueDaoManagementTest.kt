package com.stash.core.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SyncHistoryEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoManagementTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private lateinit var trackDao: TrackDao

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.downloadQueueDao()
        trackDao = db.trackDao()
    }

    @After fun tearDown() = db.close()

    @Test fun active_feed_joins_metadata_and_orders_oldest_first() = runTest {
        seedTrack(1, "Newest", "Artist C", "Album C", "art-c")
        seedTrack(2, "Oldest", "Artist A", "Album A", "art-a")
        seedTrack(3, "Middle", "Artist B", "Album B", null)
        seedQueue(1, 1, DownloadStatus.PENDING, createdAt = 300)
        seedQueue(2, 2, DownloadStatus.IN_PROGRESS, createdAt = 100)
        seedQueue(3, 3, DownloadStatus.WAITING_FOR_LOSSLESS, createdAt = 200)

        val rows = dao.observeActiveDownloadRows().first()

        assertEquals(listOf(2L, 3L, 1L), rows.map { it.queueId })
        assertEquals("Oldest", rows[0].title)
        assertEquals("Artist A", rows[0].artist)
        assertEquals("Album A", rows[0].album)
        assertEquals("art-a", rows[0].albumArtUrl)
        assertEquals(DownloadStatus.WAITING_FOR_LOSSLESS, rows[1].status)
    }

    @Test fun history_orders_by_terminal_time_with_created_fallback_and_honors_limit() = runTest {
        (1L..4L).forEach { seedTrack(it, "Track $it", "Artist", "Album", null) }
        seedQueue(1, 1, DownloadStatus.COMPLETED, createdAt = 10, completedAt = 200)
        seedQueue(2, 2, DownloadStatus.FAILED, createdAt = 20, completedAt = 400)
        seedQueue(3, 3, DownloadStatus.SKIPPED, createdAt = 300, completedAt = null)
        seedQueue(4, 4, DownloadStatus.COMPLETED, createdAt = 100, completedAt = 300)

        val rows = dao.observeDownloadHistory(limit = 3).first()

        assertEquals(listOf(2L, 4L, 3L), rows.map { it.queueId })
    }

    @Test fun active_and_history_are_disjoint_and_react_to_terminal_transition() = runTest {
        seedTrack(1, "Track", "Artist", "Album", null)
        seedQueue(1, 1, DownloadStatus.IN_PROGRESS, createdAt = 100)
        assertEquals(listOf(1L), dao.observeActiveDownloadRows().first().map { it.queueId })
        assertEquals(emptyList<Long>(), dao.observeDownloadHistory(20).first().map { it.queueId })

        assertEquals(1, dao.completeIfInProgress(1, completedAt = 500))

        assertEquals(emptyList<Long>(), dao.observeActiveDownloadRows().first().map { it.queueId })
        assertEquals(listOf(1L), dao.observeDownloadHistory(20).first().map { it.queueId })
    }

    @Test fun claim_is_atomic_and_idempotent() = runTest {
        seedTrack(1, "Track", "Artist", "Album", null)
        seedQueue(1, 1, DownloadStatus.PENDING, createdAt = 100)

        assertEquals(1, dao.claimForDownload(1))
        assertEquals(0, dao.claimForDownload(1))
        assertEquals(DownloadStatus.IN_PROGRESS, dao.getStatusById(1))
    }

    @Test fun claim_accepts_failed_retry_and_clears_terminal_failure() = runTest {
        seedTrack(1, "Track", "Artist", "Album", null)
        seedQueue(
            1, 1, DownloadStatus.FAILED, createdAt = 100, completedAt = 200,
            error = "retry me", failureType = DownloadFailureType.NETWORK,
        )

        assertEquals(1, dao.claimForDownload(1))
        val row = dao.getById(1)!!
        assertEquals(DownloadStatus.IN_PROGRESS, row.status)
        assertNull(row.errorMessage)
        assertNull(row.completedAt)
        assertEquals(DownloadFailureType.NONE, row.failureType)
    }

    @Test fun cancel_is_atomic_idempotent_and_clears_failure_fields() = runTest {
        seedTrack(1, "Track", "Artist", "Album", null)
        seedQueue(
            1, 1, DownloadStatus.IN_PROGRESS, createdAt = 100,
            error = "old", failureType = DownloadFailureType.NETWORK,
        )

        assertEquals(1, dao.markCancelledIfActive(1, completedAt = 700))
        assertEquals(0, dao.markCancelledIfActive(1, completedAt = 900))
        val row = dao.getById(1)!!
        assertEquals(DownloadStatus.SKIPPED, row.status)
        assertEquals(Instant.ofEpochMilli(700), row.completedAt)
        assertNull(row.errorMessage)
        assertEquals(DownloadFailureType.NONE, row.failureType)
    }

    @Test fun cancel_then_late_terminal_writes_are_noops() = runTest {
        seedTrack(1, "Track", "Artist", "Album", null)
        seedQueue(1, 1, DownloadStatus.IN_PROGRESS, createdAt = 100)
        assertEquals(1, dao.markCancelledIfActive(1, completedAt = 700))

        assertEquals(0, dao.completeIfInProgress(1, completedAt = 800))
        assertEquals(0, dao.failIfInProgress(1, "late", DownloadFailureType.NETWORK, 800))
        assertEquals(0, dao.deferIfInProgress(1, null))
        assertEquals(DownloadStatus.SKIPPED, dao.getStatusById(1))
    }

    @Test fun completion_then_cancel_preserves_completion() = runTest {
        seedTrack(1, "Track", "Artist", "Album", null)
        seedQueue(1, 1, DownloadStatus.IN_PROGRESS, createdAt = 100)
        assertEquals(1, dao.completeIfInProgress(1, completedAt = 800))

        assertEquals(0, dao.markCancelledIfActive(1, completedAt = 900))
        assertEquals(DownloadStatus.COMPLETED, dao.getStatusById(1))
        assertEquals(Instant.ofEpochMilli(800), dao.getById(1)?.completedAt)
    }

    @Test fun fail_and_defer_only_transition_in_progress_rows() = runTest {
        seedTrack(1, "Failed", "Artist", "Album", null)
        seedTrack(2, "Deferred", "Artist", "Album", null)
        seedTrack(3, "Pending", "Artist", "Album", null)
        seedQueue(1, 1, DownloadStatus.IN_PROGRESS, createdAt = 100)
        seedQueue(2, 2, DownloadStatus.IN_PROGRESS, createdAt = 200)
        seedQueue(3, 3, DownloadStatus.PENDING, createdAt = 300)

        assertEquals(1, dao.failIfInProgress(1, "boom", DownloadFailureType.NETWORK, 500))
        assertEquals(1, dao.deferIfInProgress(2, "wait:aac"))
        assertEquals(0, dao.failIfInProgress(3, "no", DownloadFailureType.NETWORK, 500))
        assertEquals(DownloadStatus.FAILED, dao.getStatusById(1))
        assertEquals(DownloadStatus.WAITING_FOR_LOSSLESS, dao.getStatusById(2))
        assertEquals(DownloadStatus.PENDING, dao.getStatusById(3))
        // The row keeps why it waits.
        assertEquals("wait:aac", dao.getById(2)?.errorMessage)
    }

    // ── Cancellation durability ─────────────────────────────────────────
    //
    // Cancelling a download is only real if nothing puts it back. The
    // reconciliation pass re-queues any undownloaded track that has no
    // active queue row, so a SKIPPED row must read as "active intent" there
    // — otherwise Cancel just restarts the download on the next sync.

    @Test fun `cancelled row is not re-enqueued by reconciliation`() = runTest {
        seedTrack(1, "Cancelled", "Artist", "Album", null)
        seedPlaylistMembership(trackId = 1)
        seedQueue(1, 1, DownloadStatus.PENDING, createdAt = 100)

        // Still PENDING: already queued, so not an "unqueued" track either.
        assertEquals(emptyList<Long>(), dao.getUnqueuedTrackIds(listOf("SPOTIFY")))

        assertEquals(1, dao.markCancelledIfActive(1, completedAt = 700))

        assertEquals(emptyList<Long>(), dao.getUnqueuedTrackIds(listOf("SPOTIFY")))
        assertEquals(0, dao.getOrphanedTrackCounts().size)
    }

    @Test fun `retry claim un-cancels a skipped row`() = runTest {
        seedTrack(1, "Cancelled", "Artist", "Album", null)
        seedQueue(1, 1, DownloadStatus.SKIPPED, createdAt = 100, completedAt = 700)

        assertEquals(1, dao.atomicallyClaimForRetry(1))
        assertEquals(DownloadStatus.PENDING, dao.getStatusById(1))
        // And the un-cancelled row is claimable by a worker again.
        assertEquals(1, dao.claimForDownload(1))
    }

    @Test fun `history sweep purges cancelled rows alongside completed ones`() = runTest {
        (1L..3L).forEach { seedTrack(it, "Track $it", "Artist", "Album", null) }
        seedQueue(1, 1, DownloadStatus.COMPLETED, createdAt = 10, completedAt = 200)
        seedQueue(2, 2, DownloadStatus.SKIPPED, createdAt = 20, completedAt = 300)
        seedQueue(3, 3, DownloadStatus.FAILED, createdAt = 30, completedAt = 400)

        dao.deleteCompleted()

        // FAILED survives — it is still retryable and drives the Failed
        // Downloads viewer. COMPLETED and SKIPPED are dead history.
        assertNull(dao.getById(1))
        assertNull(dao.getById(2))
        assertEquals(DownloadStatus.FAILED, dao.getStatusById(3))
    }

    @Test fun `orphan sweep evicts a cancelled row once its track loses every playlist`() = runTest {
        // sync_id is FK-constrained and the sweep only touches sync-created
        // rows, so the run has to exist.
        val syncId = db.syncHistoryDao().insert(SyncHistoryEntity())
        seedTrack(1, "Kept", "Artist", "Album", null)
        seedTrack(2, "Orphan", "Artist", "Album", null)
        seedPlaylistMembership(trackId = 1)
        seedQueue(1, 1, DownloadStatus.SKIPPED, createdAt = 100, completedAt = 700, syncId = syncId)
        seedQueue(2, 2, DownloadStatus.SKIPPED, createdAt = 100, completedAt = 700, syncId = syncId)

        assertEquals(1, dao.deleteOrphanedQueueEntries())

        // Track 1 still lives in a sync-enabled playlist, so its tombstone
        // stays and keeps suppressing re-enqueue.
        assertEquals(DownloadStatus.SKIPPED, dao.getStatusById(1))
        assertNull(dao.getById(2))
    }

    /** A sync-enabled, active, non-mix parent — the shape every "is this
     *  track wanted?" predicate in the DAO checks for. */
    private suspend fun seedPlaylistMembership(trackId: Long) {
        val playlistDao = db.playlistDao()
        playlistDao.insert(
            PlaylistEntity(
                id = trackId * 1000,
                name = "Playlist $trackId",
                source = MusicSource.SPOTIFY,
                syncEnabled = true,
                isActive = true,
            )
        )
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(playlistId = trackId * 1000, trackId = trackId)
        )
    }

    private suspend fun seedTrack(
        id: Long,
        title: String,
        artist: String,
        album: String,
        art: String?,
    ) {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                album = album,
                albumArtUrl = art,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
            )
        )
    }

    private suspend fun seedQueue(
        id: Long,
        trackId: Long,
        status: DownloadStatus,
        createdAt: Long,
        completedAt: Long? = null,
        error: String? = null,
        failureType: DownloadFailureType = DownloadFailureType.NONE,
        syncId: Long? = null,
    ) {
        dao.insert(
            DownloadQueueEntity(
                id = id,
                trackId = trackId,
                status = status,
                searchQuery = "query",
                createdAt = Instant.ofEpochMilli(createdAt),
                completedAt = completedAt?.let(Instant::ofEpochMilli),
                errorMessage = error,
                failureType = failureType,
                syncId = syncId,
            )
        )
    }
}
