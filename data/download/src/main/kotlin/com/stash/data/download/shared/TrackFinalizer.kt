package com.stash.data.download.shared

import android.util.Log
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.model.Track
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.lossless.AudioFormat
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared file-side finalisation for downloaded audio. Used by sync
 * ([com.stash.data.download.DownloadManager.tryLosslessDownload])
 * and search ([com.stash.data.download.search.SearchDownloadCoordinator]).
 *
 * Steps:
 *  1. Embed title/artist/album metadata into the file via ffmpeg.
 *  2. Move the temp file to the canonical library path.
 *  3. Probe the on-disk file for codec/bitrate/sample-rate/bit-depth.
 *
 * Returns [FinalizeResult]. **No DB writes** — caller does its own
 * row insert/update so sync preserves spotifyUri/isrc/album/explicit
 * and search runs `linkTrackToDownloadsMix` on its own path.
 *
 * Loudness measurement is intentionally NOT part of this flow anymore
 * (v0.9.26 hotfix). The synchronous ffmpeg ebur128 pass added ~25–50 s
 * per track and serialised entire albums behind a single measurement
 * mutex. Callers now trigger
 * [com.stash.core.data.audio.LoudnessMeasurer.measureAndPersistInBackground]
 * after their DB write so the download returns immediately and the
 * measurement happens off-thread.
 */
@Singleton
class TrackFinalizer @Inject constructor(
    private val metadataEmbedder: MetadataEmbedder,
    private val fileOrganizer: FileOrganizer,
    private val audioExtractor: AudioDurationExtractor,
    private val albumArtCache: AlbumArtCache,
) {
    /**
     * Embed metadata, commit to library, probe. Caller passes a [Track]
     * built from whatever shape they have ([com.stash.core.model.Track]
     * for sync; a stub built from a TrackItem for search).
     *
     * Embedding is non-fatal — file remains playable on failure.
     * The probe in [FinalizeResult.Success.meta] may be null if the
     * committed file cannot be opened by [MediaMetadataRetriever].
     */
    suspend fun finalizeFile(
        sourceFile: File,
        track: Track,
        format: AudioFormat,
        embedMetadata: Boolean = true,
    ): FinalizeResult = try {
        if (embedMetadata) {
            val art = runCatching { albumArtCache.resolveArt(track) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "art resolve failed: ${e.message}")
                }
                .getOrNull()
            runCatching { metadataEmbedder.embedMetadata(sourceFile, track, art) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "metadata embed failed: ${e.message}")
                }
        }
        val committed: CommittedTrack = fileOrganizer.commitDownload(
            tempFile = sourceFile,
            artist = track.artist,
            album = track.album.takeIf { it.isNotBlank() },
            title = track.title,
            format = format.fileExtension.ifBlank { format.codec.ifBlank { "flac" } },
            // Lets the Per-playlist layout (#198) file the track under its
            // playlist folder; null/unknown ids fall back gracefully.
            trackId = track.id.takeIf { it > 0 },
        )
        val meta: AudioMetadata? = audioExtractor.extract(committed.filePath)
        FinalizeResult.Success(committed, meta)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FinalizeResult.Failed(e.message ?: "finalize failed")
    }

    sealed interface FinalizeResult {
        /**
         * File was successfully embedded, committed, and probed.
         * [meta] is null only if [AudioDurationExtractor] could not
         * open the committed file (rare; file is still on disk and playable).
         */
        data class Success(
            val committed: CommittedTrack,
            val meta: AudioMetadata?,
        ) : FinalizeResult

        /** A fatal step (commit) failed; the temp file has been deleted by the caller. */
        data class Failed(val message: String) : FinalizeResult
    }

    companion object {
        private const val TAG = "TrackFinalizer"
    }
}
