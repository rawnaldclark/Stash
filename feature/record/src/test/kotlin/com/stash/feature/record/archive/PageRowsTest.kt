package com.stash.feature.record.archive

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.card.RecordCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.time.ZoneId

/**
 * The Home card's numbers against the record's own (stash-on-record 9dff865, `tests/stash/cardParity.test.ts`): the fork ran
 * its real pipeline (archiveReceiver, fromArchive, stack, summarize, topItems) on the rows in `card-parity.rows.json` and wrote
 * what it got to `card-parity.expect.json`. The card's code must get the same from the same rows, field for field.
 */
class PageRowsTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/parity/$name")) { name }.readText()

    private val rows: List<ArchivePlay> = Json.parseToJsonElement(resource("card-parity.rows.json")).jsonArray.map { e ->
        val o = e.jsonObject
        fun str(k: String) = o[k]?.jsonPrimitive?.content
        ArchivePlay(
            id = str("id")!!,
            t = str("t")!!,
            a = str("a")!!,
            al = str("al"),
            sid = str("sid"),
            s = o["s"]!!.jsonPrimitive.long,
            ms = o["ms"]!!.jsonPrimitive.long,
            f = o["f"]!!.jsonPrimitive.int,
        )
    }
    private val expect: JsonObject = Json.parseToJsonElement(resource("card-parity.expect.json")).jsonObject

    private fun ranked(key: String) = expect[key]!!.jsonArray.map { it.jsonObject }.map {
        RankedArtist(it["label"]!!.jsonPrimitive.content, it["ms"]!!.jsonPrimitive.long)
    }

    @Test fun `the rows the record keeps, and those it leaves out`() {
        val b = RecordSummaryBuilder()
        val leftOut = rows.indices.filter { !b.add(rows[it]) }
        assertThat(leftOut).isEqualTo(expect["leftOutRows"]!!.jsonArray.map { it.jsonPrimitive.int })
        val s = b.build()
        assertThat(s.plays).isEqualTo(expect["plays"]!!.jsonPrimitive.int)
        assertThat(s.streams).isEqualTo(expect["streams"]!!.jsonPrimitive.int)
        assertThat(s.totalMs).isEqualTo(expect["totalMs"]!!.jsonPrimitive.long)
        assertThat(s.first).isEqualTo(expect["first"]!!.jsonPrimitive.long)
    }

    @Test fun `the card's numbers are the record's`() {
        val b = RecordSummaryBuilder()
        rows.forEach(b::add)
        val s = b.build()
        val view = RecordCard.viewOf(s, emptyList())
        // the hero's number (Math.round, half up: the fixture's hours sit on a half)
        assertThat(RecordCard.hours(view.listenedMs)).isEqualTo(expect["hours"]!!.jsonPrimitive.long)
        // the card's plays are the record's streams
        assertThat(view.plays).isEqualTo(expect["streams"]!!.jsonPrimitive.int)
        for (zone in listOf("UTC", "Pacific/Kiritimati", "Pacific/Pago_Pago")) {
            assertThat(RecordCard.sinceYear(view, ZoneId.of(zone))).isEqualTo(expect["sinceYear"]!!.jsonPrimitive.int)
        }
        assertThat(s.topArtists).isEqualTo(ranked("topArtists"))
        assertThat(view.topArtist).isEqualTo(ranked("topArtists").first().name)
        assertThat(view.artists).isEqualTo(ranked("topArtists").map { it.name })
    }

    @Test fun `the whole ranking, ties and all`() {
        val b = RecordSummaryBuilder()
        rows.forEach(b::add)
        val all = ranked("artists")
        assertThat(b.build(top = all.size).topArtists).isEqualTo(all)
    }

    @Test fun `the order the rows come in changes nothing`() {
        val forward = RecordSummaryBuilder().also { b -> rows.forEach(b::add) }.build(top = 100)
        // the same rows sent another way round, the first of each id still first (it's the one the page keeps)
        val firsts = LinkedHashMap<String, ArchivePlay>()
        rows.forEach { firsts.putIfAbsent(it.id, it) }
        val reversed = RecordSummaryBuilder().also { b -> firsts.values.reversed().forEach(b::add) }.build(top = 100)
        assertThat(reversed).isEqualTo(forward)
    }

    // ── The rules one at a time ────────────────────────────────────────────────────────────────────────────────────────

    private fun row(id: String, a: String, s: Long, ms: Long, t: String = "T", f: Int = 256) = ArchivePlay(id, t, a, null, null, s, ms, f)

    @Test fun `a row the page can't take is left out`() {
        val b = RecordSummaryBuilder()
        assertThat(b.add(row("1", "A", 1, 0))).isTrue()
        assertThat(b.add(row("2", "A", 1, PageRows.MAX_MS))).isTrue()
        assertThat(b.add(row("3", "A", 1, PageRows.MAX_MS + 1))).isFalse()
        assertThat(b.add(row("4", "A", 1, -1))).isFalse()
        assertThat(b.add(row("5", "A", 0, 1))).isFalse()
        assertThat(b.add(row("6", "", 1, 1))).isFalse()
        assertThat(b.add(row("7", "A", 1, 1, t = ""))).isFalse()
        assertThat(b.add(row("8", "a".repeat(301), 1, 1))).isFalse()
        assertThat(b.add(row("9", "a".repeat(300), 1, 1))).isTrue()
        assertThat(b.add(row("10", "A", 1, 1, f = -1))).isFalse()
        assertThat(b.add(row("11", "A", 1, 1).copy(al = "x".repeat(301)))).isFalse()
        // an id already kept, whatever its words
        assertThat(b.add(row("1", "B", 99, 99))).isFalse()
        assertThat(b.build().plays).isEqualTo(3)
    }

    @Test fun `streams are 30 s or more, hours are all the time`() {
        val b = RecordSummaryBuilder()
        b.add(row("1", "A", 10, 29_999))
        b.add(row("2", "A", 20, 30_000))
        val s = b.build()
        assertThat(listOf(s.plays, s.streams)).containsExactly(2, 1).inOrder()
        assertThat(s.totalMs).isEqualTo(59_999)
        assertThat(s.first).isEqualTo(10)
        assertThat(RecordSummaryBuilder().build().first).isNull()
    }

    @Test fun `an artist is the name as written, never folded or split, ranked by time, ties by earliest end then id`() {
        val b = RecordSummaryBuilder()
        b.add(row("b", "Bj\u00f6rk", 100, 50))
        b.add(row("c", "Bjo\u0308rk", 100, 50))
        b.add(row("d", "bjork", 50, 100))
        b.add(row("a", "Kendrick Lamar, SZA", 300, 100))
        b.add(row("e", "Kendrick Lamar", 10, 10))
        val ranked = b.build(top = 10).topArtists.map { it.name }
        // 100 ms each: "bjork" ends first (150, against 400). 50 ms each: the two Bj\u00f6rks end together, so the smaller id ("b")
        assertThat(ranked).containsExactly("bjork", "Kendrick Lamar, SZA", "Bj\u00f6rk", "Bjo\u0308rk", "Kendrick Lamar").inOrder()
    }

    @Test fun `a tie on time and on end goes to the smaller id, in UTF-16 order`() {
        val b = RecordSummaryBuilder()
        b.add(row("z|1", "Later", 100, 100))
        b.add(row("Z|1", "Upper", 100, 100))
        // "Z" (U+005A) sorts before "z" (U+007A)
        assertThat(b.build().topArtists.map { it.name }).containsExactly("Upper", "Later").inOrder()
    }
}
