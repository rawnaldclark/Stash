package com.stash.feature.record.importer

import com.stash.core.data.record.ExportSong
import com.stash.feature.record.archive.PhoneRows
import java.util.regex.Pattern

/** What a file in an export is, by its name alone (export.ts `ExportFileKind`). */
enum class ExportKind { EXTENDED, ACCOUNT, PLAYLISTS, LIBRARY, IGNORE }

/**
 * The web's rules for an export's names and words (stash-player `src/lib/spotify/export.ts`), ported exactly: which file is
 * which, the Spotify track id in a uri, a local file's words, and the cleaning every kept word gets.
 *
 * The web's patterns are JavaScript regular expressions without the `u` flag: case-insensitive for ASCII letters only, `.`
 * any character but a line terminator, `$` the very end. So they are compiled here with [Pattern.CASE_INSENSITIVE] alone
 * (Kotlin's `IGNORE_CASE` adds Unicode case folding) and matched whole.
 */
internal object ExportText {
    /** Longest title, artist or album (export.ts `MAX_TEXT`), in UTF-16 units. */
    const val MAX_TEXT = PhoneRows.MAX_TEXT

    /** JavaScript's `.` without the `s` flag: anything but a line terminator. */
    private const val DOT = "[^\\n\\r\\u2028\\u2029]"

    private fun ci(p: String): Pattern = Pattern.compile(p, Pattern.CASE_INSENSITIVE)

    private val JSON = ci("$DOT*\\.json")
    private val EXTENDED = ci("Streaming_History_Audio_$DOT*\\.json")
    private val ENDSONG = ci("endsong_[0-9]+\\.json")
    private val ACCOUNT = ci("StreamingHistory_music_[0-9]+\\.json")
    private val ACCOUNT_OLD = ci("StreamingHistory[0-9]+\\.json")
    private val PLAYLISTS = ci("Playlist[0-9]*\\.json")
    private val LIBRARY = ci("YourLibrary\\.json")
    private val ZIP = ci("[\\s\\S]*\\.zip")
    private val LOOSE_JSON = ci("[\\s\\S]*\\.json")

    /**
     * export.ts `kindOf`: the base name (after the last `/` or `!`, the separator of a zip inside a zip) decides. `.` in the web's
     * `/\.json$/i` is any character but a line terminator, so a name with one in it is ignored there too.
     */
    fun kindOf(path: String): ExportKind {
        val base = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('!')) + 1)
        if (!JSON.matcher(base).matches()) return ExportKind.IGNORE
        return when {
            EXTENDED.matcher(base).matches() || ENDSONG.matcher(base).matches() -> ExportKind.EXTENDED
            ACCOUNT.matcher(base).matches() || ACCOUNT_OLD.matcher(base).matches() -> ExportKind.ACCOUNT
            PLAYLISTS.matcher(base).matches() -> ExportKind.PLAYLISTS
            LIBRARY.matcher(base).matches() -> ExportKind.LIBRARY
            else -> ExportKind.IGNORE
        }
    }

    /** importer.ts `openFiles` and zip.ts: a name ending `.zip` (any case) is a zip; one ending `.json`, a loose file. */
    fun isZipName(name: String): Boolean = ZIP.matcher(name).matches()

    fun isJsonName(name: String): Boolean = LOOSE_JSON.matcher(name).matches()

    /** export.ts `str`. */
    fun str(v: String?): String? = PhoneRows.str(v)

    private val SID_URI = Pattern.compile("spotify:track:([0-9A-Za-z]{22})")
    private val SID_LINK = Pattern.compile("open\\.spotify\\.com/track/([0-9A-Za-z]{22})")

    /** export.ts `sidOf`: the 22 characters after `spotify:track:` (the whole uri), or in an `open.spotify.com/track/` link. */
    fun sidOf(uri: String?): String? {
        if (uri == null) return null
        SID_URI.matcher(uri).let { if (it.matches()) return it.group(1) }
        SID_LINK.matcher(uri).let { if (it.find()) return it.group(1) }
        return null
    }

    /**
     * export.ts `localSong`: the song `spotify:local:Artist:Album:Title:Seconds` names (a local file in a playlist; each part
     * URL-encoded, `+` a space, then cleaned like `str`), or null when it names no title or no artist. A part that won't decode
     * (`decodeURIComponent` throws) is no part: no album, or no song.
     */
    fun localSong(uri: String?): ExportSong? {
        if (uri == null || !uri.startsWith(LOCAL)) return null
        val parts = uri.substring(LOCAL.length).split(':')
        if (parts.size < 3) return null
        fun dec(s: String?): String? = decodeUriComponent((s ?: "").replace('+', ' '))?.let(::str)
        val artist = dec(parts[0])
        val album = dec(parts[1])
        val title = dec(parts[2])
        if (title == null || artist == null) return null
        return ExportSong(title, artist, album)
    }

    private const val LOCAL = "spotify:local:"

    /**
     * JavaScript's `decodeURIComponent`: `%XX` escapes decoded as UTF-8; null where it throws a URIError (a `%` not followed by
     * two hex digits, bytes that aren't well-formed UTF-8, an encoded surrogate or overlong form). Everything else stays as it is.
     */
    fun decodeUriComponent(s: String): String? {
        if (s.indexOf('%') < 0) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            val b0 = hexByte(s, i) ?: return null
            i += 3
            if (b0 < 0x80) {
                out.append(b0.toChar())
                continue
            }
            val n = when {
                b0 and 0xE0 == 0xC0 -> 2
                b0 and 0xF0 == 0xE0 -> 3
                b0 and 0xF8 == 0xF0 -> 4
                else -> return null
            }
            var cp = b0 and (0xFF shr (n + 1))
            repeat(n - 1) {
                if (i >= s.length || s[i] != '%') return null
                val b = hexByte(s, i) ?: return null
                if (b and 0xC0 != 0x80) return null
                cp = (cp shl 6) or (b and 0x3F)
                i += 3
            }
            val min = when (n) { 2 -> 0x80; 3 -> 0x800; else -> 0x10000 }
            if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return null
            out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun hexByte(s: String, at: Int): Int? {
        if (at + 2 >= s.length) return null
        val h = hex(s[at + 1]) ?: return null
        val l = hex(s[at + 2]) ?: return null
        return h * 16 + l
    }

    private fun hex(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }
}
