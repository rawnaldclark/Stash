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

    @Test fun `a YouTube Music link for an album synced from YouTube Music is that album, never a second copy`() = runTest {
        // YouTube Music's own saved copy (sync names it by its browse id alone).
        val synced = db.playlistDao().insert(
            PlaylistEntity(name = "Discovery", source = MusicSource.YOUTUBE, sourceId = "MPREb_disc", type = PlaylistType.CUSTOM, isActive = true),
        )
        val album = LinkPreview(
            kind = LinkKind.ALBUM, name = "Discovery", byline = "Daft Punk", coverUrl = null, service = "YouTube Music",
            songs = listOf(song(1), song(2)), sourceId = "album:youtube:MPREb_disc",
        )

        assertEquals(synced, links.existing(album))
        val added = links.add(album, download = true) { _, _ -> }

        assertEquals(LinkAdded(synced, 0), added)
        assertEquals(1, playlistCount())
        // Download is that copy's download, not a second one.
        coVerify(exactly = 1) { music.setPlaylistDownload(synced, true) }
        coVerify(exactly = 1) { music.setPlaylistDownload(any(), any()) }
    }

    @Test fun `a Spotify link for an album saved from YouTube Music is that album, never a second copy or download`() = runTest {
        val inRainbows = listOf("15 Step", "Bodysnatchers", "Nude", "Weird Fishes/Arpeggi", "All I Need", "Reckoner")
        val saved = db.playlistDao().insert(
            PlaylistEntity(name = "In Rainbows", source = MusicSource.BOTH, sourceId = "album:youtube:MPREb_rainbows", type = PlaylistType.CUSTOM, isActive = true),
        )
        inRainbows.forEachIndexed { i, title ->
            val id = db.trackDao().insert(
                TrackEntity(title = title, artist = "Radiohead", canonicalTitle = title.lowercase(), canonicalArtist = "radiohead"),
            )
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = saved, trackId = id, position = i))
        }
        fun spotify(id: String, titles: List<String>) = LinkPreview(
            kind = LinkKind.ALBUM, name = "In Rainbows", byline = "Radiohead", coverUrl = null, service = "Spotify",
            songs = titles.map { WireSong(title = it, artist = "Radiohead") }, sourceId = "album:spotify:$id",
        )

        assertEquals(saved, links.existing(spotify("7eyQ", inRainbows)))
        val added = links.add(spotify("7eyQ", inRainbows), download = true) { _, _ -> }

        assertEquals(LinkAdded(saved, 0), added)
        assertEquals(1, playlistCount())
        assertEquals(inRainbows.size, members(saved).size)
        coVerify(exactly = 1) { music.setPlaylistDownload(saved, true) }
        coVerify(exactly = 1) { music.setPlaylistDownload(any(), any()) }
        // An album that only shares the name (the bonus disc) is another album.
        assertEquals(null, links.existing(spotify("disc2", listOf("MK 1", "Down Is the New Up", "Go Slowly", "MK 2"))))
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

    // ── A Spotify album taken out, then saved again (review of #817) ─────

    private fun spotifyAlbum() = LinkPreview(
        kind = LinkKind.ALBUM, name = "Repeater", byline = "Fugazi", coverUrl = null, service = "Spotify",
        songs = listOf(song(1), song(2)), sourceId = "album:spotify:FakeAlb1xxxxxxxxxxxxxx",
    )

    @Test fun `a pasted Spotify album the listener removed comes back when its link is saved again`() = runTest {
        // Remove hides a pasted Spotify album (the Spotify sync files the album under the same key).
        val removed = db.playlistDao().insert(
            PlaylistEntity(name = "Repeater", source = MusicSource.BOTH, sourceId = "album:spotify:FakeAlb1xxxxxxxxxxxxxx", type = PlaylistType.CUSTOM),
        )
        db.playlistDao().setUserHidden(removed, true)
        assertEquals("taken out: offered to add again", null, links.existing(spotifyAlbum()))

        val added = links.add(spotifyAlbum(), download = false) { _, _ -> }

        assertEquals(removed, added.playlistId)
        val row = db.playlistDao().getById(removed)!!
        assertTrue(row.isActive)
        assertTrue(!row.userHidden)
        assertEquals(MusicSource.BOTH, row.source)
        assertEquals(2, members(removed).size)
        assertEquals(1, playlistCount())
    }

    @Test fun `a synced Spotify album taken out comes back as the listener's own save when its link is pasted`() = runTest {
        val synced = db.playlistDao().insert(
            PlaylistEntity(name = "Repeater", source = MusicSource.SPOTIFY, sourceId = "album:spotify:FakeAlb1xxxxxxxxxxxxxx", type = PlaylistType.CUSTOM),
        )
        db.playlistDao().setUserHidden(synced, true)

        val added = links.add(spotifyAlbum(), download = false) { _, _ -> }

        assertEquals(synced, added.playlistId)
        val row = db.playlistDao().getById(synced)!!
        assertTrue(row.isActive && !row.userHidden)
        assertEquals("the sync never hides it again", MusicSource.BOTH, row.source)
    }
}
