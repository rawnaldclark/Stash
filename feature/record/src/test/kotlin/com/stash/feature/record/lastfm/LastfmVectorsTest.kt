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
import kotlinx.serialization.json.boolean
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
        assertThat(cases.size).isAtLeast(20)
        for (c in cases.map { it.jsonObject }) {
            val name = c.str("name")
            val got = LastfmHistory.read(c["body"]!!.jsonObject, c.int("page"), c.long("nowS"))
            val want = c["answer"]!!.jsonObject
            if (!want["ok"]!!.jsonPrimitive.boolean) {
                assertWithMessage(name).that(got).isNull()
                continue
            }
            assertWithMessage(name).that(got).isNotNull()
            assertWithMessage("$name: plays").that(got!!.plays).isEqualTo(want["plays"]!!.jsonArray.map { scrobble(it.jsonObject) })
            assertWithMessage("$name: page").that(got.page).isEqualTo(want.int("page"))
            assertWithMessage("$name: pages").that(got.pages).isEqualTo(want.int("pages"))
            assertWithMessage("$name: total").that(got.total).isEqualTo(want.int("total"))
        }
    }

    @Test fun `scrobbles are planned against the archive as the web plans them`() = runTest {
        val all = resource("plan.json").jsonObject
        for (c in all["plan"]!!.jsonArray.map { it.jsonObject }) {
            val name = c.str("name")
            val have = c["have"]!!.jsonArray.map { row(it.jsonObject) }
            val lengths = Scrobbles.knownLengthsOf(have)
            assertWithMessage("$name: lengths").that(lengths).isEqualTo(c["lengths"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long })
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

    @Test fun `whole runs ask, report, mark and keep what the web's do`() = runTest {
        for (c in resource("runs.json").jsonArray.map { it.jsonObject }) {
            val name = c.str("name")
            val store = MemoryScrobbleStore(c["have"]!!.jsonArray.map { row(it.jsonObject) }, (c["mark"] as? JsonObject)?.let(::mark))
            for ((i, st) in c["steps"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
                val at = "$name, step ${i + 1}"
                val calls = mutableListOf<Triple<Int, Long, Long?>>()
                val reader = fakeLastfm(st["scrobbles"]!!.jsonArray.map { scrobble(it.jsonObject) }, st["fails"] as? JsonArray, calls)
                val progress = mutableListOf<LastfmProgress>()
                var error: String? = null
                var got: LastfmMark? = null
                try {
                    got = ImportScrobbles.run(store, reader, st.str("user"), st.long("nowS"), { progress += it }, gapMs = 0, busyMs = 0)
                } catch (e: LastfmError) {
                    error = e.kind.name.lowercase()
                }
                assertWithMessage("$at: asks").that(calls).isEqualTo(
                    st["calls"]!!.jsonArray.map { it.jsonObject }.map { Triple(it.int("page"), it.long("to"), it["from"]?.jsonPrimitive?.longOrNull) },
                )
                assertWithMessage("$at: progress").that(progress).isEqualTo(
                    st["progress"]!!.jsonArray.map { it.jsonObject }.map { LastfmProgress(it.int("page"), it.int("pages"), it.int("added")) },
                )
                assertWithMessage("$at: error").that(error).isEqualTo((st["error"] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content)
                assertWithMessage("$at: mark").that(got).isEqualTo((st["mark"] as? JsonObject)?.let(::mark))
                assertWithMessage("$at: kept mark").that(store.mark).isEqualTo((st["stored"] as? JsonObject)?.let(::mark))
                assertWithMessage("$at: rows").that(store.rows.values.sortedBy { it.id }).isEqualTo(st["rows"]!!.jsonArray.map { row(it.jsonObject) })
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

    /** lastfm-vectors.ts `fakeLastfm`: the window below `to` (and from `from`), newest first, 200 a page; busy (or worse) a few times. */
    private fun fakeLastfm(all: List<Scrobble>, fails: JsonArray?, calls: MutableList<Triple<Int, Long, Long?>>): PageReader {
        val left = fails.orEmpty().map { it.jsonObject }.map { Triple(it.int("page"), intArrayOf(it.int("times")), it.str("kind")) }
        return PageReader { page, to, from ->
            calls += Triple(page, to, from?.takeIf { it != 0L })
            left.firstOrNull { it.first == page && it.second[0] > 0 }?.let { f ->
                f.second[0]--
                throw LastfmError(LastfmFailure.valueOf(f.third.uppercase()))
            }
            val window = all.filter { it.uts <= to && (from == null || from == 0L || it.uts >= from) }.sortedByDescending { it.uts }
            val pages = (window.size + 199) / 200
            HistoryPage(window.drop((page - 1) * 200).take(200), page, pages, window.size)
        }
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.int(k: String) = this[k]!!.jsonPrimitive.int
    private fun JsonObject.long(k: String) = this[k]!!.jsonPrimitive.long
    private fun JsonObject.opt(k: String) = (this[k] as? JsonElement)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun scrobble(o: JsonObject) = Scrobble(t = o.str("t"), a = o.str("a"), al = o.opt("al"), uts = o.long("uts"))

    private fun row(o: JsonObject) = ArchivePlay(
        id = o.str("id"), t = o.str("t"), a = o.str("a"), al = o.opt("al"), sid = o.opt("sid"), s = o.long("s"), ms = o.long("ms"), f = o.int("f"),
    )

    private fun mark(o: JsonObject) = LastfmMark(
        user = o.str("user"), to = o.long("to"), next = o.int("next"), pages = o.int("pages"), done = o["done"]!!.jsonPrimitive.boolean,
        newest = o.long("newest"), added = o.int("added"), covered = o.int("covered"), at = o.long("at"),
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

    override suspend fun add(rows: List<ArchivePlay>) = rows.forEach { this.rows[it.id] = it }

    override suspend fun deleteScrobbles(): Int {
        val ids = rows.values.filter { it.f and RecordFlags.LASTFM != 0 }.map { it.id }
        ids.forEach(rows::remove)
        return ids.size
    }
}
