package com.stash.feature.record.importer

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.record.ChosenPlaylist
import com.stash.core.data.record.SpotifyLibraryImport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.ZoneOffset

/**
 * The cross-platform truth (stash-player `docs/spotify-export.md` §6): the fixture export, Spotify's two zips byte for byte
 * (`src/test/resources/spotify-export/`, copied from stash-player `docs/spotify-export/fixture/` at 0e2cb03), read and merged
 * here must give what `expected.json` records the web's importer giving, with `now = 2026-10-23T12:00:00Z`: the files and what
 * couldn't be read, the preview, and the archive's plays (their count, the first ids and the SHA-256 of all of them, sorted and
 * joined with `\n`), and the playlists and liked songs it brings into the library (each playlist's songs, the likes). Taste and
 * eras come with the taste port.
 */
class SpotifyGoldenTest {
    @get:Rule val tmp = TemporaryFolder()

    private val expected: JsonObject = Json.parseToJsonElement(resource("expected.json").readText()).jsonObject

    private fun resource(name: String): File = File(requireNotNull(javaClass.getResource("/spotify-export/$name")) { name }.toURI())

    private fun fixture() = listOf("my_spotify_data.zip", "my_spotify_data (1).zip").map { PickedFile(it, resource(it)) }

    private suspend fun golden(files: List<PickedFile>): Triple<Prepared, MemoryArchive, SpotifyImporter> {
        val archive = MemoryArchive()
        val importer = SpotifyImporter(archive, tmp.newFolder(), ZoneOffset.UTC)
        val prep = importer.prepare(files, NOW)
        importer.commit(prep)
        return Triple(prep, archive, importer)
    }

    @Test fun `now is the fixture's`() {
        assertThat(expected["now"]!!.jsonPrimitive.long).isEqualTo(NOW)
    }

    @Test fun `the files and what couldn't be read`() = runTest {
        val (prep, _, _) = golden(fixture())
        val f = expected["files"]!!.jsonObject
        val files = prep.bundle.files
        assertThat(files.extended).isEqualTo(f["extended"]!!.jsonPrimitive.int)
        assertThat(files.account).isEqualTo(f["account"]!!.jsonPrimitive.int)
        assertThat(files.playlists).isEqualTo(f["playlists"]!!.jsonPrimitive.int)
        assertThat(files.library).isEqualTo(f["library"]!!.jsonPrimitive.int)
        assertThat(files.stash).isEqualTo(f["stash"]!!.jsonPrimitive.int)
        assertThat(files.ignored).isEqualTo(f["ignored"]!!.jsonPrimitive.int)
        assertThat(prep.bundle.unreadable).isEqualTo(expected["unreadable"]!!.jsonPrimitive.int)
    }

    @Test fun `the preview`() = runTest {
        val (prep, _, _) = golden(fixture())
        val e = expected["preview"]!!.jsonObject
        val p = prep.preview
        assertThat(p.plays).isEqualTo(e["plays"]!!.jsonPrimitive.int)
        assertThat(p.same).isEqualTo(e["same"]!!.jsonPrimitive.int)
        assertThat(p.overlap).isEqualTo(e["overlap"]!!.jsonPrimitive.int)
        assertThat(p.listenedMs).isEqualTo(e["listenedMs"]!!.jsonPrimitive.long)
        assertThat(p.first).isEqualTo(e["first"]!!.jsonPrimitive.long)
        assertThat(p.last).isEqualTo(e["last"]!!.jsonPrimitive.long)
        assertThat(p.years).isEqualTo(e["years"]!!.jsonPrimitive.int)
        assertThat(p.playlists).isEqualTo(
            e["playlists"]!!.jsonArray.map { ExportPlaylistCount(it.jsonObject["name"]!!.jsonPrimitive.content, it.jsonObject["songs"]!!.jsonPrimitive.int) },
        )
        assertThat(p.likes).isEqualTo(e["likes"]!!.jsonPrimitive.int)
        assertThat(p.hidden).isEqualTo(e["hidden"]!!.jsonPrimitive.int)
        assertThat(p.extended).isEqualTo(e["extended"]!!.jsonPrimitive.boolean)
        assertThat(p.phone).isEqualTo(e["phone"]!!.jsonPrimitive.int)
        assertThat(p.unreadable).isEqualTo(e["unreadable"]!!.jsonPrimitive.int)
    }

    @Test fun `the archive's plays, first ids and the SHA-256 of every id`() = runTest {
        val (_, archive, _) = golden(fixture())
        val e = expected["archive"]!!.jsonObject
        val ids = archive.sortedIds()
        assertThat(ids.size).isEqualTo(e["plays"]!!.jsonPrimitive.int)
        assertThat(ids.take(3)).isEqualTo(e["first"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertThat(sha256(ids.joinToString("\n"))).isEqualTo(e["sha256"]!!.jsonPrimitive.content)
    }

    @Test fun `the account zip first, then the extended one, gives the same archive`() = runTest {
        val archive = MemoryArchive()
        val importer = SpotifyImporter(archive, tmp.newFolder(), ZoneOffset.UTC)
        val (extended, account) = fixture()
        importer.commit(importer.prepare(listOf(account), NOW))
        importer.commit(importer.prepare(listOf(extended), NOW))

        val ids = archive.sortedIds()
        assertThat(ids.size).isEqualTo(expected["archive"]!!.jsonObject["plays"]!!.jsonPrimitive.int)
        assertThat(sha256(ids.joinToString("\n"))).isEqualTo(expected["archive"]!!.jsonObject["sha256"]!!.jsonPrimitive.content)
    }

    @Test fun `the same zips again add nothing`() = runTest {
        val (_, archive, importer) = golden(fixture())
        val before = archive.sortedIds()

        val again = importer.prepare(fixture(), NOW)
        val result = importer.commit(again)

        assertThat(again.preview.plays).isEqualTo(0)
        // Nothing to listen back to, but the playlists and likes can still come in (service.ts `choose`: a preview, not "nothing new")
        assertThat(again.preview.anything).isTrue()
        assertThat(again.preview.same).isGreaterThan(0)
        assertThat(result.written).isEqualTo(0)
        assertThat(result.dropped).isEqualTo(0)
        assertThat(archive.sortedIds()).isEqualTo(before)
    }

    @Test fun `the playlists and liked songs it brings`() = runTest {
        val (prep, _, _) = golden(fixture())
        val bundle = prep.bundle
        // expected.json: "Road trip" 9 and "Glass Harbor era" 8, likes 8
        assertThat(bundle.playlists.map { it.name to it.items.size }).isEqualTo(
            expected["playlists"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["items"]!!.jsonPrimitive.int },
        )
        assertThat(bundle.likes).hasSize(expected["likes"]!!.jsonPrimitive.int)
        assertThat(bundle.hidden).hasSize(expected["preview"]!!.jsonObject["hidden"]!!.jsonPrimitive.int)
        // Each playlist known by its place in the export, the likes in Spotify's order a millisecond apart
        val keys = SpotifyLibraryImport.playlistKeys(bundle.playlists)
        assertThat(keys).containsExactly("Road trip#0", "Glass Harbor era#0").inOrder()
        val content = SpotifyLibraryImport.content(keys.zip(bundle.playlists) { k, p -> ChosenPlaylist(k, p) }, bundle.likes, NOW)
        assertThat(content.likes.map { it.likedAt }).isEqualTo((0 until 8).map { NOW - it })
        assertThat(content.playlists.map { it.items.size }).containsExactly(9, 8).inOrder()
    }

    @Test fun `plays are written in batches of 5,000`() = runTest {
        val (_, archive, _) = golden(fixture())
        // 6,814 plays: one batch of 5,000 and one of the rest
        assertThat(archive.batches).isEqualTo(2)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        /** 2026-10-23T12:00:00Z. */
        const val NOW = 1_792_756_800_000L
    }
}
