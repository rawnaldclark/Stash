package com.stash.core.data.weblibrary

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.ListeningEventEntity
import com.stash.core.data.db.entity.RecordPlayEntity
import com.stash.core.data.db.entity.TrackBlocklistEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackSkipEventEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * The library file's `listens` section (library-file v1 "Listens", Your record on the web): this phone's own plays and skips,
 * streamed, against a real in-memory Room database. Plays mirrored or imported from elsewhere never go; the rest of the file
 * is byte for byte what it was; the app's own reader still reads the file and ignores the section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WebLibraryListensTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private lateinit var exporter: WebLibraryExporter

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java).allowMainThreadQueries().build()
        exporter = WebLibraryExporter(context, db)
    }

    @After fun tearDown() = db.close()

    private suspend fun track(title: String, artist: String, album: String = "", durationMs: Long = 0, youtubeId: String? = null): Long =
        db.trackDao().insert(
            TrackEntity(
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                youtubeId = youtubeId,
                dateAdded = Instant.ofEpochMilli(T),
                canonicalTitle = title.lowercase(),
                canonicalArtist = artist.lowercase(),
            ),
        )

    private suspend fun play(trackId: Long, at: Long, listenedMs: Long? = null, origin: String? = null) =
        db.listeningEventDao().insert(ListeningEventEntity(trackId = trackId, startedAt = at, listenedMs = listenedMs, originDevice = origin))

    private suspend fun skip(trackId: Long, at: Long, positionMs: Long) =
        db.trackSkipEventDao().insert(TrackSkipEventEntity(trackId = trackId, skippedAt = at, positionMs = positionMs))

    /** A phone's history with one of everything the section has to take or leave. */
    private suspend fun fill() {
        val pink = track("Pink + White", "Frank Ocean", album = "Blonde", durationMs = 184_000, youtubeId = "uxpDa-c-4Mc")
        val ivy = track("Ivy", "Frank Ocean", durationMs = 249_000)
        val runaway = track("Runaway", "Kanye West")
        val blocked = track("Blocked Song", "Someone")
        val untagged = track("Voice memo", "")
        db.trackBlocklistDao().insert(
            TrackBlocklistEntity(canonicalKey = "someone|blocked song", artist = "Someone", title = "Blocked Song", blockedAt = T, blockedFrom = "test"),
        )

        play(pink, T + 1_000, listenedMs = 180_500) // known time
        play(ivy, T + 2_000) // time unknown: `ms` left out, `d` there
        play(runaway, T + 3_000, origin = "d_web00000000000") // mirrored from the web: never goes
        play(runaway, T + 3_500, origin = "import:lastfm") // imported: never goes
        play(blocked, T + 4_000, listenedMs = 60_000) // blocked: never goes
        play(untagged, T + 5_000, listenedMs = 60_000) // no artist: never goes

        skip(ivy, T + 20_000, positionMs = 12_000) // started at T + 8,000
        skip(blocked, T + 30_000, positionMs = 1_000)
    }

    private fun parse(bytes: ByteArray): JsonObject = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject

    /** The section's bytes alone, written straight from the database. */
    private suspend fun section(budget: Long = Long.MAX_VALUE, maxPlays: Int = WebLibraryListens.MAX_PLAYS, page: Int = 2): Pair<String, ListensWritten> {
        val out = ByteArrayOutputStream()
        val written = WebLibraryListens.write(db.webLibraryExportDao(), out, budget, maxPlays = maxPlays, pageSize = page)
        return out.toString(Charsets.UTF_8.name()) to written
    }

    @Test fun `only this phone's plays go, newest first, with their own time or else the song's length`() = runTest {
        fill()
        val (text, written) = section()
        assertEquals(
            ""","listens":{"device":"phone","plays":[""" +
                """{"t":"Ivy","a":"Frank Ocean","s":${T + 2_000},"d":249000},""" +
                """{"t":"Pink + White","a":"Frank Ocean","al":"Blonde","s":${T + 1_000},"ms":180500,"d":184000}""" +
                """],"skips":[{"t":"Ivy","a":"Frank Ocean","s":${T + 8_000},"ms":12000}]}""",
            text,
        )
        assertEquals(ListensWritten(plays = 2, skips = 1), written)
    }

    @Test fun `pages run on past a page of rows that all stay out`() = runTest {
        fill()
        // Page size 1: the newest rows (untagged, blocked, two remote) each fill a page of their own and are all dropped.
        val (text, _) = section(page = 1)
        assertEquals(section(page = 5_000).first, text)
    }

    @Test fun `the play limit keeps the newest`() = runTest {
        val song = track("Song", "Band")
        repeat(7) { play(song, T + it * 1_000L) }
        val plays = Json.parseToJsonElement("{" + section(maxPlays = 3).first.drop(1) + "}").jsonObject["listens"]!!.jsonObject["plays"]!!.jsonArray
        assertEquals(listOf(T + 6_000, T + 5_000, T + 4_000), plays.map { it.jsonObject["s"]!!.jsonPrimitive.long })
    }

    @Test fun `the byte budget ends a list early and the section is still whole JSON within it`() = runTest {
        val song = track("Song", "Band")
        repeat(50) { play(song, T + it * 1_000L) }
        val budget = 400L
        val (text, written) = section(budget = budget)
        assertTrue("${text.length} bytes", text.toByteArray().size <= budget)
        assertTrue(written.plays in 1 until 50)
        val listens = Json.parseToJsonElement("{" + text.drop(1) + "}").jsonObject["listens"]!!.jsonObject
        assertEquals(written.plays, listens["plays"]!!.jsonArray.size)
        // The newest went first.
        assertEquals(T + 49_000, listens["plays"]!!.jsonArray[0].jsonObject["s"]!!.jsonPrimitive.long)
    }

    @Test fun `words are cleaned and cut as a Song's are`() = runTest {
        val long = track("A".repeat(400), "Band‮\u0000 Name", album = "  ")
        play(long, T + 1_000)
        val p = WebLibraryListens.play(db.webLibraryExportDao().ownPlaysBefore(Long.MAX_VALUE, Long.MAX_VALUE, 10).single())!!
        assertEquals(300, p.t.length)
        assertEquals("Band Name", p.a)
        assertNull(p.al)
    }

    @Test fun `a skip at position 0 started when it was skipped, and one that can't have started is dropped`() {
        val row = { at: Long, pos: Long -> com.stash.core.data.db.dao.WebLibraryExportDao.SkipRow(1, "Ivy", "Frank Ocean", "", at, pos) }
        assertEquals(WebLibraryListens.Skip("Ivy", "Frank Ocean", s = T, ms = 0), WebLibraryListens.skip(row(T, 0)))
        assertEquals(WebLibraryListens.Skip("Ivy", "Frank Ocean", s = T, ms = 0), WebLibraryListens.skip(row(T, -5)))
        assertNull(WebLibraryListens.skip(row(1_000, 1_000)))
    }

    @Test fun `the export with the history is the same file with listens before its closing brace`() = runTest {
        fill()
        val without = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(PLAIN_URI, without)
        val with = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(EXPORT_URI, with)

        exporter.export(PLAIN_URI, listens = false).getOrThrow()
        val result = exporter.export(EXPORT_URI, listens = true).getOrThrow()

        assertEquals(2, result.listens)
        assertEquals(1, result.skips)
        val plain = without.toByteArray().toString(Charsets.UTF_8)
        val full = with.toByteArray().toString(Charsets.UTF_8)
        // exportedAt differs between the two runs; everything else up to the closing brace is the same.
        val stamp = Regex("\"exportedAt\":\"[^\"]+\"")
        assertEquals(plain.replace(stamp, "").dropLast(1), full.replace(stamp, "").substringBefore(""","listens":"""))
        assertTrue(full.endsWith("}]}}"))
        val root = parse(with.toByteArray())
        assertEquals(setOf("kind", "v", "exportedAt", "generator", "likes", "playlists", "history", "listens"), root.keys)
        assertEquals(2, root["listens"]!!.jsonObject["plays"]!!.jsonArray.size)
        // `history` is untouched: every origin, newest 5,000, as before.
        assertEquals(4, root["history"]!!.jsonArray.size)
    }

    @Test fun `plays brought into Your record (a Spotify export) never go in the export`() = runTest {
        fill()
        // Your record's imported plays live in their own table: neither the history nor the listens section reads it
        db.recordPlayDao().apply(
            listOf(
                RecordPlayEntity(id = "ivy|frank ocean|${T + 9_000}", t = "Ivy", a = "Frank Ocean", al = "Blonde", s = T + 9_000, ms = 200_000, f = 34),
                RecordPlayEntity(id = "nights|frank ocean|${T + 9_500}", t = "Nights", a = "Frank Ocean", s = T + 9_500, ms = 300_000, f = 16),
            ),
            emptyList(),
        )
        val with = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(EXPORT_URI, with)

        val result = exporter.export(EXPORT_URI, listens = true).getOrThrow()

        assertEquals(2, result.listens)
        assertEquals(1, result.skips)
        val root = parse(with.toByteArray())
        val starts = root["listens"]!!.jsonObject["plays"]!!.jsonArray.map { it.jsonObject["s"]!!.jsonPrimitive.long }
        assertEquals(listOf(T + 2_000, T + 1_000), starts)
        assertEquals(4, root["history"]!!.jsonArray.size)
    }

    @Test fun `the app's own reader reads a file with listens and ignores them`() = runTest {
        fill()
        val with = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(EXPORT_URI, with)
        exporter.export(EXPORT_URI, listens = true).getOrThrow()

        val withListens = WebLibraryReader.read(with.toByteArray().inputStream(), T)
        val plain = WebLibraryReader.read(WebLibraryFile.text(exporter.collect(T, null)), T)
        assertEquals(plain.likes, withListens.likes)
        assertEquals(plain.playlists, withListens.playlists)
        assertEquals(plain.history, withListens.history)
    }

    @Test fun `a history bigger than the reader's shape limits is skipped, not refused`() {
        // 1,000,000 plays: with the root and the section's own containers, over a file's 1,000,000 if they were counted.
        val text = buildString {
            append("""{"kind":"stash-web-library","v":1,"listens":{"device":"phone","plays":[""")
            repeat(1_000_000) { if (it > 0) append(','); append("{}") }
            append("""],"skips":[]},"history":[{"item":{"title":"Ivy","artist":"Frank Ocean"},"playedAt":$T}]}""")
        }
        val c = WebLibraryReader.read(text, T)
        assertEquals(listOf("Ivy"), c.history.map { it.song.title })
        // Counted again after the section: a big library after it is still held to the limits.
        val tooMany = buildString {
            append("""{"kind":"stash-web-library","v":1,"listens":{"plays":[]},"likes":[""")
            repeat(1_000_000) { if (it > 0) append(','); append("{}") }
            append("]}")
        }
        assertEquals(WebLibraryReader.TOO_BIG, org.junit.Assert.assertThrows(WebLibraryReadException::class.java) { WebLibraryReader.read(tooMany, T) }.message)
    }

    @Test fun `the catalog counts this phone's plays and when they began`() = runTest {
        fill()
        val catalog = exporter.catalog()
        assertEquals(2, catalog.listens)
        assertEquals(T + 1_000, catalog.listensSince)
        assertEquals("history still counts every origin", 4, catalog.plays)
    }

    @Test fun `plays that share a start time all go, across a page boundary`() = runTest {
        val song = track("Song", "Band")
        repeat(3) { play(song, T + 1_000) } // three rows, one started_at: the cursor's id breaks the tie
        play(song, T + 500)
        val (text, written) = section(page = 1)
        assertEquals(4, written.plays)
        val plays = Json.parseToJsonElement("{" + text.drop(1) + "}").jsonObject["listens"]!!.jsonObject["plays"]!!.jsonArray
        assertEquals(listOf(T + 1_000, T + 1_000, T + 1_000, T + 500), plays.map { it.jsonObject["s"]!!.jsonPrimitive.long })
    }

    @Test fun `the skip limit keeps the newest`() = runTest {
        val song = track("Song", "Band")
        repeat(5) { skip(song, T + 10_000 + it * 1_000L, positionMs = 2_000) }
        val out = ByteArrayOutputStream()
        val written = WebLibraryListens.write(db.webLibraryExportDao(), out, Long.MAX_VALUE, maxSkips = 2, pageSize = 2)
        assertEquals(2, written.skips)
        val skips = Json.parseToJsonElement("{" + out.toString(Charsets.UTF_8.name()).drop(1) + "}").jsonObject["listens"]!!
            .jsonObject["skips"]!!.jsonArray
        // s is when each play started: the skip's time less its 2 s.
        assertEquals(listOf(T + 12_000, T + 11_000), skips.map { it.jsonObject["s"]!!.jsonPrimitive.long })
    }

    @Test fun `a phone play of a song with no known length leaves d out`() = runTest {
        play(track("Untimed", "Band", durationMs = 0), T + 1_000, listenedMs = 61_000)
        val p = WebLibraryListens.play(db.webLibraryExportDao().ownPlaysBefore(Long.MAX_VALUE, Long.MAX_VALUE, 10).single())!!
        assertEquals(61_000L, p.ms)
        assertNull(p.d)
        assertTrue(section().first.contains("""{"t":"Untimed","a":"Band","s":${T + 1_000},"ms":61000}"""))
    }

    @Test fun `the whole file stays within the cap, and a library that fills it leaves the section out`() = runTest {
        val song = track("Song", "Band", durationMs = 200_000)
        repeat(200) { play(song, T + it * 1_000L, listenedMs = 100_000) }
        val file = exporter.collect(T, null)
        val base = WebLibraryFile.text(file).toByteArray().size

        for (cap in listOf(base + 1_000L, base + 64L, base + 10L)) {
            val bytes = ByteArrayOutputStream()
            val out = WebLibraryExporter.CountingOutputStream(bytes)
            val written = exporter.writeWithListens(file, out, maxBytes = cap)
            assertTrue("cap $cap: ${bytes.size()} bytes", bytes.size() <= cap)
            val root = parse(bytes.toByteArray())
            if (cap - base < WebLibraryListens.MIN_BUDGET) {
                assertEquals(ListensWritten(0, 0), written)
                assertNull(root["listens"])
            } else {
                assertEquals(written.plays, root["listens"]!!.jsonObject["plays"]!!.jsonArray.size)
            }
        }
    }

    @Test fun `the hold-back stream passes all but the last byte`() {
        val out = ByteArrayOutputStream()
        val held = WebLibraryListens.HoldLastByte(out)
        held.write("{\"a\":".toByteArray())
        held.write('1'.code)
        held.write("}".toByteArray())
        assertArrayEquals("{\"a\":1".toByteArray(), out.toByteArray())
        assertEquals('}'.code, held.release())
    }

    private companion object {
        /** 2025-10-07T12:00:00Z */
        const val T = 1_759_838_400_000L

        val EXPORT_URI: Uri = Uri.parse("content://com.stash.test.export/with-listens.json")
        val PLAIN_URI: Uri = Uri.parse("content://com.stash.test.export/plain.json")
    }
}
