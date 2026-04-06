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
        // Get results from BOTH sources and merge them.
        // InnerTube (YouTube Music) has album data for better scoring.
        // yt-dlp (regular YouTube) has more reliable video IDs.
        // Merging gives the scorer the best candidates from both worlds.
        val innerTubeResults = innerTubeSearch.search(query, maxResults)
        val ytDlpResults = try {
            ytDlpSearch.search(query, maxResults = 5)
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp search failed, using InnerTube only", e)
            emptyList()
        }

        // Merge: deduplicate by video ID, yt-dlp results take priority
        // (their video IDs are more reliable for actual audio content)
        val seen = mutableSetOf<String>()
        val merged = (ytDlpResults + innerTubeResults).filter { it.id.isNotBlank() && seen.add(it.id) }

        if (merged.isEmpty() && innerTubeResults.isNotEmpty()) {
            return innerTubeResults  // InnerTube-only if yt-dlp returned nothing
        }

        Log.d(TAG, "Merged search: ${ytDlpResults.size} yt-dlp + ${innerTubeResults.size} innertube = ${merged.size} unique for '$query'")
        return merged.take(maxResults)
    }
}
