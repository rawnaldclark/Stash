package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Single-folder and Per-playlist layouts name files `<artist>-<title>`, so two
 * recordings of one song (#531: Lacrymosa on The Open Door and on Synthesis)
 * can end up on one file. The swap asks [TrackDao.countOtherTracksWithFilePath]
 * before writing over or deleting a file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoSharedFileTest {

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

    private suspend fun insert(id: Long, album: String, filePath: String?) = dao.insert(
        TrackEntity(
            id = id,
            title = "Lacrymosa",
            artist = "Evanescence",
            album = album,
            filePath = filePath,
            isDownloaded = filePath != null,
            canonicalTitle = "lacrymosa",
            canonicalArtist = "evanescence",
        ),
    )

    @Test
    fun `another track on the same file is counted, the track itself is not`() = runTest {
        val shared = "/music/evanescence-lacrymosa.opus"
        insert(id = 7L, album = "Synthesis", filePath = shared)
        insert(id = 8L, album = "The Open Door", filePath = shared)
        insert(id = 9L, album = "Fallen", filePath = "/music/evanescence-my-immortal.opus")

        assertEquals(1, dao.countOtherTracksWithFilePath(shared, trackId = 7L))
        assertEquals(0, dao.countOtherTracksWithFilePath("/music/evanescence-my-immortal.opus", trackId = 9L))
        assertEquals(0, dao.countOtherTracksWithFilePath("/music/nobody.opus", trackId = 7L))
    }
}
