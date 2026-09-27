package com.stash.core.data.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.share.SharedTrack
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class CommunityApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: CommunityApiClient
    private val key = "k".repeat(43)
    private val summary = """{"id":"AAAAAAAA","kind":"playlist","title":"sad boy hours","name":"Maya","count":42,"covers":["https://i.scdn.co/image/a"],"createdAt":5,"up":18,"down":0}"""

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = CommunityApiClient(OkHttpClient()).apply { baseUrl = server.url("/").toString().removeSuffix("/") }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `the list carries the key only when there is one, and reads posts`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"posts":[$summary]}"""))
        val posts = (client.feed(5, key = null) as CommunityResult.Ok).value
        assertThat(posts.single().title).isEqualTo("sad boy hours")
        assertThat(posts.single().myVote).isEqualTo(0)
        val first = server.takeRequest()
        assertThat(first.path).isEqualTo("/v1/community/feed?limit=5")
        assertThat(first.getHeader(CommunityApiClient.KEY_HEADER)).isNull()
        server.enqueue(MockResponse().setBody("""{"posts":[]}"""))
        client.feed(100, key)
        assertThat(server.takeRequest().getHeader(CommunityApiClient.KEY_HEADER)).isEqualTo(key)
    }

    @Test fun `a post goes out with the key, and empty fields stay out of the body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"BBBBBBBB"}"""))
        val song = NewPost("song", "Sam", "garden", NewPost.Body(track = SharedTrack("garden", "Death Plus", youtubeId = "9Vz")))
        assertThat(client.create(song, key)).isEqualTo(CommunityResult.Ok("BBBBBBBB"))
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v1/community/posts")
        assertThat(req.getHeader(CommunityApiClient.KEY_HEADER)).isEqualTo(key)
        val body = req.body.readUtf8()
        assertThat(body).contains(""""kind":"song","name":"Sam","title":"garden"""")
        assertThat(body).contains(""""track":{"t":"garden","a":"Death Plus","yt":"9Vz"}""")
        assertThat(body).doesNotContain("tracks")
        assertThat(body).doesNotContain("covers")
    }

    @Test fun `a 4xx keeps the Worker's error code, one without a code is unknown`() = runBlocking {
        val codes = listOf(429 to "daily_limit", 429 to "live_limit", 429 to "network_limit", 403 to "blocked",
            429 to "rate_limited", 413 to "too_large", 404 to "gone", 403 to "own_post", 403 to "not_yours", 401 to "bad_key")
        for ((status, code) in codes) {
            server.enqueue(MockResponse().setResponseCode(status).setBody("""{"error":"$code"}"""))
            assertThat(client.me(key)).isEqualTo(CommunityResult.Rejected(code))
        }
        server.enqueue(MockResponse().setResponseCode(400))
        assertThat(client.me(key)).isEqualTo(CommunityResult.Rejected("unknown"))
    }

    @Test fun `a 5xx or no connection is Failed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))
        assertThat(client.feed(5, null)).isInstanceOf(CommunityResult.Failed::class.java)
        val offline = CommunityApiClient(OkHttpClient()).apply { baseUrl = "http://127.0.0.1:1" }
        assertThat(offline.feed(5, null)).isInstanceOf(CommunityResult.Failed::class.java)
    }

    @Test fun `a vote sends its value and reads the counts, a take-down answers 204`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"up":3,"down":1,"myVote":-1}"""))
        assertThat(client.vote("AAAAAAAA", -1, key)).isEqualTo(CommunityResult.Ok(VoteCounts(3, 1, -1)))
        val put = server.takeRequest()
        assertThat(put.method).isEqualTo("PUT")
        assertThat(put.path).isEqualTo("/v1/community/posts/AAAAAAAA/vote")
        assertThat(put.body.readUtf8()).isEqualTo("""{"value":-1}""")
        server.enqueue(MockResponse().setResponseCode(204))
        assertThat(client.takeDown("AAAAAAAA", key)).isEqualTo(CommunityResult.Ok(Unit))
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    @Test fun `an opened post carries its songs with their art`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"post":{"id":"AAAAAAAA","kind":"playlist","title":"sad boy hours","name":"Maya","count":1,"covers":[],"createdAt":5,"up":0,"down":0,"tracks":[{"t":"T1","a":"A","art":"https://i.scdn.co/image/t"}]}}""",
        ))
        val post = (client.post("AAAAAAAA", null) as CommunityResult.Ok).value
        assertThat(post.tracks.single().artUrl).isEqualTo("https://i.scdn.co/image/t")
        assertThat(post.track).isNull()
    }
}
