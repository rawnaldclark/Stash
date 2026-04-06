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
        // Fast path: try InnerTube first (~0.2s)
        val innerTubeResults = innerTubeSearch.search(query, maxResults)

        if (innerTubeResults.isNotEmpty()) {
            // Check if InnerTube results look trustworthy:
            // - Has at least one result with a non-empty album (YouTube Music indexed it properly)
            // - Title words appear in the results (not completely unrelated)
            val hasAlbumData = innerTubeResults.any { !it.album.isNullOrBlank() }
            val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 2 }
            val titleRelevant = innerTubeResults.any { result ->
                val resultLower = result.title.lowercase()
                queryWords.count { it in resultLower } >= (queryWords.size / 2).coerceAtLeast(1)
            }

            if (hasAlbumData && titleRelevant) {
                // InnerTube results look good — use them (fast path)
                return innerTubeResults
            }

            // InnerTube results look sketchy — merge with yt-dlp for safety
            Log.d(TAG, "InnerTube results look unreliable (album=$hasAlbumData, relevant=$titleRelevant), merging with yt-dlp for: $query")
        }

        // Slow path: get yt-dlp results and merge
        val ytDlpResults = try {
            ytDlpSearch.search(query, maxResults = 5)
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp search failed", e)
            emptyList()
        }

        if (innerTubeResults.isEmpty()) return ytDlpResults

        // Merge: deduplicate by video ID, yt-dlp first (more reliable IDs)
        val seen = mutableSetOf<String>()
        val merged = (ytDlpResults + innerTubeResults).filter { it.id.isNotBlank() && seen.add(it.id) }
        Log.d(TAG, "Merged: ${ytDlpResults.size} yt-dlp + ${innerTubeResults.size} innertube = ${merged.size} unique")
        return merged.take(maxResults)
    }
}
