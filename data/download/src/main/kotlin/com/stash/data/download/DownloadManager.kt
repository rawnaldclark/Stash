package com.stash.data.download

import android.util.Log
import com.stash.core.model.Track
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.matching.DuplicateDetectionService
import com.stash.data.download.matching.MatchScorer
import com.stash.data.download.matching.YouTubeSearchExecutor
import com.stash.data.download.model.DownloadProgress
import com.stash.data.download.model.DownloadStatus
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.prefs.toYtDlpArgs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of the full download pipeline for a single track.
 */
sealed class TrackDownloadResult {
    /** Download succeeded. [filePath] is the absolute path on disk. */
    data class Success(val filePath: String) : TrackDownloadResult()

    /** No YouTube match found for the track. */
    data object Unmatched : TrackDownloadResult()

    /** Download failed. [error] describes why. */
    data class Failed(val error: String) : TrackDownloadResult()
}

/**
 * Orchestrates the full download pipeline for a single track:
 *
 * 1. Search YouTube for the track (or use a pre-resolved URL).
 * 2. Score results and select the best match.
 * 3. Download audio via yt-dlp.
 * 4. Embed metadata (title, artist, album) using ffmpeg.
 * 5. Move the file to the organized artist/album directory.
 *
 * Concurrency is limited to 3 simultaneous downloads via a [Semaphore].
 * Real-time progress is emitted through the [progress] shared flow.
 */
@Singleton
class DownloadManager @Inject constructor(
    private val downloadExecutor: DownloadExecutor,
    private val searchExecutor: YouTubeSearchExecutor,
    private val matchScorer: MatchScorer,
    private val duplicateDetection: DuplicateDetectionService,
    private val fileOrganizer: FileOrganizer,
    private val metadataEmbedder: MetadataEmbedder,
    private val qualityPrefs: QualityPreferencesManager,
) {
    /** Limits concurrent downloads. 5 is a good balance — each download is mostly
     *  network-bound (waiting for data) so parallelism helps without overwhelming CPU. */
    private val concurrencySemaphore = Semaphore(5)

    private val _progress = MutableSharedFlow<DownloadProgress>(replay = 1)

    /** Emits real-time progress snapshots for all in-flight downloads. */
    val progress: SharedFlow<DownloadProgress> = _progress.asSharedFlow()

    companion object {
        private const val TAG = "DownloadManager"
    }

    /**
     * Downloads a single track through the full pipeline.
     *
     * @param track          The track to download.
     * @param preResolvedUrl Optional YouTube URL if already known (skips search).
     * @return A [TrackDownloadResult] with either the file path or a detailed error.
     */
    suspend fun downloadTrack(
        track: Track,
        preResolvedUrl: String? = null,
    ): TrackDownloadResult {
        concurrencySemaphore.acquire()
        try {
            return executeDownload(track, preResolvedUrl)
        } finally {
            concurrencySemaphore.release()
        }
    }

    /**
     * Executes the download pipeline for a single track.
     */
    private suspend fun executeDownload(track: Track, preResolvedUrl: String?): TrackDownloadResult {
        emitProgress(track.id, 0f, DownloadStatus.MATCHING)

        // Step 1: Resolve YouTube URL
        val youtubeUrl = preResolvedUrl ?: resolveUrl(track)
        if (youtubeUrl == null) {
            emitProgress(track.id, 0f, DownloadStatus.UNMATCHED)
            return TrackDownloadResult.Unmatched
        }

        emitProgress(track.id, 0.1f, DownloadStatus.DOWNLOADING)

        // Step 2: Get quality args from user preferences
        val qualityTier = qualityPrefs.qualityTier.first()
        val qualityArgs = qualityTier.toYtDlpArgs()

        // Step 3: Download via yt-dlp
        val tempDir = fileOrganizer.getTempDir()
        val tempFilename = "dl_${track.id}"

        val dlResult = downloadExecutor.download(
            url = youtubeUrl,
            outputDir = tempDir,
            filename = tempFilename,
            qualityArgs = qualityArgs,
            onProgress = { progress ->
                emitProgress(track.id, 0.1f + progress * 0.7f, DownloadStatus.DOWNLOADING)
            },
        )

        val downloadedFile = when (dlResult) {
            is DownloadResult.Success -> dlResult.file
            is DownloadResult.YtDlpError -> {
                emitProgress(track.id, 0f, DownloadStatus.FAILED)
                return TrackDownloadResult.Failed("yt-dlp: ${dlResult.message.take(500)}")
            }
            is DownloadResult.NoOutput -> {
                emitProgress(track.id, 0f, DownloadStatus.FAILED)
                val detail = buildString {
                    append("yt-dlp produced no output file.")
                    dlResult.stderr?.let { append(" stderr: ${it.take(300)}") }
                }
                return TrackDownloadResult.Failed(detail)
            }
            is DownloadResult.Error -> {
                emitProgress(track.id, 0f, DownloadStatus.FAILED)
                return TrackDownloadResult.Failed("Error: ${dlResult.message}")
            }
        }

        emitProgress(track.id, 0.8f, DownloadStatus.PROCESSING)

        // Step 4: Embed metadata tags
        metadataEmbedder.embedMetadata(downloadedFile, track)

        emitProgress(track.id, 0.9f, DownloadStatus.TAGGING)

        // Step 5: Move to organized directory
        val finalFile = fileOrganizer.getTrackFile(
            artist = track.artist,
            album = track.album.ifEmpty { null },
            title = track.title,
            format = downloadedFile.extension,
        )
        downloadedFile.copyTo(finalFile, overwrite = true)
        downloadedFile.delete()

        Log.i(TAG, "Downloaded: ${track.artist} - ${track.title} → ${finalFile.absolutePath}")
        emitProgress(track.id, 1f, DownloadStatus.COMPLETED)
        return TrackDownloadResult.Success(finalFile.absolutePath)
    }

    /**
     * Resolves a YouTube URL for the given track by searching and scoring results.
     *
     * @return The best-matching YouTube URL, or null if no acceptable match is found.
     */
    private suspend fun resolveUrl(track: Track): String? {
        // If we already have a YouTube ID, use it directly
        track.youtubeId?.let { return "https://www.youtube.com/watch?v=$it" }

        // Search YouTube with two strategies for best results:
        // 1. "Artist - Title" (targets Topic channels which have "Artist - Topic" as uploader)
        // 2. Falls back to "Artist Title official audio" if first returns no good match
        val query = "${track.artist} ${track.title}"
        val results = searchExecutor.search(query, maxResults = 10)
        if (results.isEmpty()) {
            Log.w(TAG, "resolveUrl: search returned 0 results for '${track.artist} - ${track.title}'")
            return null
        }

        // Score all results against the target track
        val scored = matchScorer.scoreResults(
            targetTitle = track.title,
            targetArtist = track.artist,
            targetDurationMs = track.durationMs,
            results = results,
        )

        return matchScorer.bestMatch(scored)?.youtubeUrl
    }

    /**
     * Emits a progress update for the given track.
     */
    private fun emitProgress(trackId: Long, progress: Float, status: DownloadStatus) {
        _progress.tryEmit(DownloadProgress(trackId, progress, 0, status))
    }
}
