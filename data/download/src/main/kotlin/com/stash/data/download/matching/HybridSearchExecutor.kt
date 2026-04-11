package com.stash.data.download.matching

import android.util.Log
import com.stash.data.download.ytdlp.YtDlpSearchResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composite search executor: InnerTube first (fast), yt-dlp only when
 * InnerTube returns empty results.
 *
 * Performance characteristics:
 * - InnerTube: ~0.2s per search (single HTTP POST)
 * - yt-dlp:    ~3s per search (Python subprocess + network)
 *
 * InnerTube results are trusted when they contain at least one relevant
 * title match. Accuracy is enforced downstream by DownloadManager's
 * three-gate verification (title sim, artist sim, artist word match),
 * so the search layer doesn't need to double-check with yt-dlp.
 *
 * yt-dlp is only invoked when InnerTube returns zero results (obscure
 * tracks, region-restricted content, API changes).
 */
@Singleton
class HybridSearchExecutor @Inject constructor(
    private val innerTubeSearch: InnerTubeSearchExecutor,
    private val ytDlpSearch: YouTubeSearchExecutor,
) {
    companion object {
        private const val TAG = "HybridSearch"
    }

    /**
     * Verifies a video ID via the InnerTube player endpoint.
     * Returns title and playability status so [DownloadManager] can
     * reject unplayable or mismatched video IDs before downloading.
     */
    suspend fun verifyVideo(videoId: String): InnerTubeSearchExecutor.VideoVerification? {
        return innerTubeSearch.verifyVideo(videoId)
    }

    /**
     * Searches YouTube directly via yt-dlp, bypassing InnerTube.
     * Used as a final fallback when InnerTube video IDs fail verification.
     */
    suspend fun searchYtDlpDirect(query: String, maxResults: Int = 5): List<YtDlpSearchResult> {
        return try {
            ytDlpSearch.search(query, maxResults)
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp direct search failed", e)
            emptyList()
        }
    }

    /**
     * Searches YouTube for tracks matching [query].
     *
     * Uses InnerTube (~0.2s) and only falls back to yt-dlp (~3s) when
     * InnerTube returns no results at all.
     *
     * @param query      Free-text search string (e.g. "Artist Title").
     * @param maxResults Maximum number of results to return.
     * @return List of search results.
     */
    suspend fun search(query: String, maxResults: Int = 10): List<YtDlpSearchResult> {
        // Fast path: InnerTube (~0.2s)
        val innerTubeResults = innerTubeSearch.search(query, maxResults)

        if (innerTubeResults.isNotEmpty()) {
            Log.d(TAG, "InnerTube returned ${innerTubeResults.size} results for '$query'")
            return innerTubeResults
        }

        // Slow fallback: yt-dlp only when InnerTube returns nothing
        Log.d(TAG, "InnerTube empty, falling back to yt-dlp for: $query")
        return try {
            ytDlpSearch.search(query, maxResults = 5)
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp search failed", e)
            emptyList()
        }
    }
}
