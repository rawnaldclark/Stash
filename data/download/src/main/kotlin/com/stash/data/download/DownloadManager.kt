package com.stash.data.download

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
    /** Limits concurrent downloads to avoid overwhelming bandwidth and CPU. */
    private val concurrencySemaphore = Semaphore(3)

    private val _progress = MutableSharedFlow<DownloadProgress>(replay = 1)

    /** Emits real-time progress snapshots for all in-flight downloads. */
    val progress: SharedFlow<DownloadProgress> = _progress.asSharedFlow()

    /**
     * Downloads a single track through the full pipeline.
     *
     * Acquires a concurrency permit before starting, ensuring no more than
     * 3 downloads run simultaneously.
     *
     * @param track          The track to download.
     * @param preResolvedUrl Optional YouTube URL if already known (skips search).
     * @return The absolute path of the final downloaded file, or null on failure.
     */
    suspend fun downloadTrack(
        track: Track,
        preResolvedUrl: String? = null,
    ): String? {
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
    private suspend fun executeDownload(track: Track, preResolvedUrl: String?): String? {
        emitProgress(track.id, 0f, DownloadStatus.MATCHING)

        // Step 1: Resolve YouTube URL
        val youtubeUrl = preResolvedUrl ?: resolveUrl(track) ?: run {
            emitProgress(track.id, 0f, DownloadStatus.UNMATCHED)
            return null
        }

        emitProgress(track.id, 0.1f, DownloadStatus.DOWNLOADING)

        // Step 2: Get quality args from user preferences
        val qualityTier = qualityPrefs.qualityTier.first()
        val qualityArgs = qualityTier.toYtDlpArgs()

        // Step 3: Download via yt-dlp
        val tempDir = fileOrganizer.getTempDir()
        val tempFilename = "dl_${track.id}"

        val downloadedFile = downloadExecutor.download(
            url = youtubeUrl,
            outputDir = tempDir,
            filename = tempFilename,
            qualityArgs = qualityArgs,
            onProgress = { progress ->
                emitProgress(track.id, 0.1f + progress * 0.7f, DownloadStatus.DOWNLOADING)
            },
        ) ?: run {
            emitProgress(track.id, 0f, DownloadStatus.FAILED)
            return null
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

        emitProgress(track.id, 1f, DownloadStatus.COMPLETED)
        return finalFile.absolutePath
    }

    /**
     * Resolves a YouTube URL for the given track by searching and scoring results.
     *
     * @return The best-matching YouTube URL, or null if no acceptable match is found.
     */
    private suspend fun resolveUrl(track: Track): String? {
        // If we already have a YouTube ID, use it directly
        track.youtubeId?.let { return "https://www.youtube.com/watch?v=$it" }

        // Search YouTube for the track
        val query = "${track.artist} - ${track.title} official audio"
        val results = searchExecutor.search(query, maxResults = 5)
        if (results.isEmpty()) return null

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
