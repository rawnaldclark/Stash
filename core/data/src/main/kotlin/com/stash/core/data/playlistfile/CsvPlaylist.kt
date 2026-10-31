package com.stash.core.data.playlistfile

import com.stash.core.data.weblink.handoff.WireSong

/**
 * Exported spreadsheets, read by their header row: Exportify ("Track Name", "Artist Name(s)", "Album Name", "ISRC", "Track URI",
 * "Track Duration (ms)" or "Duration (ms)"), TuneMyMusic and Soundiiz ("Track name", "Artist name", "Album", "Playlist name",
 * "ISRC"), Apple Music's exported text (tab-separated "Name", "Artist", "Album", "Time" in seconds), and a plain
 * "Title,Artist[,Album]". Commas, tabs or semicolons between fields; a quoted field may hold any of them, line breaks and
 * doubled quotes (RFC 4180). A song needs a title and an artist; an ISRC or Spotify track, when the file has one, is its identity.
 */
internal object CsvPlaylist {
    /** The file starts with a header row this reads. */
    fun recognises(text: String): Boolean = layout(text) != null

    fun read(text: String, limit: Int): ReadPlaylist {
        val layout = layout(text) ?: return ReadPlaylist(null, emptyList())
        val c = layout.columns
        val songs = ArrayList<WireSong>()
        val playlists = LinkedHashSet<String>()
        var at = layout.bodyStart
        while (at < text.length && songs.size < limit) {
            val row = record(text, at, layout.separator)
            at = row.next
            val f = row.fields
            fun cell(i: Int?): String? = i?.let { f.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            val title = cell(c.title) ?: continue
            val artist = cell(c.artist)?.let(::artistOf) ?: continue
            val song = PlaylistFileParser.song(
                title = title,
                artist = artist,
                album = cell(c.album),
                durationMs = PlaylistFileParser.durationOf(cell(c.durationMs), ms = true)
                    ?: PlaylistFileParser.durationOf(cell(c.duration), ms = false),
                isrc = PlaylistFileParser.isrcOf(cell(c.isrc)),
                spotifyId = PlaylistFileParser.spotifyIdOf(cell(c.spotify), bare = c.spotifyIsBare),
            ) ?: continue
            songs += song
            if (playlists.size < 2) cell(c.playlist)?.let(playlists::add)
        }
        // One playlist's export names it on every row; a file of several is one list, named by the file.
        return ReadPlaylist(playlists.singleOrNull(), songs)
    }

    /**
     * An artist cell as it should read: an older Exportify escaped commas in names ("Tyler\, The Creator"), and some exports
     * join several artists without a space ("Daft Punk,Pharrell Williams", "Daft Punk;Pharrell Williams").
     */
    private fun artistOf(cell: String): String = cell.replace("\\,", ",").replace(JOINED_ARTISTS, ", ")

    /** Where each thing is in a row, by the header. */
    private class Columns(
        val title: Int,
        val artist: Int,
        val album: Int?,
        val isrc: Int?,
        val durationMs: Int?,
        val duration: Int?,
        val spotify: Int?,
        /** [spotify] holds bare ids (TuneMyMusic's "Spotify - id"), not URIs or links. */
        val spotifyIsBare: Boolean,
        val playlist: Int?,
    )

    /** The separator and columns of a header row, and where the first song's row starts. */
    private class Layout(val separator: Char, val columns: Columns, val bodyStart: Int)

    private class Record(val fields: List<String>, val next: Int)

    private fun layout(text: String): Layout? {
        var start = 0
        while (start < text.length && text[start] == '\n') start++
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        // Excel's hint line, "sep=;", names the separator.
        val hinted = SEPARATOR_HINT.matchEntire(text.substring(start, lineEnd).trim())?.groupValues?.get(1)?.single()
        if (hinted != null) start = lineEnd + 1
        for (separator in if (hinted != null) charArrayOf(hinted) else SEPARATORS) {
            val header = record(text, start, separator)
            val columns = columnsOf(header.fields) ?: continue
            return Layout(separator, columns, header.next)
        }
        return null
    }

    private fun columnsOf(header: List<String>): Columns? {
        if (header.size < 2) return null
        val names = header.map { it.trim().trim('"').trim().lowercase().replace(SPACES, " ") }
        fun find(aliases: List<String>): Int? = aliases.firstNotNullOfOrNull { a -> names.indexOf(a).takeIf { it >= 0 } }
        val title = find(TITLE) ?: return null
        val artist = find(ARTIST) ?: return null
        val spotifyId = find(SPOTIFY_ID)
        return Columns(
            title = title,
            artist = artist,
            album = find(ALBUM),
            isrc = find(ISRC),
            durationMs = find(DURATION_MS),
            duration = find(DURATION),
            spotify = spotifyId ?: find(SPOTIFY_URI),
            spotifyIsBare = spotifyId != null,
            playlist = find(PLAYLIST),
        )
    }

    /** One RFC 4180 record of [text] from [from]: its fields, and where the next record starts. */
    private fun record(text: String, from: Int, separator: Char): Record {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c != '"') {
                    field.append(c)
                } else if (i + 1 < text.length && text[i + 1] == '"') {
                    field.append('"')
                    i++
                } else {
                    quoted = false
                }
            } else if (c == '"' && field.isBlank()) {
                // A quote opens a field only at its start (after any spaces); inside one it is a character.
                field.setLength(0)
                quoted = true
            } else if (c == separator) {
                fields += field.toString()
                field.setLength(0)
            } else if (c == '\n') {
                fields += field.toString()
                return Record(fields, i + 1)
            } else {
                field.append(c)
            }
            i++
        }
        fields += field.toString()
        return Record(fields, text.length)
    }

    /** Tried in this order; a header that names a title and an artist with one of them settles it. */
    private val SEPARATORS = charArrayOf(',', '\t', ';')
    private val SEPARATOR_HINT = Regex("^\"?sep=(.)\"?$", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")
    private val JOINED_ARTISTS = Regex("[,;](?=\\p{L})")

    // Column names, lower-cased, most specific first: "Title" before "Track", which in some files is the track's number.
    private val TITLE = listOf("track name", "title", "song name", "song title", "song", "name", "track title", "track")
    private val ARTIST = listOf("artist name(s)", "artist name", "artist names", "artist(s)", "artist", "artists", "creator", "performer")
    private val ALBUM = listOf("album name", "album", "album title")
    private val ISRC = listOf("isrc", "track isrc")
    private val DURATION_MS = listOf("track duration (ms)", "duration (ms)", "duration_ms", "duration ms", "length (ms)")
    private val DURATION = listOf("duration", "time", "length", "track duration")
    private val SPOTIFY_URI = listOf("track uri", "spotify uri", "spotify track uri", "uri", "spotify url", "spotify link")
    private val SPOTIFY_ID = listOf("spotify - id", "spotify id", "spotify_id", "spotify track id")
    private val PLAYLIST = listOf("playlist name", "playlist")
}
