package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.ArtistSummary
import com.stash.data.ytmusic.model.TopResultItem
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonObject

/**
 * Shelf-parsing helpers for the InnerTube Search tab response.
 *
 * Extracted from [YTMusicApiClient] so that the artist-profile parsers (Task 2)
 * can grow in [data/ytmusic] without pushing [YTMusicApiClient] past ~1000 LOC.
 *
 * All functions in this file are stateless top-level parsers that operate on a
 * parsed renderer [JsonObject] and emit search DTOs. They share file-scope
 * constants with each other but have no other dependencies beyond the JSON
 * navigation extensions defined in [YTMusicApiClient.kt].
 */

// ── Internal constants shared between the search parsers ─────────────────────

/** Matches duration strings like "3:45" or "1:02:30". */
internal val DURATION_REGEX = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")

/** Matches a 4-digit year token (e.g. "1999"). */
internal val YEAR_REGEX = Regex("""\d{4}""")

/** Album-type labels that may lead an album-card subtitle in YouTube Music. */
internal val ALBUM_TYPE_LABELS = setOf("Album", "Single", "EP", "Mixtape", "Compilation")

/**
 * Parses a duration string like "3:45" or "1:02:30" into seconds.
 *
 * Uses the same parsing rules as [YTMusicApiClient]'s private
 * parseDurationToMs, but returns seconds as a Double so it slots directly into
 * [TrackSummary.durationSeconds]. Returns 0.0 for null input or malformed
 * strings — search shelves tolerate missing durations.
 */
internal fun parseDurationToSeconds(duration: String?): Double {
    if (duration == null) return 0.0
    val parts = duration.split(":").mapNotNull { it.toIntOrNull() }
    return when (parts.size) {
        2 -> (parts[0] * 60 + parts[1]).toDouble()
        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toDouble()
        else -> 0.0
    }
}

// ── Shelf parsers ────────────────────────────────────────────────────────────

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
 * For track top-cards, subtitle runs are classified by each run's
 * `navigationEndpoint.browseEndpoint.browseId` prefix:
 * - `UC…` / `MPLAUC…` → artist run (multiple are joined with ", ")
 * - `MPREb_…` → album run (first wins)
 * - no endpoint + matches [DURATION_REGEX] → duration token
 *
 * This classification runs BEFORE separator / kind-label filtering so that
 * collab artists sitting next to each other in the run list aren't silently
 * relabeled as the album name.
 *
 * @param shelf The parsed `musicCardShelfRenderer` object.
 * @return A [TopResultItem], or null if the card is neither artist nor track.
 */
internal fun parseTopResultCard(shelf: JsonObject): TopResultItem? {
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
        // Subtitle runs typically look like:
        //   [{text:"Song"}, {text:" • "}, {text:"Rick Astley", nav→UC…},
        //    {text:" & "}, {text:"John Smith", nav→UC…}, {text:" • "},
        //    {text:"Whenever You Need Somebody", nav→MPREb_…}, {text:" • "},
        //    {text:"3:32"}]
        val subtitleRuns = shelf.navigatePath("subtitle", "runs")?.asArray()
            ?.mapNotNull { it.asObject() }
            ?: emptyList()
        val artistNames = mutableListOf<String>()
        var album: String? = null
        var durationToken: String? = null
        for (run in subtitleRuns) {
            val text = run["text"]?.asString() ?: continue
            // Skip separators and the leading kind label ("Song").
            if (text == " • " || text == " & " || text == ", " || text == " x " || text == "Song") {
                continue
            }
            val browseId = run.navigatePath(
                "navigationEndpoint", "browseEndpoint", "browseId",
            )?.asString()
            when {
                browseId != null && (browseId.startsWith("UC") || browseId.startsWith("MPLAUC")) ->
                    artistNames.add(text)
                browseId != null && browseId.startsWith("MPREb_") ->
                    if (album == null) album = text
                text.matches(DURATION_REGEX) ->
                    durationToken = text
            }
        }
        val artist = artistNames.joinToString(", ")
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
 * Delegates per-row extraction to [parseTrackSummaryFromListItem] in
 * [ResponseParserHelpers] so the artist-profile "Popular" shelf parser in
 * [ArtistResponseParser] can share the column-walking logic.
 */
internal fun parseSongsShelf(shelfRenderer: JsonObject): List<TrackSummary> {
    val items = shelfRenderer["contents"]?.asArray() ?: return emptyList()
    return items.mapNotNull { item ->
        item.asObject()
            ?.get("musicResponsiveListItemRenderer")?.asObject()
            ?.let { parseTrackSummaryFromListItem(it) }
    }
}

/**
 * Parses the "Artists" shelf — `musicResponsiveListItemRenderer` items
 * where the item's top-level `navigationEndpoint.browseEndpoint.browseId`
 * is the artist channel ID.
 */
internal fun parseArtistsShelf(shelfRenderer: JsonObject): List<ArtistSummary> {
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
 * leading type label, then treat the first 4-digit token as the year and
 * the first non-year token as the artist.
 */
internal fun parseAlbumsShelf(shelfRenderer: JsonObject): List<AlbumSummary> {
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
        // Use the regex directly — comparing against the nullable `year` value
        // mis-picks when only a year token exists and when no token matches.
        val artist = dataTokens.firstOrNull { !it.matches(YEAR_REGEX) } ?: ""

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
