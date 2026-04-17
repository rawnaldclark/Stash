package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.AlbumDetail
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonObject

/**
 * Parser for the InnerTube `browse(albumBrowseId)` response.
 *
 * Production album responses use `twoColumnBrowseResultsRenderer`:
 *   - `secondaryContents.sectionListRenderer.contents[]` — tracklist
 *     (`musicShelfRenderer`) and the "More by this artist" carousel
 *     (`musicCarouselShelfRenderer`).
 *   - `tabs[0].tabRenderer.content.sectionListRenderer.contents[0]
 *     .musicResponsiveHeaderRenderer` — album title / artist /
 *     year / cover art.
 *
 * Missing shelves surface as empty lists rather than throwing so the UI can
 * render partial state when a region block or a shape-drift breaks the
 * tracklist parse.
 */
internal object AlbumResponseParser {

    fun parse(browseId: String, response: JsonObject): AlbumDetail {
        val twoColumn = response.navigatePath(
            "contents", "twoColumnBrowseResultsRenderer",
        )?.asObject()

        val header = twoColumn
            ?.navigatePath("tabs")?.firstArray()?.firstOrNull()?.asObject()
            ?.navigatePath(
                "tabRenderer", "content", "sectionListRenderer", "contents",
            )?.firstArray()?.firstOrNull()?.asObject()
            ?.get("musicResponsiveHeaderRenderer")?.asObject()

        val title = header?.navigatePath("title", "runs")?.firstArray()
            ?.firstOrNull()?.asObject()?.get("text")?.asString()
            ?: "Unknown album"

        val (artist, artistId) = parseArtistFromStrapline(header)
        val year = parseYearFromSubtitle(header)

        val thumbnails = header?.navigatePath(
            "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
        )?.asArray()
            ?: header?.navigatePath(
                "thumbnail", "croppedSquareThumbnailRenderer", "thumbnail", "thumbnails",
            )?.asArray()
        val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
            thumbnails?.maxByOrNull {
                it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
            }?.asObject()?.get("url")?.asString(),
        )

        var tracks = emptyList<TrackSummary>()
        var moreByArtist = emptyList<AlbumSummary>()

        val secondarySections = twoColumn?.navigatePath(
            "secondaryContents", "sectionListRenderer", "contents",
        )?.asArray().orEmpty()
        for (section in secondarySections) {
            val obj = section.asObject() ?: continue
            obj["musicShelfRenderer"]?.asObject()?.let { shelf ->
                if (tracks.isEmpty()) tracks = parseTracksFromShelf(shelf)
            }
            obj["musicCarouselShelfRenderer"]?.asObject()?.let { carousel ->
                if (moreByArtist.isEmpty()) moreByArtist = parseAlbumsCarousel(carousel)
            }
        }

        // Fallback for older single-column layout responses (defensive).
        if (tracks.isEmpty() && moreByArtist.isEmpty()) {
            val singleColumnSections = response.navigatePath(
                "contents", "singleColumnBrowseResultsRenderer", "tabs",
            )?.firstArray()?.firstOrNull()?.asObject()
                ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")
                ?.asArray().orEmpty()
            for (section in singleColumnSections) {
                val obj = section.asObject() ?: continue
                obj["musicShelfRenderer"]?.asObject()?.let { shelf ->
                    if (tracks.isEmpty()) tracks = parseTracksFromShelf(shelf)
                }
                obj["musicCarouselShelfRenderer"]?.asObject()?.let { carousel ->
                    if (moreByArtist.isEmpty()) moreByArtist = parseAlbumsCarousel(carousel)
                }
            }
        }

        return AlbumDetail(
            id = browseId,
            title = title,
            artist = artist,
            artistId = artistId,
            thumbnailUrl = thumbnailUrl,
            year = year,
            tracks = tracks,
            moreByArtist = moreByArtist,
        )
    }

    /**
     * In `musicResponsiveHeaderRenderer`, the artist lives in
     * `straplineTextOne.runs[]`. Each run with a `browseEndpoint.browseId` that
     * starts with `UC…` or `MPLAUC…` is an artist link; join multiple collab
     * artists with ", " to match the search-top-card convention.
     */
    private fun parseArtistFromStrapline(header: JsonObject?): Pair<String, String?> {
        val runs = header?.navigatePath("straplineTextOne", "runs")?.asArray()
            ?: return "Unknown artist" to null
        val names = mutableListOf<String>()
        var id: String? = null
        for (run in runs) {
            val obj = run.asObject() ?: continue
            val text = obj["text"]?.asString() ?: continue
            val browseId = obj.navigatePath(
                "navigationEndpoint", "browseEndpoint", "browseId",
            )?.asString()
            if (browseId != null &&
                (browseId.startsWith("UC") || browseId.startsWith("MPLAUC"))
            ) {
                names.add(text)
                if (id == null) id = normalizeArtistBrowseId(browseId)
            }
        }
        val artist = if (names.isEmpty()) "Unknown artist" else names.joinToString(", ")
        return artist to id
    }

    /**
     * In `musicResponsiveHeaderRenderer`, year lives in `subtitle.runs[]` — a
     * run whose text matches [YEAR_REGEX]. Typical shape: "Album • 2005".
     */
    private fun parseYearFromSubtitle(header: JsonObject?): String? {
        val runs = header?.navigatePath("subtitle", "runs")?.asArray() ?: return null
        return runs.mapNotNull { it.asObject()?.get("text")?.asString() }
            .firstOrNull { it.matches(YEAR_REGEX) }
    }
}
