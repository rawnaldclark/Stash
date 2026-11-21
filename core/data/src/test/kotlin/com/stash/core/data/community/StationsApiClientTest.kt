package com.stash.core.data.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.community.FollowState
import com.stash.core.model.community.Station
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The Sessions routes as infra/share-worker/README.md "Contract for the app" gives them. */
class StationsApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: StationsApiClient
    private val key = "k".repeat(43)
    private val station = """{"id":"Ab3dEf9h","name":"Rawn's Basement","hostName":"Rawn","vibe":"Late-night jungle","up":3,"down":1,"followers":2}"""

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = StationsApiClient(OkHttpClient()).apply { baseUrls = listOf(server.url("/").toString().removeSuffix("/")) }
    }

    @After fun tearDown() { server.shutdown() }

    private fun answer(code: Int = 200, body: String = "") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test fun `Live now reads its sessions, with the key only when there is one`() = runBlocking {
        answer(body = """{"sessions":[{"station":$station,"room":"K7QA2PXM","track":{"t":"Xtal","a":"Aphex Twin"},"listeners":12,
            "paused":false,"full":false,"crowd":[{"i":"R","id":"m1"}],"startedAt":5}]}""")
        val live = (client.live(10, key = null) as CommunityResult.Ok).value.single()
        assertThat(live.room).isEqualTo("K7QA2PXM")
        assertThat(live.station.name).isEqualTo("Rawn's Basement")
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/community/live?limit=10")
        assertThat(req.getHeader(CommunityApiClient.KEY_HEADER)).isNull()
        answer(body = """{"sessions":[]}""")
        client.live(500, key)
        val second = server.takeRequest()
        assertThat(second.path).isEqualTo("/v1/community/live?limit=50") // the Worker's most
        assertThat(second.getHeader(CommunityApiClient.KEY_HEADER)).isEqualTo(key)
    }

    @Test fun `the followed list, a station's page and your own station come from their own paths`() = runBlocking {
        answer(body = """{"stations":[$station]}""")
        assertThat((client.following(key) as CommunityResult.Ok).value.single().id).isEqualTo("Ab3dEf9h")
        assertThat(server.takeRequest().path).isEqualTo("/v1/community/stations/following")
        answer(body = """{"station":$station,"live":{"room":"K7QA2PXM","track":null,"listeners":4,"paused":false,"full":false}}""")
        val page = (client.station("Ab3dEf9h", key = null) as CommunityResult.Ok).value
        assertThat(page.live?.listeners).isEqualTo(4)
        server.takeRequest().let { assertThat(it.path).isEqualTo("/v1/community/stations/Ab3dEf9h"); assertThat(it.getHeader(CommunityApiClient.KEY_HEADER)).isNull() }
        answer(body = """{"station":null}""")
        assertThat(client.mine(key)).isEqualTo(CommunityResult.Ok(null))
        assertThat(server.takeRequest().path).isEqualTo("/v1/community/stations/mine")
        answer(404, """{"error":"gone"}""")
        assertThat(client.station("Zz9yXw8v", key)).isEqualTo(CommunityResult.Rejected("gone"))
    }

    @Test fun `a new station sends only the fields given, cut to the Worker's limits`() = runBlocking {
        answer(201, """{"id":"Ab3dEf9h"}""")
        assertThat(client.create(StationEdit(name = "  ${"N".repeat(45)} ", hostName = "Rawn"), key)).isEqualTo(CommunityResult.Ok("Ab3dEf9h"))
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v1/community/stations")
        assertThat(req.getHeader(CommunityApiClient.KEY_HEADER)).isEqualTo(key)
        assertThat(req.body.readUtf8()).isEqualTo("""{"name":"${"N".repeat(40)}","hostName":"Rawn"}""")
        answer(409, """{"error":"have_station","id":"Ab3dEf9h"}""")
        assertThat(client.create(StationEdit(name = "Again"), key)).isEqualTo(CommunityResult.Rejected("have_station"))
    }

    @Test fun `an edit is a PATCH of what changed, a blank vibe clearing it, and delete is a DELETE`() = runBlocking {
        answer(body = """{"station":$station}""")
        val edited = client.update("Ab3dEf9h", StationEdit(vibe = " "), key)
        assertThat((edited as CommunityResult.Ok<Station>).value.vibe).isEqualTo("Late-night jungle")
        server.takeRequest().let {
            assertThat(it.method).isEqualTo("PATCH")
            assertThat(it.path).isEqualTo("/v1/community/stations/Ab3dEf9h")
            assertThat(it.body.readUtf8()).isEqualTo("""{"vibe":""}""")
        }
        answer(204)
        assertThat(client.delete("Ab3dEf9h", key)).isEqualTo(CommunityResult.Ok(Unit))
        assertThat(server.takeRequest().method).isEqualTo("DELETE")
    }

    @Test fun `a vote and a follow send their value and read the counts back`() = runBlocking {
        answer(body = """{"up":4,"down":1,"myVote":1}""")
        assertThat(client.vote("Ab3dEf9h", 1, key)).isEqualTo(CommunityResult.Ok(VoteCounts(4, 1, 1)))
        server.takeRequest().let {
            assertThat(it.method).isEqualTo("PUT")
            assertThat(it.path).isEqualTo("/v1/community/stations/Ab3dEf9h/vote")
            assertThat(it.body.readUtf8()).isEqualTo("""{"value":1}""")
        }
        answer(403, """{"error":"listen_first"}""")
        assertThat(client.vote("Ab3dEf9h", -1, key)).isEqualTo(CommunityResult.Rejected("listen_first"))
        server.takeRequest()
        answer(body = """{"following":true,"followers":3}""")
        assertThat(client.follow("Ab3dEf9h", on = true, key)).isEqualTo(CommunityResult.Ok(FollowState(true, 3)))
        server.takeRequest().let {
            assertThat(it.path).isEqualTo("/v1/community/stations/Ab3dEf9h/follow")
            assertThat(it.body.readUtf8()).isEqualTo("""{"on":true}""")
        }
        answer(409, """{"error":"too_many"}""")
        assertThat(client.follow("Zz9yXw8v", on = true, key)).isEqualTo(CommunityResult.Rejected("too_many"))
    }

    @Test fun `a heard mark is sent once, dropped on the Worker's own refusal, and tried again otherwise`() = runBlocking {
        answer(204)
        assertThat(client.heard("Ab3dEf9h", key)).isEqualTo(HeardSend.SENT)
        server.takeRequest().let {
            assertThat(it.method).isEqualTo("PUT")
            assertThat(it.path).isEqualTo("/v1/community/stations/Ab3dEf9h/heard")
            assertThat(it.getHeader(CommunityApiClient.KEY_HEADER)).isEqualTo(key)
        }
        answer(403, """{"error":"own_station"}""")
        assertThat(client.heard("Ab3dEf9h", key)).isEqualTo(HeardSend.DROPPED)
        answer(404, """{"error":"gone"}""")
        assertThat(client.heard("Ab3dEf9h", key)).isEqualTo(HeardSend.DROPPED)
        answer(503, """{"error":"sessions_off"}""")
        assertThat(client.heard("Ab3dEf9h", key)).isEqualTo(HeardSend.RETRY)
        answer(403, "<html>challenge</html>")
        assertThat(client.heard("Ab3dEf9h", key)).isEqualTo(HeardSend.RETRY)
    }

    @Test fun `the switched-off Worker's 503 is sessions_off, any other 5xx a failure`() = runBlocking {
        answer(503, """{"error":"sessions_off"}""")
        assertThat(client.live(10, null)).isEqualTo(CommunityResult.Rejected(CommunityHttp.SESSIONS_OFF))
        answer(503, """{"error":"unavailable"}""")
        assertThat(client.live(10, null)).isInstanceOf(CommunityResult.Failed::class.java)
        answer(500)
        assertThat(client.following(key)).isInstanceOf(CommunityResult.Failed::class.java)
        answer(429, "<html>")
        assertThat(client.mine(key)).isEqualTo(CommunityResult.Rejected("rate_limited"))
    }

    @Test fun `the key never follows a redirect`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/elsewhere")))
        answer(body = """{"station":null}""")
        assertThat(client.mine(key)).isInstanceOf(CommunityResult.Failed::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }
}
