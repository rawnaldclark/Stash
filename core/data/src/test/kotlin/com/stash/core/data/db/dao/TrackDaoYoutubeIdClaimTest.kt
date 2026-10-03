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
 * The wrong-match swap (#531) claims the replacement video before it writes
 * any file, and gives the claim back if saving the file fails. These pin the
 * two queries that make that safe: the claim never takes a video another
 * track owns (tracks.youtube_id is UNIQUE), and the give-back only undoes the
 * swap's own claim.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoYoutubeIdClaimTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    private suspend fun insert(id: Long, album: String, youtubeId: String?): Long = dao.insert(
        TrackEntity(
            id = id,
            title = "Lacrymosa",
            artist = "Evanescence",
            album = album,
            youtubeId = youtubeId,
            canonicalTitle = "lacrymosa",
            canonicalArtist = "evanescence",
        ),
    )

    @Test
    fun `the claim skips a video another track owns`() = runTest {
        insert(id = 1L, album = "The Open Door", youtubeId = "open-door-video")
        val flagged = insert(id = 2L, album = "Synthesis", youtubeId = null)

        val applied = dao.updateYoutubeIdIfUnclaimed(flagged, "open-door-video")

        assertEquals(0, applied)
        assertNull(dao.getById(flagged)!!.youtubeId)
        assertEquals("open-door-video", dao.getById(1L)!!.youtubeId)
    }

    @Test
    fun `the claim takes a free video`() = runTest {
        val flagged = insert(id = 2L, album = "Synthesis", youtubeId = "wrong-video")

        val applied = dao.updateYoutubeIdIfUnclaimed(flagged, "synthesis-video")

        assertEquals(1, applied)
        assertEquals("synthesis-video", dao.getById(flagged)!!.youtubeId)
    }

    @Test
    fun `giving the claim back restores the previous video`() = runTest {
        val flagged = insert(id = 2L, album = "Synthesis", youtubeId = "synthesis-video")

        val restored = dao.restoreYoutubeIdIfClaimed(flagged, claimed = "synthesis-video", previous = "wrong-video")

        assertEquals(1, restored)
        assertEquals("wrong-video", dao.getById(flagged)!!.youtubeId)
    }

    @Test
    fun `giving the claim back to a row that had no video clears it`() = runTest {
        val flagged = insert(id = 2L, album = "Synthesis", youtubeId = "synthesis-video")

        val restored = dao.restoreYoutubeIdIfClaimed(flagged, claimed = "synthesis-video", previous = null)

        assertEquals(1, restored)
        assertNull(dao.getById(flagged)!!.youtubeId)
    }

    @Test
    fun `giving the claim back leaves a newer video alone`() = runTest {
        val flagged = insert(id = 2L, album = "Synthesis", youtubeId = "newer-video")

        val restored = dao.restoreYoutubeIdIfClaimed(flagged, claimed = "synthesis-video", previous = "wrong-video")

        assertEquals(0, restored)
        assertEquals("newer-video", dao.getById(flagged)!!.youtubeId)
    }
}
