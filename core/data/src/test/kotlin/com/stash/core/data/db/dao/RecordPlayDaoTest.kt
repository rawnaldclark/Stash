package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.RecordPlayEntity
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

/**
 * [RecordPlayDao]: Your record's imported plays, written and removed in batches, read a page at a time by id, counted for the
 * import record and the Home card, and kept out of everything that reads this phone's own history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecordPlayDaoTest {

    private lateinit var db: StashDatabase
    private val dao get() = db.recordPlayDao()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private fun row(id: String, ms: Long = 200_000, f: Int = 34, al: String? = "Blonde", s: Long = T, a: String = "Frank Ocean", t: String = "Ivy") =
        RecordPlayEntity(id = id, t = t, a = a, al = al, s = s, ms = ms, f = f)

    @Test fun `a batch writes its plays, then drops the ones it replaces`() = runTest {
        dao.apply(listOf(row("a|x|1"), row("b|x|2", f = 16)), emptyList())
        dao.apply(listOf(row("c|x|3")), listOf("b|x|2", "never|kept|0"))

        assertEquals(listOf("a|x|1", "c|x|3"), dao.pageAfter("", 10).map { it.id })
    }

    @Test fun `the same id twice is one play, the later one`() = runTest {
        dao.apply(listOf(row("a|x|1", ms = 1)), emptyList())
        dao.apply(listOf(row("a|x|1", ms = 2)), emptyList())

        assertEquals(1, dao.count())
        assertEquals(2L, dao.pageAfter("", 10).single().ms)
    }

    @Test fun `a drop longer than SQLite's bind limit goes in pieces`() = runTest {
        val ids = (0 until 2_500).map { "song|artist|$it" }
        dao.apply(ids.map { row(it) }, emptyList())
        dao.apply(emptyList(), ids.drop(1))
        assertEquals(listOf("song|artist|0"), dao.pageAfter("", 10).map { it.id })
    }

    @Test fun `pages run in id order from where the last stopped`() = runTest {
        dao.apply(listOf("d", "a", "c", "b", "e").map { row("$it|x|1") }, emptyList())

        val first = dao.pageAfter("", 2)
        val second = dao.pageAfter(first.last().id, 2)
        val third = dao.pageAfter(second.last().id, 2)

        assertEquals(listOf("a|x|1", "b|x|1"), first.map { it.id })
        assertEquals(listOf("c|x|1", "d|x|1"), second.map { it.id })
        assertEquals(listOf("e|x|1"), third.map { it.id })
        assertEquals(listOf("a|x|1", "b|x|1"), dao.keptAfter("", 2).map { it.id })
    }

    @Test fun `the import record counts listens by source, with the first and last`() = runTest {
        dao.apply(
            listOf(
                row("a|x|1", s = 1_000, ms = 30_000), // a listen (30 s exactly)
                row("b|x|2", s = 500, ms = 29_999), // a skip: not a listen, not the first
                row("c|x|3", s = 9_000, ms = 60_000, f = 64), // Last.fm
                row("d|x|4", s = 4_000, ms = 60_000, f = 256), // Stash on the phone
                row("e|x|5", s = 5_000, ms = 60_000, f = 256 or 4), // a phone skip that ran a minute: a listen
            ),
            emptyList(),
        )

        assertEquals(RecordPlayDao.Listens(spotify = 1, lastfm = 1, stash = 2, first = 1_000, last = 9_000), dao.listens())
    }

    @Test fun `nothing kept is no listens and no first or last`() = runTest {
        val none = dao.listens()
        assertEquals(0, none.spotify + none.lastfm + none.stash)
        assertNull(none.first)
        assertNull(none.last)
    }

    @Test fun `the stamp moves when plays come in or go, and only then`() = runTest {
        dao.stamp().test {
            assertEquals(RecordPlayDao.Stamp(0, 0, 0), awaitItem())
            dao.apply(listOf(row("a|x|1", s = 7, ms = 100)), emptyList())
            assertEquals(RecordPlayDao.Stamp(1, 100, 7), awaitItem())
            dao.clear()
            assertEquals(RecordPlayDao.Stamp(0, 0, 0), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `the card's albums are the listens totalled by artist and album, the album played last first`() = runTest {
        dao.apply(
            listOf(
                row("a|1", t = "Ivy", al = "Blonde", s = 1_000, ms = 200_000),
                row("a|2", t = "Nights", al = "Blonde", s = 2_000, ms = 300_000),
                row("a|3", t = "Skip", al = "Blonde", s = 9_000, ms = 10_000), // not a listen
                row("b|1", t = "Borderline", a = "Tame Impala", al = "The Slow Rush", s = 3_000, ms = 240_000),
                row("c|1", t = "Single", a = "Someone", al = null, s = 4_000), // no album
                row("d|1", t = "Blank", a = "Someone", al = "", s = 5_000), // no album
            ),
            emptyList(),
        )

        val totals = dao.albumTotals()

        assertEquals(listOf("The Slow Rush", "Blonde"), totals.map { it.album })
        val blonde = totals.last()
        assertEquals(2, blonde.plays)
        assertEquals(500_000L, blonde.listenedMs)
        assertEquals("Nights", blonde.title)
        assertNull(blonde.albumArtUrl)
        assertEquals(1_000L, blonde.firstAt)
        assertEquals(2_000L, blonde.lastAt)
    }

    @Test fun `imported plays are never this phone's own history`() = runTest {
        dao.apply(listOf(row("a|x|1"), row("b|x|2", f = 16)), emptyList())

        val export = db.webLibraryExportDao()
        assertTrue(export.ownPlaysBefore(Long.MAX_VALUE, Long.MAX_VALUE, 10).isEmpty())
        assertEquals(0, export.ownPlayCount())
        assertEquals(0, export.ownHistoryStamp().first().plays)
        assertTrue(export.ownAlbumTotals().isEmpty())
        assertTrue(db.mirrorDao().ownPlays(0, 10).isEmpty())
        val events = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM listening_events").use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, events)
    }

    private companion object {
        const val T = 1_759_838_400_000L
    }
}
