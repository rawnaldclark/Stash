package com.stash.data.download.matching

import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.data.download.ytdlp.YtDlpSearchResult
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes YouTube searches via yt-dlp and parses the JSON output
 * into [YtDlpSearchResult] DTOs.
 *
 * Uses the `ytsearchN:` prefix to request up to N results from YouTube,
 * combined with `--dump-json --no-download --flat-playlist` to retrieve
 * metadata without downloading any media.
 *
 * Thread-safe: all IO is dispatched to [Dispatchers.IO].
 */
@Singleton
class YouTubeSearchExecutor @Inject constructor(
    private val ytDlpManager: YtDlpManager,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "YouTubeSearchExecutor"
    }

    /**
     * Search YouTube for tracks matching [query].
     *
     * @param query      Free-text search string (e.g. "Artist - Title official audio").
     * @param maxResults Maximum number of results to return (1..20).
     * @return List of parsed search results, possibly empty on error.
     */
    suspend fun search(query: String, maxResults: Int = 5): List<YtDlpSearchResult> {
        require(query.isNotBlank()) { "Search query must not be blank" }
        val clampedMax = maxResults.coerceIn(1, 20)

        ytDlpManager.initialize()

        return withContext(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest("ytsearch$clampedMax:$query")
                request.addOption("--dump-json")
                request.addOption("--no-download")
                request.addOption("--flat-playlist")

                val response = YoutubeDL.getInstance().execute(request)
                val output = response.out

                if (output.isNullOrBlank()) {
                    Log.w(TAG, "yt-dlp returned empty output for query: $query")
                    return@withContext emptyList()
                }

                // yt-dlp emits one JSON object per line when using --dump-json
                output.trim().lines()
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        try {
                            json.decodeFromString<YtDlpSearchResult>(line)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse yt-dlp JSON line", e)
                            null
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "YouTube search failed for query: $query", e)
                emptyList()
            }
        }
    }
}
