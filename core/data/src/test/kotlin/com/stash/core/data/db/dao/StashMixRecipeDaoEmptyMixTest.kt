package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
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
 * A finished sync builds the Stash mixes when one is empty (2026-10-29): a new
 * install built Daily Discover at first launch from an empty library and it
 * stayed empty until the next day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixRecipeDaoEmptyMixTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: StashMixRecipeDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.stashMixRecipeDao()
    }

    @After fun tearDown() { db.close() }

    private suspend fun mixPlaylist(): Long = db.playlistDao().insert(
        PlaylistEntity(name = "Daily Discover", source = MusicSource.BOTH, sourceId = "stash_mix_1", type = PlaylistType.STASH_MIX),
    )

    @Test fun `a mix never built is empty`() = runTest {
        dao.insert(StashMixRecipeEntity(name = "Daily Discover", isBuiltin = true, isActive = true))
        assertTrue(dao.anyActiveMixEmpty())
    }

    @Test fun `a mix built from an empty library is empty`() = runTest {
        val id = dao.insert(StashMixRecipeEntity(name = "Daily Discover", isBuiltin = true, isActive = true))
        dao.setPlaylistId(id, mixPlaylist())
        assertTrue(dao.anyActiveMixEmpty())
    }

    @Test fun `a mix with songs is not empty, so a sync leaves it alone`() = runTest {
        val id = dao.insert(StashMixRecipeEntity(name = "Daily Discover", isBuiltin = true, isActive = true))
        val playlistId = mixPlaylist()
        dao.setPlaylistId(id, playlistId)
        val trackId = db.trackDao().insert(TrackEntity(title = "Song", artist = "Artist"))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId))
        assertFalse(dao.anyActiveMixEmpty())
    }

    @Test fun `a switched-off mix does not count`() = runTest {
        dao.insert(StashMixRecipeEntity(name = "Recommended by Last.fm", isBuiltin = true, isActive = false))
        assertFalse(dao.anyActiveMixEmpty())
    }
}
