package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ListeningEventDao.anyListenSince]: Community's "heard" from the listening history. This phone's own listen of one of
 * the songs, started at or after the post went up, and nothing else; read off the track_id index, never the whole
 * history (listening_events grows forever).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ListeningEventDaoListenSinceTest {

    private lateinit var db: StashDatabase
    private lateinit var events: ListeningEventDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        events = db.listeningEventDao()
    }

    @After fun tearDown() = db.close()

    private suspend fun song(id: Long) {
        db.trackDao().insert(TrackEntity(id = id, title = "Song $id", artist = "A", canonicalTitle = "song $id", canonicalArtist = "a"))
    }

    @Test fun `a listen here at or after the post's time counts`() = runTest {
        song(1)
        events.insert(ListeningEventEntity(trackId = 1, startedAt = POSTED, completedAt = POSTED + 120_000))
        assertTrue(events.anyListenSince(listOf(1L), POSTED))
        assertTrue(events.anyListenSince(listOf(1L), POSTED - 1))
        assertFalse(events.anyListenSince(listOf(1L), POSTED + 1))
    }

    @Test fun `a listen before the post went up, another device's, or another song's doesn't`() = runTest {
        song(1); song(2)
        events.insert(ListeningEventEntity(trackId = 1, startedAt = POSTED - 1, completedAt = POSTED + 120_000))
        events.insert(ListeningEventEntity(trackId = 1, startedAt = POSTED + 60_000, completedAt = POSTED + 90_000, originDevice = "d_web00000000000"))
        events.insert(ListeningEventEntity(trackId = 2, startedAt = POSTED + 60_000, completedAt = POSTED + 90_000))
        assertFalse(events.anyListenSince(listOf(1L), POSTED))
        assertFalse(events.anyListenSince(emptyList(), POSTED))
        assertTrue(events.anyListenSince(listOf(1L, 2L), POSTED))
    }

    @Test fun `more ids than one statement binds are checked in chunks`() = runTest {
        song(5_000)
        events.insert(ListeningEventEntity(trackId = 5_000, startedAt = POSTED, completedAt = POSTED + 30_000))
        // The listened song is in the third chunk; 999 binds would crash an unchunked IN () on Android 11 and older.
        assertTrue(events.anyListenSince((1L..5_000L).toList(), POSTED))
        assertFalse(events.anyListenSince((1L..4_999L).toList(), POSTED))
    }

    @Test fun `it reads the track_id index, never the whole history`() {
        val p = db.openHelper.readableDatabase.query(
            "EXPLAIN QUERY PLAN " + ANY_LISTEN_SINCE_SQL.replace(":trackIds", "?, ?").replace(":sinceMs", "?"),
            arrayOf<Any>(1L, 2L, POSTED),
        ).use { c ->
            val detail = c.getColumnIndexOrThrow("detail")
            buildList { while (c.moveToNext()) add(c.getString(detail)) }
        }
        val reads = p.filter { Regex("""\b(SCAN|SEARCH) (TABLE )?listening_events\b""").containsMatchIn(it) }
        assertTrue(p.toString(), reads.isNotEmpty())
        reads.forEach { assertTrue("$it in $p", it.contains("SEARCH") && it.contains("index_listening_events_track_id")) }
    }

    private companion object {
        /** When the post went up. */
        const val POSTED = 1_762_000_000_000L
    }
}
