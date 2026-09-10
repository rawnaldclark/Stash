package com.stash.core.data.lastfm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * v0.9.16: Rich `track.getInfo` projection. The original
 * [LastFmApiClient.getTrackInfo] returned only a `String?` image URL;
 * this expanded shape surfaces every field the recommender benefits
 * from: MBID (join key for any future MetaBrainz-stack work), per-user
 * playcount + loved (richer affinity than in-app plays for users with
 * pre-Stash Last.fm history), public listeners (popularity bucket for
 * novelty calibration), and the per-track tag set (replaces the
 * separate artist-tag fetch when both are available).
 */
data class LastFmTrackInfo(
    val mbid: String?,
    val durationMs: Long?,
    val listeners: Long,
    val playcount: Long,
    val userPlaycount: Int?,
    val userLoved: Boolean?,
    val bestImageUrl: String?,
    val tags: List<TagCount>,
) {
    data class TagCount(val name: String, val count: Int)

    companion object {
        fun parse(root: JsonElement): LastFmTrackInfo {
            val track = root.jsonObject["track"]?.jsonObject
                ?: return empty()

            val mbid = track["mbid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val duration = track["duration"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
            val listeners = track["listeners"]?.jsonPrimitive?.longOrNull ?: 0L
            val playcount = track["playcount"]?.jsonPrimitive?.longOrNull ?: 0L
            val userPlaycount = track["userplaycount"]?.jsonPrimitive?.intOrNull
            val userLovedRaw = track["userloved"]?.jsonPrimitive?.contentOrNull
            val userLoved = userLovedRaw?.let { it == "1" }
            val image = parseBestImage(track["album"]?.jsonObject)
            val tags = parseTopTags(track["toptags"]?.jsonObject)

            return LastFmTrackInfo(
                mbid = mbid,
                durationMs = duration,
                listeners = listeners,
                playcount = playcount,
                userPlaycount = userPlaycount,
                userLoved = userLoved,
                bestImageUrl = image,
                tags = tags,
            )
        }

        private fun empty() = LastFmTrackInfo(
            mbid = null, durationMs = null, listeners = 0, playcount = 0,
            userPlaycount = null, userLoved = null, bestImageUrl = null, tags = emptyList(),
        )

        private fun parseBestImage(album: JsonObject?): String? {
            val images = album?.get("image") ?: return null
            val arr = if (images is kotlinx.serialization.json.JsonArray) images
                else kotlinx.serialization.json.JsonArray(listOf(images))
            val sizes = listOf("mega", "extralarge", "large", "medium", "small")
            for (size in sizes) {
                val match = arr.firstOrNull { el ->
                    val obj = el.jsonObject
                    val text = obj["#text"]?.jsonPrimitive?.contentOrNull
                    obj["size"]?.jsonPrimitive?.contentOrNull == size &&
                        !text.isNullOrBlank() &&
                        // Filter Last.fm's "no album art" star-logo placeholder
                        // so callers can fall through to a real source (YT
                        // thumbnail, MusicBrainz, etc.) instead of rendering
                        // the generic Last.fm star image.
                        !text.contains(PLACEHOLDER_HASH)
                } ?: continue
                // Upgrade here, at the ONE place Last.fm art enters the app.
                // Every consumer (art backfill, the download match ladder, the
                // Last.fm mix source) reads this field, and none of them ran the
                // URL through the upgrader — which is why 2,317 rows on a real
                // library were still the API's 300x300 thumbnail.
                return com.stash.core.common.ArtUrlUpgrader.upgrade(
                    match.jsonObject["#text"]?.jsonPrimitive?.content,
                )
            }
            return null
        }

        /** Last.fm's star-logo "no image" placeholder hash. */
        private const val PLACEHOLDER_HASH = "2a96cbd8b46e442fc41c2b86b821562f"

        private fun parseTopTags(toptags: JsonObject?): List<TagCount> {
            val tagEl = toptags?.get("tag") ?: return emptyList()
            val arr = if (tagEl is kotlinx.serialization.json.JsonArray) tagEl
                else kotlinx.serialization.json.JsonArray(listOf(tagEl))
            return arr.mapNotNull { el ->
                val obj = el.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val count = obj["count"]?.jsonPrimitive?.intOrNull ?: 0
                TagCount(name, count)
            }
        }
    }
}
