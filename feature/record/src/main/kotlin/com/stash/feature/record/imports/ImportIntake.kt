package com.stash.feature.record.imports

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.stash.feature.record.importer.ExportKind
import com.stash.feature.record.importer.ExportText
import com.stash.feature.record.importer.ExportZip
import com.stash.feature.record.importer.ImportTooBig
import com.stash.feature.record.importer.PickedFile
import com.stash.feature.record.importer.StorageFull
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/** A file to bring in: what it's called and how big it is when the app that has it says (neither trusted), and how to read it. */
internal class IntakeSource(val name: String?, val size: Long?, val open: () -> InputStream?)

/**
 * The files someone brings (picked, shared or opened with Stash) copied into the app's own storage before anything reads them.
 *
 * Why a copy: a file shared or opened with Stash is readable only through a grant tied to the screen that received it, and that
 * grant can lapse (the screen is closed, the app swiped away) while a long import is still reading. The intake screen
 * (RecordIntakeActivity) copies a shared file at once, while its grant holds; a commit that the app's death interrupted reads the
 * same copies again. Spotify's zips are compressed (a decade is tens of MB), so the copy is quick; it is removed when the import is
 * done, cancelled or replaced, and at the next start if the app died first.
 *
 * Hostile input: only the bytes actually read count (a provider's size is a claim, used only to spot a file brought twice), and a
 * copy stops at the smaller of the free space less [headroom] and [maxTotal] for the whole import, its partial file deleted.
 *
 * A name is how the web tells a zip from a JSON file, so a copy keeps the name the person saw. A provider that gives a name
 * without an extension has its zip known by the zip signature at its start.
 */
internal class ImportIntake(
    private val dir: File,
    /** All of one import's copies together, at most. */
    private val maxTotal: Long = MAX_TOTAL,
    /** Free space a copy never eats into. */
    private val headroom: Long = HEADROOM,
    private val freeSpace: () -> Long = { dir.usableSpace },
) {
    /**
     * Copies [sources] in (one already here by name and claimed size is not copied again), [have] being the copies the import holds
     * already. Throws [StorageFull] when the phone runs out of room and [ImportTooBig] past [maxTotal]; a refused copy leaves no file.
     * [checkpoint] is called between blocks and throws to stop (the person's Cancel): what this call copied is deleted then too.
     */
    fun copy(sources: List<IntakeSource>, have: List<PickedFile>, checkpoint: () -> Unit = {}): List<PickedFile> {
        dir.mkdirs()
        val out = ArrayList<PickedFile>()
        var total = have.sumOf { it.file.length() }
        var n = (dir.listFiles()?.mapNotNull { it.name.substringBefore(SEP, "").toIntOrNull() }?.maxOrNull() ?: -1) + 1
        try {
            for (src in sources) {
                val name = src.name?.let(::safeName)?.ifEmpty { null } ?: FALLBACK
                if (src.size != null && (have + out).any { it.name == name && it.file.length() == src.size }) continue
                val target = File(dir, "${n++}$SEP$name")
                val copied = try {
                    val input = src.open() ?: throw IOException("the file couldn't be opened")
                    input.use { i -> target.outputStream().use { o -> bounded(i, o::write, maxTotal - total, checkpoint) } }
                } catch (e: Exception) {
                    target.delete()
                    if (e is CancellationException || e is StorageFull || e is ImportTooBig) throw e
                    if (freeSpace() < headroom || e.message?.contains("ENOSPC") == true) throw StorageFull()
                    throw e
                }
                total += copied
                out += PickedFile(withExtension(name, target), target)
            }
        } catch (e: Exception) {
            // One refused: none of this call's copies stay (the person brings the files again)
            delete(out)
            throw e
        }
        return out
    }

    /** Copies [input] through [write], counting: past [cap] bytes it's [ImportTooBig], into the last [headroom] of free space [StorageFull]. */
    private fun bounded(input: InputStream, write: (ByteArray, Int, Int) -> Unit, cap: Long, checkpoint: () -> Unit): Long {
        val buf = ByteArray(BUFFER)
        var n = 0L
        var room = freeSpace() - headroom
        while (true) {
            checkpoint()
            val r = input.read(buf)
            if (r < 0) return n
            n += r
            if (n > cap) throw ImportTooBig("file data")
            if (n > room) {
                // Free space is asked again before giving up (other apps write too)
                room = freeSpace() - headroom + n - r
                if (n > room) throw StorageFull()
            }
            write(buf, 0, r)
        }
    }

    /**
     * The intake's sniff for a file shared or opened with Stash: does any of [files] hold a file of a Spotify export, by its name?
     * A zip's own names are read first, and a zip inside it only when those hold none, under [zip]'s budget; what [zip] opens stays
     * open for the read that follows, so nothing is inflated twice.
     */
    fun looksLikeExport(files: List<PickedFile>, zip: ExportZip): Boolean = files.any { f ->
        when {
            ExportText.isZipName(f.name) -> zip.sniff(f.file, "${f.name}!/")
            ExportText.isJsonName(f.name) -> ExportText.kindOf(f.name) != ExportKind.IGNORE
            else -> false
        }
    }

    /** The copies kept (a commit the app's death interrupted reads them again), in the order they were brought. */
    fun existing(): List<PickedFile> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.contains(SEP) }
        .sortedBy { it.name.substringBefore(SEP).toIntOrNull() ?: Int.MAX_VALUE }
        .map { PickedFile(withExtension(it.name.substringAfter(SEP), it), it) }

    fun delete(files: List<PickedFile>) = files.forEach { it.file.delete() }

    /** Every copy gone. */
    fun clear() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    /** The name a reader of the web's rules goes by: as given, or with `.zip` when it has no extension and is a zip. */
    private fun withExtension(name: String, file: File): String {
        if (ExportText.isZipName(name) || ExportText.isJsonName(name) || name.substringAfterLast('/').contains('.')) return name
        val head = ByteArray(4)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(0)
        val zip = n == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
            ((head[2].toInt() == 3 && head[3].toInt() == 4) || (head[2].toInt() == 5 && head[3].toInt() == 6))
        return if (zip) "$name.zip" else name
    }

    companion object {
        private const val SEP = "__"
        private const val FALLBACK = "file"
        private const val BUFFER = 64 * 1024

        /** Room left on the phone beside the copies. */
        const val HEADROOM = 64L shl 20

        /** All of one import's copies together, at most (2 GiB: a heavy listener's two zips are tens of MB). */
        const val MAX_TOTAL = 2L shl 30

        /** A copy's file name, at most, in UTF-8 bytes (the file system allows 255; the copy's number goes in front). */
        private const val MAX_NAME_BYTES = 200

        /**
         * A file name as given, without what a path can't hold, cut to [MAX_NAME_BYTES] UTF-8 bytes (never through a character)
         * with its extension kept, since the extension and the base name are all the web reads.
         */
        fun safeName(name: String): String {
            val clean = name.substringAfterLast('/').substringAfterLast('\\')
                .map { c -> if (c < ' ' || c in "/\\:*?\"<>|") '_' else c }.joinToString("")
            if (clean.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return clean
            val dot = clean.lastIndexOf('.')
            val ext = if (dot > 0 && clean.length - dot <= 10) clean.substring(dot) else ""
            val base = if (ext.isEmpty()) clean else clean.substring(0, dot)
            val room = MAX_NAME_BYTES - ext.toByteArray(Charsets.UTF_8).size
            val kept = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < base.length) {
                val cp = base.codePointAt(i)
                val b = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + b > room) break
                kept.appendCodePoint(cp)
                bytes += b
                i += Character.charCount(cp)
            }
            return kept.toString() + ext
        }

        /** Only a content uri is taken (a `file:` uri could name anything on the phone, `/dev/zero` included). */
        fun takes(uri: Uri): Boolean = uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)

        /** A content uri as an [IntakeSource]: its name and size from the app that has it. */
        fun source(resolver: ContentResolver, uri: Uri): IntakeSource {
            var name: String? = null
            var size: Long? = null
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
            }
            return IntakeSource(name ?: uri.lastPathSegment, size) { resolver.openInputStream(uri) }
        }
    }
}
