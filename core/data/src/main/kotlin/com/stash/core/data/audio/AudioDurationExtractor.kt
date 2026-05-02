package com.stash.core.data.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads duration metadata off a downloaded audio file.
 *
 * The file itself is the authoritative source of truth — ExoPlayer reads
 * duration the same way at playback time, so using `MediaMetadataRetriever`
 * here guarantees the playlist UI agrees with the player's progress bar.
 *
 * Why this matters: tracks coming through the Stash Discover pipeline are
 * created as stubs with `duration_ms = 0` and rely on `persistMatchMetadata`
 * to fill duration from the YouTube match. When the match result has no
 * duration (yt-dlp direct fallback, or UGC uploads that surface as plain
 * video renderers), `fillMissingMetadata` writes 0, and the playlist row
 * displays a blank duration even though the file on disk is a real N-minute
 * track. Pulling from the file sidesteps the entire metadata-provider chain.
 *
 * Handles both app-internal java.io.File paths and SAF-backed `content://`
 * URIs (SD card / USB-OTG libraries) — same dispatch pattern as the
 * SAF-aware delete helper in `MusicRepositoryImpl`.
 */
@Singleton
class AudioDurationExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Returns the track's duration in milliseconds, or null if the file
     * is missing / corrupt / the container doesn't expose duration. Never
     * throws — a bad file returns null so a batch caller can continue.
     */
    fun extractMs(filePath: String): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (filePath.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(filePath))
            } else {
                retriever.setDataSource(filePath)
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0 }
        } catch (e: Exception) {
            Log.w(TAG, "extractMs failed for $filePath: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Reads duration + codec + bitrate from the file in a single retriever
     * pass. Used by the download path to populate `file_format` and
     * `quality_kbps` (which the sync writer historically left at defaults)
     * and to reconcile duration against sync metadata when yt-dlp matched a
     * different-length cut than Spotify's track length implied.
     *
     * Returns null only when the file can't be opened at all; partial
     * metadata returns a record with the extracted fields and zero/unknown
     * for the rest, so callers can still trust whichever field they need.
     */
    fun extract(filePath: String): AudioMetadata? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (filePath.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(filePath))
            } else {
                retriever.setDataSource(filePath)
            }
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val bitrateBps = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 0
            val mime = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            AudioMetadata(
                durationMs = durationMs,
                bitrateKbps = if (bitrateBps > 0) bitrateBps / 1000 else 0,
                format = normalizeFormat(mime),
            )
        } catch (e: Exception) {
            Log.w(TAG, "extract failed for $filePath: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val TAG = "AudioDurationExtractor"

        /**
         * Maps MediaMetadataRetriever's MIME string to the short format
         * tags we store in `file_format`. Kept stable so the Library
         * Health screen can group by these values without surprises.
         */
        internal fun normalizeFormat(mime: String?): String {
            if (mime == null) return "unknown"
            val lower = mime.lowercase()
            return when {
                "mp4a" in lower || "aac" in lower -> "aac"
                "opus" in lower -> "opus"
                "vorbis" in lower -> "vorbis"
                "flac" in lower -> "flac"
                "mpeg" in lower || "mp3" in lower -> "mp3"
                else -> lower.substringAfter("audio/", "").ifBlank { "unknown" }
            }
        }
    }
}

/**
 * Bundle of audio-file metadata read from the container itself. Each field
 * is optional in practice — `MediaMetadataRetriever` is best-effort and
 * sometimes returns nulls for fields a given codec/container exposes
 * differently. Zero / "unknown" indicate "not available" rather than
 * "actually zero."
 */
data class AudioMetadata(
    val durationMs: Long,
    val bitrateKbps: Int,
    val format: String,
)
