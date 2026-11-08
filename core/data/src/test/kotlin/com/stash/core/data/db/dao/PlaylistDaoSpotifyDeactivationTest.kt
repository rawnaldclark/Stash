package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [PlaylistDao.deactivateMissingForSource]: a source's rows a complete inventory no longer lists. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoSpotifyDeactivationTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.playlistDao()
    }

    @After fun tearDown() = db.close()

    // Spotify's rows are measured by the sync's joint hide since several accounts:
    // SpotifyJointHideTest covers them, the library-sized listing included.

    @Test fun `a library-sized YouTube inventory deactivates only what is missing`() = runTest {
        val present = (1..1200).map { "yt$it" }
        present.forEach { insert(it, MusicSource.YOUTUBE, PlaylistType.CUSTOM) }
        val missing = insert("gone", MusicSource.YOUTUBE, PlaylistType.DAILY_MIX)
        val spotify = insert("spotify-row", MusicSource.SPOTIFY, PlaylistType.CUSTOM)

        assertEquals(1, dao.deactivateMissingForSource(MusicSource.YOUTUBE, present))

        assertFalse(dao.getById(missing)!!.isActive)
        assertTrue(dao.getById(spotify)!!.isActive)
        assertEquals(1200, dao.getActivePlaylistsBySource(MusicSource.YOUTUBE).size)
    }

    private suspend fun insert(
        sourceId: String,
        source: MusicSource,
        type: PlaylistType,
        active: Boolean = true,
    ): Long = dao.insert(
        PlaylistEntity(
            name = sourceId,
            source = source,
            sourceId = sourceId,
            type = type,
            isActive = active,
        )
    )
}
