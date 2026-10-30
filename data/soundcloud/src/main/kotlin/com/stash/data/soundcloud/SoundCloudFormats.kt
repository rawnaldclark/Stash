package com.stash.data.soundcloud

import kotlinx.serialization.json.*

/** A transcoding is not downloadable until both metadata and resolved URL say full-length. */
data class SoundCloudFormat(val endpoint: String, val protocol: String, val mimeType: String, val preview: Boolean, val preset: String)

object SoundCloudFormats {
    fun parse(track: JsonObject): List<SoundCloudFormat> {
        val policy = track.string("policy")
        val preview = policy == "SNIP" || (track["snipped"] as? JsonPrimitive)?.booleanOrNull == true
        val media = track["media"] as? JsonObject ?: return emptyList()
        return (media["transcodings"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val format = item["format"] as? JsonObject ?: return@mapNotNull null
            val protocol = format.string("protocol") ?: return@mapNotNull null
            val endpoint = item.string("url") ?: return@mapNotNull null
            val preset = item.string("preset").orEmpty()
            if (protocol !in setOf("hls", "progressive") || "/encrypted-hls" in endpoint || preset.startsWith("abr")) return@mapNotNull null
            SoundCloudFormat(endpoint, protocol, format.string("mime_type").orEmpty(),
                preview || "/preview/" in endpoint || (item["snipped"] as? JsonPrimitive)?.booleanOrNull == true, preset)
        }
    }

    fun preferred(formats: List<SoundCloudFormat>): SoundCloudFormat? = ordered(formats).firstOrNull()

    fun ordered(formats: List<SoundCloudFormat>): List<SoundCloudFormat> = formats.sortedWith(
        compareBy<SoundCloudFormat> { it.preview }
            .thenByDescending { it.protocol == "hls" && it.preset.contains("aac_160") }
            .thenByDescending { it.mimeType.contains("aac") || it.mimeType.contains("mp4") }
    )

    fun isPreviewUrl(url: String): Boolean = Regex("/(?:preview|playlist)/0/30/").containsMatchIn(url)
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}
