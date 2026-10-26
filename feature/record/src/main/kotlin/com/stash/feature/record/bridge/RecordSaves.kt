package com.stash.feature.record.bridge

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID

/** What became of a file the record asked to save. */
enum class SaveOutcome {
    /** In Downloads. */
    SAVED,

    /** Handed to the share sheet instead (Android 9 and older: Downloads would need storage permission). */
    SHARED,
    FAILED,
}

/** Where a finished file from the record goes. */
interface RecordSaver {
    /** Puts [file] in Downloads as [name], or hands it to the share sheet where it can't. [file] is the saver's to move or delete. */
    suspend fun save(file: File, name: String, mime: String): SaveOutcome

    /** Readies [file] for the share sheet as [name]; true when it is ready. [file] is the saver's to move or delete. */
    suspend fun share(file: File, name: String, mime: String): Boolean
}

/**
 * Puts the record's files back together (`stash:save`: base64 chunks, `seq` from 0, the last `done`) in a file under [dir],
 * never in memory: a race video can be tens of megabytes. One file at a time, its chunks strictly in order; a chunk of another
 * file, or one out of order, drops what was gathered (the page started a new file, or lost one), and so do [abort] (the page
 * gave up on it) and [drop] (the page went). At most [maxBytes] a file, and [maxFiles] files a visit. Once whole it goes to
 * [saver].
 *
 * Flow control (`stash:save-ack`): [take] calls its `appended` once a chunk is in the file, so the page, which keeps only two
 * chunks unacknowledged, sends the next only when this one is safe.
 */
class RecordSaves(
    private val dir: File,
    private val saver: RecordSaver,
    private val maxBytes: Long = MAX_BYTES,
    private val maxFiles: Int = MAX_FILES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RecordSaveSink {
    private class Partial(val id: String, val name: String, val mime: String, val share: Boolean, val file: File) {
        var next = 0
        var size = 0L
    }

    private val lock = Mutex()
    private var current: Partial? = null
    private var finished = 0

    /**
     * Takes one chunk (in the order the page sent them), calling [appended] once it is in the file. Returns what to tell the
     * listener once a file is done or can't be: [RecordNote.Saved], [RecordNote.SaveFailed], [RecordNote.TooManySaves], or null
     * (more to come, a share sheet readied, a file handed to the share sheet, or an orphan chunk dropped).
     */
    override suspend fun take(chunk: RecordAsk.Save, appended: suspend () -> Unit): RecordNote? = lock.withLock {
        withContext(io) {
            if (chunk.seq == 0) {
                dropLocked()
                if (finished >= maxFiles) {
                    Log.w(TAG, "the record asked for more than $maxFiles files this visit; refused")
                    return@withContext RecordNote.TooManySaves
                }
                current = start(chunk)
            }
            val c = current
            if (c == null || c.id != chunk.id || chunk.seq != c.next) {
                if (c != null) Log.w(TAG, "a file from the record came out of order; dropped")
                dropLocked()
                return@withContext null
            }
            val bytes = try {
                Base64.getDecoder().decode(chunk.data)
            } catch (e: IllegalArgumentException) {
                dropLocked()
                return@withContext RecordNote.SaveFailed
            }
            if (c.size + bytes.size > maxBytes) {
                Log.w(TAG, "a file from the record is over ${maxBytes / (1024 * 1024)} MB; dropped")
                dropLocked()
                return@withContext RecordNote.SaveFailed
            }
            try {
                c.file.appendBytes(bytes)
            } catch (e: IOException) {
                dropLocked()
                return@withContext RecordNote.SaveFailed
            }
            c.size += bytes.size
            c.next++
            appended()
            if (!chunk.done) return@withContext null
            current = null
            finished++
            deliver(c)
        }
    }

    /** The page gave up on file [id]: what was gathered of it goes. */
    override suspend fun abort(id: String) = lock.withLock {
        withContext(io) { if (current?.id == id) dropLocked() }
    }

    /** The page went: what was gathered of a file goes too. */
    override suspend fun drop() = lock.withLock { withContext(io) { dropLocked() } }

    /**
     * Clears the folder at once, without waiting for anything: for the screen's last moment (`onCleared`, when no coroutine
     * of its own runs any more), so a file half made when the record closed doesn't stay in the cache.
     */
    fun clearNow() {
        current = null
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun start(chunk: RecordAsk.Save): Partial {
        dir.mkdirs()
        // A file left half-made by a record that went (the app closed mid-video) goes now
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        return Partial(chunk.id, fileName(chunk.name, chunk.mime), chunk.mime, chunk.share, File(dir, "${UUID.randomUUID()}.part"))
    }

    private suspend fun deliver(c: Partial): RecordNote? {
        try {
            return if (c.share) {
                if (saver.share(c.file, c.name, c.mime)) null else RecordNote.SaveFailed
            } else {
                when (saver.save(c.file, c.name, c.mime)) {
                    SaveOutcome.SAVED -> RecordNote.Saved
                    // The share sheet says what happens next; "saved" would be a promise it can't keep
                    SaveOutcome.SHARED -> null
                    SaveOutcome.FAILED -> RecordNote.SaveFailed
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "a file from the record couldn't be ${if (c.share) "shared" else "saved"}: ${e.javaClass.simpleName}")
            return RecordNote.SaveFailed
        } finally {
            // The saver moved or copied it; whatever is left is ours to clear
            if (c.file.exists()) c.file.delete()
        }
    }

    private fun dropLocked() {
        current?.file?.delete()
        current = null
    }

    companion object {
        private const val TAG = "YourRecord"

        /** The biggest file the record may hand over (a long race video), in bytes. */
        const val MAX_BYTES = 100L * 1024 * 1024

        /** Files one visit to the record may save or share; past that a page asking on and on is refused. */
        const val MAX_FILES = 20

        private const val MAX_NAME = 96
        private val EXTENSION = Regex("\\.[A-Za-z0-9]{1,5}\\z")
        private val EXTENSIONS = mapOf(
            "image/png" to "png",
            "image/jpeg" to "jpg",
            "image/webp" to "webp",
            "video/mp4" to "mp4",
            "video/webm" to "webm",
            "application/octet-stream" to "bin",
        )

        /**
         * A safe file name from the page's [name]: letters (any script), digits, spaces, `.`, `-`, `_` and `()` kept, anything
         * else a hyphen, runs of hyphens or dots one, no leading dots, cut to [MAX_NAME], and the extension [mime] calls for
         * (added, or put right).
         */
        fun fileName(name: String, mime: String): String {
            val ext = EXTENSIONS[mime] ?: "bin"
            val cleaned = buildString {
                for (ch in name.trim()) append(if (ch.isLetterOrDigit() || ch in " .-_()") ch else '-')
            }.trimStart('.', ' ').replace(Regex("-{2,}"), "-").replace(Regex("\\.{2,}"), ".")
            // An extension the page gave (".png", ".mp4") goes: the one the type calls for is added
            val stem = EXTENSION.replace(cleaned.trim(), "").trim().take(MAX_NAME).trimEnd('.', ' ', '-')
            return (stem.ifEmpty { "stash-record" }) + "." + ext
        }
    }
}
