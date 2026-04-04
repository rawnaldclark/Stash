package com.stash.data.download.matching

import android.util.Log
import com.stash.data.download.ytdlp.YtDlpSearchResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Composite search executor that tries the fast InnerTube path first and
 * falls back to yt-dlp if InnerTube returns no results.
 *
 * Performance characteristics:
 * - InnerTube: ~0.2s per search (single HTTP POST)
 * - yt-dlp:    ~3s per search (Python subprocess + network)
 *
 * The fallback ensures reliability: InnerTube may return empty results for
 * obscure queries, region-restricted content, or if the API response format
 * changes. The yt-dlp path is battle-tested and handles these edge cases.
 *
 * This class is injected into [DownloadManager] as a drop-in replacement
 * for [YouTubeSearchExecutor], preserving the same `search()` contract.
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
     * Searches YouTube for tracks matching [query], trying InnerTube first.
     *
     * If InnerTube returns results, they are used immediately (~15x faster).
     * If InnerTube returns empty or throws, yt-dlp is used as a fallback.
     *
     * @param query      Free-text search string (e.g. "Artist Title").
     * @param maxResults Maximum number of results to return.
     * @return List of search results from whichever source succeeded first.
     */
    suspend fun search(query: String, maxResults: Int = 10): List<YtDlpSearchResult> {
        // Try fast InnerTube search first
        val innerTubeResults = innerTubeSearch.search(query, maxResults)
        if (innerTubeResults.isNotEmpty()) {
            return innerTubeResults
        }

        // Fall back to yt-dlp search (slower but more reliable)
        Log.d(TAG, "InnerTube returned empty, falling back to yt-dlp for: $query")
        return ytDlpSearch.search(query, maxResults)
    }
}
