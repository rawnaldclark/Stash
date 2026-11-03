package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The queries behind Spotify saved-album sync's review follow-ups (#817): a page's fingerprints in
 * three queries, the albums due for a re-read, what a block and an unblock forget, and a synced
 * album taken into Stash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PlaylistDaoSpotifyAlbumsTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.playlistDao()
    }

    @After fun tearDown() = db.close()

    private suspend fun playlist(
        sourceId: String,
        source: MusicSource = MusicSource.SPOTIFY,
        stamp: String? = "v2|added:x|REFRESH|1|1|abc",
        lastSyncedMs: Long? = null,
    ): Long {
        val id = dao.insert(PlaylistEntity(name = sourceId, source = source, sourceId = sourceId, type = PlaylistType.CUSTOM))
        dao.updateSnapshotId(id, stamp)
        lastSyncedMs?.let { dao.updateLastSynced(id, it) }
        return id
    }

    private suspend fun songs(playlistId: Long, artist: String, vararg titles: String) {
        titles.forEachIndexed { i, title ->
            val track = db.trackDao().insert(
                TrackEntity(title = title, artist = artist, canonicalTitle = title.lowercase(), canonicalArtist = artist.lowercase()),
            )
            dao.insertCrossRef(PlaylistTrackCrossRef(playlistId = playlistId, trackId = track, position = i))
        }
        dao.updateTrackCount(playlistId, titles.size)
    }

    @Test fun `a page's fingerprints are each playlist's own, in three queries`() = runTest {
        val a = playlist("album:spotify:A")
        val b = playlist("album:spotify:B")
        val empty = playlist("album:spotify:C")
        songs(a, "Fugazi", "Waiting Room", "Bulldog Front")
        songs(b, "Fugazi", "Waiting Room") // shares a song with a: their copies overlap
        dao.updateTrackCount(empty, 0)

        val digests = dao.mirrorDigests(listOf(a, b, empty, 999L))

        assertEquals(dao.mirrorDigest(a), digests[a])
        assertEquals(dao.mirrorDigest(b), digests[b])
        assertEquals(dao.mirrorDigest(empty), digests[empty])
        assertNull("no such playlist, no fingerprint", digests[999L])
        assertTrue(dao.mirrorDigests(emptyList()).isEmpty())
    }

    @Test fun `rows are looked up by many keys at once`() = runTest {
        playlist("album:spotify:A")
        playlist("album:spotify:B")

        val found = dao.findBySourceIds(listOf("album:spotify:A", "album:spotify:B", "album:spotify:missing"))

        assertEquals(setOf("album:spotify:A", "album:spotify:B"), found.map { it.sourceId }.toSet())
    }

    @Test fun `the albums due for a re-read are the synced ones read longest ago, oldest first`() = runTest {
        playlist("album:spotify:old", lastSyncedMs = 1_000)
        playlist("album:spotify:older", lastSyncedMs = 500)
        playlist("album:spotify:recent", lastSyncedMs = 9_000)
        playlist("album:spotify:unstamped", stamp = null, lastSyncedMs = 100)
        playlist("album:spotify:pasted", source = MusicSource.BOTH, lastSyncedMs = 100)
        playlist("37iPlaylistNotAnAlbumx", lastSyncedMs = 100)
        val hidden = playlist("album:spotify:hidden", lastSyncedMs = 100)
        dao.setUserHidden(hidden, true)

        assertEquals(listOf("album:spotify:older", "album:spotify:old"), dao.spotifyAlbumsReadBefore(5_000, limit = 10))
        assertEquals(listOf("album:spotify:older"), dao.spotifyAlbumsReadBefore(5_000, limit = 1))
    }

    @Test fun `a block forgets the playlists' stamps and keeps every album's`() = runTest {
        val playlist = playlist("37iPlaylistxxxxxxxxxxx")
        val album = playlist("album:spotify:A")

        dao.forgetSpotifyPlaylistStamps()

        assertNull(dao.getSnapshotId(playlist))
        assertNotNull(dao.getSnapshotId(album))
    }

    @Test fun `an unblock forgets the stamps of the albums the song may be on, and no others`() = runTest {
        val sameAct = playlist("album:spotify:A")
        songs(sameAct, "Fugazi", "Waiting Room")
        val shortOfSpotify = playlist("album:spotify:B")
        songs(shortOfSpotify, "Minor Threat", "Filler")
        dao.updateTrackCount(shortOfSpotify, 2) // Spotify gave it two songs; one was blocked away
        val other = playlist("album:spotify:C")
        songs(other, "Bad Brains", "Banned in DC")
        val pasted = playlist("album:spotify:D", source = MusicSource.BOTH)
        songs(pasted, "Fugazi", "Merchandise")

        dao.forgetSpotifyAlbumStampsForUnblock("fugazi")

        assertNull(dao.getSnapshotId(sameAct))
        assertNull(dao.getSnapshotId(shortOfSpotify))
        assertNotNull(dao.getSnapshotId(other))
        assertNotNull("not the sync's", dao.getSnapshotId(pasted))
    }

    @Test fun `a synced album is taken into Stash, anything else stays as it is`() = runTest {
        val synced = playlist("album:spotify:A")
        val pasted = playlist("album:spotify:B", source = MusicSource.BOTH)

        assertEquals(1, dao.takeIntoStash(synced))
        assertEquals(0, dao.takeIntoStash(pasted))
        assertEquals(MusicSource.BOTH, dao.getById(synced)!!.source)
    }
}
