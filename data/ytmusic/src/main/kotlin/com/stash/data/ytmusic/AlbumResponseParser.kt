package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonObject

/**
 * Parser for the InnerTube `browse(albumBrowseId)` response.
 *
 * Sister file to [SearchResponseParser] and [ArtistResponseParser]; operates on
 * a parsed [JsonObject] and emits an [AlbumDetail]. Shares the JSON navigation
 * extensions in [YTMusicApiClient.kt] and the per-row / per-carousel helpers in
 * [ResponseParserHelpers.kt] / [ArtistResponseParser.kt].
 *
 * An album browse response differs from an artist browse response in two ways:
 *   - The header lives under `musicDetailHeaderRenderer` (not
 *     `musicImmersiveHeaderRenderer`).
 *   - The first shelf under the section list is always a `musicShelfRenderer`
 *     (the tracklist), optionally followed by one `musicCarouselShelfRenderer`
 *     (the "More by this artist" row).
 *
 * Missing shelves surface as empty lists rather than throwing so the UI can
 * render partial state (hero + "No tracks available" message) when a region
 * block or a shape-drift breaks the tracklist parse.
 */
internal object AlbumResponseParser {

    /**
     * Parses the InnerTube browse response for an album into an [AlbumDetail].
     *
     * @param browseId The album browse ID the caller passed to [YTMusicApiClient.getAlbum]
     *   — pinned onto the returned [AlbumDetail.id] since the InnerTube response
     *   itself doesn't include it in an easy-to-reach spot.
     * @param response The parsed `browse(…)` response body.
     */
    fun parse(browseId: String, response: JsonObject): AlbumDetail {
        val header = response["header"]?.asObject()
            ?.get("musicDetailHeaderRenderer")?.asObject()

        val title = header?.navigatePath("title", "runs")?.firstArray()
            ?.firstOrNull()?.asObject()?.get("text")?.asString()
            ?: "Unknown album"

        val subtitle = parseSubtitle(header)

        // Album header typically uses `croppedSquareThumbnailRenderer` but
        // some responses ship `musicThumbnailRenderer` instead — try both so
        // the hero never paints with a blank cover.
        val thumbnails = header?.navigatePath(
            "thumbnail", "croppedSquareThumbnailRenderer", "thumbnail", "thumbnails",
        )?.firstArray()
            ?: header?.navigatePath(
                "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
            )?.firstArray()
        val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
            thumbnails?.maxByOrNull {
                it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
            }?.asObject()?.get("url")?.asString(),
        )

        val sections = response.navigatePath(
            "contents", "singleColumnBrowseResultsRenderer", "tabs",
        )?.firstArray()?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
            ?.asArray()
            .orEmpty()

        var tracks = emptyList<TrackSummary>()
        var moreByArtist = emptyList<AlbumSummary>()

        for (section in sections) {
            val obj = section.asObject() ?: continue
            obj["musicShelfRenderer"]?.asObject()?.let { shelf ->
                // First track shelf wins — album browse only ships one.
                if (tracks.isEmpty()) {
                    tracks = parseTracksFromShelf(shelf)
                }
            }
            obj["musicCarouselShelfRenderer"]?.asObject()?.let { carousel ->
                // First carousel wins — "More by this artist" is the only one
                // album pages ship. Reuse the artist-page carousel parser since
                // the `musicTwoRowItemRenderer` shape is identical.
                if (moreByArtist.isEmpty()) {
                    moreByArtist = parseAlbumsCarousel(carousel)
                }
            }
        }

        return AlbumDetail(
            id = browseId,
            title = title,
            artist = subtitle.artist,
            artistId = subtitle.artistId,
            thumbnailUrl = thumbnailUrl,
            year = subtitle.year,
            tracks = tracks,
            moreByArtist = moreByArtist,
        )
    }

    /**
     * Intermediate holder for the three fields the subtitle runs contribute.
     *
     * Extracted so [parseSubtitle] can return all three at once instead of
     * walking the run list three times.
     */
    private data class Subtitle(
        val artist: String,
        val artistId: String?,
        val year: String?,
    )

    /**
     * Parses the album header's subtitle runs.
     *
     * InnerTube ships the subtitle as an interleaved array like:
     * ```
     * [{text:"Album"}, {text:" • "}, {text:"John Frusciante", nav→UC…},
     *  {text:" • "}, {text:"2005"}]
     * ```
     * We iterate each run and classify by `navigationEndpoint.browseEndpoint.browseId`:
     *   - `UC…` / `MPLAUC…` → artist run (name + id captured).
     *   - Runs whose text matches [YEAR_REGEX] → year token.
     *
     * Multiple artist runs (collabs) are joined with ", " per the search-top-card
     * convention, so the artist display matches what [SearchResponseParser] emits
     * for the same album-card surface.
     */
    private fun parseSubtitle(header: JsonObject?): Subtitle {
        val runs = header?.navigatePath("subtitle", "runs")?.asArray()
            ?: return Subtitle("Unknown artist", null, null)

        val artistNames = mutableListOf<String>()
        var artistId: String? = null
        var year: String? = null

        for (run in runs) {
            val obj = run.asObject() ?: continue
            val text = obj["text"]?.asString() ?: continue
            val browseId = obj.navigatePath(
                "navigationEndpoint", "browseEndpoint", "browseId",
            )?.asString()
            when {
                browseId != null &&
                    (browseId.startsWith("UC") || browseId.startsWith("MPLAUC")) -> {
                    artistNames.add(text)
                    if (artistId == null) {
                        artistId = normalizeArtistBrowseId(browseId)
                    }
                }
                text.matches(YEAR_REGEX) -> year = text
            }
        }

        val artist = if (artistNames.isEmpty()) "Unknown artist" else artistNames.joinToString(", ")
        return Subtitle(artist = artist, artistId = artistId, year = year)
    }
}
