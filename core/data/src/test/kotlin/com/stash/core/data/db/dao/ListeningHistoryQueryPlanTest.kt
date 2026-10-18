package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the "live queries, no rollup table" decision (plan §1.5): each "Your listening" query reads
 * `listening_events` through an index, never with a full pass, so it stays in the tens of
 * milliseconds at 100k listens. EXPLAIN QUERY PLAN runs on exactly the SQL Room runs
 * ([ListeningHistorySql]). No ANALYZE, as on a phone (Room never runs it).
 *
 * If this goes red after a query change, the query now scans the whole history: make its day filter
 * use [ListeningHistorySql.IN_DAYS] (an index range), not an expression over the columns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ListeningHistoryQueryPlanTest {

    private lateinit var db: StashDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private val values = mapOf(
        "fromDay" to 20_000L, "toDay" to 20_030L, "limit" to 100L,
        "beforeStartedAt" to Long.MAX_VALUE, "beforeId" to Long.MAX_VALUE, "pattern" to "%x%",
    )

    /** The plan's detail lines, binding each distinct `:name` in order of first appearance (SQLite's numbering). */
    private fun plan(sql: String): List<String> {
        val names = Regex(":([A-Za-z]+)").findAll(sql).map { it.groupValues[1] }.distinct().toList()
        val args = names.map { values.getValue(it) }.toTypedArray()
        return db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { c ->
            val detail = c.getColumnIndexOrThrow("detail")
            buildList { while (c.moveToNext()) add(c.getString(detail)) }
        }.also { println("PLAN ${sql.trim().take(60)}...\n  " + it.joinToString("\n  ")) }
    }

    /** A full pass over the listens: a SCAN of the outer `le` (table or whole index). */
    private fun scansHistory(plan: List<String>) =
        plan.any { Regex("""\bSCAN (TABLE )?(listening_events AS )?le\b""").containsMatchIn(it) }

    private fun assertDayRangeUsesIndex(name: String, sql: String) {
        val p = plan(sql)
        assertTrue("$name should read the (local_day, track_id) index: $p", p.any { "index_listening_events_local_day_track_id" in it })
        assertFalse("$name scans every listen: $p", scansHistory(p))
        assertFalse("$name scans tracks: $p", p.any { Regex("""\bSCAN (TABLE )?(tracks AS )?t\b""").containsMatchIn(it) })
    }

    @Test fun `day-range aggregates read the day index`() {
        assertDayRangeUsesIndex("dayCounts", ListeningHistorySql.DAY_COUNTS)
        assertDayRangeUsesIndex("topTracks", ListeningHistorySql.TOP_TRACKS)
        assertDayRangeUsesIndex("topArtists", ListeningHistorySql.TOP_ARTISTS)
        assertDayRangeUsesIndex("topAlbums", ListeningHistorySql.TOP_ALBUMS)
        assertDayRangeUsesIndex("totals", ListeningHistorySql.TOTALS)
        assertDayRangeUsesIndex("hourHistogram", ListeningHistorySql.HOUR_HISTOGRAM)
        assertDayRangeUsesIndex("newTracks", ListeningHistorySql.NEW_TRACKS)
        assertDayRangeUsesIndex("countNewTracks", ListeningHistorySql.COUNT_NEW_TRACKS)
        assertDayRangeUsesIndex("obsession", ListeningHistorySql.OBSESSION)
    }

    @Test fun `the discoveries' earlier-listen check looks songs up by track`() {
        val p = plan(ListeningHistorySql.NEW_TRACKS)
        assertTrue("e2 should be a track_id lookup: $p", p.any { "e2" in it && "index_listening_events_track_id" in it })
    }

    @Test fun `history pages walk the started_at index with no sort`() {
        val p = plan(ListeningHistorySql.HISTORY_PAGE)
        assertTrue("history should use the started_at index: $p", p.any { "index_listening_events_started_at" in it })
        assertFalse("history sorts the whole result: $p", p.any { "TEMP B-TREE FOR ORDER BY" in it })
    }

    @Test fun `first listen day reads one index entry`() {
        val p = plan(ListeningHistorySql.FIRST_LISTEN_DAY)
        assertTrue(p.toString(), p.any { "index_listening_events_started_at" in it })
        assertFalse(p.toString(), p.any { "TEMP B-TREE" in it })
    }
}
