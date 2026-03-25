package com.stash.data.download.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages file paths for downloaded music, organizing tracks into an
 * artist/album directory hierarchy under the app's internal storage.
 *
 * All directory creation is idempotent — callers can safely invoke any
 * getter without worrying about missing parent directories.
 */
@Singleton
class FileOrganizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Root directory for all downloaded music files. */
    private val musicDir: File get() = File(context.filesDir, "music").also { it.mkdirs() }

    /**
     * Returns the directory for a specific artist/album combination.
     * Albums default to "singles" when no album name is provided.
     *
     * @param artist Artist name (will be slugified).
     * @param album  Album name, or null/blank for loose singles.
     * @return Directory that is guaranteed to exist.
     */
    fun getTrackDir(artist: String, album: String?): File {
        val artistSlug = slugify(artist)
        val albumSlug = if (!album.isNullOrBlank()) slugify(album) else "singles"
        return File(musicDir, "$artistSlug/$albumSlug").also { it.mkdirs() }
    }

    /**
     * Returns the target file path for a downloaded track.
     *
     * @param artist Artist name (used to determine directory).
     * @param album  Album name (used to determine directory).
     * @param title  Track title (slugified for the filename).
     * @param format File extension without the dot (default "opus").
     * @return File reference within the organized directory tree.
     */
    fun getTrackFile(artist: String, album: String?, title: String, format: String = "opus"): File {
        val dir = getTrackDir(artist, album)
        val titleSlug = slugify(title)
        return File(dir, "$titleSlug.$format")
    }

    /** Temporary download directory inside the cache. Cleaned by the OS as needed. */
    fun getTempDir(): File = File(context.cacheDir, "downloads").also { it.mkdirs() }

    /** Directory for cached album artwork files. */
    fun getAlbumArtDir(): File = File(context.cacheDir, "albumart").also { it.mkdirs() }

    /** Returns the file path for a specific album's artwork. */
    fun getAlbumArtFile(albumId: String): File = File(getAlbumArtDir(), "$albumId.jpg")

    /**
     * Calculates the total storage consumed by downloaded music files.
     *
     * @return Total size in bytes across all files in the music directory.
     */
    fun getTotalStorageBytes(): Long {
        return musicDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Converts a human-readable string into a filesystem-safe slug.
     *
     * Lowercases, strips non-alphanumeric characters (except spaces and hyphens),
     * collapses whitespace into single hyphens, and truncates to 60 characters.
     */
    private fun slugify(input: String): String {
        return input.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .take(60)
    }
}
