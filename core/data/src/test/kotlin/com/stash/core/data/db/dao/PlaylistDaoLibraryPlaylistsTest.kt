package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
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
 * [PlaylistDao.hasLibraryPlaylists] decides whether a disconnected account's
 * Refresh/Accumulate still counts for deleting downloads (#273). It must see the
 * same playlists the Sync screens list for the account, and only that account's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoLibraryPlaylistsTest {
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

    private suspend fun playlist(
        source: MusicSource,
        sourceId: String,
        active: Boolean = true,
        userHidden: Boolean = false,
    ) = dao.insert(
        PlaylistEntity(
            name = sourceId, source = source, sourceId = sourceId, type = PlaylistType.CUSTOM,
            isActive = active, userHidden = userHidden,
        ),
    )

    @Test fun `an empty library has no account's playlists`() = runTest {
        assertFalse(dao.hasLibraryPlaylists(MusicSource.SPOTIFY))
        assertFalse(dao.hasLibraryPlaylists(MusicSource.YOUTUBE))
    }

    @Test fun `an active playlist counts for its own account only`() = runTest {
        playlist(MusicSource.SPOTIFY, "sp1")

        assertTrue(dao.hasLibraryPlaylists(MusicSource.SPOTIFY))
        assertFalse(dao.hasLibraryPlaylists(MusicSource.YOUTUBE))
    }

    @Test fun `a playlist the user took out of the library still counts`() = runTest {
        playlist(MusicSource.YOUTUBE, "yt1", active = false, userHidden = true)

        assertTrue(dao.hasLibraryPlaylists(MusicSource.YOUTUBE))
    }

    @Test fun `only deactivated playlists, which the Sync screens don't list, don't count`() = runTest {
        playlist(MusicSource.YOUTUBE, "yt-rotated-out", active = false)

        assertFalse(dao.hasLibraryPlaylists(MusicSource.YOUTUBE))
    }

    @Test fun `the user's own playlists don't count for any account`() = runTest {
        // Custom playlists and pasted links are the listener's own (BOTH), never an account's.
        playlist(MusicSource.BOTH, "custom_link_yt_abc")

        assertFalse(dao.hasLibraryPlaylists(MusicSource.SPOTIFY))
        assertFalse(dao.hasLibraryPlaylists(MusicSource.YOUTUBE))
    }
}
