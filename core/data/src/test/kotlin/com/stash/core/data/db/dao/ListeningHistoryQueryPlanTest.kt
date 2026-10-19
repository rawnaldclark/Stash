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
 * Guards the "live queries, no rollup table" decision (plan §1.5): every day-range read of
 * `listening_events` is answered from the covering v59 index alone (no table row, no full pass), so
 * an all-time read stays in the tens of milliseconds at 60k listens (measured on the Pixel 5 rig).
 * EXPLAIN QUERY PLAN runs on exactly the SQL Room runs ([ListeningHistorySql]). No ANALYZE, as on a
 * phone (Room never runs it).
 *
 * If this goes red after a query change: read the range through [ListeningHistorySql.LE] and the
 * earlier-listen set through [ListeningHistorySql.HEARD_BEFORE]. An OR day filter, a per-listen join
 * or a column missing from the index makes SQLite fetch every table row.
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
        "beforeStartedAt" to Long.MAX_VALUE, "beforeId" to Long.MAX_VALUE, "pattern" to "%x%", "afterId" to 0L,
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

    private val dayIndex = "index_listening_events_local_day_track_id_started_at_listened_ms_local_minute"

    /** Plan lines that read `listening_events` itself (not a subquery or alias of it). */
    private fun historyReads(plan: List<String>) =
        plan.filter { Regex("""\b(SCAN|SEARCH) (TABLE )?listening_events\b""").containsMatchIn(it) }

    private fun assertIndexOnly(name: String, sql: String) {
        val p = plan(sql)
        val reads = historyReads(p)
        assertTrue("$name should read the covering day index: $p", reads.any { "COVERING INDEX $dayIndex" in it })
        reads.forEach { line ->
            assertTrue(
                "$name reads table rows or the whole history: $line in $p",
                line.contains("SEARCH") && line.contains("COVERING INDEX"),
            )
        }
        assertFalse("$name uses an OR day filter (fetches every row): $p", p.any { "MULTI-INDEX OR" in it })
        assertFalse("$name scans tracks: $p", p.any { Regex("""\bSCAN (TABLE )?tracks\b""").containsMatchIn(it) })
    }

    @Test fun `day-range aggregates read only the covering day index`() {
        assertIndexOnly("dayCounts", ListeningHistorySql.DAY_COUNTS)
        assertIndexOnly("topTracks", ListeningHistorySql.TOP_TRACKS)
        assertIndexOnly("topArtists", ListeningHistorySql.TOP_ARTISTS)
        assertIndexOnly("topAlbums", ListeningHistorySql.TOP_ALBUMS)
        assertIndexOnly("totals", ListeningHistorySql.TOTALS)
        assertIndexOnly("hourHistogram", ListeningHistorySql.HOUR_HISTOGRAM)
        assertIndexOnly("newTracks", ListeningHistorySql.NEW_TRACKS)
        assertIndexOnly("countNewTracks", ListeningHistorySql.COUNT_NEW_TRACKS)
        assertIndexOnly("obsession", ListeningHistorySql.OBSESSION)
    }

    @Test fun `songs are joined by primary key after grouping`() {
        for (sql in listOf(
            ListeningHistorySql.TOP_TRACKS, ListeningHistorySql.TOP_ARTISTS, ListeningHistorySql.TOP_ALBUMS,
            ListeningHistorySql.TOTALS, ListeningHistorySql.NEW_TRACKS,
        )) {
            val p = plan(sql)
            assertTrue(p.toString(), p.any { Regex("""\bSEARCH (TABLE )?(tracks AS )?t\b.*PRIMARY KEY""").containsMatchIn(it) })
        }
    }

    @Test fun `history pages walk the started_at index with no sort`() {
        val p = plan(ListeningHistorySql.HISTORY_PAGE)
        assertTrue("history should use the started_at index: $p", p.any { "index_listening_events_started_at" in it })
        assertFalse("history sorts the whole result: $p", p.any { "TEMP B-TREE FOR ORDER BY" in it })
    }

    @Test fun `first listen day reads the day index, never the whole history`() {
        val p = plan(ListeningHistorySql.FIRST_LISTEN_DAY)
        val reads = historyReads(p)
        assertTrue(p.toString(), reads.isNotEmpty())
        reads.forEach { assertTrue("$it in $p", it.contains("SEARCH") && it.contains("INDEX")) }
        assertFalse(p.toString(), p.any { "TEMP B-TREE" in it })
    }

    @Test fun `the backfill walks the table by rowid, without sorting`() {
        val p = plan(ListeningHistorySql.UNSTAMPED_AFTER)
        assertTrue(p.toString(), p.any { Regex("""\bSEARCH (TABLE )?listening_events USING INTEGER PRIMARY KEY \(rowid>\?\)""").containsMatchIn(it) })
        assertFalse(p.toString(), p.any { "TEMP B-TREE" in it })
    }
}
