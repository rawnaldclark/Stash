package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import java.time.Instant
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
 * The Sync tab's track count card (2026-11-04): [TrackDao.observeSongCountsBySource]. Offline counts
 * downloaded songs per source, the old card's number. Online counts the songs Library's Songs tab
 * lists ([IN_LIBRARY]): downloaded or not, but not a mix-only song, a dismissed match or a blocked one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoSongCountsBySourceTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao
    private lateinit var playlistDao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
        playlistDao = db.playlistDao()
    }

    @After fun tearDown() { db.close() }

    private suspend fun track(id: Long, source: MusicSource, downloaded: Boolean, matchDismissed: Boolean = false): Long =
        dao.insert(
            TrackEntity(
                id = id,
                title = "Song $id",
                artist = "Artist $id",
                source = source,
                isDownloaded = downloaded,
                matchDismissed = matchDismissed,
                dateAdded = Instant.ofEpochMilli(1_000_000 + id),
                canonicalArtist = "artist$id",
                canonicalTitle = "song$id",
            ),
        )

    private suspend fun playlist(type: PlaylistType, vararg trackIds: Long) {
        val id = playlistDao.insert(
            PlaylistEntity(
                name = "Playlist ${type.name}",
                source = MusicSource.SPOTIFY,
                sourceId = "pl_${type.name}_${System.nanoTime()}",
                type = type,
                trackCount = trackIds.size,
                isActive = true,
            ),
        )
        trackIds.forEachIndexed { i, trackId ->
            playlistDao.insertCrossRef(
                PlaylistTrackCrossRef(playlistId = id, trackId = trackId, position = i, addedAt = Instant.EPOCH),
            )
        }
    }

    /** Six songs: two downloaded, two streamable library songs, one mix-only, one dismissed. */
    private suspend fun seed() {
        track(1, MusicSource.SPOTIFY, downloaded = true)
        track(2, MusicSource.YOUTUBE, downloaded = true)
        track(3, MusicSource.SPOTIFY, downloaded = false)
        track(4, MusicSource.SOUNDCLOUD, downloaded = false)
        track(5, MusicSource.YOUTUBE, downloaded = false)
        track(6, MusicSource.SPOTIFY, downloaded = false, matchDismissed = true)
        playlist(PlaylistType.CUSTOM, 3, 4, 6)
        playlist(PlaylistType.DAILY_MIX, 5)
    }

    @Test fun `offline counts downloaded songs per source`() = runTest {
        seed()

        val counts = dao.observeSongCountsBySource(inLibrary = false).first().associate { it.source to it.songs }

        assertEquals(mapOf(MusicSource.SPOTIFY to 1, MusicSource.YOUTUBE to 1), counts)
    }

    @Test fun `online counts the songs in your library, downloaded or not`() = runTest {
        seed()

        val counts = dao.observeSongCountsBySource(inLibrary = true).first().associate { it.source to it.songs }

        // 1 + 3 (Spotify), 2 (YouTube; 5 is in a mix only), 4 (SoundCloud); 6 was dismissed.
        assertEquals(
            mapOf(MusicSource.SPOTIFY to 2, MusicSource.YOUTUBE to 1, MusicSource.SOUNDCLOUD to 1),
            counts,
        )
    }

    @Test fun `online leaves a blocked song out`() = runTest {
        seed()
        db.trackBlocklistDao().insert(
            TrackBlocklistEntity(
                canonicalKey = "artist3|song3",
                artist = "Artist 3",
                title = "Song 3",
                blockedAt = 1_000L,
                blockedFrom = "CONTEXT_MENU",
            ),
        )

        val counts = dao.observeSongCountsBySource(inLibrary = true).first().associate { it.source to it.songs }

        assertEquals(1, counts[MusicSource.SPOTIFY])
    }

    @Test fun `an empty library has no rows`() = runTest {
        assertEquals(emptyList<TrackSourceCount>(), dao.observeSongCountsBySource(inLibrary = true).first())
        assertEquals(emptyList<TrackSourceCount>(), dao.observeSongCountsBySource(inLibrary = false).first())
    }
}
