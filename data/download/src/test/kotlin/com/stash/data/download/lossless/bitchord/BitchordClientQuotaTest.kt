package com.stash.data.download.lossless.bitchord

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TidalMatchDao
import com.stash.core.data.db.entity.TidalMatchEntity
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.relay.supporter
import com.stash.data.download.lossless.relay.TidalRelay
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * The daily lossless allowance on the Tidal rung: headers feed [LosslessQuota]; while it is
 * spent a lossless lookup on the owner's relay makes no request; the relay's cap answer is
 * neither a miss nor a busy pool; and an AAC answer on a spent day is not a lossless miss.
 */
class BitchordClientQuotaTest {

    private class FakeDao : TidalMatchDao {
        val rows = ConcurrentHashMap<String, TidalMatchEntity>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun upsert(entity: TidalMatchEntity) { rows[entity.key] = entity }
        override suspend fun deleteOlderThan(before: Long) = 0
    }

    private val server = MockWebServer()
    private val dao = FakeDao()
    private var now = 1_792_238_400_000L
    private val resetsAt get() = now / 1000 + 3600
    private val quota = LosslessQuota.inMemory { now }
    private val streams = AtomicInteger()
    private var userEndpoint: String? = null
    @Volatile private var streamReply: (RecordedRequest) -> MockResponse = { ok(FLAC) }
    private lateinit var client: BitchordClient

    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky", isrc = "USQX91300108", durationMs = 248_000)

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/search") -> ok(SEARCH_HIT)
                request.path!!.startsWith("/stream") -> { streams.incrementAndGet(); streamReply(request) }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val prefs: LosslessSourcePreferences = mockk {
            every { bitchordEndpoint } returns MutableStateFlow(null)
            coEvery { bitchordEndpointNow() } answers { userEndpoint }
            coEvery { bitchordAccessTokenNow() } returns null
        }
        val config: LosslessConfigFetcher = mockk {
            every { tidalRelay } returns MutableStateFlow(TidalRelay(base, null))
            every { relayKey } returns MutableStateFlow(null)
        }
        client = BitchordClient(OkHttpClient(), prefs, config, dao, RelaySigner(config, supporter()), quota)
        client.nowMs = { now }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `a counted answer's headers reach the quota`() = runBlocking {
        streamReply = { ok(FLAC).setHeader("X-Stash-Lossless", "451/500").setHeader("X-Stash-Lossless-Reset", "$resetsAt") }
        client.lookup(query)
        assertThat(quota.current()?.used).isEqualTo(451)
    }

    @Test fun `spent - a lossless lookup on the owner's relay makes no request`() = runBlocking {
        quota.onHeaders("500/500", "$resetsAt")
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.Spent(resetsAt * 1000))
        assertThat(client.lookup(query, cacheOnly = true)).isInstanceOf(TidalLookup.Spent::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `spent - a user's own server is still asked for FLAC`() = runBlocking {
        userEndpoint = server.url("/").toString().trimEnd('/')
        quota.onHeaders("500/500", "$resetsAt")
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
    }

    @Test fun `spent - AAC uses the allowance too, so no AAC lookup goes out either`() = runBlocking {
        quota.onHeaders("500/500", "$resetsAt")
        streamReply = { ok(AAC) }

        assertThat(client.lookup(query, allowAac = true)).isEqualTo(TidalLookup.Spent(resetsAt * 1000))
        assertThat(client.lookup(query, allowAac = true, aacOnly = true)).isEqualTo(TidalLookup.Spent(resetsAt * 1000))
        assertThat(server.requestCount).isEqualTo(0)

        // After the reset both go out again.
        now += 3_600_000
        assertThat(client.lookup(query, allowAac = true)).isInstanceOf(TidalLookup.Found::class.java)
    }

    @Test fun `spent - a user's own server is still asked for AAC`() = runBlocking {
        userEndpoint = server.url("/").toString().trimEnd('/')
        quota.onHeaders("500/500", "$resetsAt")
        streamReply = { ok(AAC) }
        assertThat(client.lookup(query, allowAac = true, aacOnly = true)).isInstanceOf(TidalLookup.Found::class.java)
    }

    @Test fun `an AAC answer's counter headers reach the quota, counted by the relay, never by the app`() = runBlocking {
        streamReply = { ok(AAC).setHeader("X-Stash-Lossless", "120/500").setHeader("X-Stash-Lossless-Reset", "$resetsAt") }
        client.lookup(query, allowAac = true, aacOnly = true)
        assertThat(quota.current()?.used).isEqualTo(120)

        // A relay that doesn't count AAC yet sends no headers: nothing moves.
        streamReply = { ok(AAC) }
        client.lookup(query.copy(title = "One More Time", isrc = "GBDUW0000059"), allowAac = true, aacOnly = true)
        assertThat(quota.current()?.used).isEqualTo(120)
    }

    @Test fun `the relay's cap answer is Spent - not busy, not a miss`() = runBlocking {
        streamReply = {
            MockResponse().setResponseCode(429).setHeader("Retry-After", "3600")
                .setBody("""{"error":"daily_lossless_cap","used":500,"limit":500,"resetsAt":$resetsAt}""")
        }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.Spent(resetsAt * 1000))
        assertThat(client.isBusy()).isFalse()
        assertThat(quota.isSpent()).isTrue()
        assertThat(dao.rows.values.single().losslessMissAt).isNull()

        // Past the cap AAC is not asked either: it counts toward the same allowance.
        val before = server.requestCount
        assertThat(client.lookup(query, allowAac = true)).isInstanceOf(TidalLookup.Spent::class.java)
        assertThat(server.requestCount).isEqualTo(before)
    }

    @Test fun `the relay's cap answer to an AAC request is Spent too`() = runBlocking {
        streamReply = {
            MockResponse().setResponseCode(429).setHeader("Retry-After", "3600")
                .setBody("""{"error":"daily_lossless_cap","used":500,"limit":500,"resetsAt":$resetsAt}""")
        }
        assertThat(client.lookup(query, allowAac = true, aacOnly = true)).isEqualTo(TidalLookup.Spent(resetsAt * 1000))
        assertThat(quota.isSpent()).isTrue()
        assertThat(client.isBusy()).isFalse()
    }

    @Test fun `a user's own server never moves the shared count or the spent day`() = runBlocking {
        userEndpoint = server.url("/").toString().trimEnd('/')
        streamReply = { ok(FLAC).setHeader("X-Stash-Lossless", "451/500").setHeader("X-Stash-Lossless-Reset", "$resetsAt") }
        client.lookup(query)
        assertThat(quota.current()).isNull()

        streamReply = {
            MockResponse().setResponseCode(429).setHeader("Retry-After", "60")
                .setBody("""{"error":"daily_lossless_cap","used":500,"limit":500,"resetsAt":$resetsAt}""")
        }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Busy::class.java)
        assertThat(quota.isSpent()).isFalse()
    }

    @Test fun `any other 429 still opens the busy circuit`() = runBlocking {
        streamReply = { MockResponse().setResponseCode(429).setHeader("Retry-After", "60").setBody("""{"error":"network_daily_cap"}""") }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Busy::class.java)
        assertThat(client.isBusy()).isTrue()
        assertThat(quota.current()).isNull()
    }

    private companion object {
        fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
        const val SEARCH_HIT = """{"tracks":[{"id":"td:1","title":"Get Lucky","artist":"Daft Punk","duration":248,"audioQuality":"LOSSLESS","isrc":"USQX91300108"}]}"""
        const val FLAC = """{"url":"https://cdn.example/a.flac","codec":"flac","manifest":"none","bitDepth":16,"sampleRate":44100}"""
        const val AAC = """{"url":"https://cdn.example/a.mp4","codec":"aac","manifest":"none","bitrate":320}"""
    }
}
