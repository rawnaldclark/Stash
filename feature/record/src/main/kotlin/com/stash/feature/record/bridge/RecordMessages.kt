package com.stash.feature.record.bridge

import com.stash.feature.record.archive.ArchivePlay
import com.stash.feature.record.archive.JsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A song the record asks Stash to play: its words only (Stash finds it on its sources when it plays). */
data class RecordSong(val title: String, val artist: String, val album: String? = null)

/** A cover the record asks for (the fork's src/stash/covers.ts): its name there, and what it is. No title: an artist. */
data class RecordArtAsk(val id: String, val artist: String, val title: String? = null, val album: String? = null) {
    val isArtist: Boolean get() = title == null
}

/** What the record said, once read: every field checked (stash-player src/lib/ui/spotify/record.ts `RecordAsk`, plus hello). */
sealed interface RecordAsk {
    /** Bridge protocol v1: the page's listener is live (on load, and again after `stash:reload`). [v] is its version. */
    data class Hello(val v: Int) : RecordAsk

    data class Ready(val plays: Int) : RecordAsk

    data class Counts(val spotify: Int, val stash: Int, val lastfm: Int) : RecordAsk

    data class Play(val songs: List<RecordSong>, val index: Int, val from: String) : RecordAsk

    data object Import : RecordAsk

    /** Its × or a back with nothing of its own open: back to Stash. */
    data object Close : RecordAsk

    data class Art(val asks: List<RecordArtAsk>) : RecordAsk

    /**
     * One chunk of a file the record made (a poster, a race video) to save to Downloads or to share ([share]): [data] is base64
     * of at most [RecordMessages.MAX_SAVE_CHUNK] bytes, [seq] counts the file's chunks from 0, [done] marks its last.
     */
    data class Save(
        val id: String,
        val name: String,
        val mime: String,
        val share: Boolean,
        val seq: Int,
        val done: Boolean,
        val data: String,
    ) : RecordAsk

    /** The page gave up on file [id] (no ack in time, or it is going away mid-file): what was gathered goes. */
    data class SaveAbort(val id: String) : RecordAsk

    /** The record closed what it had open after `stash:back` (it says `stash:close` instead when nothing was). */
    data object Backed : RecordAsk
}

/**
 * The bridge's messages, both ways (PLAN "Bridge protocol v1"; the payloads are the fork's embed.ts ones). Reading ports the web
 * player's `readAsk` rule for rule: nothing the page sends is trusted (lists capped, strings cut, the wrong shape dropped).
 * Writing makes JSON exactly as `JSON.stringify` would, lone surrogates escaped, so a title cut through a pair (the web cuts
 * UTF-16 units too) crosses intact and its play keeps its id.
 */
object RecordMessages {
    /** The most songs one play asks for (record.ts `MAX_SONGS`). */
    const val MAX_SONGS = 2000

    /** Longest title, artist or album kept (record.ts `MAX_TEXT`). */
    const val MAX_TEXT = 300

    /** Longest "Playing from" label kept. */
    const val MAX_FROM = 120

    /** The label when the record names none. */
    const val FROM = "Your record"

    /** Covers asked per message, at most (record.ts `MAX_ART`). */
    const val MAX_ART = 150

    /** Longer than this, it isn't one of the record's messages (2,000 songs of 3 × 300 characters is well under it). */
    const val MAX_MESSAGE = 4 * 1024 * 1024

    const val PROTOCOL = 1

    /** The most bytes one `stash:save` chunk carries (512 KiB), and so the longest its base64 can be. */
    const val MAX_SAVE_CHUNK = 512 * 1024
    private const val MAX_SAVE_DATA = (MAX_SAVE_CHUNK + 2) / 3 * 4

    /** A file's id and name, at most. */
    private const val MAX_SAVE_ID = 128
    private const val MAX_SAVE_NAME = 200

    /**
     * The files the record makes: its posters and its race video, and a file the page couldn't type (the fork falls back to
     * `application/octet-stream`). Anything else isn't saved.
     */
    val SAVE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "video/mp4", "video/webm", "application/octet-stream")

    /** The record's message, or null for anything that isn't one of them. */
    fun read(raw: String): RecordAsk? {
        if (raw.length > MAX_MESSAGE) return null
        val m = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        return when (m.string("type")) {
            "stash:hello" -> m.number("v")?.takeIf { it >= PROTOCOL && it == Math.floor(it) && it <= Int.MAX_VALUE }?.let { RecordAsk.Hello(it.toInt()) }
            "stash:import" -> RecordAsk.Import
            "stash:close" -> RecordAsk.Close
            "stash:ready" -> RecordAsk.Ready(plays(m["plays"]))
            "stash:counts" -> (m["counts"] as? JsonObject)?.let { RecordAsk.Counts(plays(it["spotify"]), plays(it["stash"]), plays(it["lastfm"])) }
            "stash:art" -> art(m)
            "stash:play" -> play(m)
            "stash:save" -> save(m)
            "stash:save-abort" -> m.string("id")?.takeIf { it.isNotEmpty() && it.length <= MAX_SAVE_ID }?.let { RecordAsk.SaveAbort(it) }
            "stash:backed" -> RecordAsk.Backed
            else -> null
        }
    }

    private fun art(m: JsonObject): RecordAsk? {
        val list = m["asks"] as? JsonArray ?: return null
        val asks = ArrayList<RecordArtAsk>()
        for (a in list.take(MAX_ART)) {
            val o = a as? JsonObject ?: JsonObject(emptyMap())
            val id = o.string("id")?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT * 2 + 1 } ?: continue
            val artist = line(o["artist"], MAX_TEXT).ifEmpty { continue }
            val title = line(o["title"], MAX_TEXT)
            val album = line(o["album"], MAX_TEXT)
            // A name with no title is an artist (their picture), else a song or an album
            asks += if (title.isNotEmpty()) RecordArtAsk(id, artist, title, album.ifEmpty { null }) else RecordArtAsk(id, artist)
        }
        return if (asks.isEmpty()) null else RecordAsk.Art(asks)
    }

    /** A play keeps its songs, and its index still names the song it named: a bad song before it moves it, one at it starts on the next. */
    private fun play(m: JsonObject): RecordAsk? {
        val list = m["songs"] as? JsonArray ?: return null
        val at = m.number("index")?.takeIf { it == Math.floor(it) } ?: 0.0
        val songs = ArrayList<RecordSong>()
        var index = 0
        for (i in 0 until minOf(list.size, MAX_SONGS)) {
            val s = song(list[i]) ?: continue
            if (i < at) index++
            songs += s
        }
        if (songs.isEmpty()) return null
        return RecordAsk.Play(songs, minOf(index, songs.lastIndex), line(m["from"], MAX_FROM).ifEmpty { FROM })
    }

    private fun save(m: JsonObject): RecordAsk? {
        val id = m.string("id")?.takeIf { it.isNotEmpty() && it.length <= MAX_SAVE_ID } ?: return null
        val name = m.string("name")?.takeIf { it.isNotBlank() && it.length <= MAX_SAVE_NAME } ?: return null
        val mime = m.string("mime")?.takeIf { it in SAVE_TYPES } ?: return null
        val share = when (m.string("action")) {
            "save" -> false
            "share" -> true
            else -> return null
        }
        val seq = m.number("seq")?.takeIf { it >= 0 && it == Math.floor(it) && it <= Int.MAX_VALUE }?.toInt() ?: return null
        val done = (m["done"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: return null
        val data = m.string("data")?.takeIf { it.length <= MAX_SAVE_DATA } ?: return null
        return RecordAsk.Save(id, name, mime, share, seq, done, data)
    }

    private fun song(v: JsonElement): RecordSong? {
        val o = v as? JsonObject ?: return null
        val title = line(o["title"], MAX_TEXT).ifEmpty { return null }
        val artist = line(o["artist"], MAX_TEXT).ifEmpty { return null }
        return RecordSong(title, artist, line(o["album"], MAX_TEXT).ifEmpty { null })
    }

    /** record.ts `line`: a string as one clean line (control runs a space, trimmed, cut to [max], trimmed again); '' otherwise. */
    internal fun line(v: JsonElement?, max: Int): String {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return ""
        val spaced = CONTROL_RUN.replace(JsText.slice(s, max * 2), " ")
        return JsText.trim(JsText.slice(JsText.trim(spaced), max))
    }

    /** record.ts `plays`: a whole number of plays, 0 for anything else. */
    private fun plays(v: JsonElement?): Int {
        val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull() ?: return 0
        return if (n.isFinite() && n > 0) minOf(Math.floor(n), Int.MAX_VALUE.toDouble()).toInt() else 0
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number (never a string or a boolean), finite. */
    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private val CONTROL_RUN = Regex("[\\u0000-\\u001f\\u007f]+")

    // ── Host → record ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** `stash:archive {rows, seq, done}`: one chunk of the archive; [seq] counts the chunks of one hello from 0. */
    fun archive(rows: List<ArchivePlay>, seq: Int, done: Boolean): String = buildString(rows.size * 140 + 64) {
        append("{\"type\":\"stash:archive\",\"rows\":[")
        rows.forEachIndexed { i, r ->
            if (i > 0) append(',')
            append("{\"id\":").quote(r.id)
            append(",\"t\":").quote(r.t)
            append(",\"a\":").quote(r.a)
            r.al?.let { append(",\"al\":").quote(it) }
            r.sid?.let { append(",\"sid\":").quote(it) }
            append(",\"s\":").append(r.s)
            append(",\"ms\":").append(r.ms)
            append(",\"f\":").append(r.f)
            append('}')
        }
        append("],\"seq\":").append(seq).append(",\"done\":").append(done).append('}')
    }

    fun theme(light: Boolean): String = "{\"type\":\"stash:theme\",\"theme\":\"${if (light) "light" else "dark"}\"}"

    /** Back pressed: the record closes what it has open on top and says `stash:backed`, or `stash:close` when nothing was. */
    const val BACK = "{\"type\":\"stash:back\"}"

    /** `stash:save-ack {id, seq}`: chunk [seq] of file [id] is in the file; the page may send more (it keeps two in flight). */
    fun saveAck(id: String, seq: Int): String = buildString(id.length + 48) {
        append("{\"type\":\"stash:save-ack\",\"id\":").quote(id).append(",\"seq\":").append(seq).append('}')
    }

    /** `stash:covers`: the covers asked for, by the record's own names; null for none known. */
    fun covers(list: List<Pair<String, String?>>): String = buildString(list.size * 120 + 40) {
        append("{\"type\":\"stash:covers\",\"covers\":[")
        list.forEachIndexed { i, (id, url) ->
            if (i > 0) append(',')
            append("{\"id\":").quote(id).append(",\"url\":")
            if (url == null) append("null") else quote(url)
            append('}')
        }
        append("]}")
    }

    /** [s] as `JSON.stringify` writes a string: quotes, backslashes and control characters escaped, and a lone surrogate too. */
    internal fun StringBuilder.quote(s: String): StringBuilder {
        append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> hex(c)
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                    append(c).append(s[i + 1])
                    i++
                }
                c.isSurrogate() -> hex(c)
                else -> append(c)
            }
            i++
        }
        return append('"')
    }

    private fun StringBuilder.hex(c: Char) {
        append("\\u")
        val h = Integer.toHexString(c.code)
        repeat(4 - h.length) { append('0') }
        append(h)
    }
}
