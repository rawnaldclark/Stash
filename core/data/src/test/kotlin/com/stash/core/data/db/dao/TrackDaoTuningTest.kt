package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
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
 * The tuning columns' queries (v81). A row is: never measured (all NULL, the worker takes it), measured (a reading
 * and a stamp), or undecodable (a stamp alone, not tried again).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoTuningTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    private fun track(id: Long, filePath: String? = "/$id.flac") = TrackEntity(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        canonicalTitle = "title $id",
        canonicalArtist = "artist $id",
        filePath = filePath,
        isDownloaded = filePath != null,
    )

    @Test fun `only downloaded songs never measured are asked for, in id order, a page at a time`() = runTest {
        (1L..5L).forEach { dao.insert(track(it)) }
        dao.insert(track(6L, filePath = null))
        dao.updateTuning(2L, cents = -31.77f, confidence = 0.9f, now = 1_000L)
        dao.markTuningFailed(4L, now = 1_000L)

        assertEquals(listOf(1L, 3L), dao.tracksNeedingTuning(afterId = 0L, limit = 2).map { it.id })
        assertEquals(listOf(5L), dao.tracksNeedingTuning(afterId = 3L, limit = 2).map { it.id })
    }

    @Test fun `a reading is stored with its confidence and its time`() = runTest {
        dao.insert(track(1L))
        dao.updateTuning(1L, cents = 21.7f, confidence = 0.42f, now = 5_000L)

        val row = dao.getById(1L)!!
        assertEquals(21.7f, row.tuningCents!!, 1e-4f)
        assertEquals(0.42f, row.tuningConfidence!!, 1e-4f)
        assertEquals(5_000L, row.tuningMeasuredAt)
    }

    @Test fun `an undecodable file keeps a stamp and no reading`() = runTest {
        dao.insert(track(1L))
        dao.updateTuning(1L, cents = 3f, confidence = 1f, now = 1_000L)
        dao.markTuningFailed(1L, now = 2_000L)

        val row = dao.getById(1L)!!
        assertNull(row.tuningCents)
        assertNull(row.tuningConfidence)
        assertEquals(2_000L, row.tuningMeasuredAt)
    }

    @Test fun `clearing a song's tuning puts it back in the queue`() = runTest {
        dao.insert(track(1L))
        dao.updateTuning(1L, cents = 3f, confidence = 1f, now = 1_000L)
        assertEquals(emptyList<Long>(), dao.tracksNeedingTuning(0L, 10).map { it.id })

        dao.clearTuning(1L)

        val row = dao.getById(1L)!!
        assertNull(row.tuningCents)
        assertNull(row.tuningMeasuredAt)
        assertEquals(listOf(1L), dao.tracksNeedingTuning(0L, 10).map { it.id })
    }
}
