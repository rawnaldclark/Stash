package com.stash.core.data.weblibrary

import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.data.weblink.handoff.WireSong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Why a file or a send can't be imported, in the words the screen shows. */
class WebLibraryReadException(message: String) : Exception(message)

/** A like from the file: the song (identity worked out again here, never taken from the file) and when it was liked. */
data class ImportedLike(val song: WireSong, val likedAt: Long)

/** A playlist from the file, its songs in order. [follow] marks a shared mix someone else owns. */
data class ImportedPlaylist(
    val id: String,
    val name: String,
    val items: List<WireSong>,
    val createdAt: Long,
    val updatedAt: Long,
    val follow: WebLibraryFile.Follow? = null,
)

data class ImportedPlay(val song: WireSong, val playedAt: Long)

/** What a `stash-web-library` v1 file holds, cleaned. */
data class WebLibraryContent(
    val likes: List<ImportedLike>,
    val playlists: List<ImportedPlaylist>,
    val history: List<ImportedPlay>,
    /** Who wrote it ("Stash for Android 0.9.112"), informational. */
    val generator: String? = null,
)

/**
 * Reads a `stash-web-library` v1 file (stash-player `docs/library-file-v1.md`) the way the web's `readBackup` does: the file is
 * untrusted input, so every value is rebuilt from the known fields and limits and the rest is dropped. Songs go through the
 * sync wire's song reader ([HandoffWire.readSong]: cleaned text, title and artist required, ISRC, https covers, at most 16
 * refs). `settings` and `appearance` are never read: importing never applies settings.
 */
object WebLibraryReader {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MAX_LIKES = 50_000
    const val MAX_PLAYLISTS = 2_000
    const val MAX_PLAYLIST_ITEMS = 10_000
    const val MAX_NAME = 100
    const val MAX_SHARED_BY = 40

    const val NOT_A_BACKUP = "That file isn't a Stash backup."
    const val TOO_BIG = "That file is too big to be a Stash backup."
    const val NEWER = "That backup comes from a newer Stash. Update Stash and try again."

    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val SHARE_ID = Regex("^[A-Za-z0-9]{8}$")
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads [input] (closed by the caller), refusing more than [MAX_BYTES]. */
    fun read(input: InputStream, now: Long): WebLibraryContent {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) throw WebLibraryReadException(TOO_BIG)
            out.write(buf, 0, n)
        }
        return read(out.toString(Charsets.UTF_8.name()), now)
    }

    fun read(text: String, now: Long): WebLibraryContent {
        if (text.length > MAX_BYTES) throw WebLibraryReadException(TOO_BIG)
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw WebLibraryReadException(NOT_A_BACKUP)
        if (root.str("kind") != WebLibraryFile.KIND) throw WebLibraryReadException(NOT_A_BACKUP)
        val v = root.integer("v")
        if (v != WebLibraryFile.VERSION.toLong()) throw WebLibraryReadException(if (v != null && v > WebLibraryFile.VERSION) NEWER else NOT_A_BACKUP)

        val likes = (root["likes"] as? JsonArray).orEmpty().take(MAX_LIKES).mapNotNull { l ->
            val o = l as? JsonObject ?: return@mapNotNull null
            val song = HandoffWire.readSong(o["item"]) ?: return@mapNotNull null
            ImportedLike(song, o.time("likedAt") ?: song.addedAt ?: now)
        }

        val seen = HashSet<String>()
        val playlists = (root["playlists"] as? JsonArray).orEmpty().take(MAX_PLAYLISTS).mapNotNull { p ->
            readPlaylist(p, now)?.takeIf { seen.add(it.id) }
        }

        val history = (root["history"] as? JsonArray).orEmpty().mapNotNull { h ->
            val o = h as? JsonObject ?: return@mapNotNull null
            val song = HandoffWire.readSong(o["item"]) ?: return@mapNotNull null
            val at = o.time("playedAt") ?: return@mapNotNull null
            ImportedPlay(song, at)
        }.sortedByDescending { it.playedAt }.take(WebLibraryFile.MAX_PLAYS)

        return WebLibraryContent(likes, playlists, history, root.str("generator")?.let { HandoffWire.cleanText(it, 200) })
    }

    private fun readPlaylist(e: JsonElement, now: Long): ImportedPlaylist? {
        val o = e as? JsonObject ?: return null
        val id = o.str("id")?.takeIf(PLAYLIST_ID::matches) ?: return null
        val items = o["items"] as? JsonArray ?: return null
        val name = o.str("name")?.let { HandoffWire.cleanText(it, MAX_NAME) }?.takeIf { it.isNotEmpty() } ?: return null
        val created = o.time("createdAt") ?: now
        return ImportedPlaylist(
            id = id,
            name = name,
            items = items.take(MAX_PLAYLIST_ITEMS).mapNotNull(HandoffWire::readSong),
            createdAt = created,
            updatedAt = o.time("updatedAt") ?: created,
            follow = (o["follow"] as? JsonObject)?.let(::readFollow),
        )
    }

    private fun readFollow(o: JsonObject): WebLibraryFile.Follow? {
        val id = o.str("id")?.takeIf(SHARE_ID::matches) ?: return null
        val version = o.integer("version")?.takeIf { it in 1..Int.MAX_VALUE } ?: return null
        return WebLibraryFile.Follow(
            id = id,
            version = version.toInt(),
            sharedBy = o.str("sharedBy")?.let { HandoffWire.cleanText(it, MAX_SHARED_BY) }?.takeIf { it.isNotEmpty() },
            checkedAt = o.time("checkedAt") ?: 0,
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number with no fraction (never a string), or null. */
    private fun JsonObject.integer(k: String): Long? {
        val p = (this[k] as? JsonPrimitive)?.takeUnless { it.isString } ?: return null
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: return null
        return if (d.isFinite() && d == Math.floor(d) && kotlin.math.abs(d) < 9.0e15) d.toLong() else null
    }

    /** An epoch-ms time as the web reads one: a finite number, > 0, < 1e14, rounded. */
    private fun JsonObject.time(k: String): Long? {
        val d = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
        return if (d.isFinite() && d > 0 && d < 1e14) Math.round(d) else null
    }
}
