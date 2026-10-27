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

/**
 * A file to bring in: what it's called and how big it is when the app that has it says (neither trusted, and asked only when
 * first needed, off the main thread: asking a provider is a call into another app), and how to read it.
 */
internal class IntakeSource(private val describe: () -> Pair<String?, Long?>, val open: () -> InputStream?) {
    constructor(name: String?, size: Long?, open: () -> InputStream?) : this({ name to size }, open)

    private val described by lazy(describe)

    val name: String? get() = described.first
    val size: Long? get() = described.second
}

/**
 * The files someone brings (picked, shared or opened with Stash) copied into the app's own storage before anything reads them.
 *
 * Why a copy: a file shared or opened with Stash is readable only through a grant tied to the screen that received it, and that
 * grant can lapse (the screen is closed, the app swiped away) while a long import is still reading. The intake screen
 * (RecordIntakeActivity) keeps its grant until the copy is done; a commit that the app's death interrupted reads the same copies
 * again. Spotify's zips are compressed (a decade is tens of MB), so the copy is quick; it is removed when the import is done,
 * cancelled or replaced, and at the next start if the app died first.
 *
 * A copy is made in three steps so that nothing waits on a provider that never answers: [reserve] a name (a `part…` file),
 * [stream] into it with no lock held (a read can block for as long as the provider likes), and [finish] it (renamed to its
 * number). [existing] and [clear] see only finished copies; each part belongs to the copy streaming it, which deletes it however
 * it ends. Reserving, finishing, deleting and listing are quick and are made under the import's own lock (RecordImports).
 *
 * Hostile input: only the bytes actually read count (a provider's size is a claim, used only to spot a file brought twice), and a
 * copy stops at the smaller of the free space less [headroom] and [maxTotal] for the whole import.
 *
 * A name is how the web tells a zip from a JSON file, so a copy keeps the name the person saw. A provider that gives a name
 * without an extension has its zip known by the zip signature at its start.
 */
internal class ImportIntake(
    private val dir: File,
    /** All of one import's copies together, at most. */
    val maxTotal: Long = MAX_TOTAL,
    /** Free space a copy never eats into. */
    private val headroom: Long = HEADROOM,
    private val freeSpace: () -> Long = { dir.usableSpace },
    /** How a part becomes its copy (a seam for the tests of a rename that fails). */
    private val rename: (File, File) -> Boolean = File::renameTo,
) {
    /** A file named [name] and claiming [size] is one of [have] already (brought twice). */
    fun alreadyHave(name: String?, size: Long?, have: List<PickedFile>): Boolean {
        if (size == null) return false
        val safe = nameOf(name)
        return have.any { it.file.name.substringAfter(SEP) == safe && it.file.length() == size }
    }

    /** A part for a file named [name] (as given; made safe here): the next number, created empty. Under the import's lock. */
    fun reserve(name: String?): File {
        dir.mkdirs()
        val next = (dir.listFiles()?.mapNotNull { number(it.name.removePrefix(PART)) }?.maxOrNull() ?: -1) + 1
        return File(dir, "$PART$next$SEP${nameOf(name)}").also { it.createNewFile() }
    }

    /**
     * Streams [src] into [part] and returns the bytes copied: past [cap] it throws [ImportTooBig], into the last [headroom] of free
     * space [StorageFull]. [opened] is handed the provider's stream (so a cancel can close it); [checkpoint] runs between blocks
     * and throws to stop. No lock is held. A stream that fails, for any Throwable, deletes its part (closed first); one that
     * succeeds leaves it to be [finish]ed (or deleted by the caller).
     */
    fun stream(src: IntakeSource, part: File, cap: Long, opened: (InputStream) -> Unit = {}, checkpoint: () -> Unit = {}): Long {
        try {
            val input = src.open() ?: throw IOException("the file couldn't be opened")
            opened(input)
            return input.use { i -> part.outputStream().use { o -> bounded(i, o::write, cap, checkpoint) } }
        } catch (t: Throwable) {
            part.delete()
            if (t !is Exception || t is CancellationException || t is StorageFull || t is ImportTooBig) throw t
            if (freeSpace() < headroom || t.message?.contains("ENOSPC") == true) throw StorageFull()
            throw t
        }
    }

    /** [part], streamed, as a copy: renamed to its number. Under the import's lock. */
    fun finish(part: File): PickedFile {
        val done = File(dir, part.name.removePrefix(PART))
        if (!rename(part, done)) throw IOException("the copy couldn't be kept")
        return PickedFile(withExtension(done.name.substringAfter(SEP), done), done)
    }

    /**
     * Copies [sources] in one after the other ([have]: the copies the import holds already; one brought again is skipped). For a
     * caller with nothing else running (tests); RecordImports makes each step itself, its read never under a lock. A refused copy
     * leaves no file.
     */
    fun copy(sources: List<IntakeSource>, have: List<PickedFile>, checkpoint: () -> Unit = {}): List<PickedFile> {
        val out = ArrayList<PickedFile>()
        var total = have.sumOf { it.file.length() }
        try {
            for (src in sources) {
                if (alreadyHave(src.name, src.size, have + out)) continue
                val part = reserve(src.name)
                try {
                    total += stream(src, part, maxTotal - total, checkpoint = checkpoint)
                    out += finish(part)
                } finally {
                    part.delete()
                }
            }
        } catch (t: Throwable) {
            // One refused: none of this call's copies stay (the person brings the files again)
            delete(out)
            throw t
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

    /** The finished copies (a commit the app's death interrupted reads them again), in the order they were brought. Under the lock. */
    fun existing(): List<PickedFile> = dir.listFiles().orEmpty()
        .filter { it.isFile && number(it.name) != null }
        .sortedBy { number(it.name) }
        .map { PickedFile(withExtension(it.name.substringAfter(SEP), it), it) }

    fun delete(files: List<PickedFile>) = files.forEach { it.file.delete() }

    /** Every copy gone, and every part but [live] (those a copy is still streaming or renaming; each deletes its own). Under the lock. */
    fun clear(live: Set<File> = emptySet()) {
        dir.listFiles()?.filter { it !in live }?.forEach { it.deleteRecursively() }
    }

    /** Everything, parts too: at start, when no copy can be running. */
    fun clearAll() {
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

        /** A copy still streaming: `part<n>__<name>`. A prefix, not a suffix, so a file someone named `x.part` is still a copy. */
        private const val PART = "part"
        private const val FALLBACK = "file"
        private const val BUFFER = 64 * 1024

        /** Room left on the phone beside the copies. */
        const val HEADROOM = 64L shl 20

        /** All of one import's copies together, at most (2 GiB: a heavy listener's two zips are tens of MB). */
        const val MAX_TOTAL = 2L shl 30

        /** A copy's file name, at most, in UTF-8 bytes (the file system allows 255; the copy's number goes in front). */
        private const val MAX_NAME_BYTES = 200

        /** A finished copy's number (`<n>__…`); null for anything else. */
        private fun number(fileName: String): Int? = fileName.substringBefore(SEP, "").toIntOrNull()

        private fun nameOf(name: String?): String = name?.let(::safeName)?.ifEmpty { null } ?: FALLBACK

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

        /**
         * Only a content URI another app hands over is taken: a `file:` URI could name anything on the phone (`/dev/zero`, the
         * app's own database through a path), and one of Stash's own providers ([ownPackage]'s authorities) is never read back
         * through the intake (LocalImportCoordinator's rule). A `<user>@` prefix is ignored, as the system ignores it.
         */
        fun takes(uri: Uri, ownPackage: String = ""): Boolean {
            if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return false
            if (ownPackage.isEmpty()) return true
            val authority = uri.authority?.substringAfterLast('@') ?: return false
            return authority != ownPackage && !authority.startsWith("$ownPackage.")
        }

        /** A content uri as an [IntakeSource]: its name and size asked of the app that has it when first needed (off the main thread). */
        fun source(resolver: ContentResolver, uri: Uri): IntakeSource = IntakeSource(
            describe = {
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
                (name ?: uri.lastPathSegment) to size
            },
            open = { resolver.openInputStream(uri) },
        )
    }
}
