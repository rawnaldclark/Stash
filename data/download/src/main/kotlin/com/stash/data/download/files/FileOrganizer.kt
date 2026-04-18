package com.stash.data.download.files

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.stash.core.data.prefs.StoragePreference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages file paths for downloaded music.
 *
 * Supports two storage destinations:
 *  - **Internal** (default): tracks live under `context.filesDir/music/<artist>/<album>/`.
 *    Pure `java.io.File` API. Tracks are deleted when the app is uninstalled
 *    and are not visible to other apps.
 *  - **External** (user-chosen via SAF): when the user picks an SD card or
 *    USB-OTG folder via `ACTION_OPEN_DOCUMENT_TREE`, new downloads are
 *    written to that location via `ContentResolver` / `DocumentFile`. These
 *    files survive app uninstall and are accessible to other apps + over
 *    USB/PC — users can take their library anywhere.
 *
 * yt-dlp always writes to the internal cache (`getTempDir()`); the
 * destination switch happens in [commitDownload] after the download
 * completes, which copies the temp file to the final location and cleans up.
 */
@Singleton
class FileOrganizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storagePreference: StoragePreference,
) {
    /** Root directory for all internally-stored downloaded music files. */
    private val musicDir: File get() = File(context.filesDir, "music").also { it.mkdirs() }

    /**
     * Returns the internal directory for a specific artist/album combination.
     * Only used for internal-storage downloads; SAF downloads compute their
     * own path inside [commitDownload].
     */
    fun getTrackDir(artist: String, album: String?): File {
        val artistSlug = slugify(artist)
        val albumSlug = if (!album.isNullOrBlank()) slugify(album) else "singles"
        return File(musicDir, "$artistSlug/$albumSlug").also { it.mkdirs() }
    }

    /**
     * Returns the internal file path for a downloaded track. Prefer
     * [commitDownload] which automatically handles both internal and SAF
     * destinations; this getter is retained for callers that only need the
     * target path (e.g. existence checks before download).
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
     * Calculates the total storage consumed by internally-downloaded files.
     * Does not include tracks written to an external SAF target (those
     * aren't app-owned, and the user sees them in their file manager).
     */
    fun getTotalStorageBytes(): Long =
        musicDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Moves [tempFile] (written by yt-dlp to the cache) into its final
     * destination and returns the path/URI to store in
     * [com.stash.core.model.Track.filePath]. Deletes [tempFile] on success.
     *
     * If the user has chosen an external SAF folder via Settings, the file
     * is copied there through `ContentResolver` and the returned path is a
     * `content://` URI string. ExoPlayer + MediaPlayer + MediaMetadataRetriever
     * all accept content URIs natively so playback works without further
     * changes. Otherwise the file is moved into internal storage and the
     * returned path is the absolute File path (existing behaviour).
     */
    suspend fun commitDownload(
        tempFile: File,
        artist: String,
        album: String?,
        title: String,
        format: String,
    ): CommittedTrack {
        val size = tempFile.length()
        val externalTree = storagePreference.externalTreeUri.first()
        return if (externalTree == null) {
            val finalFile = getTrackFile(artist, album, title, format)
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()
            CommittedTrack(finalFile.absolutePath, size)
        } else {
            val safUriString = writeToSafTree(tempFile, externalTree, artist, album, title, format)
            tempFile.delete()
            CommittedTrack(safUriString, size)
        }
    }

    /**
     * Writes [tempFile] to the user's SAF tree under `artist/album/title.ext`.
     * Returns the created document's URI as a string. Throws if the tree
     * URI is stale (permission revoked) or I/O fails — caller should log
     * and leave the temp file in place for retry.
     */
    private fun writeToSafTree(
        tempFile: File,
        treeUri: Uri,
        artist: String,
        album: String?,
        title: String,
        format: String,
    ): String {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Could not open SAF tree; permission may have been revoked: $treeUri")
        val artistSlug = slugify(artist)
        val albumSlug = if (!album.isNullOrBlank()) slugify(album) else "singles"
        val artistDir = root.findOrCreateDir(artistSlug)
        val albumDir = artistDir.findOrCreateDir(albumSlug)
        val titleSlug = slugify(title)
        val filename = "$titleSlug.$format"
        // Overwrite: delete any existing file with the same name before creating.
        albumDir.findFile(filename)?.delete()
        val target = albumDir.createFile(mimeTypeFor(format), filename)
            ?: error("Could not create SAF file '$filename' under ${albumDir.uri}")
        tempFile.inputStream().use { input ->
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                input.copyTo(output)
            } ?: error("Could not open SAF output stream for '$filename'")
        }
        return target.uri.toString()
    }

    private fun DocumentFile.findOrCreateDir(name: String): DocumentFile =
        findFile(name)?.takeIf { it.isDirectory }
            ?: createDirectory(name)
            ?: error("Could not create SAF directory '$name' under $uri")

    private fun mimeTypeFor(format: String): String = when (format.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        else -> "audio/*"
    }

    /**
     * Converts a human-readable string into a filesystem-safe slug.
     *
     * Lowercases, strips non-alphanumeric characters (except spaces and hyphens),
     * collapses whitespace into single hyphens, and truncates to 60 characters.
     */
    private fun slugify(input: String): String =
        input.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .trim('-')
            .take(60)

    /**
     * Result of a successful [commitDownload]. `filePath` is either an
     * absolute `java.io.File` path (internal storage) or a `content://…`
     * URI string (SAF target); `sizeBytes` is the track's size at the
     * moment it was written.
     */
    data class CommittedTrack(
        val filePath: String,
        val sizeBytes: Long,
    )
}
