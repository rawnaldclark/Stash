package com.stash.data.download.model

import com.stash.core.model.Track

/**
 * Describes everything needed to download a single track's audio.
 *
 * @property track         the library track this download is for.
 * @property youtubeUrl    a pre-resolved YouTube URL, or null if a search is needed.
 * @property searchQuery   query string used to find the track on YouTube (e.g. "Artist - Title official audio").
 * @property qualityArgs   yt-dlp CLI arguments controlling audio quality and format.
 * @property outputDir     absolute path to the directory where the file should be written.
 */
data class DownloadRequest(
    val track: Track,
    val youtubeUrl: String? = null,
    val searchQuery: String,
    val qualityArgs: List<String>,
    val outputDir: String,
)

/**
 * Real-time progress snapshot for an in-flight download.
 *
 * @property trackId    database ID of the track being downloaded.
 * @property progress   download progress as a fraction in [0.0, 1.0].
 * @property etaSeconds estimated seconds remaining, or 0 if unknown.
 * @property status     current phase of the download pipeline.
 */
data class DownloadProgress(
    val trackId: Long,
    val progress: Float,
    val etaSeconds: Long = 0,
    val status: DownloadStatus,
)

/**
 * Phases of the download pipeline, from initial queue through completion.
 */
enum class DownloadStatus {
    /** Waiting in the download queue. */
    QUEUED,
    /** Searching YouTube for a matching video. */
    MATCHING,
    /** Downloading audio from YouTube. */
    DOWNLOADING,
    /** Post-download processing (format conversion, normalization). */
    PROCESSING,
    /** Writing ID3/Vorbis metadata tags. */
    TAGGING,
    /** Download finished successfully. */
    COMPLETED,
    /** Download failed after exhausting retries. */
    FAILED,
    /** No suitable YouTube match could be found. */
    UNMATCHED,
}

/**
 * Result of a YouTube search-and-match operation for a single track.
 *
 * @property youtubeUrl      full URL to the matched video.
 * @property videoId         YouTube video ID.
 * @property title           video title as reported by YouTube.
 * @property uploader        channel/uploader name.
 * @property durationSeconds video duration in seconds.
 * @property viewCount       total view count at time of search.
 * @property matchScore      composite match score. Non-negative; typically in
 *                            [0.0, 1.0] but may exceed 1.0 when multiple
 *                            positive signals (ATV + Topic + album match)
 *                            stack. Compare via
 *                            [com.stash.data.download.matching.MatchScorer.AUTO_ACCEPT_THRESHOLD].
 */
data class MatchResult(
    val youtubeUrl: String,
    val videoId: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val viewCount: Long,
    val matchScore: Float,
    /**
     * Album as reported by YouTube Music's search shelf. Populated from
     * [com.stash.data.download.ytdlp.YtDlpSearchResult.album]; null for
     * yt-dlp-sourced fallback results that don't carry album metadata.
     * Used by [com.stash.data.download.matching.YtLibraryCanonicalizer]
     * to refresh a canonicalized track's `album` column.
     */
    val album: String? = null,
    /**
     * Thumbnail URL from YouTube Music's search shelf. Used by the
     * canonicalizer to refresh a track's `album_art_url` so the cover
     * switches from the music-video still to the studio master's art.
     * Null when the source didn't carry a thumbnail.
     */
    val thumbnailUrl: String? = null,
)
