package com.stash.core.data.link

import java.net.URI
import java.net.URLDecoder

/** A Spotify or YouTube link a listener pasted or shared, as far as the link alone says what it is. */
sealed interface MusicLink {
    data class SpotifyPlaylist(val id: String) : MusicLink
    data class SpotifyAlbum(val id: String) : MusicLink
    data class SpotifyTrack(val id: String) : MusicLink

    /** A `spotify.link` / `spotify.app.link` short link: what it is shows once it is followed. */
    data class SpotifyShort(val url: String) : MusicLink

    /** A playlist (`PL…`), an album's own playlist (`OLAK5uy_…`), a radio (`RD…`) or a listener's mix. */
    data class YouTubePlaylist(val id: String) : MusicLink

    /** An album page (`browse/MPREb_…`). */
    data class YouTubeAlbum(val browseId: String) : MusicLink

    /** One song (`watch?v=`, `youtu.be/`, `/shorts/`); [listId] is the playlist it was opened from, when it names one worth importing. */
    data class YouTubeSong(val videoId: String, val listId: String?) : MusicLink
}

/**
 * Finds a music link in pasted or shared text ("Check this out https://open.spotify.com/playlist/…?si=…"): the first URL in it
 * that is a Spotify playlist, album or track, or a YouTube (Music) playlist, album or song. Pure, so it can be tested off-device.
 */
object MusicLinks {

    /** The first music link in [text], or null. */
    fun find(text: String?): MusicLink? = findUrl(text)?.let(::parse)

    /** The first music link in [text] as it is written there (a URL, or a `spotify:` URI), or null. */
    fun findUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val t = text.take(MAX_TEXT)
        SPOTIFY_URI.find(t)?.let { return it.value }
        return URL.findAll(t).map { it.value.trimEnd('.', ',', ')', '!', '?', ';', ':') }.firstOrNull { parse(it) != null }
    }

    /** [url] (or a `spotify:` URI) as a music link, or null. */
    fun parse(url: String): MusicLink? {
        SPOTIFY_URI.matchEntire(url.trim())?.let { m -> return spotify(m.groupValues[1], m.groupValues[2]) }
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        val query = queryOf(uri.rawQuery)
        return when (host) {
            "open.spotify.com", "play.spotify.com" -> {
                // `intl-de/` and `embed/` sit in front of the kind on some links.
                val rest = segments.dropWhile { it.startsWith("intl-") || it == "embed" }
                if (rest.size >= 2) spotify(rest[0], rest[1]) else null
            }
            "spotify.link", "spotify.app.link" -> if (segments.isNotEmpty()) MusicLink.SpotifyShort("https://$host${uri.rawPath}") else null
            "youtube.com", "music.youtube.com" -> youtube(segments, query)
            "youtu.be" -> segments.firstOrNull()?.takeIf { VIDEO_ID.matches(it) }?.let { MusicLink.YouTubeSong(it, importableList(query["list"])) }
            else -> null
        }
    }

    private fun spotify(kind: String, id: String): MusicLink? {
        if (!SPOTIFY_ID.matches(id)) return null
        return when (kind) {
            "playlist" -> MusicLink.SpotifyPlaylist(id)
            "album" -> MusicLink.SpotifyAlbum(id)
            "track" -> MusicLink.SpotifyTrack(id)
            else -> null
        }
    }

    private fun youtube(segments: List<String>, query: Map<String, String>): MusicLink? = when (segments.firstOrNull()) {
        "playlist" -> query["list"]?.takeIf { LIST_ID.matches(it) }?.let { MusicLink.YouTubePlaylist(it) }
        "watch" -> query["v"]?.takeIf { VIDEO_ID.matches(it) }?.let { MusicLink.YouTubeSong(it, importableList(query["list"])) }
        "shorts" -> segments.getOrNull(1)?.takeIf { VIDEO_ID.matches(it) }?.let { MusicLink.YouTubeSong(it, null) }
        "browse" -> segments.getOrNull(1)?.let { id ->
            when {
                id.startsWith("MPREb_") && LIST_ID.matches(id) -> MusicLink.YouTubeAlbum(id)
                id.startsWith("VL") && LIST_ID.matches(id.removePrefix("VL")) -> MusicLink.YouTubePlaylist(id.removePrefix("VL"))
                else -> null
            }
        }
        else -> null
    }

    /**
     * The playlist a song link was opened from, when it is one to offer importing: a playlist or an album. A radio (`RD…`,
     * YouTube's endless "Mix" behind most watch links) or the listener's own queue/likes (`LL`, `WL`) is not.
     */
    private fun importableList(list: String?): String? =
        list?.takeIf { LIST_ID.matches(it) && (it.startsWith("PL") || it.startsWith("OLAK5uy_")) }

    private fun queryOf(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { p ->
            val i = p.indexOf('=')
            if (i <= 0) null else p.substring(0, i) to runCatching { URLDecoder.decode(p.substring(i + 1), "UTF-8") }.getOrDefault("")
        }.toMap()

    private const val MAX_TEXT = 4_000
    private val URL = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val SPOTIFY_URI = Regex("""spotify:(playlist|album|track):([A-Za-z0-9]{22})""")
    private val SPOTIFY_ID = Regex("""[A-Za-z0-9]{22}""")
    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")
    private val LIST_ID = Regex("""[A-Za-z0-9_-]{2,64}""")
}
