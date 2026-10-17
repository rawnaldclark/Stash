package com.stash.data.download.lossless.quota

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelayEntry
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.relay.supporter
import com.stash.data.download.lossless.relay.TidalRelay
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class LosslessQuotaRefresherTest {

    private val server = MockWebServer()
    private var now = 1_792_238_400_000L
    private val relays = MutableStateFlow<List<RelayEntry>>(emptyList())
    private val tidal = MutableStateFlow<TidalRelay?>(null)
    private val key = MutableStateFlow<String?>("k-secret")
    private val config: LosslessConfigFetcher = mockk {
        every { this@mockk.relays } returns this@LosslessQuotaRefresherTest.relays
        every { tidalRelay } returns tidal
        every { relayKey } returns key
        coEvery { installId() } returns INSTALL_ID
    }
    private val quota = LosslessQuota.inMemory { now }
    private var supporterToken: String? = null
    private lateinit var refresher: LosslessQuotaRefresher

    @Before fun setUp() {
        server.start()
        refresher = LosslessQuotaRefresher(OkHttpClient(), config, RelaySigner(config, supporter { supporterToken }), quota).also {
            it.nowMs = { now }
            it.configWaitMs = 200
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun base() = server.url("/").toString().trimEnd('/')

    @Test fun `a stale value asks the Qobuz relay's quota endpoint, signed over quota`() = runBlocking {
        relays.value = listOf(RelayEntry(base()))
        server.enqueue(MockResponse().setBody("""{"used":412,"limit":500,"resetsAt":$RESET,"supporter":false}"""))

        assertThat(refresher.refreshIfStale()).isTrue()
        assertThat(quota.current()).isEqualTo(LosslessAllowance(412, 500, RESET))

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertThat(req.path).isEqualTo("/v1/quota")
        assertThat(req.getHeader("X-Stash-Install")).isEqualTo(INSTALL_ID)
        assertThat(req.getHeader("X-Stash-Ts")).isEqualTo("1792238400")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("k-secret".toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal("$INSTALL_ID:quota:1792238400".toByteArray()).joinToString("") { "%02x".format(it) }
        assertThat(req.getHeader("X-Stash-Auth")).isEqualTo(expected)
    }

    @Test fun `a value from today makes no request`() = runBlocking {
        relays.value = listOf(RelayEntry(base()))
        quota.onHeaders("3/500", "$RESET")
        assertThat(refresher.refreshIfStale()).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `no relay configured, or no key, makes no request at all`() = runBlocking {
        assertThat(refresher.refreshIfStale()).isFalse()
        key.value = null
        relays.value = listOf(RelayEntry(base()))
        assertThat(refresher.refreshIfStale()).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `only the Tidal relay configured asks its quota endpoint with the access token`() = runBlocking {
        tidal.value = TidalRelay(base(), "tok")
        server.enqueue(MockResponse().setBody("""{"used":0,"limit":"unlimited","resetsAt":$RESET,"supporter":true}"""))
        assertThat(refresher.refreshIfStale()).isTrue()
        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertThat(req.path).isEqualTo("/quota")
        assertThat(req.getHeader("X-Access-Token")).isEqualTo("tok")
        assertThat(quota.current()?.unlimited).isTrue()
    }

    @Test fun `a failing Qobuz relay falls to the Tidal one`() = runBlocking {
        relays.value = listOf(RelayEntry(base()))
        tidal.value = TidalRelay(base(), null)
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"used":7,"limit":500,"resetsAt":$RESET}"""))
        assertThat(refresher.refreshIfStale()).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/v1/quota")
        assertThat(server.takeRequest().path).isEqualTo("/quota")
        assertThat(quota.current()?.used).isEqualTo(7)
    }

    @Test fun `a 401 with the relay's time re-signs once`() = runBlocking {
        relays.value = listOf(RelayEntry(base()))
        server.enqueue(MockResponse().setResponseCode(401).setHeader("X-Stash-Server-Time", "1792239000"))
        server.enqueue(MockResponse().setBody("""{"used":1,"limit":500,"resetsAt":$RESET}"""))
        assertThat(refresher.refreshIfStale()).isTrue()
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("X-Stash-Ts")).isEqualTo("1792239000")
    }

    @Test fun `refreshNow asks even with today's value, carrying the supporter token`() = runBlocking {
        relays.value = listOf(RelayEntry(base()))
        quota.onHeaders("500/500", "$RESET")
        supporterToken = "tok.sig"
        server.enqueue(MockResponse().setBody("""{"used":500,"limit":null,"resetsAt":$RESET,"supporter":true}"""))

        assertThat(refresher.refreshNow()).isTrue()
        assertThat(server.takeRequest().getHeader(RelaySigner.ENTITLEMENT_HEADER)).isEqualTo("tok.sig")
        assertThat(quota.current()).isEqualTo(LosslessAllowance(500, null, RESET))
        assertThat(quota.isSpent()).isFalse()
    }

    @Test fun `a removed token's refreshNow brings the free limit back, without the header`() = runBlocking {
        relays.value = listOf(RelayEntry(base()))
        quota.onHeaders("620/unlimited", "$RESET")
        server.enqueue(MockResponse().setBody("""{"used":620,"limit":500,"resetsAt":$RESET,"supporter":false}"""))

        assertThat(refresher.refreshNow()).isTrue()
        assertThat(server.takeRequest().getHeader(RelaySigner.ENTITLEMENT_HEADER)).isNull()
        assertThat(quota.isSpent()).isTrue()
    }

    @Test fun `a cold start with a token the relays have not confirmed today asks once`() = runBlocking {
        relays.value = listOf(RelayEntry(base()))
        quota.onHeaders("500/500", "$RESET") // spent before the claim; the claim's refresh failed
        supporterToken = "tok.sig"
        server.enqueue(MockResponse().setBody("""{"used":500,"limit":null,"resetsAt":$RESET,"supporter":true}"""))
        assertThat(refresher.refreshIfStale()).isTrue()

        // Confirmed: the next cold start has nothing to ask.
        assertThat(refresher.refreshIfStale()).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
    }

    private companion object {
        const val INSTALL_ID = "0f7c3a2e-install"
        const val RESET = 1_792_281_600L
    }
}
