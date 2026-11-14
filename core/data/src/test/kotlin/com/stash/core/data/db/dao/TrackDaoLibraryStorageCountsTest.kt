package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The two queries Settings › Library & Storage runs on entry answer exactly
 * what the slower ones they replaced did: the batched playlist names match
 * the one-track query for every track, and the count matches the size of the
 * list it used to load.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoLibraryStorageCountsTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao
    private lateinit var playlists: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.trackDao()
        playlists = db.playlistDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `batched playlist names match the one-track query`() = runTest {
        // Ids in insert order: a name that sorts last gets the lowest id, so
        // "lowest id" and "first by name" give different answers.
        val zeta = playlists.insert(playlist("Zeta", "p1"))
        val off = playlists.insert(playlist("Switched off", "p2", syncEnabled = false))
        val alpha = playlists.insert(playlist("Alpha", "p3"))
        (1L..7L).forEach { dao.insert(track(it)) }

        link(zeta, 1L); link(alpha, 1L)                       // two enabled: lowest id
        link(off, 2L); link(alpha, 2L)                         // lowest id is switched off
        link(zeta, 3L, removed = true); link(alpha, 3L)        // lowest id was removed
        link(off, 4L)                                          // only a switched-off one
        link(zeta, 5L, removed = true)                         // only a removed one
        link(alpha, 6L)                                        // one playlist
        // 7: in no playlist

        val ids = (1L..7L).toList()
        val batched = dao.getFirstPlaylistNamesForTracks(ids + ids + 99L)

        val oneByOne = ids.mapNotNull { id -> dao.getFirstPlaylistNameForTrack(id)?.let { id to it } }.toMap()
        assertEquals(oneByOne, batched)
        assertEquals(
            mapOf(1L to "Zeta", 2L to "Alpha", 3L to "Alpha", 6L to "Alpha"),
            batched,
        )
    }

    /** More ids than one statement can bind: every chunk still picks the lowest id. */
    @Test fun `batched playlist names hold across bind chunks`() = runTest {
        val first = playlists.insert(playlist("First", "p1"))
        val second = playlists.insert(playlist("Second", "p2"))
        val ids = (1L..1_700L).toList()
        dao.insertAll(ids.map(::track))
        // Linked to the higher playlist id first, so row order alone is wrong.
        playlists.insertAllCrossRefs(ids.map { PlaylistTrackCrossRef(playlistId = second, trackId = it) })
        playlists.insertAllCrossRefs(ids.filter { it % 2L == 0L }.map { PlaylistTrackCrossRef(playlistId = first, trackId = it) })

        val batched = dao.getFirstPlaylistNamesForTracks(ids)

        assertEquals(ids.size, batched.size)
        assertEquals(ids.associateWith { if (it % 2L == 0L) "First" else "Second" }, batched)
        assertEquals(emptyMap<Long, String>(), dao.getFirstPlaylistNamesForTracks(emptyList()))
    }

    @Test fun `the movable count is the size of the movable list`() = runTest {
        val prefix = "/data/user/0/com.stash.app/files/music"
        var id = 0L
        fun row(path: String?, downloaded: Boolean = true) =
            track(++id).copy(filePath = path, isDownloaded = downloaded)
        dao.insertAll(
            listOf(
                row("$prefix/artist/album/song.opus"),
                row("$prefix/artist-song.m4a"),
                row("$prefix/artist/album/song.opus"),                 // two rows on one file
                row("$prefix/artist/album/gone.opus", downloaded = false),
                row(null),
                row(""),
                row("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fsong.opus"),
                row("/storage/emulated/0/Music/song.opus"),
                // LIKE reads "_" as "any one character": both queries do.
                row("/data/user/0/comXstashXapp/files/music/odd.opus"),
            ),
        )

        val listed = dao.getDownloadedTracksWithPathPrefix(prefix)

        assertEquals(listed.size, dao.countDownloadedTracksWithPathPrefix(prefix))
        assertEquals(3, listed.count { it.filePath!!.startsWith(prefix) })
        assertEquals(0, dao.countDownloadedTracksWithPathPrefix("/nowhere"))
    }

    private suspend fun link(playlistId: Long, trackId: Long, removed: Boolean = false) =
        playlists.insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                removedAt = if (removed) Instant.ofEpochMilli(1_000L) else null,
            ),
        )

    private fun playlist(name: String, sourceId: String, syncEnabled: Boolean = true) = PlaylistEntity(
        name = name,
        source = MusicSource.BOTH,
        sourceId = sourceId,
        syncEnabled = syncEnabled,
    )

    private fun track(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist",
        durationMs = 200_000L,
    )
}
