package com.stash.data.download.matching

import android.util.Log
import com.stash.core.data.sync.TrackMatcher
import com.stash.data.download.model.MatchResult
import com.stash.data.download.ytdlp.YtDlpSearchResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log10

/**
 * Scores YouTube search results against a target track using a weighted
 * composite of title similarity, artist similarity, duration closeness,
 * and popularity.
 *
 * Delegates canonical normalisation and Jaro-Winkler computation to
 * [TrackMatcher] (from :core:data) to avoid logic duplication.
 *
 * Scoring formula:
 *   score = titleSim * 0.35 + artistSim * 0.25 + durationSim * 0.25
 *           + popularitySim * 0.15 + topicBonus - penalty
 *
 * Results are returned sorted by descending score.
 */
@Singleton
class MatchScorer @Inject constructor(
    private val trackMatcher: TrackMatcher,
) {
    companion object {
        /** Weight given to title similarity in the composite score. */
        const val TITLE_WEIGHT = 0.30f

        /** Weight given to artist similarity in the composite score. */
        const val ARTIST_WEIGHT = 0.30f

        /** Weight given to duration closeness in the composite score. */
        const val DURATION_WEIGHT = 0.20f

        /** Weight given to relative popularity (view count) in the composite score. */
        const val POPULARITY_WEIGHT = 0.10f

        /** Minimum score to auto-accept a match. Set at 0.60 to avoid accepting
         *  wrong versions. The multi-strategy search (4 query variations) handles
         *  hard-to-find tracks by producing cleaner queries, not by lowering standards. */
        const val AUTO_ACCEPT_THRESHOLD = 0.60f

        /** Scores below this value are discarded as unlikely matches. */
        const val REJECT_THRESHOLD = 0.50f
    }

    /**
     * Score every [YtDlpSearchResult] against the target track metadata.
     *
     * @param targetTitle      Track title from the library/playlist.
     * @param targetArtist     Track artist from the library/playlist.
     * @param targetDurationMs Track duration in milliseconds.
     * @param results          Raw search results from YouTube.
     * @return Scored [MatchResult] list, sorted best-first.
     */
    fun scoreResults(
        targetTitle: String,
        targetArtist: String,
        targetDurationMs: Long,
        results: List<YtDlpSearchResult>,
        targetAlbum: String = "",
    ): List<MatchResult> {
        if (results.isEmpty()) return emptyList()

        val maxViewCount = results.maxOf { it.viewCount }

        return results.map { result ->
            val titleScore = computeTitleScore(targetTitle, result.title)
            val artistScore = computeArtistScore(targetArtist, result.uploader)
            val durationScore = computeDurationScore(targetDurationMs, result.duration.toLong())
            val popularityScore = computePopularityScore(result.viewCount, maxViewCount)
            val penalty = computePenalty(targetTitle, result.title)
            val topicBonus = computeTopicBonus(result.uploader, result.channel)
            val uploaderPenalty = computeUploaderMismatchPenalty(targetArtist, result.uploader, result.channel)
            val albumBonus = computeAlbumBonus(targetAlbum, result.album, result.title)

            val finalScore = (
                titleScore * TITLE_WEIGHT +
                    artistScore * ARTIST_WEIGHT +
                    durationScore * DURATION_WEIGHT +
                    popularityScore * POPULARITY_WEIGHT +
                    topicBonus + albumBonus - penalty - uploaderPenalty
                ).coerceIn(0f, 1f)

            Log.d("MatchScorer", "  ${result.title} by ${result.uploader} (album=${result.album ?: "?"}) | " +
                "title=%.2f art=%.2f dur=%.2f pop=%.2f topic=%.2f alb=%.2f pen=%.2f uplPen=%.2f → %.2f".format(
                    titleScore, artistScore, durationScore, popularityScore,
                    topicBonus, albumBonus, penalty, uploaderPenalty, finalScore))

            MatchResult(
                youtubeUrl = result.webpageUrl.ifEmpty {
                    "https://www.youtube.com/watch?v=${result.id}"
                },
                videoId = result.id,
                title = result.title,
                uploader = result.uploader,
                durationSeconds = result.duration.toLong(),
                viewCount = result.viewCount,
                matchScore = finalScore,
            )
        }.sortedByDescending { it.matchScore }
    }

    /**
     * Return the best match from a scored list if it exceeds [AUTO_ACCEPT_THRESHOLD].
     *
     * @param results Scored and sorted match results.
     * @return The top result if its score is high enough, null otherwise.
     */
    fun bestMatch(results: List<MatchResult>): MatchResult? {
        val best = results.firstOrNull()
        if (best != null && best.matchScore < AUTO_ACCEPT_THRESHOLD) {
            Log.w("MatchScorer", "Best match rejected: ${best.title} score=%.2f (threshold=%.2f)".format(
                best.matchScore, AUTO_ACCEPT_THRESHOLD))
        }
        return results.firstOrNull { it.matchScore >= AUTO_ACCEPT_THRESHOLD }
    }

    /**
     * Computes the raw artist similarity for a result (used by the caller
     * to enforce a minimum artist match independently of the weighted score).
     */
    fun artistSimilarity(targetArtist: String, resultUploader: String): Float {
        return computeArtistScore(targetArtist, resultUploader)
    }

    fun titleSimilarity(targetTitle: String, resultTitle: String): Float {
        return computeTitleScore(targetTitle, resultTitle)
    }

    // -- Private scoring helpers --------------------------------------------------

    /**
     * Jaro-Winkler similarity between canonical titles.
     */
    private fun computeTitleScore(target: String, candidate: String): Float {
        return trackMatcher.jaroWinklerSimilarity(
            trackMatcher.canonicalTitle(target),
            trackMatcher.canonicalTitle(candidate),
        ).toFloat()
    }

    /**
     * Jaro-Winkler similarity between canonical artist names.
     * Strips YouTube's " - Topic" suffix from auto-generated channels.
     */
    private fun computeArtistScore(target: String, candidateUploader: String): Float {
        val cleanUploader = candidateUploader.replace(" - Topic", "")
        return trackMatcher.jaroWinklerSimilarity(
            trackMatcher.canonicalArtist(target),
            trackMatcher.canonicalArtist(cleanUploader),
        ).toFloat()
    }

    /**
     * Duration closeness score using tiered thresholds.
     *
     * - Within 3 seconds: perfect (1.0)
     * - Within 10 seconds: good (0.8)
     * - Within 30 seconds: fair (0.4)
     * - Beyond 30 seconds: no match (0.0)
     */
    private fun computeDurationScore(targetDurationMs: Long, candidateDurationSec: Long): Float {
        // When duration is unknown (0), return neutral score instead of penalizing.
        // InnerTube music search often doesn't include duration data.
        if (candidateDurationSec <= 0 || targetDurationMs <= 0) return 0.5f

        val durationDiffSec = abs((targetDurationMs / 1000) - candidateDurationSec)
        return when {
            durationDiffSec <= 3 -> 1.0f
            durationDiffSec <= 10 -> 0.8f
            durationDiffSec <= 30 -> 0.4f
            else -> 0.0f
        }
    }

    /**
     * Log-scaled popularity relative to the most-viewed result in the set.
     */
    private fun computePopularityScore(viewCount: Long, maxViewCount: Long): Float {
        // When all results have viewCount=0 (common with InnerTube/YouTube Music),
        // return a neutral score instead of 0. This prevents InnerTube results from
        // being penalized for lacking view data.
        if (maxViewCount <= 0) return 0.5f
        if (viewCount <= 0) return 0.1f
        return (log10(viewCount.toDouble()) / log10(maxViewCount.toDouble())).toFloat()
    }

    /**
     * Penalty for content variants (covers, remixes, live, karaoke, instrumental)
     * when the target title does not contain the same keyword.
     */
    private fun computePenalty(targetTitle: String, candidateTitle: String): Float {
        val targetLower = targetTitle.lowercase()
        val candidateLower = candidateTitle.lowercase()

        // Live/concert indicators (many live versions don't say "live" explicitly)
        val liveIndicators = listOf("live", "concert", "woodstock", "festival", "session",
            "unplugged", "acoustic version", "radio session", "bbc session", "peel session",
            "music video", "official video", "mtv", "letterman", "snl", "tonight show")
        val isLikelyLive = liveIndicators.any { it in candidateLower } &&
            liveIndicators.none { it in targetLower }

        return when {
            "karaoke" in candidateLower -> 0.4f
            "cover" in candidateLower && "cover" !in targetLower -> 0.25f
            "remix" in candidateLower && "remix" !in targetLower -> 0.25f
            "instrumental" in candidateLower && "instrumental" !in targetLower -> 0.2f
            isLikelyLive -> 0.15f
            else -> 0f
        }
    }

    /**
     * Bonus when the YouTube result's album matches the Spotify track's album.
     * This is the strongest signal for correct matching — if album matches,
     * it's almost certainly the right recording.
     */
    private fun computeAlbumBonus(targetAlbum: String, resultAlbum: String?, resultTitle: String): Float {
        if (targetAlbum.isBlank()) return 0f
        val target = trackMatcher.canonicalTitle(targetAlbum)
        if (target.isBlank()) return 0f

        // Check result's album field (from InnerTube)
        if (resultAlbum != null) {
            val candidate = trackMatcher.canonicalTitle(resultAlbum)
            val similarity = trackMatcher.jaroWinklerSimilarity(target, candidate).toFloat()
            if (similarity >= 0.8f) return 0.15f  // Strong album match
            if (similarity >= 0.6f) return 0.08f  // Partial album match
        }

        // Check if album name appears in the video title
        val titleLower = resultTitle.lowercase()
        val albumLower = targetAlbum.lowercase()
        if (albumLower.length > 3 && albumLower in titleLower) return 0.05f

        return 0f
    }

    /**
     * Strong bonus for YouTube's auto-generated "Artist - Topic" channels.
     * These carry the official audio from the label and are the most reliable
     * source for correct tracks. A Topic channel match is the single strongest
     * signal that this is the right version of the song.
     */
    private fun computeTopicBonus(uploader: String, channel: String): Float {
        return if (uploader.endsWith(" - Topic") || channel.endsWith(" - Topic")) 0.25f else 0f
    }

    /**
     * Penalty when the uploader clearly doesn't match the target artist.
     * Catches covers and re-uploads by unrelated channels. A low artist score
     * (<0.4 Jaro-Winkler) combined with NOT being a Topic channel means this
     * is likely someone else's upload of the song (cover, tribute, etc).
     */
    private fun computeUploaderMismatchPenalty(
        targetArtist: String,
        uploader: String,
        channel: String,
    ): Float {
        val isTopic = uploader.endsWith(" - Topic") || channel.endsWith(" - Topic")
        if (isTopic) return 0f // Topic channels are always trusted

        val cleanUploader = uploader.replace(" - Topic", "")
        val similarity = trackMatcher.jaroWinklerSimilarity(
            trackMatcher.canonicalArtist(targetArtist),
            trackMatcher.canonicalArtist(cleanUploader),
        ).toFloat()

        // If the uploader name is very different from the artist, penalize
        return when {
            similarity >= 0.7f -> 0f        // close enough match
            similarity >= 0.4f -> 0.05f     // somewhat different
            else -> 0.15f                    // clearly different uploader
        }
    }
}
