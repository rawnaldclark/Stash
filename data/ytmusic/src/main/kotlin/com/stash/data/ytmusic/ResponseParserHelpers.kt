package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonObject

/**
 * Cross-file renderer-parsing helpers shared by [SearchResponseParser] and
 * [ArtistResponseParser].
 *
 * The search "Songs" shelf and the artist "Popular" shelf both ship their rows
 * as `musicResponsiveListItemRenderer` objects with identical flex/fixed column
 * semantics — extracting a single helper avoids copy-pasting ~40 lines of
 * column-walking code into two places.
 *
 * Kept `internal` so it's a module-private contract: parser files in
 * `data.ytmusic` may use it; no other module should need it.
 */

/**
 * Spec §8 Open Question 1: InnerTube returns artists with either `UC…`
 * (channel) or `MPLAUC…` (music channel) browseIds. Cache-key stability
 * requires a single form — we strip the `MPLA` prefix only when it is
 * immediately followed by `UC`, leaving other unknown `MPLA`-prefixed ids
 * (e.g. `MPLARZ…`) untouched to avoid truncating forms we don't recognize.
 *
 * Top-level `internal` so parser tests can exercise it directly.
 */
internal fun normalizeArtistBrowseId(browseId: String): String =
    if (browseId.startsWith("MPLAUC")) browseId.removePrefix("MPLA") else browseId

/**
 * Matches a YouTube play-count string ("16M plays", "1.2B plays", "1,234 plays",
 * "1 play") so it's never mistaken for an album title. A compact/grouped number,
 * an optional K/M/B magnitude suffix, then "play"/"plays". Real album names don't
 * take this shape (e.g. "Child's Play" has word text before "Play", so it's safe).
 */
internal val PLAY_COUNT_REGEX =
    Regex("""^[\d.,]+\s*[KMB]?\s*plays?$""", RegexOption.IGNORE_CASE)

/**
 * Leading content-type labels that flat search rows (issue #268) put before the
 * artist in a song row's subtitle ("Song • <artist>"). The titled "Songs" shelf
 * omits these, so stripping a single leading match is safe for both shapes.
 */
internal val SONG_ROW_TYPE_LABELS = setOf("Song", "Video", "Music video", "Episode")

/**
 * Parses a `musicResponsiveListItemRenderer` into a [TrackSummary].
 *
 * Expected shape:
 * ```
 * {
 *   "playlistItemData": { "videoId": "..." },     // or overlay fallback
 *   "flexColumns": [                              // [title, artists, album?]
 *     { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [...] } } },
 *     ...
 *   ],
 *   "fixedColumns": [                             // [duration?]
 *     { "musicResponsiveListItemFixedColumnRenderer": { "text": { "runs": [...] } } }
 *   ],
 *   "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [...] } } }
 * }
 * ```
 *
 * Returns null when a required field (videoId, title) is missing so callers
 * can `mapNotNull` over a shelf's items without filtering separately.
 *
 * @param renderer The parsed `musicResponsiveListItemRenderer` object.
 * @return A [TrackSummary], or null if the row is malformed.
 */
internal fun parseTrackSummaryFromListItem(
    renderer: JsonObject,
    fallbackArtist: String? = null,
): TrackSummary? {
    val videoId = renderer["playlistItemData"]?.asObject()
        ?.get("videoId")?.asString()
        ?: renderer.navigatePath(
            "overlay", "musicItemThumbnailOverlayRenderer", "content",
            "musicPlayButtonRenderer", "playNavigationEndpoint",
            "watchEndpoint", "videoId",
        )?.asString()
        ?: return null

    val flexColumns = renderer["flexColumns"]?.asArray() ?: return null
    val title = flexColumns.getOrNull(0)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()
        ?: return null

    // Album-page tracklists omit the artist column from flexColumns (the
    // artist is shown once in the album header). The result is an empty
    // artist string here, which breaks downstream lossless matching
    // (Qobuz/Kennyy score on artist+title and can't find candidates
    // without an artist). The caller — typically AlbumResponseParser —
    // passes the album header's artist as fallbackArtist so per-row
    // artists default to that when the shelf row carries none.
    val artistTexts = flexColumns.getOrNull(1)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.asArray()
        ?.mapNotNull { it.asObject()?.get("text")?.asString() }
        ?.filterNot { it == " & " || it == ", " || it == " x " || it == " • " }
        ?: emptyList()
    // Flat search rows (issue #268) prefix the subtitle with a content-type
    // label — "Song • <artist>" — that the titled "Songs" shelf omits. Drop a
    // single leading label so it doesn't pollute the artist string; harmless
    // for legacy rows, whose first token is already the artist.
    val cleanedArtistTexts =
        if (artistTexts.firstOrNull() in SONG_ROW_TYPE_LABELS) artistTexts.drop(1) else artistTexts
    val artist = cleanedArtistTexts.joinToString(", ").ifBlank { fallbackArtist.orEmpty() }

    // flexColumns[2] is the album ONLY on album-page tracklists (a different
    // parser). This helper's callers are the search "Songs" shelf and the artist
    // "Popular" shelf, where flexColumns[2] is the PLAY COUNT ("16M plays"), not
    // the album — YouTube Music search song rows carry no album at all. Guard
    // against stamping the play count as the album (which polluted track.album and
    // broke tap-to-album focus); keep a real album if a future layout ever supplies one.
    val albumRaw = flexColumns.getOrNull(2)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()
    val album = albumRaw?.takeUnless { it.matches(PLAY_COUNT_REGEX) }

    val thumbnails = renderer.navigatePath(
        "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
    )?.firstArray()
    val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
        thumbnails?.maxByOrNull {
            it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
        }?.asObject()?.get("url")?.asString(),
    )

    val durationText = renderer["fixedColumns"]?.asArray()
        ?.firstOrNull()?.asObject()
        ?.navigatePath("musicResponsiveListItemFixedColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()

    return TrackSummary(
        videoId = videoId,
        title = title,
        artist = artist,
        album = album,
        durationSeconds = parseDurationToSeconds(durationText),
        thumbnailUrl = thumbnailUrl,
    )
}
