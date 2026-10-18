package com.stash.core.data.listening

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** [ListeningDayBackfill] stamps rows from before v59, in batches, without touching stamped rows. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ListeningDayBackfillTest {

    private lateinit var db: StashDatabase
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private suspend fun track() = db.trackDao().insert(
        TrackEntity(id = 1, title = "Song", artist = "Artist", canonicalTitle = "song", canonicalArtist = "artist"),
    )

    private fun stamps(): List<Triple<Long, Long?, Int?>> =
        db.query("SELECT id, local_day, local_minute FROM listening_events ORDER BY id", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Triple(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), if (c.isNull(2)) null else c.getInt(2)))
                }
            }
        }

    @Test fun `stamps every unstamped row in the given zone, more than one batch`() = runTest {
        track()
        val events = db.listeningEventDao()
        val base = Instant.parse("2026-10-17T18:00:00Z").toEpochMilli() // 23:30 in Kolkata
        val n = ListeningDayBackfill.BATCH * 2 + 17
        repeat(n) { i -> events.insert(ListeningEventEntity(trackId = 1, startedAt = base + i * 60_000L)) }

        val stamped = ListeningDayBackfill(db.listeningHistoryDao()) { kolkata }.run()

        assertEquals(n, stamped)
        assertEquals(0, db.listeningHistoryDao().countUnstamped())
        val rows = stamps()
        val oct17 = LocalDate.of(2026, 10, 17).toEpochDay()
        // Row 0: 23:30 on the 17th; row 30: 00:00 on the 18th.
        assertEquals(Triple(rows[0].first, oct17, 23 * 60 + 30), rows[0])
        assertEquals(Triple(rows[30].first, oct17 + 1, 0), rows[30])
        rows.forEachIndexed { i, (_, day, minute) ->
            assertEquals(ListeningDay.localStamp(base + i * 60_000L, kolkata), LocalStamp(day!!, minute!!))
        }
    }

    @Test fun `leaves stamped rows and the new columns of others alone, and a second run does nothing`() = runTest {
        track()
        val events = db.listeningEventDao()
        val stampedId = events.insert(
            ListeningEventEntity(trackId = 1, startedAt = 0L, localDay = 12345, localMinute = 7, listenedMs = 9_000),
        )
        val oldId = events.insert(ListeningEventEntity(trackId = 1, startedAt = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()))
        val backfill = ListeningDayBackfill(db.listeningHistoryDao()) { ZoneId.of("UTC") }

        assertEquals(1, backfill.run())
        assertEquals(0, backfill.run())

        val rows = stamps().associateBy { it.first }
        assertEquals(Triple(stampedId, 12345L, 7), rows[stampedId])
        assertEquals(Triple(oldId, LocalDate.of(2026, 1, 1).toEpochDay(), 0), rows[oldId])
        db.query("SELECT listened_ms FROM listening_events WHERE id = $stampedId", null).use { c ->
            c.moveToFirst()
            assertEquals(9_000L, c.getLong(0))
        }
    }

    @Test fun `an empty history is a no-op`() = runTest {
        assertEquals(0, ListeningDayBackfill(db.listeningHistoryDao()) { kolkata }.run())
    }
}
