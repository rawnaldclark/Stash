package com.stash.data.spotify

import kotlinx.serialization.json.*

/** Select the widest usable image regardless of response order. */
object SpotifyArtwork {
    fun largestSource(value: JsonElement?): String? = (value as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.mapNotNull { image ->
            val url = (image["url"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            url to ((image["width"] as? JsonPrimitive)?.intOrNull ?: 0)
        }?.maxByOrNull { it.second }?.first
}
