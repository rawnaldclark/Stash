package com.stash.feature.library.allsongs

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cover files of scanned songs, in their own directory. A file is named by its content (songs of
 * one album share it), and a row stores only that name: an absolute path would break when the
 * app's files move (a restore, a different storage).
 */
@Singleton
class ScannedCoverStore @VisibleForTesting internal constructor(val dir: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR_NAME))

    /**
     * Saves [bytes] and returns the file name to store: "" when there is no art, null when it
     * couldn't be written (store NULL, so the next scan tries again).
     */
    fun save(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty()) return ""
        return try {
            dir.mkdirs()
            val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
            val name = "$PREFIX$hash.jpg"
            val file = File(dir, name)
            // Written whole or not at all: to a temp name, then renamed. A file of the wrong size
            // (one an older build left half-written) is replaced.
            if (!file.exists() || file.length() != bytes.size.toLong()) {
                val tmp = File(dir, "$PREFIX${UUID.randomUUID()}.tmp")
                try {
                    tmp.writeBytes(bytes)
                    // An atomic replace, as AlbumArtCache does: File.renameTo can't replace an
                    // existing file everywhere, so a short one would otherwise stay.
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    tmp.delete()
                }
            }
            name
        } catch (e: Exception) {
            null
        }
    }

    fun file(name: String): File = File(dir, name)

    /** The file names on disk, to tell which rows' covers have gone missing. */
    fun names(): Set<String> = dir.list()?.toHashSet() ?: emptySet()

    /** Deletes our files (and half-written temp files) whose name isn't in [keep]. */
    fun sweep(keep: Set<String>) {
        dir.listFiles()?.forEach { f ->
            if (f.name.startsWith(PREFIX) && f.name !in keep) f.delete()
        }
    }

    companion object {
        const val DIR_NAME = "scanned_art"
        const val PREFIX = "scanned_"
    }
}