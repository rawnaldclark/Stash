package com.stash.core.data.home

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where Home's own shelves keep what they last built: one small JSON file each under `filesDir/home_shelves/`
 * ("New from your artists", "Moods & moments", "Because you like"). Files, not the main database: a shelf is
 * written whole and read whole, nothing is queried by column, and the Room version must not change for a cache.
 *
 * Writes go to a temp file renamed over the old one, so a crash mid-write leaves the previous version. An
 * unreadable file (corrupt, or from a later format) reads as nothing, never a crash: the shelf is simply built
 * again. Not thread-safe on its own; each shelf's repository serialises its own file.
 */
@Singleton
class HomeShelfStore internal constructor(private val root: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIR))

    /** The shelf saved as [name], or null when there is none or it can't be read. */
    fun <T> read(name: String, serializer: KSerializer<T>): T? {
        val file = root.resolve("$name.json")
        if (!file.isFile) return null
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "unreadable $name: ${e.message}")
            null
        }
    }

    /** Saves [value] as [name]. A file that can't be written only costs a rebuild next time. */
    fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        try {
            root.mkdirs()
            val file = root.resolve("$name.json")
            val tmp = root.resolve("$name.json.tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            if (!tmp.renameTo(file)) {
                // Rename-over refused (Windows-style filesystems, some FUSE mounts): replace by copy.
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't write $name: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HomeShelfStore"
        const val DIR = "home_shelves"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
