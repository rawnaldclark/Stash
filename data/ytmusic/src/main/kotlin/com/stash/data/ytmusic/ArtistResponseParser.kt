package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonObject

/**
 * Shelf-parsing helpers for the InnerTube artist-page browse response.
 *
 * Sister file to [SearchResponseParser]. Kept separate so that
 * [YTMusicApiClient] stays under ~560 LOC even as search and artist parsing
 * each gain new shelf kinds. Per-row track extraction that's shared with
 * search lives in [ResponseParserHelpers].
 *
 * All functions are stateless top-level `internal` parsers that operate on a
 * parsed renderer [JsonObject]. They have no dependencies beyond the JSON
 * navigation extensions defined in [YTMusicApiClient] and the shared helpers
 * in [ResponseParserHelpers] / [SearchResponseParser].
 */

// ── Artist-page shelf parsers ────────────────────────────────────────────────

/**
 * Parses the artist "Popular" / "Top songs" shelf.
 *
 * The shelf ships its rows as `musicShelfRenderer.contents[*].musicResponsiveListItemRenderer`,
 * identical in shape to the search "Songs" shelf — we delegate per-row
 * extraction to [parseTrackSummaryFromListItem].
 *
 * @param shelf The parsed `musicShelfRenderer` object.
 * @return A list of [TrackSummary], or empty if the shelf has no parseable rows.
 */
internal fun parseTracksFromShelf(shelf: JsonObject): List<TrackSummary> {
    val items = shelf["contents"]?.asArray() ?: return emptyList()
    return items.mapNotNull { item ->
        item.asObject()
            ?.get("musicResponsiveListItemRenderer")?.asObject()
            ?.let { parseTrackSummaryFromListItem(it) }
    }
}

/**
 * Parses an "Albums" or "Singles and EPs" carousel on an artist page.
 *
 * The carousel ships cards as `musicCarouselShelfRenderer.contents[*].musicTwoRowItemRenderer`:
 * ```
 * {
 *   "navigationEndpoint": { "browseEndpoint": { "browseId": "MPREb_..." } },
 *   "title": { "runs": [ { "text": "Soundpieces: Da Antidote" } ] },
 *   "subtitle": { "runs": [ { "text": "Album" }, { "text": " • " }, { "text": "1999" } ] },
 *   "thumbnailRenderer": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [...] } } }
 * }
 * ```
 *
 * Unlike the search "Albums" shelf, artist-page subtitles don't usually
 * include the artist name (the user is already on that artist's page) — we
 * leave [AlbumSummary.artist] blank in that case rather than mis-pick the
 * "Album" / "Single" type label as the artist.
 *
 * @param carousel The parsed `musicCarouselShelfRenderer` object.
 * @return A list of [AlbumSummary], or empty if the carousel has no parseable cards.
 */
internal fun parseAlbumsCarousel(carousel: JsonObject): List<AlbumSummary> {
    val items = carousel["contents"]?.asArray() ?: return emptyList()
    return items.mapNotNull { item ->
        val renderer = item.asObject()
            ?.get("musicTwoRowItemRenderer")?.asObject()
            ?: return@mapNotNull null

        val id = renderer.navigatePath(
            "navigationEndpoint", "browseEndpoint", "browseId",
        )?.asString() ?: return@mapNotNull null

        val title = renderer.navigatePath("title", "runs")?.firstArray()
            ?.firstOrNull()?.asObject()?.get("text")?.asString()
            ?: return@mapNotNull null

        // Subtitle tokens on an artist page: [TypeLabel, " • ", Year] — no artist run.
        // Strip separators and the leading type label; the first non-year token (if
        // any) becomes the artist, matching the search "Albums" shelf behaviour.
        val subtitleTexts = renderer.navigatePath("subtitle", "runs")?.asArray()
            ?.mapNotNull { it.asObject()?.get("text")?.asString() }
            ?.filterNot { it == " • " || it == " & " || it == ", " || it == " x " }
            ?: emptyList()
        val dataTokens = if (
            subtitleTexts.firstOrNull()?.let { ALBUM_TYPE_LABELS.contains(it) } == true
        ) subtitleTexts.drop(1) else subtitleTexts
        val year = dataTokens.firstOrNull { it.matches(YEAR_REGEX) }
        val artist = dataTokens.firstOrNull { !it.matches(YEAR_REGEX) } ?: ""

        val thumbnails = renderer.navigatePath(
            "thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails",
        )?.firstArray()
        val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
            thumbnails?.maxByOrNull {
                it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
            }?.asObject()?.get("url")?.asString(),
        )

        AlbumSummary(
            id = id,
            title = title,
            artist = artist,
            thumbnailUrl = thumbnailUrl,
            year = year,
        )
    }
}

/**
 * Parses a "Fans also like" carousel (related artists) on an artist page.
 *
 * Same `musicCarouselShelfRenderer` shape as [parseAlbumsCarousel] but each
 * card's `browseId` is a channel ID (`UC…` or `MPLAUC…`) pointing to another
 * artist. The subtitle is a one-run "Artist" label that we ignore — we only
 * need the id, name, and avatar for the related-artists row.
 *
 * @param carousel The parsed `musicCarouselShelfRenderer` object.
 * @return A list of [ArtistSummary], or empty if the carousel has no parseable cards.
 */
internal fun parseArtistsCarousel(carousel: JsonObject): List<ArtistSummary> {
    val items = carousel["contents"]?.asArray() ?: return emptyList()
    return items.mapNotNull { item ->
        val renderer = item.asObject()
            ?.get("musicTwoRowItemRenderer")?.asObject()
            ?: return@mapNotNull null

        val id = renderer.navigatePath(
            "navigationEndpoint", "browseEndpoint", "browseId",
        )?.asString() ?: return@mapNotNull null

        val name = renderer.navigatePath("title", "runs")?.firstArray()
            ?.firstOrNull()?.asObject()?.get("text")?.asString()
            ?: return@mapNotNull null

        val thumbnails = renderer.navigatePath(
            "thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails",
        )?.firstArray()
        val avatarUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
            thumbnails?.maxByOrNull {
                it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
            }?.asObject()?.get("url")?.asString(),
        )

        ArtistSummary(id = id, name = name, avatarUrl = avatarUrl)
    }
}
