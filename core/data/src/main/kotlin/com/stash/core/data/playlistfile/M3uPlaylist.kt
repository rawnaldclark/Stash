package com.stash.core.data.playlistfile

import com.stash.core.data.weblink.handoff.WireSong
import kotlin.math.roundToLong

/**
 * M3U and M3U8 as players and converters write them: `#EXTINF:<seconds>,Artist - Title` before each song's path or URL,
 * `#PLAYLIST:` for the playlist's own name, and now and then `#EXTALB:` / `#EXTART:` (the album and its artist, which stand
 * until the file names another). A song without an `#EXTINF` is read from its file name; a path or URL says nothing else.
 */
internal object M3uPlaylist {
    /** What an `#EXTINF` line said: the song's length (null when unknown: `-1`) and the text after its comma. */
    private class Info(val durationMs: Long?, val display: String)

    fun read(text: String, limit: Int): ReadPlaylist {
        var title: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var info: Info? = null
        val songs = ArrayList<WireSong>()
        for (raw in text.lineSequence()) {
            if (songs.size >= limit) break
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith('#')) {
                val value = line.substringAfter(':', "").trim()
                when (line.substringBefore(':').uppercase()) {
                    "#EXTINF" -> {
                        // An #EXTINF with no location after it is a song of its own.
                        info?.let { songOf(it, null, album, albumArtist)?.let(songs::add) }
                        info = infoOf(value)
                    }
                    "#PLAYLIST" -> if (title == null) title = value.ifEmpty { null }
                    "#EXTALB" -> album = value.ifEmpty { null }
                    "#EXTART" -> albumArtist = value.ifEmpty { null }
                }
                continue
            }
            songOf(info, line, album, albumArtist)?.let(songs::add)
            info = null
        }
        if (songs.size < limit) info?.let { songOf(it, null, album, albumArtist)?.let(songs::add) }
        return ReadPlaylist(title, songs)
    }

    /** `<seconds>[ attributes],<text>`: the text starts after the first comma outside quotes (IPTV's `tvg-name="a, b"`). */
    private fun infoOf(value: String): Info {
        var quoted = false
        var comma = -1
        for (i in value.indices) {
            val c = value[i]
            if (c == '"') {
                quoted = !quoted
            } else if (c == ',' && !quoted) {
                comma = i
                break
            }
        }
        val head = if (comma >= 0) value.substring(0, comma) else value
        val display = if (comma >= 0) value.substring(comma + 1).trim() else ""
        val seconds = head.trim().substringBefore(' ').toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() }
        return Info(seconds?.let { (it * 1000).roundToLong() }, display)
    }

    /**
     * The song an entry names: "Artist - Title" from its `#EXTINF` (or "Title - Artist" when that ends with the `#EXTART`
     * artist), a bare title with the `#EXTART` artist, else what the [location]'s file name says. Null when no artist can be told.
     */
    private fun songOf(info: Info?, location: String?, album: String?, albumArtist: String?): WireSong? {
        val display = info?.display.orEmpty()
        val (artist, title) = PlaylistFileParser.artistTitle(display, albumArtist)
            ?: albumArtist?.takeIf { display.isNotEmpty() }?.let { it to display }
            ?: location?.let(PlaylistFileParser::fromLocation)
            ?: return null
        return PlaylistFileParser.song(title, artist, album, info?.durationMs)
    }
}
