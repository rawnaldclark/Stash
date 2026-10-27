package com.stash.feature.record.taste

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.stash.core.model.weblink.Credits
import com.stash.core.model.weblink.SongKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The taste's words and maths on the phone (the unit tests run the JVM's `java.util.regex` and its maths; Android runs ICU's
 * regexes and ART's): `bareTitle`, `songKey`, `primaryArtist` and `INTERLUDE` over the tricky words, and every crafted taste,
 * doubles to the last bit, against what stash-player's own code gave (`src/test/resources/taste/`, packaged as this test's
 * assets). The first test shows it runs where it matters: ICU's own `\d` takes an Arabic-Indic digit, and its `IGNORE_CASE` a
 * KELVIN SIGN, which JavaScript's never do.
 *
 * Run on the rig: `./gradlew :feature:record:assembleDebugAndroidTest`, then `adb -s <rig> install -r` the test APK and
 * `adb -s <rig> shell am instrument -w com.stash.feature.record.test/androidx.test.runner.AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class TasteIcuAndroidTest {
    private fun read(name: String): JsonElement =
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)) }

    private fun strings(e: JsonElement?): List<String> = e?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    @Test fun thisIsIcu() {
        // The engine these regexes were spelled out against: Unicode-aware \d and case folding (the JVM's \d is ASCII)
        assertTrue(Regex("\\d").matches("\u0662"))
        assertTrue(Regex("k", RegexOption.IGNORE_CASE).matches("\u212A"))
    }

    @Test fun songKeyCreditsAndInterludeAsTheWebHasThem() {
        val w = read("words.json").jsonObject
        val keys = w["keys"]!!.jsonArray.map { it.jsonObject }
        assertTrue(keys.size >= 30)
        for (k in keys) {
            val t = k["title"]!!.jsonPrimitive.content
            assertEquals("bareTitle $t", k["bare"]!!.jsonPrimitive.content, SongKey.bareTitle(t))
            assertEquals("songKey $t", k["key"]!!.jsonPrimitive.content, SongKey.songKey(t, "Band"))
            assertEquals("INTERLUDE $t", k["interlude"]!!.jsonPrimitive.boolean, Taste.INTERLUDE.containsMatchIn(t))
        }
        for (c in w["credits"]!!.jsonArray.map { it.jsonObject }) {
            val credit = c["credit"]!!.jsonPrimitive.content
            assertEquals("primary $credit", c["primary"]!!.jsonPrimitive.content, Credits.primary(credit))
        }
    }

    @Test fun everyTasteToTheLastBit() {
        val cases = read("taste.json").jsonArray.map { it.jsonObject }
        assertTrue(cases.size >= 12)
        for (c in cases) {
            val plays = c["plays"]!!.jsonArray.map { it.jsonObject }.map {
                TastePlay(
                    t = it["t"]!!.jsonPrimitive.content, a = it["a"]!!.jsonPrimitive.content, s = it["s"]!!.jsonPrimitive.long,
                    ms = it["ms"]!!.jsonPrimitive.long, f = it["f"]!!.jsonPrimitive.int,
                    al = it["al"]?.jsonPrimitive?.content, sid = it["sid"]?.jsonPrimitive?.content,
                )
            }
            val t = Taste.compute(
                plays,
                TasteOptions(
                    now = c["now"]!!.jsonPrimitive.long,
                    hidden = strings(c["hidden"]).toSet(),
                    kept = strings(c["kept"]).toSet(),
                    putBack = strings(c["putBack"]).toSet(),
                ),
            )
            same(c["name"]!!.jsonPrimitive.content, tasteJson(t), c["taste"]!!)
        }
    }

    private val json = Json { explicitNulls = false; encodeDefaults = true }

    private fun tasteJson(t: Taste): JsonObject = buildJsonObject {
        put("v", t.v)
        put("computedAt", t.computedAt)
        put("first", t.first)
        put("last", t.last)
        put("plays", t.plays)
        put("listenedMs", t.listenedMs)
        put("artists", t.artists)
        put("songs", json.encodeToJsonElement(ListSerializer(SongStat.serializer()), t.songs))
        put("eras", json.encodeToJsonElement(ListSerializer(Era.serializer()), t.eras))
        put("constants", json.encodeToJsonElement(t.constants))
        put("lost", json.encodeToJsonElement(t.lost))
        put("obsessions", json.encodeToJsonElement(ListSerializer(Obsession.serializer()), t.obsessions))
        put("found", t.found?.let { json.encodeToJsonElement(Found.serializer(), it) } ?: JsonNull)
        put("years", json.encodeToJsonElement(ListSerializer(TasteYear.serializer()), t.years))
        put("topArtists", json.encodeToJsonElement(ListSerializer(TopArtist.serializer()), t.topArtists))
        put("seedArtists", json.encodeToJsonElement(t.seedArtists))
        put("yearTops", json.encodeToJsonElement(ListSerializer(YearTop.serializer()), t.yearTops))
    }

    /** The same keys, strings and booleans; numbers the same doubles, every bit. */
    private fun same(path: String, got: JsonElement, want: JsonElement) {
        when (want) {
            is JsonObject -> {
                val g = got as JsonObject
                assertEquals("$path keys", want.keys, g.keys)
                for ((k, v) in want) same("$path.$k", g.getValue(k), v)
            }
            is JsonArray -> {
                val g = got as JsonArray
                assertEquals("$path size", want.size, g.size)
                for (i in want.indices) same("$path[$i]", g[i], want[i])
            }
            JsonNull -> assertEquals(path, JsonNull, got)
            is JsonPrimitive -> {
                val g = got as JsonPrimitive
                when {
                    want.isString -> assertEquals(path, want.content, g.content)
                    want.booleanOrNull != null -> assertEquals(path, want.booleanOrNull, g.booleanOrNull)
                    else -> assertEquals(path, want.content.toDouble(), g.content.toDouble(), 0.0)
                }
            }
        }
    }
}
