package com.stash.data.download.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a wrong-match swap moved a track's old file before saving over its
 * spot (#531 review). An entry lives from the move until the swap has either
 * finished or put the file back, so a swap cut short by the app being killed
 * can be repaired at the next start ([SwapCoordinator.recoverInterruptedSwaps])
 * without walking the whole library, which on a SAF tree costs a call per
 * folder.
 *
 * One small file per track under `noBackupFilesDir/swap-journal`, written to
 * a temp name, synced and moved into place so a crash mid-write can't leave
 * half an entry. A track has at most one swap running at a time.
 */
@Singleton
class SwapJournal @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Entry(
        val trackId: Long,
        val originalPath: String,
        val backupPath: String,
        /**
         * The video the swap was for. The final write sets it on the track
         * with the new file, so it tells a finished swap from a cut-short
         * one. Null in entries from before it was written down.
         */
        val newVideoId: String? = null,
    )

    /** True while [trackId] has an entry: a set-aside file not yet handled. */
    fun has(trackId: Long): Boolean = File(dir, trackId.toString()).exists()

    private val dir: File get() = File(context.noBackupFilesDir, "swap-journal")

    /** Writes [entry] down, replacing an earlier one for the same track. */
    fun record(entry: Entry) {
        val dir = dir.apply { mkdirs() }
        val temp = File(dir, "${entry.trackId}$TEMP_SUFFIX")
        val text = listOfNotNull(entry.originalPath, entry.backupPath, entry.newVideoId).joinToString("\n")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync() // on disk before the rename makes it count
        }
        Files.move(temp.toPath(), File(dir, entry.trackId.toString()).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun clear(trackId: Long) {
        File(dir, trackId.toString()).delete()
    }

    /**
     * Every entry still written down, by track id. A temp file left by a crash
     * mid-write, or anything else unreadable, is deleted on the way.
     */
    fun pending(): List<Entry> = dir.listFiles().orEmpty()
        .mapNotNull { file ->
            val trackId = file.name.toLongOrNull()
            val lines = if (trackId == null) null else runCatching { file.readLines() }.getOrNull()
            if (trackId == null || lines == null || lines.size < 2) {
                file.delete()
                null
            } else {
                Entry(trackId, lines[0], lines[1], lines.getOrNull(2)?.takeIf { it.isNotBlank() })
            }
        }
        .sortedBy { it.trackId }

    private companion object {
        const val TEMP_SUFFIX = ".tmp"
    }
}
