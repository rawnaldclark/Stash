package com.stash.core.data.weblink.handoff

import com.stash.core.data.weblibrary.WebLibraryFile
import com.stash.core.model.weblink.SongIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A song on the handoff wire (sync-v1 §2.1): the library file's Song plus the sync-only `phoneOnly` flag. Read back with the
 * web's `readBackup` rules ([HandoffWire.readSong]), so what arrives here is always clean.
 */
data class WireSong(
    override val title: String,
    override val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    override val isrc: String? = null,
    val spotifyId: String? = null,
    /** Web source id → that source's id; `youtube` is the one this phone plays. */
    val refs: Map<String, String> = emptyMap(),
    val artwork: List<String> = emptyList(),
    val addedAt: Long? = null,
    /** Its only source is a file on the phone: the web leaves it out of a handoff (spec §2.5). */
    val phoneOnly: Boolean = false,
) : SongIdentity {
    val youtubeId: String? get() = refs[WebLibraryFile.YOUTUBE_SOURCE]

    companion object {
        fun of(song: WebLibraryFile.Song, phoneOnly: Boolean) = WireSong(
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            isrc = song.isrc,
            spotifyId = song.spotifyId,
            refs = song.refs.orEmpty(),
            artwork = song.artwork.orEmpty().map { it.url },
            addedAt = song.addedAt,
            phoneOnly = phoneOnly,
        )
    }
}

enum class HandoffRepeat(val wire: String) {
    OFF("off"), ALL("all"), ONE("one");

    companion object {
        fun of(wire: String?): HandoffRepeat? = entries.firstOrNull { it.wire == wire }
    }
}

/** `stash-now` v1 (sync-v1 §5.1): what a device is playing, small and published often. */
data class StashNow(
    val playing: Boolean,
    val positionMs: Long,
    val rate: Double = 1.0,
    val index: Int,
    val queueId: String,
    val song: WireSong?,
    val shuffle: Boolean,
    val repeat: HandoffRepeat,
    val from: String? = null,
    val leftOut: Int = 0,
)

/**
 * `stash-queue` v1 (sync-v1 §5.1): the queue in play order (what Up next shows), at most 2,000. An item a reader couldn't read
 * stays as null so [index] and [original] keep pointing at the right songs; the restore skips it.
 */
data class StashQueue(
    val id: String,
    val items: List<WireSong?>,
    val index: Int,
    val offset: Int = 0,
    /** With shuffle on: the unshuffled order as indexes into [items] (a permutation), else null. */
    val original: List<Int>? = null,
)

class HandoffWireException(message: String, val newer: Boolean = false) : Exception(message)

/**
 * Writers and readers of the handoff documents (sync-v1 §1, §2.1, §5.1). Readers refuse another kind and a higher `v`, ignore
 * unknown fields, check every value by its JSON type and rebuild what they keep, as the web's `readBackup` does.
 */
object HandoffWire {
    const val NOW_KIND = "stash-now"
    const val QUEUE_KIND = "stash-queue"
    const val VERSION = 1
    const val MAX_QUEUE = 2_000

    /** A queue longer than [MAX_QUEUE] goes as a window that starts this many songs before the current one (sync-v1 §5.1). */
    const val WINDOW_BEFORE = 100

    private const val MAX_TITLE = 300
    private const val MAX_FROM = 300
    private const val MAX_REF = 200
    private const val MAX_REFS = 16
    private const val MAX_ART = 8
    private const val MAX_URL = 2048
    private const val MAX_DURATION_MS = 24L * 3600 * 1000
    private val ISRC = Regex("^[A-Za-z0-9]{12}$")
    private val SPOTIFY_ID = Regex("^[A-Za-z0-9]{22}$")
    private val REF_KEY = Regex("^[A-Za-z0-9._-]{1,64}$")
    private val QUEUE_ID = Regex("^q_[A-Za-z0-9_-]{1,64}$")

    private val json = Json { ignoreUnknownKeys = true }

    // ---------------------------------------------------------------------------------------- writing

    fun songJson(s: WireSong): JsonObject = buildJsonObject {
        put("title", s.title)
        put("artist", s.artist)
        s.album?.let { put("album", it) }
        s.durationMs?.let { put("durationMs", it) }
        s.isrc?.let { put("isrc", it) }
        s.spotifyId?.let { put("spotifyId", it) }
        if (s.refs.isNotEmpty()) putJsonObject("refs") { s.refs.forEach { (k, v) -> put(k, v) } }
        if (s.artwork.isNotEmpty()) putJsonArray("artwork") { s.artwork.forEach { u -> addJsonObject { put("url", u) } } }
        s.addedAt?.let { put("addedAt", it) }
        if (s.phoneOnly) put("phoneOnly", true)
    }

    fun nowJson(n: StashNow): String = buildJsonObject {
        put("kind", NOW_KIND)
        put("v", VERSION)
        put("playing", n.playing)
        put("positionMs", n.positionMs.coerceAtLeast(0))
        put("rate", n.rate)
        put("index", n.index)
        put("queueId", n.queueId)
        n.song?.let { put("song", songJson(it)) }
        put("shuffle", n.shuffle)
        put("repeat", n.repeat.wire)
        n.from?.takeIf { it.isNotBlank() }?.let { put("from", it) }
        if (n.leftOut > 0) put("leftOut", n.leftOut)
    }.toString()

    fun queueJson(q: StashQueue): String = buildJsonObject {
        put("kind", QUEUE_KIND)
        put("v", VERSION)
        put("id", q.id)
        putJsonArray("items") { q.items.forEach { s -> requireNotNull(s) { "a written queue has every song" }; add(songJson(s)) } }
        put("index", q.index)
        put("offset", q.offset)
        q.original?.let { o -> putJsonArray("original") { o.forEach { add(JsonPrimitive(it)) } } }
    }.toString()

    // ---------------------------------------------------------------------------------------- reading

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    /** An integer (a JSON number with no fraction), or null. */
    private fun JsonObject.long(k: String): Long? {
        val p = (this[k] as? JsonPrimitive)?.takeUnless { it.isString } ?: return null
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: return null
        return if (d.isFinite() && d == Math.floor(d) && kotlin.math.abs(d) < 9.0e15) d.toLong() else null
    }

    private fun JsonObject.num(k: String): Double? =
        (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun root(text: String, kind: String): JsonObject {
        val o = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw HandoffWireException("not a document")
        if (o.str("kind") != kind) throw HandoffWireException("not a $kind")
        val v = o.long("v") ?: throw HandoffWireException("no version")
        if (v > VERSION) throw HandoffWireException("$kind v$v is newer than this app", newer = true)
        if (v < 1) throw HandoffWireException("bad version")
        return o
    }

    fun readNow(text: String): StashNow {
        val o = root(text, NOW_KIND)
        val playing = o.bool("playing") ?: throw HandoffWireException("no playing")
        val position = o.long("positionMs")?.takeIf { it >= 0 } ?: throw HandoffWireException("bad positionMs")
        val index = o.long("index")?.takeIf { it in 0..Int.MAX_VALUE } ?: throw HandoffWireException("bad index")
        val queueId = o.str("queueId")?.takeIf(QUEUE_ID::matches) ?: throw HandoffWireException("bad queueId")
        val rate = if (o.containsKey("rate")) o.num("rate")?.takeIf { it > 0 && it <= 16 } ?: throw HandoffWireException("bad rate") else 1.0
        return StashNow(
            playing = playing,
            positionMs = position,
            rate = rate,
            index = index.toInt(),
            queueId = queueId,
            song = o["song"]?.let(::readSong),
            shuffle = o.bool("shuffle") ?: false,
            repeat = HandoffRepeat.of(o.str("repeat")) ?: HandoffRepeat.OFF,
            from = o.str("from")?.let { cleanText(it, MAX_FROM) }?.takeIf { it.isNotEmpty() },
            leftOut = (o.long("leftOut") ?: 0).coerceIn(0, MAX_QUEUE.toLong()).toInt(),
        )
    }

    fun readQueue(text: String): StashQueue {
        val o = root(text, QUEUE_KIND)
        val id = o.str("id")?.takeIf(QUEUE_ID::matches) ?: throw HandoffWireException("bad id")
        val arr = o["items"] as? JsonArray ?: throw HandoffWireException("no items")
        if (arr.size > MAX_QUEUE) throw HandoffWireException("too many items")
        val items = arr.map(::readSong)
        val index = o.long("index")?.takeIf { it in 0 until items.size } ?: throw HandoffWireException("bad index")
        val offset = (o.long("offset") ?: 0).takeIf { it in 0..Int.MAX_VALUE } ?: throw HandoffWireException("bad offset")
        val original = (o["original"] as? JsonArray)?.let { a ->
            val ints = a.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull?.toInt() ?: -1 }
            // A permutation of 0 … n − 1, or nothing: anything else can't say what the unshuffled order was.
            ints.takeIf { it.size == items.size && it.toSet() == items.indices.toSet() }
        }
        return StashQueue(id, items, index.toInt(), offset.toInt(), original)
    }

    /** A Song by `readBackup`'s rules, or null (no title or artist, or not an object). */
    fun readSong(e: JsonElement?): WireSong? {
        val o = e as? JsonObject ?: return null
        val title = o.str("title")?.let { cleanText(it, MAX_TITLE) }?.takeIf { it.isNotEmpty() } ?: return null
        val artist = o.str("artist")?.let { cleanText(it, MAX_TITLE) }?.takeIf { it.isNotEmpty() } ?: return null
        val refs = LinkedHashMap<String, String>()
        (o["refs"] as? JsonObject)?.forEach { (k, v) ->
            val id = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { cleanText(it, MAX_REF) }
            if (REF_KEY.matches(k) && !id.isNullOrEmpty() && refs.size < MAX_REFS) refs[k] = id
        }
        val art = (o["artwork"] as? JsonArray).orEmpty().take(MAX_ART)
            .mapNotNull { (it as? JsonObject)?.str("url")?.takeIf(::isAllowedUrl) }
        return WireSong(
            title = title,
            artist = artist,
            album = o.str("album")?.let { cleanText(it, MAX_TITLE) }?.takeIf { it.isNotEmpty() },
            durationMs = o.num("durationMs")?.takeIf { it > 0 && it <= MAX_DURATION_MS }?.let { Math.round(it) },
            isrc = o.str("isrc")?.takeIf(ISRC::matches)?.uppercase(),
            spotifyId = o.str("spotifyId")?.takeIf(SPOTIFY_ID::matches),
            refs = refs,
            artwork = art,
            addedAt = o.num("addedAt")?.takeIf { it > 0 && it < 1e14 }?.let { Math.round(it) },
            phoneOnly = o.bool("phoneOnly") == true,
        )
    }

    /** The web's `stripUnsafe` ranges: controls, bidi controls, zero-width characters, the BOM, line separators. */
    private fun unsafe(c: Int) = c in 0x00..0x1f || c in 0x7f..0x9f || c == 0x61c || c in 0x200b..0x200f ||
        c in 0x2028..0x202e || c in 0x2060..0x2069 || c == 0xfeff

    /** The web's `cleanText`: unsafe characters become spaces, whitespace runs one space, trimmed, cut at [max] code points. */
    fun cleanText(s: String, max: Int): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            if (unsafe(c)) sb.append(' ') else sb.appendCodePoint(c)
            i += Character.charCount(c)
        }
        val out = sb.toString().replace(JS_SPACE_RUN, " ").trim(' ')
        val n = out.codePointCount(0, out.length)
        return if (n <= max) out else out.substring(0, out.offsetByCodePoints(0, max))
    }

    private val JS_SPACE_RUN = Regex("[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

    /** An https address with a host and no credentials, no spaces, controls or backslashes (cover art only). */
    fun isAllowedUrl(u: String): Boolean {
        if (u.isEmpty() || u.length > MAX_URL) return false
        if (u.any { it <= ' ' || it in '\u007f'..'\u009f' || it == '\\' }) return false
        val uri = try {
            java.net.URI(u)
        } catch (e: java.net.URISyntaxException) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
    }
}
