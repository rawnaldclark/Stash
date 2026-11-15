package com.stash.data.ytmusic

import com.stash.core.common.ArtUrlUpgrader
import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One page of a YouTube Music radio station: the songs it offers and the token
 * that asks for the next page.
 *
 * A station lives on the `next` endpoint, not `browse`: the request names the seed
 * video plus a playlist id of `RDAMVM<videoId>`, and the answer carries a
 * `playlistPanelRenderer` — the player's queue panel — where a normal watch page
 * carries an up-next row. [continuation] null means the station has ended, which for
 * `RD…` ids is rare but must be handled rather than read as an error.
 */
data class RadioQueuePage(
    val tracks: List<TrackSummary>,
    val continuation: String?,
)

/**
 * Reads the queue panel out of a `next` answer.
 *
 * Two shapes, both measured on InnerTube WEB_REMIX:
 * - the first page: `contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer
 *   .watchNextTabbedResultsRenderer.tabs[0].tabRenderer.content.musicQueueRenderer
 *   .content.playlistPanelRenderer`
 * - every page after it: `continuationContents.playlistPanelContinuation`, from the
 *   same request with only a `continuation` in the body.
 *
 * Rows arrive as `playlistPanelVideoRenderer`, sometimes wrapped once in
 * `playlistPanelVideoWrapperRenderer.primaryRenderer` (a song whose video counterpart
 * is offered alongside). A row that doesn't parse is skipped rather than failing the
 * page: a station that plays 24 of 25 songs is a working station, and one odd renderer
 * must never end one.
 */
internal object RadioQueueParser {

    fun page(root: JsonObject): RadioQueuePage {
        val panel = queuePanel(root)
        val items = panel?.get("contents")?.asArray()
        val tracks = items.orEmptyRows().mapNotNull(::row)
        return RadioQueuePage(
            tracks = tracks.distinctBy { it.videoId },
            continuation = continuationOf(panel, items),
        )
    }

    /** The panel holding this page's rows: the first page's queue, or a continuation's. */
    private fun queuePanel(root: JsonObject): JsonObject? =
        root.navigatePath(
            "contents",
            "singleColumnMusicWatchNextResultsRenderer",
            "tabbedRenderer",
            "watchNextTabbedResultsRenderer",
            "tabs",
        )?.asArray()?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer")
            ?.asObject()
            ?: root.navigatePath("continuationContents", "playlistPanelContinuation")?.asObject()

    /**
     * The station's next-page token. A radio's own key is `nextRadioContinuationData`;
     * `nextContinuationData` is what a station saved to the library answers with; the
     * reload key is a stale-token retry that still advances it. The last resort walks the
     * rows, because some answers carry the token only in a row's `continuationCommand`.
     */
    private fun continuationOf(panel: JsonObject?, items: JsonArray?): String? =
        panel?.get("continuations")?.asArray()?.firstNotNullOfOrNull { element ->
            val continuations = element.asObject() ?: return@firstNotNullOfOrNull null
            CONTINUATION_KEYS.firstNotNullOfOrNull { key ->
                continuations.navigatePath(key, "continuation")?.asString()?.takeIf { it.isNotBlank() }
            }
        }
            ?: items.orEmptyRows().firstNotNullOfOrNull { it.continuationToken() }

    private fun row(item: JsonObject): TrackSummary? {
        val renderer = item["playlistPanelVideoRenderer"]?.asObject()
            ?: item.navigatePath(
                "playlistPanelVideoWrapperRenderer",
                "primaryRenderer",
                "playlistPanelVideoRenderer",
            )?.asObject()
            ?: return null

        val videoId = renderer["videoId"]?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val title = renderer.navigatePath("title", "runs")?.asArray()
            ?.firstOrNull()?.asObject()?.get("text")?.asString()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        // The byline reads "‹artist› • ‹album› • ‹length›" with the artist linked to its
        // channel — the same shape a list row's subtitle has, so a station names artists
        // and albums through the very parser a playlist's rows do.
        val byline = parseListItemSubtitle(renderer.navigatePath("longBylineText", "runs")?.asArray())
        val artist = byline.artist.takeIf { it.isNotBlank() }
            ?: parseListItemSubtitle(renderer.navigatePath("shortBylineText", "runs")?.asArray()).artist

        val lengthText = renderer.navigatePath("lengthText", "runs")?.asArray()
            ?.firstOrNull()?.asObject()?.get("text")?.asString()

        val thumbnails = renderer.navigatePath("thumbnail", "thumbnails")?.asArray()
        val thumbnailUrl = ArtUrlUpgrader.upgrade(
            thumbnails?.maxByOrNull { it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0 }
                ?.asObject()?.get("url")?.asString(),
        )

        return TrackSummary(
            videoId = videoId,
            title = title,
            artist = artist,
            album = byline.album,
            durationSeconds = byline.durationSeconds.takeIf { it > 0.0 }
                ?: parseDurationToSeconds(lengthText),
            thumbnailUrl = thumbnailUrl,
            explicit = explicitFromBadge(renderer),
            musicVideoType = renderer.navigatePath("navigationEndpoint")?.asObject()?.watchMusicVideoType(),
        )
    }

    private val CONTINUATION_KEYS = listOf(
        "nextRadioContinuationData",
        "nextContinuationData",
        "reloadContinuationData",
    )

    /** The first `continuationCommand.token` under this element, if any. */
    private fun JsonElement.continuationToken(): String? {
        val obj = asObject() ?: return null
        obj.navigatePath("continuationCommand", "token")?.asString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        for ((_, value) in obj) {
            value.continuationToken()?.let { return it }
            value.asArray()?.forEach { child -> child.continuationToken()?.let { return it } }
        }
        return null
    }

    private fun JsonArray?.orEmptyRows(): List<JsonObject> = this?.mapNotNull { it.asObject() }.orEmpty()
}
