package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [TrackDao.getRecentlyPlayed]: the Community picker's recent songs (spec 2026-09-26 §3). */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoRecentlyPlayedTest {
    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `played songs come newest first, downloaded or stream-only, and never-played ones don't come`() = runTest {
        dao.insert(TrackEntity(title = "old", artist = "A", isDownloaded = true, lastPlayed = Instant.ofEpochMilli(100)))
        dao.insert(TrackEntity(title = "streamed", artist = "A", youtubeId = "y1", lastPlayed = Instant.ofEpochMilli(300)))
        dao.insert(TrackEntity(title = "never", artist = "A"))
        assertThat(dao.getRecentlyPlayed(20).map { it.title }).containsExactly("streamed", "old").inOrder()
        assertThat(dao.getRecentlyPlayed(1).map { it.title }).containsExactly("streamed")
    }

    @Test fun `a blocked song isn't suggested, and the limit counts after the filter`() = runTest {
        dao.insert(TrackEntity(title = "Banned", artist = "Drake", canonicalArtist = "drake", canonicalTitle = "banned",
            youtubeId = "y9", lastPlayed = Instant.ofEpochMilli(500)))
        dao.insert(TrackEntity(title = "ok", artist = "A", youtubeId = "y1", lastPlayed = Instant.ofEpochMilli(300)))
        db.trackBlocklistDao().insert(TrackBlocklistEntity(canonicalKey = "drake|banned", artist = "Drake",
            title = "Banned", blockedAt = 1L, blockedFrom = "OTHER"))
        assertThat(dao.getRecentlyPlayed(1).map { it.title }).containsExactly("ok")
    }
}
