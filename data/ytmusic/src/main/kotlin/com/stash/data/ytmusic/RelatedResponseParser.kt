package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonObject

/**
 * Parsers for a song's "Related" page (YouTube Music's "You might also like"), Home's "Because you like ‹song›".
 *
 * The page is reached in two calls: `next(videoId)` lists the watch page's tabs, and the Related tab's browse id
 * (`MPTRt_…`) opens it. The tab is found by that id's prefix, never by its position: on 2026-11-01 a "Comments"
 * tab sat at index 2 and Related moved to 3, which silently emptied every client that read `tabs[2]` (Mochy Music's
 * `YouTube.next()` among them). Its songs are the `musicResponsiveListItemRenderer` rows of its carousels, in
 * order; the other carousels (playlists, artists) have none and are passed over.
 */
internal object RelatedResponseParser {

    /** The prefix of a Related page's browse id. */
    const val RELATED_PREFIX = "MPTRt_"

    /** The Related page's browse id among a `next` response's tabs, or null when it has none. */
    fun relatedBrowseId(next: JsonObject): String? =
        tabIds(next).firstOrNull { it.startsWith(RELATED_PREFIX) }

    /**
     * Each tab of a `next` response as its browse id, or `"(none)"` for a tab without one (Up next, Comments): what
     * the log names when no tab is Related, so a moved or renamed tab shows up in a shared log.
     */
    fun tabIds(next: JsonObject): List<String> =
        next.navigatePath(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
            "watchNextTabbedResultsRenderer", "tabs",
        )?.asArray().orEmpty().map { tab ->
            tab.asObject()?.navigatePath("tabRenderer", "endpoint", "browseEndpoint", "browseId")?.asString() ?: "(none)"
        }

    /** Every song row on a Related page, carousel after carousel, each once (by videoId), in YouTube's order. */
    fun songs(related: JsonObject): List<TrackSummary> {
        val sections = related.navigatePath("contents", "sectionListRenderer", "contents")?.asArray() ?: return emptyList()
        val seen = HashSet<String>()
        return sections.flatMap { section ->
            section.asObject()?.navigatePath("musicCarouselShelfRenderer", "contents")?.asArray().orEmpty().mapNotNull { item ->
                item.asObject()?.get("musicResponsiveListItemRenderer")?.asObject()?.let { parseTrackSummaryFromListItem(it) }
            }
        }.filter { seen.add(it.videoId) }
    }
}
