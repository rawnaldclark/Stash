package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonArray
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
 * Mostly `internal` (a module-private contract for the `data.ytmusic` parsers).
 * The one exception is [parseListItemSubtitle], public so `:data:download`'s
 * YouTube matcher reads a row's artist and length the same way search does.
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

/** Same shape as [PLAY_COUNT_REGEX] for a video's view count ("845 views", "1.4K views"). */
internal val VIEW_COUNT_REGEX =
    Regex("""^[\d.,]+\s*[KMB]?\s*views?$""", RegexOption.IGNORE_CASE)

/** Subtitle separators between runs of one group ("A, B & C", "A x B"). */
private val SUBTITLE_JOINERS = setOf(", ", " & ", " x ")

/** Separates the subtitle's groups: artist(s) • album • length. */
private const val SUBTITLE_GROUP_SEPARATOR = " • "

/**
 * A length or play/view count: row metadata, never an artist or album name.
 * Years are deliberately NOT included — "1989" and "2112" are real album titles.
 */
private fun isRowStat(text: String): Boolean =
    text.matches(DURATION_REGEX) || text.matches(PLAY_COUNT_REGEX) || text.matches(VIEW_COUNT_REGEX)

private fun JsonObject.runText(): String? = this["text"]?.asString()

private fun JsonObject.runBrowseId(): String? =
    navigatePath("navigationEndpoint", "browseEndpoint", "browseId")?.asString()

/** A run linking to an artist or channel (UC…, MPLAUC…) or a podcast show (MPSPP…). */
private fun JsonObject.isArtistLink(): Boolean =
    runBrowseId()?.let { it.startsWith("UC") || it.startsWith("MPLAUC") || it.startsWith("MPSPP") } == true

/**
 * Leading content-type labels that flat search rows (issue #268) put before the
 * artist in a song row's subtitle ("Song • <artist>"). The titled "Songs" shelf
 * omits these, so stripping a single leading match is safe for both shapes.
 */
internal val SONG_ROW_TYPE_LABELS = setOf("Song", "Video", "Music video", "Episode")

/**
 * What a list row's subtitle (`flexColumns[1]`) says once its runs are read by
 * their browse ids rather than joined: no "Song"/"Video" label, view or play count.
 */
data class ListItemSubtitle(
    /** Artist(s) joined with ", "; blank when the subtitle names none. */
    val artist: String,
    /** The album linked in the subtitle (the search "Songs" shelf), or null. */
    val album: String?,
    /** The subtitle's unlinked length ("3:52") in seconds; 0.0 when it has none. */
    val durationSeconds: Double,
)

/**
 * Reads a list row's subtitle runs (`flexColumns[1]...runs`). Its " • " groups
 * vary by layout (live InnerTube, 2026-09-27):
 * ```
 *   flat search (#268):  "Song" • <artist>          | "Video" • <channel> • "845 views"
 *   "Songs" shelf:       <artist(s)> • <album> • "5:13"
 *   older / hand-built:  <artist> & <artist>        (length in fixedColumns)
 * ```
 * Joining every run made the artist "Grimm's VGM, 845 views". Public so the
 * download matcher (`:data:download`) reads candidates the same way search does.
 */
fun parseListItemSubtitle(runs: JsonArray?): ListItemSubtitle {
    val groups = mutableListOf(mutableListOf<JsonObject>())
    runs?.mapNotNull { it.asObject() }?.forEach { run ->
        if (run.runText() == SUBTITLE_GROUP_SEPARATOR) groups.add(mutableListOf())
        else groups.last().add(run)
    }
    // Flat rows lead with a content-type label ("Song", "Video") the shelf omits.
    if (groups.first().singleOrNull()?.runText() in SONG_ROW_TYPE_LABELS) groups.removeAt(0)

    // Artist: the whole group holding the first artist link, so an unlinked
    // co-artist in it is kept ("Yeahman, Hajna & Mina Shankha" links only two).
    // No link: the first group, unless it links elsewhere or is a stat/year.
    val artistGroup = groups.firstOrNull { group -> group.any { it.isArtistLink() } }
        ?: groups.firstOrNull()?.takeIf { group ->
            group.none { run ->
                run.runBrowseId() != null ||
                    run.runText().orEmpty().let { isRowStat(it) || it.matches(YEAR_REGEX) }
            }
        }
    val artist = artistGroup.orEmpty()
        .mapNotNull { it.runText() }
        .filterNot { it in SUBTITLE_JOINERS }
        .joinToString(", ")

    val album = groups.flatten().firstOrNull { it.runBrowseId()?.startsWith("MPREb_") == true }?.runText()

    // Only an unlinked group: a linked one is an album, even one titled like a
    // length (JAY-Z "4:44").
    val durationText = groups.firstNotNullOfOrNull { group ->
        group.singleOrNull()?.takeIf { it.runBrowseId() == null }
            ?.runText()?.takeIf { it.matches(DURATION_REGEX) }
    }
    return ListItemSubtitle(artist, album, parseDurationToSeconds(durationText))
}

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

    val subtitle = parseListItemSubtitle(
        flexColumns.getOrNull(1)?.asObject()
            ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
            ?.asArray(),
    )
    // Rows that name no artist at all (the artist "Popular" shelf, album
    // tracklists) take the caller's fallbackArtist: lossless matching scores
    // on artist + title and finds nothing with a blank artist.
    val artist = subtitle.artist.ifBlank { fallbackArtist.orEmpty() }

    // flexColumns[2] is the album on album-page tracklists but the PLAY COUNT
    // ("16M plays") on the search "Songs" shelf and artist "Popular" shelf —
    // stamping that as the album broke tap-to-album focus. Otherwise the
    // "Songs" shelf names the album in the subtitle, linked to an MPREb_ page.
    val albumColumn = flexColumns.getOrNull(2)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.runText()
        ?.takeUnless(::isRowStat)
    val album = albumColumn ?: subtitle.album

    val thumbnails = renderer.navigatePath(
        "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
    )?.firstArray()
    val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
        thumbnails?.maxByOrNull {
            it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
        }?.asObject()?.get("url")?.asString(),
    )

    // Length: the fixed column (album pages, older shapes), else the "Songs"
    // shelf's "5:13" subtitle group. Flat rows carry no length at all (0).
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
        durationSeconds = durationText?.let(::parseDurationToSeconds) ?: subtitle.durationSeconds,
        thumbnailUrl = thumbnailUrl,
    )
}
