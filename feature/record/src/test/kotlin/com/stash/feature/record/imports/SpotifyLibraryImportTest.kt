package com.stash.feature.record.imports

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.stash.core.common.TrackCanonical
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.blocklist.BlocklistKey
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.library.canonicalWordsForSave
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.record.ChosenPlaylist
import com.stash.core.data.record.ExportPlaylist
import com.stash.core.data.record.ExportSong
import com.stash.core.data.record.SpotifyLibraryImport
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.share.SharedMixRepository
import com.stash.core.data.social.stash.StashLikedPlaylistRepository
import com.stash.core.data.weblibrary.WebLibraryImporter
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.share.SharedTrack
import com.stash.feature.record.importer.ExportBundle
import com.stash.feature.record.importer.MemoryArchive
import com.stash.feature.record.importer.PickedFile
import com.stash.feature.record.importer.SpotifyImporter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset

/**
 * The library half of Bring your Spotify on a real (in-memory) library: the golden fixture's two playlists and eight liked songs
 * through [SpotifyLibraryImport] and the web library's own importer, the way the app writes them. Each playlist is CUSTOM,
 * `custom_spotify_…`, source BOTH, never kept offline, and nothing is queued to download; the same export again adds nothing; a
 * playlist renamed here still gains the songs it lacks, and songs added here stay; blocked songs stay out; a Spotify sync's sweep
 * leaves the playlists be; Liked Songs keeps Spotify's order; and owner decision D3's "Already in your library" against the
 * listener's own playlists (never one a Spotify import made).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SpotifyLibraryImportTest {
    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var spotify: SpotifyLibraryImport
    private lateinit var library: DaoRecordLibrary

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        val music = mockk<MusicRepository>()
        // The app's blocklist guard over this database: its key check, and so the real canonicalWordsForSave
        val guard = mockk<BlocklistGuard>()
        coEvery { guard.isKeyBlocked(any(), any()) } coAnswers {
            db.trackBlocklistDao().findByKey(BlocklistKey.fromStoredCanonicals(secondArg(), firstArg())) != null
        }
        // Listen Together's exact persist, as MusicRepositoryImpl does it: a row found by an id, else a new stream-only row
        // (never downloaded) under the words canonicalWordsForSave picks (the strict ones when the usual key is blocked)
        coEvery { music.ensureExactTrackPersisted(any()) } coAnswers {
            val s = firstArg<SharedTrack>()
            val byId = s.youtubeId?.let { db.trackDao().findByYoutubeId(it) } ?: s.spotifyId?.let { db.trackDao().findBySpotifyUri("spotify:track:$it") }
            byId?.id ?: run {
                val (title, artist) = guard.canonicalWordsForSave(s.title, s.artist)
                db.trackDao().insert(
                    TrackEntity(
                        title = s.title, artist = s.artist, album = s.album ?: "", isrc = s.isrc, youtubeId = s.youtubeId,
                        spotifyUri = s.spotifyId?.let { "spotify:track:$it" }, isStreamable = true, source = MusicSource.BOTH,
                        canonicalTitle = title, canonicalArtist = artist,
                    ),
                )
            }
        }
        // The repository's add, as it is when the playlist isn't kept offline (it queues nothing then)
        coEvery { music.addTracksToPlaylist(any(), any()) } coAnswers {
            val ids = firstArg<List<Long>>()
            val pl = secondArg<Long>()
            check(db.playlistDao().getById(pl)?.keepOffline != true) { "a kept-offline playlist would queue downloads here" }
            ids.forEach { db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = pl, trackId = it, position = db.playlistDao().getNextPosition(pl))) }
        }
        val matcher = HandoffSongMatcher(db.trackDao(), music, db)
        val liked = StashLikedPlaylistRepository(db.playlistDao(), db.trackDao(), music)
        val importer = WebLibraryImporter(context, db, matcher, liked, music, mockk<SharedMixRepository>(relaxed = true))
        spotify = SpotifyLibraryImport(db, importer, liked)
        library = DaoRecordLibrary(spotify, db.recordHiddenDao())
    }

    @After fun tearDown() = db.close()

    private fun resource(name: String): File = File(requireNotNull(javaClass.getResource("/spotify-export/$name")) { name }.toURI())

    /** The golden fixture's export as the reader reads it (its Account zip holds the playlists and the library). */
    private fun fixture(): ExportBundle = SpotifyImporter(MemoryArchive(), tmp.newFolder(), ZoneOffset.UTC)
        .read(listOf("my_spotify_data.zip", "my_spotify_data (1).zip").map { PickedFile(it, resource(it)) }, NOW)

    private fun chosen(playlists: List<ExportPlaylist>): List<ChosenPlaylist> =
        SpotifyLibraryImport.playlistKeys(playlists).zip(playlists) { k, p -> ChosenPlaylist(k, p) }

    private suspend fun imported(): List<PlaylistEntity> =
        db.webLibraryExportDao().playlists().filter { it.sourceId.startsWith(SpotifyLibraryImport.SOURCE_PREFIX) }

    private suspend fun songsOf(id: Long): List<String> = db.webLibraryExportDao().playlistItems(id, 10_000).map { it.track.title }

    private fun count(sql: String): Int = db.query(sql, null).use { it.moveToFirst(); it.getInt(0) }

    private fun counts(): List<Int> = listOf(
        count("SELECT COUNT(*) FROM tracks"),
        count("SELECT COUNT(*) FROM playlists"),
        count("SELECT COUNT(*) FROM playlist_tracks WHERE removed_at IS NULL"),
        count("SELECT COUNT(*) FROM tracks WHERE stash_liked_at IS NOT NULL"),
    )

    @Test fun `the fixture's two playlists and eight liked songs come in, as stream-only songs, nothing to download`() = runTest {
        val b = fixture()
        library.write(chosen(b.playlists), b.likes, NOW) { _, _ -> }

        val pls = imported()
        assertThat(pls.map { it.name }).containsExactly("Road trip", "Glass Harbor era")
        for ((p, key) in pls.zip(listOf("Road trip#0", "Glass Harbor era#0"))) {
            assertThat(p.sourceId).isEqualTo(SpotifyLibraryImport.sourceIdOf(key))
            assertThat(p.sourceId).matches("custom_spotify_[0-9a-f]{24}")
            assertThat(p.type).isEqualTo(PlaylistType.CUSTOM)
            assertThat(p.source).isEqualTo(MusicSource.BOTH)
            assertThat(p.keepOffline).isFalse()
            assertThat(p.isActive).isTrue()
        }
        // Every song, in the export's order (the fixture's playlists hold no song twice)
        assertThat(songsOf(pls.single { it.name == "Road trip" }.id)).isEqualTo(b.playlists[0].items.map { it.title })
        assertThat(songsOf(pls.single { it.name == "Glass Harbor era" }.id)).isEqualTo(b.playlists[1].items.map { it.title })
        assertThat(songsOf(pls[0].id).size + songsOf(pls[1].id).size).isEqualTo(17)
        // The liked songs, newest first as Spotify lists them
        assertThat(db.webLibraryExportDao().likes().map { it.track.title }).isEqualTo(b.likes.map { it.title })
        // Nothing downloaded or queued to download
        assertThat(count("SELECT COUNT(*) FROM download_queue")).isEqualTo(0)
        assertThat(count("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1")).isEqualTo(0)
    }

    @Test fun `Liked Songs keeps Spotify's order, its place and its time`() = runTest {
        val b = fixture()
        library.write(emptyList(), b.likes, NOW) { _, _ -> }
        val likedId = db.playlistDao().findBySourceId("stash_liked_songs")!!.id
        val refs = db.playlistDao().getCrossRefsForPlaylist(likedId).sortedBy { it.position }
        val titles = refs.map { r -> db.trackDao().getById(r.trackId)!!.title }
        assertThat(titles).isEqualTo(b.likes.map { it.title })
        assertThat(refs.map { it.addedAt!!.toEpochMilli() }).isEqualTo(b.likes.indices.map { NOW - it })
    }

    @Test fun `the same export again adds nothing, and so does a commit taken up again`() = runTest {
        val b = fixture()
        library.write(chosen(b.playlists), b.likes, NOW) { _, _ -> }
        val before = counts()
        val progress = mutableListOf<Pair<Int, Int>>()

        library.write(chosen(b.playlists), b.likes, NOW + 60_000) { d, t -> progress += d to t }

        assertThat(counts()).isEqualTo(before)
        assertThat(imported()).hasSize(2)
        // Nothing was left to match: the playlists lacked no song, every like was already here
        assertThat(progress.last()).isEqualTo(0 to 0)
    }

    @Test fun `a playlist renamed here still gains the songs it lacks, and the songs added here stay`() = runTest {
        val b = fixture()
        val road = b.playlists[0]
        // An older export, its last song not there yet
        library.write(chosen(listOf(road.copy(items = road.items.dropLast(1)))), emptyList(), NOW) { _, _ -> }
        val pl = imported().single()
        db.playlistDao().updateName(pl.id, "Summer drives")
        val mine = db.trackDao().insert(
            TrackEntity(title = "Mine", artist = "Me", canonicalTitle = TrackCanonical.title("Mine"), canonicalArtist = TrackCanonical.artist("Me"), isStreamable = true),
        )
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = pl.id, trackId = mine, position = db.playlistDao().getNextPosition(pl.id)))

        library.write(chosen(listOf(road)), emptyList(), NOW + 60_000) { _, _ -> }

        val after = imported().single()
        assertThat(after.id).isEqualTo(pl.id)
        assertThat(after.name).isEqualTo("Summer drives")
        assertThat(songsOf(pl.id)).isEqualTo(road.items.dropLast(1).map { it.title } + "Mine" + road.items.last().title)
    }

    @Test fun `songs this phone blocked are left out`() = runTest {
        val b = fixture()
        val blocked = b.playlists[0].items[2]
        val liked = b.likes[3]
        for (s in listOf(blocked, liked)) {
            db.trackBlocklistDao().insert(
                TrackBlocklistEntity(
                    canonicalKey = "${TrackCanonical.artist(s.artist)}|${TrackCanonical.title(s.title)}",
                    artist = s.artist, title = s.title, blockedAt = NOW, blockedFrom = "OTHER",
                ),
            )
        }

        library.write(chosen(b.playlists), b.likes, NOW) { _, _ -> }

        // Never written at all (the library's reads hide a blocked song anyway: count what is in the tables)
        val road = imported().single { it.name == "Road trip" }.id
        val inRoad = db.playlistDao().getCrossRefsForPlaylist(road).filter { it.removedAt == null }.sortedBy { it.position }.map { db.trackDao().getById(it.trackId)!!.title }
        assertThat(inRoad).isEqualTo(b.playlists[0].items.filter { it != blocked }.map { it.title })
        assertThat(count("SELECT COUNT(*) FROM tracks WHERE stash_liked_at IS NOT NULL")).isEqualTo(b.likes.size - 1)
        assertThat(db.webLibraryExportDao().likes().map { it.track.title }).isEqualTo(b.likes.filter { it != liked }.map { it.title })
    }

    @Test fun `a blocked song whose strict words differ from its key stays out, and leaves no row behind`() = runTest {
        // "Don't Stop Me Now": its usual key is `queen|don t stop me now`, its strict words `don't stop me now`
        val song = ExportSong("Don't Stop Me Now", "Queen", sid = "7hQJA50XrCWABAu5v6QZ4i")
        val other = ExportSong("Bohemian Rhapsody", "Queen")
        db.trackBlocklistDao().insert(
            TrackBlocklistEntity(
                canonicalKey = BlocklistKey.fromStoredCanonicals(TrackCanonical.artist("Queen"), TrackCanonical.title("Don't Stop Me Now")),
                artist = "Queen", title = "Don't Stop Me Now", blockedAt = NOW, blockedFrom = "OTHER",
            ),
        )
        val tracks = count("SELECT COUNT(*) FROM tracks")

        library.write(chosen(listOf(ExportPlaylist("Queen", items = listOf(song, other)))), listOf(song, other), NOW) { _, _ -> }

        val pl = imported().single()
        assertThat(db.playlistDao().getCrossRefsForPlaylist(pl.id).map { db.trackDao().getById(it.trackId)!!.title }).containsExactly("Bohemian Rhapsody")
        assertThat(count("SELECT COUNT(*) FROM tracks WHERE stash_liked_at IS NOT NULL")).isEqualTo(1)
        // Only the song brought in has a row: none was made for the blocked one
        assertThat(count("SELECT COUNT(*) FROM tracks")).isEqualTo(tracks + 1)
        assertThat(count("SELECT COUNT(*) FROM tracks WHERE title = 'Don''t Stop Me Now'")).isEqualTo(0)
    }

    @Test fun `a blocked Spotify id stays out too`() = runTest {
        val song = ExportSong("Renamed Upstream", "Band", sid = "0123456789abcdefghijAB")
        db.trackBlocklistDao().insert(
            TrackBlocklistEntity(canonicalKey = "someone|something else", artist = "Someone", title = "Something else", spotifyUri = "spotify:track:0123456789abcdefghijAB", blockedAt = NOW, blockedFrom = "OTHER"),
        )
        library.write(chosen(listOf(ExportPlaylist("Ids", items = listOf(song)))), emptyList(), NOW) { _, _ -> }
        assertThat(db.playlistDao().getCrossRefsForPlaylist(imported().single().id)).isEmpty()
        assertThat(count("SELECT COUNT(*) FROM tracks WHERE title = 'Renamed Upstream'")).isEqualTo(0)
    }

    @Test fun `a local file whose title is only brackets is brought in once, import after import`() = runTest {
        val intro = ExportSong("(Intro)", "Band", album = "Demo")
        val outro = ExportSong("[Outro]", "Band")
        val songs = listOf(intro, ExportSong("Real Song", "Band"), outro)
        repeat(3) { library.write(chosen(listOf(ExportPlaylist("Locals", items = songs))), listOf(intro), NOW) { _, _ -> } }

        val pl = imported().single()
        assertThat(db.playlistDao().getCrossRefsForPlaylist(pl.id).filter { it.removedAt == null }.sortedBy { it.position }.map { db.trackDao().getById(it.trackId)!!.title })
            .containsExactly("(Intro)", "Real Song", "[Outro]").inOrder()
        assertThat(count("SELECT COUNT(*) FROM tracks WHERE title IN ('(Intro)', '[Outro]', 'Real Song')")).isEqualTo(3)
        assertThat(count("SELECT COUNT(*) FROM tracks WHERE stash_liked_at IS NOT NULL")).isEqualTo(1)
    }

    @Test fun `a Spotify sync's sweep of its playlists leaves the imported ones be`() = runTest {
        val b = fixture()
        library.write(chosen(b.playlists), emptyList(), NOW) { _, _ -> }

        db.playlistDao().deactivateMissingSpotifyCustomPlaylists(emptyList(), hasCurrentIds = false)

        assertThat(imported().map { it.isActive }).containsExactly(true, true)
    }

    @Test fun `already in your library - the listener's own playlist of the name with half its songs, never one an import made`() = runTest {
        val b = fixture()
        val road = b.playlists[0]
        // The listener's own "road trip" holding half of Road trip's 9 songs: 4 is under half, 5 is enough
        val own = db.playlistDao().insert(PlaylistEntity(name = "road trip", source = MusicSource.BOTH, sourceId = "custom_1", type = PlaylistType.CUSTOM, syncEnabled = true))
        for ((i, s) in road.items.take(4).withIndex()) {
            val t = db.trackDao().insert(TrackEntity(title = s.title, artist = s.artist, canonicalTitle = TrackCanonical.title(s.title), canonicalArtist = TrackCanonical.artist(s.artist), isStreamable = true))
            db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = own, trackId = t, position = i))
        }
        assertThat(library.alreadyHere(b.playlists)).isEmpty()
        val fifth = road.items[4]
        val t = db.trackDao().insert(TrackEntity(title = fifth.title, artist = fifth.artist, canonicalTitle = TrackCanonical.title(fifth.title), canonicalArtist = TrackCanonical.artist(fifth.artist), isStreamable = true))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = own, trackId = t, position = 4))
        assertThat(library.alreadyHere(b.playlists)).containsExactly(0)

        // Brought in anyway: its own playlist, beside the listener's
        library.write(chosen(b.playlists), emptyList(), NOW) { _, _ -> }
        assertThat(imported().map { it.name }).containsExactly("Road trip", "Glass Harbor era")
        // Next time it is that playlist, brought in once and updated now: ticked, though the listener's own still holds it
        assertThat(library.alreadyHere(b.playlists)).isEmpty()
        // The listener's gone too: still ticked
        db.playlistDao().setActiveById(own, false)
        assertThat(library.alreadyHere(b.playlists)).isEmpty()
        // The import's own deleted since, the listener's back: compared again
        db.playlistDao().setActiveById(own, true)
        db.playlistDao().setActiveById(imported().single { it.name == "Road trip" }.id, false)
        assertThat(library.alreadyHere(b.playlists)).containsExactly(0)
    }

    @Test fun `the liked songs would download when Liked Songs is kept on this phone`() = runTest {
        assertThat(library.likesDownload()).isFalse()
        library.write(emptyList(), fixture().likes.take(1), NOW) { _, _ -> }
        assertThat(library.likesDownload()).isFalse()
        db.playlistDao().setKeepOffline(db.playlistDao().findBySourceId("stash_liked_songs")!!.id, true)
        assertThat(library.likesDownload()).isTrue()
    }

    @Test fun `the songs hidden on Spotify are kept by song key, a newer import adds to them, and Remove empties them`() = runTest {
        val b = fixture()
        library.addHidden(b.hidden)
        val keys = db.recordHiddenDao().keys().sorted()
        assertThat(keys).isEqualTo(RecordLibrary.hiddenRows(b.hidden).map { it.key }.sorted())
        assertThat(keys).isNotEmpty()

        library.addHidden(b.hidden + com.stash.core.data.record.ExportSong("Never Again (Remastered)", "Band"))
        assertThat(db.recordHiddenDao().count()).isEqualTo(keys.size + 1)
        assertThat(db.recordHiddenDao().keys()).contains("never again|band")

        library.clearHidden()
        assertThat(db.recordHiddenDao().count()).isEqualTo(0)
    }

    private companion object {
        /** 2026-10-23T12:00:00Z, the fixture's now. */
        const val NOW = 1_792_756_800_000L
    }
}
