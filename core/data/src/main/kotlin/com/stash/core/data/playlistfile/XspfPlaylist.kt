package com.stash.core.data.playlistfile

import com.stash.core.data.weblink.handoff.WireSong

/**
 * XSPF (VLC, Clementine, Strawberry, Audacious, and converters): each `<track>`'s `<title>`, `<creator>`, `<album>` and
 * `<duration>` (milliseconds), an ISRC or Spotify track in its `<identifier>` or `<location>`, and the playlist's own `<title>`.
 * Read by finding the elements rather than with an XML parser: a document type is never fetched or expanded (a hostile file
 * can't blow up), and a file cut short still gives its whole tracks.
 */
internal object XspfPlaylist {
    fun read(text: String, limit: Int): ReadPlaylist {
        val doc = COMMENT.replace(text, "")
        // The playlist's own <title> comes before its tracks; each track has one too.
        val head = TRACK_LIST.find(doc)?.let { doc.substring(0, it.range.first) } ?: doc.substringBefore("<track")
        val title = TITLE.find(head)?.let { textOf(it.groupValues[1]).trim() }?.takeIf { it.isNotEmpty() }
        val songs = ArrayList<WireSong>()
        for (m in TRACK.findAll(doc)) {
            if (songs.size >= limit) break
            songOf(m.groupValues[1])?.let(songs::add)
        }
        return ReadPlaylist(title, songs)
    }

    private fun songOf(track: String): WireSong? {
        fun field(element: Regex) = element.find(track)?.let { textOf(it.groupValues[1]).trim() }?.takeIf { it.isNotEmpty() }
        val title = field(TITLE)
        val creator = field(CREATOR)
        // A track without a creator may say "Artist - Title" in its title; one with neither, in its file's name.
        val words = when {
            title != null && creator != null -> creator to title
            title != null -> PlaylistFileParser.artistTitle(title)
            else -> null
        } ?: field(LOCATION)?.let(PlaylistFileParser::fromLocation) ?: return null
        val ids = (IDENTIFIER.findAll(track) + LOCATION.findAll(track)).map { textOf(it.groupValues[1]).trim() }.toList()
        return PlaylistFileParser.song(
            title = words.second,
            artist = words.first,
            album = field(ALBUM),
            durationMs = field(DURATION)?.let { PlaylistFileParser.durationOf(it, ms = true) },
            isrc = ids.firstNotNullOfOrNull { id -> id.takeIf { it.startsWith("isrc:", ignoreCase = true) }?.let(PlaylistFileParser::isrcOf) },
            spotifyId = ids.firstNotNullOfOrNull { PlaylistFileParser.spotifyIdOf(it) },
        )
    }

    /** An element's text: CDATA as it is, entities decoded, any markup inside it dropped. */
    private fun textOf(raw: String): String {
        val out = StringBuilder()
        var at = 0
        for (m in CDATA.findAll(raw)) {
            out.append(decoded(TAG.replace(raw.substring(at, m.range.first), "")))
            out.append(m.groupValues[1])
            at = m.range.last + 1
        }
        out.append(decoded(TAG.replace(raw.substring(at), "")))
        return out.toString()
    }

    /** XML's five entities and character references; any other `&name;` stays as written (none is ever defined here). */
    private fun decoded(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = if (e[1] == 'x' || e[1] == 'X') e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else m.value
            }
        }
    }

    /** `<name …>…</name>`, with or without a namespace prefix; `<track>` never matches `<trackList>` or `<trackNum>`. */
    private fun element(name: String) =
        Regex("<$PREFIX$name(?:\\s[^>]*)?>(.*?)</$PREFIX$name\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    private const val PREFIX = "(?:[\\w.-]+:)?"
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val TRACK_LIST = Regex("<${PREFIX}trackList\\b", RegexOption.IGNORE_CASE)
    private val TRACK = element("track")
    private val TITLE = element("title")
    private val CREATOR = element("creator")
    private val ALBUM = element("album")
    private val DURATION = element("duration")
    private val LOCATION = element("location")
    private val IDENTIFIER = element("identifier")
    private val CDATA = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos);")
}
