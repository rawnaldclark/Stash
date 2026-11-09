package com.stash.core.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadFailureType
import com.stash.core.model.DownloadStatus
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DownloadQueueDaoFailedDownloadsTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private lateinit var trackDao: TrackDao
    private lateinit var playlistDao: PlaylistDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.downloadQueueDao()
        trackDao = db.trackDao()
        playlistDao = db.playlistDao()
    }

    @After fun tearDown() { db.close() }

    // ---- atomicallyClaimForRetry ----------------------------------

    @Test fun atomicallyClaimForRetry_returns_1_when_row_is_FAILED() = runTest {
        seedTrack(id = 1, title = "Auth failure", artist = "X")
        seedFailedQueueRow(queueId = 1, trackId = 1, type = DownloadFailureType.AUTH_EXPIRED)
        val affected = dao.atomicallyClaimForRetry(1)
        assertEquals(1, affected)
        val row = dao.getById(1)!!
        assertEquals(DownloadStatus.PENDING, row.status)
        assertNull(row.errorMessage)
        assertEquals(DownloadFailureType.NONE, row.failureType)
    }

    @Test fun atomicallyClaimForRetry_returns_0_when_row_is_PENDING() = runTest {
        seedTrack(id = 1, title = "Auth failure", artist = "X")
        seedFailedQueueRow(queueId = 1, trackId = 1, type = DownloadFailureType.AUTH_EXPIRED)
        dao.atomicallyClaimForRetry(1)              // first claim succeeds
        val affected = dao.atomicallyClaimForRetry(1) // second claim no-ops
        assertEquals(0, affected)
    }

    // ---- markFailed ------------------------------------------------

    @Test fun markFailed_writes_status_and_failureType_and_completedAt() = runTest {
        seedTrack(id = 1, title = "Pending track", artist = "X")
        seedPendingQueueRow(queueId = 1, trackId = 1)
        dao.markFailed(queueId = 1, errorMessage = "boom", failureType = DownloadFailureType.NETWORK)
        val row = dao.getById(1)!!
        assertEquals(DownloadStatus.FAILED, row.status)
        assertEquals("boom", row.errorMessage)
        assertEquals(DownloadFailureType.NETWORK, row.failureType)
        assertNotNull(row.completedAt)
    }

    // ---- getFailedDownloads ---------------------------------------

    @Test fun getFailedDownloads_excludes_NONE_and_NO_MATCH() = runTest {
        seedTrack(id = 1, title = "Auth failure", artist = "X")
        seedTrack(id = 2, title = "No match", artist = "Y")
        seedTrack(id = 3, title = "Pending", artist = "Z")
        seedFailedQueueRow(queueId = 1, trackId = 1, type = DownloadFailureType.AUTH_EXPIRED)
        seedFailedQueueRow(queueId = 2, trackId = 2, type = DownloadFailureType.NO_MATCH)
        seedPendingQueueRow(queueId = 3, trackId = 3)  // NONE
        val rows = dao.getFailedDownloads().first()
        assertEquals(1, rows.size)
        assertEquals(1L, rows[0].queueId)
        assertEquals(DownloadFailureType.AUTH_EXPIRED, rows[0].failureType)
    }

    @Test fun getFailedDownloads_returns_sync_enabled_playlist_name_first() = runTest {
        // Regression for spec §3.6 — when a track belongs to both a
        // sync-disabled playlist (lower id) and a sync-enabled playlist
        // (higher id), the subquery must surface the *sync-enabled* name.
        // ORDER BY p.sync_enabled DESC, p.id ASC ensures the enabled
        // playlist wins regardless of insertion order or id sort.
        seedTrack(id = 1, title = "Both playlists", artist = "X")
        seedPlaylist(id = 10, name = "Liked Songs", syncEnabled = false)
        seedPlaylist(id = 20, name = "Heavy Rotation", syncEnabled = true)
        seedPlaylistMembership(playlistId = 10, trackId = 1)
        seedPlaylistMembership(playlistId = 20, trackId = 1)
        seedFailedQueueRow(queueId = 1, trackId = 1, type = DownloadFailureType.AUTH_EXPIRED)

        val rows = dao.getFailedDownloads().first()
        assertEquals(1, rows.size)
        assertEquals("Heavy Rotation", rows.single().playlistName)
    }

    // ---- atomicallyClaimGroupForRetry -----------------------------

    @Test fun atomicallyClaimGroupForRetry_returns_claimed_ids_for_type() = runTest {
        seedTrack(id = 1, title = "Net 1", artist = "X")
        seedTrack(id = 2, title = "Auth", artist = "Y")
        seedTrack(id = 3, title = "Net 2", artist = "Z")
        seedFailedQueueRow(queueId = 1, trackId = 1, type = DownloadFailureType.NETWORK)
        seedFailedQueueRow(queueId = 2, trackId = 2, type = DownloadFailureType.AUTH_EXPIRED)
        seedFailedQueueRow(queueId = 3, trackId = 3, type = DownloadFailureType.NETWORK)

        val claimed = dao.atomicallyClaimGroupForRetry(DownloadFailureType.NETWORK)

        assertEquals(setOf(1L, 3L), claimed.toSet())
        // Rows 1 and 3 should be back to PENDING with cleared failure_type.
        val row1 = dao.getById(1)!!
        assertEquals(DownloadStatus.PENDING, row1.status)
        assertEquals(DownloadFailureType.NONE, row1.failureType)
        assertNull(row1.errorMessage)
        val row3 = dao.getById(3)!!
        assertEquals(DownloadStatus.PENDING, row3.status)
        assertEquals(DownloadFailureType.NONE, row3.failureType)
        assertNull(row3.errorMessage)
        // Row 2 (AUTH_EXPIRED) should be untouched.
        val row2 = dao.getById(2)!!
        assertEquals(DownloadStatus.FAILED, row2.status)
        assertEquals(DownloadFailureType.AUTH_EXPIRED, row2.failureType)
    }

    // ---- releaseClaim ----------------------------------------------

    @Test fun releaseClaim_hands_an_in_progress_row_back_and_leaves_others_alone() = runTest {
        seedTrack(id = 1, title = "Stopped mid-download", artist = "X")
        seedTrack(id = 2, title = "User skipped", artist = "X")
        seedTrack(id = 3, title = "Already done", artist = "X")
        seedPendingQueueRow(queueId = 1, trackId = 1)
        seedPendingQueueRow(queueId = 2, trackId = 2)
        seedPendingQueueRow(queueId = 3, trackId = 3)
        assertEquals(1, dao.claimForDownload(1))
        dao.updateStatus(id = 2, status = DownloadStatus.SKIPPED)
        dao.updateStatus(id = 3, status = DownloadStatus.COMPLETED)

        assertEquals(1, dao.releaseClaim(1))
        assertEquals(DownloadStatus.PENDING, dao.getById(1)!!.status)
        assertEquals(1, dao.claimForDownload(1)) // the rerun can claim it again
        assertEquals(0, dao.releaseClaim(2))
        assertEquals(DownloadStatus.SKIPPED, dao.getById(2)!!.status)
        assertEquals(0, dao.releaseClaim(3))
        assertEquals(DownloadStatus.COMPLETED, dao.getById(3)!!.status)
    }

    // ---- revertToPendingAfterInterruption -------------------------

    @Test fun revertToPendingAfterInterruption_flips_IN_PROGRESS_to_PENDING_and_increments_retry() = runTest {
        // Setup: a claimed row (a run is downloading it) that has used two retries.
        seedTrack(id = 1, title = "Interrupted", artist = "X")
        dao.insert(
            DownloadQueueEntity(
                id = 1,
                trackId = 1,
                status = DownloadStatus.IN_PROGRESS,
                syncId = null,
                searchQuery = "q",
                failureType = DownloadFailureType.NETWORK,
                errorMessage = "prior error",
                retryCount = 2,
            )
        )

        dao.revertToPendingAfterInterruption(
            queueId = 1,
            lastErrorMessage = "socket timeout",
            lastFailureType = DownloadFailureType.NETWORK,
        )

        val row = dao.getById(1)!!
        // Status is PENDING so next sync re-attempts; viewer (gated on FAILED)
        // does NOT pick it up.
        assertEquals(DownloadStatus.PENDING, row.status)
        // Telemetry retained.
        assertEquals("socket timeout", row.errorMessage)
        assertEquals(DownloadFailureType.NETWORK, row.failureType)
        // Retry counter bumped so escalation eventually triggers.
        assertEquals(3, row.retryCount)
        // completed_at cleared (no longer terminal).
        assertNull(row.completedAt)
    }

    @Test fun revertToPendingAfterInterruption_never_revives_a_row_that_is_not_downloading() = runTest {
        // A cancel (SKIPPED) or a finished row that raced the failure stays as it is.
        seedTrack(id = 1, title = "Cancelled", artist = "X")
        seedTrack(id = 2, title = "Failed", artist = "Y")
        dao.insert(DownloadQueueEntity(id = 1, trackId = 1, status = DownloadStatus.SKIPPED, syncId = null, searchQuery = "q"))
        dao.insert(DownloadQueueEntity(id = 2, trackId = 2, status = DownloadStatus.FAILED, syncId = null, searchQuery = "q", retryCount = 1))

        dao.revertToPendingAfterInterruption(1, "socket timeout", DownloadFailureType.NETWORK)
        dao.revertToPendingAfterInterruption(2, "socket timeout", DownloadFailureType.NETWORK)

        assertEquals(DownloadStatus.SKIPPED, dao.getById(1)!!.status)
        assertEquals(DownloadStatus.FAILED, dao.getById(2)!!.status)
        assertEquals(1, dao.getById(2)!!.retryCount)
    }

    // ---- atomicallyClaimAllForRetry -------------------------------

    @Test fun atomicallyClaimAllForRetry_returns_all_non_match_failed_ids() = runTest {
        seedTrack(id = 1, title = "Auth", artist = "A")
        seedTrack(id = 2, title = "No match", artist = "B")
        seedTrack(id = 3, title = "Net", artist = "C")
        seedTrack(id = 4, title = "Pending", artist = "D")
        seedFailedQueueRow(queueId = 1, trackId = 1, type = DownloadFailureType.AUTH_EXPIRED)
        seedFailedQueueRow(queueId = 2, trackId = 2, type = DownloadFailureType.NO_MATCH)
        seedFailedQueueRow(queueId = 3, trackId = 3, type = DownloadFailureType.NETWORK)
        seedPendingQueueRow(queueId = 4, trackId = 4)  // NONE + PENDING

        val claimed = dao.atomicallyClaimAllForRetry()

        assertEquals(setOf(1L, 3L), claimed.toSet())
        // Claimed rows flipped to PENDING with cleared failure_type.
        val row1 = dao.getById(1)!!
        assertEquals(DownloadStatus.PENDING, row1.status)
        assertEquals(DownloadFailureType.NONE, row1.failureType)
        val row3 = dao.getById(3)!!
        assertEquals(DownloadStatus.PENDING, row3.status)
        assertEquals(DownloadFailureType.NONE, row3.failureType)
        // NO_MATCH row untouched.
        val row2 = dao.getById(2)!!
        assertEquals(DownloadStatus.FAILED, row2.status)
        assertEquals(DownloadFailureType.NO_MATCH, row2.failureType)
        // Already-PENDING row untouched.
        val row4 = dao.getById(4)!!
        assertEquals(DownloadStatus.PENDING, row4.status)
        assertEquals(DownloadFailureType.NONE, row4.failureType)
    }

    // ---- Helpers --------------------------------------------------

    private suspend fun seedTrack(id: Long, title: String, artist: String) {
        trackDao.insert(
            TrackEntity(
                id = id,
                title = title,
                artist = artist,
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
                source = MusicSource.YOUTUBE,
                isDownloaded = false,
            )
        )
    }

    private suspend fun seedFailedQueueRow(queueId: Long, trackId: Long, type: DownloadFailureType) {
        dao.insert(
            DownloadQueueEntity(
                id = queueId,
                trackId = trackId,
                status = DownloadStatus.FAILED,
                syncId = null,
                searchQuery = "q",
                failureType = type,
                errorMessage = "previous error",
            )
        )
    }

    private suspend fun seedPendingQueueRow(queueId: Long, trackId: Long) {
        dao.insert(
            DownloadQueueEntity(
                id = queueId,
                trackId = trackId,
                status = DownloadStatus.PENDING,
                syncId = null,
                searchQuery = "q",
                failureType = DownloadFailureType.NONE,
            )
        )
    }

    private suspend fun seedPlaylist(id: Long, name: String, syncEnabled: Boolean) {
        // Room honours an explicitly-supplied non-zero primary key even
        // when the column is autoGenerate=true — that lets us pin
        // p.id = 10 / 20 so the ORDER BY tie-breaker is deterministic.
        playlistDao.insert(
            PlaylistEntity(
                id = id,
                name = name,
                source = MusicSource.BOTH,
                sourceId = "test_playlist_$id",
                type = PlaylistType.CUSTOM,
                trackCount = 0,
                syncEnabled = syncEnabled,
                isActive = true,
            )
        )
    }

    private suspend fun seedPlaylistMembership(playlistId: Long, trackId: Long) {
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = 0,
                addedAt = Instant.EPOCH,
            )
        )
    }
}
