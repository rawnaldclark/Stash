package com.stash.core.model.share

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Where share links live. One place to change when a custom domain is added (spec §1). */
object ShareConfig {
    const val BASE_URL = "https://stash-share.rawnaldclark.workers.dev"
    val HOSTS: Set<String> = setOf("stash-share.rawnaldclark.workers.dev")

    /**
     * Album-art CDNs a shared mix's covers may point at. Anything else could log recipients' IPs.
     * Keep in sync with COVER_HOSTS in infra/share-worker/src/validate.js.
     */
    val COVER_HOSTS: List<String> = listOf(
        "i.scdn.co", "mosaic.scdn.co",
        "i.ytimg.com", "lh3.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com",
        "lastfm.freetls.fastly.net", "lastfm-img.freetls.fastly.net",
        "static.qobuz.com", "c.saavncdn.com",
    )

    /** An https URL on [COVER_HOSTS], exactly or as a subdomain. Never throws. */
    fun isAllowedCover(url: String?): Boolean {
        val uri = runCatching { URI(url ?: return false) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && COVER_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}

object ShareLinks {
    sealed interface Parsed {
        data class Mix(val shareId: String) : Parsed
        data class Track(val track: SharedTrack) : Parsed

        /** A Listen Together invite, `https://…/l/{code}` (spec 2026-09-24 §5). */
        data class Room(val code: String) : Parsed
    }

    private val ID = Regex("^[A-Za-z0-9]{8}$")

    /** 8 of the Worker's 32 room-code symbols (no 0/O, 1/I). Keep in sync with ROOM_API in infra/share-worker/src/index.js. */
    private val ROOM_CODE = Regex("^[A-HJ-NP-Z2-9]{8}$")

    /** A share id for logs: the id is the only secret a link has, so logs show just a prefix. */
    fun logId(shareId: String): String = shareId.take(3) + "…"

    fun mixUrl(shareId: String): String = "${ShareConfig.BASE_URL}/m/$shareId"

    fun roomUrl(code: String): String = "${ShareConfig.BASE_URL}/l/$code"

    fun trackUrl(t: SharedTrack): String = buildString {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        append(ShareConfig.BASE_URL).append("/t?t=").append(enc(t.title)).append("&a=").append(enc(t.artist))
        t.album?.let { append("&al=").append(enc(it)) }
        t.durationMs?.let { append("&d=").append(it) }
        t.isrc?.let { append("&isrc=").append(enc(it)) }
        t.spotifyId?.let { append("&sp=").append(enc(it)) }
        t.youtubeId?.let { append("&yt=").append(enc(it)) }
    }

    /** A share link (https mix/track, or the legacy `stash://track`), or null when it isn't one. */
    fun parse(link: String?): Parsed? {
        if (link == null) return null
        // Links arrive from any app or page, so nothing here may throw.
        val (uri, q) = runCatching { URI(link).let { it to query(it.rawQuery) } }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val path = uri.path.orEmpty().trimEnd('/')
        return when {
            scheme == "https" && host in ShareConfig.HOSTS -> when {
                path.startsWith("/m/") -> path.removePrefix("/m/").takeIf { ID.matches(it) }?.let { Parsed.Mix(it) }
                path.startsWith("/l/") -> path.removePrefix("/l/").uppercase().takeIf { ROOM_CODE.matches(it) }?.let { Parsed.Room(it) }
                path == "/t" -> trackFrom(q["t"], q["a"], q["al"], q["d"], q["isrc"], q["sp"], q["yt"])
                else -> null
            }
            scheme == "stash" && host == "track" ->
                trackFrom(q["t"], q["a"], null, null, null, spotifyTrackId(q["s"]), q["y"])
            else -> null
        }
    }

    private fun trackFrom(t: String?, a: String?, al: String?, d: String?, isrc: String?, sp: String?, yt: String?): Parsed? {
        // Same caps as the Worker's validator: a hostile link can't push huge strings into the DB.
        fun String?.field(max: Int) = this?.trim()?.take(max)?.ifEmpty { null }
        val title = t.field(500) ?: return null
        val artist = a.field(500) ?: return null
        return Parsed.Track(
            SharedTrack(title, artist, al.field(500), d?.toLongOrNull()?.takeIf { it > 0 },
                isrc.field(20), sp.field(40), yt.field(20)),
        )
    }

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { '=' in it }.associate { part ->
            val (k, v) = part.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
}
