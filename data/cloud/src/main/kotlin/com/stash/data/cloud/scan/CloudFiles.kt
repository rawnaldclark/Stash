package com.stash.data.cloud.scan

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Which cloud files the scan cares about, and how they're named in the library. */
object CloudFiles {

    /** Audio Stash plays, by extension. */
    private val AUDIO_EXTENSIONS = setOf("flac", "mp3", "m4a", "aac", "alac", "opus", "ogg", "oga", "wav")

    /** Audio by MIME when the name has no usable extension (video MIME types are excluded). */
    private val AUDIO_MIMES = setOf(
        "audio/flac", "audio/x-flac", "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac",
        "audio/ogg", "audio/opus", "audio/wav", "audio/x-wav", "audio/vnd.wave",
    )

    /** Sibling album art, in preference order (`cover.jpg` beats `folder.png`). */
    private val COVER_NAMES = listOf("cover", "folder", "front", "album")
    private val COVER_EXTENSIONS = setOf("jpg", "jpeg", "png")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun isAudio(name: String, mimeType: String?): Boolean {
        val ext = extension(name)
        if (ext in AUDIO_EXTENSIONS) return true
        return ext.isEmpty() && mimeType?.lowercase() in AUDIO_MIMES
    }

    /** Rank of a sibling cover image (lower is better), null when [name] isn't one. */
    fun coverRank(name: String): Int? {
        if (extension(name) !in COVER_EXTENSIONS) return null
        val base = name.substringBeforeLast('.').trim().lowercase()
        return COVER_NAMES.indexOf(base).takeIf { it >= 0 }
    }

    /**
     * The `file_format` a cloud song is filed under: what its container really holds when the
     * tags said (an `.m4a` holding ALAC is "alac", so it counts as lossless), else the extension.
     */
    fun formatFor(name: String, codec: String?): String {
        val ext = extension(name)
        return when {
            codec == "alac" -> "alac"
            codec == "flac" -> "flac"
            codec == "opus" -> "opus"
            ext == "oga" -> "ogg"
            ext.isNotEmpty() -> ext
            codec == "mp3" -> "mp3"
            codec == "aac" -> "m4a"
            codec == "vorbis" -> "ogg"
            codec == "pcm" -> "wav"
            else -> "mp3"
        }
    }
}

/**
 * Where cloud songs' cover art is kept on the phone: `files/cloud_art/<sha1>.<ext>`, one file per
 * distinct image, so the twelve songs of an album that each embed the same cover share one file.
 */
@Singleton
class CloudArtStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir: File by lazy { File(context.filesDir, DIR).apply { mkdirs() } }

    /** Saves [bytes] (if not already there) and returns the absolute path; null when they aren't an image. */
    fun save(bytes: ByteArray, mimeType: String?): String? {
        val ext = imageExtension(bytes, mimeType) ?: return null
        val name = sha1(bytes) + "." + ext
        val file = File(dir, name)
        if (!file.exists()) {
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) tmp.delete()
        }
        return file.takeIf { it.exists() }?.absolutePath
    }

    companion object {
        const val DIR = "cloud_art"

        /** jpg / png / webp by magic bytes, then by declared MIME. */
        internal fun imageExtension(bytes: ByteArray, mimeType: String?): String? = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
            bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            mimeType?.contains("png") == true -> "png"
            mimeType?.contains("jpeg") == true || mimeType?.contains("jpg") == true -> "jpg"
            else -> null
        }

        private fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
