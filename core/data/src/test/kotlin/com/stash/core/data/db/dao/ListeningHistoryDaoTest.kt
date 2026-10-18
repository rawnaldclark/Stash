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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [ListeningHistoryDao] over a real in-memory Room database: paging, search, per-day counts, the
 * top lists and their tie-breaks, totals with the song-length fallback, the hour histogram, the
 * Your Year helpers and the privacy deletes.
 *
 * Plays from another device (`origin_device` set) count here on purpose (owner decision 3,
 * 2026-10-18); the play_count they must never reach is checked after each delete.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ListeningHistoryDaoTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: ListeningHistoryDao
    private lateinit var events: ListeningEventDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.listeningHistoryDao()
        events = db.listeningEventDao()
    }

    @After fun tearDown() = db.close()

    private suspend fun track(
        id: Long,
        title: String,
        artist: String,
        album: String = "",
        albumArtist: String = "",
        durationMs: Long = 200_000,
    ) {
        db.trackDao().insert(
            TrackEntity(
                id = id, title = title, artist = artist, album = album, albumArtist = albumArtist, durationMs = durationMs,
                canonicalTitle = title.lowercase(), canonicalArtist = artist.lowercase(),
            ),
        )
    }

    /** A listen on [day] at [minute]; [startedAt] defaults to a value consistent with them in UTC. */
    private suspend fun listen(
        trackId: Long,
        day: Long?,
        minute: Int? = 600,
        startedAt: Long = (day ?: 0) * ListeningDay.MS_PER_DAY + (minute ?: 0) * 60_000L,
        listenedMs: Long? = null,
        origin: String? = null,
    ): Long = events.insert(
        ListeningEventEntity(
            trackId = trackId, startedAt = startedAt, completedAt = if (origin == null) startedAt + 30_000 else null,
            originDevice = origin, localDay = day, localMinute = minute, listenedMs = listenedMs,
        ),
    )

    private fun playCount(trackId: Long): Pair<Int, Long?> =
        db.query("SELECT play_count, last_played FROM tracks WHERE id = $trackId", null).use { c ->
            c.moveToFirst()
            c.getInt(0) to (if (c.isNull(1)) null else c.getLong(1))
        }

    // ── History paging ──────────────────────────────────────────────

    @Test fun `history pages newest first and ties on started_at never repeat or drop`() = runTest {
        track(1, "A", "X")
        // Five listens: three share one start time.
        val ids = listOf(
            listen(1, 10, startedAt = 5_000),
            listen(1, 10, startedAt = 9_000),
            listen(1, 10, startedAt = 9_000),
            listen(1, 10, startedAt = 9_000),
            listen(1, 10, startedAt = 7_000),
        )
        val repo = ListeningHistoryRepository(dao, { 0L }, { ZoneOffset.UTC })
        val seen = ArrayList<Long>()
        var cursor: HistoryCursor? = null
        var pages = 0
        do {
            val page = repo.historyPage(cursor, limit = 2)
            seen += page.rows.map { it.event.id }
            cursor = page.next
            pages++
        } while (cursor != null)

        // started_at DESC, then id DESC among the three at 9_000.
        assertEquals(listOf(ids[3], ids[2], ids[1], ids[4], ids[0]), seen)
        assertEquals(3, pages)
        assertTrue(dao.historyPage(Long.MAX_VALUE, Long.MAX_VALUE, null, 10).all { it.track.id == 1L })
    }

    @Test fun `a full last page is followed by an empty one`() = runTest {
        track(1, "A", "X")
        listen(1, 10, startedAt = 1)
        listen(1, 10, startedAt = 2)
        val repo = ListeningHistoryRepository(dao, { 0L }, { ZoneOffset.UTC })
        val first = repo.historyPage(null, limit = 2)
        assertEquals(2, first.rows.size)
        val second = repo.historyPage(first.next, limit = 2)
        assertEquals(0, second.rows.size)
        assertNull(second.next)
    }

    @Test fun `search matches title, artist or album, case-insensitively, wildcards taken literally`() = runTest {
        track(1, "Borderline", "Tame Impala", album = "The Slow Rush")
        track(2, "100% Pure", "Someone")
        track(3, "1000 Days", "Other")
        track(4, "a_b", "Under")
        track(5, "axb", "Under")
        for (id in 1L..5L) listen(id, 10, startedAt = id)

        suspend fun search(q: String?) = dao.historyPage(Long.MAX_VALUE, Long.MAX_VALUE, ListeningHistoryDao.likePattern(q), 50)
            .map { it.track.id }.sorted()

        assertEquals(listOf(1L), search("border"))
        assertEquals(listOf(1L), search("TAME"))
        assertEquals(listOf(1L), search("slow rush"))
        assertEquals(listOf(2L), search("100%"))
        assertEquals(listOf(4L), search("a_b"))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), search("  "))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), search(null))
        assertEquals(emptyList<Long>(), search("zzz"))
        assertEquals("%a\\%b\\_c\\\\%", ListeningHistoryDao.likePattern(" a%b_c\\ "))
    }

    @Test fun `search pages with the same cursor rule`() = runTest {
        track(1, "Match", "X")
        track(2, "Other", "Y")
        repeat(5) { i -> listen(if (i % 2 == 0) 1 else 2, 10, startedAt = 100L + i) }
        val repo = ListeningHistoryRepository(dao, { 0L }, { ZoneOffset.UTC })
        val p1 = repo.historyPage(null, "match", limit = 2)
        val p2 = repo.historyPage(p1.next, "match", limit = 2)
        assertEquals(listOf(104L, 102L), p1.rows.map { it.event.startedAt })
        assertEquals(listOf(100L), p2.rows.map { it.event.startedAt })
        assertNull(p2.next)
    }

    // ── Day counts ──────────────────────────────────────────────────

    @Test fun `day counts group by local day, fall back to the UTC day and the song length`() = runTest {
        track(1, "A", "X", durationMs = 200_000)
        track(2, "B", "Y", durationMs = 0)
        listen(1, 100, listenedMs = 50_000)
        listen(1, 100) // unknown time -> 200 s
        listen(2, 100) // unknown time, unknown length -> 0
        listen(1, 102, listenedMs = 1_000, origin = "d_web00000000000") // another device counts
        // Not stamped yet: its UTC day (101) stands in.
        listen(1, null, minute = null, startedAt = 101 * ListeningDay.MS_PER_DAY + 5)
        listen(1, 99) // outside the range
        listen(1, 103) // outside the range

        val counts = dao.observeDayCounts(100, 102).first()
        assertEquals(
            listOf(DayCount(100, 3, 250_000), DayCount(101, 1, 200_000), DayCount(102, 1, 1_000)),
            counts,
        )
    }

    @Test fun `day counts flow updates when a listen lands`() = runTest {
        track(1, "A", "X")
        assertEquals(emptyList<DayCount>(), dao.observeDayCounts(0, 1000).first())
        listen(1, 5, listenedMs = 10)
        assertEquals(listOf(DayCount(5, 1, 10)), dao.observeDayCounts(0, 1000).first())
    }

    // ── Top lists ───────────────────────────────────────────────────

    @Test fun `top tracks by plays, ties to the one heard last, then the lower id`() = runTest {
        track(1, "One", "X")
        track(2, "Two", "X")
        track(3, "Three", "Y")
        track(4, "Four", "Y")
        repeat(3) { listen(1, 10, startedAt = 1_000L + it) }
        listen(2, 10, startedAt = 5_000)
        listen(2, 11, startedAt = 6_000, origin = "d_web00000000000") // counts: 2 plays, last at 6000
        listen(3, 10, startedAt = 2_000)
        listen(3, 10, startedAt = 6_000) // same plays and same last play as 2 -> lower id first
        listen(4, 50, startedAt = 9_000) // outside the range

        val top = dao.observeTopTracks(10, 20, 10).first()
        assertEquals(listOf(1L, 2L, 3L), top.map { it.track.id })
        assertEquals(listOf(3, 2, 2), top.map { it.plays })
        assertEquals(1_002L, top[0].lastPlayedAt)
        assertEquals("One", top[0].track.title)
        assertEquals(listOf(1L), dao.observeTopTracks(10, 20, 1).first().map { it.track.id })
    }

    @Test fun `top artists group case-insensitively and take the latest spelling and cover`() = runTest {
        track(1, "A", "Tame Impala")
        track(2, "B", "tame impala")
        track(3, "C", "Other")
        db.openHelper.writableDatabase.execSQL("UPDATE tracks SET album_art_url = 'art-' || id")
        listen(1, 10, startedAt = 1)
        listen(1, 10, startedAt = 2)
        listen(2, 10, startedAt = 9)
        listen(3, 10, startedAt = 3)
        listen(3, 10, startedAt = 4)

        val top = dao.observeTopArtists(0, 100, 10).first()
        assertEquals(listOf("tame impala", "Other"), top.map { it.artist })
        assertEquals(listOf(3, 2), top.map { it.plays })
        assertEquals(listOf(2, 1), top.map { it.songs })
        assertEquals("art-2", top[0].albumArtUrl)
    }

    @Test fun `top albums group by album and album artist, else artist, and skip songs without one`() = runTest {
        track(1, "A", "Feat Guest", album = "Currents", albumArtist = "Tame Impala")
        track(2, "B", "Tame Impala", album = "currents")
        track(3, "C", "Someone", album = "Currents") // another artist's album of the same name
        track(4, "D", "Single", album = "")
        listen(1, 10, startedAt = 1)
        listen(2, 10, startedAt = 2)
        listen(3, 10, startedAt = 3)
        listen(4, 10, startedAt = 4)
        listen(4, 10, startedAt = 5)

        val top = dao.observeTopAlbums(0, 100, 10).first()
        assertEquals(2, top.size)
        assertEquals(2, top[0].plays)
        assertEquals("tame impala", top[0].artist.lowercase())
        assertEquals(2, top[0].songs)
        assertEquals("Someone", top[1].artist)
    }

    // ── Totals and the clock ────────────────────────────────────────

    @Test fun `totals count songs, artists and days, and say how much time is estimated`() = runTest {
        track(1, "A", "Artist", durationMs = 100_000)
        track(2, "B", "ARTIST", durationMs = 300_000)
        track(3, "C", "Else", durationMs = 0)
        listen(1, 10, listenedMs = 40_000)
        listen(1, 11)
        listen(2, 11, origin = "d_web00000000000")
        listen(3, 12)

        val t = dao.observeTotals(10, 12).first()
        assertEquals(ListeningTotals(plays = 4, songs = 3, artists = 2, days = 3, listenedMs = 440_000, estimatedPlays = 3), t)
        assertEquals(ListeningTotals.EMPTY, dao.observeTotals(500, 600).first())
    }

    @Test fun `hour histogram reads the local minute, else the UTC hour`() = runTest {
        track(1, "A", "X")
        listen(1, 10, minute = 0)
        listen(1, 10, minute = 59)
        listen(1, 10, minute = 23 * 60 + 59)
        listen(1, null, minute = null, startedAt = 10 * ListeningDay.MS_PER_DAY + 5 * 3_600_000L + 1)
        assertEquals(
            listOf(HourCount(0, 2), HourCount(5, 1), HourCount(23, 1)),
            dao.observeHourHistogram(10, 10).first(),
        )
    }

    // ── Your Year ───────────────────────────────────────────────────

    @Test fun `new tracks are those with no listen before the range`() = runTest {
        track(1, "Old", "X")
        track(2, "New", "X")
        track(3, "Newer", "X")
        listen(1, 5)
        listen(1, 20)
        listen(2, 20)
        listen(2, 21)
        listen(3, 22)
        listen(3, 40) // after the range: still new in it, counted only inside it

        val fresh = dao.newTracksIn(10, 30)
        assertEquals(listOf(2L, 3L), fresh.map { it.track.id })
        assertEquals(listOf(2, 1), fresh.map { it.plays })
        assertEquals(2, dao.countNewTracksIn(10, 30))
        assertEquals(0, dao.countNewTracksIn(100, 200))
    }

    @Test fun `an earlier listen not yet stamped still makes a song not new`() = runTest {
        track(1, "Old", "X")
        track(2, "New", "X")
        listen(1, null, minute = null, startedAt = 5 * ListeningDay.MS_PER_DAY) // UTC day 5
        listen(1, 20)
        listen(2, null, minute = null, startedAt = 20 * ListeningDay.MS_PER_DAY + 1) // in range, unstamped
        assertEquals(listOf(2L), dao.newTracksIn(10, 30).map { it.track.id })
        assertEquals(1, dao.countNewTracksIn(10, 30))
        assertEquals(2, dao.countNewTracksIn(0, 30))
    }

    @Test fun `obsession is the most plays of one song in one day, the later day on a tie`() = runTest {
        track(1, "A", "X")
        track(2, "B", "X")
        repeat(3) { listen(1, 10) }
        repeat(3) { listen(2, 12) }
        repeat(2) { listen(2, 13) }
        val o = dao.obsession(0, 100)!!
        assertEquals(2L, o.track.id)
        assertEquals(12L, o.day)
        assertEquals(3, o.plays)
        assertNull(dao.obsession(500, 600))
    }

    @Test fun `first listen day and the years with listens`() = runTest {
        assertNull(dao.firstListenDay())
        assertEquals(emptyList<Int>(), dao.yearsWithListens())
        track(1, "A", "X")
        val d2024 = LocalDate.of(2024, 12, 31).toEpochDay()
        val d2026 = LocalDate.of(2026, 1, 1).toEpochDay()
        listen(1, d2026)
        listen(1, d2024)
        listen(1, d2026 + 3)
        assertEquals(d2024, dao.firstListenDay())
        assertEquals(listOf(2026, 2024), dao.yearsWithListens())
    }

    @Test fun `first listen day is the smallest day, not the day of the earliest start`() = runTest {
        track(1, "A", "X")
        // Heard in Tokyo (day 101 there), then an hour later in Los Angeles, still day 100 there.
        listen(1, 101, startedAt = 1_000_000)
        listen(1, 100, startedAt = 1_000_000 + 3_600_000)
        assertEquals(100L, dao.firstListenDay())
        // An unstamped row counts on its UTC day: a later start on an earlier day still wins.
        listen(1, null, minute = null, startedAt = 99 * ListeningDay.MS_PER_DAY + 5_000_000)
        assertEquals(99L, dao.firstListenDay())
    }

    @Test fun `first listen day with only unstamped rows`() = runTest {
        track(1, "A", "X")
        listen(1, null, minute = null, startedAt = 42 * ListeningDay.MS_PER_DAY + 1)
        listen(1, null, minute = null, startedAt = 40 * ListeningDay.MS_PER_DAY + 1)
        assertEquals(40L, dao.firstListenDay())
    }

    // ── Privacy deletes ─────────────────────────────────────────────

    @Test fun `deleting a listen recomputes the song's play count from this phone's listens only`() = runTest {
        track(1, "A", "X")
        val a = listen(1, 10, startedAt = 1_000)
        val b = listen(1, 11, startedAt = 2_000)
        listen(1, 12, startedAt = 3_000, origin = "d_web00000000000")
        events.backfillMissingTrackStats()
        assertEquals(2 to 32_000L, playCount(1))
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO listen_submissions (event_id, target, state, attempts, updated_at) VALUES ($b, 'listenbrainz', 'FAILED', 1, 0)",
        )

        assertEquals(1, dao.deleteEvent(b))
        assertEquals(1 to 31_000L, playCount(1))
        assertEquals(0, db.query("SELECT COUNT(*) FROM listen_submissions", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, dao.deleteEvent(b))

        assertEquals(1, dao.deleteEvent(a))
        assertEquals(0 to null, playCount(1))
        // The other device's listen is still in History.
        assertEquals(1, dao.observeTotals(0, 100).first().plays)
    }

    @Test fun `delete a day, a range, everything`() = runTest {
        track(1, "A", "X")
        track(2, "B", "Y")
        listen(1, 10)
        listen(2, 10)
        listen(1, 11)
        listen(1, 12)
        listen(2, null, minute = null, startedAt = 13 * ListeningDay.MS_PER_DAY) // unstamped, UTC day 13
        listen(2, 14)
        events.backfillMissingTrackStats()

        assertEquals(2, dao.deleteDay(10))
        assertEquals(listOf(11L, 12L, 13L, 14L), dao.observeDayCounts(0, 100).first().map { it.day })
        assertEquals(2, dao.deleteBetween(12, 13))
        assertEquals(listOf(11L, 14L), dao.observeDayCounts(0, 100).first().map { it.day })
        assertEquals(1, playCount(1).first)
        assertEquals(1, playCount(2).first)
        assertEquals(2, dao.deleteAll())
        assertEquals(0 to null, playCount(1))
        assertEquals(0 to null, playCount(2))
    }

    @Test fun `deleteFromOrigin removes only that origin's rows`() = runTest {
        track(1, "A", "X")
        listen(1, 10)
        listen(1, 10, origin = "debug-seed")
        listen(1, 10, origin = "debug-seed")
        listen(1, 10, origin = "d_web00000000000")
        assertEquals(2, dao.deleteFromOrigin("debug-seed"))
        assertEquals(2, dao.observeTotals(0, 100).first().plays)
    }

    @Test fun `deleting a song takes its listens out of history`() = runTest {
        track(1, "A", "X")
        listen(1, 10)
        db.trackDao().deleteById(1)
        assertEquals(0, dao.observeTotals(0, 100).first().plays)
    }
}
