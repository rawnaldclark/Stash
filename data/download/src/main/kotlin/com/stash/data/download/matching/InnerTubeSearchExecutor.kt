package com.stash.data.download.matching

import android.util.Log
import com.stash.data.download.ytdlp.YtDlpSearchResult
import com.stash.data.ytmusic.InnerTubeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Searches YouTube Music via the InnerTube API and maps results to
 * [YtDlpSearchResult] so they can be scored by [MatchScorer].
 *
 * InnerTube search is significantly faster than yt-dlp subprocess search
 * (~0.2s vs ~3s) because it's a single HTTP POST rather than spawning a
 * Python process. The trade-off is that InnerTube responses are deeply
 * nested and may change without notice, so this executor is designed to
 * fail gracefully (returning an empty list) so the caller can fall back
 * to yt-dlp.
 *
 * The response structure for YouTube Music search is:
 * ```
 * contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer.content
 *   .sectionListRenderer.contents[].musicShelfRenderer.contents[]
 *     .musicResponsiveListItemRenderer
 * ```
 */
@Singleton
class InnerTubeSearchExecutor @Inject constructor(
    private val innerTubeClient: InnerTubeClient,
) {
    companion object {
        private const val TAG = "InnerTubeSearch"
    }

    /**
     * Searches YouTube Music for tracks matching [query].
     *
     * @param query      Free-text search string (e.g. "Artist Title").
     * @param maxResults Maximum number of results to return.
     * @return List of parsed search results, empty on error or no results.
     */
    suspend fun search(query: String, maxResults: Int = 10): List<YtDlpSearchResult> {
        if (query.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val response = innerTubeClient.search(query) ?: return@withContext emptyList()
                val results = parseSearchResults(response, maxResults)
                Log.d(TAG, "InnerTube search '$query': ${results.size} results")
                results
            } catch (e: Exception) {
                Log.w(TAG, "InnerTube search failed for '$query'", e)
                emptyList()
            }
        }
    }

    /**
     * Parses the top-level InnerTube search response into a flat list of results.
     *
     * Navigates through `tabbedSearchResultsRenderer` -> tabs -> sections ->
     * `musicShelfRenderer` -> contents, extracting each
     * `musicResponsiveListItemRenderer` item.
     */
    private fun parseSearchResults(response: JsonObject, maxResults: Int): List<YtDlpSearchResult> {
        val results = mutableListOf<YtDlpSearchResult>()

        // Navigate: contents -> tabbedSearchResultsRenderer -> tabs
        val tabs = response.navigatePath("contents", "tabbedSearchResultsRenderer", "tabs")
            ?.jsonArray

        if (tabs == null || tabs.isEmpty()) {
            Log.d(TAG, "No tabs in search response, keys: ${response.keys}")
            return emptyList()
        }

        // tabs[0] -> tabRenderer -> content -> sectionListRenderer -> contents
        val sections = tabs[0].jsonObject
            .navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.jsonArray ?: return emptyList()

        for (section in sections) {
            val shelf = section.jsonObject["musicShelfRenderer"]?.jsonObject ?: continue
            val contents = shelf["contents"]?.jsonArray ?: continue

            for (item in contents) {
                if (results.size >= maxResults) break

                val renderer = item.jsonObject["musicResponsiveListItemRenderer"]?.jsonObject
                    ?: continue

                val result = parseRenderer(renderer) ?: continue
                results.add(result)
            }

            if (results.size >= maxResults) break
        }

        return results
    }

    /**
     * Parses a single `musicResponsiveListItemRenderer` into a [YtDlpSearchResult].
     *
     * Extracts:
     * - **videoId** from `playlistItemData` or the overlay play button endpoint
     * - **title** from flexColumns[0]
     * - **artist** from flexColumns[1] (all text runs joined, separators filtered)
     * - **duration** from fixedColumns[0] (converted from "M:SS" to seconds)
     *
     * @return A populated [YtDlpSearchResult], or null if essential fields are missing.
     */
    private fun parseRenderer(renderer: JsonObject): YtDlpSearchResult? {
        // Extract video ID from playlistItemData or overlay play button
        val videoId = renderer["playlistItemData"]?.jsonObject
            ?.get("videoId")?.jsonPrimitive?.contentOrNull
            ?: renderer.navigatePath(
                "overlay", "musicItemThumbnailOverlayRenderer", "content",
                "musicPlayButtonRenderer", "playNavigationEndpoint",
                "watchEndpoint", "videoId"
            )?.jsonPrimitive?.contentOrNull
            ?: return null

        // Extract title from flexColumns[0]
        val flexColumns = renderer["flexColumns"]?.jsonArray ?: return null

        val title = flexColumns.getOrNull(0)?.jsonObject
            ?.navigatePath(
                "musicResponsiveListItemFlexColumnRenderer", "text", "runs"
            )?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?: return null

        // Extract artist from flexColumns[1] — join all text runs, filter separators
        val artistRuns = flexColumns.getOrNull(1)?.jsonObject
            ?.navigatePath(
                "musicResponsiveListItemFlexColumnRenderer", "text", "runs"
            )?.jsonArray

        val artist = artistRuns
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.filterNot { it == " & " || it == ", " || it == " • " || it == " x " || it == " · " }
            ?.joinToString(", ")
            ?: ""

        // Extract album from flexColumns[2] (if present)
        val albumName = flexColumns.getOrNull(2)?.jsonObject
            ?.navigatePath(
                "musicResponsiveListItemFlexColumnRenderer", "text", "runs"
            )?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

        // Extract duration from fixedColumns[0] — format "M:SS" or "H:MM:SS"
        // Some results have duration in flexColumns[1] runs (after artist, album, duration)
        val durationText = renderer["fixedColumns"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.navigatePath(
                "musicResponsiveListItemFixedColumnRenderer", "text", "runs"
            )?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull

        // Fallback: duration might be in flexColumns[1] as the last run (after artist info)
        val durationFallback = if (durationText == null) {
            artistRuns
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.lastOrNull { it.matches(Regex("\\d+:\\d+")) }
        } else null

        val finalDurationText = durationText ?: durationFallback
        val durationSeconds = parseDurationToSeconds(finalDurationText)

        if (durationSeconds == 0.0) {
            Log.d(TAG, "No duration for '$title' — fixedColumns=${renderer["fixedColumns"]?.toString()?.take(200)}")
        }

        // Extract thumbnail URL — pick the largest available
        val thumbnailUrl = renderer.navigatePath("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
            ?.jsonArray
            ?.maxByOrNull { it.jsonObject["width"]?.jsonPrimitive?.intOrNull ?: 0 }
            ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

        return YtDlpSearchResult(
            id = videoId,
            title = title,
            uploader = artist,
            uploaderId = "",
            channel = artist,
            duration = durationSeconds,
            viewCount = 0,
            webpageUrl = "https://www.youtube.com/watch?v=$videoId",
            url = "",
            likeCount = null,
            thumbnail = thumbnailUrl,
            album = albumName,
            description = "",
        )
    }

    /**
     * Converts a duration string like "3:45" or "1:02:30" to seconds.
     *
     * @param duration The duration string in "M:SS" or "H:MM:SS" format.
     * @return Duration in seconds, or 0.0 if the format is unrecognized.
     */
    private fun parseDurationToSeconds(duration: String?): Double {
        if (duration == null) return 0.0
        val parts = duration.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60.0 + parts[1])
            3 -> (parts[0] * 3600.0 + parts[1] * 60.0 + parts[2])
            else -> 0.0
        }
    }

    /**
     * Navigates nested JSON objects by key path, returning null if any key is missing.
     *
     * This is a local copy of the same utility in [YTMusicApiClient] since that
     * version is file-private.
     */
    private fun JsonObject.navigatePath(vararg keys: String): JsonElement? {
        var current: JsonElement = this
        for (key in keys) {
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        return current
    }
}
