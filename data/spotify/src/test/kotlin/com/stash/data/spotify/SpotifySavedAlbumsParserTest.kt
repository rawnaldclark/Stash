package com.stash.data.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [parseSavedAlbumsPage] and [parseAlbumTracksPage] against real responses: the fixtures
 * `libraryv3_saved_albums.json` (a listener's 28 saved albums, libraryV3 with the "Albums" filter)
 * and `getalbum.json` (the first of them, the web player's album query), captured on 2026-11-02
 * and sanitised (every name, id, URL, date and duration replaced; the structure and field names are
 * Spotify's). The two `page1of2`/`page2of2` files are the same 28 rows split into two pages.
 */
class SpotifySavedAlbumsParserTest {

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(
            this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().use { it.readText() },
        ).jsonObject

    // ── The saved-albums listing ─────────────────────────────────────────

    @Test fun `every saved album of the captured page is read, with what the sync needs`() {
        val page = parseSavedAlbumsPage(fixture("libraryv3_saved_albums.json"))

        assertEquals(28, page.albums.size)
        assertEquals(28, page.rawItemCount)
        assertEquals(28, page.totalCount)
        assertEquals(0, page.offset)
        assertTrue(page.isComplete)
        assertEquals("the order the page came in (selectedSortOrder)", "Recents", page.sortOrder)
        assertEquals("every listed album is read", page.listedIds, page.albums.map { it.id })
        assertEquals(28, page.listedIds.toSet().size)

        val first = page.albums.first()
        assertEquals("FakeAlb1xxxxxxxxxxxxxx", first.id)
        assertEquals("Album 1", first.name)
        assertEquals(listOf("Artist 1"), first.artists)
        assertEquals("the widest cover (640 px)", "https://i.scdn.co/fake/url3", first.coverUrl)
        assertEquals("2001-02-27T12:00:00Z", first.addedAt)
        assertEquals("ALBUM", first.albumType)
    }

    @Test fun `singles and EPs are saved albums too`() {
        val types = parseSavedAlbumsPage(fixture("libraryv3_saved_albums.json")).albums.mapNotNull { it.albumType }.toSet()

        assertEquals(setOf("ALBUM", "SINGLE", "EP"), types)
    }

    @Test fun `two pages read on from the offset and add up to the library's own count`() {
        val one = parseSavedAlbumsPage(fixture("libraryv3_saved_albums_page1of2.json"))
        val two = parseSavedAlbumsPage(fixture("libraryv3_saved_albums_page2of2.json"))
        val whole = parseSavedAlbumsPage(fixture("libraryv3_saved_albums.json"))

        assertEquals(0, one.offset)
        assertEquals(20, one.rawItemCount)
        assertEquals(20, two.offset)
        assertEquals(8, two.rawItemCount)
        assertEquals(28, one.totalCount)
        assertEquals(28, two.totalCount)
        assertEquals(whole.albums, one.albums + two.albums)
    }

    @Test fun `a pre-release and an album Spotify no longer has are listed, never read`() {
        val json = withItems(fixture("libraryv3_saved_albums.json")) { items ->
            val future = Instant.now().plusSeconds(86_400).toString()
            val past = Instant.now().minusSeconds(86_400).toString()
            listOf(
                setData(items[0]) { put("preReleaseEndDateTime", buildJsonObject { put("isoString", future) }) },
                setData(items[1]) { put("preReleaseEndDateTime", buildJsonObject { put("isoString", past) }) },
                // NotFound: the web player falls back to the wrapper's `_uri`.
                JsonObject(
                    items[2].jsonObject + ("item" to JsonObject(
                        items[2]["item"]!!.jsonObject + ("data" to buildJsonObject {
                            put("__typename", "NotFound")
                            put("message", "Entity not found")
                        }),
                    )),
                ),
            ) + items.drop(3)
        }

        val page = parseSavedAlbumsPage(json)

        assertEquals(28, page.listedIds.size)
        assertEquals("the pre-release and the NotFound row aren't read", 26, page.albums.size)
        assertFalse(page.albums.any { it.id == "FakeAlb1xxxxxxxxxxxxxx" || it.id == "FakeAlb3xxxxxxxxxxxxxx" })
        assertTrue("released since: read", page.albums.any { it.id == "FakeAlb2xxxxxxxxxxxxxx" })
        assertTrue("both are known, so the listing still counts as whole", page.isComplete)
    }

    @Test fun `an unreadable row or a response with errors is not a whole listing`() {
        val noUri = withItems(fixture("libraryv3_saved_albums.json")) { items ->
            listOf(JsonObject(mapOf("addedAt" to items[0]["addedAt"]!!))) + items.drop(1)
        }
        assertFalse(parseSavedAlbumsPage(noUri).isComplete)

        val errors = JsonObject(fixture("libraryv3_saved_albums.json") + ("errors" to JsonArray(listOf(JsonPrimitive("x")))))
        assertFalse(parseSavedAlbumsPage(errors).isComplete)
    }

    @Test fun `a response without the library is the failure sentinel`() {
        assertNull("a page is no error", libraryErrorTypename(fixture("libraryv3_saved_albums.json")))
        assertSame(SpotifySavedAlbumsPage.EMPTY, parseSavedAlbumsPage(buildJsonObject { put("data", buildJsonObject { }) }))
        val invalidSort = buildJsonObject {
            put("data", buildJsonObject {
                put("me", buildJsonObject {
                    put("libraryV3", buildJsonObject { put("__typename", "LibraryInvalidSortOrderIdError") })
                })
            })
        }
        assertSame(SpotifySavedAlbumsPage.EMPTY, parseSavedAlbumsPage(invalidSort))
        assertEquals("what the client retries in the default order on", "LibraryInvalidSortOrderIdError", libraryErrorTypename(invalidSort))
    }

    // ── An album's songs ─────────────────────────────────────────────────

    @Test fun `an album's songs come in the album's order with their details`() {
        val page = parseAlbumTracksPage(fixture("getalbum.json"))!!

        assertEquals(8, page.totalCount)
        assertEquals(8, page.rawItemCount)
        assertTrue(page.isComplete)
        assertEquals((1..8).toList(), page.tracks.map { it.trackNumber })
        assertEquals((1..8).map { "Track $it" }, page.tracks.map { it.name })
        assertEquals((1..8).map { "FakeTra${it}xxxxxxxxxxxxxx" }, page.tracks.map { it.id })
        assertTrue(page.tracks.all { it.artists == listOf("Artist 1") && it.discNumber == 1 })
        assertEquals(181_000L, page.tracks.first().durationMs)
        assertFalse(page.tracks.any { it.explicit })
    }

    @Test fun `an explicit song is marked explicit`() {
        val json = fixture("getalbum.json").edit("data", "albumUnion", "tracksV2") { tracks ->
            val items = tracks["items"] as JsonArray
            val first = items[0].jsonObject
            val track = JsonObject(first["track"]!!.jsonObject + ("contentRating" to buildJsonObject { put("label", "EXPLICIT") }))
            JsonObject(tracks + ("items" to JsonArray(listOf(JsonObject(first + ("track" to track))) + items.drop(1))))
        }

        val tracks = parseAlbumTracksPage(json)!!.tracks

        assertTrue(tracks[0].explicit)
        assertFalse(tracks[1].explicit)
    }

    @Test fun `a response without an album is null`() {
        assertNull(parseAlbumTracksPage(buildJsonObject { put("data", buildJsonObject { }) }))
        val missing = fixture("getalbum.json").edit("data") { data ->
            JsonObject(data + ("albumUnion" to buildJsonObject { put("__typename", "NotFound") }))
        }
        assertNull(parseAlbumTracksPage(missing))
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun withItems(root: JsonObject, change: (List<JsonObject>) -> List<JsonObject>): JsonObject =
        root.edit("data", "me", "libraryV3") { library ->
            JsonObject(library + ("items" to JsonArray(change((library["items"] as JsonArray).map { it.jsonObject }))))
        }

    private fun setData(item: JsonObject, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject {
        val wrapper = item["item"]!!.jsonObject
        val data = JsonObject(wrapper["data"]!!.jsonObject + buildJsonObject(extra))
        return JsonObject(item + ("item" to JsonObject(wrapper + ("data" to data))))
    }

    /** [this] with the object at [path] replaced by [change] of it. */
    private fun JsonObject.edit(vararg path: String, change: (JsonObject) -> JsonObject): JsonObject {
        if (path.isEmpty()) return change(this)
        val child = this[path[0]]!!.jsonObject
        return JsonObject(this + (path[0] to child.edit(*path.copyOfRange(1, path.size), change = change)))
    }
}
