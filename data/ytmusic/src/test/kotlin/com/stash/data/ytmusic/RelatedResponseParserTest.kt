package com.stash.data.ytmusic

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Home's "Because you like ‹song›" against real YouTube Music responses, fetched signed out with hl=en gl=US on
 * 2026-11-01 for "Never Gonna Give You Up" (lYBUbBu4W08) and trimmed (menus and tracking params dropped):
 * - `next_never_gonna.json`: `next(videoId)`, its four tabs (Up next, Lyrics, Comments, Related);
 * - `related_never_gonna.json`: `browse MPTRt_dcYZhAh5urI-1`, the Related page: "You might also like" (20 songs),
 *   "Recommended playlists", "Other performances" (3 songs), "Similar artists", Rick Astley's albums, the description.
 */
class RelatedResponseParserTest {

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(
            this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().use { it.readText() },
        ).jsonObject

    private fun tabsOf(next: JsonObject): JsonArray =
        next["contents"]!!.jsonObject["singleColumnMusicWatchNextResultsRenderer"]!!.jsonObject["tabbedRenderer"]!!
            .jsonObject["watchNextTabbedResultsRenderer"]!!.jsonObject["tabs"]!!.jsonArray

    /** [next] with its tabs replaced by [edit]'s. */
    private fun withTabs(next: JsonObject, edit: (List<kotlinx.serialization.json.JsonElement>) -> List<kotlinx.serialization.json.JsonElement>): JsonObject {
        val tabbed = JsonObject(
            next["contents"]!!.jsonObject["singleColumnMusicWatchNextResultsRenderer"]!!.jsonObject["tabbedRenderer"]!!
                .jsonObject["watchNextTabbedResultsRenderer"]!!.jsonObject + ("tabs" to JsonArray(edit(tabsOf(next)))),
        )
        return JsonObject(
            mapOf(
                "contents" to JsonObject(
                    mapOf(
                        "singleColumnMusicWatchNextResultsRenderer" to JsonObject(
                            mapOf("tabbedRenderer" to JsonObject(mapOf("watchNextTabbedResultsRenderer" to tabbed))),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `the related tab is found by its id, and today it isn't the third tab`() {
        val next = fixture("next_never_gonna.json")
        assertEquals("MPTRt_dcYZhAh5urI-1", RelatedResponseParser.relatedBrowseId(next))
        // A client reading tabs[2] (Mochy Music's YouTube.next()) gets Comments, which has no browse id at all
        val third = tabsOf(next)[2].jsonObject["tabRenderer"]!!.jsonObject
        assertNull(third["endpoint"])
        assertEquals(4, tabsOf(next).size)
    }

    @Test
    fun `wherever the related tab moves, it is found, and without it there is none`() {
        val next = fixture("next_never_gonna.json")
        assertEquals("MPTRt_dcYZhAh5urI-1", RelatedResponseParser.relatedBrowseId(withTabs(next) { it.reversed() }))
        // The Lyrics tab (MPLYt_) is a browse tab too, but never taken for Related
        val noRelated = withTabs(next) { tabs -> tabs.filterNot { "MPTRt_" in it.toString() } }
        assertNull(RelatedResponseParser.relatedBrowseId(noRelated))
        assertNull(RelatedResponseParser.relatedBrowseId(JsonObject(emptyMap())))
    }

    @Test
    fun `the related page's songs, carousel after carousel, playlists and artists passed over`() {
        val songs = RelatedResponseParser.songs(fixture("related_never_gonna.json"))
        // "You might also like" (20) then "Other performances" (3): the other carousels hold no song rows
        assertEquals(23, songs.size)
        assertEquals("Don't Dream It's Over", songs.first().title)
        assertEquals("Crowded House", songs.first().artist)
        assertEquals("Footloose (From \"Footloose\" Soundtrack)", songs[19].title)
        assertEquals("Never Gonna Give You Up (2022 Remaster)", songs[20].title)
        assertTrue(songs.all { it.musicVideoType == "ATV" && it.videoId.isNotBlank() })
        assertTrue(songs.all { !it.thumbnailUrl.isNullOrBlank() })
        assertEquals(songs.size, songs.map { it.videoId }.toSet().size)
        assertNotEquals("lYBUbBu4W08", songs.first().videoId)
    }

    @Test
    fun `getRelatedSongs makes the two calls, next then the related tab's browse`() = runTest {
        val inner = mock<InnerTubeClient>()
        runBlocking {
            whenever(inner.next(eq("lYBUbBu4W08"))).thenReturn(fixture("next_never_gonna.json"))
            whenever(inner.browse(eq("MPTRt_dcYZhAh5urI-1"), anyOrNull())).thenReturn(fixture("related_never_gonna.json"))
        }
        val songs = YTMusicApiClient(inner).getRelatedSongs("lYBUbBu4W08")
        assertEquals(23, songs?.size)
        assertEquals("Crowded House", songs?.first()?.artist)
    }

    @Test
    fun `no answer is null, a song without a related tab is empty`() = runTest {
        assertNull(YTMusicApiClient(mock()).getRelatedSongs("lYBUbBu4W08"))
        val inner = mock<InnerTubeClient>()
        val next = fixture("next_never_gonna.json")
        runBlocking {
            whenever(inner.next(eq("x"))).thenReturn(withTabs(next) { tabs -> tabs.take(2) })
        }
        assertEquals(emptyList<Any>(), YTMusicApiClient(inner).getRelatedSongs("x"))
        // The related page itself not answering is null too: the shelf keeps what it had
        val noPage = mock<InnerTubeClient>()
        runBlocking { whenever(noPage.next(eq("lYBUbBu4W08"))).thenReturn(next) }
        assertNull(YTMusicApiClient(noPage).getRelatedSongs("lYBUbBu4W08"))
    }
}
