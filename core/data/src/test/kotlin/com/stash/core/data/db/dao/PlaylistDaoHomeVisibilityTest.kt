package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's visibility query admitted a mix only when sync was on or one of its
 * tracks was downloaded or streamable. The download queue excludes mixes on
 * purpose (#368), so a radio sharing no track with the user's own playlists
 * could never reach Home however it was toggled — Cup Noodle Radio, Pixel 6,
 * 2026-09-07: 50 tracks, none downloaded, none streamable, "Shown on Home" on.
 * A mix the user pinned is visible because they said so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoHomeVisibilityTest {

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

    @Test fun `a pinned mix with no playable track is visible`() = runTest {
        val cupNoodle = insert("Cup Noodle Radio", pinnedAt = 1_788_815_681_296L)
        insert("Zach Top Radio", pinnedAt = null)

        assertEquals(listOf(cupNoodle), dao.getAllVisible(includeStreamable = false).first().map { it.id })
    }

    @Test fun `hiding unpins, and an unpinned mix with no playable track stays out`() = runTest {
        val id = insert("Cup Noodle Radio", pinnedAt = 1L)
        dao.setHomeVisibility(id, hidden = true, pinnedAt = null)

        assertEquals(emptyList<Long>(), dao.getAllVisible(includeStreamable = false).first().map { it.id })
    }

    private suspend fun insert(name: String, pinnedAt: Long?, type: PlaylistType = PlaylistType.DAILY_MIX): Long = dao.insert(
        PlaylistEntity(
            name = name,
            source = MusicSource.SPOTIFY,
            sourceId = name,
            type = type,
            isActive = true,
            syncEnabled = false,
            pinnedToHomeAt = pinnedAt,
        )
    )

    // -- Sync on pins, sync off unpins (the Sync tab's switch decides Home) ----------

    @Test fun `syncing a playlist pins it to Home and keeps an earlier pin stamp`() = runTest {
        val fresh = insert("Euro trash", pinnedAt = null, type = PlaylistType.CUSTOM)
        val pinnedBefore = insert("Road trip", pinnedAt = 5L, type = PlaylistType.CUSTOM)
        dao.setSyncEnabledAndPin(fresh, enabled = true, now = 1_000L)
        dao.setSyncEnabledAndPin(pinnedBefore, enabled = true, now = 1_000L)

        assertEquals(1_000L, dao.getById(fresh)!!.pinnedToHomeAt)
        assertEquals(5L, dao.getById(pinnedBefore)!!.pinnedToHomeAt)
        assertEquals(true, dao.getById(fresh)!!.syncEnabled)
    }

    @Test fun `turning sync off unpins the playlist`() = runTest {
        val id = insert("Euro trash", pinnedAt = 5L, type = PlaylistType.CUSTOM)
        dao.setSyncEnabledAndPin(id, enabled = false, now = 1_000L)

        assertEquals(null, dao.getById(id)!!.pinnedToHomeAt)
        assertEquals(false, dao.getById(id)!!.syncEnabled)
    }

    @Test fun `Liked Songs is never auto-pinned, it has its own card`() = runTest {
        val liked = insert("Liked Songs", pinnedAt = null, type = PlaylistType.LIKED_SONGS)
        dao.setSyncEnabledAndPin(liked, enabled = true, now = 1_000L)

        assertEquals(null, dao.getById(liked)!!.pinnedToHomeAt)
        assertEquals(true, dao.getById(liked)!!.syncEnabled)
    }
}
