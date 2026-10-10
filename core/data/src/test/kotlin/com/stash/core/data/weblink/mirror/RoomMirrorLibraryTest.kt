package com.stash.core.data.weblink.mirror

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.data.weblink.merge.PlayRec
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mirror's writes into a real in-memory library (spec §7.1, §7.2): a mirrored like is a Stash like and an unlike clears only
 * a Stash like; a play from another device lands in History with its origin and never moves play counts or the mix inputs; a
 * mirrored playlist is written in order and starts no downloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomMirrorLibraryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var lib: RoomMirrorLibrary

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        val music = mockk<MusicRepository>()
        coEvery { music.ensureExactTrackPersisted(any()) } coAnswers {
            val s = firstArg<SharedTrack>()
            db.trackDao().insert(TrackEntity(title = s.title, artist = s.artist, isrc = s.isrc, isStreamable = true, canonicalTitle = canon(s.title), canonicalArtist = canon(s.artist)))
        }
        coEvery { music.addTrackToPlaylist(any(), any()) } coAnswers {
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = secondArg(), trackId = firstArg(), position = db.playlistDao().getNextPosition(secondArg())))
        }
        coEvery { music.removeTrackFromPlaylist(any(), any()) } coAnswers {
            db.playlistDao().removeTrackFromPlaylist(secondArg(), firstArg())
        }
        lib = RoomMirrorLibrary(db, HandoffSongMatcher(db.trackDao(), music, db), StashLikedPlaylistRepository(db.playlistDao(), db.trackDao(), music))
    }

    @After fun tearDown() = db.close()

    private suspend fun track(title: String, stashLiked: Boolean = false) = db.trackDao().insert(
        TrackEntity(title = title, artist = "Artist", stashLikedAt = if (stashLiked) 5L else null, source = MusicSource.SPOTIFY, isStreamable = true, canonicalTitle = canon(title), canonicalArtist = "artist"),
    )

    @Test fun `a mirrored like is a Stash like, and an unlike leaves a Spotify like alone`() = runTest {
        val spotifyLiked = db.playlistDao().insert(PlaylistEntity(name = "Liked Songs", source = MusicSource.SPOTIFY, sourceId = "spotify_liked", type = PlaylistType.LIKED_SONGS))
        val s = track("From Spotify")
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = spotifyLiked, trackId = s, position = 0))
        track("Here", stashLiked = true)

        assertThat(lib.likes().map { it.s.title to it.external }).containsExactly("From Spotify" to true, "Here" to false)
        lib.setLikes(on = listOf(song("New one")), off = listOf(song("From Spotify"), song("Here")), at = 99)
        assertThat(lib.likes().map { it.s.title }).containsExactly("From Spotify", "New one")
        assertThat(lib.likes().single { it.s.title == "New one" }.external).isFalse()
    }

    @Test fun `plays from another device go to History with their origin, once, and never touch play counts or mix inputs`() = runTest {
        val mine = track("Mine")
        db.listeningEventDao().recordCompletedListen(ListeningEventEntity(trackId = mine, startedAt = 1_000, completedAt = 1_200))
        lib.addPlays(listOf(PlayRec(song("Mine"), 2_000, "d_web000000000001"), PlayRec(song("Other"), 3_000, "d_web000000000001")))
        lib.addPlays(listOf(PlayRec(song("Other"), 3_000, "d_web000000000001"))) // again: skipped
        val events = db.query("SELECT origin_device, scrobbled FROM listening_events ORDER BY started_at", null).use { c ->
            buildList { while (c.moveToNext()) add((c.getString(0) ?: "own") to c.getInt(1)) }
        }
        assertThat(events).containsExactly("own" to 0, "d_web000000000001" to 1, "d_web000000000001" to 1).inOrder()
        assertThat(db.trackDao().getById(mine)!!.playCount).isEqualTo(1)
        // What Stash Mixes read sees only this phone's own play.
        assertThat(db.listeningEventDao().getPlayCountsSince(0).associate { it.trackId to it.plays }).containsExactly(mine, 1)
        assertThat(lib.ownPlays(0).map { it.s.title to it.playedAt }).containsExactly("Mine" to 1_000L)

        lib.dropPlaysFrom("d_web000000000001", 2_500)
        assertThat(db.query("SELECT COUNT(*) FROM listening_events", null).use { it.moveToFirst(); it.getInt(0) }).isEqualTo(2)
    }

    @Test fun `a mirrored playlist is written in order, renamed and replaced in place, and set not to download`() = runTest {
        track("1")
        track("2")
        val id = lib.putPlaylist(null, "m_AAAAAAAAAAAAAAAA", "Night drive", listOf(song("2"), song("1"), song("Brand new")))
        val p = db.playlistDao().getById(id)!!
        assertThat(p.sourceId).isEqualTo("custom_sync_m_AAAAAAAAAAAAAAAA")
        assertThat(p.syncEnabled).isFalse()
        assertThat(lib.playlist(id)!!.items.map { it.title }).containsExactly("2", "1", "Brand new").inOrder()
        assertThat(lib.playlists().map { it.name }).contains("Night drive")

        assertThat(lib.putPlaylist(id, "m_AAAAAAAAAAAAAAAA", "Night drive 2", listOf(song("1")))).isEqualTo(id)
        assertThat(lib.playlist(id)!!.let { it.name to it.items.map { s -> s.title } }).isEqualTo("Night drive 2" to listOf("1"))
        lib.deletePlaylist(id)
        assertThat(lib.playlist(id)).isNull()
        assertThat(db.trackDao().getById(1)).isNotNull() // the songs stay
    }

    @Test fun `a Spotify-synced playlist is read-only for the mirror`() = runTest {
        val sp = db.playlistDao().insert(PlaylistEntity(name = "Discover Weekly", source = MusicSource.SPOTIFY, sourceId = "37i9dQZEVXcJ", type = PlaylistType.CUSTOM, syncEnabled = true))
        val mine = db.playlistDao().insert(PlaylistEntity(name = "Mine", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM, syncEnabled = true))
        val byId = lib.playlists().associateBy { it.id }
        assertThat(byId.getValue(sp).ro).isTrue()
        assertThat(byId.getValue(mine).ro).isFalse()
    }

    private companion object {
        fun canon(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
