package com.stash.feature.nowplaying.ui

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The browser tuner (tools/np-tuner) builds its sliders, its ranges and its "Copy values" block from
 * fields.json. It must name the same keys, units, ranges and production values as the app, or the
 * page would show one thing and the phone clamp to another.
 */
class NpTunerFieldsTest {

    private val fields: JsonObject by lazy {
        Json.parseToJsonElement(repoFile("tools/np-tuner/fields.json").readText()).jsonObject
    }

    @Test
    fun `fields json lists every number, in order, with the app's unit and range`() {
        val numbers = fields["numbers"]!!.jsonArray.map { it.jsonObject }
        assertEquals(NpSpacingJson.fields.map { it.key }, numbers.map { it.str("key") })
        numbers.zip(NpSpacingJson.fields).forEach { (json, field) ->
            assertEquals(field.key, field.constName, json.str("const"))
            assertEquals(field.key, field.unit, json.str("unit"))
            assertEquals(field.key, field.min, json["min"]!!.jsonPrimitive.float)
            assertEquals(field.key, field.max, json["max"]!!.jsonPrimitive.float)
        }
    }

    @Test
    fun `fields json's defaults are the production values, constant by constant`() {
        val numbers = fields["numbers"]!!.jsonArray.map { it.jsonObject }
        numbers.zip(NpSpacingJson.fields).forEach { (json, field) ->
            val constant = NpSpacingDefaults::class.java.getDeclaredField(field.constName).getFloat(null)
            assertEquals(field.key, constant, json["default"]!!.jsonPrimitive.float)
            assertEquals(field.key, constant, field.get(NpSpacing()))
        }
    }

    @Test
    fun `fields json's choices are the app's, with the production default`() {
        val choices = fields["choices"]!!.jsonArray.map { it.jsonObject }.associateBy { it.str("key") }
        val align = choices.getValue(NpSpacingJson.ALIGN_KEY)
        assertEquals(NpAlign.entries.map { it.key }, align.options("value"))
        assertEquals(NpAlign.entries.map { it.name }, align.options("const"))
        assertEquals(NpSpacingDefaults.ALIGN.key, align.str("default"))

        val text = choices.getValue(NpSpacingJson.FULL_ART_TEXT_KEY)
        assertEquals(FullArtTextPosition.entries.map { it.key }, text.options("value"))
        assertEquals(FullArtTextPosition.entries.map { it.name }, text.options("const"))
        assertEquals(NpSpacingDefaults.FULL_ART_TEXT.key, text.str("default"))
    }

    @Test
    fun `fields json names the debug receiver`() {
        val receiver = fields["receiver"]!!.jsonObject
        assertEquals("com.stash.app.debug.NP_SPACING", receiver.str("action"))
        assertEquals("com.stash.app.debug", receiver.str("package"))
        assertEquals("NpSpacingDefaults", fields.str("kotlinObject"))
    }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.options(key: String): List<String> =
        this["options"]!!.jsonArray.map { it.jsonObject.str(key) }

    /** [path] from the repository root, found from wherever Gradle runs the test. */
    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return File(dir, path)
            dir = dir.parentFile
        }
        error("repository root (settings.gradle.kts) not found above ${File("").absolutePath}")
    }
}
