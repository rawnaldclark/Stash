package com.stash.core.data.link

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.data.weblibrary.WebLibraryImporter
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LinkImporter.add] against a real in-memory library and the real [WebLibraryImporter] (review on #740): pasting again adds
 * only what's missing, an album saved from its page is the same playlist, Download follows the switch, and a stopped add
 * finishes without doubling anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LinkImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var music: MusicRepository
    private lateinit var links: LinkImporter

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        music = mockk(relaxUnitFun = true)
        coEvery { music.ensureExactTrackPersisted(any()) } coAnswers {
            val s = firstArg<SharedTrack>()
            db.trackDao().insert(
                TrackEntity(
                    title = s.title, artist = s.artist, youtubeId = s.youtubeId, isStreamable = true,
                    canonicalTitle = s.title.lowercase(), canonicalArtist = s.artist.lowercase(),
                ),
            )
        }
        coEvery { music.addTracksToPlaylist(any(), any()) } coAnswers {
            val pl = secondArg<Long>()
            firstArg<List<Long>>().forEach {
                db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = pl, trackId = it, position = db.playlistDao().getNextPosition(pl)))
            }
        }
        val matcher = HandoffSongMatcher(db.trackDao(), music, db)
        val liked = StashLikedPlaylistRepository(db.playlistDao(), db.trackDao(), music)
        val importer = WebLibraryImporter(context, db, matcher, liked, music) { false }
        links = LinkImporter(mockk(), mockk(), mockk(), importer, db.playlistDao(), music)
    }

    @After fun tearDown() = db.close()

    private fun song(n: Int) = WireSong(
        title = "Song $n", artist = "Artist $n", refs = mapOf(WebLibraryFile.YOUTUBE_SOURCE to "vid%08d".format(n)),
    )

    private fun playlist(vararg n: Int) = LinkPreview(
        kind = LinkKind.PLAYLIST, name = "Road trip", byline = null, coverUrl = "https://i.ytimg.com/c.jpg", service = "YouTube Music",
        songs = n.map(::song), sourceId = "${LinkImporter.PLAYLIST_PREFIX}yt_PLroad",
    )

    private suspend fun members(playlistId: Long): List<Long> =
        db.playlistDao().getCrossRefsForPlaylist(playlistId).filter { it.removedAt == null }.map { it.trackId }

    private suspend fun playlistCount(): Int = db.query("SELECT COUNT(*) FROM playlists", null).use { it.moveToFirst(); it.getInt(0) }

    @Test fun `pasting an overlapping playlist again adds only the missing songs to the same playlist`() = runTest {
        val first = links.add(playlist(1, 2, 3), download = false) { _, _ -> }
        val again = links.add(playlist(2, 3, 4, 5), download = false) { _, _ -> }

        assertEquals(first.playlistId, again.playlistId)
        assertEquals(3, first.songsAdded)
        assertEquals(2, again.songsAdded)
        assertEquals(5, members(first.playlistId).size)
        assertEquals(5, members(first.playlistId).toSet().size)
        assertEquals(1, playlistCount())
        // The playlist wears the link's cover.
        assertEquals("https://i.ytimg.com/c.jpg", db.playlistDao().getById(first.playlistId)!!.artUrl)
    }

    @Test fun `an album already saved from its album page is the same playlist`() = runTest {
        val saved = db.playlistDao().insert(
            PlaylistEntity(name = "Discovery", source = MusicSource.BOTH, sourceId = "album:youtube:MPREb_disc", type = PlaylistType.CUSTOM, isActive = true, syncEnabled = true),
        )
        val album = LinkPreview(
            kind = LinkKind.ALBUM, name = "Discovery", byline = "Daft Punk", coverUrl = null, service = "YouTube Music",
            songs = listOf(song(1), song(2)), sourceId = "album:youtube:MPREb_disc",
        )

        val added = links.add(album, download = false) { _, _ -> }

        assertEquals(saved, added.playlistId)
        assertEquals(1, playlistCount())
        assertEquals(2, members(saved).size)
    }

    @Test fun `download follows the switch`() = runTest {
        val off = links.add(playlist(1, 2), download = false) { _, _ -> }
        coVerify(exactly = 0) { music.setPlaylistDownload(any(), any()) }

        val on = links.add(playlist(1, 2, 3), download = true) { _, _ -> }
        assertEquals(off.playlistId, on.playlistId)
        coVerify(exactly = 1) { music.setPlaylistDownload(on.playlistId, true) }
    }

    @Test fun `a stopped add finishes on a retry without doubling anything`() = runTest {
        val songs = (1..40).toList().toIntArray()
        val stopped = runCatching {
            links.add(playlist(*songs), download = false) { done, _ -> if (done > 0) throw CancellationException("left the screen") }
        }
        assertTrue(stopped.exceptionOrNull() is CancellationException)

        val retried = links.add(playlist(*songs), download = false) { _, _ -> }

        assertEquals(40, members(retried.playlistId).size)
        assertEquals(40, members(retried.playlistId).toSet().size)
        assertEquals(1, playlistCount())
    }
}
