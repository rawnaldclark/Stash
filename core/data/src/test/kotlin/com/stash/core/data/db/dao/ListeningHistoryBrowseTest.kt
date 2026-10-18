package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.listening.HistoryCursor
import com.stash.core.data.listening.ListeningDay
import com.stash.core.data.listening.ListeningHistoryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * The reads History and Home's "Your listening" add on top of [ListeningHistoryDao]'s base set:
 * paging upwards after a calendar jump, one day's listens for "Play this day", and the
 * "Recently played" rail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ListeningHistoryBrowseTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: ListeningHistoryDao
    private lateinit var events: ListeningEventDao
    private lateinit var repo: ListeningHistoryRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.listeningHistoryDao()
        events = db.listeningEventDao()
        repo = ListeningHistoryRepository(dao, { 0L }, { ZoneOffset.UTC })
    }

    @After fun tearDown() = db.close()

    private suspend fun track(id: Long, title: String, artist: String = "X") {
        db.trackDao().insert(
            TrackEntity(
                id = id, title = title, artist = artist, album = "", durationMs = 200_000,
                canonicalTitle = title.lowercase(), canonicalArtist = artist.lowercase(),
            ),
        )
    }

    private suspend fun listen(trackId: Long, startedAt: Long, day: Long? = startedAt / ListeningDay.MS_PER_DAY): Long =
        events.insert(
            ListeningEventEntity(
                trackId = trackId, startedAt = startedAt, completedAt = startedAt + 30_000,
                localDay = day, localMinute = 0,
            ),
        )

    @Test fun `newer pages walk up from a cursor without repeats, newest first within a page`() = runTest {
        track(1, "A")
        // Seven listens; two pairs share a start time.
        val ids = listOf(1_000L, 2_000L, 2_000L, 3_000L, 4_000L, 4_000L, 5_000L).map { listen(1, it) }
        // Start just after the oldest listen, as after a jump to its day.
        val oldest = ids.first()
        var cursor: HistoryCursor? = HistoryCursor(1_000L, oldest)
        val seen = ArrayList<Long>()
        while (cursor != null) {
            val page = repo.historyPageNewer(cursor, limit = 2)
            // Each page reads newest first, like History.
            assertEquals(page.rows.sortedWith(compareByDescending<HistoryRow> { it.event.startedAt }.thenByDescending { it.event.id }), page.rows)
            seen += page.rows.map { it.event.id }
            cursor = page.next
        }
        assertEquals((ids - oldest).toSet(), seen.toSet())
        assertEquals(ids.size - 1, seen.size)
    }

    @Test fun `newer pages honour the search`() = runTest {
        track(1, "Glory Box", "Portishead")
        track(2, "Teardrop", "Massive Attack")
        val first = listen(1, 1_000)
        listen(2, 2_000)
        val match = listen(1, 3_000)
        val page = repo.historyPageNewer(HistoryCursor(1_000, first), query = "glory")
        assertEquals(listOf(match), page.rows.map { it.event.id })
        assertNull(page.next)
    }

    @Test fun `one day's listens come oldest first, and only that day`() = runTest {
        track(1, "A")
        val day = 20_000L
        val base = day * ListeningDay.MS_PER_DAY
        listen(1, base - 60_000, day = day - 1)
        val a = listen(1, base + 3_600_000, day = day)
        val b = listen(1, base + 60_000, day = day)
        listen(1, base + ListeningDay.MS_PER_DAY + 1, day = day + 1)
        // Not yet stamped by the backfill: counts on its UTC day.
        val c = listen(1, base + 7_200_000, day = null)
        assertEquals(listOf(b, a, c), repo.listensOn(day).map { it.event.id })
    }

    @Test fun `recently played lists each song once, newest first`() = runTest {
        track(1, "A"); track(2, "B"); track(3, "C")
        listen(1, 1_000)
        listen(2, 2_000)
        listen(1, 3_000)
        listen(3, 4_000)
        listen(1, 5_000)
        val recent = repo.observeRecentTracks(limit = 12).first()
        assertEquals(listOf(1L, 3L, 2L), recent.map { it.track.id })
        assertEquals(listOf(3, 1, 1), recent.map { it.plays })
        assertEquals(5_000L, recent.first().lastPlayedAt)
    }

    @Test fun `recently played is cut to its limit and empty with no listens`() = runTest {
        assertTrue(repo.observeRecentTracks().first().isEmpty())
        (1L..15L).forEach { track(it, "T$it"); listen(it, it * 1_000) }
        val recent = repo.observeRecentTracks(limit = 12).first()
        assertEquals((15L downTo 4L).toList(), recent.map { it.track.id })
    }

    @Test fun `recently played re-emits when a listen is removed`() = runTest {
        track(1, "A"); track(2, "B")
        listen(1, 1_000)
        val newest = listen(2, 2_000)
        assertEquals(listOf(2L, 1L), repo.observeRecentTracks().first().map { it.track.id })
        repo.deleteListen(newest)
        assertEquals(listOf(1L), repo.observeRecentTracks().first().map { it.track.id })
    }

    // ── Query plans (the same guard as ListeningHistoryQueryPlanTest) ─────

    private fun plan(sql: String, values: Map<String, Long>): List<String> {
        val names = Regex(":([A-Za-z]+)").findAll(sql).map { it.groupValues[1] }.distinct().toList()
        val args = names.map<String, Any> { if (it == "pattern") "%x%" else values.getValue(it) }.toTypedArray()
        return db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { c ->
            val detail = c.getColumnIndexOrThrow("detail")
            buildList { while (c.moveToNext()) add(c.getString(detail)) }
        }
    }

    @Test fun `newer pages walk the started_at index with no sort`() {
        val p = plan(ListeningHistorySql.HISTORY_PAGE_NEWER, mapOf("afterStartedAt" to 0L, "afterId" to 0L, "limit" to 200L))
        assertTrue("should use the started_at index: $p", p.any { "index_listening_events_started_at" in it })
        assertFalse("sorts the whole result: $p", p.any { "TEMP B-TREE FOR ORDER BY" in it })
    }

    @Test fun `recently played reads only the newest rows off the started_at index`() {
        val p = plan(ListeningHistorySql.RECENT_TRACKS, mapOf("window" to 300L, "limit" to 12L))
        assertTrue("should read the started_at index: $p", p.any { "index_listening_events_started_at" in it })
        assertFalse("scans the whole history: $p", p.any { Regex("""SCAN (TABLE )?listening_events(?! USING)""").containsMatchIn(it) })
    }
}
