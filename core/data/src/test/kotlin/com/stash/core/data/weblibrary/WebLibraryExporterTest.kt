package com.stash.core.data.weblibrary

import android.content.Context
import android.net.Uri
import androidx.room.Room
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
