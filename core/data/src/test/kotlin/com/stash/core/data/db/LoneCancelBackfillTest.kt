package com.stash.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.KEPT_OUT_MARK
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
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
import java.time.Instant

/**
 * The one-time pass that marks cancels from before the kept-out mark: a lone cancel (the X on one
 * song) stays out, a row of a Clear queue (cancels milliseconds apart) comes back with its
 * playlist. The rows below copy the Pixel 5's real ones (2026-11-09): "conundrum"'s Clear queue of
 * 52 rows 4 to 70 ms apart, a pair 32 ms apart, a lone cancel 4.1 s before that pair. Real
 * in-memory Room and the real SharedPreferences file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LoneCancelBackfillTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("stash_migrations_test", Context.MODE_PRIVATE)
    private lateinit var db: StashDatabase
    private lateinit var dao: DownloadQueueDao
    private var nextTrack = 1L

    @Before fun setUp() {
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        dao = db.downloadQueueDao()
    }

    @After fun tearDown() = db.close()

    private fun backfill(now: Long = FIRST_RUN) = LoneCancelBackfill(dao, prefs, now = { now })

    /** A song with one row: cancelled at [completedAt] when [status] is SKIPPED. Returns the row id. */
    private suspend fun row(completedAt: Long?, status: DownloadStatus = DownloadStatus.SKIPPED, error: String? = null): Long {
        val track = db.trackDao().insert(
            TrackEntity(title = "Song ${nextTrack}", artist = "A", canonicalTitle = "song ${nextTrack}", canonicalArtist = "a"),
        )
        nextTrack++
        return dao.insert(
            DownloadQueueEntity(
                trackId = track, status = status, errorMessage = error,
                completedAt = completedAt?.let(Instant::ofEpochMilli),
            ),
        )
    }

    private suspend fun keptOut(id: Long) = dao.getById(id)!!.errorMessage == KEPT_OUT_MARK

    @Test fun `a lone cancel stays out and a Clear queue's rows come back`() = runTest {
        // Before Clear queue existed every cancel was the X, even two taps 10 ms apart.
        val oldX = listOf(row(CLEAR_QUEUE - DAY), row(CLEAR_QUEUE - DAY + 10))
        val loneX = row(CLEAR_QUEUE + DAY)
        // conundrum: 52 rows, each 4 to 70 ms after the one before.
        val gaps = listOf(5L, 4L, 11L, 70L)
        var at = CLEAR_QUEUE + 2 * DAY
        val conundrum = (0 until 52).map { i -> row(at).also { at += gaps[i % gaps.size] } }
        // Programs alone, then Small Worlds and Buttons together 4.1 s later.
        val programs = row(CLEAR_QUEUE + 3 * DAY)
        val pair = listOf(row(CLEAR_QUEUE + 3 * DAY + 4_143), row(CLEAR_QUEUE + 3 * DAY + 4_175))
        val alreadyMarked = row(CLEAR_QUEUE + 4 * DAY, error = KEPT_OUT_MARK)
        val failed = row(CLEAR_QUEUE + 5 * DAY, status = DownloadStatus.FAILED)
        val noTime = row(completedAt = null)
        // A Clear queue of one song by this build, after the first run: never marked.
        val thisBuild = row(FIRST_RUN + 5_000)

        assertEquals(5, backfill().runOnce())

        (oldX + loneX + programs + noTime).forEach { assertTrue("lone cancel $it stays out", keptOut(it)) }
        (conundrum + pair).forEach { assertNull("Clear queue's row $it comes back", dao.getById(it)!!.errorMessage) }
        assertTrue(keptOut(alreadyMarked))
        assertNull(dao.getById(failed)!!.errorMessage)
        assertNull(dao.getById(thisBuild)!!.errorMessage)
        // What a playlist's Download does with them now: queues conundrum's songs, not Programs.
        assertEquals(true, dao.hasRowToLeaveAlone(dao.getById(programs)!!.trackId))
        assertEquals(false, dao.hasRowToLeaveAlone(dao.getById(conundrum.first())!!.trackId))
    }

    @Test fun `runs once`() = runTest {
        row(CLEAR_QUEUE + DAY)
        assertEquals(1, backfill().runOnce())
        assertTrue(prefs.getBoolean(LoneCancelBackfill.DONE_KEY, false))
        assertEquals(FIRST_RUN, prefs.getLong(LoneCancelBackfill.CUTOFF_KEY, 0L))

        // An old lone cancel that turns up later is left alone: the flag is set.
        val restored = row(CLEAR_QUEUE + 2 * DAY)
        assertNull(backfill().runOnce())
        assertNull(dao.getById(restored)!!.errorMessage)
    }

    @Test fun `a re-arm runs it again, still only over cancels from before the first run`() = runTest {
        assertEquals(0, backfill().runOnce())
        // A restored backup brings an old lone cancel back; this build has since cleared one song.
        val restored = row(CLEAR_QUEUE + DAY)
        val clearedSince = row(FIRST_RUN + 60_000)
        backfill().rearm()

        assertEquals(1, backfill(now = FIRST_RUN + 3_600_000).runOnce())
        assertTrue(keptOut(restored))
        assertNull(dao.getById(clearedSince)!!.errorMessage)
        assertNull(backfill().runOnce())
    }

    @Test fun `the update itself is idempotent`() = runTest {
        val lone = row(CLEAR_QUEUE + DAY)
        val burst = listOf(row(CLEAR_QUEUE + 2 * DAY), row(CLEAR_QUEUE + 2 * DAY + 30))
        suspend fun mark() = dao.markLoneCancelsKeptOut(FIRST_RUN, CLEAR_QUEUE, LoneCancelBackfill.BURST_WINDOW_MS)

        assertEquals(1, mark())
        assertEquals(0, mark())
        assertTrue(keptOut(lone))
        burst.forEach { assertNull(dao.getById(it)!!.errorMessage) }
    }

    /**
     * Review of #873, R2-4: a new phone runs the pass for the first time over a backup from the old
     * one. A Clear queue of one song made there by a build with the mark is not a lone cancel.
     */
    @Test fun `a cancel from after the mark existed is never marked, even on a first run long after`() = runTest {
        val beforeTheMark = row(MARK_SINCE - DAY)
        val oneSongClearQueue = row(MARK_SINCE + DAY)

        assertEquals(1, backfill(now = MARK_SINCE + 30 * DAY).runOnce())

        assertTrue(keptOut(beforeTheMark))
        assertNull(dao.getById(oneSongClearQueue)!!.errorMessage)
    }

    /** Review of #873, R2-3: the launch's call and a sync's requeue can ask at once. */
    @Test fun `ensureRan runs the pass once however many ask at once, then it is a flag read`() = runTest {
        val lone = row(CLEAR_QUEUE + DAY)
        val b = backfill()

        val results = listOf(async { b.ensureRan() }, async { b.ensureRan() }).map { it.await() }

        assertEquals(listOf(1), results.filterNotNull())
        assertTrue(keptOut(lone))
        assertNull(b.ensureRan())
    }

    @Test fun `a pass that fails lets the requeue go on, and the next launch tries again`() = runTest {
        val failing = mockk<DownloadQueueDao> {
            coEvery { markLoneCancelsKeptOut(any(), any(), any()) } throws IllegalStateException("disk full")
        }
        assertNull(LoneCancelBackfill(failing, prefs, now = { FIRST_RUN }).ensureRan())
        assertEquals(false, prefs.getBoolean(LoneCancelBackfill.DONE_KEY, false))

        row(CLEAR_QUEUE + DAY)
        assertEquals(1, backfill().ensureRan())
        coVerify(exactly = 1) { failing.markLoneCancelsKeptOut(any(), any(), any()) }
    }

    @Test fun `the mark first existed in c1675692`() {
        // c1675692 "Clear queue stops the attempt, it doesn't keep the songs out", 2026-11-09 12:40:38 -0500.
        assertEquals(Instant.parse("2026-11-09T17:40:38Z").toEpochMilli(), MARK_SINCE)
    }

    @Test fun `Clear queue first existed in 8008e294`() {
        // 8008e294 "Downloads queue: ... Clear queue and Clear history", 2026-10-29 11:03:28 -0400.
        assertEquals(Instant.parse("2026-10-29T15:03:28Z").toEpochMilli(), CLEAR_QUEUE)
    }

    private companion object {
        const val CLEAR_QUEUE = LoneCancelBackfill.CLEAR_QUEUE_SINCE_MS
        const val MARK_SINCE = LoneCancelBackfill.MARK_SINCE_MS
        const val DAY = 86_400_000L
        const val FIRST_RUN = CLEAR_QUEUE + 10 * DAY
    }
}
