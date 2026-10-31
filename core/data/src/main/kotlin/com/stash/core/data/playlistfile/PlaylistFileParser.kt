package com.stash.core.data.playlistfile

import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.model.weblink.SongKey
import java.net.URLDecoder
import kotlin.math.roundToLong

/** The kinds of playlist file "+" › Import a playlist file reads (issue #61). */
enum class PlaylistFileFormat { M3U, PLS, XSPF, CSV, TEXT }

/**
 * A playlist file, read: the name it gives itself (M3U `#PLAYLIST:`, XSPF's `<title>`, a CSV's one "Playlist name"), else null;
 * its songs in order, at most [PlaylistFileParser.MAX_SONGS]; and [cut] when it held more than that, or more than was read.
 */
data class PlaylistFileContent(
    val title: String?,
    val format: PlaylistFileFormat,
    val songs: List<WireSong>,
    val cut: Boolean,
)

/** What one format's reader found: the name the file gives itself, and its songs. */
internal class ReadPlaylist(val title: String?, val songs: List<WireSong>)

/**
 * Reads a playlist file another app or service exported (issue #61): Exportify, TuneMyMusic and Soundiiz spreadsheets, Apple
 * Music's exported text (tab-separated), M3U / M3U8 and PLS from players, XSPF, and plain "Artist - Title" lists. Pure Kotlin: no file
 * access ([PlaylistFileImport] reads the document) and no network. Each song is a [WireSong] for the link import's matcher, with
 * its ISRC or Spotify id when the file has one (matched by identity), else its words alone.
 */
object PlaylistFileParser {
    /** The most songs one file brings in. */
    const val MAX_SONGS = 2_000

    /**
     * Reads [bytes], the file named [fileName] ([truncated]: only its start). The format is told by what the file holds, its
     * extension only when that says nothing: an Apple Music export is a .txt that is really a spreadsheet, and an .m3u may be
     * saved as .txt.
     */
    fun parse(bytes: ByteArray, fileName: String?, truncated: Boolean = false): PlaylistFileContent {
        val decoded = PlaylistFileText.decode(bytes, truncated)
        val format = formatOf(decoded, fileName)
        // The line a cut file stops in is half a line: never a song. XSPF's whole tracks are found as they are.
        val text = if (truncated && format != PlaylistFileFormat.XSPF) decoded.substringBeforeLast('\n', "") else decoded
        val limit = MAX_SONGS + 1 // one past the cap tells that there was more
        val read = when (format) {
            PlaylistFileFormat.M3U -> M3uPlaylist.read(text, limit)
            PlaylistFileFormat.PLS -> PlsPlaylist.read(text, limit)
            PlaylistFileFormat.XSPF -> XspfPlaylist.read(text, limit)
            PlaylistFileFormat.CSV -> CsvPlaylist.read(text, limit)
            PlaylistFileFormat.TEXT -> TextPlaylist.read(text, limit)
        }
        return PlaylistFileContent(
            title = read.title?.let(::clean)?.takeIf { it.isNotEmpty() },
            format = format,
            songs = read.songs.take(MAX_SONGS),
            cut = truncated || read.songs.size > MAX_SONGS,
        )
    }

    internal fun formatOf(text: String, fileName: String?): PlaylistFileFormat {
        val head = text.trimStart().take(SNIFF_CHARS)
        if (head.startsWith("#EXTM3U", ignoreCase = true) || EXTINF_LINE.containsMatchIn(head)) return PlaylistFileFormat.M3U
        if (head.startsWith("[playlist]", ignoreCase = true)) return PlaylistFileFormat.PLS
        if (head.startsWith("<") && (TRACK_LIST.containsMatchIn(head) || head.contains("xspf.org/ns", ignoreCase = true))) {
            return PlaylistFileFormat.XSPF
        }
        if (CsvPlaylist.recognises(head)) return PlaylistFileFormat.CSV
        return when (fileName?.substringAfterLast('.', "")?.lowercase()) {
            "m3u", "m3u8" -> PlaylistFileFormat.M3U
            "pls" -> PlaylistFileFormat.PLS
            "xspf" -> PlaylistFileFormat.XSPF
            "csv", "tsv" -> PlaylistFileFormat.CSV
            else -> PlaylistFileFormat.TEXT
        }
    }

    // ------------------------------------------------------------------------------------------- shared by the readers

    /** A song from a file's words, cleaned as the web library file's are; null without a title or an artist. */
    internal fun song(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long?,
        isrc: String? = null,
        spotifyId: String? = null,
    ): WireSong? {
        val t = clean(title).takeIf { it.isNotEmpty() } ?: return null
        val a = clean(artist).takeIf { it.isNotEmpty() } ?: return null
        return WireSong(
            title = t,
            artist = a,
            album = album?.let(::clean)?.takeIf { it.isNotEmpty() },
            durationMs = durationMs?.takeIf { it in 1..MAX_DURATION_MS },
            isrc = isrc,
            spotifyId = spotifyId,
        )
    }

    /** The web's cleaning (controls and zero-width characters out, whitespace runs one space), cut as its song text is. */
    internal fun clean(s: String): String = HandoffWire.cleanText(s, MAX_TEXT)

    /**
     * "Artist - Title", split at the first separator (a title may hold one of its own: "Song - 2011 Remaster"). When the file
     * named the artist ([knownArtist], an M3U's `#EXTART`) and the text ends with that artist, it is "Title - Artist". Null
     * without a separator or with nothing on one side of it.
     */
    internal fun artistTitle(text: String, knownArtist: String? = null): Pair<String, String>? {
        val first = SEPARATOR.find(text) ?: return null
        val known = knownArtist?.let(SongKey::fold)?.takeIf { it.isNotEmpty() }
        if (known != null) {
            val last = SEPARATOR.findAll(text).last()
            val tail = text.substring(last.range.last + 1).trim()
            val head = text.substring(0, first.range.first).trim()
            if (SongKey.fold(tail) == known && SongKey.fold(head) != known) {
                val title = text.substring(0, last.range.first).trim()
                return if (title.isEmpty()) null else tail to title
            }
        }
        val artist = text.substring(0, first.range.first).trim()
        val title = text.substring(first.range.last + 1).trim()
        return if (artist.isEmpty() || title.isEmpty()) null else artist to title
    }

    /**
     * What a song's location says when nothing else does: its file name read as "Artist - Title" ("03 - Daft Punk - Digital
     * Love.mp3"; a URL's decoded). Null when the name has no separator: a path alone doesn't say who the artist is.
     */
    internal fun fromLocation(location: String): Pair<String, String>? {
        val path = if (location.contains("://") || location.startsWith("file:", ignoreCase = true)) {
            percentDecoded(location.substringBefore('?').substringBefore('#'))
        } else {
            location
        }
        val name = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        val words = TRACK_NUMBER.replace(FILE_EXTENSION.replace(name, ""), "").replace('_', ' ').trim()
        return artistTitle(words)
    }

    /** An ISRC as exports write it ("USUM71703861", "US-UM7-17-03861", "isrc:…"), upper-cased; null for anything else. */
    internal fun isrcOf(raw: String?): String? {
        val s = raw?.filterNot { it == '-' || it.isWhitespace() }?.uppercase()?.removePrefix("ISRC:") ?: return null
        return s.takeIf(ISRC::matches)
    }

    /** A Spotify track's id from its URI or link ("spotify:track:…", "https://open.spotify.com/track/…"), or a bare id when [bare]. */
    internal fun spotifyIdOf(raw: String?, bare: Boolean = false): String? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        SPOTIFY_TRACK.find(s)?.let { return it.groupValues[1] }
        return s.takeIf { bare && SPOTIFY_ID.matches(it) }
    }

    /**
     * A length as exports write it: "3:45" or "1:02:03"; milliseconds when [ms] says so (Exportify's "Track Duration (ms)",
     * XSPF's `<duration>`); else whole seconds (Apple Music's "Time"), unless the number is too big to be seconds.
     */
    internal fun durationOf(raw: String?, ms: Boolean): Long? {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (':' in s) {
            val parts = s.split(':').map { it.trim().toDoubleOrNull() ?: return null }
            if (parts.size > 3) return null
            val seconds = parts.fold(0.0) { total, part -> total * 60 + part }
            return (seconds * 1000).roundToLong().takeIf { it in 1..MAX_DURATION_MS }
        }
        val n = s.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: return null
        val millis = if (ms || n >= SECONDS_LIMIT) n else n * 1000
        return millis.roundToLong().takeIf { it in 1..MAX_DURATION_MS }
    }

    private fun percentDecoded(s: String): String = try {
        // A "+" in a path is a plus, not a space.
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }

    /** As the web library file's song text ([HandoffWire]'s title and artist). */
    private const val MAX_TEXT = 300
    private const val MAX_DURATION_MS = 24L * 3600 * 1000

    /** A length with no unit this big is milliseconds: no song runs ten hours. */
    private const val SECONDS_LIMIT = 36_000

    /** How far into a file its format is looked for. */
    private const val SNIFF_CHARS = 64 * 1024

    private val EXTINF_LINE = Regex("^\\s*#EXTINF:", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
    private val TRACK_LIST = Regex("<(?:[\\w.-]+:)?trackList\\b", RegexOption.IGNORE_CASE)

    /** " - ", " – " or " — ", with space around it: Yandex Music's lists use the em dash; "Jay-Z" is no separator. */
    private val SEPARATOR = Regex("\\s+[-\\u2013\\u2014]\\s+")

    /** An audio file's extension ("….mp3", "….flac"); "Mr. Brightside" has none. */
    private val FILE_EXTENSION = Regex("\\.[A-Za-z0-9]{2,5}$")

    /** A track number before a file name's words: "1-02 ", "02 - ", "02. ", "02_", "02 " (only zero-padded: "99 Problems" stays). */
    private val TRACK_NUMBER = Regex("^(?:\\d{1,2}-\\d{1,3}\\s+|\\d{1,3}\\s*[-._)]\\s*|0\\d\\s+)")

    private val ISRC = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")
    private val SPOTIFY_TRACK = Regex("(?:spotify:track:|open\\.spotify\\.com/(?:intl-[A-Za-z-]+/)?track/)([A-Za-z0-9]{22})")
    private val SPOTIFY_ID = Regex("^[A-Za-z0-9]{22}$")
}
