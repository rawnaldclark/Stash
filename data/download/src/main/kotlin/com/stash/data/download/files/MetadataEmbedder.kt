package com.stash.data.download.files

import android.content.Context
import com.stash.core.model.Track
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Embeds metadata (title, artist, album) into audio files using ffmpeg.
 *
 * The ffmpeg binary is bundled by the youtubedl-android library as a native .so.
 * If tagging fails for any reason, the original untagged file is preserved —
 * an untagged download is preferable to a missing one.
 */
@Singleton
class MetadataEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileOrganizer: FileOrganizer,
) {
    /**
     * Strips control characters (0x00-0x1F) from metadata values to prevent
     * ffmpeg command injection via crafted track names.
     */
    private fun sanitize(value: String): String = value.replace(Regex("[\\x00-\\x1f]"), "")

    /**
     * Embeds metadata (title, artist, album, track number) into an audio file
     * using ffmpeg via the native .so bundled by youtubedl-android.
     * Also embeds album art if available.
     *
     * @param audioFile    The downloaded audio file to tag.
     * @param track        Track metadata to embed.
     * @param albumArtFile Optional album art JPEG to attach as cover art.
     * @return The tagged audio file (same path as [audioFile]).
     */
    suspend fun embedMetadata(
        audioFile: File,
        track: Track,
        albumArtFile: File? = null,
    ): File = withContext(Dispatchers.IO) {
        val outputFile = File(
            audioFile.parent,
            "${audioFile.nameWithoutExtension}_tagged.${audioFile.extension}",
        )

        val args = buildList {
            add("-i")
            add(audioFile.absolutePath)

            if (albumArtFile != null && albumArtFile.exists()) {
                add("-i")
                add(albumArtFile.absolutePath)
                add("-map")
                add("0:a")
                add("-map")
                add("1:0")
                add("-disposition:v:0")
                add("attached_pic")
            }

            add("-metadata")
            add("title=${sanitize(track.title)}")
            add("-metadata")
            add("artist=${sanitize(track.artist)}")
            if (track.album.isNotEmpty()) {
                add("-metadata")
                add("album=${sanitize(track.album)}")
            }
            add("-c")
            add("copy")
            add("-y")
            add(outputFile.absolutePath)
        }

        try {
            // youtubedl-android (JunkFood02 fork) bundles ffmpeg as a native .so
            val ffmpegPath = resolveFfmpegBinary()
            if (ffmpegPath != null) {
                val process = ProcessBuilder(listOf(ffmpegPath.absolutePath) + args)
                    .redirectErrorStream(true)
                    .start()
                process.waitFor()

                if (outputFile.exists() && outputFile.length() > 0) {
                    audioFile.delete()
                    outputFile.renameTo(audioFile)
                }
            }
        } catch (_: Exception) {
            // If tagging fails, keep the untagged file — better than no file
            outputFile.delete()
        }

        audioFile
    }

    /**
     * Resolves the ffmpeg binary path from the native library directory.
     * The JunkFood02 fork may bundle it as `libffmpeg.so` or `libffmpeg.zip.so`.
     *
     * @return The ffmpeg [File] if found, null otherwise.
     */
    private fun resolveFfmpegBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val candidates = listOf("libffmpeg.so", "libffmpeg.zip.so")
        return candidates
            .map { File(nativeDir, it) }
            .firstOrNull { it.exists() }
    }
}
