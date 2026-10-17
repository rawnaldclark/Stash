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
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Install headers on the Tidal relay: a `/stream` to the owner's relay is signed like a Qobuz
 * mint (`<install>:td:<id>:<ts>`), `/search` never is, a user's own server never is, and a 401
 * naming the relay's clock re-signs once.
 */
class BitchordClientSigningTest {

    private class FakeDao : TidalMatchDao {
        val rows = ConcurrentHashMap<String, TidalMatchEntity>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun upsert(entity: TidalMatchEntity) { rows[entity.key] = entity }
        override suspend fun deleteOlderThan(before: Long) = 0
    }

    private val server = MockWebServer()
    private var now = 1_700_000_000_123L
    private val relayKey = MutableStateFlow<String?>("k-secret")
    private val tidalRelay = MutableStateFlow<TidalRelay?>(null)
    private var userEndpoint: String? = null
    private var supporterToken: String? = null
    private lateinit var client: BitchordClient

    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky", isrc = "USQX91300108", durationMs = 248_000)

    @Before fun setUp() {
        server.start()
        val prefs: LosslessSourcePreferences = mockk {
            every { bitchordEndpoint } returns MutableStateFlow(null)
            coEvery { bitchordEndpointNow() } answers { userEndpoint }
            coEvery { bitchordAccessTokenNow() } returns null
        }
        val config: LosslessConfigFetcher = mockk {
            every { tidalRelay } returns this@BitchordClientSigningTest.tidalRelay
            every { relayKey } returns this@BitchordClientSigningTest.relayKey
            coEvery { installId() } returns INSTALL_ID
        }
        client = BitchordClient(OkHttpClient(), prefs, config, FakeDao(), RelaySigner(config, supporter { supporterToken }), LosslessQuota.inMemory { now })
        client.nowMs = { now }
    }

    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun hmac(message: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("k-secret".toByteArray(), "HmacSHA256")) }
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun take(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS)!!

    @Test fun `a stream to the owner's relay carries install headers over td and the numeric id`() = runBlocking {
        tidalRelay.value = TidalRelay(base(), null)
        server.enqueue(MockResponse().setBody(SEARCH_HIT))
        server.enqueue(MockResponse().setBody(FLAC))
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)

        val search = take()
        assertThat(search.path).startsWith("/search")
        assertThat(search.getHeader("X-Stash-Auth")).isNull()
        assertThat(search.getHeader("X-Stash-Install")).isNull()

        val stream = take()
        assertThat(stream.path).startsWith("/stream/td:4711")
        assertThat(stream.getHeader("X-Stash-Install")).isEqualTo(INSTALL_ID)
        assertThat(stream.getHeader("X-Stash-Ts")).isEqualTo("1700000000")
        assertThat(stream.getHeader("X-Stash-Auth")).isEqualTo(hmac("$INSTALL_ID:td:4711:1700000000"))
    }

    @Test fun `a user's own server never gets install headers or a supporter token`() = runBlocking {
        userEndpoint = base()
        supporterToken = "tok.sig"
        server.enqueue(MockResponse().setBody(SEARCH_HIT))
        server.enqueue(MockResponse().setBody(FLAC))
        client.lookup(query)
        take()
        val stream = take()
        assertThat(stream.getHeader("X-Stash-Auth")).isNull()
        assertThat(stream.getHeader(RelaySigner.ENTITLEMENT_HEADER)).isNull()
    }

    @Test fun `a supporter token rides the owner's stream, never its search or a user's server`() = runBlocking {
        supporterToken = "tok.sig"
        tidalRelay.value = TidalRelay(base(), null)
        server.enqueue(MockResponse().setBody(SEARCH_HIT))
        server.enqueue(MockResponse().setBody(FLAC))
        client.lookup(query)
        assertThat(take().getHeader(RelaySigner.ENTITLEMENT_HEADER)).isNull()
        assertThat(take().getHeader(RelaySigner.ENTITLEMENT_HEADER)).isEqualTo("tok.sig")
    }

    @Test fun `no relay key means an unsigned stream`() = runBlocking {
        relayKey.value = null
        tidalRelay.value = TidalRelay(base(), null)
        server.enqueue(MockResponse().setBody(SEARCH_HIT))
        server.enqueue(MockResponse().setBody(FLAC))
        client.lookup(query)
        take()
        assertThat(take().getHeader("X-Stash-Install")).isNull()
    }

    @Test fun `a 401 with the relay's time re-signs once on that clock`() = runBlocking {
        tidalRelay.value = TidalRelay(base(), null)
        val relayTs = 1_700_000_000L + 600
        server.enqueue(MockResponse().setBody(SEARCH_HIT))
        server.enqueue(MockResponse().setResponseCode(401).setHeader("X-Stash-Server-Time", relayTs.toString()))
        server.enqueue(MockResponse().setBody(FLAC))
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        take()
        assertThat(take().getHeader("X-Stash-Ts")).isEqualTo("1700000000")
        val retry = take()
        assertThat(retry.getHeader("X-Stash-Ts")).isEqualTo(relayTs.toString())
        assertThat(retry.getHeader("X-Stash-Auth")).isEqualTo(hmac("$INSTALL_ID:td:4711:$relayTs"))
    }

    @Test fun `a second 401 is a transient failure, not a miss`() = runBlocking {
        tidalRelay.value = TidalRelay(base(), null)
        server.enqueue(MockResponse().setBody(SEARCH_HIT))
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(401).setHeader("X-Stash-Server-Time", (now / 1000 + 900).toString()))
        }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.Failed)
        assertThat(server.requestCount).isEqualTo(3)
    }

    private companion object {
        const val INSTALL_ID = "0f7c3a2e-install"
        const val SEARCH_HIT = """{"tracks":[{"id":"td:4711","title":"Get Lucky","artist":"Daft Punk","duration":248,"audioQuality":"LOSSLESS","isrc":"USQX91300108"}]}"""
        const val FLAC = """{"url":"https://cdn.example/a.flac","codec":"flac","manifest":"none","bitDepth":16,"sampleRate":44100}"""
    }
}
