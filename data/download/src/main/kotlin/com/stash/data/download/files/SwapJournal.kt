package com.stash.data.download.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
 * a temp name and moved into place so a crash mid-write can't leave half an
 * entry. A track has at most one swap running at a time.
 */
@Singleton
class SwapJournal @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Entry(val trackId: Long, val originalPath: String, val backupPath: String)

    private val dir: File get() = File(context.noBackupFilesDir, "swap-journal")

    /** Writes [entry] down, replacing an earlier one for the same track. */
    fun record(entry: Entry) {
        val dir = dir.apply { mkdirs() }
        val temp = File(dir, "${entry.trackId}.tmp")
        temp.writeText("${entry.originalPath}\n${entry.backupPath}")
        Files.move(temp.toPath(), File(dir, entry.trackId.toString()).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    fun clear(trackId: Long) {
        File(dir, trackId.toString()).delete()
    }

    /** Every entry still written down, by track id. */
    fun pending(): List<Entry> = dir.listFiles().orEmpty()
        .mapNotNull { file ->
            val trackId = file.name.toLongOrNull() ?: return@mapNotNull null
            val lines = runCatching { file.readLines() }.getOrNull() ?: return@mapNotNull null
            if (lines.size < 2) null else Entry(trackId, lines[0], lines[1])
        }
        .sortedBy { it.trackId }
}
