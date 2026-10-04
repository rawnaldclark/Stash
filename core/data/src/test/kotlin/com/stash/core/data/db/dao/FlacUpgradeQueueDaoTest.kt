package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.FlacUpgradeStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Persisted-worklist semantics for the batch FLAC-upgrade worker (spec
 * 2026-07-22 §3): single-batch startBatch (clear-and-insert), per-row
 * status transitions, and cancel's keep-terminal/drop-pending split.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FlacUpgradeQueueDaoTest {

    private lateinit var db: StashDatabase
    private lateinit var trackDao: TrackDao
    private lateinit var dao: FlacUpgradeQueueDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        trackDao = db.trackDao()
        dao = db.flacUpgradeQueueDao()
    }

    @After fun tearDown() { db.close() }

    private suspend fun insertTrack(title: String): Long = trackDao.insert(
        TrackEntity(
            title = title, artist = "x",
            canonicalTitle = title.lowercase(), canonicalArtist = "x",
        ),
    )

    @Test fun `startBatch clears previous rows and inserts fresh PENDING`() = runTest {
        val t1 = insertTrack("A")
        val t2 = insertTrack("B")

        dao.startBatch(listOf(t1))
        dao.setStatus(t1, FlacUpgradeStatus.DONE)
        dao.startBatch(listOf(t2))

        assertEquals(listOf(t2), dao.pendingTrackIds())
        assertEquals(1, dao.countAll()) // t1's DONE row was cleared by the new batch
    }

    @Test fun `the worker drains a batch in the order it was given`() = runTest {
        val (a, b, c) = listOf("A", "B", "C").map { insertTrack(it) }

        // The sweep orders never-tried tracks first; a selection keeps the user's order.
        dao.startBatch(listOf(c, a, b))

        assertEquals(listOf(c, a, b), dao.pendingTrackIds())
    }

    @Test fun `counts reflect per-status transitions`() = runTest {
        val ids = listOf("A", "B", "C").map { insertTrack(it) }
        dao.startBatch(ids)

        dao.setStatus(ids[0], FlacUpgradeStatus.DONE)
        dao.setStatus(ids[1], FlacUpgradeStatus.NO_MATCH)

        assertEquals(1, dao.countByStatus(FlacUpgradeStatus.DONE))
        assertEquals(1, dao.countByStatus(FlacUpgradeStatus.NO_MATCH))
        assertEquals(1, dao.countByStatus(FlacUpgradeStatus.PENDING))
        assertEquals(0, dao.countByStatus(FlacUpgradeStatus.FAILED))
    }

    @Test fun `clearPending drops the remainder but keeps terminal rows`() = runTest {
        val ids = listOf("A", "B").map { insertTrack(it) }
        dao.startBatch(ids)
        dao.setStatus(ids[0], FlacUpgradeStatus.DONE)

        dao.clearPending()

        assertEquals(emptyList<Long>(), dao.pendingTrackIds())
        assertEquals(1, dao.countAll())
    }

    @Test fun `deleting a track cascades its queue row away`() = runTest {
        val id = insertTrack("A")
        dao.startBatch(listOf(id))

        trackDao.deleteById(id)

        assertEquals(0, dao.countAll())
    }
}
