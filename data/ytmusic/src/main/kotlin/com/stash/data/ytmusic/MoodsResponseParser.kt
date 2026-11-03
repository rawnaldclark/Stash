package com.stash.data.ytmusic

import android.util.Log
import com.stash.data.ytmusic.model.AlbumSummary
import com.stash.data.ytmusic.model.MoodGrids
import com.stash.data.ytmusic.model.MoodPage
import com.stash.data.ytmusic.model.MoodPlaylist
import com.stash.data.ytmusic.model.MoodShelf
import com.stash.data.ytmusic.model.MoodTile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsers for YouTube Music's moods and genres pages: the "Moods & moments" and "Genres" grids on
 * `FEmusic_moods_and_genres`, and a tile's own page (`FEmusic_moods_and_genres_category` + the tile's params), a
 * column of carousels. A mood's carousels hold playlists; a genre's hold Songs, Featured playlists, Community
 * playlists, Music videos and Albums (live 2026-11-03, Rock and Hip-hop).
 *
 * Matched by structure, never by position: a tile is a `musicNavigationButtonRenderer` whose browse endpoint is
 * [CATEGORY_BROWSE_ID]; a song is a `musicResponsiveListItemRenderer` row; a playlist is a `musicTwoRowItemRenderer`
 * whose browse id starts with `VL`, an album one whose browse id starts with `MPREb_`. Anything else (music videos,
 * which open a watch page, not a browse) is skipped, so a new shelf or a reordered page changes nothing.
 */
internal object MoodsResponseParser {

    private const val TAG = "MoodsParser"

    /** The browse id every moods-and-genres tile opens, its params naming which. */
    const val CATEGORY_BROWSE_ID = "FEmusic_moods_and_genres_category"

    /** Both grids of the page, read once: [tiles] and [genres]. */
    fun grids(response: JsonObject): MoodGrids {
        val grids = headedGrids(response)
        return MoodGrids(moods = moods(grids), genres = genres(grids))
    }

    /**
     * The "Moods & moments" tiles, in YouTube's order. The page holds grids of identical tiles, moods and genres
     * (and, signed in, a "For you" grid above them), told apart only by their headers, which come in English (Stash
     * always asks with hl=en): the grid whose header names moods; failing that, the only grid whose header names
     * neither genres nor "for you". Empty when neither finds it (a reworded page hides the section rather than
     * showing genres as moods), and the headers it saw are logged.
     */
    fun tiles(response: JsonObject): List<MoodTile> = moods(headedGrids(response))

    /**
     * The "Genres" tiles (Pop, Rock, Hip-hop...), in YouTube's order: the grid whose header names genres. Empty when
     * none does (a reworded header hides Genres rather than guessing), and the headers it saw are logged.
     */
    fun genres(response: JsonObject): List<MoodTile> = genres(headedGrids(response))

    private fun moods(grids: List<Pair<String, List<MoodTile>>>): List<MoodTile> {
        val moods = grids.firstOrNull { (title, _) -> title.contains("mood", ignoreCase = true) }
            ?: grids.singleOrNull { (title, _) ->
                !title.contains("genre", ignoreCase = true) && !title.contains("for you", ignoreCase = true)
            }
        if (moods == null) Log.w(TAG, "no Moods & moments grid among ${grids.map { it.first }}")
        // One tile per page: the grid keys the tiles by their params
        return moods?.second.orEmpty().distinctBy { it.params }
    }

    private fun genres(grids: List<Pair<String, List<MoodTile>>>): List<MoodTile> {
        val genres = grids.firstOrNull { (title, _) -> title.contains("genre", ignoreCase = true) }
        if (genres == null) Log.w(TAG, "no Genres grid among ${grids.map { it.first }}")
        return genres?.second.orEmpty().distinctBy { it.params }
    }

    /** Every grid of tiles on the page with its header, in order; a grid with no tile in it is left out. */
    private fun headedGrids(response: JsonObject): List<Pair<String, List<MoodTile>>> =
        sections(response)?.mapNotNull { section ->
            val grid = section.asObject()?.get("gridRenderer")?.asObject() ?: return@mapNotNull null
            val title = grid.navigatePath("header", "gridHeaderRenderer", "title", "runs")
                ?.asArray()?.joinToString("") { it.asObject()?.get("text")?.asString().orEmpty() }
                .orEmpty()
            val tiles = grid["items"]?.asArray().orEmpty().mapNotNull { item ->
                item.asObject()?.get("musicNavigationButtonRenderer")?.asObject()?.let(::tile)
            }
            if (tiles.isEmpty()) null else title to tiles
        }.orEmpty()

    /**
     * A mood's or a genre's page: its title ([fallbackTitle] when the header has none) and each carousel or grid
     * that holds songs, playlists or albums, in YouTube's order. A shelf with none of them is left out (a genre's
     * Music videos); so is an item a shelf repeats.
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
            shelf(shelfTitle, items.orEmpty())
        }
        return MoodPage(title, shelves)
    }

    /**
     * One row of a page as the kind it holds most of: a genre's "Songs" row is all song rows, every other row all
     * cards. A row with no song, playlist or album on it is null.
     */
    private fun shelf(title: String, items: List<JsonElement>): MoodShelf? {
        val songIds = HashSet<String>()
        val songs = items.mapNotNull { item ->
            item.asObject()?.get("musicResponsiveListItemRenderer")?.asObject()?.let { parseTrackSummaryFromListItem(it) }
        }.filter { songIds.add(it.videoId) }
        val cards = items.mapNotNull { it.asObject()?.get("musicTwoRowItemRenderer")?.asObject() }
        val playlistIds = HashSet<String>()
        val playlists = cards.mapNotNull(::playlist).filter { playlistIds.add(it.id) }
        val albumIds = HashSet<String>()
        val albums = cards.mapNotNull(::album).filter { albumIds.add(it.id) }
        return when {
            songs.isNotEmpty() && songs.size >= playlists.size + albums.size -> MoodShelf(title, songs = songs)
            playlists.isEmpty() && albums.isEmpty() -> null
            playlists.size >= albums.size -> MoodShelf(title, playlists = playlists)
            else -> MoodShelf(title, albums = albums)
        }
    }

    private fun tile(button: JsonObject): MoodTile? {
        val endpoint = button.navigatePath("clickCommand", "browseEndpoint")?.asObject() ?: return null
        if (endpoint["browseId"]?.asString() != CATEGORY_BROWSE_ID) return null
        val params = endpoint["params"]?.asString()?.takeIf { it.isNotBlank() } ?: return null
        val title = runsText(button.navigatePath("buttonText", "runs")).takeIf { it.isNotBlank() } ?: return null
        val color = button.navigatePath("solid", "leftStripeColor")?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }
        return MoodTile(title = title, params = params, color = color)
    }

    /** The browse id a card opens: its own endpoint, else its title's. */
    private fun browseId(card: JsonObject): String? =
        card.navigatePath("navigationEndpoint", "browseEndpoint", "browseId")?.asString()
            ?: card.navigatePath("title", "runs")?.asArray()?.firstOrNull()?.asObject()
                ?.navigatePath("navigationEndpoint", "browseEndpoint", "browseId")?.asString()

    private fun playlist(card: JsonObject): MoodPlaylist? {
        val id = browseId(card) ?: return null
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

    /** A genre's album card (`MPREb_…`), read like Search's Albums shelf: it opens as a YouTube album. */
    private fun album(card: JsonObject): AlbumSummary? {
        if (browseId(card)?.startsWith("MPREb_") != true) return null
        return parseAlbumShelfCard(card)?.takeIf { it.id.startsWith("MPREb_") }
    }

    private fun sections(response: JsonObject): JsonArray? =
        response.navigatePath("contents", "singleColumnBrowseResultsRenderer", "tabs")?.asArray()
            ?.firstOrNull()?.asObject()
            ?.navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")?.asArray()

    private fun runsText(runs: kotlinx.serialization.json.JsonElement?): String =
        runs?.asArray()?.joinToString("") { it.asObject()?.get("text")?.asString().orEmpty() }.orEmpty().trim()
}
