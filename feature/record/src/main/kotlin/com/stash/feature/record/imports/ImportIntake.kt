package com.stash.feature.record.imports

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.stash.feature.record.importer.ExportKind
import com.stash.feature.record.importer.ExportText
import com.stash.feature.record.importer.ExportZip
import com.stash.feature.record.importer.PickedFile
import com.stash.feature.record.importer.ZipError
import java.io.File
import java.io.IOException
import java.io.InputStream

/** There isn't room on the phone for the files. */
class StorageFull : IOException("no room for the import's files")

/** A file to bring in: where it is, and what it is called and how big it is when the app that has it says. */
internal class IntakeSource(val name: String?, val size: Long?, val open: () -> InputStream?)

/**
 * The files someone brings (picked, shared or opened with Stash) copied into the app's own storage before anything reads them.
 *
 * Why a copy: a file shared or opened with Stash is readable only through a grant tied to the screen that received it, and that
 * grant can lapse (the screen is closed, the app swiped away) while a long import is still reading. A copy, made at once while the
 * grant holds, is read by the import whenever it runs, and a commit that the app's death interrupted can read the same files
 * again. Spotify's zips are compressed (a decade is tens of MB), so the copy is quick; it is removed when the import is done,
 * cancelled or replaced, and at the next start if the app died first.
 *
 * A name is how the web tells a zip from a JSON file, so a copy keeps the name the person saw. A provider that gives a name
 * without an extension has its zip known by the zip signature at its start.
 */
internal class ImportIntake(private val dir: File) {
    /** Copies [sources] in (one already here by name and size is not copied again). Throws [StorageFull] when there is no room. */
    fun copy(sources: List<IntakeSource>, have: List<PickedFile>): List<PickedFile> {
        dir.mkdirs()
        val out = ArrayList<PickedFile>()
        var n = (dir.listFiles()?.mapNotNull { it.name.substringBefore(SEP, "").toIntOrNull() }?.maxOrNull() ?: -1) + 1
        for (src in sources) {
            val name = src.name?.let(::safeName)?.ifEmpty { null } ?: FALLBACK
            if (src.size != null && (have + out).any { it.name == name && it.file.length() == src.size }) continue
            if (src.size != null && src.size > dir.usableSpace - HEADROOM) throw StorageFull()
            val target = File(dir, "${n++}$SEP$name")
            try {
                val input = src.open() ?: throw IOException("the file couldn't be opened")
                input.use { i -> target.outputStream().use { o -> i.copyTo(o, BUFFER) } }
            } catch (e: IOException) {
                target.delete()
                if (dir.usableSpace < HEADROOM || e.message?.contains("ENOSPC") == true) throw StorageFull()
                throw e
            }
            out += PickedFile(withExtension(name, target), target)
        }
        return out
    }

    /**
     * The intake's sniff for a file shared or opened with Stash: does any of [files] hold a file of a Spotify export, by its name
     * (a zip's names are read from its directory, nested zips too, without inflating a single file)?
     */
    fun looksLikeExport(files: List<PickedFile>, scratch: File): Boolean = ExportZip(scratch).use { zip ->
        files.any { f ->
            when {
                ExportText.isZipName(f.name) -> try {
                    zip.entries(f.file, "${f.name}!/").any { !it.broken && ExportText.kindOf(it.name) != ExportKind.IGNORE }
                } catch (e: ZipError) {
                    false
                }
                ExportText.isJsonName(f.name) -> ExportText.kindOf(f.name) != ExportKind.IGNORE
                else -> false
            }
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

        /** Room to leave on the phone beside a copy. */
        private const val HEADROOM = 32L * 1024 * 1024

        /** A file name as given, without what a path can't hold; the extension and the base name (all the web reads) stay. */
        fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\')
            .map { c -> if (c < ' ' || c in "/\\:*?\"<>|") '_' else c }.joinToString("").take(200)

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
