package com.stash.feature.record.importer

import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.data.record.ChosenPlaylist
import com.stash.core.data.record.ExportSong
import com.stash.core.data.record.SpotifyLibraryImport
import com.stash.feature.record.imports.RecordLibrary
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
 * - The library half (`library-*`): two playlist files (`name#n` runs across both), tracks and local files (`%`, `+`, a `%`
 *   that won't decode), episodes, audiobooks and null tracks, every `addedDate` V8's ISO reading takes or refuses, a name over
 *   100 units cut through a surrogate pair, Liked Songs and hidden songs (one both), missing and odd fields; a chosen subset
 *   (its `name#n` still counted over the whole export), none chosen, in a zip after an earlier import's hidden songs, likes
 *   alone. Each compares the export as read (`bundle`), what the commit brings (`commit`: each playlist's key, the likes, the
 *   record's counts), the hidden set kept (song keys, sorted) and the library's content (`libraryData`).
 *
 * The web's `alreadyIn` (the preview's "Already in your library") has its own cases: [AlreadyInVectorsTest].
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

        // What an earlier import kept (its hidden songs, its counts: everything chosen)
        val earlier = before.takeIf { it.isNotEmpty() }?.let { importer.read(it, now) }
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

        library(name, c, expected, prep.bundle, earlier)
    }

    /** A song as the vectors write it: every field, null when it has none. */
    private fun song(o: JsonObject) = ExportSong(
        title = o["title"]!!.jsonPrimitive.content,
        artist = o["artist"]!!.jsonPrimitive.content,
        album = o["album"].text(),
        sid = o["sid"].text(),
        addedAt = o["addedAt"]?.takeIf { it != JsonNull }?.jsonPrimitive?.long,
    )

    private fun kotlinx.serialization.json.JsonElement?.text(): String? = this?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    /** The library half: the export as read, what the commit brings, the hidden set and `libraryData`, against the web's. */
    private fun library(name: String, c: JsonObject, expected: JsonObject, bundle: ExportBundle, earlier: ExportBundle?) {
        val b = expected["bundle"]!!.jsonObject
        val playlists = b["playlists"]!!.jsonArray.map { it.jsonObject }
        assertWithMessage("$name: playlists read").that(bundle.playlists.map { listOf(it.name, it.description) })
            .isEqualTo(playlists.map { listOf(it["name"]!!.jsonPrimitive.content, it["description"].text()) })
        for ((i, p) in playlists.withIndex()) {
            val want = p["items"]!!.jsonArray.map { song(it.jsonObject) }
            for ((k, w) in want.withIndex()) assertWithMessage("$name: playlist $i song $k").that(bundle.playlists[i].items.getOrNull(k)).isEqualTo(w)
            assertWithMessage("$name: playlist $i").that(bundle.playlists[i].items).isEqualTo(want)
        }
        assertWithMessage("$name: likes read").that(bundle.likes).isEqualTo(b["likes"]!!.jsonArray.map { song(it.jsonObject) })
        assertWithMessage("$name: hidden read").that(bundle.hidden).isEqualTo(b["hidden"]!!.jsonArray.map { song(it.jsonObject) })

        // The commit's choice (the case's `choose`; none: everything)
        val choose = c["choose"] as? JsonObject
        val choice = ImportChoice(
            playlists = (choose?.get("playlists") as? JsonArray)?.mapTo(LinkedHashSet()) { it.jsonPrimitive.int },
            likes = choose?.get("likes")?.jsonPrimitive?.boolean ?: true,
        )
        val keys = SpotifyLibraryImport.playlistKeys(bundle.playlists)
        val chosen = bundle.playlists.indices.filter(choice::takes).map { ChosenPlaylist(keys[it], bundle.playlists[it]) }
        val likes = if (choice.likes) bundle.likes else emptyList()
        val commit = expected["commit"]!!.jsonObject
        assertWithMessage("$name: chosen").that(chosen.map { listOf(it.key, it.playlist.name, it.playlist.items.size) })
            .isEqualTo(commit["playlists"]!!.jsonArray.map { listOf(it.jsonObject["key"]!!.jsonPrimitive.content, it.jsonObject["name"]!!.jsonPrimitive.content, it.jsonObject["songs"]!!.jsonPrimitive.int) })
        assertWithMessage("$name: likes chosen").that(likes).isEqualTo(commit["likes"]!!.jsonArray.map { song(it.jsonObject) })
        // The record counts what each import brought (an earlier one brought everything)
        val record = commit["record"]!!.jsonObject
        assertWithMessage("$name: record").that(
            listOf(chosen.size + (earlier?.playlists?.size ?: 0), likes.size + (earlier?.likes?.size ?: 0)),
        ).isEqualTo(listOf(record["playlists"]!!.jsonPrimitive.int, record["likes"]!!.jsonPrimitive.int))

        // The hidden set: every import's, by song key, sorted by UTF-16 units
        val hidden = (RecordLibrary.hiddenRows(earlier?.hidden.orEmpty()) + RecordLibrary.hiddenRows(bundle.hidden)).map { it.key }.distinct().sorted()
        assertWithMessage("$name: hidden").that(hidden).isEqualTo(expected["hidden"]!!.jsonArray.map { it.jsonPrimitive.content })

        // libraryData: each playlist under its key, its name cut to 100 units, its songs' added times; the likes a millisecond apart
        val lib = expected["library"]!!.jsonObject
        val content = SpotifyLibraryImport.content(chosen, likes, now)
        assertWithMessage("$name: library keys").that(content.playlists.map { it.id }).isEqualTo(lib["keys"]!!.jsonArray.map { it.jsonPrimitive.content })
        val want = lib["playlists"]!!.jsonArray.map { it.jsonObject }
        assertWithMessage("$name: library playlists").that(content.playlists.map { p -> listOf(p.id, p.name) + p.items.map { listOf(it.title, it.artist, it.album, it.addedAt) } })
            .isEqualTo(want.map { p -> listOf(p["key"]!!.jsonPrimitive.content, p["name"]!!.jsonPrimitive.content) + p["items"]!!.jsonArray.map { i -> i.jsonObject.let { listOf(it["title"]!!.jsonPrimitive.content, it["artist"]!!.jsonPrimitive.content, it["album"].text(), it["addedAt"]!!.jsonPrimitive.long) } } })
        assertWithMessage("$name: library likes").that(content.likes.map { listOf(it.song.title, it.song.artist, it.song.album, it.likedAt) })
            .isEqualTo(lib["likes"]!!.jsonArray.map { l -> l.jsonObject.let { listOf(it["title"]!!.jsonPrimitive.content, it["artist"]!!.jsonPrimitive.content, it["album"].text(), it["likedAt"]!!.jsonPrimitive.long) } })
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

    @Test fun `the library - playlists across two files, local files, dates, a long name, likes and hidden songs`() = check("library-all")

    @Test fun `the library - a chosen subset keeps each playlist's place in the whole export`() = check("library-chosen")

    @Test fun `the library - no playlist chosen, the likes on`() = check("library-none-chosen")

    @Test fun `the library - in a zip, after an earlier import's hidden songs`() = check("library-in-a-zip")

    @Test fun `the library - liked songs alone`() = check("library-only-likes")

    @Test fun `every vector is checked`() {
        val names = index["cases"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        val tested = javaClass.declaredMethods.mapNotNull { it.getAnnotation(Test::class.java)?.let { _ -> it.name } }
        assertWithMessage("vectors").that(names.size).isEqualTo(tested.size - 1)
    }
}
