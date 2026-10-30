package com.stash.data.soundcloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SoundCloudPlaylistClientTest {
    private fun client(handler: (Request) -> Pair<Int, String>) = SoundCloudClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            val (code, body) = when (req.url.host) {
                "soundcloud.com" -> 200 to "<script src=\"https://a-v2.sndcdn.com/app.js\"></script>"
                "a-v2.sndcdn.com" -> 200 to "client_id:\"${"a".repeat(32)}\""
                else -> handler(req)
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(code).body(body.toResponseBody()).build()
        }.build(), pause = {},
    )
    private fun playlist(id: Int, owner: Int = 7) = """{"id":$id,"kind":"playlist","title":"Set $id","user_id":$owner,"permalink_url":"https://soundcloud.com/artist/sets/set-$id"}"""
    private fun mix(user: Int = 7) = """{"id":"soundcloud:system-playlists:your-moods:$user:1","kind":"system-playlist","title":"Your Mix 1","made_for":{"id":$user},"permalink_url":"https://soundcloud.com/discover/sets/your-moods:$user:1"}"""
    private fun track(id: Int) = """{"id":$id,"title":"Track $id","permalink_url":"https://soundcloud.com/artist/track-$id","user":{"username":"Artist"},"duration":180000}"""

    @Test fun `owned playlists follow paging and exclude other owners`() = runTest {
        val c = client { request ->
            assertEquals("OAuth secret", request.header("Authorization"))
            200 to if (request.url.queryParameter("cursor") == null)
                """{"collection":[${playlist(1)},${playlist(2, 8)}],"next_href":"https://api-v2.soundcloud.com/users/7/playlists?cursor=next"}"""
            else """{"collection":[${playlist(3)}]}"""
        }
        assertEquals(listOf("1", "3"), c.ownPlaylists("7", "secret").map { it.id })
    }

    @Test fun `failed or hostile next playlist page never returns a partial inventory`() = runTest {
        for (next in listOf("https://evil.example/playlists", "https://api-v2.soundcloud.com/failed")) {
            val c = client { request ->
                if (request.url.encodedPath == "/failed") 500 to "{}"
                else 200 to """{"collection":[${playlist(1)}],"next_href":"$next"}"""
            }
            try { c.ownPlaylists("7", "secret"); fail("Partial inventory accepted") }
            catch (_: SoundCloudException) { }
        }
    }

    @Test fun `only Mixed for shelf and matching account mixes are discovered`() = runTest {
        val c = client { 200 to """{"collection":[
            {"urn":"soundcloud:selections:your-moods","items":{"collection":[${mix()},${mix(8)},${playlist(1)}]}},
            {"urn":"soundcloud:selections:artist-stations","items":{"collection":[${mix()}]}}
        ]}""" }
        assertEquals(listOf("soundcloud:system-playlists:your-moods:7:1"), c.personalMixes("7", "secret").map { it.id })
    }

    @Test fun `home discovery caps requests at five pages`() = runTest {
        var pages = 0
        val c = client { pages++; 200 to """{"collection":[],"next_href":"https://api-v2.soundcloud.com/mixed-selections?page=$pages"}""" }
        assertTrue(c.personalMixes("7", "secret").isEmpty())
        assertEquals(5, pages)
    }

    @Test fun `mix shelf pagination is not truncated`() = runTest {
        val c = client { req ->
            200 to if (req.url.encodedPath == "/more") """{"collection":[${mix()}]}"""
            else """{"collection":[{"urn":"soundcloud:selections:your-moods","items":{"collection":[],"next_href":"https://api-v2.soundcloud.com/more"}}]}"""
        }
        assertEquals(1, c.personalMixes("7", "secret").size)
    }

    @Test fun `stub batches preserve order and deduplicate repeated song ids`() = runTest {
        var calls = 0
        val c = client { req ->
            calls++
            assertEquals("/tracks", req.url.encodedPath)
            assertEquals("2,1", req.url.queryParameter("ids"))
            200 to "[${track(1)},${track(2)}]"
        }
        val set = Json.parseToJsonElement("""{"track_count":3,"tracks":[{"id":2},{"id":1},{"id":2}]}""").jsonObject
        assertEquals(listOf("2", "1"), c.playlistTracks(set, "secret").map { it.id })
        assertEquals(1, calls)
    }

    @Test fun `missing hydrated ids and truncated arrays fail closed`() = runTest {
        val c = client { 200 to "[${track(1)}]" }
        for (set in listOf("""{"tracks":[{"id":1},{"id":2}]}""", """{"track_count":5,"tracks":[]}""")) {
            try { c.playlistTracks(Json.parseToJsonElement(set).jsonObject); fail("Partial tracks accepted") }
            catch (_: SoundCloudException) { }
        }
    }

    @Test fun `known blocked stubs are omitted without substituting another track`() = runTest {
        val c = client { 200 to "[${track(1)}]" }
        assertEquals(listOf("1"), c.playlistTracks(Json.parseToJsonElement(
            """{"tracks":[{"id":1},{"id":2,"policy":"BLOCK"}]}""").jsonObject).map { it.id })
    }

    @Test fun `numeric and system playlist ids use their own endpoint`() = runTest {
        val paths = mutableListOf<String>()
        val c = client { req -> paths += req.url.encodedPath; 200 to if (req.url.encodedPath.startsWith("/playlists/")) playlist(1) else mix() }
        c.playlist("1")
        c.playlist("soundcloud:system-playlists:your-moods:7:1")
        assertEquals(2, paths.size)
        assertTrue(paths[1].startsWith("/system-playlists/"))
    }

    @Test fun `mix links preserve colon ids but refuse hostile path forms`() {
        assertEquals(SoundCloudLink.Kind.MIX, SoundCloudLinks.parse("https://soundcloud.com/discover/sets/your-moods:7:1?si=share")?.kind)
        assertNull(SoundCloudLinks.parse("https://soundcloud.com/discover/sets/a%2Fb"))
        assertNull(SoundCloudLinks.parse("https://soundcloud.com.evil.test/discover/sets/your-moods:7:1"))
        assertNull(SoundCloudLinks.parse("https://soundcloud.com/discover/sets/../settings"))
    }
}
