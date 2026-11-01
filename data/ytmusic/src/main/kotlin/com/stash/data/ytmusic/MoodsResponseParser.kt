package com.stash.data.ytmusic

import android.util.Log
import com.stash.data.ytmusic.model.MoodPage
import com.stash.data.ytmusic.model.MoodPlaylist
import com.stash.data.ytmusic.model.MoodShelf
import com.stash.data.ytmusic.model.MoodTile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsers for YouTube Music's moods pages: the "Moods & moments" grid on `FEmusic_moods_and_genres` and a mood's
 * own page (`FEmusic_moods_and_genres_category` + the tile's params), a column of carousels of playlists.
 *
 * Matched by structure, never by position: a tile is a `musicNavigationButtonRenderer` whose browse endpoint is
 * [CATEGORY_BROWSE_ID]; a playlist is a `musicTwoRowItemRenderer` whose browse id starts with `VL`. Shelf kinds
 * and anything else on the page are skipped, so a new shelf or a reordered page changes nothing.
 */
internal object MoodsResponseParser {

    private const val TAG = "MoodsParser"

    /** The browse id every moods-and-genres tile opens, its params naming which. */
    const val CATEGORY_BROWSE_ID = "FEmusic_moods_and_genres_category"

    /**
     * The "Moods & moments" tiles, in YouTube's order. The page holds grids of identical tiles, moods and genres
     * (and, signed in, a "For you" grid above them), told apart only by their headers, which come in English (Stash
     * always asks with hl=en): the grid whose header names moods; failing that, the only grid whose header names
     * neither genres nor "for you". Empty when neither finds it (a reworded page hides the shelf rather than
     * showing genres as moods), and the headers it saw are logged.
     */
    fun tiles(response: JsonObject): List<MoodTile> {
        val grids = sections(response)?.mapNotNull { section ->
            val grid = section.asObject()?.get("gridRenderer")?.asObject() ?: return@mapNotNull null
            val title = grid.navigatePath("header", "gridHeaderRenderer", "title", "runs")
                ?.asArray()?.joinToString("") { it.asObject()?.get("text")?.asString().orEmpty() }
                .orEmpty()
            val tiles = grid["items"]?.asArray().orEmpty().mapNotNull { item ->
                item.asObject()?.get("musicNavigationButtonRenderer")?.asObject()?.let(::tile)
            }
            if (tiles.isEmpty()) null else title to tiles
        }.orEmpty()
        val moods = grids.firstOrNull { (title, _) -> title.contains("mood", ignoreCase = true) }
            ?: grids.singleOrNull { (title, _) ->
                !title.contains("genre", ignoreCase = true) && !title.contains("for you", ignoreCase = true)
            }
        if (moods == null) Log.w(TAG, "no Moods & moments grid among ${grids.map { it.first }}")
        // One tile per page: Home keys the tiles by their params
        return moods?.second.orEmpty().distinctBy { it.params }
    }

    /**
     * A mood's page: its title ([fallbackTitle] when the header has none) and each carousel or grid that holds
     * playlists, in YouTube's order. A shelf with no playlist on it is left out; so is a playlist a shelf repeats.
     */
    fun page(response: JsonObject, fallbackTitle: String): MoodPage {
        val title = response.navigatePath("header", "musicHeaderRenderer", "title", "runs")
            ?.asArray()?.joinToString("") { it.asObject()?.get("text")?.asString().orEmpty() }
            ?.takeIf { it.isNotBlank() }
            ?: fallbackTitle
        val shelves = sections(response).orEmpty().mapNotNull { section ->
            val obj = section.asObject() ?: return@mapNotNull null
            val carousel = obj["musicCarouselShelfRenderer"]?.asObject()
            val grid = obj["gridRenderer"]?.asObject()
            val (shelfTitle, items) = when {
                carousel != null -> runsText(carousel.navigatePath("header", "musicCarouselShelfBasicHeaderRenderer", "title", "runs")) to
                    carousel["contents"]?.asArray()
                grid != null -> runsText(grid.navigatePath("header", "gridHeaderRenderer", "title", "runs")) to grid["items"]?.asArray()
                else -> return@mapNotNull null
            }
            val seen = HashSet<String>()
            val playlists = items.orEmpty().mapNotNull { item ->
                item.asObject()?.get("musicTwoRowItemRenderer")?.asObject()?.let(::playlist)
            }.filter { seen.add(it.id) }
            if (playlists.isEmpty()) null else MoodShelf(shelfTitle, playlists)
        }
        return MoodPage(title, shelves)
    }

    private fun tile(button: JsonObject): MoodTile? {
        val endpoint = button.navigatePath("clickCommand", "browseEndpoint")?.asObject() ?: return null
        if (endpoint["browseId"]?.asString() != CATEGORY_BROWSE_ID) return null
        val params = endpoint["params"]?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val title = runsText(button.navigatePath("buttonText", "runs")).takeIf { it.isNotBlank() } ?: return null
        val color = button.navigatePath("solid", "leftStripeColor")?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
        return MoodTile(title = title, params = params, color = color)
    }

    private fun playlist(card: JsonObject): MoodPlaylist? {
        val id = card.navigatePath("navigationEndpoint", "browseEndpoint", "browseId")?.asString()
            ?: card.navigatePath("title", "runs")?.asArray()?.firstOrNull()?.asObject()
                ?.navigatePath("navigationEndpoint", "browseEndpoint", "browseId")?.asString()
            ?: return null
        if (!id.startsWith("VL")) return null
        val title = runsText(card.navigatePath("title", "runs")).takeIf { it.isNotBlank() } ?: return null
        val subtitle = card.navigatePath("subtitle", "runs")?.asArray().orEmpty()
            .mapNotNull { it.asObject()?.get("text")?.asString()?.trim() }
            // "Playlist • YouTube Music": the kind is the same on every card here, the rest says who made it
            .filterNot { it.isEmpty() || it == "•" || it.equals("Playlist", ignoreCase = true) }
            .joinToString(" • ")
        val thumbnail = card.navigatePath("thumbnailRenderer", "musicThumbnailRenderer", "thumbnail", "thumbnails")
            ?.asArray()
            ?.maxByOrNull { it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0 }
            ?.asObject()?.get("url")?.asString()
        return MoodPlaylist(id = id, title = title, subtitle = subtitle, thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(thumbnail))
    }

    private fun sections(response: JsonObject): JsonArray? =
        response.navigatePath("contents", "singleColumnBrowseResultsRenderer", "tabs")?.asArray()
            ?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")?.asArray()

    private fun runsText(runs: kotlinx.serialization.json.JsonElement?): String =
        runs?.asArray()?.joinToString("") { it.asObject()?.get("text")?.asString().orEmpty() }.orEmpty().trim()
}
