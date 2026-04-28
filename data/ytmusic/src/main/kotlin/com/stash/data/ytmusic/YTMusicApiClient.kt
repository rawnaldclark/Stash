package com.stash.data.ytmusic

import android.util.Log
import com.stash.core.model.SyncResult
import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistProfile
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.MusicVideoType
import com.stash.data.ytmusic.model.PagedPlaylists
import com.stash.data.ytmusic.model.PagedTracks
import com.stash.data.ytmusic.model.SearchAllResults
import com.stash.data.ytmusic.model.SearchResultSection
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

        /** InnerTube browse ID for the YouTube Music home feed. */
        private const val BROWSE_HOME = "FEmusic_home"

        /**
         * Library Playlists tab — user-created + user-saved playlists.
         * Returns a tabbed browse with a `gridRenderer` of
         * `musicTwoRowItemRenderer` items. Each item's
         * `navigationEndpoint.browseEndpoint.browseId` resolves to the
         * same `VL{playlistId}` format as every other playlist in the
         * system, so [getPlaylistTracks] works without modification.
         */
        private const val BROWSE_LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"

        /** Safety cap on continuation depth. ~10K items @ 100/page. */
        internal const val MAX_PAGES = 100

        /**
         * Cap for radio playlists (browseId VLRD*, raw RD*). YT Music radios
         * emit a continuation token after every page — walking them is
         * effectively infinite. The initial page (~100 tracks) already
         * exceeds the user's expectation for these "Daily Mix"-style surfaces.
         */
        internal const val RADIO_MAX_PAGES = 1

        /** Backoff delays (ms) between retries on transient failures. */
        private val RETRY_BACKOFFS_MS = listOf(500L, 1500L)
    }

    /**
     * Fetches the authenticated user's YT Music Liked Songs — the playlist
     * surface marked with the red heart icon in the YT Music Library.
     *
     * Identified by the special playlist ID `LM` (browseId `VLLM`), this is
     * the same playlist endpoint as any other user playlist — it just has a
     * fixed ID. ytmusicapi's `get_liked_songs()` resolves to `get_playlist("LM")`
     * for the same reason.
     *
     * Note: this is **distinct** from `FEmusic_liked_videos`, which is YouTube
     * proper's "Liked Videos" feature (mostly non-music thumb-ups). Earlier
     * versions of this method incorrectly used that endpoint and capped at
     * the user's much smaller liked-videos count rather than their actual
     * Liked Songs library.
     */
    suspend fun getLikedSongs(): SyncResult<PagedTracks> = getPlaylistTracks("LM")

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
     * Fetches the authenticated user's Library → Playlists tab — every
     * playlist they created or saved, regardless of whether it came from
     * Home mixes or an external share. Built-in pseudo-playlists (Liked
     * Music / Episodes for Later) are filtered out so they don't create
     * duplicate sync rows alongside [getLikedSongs].
     *
     * Requires a valid SAPISID cookie; an unauthenticated browse returns
     * an empty library.
     */
    suspend fun getUserPlaylists(): SyncResult<PagedPlaylists> {
        val response = innerTubeClient.browse(BROWSE_LIBRARY_PLAYLISTS)
            ?: return SyncResult.Error("InnerTube browse($BROWSE_LIBRARY_PLAYLISTS) returned null")

        val paginated = paginateBrowse(response) { page ->
            val isContinuation = page["continuationContents"] != null || page["onResponseReceivedActions"] != null
            if (isContinuation) parseUserPlaylistsContinuationPage(page) else parseUserPlaylists(page)
        }

        if (paginated.items.isEmpty()) {
            return SyncResult.Empty("Library returned no playlists")
        }
        return SyncResult.Success(
            PagedPlaylists(
                playlists = paginated.items,
                partial = paginated.partial,
                partialReason = paginated.partialReason,
            )
        )
    }

    /**
     * Fetches all tracks in a specific YouTube Music playlist, walking all
     * continuation pages and returning a [PagedTracks] result.
     *
     * The browse ID for playlists is `VL` + the playlist ID (e.g. `VLPLxxxxxx`).
     * If the playlist header contains a track count, [PagedTracks.expectedCount] is
     * populated and a 95%-threshold check is applied — if fewer than 95% of the
     * expected tracks were fetched, [PagedTracks.partial] is set to true.
     *
     * @param playlistId The playlist ID (without the `VL` prefix).
     * @return [SyncResult.Success] wrapping [PagedTracks], [SyncResult.Empty]
     *   if no tracks were found, or [SyncResult.Error] on a null initial response.
     */
    suspend fun getPlaylistTracks(
        playlistId: String,
        maxPages: Int = MAX_PAGES,
    ): SyncResult<PagedTracks> {
        val browseId = if (playlistId.startsWith("VL")) playlistId else "VL$playlistId"
        // Radio IDs (RD*, including VLRD*) are infinite continuation chains.
        // Auto-cap at 1 page regardless of caller — getUserPlaylists() can also
        // return radios that the user has saved to their library, and we must
        // not walk those greedily. Caller-supplied lower caps still win.
        val isRadio = browseId.startsWith("VLRD") || browseId.startsWith("RD")
        val effectiveMaxPages = if (isRadio) minOf(maxPages, RADIO_MAX_PAGES) else maxPages
        if (isRadio && effectiveMaxPages < maxPages) {
            Log.d(TAG, "getPlaylistTracks: $browseId is radio, capping to $effectiveMaxPages page(s)")
        }
        val response = innerTubeClient.browse(browseId)
            ?: return SyncResult.Error("InnerTube browse($browseId) returned null")
        Log.d(TAG, "getPlaylistTracks: response top-level keys: ${response.keys}")

        val expectedCount = extractExpectedTrackCount(response)
        val paginated = paginateBrowse(response, maxPages = effectiveMaxPages) { page ->
            val isContinuation = page["continuationContents"] != null || page["onResponseReceivedActions"] != null
            if (isContinuation) parseContinuationPage(page) else parseTracksFromBrowse(page)
        }

        if (paginated.items.isEmpty()) {
            return SyncResult.Empty("Playlist $playlistId returned no tracks")
        }

        val (partial, partialReason) = verifyExpectedCount(
            fetched = paginated.items.size,
            expected = expectedCount,
            existingPartial = paginated.partial,
            existingReason = paginated.partialReason,
        )

        return SyncResult.Success(
            PagedTracks(
                tracks = paginated.items,
                expectedCount = expectedCount,
                partial = partial,
                partialReason = partialReason,
            )
        )
    }

    /**
     * Extends [paginateBrowse]'s partial signal with the count-vs-header check.
     * If fetched count is below 95% of expected, mark partial (and append the
     * count info to the existing reason if there already was one).
     */
    private fun verifyExpectedCount(
        fetched: Int,
        expected: Int?,
        existingPartial: Boolean,
        existingReason: String?,
    ): Pair<Boolean, String?> {
        if (expected == null) return existingPartial to existingReason
        if (fetched >= expected * 0.95) return existingPartial to existingReason

        val countReason = "fetched $fetched of $expected expected"
        Log.w(TAG, "verifyExpectedCount: $countReason")
        val combined = if (existingReason == null) countReason else "$existingReason; $countReason"
        return true to combined
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
            // Parsers live in SearchResponseParser.kt as top-level internal funcs.
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

    /**
     * Fetch a YouTube Music artist browse page in one round-trip and parse it
     * into an [ArtistProfile].
     *
     * The browse response for an artist channel (browseId starting with `UC`
     * or `MPLAUC`) contains:
     *   - `header.musicImmersiveHeaderRenderer` — name, avatar, subscribers.
     *   - A single `singleColumnBrowseResultsRenderer` tab holding a mix of
     *     `musicShelfRenderer` (Popular) and `musicCarouselShelfRenderer`
     *     (Albums, Singles, "Fans also like") sections.
     *
     * Missing shelves surface as empty lists so the UI can render without
     * branching on null. A null InnerTube response (e.g. network failure)
     * yields an [ArtistProfile] with empty name / empty shelves — callers
     * should treat a blank name as a "retry" signal.
     *
     * @param browseId Either a raw channel ID (`UC…`) or an `MPLAUC…` music-
     *   channel variant. Normalized to the bare channel form for cache-key
     *   stability per spec §8.
     * @return A populated [ArtistProfile]; shelves that the real response
     *   lacks are empty lists.
     */
    suspend fun getArtist(browseId: String): ArtistProfile {
        val normalized = normalizeArtistBrowseId(browseId)
        val response = innerTubeClient.browse(normalized)
            ?: return emptyArtistProfile(normalized)

        val header = response["header"]?.asObject()
            ?.get("musicImmersiveHeaderRenderer")?.asObject()
        val name = header?.navigatePath("title", "runs")?.firstArray()
            ?.firstOrNull()?.asObject()?.get("text")?.asString() ?: ""
        // Pick the largest thumbnail by explicit width — InnerTube's ordering
        // isn't guaranteed across locales — then run it through the shared
        // [ArtUrlUpgrader] so lh3 CDN URLs are bumped to 544×544 (matching
        // every other art surface in the app).
        val avatarUrl = header?.navigatePath(
            "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
        )?.asArray()
            ?.maxByOrNull { it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0 }
            ?.asObject()?.get("url")?.asString()
            ?.let { com.stash.core.common.ArtUrlUpgrader.upgrade(it) }
        val subscribersText = header?.navigatePath(
            "subscriptionButton", "subscribeButtonRenderer",
            "subscriberCountText", "runs",
        )?.firstArray()?.firstOrNull()?.asObject()?.get("text")?.asString()

        val sections = response.navigatePath(
            "contents", "singleColumnBrowseResultsRenderer", "tabs",
        )?.firstArray()?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.asArray()
            ?: return emptyArtistProfile(normalized, name, avatarUrl, subscribersText)

        var popular = emptyList<TrackSummary>()
        var albums = emptyList<AlbumSummary>()
        var singles = emptyList<AlbumSummary>()
        var related = emptyList<ArtistSummary>()

        // Parsers live in ArtistResponseParser.kt as top-level internal funcs.
        for (section in sections) {
            val obj = section.asObject() ?: continue
            obj["musicShelfRenderer"]?.asObject()?.let { shelf ->
                // Structural match: take the first musicShelfRenderer that
                // produces non-empty track rows. Title matching breaks when
                // YouTube Music labels the shelf "Songs" (or leaves it blank)
                // instead of "Popular" — which is common in production.
                if (popular.isEmpty()) {
                    val parsed = parseTracksFromShelf(shelf).take(10)
                    if (parsed.isNotEmpty()) popular = parsed
                }
            }
            obj["musicCarouselShelfRenderer"]?.asObject()?.let { carousel ->
                val title = carousel.navigatePath(
                    "header", "musicCarouselShelfBasicHeaderRenderer",
                    "title", "runs",
                )?.firstArray()?.firstOrNull()?.asObject()
                    ?.get("text")?.asString().orEmpty()
                when {
                    title.equals("Albums", ignoreCase = true) ->
                        albums = parseAlbumsCarousel(carousel)
                    // `contains("Singles")` subsumes both the stand-alone
                    // "Singles" shelf and the combined "Singles and EPs"
                    // shelf that InnerTube A/B-ships; the `contains("EPs")`
                    // arm guards against an EP-only locale variant. If both
                    // arrive on the same page, defensively merge them rather
                    // than clobber the first shelf we saw.
                    title.contains("Singles", ignoreCase = true) ||
                        title.contains("EPs", ignoreCase = true) ->
                        singles = singles + parseAlbumsCarousel(carousel)
                    title.contains("Fans also like", ignoreCase = true) ->
                        related = parseArtistsCarousel(carousel)
                    // Surface locale regressions (e.g. a translated "Albumes"
                    // or a new shelf we don't dispatch yet) in logs instead of
                    // silently producing an empty shelf.
                    else -> Log.d(TAG, "getArtist: unknown carousel '$title' — skipped")
                }
            }
        }

        Log.d(
            TAG,
            "getArtist('$normalized'): name='$name' popular=${popular.size} " +
                "albums=${albums.size} singles=${singles.size} related=${related.size}",
        )
        return ArtistProfile(
            id = normalized,
            name = name,
            avatarUrl = avatarUrl,
            subscribersText = subscribersText,
            popular = popular,
            albums = albums,
            singles = singles,
            related = related,
        )
    }

    /** Builds a shelf-less [ArtistProfile] for error / partial-response paths. */
    private fun emptyArtistProfile(
        id: String,
        name: String = "",
        avatarUrl: String? = null,
        subscribersText: String? = null,
    ): ArtistProfile = ArtistProfile(
        id = id,
        name = name,
        avatarUrl = avatarUrl,
        subscribersText = subscribersText,
        popular = emptyList(),
        albums = emptyList(),
        singles = emptyList(),
        related = emptyList(),
    )

    /**
     * Fetch a YouTube Music album browse page in one round-trip and parse it
     * into an [AlbumDetail].
     *
     * The browse response for an album ([browseId] starts with `MPREb_`)
     * contains:
     *   - `header.musicDetailHeaderRenderer` — title, subtitle (artist + year),
     *     and cover art.
     *   - A single `singleColumnBrowseResultsRenderer` tab holding the
     *     tracklist (`musicShelfRenderer`) followed by the "More by this
     *     artist" row (`musicCarouselShelfRenderer`).
     *
     * Missing shelves surface as empty lists so the UI can still render the
     * hero from nav args + a "No tracks available" message when the tracklist
     * is region-blocked. A null InnerTube response (e.g. network failure)
     * yields an [AlbumDetail] with a blank title / empty shelves — callers
     * should treat a blank title as a retry signal.
     *
     * @param browseId The album browseId (e.g. `MPREb_…`).
     * @return A populated [AlbumDetail]; shelves the real response lacks are
     *   returned as empty lists.
     */
    suspend fun getAlbum(browseId: String): AlbumDetail {
        val response = innerTubeClient.browse(browseId)
            ?: return AlbumDetail(
                id = browseId,
                title = "",
                artist = "",
                artistId = null,
                thumbnailUrl = null,
                year = null,
                tracks = emptyList(),
                moreByArtist = emptyList(),
            )
        return AlbumResponseParser.parse(browseId, response)
    }

    // `normalizeArtistBrowseId` lives as a top-level `internal` fun in
    // [ResponseParserHelpers.kt] so unit tests can exercise it directly
    // without reflection; this file calls it below in [getArtist].

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
            val items = twoColumnShelf["contents"]?.asArray()
            if (items == null) {
                Log.d(
                    TAG,
                    "parseTracksFromBrowse: twoColumn path — shelf present but no 'contents' " +
                        "array; shelf keys=${twoColumnShelf.keys}",
                )
                return emptyList()
            }
            var skipped = 0
            for (item in items) {
                val obj = item.asObject()
                val renderer = obj?.get("musicResponsiveListItemRenderer")?.asObject()
                if (renderer == null) {
                    skipped++
                    continue
                }
                val parsed = parseTrackFromRenderer(renderer)
                if (parsed != null) tracks.add(parsed) else skipped++
            }
            Log.d(
                TAG,
                "parseTracksFromBrowse: twoColumn path parsed=${tracks.size} skipped=$skipped " +
                    "rawItems=${items.size}",
            )
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
            Log.d(TAG, "parseTracksFromBrowse: found ${tracks.size} tracks (singleColumn path)")
            return tracks
        }

        Log.w(
            TAG,
            "parseTracksFromBrowse: NEITHER shelf path matched. Top-level keys=${response.keys}",
        )
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

        // InnerTube's authoritative video-type enum. Same renderer path as
        // [InnerTubeSearchExecutor.parseRenderer]; drives Mode B canonicalization
        // — a YT library import carrying MUSIC_VIDEO_TYPE_OMV triggers a search
        // for the ATV equivalent so users get studio audio instead of MV audio.
        val musicVideoType = MusicVideoType.fromInnerTube(
            renderer.navigatePath(
                "overlay", "musicItemThumbnailOverlayRenderer", "content",
                "musicPlayButtonRenderer", "playNavigationEndpoint",
                "watchEndpoint", "watchEndpointMusicSupportedConfigs",
                "watchEndpointMusicConfig", "musicVideoType",
            )?.asString(),
        )

        return YTMusicTrack(
            videoId = videoId,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
            thumbnailUrl = thumbnailUrl,
            musicVideoType = musicVideoType,
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

    /**
     * Extracts user-library playlists from the `FEmusic_liked_playlists`
     * browse response.
     *
     * The Library → Playlists tab typically renders either:
     *  - A `gridRenderer` (newer web client): flat grid of
     *    `musicTwoRowItemRenderer` items.
     *  - A `musicShelfRenderer` (older / mobile clients): list of
     *    `musicResponsiveListItemRenderer` items.
     *
     * We walk every tab → section → contents and try both shapes on each
     * container, collecting whatever works. Built-in playlists (Liked
     * Music, Episodes for Later) and non-playlist cards (the "New
     * playlist" tile) are filtered out by browseId prefix.
     */
    private fun parseUserPlaylists(response: JsonObject): List<YTMusicPlaylist> {
        val playlists = mutableListOf<YTMusicPlaylist>()
        val seenIds = mutableSetOf<String>()

        val tabs = response.navigatePath(
            "contents", "singleColumnBrowseResultsRenderer", "tabs",
        )?.asArray() ?: return emptyList()

        for (tab in tabs) {
            val sections = tab.asObject()
                ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
                ?.asArray()
                ?: continue

            for (section in sections) {
                val sectionObj = section.asObject() ?: continue

                // Collect candidate item arrays from either shape.
                val gridItems = sectionObj
                    .get("gridRenderer")?.asObject()
                    ?.get("items")?.asArray()
                val itemSectionGridItems = sectionObj
                    .get("itemSectionRenderer")?.asObject()
                    ?.get("contents")?.asArray()
                    ?.firstOrNull()?.asObject()
                    ?.get("gridRenderer")?.asObject()
                    ?.get("items")?.asArray()
                val musicShelfItems = sectionObj
                    .get("musicShelfRenderer")?.asObject()
                    ?.get("contents")?.asArray()

                val items = gridItems ?: itemSectionGridItems ?: musicShelfItems ?: continue

                for (item in items) {
                    val renderer = item.asObject()
                        ?.get("musicTwoRowItemRenderer")?.asObject()
                        ?: item.asObject()
                            ?.get("musicResponsiveListItemRenderer")?.asObject()
                        ?: continue

                    val playlist = parseSinglePlaylistFromTwoRowRenderer(renderer) ?: continue
                    if (!seenIds.add(playlist.playlistId)) continue
                    playlists.add(playlist)
                }
            }
        }

        Log.d(TAG, "parseUserPlaylists: found ${playlists.size} library playlists")
        return playlists
    }

    /**
     * Continuation-shape parser for the user-library playlist list. Mirrors
     * [parseUserPlaylists] but reads from `continuationContents.musicShelfContinuation`.
     */
    private fun parseUserPlaylistsContinuationPage(response: JsonObject): List<YTMusicPlaylist> {
        val items = response.navigatePath(
            "continuationContents", "musicShelfContinuation", "contents",
        )?.asArray() ?: return emptyList()
        val out = mutableListOf<YTMusicPlaylist>()
        for (item in items) {
            val renderer = item.asObject()?.get("musicTwoRowItemRenderer")?.asObject() ?: continue
            parseSinglePlaylistFromTwoRowRenderer(renderer)?.let { out.add(it) }
        }
        return out
    }

    /**
     * Parses a single playlist entry from a `musicTwoRowItemRenderer` (or
     * `musicResponsiveListItemRenderer`) object. Shared by [parseUserPlaylists]
     * and [parseUserPlaylistsContinuationPage].
     *
     * Returns `null` if the renderer does not represent an accepted user playlist
     * (e.g. missing browseId, non-VL prefix, built-in pseudo-playlists, or no title).
     */
    private fun parseSinglePlaylistFromTwoRowRenderer(renderer: JsonObject): YTMusicPlaylist? {
        val browseId = renderer.navigatePath(
            "navigationEndpoint", "browseEndpoint", "browseId",
        )?.asString() ?: return null

        // Accept only playlist browseIds (VL-prefixed). Filter
        // out built-ins and non-playlist tiles.
        if (!browseId.startsWith("VL")) return null
        if (browseId == "VLLM" || browseId == "VLSE") return null

        val playlistId = browseId.removePrefix("VL")

        val title = renderer["title"]?.asObject()
            ?.get("runs")?.asArray()
            ?.firstOrNull()?.asObject()
            ?.get("text")?.asString()
            ?: renderer["flexColumns"]?.asArray()
                ?.firstOrNull()?.asObject()
                ?.navigatePath(
                    "musicResponsiveListItemFlexColumnRenderer",
                    "text",
                    "runs",
                )?.asArray()
                ?.firstOrNull()?.asObject()
                ?.get("text")?.asString()
            ?: return null

        val thumbnailUrl = renderer.navigatePath(
            "thumbnailRenderer", "musicThumbnailRenderer",
            "thumbnail", "thumbnails",
        )?.firstArray()?.lastOrNull()
            ?.asObject()?.get("url")?.asString()

        val subtitleText = renderer["subtitle"]?.asObject()
            ?.get("runs")?.asArray()
            ?.mapNotNull { it.asObject()?.get("text")?.asString() }
            ?.joinToString("")
        val trackCount = subtitleText?.let { TRACK_COUNT_REGEX.find(it) }
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        return YTMusicPlaylist(
            playlistId = playlistId,
            title = title,
            thumbnailUrl = thumbnailUrl,
            trackCount = trackCount,
        )
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

    // Search-tab shelf parsers live in SearchResponseParser.kt as top-level
    // internal functions so that follow-up artist-profile parsers (Task 2)
    // can grow without pushing this file past 1000 LOC.

    private val TRACK_COUNT_REGEX = Regex("""(\d+)\s+(?:songs?|tracks?)""")

    /**
     * Extracts the continuation token from any of the four InnerTube response
     * shapes that can carry it.
     *
     * Shape 1 – `continuationContents.musicPlaylistShelfContinuation` or
     *   `musicShelfContinuation` (liked-songs page 2+):
     *   token at `.continuations[0].nextContinuationData.continuation`
     *
     * Shape 2 – `onResponseReceivedActions[*].appendContinuationItemsAction`
     *   (playlist page 2+, the shape our captured fixtures actually return):
     *   last item in `continuationItems` → `continuationItemRenderer
     *   .continuationEndpoint.continuationCommand.token`
     *
     * Shape 3 – initial twoColumn browse (`contents.twoColumnBrowseResultsRenderer`):
     *   last item in `...musicPlaylistShelfRenderer.contents` → same
     *   `continuationItemRenderer` path as Shape 2.
     *
     * Shape 4 – initial singleColumn browse (`contents.singleColumnBrowseResultsRenderer`):
     *   4a) last item in `...musicShelfRenderer.contents` → same
     *       `continuationItemRenderer` path as Shape 2.
     *   4b) `...musicShelfRenderer.continuations[0].nextContinuationData.continuation`
     *       (liked-songs / `FEmusic_liked_videos` — token lives at shelf level, not
     *       as a synthetic last item in `contents`).
     *
     * Returns the first token found across all shapes, or null if none present.
     */
    private fun extractContinuationToken(response: JsonObject): String? {
        // Shape 1: continuationContents.musicPlaylistShelfContinuation or musicShelfContinuation
        val continuationContents = response["continuationContents"]?.asObject()
        if (continuationContents != null) {
            val shelf = continuationContents["musicPlaylistShelfContinuation"]?.asObject()
                ?: continuationContents["musicShelfContinuation"]?.asObject()
            val token = shelf?.get("continuations")?.asArray()
                ?.firstOrNull()?.asObject()
                ?.navigatePath("nextContinuationData", "continuation")?.asString()
            if (token != null) return token
            // Also check last item for continuationItemRenderer (some continuation responses
            // embed the next-page token as the last item rather than a top-level continuations key).
            val lastItemToken = shelf?.get("contents")?.asArray()
                ?.lastOrNull()?.asObject()
                ?.navigatePath(
                    "continuationItemRenderer", "continuationEndpoint",
                    "continuationCommand", "token",
                )?.asString()
            if (lastItemToken != null) return lastItemToken
        }

        // Shape 2: onResponseReceivedActions[*].appendContinuationItemsAction.continuationItems
        val actions = response["onResponseReceivedActions"]?.asArray()
        if (actions != null) {
            for (action in actions) {
                val items = action.asObject()
                    ?.navigatePath("appendContinuationItemsAction", "continuationItems")
                    ?.asArray()
                    ?: continue
                val token = items.lastOrNull()?.asObject()
                    ?.navigatePath(
                        "continuationItemRenderer", "continuationEndpoint",
                        "continuationCommand", "token",
                    )?.asString()
                if (token != null) return token
            }
        }

        // Shape 3: initial twoColumn browse — last item in musicPlaylistShelfRenderer.contents
        val twoColumnShelfContents = response.navigatePath(
            "contents", "twoColumnBrowseResultsRenderer",
            "secondaryContents", "sectionListRenderer", "contents",
        )?.asArray()?.firstOrNull()?.asObject()
            ?.get("musicPlaylistShelfRenderer")?.asObject()
            ?.get("contents")?.asArray()
        if (twoColumnShelfContents != null) {
            val token = twoColumnShelfContents.lastOrNull()?.asObject()
                ?.navigatePath(
                    "continuationItemRenderer", "continuationEndpoint",
                    "continuationCommand", "token",
                )?.asString()
            if (token != null) return token
        }

        // Shape 4: initial singleColumn browse — two sub-shapes:
        //   4a) last item in musicShelfRenderer.contents → continuationItemRenderer
        //   4b) musicShelfRenderer.continuations[0].nextContinuationData.continuation
        //       (used by FEmusic_liked_videos; the token sits at the shelf level, not as
        //        a synthetic last item in contents)
        val singleColumnSections = response.navigatePath(
            "contents", "singleColumnBrowseResultsRenderer", "tabs",
        )?.firstArray()?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.asArray()
        if (singleColumnSections != null) {
            for (section in singleColumnSections) {
                val shelf = section.asObject()?.get("musicShelfRenderer")?.asObject() ?: continue
                // 4a: last content item may be a continuationItemRenderer
                val contItemToken = shelf["contents"]?.asArray()
                    ?.lastOrNull()?.asObject()
                    ?.navigatePath(
                        "continuationItemRenderer", "continuationEndpoint",
                        "continuationCommand", "token",
                    )?.asString()
                if (contItemToken != null) return contItemToken
                // 4b: liked-songs / singleColumn shelf-level continuations array
                val shelfContToken = shelf["continuations"]?.asArray()
                    ?.firstOrNull()?.asObject()
                    ?.navigatePath("nextContinuationData", "continuation")?.asString()
                if (shelfContToken != null) return shelfContToken
            }
        }

        return null
    }

    /** Test seam — allows unit tests to call [extractContinuationToken] without reflection. */
    internal fun extractContinuationTokenForTest(response: JsonObject): String? =
        extractContinuationToken(response)

    /**
     * Parses tracks from a continuation-response shape. Handles two envelopes:
     *
     *   1) continuationContents.{musicPlaylistShelfContinuation | musicShelfContinuation}
     *      .contents[*].musicResponsiveListItemRenderer (used by liked-songs
     *      continuation responses).
     *
     *   2) onResponseReceivedActions[*].appendContinuationItemsAction
     *      .continuationItems[*].musicResponsiveListItemRenderer (used by
     *      twoColumn playlist continuation responses; the trailing
     *      continuationItemRenderer is skipped).
     *
     * Each item is the same musicResponsiveListItemRenderer that
     * [parseTrackFromRenderer] already understands.
     */
    private fun parseContinuationPage(response: JsonObject): List<YTMusicTrack> {
        val out = mutableListOf<YTMusicTrack>()

        // Shape 1: continuationContents.{musicPlaylistShelfContinuation | musicShelfContinuation}
        val cc = response["continuationContents"]?.asObject()
        if (cc != null) {
            val shelf = cc["musicPlaylistShelfContinuation"]?.asObject()
                ?: cc["musicShelfContinuation"]?.asObject()
            val items = shelf?.get("contents")?.asArray()
            if (items != null) {
                for (item in items) {
                    val renderer = item.asObject()
                        ?.get("musicResponsiveListItemRenderer")?.asObject()
                        ?: continue
                    parseTrackFromRenderer(renderer)?.let { out.add(it) }
                }
            }
        }

        // Shape 2: onResponseReceivedActions[*].appendContinuationItemsAction.continuationItems
        val actions = response["onResponseReceivedActions"]?.asArray()
        if (actions != null) {
            for (action in actions) {
                val items = action.asObject()
                    ?.get("appendContinuationItemsAction")?.asObject()
                    ?.get("continuationItems")?.asArray()
                    ?: continue
                for (item in items) {
                    val renderer = item.asObject()
                        ?.get("musicResponsiveListItemRenderer")?.asObject()
                        ?: continue  // skips continuationItemRenderer entries
                    parseTrackFromRenderer(renderer)?.let { out.add(it) }
                }
            }
        }

        return out
    }

    /** Test seam — allows unit tests to call [parseContinuationPage] without reflection. */
    internal fun parseContinuationPageForTest(response: JsonObject): List<YTMusicTrack> =
        parseContinuationPage(response)

    private val expectedCountRegex = Regex("""([\d,]+)\s+(?:songs?|tracks?|videos?)""", RegexOption.IGNORE_CASE)

    /**
     * Reads the playlist's reported track count from the response header. Used
     * by [paginateBrowse] callers to verify that pagination didn't silently
     * stop short.
     *
     * Walks two known header shapes (modern editable header, legacy detail
     * header), concatenates the secondSubtitle runs, and matches the first
     * "X songs" / "X tracks" / "X videos" pattern. Comma thousands separators
     * are stripped.
     *
     * Returns null when the header is absent (Liked Songs, library list, Home
     * Mixes) or the count can't be parsed.
     */
    private fun extractExpectedTrackCount(response: JsonObject): Int? {
        val runs = response.navigatePath(
            "header", "musicEditablePlaylistDetailHeaderRenderer", "header",
            "musicResponsiveHeaderRenderer", "secondSubtitle", "runs",
        )?.asArray()
            ?: response.navigatePath(
                "header", "musicDetailHeaderRenderer", "secondSubtitle", "runs",
            )?.asArray()
            ?: return null

        val text = runs.joinToString(separator = "") {
            it.asObject()?.get("text")?.asString() ?: ""
        }
        val match = expectedCountRegex.find(text) ?: return null
        return match.groupValues[1].replace(",", "").toIntOrNull()
    }

    internal fun extractExpectedTrackCountForTest(response: JsonObject): Int? =
        extractExpectedTrackCount(response)

    // ── Pagination ───────────────────────────────────────────────────────

    /**
     * Result of a paginated browse walk.
     *
     * @property items         Accumulated parsed items from all successful pages.
     * @property pagesFetched  Including the initial page (always >= 1).
     * @property partial       True if a continuation page failed after retries OR
     *                         the safety cap was hit.
     * @property partialReason Human-readable explanation when partial.
     */
    internal data class PaginationResult<T>(
        val items: List<T>,
        val pagesFetched: Int,
        val partial: Boolean,
        val partialReason: String?,
    )

    /**
     * Walks an InnerTube browse response's continuation chain, accumulating
     * items via [parsePage].
     *
     * Retry policy:
     *   - Transient failure (null body, HTTP 5xx, network error): retry up to
     *     2 times with [RETRY_BACKOFFS_MS] backoff, then mark partial.
     *   - Permanent failure (HTTP 4xx, esp. 401/403): no retry, mark partial.
     *   - [MAX_PAGES] reached with token still pending: stop, mark partial.
     *
     * The [parsePage] lambda is called once per page (initial + continuations).
     */
    private suspend fun <T> paginateBrowse(
        initialResponse: JsonObject,
        maxPages: Int = MAX_PAGES,
        parsePage: (JsonObject) -> List<T>,
    ): PaginationResult<T> {
        val items = mutableListOf<T>()
        items += parsePage(initialResponse)
        var token = extractContinuationToken(initialResponse)
        var pages = 1
        var partial = false
        var partialReason: String? = null

        while (token != null && pages < maxPages) {
            val (next, attempts) = browseWithRetry(token)
            if (next == null) {
                partial = true
                partialReason = "page ${pages + 1} failed after $attempts attempts"
                Log.w(TAG, "paginateBrowse: $partialReason")
                break
            }
            items += parsePage(next)
            token = extractContinuationToken(next)
            pages++
        }

        // Hitting the caller-supplied cap with a token still pending is only
        // partial when the cap is the safety MAX_PAGES. A caller-imposed cap
        // (e.g. home-mix radio capped at 1 page) is "as much as we wanted",
        // not a partial fetch.
        if (token != null && pages >= maxPages && maxPages == MAX_PAGES) {
            partial = true
            partialReason = "hit MAX_PAGES=$MAX_PAGES safety cap"
            Log.w(TAG, "paginateBrowse: $partialReason")
        }

        return PaginationResult(items, pages, partial, partialReason)
    }

    /**
     * Calls [InnerTubeClient.browseWithStatus] with retry-on-transient policy.
     * Returns (body or null, total attempt count). On 4xx, does not retry.
     */
    private suspend fun browseWithRetry(token: String): Pair<JsonObject?, Int> {
        var attempts = 0
        var outcome = innerTubeClient.browseWithStatus(token)
        attempts++
        if (outcome.body != null) return outcome.body to attempts
        if (outcome.statusCode in 400..499) return null to attempts  // permanent

        for (backoff in RETRY_BACKOFFS_MS) {
            kotlinx.coroutines.delay(backoff)
            outcome = innerTubeClient.browseWithStatus(token)
            attempts++
            if (outcome.body != null) return outcome.body to attempts
            if (outcome.statusCode in 400..499) return null to attempts  // permanent
        }
        return null to attempts
    }

    /** Test seam — allows unit tests to call [paginateBrowse] without reflection. */
    internal suspend fun <T> paginateBrowseForTest(
        initialResponse: JsonObject,
        maxPages: Int = MAX_PAGES,
        parsePage: (JsonObject) -> List<T>,
    ): PaginationResult<T> = paginateBrowse(initialResponse, maxPages, parsePage)
}

// ── JsonElement navigation extensions ────────────────────────────────────────
// Exposed as `internal` so SearchResponseParser.kt (and future parser files in
// this module) can reuse them without duplication.

/**
 * Safely navigates a chain of JSON object keys.
 *
 * Returns the [JsonElement] at the end of the path, or null if any key is missing
 * or the intermediate value is not a [JsonObject].
 */
internal fun JsonObject.navigatePath(vararg keys: String): JsonElement? {
    var current: JsonElement = this
    for (key in keys) {
        current = (current as? JsonObject)?.get(key) ?: return null
    }
    return current
}

/** Safely casts to [JsonObject], returning null on type mismatch. */
internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject

/** Safely casts to [JsonArray], returning null on type mismatch. */
internal fun JsonElement.asArray(): JsonArray? = this as? JsonArray

/**
 * If this element is a [JsonArray], returns it; otherwise returns null.
 * Useful when the navigation target might already be an array.
 */
internal fun JsonElement.firstArray(): JsonArray? = this as? JsonArray

/** Safely extracts a string primitive value, returning null on type mismatch. */
internal fun JsonElement.asString(): String? =
    try { jsonPrimitive.contentOrNull } catch (_: Exception) { null }
