package com.stash.feature.record.art

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A lookup kept: the cover's address, or null for "none known", and when it was found (epoch ms). */
data class ArtHit(val url: String?, val at: Long)

/** Where lookups are kept between visits. */
interface ArtStore {
    suspend fun get(key: String): ArtHit?

    suspend fun set(key: String, hit: ArtHit)

    /** Writes what [set] changed; called once a batch, not once a cover. */
    suspend fun flush()
}

/**
 * The covers looked up, kept in one small file (no database change: PLAN phase 2): a line per album or artist, `key`, `at`
 * and the address (empty for none), tab-separated. Keys are folded words (letters, digits, spaces, `|`, `:`), and addresses are
 * checked https URLs, so neither holds a tab or a line break; a line that does is skipped. Read once, on first use; written
 * whole through a temporary file and a rename, so a crash mid-write leaves the old file. At most [maxEntries], the newest.
 */
class FileArtStore(
    private val file: File,
    private val maxEntries: Int = MAX_ENTRIES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ArtStore {
    private val lock = Mutex()
    private var entries: LinkedHashMap<String, ArtHit>? = null
    private var dirty = false

    override suspend fun get(key: String): ArtHit? = lock.withLock { loaded()[key] }

    override suspend fun set(key: String, hit: ArtHit) {
        if (!writable(key) || (hit.url != null && !writable(hit.url))) return
        lock.withLock {
            val m = loaded()
            m.remove(key)
            m[key] = hit
            dirty = true
        }
    }

    override suspend fun flush(): Unit = lock.withLock {
        if (!dirty) return@withLock
        val m = loaded()
        // The newest kept when there are too many (insertion order is the order they were found in, oldest first)
        while (m.size > maxEntries) m.remove(m.keys.first())
        val text = buildString(m.size * 120) {
            for ((k, v) in m) append(k).append('\t').append(v.at).append('\t').append(v.url.orEmpty()).append('\n')
        }
        withContext(io) {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.delete()
                    if (!tmp.renameTo(file)) throw IOException("rename failed")
                }
                dirty = false
            } catch (e: IOException) {
                // Covers are looked up again next time: nothing is lost but a lookup
                Log.w(TAG, "covers not kept: ${e.javaClass.simpleName}")
                Unit
            }
        }
    }

    /** The entries, read from the file the first time (a missing or broken file is an empty store). Under [lock]. */
    private suspend fun loaded(): LinkedHashMap<String, ArtHit> {
        entries?.let { return it }
        val m = LinkedHashMap<String, ArtHit>()
        withContext(io) {
            try {
                if (file.exists()) {
                    file.forEachLine { line ->
                        val parts = line.split('\t')
                        val at = parts.getOrNull(1)?.toLongOrNull()
                        if (parts.size == 3 && parts[0].isNotEmpty() && at != null) {
                            m[parts[0]] = ArtHit(parts[2].ifEmpty { null }?.takeIf(::isAllowedCover), at)
                        }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "covers not read: ${e.javaClass.simpleName}")
            }
        }
        // In the order found, so the oldest go first when there are too many
        val sorted = m.entries.sortedBy { it.value.at }
        return LinkedHashMap<String, ArtHit>(sorted.size).also { out ->
            for ((k, v) in sorted) out[k] = v
            entries = out
        }
    }

    private fun writable(s: String) = s.isNotEmpty() && s.none { it == '\t' || it == '\n' || it == '\r' }

    companion object {
        private const val TAG = "RecordArt"

        /** A record's covers and a Home card's: a few thousand albums and artists at most, about 0.5 MB. */
        const val MAX_ENTRIES = 5_000
    }
}
