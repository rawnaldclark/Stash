package com.stash.data.ytmusic

import android.util.Log
import com.stash.core.model.SyncResult
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.SearchAllResults
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import com.stash.data.ytmusic.model.TrackSummary
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
        private const val TAG = "StashYTApi"

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
        Log.d(TAG, "getPlaylistTracks: response top-level keys: ${response.keys}")
        val tracks = parseTracksFromBrowse(response)
        return if (tracks.isEmpty()) {
            SyncResult.Empty("Playlist $playlistId returned no tracks")
        } else {
            SyncResult.Success(tracks)
        }
    }

    /**
     * Sectioned Search tab results.
     *
     * Wraps [InnerTubeClient.search]. InnerTube search returns shelves under
     * `contents.tabbedSearchResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer.contents`.
     * Each shelf is either a [musicCardShelfRenderer] (the tall "Top result"
     * card, at most one) or a named [musicShelfRenderer] (Songs / Artists /
     * Albums / Videos / Playlists / Community playlists / Featured playlists …).
     *
     * This method emits four section kinds in fixed order —
     * Top → Songs → Artists → Albums — skipping any shelf that is missing or
     * empty. The Songs list is capped at 4 rows per the Search tab spec.
     *
     * A null InnerTube response, a missing `sectionListRenderer`, or a
     * zero-shelf response (e.g. InnerTube's "No results" message) all yield
     * [SearchAllResults] with an empty sections list — callers should render
     * the empty-state UI in that case.
     *
     * @param query The search query string as typed by the user.
     * @return An ordered list of sections; empty if nothing matched.
     */
    suspend fun searchAll(query: String): SearchAllResults {
        val response = innerTubeClient.search(query)
            ?: return SearchAllResults(emptyList())

        val shelves = response.navigatePath(
            "contents", "tabbedSearchResultsRenderer", "tabs",
        )?.firstArray()?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.asArray()
            ?: return SearchAllResults(emptyList())

        val sections = mutableListOf<SearchResultSection>()

        // 1. Top result — musicCardShelfRenderer appears at most once, usually first.
        shelves.asSequence()
            .mapNotNull { it.asObject() }
            .firstOrNull { it.containsKey("musicCardShelfRenderer") }
            ?.get("musicCardShelfRenderer")?.asObject()
            ?.let { parseTopResultCard(it) }
            ?.let { sections.add(SearchResultSection.Top(it)) }

        // 2..4. Named musicShelfRenderer shelves, dispatched by their title text.
        for (shelf in shelves) {
            val renderer = shelf.asObject()?.get("musicShelfRenderer")?.asObject() ?: continue
            val title = renderer.navigatePath("title", "runs")?.firstArray()
                ?.firstOrNull()?.asObject()?.get("text")?.asString() ?: continue
            when (title) {
                "Songs" -> parseSongsShelf(renderer).takeIf { it.isNotEmpty() }
                    ?.let { sections.add(SearchResultSection.Songs(it.take(4))) }
                "Artists" -> parseArtistsShelf(renderer).takeIf { it.isNotEmpty() }
                    ?.let { sections.add(SearchResultSection.Artists(it)) }
                "Albums" -> parseAlbumsShelf(renderer).takeIf { it.isNotEmpty() }
                    ?.let { sections.add(SearchResultSection.Albums(it)) }
            }
        }

        Log.d(TAG, "searchAll('$query'): ${sections.size} sections")
        return SearchAllResults(sections)
    }

    // ── InnerTube response parsers ───────────────────────────────────────

    /**
     * Extracts tracks from a browse response.
     *
     * Tries two renderer paths:
     * 1. **twoColumnBrowseResultsRenderer** (playlist pages via `VL{playlistId}`) —
     *    tracks live under `secondaryContents -> sectionListRenderer -> contents[0]
     *    -> musicPlaylistShelfRenderer -> contents`. This matches the path used by
     *    ytmusicapi's `get_playlist()`.
     * 2. **singleColumnBrowseResultsRenderer** (liked songs, home page) —
     *    tracks live under `tabs[0] -> tabRenderer -> content -> sectionListRenderer
     *    -> contents -> musicShelfRenderer -> contents`.
     */
    private fun parseTracksFromBrowse(response: JsonObject): List<YTMusicTrack> {
        val tracks = mutableListOf<YTMusicTrack>()

        // Path 1 (playlist pages): twoColumnBrowseResultsRenderer -> secondaryContents
        // -> sectionListRenderer -> contents[0] -> musicPlaylistShelfRenderer -> contents
        // This is the path ytmusicapi uses for get_playlist().
        val twoColumnShelf = response.navigatePath(
            "contents", "twoColumnBrowseResultsRenderer",
            "secondaryContents", "sectionListRenderer", "contents",
        )?.asArray()?.firstOrNull()?.asObject()
            ?.get("musicPlaylistShelfRenderer")?.asObject()

        if (twoColumnShelf != null) {
            Log.d(TAG, "parseTracksFromBrowse: using twoColumnBrowseResultsRenderer path")
            val items = twoColumnShelf["contents"]?.asArray() ?: return emptyList()
            for (item in items) {
                val renderer = item.asObject()
                    ?.get("musicResponsiveListItemRenderer")?.asObject()
                    ?: continue
                parseTrackFromRenderer(renderer)?.let { tracks.add(it) }
            }
            return tracks
        }

        // Path 2 (home page, liked songs): singleColumnBrowseResultsRenderer -> tabs[0]
        // -> tabRenderer -> content -> sectionListRenderer -> contents -> musicShelfRenderer
        val sections = response.navigatePath(
            "contents",
            "singleColumnBrowseResultsRenderer",
            "tabs",
        )?.firstArray()?.firstOrNull()
            ?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.asArray()

        if (sections != null) {
            Log.d(TAG, "parseTracksFromBrowse: using singleColumnBrowseResultsRenderer path")
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
        }

        Log.d(TAG, "parseTracksFromBrowse: found ${tracks.size} tracks")
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

        // Extract thumbnail — pick the largest available by width, then
        // upgrade the CDN URL to request high-res (544px for lh3).
        val thumbnails = renderer.navigatePath("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")
            ?.firstArray()
        val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
            thumbnails?.maxByOrNull {
                it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
            }?.asObject()?.get("url")?.asString()
        )

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

                // Filter: only accept algorithmic mix playlists (Discover/Daily/Supermix/
                // Replay/Archive/New Release). Reject albums (MPRE*), channels (UC*),
                // and community/user playlists (VLPL*, PL*, OLAK5uy_*).
                if (!isAllowedMixPlaylist(playlistId)) {
                    Log.d(TAG, "parseMixesFromHome: skipping non-mix '$title' (id=$playlistId)")
                    continue
                }

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
     * Whitelists algorithmic mix playlist IDs from YouTube Music's home feed.
     *
     * YouTube Music mixes all share identifiable ID prefixes because they are
     * generated playlists rather than user-created content:
     * - `VLRDTMAK5uy_*` — Daily Mixes, Discover Mix, Supermix, Replay Mix, Archive Mix
     * - `RDTMAK5uy_*` — Same playlists, without the `VL` browse prefix
     * - `RDCLAK5uy_*` — YouTube Music radio / station mixes
     * - `RDMM` — "My Mix" (personalized mix)
     * - `LM` — Liked Music
     *
     * This explicitly rejects:
     * - `MPRE*` — Album browse IDs
     * - `UC*` — Channel IDs (artists)
     * - `VLPL*` / `PL*` — User-created playlists (community content)
     * - `OLAK5uy_*` — Album content playlists
     */
    private fun isAllowedMixPlaylist(playlistId: String): Boolean {
        return playlistId.startsWith("VLRDTMAK5uy_") ||
            playlistId.startsWith("RDTMAK5uy_") ||
            playlistId.startsWith("RDCLAK5uy_") ||
            playlistId == "RDMM" ||
            playlistId == "LM"
    }

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

    /**
     * Parses a duration string like "3:45" or "1:02:30" into seconds.
     *
     * @return Duration in seconds as a Double, or 0.0 if the format is unrecognized.
     */
    private fun parseDurationToSeconds(duration: String?): Double {
        val ms = parseDurationToMs(duration) ?: return 0.0
        return ms / 1000.0
    }

    // ── Search-tab shelf parsers (used by searchAll) ─────────────────────────

    /**
     * Parses the tall "Top result" card (`musicCardShelfRenderer`).
     *
     * The card's `title.runs[0].navigationEndpoint` tells us the kind:
     * - `browseEndpoint` with `pageType == MUSIC_PAGE_TYPE_ARTIST` → [TopResultItem.ArtistTop].
     * - `watchEndpoint` with a `videoId` → [TopResultItem.TrackTop].
     *
     * Any other kind (album, playlist, podcast, …) is out-of-scope for the
     * Search tab's top slot and returns null — the UI will render no top card
     * but still show the named shelves below.
     *
     * @param shelf The parsed `musicCardShelfRenderer` object.
     * @return A [TopResultItem], or null if the card is neither artist nor track.
     */
    private fun parseTopResultCard(shelf: JsonObject): TopResultItem? {
        val titleRun = shelf.navigatePath("title", "runs")?.firstArray()
            ?.firstOrNull()?.asObject()
            ?: return null
        val title = titleRun["text"]?.asString() ?: return null

        val thumbnails = shelf.navigatePath(
            "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
        )?.firstArray()
        val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
            thumbnails?.maxByOrNull {
                it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
            }?.asObject()?.get("url")?.asString()
        )

        // Artist top — browseEndpoint with artist page type.
        val browseEndpoint = titleRun.navigatePath("navigationEndpoint", "browseEndpoint")?.asObject()
        if (browseEndpoint != null) {
            val pageType = browseEndpoint.navigatePath(
                "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig",
                "pageType",
            )?.asString()
            if (pageType == "MUSIC_PAGE_TYPE_ARTIST") {
                val id = browseEndpoint["browseId"]?.asString() ?: return null
                return TopResultItem.ArtistTop(
                    ArtistSummary(id = id, name = title, avatarUrl = thumbnailUrl),
                )
            }
        }

        // Track top — watchEndpoint with a videoId.
        val watchEndpoint = titleRun.navigatePath("navigationEndpoint", "watchEndpoint")?.asObject()
        if (watchEndpoint != null) {
            val videoId = watchEndpoint["videoId"]?.asString() ?: return null
            // Subtitle runs: ["Song", " • ", "<artist>", " • ", "<album>", " • ", "<duration>"].
            // Drop the kind label, then separators (' • ', ' & ', ', ', ' x ') and take the
            // remaining data items in order. Duration is the last token like "3:32".
            val subtitleTexts = shelf.navigatePath("subtitle", "runs")?.asArray()
                ?.mapNotNull { it.asObject()?.get("text")?.asString() }
                ?.filterNot { it == " • " || it == " & " || it == ", " || it == " x " }
                ?: emptyList()
            val dataTokens = if (subtitleTexts.isNotEmpty()) subtitleTexts.drop(1) else emptyList()
            val durationToken = dataTokens.lastOrNull()?.takeIf { it.matches(DURATION_REGEX) }
            val nonDurationTokens = if (durationToken != null) dataTokens.dropLast(1) else dataTokens
            val artist = nonDurationTokens.getOrNull(0) ?: ""
            val album = nonDurationTokens.getOrNull(1)
            return TopResultItem.TrackTop(
                TrackSummary(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = parseDurationToSeconds(durationToken),
                    thumbnailUrl = thumbnailUrl,
                ),
            )
        }

        return null
    }

    /**
     * Parses the "Songs" shelf — a vertical list of `musicResponsiveListItemRenderer`
     * items with videoId, title, artists, album, and duration.
     *
     * Mirrors the column layout used by [parseTrackFromRenderer] but emits
     * [TrackSummary] instead of [YTMusicTrack] so the Search tab and the
     * download-match pipeline can diverge.
     */
    private fun parseSongsShelf(shelfRenderer: JsonObject): List<TrackSummary> {
        val items = shelfRenderer["contents"]?.asArray() ?: return emptyList()
        val result = mutableListOf<TrackSummary>()
        for (item in items) {
            val renderer = item.asObject()
                ?.get("musicResponsiveListItemRenderer")?.asObject()
                ?: continue

            val videoId = renderer["playlistItemData"]?.asObject()
                ?.get("videoId")?.asString()
                ?: renderer.navigatePath(
                    "overlay", "musicItemThumbnailOverlayRenderer", "content",
                    "musicPlayButtonRenderer", "playNavigationEndpoint",
                    "watchEndpoint", "videoId",
                )?.asString()
                ?: continue

            val flexColumns = renderer["flexColumns"]?.asArray() ?: continue
            val title = flexColumns.getOrNull(0)?.asObject()
                ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
                ?.firstArray()?.firstOrNull()?.asObject()
                ?.get("text")?.asString()
                ?: continue

            val artistRuns = flexColumns.getOrNull(1)?.asObject()
                ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
                ?.asArray()
            val artist = artistRuns
                ?.mapNotNull { it.asObject()?.get("text")?.asString() }
                ?.filterNot { it == " & " || it == ", " || it == " x " }
                ?.joinToString(", ")
                ?: ""

            val album = flexColumns.getOrNull(2)?.asObject()
                ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
                ?.firstArray()?.firstOrNull()?.asObject()
                ?.get("text")?.asString()

            val thumbnails = renderer.navigatePath(
                "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
            )?.firstArray()
            val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
                thumbnails?.maxByOrNull {
                    it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
                }?.asObject()?.get("url")?.asString()
            )

            val durationText = renderer["fixedColumns"]?.asArray()
                ?.firstOrNull()?.asObject()
                ?.navigatePath("musicResponsiveListItemFixedColumnRenderer", "text", "runs")
                ?.firstArray()?.firstOrNull()?.asObject()
                ?.get("text")?.asString()

            result.add(
                TrackSummary(
                    videoId = videoId,
                    title = title,
                    artist = artist,
                    album = album,
                    durationSeconds = parseDurationToSeconds(durationText),
                    thumbnailUrl = thumbnailUrl,
                ),
            )
        }
        return result
    }

    /**
     * Parses the "Artists" shelf — `musicResponsiveListItemRenderer` items
     * where the item's top-level `navigationEndpoint.browseEndpoint.browseId`
     * is the artist channel ID.
     */
    private fun parseArtistsShelf(shelfRenderer: JsonObject): List<ArtistSummary> {
        val items = shelfRenderer["contents"]?.asArray() ?: return emptyList()
        val result = mutableListOf<ArtistSummary>()
        for (item in items) {
            val renderer = item.asObject()
                ?.get("musicResponsiveListItemRenderer")?.asObject()
                ?: continue

            val id = renderer.navigatePath(
                "navigationEndpoint", "browseEndpoint", "browseId",
            )?.asString() ?: continue

            val name = renderer["flexColumns"]?.asArray()
                ?.getOrNull(0)?.asObject()
                ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
                ?.firstArray()?.firstOrNull()?.asObject()
                ?.get("text")?.asString()
                ?: continue

            val thumbnails = renderer.navigatePath(
                "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
            )?.firstArray()
            val avatarUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
                thumbnails?.maxByOrNull {
                    it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
                }?.asObject()?.get("url")?.asString()
            )

            result.add(ArtistSummary(id = id, name = name, avatarUrl = avatarUrl))
        }
        return result
    }

    /**
     * Parses the "Albums" shelf — `musicTwoRowItemRenderer` cards with a
     * browseId (MPREb_*), title, artist and year in subtitle, and a square
     * thumbnail.
     *
     * Subtitle runs typically look like ["Album"/"EP"/"Single", " • ",
     * "<artist>", " • ", "<year>"]. We filter out separator runs and the
     * leading type label, then treat the first remaining token as the artist
     * and a 4-digit numeric token as the year.
     */
    private fun parseAlbumsShelf(shelfRenderer: JsonObject): List<AlbumSummary> {
        val items = shelfRenderer["contents"]?.asArray() ?: return emptyList()
        val result = mutableListOf<AlbumSummary>()
        for (item in items) {
            val renderer = item.asObject()
                ?.get("musicTwoRowItemRenderer")?.asObject()
                ?: continue

            val id = renderer.navigatePath(
                "navigationEndpoint", "browseEndpoint", "browseId",
            )?.asString() ?: continue

            val title = renderer.navigatePath("title", "runs")?.firstArray()
                ?.firstOrNull()?.asObject()?.get("text")?.asString()
                ?: continue

            val subtitleTexts = renderer.navigatePath("subtitle", "runs")?.asArray()
                ?.mapNotNull { it.asObject()?.get("text")?.asString() }
                ?.filterNot { it == " • " || it == " & " || it == ", " || it == " x " }
                ?: emptyList()
            // Drop the type label ("Album"/"EP"/"Single") if present.
            val dataTokens = if (
                subtitleTexts.firstOrNull()?.let { ALBUM_TYPE_LABELS.contains(it) } == true
            ) subtitleTexts.drop(1) else subtitleTexts
            val year = dataTokens.firstOrNull { it.matches(YEAR_REGEX) }
            val artist = dataTokens.firstOrNull { it != year } ?: ""

            val thumbnails = renderer.navigatePath(
                "thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails",
            )?.firstArray()
            val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
                thumbnails?.maxByOrNull {
                    it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
                }?.asObject()?.get("url")?.asString()
            )

            result.add(
                AlbumSummary(
                    id = id,
                    title = title,
                    artist = artist,
                    thumbnailUrl = thumbnailUrl,
                    year = year,
                ),
            )
        }
        return result
    }

    private val TRACK_COUNT_REGEX = Regex("""(\d+)\s+(?:songs?|tracks?)""")
    private val DURATION_REGEX = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
    private val YEAR_REGEX = Regex("""\d{4}""")
    private val ALBUM_TYPE_LABELS = setOf("Album", "Single", "EP", "Mixtape", "Compilation")
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
