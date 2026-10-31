package com.stash.core.data.playlistfile

import com.stash.core.data.weblink.handoff.WireSong
import kotlin.math.roundToLong

/**
 * PLS, Winamp's and iTunes' other playlist (named in issue #61): a `[playlist]` section of numbered `FileN=`, `TitleN=`
 * ("Artist - Title") and `LengthN=` (seconds, `-1` unknown) entries, in the order of their numbers. A song without a title is
 * read from its file's name, as an M3U's is. Read as plain text, a PLS would give artists like "Title1=Radiohead".
 */
internal object PlsPlaylist {
    private class Entry(var file: String? = null, var title: String? = null, var seconds: Double? = null)

    fun read(text: String, limit: Int): ReadPlaylist {
        val entries = sortedMapOf<Int, Entry>()
        for (raw in text.lineSequence()) {
            val m = KEY.matchEntire(raw.trim()) ?: continue
            val n = m.groupValues[2].toIntOrNull() ?: continue
            // A hostile file can't make a map of millions: entries past the cap are never songs.
            if (n !in entries && entries.size > limit) continue
            val e = entries.getOrPut(n) { Entry() }
            val value = m.groupValues[3].trim()
            when (m.groupValues[1].lowercase()) {
                "file" -> e.file = value
                "title" -> e.title = value
                "length" -> e.seconds = value.toDoubleOrNull()
            }
        }
        val songs = ArrayList<WireSong>()
        for (e in entries.values) {
            if (songs.size >= limit) break
            val (artist, title) = e.title?.let { PlaylistFileParser.artistTitle(it) }
                ?: e.file?.let(PlaylistFileParser::fromLocation)
                ?: continue
            val durationMs = e.seconds?.takeIf { it > 0 && it.isFinite() }?.let { (it * 1000).roundToLong() }
            PlaylistFileParser.song(title, artist, album = null, durationMs = durationMs)?.let(songs::add)
        }
        return ReadPlaylist(null, songs)
    }

    /** `File1=…`, `Title1=…`, `Length1=…`; `NumberOfEntries` and `Version` say nothing about a song. */
    private val KEY = Regex("^(File|Title|Length)(\\d{1,6})\\s*=(.*)$", RegexOption.IGNORE_CASE)
}
