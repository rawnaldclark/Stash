package com.stash.core.data.files

import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.stash.core.common.constants.StashConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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

    /** Best-effort delete of the file behind [path]. No-op on null/blank/missing. */
    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            if (path.startsWith("content://")) {
                DocumentFile.fromSingleUri(context, path.toUri())?.delete()
            } else {
                File(plainPath(path)).delete()
            }
        }
    }

    private fun plainPath(path: String): String =
        if (path.startsWith("file://")) path.toUri().path ?: path.removePrefix("file://") else path
}
