package com.stash.core.data.lastfm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parsed artist.getInfo result. [bio] is null when empty/placeholder. */
data class LastFmArtistInfo(val bio: String?, val mbid: String?)

private val READ_MORE = Regex("""<a[^>]*>\s*Read more on Last\.fm\s*</a>""", RegexOption.IGNORE_CASE)
private val ANY_TAG = Regex("""<[^>]+>""")
// Last.fm's content-free placeholder for artists with no real bio is a single
// short "<name> is a[n] … artist." sentence. Only null out that shape, and only
// when SHORT — a broad "contains 'artist'" match would wrongly drop real bios
// (many legit bios open "X is a <genre> artist …" and run for paragraphs).
private const val PLACEHOLDER_MAX_LEN = 60
private val PLACEHOLDER = Regex("""^.{0,40}\bis an? .{0,20}artist\.?$""", RegexOption.IGNORE_CASE)

fun parseArtistInfo(root: JsonObject): LastFmArtistInfo? {
    val artist = root["artist"]?.jsonObject ?: return null
    val mbid = artist["mbid"]?.jsonPrimitive?.contentOrNullBlank()
    val raw = artist["bio"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNullBlank()
    val cleaned = raw
        ?.replace(READ_MORE, "")
        ?.replace(ANY_TAG, "")
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !(it.length <= PLACEHOLDER_MAX_LEN && PLACEHOLDER.matches(it)) }
    return LastFmArtistInfo(bio = cleaned, mbid = mbid)
}

private fun JsonPrimitive.contentOrNullBlank(): String? = content.trim().ifBlank { null }
