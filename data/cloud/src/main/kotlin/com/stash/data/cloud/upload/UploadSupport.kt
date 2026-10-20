package com.stash.data.cloud.upload

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.stash.core.data.db.dao.CloudDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Where a song goes in the user's Drive: folders below "Stash" ([folders]) and its file name. */
data class UploadPlace(val folders: List<String>, val fileName: String)

/**
 * Names a song's place in Drive the way the downloader names it on the phone (Artist/Album
 * layout, the same slugs, `singles` for no album). Implemented next to the downloader's own
 * resolver (data:download), which this module can't see.
 */
fun interface UploadNaming {
    /** [extension] without the dot ("flac"). */
    fun place(artist: String, album: String, title: String, extension: String): UploadPlace
}

/** Drive refused because the user's storage is full: backup pauses until the user tries again. */
class DriveFullException : IOException("Google Drive is full")

/**
 * The song's file on the phone couldn't be read (an SD card error, a folder grant gone, the file
 * changing mid-upload): a problem with this song, not with the connection, so the worker counts a
 * failed try for it and moves on instead of retrying the whole queue behind it.
 */
class LocalFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A resumable session Drive no longer knows (404/410, about a week old): start a new one. */
class UploadSessionExpiredException(status: Int) : IOException("upload session answered $status")

/**
 * The bytes of a downloaded song, by the `tracks.file_path` the downloader stored: a plain path
 * (internal storage) or a `content://` document (an SD card or folder the user picked).
 */
interface LocalAudioFiles {
    /** The file's size, or null when it is gone. */
    suspend fun size(path: String): Long?

    /** Opens the file positioned at [offset]. The caller closes it. */
    fun open(path: String, offset: Long): InputStream

    /** Lower-case hex md5 of the whole file (what Drive reports as md5Checksum). */
    suspend fun md5(path: String): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("MD5")
        try {
            open(path, 0).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
        } catch (e: IOException) {
            throw LocalFileException("the song's file couldn't be read", e)
        } catch (e: SecurityException) {
            throw LocalFileException("the song's file couldn't be read", e)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@Singleton
class AndroidLocalAudioFiles @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocalAudioFiles {

    override suspend fun size(path: String): Long? = withContext(Dispatchers.IO) {
        try {
            if (isDocument(path)) {
                context.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { it.statSize.takeIf { s -> s >= 0 } }
            } else {
                File(path).takeIf { it.isFile }?.length()
            }
        } catch (e: SecurityException) {
            null // the folder grant is gone: as good as no file
        } catch (e: IOException) {
            null
        }
    }

    override fun open(path: String, offset: Long): InputStream {
        val stream: FileInputStream = if (isDocument(path)) {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                ?: throw IOException("file not readable")
            ParcelFileDescriptor.AutoCloseInputStream(pfd)
        } else {
            FileInputStream(File(path))
        }
        // A seek, not a read-and-drop: resuming at 300 MB costs nothing.
        if (offset > 0) stream.channel.position(offset)
        return stream
    }

    private fun isDocument(path: String) = path.startsWith("content://")
}

/** The private tags Stash puts on what it uploads, so a second phone or a reinstall finds them. */
object UploadKeys {
    /** App property naming the song (see [trackKey]). */
    const val TRACK_KEY = "stashTrackKey"

    /** App property on the folders Stash creates. */
    const val FOLDER_KEY = "stashFolder"

    /**
     * A stable name for a song across phones: its ISRC when known, else its artist, title and
     * length in whole seconds, lower-cased with spaces collapsed; hashed (Drive limits a property
     * to 124 bytes, and the words needn't be readable). With the file's md5 it decides whether a
     * song is already in Drive.
     */
    fun trackKey(isrc: String?, artist: String, title: String, durationMs: Long): String {
        val words = isrc?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { "isrc:$it" }
            ?: "w:${norm(artist)}|${norm(title)}|${durationMs / 1000}"
        val hash = MessageDigest.getInstance("SHA-256").digest(words.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }.take(40)
    }

    private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    /**
     * This phone's downloads that aren't linked to a cloud file, by [trackKey] (the first one
     * wins): what the Drive scan uses to recognise a saved song that is already here.
     */
    suspend fun downloadsByKey(dao: CloudDao): Map<String, Long> = buildMap {
        for (d in dao.unlinkedDownloads()) putIfAbsent(trackKey(d.isrc, d.artist, d.title, d.durationMs), d.id)
    }

    /** The MIME type Drive is told for a song file, by its extension. */
    fun mimeType(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "flac" -> "audio/flac"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac", "alac" -> "audio/mp4"
        "opus", "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }
}
