package com.stash.core.data.lastfm

import com.stash.core.common.ArtUrlUpgrader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One scrobble read from a user's Last.fm history (`user.getRecentTracks`). Only what the Last.fm
 * recap needs; [image] is the album image Last.fm attaches to the scrobble (null when it only has
 * its star placeholder). Serializable: the recap's file cache stores these as they are.
 */
@Serializable
data class LastFmScrobble(
    /** When it was scrobbled, UNIX seconds (UTC). */
    @SerialName("t") val uts: Long,
    @SerialName("a") val artist: String,
    @SerialName("n") val title: String,
    @SerialName("l") val album: String = "",
    @SerialName("i") val image: String? = null,
)

/** One page of `user.getRecentTracks`: the scrobbles plus Last.fm's paging (`@attr`). */
data class LastFmRecentTracksPage(
    val scrobbles: List<LastFmScrobble>,
    val page: Int,
    val totalPages: Int,
    /** Scrobbles in the whole requested range (all pages), as Last.fm counts them. */
    val total: Int,
    /** False when the answer had no `recenttracks` at all: not a trustworthy (empty) page. */
    val wellFormed: Boolean = true,
)

/**
 * Parses `user.getRecentTracks` (non-extended) responses. Pure, so its quirks are unit-tested:
 *  - a single track comes as an object, not a one-element array;
 *  - the track playing right now comes first with `@attr.nowplaying` and no `date`: skipped (it
 *    isn't a scrobble yet, and has no time);
 *  - the artist is `{ "#text": name }`, or `{ "name": name }` in the extended shape;
 *  - images are `[{ size, "#text" }]`, often Last.fm's star placeholder: filtered out.
 */
object LastFmRecentTracksParser {

    /** Last.fm's star-logo "no image" placeholder (the same hash LastFmTrackInfo filters). */
    internal const val PLACEHOLDER_HASH = "2a96cbd8b46e442fc41c2b86b821562f"

    fun parse(root: JsonObject): LastFmRecentTracksPage {
        val recent = root["recenttracks"] as? JsonObject ?: return LastFmRecentTracksPage(emptyList(), 1, 0, 0, wellFormed = false)
        val attr = recent["@attr"] as? JsonObject
        val page = attr.int("page") ?: 1
        val totalPages = attr.int("totalPages") ?: 0
        val total = attr.int("total") ?: 0
        val tracks = when (val raw = recent["track"]) {
            is JsonArray -> raw
            is JsonObject -> JsonArray(listOf(raw))
            else -> JsonArray(emptyList())
        }
        val scrobbles = tracks.mapNotNull { (it as? JsonObject)?.let(::scrobble) }
        return LastFmRecentTracksPage(scrobbles, page, totalPages, total)
    }

    private fun scrobble(obj: JsonObject): LastFmScrobble? {
        // The now-playing row: not a scrobble (no date), skip it.
        val nowPlaying = (obj["@attr"] as? JsonObject)?.get("nowplaying")?.primitive() == "true"
        if (nowPlaying) return null
        val uts = (obj["date"] as? JsonObject)?.get("uts")?.primitive()?.toLongOrNull() ?: return null
        val title = obj["name"]?.primitive()?.trim().orEmpty()
        val artist = when (val a = obj["artist"]) {
            is JsonObject -> (a["#text"] ?: a["name"])?.primitive()
            else -> a?.primitive()
        }?.trim().orEmpty()
        if (title.isEmpty() || artist.isEmpty()) return null
        val album = when (val al = obj["album"]) {
            is JsonObject -> al["#text"]?.primitive()
            else -> al?.primitive()
        }?.trim().orEmpty()
        return LastFmScrobble(uts = uts, artist = artist, title = title, album = album, image = bestImage(obj["image"]))
    }

    /** The largest real image, upgraded (ArtUrlUpgrader), or null when there's only the placeholder. */
    internal fun bestImage(images: JsonElement?): String? {
        val arr = when (images) {
            is JsonArray -> images
            is JsonObject -> JsonArray(listOf(images))
            else -> return null
        }
        for (size in SIZES) {
            val url = arr.firstNotNullOfOrNull { el ->
                val o = el as? JsonObject ?: return@firstNotNullOfOrNull null
                val text = o["#text"]?.primitive()
                text.takeIf { o["size"]?.primitive() == size && !it.isNullOrBlank() && PLACEHOLDER_HASH !in it }
            } ?: continue
            return ArtUrlUpgrader.upgrade(url) ?: url
        }
        return null
    }

    private val SIZES = listOf("mega", "extralarge", "large", "medium", "small")

    private fun JsonElement.primitive(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.int(key: String): Int? = this?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }

    /** `user.getInfo`'s registration time, UNIX seconds, or null when the response lacks it. */
    fun registeredAt(root: JsonObject): Long? {
        val user = root["user"] as? JsonObject ?: return null
        val registered = user["registered"] as? JsonObject ?: return null
        return (registered["unixtime"] ?: registered["#text"])?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.takeIf { it > 0 }
    }
}
