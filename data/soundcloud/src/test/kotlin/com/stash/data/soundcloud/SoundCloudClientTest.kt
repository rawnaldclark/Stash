package com.stash.data.soundcloud

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SoundCloudClientTest {
    private val first = "a".repeat(32)
    private val second = "b".repeat(32)
    private val track = """{"id":123,"permalink_url":"https://soundcloud.com/artist/remix","title":"Remix","duration":210000,"user":{"username":"Artist"},"policy":"ALLOW","media":{"transcodings":[]}}"""
    private fun client(handler: (Request) -> Pair<Int, String>): SoundCloudClient = SoundCloudClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(code).body(body.toResponseBody()).build()
        }.build(), pause = {},
    )

    @Test fun `client id refreshes once on forbidden and is reused`() = runTest {
        var scrapes = 0
        val apiIds = CopyOnWriteArrayList<String?>()
        val client = client { request ->
            when (request.url.host) {
                "soundcloud.com" -> { scrapes++; 200 to "<script src=\"https://a-v2.sndcdn.com/assets/app.js\"></script>" }
                "a-v2.sndcdn.com" -> {
                    assertNull(request.header("Authorization"))
                    200 to "client_id:\"${if (scrapes == 1) first else second}\""
                }
                else -> {
                    assertEquals("OAuth secret", request.header("Authorization"))
                    val id = request.url.queryParameter("client_id")
                    apiIds.add(id)
                    if (id == first) 403 to "{}" else 200 to track
                }
            }
        }
        assertEquals("123", client.track("123", "secret").id)
        client.track("123", "secret")
        assertEquals(2, scrapes)
        assertEquals(listOf(first, second, second), apiIds)
    }

    @Test fun `failed later likes page never returns partial list`() = runTest {
        val client = client { request ->
            when (request.url.host) {
                "soundcloud.com" -> 200 to "<script src=\"https://a-v2.sndcdn.com/app.js\"></script>"
                "a-v2.sndcdn.com" -> 200 to "client_id:\"$first\""
                else -> if (request.url.queryParameter("offset") == null) {
                    200 to """{"collection":[{"track":$track}],"next_href":"https://api-v2.soundcloud.com/users/1/likes?offset=2"}"""
                } else 500 to "{}"
            }
        }
        try { client.likes("1"); fail("partial likes returned") } catch (e: SoundCloudException) { assertEquals(500, e.status) }
    }

    @Test fun `hostile paging cannot receive token`() = runTest {
        val visited = CopyOnWriteArrayList<String>()
        val client = client { request ->
            visited.add(request.url.host)
            when (request.url.host) {
                "soundcloud.com" -> 200 to "<script src=\"https://a-v2.sndcdn.com/app.js\"></script>"
                "a-v2.sndcdn.com" -> 200 to "client_id:\"$first\""
                else -> 200 to """{"collection":[],"next_href":"https://evil.example/steal"}"""
            }
        }
        try { client.likes("1", "secret"); fail("hostile page accepted") } catch (_: SoundCloudException) { }
        assertFalse(visited.contains("evil.example"))
    }

    @Test fun `likes paginate deduplicate and ignore playlist likes`() = runTest {
        var requests = 0
        val client = client { request ->
            when (request.url.host) {
                "soundcloud.com" -> 200 to "<script src=\"https://a-v2.sndcdn.com/app.js\"></script>"
                "a-v2.sndcdn.com" -> 200 to "client_id:\"$first\""
                else -> {
                    requests++
                    if (requests == 1) 429 to "{}"
                    else if (requests == 2) 200 to """{"collection":[{"track":$track},{"playlist":{"id":3}}],"next_href":"https://api-v2.soundcloud.com/users/1/likes?offset=2"}"""
                    else 200 to """{"collection":[{"track":$track}],"next_href":null}"""
                }
            }
        }
        assertEquals(listOf("123"), client.likes("1").map { it.id })
        assertEquals(3, requests)
    }

    @Test fun `preview metadata and protected formats are respected`() {
        val value = Json.parseToJsonElement("""{"policy":"SNIP","media":{"transcodings":[{"url":"https://api-v2.soundcloud.com/media/1","preset":"aac_160k","format":{"protocol":"hls","mime_type":"audio/mp4"}},{"url":"https://api-v2.soundcloud.com/media/2","preset":"aac_256k","format":{"protocol":"ctr-aes","mime_type":"audio/mp4"}}]}}""").jsonObject
        val formats = SoundCloudFormats.parse(value)
        assertEquals(1, formats.size)
        assertTrue(formats.single().preview)
        assertTrue(SoundCloudFormats.isPreviewUrl("https://cf-media.sndcdn.com/preview/0/30/abc"))
    }

    @Test fun `retry after supports seconds and HTTP date without shortening cooldown`() {
        assertEquals(120000, SoundCloudClient.retryDelay("120"))
        assertEquals(60000, SoundCloudClient.retryDelay("Thu, 01 Jan 1970 00:01:00 GMT", now = 0))
        assertEquals(2000, SoundCloudClient.retryDelay("invalid"))
    }

    @Test fun `short redirect outside SoundCloud is rejected before following`() = runTest {
        val visited = CopyOnWriteArrayList<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            visited.add(chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(302).header("Location", "https://evil.example/track")
                .body("".toResponseBody()).build()
        }.build()
        try {
            SoundCloudClient(http).expand("https://on.soundcloud.com/abc")
            fail("redirect accepted")
        } catch (_: SoundCloudException) { }
        assertEquals(listOf("on.soundcloud.com"), visited)
    }

    @Test fun `cancellation during backoff propagates without retry`() = runTest {
        var attempts = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = when (chain.request().url.host) {
                "soundcloud.com" -> 200 to "<script src=\"https://a-v2.sndcdn.com/app.js\"></script>"
                "a-v2.sndcdn.com" -> 200 to "client_id:\"$first\""
                else -> { attempts++; 429 to "{}" }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("fixture")
                .code(code).body(body.toResponseBody()).build()
        }.build()
        val client = SoundCloudClient(http, pause = { throw CancellationException("cancel test") })
        try { client.likes("1"); fail("cancellation swallowed") } catch (_: CancellationException) { }
        assertEquals(1, attempts)
    }

    @Test fun `stream falls back from missing format and recognizes preview URL`() = runTest {
        val withFormats = track.replace("\"transcodings\":[]", """"transcodings":[
            {"url":"https://api-v2.soundcloud.com/media/aac","preset":"aac_160k","format":{"protocol":"hls","mime_type":"audio/mp4"}},
            {"url":"https://api-v2.soundcloud.com/media/mp3","preset":"mp3_128k","format":{"protocol":"progressive","mime_type":"audio/mpeg"}}
        ]""")
        val client = client { request ->
            when (request.url.host) {
                "soundcloud.com" -> 200 to "<script src=\"https://a-v2.sndcdn.com/app.js\"></script>"
                "a-v2.sndcdn.com" -> 200 to "client_id:\"$first\""
                else -> when (request.url.encodedPath) {
                    "/tracks/123" -> 200 to withFormats
                    "/media/aac" -> 404 to "{}"
                    else -> 200 to """{"url":"https://cf-media.sndcdn.com/preview/0/30/song.mp3"}"""
                }
            }
        }
        val stream = client.stream("123")!!
        assertTrue(stream.preview)
        assertFalse(stream.hls)
    }

    @Test fun `session validation posts token and obtains account profile`() = runTest {
        var validated = false
        val client = client { request ->
            when (request.url.host) {
                "soundcloud.com" -> 200 to "<script src=\"https://a-v2.sndcdn.com/app.js\"></script>"
                "a-v2.sndcdn.com" -> 200 to "client_id:\"$first\""
                "api-auth.soundcloud.com" -> {
                    assertEquals("POST", request.method)
                    val buffer = okio.Buffer()
                    request.body!!.writeTo(buffer)
                    assertEquals("""{"session":{"access_token":"secret"}}""", buffer.readUtf8())
                    validated = true
                    200 to "{}"
                }
                else -> {
                    assertTrue(validated)
                    assertEquals("OAuth secret", request.header("Authorization"))
                    200 to """{"id":7,"username":"Listener"}"""
                }
            }
        }
        assertEquals("7", client.validateToken("secret").id)
    }
}
