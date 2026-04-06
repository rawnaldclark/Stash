package com.stash.data.download

import android.util.Log
import com.stash.core.model.Track
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.matching.DuplicateDetectionService
import com.stash.data.download.matching.MatchScorer
import com.stash.data.download.matching.HybridSearchExecutor
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
 * 3. Download native Opus audio via yt-dlp (metadata embedded via --embed-metadata).
 * 4. Move the file to the organized artist/album directory.
 *
 * Concurrency is limited to 8 simultaneous downloads via a [Semaphore].
 * Real-time progress is emitted through the [progress] shared flow.
 */
@Singleton
class DownloadManager @Inject constructor(
    private val downloadExecutor: DownloadExecutor,
    private val searchExecutor: HybridSearchExecutor,
    private val matchScorer: MatchScorer,
    private val duplicateDetection: DuplicateDetectionService,
    private val fileOrganizer: FileOrganizer,
    private val metadataEmbedder: MetadataEmbedder,
    private val qualityPrefs: QualityPreferencesManager,
) {
    /** Limits concurrent downloads. 8 parallel slots — with native opus (no FFmpeg
     *  transcode) downloads are almost entirely network-bound so more parallelism helps. */
    private val concurrencySemaphore = Semaphore(8)

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

        // Metadata is now embedded by yt-dlp via --embed-metadata flag.
        // No separate ffmpeg step needed.

        emitProgress(track.id, 0.9f, DownloadStatus.PROCESSING)

        // Step 4: Move to organized directory
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
     * Resolves a YouTube URL for the given track by trying multiple search
     * strategies in order of expected quality. Stops as soon as one produces
     * a match above the auto-accept threshold.
     *
     * Strategies (in order):
     * 1. Original full query: "Artist Title (feat. X) [Remaster]"
     * 2. Without parentheticals: "Artist Title"
     * 3. Without remaster/deluxe suffixes: "Artist Title"
     * 4. With dash separator: "Artist - Title"
     *
     * @return The best-matching YouTube URL, or null if no acceptable match is found.
     */
    private suspend fun resolveUrl(track: Track): String? {
        // If we already have a YouTube ID, use it directly
        track.youtubeId?.let { return "https://www.youtube.com/watch?v=$it" }

        // Try multiple search strategies in order of expected quality
        val strategies = buildSearchQueries(track)

        for (query in strategies) {
            if (query.isBlank()) continue
            val results = searchExecutor.search(query, maxResults = 10)
            if (results.isEmpty()) continue

            val scored = matchScorer.scoreResults(
                targetTitle = track.title,
                targetArtist = track.artist,
                targetDurationMs = track.durationMs,
                results = results,
                targetAlbum = track.album,
            )

            val best = matchScorer.bestMatch(scored)
            if (best != null) {
                // Three-level verification — all must pass:
                // 1. Title similarity >= 0.6 (prevents wrong song by same artist)
                // 2. Artist Jaro-Winkler >= 0.65 (prevents wrong artist)
                // 3. Artist words overlap (prevents near-name mismatches)
                val titleSim = matchScorer.titleSimilarity(track.title, best.title)
                if (titleSim < 0.6f) {
                    Log.w(TAG, "resolveUrl: rejecting '${best.title}' — title sim ${String.format("%.2f", titleSim)} too low for '${track.title}'")
                    continue
                }
                val artistSim = matchScorer.artistSimilarity(track.artist, best.uploader)
                if (artistSim < 0.65f) {
                    Log.w(TAG, "resolveUrl: rejecting '${best.title}' by '${best.uploader}' — fuzzy artist sim ${String.format("%.2f", artistSim)} too low for '${track.artist}'")
                    continue
                }
                if (!artistWordsMatch(track.artist, best.uploader)) {
                    Log.w(TAG, "resolveUrl: rejecting '${best.title}' by '${best.uploader}' — artist words don't match '${track.artist}'")
                    continue
                }
                Log.d(TAG, "resolveUrl: matched '${track.artist} - ${track.title}' with query '$query' → ${best.youtubeUrl} (artist=%.2f)".format(artistSim))
                return best.youtubeUrl
            }
        }

        Log.w(TAG, "resolveUrl: all strategies failed for '${track.artist} - ${track.title}'")
        return null
    }

    /**
     * Builds a list of progressively simplified search queries for a track.
     * Each query strips more noise (feat. credits, remaster tags, etc.) to
     * increase the chance of finding a match on YouTube.
     *
     * @param track The track to build queries for.
     * @return Deduplicated list of search queries, ordered from most specific to simplest.
     */
    private fun buildSearchQueries(track: Track): List<String> {
        val artist = track.artist
        val title = track.title
        // Strip parenthetical content: (feat. X), [Remaster], (Deluxe), etc.
        val cleanTitle = title
            .replace(Regex("""\s*[\(\[][^)\]]*[\)\]]"""), "")
            .replace(Regex("""\s*(feat\.?|ft\.?|featuring)\s+.*""", RegexOption.IGNORE_CASE), "")
            .trim()
        // Strip "- Remaster", "- Single Version", etc.
        val simpleTitle = cleanTitle
            .replace(Regex("""\s*-\s*(Remaster|Remastered|Single Version|Deluxe|Bonus Track).*""", RegexOption.IGNORE_CASE), "")
            .trim()

        val album = track.album.takeIf { it.isNotBlank() }

        return listOfNotNull(
            "$artist $simpleTitle $album",  // Most specific: artist + clean title + album
            "$artist $title",               // Original full query
            "$artist $cleanTitle",          // Without parentheticals
            "$artist $simpleTitle",         // Without remaster/deluxe suffixes
            "$artist - $simpleTitle",       // With dash separator
        ).map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    /**
     * Checks that the significant words in the artist names actually match,
     * not just that the characters are similar.
     *
     * Prevents "Jimi Hendrix" matching "Jim Hendricks" — Jaro-Winkler gives
     * 0.77 similarity for these, but "Hendrix" ≠ "Hendricks" word-wise.
     *
     * At least one significant word (>3 chars) from the target must appear
     * exactly (case-insensitive) in the candidate, or vice versa.
     */
    private fun artistWordsMatch(targetArtist: String, candidateArtist: String): Boolean {
        val targetWords = targetArtist.lowercase().split(Regex("[\\s,&+]+"))
            .filter { it.length > 3 }
            .map { it.trim() }
            .toSet()
        val candidateWords = candidateArtist.lowercase().split(Regex("[\\s,&+]+"))
            .filter { it.length > 3 }
            .map { it.trim() }
            .toSet()

        if (targetWords.isEmpty() || candidateWords.isEmpty()) return true // Can't verify short names

        // At least one significant word must match exactly
        return targetWords.any { it in candidateWords } || candidateWords.any { it in targetWords }
    }

    /**
     * Emits a progress update for the given track.
     */
    private fun emitProgress(trackId: Long, progress: Float, status: DownloadStatus) {
        _progress.tryEmit(DownloadProgress(trackId, progress, 0, status))
    }
}
