package com.stash.core.data.playlistfile

import com.stash.core.data.weblink.handoff.WireSong

/**
 * A plain list, a song a line: "Artist - Title" (or with an en or em dash, as Yandex Music exporters write it), numbered or not
 * ("1. Artist - Title"). Blank lines and lines that name no artist (a heading, a note) are passed over; a line that is a path or
 * a URL is read from its file name.
 */
internal object TextPlaylist {
    fun read(text: String, limit: Int): ReadPlaylist {
        val songs = ArrayList<WireSong>()
        for (raw in text.lineSequence()) {
            if (songs.size >= limit) break
            val line = raw.trim()
            if (line.isEmpty()) continue
            val words = if (PATH_OR_URL.containsMatchIn(line)) {
                PlaylistFileParser.fromLocation(line)
            } else {
                PlaylistFileParser.artistTitle(NUMBERED.replace(line, ""))
            }
            val (artist, title) = words ?: continue
            PlaylistFileParser.song(title, artist, album = null, durationMs = null)?.let(songs::add)
        }
        return ReadPlaylist(null, songs)
    }

    /** A list's number: "1. ", "12) ". A band's own ("10cc - …", "50 Cent - …") has no point or bracket after it. */
    private val NUMBERED = Regex("^\\d{1,4}\\s*[.)]\\s+")

    /** A URL ("https://…", "file:…"), a Windows path ("C:\…", "\\server\…") or a Unix one ("/…"). */
    private val PATH_OR_URL = Regex("""^(?:[A-Za-z][A-Za-z0-9+.-]*://|file:|[A-Za-z]:\\|\\\\|/)""")
}
