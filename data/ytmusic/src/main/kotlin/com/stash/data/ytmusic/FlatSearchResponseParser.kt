package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Parser for the "flat" InnerTube search response shape.
 *
 * The titled `musicShelfRenderer` shape ("Songs"/"Artists"/"Albums") that
 * [parseSongsShelf] / [parseArtistsShelf] / [parseAlbumsShelf] expect is only
 * served to authenticated YouTube-Music clients in supported regions. In other
 * cases — unauthenticated, or a region where the request is treated as plain
 * YouTube — InnerTube returns a FLAT list instead: `sectionListRenderer.contents`
 * holds a run of `itemSectionRenderer` wrappers, each carrying a single
 * `musicResponsiveListItemRenderer` row, with no titled shelves at all.
 *
 * The legacy parser finds no `musicShelfRenderer` in that shape and yields zero
 * sections, so the Search screen shows "No results found" even though the
 * response is full of real songs/artists/albums (issue #268). This parser
 * recovers those rows: it classifies each `musicResponsiveListItemRenderer` by
 * its navigation target — a videoId → song, `MUSIC_PAGE_TYPE_ARTIST` → artist,
 * `MUSIC_PAGE_TYPE_ALBUM` → album — and groups them into the same
 * [SearchResultSection]s the UI already renders.
 */

private const val PAGE_TYPE_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
private const val PAGE_TYPE_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"

/** Max song rows to surface, matching the titled-"Songs"-shelf cap in searchAll. */
private const val FLAT_SONGS_CAP = 4

/**
 * Walks the flat `itemSectionRenderer` rows of a search response and groups them
 * into Songs / Artists / Albums sections. Returns an empty list when [shelves]
 * carries no recognisable flat rows (e.g. it was the titled-shelf shape, already
 * handled by the caller).
 */
internal fun parseFlatSearchSections(shelves: JsonArray): List<SearchResultSection> {
    val songs = mutableListOf<TrackSummary>()
    val artists = mutableListOf<ArtistSummary>()
    val albums = mutableListOf<AlbumSummary>()

    for (shelf in shelves) {
        val rows = shelf.asObject()
            ?.get("itemSectionRenderer")?.asObject()
            ?.get("contents")?.asArray()
            ?: continue
        for (row in rows) {
            val renderer = row.asObject()
                ?.get("musicResponsiveListItemRenderer")?.asObject()
                ?: continue

            // A row that resolves to a videoId is a song, regardless of any
            // browse endpoint it may also carry.
            val track = parseTrackSummaryFromListItem(renderer)
            if (track != null) {
                songs.add(track)
                continue
            }

            val pageType = renderer.navigatePath(
                "navigationEndpoint", "browseEndpoint",
                "browseEndpointContextSupportedConfigs",
                "browseEndpointContextMusicConfig", "pageType",
            )?.asString()
            when (pageType) {
                PAGE_TYPE_ARTIST -> parseArtistFromListItem(renderer)?.let { artists.add(it) }
                PAGE_TYPE_ALBUM -> parseAlbumFromListItem(renderer)?.let { albums.add(it) }
            }
        }
    }

    return buildList {
        if (songs.isNotEmpty()) add(SearchResultSection.Songs(songs.take(FLAT_SONGS_CAP)))
        if (artists.isNotEmpty()) add(SearchResultSection.Artists(artists))
        if (albums.isNotEmpty()) add(SearchResultSection.Albums(albums))
    }
}

/** Parses a flat `musicResponsiveListItemRenderer` artist row into an [ArtistSummary]. */
internal fun parseArtistFromListItem(renderer: JsonObject): ArtistSummary? {
    val id = renderer.navigatePath(
        "navigationEndpoint", "browseEndpoint", "browseId",
    )?.asString() ?: return null
    val name = renderer["flexColumns"]?.asArray()
        ?.getOrNull(0)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()
        ?: return null
    val thumbnails = renderer.navigatePath(
        "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
    )?.firstArray()
    val avatarUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
        thumbnails?.maxByOrNull {
            it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
        }?.asObject()?.get("url")?.asString()
    )
    return ArtistSummary(id = id, name = name, avatarUrl = avatarUrl)
}

/**
 * Parses a flat `musicResponsiveListItemRenderer` album row into an
 * [AlbumSummary]. Unlike the titled "Albums" shelf (which ships
 * `musicTwoRowItemRenderer` cards), flat album rows are list items whose
 * subtitle reads like "Album • <artist> • <year>".
 */
internal fun parseAlbumFromListItem(renderer: JsonObject): AlbumSummary? {
    val id = renderer.navigatePath(
        "navigationEndpoint", "browseEndpoint", "browseId",
    )?.asString() ?: return null
    val flexColumns = renderer["flexColumns"]?.asArray() ?: return null
    val title = flexColumns.getOrNull(0)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()
        ?: return null

    val subtitleTexts = flexColumns.getOrNull(1)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.asArray()
        ?.mapNotNull { it.asObject()?.get("text")?.asString() }
        ?.filterNot { it == " • " || it == " & " || it == ", " || it == " x " }
        ?: emptyList()
    // Drop the leading type label ("Album"/"EP"/"Single") if present.
    val dataTokens = if (
        subtitleTexts.firstOrNull()?.let { ALBUM_TYPE_LABELS.contains(it) } == true
    ) subtitleTexts.drop(1) else subtitleTexts
    val year = dataTokens.firstOrNull { it.matches(YEAR_REGEX) }
    val artist = dataTokens.firstOrNull { !it.matches(YEAR_REGEX) } ?: ""

    val thumbnails = renderer.navigatePath(
        "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
    )?.firstArray()
    val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
        thumbnails?.maxByOrNull {
            it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
        }?.asObject()?.get("url")?.asString()
    )

    return AlbumSummary(
        id = id,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        year = year,
    )
}
