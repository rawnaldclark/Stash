package com.stash.feature.record.archive

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.feature.record.card.CardData
import com.stash.feature.record.card.RecordCardSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Every source stacked, a listen counted once, and the switches (spec §7): the page's own fixture (stash-on-record
 * `src/stash/fixtures/card-parity-sources.*` at 2e5d17a, byte for byte in `src/test/resources/parity/`), made by its `stack` with
 * the phone's plays covering scrobbles, `countSources`, and the record for each of the eight switch states.
 *
 * The app must leave out exactly the scrobbles the page's rule leaves out ([RecordArchive], since the page stacks nothing in the
 * app), count each source as the page does ([RecordSources.Counter]), and its Home card must show the record's numbers for every
 * switch state ([RecordCardSource]).
 */
class SourcesParityTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/parity/$name")) { name }.readText()

    private val rows: List<ArchivePlay> = Json.parseToJsonElement(resource("card-parity-sources.rows.json")).jsonArray.map { e ->
        val o = e.jsonObject
        fun str(k: String) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        ArchivePlay(str("id")!!, str("t")!!, str("a")!!, str("al"), str("sid"), o["s"]!!.jsonPrimitive.long, o["ms"]!!.jsonPrimitive.long, o["f"]!!.jsonPrimitive.int)
    }
    private val expect: JsonObject = Json.parseToJsonElement(resource("card-parity-sources.expect.json")).jsonObject

    private val spotify = rows.filter { RecordSource.of(it.f) == RecordSource.SPOTIFY }
    private val own = rows.filter { RecordSource.of(it.f) == RecordSource.STASH }
    private val scrobbles = rows.filter { RecordSource.of(it.f) == RecordSource.LASTFM }

    /** [list] handed on in chunks of the size asked, as the database's pages are. */
    private fun source(list: List<ArchivePlay>) = RecordArchiveSource { size, emit ->
        val chunks = list.chunked(size)
        if (chunks.isEmpty()) emit(emptyList(), true) else chunks.forEachIndexed { i, c -> emit(c, i == chunks.lastIndex) }
    }

    private suspend fun archive(chunk: Int = 3): List<ArchivePlay> {
        val out = ArrayList<ArchivePlay>()
        RecordArchive(source(spotify), source(own), source(scrobbles)).stream(chunk) { r, _ -> out += r }
        return out
    }

    private fun counts(o: JsonObject) = SourceCounts(o["spotify"]!!.jsonPrimitive.int, o["stash"]!!.jsonPrimitive.int, o["lastfm"]!!.jsonPrimitive.int)

    private fun on(o: JsonObject) = SourcesOn(o["spotify"]!!.jsonPrimitive.boolean, o["stash"]!!.jsonPrimitive.boolean, o["lastfm"]!!.jsonPrimitive.boolean)

    private val states = expect["states"]!!.jsonArray.map { it.jsonObject }

    @Test fun `the archive leaves out exactly the scrobbles the page's rule leaves out, and a scrobble with a skip's id`() = runTest {
        // By index: the scrobble sharing a skip's id goes, the skip stays
        val gone = (expect["coveredRows"]!!.jsonArray + expect["leftOutRows"]!!.jsonArray).map { it.jsonPrimitive.int }.toSet()
        assertThat(gone).hasSize(6)
        val kept = rows.filterIndexed { i, _ -> i !in gone }.map { it.id }.sorted()
        for (chunk in listOf(1, 3, 20_000)) {
            val sent = archive(chunk)
            assertWithMessage("chunks of $chunk").that(sent.map { it.id }.sorted()).isEqualTo(kept)
            // one row per id: the page keeps them all
            assertThat(sent.map { it.id }.toSet()).hasSize(sent.size)
        }
    }

    @Test fun `each source counted as the page counts it`() = runTest {
        val c = RecordSources.Counter()
        archive().forEach(c::add)
        assertThat(c.counts()).isEqualTo(counts(expect["counts"]!!.jsonObject))
    }

    @Test fun `the record for every switch state`() = runTest {
        val sent = archive()
        assertThat(states).hasSize(8)
        for (st in states) {
            val choice = on(st["on"]!!.jsonObject)
            val b = RecordSummaryBuilder()
            sent.filter { choice[RecordSource.of(it.f)] }.forEach(b::add)
            val s = b.build(top = 100)
            assertMatches("$choice", s, st)
        }
    }

    @Test fun `the Home card shows the record's numbers for every switch state`() = runTest {
        val sources = MutableStateFlow(SourcesOn.ALL_ON)
        val card = RecordCardSource(
            stamps = flowOf(1),
            tracks = MutableStateFlow(0L),
            archive = source(own),
            albums = { emptyList() },
            scope = backgroundScope,
            importedStamps = flowOf(1),
            imported = source(spotify),
            scrobbles = source(scrobbles),
            sources = sources,
            work = StandardTestDispatcher(testScheduler),
            now = { testScheduler.currentTime },
        )
        val allOn = states.single { on(it["on"]!!.jsonObject) == SourcesOn.ALL_ON }
        // Home is showing: the card is listened to throughout
        var latest: CardData? = null
        backgroundScope.launch { card.data.collect { latest = it } }
        for (st in states) {
            val choice = on(st["on"]!!.jsonObject)
            sources.value = choice
            // the card's work runs in the background (Home's scope): run what is due now
            runCurrent()
            val data = latest!!
            assertThat(data.counts).isEqualTo(counts(expect["counts"]!!.jsonObject))
            // The page is never told "nothing" (record.ts shownSources: a choice with no source left on shows them all)
            val want = if (choice == SourcesOn(false, false, false)) allOn else st
            assertMatches("card $choice", data.summary, want, top = 5)
        }
    }

    private fun assertMatches(at: String, s: RecordSummary, st: JsonObject, top: Int = 100) {
        assertWithMessage("$at: plays").that(s.plays).isEqualTo(st["plays"]!!.jsonPrimitive.int)
        assertWithMessage("$at: streams").that(s.streams).isEqualTo(st["streams"]!!.jsonPrimitive.int)
        assertWithMessage("$at: totalMs").that(s.totalMs).isEqualTo(st["totalMs"]!!.jsonPrimitive.long)
        assertWithMessage("$at: hours").that(Math.round(s.totalMs / 3_600_000.0)).isEqualTo(st["hours"]!!.jsonPrimitive.long)
        val first = st["first"]!!.takeIf { it !is JsonNull }?.jsonPrimitive?.long
        assertWithMessage("$at: first").that(s.first).isEqualTo(first)
        val year = st["sinceYear"]!!.takeIf { it !is JsonNull }?.jsonPrimitive?.int
        assertWithMessage("$at: since").that(s.first?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year }).isEqualTo(year)
        val ranked = st["artists"]!!.jsonArray.map { it.jsonObject }.map { RankedArtist(it["label"]!!.jsonPrimitive.content, it["ms"]!!.jsonPrimitive.long) }
        assertWithMessage("$at: artists").that(s.topArtists).isEqualTo(ranked.take(top))
    }
}
