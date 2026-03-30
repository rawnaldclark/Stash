package com.stash.data.ytmusic

import com.stash.core.model.SyncResult
import com.stash.data.ytmusic.model.YTMusicPlaylist
import com.stash.data.ytmusic.model.YTMusicTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level YouTube Music client that wraps [InnerTubeClient] and parses the
 * deeply-nested InnerTube renderer responses into simple DTOs.
 *
 * InnerTube responses consist of nested "renderer" objects (e.g.
 * `musicShelfRenderer`, `musicResponsiveListItemRenderer`) whose structure
 * varies between endpoints and can change without notice. This parser uses
 * best-effort extraction with lenient fallback defaults so that partial
 * responses still yield usable data.
 */
@Singleton
class YTMusicApiClient @Inject constructor(
    private val innerTubeClient: InnerTubeClient,
) {

    companion object {
        /** InnerTube browse ID for the user's liked music videos. */
        private const val BROWSE_LIKED_SONGS = "FEmusic_liked_videos"

        /** InnerTube browse ID for the YouTube Music home feed. */
        private const val BROWSE_HOME = "FEmusic_home"
    }

    /**
     * Fetches the authenticated user's liked songs from YouTube Music.
     *
     * Requires a valid YouTube access token; returns an empty list if
     * unauthenticated or if the response cannot be parsed.
     *
     * @return List of [YTMusicTrack] representing liked songs.
     */
    suspend fun getLikedSongs(): SyncResult<List<YTMusicTrack>> {
        val response = innerTubeClient.browse(BROWSE_LIKED_SONGS)
        if (response == null) {
            return SyncResult.Error("InnerTube browse($BROWSE_LIKED_SONGS) returned null")
        }
        val tracks = parseTracksFromBrowse(response)
        return if (tracks.isEmpty()) {
            SyncResult.Empty("Liked songs returned no tracks")
        } else {
            SyncResult.Success(tracks)
        }
    }

    /**
     * Fetches mix playlists from the YouTube Music home feed.
     *
     * The home feed contains personalized playlists (mixes, radio stations,
     * etc.) rendered as carousels. This method extracts playlist items from
     * `musicCarouselShelfRenderer` sections whose titles suggest they are mixes.
     *
     * @return List of [YTMusicPlaylist] representing discovered mixes.
     */
    suspend fun getHomeMixes(): SyncResult<List<YTMusicPlaylist>> {
        val response = innerTubeClient.browse(BROWSE_HOME)
        if (response == null) {
            return SyncResult.Error("InnerTube browse($BROWSE_HOME) returned null — check CLIENT_VERSION or cookie")
        }
        val mixes = parseMixesFromHome(response)
        return if (mixes.isEmpty()) {
            SyncResult.Empty("Home feed returned no mixes")
        } else {
            SyncResult.Success(mixes)
        }
    }

    /**
     * Fetches all tracks in a specific YouTube Music playlist.
     *
     * The browse ID for playlists is `VL` + the playlist ID (e.g. `VLPLxxxxxx`).
     *
     * @param playlistId The playlist ID (without the `VL` prefix).
     * @return List of [YTMusicTrack] in the playlist.
     */
    suspend fun getPlaylistTracks(playlistId: String): SyncResult<List<YTMusicTrack>> {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        val response = innerTubeClient.browse(browseId)
        if (response == null) {
            return SyncResult.Error("InnerTube browse($browseId) returned null")
        }
        val tracks = parseTracksFromBrowse(response)
        return if (tracks.isEmpty()) {
            SyncResult.Empty("Playlist $playlistId returned no tracks")
        } else {
            SyncResult.Success(tracks)
        }
    }

    // ── InnerTube response parsers ───────────────────────────────────────

    /**
     * Extracts tracks from a browse response.
     *
     * Looks for `musicShelfRenderer` objects in the tab contents and parses
     * each `musicResponsiveListItemRenderer` item into a [YTMusicTrack].
     */
    private fun parseTracksFromBrowse(response: JsonObject): List<YTMusicTrack> {
        val tracks = mutableListOf<YTMusicTrack>()

        // Navigate: contents -> singleColumnBrowseResultsRenderer -> tabs[0]
        //           -> tabRenderer -> content -> sectionListRenderer -> contents
        val sections = response.navigatePath(
            "contents",
            "singleColumnBrowseResultsRenderer",
            "tabs",
        )?.firstArray()?.firstOrNull()
            ?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.asArray()
            ?: return emptyList()

        for (section in sections) {
            val shelf = section.asObject()?.get("musicShelfRenderer")?.asObject() ?: continue
            val items = shelf["contents"]?.asArray() ?: continue

            for (item in items) {
                val renderer = item.asObject()
                    ?.get("musicResponsiveListItemRenderer")?.asObject()
                    ?: continue
                parseTrackFromRenderer(renderer)?.let { tracks.add(it) }
            }
        }

        return tracks
    }

    /**
     * Parses a single track from a `musicResponsiveListItemRenderer`.
     *
     * The videoId is extracted from `playlistItemData` or `overlay` -> `musicItemThumbnailOverlayRenderer`.
     * Title and artist info come from `flexColumns`.
     */
    private fun parseTrackFromRenderer(renderer: JsonObject): YTMusicTrack? {
        // Extract video ID from playlistItemData or overlay
        val videoId = renderer["playlistItemData"]?.asObject()
            ?.get("videoId")?.asString()
            ?: renderer.navigatePath(
                "overlay",
                "musicItemThumbnailOverlayRenderer",
                "content",
                "musicPlayButtonRenderer",
                "playNavigationEndpoint",
                "watchEndpoint",
                "videoId",
            )?.asString()
            ?: return null

        // Extract title from first flex column
        val flexColumns = renderer["flexColumns"]?.asArray() ?: return null
        val title = flexColumns.getOrNull(0)?.asObject()
            ?.navigatePath(
                "musicResponsiveListItemFlexColumnRenderer",
                "text",
                "runs",
            )?.firstArray()?.firstOrNull()
            ?.asObject()?.get("text")?.asString()
            ?: return null

        // Extract artist(s) from second flex column
        val artistRuns = flexColumns.getOrNull(1)?.asObject()
            ?.navigatePath(
                "musicResponsiveListItemFlexColumnRenderer",
                "text",
                "runs",
            )?.asArray()
        val artists = artistRuns
            ?.mapNotNull { it.asObject()?.get("text")?.asString() }
            ?.filterNot { it == " & " || it == ", " || it == " x " }
            ?.joinToString(", ")
            ?: ""

        // Extract album from third flex column (if present)
        val album = flexColumns.getOrNull(2)?.asObject()
            ?.navigatePath(
                "musicResponsiveListItemFlexColumnRenderer",
                "text",
                "runs",
            )?.firstArray()?.firstOrNull()
            ?.asObject()?.get("text")?.asString()

        // Extract thumbnail
        val thumbnailUrl = renderer.navigatePath("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
            ?.firstArray()?.firstOrNull()
            ?.asObject()?.get("url")?.asString()

        // Extract duration from fixedColumns if available
        val durationText = renderer["fixedColumns"]?.asArray()
            ?.firstOrNull()?.asObject()
            ?.navigatePath(
                "musicResponsiveListItemFixedColumnRenderer",
                "text",
                "runs",
            )?.firstArray()?.firstOrNull()
            ?.asObject()?.get("text")?.asString()
        val durationMs = parseDurationToMs(durationText)

        return YTMusicTrack(
            videoId = videoId,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
            thumbnailUrl = thumbnailUrl,
        )
    }

    /**
     * Extracts mix/playlist items from the YouTube Music home feed response.
     *
     * Looks for `musicCarouselShelfRenderer` sections and parses
     * `musicTwoRowItemRenderer` items that have a playlist navigation endpoint.
     */
    private fun parseMixesFromHome(response: JsonObject): List<YTMusicPlaylist> {
        val playlists = mutableListOf<YTMusicPlaylist>()

        val sections = response.navigatePath(
            "contents",
            "singleColumnBrowseResultsRenderer",
            "tabs",
        )?.firstArray()?.firstOrNull()
            ?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.asArray()
            ?: return emptyList()

        for (section in sections) {
            val carousel = section.asObject()
                ?.get("musicCarouselShelfRenderer")?.asObject()
                ?: continue
            val items = carousel["contents"]?.asArray() ?: continue

            for (item in items) {
                val twoRowRenderer = item.asObject()
                    ?.get("musicTwoRowItemRenderer")?.asObject()
                    ?: continue

                val playlistId = twoRowRenderer.navigatePath(
                    "navigationEndpoint",
                    "watchPlaylistEndpoint",
                    "playlistId",
                )?.asString()
                    ?: twoRowRenderer.navigatePath(
                        "navigationEndpoint",
                        "browseEndpoint",
                        "browseId",
                    )?.asString()
                    ?: continue

                val title = twoRowRenderer["title"]?.asObject()
                    ?.get("runs")?.asArray()
                    ?.firstOrNull()?.asObject()
                    ?.get("text")?.asString()
                    ?: continue

                val thumbnailUrl = twoRowRenderer.navigatePath(
                    "thumbnailRenderer",
                    "musicThumbnailRenderer",
                    "thumbnail",
                    "thumbnails",
                )?.firstArray()?.lastOrNull()
                    ?.asObject()?.get("url")?.asString()

                // Extract track count from subtitle if available
                val subtitleText = twoRowRenderer["subtitle"]?.asObject()
                    ?.get("runs")?.asArray()
                    ?.mapNotNull { it.asObject()?.get("text")?.asString() }
                    ?.joinToString("")
                val trackCount = subtitleText?.let { TRACK_COUNT_REGEX.find(it) }
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

                playlists.add(
                    YTMusicPlaylist(
                        playlistId = playlistId,
                        title = title,
                        thumbnailUrl = thumbnailUrl,
                        trackCount = trackCount,
                    )
                )
            }
        }

        return playlists
    }

    // ── Utility helpers ──────────────────────────────────────────────────

    /**
     * Parses a duration string like "3:45" or "1:02:30" into milliseconds.
     *
     * @return Duration in milliseconds, or null if the format is unrecognized.
     */
    private fun parseDurationToMs(duration: String?): Long? {
        if (duration == null) return null
        val parts = duration.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60L + parts[1]) * 1000L
            3 -> (parts[0] * 3600L + parts[1] * 60L + parts[2]) * 1000L
            else -> null
        }
    }

    private val TRACK_COUNT_REGEX = Regex("""(\d+)\s+(?:songs?|tracks?)""")
}

// ── JsonElement navigation extensions ────────────────────────────────────────

/**
 * Safely navigates a chain of JSON object keys.
 *
 * Returns the [JsonElement] at the end of the path, or null if any key is missing
 * or the intermediate value is not a [JsonObject].
 */
private fun JsonObject.navigatePath(vararg keys: String): JsonElement? {
    var current: JsonElement = this
    for (key in keys) {
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return current
}

/** Safely casts to [JsonObject], returning null on type mismatch. */
private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

/** Safely casts to [JsonArray], returning null on type mismatch. */
private fun JsonElement.asArray(): JsonArray? = this as? JsonArray

/**
 * If this element is a [JsonArray], returns it; otherwise returns null.
 * Useful when the navigation target might already be an array.
 */
private fun JsonElement.firstArray(): JsonArray? = this as? JsonArray

/** Safely extracts a string primitive value, returning null on type mismatch. */
private fun JsonElement.asString(): String? =
    try { jsonPrimitive.contentOrNull } catch (_: Exception) { null }
