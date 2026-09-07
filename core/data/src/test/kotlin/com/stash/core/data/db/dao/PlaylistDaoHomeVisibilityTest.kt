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

    private suspend fun insert(name: String, pinnedAt: Long?): Long = dao.insert(
        PlaylistEntity(
            name = name,
            source = MusicSource.SPOTIFY,
            sourceId = name,
            type = PlaylistType.DAILY_MIX,
            isActive = true,
            syncEnabled = false,
            pinnedToHomeAt = pinnedAt,
        )
    )
}
