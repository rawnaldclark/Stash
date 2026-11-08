package com.stash.core.data.social

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.sync.SpotifyPlaylistIds
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Like mirroring reads the MAIN Spotify account's Liked Songs only (plan 2026-11-04 §2.4): hearts
 * go to the main account, so a song liked only on an extra account (its own Liked row,
 * `spotify_liked_songs:<userId>`) must not count as "already liked there", nor may that row's
 * freshness vouch for the main's. Otherwise the main account would never get the heart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SpotifyLikedThereMainAccountTest {

    private lateinit var db: StashDatabase
    private val now = System.currentTimeMillis()
    private val hour = 60L * 60 * 1_000
    private var completeAt: Long? = null

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    private val dao get() = db.playlistDao()

    private suspend fun song(name: String): Long = db.trackDao().insert(
        TrackEntity(
            title = name, artist = "Artist", canonicalTitle = name.lowercase(), canonicalArtist = "artist",
            spotifyUri = "spotify:track:$name",
        ),
    )

    /** A Spotify Liked Songs row [sourceId] holding [songs], last read at [syncedAt]. */
    private suspend fun likedRow(sourceId: String, syncedAt: Long?, vararg songs: Long): Long {
        val id = dao.insert(
            PlaylistEntity(
                name = "Liked Songs", source = MusicSource.SPOTIFY, sourceId = sourceId,
                type = PlaylistType.LIKED_SONGS, syncEnabled = true,
                spotifyAccount = if (sourceId == SpotifyPlaylistIds.MAIN_LIKED_SONGS) null else "sam",
            ),
        )
        songs.forEachIndexed { i, track -> dao.insertCrossRef(PlaylistTrackCrossRef(playlistId = id, trackId = track, position = i)) }
        syncedAt?.let { dao.updateLastSynced(id, it) }
        return id
    }

    private fun check() = ServiceLikedThereCheck(
        playlistDao = dao,
        syncPreferencesManager = mockk<SyncPreferencesManager> { every { spotifySyncMode } returns flowOf(SyncMode.REFRESH) },
        innerTubeClient = mockk(relaxed = true),
        lastFmApiClient = mockk(relaxed = true),
        lastFmSessionPreference = mockk(relaxed = true),
        likedSongsFetch = mockk<SpotifyLikedSongsFetch> { coEvery { completeAt() } answers { completeAt } },
    )

    private val extraLiked = SpotifyPlaylistIds.extraLikedSongs("sam")

    @Test fun `the ids`() {
        assertEquals("spotify_liked_songs", SpotifyPlaylistIds.MAIN_LIKED_SONGS)
        assertEquals("spotify_liked_songs:sam", extraLiked)
    }

    @Test fun `a song liked only on an extra account isn't in the main's Liked Songs`() = runTest {
        val mainSong = song("main")
        val extraSong = song("extra")
        likedRow(SpotifyPlaylistIds.MAIN_LIKED_SONGS, now, mainSong)
        likedRow(extraLiked, now, extraSong)

        assertTrue(dao.isInSpotifyLikedSongs(mainSong, "spotify:track:main"))
        assertFalse(dao.isInSpotifyLikedSongs(extraSong, "spotify:track:extra"))
        // Nor through another row of the same Spotify song.
        assertFalse(dao.isInSpotifyLikedSongs(-1, "spotify:track:extra"))
    }

    @Test fun `the main's last read is the main row's, however fresh an extra's is`() = runTest {
        likedRow(extraLiked, now)
        assertNull("only an extra's Liked Songs: the main's was never read", dao.spotifyLikedSongsLastSynced())

        likedRow(SpotifyPlaylistIds.MAIN_LIKED_SONGS, now - 30 * 24 * hour)
        assertEquals(now - 30 * 24 * hour, dao.spotifyLikedSongsLastSynced())
    }

    @Test fun `like mirroring sends the heart for a song liked only on an extra account`() = runTest {
        val mainSong = song("main")
        val extraSong = song("extra")
        likedRow(SpotifyPlaylistIds.MAIN_LIKED_SONGS, now - hour, mainSong)
        likedRow(extraLiked, now - hour, extraSong)
        completeAt = now - 10 * 60 * 1_000

        val check = check()

        assertEquals(LikedThere.YES, check.onSpotify(Track(id = mainSong, title = "main", artist = "Artist", spotifyUri = "spotify:track:main")))
        // Not liked on the main account: Stash sends it there (and may take its own like back later).
        assertEquals(LikedThere.NO, check.onSpotify(Track(id = extraSong, title = "extra", artist = "Artist", spotifyUri = "spotify:track:extra")))
    }

    @Test fun `a fresh extra Liked Songs doesn't make a stale main one answer`() = runTest {
        val mainSong = song("main")
        likedRow(SpotifyPlaylistIds.MAIN_LIKED_SONGS, now - 30 * 24 * hour, mainSong)
        likedRow(extraLiked, now - hour, mainSong)

        assertEquals(
            LikedThere.UNKNOWN,
            check().onSpotify(Track(id = mainSong, title = "main", artist = "Artist", spotifyUri = "spotify:track:main")),
        )
    }
}
