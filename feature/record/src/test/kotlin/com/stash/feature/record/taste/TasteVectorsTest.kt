package com.stash.feature.record.taste

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.core.data.record.MixTaste
import com.stash.core.model.weblink.Credits
import com.stash.core.model.weblink.SongKey
import com.stash.feature.record.archive.ArchivePlay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * The taste here gives what the web's gives (`src/test/resources/taste/`, written by `scripts/taste-vectors.ts` from stash-player's
 * own code at the live commit): every field of `computeTaste` over crafted plays, doubles compared exactly (weights, depths, era
 * shares); `mixTasteOf` with songs put back and without; `seedArtists`' blend; `refreshTaste` over an archive and this device's own
 * plays; `keptOut` and `whyKeptOut`; and `bareTitle`, `songKey`, `primaryArtist` and `INTERLUDE` over tricky words.
 */
class TasteVectorsTest {
    private fun read(name: String): JsonElement = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/taste/$name")) { name }.readText())

    private val tastes = read("taste.json").jsonArray.map { it.jsonObject }

    private fun strings(e: JsonElement?): List<String> = e?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    private fun play(o: JsonObject) = TastePlay(
        t = o["t"]!!.jsonPrimitive.content,
        a = o["a"]!!.jsonPrimitive.content,
        s = o["s"]!!.jsonPrimitive.long,
        ms = o["ms"]!!.jsonPrimitive.long,
        f = o["f"]!!.jsonPrimitive.int,
        al = o["al"]?.jsonPrimitive?.content,
        sid = o["sid"]?.jsonPrimitive?.content,
    )

    private fun options(c: JsonObject, putBackKey: String = "putBack") = TasteOptions(
        now = c["now"]!!.jsonPrimitive.long,
        hidden = strings(c["hidden"]).toSet(),
        kept = strings(c["kept"]).toSet(),
        putBack = strings(c[putBackKey]).toSet(),
    )

    private fun computed(c: JsonObject): Taste = Taste.compute(c["plays"]!!.jsonArray.map { play(it.jsonObject) }, options(c))

    @Test fun `every field of the taste, doubles to the last bit`() {
        assertThat(tastes.size).isAtLeast(12)
        for (c in tastes) {
            val name = c["name"]!!.jsonPrimitive.content
            same(name, tasteJson(computed(c)), c["taste"]!!)
        }
    }

    @Test fun `the cases cover what they say they do`() {
        val all = tastes.associate { it["name"]!!.jsonPrimitive.content to it["taste"]!!.jsonObject }
        // an exact 0.4 joins, one just under doesn't: one era of the two pairs
        assertThat(all.entries.single { it.key.contains("exactly 0.4") }.value["eras"]!!.jsonArray).hasSize(1)
        // the tie goes to Alpha, Paper Moons' two runs are one era
        assertThat(all.entries.single { it.key.contains("lead tie") }.value["eras"]!!.jsonArray.map { it.jsonObject["artist"]!!.jsonPrimitive.content })
            .containsExactly("Alpha", "Paper Moons").inOrder()
        // an artist that folds to nothing is a seed artist named "" on the web too
        assertThat(strings(all.entries.single { it.key.contains("folds to nothing") }.value["seedArtists"])).contains("")
    }

    @Test fun `mixTasteOf - kept out minus the songs put back, weights over the top weight of every song`() {
        val byName = tastes.associate { it["name"]!!.jsonPrimitive.content to computed(it) }
        val cases = read("mix-taste.json").jsonArray.map { it.jsonObject }
        assertThat(cases.size).isAtLeast(40)
        for (c in cases) {
            val t = byName.getValue(c["name"]!!.jsonPrimitive.content)
            val putBack = strings(c["putBack"]).toSet()
            val m = MixTaste.of(t.songs.map { MixTaste.Companion.Song(it.key, it.weight, it.avoid) }, t.seedArtists, putBack)
            val out = c["out"]!!.jsonObject
            val why = "${c["name"]!!.jsonPrimitive.content} / put back $putBack"
            assertWithMessage(why).that(m.artists).isEqualTo(strings(out["artists"]))
            assertWithMessage(why).that(m.avoid.sorted()).isEqualTo(strings(out["avoid"]))
            val weights = out["weights"]!!.jsonObject.mapValues { it.value.jsonPrimitive.double }
            assertWithMessage(why).that(m.weights).isEqualTo(weights)
        }
    }

    @Test fun `seedArtists - the taste keeps max(3, 8 - own) places, the taste alone with no own seeds`() {
        val cases = read("seeds.json").jsonArray.map { it.jsonObject }
        assertThat(cases.size).isAtLeast(10)
        for (c in cases) {
            val seeds = MixTaste.blendSeeds(strings(c["own"]), strings(c["taste"]))
            assertWithMessage(c["name"]!!.jsonPrimitive.content).that(seeds).isEqualTo(strings(c["seeds"]))
        }
    }

    @Test fun `refreshTaste - the scrobbles own plays cover left out, an own play the archive holds counted once`() {
        val cases = read("refresh.json").jsonArray.map { it.jsonObject }
        assertThat(cases.size).isAtLeast(5)
        for (c in cases) {
            val own = c["own"]!!.jsonArray.map { it.jsonObject }.map {
                OwnPlay(it["t"]!!.jsonPrimitive.content, it["a"]!!.jsonPrimitive.content, it["al"]?.jsonPrimitive?.content, it["s"]!!.jsonPrimitive.long, it["d"]?.jsonPrimitive?.long)
            }
            val rows = c["rows"]!!.jsonArray.map { it.jsonObject }.map {
                ArchivePlay(
                    id = it["id"]!!.jsonPrimitive.content, t = it["t"]!!.jsonPrimitive.content, a = it["a"]!!.jsonPrimitive.content,
                    al = it["al"]?.jsonPrimitive?.content, s = it["s"]!!.jsonPrimitive.long, ms = it["ms"]!!.jsonPrimitive.long, f = it["f"]!!.jsonPrimitive.int,
                )
            }
            val input = TasteInput(own)
            // a page at a time, in two pages (any order is the same taste)
            input.add(rows.drop(rows.size / 2))
            input.add(rows.take(rows.size / 2))
            val t = Taste.compute(input.plays(), options(c, putBackKey = "keep"))
            same(c["name"]!!.jsonPrimitive.content, tasteJson(t), c["taste"]!!)
        }
    }

    @Test fun `keptOut and whyKeptOut - the first rule that holds, in the taste's order, none put back`() {
        val byName = tastes.associate { it["name"]!!.jsonPrimitive.content to computed(it) }
        val k = read("kept-out.json").jsonObject
        for (c in k["lists"]!!.jsonArray.map { it.jsonObject }) {
            val t = byName.getValue(c["name"]!!.jsonPrimitive.content)
            val list = KeptOut.ofTaste(t, strings(c["hidden"]).toSet(), strings(c["putBack"]).toSet())
            val want = c["list"]!!.jsonArray.map { it.jsonObject }
            assertWithMessage(c["name"]!!.jsonPrimitive.content).that(list.map { it.key }).isEqualTo(want.map { it["key"]!!.jsonPrimitive.content })
            for ((got, w) in list.zip(want)) {
                assertThat(got.title).isEqualTo(w["title"]!!.jsonPrimitive.content)
                assertThat(got.artist).isEqualTo(w["artist"]!!.jsonPrimitive.content)
                assertThat(got.album).isEqualTo(w["album"]?.jsonPrimitive?.content)
                assertThat(whyJson(got.why)).isEqualTo(w["why"])
            }
        }
        for (c in k["why"]!!.jsonArray.map { it.jsonObject }) {
            val key = c["key"]!!.jsonPrimitive.content
            val hidden = if (c["hidden"]!!.jsonPrimitive.boolean) setOf(key) else emptySet()
            val why = KeptOut.why(key, c["title"]!!.jsonPrimitive.content, c["starts"]!!.jsonPrimitive.int, c["skips"]!!.jsonPrimitive.int, hidden)
            assertWithMessage(c.toString()).that(whyJson(why)).isEqualTo(c["why"])
        }
    }

    @Test fun `the song key, the credit's cut and the interlude rule over tricky words`() {
        val w = read("words.json").jsonObject
        for (k in w["keys"]!!.jsonArray.map { it.jsonObject }) {
            val t = k["title"]!!.jsonPrimitive.content
            assertWithMessage("bareTitle(${t.escaped()})").that(SongKey.bareTitle(t)).isEqualTo(k["bare"]!!.jsonPrimitive.content)
            assertWithMessage("songKey(${t.escaped()})").that(SongKey.songKey(t, "Band")).isEqualTo(k["key"]!!.jsonPrimitive.content)
            assertWithMessage("INTERLUDE(${t.escaped()})").that(Taste.INTERLUDE.containsMatchIn(t)).isEqualTo(k["interlude"]!!.jsonPrimitive.boolean)
        }
        for (c in w["credits"]!!.jsonArray.map { it.jsonObject }) {
            val credit = c["credit"]!!.jsonPrimitive.content
            assertWithMessage("primary(${credit.escaped()})").that(Credits.primary(credit)).isEqualTo(c["primary"]!!.jsonPrimitive.content)
        }
    }

    private fun String.escaped() = buildString { for (ch in this@escaped) if (ch.code in 0x20..0x7e) append(ch) else append("\\u%04x".format(ch.code)) }

    // ── the taste as the web writes it ──────────────────────────────────────────────────────────────────────────────

    private fun whyJson(w: KeptOutWhy): JsonObject = when (w) {
        KeptOutWhy.Hidden -> buildJsonObject { put("kind", "hidden") }
        is KeptOutWhy.Skips -> buildJsonObject {
            put("kind", "skips")
            put("skips", w.skips)
            put("starts", w.starts)
        }
        KeptOutWhy.Interlude -> buildJsonObject { put("kind", "interlude") }
    }

    private fun strs(l: List<String>) = buildJsonArray { l.forEach { add(JsonPrimitive(it)) } }

    /** [t] in the web's shape (taste.ts `Taste`: `album` and `sid` only when there are some). */
    private fun tasteJson(t: Taste): JsonObject = buildJsonObject {
        put("v", t.v)
        put("computedAt", t.computedAt)
        put("first", t.first)
        put("last", t.last)
        put("plays", t.plays)
        put("listenedMs", t.listenedMs)
        put("artists", t.artists)
        put(
            "songs",
            buildJsonArray {
                for (s in t.songs) {
                    add(
                        buildJsonObject {
                            put("key", s.key)
                            put("title", s.title)
                            put("artist", s.artist)
                            put("starts", s.starts)
                            put("plays", s.plays)
                            put("full", s.full)
                            put("chosen", s.chosen)
                            put("skips", s.skips)
                            put("years", s.years)
                            put("listenedMs", s.listenedMs)
                            put("first", s.first)
                            put("last", s.last)
                            put("peak", s.peak)
                            put("peakAt", s.peakAt)
                            put("weight", s.weight)
                            put("depth", s.depth)
                            put("avoid", s.avoid)
                            s.album?.let { put("album", it) }
                            s.sid?.let { put("sid", it) }
                        },
                    )
                }
            },
        )
        put(
            "eras",
            buildJsonArray {
                for (e in t.eras) {
                    add(
                        buildJsonObject {
                            put("start", e.start)
                            put("end", e.end)
                            put("artist", e.artist)
                            put("artists", strs(e.artists))
                            put("share", e.share)
                            put("listenedMs", e.listenedMs)
                            put("songs", strs(e.songs))
                        },
                    )
                }
            },
        )
        put("constants", strs(t.constants))
        put("lost", strs(t.lost))
        put(
            "obsessions",
            buildJsonArray {
                for (o in t.obsessions) {
                    add(
                        buildJsonObject {
                            put("key", o.key)
                            put("n", o.n)
                            put("at", o.at)
                        },
                    )
                }
            },
        )
        put(
            "found",
            t.found?.let {
                buildJsonObject {
                    put("year", it.year)
                    put("artists", it.artists)
                }
            } ?: JsonNull,
        )
        put(
            "years",
            buildJsonArray {
                for (y in t.years) {
                    add(
                        buildJsonObject {
                            put("year", y.year)
                            put("plays", y.plays)
                            put("listenedMs", y.listenedMs)
                            put("newArtists", y.newArtists)
                        },
                    )
                }
            },
        )
        put(
            "topArtists",
            buildJsonArray {
                for (a in t.topArtists) {
                    add(
                        buildJsonObject {
                            put("key", a.key)
                            put("name", a.name)
                            put("plays", a.plays)
                            put("listenedMs", a.listenedMs)
                        },
                    )
                }
            },
        )
        put("seedArtists", strs(t.seedArtists))
        put(
            "yearTops",
            buildJsonArray {
                for (y in t.yearTops) {
                    add(
                        buildJsonObject {
                            put("year", y.year)
                            put("artists", strs(y.artists))
                            put("songs", strs(y.songs))
                        },
                    )
                }
            },
        )
    }

    /** [got] is [want]: the same keys, the same strings and booleans, numbers equal as doubles (exactly). */
    private fun same(path: String, got: JsonElement, want: JsonElement) {
        when (want) {
            is JsonObject -> {
                assertWithMessage("$path: an object").that(got).isInstanceOf(JsonObject::class.java)
                val g = got.jsonObject
                assertWithMessage("$path: its keys").that(g.keys).isEqualTo(want.keys)
                for ((k, v) in want) same("$path.$k", g.getValue(k), v)
            }
            is JsonArray -> {
                assertWithMessage("$path: an array").that(got).isInstanceOf(JsonArray::class.java)
                val g = got.jsonArray
                assertWithMessage("$path: its size").that(g.size).isEqualTo(want.size)
                for (i in want.indices) same("$path[$i]", g[i], want[i])
            }
            JsonNull -> assertWithMessage(path).that(got).isEqualTo(JsonNull)
            is JsonPrimitive -> {
                val g = got.jsonPrimitive
                when {
                    want.isString -> assertWithMessage(path).that(g.contentOrNull).isEqualTo(want.content)
                    want.booleanOrNull != null -> assertWithMessage(path).that(g.booleanOrNull).isEqualTo(want.booleanOrNull)
                    // A number: the same double, every bit (a weight, a depth, an era's share; ms and counts are exact integers)
                    else -> assertWithMessage(path).that(g.content.toDouble()).isEqualTo(want.content.toDouble())
                }
            }
        }
    }
}
