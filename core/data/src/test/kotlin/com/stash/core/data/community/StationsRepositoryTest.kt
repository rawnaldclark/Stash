package com.stash.core.data.community

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.prefs.HomeSectionsPreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class StationsRepositoryTest {
    private lateinit var server: MockWebServer
    private val key = "k".repeat(43)
    private val heard = mockk<StationHeard>(relaxed = true) {
        coEvery { beforeVote(any()) } returns true
        coEvery { forget(any()) } returns true
    }
    private val station = """{"id":"Ab3dEf9h","name":"Rawn's Basement","up":0,"down":0,"followers":0}"""

    @Before fun setUp() { server = MockWebServer().also { it.start() } }

    @After fun tearDown() { server.shutdown() }

    private fun keyStore(existingKey: String?) = mockk<CommunityKeyStore> {
        coEvery { key() } returns key
        coEvery { existingKey() } returns existingKey
    }

    private fun repository(keys: CommunityKeyStore = keyStore(key), on: Boolean = true): StationsRepository {
        val api = StationsApiClient(OkHttpClient()).apply { baseUrls = listOf(server.url("/").toString().removeSuffix("/")) }
        val homeSections = mockk<HomeSectionsPreference> { every { communityOn } returns flowOf(on) }
        return StationsRepository(api, keys, homeSections, heard)
    }

    private fun answer(code: Int = 200, body: String = "") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test fun `with Community off nothing is sent and every call says off`() = runBlocking {
        val repo = repository(on = false)
        assertThat(repo.live(10)).isEqualTo(CommunityResult.Rejected("off"))
        assertThat(repo.follow("Ab3dEf9h", true)).isEqualTo(CommunityResult.Rejected("off"))
        assertThat(repo.vote("Ab3dEf9h", 1)).isEqualTo(CommunityResult.Rejected("off"))
        assertThat(repo.create(StationEdit(name = "B"))).isEqualTo(CommunityResult.Rejected("off"))
        repo.ensureKnown()
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(repo.sessionsOn.value).isNull()
    }

    @Test fun `the switch is unknown until the Worker answers, off on sessions_off, on after any other answer`() = runBlocking {
        val repo = repository()
        assertThat(repo.sessionsOn.value).isNull()
        answer(503, """{"error":"sessions_off"}""")
        repo.ensureKnown()
        assertThat(server.takeRequest().path).isEqualTo("/v1/community/live?limit=1")
        assertThat(repo.sessionsOn.value).isFalse()
        repo.ensureKnown() // known now: no second ask
        assertThat(server.requestCount).isEqualTo(1)
        answer(404, """{"error":"gone"}""")
        repo.station("Zz9yXw8v")
        assertThat(repo.sessionsOn.value).isTrue() // a route that answers is a switch that's on
        answer(503, """{"error":"sessions_off"}""")
        repo.live(10)
        assertThat(repo.sessionsOn.value).isFalse() // turned off again
    }

    @Test fun `a Worker without the routes is off, and offline or rate limited says nothing`() = runBlocking {
        val repo = repository()
        answer(429, "<html>")
        repo.ensureKnown()
        assertThat(repo.sessionsOn.value).isNull()
        answer(500)
        repo.ensureKnown() // still unknown, so it asks again
        assertThat(repo.sessionsOn.value).isNull()
        answer(404, """{"error":"not_found"}""")
        repo.ensureKnown()
        assertThat(repo.sessionsOn.value).isFalse()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `reads carry the key only once the phone has one, and never make it`() = runBlocking {
        val keys = keyStore(existingKey = null)
        val repo = repository(keys)
        answer(body = """{"sessions":[]}""")
        repo.live(10)
        assertThat(server.takeRequest().getHeader(CommunityApiClient.KEY_HEADER)).isNull()
        assertThat(repo.following()).isEqualTo(CommunityResult.Ok(emptyList<Any>()))
        assertThat(repo.mine()).isEqualTo(CommunityResult.Ok(null))
        assertThat(server.requestCount).isEqualTo(1) // following and mine asked nothing
        coVerify(exactly = 0) { keys.key() }
        assertThat(repo.sessionsOn.value).isTrue() // from live's answer only
    }

    @Test fun `a follow makes the key and moves the revision, a refused one doesn't`() = runBlocking {
        val keys = keyStore(existingKey = null)
        val repo = repository(keys)
        answer(body = """{"following":true,"followers":3}""")
        assertThat((repo.follow("Ab3dEf9h", true) as CommunityResult.Ok).value.followers).isEqualTo(3)
        assertThat(server.takeRequest().getHeader(CommunityApiClient.KEY_HEADER)).isEqualTo(key)
        assertThat(repo.revision.value).isEqualTo(1)
        answer(409, """{"error":"too_many"}""")
        repo.follow("Zz9yXw8v", true)
        assertThat(repo.revision.value).isEqualTo(1)
    }

    @Test fun `a vote sends the waiting heard mark first, and holds itself when the mark can't go`() = runBlocking {
        val repo = repository()
        coEvery { heard.beforeVote("Ab3dEf9h") } returns false
        assertThat(repo.vote("Ab3dEf9h", 1)).isEqualTo(CommunityResult.Rejected(CommunityRepository.VOTE_NOT_NOW))
        assertThat(server.requestCount).isEqualTo(0)
        answer(body = """{"up":0,"down":0,"myVote":0}""")
        assertThat(repo.vote("Ab3dEf9h", 0)).isEqualTo(CommunityResult.Ok(VoteCounts(0, 0, 0))) // a 0 needs no mark
        coEvery { heard.beforeVote("Ab3dEf9h") } returns true
        answer(403, """{"error":"listen_first"}""")
        coEvery { heard.forget("Ab3dEf9h") } returns false // the Worker never had it: still waiting here
        assertThat(repo.vote("Ab3dEf9h", 1)).isEqualTo(CommunityResult.Rejected(CommunityRepository.VOTE_NOT_NOW))
        answer(403, """{"error":"listen_first"}""")
        coEvery { heard.forget("Ab3dEf9h") } returns true
        assertThat(repo.vote("Ab3dEf9h", 1)).isEqualTo(CommunityResult.Rejected(CommunityRepository.LISTEN_FIRST))
    }

    @Test fun `deleting a station that's already gone counts as deleted`() = runBlocking {
        val repo = repository()
        answer(404, """{"error":"gone"}""")
        assertThat(repo.delete("Ab3dEf9h")).isEqualTo(CommunityResult.Ok(Unit))
        assertThat(repo.revision.value).isEqualTo(1)
    }

    @Test fun `a station's room comes from its page while it's on the air`() = runBlocking {
        val repo = repository()
        answer(body = """{"station":$station,"live":{"room":"K7QA2PXM","track":null,"listeners":2,"paused":false,"full":false}}""")
        assertThat(repo.liveRoom("Ab3dEf9h")).isEqualTo("K7QA2PXM")
        answer(body = """{"station":$station}""")
        assertThat(repo.liveRoom("Ab3dEf9h")).isNull()
        answer(503, """{"error":"sessions_off"}""")
        assertThat(repo.liveRoom("Ab3dEf9h")).isNull()
    }
}
