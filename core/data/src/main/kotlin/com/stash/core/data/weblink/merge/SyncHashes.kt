package com.stash.core.data.weblink.merge

import com.stash.core.data.weblink.Base64Url
import com.stash.core.model.weblink.SongIdentity
import com.stash.core.model.weblink.SongKey
import java.security.MessageDigest

/**
 * Content hashes of sync-v1 (stash-player `docs/sync-v1.md` "Hashes"): a mirrored playlist's version (`pl.parent`, the snapshot's
 * `hash`) and a handoff queue's id. Input is a format line, then fields written as `<UTF-8 byte length>:<text>\n`; the hash is
 * the first 16 bytes of SHA-256, base64url, after a prefix. Same as the web's `src/lib/sync/hash.ts`.
 */
object SyncHashes {
    private fun field(s: String) = "${s.toByteArray(Charsets.UTF_8).size}:$s\n"

    private fun h16(text: String): String =
        Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).copyOf(16))

    /** `stash-pl/1\n`, then `deleted\n`, or the name, the follow (`<id>@<version>` or empty), the count and each item's `textKey`. */
    fun playlistHashInput(name: String, items: List<SongIdentity>?, follow: Follow?): String {
        if (items == null) return "stash-pl/1\ndeleted\n"
        return buildString {
            append("stash-pl/1\n")
            append(field(name))
            append(field(follow?.let { "${it.id}@${it.version}" } ?: ""))
            append(field(items.size.toString()))
            items.forEach { append(field(SongKey.textKey(it))) }
        }
    }

    fun playlistHash(name: String, items: List<SongIdentity>?, follow: Follow?): String = "h_" + h16(playlistHashInput(name, items, follow))

    /** `stash-queue/1\n`, then the offset, the count, each item's key (`keyOf`) and `original` joined by commas. */
    fun queueIdInput(items: List<SongIdentity>, offset: Int, original: List<Int>?): String = buildString {
        append("stash-queue/1\n")
        append(field(offset.toString()))
        append(field(items.size.toString()))
        items.forEach { append(field(SongKey.keyOf(it))) }
        append(field(original.orEmpty().joinToString(",")))
    }

    fun queueId(items: List<SongIdentity>, offset: Int, original: List<Int>?): String = "q_" + h16(queueIdInput(items, offset, original))
}
