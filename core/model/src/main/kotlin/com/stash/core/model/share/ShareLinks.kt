package com.stash.core.model.share

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Where share links live (spec §1; moved to stashfm.app on 2026-10-03). */
object ShareConfig {
    /** Every link the app builds, and the first host every share API call tries (mixes, song links, rooms, Community). */
    const val BASE_URL = "https://stashfm.app"

    /** Where links lived before stashfm.app. The same Worker and data still answer there, forever. */
    const val LEGACY_BASE_URL = "https://stash-share.rawnaldclark.workers.dev"

    /**
     * Where share API calls go, in order. The old host is tried only when a request never reached stashfm.app (no
     * DNS answer, refused, TLS failed): DNS filters often block newly registered domains for weeks. Links are still
     * built on [BASE_URL], whichever host answered.
     */
    val API_BASE_URLS: List<String> = listOf(BASE_URL, LEGACY_BASE_URL)

    /**
     * Hosts whose links open in Stash: stashfm.app, and the workers.dev host older links use. Keep in sync with the
     * App Links filter in app/src/main/AndroidManifest.xml.
     */
    val HOSTS: Set<String> = API_BASE_URLS.mapTo(LinkedHashSet()) { it.removePrefix("https://") }

    /**
     * Album-art CDNs a shared mix's or song's covers may point at. Anything else could log recipients' IPs.
     * Keep in sync with COVER_HOSTS in infra/share-worker/src/validate.js.
     */
    val COVER_HOSTS: List<String> = listOf(
        "i.scdn.co", "mosaic.scdn.co",
        "i.ytimg.com", "lh3.googleusercontent.com", "yt3.googleusercontent.com", "yt3.ggpht.com",
        "lastfm.freetls.fastly.net", "lastfm-img.freetls.fastly.net",
        "static.qobuz.com", "c.saavncdn.com",
        // Deezer: where the Worker finds a song page's cover by ISRC.
        "cdn-images.dzcdn.net", "e-cdns-images.dzcdn.net",
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

        /** A short song link, `https://…/t/{id}`: the song itself is on the share Worker (`GET /v1/tracks/{id}`). */
        data class TrackRef(val id: String) : Parsed

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

    /**
     * How long Share → "Stash link" waits for `POST /v1/tracks` before it shares the long link instead.
     * The share sheet's time box and the request's own call timeout both use it.
     */
    const val SHORT_LINK_TIMEOUT_MS = 4_000L

    /** The short song link for an id the Worker's `POST /v1/tracks` returned. */
    fun trackShortUrl(id: String): String = "${ShareConfig.BASE_URL}/t/$id"

    /** An id the Worker hands out for mixes and short song links: 8 letters or digits. */
    fun isShareId(id: String): Boolean = ID.matches(id)

    fun trackUrl(t: SharedTrack): String = buildString {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        append(ShareConfig.BASE_URL).append("/t?t=").append(enc(t.title)).append("&a=").append(enc(t.artist))
        t.album?.let { append("&al=").append(enc(it)) }
        t.durationMs?.let { append("&d=").append(it) }
        t.isrc?.let { append("&isrc=").append(enc(it)) }
        t.spotifyId?.let { append("&sp=").append(enc(it)) }
        t.youtubeId?.let { append("&yt=").append(enc(it)) }
    }

    /** A share link (https mix/track/room, short or long song link, or the legacy `stash://track`), or null when it isn't one. */
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
                path.startsWith("/t/") -> path.removePrefix("/t/").takeIf { ID.matches(it) }?.let { Parsed.TrackRef(it) }
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
