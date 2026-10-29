package com.stash.data.ytmusic

import com.stash.core.common.ArtUrlUpgrader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A playlist's page header: its name, who made it, its cover. */
data class YTPlaylistHeader(val title: String, val author: String?, val thumbnailUrl: String?)

/** One song as its `next` (watch) response shows it, for a pasted `watch?v=` / `youtu.be` link. */
data class YTSong(
    val videoId: String,
    val title: String,
    val artist: String,
    /** Its album, when the byline names one (an official track: "Artist • Album • 2011"); a video's byline has none. */
    val album: String?,
    val durationMs: Long?,
    val thumbnailUrl: String?,
)

/** Reads what a pasted YouTube link needs from browse and `next` responses. */
internal object LinkResponseParser {

    /** A playlist page's `musicResponsiveHeaderRenderer` (or the editable one, the listener's own playlists). */
    fun playlistHeader(response: JsonObject): YTPlaylistHeader? {
        val sections = response.navigatePath(
            "contents", "twoColumnBrowseResultsRenderer", "tabs",
        )?.asArray()?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")?.asArray().orEmpty()
        val header = sections.firstNotNullOfOrNull { s ->
            val o = s.asObject() ?: return@firstNotNullOfOrNull null
            o["musicResponsiveHeaderRenderer"]?.asObject()
                ?: o.navigatePath("musicEditablePlaylistDetailHeaderRenderer", "header", "musicResponsiveHeaderRenderer")?.asObject()
        } ?: return null
        val title = runsText(header.navigatePath("title", "runs"))?.takeIf { it.isNotBlank() } ?: return null
        val author = header.navigatePath("facepile", "avatarStackViewModel", "text", "content")?.asString()
            ?: runsText(header.navigatePath("straplineTextOne", "runs"))
        return YTPlaylistHeader(title.trim(), author?.trim()?.takeIf { it.isNotEmpty() }, largestThumbnail(header))
    }

    /**
     * The album an album's own playlist (`OLAK5uy_…`) is of: the first `MPREb_` browse id its songs link to (every song of it
     * names the same album), or null.
     */
    fun albumBrowseId(response: JsonElement): String? {
        val stack = ArrayDeque<JsonElement>().apply { add(response) }
        while (stack.isNotEmpty()) {
            when (val node = stack.removeFirst()) {
                is JsonObject -> {
                    node["browseId"]?.asString()?.takeIf { it.startsWith(ALBUM_PREFIX) }?.let { return it }
                    stack.addAll(node.values)
                }
                is JsonArray -> stack.addAll(node)
                else -> Unit
            }
        }
        return null
    }

    /** The song a `next` response plays first (its queue's first `playlistPanelVideoRenderer`), or null. */
    fun song(response: JsonObject): YTSong? {
        val tabs = response.navigatePath(
            "contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer", "watchNextTabbedResultsRenderer", "tabs",
        )?.asArray() ?: return null
        val queue = tabs.firstOrNull()?.asObject()?.navigatePath(
            "tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer", "contents",
        )?.asArray() ?: return null
        val r = queue.firstNotNullOfOrNull { it.asObject()?.get("playlistPanelVideoRenderer")?.asObject() } ?: return null
        val videoId = r["videoId"]?.asString() ?: return null
        val title = runsText(r.navigatePath("title", "runs"))?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // "Artist • Album • 2011" for a track, "Artist • 1.8B views • 19M likes" for a video: the runs before the first dot
        // are the artists; the album is the run that opens an album page.
        val runs = r.navigatePath("longBylineText", "runs")?.asArray().orEmpty().mapNotNull { it.asObject() }
        val artist = runs.takeWhile { it["text"]?.asString()?.trim() != DOT }
            .joinToString("") { it["text"]?.asString().orEmpty() }.trim().takeIf { it.isNotEmpty() } ?: return null
        val album = runs.firstOrNull {
            it.navigatePath("navigationEndpoint", "browseEndpoint", "browseId")?.asString()?.startsWith(ALBUM_PREFIX) == true
        }?.get("text")?.asString()
        val length = runsText(r.navigatePath("lengthText", "runs"))
        return YTSong(videoId, title, artist, album, durationMs(length), largestThumbnail(r))
    }

    /** "3:34" or "1:02:03" in milliseconds. */
    fun durationMs(text: String?): Long? {
        val parts = text?.trim()?.split(':')?.map { it.toLongOrNull() ?: return null } ?: return null
        if (parts.isEmpty() || parts.size > 3) return null
        return parts.fold(0L) { acc, p -> acc * 60 + p } * 1000
    }

    private fun runsText(runs: JsonElement?): String? =
        runs?.asArray()?.joinToString("") { it.asObject()?.get("text")?.asString().orEmpty() }

    private fun largestThumbnail(o: JsonObject): String? {
        val thumbnails = o.navigatePath("thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails")?.asArray()
            ?: o.navigatePath("thumbnail", "thumbnails")?.asArray()
            ?: return null
        val url = thumbnails.maxByOrNull { it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0 }
            ?.asObject()?.get("url")?.asString()
        return ArtUrlUpgrader.upgrade(url)
    }

    private const val ALBUM_PREFIX = "MPREb_"
    private const val DOT = "•"
}
