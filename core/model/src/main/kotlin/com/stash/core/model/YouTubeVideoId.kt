package com.stash.core.model

import java.net.URI

/**
 * The shape of a YouTube video id: exactly 11 characters from the URL-safe
 * base64 alphabet (`A-Z`, `a-z`, `0-9`, `_`, `-`).
 *
 * An id reaches a temp file name, yt-dlp, a stream lookup or a thumbnail URL
 * only when it has this shape; queued watch links are rebuilt from their
 * checked id ([rebuildWatchUrl]) before yt-dlp gets them. A caller treats any
 * other id as no usable video (an unmatched or failed result) and never trims
 * or rewrites it into one that passes.
 */
object YouTubeVideoId {

    /** Every YouTube video id is exactly this long. */
    const val LENGTH = 11

    private const val WATCH_URL_PREFIX = "https://www.youtube.com/watch?v="
    private const val MUSIC_HOST = "music.youtube.com"
    private const val MUSIC_WATCH_URL_PREFIX = "https://$MUSIC_HOST/watch?v="

    /** Hosts whose `/watch?v=` links name a video. */
    private val WATCH_HOSTS = setOf("www.youtube.com", MUSIC_HOST, "m.youtube.com", "youtube.com")

    /** True when [id] has the YouTube video-id shape. Never throws. */
    fun isValid(id: String?): Boolean =
        id != null && id.length == LENGTH && id.all(::isIdChar)

    /** `https://www.youtube.com/watch?v=<id>`, or null when [id] isn't a video id. */
    fun watchUrl(id: String?): String? = if (isValid(id)) "$WATCH_URL_PREFIX$id" else null

    /**
     * The video id named by a YouTube watch link
     * (`https://[www.|music.|m.]youtube.com/watch?v=<id>`), or null when [url]
     * isn't such a link: another scheme, host, port or path, user info, no `v`
     * or more than one, or a `v` without the video-id shape. Never throws.
     */
    fun fromWatchUrl(url: String?): String? = parseWatchUrl(url)?.second

    /**
     * The watch link [url] names, rebuilt from its checked id: on
     * music.youtube.com for a YouTube Music link, else [watchUrl]. Nothing
     * else of [url] is kept. Null when [url] isn't a watch link
     * ([fromWatchUrl]). Never throws.
     */
    fun rebuildWatchUrl(url: String?): String? {
        val (host, id) = parseWatchUrl(url) ?: return null
        return if (host == MUSIC_HOST) "$MUSIC_WATCH_URL_PREFIX$id" else "$WATCH_URL_PREFIX$id"
    }

    /** The lower-case host and the video id of a watch link (see [fromWatchUrl]), or null. */
    private fun parseWatchUrl(url: String?): Pair<String, String>? {
        val uri = runCatching { URI(url ?: return null) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) return null
        val host = uri.host?.lowercase()?.takeIf { it in WATCH_HOSTS } ?: return null
        if (uri.rawPath != "/watch") return null
        val ids = uri.rawQuery.orEmpty().split('&').filter { it.startsWith("v=") }
        val id = ids.singleOrNull()?.removePrefix("v=")?.takeIf(::isValid) ?: return null
        return host to id
    }

    private fun isIdChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'
}
