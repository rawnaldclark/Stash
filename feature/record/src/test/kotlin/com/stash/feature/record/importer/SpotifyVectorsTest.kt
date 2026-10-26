package com.stash.feature.record.importer

import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
import java.time.ZoneOffset

/**
 * The web's importer and this port, rule by rule: small exports crafted around each of the web's rules
 * (`feature/record/scripts/spotify-vectors.ts` made them and ran them through stash-player's own `openFiles`, `prepare` and
 * `commit`, with TZ=UTC), read here and compared field by field: the files read, what couldn't be read, the preview, and every
 * archive row (id, words, album, Spotify id, start, time played, flags).
 *
 * - `extended-rows`: no song, incognito, times (V8's ISO forms, 2006, two days on), times played (half up, minus nothing, six
 *   hours), reasons and flags, uris, control characters, JavaScript's trim, the 300-unit cut through a surrogate pair, the
 *   identity fold, duplicate keys, escapes, rows that aren't objects, the same row twice.
 * - `account-rows`: `Date.UTC`'s month and day overflow, the minute pattern, the year 99.
 * - Account against Extended in one import and in either order across two (twins by title and minute, the Extended play kept).
 * - `nested-zips`: zips three deep (one stored), a fourth left alone, a broken one, folders, backslashes, files to ignore.
 * - `broken-files`: a file cut short (its rows roll back), JSON that isn't a list, a BOM, trailing text, a trailing comma, and
 *   playlists and a library with odd entries; `not-an-export`, `only-broken`; a newer export, and the same export again.
 */
class SpotifyVectorsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root = File(requireNotNull(javaClass.getResource("/spotify-export/vectors/cases.json")).toURI()).parentFile
    private val index = Json.parseToJsonElement(File(root, "cases.json").readText()).jsonObject
    private val now = index["now"]!!.jsonPrimitive.long

    private fun case(name: String): JsonObject =
        index["cases"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }

    private fun picked(case: String, sub: String, names: JsonArray?): List<PickedFile> =
        names.orEmpty().map { n -> n.jsonPrimitive.content.let { PickedFile(it, File(root, "$case/$sub/$it")) } }

    private fun check(name: String) = runTest {
        val c = case(name)
        val archive = MemoryArchive(pageSize = 3)
        val importer = SpotifyImporter(archive, tmp.newFolder(), ZoneOffset.UTC)
        val before = picked(name, "before", c["before"] as? JsonArray)
        if (before.isNotEmpty()) importer.commit(importer.prepare(before, now))
        val expected = c["expected"]!!.jsonObject
        val files = picked(name, "files", c["files"]!!.jsonArray)

        expected["error"]?.let { err ->
            val thrown = runCatching { importer.prepare(files, now) }.exceptionOrNull()
            assertWithMessage("$name: error").that(thrown?.javaClass?.simpleName).isEqualTo(err.jsonPrimitive.content)
            return@runTest
        }

        val prep = importer.prepare(files, now)
        importer.commit(prep)

        val f = expected["files"]!!.jsonObject
        val counts = prep.bundle.files
        assertWithMessage("$name: files").that(
            listOf(counts.extended, counts.account, counts.playlists, counts.library, counts.stash, counts.ignored),
        ).isEqualTo(listOf("extended", "account", "playlists", "library", "stash", "ignored").map { f[it]!!.jsonPrimitive.int })
        assertWithMessage("$name: unreadable").that(prep.bundle.unreadable).isEqualTo(expected["unreadable"]!!.jsonPrimitive.int)

        val e = expected["preview"]!!.jsonObject
        val p = prep.preview
        assertWithMessage("$name: preview").that(
            listOf(p.plays, p.same, p.overlap, p.listenedMs, p.first, p.last, p.years, p.likes, p.hidden, p.extended, p.unreadable),
        ).isEqualTo(
            listOf(
                e["plays"]!!.jsonPrimitive.int, e["same"]!!.jsonPrimitive.int, e["overlap"]!!.jsonPrimitive.int,
                e["listenedMs"]!!.jsonPrimitive.long, e["first"]!!.jsonPrimitive.long, e["last"]!!.jsonPrimitive.long,
                e["years"]!!.jsonPrimitive.int, e["likes"]!!.jsonPrimitive.int, e["hidden"]!!.jsonPrimitive.int,
                e["extended"]!!.jsonPrimitive.boolean, e["unreadable"]!!.jsonPrimitive.int,
            ),
        )
        assertWithMessage("$name: playlists").that(p.playlists).isEqualTo(
            e["playlists"]!!.jsonArray.map { ExportPlaylistCount(it.jsonObject["name"]!!.jsonPrimitive.content, it.jsonObject["songs"]!!.jsonPrimitive.int) },
        )

        val rows = archive.plays.values.sortedBy { it.id }.map { listOf(it.id, it.t, it.a, it.al, it.sid, it.s, it.ms, it.f) }
        val want = expected["rows"]!!.jsonArray.map { r ->
            val a = r.jsonArray
            fun str(i: Int) = a[i].takeIf { it != JsonNull }?.jsonPrimitive?.content
            listOf(str(0), str(1), str(2), str(3), str(4), a[5].jsonPrimitive.long, a[6].jsonPrimitive.long, a[7].jsonPrimitive.int)
        }
        // Row by row first, so a difference names the play
        for ((i, w) in want.withIndex()) assertWithMessage("$name: row $i").that(rows.getOrNull(i)).isEqualTo(w)
        assertWithMessage("$name: rows").that(rows).isEqualTo(want)
    }

    @Test fun `extended rows`() = check("extended-rows")

    @Test fun `account rows`() = check("account-rows")

    @Test fun `account and extended in one import`() = check("account-and-extended")

    @Test fun `account data, then extended history`() = check("account-then-extended")

    @Test fun `extended history, then account data`() = check("extended-then-account")

    @Test fun `zips inside zips`() = check("nested-zips")

    @Test fun `broken and odd files`() = check("broken-files")

    @Test fun `files that aren't an export`() = check("not-an-export")

    @Test fun `only a broken file`() = check("only-broken")

    @Test fun `a newer export adds only what is new`() = check("a-newer-export")

    @Test fun `the same export again adds nothing`() = check("the-same-again")

    @Test fun `every vector is checked`() {
        val names = index["cases"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        val tested = javaClass.declaredMethods.mapNotNull { it.getAnnotation(Test::class.java)?.let { _ -> it.name } }
        assertWithMessage("vectors").that(names.size).isEqualTo(tested.size - 1)
    }
}
