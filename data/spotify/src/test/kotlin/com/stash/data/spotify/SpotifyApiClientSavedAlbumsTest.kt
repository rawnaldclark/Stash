package com.stash.data.spotify

import com.stash.core.auth.TokenManager
import com.stash.core.auth.spotify.SpotifyAuthManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two reads behind Spotify saved-album sync, over the captured (sanitised) responses: the
 * library listing asks for albums, and an album's songs are read page by page until Spotify's own
 * count is met. Both are reads: nothing on the listener's account is saved, removed or followed.
 */
class SpotifyApiClientSavedAlbumsTest {

    /** Response per (operation, offset); anything unlisted answers HTTP 500. */
    private val pages = mutableMapOf<Pair<String, Int>, Pair<Int, String>>()
    private val requests = mutableListOf<Pair<String, String>>()

    private val tokenManager = mockk<TokenManager> {
        coEvery { getSpotifyAccessToken() } returns "access-token"
        coEvery { getSpotifyClientId() } returns "client-id"
        coEvery { forceRefreshSpotifyAccessToken() } returns null
    }

    private val authManager = mockk<SpotifyAuthManager> {
        every { getClientVersion() } returns "1.2.3.4.g0"
        coEvery { getClientToken("client-id") } returns "client-token"
        every { scrapePersistedQueryHash(any(), any()) } returns null
    }

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val url = chain.request().url
        val operation = url.queryParameter("operationName").orEmpty()
        val variables = url.queryParameter("variables").orEmpty()
        requests += operation to variables
        val offset = Regex(""""offset":(\d+)""").find(variables)!!.groupValues[1].toInt()
        val (code, body) = pages[operation to offset] ?: (500 to """{"error":"boom"}""")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    private val client = SpotifyApiClient(http, tokenManager, authManager)

    private fun fixture(name: String): String =
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().use { it.readText() }

    /** The captured album page cut to songs [from] until [to], Spotify still counting all 8. */
    private fun albumPage(from: Int, to: Int): String {
        val root = Json.parseToJsonElement(fixture("getalbum.json")).jsonObject
        val data = root["data"]!!.jsonObject
        val album = data["albumUnion"]!!.jsonObject
        val tracks = album["tracksV2"]!!.jsonObject
        val cut = JsonObject(tracks + ("items" to JsonArray((tracks["items"] as JsonArray).subList(from, to))) + ("totalCount" to JsonPrimitive(8)))
        return JsonObject(root + ("data" to JsonObject(data + ("albumUnion" to JsonObject(album + ("tracksV2" to cut)))))).toString()
    }

    @Test fun `the library listing asks for albums and reads the page`() = runBlocking {
        pages["libraryV3" to 0] = 200 to fixture("libraryv3_saved_albums.json")

        val page = client.getSavedAlbumsPage(limit = 50, offset = 0)

        assertEquals(28, page.albums.size)
        val (operation, variables) = requests.single()
        assertEquals("libraryV3", operation)
        assertTrue(variables, variables.contains(""""filters":["Albums"]"""))
    }

    @Test fun `a failed listing is the failure sentinel, never an empty library`() = runBlocking {
        assertSame(SpotifySavedAlbumsPage.EMPTY, client.getSavedAlbumsPage(limit = 50, offset = 0))
    }

    @Test fun `an album's songs are read page by page until Spotify's count is met`() = runBlocking {
        pages["getAlbum" to 0] = 200 to albumPage(0, 5)
        pages["getAlbum" to 5] = 200 to albumPage(5, 8)

        val list = client.getAlbumTracks("FakeAlb1xxxxxxxxxxxxxx")!!

        assertTrue(list.complete)
        assertEquals(8, list.totalCount)
        assertEquals((1..8).toList(), list.tracks.map { it.trackNumber })
        assertTrue(requests.first().second.contains(""""uri":"spotify:album:FakeAlb1xxxxxxxxxxxxxx""""))
    }

    @Test fun `a later page failing leaves the album partial, the first failing reads nothing`() = runBlocking {
        pages["getAlbum" to 0] = 200 to albumPage(0, 5)

        val partial = client.getAlbumTracks("FakeAlb1xxxxxxxxxxxxxx")!!
        assertFalse(partial.complete)
        assertEquals(5, partial.tracks.size)

        pages.clear()
        assertNull(client.getAlbumTracks("FakeAlb1xxxxxxxxxxxxxx"))
    }

    @Test fun `the whole album in one page is complete`() = runBlocking {
        pages["getAlbum" to 0] = 200 to fixture("getalbum.json")

        val list = client.getAlbumTracks("FakeAlb1xxxxxxxxxxxxxx")!!

        assertTrue(list.complete)
        assertEquals(8, list.tracks.size)
        assertEquals("one request", 1, requests.size)
    }

    @Test fun `only reads go to Spotify`() = runBlocking {
        pages["libraryV3" to 0] = 200 to fixture("libraryv3_saved_albums.json")
        pages["getAlbum" to 0] = 200 to fixture("getalbum.json")

        client.getSavedAlbumsPage(limit = 50, offset = 0)
        client.getAlbumTracks("FakeAlb1xxxxxxxxxxxxxx")

        assertEquals(setOf("libraryV3", "getAlbum"), requests.map { it.first }.toSet())
    }
}
