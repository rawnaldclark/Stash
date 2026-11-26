package com.stash.data.download.files

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.InputStream

/**
 * [SendFolder] as `Download/Stash transfer/` in MediaStore's Downloads collection: this app's own files there, which
 * it may list, write and delete with no storage permission (Android 10 and later). Unfinished files are listed too, so
 * a stopped send's halves can be removed. Every delete is by the file's own id: a delete by folder was seen to leave
 * files behind.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreSendFolder(private val resolver: ContentResolver) : SendFolder {
    private val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    private data class Row(val id: Long, val file: SentFile)

    private fun rows(): List<Row> {
        val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.IS_PENDING)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.query(
                collection,
                columns,
                Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Downloads.RELATIVE_PATH} = ?")
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(RELATIVE_PATH))
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                },
                null,
            )
        } else {
            @Suppress("DEPRECATION")
            resolver.query(MediaStore.setIncludePending(collection), columns, "${MediaStore.Downloads.RELATIVE_PATH} = ?", arrayOf(RELATIVE_PATH), null)
        }
        val out = ArrayList<Row>()
        cursor?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out += Row(c.getLong(0), SentFile(name, c.getLong(2), pending = c.getInt(3) != 0))
            }
        }
        return out
    }

    override fun files(): List<SentFile> = rows().map { it.file }

    override fun write(name: String, source: InputStream, keepGoing: () -> Boolean): SentFile? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            var written = 0L
            var whole = false
            resolver.openOutputStream(uri)?.use { out ->
                val block = ByteArray(BUFFER)
                while (true) {
                    if (!keepGoing()) return@use
                    val n = source.read(block)
                    if (n < 0) { whole = true; break }
                    out.write(block, 0, n)
                    written += n
                }
            }
            if (!whole) {
                resolver.delete(uri, null, null)
                return null
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            // The name it really has: MediaStore renames a file whose name is taken ("76 (1).flac").
            val real = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
            return SentFile(real, written)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    override fun delete(name: String) {
        for (row in rows()) {
            if (row.file.name == name) runCatching { resolver.delete(ContentUris.withAppendedId(collection, row.id), null, null) }
        }
    }

    /** Everything of this app's in the folder: a cancelled send leaves nothing. How many were removed. */
    fun clear(): Int {
        var removed = 0
        for (row in rows()) {
            if (runCatching { resolver.delete(ContentUris.withAppendedId(collection, row.id), null, null) }.getOrDefault(0) > 0) removed++
        }
        return removed
    }

    override fun writeList(text: String) {
        delete(SongSender.LIST_NAME)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, SongSender.LIST_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("The song list could not be written.")
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    /** The list's text, when this app's list is in the folder. */
    fun readList(): String? {
        val row = rows().firstOrNull { it.file.name == SongSender.LIST_NAME && !it.file.pending } ?: return null
        return resolver.openInputStream(ContentUris.withAppendedId(collection, row.id))?.use { it.readBytes().decodeToString() }
    }

    companion object {
        /** The folder a computer finds: Internal storage › Download › Stash transfer. */
        const val RELATIVE_PATH = "Download/Stash transfer/"
        private const val BUFFER = 256 * 1024
    }
}
