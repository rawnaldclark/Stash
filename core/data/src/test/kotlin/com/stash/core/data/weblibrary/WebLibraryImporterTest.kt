package com.stash.core.data.weblibrary

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.share.SharedTrack
import io.mockk.coEvery
import io.mockk.mockk
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
import java.time.Instant

/**
 * The app importer (link-sync spec §2.3, §9) against a real in-memory library: adds and never removes, matches songs by
 * identity, and an app export imported back is a no-op.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WebLibraryImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var importer: WebLibraryImporter
    private lateinit var exporter: WebLibraryExporter
    private val followed = mutableListOf<String>()
    private var shareReachable = true

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        val music = mockk<MusicRepository>()
        // Listen Together's exact persist: a stream-only row for a song the library doesn't have.
        coEvery { music.ensureExactTrackPersisted(any()) } coAnswers {
            val s = firstArg<SharedTrack>()
            db.trackDao().insert(
                TrackEntity(
                    title = s.title, artist = s.artist, isrc = s.isrc, youtubeId = s.youtubeId,
                    spotifyUri = s.spotifyId?.let { "spotify:track:$it" }, isStreamable = true,
                    canonicalTitle = canon(s.title), canonicalArtist = canon(s.artist),
                ),
            )
        }
        coEvery { music.addTracksToPlaylist(any(), any()) } coAnswers {
            val ids = firstArg<List<Long>>()
            val pl = secondArg<Long>()
            ids.forEach { db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = pl, trackId = it, position = db.playlistDao().getNextPosition(pl))) }
        }
        coEvery { music.addTrackToPlaylist(any(), any()) } coAnswers {
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = secondArg(), trackId = firstArg(), position = db.playlistDao().getNextPosition(secondArg())))
        }
        val matcher = HandoffSongMatcher(db.trackDao(), music, db)
        val liked = StashLikedPlaylistRepository(db.playlistDao(), db.trackDao(), music)
        importer = WebLibraryImporter(context, db, matcher, liked, music) { id ->
            if (shareReachable) followed += id
            shareReachable
        }
        exporter = WebLibraryExporter(context, db)
    }

    @After fun tearDown() = db.close()

    private fun track(title: String, artist: String, isrc: String? = null, youtubeId: String? = null, likedAt: Long? = null) = TrackEntity(
        title = title, artist = artist, isrc = isrc, youtubeId = youtubeId, stashLikedAt = likedAt, source = MusicSource.SPOTIFY,
        isStreamable = true, canonicalTitle = canon(title), canonicalArtist = canon(artist), dateAdded = Instant.ofEpochMilli(T),
    )

    private suspend fun fill(): Map<String, Long> {
        val t = db.trackDao()
        val halo = t.insert(track("Halo", "Beyoncé", isrc = "USSM10804554", likedAt = T + 1))
        val ivy = t.insert(track("Ivy", "Frank Ocean", youtubeId = "AE005nZeF-A"))
        val pink = t.insert(track("Pink + White", "Frank Ocean", isrc = "USUM71607009"))
        val drive = db.playlistDao().insert(
            PlaylistEntity(name = "Night drive", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM, syncEnabled = true),
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = drive, trackId = ivy, position = 0))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = drive, trackId = pink, position = 1))
        val mix = db.playlistDao().insert(
            PlaylistEntity(name = "Robin's mix", source = MusicSource.BOTH, sourceId = "share:Fw12ab34", type = PlaylistType.CUSTOM, syncEnabled = false),
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = mix, trackId = pink, position = 0))
        db.sharedMixDao().insert(SharedMixEntity(playlistId = mix, shareId = "Fw12ab34", role = SharedMixEntity.ROLE_FOLLOWER, name = "Robin's mix", version = 3))
        db.listeningEventDao().insert(ListeningEventEntity(trackId = ivy, startedAt = T + 100))
        db.listeningEventDao().insert(ListeningEventEntity(trackId = halo, startedAt = T + 200))
        return mapOf("halo" to halo, "ivy" to ivy, "pink" to pink, "drive" to drive, "mix" to mix)
    }

    private suspend fun counts(): List<Int> = listOf(
        db.query("SELECT COUNT(*) FROM tracks", null).use { it.moveToFirst(); it.getInt(0) },
        db.webLibraryExportDao().likes().size,
        db.webLibraryExportDao().playlists().size,
        db.webLibraryExportDao().recentPlays(10_000).size,
        db.webLibraryExportDao().playlists().sumOf { db.webLibraryExportDao().playlistItems(it.id, 10_000).size },
    )

    @Test fun `an app export imported back changes nothing`() = runTest {
        fill()
        val before = counts()
        val file = exporter.collect(T + 5_000, "test")
        val r = importer.import(WebLibraryReader.read(exporter.text(file), T), ImportSelection.ALL, WebLibraryImporter.ORIGIN_FILE)
        assertEquals(WebLibraryImportResult(likesSkipped = 1, playlistsSkipped = 2, playsSkipped = 2), r)
        assertEquals(before, counts())
        assertTrue(followed.isEmpty())
    }

    private fun webFile(vararg playlists: String, likes: String = "", history: String = "") =
        """{"kind":"stash-web-library","v":1,"exportedAt":"2026-10-10T00:00:00.000Z","likes":[$likes],"playlists":[${playlists.joinToString(",")}],"history":[$history],
            "settings":{"quality":"high"}}"""

    @Test fun `a web library adds likes, playlists and plays by identity, and importing it again adds nothing`() = runTest {
        val ids = fill()
        val text = webFile(
            """{"id":"pl_web1","name":"Gym","items":[{"title":"Ivy","artist":"Frank Ocean"},{"title":"Brand New","artist":"Web Only","refs":{"youtube":"bbbbbbbbbbb"}},{"title":"pink + white","artist":"FRANK OCEAN"}]}""",
            likes = """{"item":{"title":"Pink + White","artist":"Frank Ocean","isrc":"USUM71607009"},"likedAt":${T + 50}},{"item":{"title":"halo","artist":"beyonce"},"likedAt":${T + 60}}""",
            history = """{"item":{"title":"Brand New","artist":"Web Only","refs":{"youtube":"bbbbbbbbbbb"}},"playedAt":${T + 300}},{"item":{"title":"Ivy","artist":"Frank Ocean"},"playedAt":${T + 100}}""",
        )
        val r = importer.import(WebLibraryReader.read(text, T), ImportSelection.ALL, "d_web0000000000001")
        assertEquals(WebLibraryImportResult(likesAdded = 1, likesSkipped = 1, playlistsAdded = 1, songsAdded = 3, playsAdded = 1, playsSkipped = 1), r)

        // Pink + White matched by its ISRC: liked here now, at the file's time, as a Stash like.
        assertEquals(T + 50, db.trackDao().getById(ids.getValue("pink"))!!.stashLikedAt)
        // The playlist, in the file's order, songs matched by identity; the web-only song is a new stream-only row.
        val gym = db.playlistDao().findBySourceId("custom_web_pl_web1")!!
        assertEquals("Gym", gym.name)
        val items = db.webLibraryExportDao().playlistItems(gym.id, 100).map { it.track.title }
        assertEquals(listOf("Ivy", "Brand New", "Pink + White"), items)
        assertEquals(3, db.playlistDao().getById(gym.id)!!.trackCount)
        // The play from the web is History only: marked, already "scrobbled", no play count.
        val brandNew = db.trackDao().findByYoutubeId("bbbbbbbbbbb")!!
        val event = db.query("SELECT origin_device, scrobbled, yt_scrobbled, completed_at FROM listening_events WHERE track_id = ${brandNew.id}", null).use { c ->
            c.moveToFirst()
            listOf(c.getString(0), c.getInt(1).toString(), c.getInt(2).toString(), if (c.isNull(3)) null else c.getString(3))
        }
        assertEquals(listOf("d_web0000000000001", "1", "1", null), event)
        assertEquals(0, db.trackDao().getById(brandNew.id)!!.playCount)
        assertTrue(db.listeningEventDao().getPlayCountsSince(0).none { it.trackId == brandNew.id })

        val before = counts()
        val again = importer.import(WebLibraryReader.read(text, T), ImportSelection.ALL, "d_web0000000000001")
        assertEquals(WebLibraryImportResult(likesSkipped = 2, playlistsSkipped = 1, playsSkipped = 2), again)
        assertEquals(before, counts())
    }

    @Test fun `a playlist that came from the web goes back under the web's id, so the web merges it instead of copying it`() = runTest {
        fill()
        val file = webFile("""{"id":"webmix1","name":"Web picks","items":[{"title":"Halo","artist":"Beyoncé"}]}""")
        importer.import(WebLibraryReader.read(file, T), ImportSelection.ALL, "d_web0000000000001")
        val exported = exporter.collect(T, null)
        assertEquals(listOf("app-${db.playlistDao().findBySourceId("custom_1")!!.id}", "webmix1", "app-${db.playlistDao().findBySourceId("share:Fw12ab34")!!.id}").sorted(), exported.playlists.map { it.id }.sorted())
        // And back on this phone, the same file is still a no-op.
        val r = importer.import(WebLibraryReader.read(exporter.text(exported), T), ImportSelection.ALL, WebLibraryImporter.ORIGIN_FILE)
        assertEquals(0, r.playlistsAdded + r.playlistsUpdated + r.songsAdded)
    }

    @Test fun `a playlist already here gains only what it lacks, at the end`() = runTest {
        val ids = fill()
        val file = webFile(
            """{"id":"app-${ids.getValue("drive")}","name":"Night drive","items":[{"title":"Ivy","artist":"Frank Ocean"},{"title":"Halo","artist":"Beyoncé"},{"title":"Pink + White","artist":"Frank Ocean"}]}""",
        )
        val r = importer.import(WebLibraryReader.read(file, T), ImportSelection.ALL, WebLibraryImporter.ORIGIN_FILE)
        assertEquals(1, r.playlistsUpdated)
        assertEquals(1, r.songsAdded)
        assertEquals(listOf("Ivy", "Pink + White", "Halo"), db.webLibraryExportDao().playlistItems(ids.getValue("drive"), 100).map { it.track.title })
    }

    @Test fun `another phone's app-N with another name is a new playlist, not merged into this phone's N`() = runTest {
        val ids = fill()
        val file = webFile("""{"id":"app-${ids.getValue("drive")}","name":"Workout","items":[{"title":"Halo","artist":"Beyoncé"}]}""")
        importer.import(WebLibraryReader.read(file, T), ImportSelection.ALL, WebLibraryImporter.ORIGIN_FILE)
        assertEquals(2, db.webLibraryExportDao().playlistItems(ids.getValue("drive"), 100).size)
        assertNotNull(db.playlistDao().findBySourceId("custom_web_app-${ids.getValue("drive")}"))
    }

    @Test fun `this phone's playlist renamed since the export is still that playlist, not a duplicate`() = runTest {
        val ids = fill()
        val drive = ids.getValue("drive")
        db.playlistDao().updateName(drive, "Night drive 2026")
        val file = webFile(
            """{"id":"app-$drive","name":"Night drive","items":[{"title":"Ivy","artist":"Frank Ocean"},{"title":"Pink + White","artist":"Frank Ocean"},{"title":"Halo","artist":"Beyoncé"}]}""",
        )
        importer.import(WebLibraryReader.read(file, T), ImportSelection.ALL, WebLibraryImporter.ORIGIN_FILE)
        assertEquals(3, db.webLibraryExportDao().playlistItems(drive, 100).size)
        assertEquals(null, db.playlistDao().findBySourceId("custom_web_app-$drive"))
    }

    @Test fun `a shared mix is followed here, and kept as a copy when the share service is out of reach`() = runTest {
        fill()
        val mix = """{"id":"w9","name":"Sam's mix","items":[{"title":"Halo","artist":"Beyoncé"}],"follow":{"id":"Zz99Yy88","version":2}}"""
        val r = importer.import(WebLibraryReader.read(webFile(mix), T), ImportSelection.ALL, WebLibraryImporter.ORIGIN_FILE)
        assertEquals(listOf("Zz99Yy88"), followed)
        assertEquals(1, r.playlistsAdded)
        assertNull(db.playlistDao().findBySourceId("custom_web_w9"))

        shareReachable = false
        val mix2 = """{"id":"w10","name":"Kim's mix","items":[{"title":"Halo","artist":"Beyoncé"}],"follow":{"id":"Kk11Mm22","version":1}}"""
        importer.import(WebLibraryReader.read(webFile(mix2), T), ImportSelection.ALL, WebLibraryImporter.ORIGIN_FILE)
        assertEquals("Kim's mix", db.playlistDao().findBySourceId("custom_web_w10")!!.name)
    }

    @Test fun `only the ticked parts are taken, and a blocked song never comes in`() = runTest {
        fill()
        db.trackBlocklistDao().insert(TrackBlocklistEntity(canonicalKey = "frank ocean|ivy", artist = "Frank Ocean", title = "Ivy", blockedAt = T, blockedFrom = "test"))
        val file = webFile(
            """{"id":"a","name":"A","items":[{"title":"Ivy","artist":"Frank Ocean"},{"title":"New","artist":"X"}]}""",
            """{"id":"b","name":"B","items":[{"title":"Other","artist":"X"}]}""",
            likes = """{"item":{"title":"Liked","artist":"X"},"likedAt":${T + 1}}""",
            history = """{"item":{"title":"Played","artist":"X"},"playedAt":${T + 9}}""",
        )
        val r = importer.import(WebLibraryReader.read(file, T), ImportSelection(likes = false, plays = false, playlistIds = setOf("a")), WebLibraryImporter.ORIGIN_FILE)
        assertEquals(WebLibraryImportResult(playlistsAdded = 1, songsAdded = 1), r)
        assertEquals(listOf("New"), db.webLibraryExportDao().playlistItems(db.playlistDao().findBySourceId("custom_web_a")!!.id, 10).map { it.track.title })
        assertNull(db.playlistDao().findBySourceId("custom_web_b"))
        assertTrue(db.webLibraryExportDao().likes().none { it.track.title == "Liked" })
        assertTrue(db.webLibraryExportDao().recentPlays(100).none { it.track.title == "Played" })
    }

    private companion object {
        const val T = 1_759_838_400_000L

        /** The library's canonical_title / canonical_artist (MusicRepositoryImpl's canonicalizeIdentity). */
        fun canon(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
