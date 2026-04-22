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

    companion object {
        private const val TAG = "AudioDurationExtractor"
    }
}
