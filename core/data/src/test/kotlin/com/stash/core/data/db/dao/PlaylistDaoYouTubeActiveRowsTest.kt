package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.album.SavedAlbums
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

/**
 * [PlaylistDao.hasActiveYouTubeRows] decides whether an Empty YouTube library
 * listing is believable. If it answered for the wrong kind of row (an album for
 * a playlist, an inactive row, a Spotify row), an expired cookie could again
 * pass as a complete inventory and REFRESH would hide the user's library.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoYouTubeActiveRowsTest {

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

    @Test fun `empty database holds nothing to hide`() = runTest {
        assertFalse(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = false))
        assertFalse(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = true))
        assertFalse(dao.hasActiveYouTubeRows(PlaylistType.LIKED_SONGS, savedAlbums = false))
    }

    @Test fun `library playlists and saved albums are told apart`() = runTest {
        insert("PLmine", MusicSource.YOUTUBE, PlaylistType.CUSTOM)

        assertTrue(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = false))
        assertFalse(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = true))

        insert("MPREb_album", MusicSource.YOUTUBE, PlaylistType.CUSTOM)

        assertTrue(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = true))
    }

    @Test fun `liked songs row counts for the liked listing`() = runTest {
        insert("youtube_liked_songs", MusicSource.YOUTUBE, PlaylistType.LIKED_SONGS)

        assertTrue(dao.hasActiveYouTubeRows(PlaylistType.LIKED_SONGS, savedAlbums = false))
        assertFalse(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = false))
    }

    @Test fun `inactive and other-source rows are ignored`() = runTest {
        insert("PLhidden", MusicSource.YOUTUBE, PlaylistType.CUSTOM, active = false)
        insert("spotify:playlist:x", MusicSource.SPOTIFY, PlaylistType.CUSTOM)
        insert("RDmix", MusicSource.YOUTUBE, PlaylistType.DAILY_MIX)

        assertFalse(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = false))
    }

    @Test fun `the album split is the one saved-album rule, and other services' albums never count`() = runTest {
        // Albums saved elsewhere are no evidence about the YouTube listings.
        insert("album:spotify:4aawyAB9vmqN3uQ7FjRGTy", MusicSource.SPOTIFY, PlaylistType.CUSTOM)
        insert("album:youtube:MPREb_saved", MusicSource.BOTH, PlaylistType.CUSTOM)
        assertFalse(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = true))
        assertFalse(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = false))

        for ((sourceId, album) in listOf("MPREb_synced" to true, "PLlibrary" to false)) {
            assertEquals(album, SavedAlbums.isSavedAlbum(PlaylistType.CUSTOM, MusicSource.YOUTUBE, sourceId))
        }
        insert("MPREb_synced", MusicSource.YOUTUBE, PlaylistType.CUSTOM)
        assertTrue(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = true))
        assertFalse(dao.hasActiveYouTubeRows(PlaylistType.CUSTOM, savedAlbums = false))
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
