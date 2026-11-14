package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.quota.LosslessAllowance
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.quota.LosslessQuotaRefresher
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.relay.TidalRelay
import com.stash.data.download.lossless.relay.appVersionProvider
import com.stash.data.download.lossless.relay.supporter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Add-ons phase 2 exit test (plan §3.3): the cutover shim answers the old relay origin with a
 * cross-host `307` to the new Worker. Released apps follow it with the app's shared OkHttp client
 * (redirects on), and the new origin must still see `X-Access-Token` and every `X-Stash-*`
 * header, with an install signature that verifies there. OkHttp drops only `Authorization` on a
 * cross-host hop (here: another port on localhost, which OkHttp treats as another host).
 *
 * Proven for the Tidal rung's legacy wire (`/search`, `/stream`) and [LosslessQuotaRefresher]
 * (`/quota`), each built on a plain shared client exactly as the app builds them.
 */
class TidalRelayRedirectTest {

    private val old = MockWebServer()
    private val new = MockWebServer()
    private var now = 1_700_000_000_123L
    private val supporterToken: String? = "kofi.token.sig"

    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky", isrc = "USQX91300108", durationMs = 248_000)

    /** The app's shared client: follows redirects, and (here) sends an Authorization header that must not cross hosts. */
    private val shared = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer never-cross").build()) })
        .build()

    @Before fun setUp() {
        old.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = new.url(request.path!!)
                return MockResponse().setResponseCode(307).setHeader("Location", target.toString())
            }
        }
        new.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/search") -> MockResponse().setBody(SEARCH_HIT)
                request.path!!.startsWith("/stream/") -> MockResponse().setBody(FLAC).setHeader("X-Stash-Lossless", "12/300").setHeader("X-Stash-Lossless-Reset", "$RESET")
                request.path == "/quota" -> MockResponse().setBody("""{"used":41,"limit":300,"resetsAt":$RESET,"supporter":false}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        old.start()
        new.start()
    }

    @After fun tearDown() {
        old.shutdown()
        new.shutdown()
    }

    private fun config(): LosslessConfigFetcher = mockk {
        every { tidalRelay } returns MutableStateFlow(TidalRelay(old.url("/").toString().trimEnd('/'), ACCESS))
        every { relayKey } returns MutableStateFlow("k-secret")
        coEvery { installId() } returns INSTALL_ID
    }

    private fun hmac(message: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("k-secret".toByteArray(), "HmacSHA256")) }
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun take(server: MockWebServer): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS)!!

    @Test fun `the Tidal rung follows the 307 and the new origin gets the token and a signature that verifies`() = runBlocking {
        val config = config()
        val prefs: LosslessSourcePreferences = mockk {
            every { bitchordEndpoint } returns MutableStateFlow(null)
            coEvery { bitchordEndpointNow() } returns null
            coEvery { bitchordAccessTokenNow() } returns null
        }
        val quota = LosslessQuota.inMemory { now }
        val client = tidalClient(shared, prefs, config, FakeAddonRefDao(), RelaySigner(config, supporter { supporterToken }, appVersionProvider()), quota)
        client.nowMs = { now }

        val found = client.lookup(query) as TidalLookup.Found
        assertThat(found.stream.url).isEqualTo("https://lgf.audio.tidal.com/a.flac")
        assertThat(quota.current()?.used).isEqualTo(12) // the new origin's answer headers were read

        assertThat(take(old).path).startsWith("/search")
        val search = take(new)
        assertThat(search.path).startsWith("/search")
        assertThat(search.getHeader("X-Access-Token")).isEqualTo(ACCESS)
        assertThat(search.getHeader("Authorization")).isNull()

        assertThat(take(old).path).startsWith("/stream/td:4711")
        val stream = take(new)
        assertThat(stream.path).startsWith("/stream/td:4711")
        assertThat(stream.getHeader("X-Access-Token")).isEqualTo(ACCESS)
        assertThat(stream.getHeader("Authorization")).isNull()
        assertThat(stream.getHeader("X-Stash-Install")).isEqualTo(INSTALL_ID)
        assertThat(stream.getHeader("X-Stash-App-Build")).isEqualTo("157")
        assertThat(stream.getHeader("X-Stash-Entitlement")).isEqualTo(supporterToken)
        assertThat(stream.getHeader("X-Stash-Ts")).isEqualTo("1700000000")
        assertThat(stream.getHeader("X-Stash-Auth")).isEqualTo(hmac("$INSTALL_ID:td:4711:1700000000"))
    }

    @Test fun `the quota read follows the 307 signed over quota`() = runBlocking {
        val config = config()
        val quota = LosslessQuota.inMemory { now }
        val refresher = LosslessQuotaRefresher(shared, config, RelaySigner(config, supporter { supporterToken }, appVersionProvider()), quota).also {
            it.nowMs = { now }
            it.configWaitMs = 200
        }
        assertThat(refresher.refreshNow()).isTrue()
        assertThat(quota.current()).isEqualTo(LosslessAllowance(41, 300, RESET))

        assertThat(take(old).path).isEqualTo("/quota")
        val q = take(new)
        assertThat(q.path).isEqualTo("/quota")
        assertThat(q.getHeader("X-Access-Token")).isEqualTo(ACCESS)
        assertThat(q.getHeader("Authorization")).isNull()
        assertThat(q.getHeader("X-Stash-Install")).isEqualTo(INSTALL_ID)
        assertThat(q.getHeader("X-Stash-Ts")).isEqualTo("1700000000")
        assertThat(q.getHeader("X-Stash-Auth")).isEqualTo(hmac("$INSTALL_ID:quota:1700000000"))
    }

    private companion object {
        const val INSTALL_ID = "0f7c3a2e-install"
        const val ACCESS = "relay-access-fixture"
        const val RESET = 1_700_006_400L
        const val SEARCH_HIT = """{"tracks":[{"id":"td:4711","title":"Get Lucky","artist":"Daft Punk","duration":248,"audioQuality":"LOSSLESS","isrc":"USQX91300108"}]}"""
        const val FLAC = """{"url":"https://lgf.audio.tidal.com/a.flac","codec":"flac","manifest":"none","bitDepth":16,"sampleRate":44100,"bitrate":1411}"""
    }
}
