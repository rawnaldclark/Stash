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
internal fun parseTracksFromShelf(
    shelf: JsonObject,
    fallbackArtist: String? = null,
): List<TrackSummary> {
    val items = shelf["contents"]?.asArray() ?: return emptyList()
    return items.mapNotNull { item ->
        item.asObject()
            ?.get("musicResponsiveListItemRenderer")?.asObject()
            ?.let { parseTrackSummaryFromListItem(it, fallbackArtist = fallbackArtist) }
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
        item.asObject()
            ?.get("musicTwoRowItemRenderer")?.asObject()
            ?.let { parseAlbumCard(it) }
    }
}

/**
 * Card-level parser for `musicTwoRowItemRenderer` album/single cards.
 * Shared between the inline carousel ([parseAlbumsCarousel]) and the
 * "View all" grid response ([parseAlbumsGridResponse]) — both surfaces
 * use the same card shape, so the logic lives once here.
 */
private fun parseAlbumCard(renderer: JsonObject): AlbumSummary? {
    val id = renderer.navigatePath(
        "navigationEndpoint", "browseEndpoint", "browseId",
    )?.asString() ?: return null

    val title = renderer.navigatePath("title", "runs")?.firstArray()
        ?.firstOrNull()?.asObject()?.get("text")?.asString()
        ?: return null

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

    return AlbumSummary(
        id = id,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        year = year,
        // Kept for the artist page's Latest releases row, which mixes albums with
        // singles and EPs and so has to say which each card is ("2021 • EP").
        releaseType = subtitleTexts.firstOrNull()?.takeIf { it in ALBUM_TYPE_LABELS },
    )
}

/**
 * Extracts the "View all" / "More" browseId from a carousel header if
 * present. YT Music attaches one when the artist has more items than
 * fit on the main artist page (typically when the discography exceeds
 * the ~10-card carousel cap). Following the browseId returns a
 * dedicated browse page with the full grid of cards.
 *
 * The header has slightly different shapes depending on which client
 * variant InnerTube A/B-ships; we check the two known paths.
 *
 * Returns null when there is no "more" link (small discography that
 * already fits in the carousel).
 */
internal data class CarouselMore(val browseId: String, val params: String?)

internal fun parseCarouselMore(carousel: JsonObject): CarouselMore? {
    val header = carousel.navigatePath(
        "header", "musicCarouselShelfBasicHeaderRenderer",
    )?.asObject() ?: return null
    // The "View all" button's browseEndpoint carries BOTH a browseId and a
    // `params` token. The params scope the discography grid to this shelf's
    // release type (Albums vs Singles/EPs) — the artist's albums and singles
    // "View all" links often share ONE browseId and differ ONLY by params.
    // Dropping params returns the artist's FULL, unfiltered discography, which
    // dumps singles/EPs into the album grid (and vice-versa). So capture both.
    val endpoint = header.navigatePath(
        "moreContentButton", "buttonRenderer",
        "navigationEndpoint", "browseEndpoint",
    )?.asObject()
        ?: header.navigatePath("endIcons")?.firstArray()
            ?.firstOrNull()?.asObject()
            ?.navigatePath(
                "buttonRenderer",
                "navigationEndpoint", "browseEndpoint",
            )?.asObject()
        ?: return null
    val browseId = endpoint.get("browseId")?.asString() ?: return null
    return CarouselMore(browseId, endpoint.get("params")?.asString())
}

/**
 * Parses the response returned by the carousel's "View all" browseId.
 *
 * Two shapes show up in the wild:
 *   1. A `gridRenderer` with `items[*].musicTwoRowItemRenderer` cards
 *      — same shape as the inline carousel cards, just unbounded.
 *   2. A `musicShelfRenderer` with list-style rows (older clients).
 *
 * We only parse (1) for now since (2) is rarer and serves the same
 * data through a different surface. Falling through to empty signals
 * the caller to keep the original carousel result.
 */
internal fun parseAlbumsGridResponse(response: JsonObject): List<AlbumSummary> {
    val gridContents = response.navigatePath(
        "contents", "singleColumnBrowseResultsRenderer", "tabs",
    )?.firstArray()?.firstOrNull()?.asObject()
        ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.navigatePath("gridRenderer", "items")
        ?.asArray()
        ?: return emptyList()

    return gridContents.mapNotNull { item ->
        item.asObject()
            ?.get("musicTwoRowItemRenderer")?.asObject()
            ?.let { parseAlbumCard(it) }
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
