package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WebLibraryExportDao.libraryStamp]: what Your record's mixes are made again for. A like, or a song added to your own playlist or
 * a Liked Songs list, changes it; a Daily Mix, a Stash Mix or the Downloads list rewritten by a sync doesn't (those aren't the
 * library the mixes read), nor does your own playlist while it is removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LibraryStampDaoTest {
    private lateinit var db: StashDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private var n = 0

    private suspend fun song(liked: Boolean = false): Long = db.trackDao().insert(
        TrackEntity(title = "Song ${++n}", artist = "A", youtubeId = "y$n", source = MusicSource.YOUTUBE, stashLikedAt = if (liked) 1L else null),
    )

    private suspend fun playlist(type: PlaylistType, active: Boolean = true): Long =
        db.playlistDao().insert(PlaylistEntity(name = "$type", source = MusicSource.BOTH, sourceId = "p_${type}_${++n}", type = type, isActive = active))

    private suspend fun add(playlist: Long, track: Long) = db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlist, track, position = n))

    private suspend fun stamp() = db.webLibraryExportDao().libraryStamp().first()

    @Test fun `mixes and the Downloads list leave it as it was, your library changes it`() = runTest {
        val mine = playlist(PlaylistType.CUSTOM)
        add(mine, song())
        song(liked = true)
        val before = stamp()
        assertThat(db.webLibraryExportDao().libraryStampNow()).isEqualTo(before)

        // a sync rewriting Daily Mixes, Stash Mixes and Downloads: nothing the mixes read
        for (type in listOf(PlaylistType.DAILY_MIX, PlaylistType.STASH_MIX, PlaylistType.DOWNLOADS_MIX)) {
            val p = playlist(type)
            repeat(3) { add(p, song()) }
        }
        // your own playlist while it is removed: not read either
        add(playlist(PlaylistType.CUSTOM, active = false), song())
        assertThat(stamp()).isEqualTo(before)

        // a song added to your playlist, a song liked, a song in a Liked Songs list: each changes it
        add(mine, song())
        val added = stamp()
        assertThat(added).isNotEqualTo(before)
        song(liked = true)
        val liked = stamp()
        assertThat(liked).isNotEqualTo(added)
        add(playlist(PlaylistType.LIKED_SONGS), song())
        assertThat(stamp()).isNotEqualTo(liked)
    }
}
