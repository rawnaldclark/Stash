package com.stash.core.data.lastfm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parsed artist.getInfo result. [bio] is null when empty/placeholder. */
data class LastFmArtistInfo(val bio: String?, val mbid: String?)

private val READ_MORE = Regex("""<a[^>]*>\s*Read more on Last\.fm\s*</a>""", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("""<[^>]+>""")
private val PLACEHOLDER = Regex("""^.+ is (a|an) .*artist.*\.?$""", RegexOption.IGNORE_CASE)

fun parseArtistInfo(root: JsonObject): LastFmArtistInfo? {
    val artist = root["artist"]?.jsonObject ?: return null
    val mbid = artist["mbid"]?.jsonPrimitive?.contentOrNullBlank()
    val raw = artist["bio"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNullBlank()
    val cleaned = raw
        ?.replace(READ_MORE, "")
        ?.replace(ANY_TAG, "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !PLACEHOLDER.matches(it) }
    return LastFmArtistInfo(bio = cleaned, mbid = mbid)
}

private fun JsonPrimitive.contentOrNullBlank(): String? = content.trim().ifBlank { null }
