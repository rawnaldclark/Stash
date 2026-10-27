package com.stash.feature.record.lastfm

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.archive.RecordSource
import com.stash.feature.record.archive.RecordSources
import com.stash.feature.record.archive.SourceCounts
import com.stash.feature.record.archive.SourcesOn
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Test

/**
 * The web player's own answers for Last.fm in Your record (`src/test/resources/lastfm/`, written by
 * `feature/record/scripts/lastfm-vectors.ts` from stash-player's own code: the run is in its header). Each case goes through the
 * port here, which must give the same, field for field. After a change to the web's rules, run the script again and port the
 * change until this passes.
 */
class LastfmVectorsTest {
    private fun resource(name: String): JsonElement =
        Json.parseToJsonElement(requireNotNull(javaClass.getResource("/lastfm/$name")) { name }.readText())

    @Test fun `Last fm's answer is read as the Worker reads it`() {
        val cases = resource("history.json").jsonArray
        assertThat(cases.size).isAtLeast(40)
        for (c in cases.map { it.jsonObject }) {
            val name = c.str("name")
            val got = LastfmHistory.read(c["body"]!!, c.int("page"), c.long("nowS"), signed = c["auth"]!!.jsonPrimitive.boolean)
            val want = c["answer"]!!.jsonObject
            if (!want["ok"]!!.jsonPrimitive.boolean) {
                val expected = if (want["reconnect"] != null) HistoryAnswer.Reconnect else HistoryAnswer.Retry
                assertWithMessage(name).that(got).isEqualTo(expected)
                continue
            }
            assertWithMessage(name).that(got).isInstanceOf(HistoryAnswer.Page::class.java)
            val p = (got as HistoryAnswer.Page).page
            assertWithMessage("$name: plays").that(p.plays).isEqualTo(want["plays"]!!.jsonArray.map { scrobble(it.jsonObject) })
            assertWithMessage("$name: page").that(p.page).isEqualTo(want.int("page"))
            assertWithMessage("$name: pages").that(p.pages).isEqualTo(want.int("pages"))
            assertWithMessage("$name: total").that(p.total).isEqualTo(want.int("total"))
        }
    }

    @Test fun `scrobbles are planned against the archive as the web plans them`() = runTest {
        val all = resource("plan.json").jsonObject
        for (c in all["plan"]!!.jsonArray.map { it.jsonObject }) {
            val name = c.str("name")
            val have = c["have"]!!.jsonArray.map { row(it.jsonObject) }
            val library = Scrobbles.libraryLengths(c["library"]!!.jsonArray.map { item(it.jsonObject) })
            assertWithMessage("$name: library").that(library).isEqualTo(longs(c["libraryLengths"]!!.jsonObject))
            val lengths = Scrobbles.bestLengths(Scrobbles.knownLengthsOf(have), library)
            assertWithMessage("$name: lengths").that(lengths).isEqualTo(longs(c["lengths"]!!.jsonObject))
            val plan = Scrobbles.plan(MemoryScrobbleArchive(have), c["scrobbles"]!!.jsonArray.map { scrobble(it.jsonObject) }, lengths)
            val want = c["plan"]!!.jsonObject
            assertWithMessage("$name: add").that(plan.add).isEqualTo(want["add"]!!.jsonArray.map { row(it.jsonObject) })
            assertWithMessage("$name: same").that(plan.same).isEqualTo(want.int("same"))
            assertWithMessage("$name: covered").that(plan.covered).isEqualTo(want.int("covered"))
        }
        for (c in all["covered"]!!.jsonArray.map { it.jsonObject }) {
            val rows = c["rows"]!!.jsonArray.map { row(it.jsonObject) }
            val plays = c["plays"]!!.jsonArray.map { it.jsonObject }.map { Triple(it.str("t"), it.long("s"), it.long("ms")) }
            assertWithMessage(c.str("name")).that(Scrobbles.coveredBy(rows, plays).toList())
                .isEqualTo(c["covered"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
    }

    @Test fun `whole runs ask, report, start, mark and keep what the web's do`() = runTest {
        val cases = resource("runs.json").jsonArray
        assertThat(cases.size).isAtLeast(20)
        for (c in cases.map { it.jsonObject }) {
            val name = c.str("name")
            val store = MemoryScrobbleStore(c["have"]!!.jsonArray.map { row(it.jsonObject) }, (c["mark"] as? JsonObject)?.let(::mark))
            for ((i, st) in c["steps"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
                val at = "$name, step ${i + 1}"
                val before = st["before"]!!.jsonObject
                store.add(before["add"]!!.jsonArray.map { row(it.jsonObject) })
                before["drop"]!!.jsonArray.forEach { store.rows.remove(it.jsonPrimitive.content) }
                val calls = mutableListOf<Triple<Int, Long, Long?>>()
                val reader = fakeLastfm(st["scrobbles"]!!.jsonArray.map { scrobble(it.jsonObject) }, st["fails"] as? JsonArray, calls)
                val progress = mutableListOf<LastfmProgress>()
                var error: String? = null
                var got: LastfmMark? = null
                var start: LastfmMark? = null
                val library = Scrobbles.libraryLengths(st["library"]!!.jsonArray.map { item(it.jsonObject) })
                try {
                    got = ImportScrobbles.run(store, reader, st.str("user"), st.long("nowS"), library, { progress += it }, { start = it }, gapMs = 0, busyMs = 0)
                } catch (e: LastfmError) {
                    error = e.kind.name.lowercase()
                }
                assertWithMessage("$at: asks").that(calls).isEqualTo(
                    st["calls"]!!.jsonArray.map { it.jsonObject }.map { Triple(it.int("page"), it.long("to"), it["from"]?.jsonPrimitive?.longOrNull) },
                )
                assertWithMessage("$at: progress").that(progress).isEqualTo(
                    st["progress"]!!.jsonArray.map { it.jsonObject }.map { LastfmProgress(it.int("page"), it.int("pages"), it.int("added")) },
                )
                assertWithMessage("$at: error").that(error).isEqualTo(st.opt("error"))
                assertWithMessage("$at: start").that(start).isEqualTo((st["start"] as? JsonObject)?.let(::mark))
                assertWithMessage("$at: mark").that(got).isEqualTo((st["mark"] as? JsonObject)?.let(::mark))
                assertWithMessage("$at: kept mark").that(store.mark).isEqualTo((st["stored"] as? JsonObject)?.let(::mark))
                assertWithMessage("$at: rows").that(store.rows.values.sortedBy { it.id }).isEqualTo(st["rows"]!!.jsonArray.map { row(it.jsonObject) })
            }
        }
    }

    @Test fun `a run starts where the web's startMark says`() {
        for (c in resource("start.json").jsonArray.map { it.jsonObject }) {
            val got = ImportScrobbles.startMark((c["prev"] as? JsonObject)?.let(::mark), c.str("user"), c.long("nowS"), c.int("held"))
            assertWithMessage(c.str("name")).that(got).isEqualTo(mark(c["out"]!!.jsonObject))
        }
    }

    @Test fun `lengths are read, chosen and floored as the web's are`() {
        val all = resource("lengths.json").jsonObject
        for (c in all["library"]!!.jsonArray.map { it.jsonObject }) {
            assertWithMessage(c.str("name")).that(Scrobbles.libraryLengths(c["items"]!!.jsonArray.map { item(it.jsonObject) })).isEqualTo(longs(c["out"]!!.jsonObject))
        }
        for (c in all["best"]!!.jsonArray.map { it.jsonObject }) {
            val library = (c["library"] as? JsonObject)?.let(::longs)
            assertWithMessage(c.str("name")).that(Scrobbles.bestLengths(longs(c["archive"]!!.jsonObject), library)).isEqualTo(longs(c["out"]!!.jsonObject))
        }
        for (c in all["ms"]!!.jsonArray.map { it.jsonObject }) {
            val length = c["length"]!!.jsonPrimitive.double
            assertWithMessage("scrobbleMs($length)").that(Scrobbles.scrobbleMs(length)).isEqualTo(c.long("ms"))
        }
    }

    @Test fun `the lengths pass plans as the web's planLengths does`() {
        for (c in resource("lengths.json").jsonObject["plan"]!!.jsonArray.map { it.jsonObject }) {
            val name = c.str("name")
            val rows = c["rows"]!!.jsonArray.map { row(it.jsonObject) }
            val guessed = Lengths.guessedBySong(MemoryLengthsStore.spellings(rows))
            val wantGuessed = c["guessed"]!!.jsonObject.mapValues { (_, ids) -> ids.jsonArray.size }
            assertWithMessage("$name: guessed").that(guessed.mapValues { (_, s) -> s.sumOf { it.n } }).isEqualTo(wantGuessed)
            val plan = Lengths.planLengths(guessed, longs(c["known"]!!.jsonObject), longs(c["none"]!!.jsonObject), c.long("nowS"), longs(c["failed"]!!.jsonObject))
            val want = c["plan"]!!.jsonObject
            // The web's fill is the rows themselves: here, each song's key, the ms its rows will play, and how many there are
            val wantFill = want["fill"]!!.jsonArray.map { row(it.jsonObject) }.groupBy { Scrobbles.songKey(it.t, it.a) }
                .mapValues { (_, r) -> r.first().ms to r.size }
            assertWithMessage("$name: fill").that(plan.fill.associate { it.key to (it.ms to it.rows) }).isEqualTo(wantFill)
            assertWithMessage("$name: fill flags").that(want["fill"]!!.jsonArray.all { it.jsonObject.int("f") and RecordFlags.ESTIMATED == 0 }).isTrue()
            assertWithMessage("$name: asks").that(plan.asks).isEqualTo(
                want["asks"]!!.jsonArray.map { it.jsonObject }.map { LengthAsk(it.str("key"), it.str("t"), it.str("a"), it.int("plays")) },
            )
            assertWithMessage("$name: none").that(plan.none).isEqualTo(longs(want["none"]!!.jsonObject))
            assertWithMessage("$name: failed").that(plan.failed).isEqualTo(longs(want["failed"]!!.jsonObject))
        }
    }

    @Test fun `lengths pass slices ask, write, save and stop as the web's fillLengths does`() = runTest {
        val cases = resource("lengths.json").jsonObject["fill"]!!.jsonArray
        assertThat(cases.size).isAtLeast(10)
        for (c in cases.map { it.jsonObject }) {
            val name = c.str("name")
            val store = MemoryLengthsStore(c["have"]!!.jsonArray.map { row(it.jsonObject) }, (c["mark"] as? JsonObject)?.let(::lengthsMark))
            for ((i, sl) in c["slices"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
                val at = "$name, slice ${i + 1}"
                store.saves.clear()
                val asked = mutableListOf<String>()
                val lengths = sl["lengths"]!!.jsonObject
                val fails = sl["fails"]!!.jsonArray.map { it.jsonObject }
                val hit = (sl["hit"] as? JsonPrimitive)?.boolean ?: true
                val look = LengthReader { artist, track ->
                    asked += "$track / $artist"
                    val f = fails.firstOrNull { f -> (f["at"] as? JsonArray)?.any { it.jsonPrimitive.int == asked.size } == true || f.opt("track") == track }
                    if (f != null) throw LastfmError(LastfmFailure.valueOf(f.str("kind").uppercase()))
                    // As the web's reader reads the Worker's answer: a length above 0 and at most 6 h, else none
                    val v = (lengths[track] as? JsonPrimitive)?.doubleOrNull
                    LengthAnswer(v?.takeIf { it > 0 && it <= Scrobbles.MAX_SONG_MS }?.toLong(), hit)
                }
                val library = Scrobbles.libraryLengths(sl["library"]!!.jsonArray.map { item(it.jsonObject) })
                val run = Lengths.fill(store, look, sl.long("nowS"), library, max = (sl["max"] as? JsonPrimitive)?.int ?: Lengths.SLICE, gapMs = 0, busyMs = 0)
                val want = sl["run"]!!.jsonObject
                val wantStopped = want.opt("stopped")?.let { LastfmFailure.valueOf(it.uppercase()) }
                assertWithMessage("$at: run").that(run).isEqualTo(LengthsRun(want.int("rows"), want.int("asked"), want.int("left"), wantStopped))
                assertWithMessage("$at: asks").that(asked).isEqualTo(sl["asked"]!!.jsonArray.map { it.jsonPrimitive.content })
                assertWithMessage("$at: saves").that(store.saves).isEqualTo(sl["saves"]!!.jsonArray.map { lengthsMark(it.jsonObject) })
                assertWithMessage("$at: rows").that(store.rows.values.sortedBy { it.id }).isEqualTo(sl["rows"]!!.jsonArray.map { row(it.jsonObject) })
            }
        }
    }

    @Test fun `the switches follow the web's rules for every choice`() {
        val all = resource("sources.json").jsonObject
        for (r in all["read"]!!.jsonArray.map { it.jsonObject }) {
            val raw = (r["raw"] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            assertWithMessage("read $raw").that(RecordSources.read(raw)).isEqualTo(on(r["on"]!!.jsonObject))
        }
        val rules = all["rules"]!!.jsonArray.map { it.jsonObject }
        assertThat(rules).hasSize(48)
        for (r in rules) {
            val counts = (r["counts"] as? JsonObject)?.let { SourceCounts(it.int("spotify"), it.int("stash"), it.int("lastfm")) }
            val choice = on(r["on"]!!.jsonObject)
            val at = "$counts $choice"
            assertWithMessage("$at: present").that(RecordSources.present(counts).map { it.key }).isEqualTo(r["present"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertWithMessage("$at: shown").that(RecordSources.shown(choice, counts)).isEqualTo(on(r["shown"]!!.jsonObject))
            val toggles = r["toggle"]!!.jsonObject
            for (s in RecordSource.entries) {
                val want = toggles[s.key]?.takeIf { it !is JsonNull }?.jsonObject?.let(::on)
                assertWithMessage("$at: toggle $s").that(RecordSources.toggle(choice, s, counts)).isEqualTo(want)
            }
        }
        // As the web keeps it, and read back the same
        val choice = SourcesOn(spotify = false, stash = true, lastfm = false)
        assertThat(RecordSources.read(RecordSources.write(choice))).isEqualTo(choice)
    }

    // ── the web's fakes, ported ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * lastfm-vectors.ts `fakeLastfm`: the window below `to` (and from `from`), newest first, 200 a page; for a few asks of one
     * page busy (or worse), or empty saying it has `pages` (`topup`: only the asks with a `from`).
     */
    private fun fakeLastfm(all: List<Scrobble>, fails: JsonArray?, calls: MutableList<Triple<Int, Long, Long?>>): PageReader {
        class Odd(val page: Int, var times: Int, val topup: Boolean?, val kind: String?, val empty: JsonObject?)
        val left = fails.orEmpty().map { it.jsonObject }.map {
            Odd(it.int("page"), it.int("times"), (it["topup"] as? JsonPrimitive)?.boolean, it.opt("kind"), it["empty"] as? JsonObject)
        }
        return PageReader { page, to, from ->
            val f0 = from?.takeIf { it != 0L }
            calls += Triple(page, to, f0)
            left.firstOrNull { it.page == page && it.times > 0 && (it.topup == null || it.topup == (f0 != null)) }?.let { f ->
                f.times--
                if (f.kind != null) throw LastfmError(LastfmFailure.valueOf(f.kind.uppercase()))
                return@PageReader HistoryPage(emptyList(), page, f.empty!!.int("pages"), f.empty.int("total"))
            }
            val window = all.filter { it.uts <= to && (f0 == null || it.uts >= f0) }.sortedByDescending { it.uts }
            val pages = (window.size + 199) / 200
            HistoryPage(window.drop((page - 1) * 200).take(200), page, pages, window.size)
        }
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.int(k: String) = this[k]!!.jsonPrimitive.int
    private fun JsonObject.long(k: String) = this[k]!!.jsonPrimitive.long
    private fun JsonObject.opt(k: String) = (this[k] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun longs(o: JsonObject): Map<String, Long> = o.mapValues { it.value.jsonPrimitive.long }

    private fun scrobble(o: JsonObject) = Scrobble(t = o.str("t"), a = o.str("a"), al = o.opt("al"), uts = o.long("uts"))

    /** A library song as plays.ts `libraryLengths` reads one: its length as JavaScript reads a number (none when it isn't one). */
    private fun item(o: JsonObject) = Triple(o.str("title"), o.str("artist"), (o["durationMs"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull)

    private fun row(o: JsonObject) = ArchivePlay(
        id = o.str("id"), t = o.str("t"), a = o.str("a"), al = o.opt("al"), sid = o.opt("sid"), s = o.long("s"), ms = o.long("ms"), f = o.int("f"),
    )

    private fun mark(o: JsonObject) = LastfmMark(
        user = o.str("user"), to = o.long("to"), next = o.int("next"), pages = o.int("pages"), done = o["done"]!!.jsonPrimitive.boolean,
        newest = o.long("newest"), added = o.int("added"), covered = o.int("covered"), at = o.long("at"),
        base = (o["base"] as? JsonPrimitive)?.int,
    )

    private fun lengthsMark(o: JsonObject) = LengthsMark(
        none = longs(o["none"]!!.jsonObject),
        failed = (o["failed"] as? JsonObject)?.let(::longs).orEmpty(),
        timed = (o["timed"] as? JsonPrimitive)?.int ?: 0,
        at = o.long("at"),
    )

    private fun on(o: JsonObject) = SourcesOn(o["spotify"]!!.jsonPrimitive.boolean, o["stash"]!!.jsonPrimitive.boolean, o["lastfm"]!!.jsonPrimitive.boolean)
}

/** The web's `memoryArchive` for the import: rows by id (a write of a kept id replaces it), and the mark. */
internal class MemoryScrobbleStore(have: List<ArchivePlay> = emptyList(), var mark: LastfmMark? = null) : ScrobbleStore {
    val rows = LinkedHashMap<String, ArchivePlay>().apply { have.forEach { put(it.id, it) } }

    override suspend fun kept(ids: List<String>): Set<String> = ids.filterTo(HashSet()) { it in rows }

    override suspend fun playsOf(titleFold: String): List<PlaySpan> =
        rows.values.filter { it.f and RecordFlags.LASTFM == 0 && SongKey.fold(it.t) == titleFold }.map { PlaySpan(it.s, it.s + it.ms) }

    override suspend fun mark(): LastfmMark? = mark

    override suspend fun setMark(mark: LastfmMark) {
        this.mark = mark
    }

    override suspend fun clearMark() {
        mark = null
    }

    override suspend fun knownLengths(): Map<String, Long> = Scrobbles.knownLengthsOf(rows.values)

    override suspend fun held(): Int = rows.values.count { it.f and RecordFlags.LASTFM != 0 }

    override suspend fun add(rows: List<ArchivePlay>) = rows.forEach { this.rows[it.id] = it }

    override suspend fun deleteScrobbles(): Int {
        val ids = rows.values.filter { it.f and RecordFlags.LASTFM != 0 }.map { it.id }
        ids.forEach(rows::remove)
        return ids.size
    }
}

/** The web's `memoryArchive` for the lengths pass: rows by id and the `lengths` mark, every save of it kept ([saves]). */
internal class MemoryLengthsStore(have: List<ArchivePlay> = emptyList(), var mark: LengthsMark? = null) : LengthsStore {
    val rows = LinkedHashMap<String, ArchivePlay>().apply { have.forEach { put(it.id, it) } }
    val saves = mutableListOf<LengthsMark>()

    override suspend fun guessed(): List<GuessedSpelling> = spellings(rows.values)

    override suspend fun knownLengths(): Map<String, Long> = Scrobbles.knownLengthsOf(rows.values)

    override suspend fun mark(): LengthsMark? = mark

    override suspend fun setMark(mark: LengthsMark) {
        this.mark = mark
        saves += mark
    }

    override suspend fun time(spellings: List<GuessedSpelling>, ms: Long): Int {
        val words = spellings.mapTo(HashSet()) { it.t to it.a }
        var n = 0
        for (e in rows.entries) {
            val r = e.value
            if (r.f and GUESSED != GUESSED || (r.t to r.a) !in words) continue
            e.setValue(r.copy(ms = ms, f = r.f and RecordFlags.ESTIMATED.inv()))
            n++
        }
        return n
    }

    companion object {
        private const val GUESSED = RecordFlags.LASTFM or RecordFlags.ESTIMATED

        /** The guessed rows by spelling, as the database groups them ([com.stash.core.data.db.dao.RecordPlayDao.guessed]). */
        fun spellings(rows: Iterable<ArchivePlay>): List<GuessedSpelling> =
            rows.filter { it.f and GUESSED == GUESSED }.groupingBy { it.t to it.a }.eachCount().map { (w, n) -> GuessedSpelling(w.first, w.second, n) }
    }
}
