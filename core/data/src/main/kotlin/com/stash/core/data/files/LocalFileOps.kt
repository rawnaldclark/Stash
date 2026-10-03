package com.stash.core.data.files

import android.content.Context
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.stash.core.common.constants.StashConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/** Health of a downloaded row's backing local file, for the repair sweep. */
enum class LocalFileState {
    /** Present and large enough to be real audio — keep as-is. */
    OK,

    /** Present but below the floor (a failed download's garbage body) — delete + un-mark. */
    TOO_SMALL,

    /** Reliably absent (null path, or an internal file that doesn't exist) — un-mark, nothing to delete. */
    MISSING,

    /**
     * Couldn't determine — a SAF document whose provider didn't report a size,
     * or a transient read failure at cold start. The sweep must do NOTHING with
     * these: deleting or un-marking on an ambiguous read would damage a real
     * external-storage library on a flaky boot.
     */
    INCONCLUSIVE,
}

/**
 * SAF-aware helpers for the size, health, and deletion of a local download file.
 *
 * A track's `filePath` can be a plain filesystem path, a `file://` URI, or a
 * `content://` SAF document (when the user picked an external download tree),
 * so every operation branches on the scheme. Plain `File.length()`/`delete()`
 * return 0 / no-op for a `content://` string, which would silently mis-handle
 * SAF downloads — hence this single SAF-aware seam, shared by the playback
 * floor, the download validation gate, and the startup repair sweep.
 */
@Singleton
class LocalFileOps @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Mount-state probe for the volume containing a plain path. Overridable in
     * unit tests (Environment is Android framework). Defaults to "is the
     * volume MOUNTED"; any resolution failure reads as not-mounted so the
     * sweep never acts on a path it can't attribute to a live volume.
     */
    internal var volumeMounted: (File) -> Boolean = { f ->
        try {
            Environment.getExternalStorageState(f) == Environment.MEDIA_MOUNTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * True when [f]'s absence can be trusted as a real deletion. Internal app
     * storage (`/data/...`) never unmounts, so `exists()` is reliable there.
     * Any other plain path (`/storage/XXXX-XXXX/...` on an SD card, USB-OTG)
     * only counts when its volume is currently mounted: with the card ejected
     * every file on it "doesn't exist", and classifying that as MISSING would
     * un-mark the user's whole library — permanently, since the sweep result
     * sticks after the card is reinserted (issue #98).
     */
    private fun absenceIsReliable(f: File): Boolean =
        // File() normalizes to the platform separator; compare with '/' so the
        // prefix check also holds in JVM unit tests on Windows.
        f.path.replace(File.separatorChar, '/').startsWith("/data/") || volumeMounted(f)

    /** Size of the file behind [path] in bytes; 0 if null/blank/missing/unreadable. */
    fun sizeBytes(path: String?): Long {
        if (path.isNullOrBlank()) return 0L
        return runCatching {
            if (path.startsWith("content://")) {
                DocumentFile.fromSingleUri(context, path.toUri())?.length() ?: 0L
            } else {
                File(plainPath(path)).length()
            }
        }.getOrDefault(0L)
    }

    /**
     * Classifies the file behind [path] against [minBytes], distinguishing a
     * reliably-absent/junk file (safe to act on) from an inconclusive read
     * (must be left alone). Used by the repair sweep, which deletes files —
     * so it errs hard toward [LocalFileState.INCONCLUSIVE] for SAF, whose
     * `exists()`/`length()` can transiently fail at process start.
     */
    fun classify(path: String?, minBytes: Long): LocalFileState {
        if (path.isNullOrBlank()) return LocalFileState.MISSING
        return try {
            if (path.startsWith("content://")) {
                val df = DocumentFile.fromSingleUri(context, path.toUri())
                    ?: return LocalFileState.INCONCLUSIVE
                if (!df.exists()) return LocalFileState.INCONCLUSIVE // SAF exists() is unreliable; don't reset
                val len = df.length()
                when {
                    len in 1 until minBytes -> LocalFileState.TOO_SMALL
                    len >= minBytes -> LocalFileState.OK
                    else -> LocalFileState.INCONCLUSIVE // len <= 0: provider may not report size
                }
            } else {
                val f = File(plainPath(path))
                when {
                    !f.exists() ->
                        if (absenceIsReliable(f)) LocalFileState.MISSING
                        else LocalFileState.INCONCLUSIVE // volume unmounted (e.g. SD ejected) — don't touch
                    f.length() < minBytes -> LocalFileState.TOO_SMALL // includes a 0-byte present file
                    else -> LocalFileState.OK
                }
            }
        } catch (e: Exception) {
            LocalFileState.INCONCLUSIVE // any read error -> do not touch
        }
    }

    /**
     * Validation gate for a just-committed download. Returns true if the file
     * is a real download (>= [StashConstants.MIN_PLAYABLE_LOCAL_BYTES]).
     * Otherwise it's a failed download's garbage body (e.g. a ~274-byte yt-dlp
     * error written to a `.webm`): the file is DELETED and false returned, so
     * the caller routes it as a failure instead of marking the track
     * downloaded. Apply this at EVERY `markAsDownloaded` site so junk can never
     * masquerade as a completed download.
     */
    fun acceptDownloadOrDelete(path: String?): Boolean {
        if (sizeBytes(path) >= StashConstants.MIN_PLAYABLE_LOCAL_BYTES) return true
        delete(path)
        return false
    }

    /**
     * Best-effort delete of the file behind [path]. True when a file was
     * deleted; false for null/blank/missing paths and failed deletes.
     */
    fun delete(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching {
            if (path.startsWith("content://")) {
                DocumentFile.fromSingleUri(context, path.toUri())?.delete() == true
            } else {
                File(plainPath(path)).delete()
            }
        }.getOrDefault(false)
    }

    /**
     * The name [setAside] would move the plain file at [path] to:
     * `<name>.swapbak`, or with a counter when that is taken. Known ahead so
     * the move can be written down before it happens. Null for a SAF
     * document: the provider picks the name when it renames.
     */
    fun backupPathFor(path: String): String? {
        if (path.startsWith("content://")) return null
        val file = File(plainPath(path))
        var backup = File(file.parentFile, file.name + BACKUP_SUFFIX)
        var counter = 1
        while (backup.exists()) {
            backup = File(file.parentFile, "${file.name}.${counter++}$BACKUP_SUFFIX")
        }
        return backup.path
    }

    /** True when a file is at [path]: checked on disk, or asked of the SAF provider. */
    fun exists(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching {
            if (path.startsWith("content://")) {
                DocumentFile.fromSingleUri(context, path.toUri())?.exists() == true
            } else {
                File(plainPath(path)).exists()
            }
        }.getOrDefault(false)
    }

    /**
     * Moves the file at [path] aside, to `<name>.swapbak` next to it, so that
     * writing to [path] can't destroy it (#531: a wrong-match swap usually
     * saves onto the old file's own path, and the save deletes what is there
     * before writing). Returns where the backup is, a plain path or the
     * renamed document's URI, or null when nothing was moved: no file there,
     * or the move failed. Never over an earlier backup: one a failed restore
     * left behind still holds someone's original audio, so a taken name gets
     * a counter (a SAF provider picks its own unique name). [backupPath],
     * from [backupPathFor], moves a plain file there, so the caller could
     * write the name down first. Put it back with [restoreSetAside]; [delete]
     * it once it isn't needed.
     */
    fun setAside(path: String, backupPath: String? = null): String? = runCatching {
        if (path.startsWith("content://")) {
            val uri = path.toUri()
            val name = DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() }?.name
                ?: return null
            DocumentsContract.renameDocument(context.contentResolver, uri, name + BACKUP_SUFFIX)?.toString()
        } else {
            val file = File(plainPath(path))
            if (!file.exists()) return null
            val backup = File(backupPath ?: backupPathFor(path) ?: return null)
            if (backup.exists()) return null
            // No REPLACE_EXISTING: if the name was taken meanwhile, fail rather
            // than overwrite.
            Files.move(file.toPath(), backup.toPath())
            backup.path
        }
    }.getOrNull()

    /**
     * Puts a [setAside] backup back at [originalPath], replacing whatever a
     * failed write left there. True when the backup is back in place.
     */
    fun restoreSetAside(backup: String, originalPath: String): Boolean = runCatching {
        if (backup.startsWith("content://")) {
            val backupUri = backup.toUri()
            val name = DocumentFile.fromSingleUri(context, backupUri)?.name?.let(::originalNameOf)
                ?: return false
            // A failed write can leave a document under the original name (with
            // path-based document ids the original URI now resolves to it).
            // Clear it first, or the rename would land on "name (1)".
            if (!isSameFile(backup, originalPath)) {
                DocumentFile.fromSingleUri(context, originalPath.toUri())?.takeIf { it.exists() }?.delete()
            }
            DocumentsContract.renameDocument(context.contentResolver, backupUri, name) != null
        } else {
            Files.move(
                File(plainPath(backup)).toPath(),
                File(plainPath(originalPath)).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        }
    }.getOrDefault(false)

    /**
     * True when [a] and [b] are the same file: the same normalized plain path,
     * or the same SAF document. Documents compare by document id, so a tree
     * URI and a single-document URI for one file match.
     */
    fun isSameFile(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true
        val aIsDocument = a.startsWith("content://")
        if (aIsDocument != b.startsWith("content://")) return false
        return if (aIsDocument) {
            val aId = documentId(a)
            aId != null && aId == documentId(b)
        } else {
            File(plainPath(a)).absoluteFile.normalize() == File(plainPath(b)).absoluteFile.normalize()
        }
    }

    /**
     * The name a backup was set aside from: without [BACKUP_SUFFIX] and a
     * counter, ours (`.1`) or a SAF provider's (` (1)`).
     */
    private fun originalNameOf(backupName: String): String =
        backupName.removeSuffix(BACKUP_SUFFIX).replace(BACKUP_COUNTER, "")

    /** The (still encoded) document id of a SAF document URI, or null without one. */
    private fun documentId(uri: String): String? =
        uri.substringAfterLast("/document/", missingDelimiterValue = "")
            .substringBefore('?')
            .ifEmpty { null }

    private companion object {
        /** Name suffix of a file [setAside] moved out of the way. */
        const val BACKUP_SUFFIX = ".swapbak"

        /** A counter added to a backup name that was taken. */
        val BACKUP_COUNTER = Regex("""( \(\d+\)|\.\d+)$""")
    }

    private fun plainPath(path: String): String =
        if (path.startsWith("file://")) path.toUri().path ?: path.removePrefix("file://") else path
}
