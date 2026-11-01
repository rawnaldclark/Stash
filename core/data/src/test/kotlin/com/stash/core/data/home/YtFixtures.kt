package com.stash.core.data.home

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * The real YouTube Music responses data:ytmusic's tests keep (`data/ytmusic/src/test/resources/fixtures/`), read
 * from there so each is committed once. Gradle runs a module's tests in the module's folder; the repo root is
 * tried too, for a runner started there.
 */
internal object YtFixtures {
    private const val DIR = "data/ytmusic/src/test/resources/fixtures"

    fun json(name: String): JsonObject {
        val file = listOf(File("../../$DIR/$name"), File("$DIR/$name")).firstOrNull { it.isFile }
            ?: error("fixture $name not found from ${File(".").absolutePath}")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }
}
