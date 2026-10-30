package com.stash.data.soundcloud

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Parsed identity only; no arbitrary input URL is used for authenticated API requests. */
data class SoundCloudLink(val url: String, val kind: Kind) {
    enum class Kind { TRACK, SET, LIKES, PROFILE, SHORT }
}

object SoundCloudLinks {
    fun parse(text: String): SoundCloudLink? {
        if (text.length > 4096) return null
        val url = text.trim().toHttpUrlOrNull() ?: return null
        if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.host !in setOf("soundcloud.com", "www.soundcloud.com", "m.soundcloud.com", "on.soundcloud.com")) return null
        val host = url.host.removePrefix("www.").removePrefix("m.")
        val parts = url.pathSegments.filter { it.isNotEmpty() }
        if (parts.any { !it.matches(Regex("[A-Za-z0-9_-]+")) }) return null
        val kind = when {
            url.host == "on.soundcloud.com" && parts.size == 1 -> SoundCloudLink.Kind.SHORT
            host != "soundcloud.com" -> return null
            parts.isEmpty() -> return null
            parts[0] in setOf("discover", "search", "you", "stream", "upload", "settings", "signin") -> return null
            parts.size == 1 -> SoundCloudLink.Kind.PROFILE
            parts.size == 2 && parts[1] == "likes" -> SoundCloudLink.Kind.LIKES
            parts.size == 3 && parts[1] == "sets" -> SoundCloudLink.Kind.SET
            parts.size == 2 -> SoundCloudLink.Kind.TRACK
            else -> return null
        }
        return SoundCloudLink(url.newBuilder().query(null).fragment(null).build().toString(), kind)
    }
}
