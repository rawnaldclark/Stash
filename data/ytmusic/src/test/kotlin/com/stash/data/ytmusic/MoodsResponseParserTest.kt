package com.stash.data.ytmusic

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

/**
 * Home's "Moods & moments" against real YouTube Music responses, fetched signed out with hl=en gl=US on 2026-11-01
 * and trimmed (menus and tracking params dropped):
 * - `moods_and_genres.json`: `browse FEmusic_moods_and_genres`, both grids whole (13 moods, 27 genres);
 * - `mood_chill.json`: the Chill tile's page (`FEmusic_moods_and_genres_category`), its first 4 rows of 3 playlists;
 * - `playlist_mood.json`: one of those playlists (`VLRDCLAK5uy_nBE4…`, "Coffee Shop Blend"), its first 5 songs.
 * The page cap is read against sync's `playlist_long_page1.json` / `playlist_long_page2.json`.
 */
class MoodsResponseParserTest {

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(
            this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().use { it.readText() },
        ).jsonObject

    @Test
    fun `the moods grid, not the genres grid, in YouTube's order with its colours`() {
        val tiles = MoodsResponseParser.tiles(fixture("moods_and_genres.json"))
        assertEquals(13, tiles.size)
        assertEquals(listOf("Chill", "Commute", "Diwali", "Energize", "Feel good"), tiles.take(5).map { it.title })
        assertEquals("Workout", tiles.last().title)
        assertEquals("ggMPOg1uX1JOQWZFeDByc2Jm", tiles.first().params)
        assertEquals(4288988671L, tiles.first().color)
        // Not one genre among them
        assertTrue(tiles.none { it.title in setOf("Pop", "Rock", "Hip-hop", "Jazz", "African") })
    }

    @Test
    fun `the grids are told apart by header, never by position`() {
        val response = fixture("moods_and_genres.json")
        val swapped = withSections(response) { it.reversed() }
        assertEquals(MoodsResponseParser.tiles(response), MoodsResponseParser.tiles(swapped))
    }

    @Test
    fun `a reworded moods header still finds the grid that isn't genres, and genres alone are never shown as moods`() {
        val response = fixture("moods_and_genres.json")
        val reworded = withSections(response) { sections ->
            sections.map { s -> if (headerOf(s) == "Moods & moments") retitled(s, "Moments") else s }
        }
        assertEquals(13, MoodsResponseParser.tiles(reworded).size)
        val genresOnly = withSections(response) { sections -> sections.filter { headerOf(it) == "Genres" } }
        assertTrue(MoodsResponseParser.tiles(genresOnly).isEmpty())
        assertTrue(MoodsResponseParser.tiles(JsonObject(emptyMap())).isEmpty())
    }

    @Test
    fun `signed in, a For you grid above them is never taken for moods, reworded header or not`() {
        val response = fixture("moods_and_genres.json")
        val moods = MoodsResponseParser.tiles(response)
        // Signed in, the page opens with a third grid of the same tiles; here a copy of the genres, retitled
        fun signedIn(moodsHeader: String) = withSections(response) { sections ->
            val forYou = retitled(sections.first { headerOf(it) == "Genres" }, "For you")
            listOf(forYou) + sections.map { s -> if (headerOf(s) == "Moods & moments") retitled(s, moodsHeader) else s }
        }
        assertEquals(moods, MoodsResponseParser.tiles(signedIn("Moods & moments")))
        // Three grids, the moods one reworded: still the one that is neither genres nor For you
        assertEquals(moods, MoodsResponseParser.tiles(signedIn("Moments")))
        // For you and genres alone: no moods, rather than either of them
        val noMoods = withSections(signedIn("Moments")) { sections -> sections.filter { headerOf(it) != "Moments" } }
        assertTrue(MoodsResponseParser.tiles(noMoods).isEmpty())
    }

    @Test
    fun `a mood's page is its rows of playlists, each opening a VL playlist`() {
        val page = MoodsResponseParser.page(fixture("mood_chill.json"), fallbackTitle = "Tile")
        assertEquals("Chill", page.title)
        assertEquals(listOf("Coffee shop blends", "Unwind + explore", "Mindfulness & meditation", "Chilled"), page.shelves.map { it.title })
        val first = page.shelves.first().playlists.first()
        assertEquals("VLRDCLAK5uy_nBE4bLuBHUXWZrF59ZrkPEToKt8M_I3Vc", first.id)
        assertEquals("Coffee Shop Blend", first.title)
        assertEquals("YouTube Music", first.subtitle)
        assertTrue(first.thumbnailUrl!!.startsWith("https://"))
        assertTrue(page.shelves.all { shelf -> shelf.playlists.size == 3 && shelf.playlists.all { it.id.startsWith("VL") } })
    }

    @Test
    fun `a page without a header keeps the tile's name, and a page without playlists has no rows`() {
        val page = MoodsResponseParser.page(JsonObject(emptyMap()), fallbackTitle = "Chill")
        assertEquals("Chill", page.title)
        assertTrue(page.shelves.isEmpty())
    }

    @Test
    fun `the client reads the tiles and a mood's page with one browse each`() = runTest {
        val inner = mock<InnerTubeClient>()
        runBlocking {
            whenever(inner.browse(eq("FEmusic_moods_and_genres"), anyOrNull())).thenReturn(fixture("moods_and_genres.json"))
            whenever(inner.browse(eq("FEmusic_moods_and_genres_category"), eq("ggMPOg1uX1JOQWZFeDByc2Jm"))).thenReturn(fixture("mood_chill.json"))
        }
        val client = YTMusicApiClient(inner)
        val tiles = client.getMoodTiles()!!
        val page = client.getMoodPage(tiles.first().params, tiles.first().title)!!
        assertEquals("Chill", page.title)
        verifyBlocking(inner, times(2)) { browse(any(), anyOrNull()) }
        // No answer is null (keep what's saved), never an empty list (an answer without the grid)
        assertNull(YTMusicApiClient(mock()).getMoodTiles())
        assertNull(YTMusicApiClient(mock()).getMoodPage("p", "t"))
    }

    @Test
    fun `a mood's playlist opens as an album page - its header and songs, each with its own artist`() = runTest {
        val inner = mock<InnerTubeClient>()
        runBlocking {
            whenever(inner.browse(eq("VLRDCLAK5uy_nBE4bLuBHUXWZrF59ZrkPEToKt8M_I3Vc"), anyOrNull())).thenReturn(fixture("playlist_mood.json"))
        }
        val detail = YTMusicApiClient(inner).getPlaylistAlbum("VLRDCLAK5uy_nBE4bLuBHUXWZrF59ZrkPEToKt8M_I3Vc")
        assertEquals("Coffee Shop Blend", detail.title)
        assertEquals("YouTube Music", detail.artist)
        assertTrue(detail.thumbnailUrl!!.startsWith("https://"))
        assertEquals(listOf("H-zVEnHB3_g", "HedhexHvLZs", "LKroEZ9vVyw", "pNVo56WV5Nw", "jXpUsKqVDtw"), detail.tracks.map { it.videoId })
        assertEquals("Your Other Life", detail.tracks.first().title)
        assertEquals("Lord Huron", detail.tracks.first().artist)
        assertTrue(detail.tracks.all { it.durationSeconds > 0 })
        // No answer is the blank, song-less playlist AlbumCache treats as a failed load
        assertTrue(YTMusicApiClient(mock()).getPlaylistAlbum("PLx").tracks.isEmpty())
    }

    @Test
    fun `a radio-style playlist is its first page only, though the page offers more`() = runTest {
        // The saved page was trimmed to 5 songs, its "more" token with them: put one back, as YouTube sends it
        val page = withShelfToken(fixture("playlist_mood.json"), "NEXT_PAGE")
        val radio = mock<InnerTubeClient>()
        runBlocking {
            whenever(radio.browse(eq("VLRDCLAK5uy_nBE4bLuBHUXWZrF59ZrkPEToKt8M_I3Vc"), anyOrNull())).thenReturn(page)
            whenever(radio.browseWithStatus(any())).thenReturn(InnerTubeClient.RequestOutcome(LAST_PAGE, 200))
        }
        assertEquals(5, YTMusicApiClient(radio).getPlaylistAlbum("VLRDCLAK5uy_nBE4bLuBHUXWZrF59ZrkPEToKt8M_I3Vc").tracks.size)
        verifyBlocking(radio, times(1)) { browse(any(), anyOrNull()) }
        verifyBlocking(radio, never()) { browseWithStatus(any()) }
        // The same page as an ordinary playlist follows the token: the radio rule, not a missing token, stopped it
        val ordinary = mock<InnerTubeClient>()
        runBlocking {
            whenever(ordinary.browse(eq("VLPLmood"), anyOrNull())).thenReturn(page)
            whenever(ordinary.browseWithStatus(any())).thenReturn(InnerTubeClient.RequestOutcome(LAST_PAGE, 200))
        }
        YTMusicApiClient(ordinary).getPlaylistAlbum("PLmood")
        verifyBlocking(ordinary, times(1)) { browseWithStatus(eq("NEXT_PAGE")) }
    }

    @Test
    fun `a playlist reads on for 3 pages at most`() = runTest {
        // A real 3,000-song playlist: page 1 (the browse) and page 2 (a continuation), which ends in a token too
        val page1 = fixture("playlist_long_page1.json")
        val page2 = fixture("playlist_long_page2.json")
        val alone = mock<InnerTubeClient>()
        runBlocking {
            whenever(alone.browse(eq("VLPLlong"), anyOrNull())).thenReturn(page1)
            // A refused continuation (a 4xx is never retried): the first page alone
            whenever(alone.browseWithStatus(any())).thenReturn(InnerTubeClient.RequestOutcome(null, 404))
        }
        val firstPage = YTMusicApiClient(alone).getPlaylistAlbum("PLlong").tracks.size
        assertTrue(firstPage > 0)
        val inner = mock<InnerTubeClient>()
        runBlocking {
            whenever(inner.browse(eq("VLPLlong"), anyOrNull())).thenReturn(page1)
            // Every continuation answers with a page that offers another: a playlist with no end
            whenever(inner.browseWithStatus(any())).thenReturn(InnerTubeClient.RequestOutcome(page2, 200))
        }
        val client = YTMusicApiClient(inner)
        val perPage = client.parseContinuationPageForTest(page2).size
        assertTrue(perPage > 0)
        val detail = client.getPlaylistAlbum("PLlong")
        assertEquals(firstPage + 2 * perPage, detail.tracks.size)
        verifyBlocking(inner, times(2)) { browseWithStatus(any()) }
        assertEquals("VLPLlong", detail.id)
    }

    // ── Helpers that rework a real response ────────────────────────────

    /** A continuation that answers with no songs and no token: the end of a playlist. */
    private val LAST_PAGE: JsonObject = Json.parseToJsonElement(
        """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[]}}]}""",
    ).jsonObject

    /** [response], a playlist's first page, with [token] as its shelf's last item: the way YouTube offers more. */
    private fun withShelfToken(response: JsonObject, token: String): JsonObject {
        val more = Json.parseToJsonElement(
            """{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"$token"}}}}""",
        )
        return response.edit(
            listOf("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer", "contents", 0, "musicPlaylistShelfRenderer", "contents"),
        ) { JsonArray(it.jsonArray + more) }.jsonObject
    }

    /** This element with the one at [path] (object keys and array indexes) replaced by [change] of it. */
    private fun JsonElement.edit(path: List<Any>, change: (JsonElement) -> JsonElement): JsonElement =
        if (path.isEmpty()) {
            change(this)
        } else {
            when (val key = path.first()) {
                is String -> JsonObject(jsonObject + (key to jsonObject.getValue(key).edit(path.drop(1), change)))
                is Int -> JsonArray(jsonArray.mapIndexed { i, e -> if (i == key) e.edit(path.drop(1), change) else e })
                else -> error("a path is keys and indexes, not $key")
            }
        }

    private fun sectionsOf(response: JsonObject): JsonArray =
        response.navigatePath("contents", "singleColumnBrowseResultsRenderer", "tabs")!!.asArray()!!.first().asObject()!!
            .navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")!!.asArray()!!

    private fun withSections(response: JsonObject, change: (List<JsonObject>) -> List<JsonObject>): JsonObject {
        val sections = JsonArray(change(sectionsOf(response).map { it.asObject()!! }))
        val tab = response.navigatePath("contents", "singleColumnBrowseResultsRenderer", "tabs")!!.asArray()!!.first().asObject()!!
        val tabRenderer = tab["tabRenderer"]!!.asObject()!!
        val content = tabRenderer["content"]!!.asObject()!!
        val list = content["sectionListRenderer"]!!.asObject()!!
        val newTab = JsonObject(
            tab + ("tabRenderer" to JsonObject(tabRenderer + ("content" to JsonObject(content + ("sectionListRenderer" to JsonObject(list + ("contents" to sections))))))),
        )
        val single = response["contents"]!!.asObject()!!["singleColumnBrowseResultsRenderer"]!!.asObject()!!
        return JsonObject(
            response + ("contents" to JsonObject(mapOf("singleColumnBrowseResultsRenderer" to JsonObject(single + ("tabs" to JsonArray(listOf(newTab))))))),
        )
    }

    private fun headerOf(section: JsonObject): String? =
        section.navigatePath("gridRenderer", "header", "gridHeaderRenderer", "title", "runs")?.asArray()
            ?.firstOrNull()?.asObject()?.get("text")?.asString()

    private fun retitled(section: JsonObject, title: String): JsonObject {
        val grid = section["gridRenderer"]!!.asObject()!!
        val run = JsonObject(mapOf("text" to JsonPrimitive(title)))
        val titleRuns = JsonObject(mapOf("runs" to JsonArray(listOf(run))))
        val header = JsonObject(mapOf("gridHeaderRenderer" to JsonObject(mapOf("title" to titleRuns))))
        return JsonObject(mapOf("gridRenderer" to JsonObject(grid + ("header" to header))))
    }
}
