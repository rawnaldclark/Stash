package com.stash.feature.record.importer

import android.util.Log
import com.stash.feature.record.archive.PhoneRows
import com.stash.feature.record.archive.RecordFlags
import com.stash.feature.record.importer.JsonPull.Token
import kotlinx.coroutines.CancellationException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A playlist of an export, as far as the preview counts it: its name and how many songs it holds (export.ts `ExportPlaylist`). */
data class ExportPlaylistCount(val name: String, val songs: Int)

/** What was read and what wasn't (export.ts `ExportBundle.files`): files read by kind, and the ones left alone. */
data class ExportFileCounts(
    val extended: Int = 0,
    val account: Int = 0,
    val playlists: Int = 0,
    val library: Int = 0,
    /** A Stash library file (the web reads the app's own export); the app's own plays are already here, so always 0. */
    val stash: Int = 0,
    val ignored: Int = 0,
) {
    /** Files of an export found at all (importer.ts `prepare`'s `found`). */
    val found: Int get() = extended + account + playlists + library + stash
}

/** An export, read (export.ts `ExportBundle`): its plays, its playlists' and libraries' counts, and what couldn't be read. */
internal class ExportBundle(
    val plays: ExportPlays,
    val playlists: List<ExportPlaylistCount>,
    /** Liked Songs (YourLibrary.json `tracks`). */
    val likes: Int,
    /** Songs hidden on Spotify (YourLibrary.json `bannedTracks`). */
    val hidden: Int,
    val files: ExportFileCounts,
    val unreadable: Int,
)

/** How far reading is: files done of all, bytes read of all (inflated), and the latest year of the plays read so far. */
internal fun interface ReadProgress {
    fun onRead(filesDone: Int, filesTotal: Int, bytesRead: Long, bytesTotal: Long, latestYear: Int?)
}

/**
 * The web's reading of a Spotify export (stash-player `src/lib/spotify/export.ts` `readExport` and its row rules), streamed: no
 * file is ever held whole, each JSON file is read a token at a time ([JsonPull]) and only the fields Stash keeps are copied out.
 *
 * - **Extended history** (`extendedRow`): a row with no track name or artist is no song (not an error); an incognito row is left
 *   out; a `ts` that doesn't parse, falls before 2006 or more than two days after [now], or an `ms_played` that is missing, below
 *   0 or above 6 h makes the row unreadable. `startedAt = endedAt − ms`.
 * - **Account data** (`accountRow`): `endTime` read as UTC to the minute (`Date.UTC`'s arithmetic), no id, no reasons.
 * - **Playlists and the library**: only counted (the preview's numbers); bringing them into the library is a later step.
 * - A row that isn't an object is unreadable; an array (an object to JavaScript, with no fields) is no song.
 *
 * `JSON.parse` reads a file whole, so a file that turns out not to be JSON halfway through gave the web no rows at all: its
 * plays, playlists and unreadable rows are rolled back, and it counts once as unreadable. Duplicate keys: the last one counts.
 */
internal class ExportReader(
    private val now: Long,
    /** Where a time with no offset is read (JavaScript reads it as local time); Spotify always writes UTC. */
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Throws when the reading should stop (the import was cancelled). */
    private val checkpoint: () -> Unit = {},
) {
    private val plays = ExportPlays()
    private var unreadable = 0
    private val playlists = ArrayList<ExportPlaylistCount>()
    private var likes = 0
    private var hidden = 0
    private var latestYear: Int? = null
    private var rowsSinceCheck = 0

    private fun bad() {
        unreadable++
    }

    fun read(entries: List<ExportEntry>, progress: ReadProgress? = null): ExportBundle {
        val wanted = entries.filter { !it.broken && ExportText.kindOf(it.name) != ExportKind.IGNORE }
        val broken = entries.count { it.broken }
        unreadable += broken
        val totalBytes = wanted.sumOf { maxOf(0L, it.size) }
        var doneBytes = 0L
        var extended = 0
        var account = 0
        var playlistFiles = 0
        var library = 0
        progress?.onRead(0, wanted.size, 0, totalBytes, null)
        for ((n, f) in wanted.withIndex()) {
            val kind = ExportText.kindOf(f.name)
            val mark = Mark(plays.size, unreadable, playlists.size, likes, hidden)
            val fileStart = doneBytes
            try {
                f.open().use { raw ->
                    val counted = Counting(raw) { read ->
                        doneBytes = fileStart + read
                        progress?.onRead(n, wanted.size, doneBytes, totalBytes, latestYear)
                    }
                    val j = JsonPull(InputStreamReader(counted, Charsets.UTF_8))
                    when (kind) {
                        ExportKind.EXTENDED, ExportKind.ACCOUNT -> historyFile(j, kind)
                        ExportKind.PLAYLISTS -> playlistsFile(j)
                        ExportKind.LIBRARY -> libraryFile(j)
                        ExportKind.IGNORE -> Unit
                    }
                }
                when (kind) {
                    ExportKind.EXTENDED -> extended++
                    ExportKind.ACCOUNT -> account++
                    ExportKind.PLAYLISTS -> playlistFiles++
                    ExportKind.LIBRARY -> library++
                    ExportKind.IGNORE -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not JSON (or not readable at all): the web's JSON.parse would have thrown before a single row
                Log.i(TAG, "a ${kind.name.lowercase()} file couldn't be read: ${e.javaClass.simpleName}")
                mark.restore()
                bad()
            }
            doneBytes = fileStart + maxOf(0L, f.size)
            progress?.onRead(n + 1, wanted.size, doneBytes, totalBytes, latestYear)
        }
        return ExportBundle(
            plays = plays,
            playlists = playlists.toList(),
            likes = likes,
            hidden = hidden,
            files = ExportFileCounts(extended, account, playlistFiles, library, stash = 0, ignored = entries.size - wanted.size - broken),
            unreadable = unreadable,
        )
    }

    /** What a file had added when it started, to take back if it turns out not to be JSON. */
    private inner class Mark(val plays: Int, val unreadable: Int, val playlists: Int, val likes: Int, val hidden: Int) {
        fun restore() {
            this@ExportReader.plays.truncate(plays)
            this@ExportReader.unreadable = unreadable
            while (this@ExportReader.playlists.size > playlists) this@ExportReader.playlists.removeAt(this@ExportReader.playlists.lastIndex)
            this@ExportReader.likes = likes
            this@ExportReader.hidden = hidden
        }
    }

    // ── history ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun historyFile(j: JsonPull, kind: ExportKind) {
        if (j.peek() != Token.BEGIN_ARRAY) {
            // JSON, but not a list of plays: one unreadable (once the whole file has parsed)
            j.skipValue()
            j.endDocument()
            bad()
            return
        }
        j.beginArray()
        while (j.hasNext()) {
            if (kind == ExportKind.EXTENDED) extendedRow(j) else accountRow(j)
            if (++rowsSinceCheck >= CHECK_EVERY) {
                rowsSinceCheck = 0
                checkpoint()
            }
        }
        j.endArray()
        j.endDocument()
    }

    /** export.ts `!r || typeof r !== 'object'`: null and every scalar is unreadable; an array is an object with none of the fields. */
    private fun rowObject(j: JsonPull): Boolean = when (j.peek()) {
        Token.BEGIN_OBJECT -> true
        Token.BEGIN_ARRAY -> { j.skipValue(); false }
        else -> { j.skipValue(); bad(); false }
    }

    /** A string field as `str` keeps it; null for anything that isn't a string. */
    private fun text(j: JsonPull): String? =
        if (j.peek() == Token.STRING) j.nextText(ExportText.MAX_TEXT) else { j.skipValue(); null }

    /** A number field; NaN for anything that isn't a number (export.ts `typeof … === 'number'`). */
    private fun number(j: JsonPull): Double =
        if (j.peek() == Token.NUMBER) j.nextNumber() else { j.skipValue(); Double.NaN }

    /** A boolean field; null for anything that isn't one. */
    private fun bool(j: JsonPull): Boolean? = when (j.peek()) {
        Token.TRUE, Token.FALSE -> j.nextBoolean()
        else -> { j.skipValue(); null }
    }

    /** A short string field as written (its first [max] units; [Raw.long] when there were more); null for a non-string. */
    private class Raw(val head: String, val long: Boolean) {
        /** JavaScript's truthiness of the string: not empty. */
        val nonEmpty: Boolean get() = head.isNotEmpty()

        fun isExactly(s: String) = !long && head == s
    }

    private fun raw(j: JsonPull, max: Int): Raw? =
        if (j.peek() == Token.STRING) Raw(j.nextString(max), j.truncated) else { j.skipValue(); null }

    private fun extendedRow(j: JsonPull) {
        if (!rowObject(j)) return
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var incognito = false
        var ts: Raw? = null
        var msPlayed = Double.NaN
        var uri: Raw? = null
        var rs: Raw? = null
        var re: Raw? = null
        var skipped: Boolean? = null
        var shuffle: Boolean? = null
        j.beginObject()
        while (j.hasNext()) {
            val name = j.nextName()
            if (j.truncated) { j.skipValue(); continue }
            when (name) {
                "master_metadata_track_name" -> title = text(j)
                "master_metadata_album_artist_name" -> artist = text(j)
                "master_metadata_album_album_name" -> album = text(j)
                "incognito_mode" -> incognito = bool(j) == true
                "ts" -> ts = raw(j, MAX_TS)
                "ms_played" -> msPlayed = number(j)
                "spotify_track_uri" -> uri = raw(j, MAX_URI)
                "reason_start" -> rs = raw(j, MAX_REASON)
                "reason_end" -> re = raw(j, MAX_REASON)
                "skipped" -> skipped = bool(j)
                "shuffle" -> shuffle = bool(j)
                else -> j.skipValue()
            }
        }
        j.endObject()

        // A podcast, an audiobook or a row with no song: not music, not an error
        if (title == null || artist == null) return
        if (incognito) return
        val endedAt = ts?.takeIf { !it.long }?.let { JsDate.parse(it.head, zone) } ?: Double.NaN
        val ms = playedMs(msPlayed) ?: return bad()
        val end = okTime(endedAt) ?: return bad()

        val chosen: Boolean? = rs?.takeIf { it.nonEmpty }?.let { it.isExactly("clickrow") || it.isExactly("playbtn") }
        val done: Boolean? = re?.takeIf { it.nonEmpty }?.let { it.isExactly("trackdone") }
        val skip: Boolean? = when {
            skipped == true || re?.isExactly("fwdbtn") == true -> true
            skipped == false || re?.nonEmpty == true -> false
            else -> null
        }
        var f = 0
        if (chosen == true) f = f or RecordFlags.CHOSEN
        if (done == true) f = f or RecordFlags.DONE
        if (skip == true) f = f or RecordFlags.SKIPPED
        if (shuffle == true) f = f or RecordFlags.SHUFFLE
        if (chosen != null || done != null) f = f or RecordFlags.REASONS
        val sid = uri?.let { ExportText.sidOf(it.head) }
        plays.add(title, artist, album, sid, end, ms, f)
        seen(end)
    }

    private fun accountRow(j: JsonPull) {
        if (!rowObject(j)) return
        var title: String? = null
        var artist: String? = null
        var endTime: Raw? = null
        var msPlayed = Double.NaN
        j.beginObject()
        while (j.hasNext()) {
            val name = j.nextName()
            if (j.truncated) { j.skipValue(); continue }
            when (name) {
                "trackName" -> title = text(j)
                "artistName" -> artist = text(j)
                "endTime" -> endTime = raw(j, MAX_REASON)
                "msPlayed" -> msPlayed = number(j)
                else -> j.skipValue()
            }
        }
        j.endObject()
        if (title == null || artist == null) return
        // The pattern is matched at the start only: the first characters are all it reads
        val endedAt = endTime?.let { minuteUtc(it.head) } ?: Double.NaN
        val ms = playedMs(msPlayed) ?: return bad()
        val end = okTime(endedAt) ?: return bad()
        plays.add(title, artist, null, null, end, ms, RecordFlags.ACCOUNT)
        seen(end)
    }

    /** `Math.round(ms_played)`, half up, when it is a number from 0 to 6 h; null otherwise (the row is unreadable). */
    private fun playedMs(v: Double): Long? {
        if (!v.isFinite()) return null
        val ms = Math.round(v)
        return if (ms < 0 || ms > MAX_PLAY_MS) null else ms
    }

    /** export.ts `okTime`: a finite time from 2006 to two days after now; null otherwise. */
    private fun okTime(t: Double): Long? {
        if (!t.isFinite()) return null
        val ms = t.toLong()
        return if (ms >= PhoneRows.MIN_T && ms <= now + PhoneRows.FUTURE_MS) ms else null
    }

    private fun seen(end: Long) {
        val y = Instant.ofEpochMilli(end).atOffset(ZoneOffset.UTC).year
        if (latestYear == null || y > latestYear!!) latestYear = y
    }

    /** export.ts `minuteUtc`: "2025-01-31 18:04" (seconds optional), read as UTC by `Date.UTC`. */
    private fun minuteUtc(s: String): Double {
        val m = MINUTE.matcher(s)
        if (!m.lookingAt()) return Double.NaN
        return JsDate.utc(
            m.group(1)!!.toInt(), m.group(2)!!.toInt() - 1, m.group(3)!!.toInt(),
            m.group(4)!!.toInt(), m.group(5)!!.toInt(), m.group(6)?.toInt() ?: 0,
        )
    }

    // ── playlists and the library (counted) ─────────────────────────────────────────────────────────────────────────

    /** export.ts `playlistsFile`: `{ playlists: [...] }`, the last `playlists` key counting; anything else is one unreadable. */
    private fun playlistsFile(j: JsonPull) {
        var found: Pair<List<ExportPlaylistCount>, Int>? = null
        if (j.peek() == Token.BEGIN_OBJECT) {
            j.beginObject()
            while (j.hasNext()) {
                val name = j.nextName()
                if (!j.truncated && name == "playlists") {
                    found = if (j.peek() == Token.BEGIN_ARRAY) readPlaylists(j) else { j.skipValue(); null }
                } else {
                    j.skipValue()
                }
            }
            j.endObject()
        } else {
            j.skipValue()
        }
        j.endDocument()
        val f = found ?: return bad()
        playlists += f.first
        repeat(f.second) { bad() }
    }

    /** The playlists of one `playlists` array, and how many of them had no name (each one unreadable). */
    private fun readPlaylists(j: JsonPull): Pair<List<ExportPlaylistCount>, Int> {
        val out = ArrayList<ExportPlaylistCount>()
        var nameless = 0
        j.beginArray()
        while (j.hasNext()) {
            if (j.peek() != Token.BEGIN_OBJECT) {
                j.skipValue()
                nameless++
                continue
            }
            var name: String? = null
            var songs = 0
            j.beginObject()
            while (j.hasNext()) {
                val key = j.nextName()
                if (j.truncated) { j.skipValue(); continue }
                when (key) {
                    "name" -> name = text(j)
                    "items" -> songs = if (j.peek() == Token.BEGIN_ARRAY) countItems(j) else { j.skipValue(); 0 }
                    else -> j.skipValue()
                }
            }
            j.endObject()
            if (name == null) nameless++ else out += ExportPlaylistCount(name, songs)
        }
        j.endArray()
        return out to nameless
    }

    /** How a playlist item's `track` (or `localTrack`) reads to JavaScript: an object, a truthy other value, or a falsy one. */
    private enum class Shape { OBJECT, TRUTHY, FALSY }

    /** Songs among a playlist's items: a track with a title and an artist, or a local file that names one (episodes left out). */
    private fun countItems(j: JsonPull): Int {
        var n = 0
        j.beginArray()
        while (j.hasNext()) {
            if (j.peek() != Token.BEGIN_OBJECT) {
                j.skipValue()
                continue
            }
            var track = Shape.FALSY
            var trackSong = false
            var local = Shape.FALSY
            var localSong = false
            j.beginObject()
            while (j.hasNext()) {
                val key = j.nextName()
                if (j.truncated) { j.skipValue(); continue }
                when (key) {
                    "track" -> {
                        track = shape(j)
                        trackSong = if (track == Shape.OBJECT) songObject(j, "trackName", "artistName") else false
                    }
                    "localTrack" -> {
                        local = shape(j)
                        localSong = if (local == Shape.OBJECT) localObject(j) else false
                    }
                    else -> j.skipValue()
                }
            }
            j.endObject()
            val song = if (track == Shape.OBJECT) trackSong else local == Shape.OBJECT && localSong
            if (song) n++
        }
        j.endArray()
        return n
    }

    /** The next value's [Shape]; an object or array is left to read, anything else is consumed. */
    private fun shape(j: JsonPull): Shape = when (j.peek()) {
        Token.BEGIN_OBJECT, Token.BEGIN_ARRAY -> Shape.OBJECT
        Token.STRING -> if (j.nextString(1).isNotEmpty()) Shape.TRUTHY else Shape.FALSY
        Token.NUMBER -> j.nextNumber().let { if (it != 0.0 && !it.isNaN()) Shape.TRUTHY else Shape.FALSY }
        Token.TRUE -> { j.nextBoolean(); Shape.TRUTHY }
        else -> { j.skipValue(); Shape.FALSY }
    }

    /** An object (or array) at the reader: whether its [titleKey] and [artistKey] both clean to words. */
    private fun songObject(j: JsonPull, titleKey: String, artistKey: String): Boolean {
        if (j.peek() == Token.BEGIN_ARRAY) {
            j.skipValue()
            return false
        }
        var title: String? = null
        var artist: String? = null
        j.beginObject()
        while (j.hasNext()) {
            val key = j.nextName()
            when {
                j.truncated -> j.skipValue()
                key == titleKey -> title = text(j)
                key == artistKey -> artist = text(j)
                else -> j.skipValue()
            }
        }
        j.endObject()
        return title != null && artist != null
    }

    /** A `localTrack` object (or array): whether its `uri` is a local file that names a song (export.ts `localSong`). */
    private fun localObject(j: JsonPull): Boolean {
        if (j.peek() == Token.BEGIN_ARRAY) {
            j.skipValue()
            return false
        }
        var uri: Raw? = null
        j.beginObject()
        while (j.hasNext()) {
            val key = j.nextName()
            if (!j.truncated && key == "uri") uri = raw(j, MAX_LOCAL_URI) else j.skipValue()
        }
        j.endObject()
        return ExportText.isLocalSong(uri?.head)
    }

    /** export.ts `libraryFile`: Liked Songs (`tracks`) and hidden songs (`bannedTracks`), the last of each key counting. */
    private fun libraryFile(j: JsonPull) {
        when (j.peek()) {
            Token.BEGIN_OBJECT -> {
                var l = 0
                var h = 0
                j.beginObject()
                while (j.hasNext()) {
                    val key = j.nextName()
                    when {
                        j.truncated -> j.skipValue()
                        key == "tracks" -> l = librarySongs(j)
                        key == "bannedTracks" -> h = librarySongs(j)
                        else -> j.skipValue()
                    }
                }
                j.endObject()
                j.endDocument()
                likes += l
                hidden += h
            }
            // An array is an object to JavaScript, with no tracks: nothing, and nothing wrong
            Token.BEGIN_ARRAY -> { j.skipValue(); j.endDocument() }
            else -> { j.skipValue(); j.endDocument(); bad() }
        }
    }

    /** export.ts `pick`: the songs of a list (each with a track and an artist); 0 for anything that isn't a list. */
    private fun librarySongs(j: JsonPull): Int {
        if (j.peek() != Token.BEGIN_ARRAY) {
            j.skipValue()
            return 0
        }
        var n = 0
        j.beginArray()
        while (j.hasNext()) {
            // libSong: an object with a track and an artist; null, an array or a scalar is no song (and nothing wrong)
            if (j.peek() == Token.BEGIN_OBJECT) {
                if (songObject(j, "track", "artist")) n++
            } else {
                j.skipValue()
            }
        }
        j.endArray()
        return n
    }

    /** Counts the bytes read from an entry, telling [onBytes] every [STEP] (the honest progress bar). */
    private class Counting(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        private var n = 0L
        private var told = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }

        private fun count(k: Long) {
            n += k
            if (n - told >= STEP) {
                told = n
                onBytes(n)
            }
        }

        private companion object {
            const val STEP = 256L * 1024
        }
    }

    companion object {
        private const val TAG = "YourRecord"

        /** One play longer than this is a broken row (export.ts `MAX_PLAY_MS`: a paused track whose clock ran on). */
        const val MAX_PLAY_MS = 6 * 3_600_000L

        /** Rows between two checks that the import should go on. */
        private const val CHECK_EVERY = 2_000

        /** The longest `ts` read whole (an ISO time is a few dozen characters; a longer one is no time). */
        private const val MAX_TS = 1_024

        /** A `reason_start`/`reason_end` or `endTime`: compared with a few short words, or matched at its start. */
        private const val MAX_REASON = 64

        /** A `spotify_track_uri`: the id is in its first characters. */
        private const val MAX_URI = 4_096

        private const val MAX_LOCAL_URI = 16_384

        /** export.ts `minuteUtc`'s pattern (ASCII digits, matched at the start). */
        private val MINUTE = java.util.regex.Pattern.compile("([0-9]{4})-([0-9]{2})-([0-9]{2})[ T]([0-9]{2}):([0-9]{2})(?::([0-9]{2}))?")
    }
}
