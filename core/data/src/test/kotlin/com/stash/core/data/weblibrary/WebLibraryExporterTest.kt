package com.stash.core.data.weblibrary

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.SharedMixEntity
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset

/**
 * "Export for Stash on the web": what goes into the web player's library file and what never does, against a real
 * in-memory Room database. The golden test pins the exact bytes for a fixed library; the same file is the web
 * player's import fixture (stash-player `src/lib/library/fixtures/app-export-v1.json`), so the two sides can't drift.
 *
 * To rewrite the golden file after a deliberate change (a v1 change is not allowed: see docs/library-file-v1.md in
 * the web player), run this test with the environment variable UPDATE_WEB_LIBRARY_GOLDEN=1 and copy the file to the
 * web player's fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WebLibraryExporterTest {
    @Test fun `SoundCloud only recordings are not exported as web title matches`() {
        assertNull(WebLibraryFile.song(com.stash.core.model.Track(title = "Remix", artist = "Artist",
            source = MusicSource.SOUNDCLOUD, soundCloudId = "123"), null))
    }


    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var exporter: WebLibraryExporter

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        exporter = WebLibraryExporter(context, db)
    }

    @After fun tearDown() = db.close()

    private fun at(offset: Long): Instant = Instant.ofEpochMilli(T + offset)

    private fun track(
        title: String,
        artist: String,
        added: Long,
        album: String = "",
        durationMs: Long = 0,
        youtubeId: String? = null,
        spotifyUri: String? = null,
        isrc: String? = null,
        art: String? = null,
        stashLikedAt: Long? = null,
        source: MusicSource = MusicSource.SPOTIFY,
        filePath: String? = null,
        downloaded: Boolean = false,
        streamableCheckedAt: Long? = null,
    ) = TrackEntity(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        youtubeId = youtubeId,
        spotifyUri = spotifyUri,
        isrc = isrc,
        albumArtUrl = art,
        albumArtPath = filePath?.let { "$it.jpg" },
        stashLikedAt = stashLikedAt,
        source = source,
        filePath = filePath,
        isDownloaded = downloaded,
        isStreamableCheckedAt = streamableCheckedAt,
        dateAdded = at(added),
        canonicalTitle = title.lowercase(),
        canonicalArtist = artist.lowercase(),
    )

    private fun playlist(name: String, type: PlaylistType, sourceId: String, added: Long, syncEnabled: Boolean = true) =
        PlaylistEntity(
            name = name,
            source = MusicSource.SPOTIFY,
            sourceId = sourceId,
            type = type,
            syncEnabled = syncEnabled,
            dateAdded = at(added),
        )

    private suspend fun member(playlistId: Long, trackId: Long, position: Int, added: Long, removed: Boolean = false) =
        db.playlistDao().insertCrossRef(
            PlaylistTrackCrossRef(
                playlistId = playlistId,
                trackId = trackId,
                position = position,
                addedAt = at(added),
                removedAt = if (removed) at(added + 1) else null,
            ),
        )

    /** A small library with one of everything the export has to take or leave. */
    private suspend fun fill() {
        val tracks = db.trackDao()
        val pinkWhite = tracks.insert(
            track(
                "Pink + White", "Frank Ocean", added = 10, album = "Blonde", durationMs = 184_000,
                youtubeId = "uxpDa-c-4Mc", spotifyUri = "spotify:track:3xKsf9qdS1CyvXSMEid6g8", isrc = "usum71607009",
                art = "https://i.scdn.co/image/ab67616d0000b273c5649add07ed3720be9d5526", stashLikedAt = T + 100,
                filePath = "/storage/emulated/0/Music/Stash/pink-white.opus", downloaded = true,
            ),
        )
        // Liked on Spotify only (in the Liked Songs playlist); its cover is on a host the web would never load.
        val nights = tracks.insert(
            track("Nights", "Frank Ocean", added = 20, youtubeId = "r4l9bFqgMaQ", art = "https://tracker.example/nights.jpg"),
        )
        val halo = tracks.insert(
            track("Halo", "Beyoncé", added = 30, durationMs = 261_000, art = "https://lh3.googleusercontent.com/halo=w544", stashLikedAt = T + 200),
        )
        val blocked = tracks.insert(track("Blocked Song", "Someone", added = 40, youtubeId = "aaaaaaaaaaa", stashLikedAt = T + 300))
        // A local file with no artist tag: the web can't find or show it.
        tracks.insert(
            track("Voice memo", "", added = 50, stashLikedAt = T + 400, source = MusicSource.LOCAL, filePath = "/storage/emulated/0/Recordings/memo.m4a", downloaded = true),
        )
        val ivy = tracks.insert(track("Ivy", "Frank Ocean", added = 60, youtubeId = "AE005nZeF-A", durationMs = 249_000))
        val removed = tracks.insert(track("Removed One", "Band", added = 70))
        val daily = tracks.insert(track("Daily Song", "Mixer", added = 80))
        val shared = tracks.insert(track("Shared Tune", "Robin Band", added = 90, spotifyUri = "spotify:track:not-a-real-id"))
        val hidden = tracks.insert(track("Hidden", "Nobody", added = 95, streamableCheckedAt = T))

        db.trackBlocklistDao().insert(
            TrackBlocklistEntity(canonicalKey = "someone|blocked song", artist = "Someone", title = "Blocked Song", blockedAt = T, blockedFrom = "test"),
        )

        val playlists = db.playlistDao()
        val liked = playlists.insert(playlist("Liked Songs", PlaylistType.LIKED_SONGS, "spotify:liked", added = 1))
        member(liked, nights, 0, added = 50)
        member(liked, blocked, 1, added = 51)

        val drive = playlists.insert(playlist("  Night drive ", PlaylistType.CUSTOM, "custom_1", added = 2, syncEnabled = false))
        member(drive, ivy, 0, added = 500)
        member(drive, pinkWhite, 1, added = 600)
        member(drive, removed, 2, added = 700, removed = true)
        member(drive, blocked, 3, added = 800)

        val mix = playlists.insert(playlist("Daily Mix 1", PlaylistType.DAILY_MIX, "daily1", added = 3))
        member(mix, daily, 0, added = 900)
        member(mix, ivy, 1, added = 900)
        val stashMix = playlists.insert(playlist("Daily Discover", PlaylistType.STASH_MIX, "stash_mix_1", added = 4))
        member(stashMix, daily, 0, added = 900)
        val downloads = playlists.insert(playlist("Downloads", PlaylistType.DOWNLOADS_MIX, "downloads", added = 5))
        member(downloads, daily, 0, added = 900)

        val followed = playlists.insert(playlist("Robin’s mix", PlaylistType.CUSTOM, "share:Fw12ab34", added = 6, syncEnabled = false))
        member(followed, shared, 0, added = 950)
        db.sharedMixDao().insert(
            SharedMixEntity(playlistId = followed, shareId = "Fw12ab34", role = SharedMixEntity.ROLE_FOLLOWER, name = "Robin’s mix", version = 3, sharedBy = "Robin"),
        )

        // A Spotify playlist the user never turned on, with nothing playable: Library doesn't show it, so it stays.
        val off = playlists.insert(playlist("Not synced", PlaylistType.CUSTOM, "spotify:pl:off", added = 7, syncEnabled = false))
        member(off, hidden, 0, added = 960)

        val events = db.listeningEventDao()
        events.insert(ListeningEventEntity(trackId = pinkWhite, startedAt = T + 1_000))
        events.insert(ListeningEventEntity(trackId = nights, startedAt = T + 1_500))
        events.insert(ListeningEventEntity(trackId = ivy, startedAt = T + 2_000))
        events.insert(ListeningEventEntity(trackId = blocked, startedAt = T + 3_000))
    }

    /** The file's bytes, written the way the export writes them (streamed). */
    private fun bytesOf(file: WebLibraryFile): ByteArray =
        ByteArrayOutputStream().also { WebLibraryFile.write(file, it) }.toByteArray()

    private suspend fun golden(): ByteArray {
        fill()
        return bytesOf(exporter.collect(nowMs = T + 5_000, generator = "Stash for Android (golden)"))
    }

    @Test fun `the file holds your likes, your playlists in order and your plays, newest first`() = runTest {
        fill()
        val file = exporter.collect(nowMs = T + 5_000, generator = "Stash for Android 1.0")

        assertEquals("stash-web-library", file.kind)
        assertEquals(1, file.v)
        assertEquals("2025-10-07T12:00:05.000Z", file.exportedAt)

        // Stash likes and Spotify likes, newest first; the blocked song and the untagged local file never leave.
        assertEquals(listOf("Halo", "Pink + White", "Nights"), file.likes.map { it.item.title })
        assertEquals(listOf(T + 200, T + 100, T + 50), file.likes.map { it.likedAt })

        val pink = file.likes[1].item
        assertEquals(
            WebLibraryFile.Song(
                title = "Pink + White", artist = "Frank Ocean", album = "Blonde", durationMs = 184_000, isrc = "USUM71607009",
                spotifyId = "3xKsf9qdS1CyvXSMEid6g8", refs = mapOf("youtube" to "uxpDa-c-4Mc"),
                artwork = listOf(WebLibraryFile.Artwork("https://i.scdn.co/image/ab67616d0000b273c5649add07ed3720be9d5526")),
                addedAt = T + 10,
            ),
            pink,
        )
        assertNull("a cover on another host is dropped", file.likes[2].item.artwork)

        // Only your own playlists: no Liked Songs (those are likes), Daily Mixes, Stash Mixes or Downloads.
        assertEquals(listOf("Night drive", "Robin’s mix"), file.playlists.map { it.name })
        val drive = file.playlists[0]
        assertEquals(listOf("Ivy", "Pink + White"), drive.items.map { it.title })
        assertEquals(T + 2, drive.createdAt)
        assertEquals("the newest song still in it (not the removed or blocked ones)", T + 600, drive.updatedAt)
        assertNull(drive.follow)
        assertEquals(WebLibraryFile.Follow("Fw12ab34", 3, "Robin"), file.playlists[1].follow)
        assertNull("not a Spotify id", file.playlists[1].items.single().spotifyId)

        assertEquals(listOf("Ivy", "Nights", "Pink + White"), file.history.map { it.item.title })
        assertEquals(listOf(T + 2_000, T + 1_500, T + 1_000), file.history.map { it.playedAt })
    }

    @Test fun `ids are stable, so a second import merges into the same playlist`() = runTest {
        fill()
        val first = exporter.collect(T, null).playlists.map { it.id }
        val second = exporter.collect(T + 1, null).playlists.map { it.id }
        assertEquals(first, second)
        first.forEach { assertTrue(it, Regex("^app-[0-9]+$").matches(it)) }
    }

    @Test fun `no file paths, download state, mixes or blocked songs in the text`() = runTest {
        val text = golden().toString(Charsets.UTF_8)
        listOf("/storage", ".opus", "memo", "tracker.example", "Daily", "Downloads", "Blocked", "Hidden", "Not synced", "isDownloaded", "filePath")
            .forEach { assertFalse("leaked: $it", text.contains(it)) }
        // The file reads back as the web expects: plain JSON objects with only the v1 fields.
        val root = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("kind", "v", "exportedAt", "generator", "likes", "playlists", "history"), root.keys)
        val item = root["likes"]!!.jsonArray[0].jsonObject["item"] as JsonObject
        assertEquals("Halo", item["title"]!!.jsonPrimitive.content)
        assertEquals(setOf("title", "artist", "durationMs", "artwork", "addedAt"), item.keys)
    }

    @Test fun `a deleted playlist never leaves`() = runTest {
        val song = db.trackDao().insert(track("Ivy", "Frank Ocean", added = 10, youtubeId = "AE005nZeF-A"))
        val playlists = db.playlistDao()
        val kept = playlists.insert(playlist("Kept", PlaylistType.CUSTOM, "custom_kept", added = 1, syncEnabled = false))
        member(kept, song, 0, added = 100)
        // Deleted in Stash (is_active = 0), though it is made in Stash and still holds a song you can play.
        val gone = playlists.insert(
            playlist("Deleted list", PlaylistType.CUSTOM, "custom_gone", added = 2, syncEnabled = false).copy(isActive = false),
        )
        member(gone, song, 0, added = 100)

        val file = exporter.collect(T, null)

        assertEquals(listOf("Kept"), file.playlists.map { it.name })
        assertFalse(bytesOf(file).toString(Charsets.UTF_8).contains("Deleted list"))
    }

    @Test fun `a playlist keeps the order Library shows (its positions), not the order songs were added`() = runTest {
        val tracks = db.trackDao()
        val first = tracks.insert(track("First", "Band", added = 10))
        val second = tracks.insert(track("Second", "Band", added = 20))
        val third = tracks.insert(track("Third", "Band", added = 30))
        val list = db.playlistDao().insert(playlist("Moved around", PlaylistType.CUSTOM, "custom_order", added = 1, syncEnabled = false))
        // Added newest-first, then dragged into order: position and added_at disagree on every song.
        member(list, first, 0, added = 900)
        member(list, second, 1, added = 500)
        member(list, third, 2, added = 100)

        val playlist = exporter.collect(T, null).playlists.single()

        assertEquals(listOf("First", "Second", "Third"), playlist.items.map { it.title })
        assertEquals("the newest addition, wherever it sits", T + 900, playlist.updatedAt)
    }

    @Test fun `a song blocked by its Spotify or YouTube id never leaves, whatever it is called now`() = runTest {
        val tracks = db.trackDao()
        val bySpotify = tracks.insert(
            track("Renamed On Spotify", "Artist A", added = 10, spotifyUri = "spotify:track:0aBcDeFgHiJkLmNoPqRsTu", stashLikedAt = T + 10),
        )
        val byYoutube = tracks.insert(
            track("Renamed On YouTube", "Artist B", added = 20, youtubeId = "bbbbbbbbbbb", stashLikedAt = T + 20),
        )
        val fine = tracks.insert(track("Still Fine", "Artist C", added = 30, youtubeId = "ccccccccccc", stashLikedAt = T + 30))
        // Blocked under the names they had then, so only the id can match.
        val blocklist = db.trackBlocklistDao()
        blocklist.insert(
            TrackBlocklistEntity(
                canonicalKey = "artist a|old spotify title", artist = "Artist A", title = "Old Spotify Title",
                spotifyUri = "spotify:track:0aBcDeFgHiJkLmNoPqRsTu", blockedAt = T, blockedFrom = "test",
            ),
        )
        blocklist.insert(
            TrackBlocklistEntity(
                canonicalKey = "artist b|old youtube title", artist = "Artist B", title = "Old YouTube Title",
                youtubeId = "bbbbbbbbbbb", blockedAt = T, blockedFrom = "test",
            ),
        )
        val list = db.playlistDao().insert(playlist("Mine", PlaylistType.CUSTOM, "custom_mine", added = 1, syncEnabled = false))
        member(list, bySpotify, 0, added = 100)
        member(list, byYoutube, 1, added = 100)
        member(list, fine, 2, added = 100)
        val events = db.listeningEventDao()
        listOf(bySpotify, byYoutube, fine).forEachIndexed { i, id ->
            events.insert(ListeningEventEntity(trackId = id, startedAt = T + 1_000 + i))
        }

        val file = exporter.collect(T + 5_000, null)

        assertEquals(listOf("Still Fine"), file.likes.map { it.item.title })
        assertEquals(listOf("Still Fine"), file.playlists.single().items.map { it.title })
        assertEquals(listOf("Still Fine"), file.history.map { it.item.title })
        assertFalse(bytesOf(file).toString(Charsets.UTF_8).contains("Renamed"))
    }

    @Test fun `plays stop at the limit, newest kept`() = runTest {
        fill()
        assertEquals(listOf(T + 2_000, T + 1_500), db.webLibraryExportDao().recentPlays(2).map { it.playedAt })
    }

    @Test fun `an empty library writes an empty file the web accepts`() = runTest {
        val text = bytesOf(exporter.collect(T, null)).toString(Charsets.UTF_8)
        assertEquals("""{"kind":"stash-web-library","v":1,"exportedAt":"2025-10-07T12:00:00.000Z","likes":[],"playlists":[],"history":[]}""", text)
    }

    @Test fun `the file name is the web's backup name`() {
        assertEquals("stash-library-2025-10-07.json", WebLibraryFile.fileName(T, ZoneOffset.UTC))
    }

    @Test fun `everything ticked writes exactly what the export always wrote`() = runTest {
        fill()
        val all = bytesOf(exporter.collect(T + 5_000, "g"))
        val explicit = ExportSelection(likes = true, plays = true, playlistIds = db.webLibraryExportDao().playlists().map { it.id }.toSet())
        assertArrayEquals(all, bytesOf(exporter.collect(T + 5_000, "g", ExportSelection.ALL)))
        assertArrayEquals(all, bytesOf(exporter.collect(T + 5_000, "g", explicit)))
    }

    @Test fun `the picker drops exactly the unticked parts, as empty arrays`() = runTest {
        fill()
        val drive = db.webLibraryExportDao().playlists().first { it.name.trim() == "Night drive" }.id
        val file = exporter.collect(T + 5_000, "g", ExportSelection(likes = false, plays = true, playlistIds = setOf(drive)))
        assertTrue(file.likes.isEmpty())
        assertEquals(listOf("Night drive"), file.playlists.map { it.name })
        assertEquals(3, file.history.size)

        val none = exporter.collect(T + 5_000, null, ExportSelection(likes = true, plays = false, playlistIds = emptySet()))
        assertEquals(3, none.likes.size)
        assertTrue(none.playlists.isEmpty())
        assertTrue(none.history.isEmpty())
        // Still v1: the arrays are there, only empty, so every v1 reader takes it.
        val text = bytesOf(none).toString(Charsets.UTF_8)
        assertTrue(text.contains("\"playlists\":[]") && text.contains("\"history\":[]"))
    }

    @Test fun `a playlist made after the choice was saved goes too when every playlist was ticked`() = runTest {
        fill()
        val newer = db.playlistDao().insert(playlist("Made later", PlaylistType.CUSTOM, "custom_later", added = 9))
        db.playlistDao().insertCrossRef(PlaylistTrackCrossRef(playlistId = newer, trackId = db.trackDao().findByYoutubeId("AE005nZeF-A")!!.id, position = 0))
        assertTrue(exporter.collect(T, null, ExportSelection.ALL).playlists.any { it.name == "Made later" })
    }

    @Test fun `the catalog counts what the file would hold`() = runTest {
        fill()
        val catalog = exporter.catalog()
        val file = exporter.collect(T + 5_000, null)
        assertEquals(file.likes.size, catalog.likes)
        assertEquals(file.history.size, catalog.plays)
        assertEquals(file.playlists.map { it.name }, catalog.playlists.map { it.name })
        assertEquals(file.playlists.map { it.items.size }, catalog.playlists.map { it.songs })
        assertEquals(listOf(false, true), catalog.playlists.map { it.sharedMix })
    }

    @Test fun `the text of a send is the file's bytes`() = runTest {
        fill()
        val file = exporter.collect(T + 5_000, "g")
        assertArrayEquals(bytesOf(file), exporter.text(file).toByteArray(Charsets.UTF_8))
    }

    /** A stream that fails on the first write, as a full disk or a vanished SD card does. */
    private fun failingStream(error: () -> Throwable) = object : OutputStream() {
        override fun write(b: Int) = throw error()
        override fun write(b: ByteArray, off: Int, len: Int) = throw error()
    }

    /** The documents the exporter deleted (Robolectric records a delete with no provider behind it only here). */
    @Suppress("DEPRECATION")
    private fun deleted(): List<Uri> = shadowOf(context.contentResolver).deleteStatements.map { it.uri }

    @Test fun `a finished export keeps its file`() = runTest {
        fill()
        val out = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(EXPORT_URI, out)

        val result = exporter.export(EXPORT_URI).getOrThrow()

        assertEquals(WebLibraryExportResult(likes = 3, playlists = 2, plays = 3), result)
        val root = Json.parseToJsonElement(out.toString(Charsets.UTF_8.name())).jsonObject
        assertEquals(3, root["likes"]!!.jsonArray.size)
        assertTrue(deleted().isEmpty())
    }

    @Test fun `a failed export deletes the file it was writing, so no empty file is left`() = runTest {
        fill()
        shadowOf(context.contentResolver).registerOutputStream(EXPORT_URI, failingStream { IOException("disk full") })

        assertTrue(exporter.export(EXPORT_URI).isFailure)
        assertEquals(listOf(EXPORT_URI), deleted())
    }

    @Test fun `a cancelled export deletes the file too`() = runTest {
        fill()
        // Leaving the screen cancels the export; here the cancellation lands while the file is being written.
        shadowOf(context.contentResolver).registerOutputStream(EXPORT_URI, failingStream { CancellationException("left the screen") })

        val outcome = runCatching { exporter.export(EXPORT_URI) }
        assertTrue("cancellation is passed on, not turned into a result", outcome.exceptionOrNull() is CancellationException)
        assertEquals(listOf(EXPORT_URI), deleted())
    }

    @Test fun `a saved album is not a playlist on the web, from any service, and the mirror never offers one`() = runTest {
        val song = db.trackDao().insert(track("Ivy", "Frank Ocean", added = 10, youtubeId = "AE005nZeF-A"))
        val playlists = db.playlistDao()
        val mine = playlists.insert(playlist("Night drive", PlaylistType.CUSTOM, "custom_1", added = 1, syncEnabled = false))
        member(mine, song, 0, added = 100)
        val stampBefore = db.webLibraryExportDao().libraryStampNow()
        val digestBefore = db.mirrorDao().playlistSetDigest()
        // Synced from Spotify's saved albums, saved on an album page in Stash, synced from YouTube Music:
        // all switched on and holding a song you can play, as a playlist would need to be to go.
        listOf(
            playlist("Blonde", PlaylistType.CUSTOM, "album:spotify:3mH6qwIy9crq0I9YQbOuDf", added = 2),
            playlist("Blonde", PlaylistType.CUSTOM, "album:qobuz:0886446224536", added = 3).copy(source = MusicSource.BOTH),
            playlist("Blonde", PlaylistType.CUSTOM, "MPREb_blonde", added = 4).copy(source = MusicSource.YOUTUBE),
        ).forEach { member(playlists.insert(it), song, 0, added = 100) }

        assertEquals(listOf("Night drive"), exporter.collect(T, null).playlists.map { it.name })
        assertEquals(listOf("Night drive"), exporter.catalog().playlists.map { it.name })
        assertEquals(listOf("Night drive"), db.mirrorDao().playlists().map { it.name })
        assertEquals("an album's songs don't move the playlists' change stamp", stampBefore, db.webLibraryExportDao().libraryStampNow())
        assertEquals("a new album is not a new playlist for \"Mirror new playlists too\"", digestBefore, db.mirrorDao().playlistSetDigest())
        // A saved Qobuz PLAYLIST shares the album: key and is still a playlist.
        playlists.insert(playlist("Qobuz picks", PlaylistType.CUSTOM, "album:qobuz_playlist:42", added = 5).copy(source = MusicSource.BOTH))
        assertEquals(listOf("Night drive", "Qobuz picks"), db.mirrorDao().playlists().map { it.name })
    }

    // ── Send to computer's library file (stash-library.json) ─────────────────────────────────────────────────────────────

    @Test fun `the web's file has neither of Send to computer's fields, not even empty`() = runTest {
        val text = golden().toString(Charsets.UTF_8)
        assertFalse(text.contains("sent_at_ms"))
        assertFalse(text.contains("\"from\""))
        Json.parseToJsonElement(text).jsonObject["playlists"]!!.jsonArray.forEach {
            assertEquals(setOf("id", "name", "items", "createdAt", "updatedAt"), it.jsonObject.keys - "follow")
        }
    }

    @Test fun `the computer's file adds the synced mixes and says where each synced playlist came from`() = runTest {
        fill()
        val tracks = db.trackDao()
        val playlists = db.playlistDao()
        val homeSong = tracks.insert(track("Home Song", "Tuber", added = 97, youtubeId = "dddddddddd1"))
        // A YouTube Music home mix as the sync stores it, by its browse id; a Spotify one by its bare id (Daily Mix 1).
        val supermix = playlists.insert(playlist("My Supermix", PlaylistType.DAILY_MIX, "VLRDTMAK5uy_super", added = 8).copy(source = MusicSource.YOUTUBE))
        member(supermix, homeSong, 0, added = 990)
        // Neither goes: a mix no longer in the library, and a SoundCloud one (the file can't say it came from SoundCloud).
        member(playlists.insert(playlist("Old Radio", PlaylistType.DAILY_MIX, "radio_old", added = 9).copy(isActive = false)), homeSong, 0, added = 990)
        member(playlists.insert(playlist("SoundCloud Weekly", PlaylistType.DAILY_MIX, "sc_mix_1", added = 10).copy(source = MusicSource.SOUNDCLOUD)), homeSong, 0, added = 990)
        // One of your Spotify playlists, synced.
        val roadTrip = playlists.insert(playlist("Road trip", PlaylistType.CUSTOM, "37i9dQZF1DXroad", added = 11))
        member(roadTrip, homeSong, 0, added = 995)

        val web = exporter.collect(T + 5_000, null)
        val file = exporter.forComputer(T + 5_000, sentAtMs = 1_764_000_000_123L)!!

        assertEquals(1_764_000_000_123L, file.sentAtMs)
        // Your own playlists and the shared mix first, as the web has them, then the mixes: Spotify's, then YouTube Music's.
        // Never a Stash Mix, the Downloads list, a Liked Songs list (the likes are those) or a saved album.
        assertEquals(listOf("Night drive", "Robin’s mix", "Road trip", "Daily Mix 1", "My Supermix"), file.playlists.map { it.name })
        assertEquals(listOf(null, null, "spotify", "spotify", "youtube"), file.playlists.map { it.from })
        // Ids the same on every install: the service's own id, your own list's custom_ id; the shared mix as the web has it.
        val robin = db.playlistDao().findBySourceId("share:Fw12ab34")!!.id
        assertEquals(
            listOf("custom_1", "app-$robin", "spotify-37i9dQZF1DXroad", "spotify-daily1", "youtube-RDTMAK5uy_super"),
            file.playlists.map { it.id },
        )
        // The web's file keeps its own ids.
        assertTrue(web.playlists.all { Regex("^app-[0-9]+$").matches(it.id) })
        // A mix with every song in it, downloaded or not.
        assertEquals(listOf("Daily Song", "Ivy"), file.playlists[3].items.map { it.title })
        // The likes are the web's, every one, downloaded or not.
        assertEquals(web.likes, file.likes)
        assertEquals(web.history, file.history)

        // In the text: the send's number at the top; "from" only where it is set.
        val root = Json.parseToJsonElement(bytesOf(file).toString(Charsets.UTF_8)).jsonObject
        assertEquals("1764000000123", root["sent_at_ms"]!!.jsonPrimitive.content)
        val froms = root["playlists"]!!.jsonArray.map { it.jsonObject["from"]?.jsonPrimitive?.content }
        assertEquals(listOf(null, null, "spotify", "spotify", "youtube"), froms)
        assertFalse(root["playlists"]!!.jsonArray[0].jsonObject.containsKey("from"))
    }

    @Test fun `the computer's file keeps more plays than the web's 5,000`() = runTest {
        val song = db.trackDao().insert(track("Ivy", "Frank Ocean", added = 10, youtubeId = "AE005nZeF-A"))
        val events = db.listeningEventDao()
        db.withTransaction {
            repeat(WebLibraryFile.MAX_PLAYS + 236) { i -> events.insert(ListeningEventEntity(trackId = song, startedAt = T + 1_000 + i)) }
        }

        assertEquals(WebLibraryFile.MAX_PLAYS, exporter.collect(T, null).history.size)
        val history = exporter.forComputer(T, sentAtMs = 1L)!!.history
        assertEquals(WebLibraryFile.MAX_PLAYS + 236, history.size)
        assertEquals("newest first", T + 1_000 + WebLibraryFile.MAX_PLAYS + 235, history.first().playedAt)
    }

    @Test fun `a file too big for the computer loses its oldest plays first, then the likes beyond 50,000`() {
        val song = WebLibraryFile.Song(title = "Song", artist = "Band")
        val plays = List(10) { WebLibraryFile.Play(song, playedAt = T - it) }
        val likes = List(WebLibraryFile.COMPUTER_LIKES_KEPT + 10) { WebLibraryFile.Like(song, likedAt = T - it) }
        val file = WebLibraryFile(exportedAt = "x", likes = likes, playlists = emptyList(), history = plays, sentAtMs = 1L)
        fun bytes(f: WebLibraryFile) = WebLibraryFile.bytes(WebLibraryFile.serializer(), f)
        val whole = bytes(file)

        // Small enough: as it was.
        assertTrue(exporter.fitForComputer(file, target = whole) === file)
        // A little over: the oldest play goes, nothing else.
        val oneOver = exporter.fitForComputer(file, target = whole - 1)!!
        assertEquals(plays.dropLast(1), oneOver.history)
        assertEquals(likes, oneOver.likes)
        // Over by more than every play: every play goes, then the oldest like.
        val noPlays = bytes(file.copy(history = emptyList()))
        val more = exporter.fitForComputer(file, target = noPlays - 1)!!
        assertTrue(more.history.isEmpty())
        assertEquals(likes.dropLast(1), more.likes)
        assertTrue(bytes(more) <= noPlays - 1)
        // However big, the newest 50,000 likes stay, and the send's number with them.
        val most = exporter.fitForComputer(file, target = 0, most = Long.MAX_VALUE)!!
        assertEquals(likes.take(WebLibraryFile.COMPUTER_LIKES_KEPT), most.likes)
        assertEquals(1L, most.sentAtMs)
        // Still more than the computer reads: no file at all.
        assertNull(exporter.fitForComputer(file, target = 0, most = 1_000))
    }

    @Test fun `the computer's playlist ids are the same on every install, each given once, all ids the desktop takes`() {
        val used = HashSet<String>()
        fun id(source: MusicSource, sourceId: String, row: Long): String {
            val p = PlaylistEntity(id = row, name = "p", source = source, sourceId = sourceId)
            return computerIdOf(p, fromOf(p), fallback = "app-$row", used = used)
        }
        assertEquals("spotify-37i9dQZF1DXcBWIGoYBM5M", id(MusicSource.SPOTIFY, "37i9dQZF1DXcBWIGoYBM5M", 1))
        assertEquals("youtube-RDTMAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56E", id(MusicSource.YOUTUBE, "RDTMAK5uy_kset8DisdE7LSD4TNjEVvrKRTmG7a56E", 2))
        assertEquals("custom_3f2a6c1e-9b1d-4c2e-8a4f-0d6b7e5c9a11", id(MusicSource.BOTH, "custom_3f2a6c1e-9b1d-4c2e-8a4f-0d6b7e5c9a11", 3))
        // The longest a service's id can be and still fit the desktop's 64 characters, and one longer.
        assertEquals("spotify-" + "a".repeat(55), id(MusicSource.SPOTIFY, "a".repeat(55), 4))
        assertEquals("app-5", id(MusicSource.SPOTIFY, "b".repeat(56), 5))
        // Not an id the desktop takes, a shared mix, one saved in Stash: as the web's file has them.
        assertEquals("app-6", id(MusicSource.SPOTIFY, "spotify:collection:tracks", 6))
        assertEquals("app-7", id(MusicSource.BOTH, "share:Fw12ab34", 7))
        assertEquals("app-8", id(MusicSource.BOTH, "custom_" + "c".repeat(58), 8))
        assertEquals("app-9", id(MusicSource.BOTH, "album:qobuz_playlist:42", 9))
        // The same id twice: the first keeps it, the other has its row's.
        assertEquals("app-10", id(MusicSource.SPOTIFY, "37i9dQZF1DXcBWIGoYBM5M", 10))
        // As the sync stores them on one install or another: a Spotify URI is its id, a YouTube Music browse id (VL…) its
        // playlist's, so both installs give one id.
        assertEquals("spotify-37i9dQZF1E4yrYiQJfy370", id(MusicSource.SPOTIFY, "spotify:playlist:37i9dQZF1E4yrYiQJfy370", 11))
        assertEquals("spotify-1aBcDeFgHiJkLmNoPqRsTu", id(MusicSource.SPOTIFY, "spotify:user:someone:playlist:1aBcDeFgHiJkLmNoPqRsTu", 12))
        assertEquals("youtube-RDTMAK5uy_nGQKSMIkpr4o9VI_2i56pkGliD6FQRo", id(MusicSource.YOUTUBE, "VLRDTMAK5uy_nGQKSMIkpr4o9VI_2i56pkGliD6FQRo", 13))
        assertEquals("youtube-PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf", id(MusicSource.YOUTUBE, "VLPLrAXtmErZgOeiKm4sgNOknGvNjby9efdf", 14))
        // The same playlist stored both ways on one phone: the first keeps the id.
        assertEquals("app-15", id(MusicSource.YOUTUBE, "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf", 15))
        assertEquals(15, used.size)
        used.forEach { assertTrue(it, Regex("^[A-Za-z0-9_-]{1,64}$").matches(it)) }
    }

    @Test fun `where a playlist came from goes by its source, never for your own or a shared mix`() {
        fun from(source: MusicSource, sourceId: String) =
            fromOf(PlaylistEntity(name = "p", source = source, sourceId = sourceId))
        assertEquals("spotify", from(MusicSource.SPOTIFY, "37i9dQZF1DX"))
        assertEquals("youtube", from(MusicSource.YOUTUBE, "PLxyz"))
        assertNull("made in Stash", from(MusicSource.SPOTIFY, "custom_1"))
        assertNull("made in Stash", from(MusicSource.BOTH, "custom_3f2a"))
        assertNull("from Stash on the web", from(MusicSource.BOTH, "custom_web_abc"))
        assertNull("a shared mix", from(MusicSource.BOTH, "share:Fw12ab34"))
        assertNull("SoundCloud can't be named", from(MusicSource.SOUNDCLOUD, "sc_1"))
    }

    /** The exact bytes for the fixed library; the web player imports the same file in its tests. */
    @Test fun `golden file`() = runTest {
        val bytes = golden()
        val path = File("src/test/resources/weblibrary/golden-v1.json")
        if (System.getenv("UPDATE_WEB_LIBRARY_GOLDEN") == "1") {
            path.parentFile?.mkdirs()
            path.writeBytes(bytes)
        }
        // Text first, for a readable diff when it fails; then the exact bytes, as the streamed export writes them.
        assertEquals(path.readText(Charsets.UTF_8), bytes.toString(Charsets.UTF_8))
        assertArrayEquals(path.readBytes(), bytes)
        // The web player's fixture pins the same hash (stash-player backup.test.ts, APP_GOLDEN_SHA256): change both or neither.
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(GOLDEN_SHA256, sha)
    }

    private companion object {
        /** 2025-10-07T12:00:00Z */
        const val T = 1_759_838_400_000L

        val EXPORT_URI: Uri = Uri.parse("content://com.stash.test.export/stash-library-2025-10-07.json")

        const val GOLDEN_SHA256 = "0810e42571ca36d1ee2209935604307a23c11b36166bee54518626b0b6b710e2"
    }
}
