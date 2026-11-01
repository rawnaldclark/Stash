package com.stash.core.data.playlistfile

import com.stash.core.data.weblink.handoff.WireSong

/**
 * Exported spreadsheets, read by their header row: Exportify ("Track Name", "Artist Name(s)", "Album Name", "ISRC", "Track URI",
 * "Track Duration (ms)" or "Duration (ms)"), TuneMyMusic and Soundiiz ("Track name", "Artist name", "Album", "Playlist name",
 * "ISRC"), Apple Music's exported text (tab-separated "Name", "Artist", "Album", "Time" in seconds), and a plain
 * "Title,Artist[,Album]". Commas, tabs or semicolons between fields; a quoted field may hold any of them, line breaks and
 * doubled quotes (RFC 4180). A song needs a title and an artist; an ISRC or Spotify track, when the file has one, is its identity.
 * A .csv without a header row ("Daft Punk,One More Time") is read as a hand-made list is written: artist, then title.
 */
internal object CsvPlaylist {
    /** The file starts with a header row this reads. */
    fun recognises(text: String): Boolean = layout(text) != null

    fun read(text: String, limit: Int): ReadPlaylist {
        val layout = layout(text) ?: headerless(text) ?: return ReadPlaylist(null, emptyList())
        val c = layout.columns
        val songs = ArrayList<WireSong>()
        // A unit-less length column's cells, by song: read once the whole column is seen (see below).
        val lengths = ArrayList<String?>()
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
                durationMs = PlaylistFileParser.durationOf(cell(c.durationMs), ms = true),
                isrc = PlaylistFileParser.isrcOf(cell(c.isrc)),
                spotifyId = PlaylistFileParser.spotifyIdOf(cell(c.spotify), bare = c.spotifyIsBare),
            ) ?: continue
            songs += song
            lengths += cell(c.duration)
            if (playlists.size < 2) cell(c.playlist)?.let(playlists::add)
        }
        // A "Duration" column without a unit is in seconds or in milliseconds all the way down: one cell too big for seconds
        // says milliseconds, for a 30-second intro's "30000" too (read alone, that is eight hours).
        val ms = lengths.any { (it?.toDoubleOrNull() ?: 0.0) >= PlaylistFileParser.SECONDS_LIMIT }
        val timed = songs.mapIndexed { i, s ->
            if (s.durationMs != null || lengths[i] == null) s else s.copy(durationMs = PlaylistFileParser.durationOf(lengths[i], ms))
        }
        // One playlist's export names it on every row; a file of several is one list, named by the file.
        return ReadPlaylist(playlists.singleOrNull(), timed)
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
        val (start, hinted) = firstRow(text)
        for (separator in if (hinted != null) charArrayOf(hinted) else SEPARATORS) {
            val header = record(text, start, separator)
            val columns = columnsOf(header.fields) ?: continue
            return Layout(separator, columns, header.next)
        }
        return null
    }

    /**
     * A sheet without a header row: artist, then title (the way a list is written by hand, and how a song is said). Its separator
     * is the one that splits the most of its first rows in two or more ("Earth, Wind & Fire;September" is split by the
     * semicolon every row has, not the comma one row has); a tab first on a tie, as no name holds one.
     */
    private fun headerless(text: String): Layout? {
        val (start, hinted) = firstRow(text)
        var best: Char? = null
        var bestSplit = 0
        for (separator in if (hinted != null) charArrayOf(hinted) else HEADERLESS_SEPARATORS) {
            var at = start
            var rows = 0
            var split = 0
            while (at < text.length && rows < SAMPLE_ROWS) {
                val row = record(text, at, separator)
                at = row.next
                val filled = row.fields.count { it.isNotBlank() }
                if (filled == 0) continue
                rows++
                if (filled >= 2) split++
            }
            if (split > bestSplit) {
                best = separator
                bestSplit = split
            }
        }
        val separator = best ?: return null
        val columns = Columns(
            title = 1, artist = 0, album = null, isrc = null, durationMs = null, duration = null,
            spotify = null, spotifyIsBare = false, playlist = null,
        )
        return Layout(separator, columns, start)
    }

    /** Where the first row starts, past blank lines and Excel's hint line ("sep=;"), and the separator that line names. */
    private fun firstRow(text: String): Pair<Int, Char?> {
        var start = 0
        while (start < text.length && text[start] == '\n') start++
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val hinted = SEPARATOR_HINT.matchEntire(text.substring(start, lineEnd).trim())?.groupValues?.get(1)?.single()
        return if (hinted != null) minOf(lineEnd + 1, text.length) to hinted else start to null
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
    private val HEADERLESS_SEPARATORS = charArrayOf('\t', ',', ';')

    /** How many rows of a sheet without a header tell its separator. */
    private const val SAMPLE_ROWS = 20
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
