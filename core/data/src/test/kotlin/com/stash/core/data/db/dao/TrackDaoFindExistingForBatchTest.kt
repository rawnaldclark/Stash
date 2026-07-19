package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the sentinel-value contract on [TrackDao.findExistingForBatch]:
 * callers must never pass an empty list (SQLite `IN ()` is a syntax error),
 * and a sentinel placeholder for a dimension with no real values must not
 * spuriously match.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoFindExistingForBatchTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    private val sentinel = "\u0000__stash_never_match__"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `empty list for a dimension is safe and matches via the others`() = runTest {
        // Room 2.7 expands an empty IN () safely (no SQLiteException) — the
        // sentinel in the DAO wrapper is belt-and-suspenders for older Room,
        // not a live requirement. Documents actual behavior.
        val id = dao.insert(TrackEntity(title = "T", artist = "A", canonicalTitle = "t", canonicalArtist = "a"))

        val result = dao.findExistingForBatch(
            spotifyUris = emptyList(),
            youtubeIds = emptyList(),
            canonicalKeys = listOf("t|a"),
        )

        assertEquals(1, result.size)
        assertEquals(id, result.single().id)
    }

    @Test fun `sentinel placeholder matches nothing by itself, only real canonical key matches`() = runTest {
        val id = dao.insert(
            TrackEntity(title = "Runaway", artist = "Kanye West", canonicalTitle = "runaway", canonicalArtist = "kanye west")
        )

        val result = dao.findExistingForBatch(
            spotifyUris = listOf(sentinel),
            youtubeIds = listOf(sentinel),
            canonicalKeys = listOf("runaway|kanye west"),
        )

        assertEquals(1, result.size)
        assertEquals(id, result.single().id)
    }

    @Test fun `sentinel never accidentally matches a real row`() = runTest {
        dao.insert(TrackEntity(title = "T", artist = "A", spotifyUri = null, canonicalTitle = "t", canonicalArtist = "a"))

        val result = dao.findExistingForBatch(
            spotifyUris = listOf(sentinel),
            youtubeIds = listOf(sentinel),
            canonicalKeys = listOf("no-match-key"),
        )

        assertTrue(result.isEmpty())
    }
}