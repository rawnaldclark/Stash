package com.stash.data.download.lossless.relay

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

class LosslessRelayClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: LosslessRelayClient
    private lateinit var config: LosslessConfigFetcher
    /** Null by default: the existing tests exercise the unsigned path. */
    private val relayKey = MutableStateFlow<String?>(null)
    private var now = 1_000_000L
    private val base get() = server.url("/").toString().trimEnd('/')

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        config = mockk()
        every { config.relayKey } returns relayKey
        coEvery { config.installId() } returns INSTALL_ID
        client = LosslessRelayClient(OkHttpClient(), config).also { it.clock = { now } }
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `200 maps to Ok with Hz as sent and the protocol header`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/f.flac?etsp=1","format_id":27,"bit_depth":24,"sample_rate":96000}"""))
        val r = client.mint(base, 42, 27)
        assertThat(r).isEqualTo(RelayMint.Ok("https://cdn.example/f.flac?etsp=1", 27, 24, 96_000))
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/qobuz/file?track_id=42&format_id=27")
        assertThat(req.getHeader("X-Stash-Version")).isEqualTo("1")
        assertThat(req.getHeader("X-Stash-Version")).isEqualTo(LosslessRelayClient.PROTOCOL_VERSION)
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `404 is NoMatch with no cooldown`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"status":"no_match"}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.NoMatch)
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `503 busy cools the base for 60s and skips the request while cooled`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"status":"busy","retry_after":30}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
        assertThat(client.mint(base, 43, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(server.requestCount).isEqualTo(1)
        now += LosslessRelayClient.BUSY_COOLDOWN_MS + 1
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `502 and unreachable cool the base for 5 minutes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"status":"upstream"}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        now += LosslessRelayClient.BUSY_COOLDOWN_MS + 1
        assertThat(client.isCooled(base)).isTrue()
        now += LosslessRelayClient.UNAVAILABLE_COOLDOWN_MS
        assertThat(client.isCooled(base)).isFalse()

        server.shutdown()
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `200 with a non-https url is Unavailable`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"http://cdn.example/f.flac","format_id":27,"bit_depth":16,"sample_rate":44100}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `malformed base is Unavailable without a request`() = runTest {
        assertThat(client.mint("https://bad host", 1, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(client.isCooled("https://bad host")).isFalse()
    }

    @Test fun `200 with an unusable body cools the base`() = runTest {
        server.enqueue(MockResponse().setBody("<html>gateway error</html>"))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `a body that dies mid-read cools the base`() = runTest {
        server.enqueue(MockResponse()
            .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            .setBody("""{"url":"https://cdn.example/f.flac?etsp=1","format_id":27,"bit_depth":16,"sample_rate":44100}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `omitted format_id echoes the requested one`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/f.flac?etsp=1"}"""))
        val r = client.mint(base, 42, 27) as RelayMint.Ok
        assertThat(r.formatId).isEqualTo(27)
        assertThat(r.bitDepth).isEqualTo(0); assertThat(r.sampleRateHz).isEqualTo(0)
    }

    @Test fun `probe is true on 200 and never cools`() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        assertThat(client.probe(base)).isTrue()
        // Bounded: an untimed takeRequest() on a probe that stopped issuing the
        // request would hang to the CI job timeout instead of failing here.
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)?.path).isEqualTo("/v1/status")
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `probe is true on 404 and on 400 — reachability, not health`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.probe(base)).isTrue()
        assertThat(client.isCooled(base)).isFalse()

        server.enqueue(MockResponse().setResponseCode(400))
        assertThat(client.probe(base)).isTrue()
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `probe is false when the host is unreachable and still never cools`() = runTest {
        server.shutdown()
        assertThat(client.probe(base)).isFalse()
        assertThat(client.isCooled(base)).isFalse()
    }

    @Test fun `probe ignores the cooldown so a manual test gets a real answer`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(client.isCooled(base)).isTrue()

        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        assertThat(client.probe(base)).isTrue()
        assertThat(server.requestCount).isEqualTo(2)
        // The manual test neither consulted nor extended the cooldown.
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `probe on a malformed or non-http base is false without a request`() = runTest {
        assertThat(client.probe("https://bad host")).isFalse()
        assertThat(client.probe("ftp://relay.example")).isFalse()
        assertThat(client.probe("relay.example")).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    // --- request signing (spec §5.4) --------------------------------------

    @Test fun `no relay key means no auth headers at all`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/f.flac?etsp=1"}"""))
        client.mint(base, 42, 27)
        val req = server.takeRequest()
        assertThat(req.getHeader("X-Stash-Auth")).isNull()
        assertThat(req.getHeader("X-Stash-Install")).isNull()
        assertThat(req.getHeader("X-Stash-Ts")).isNull()
    }

    @Test fun `with a relay key the mint carries install id, timestamp, and a verifiable HMAC`() = runTest {
        relayKey.value = "k-secret"
        now = 1_700_000_000_123L // ts is unix SECONDS — the millis must be truncated, not rounded
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/f.flac?etsp=1"}"""))
        client.mint(base, 42, 27)
        val req = server.takeRequest()
        assertThat(req.getHeader("X-Stash-Install")).isEqualTo(INSTALL_ID)
        assertThat(req.getHeader("X-Stash-Ts")).isEqualTo("1700000000")
        // Recompute independently: the relay will do exactly this.
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("k-secret".toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal("$INSTALL_ID:42:27:1700000000".toByteArray()).joinToString("") { "%02x".format(it) }
        assertThat(req.getHeader("X-Stash-Auth")).isEqualTo(expected)
        // The custom-endpoint / unsigned contract header is unchanged by signing.
        assertThat(req.getHeader("X-Stash-Version")).isEqualTo("1")
    }

    @Test fun `a failing install-id store falls back to a transient id instead of throwing`() = runTest {
        // This runs BEFORE the HTTP try in mint(): a throw here would escape into
        // QbdlxQobuzSource's breaker and trip qbdlx wholesale.
        relayKey.value = "k-secret"
        coEvery { config.installId() } throws java.io.IOException("datastore unavailable")
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/f.flac?etsp=1"}"""))
        val r = client.mint(base, 42, 27)
        assertThat(r).isInstanceOf(RelayMint.Ok::class.java)
        assertThat(server.takeRequest().getHeader("X-Stash-Install")).isNotEmpty()
    }

    // ── Clock skew: a 401 carrying the relay's time re-signs once ──────────────────────────

    @Test fun `a 401 with X-Stash-Server-Time re-signs once with the relay's clock and keeps the offset`() = runTest {
        relayKey.value = "k-secret"
        now = 1_700_000_000_000L
        val relayTs = 1_700_000_000L + 600 // the device is 10 minutes slow
        server.enqueue(MockResponse().setResponseCode(401).setHeader("X-Stash-Server-Time", relayTs.toString()).setBody("""{"error":"bad_ts"}"""))
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/f.flac?etsp=1","format_id":27}"""))
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/g.flac?etsp=1","format_id":27}"""))

        assertThat(client.mint(base, 42, 27)).isInstanceOf(RelayMint.Ok::class.java)
        assertThat(client.isCooled(base)).isFalse()
        assertThat(server.takeRequest().getHeader("X-Stash-Ts")).isEqualTo("1700000000")
        val retry = server.takeRequest()
        assertThat(retry.getHeader("X-Stash-Ts")).isEqualTo(relayTs.toString())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("k-secret".toByteArray(), "HmacSHA256")) }
        val expected = mac.doFinal("$INSTALL_ID:42:27:$relayTs".toByteArray()).joinToString("") { "%02x".format(it) }
        assertThat(retry.getHeader("X-Stash-Auth")).isEqualTo(expected)

        // Later mints sign with the learned offset straight away: one request, no 401.
        now += 5_000
        assertThat(client.mint(base, 43, 27)).isInstanceOf(RelayMint.Ok::class.java)
        assertThat(server.takeRequest().getHeader("X-Stash-Ts")).isEqualTo((relayTs + 5).toString())
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `a second 401 after the skew retry cools the base as today`() = runTest {
        relayKey.value = "k-secret"
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(401).setHeader("X-Stash-Server-Time", (now / 1000 + 900).toString()))
        }
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `a 401 without X-Stash-Server-Time is not retried`() = runTest {
        relayKey.value = "k-secret"
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad_sig"}"""))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(client.isCooled(base)).isTrue()
    }

    @Test fun `an unsigned mint ignores X-Stash-Server-Time`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("X-Stash-Server-Time", (now / 1000 + 900).toString()))
        assertThat(client.mint(base, 42, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(client.isCooled(base)).isTrue()
    }

    // ── Streams first: downloads are labelled, and a "paced" answer waits ──────────────────

    @Test fun `a stream sends no purpose, while a mint under LosslessDownloadPurpose says download`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/a.flac?etsp=1"}"""))
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/b.flac?etsp=1"}"""))
        client.mint(base, 42, 27)
        withContext(LosslessDownloadPurpose()) { client.mint(base, 43, 27) }
        assertThat(server.takeRequest().getHeader("X-Stash-Purpose")).isNull()
        assertThat(server.takeRequest().getHeader("X-Stash-Purpose")).isEqualTo("download")
    }

    @Test fun `429 paced on a download is Paced, records the wait, and does NOT cool the base`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7980").setBody("""{"error":"paced"}"""))
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn.example/f.flac?etsp=1","format_id":7}"""))
        val purpose = LosslessDownloadPurpose()
        val r = withContext(purpose) { client.mint(base, 42, 27) }
        assertThat(r).isEqualTo(RelayMint.Paced(7980))
        assertThat(purpose.pacedRetryAfterSec).isEqualTo(7980L)
        assertThat(client.isCooled(base)).isFalse()
        // A stream right after still reaches the relay.
        assertThat(client.mint(base, 43, 27)).isInstanceOf(RelayMint.Ok::class.java)
    }

    @Test fun `any other 429 still cools the base for 5 minutes`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3600").setBody("""{"error":"rate_limited"}"""))
        assertThat(withContext(LosslessDownloadPurpose()) { client.mint(base, 42, 27) }).isEqualTo(RelayMint.Unavailable)
        now += LosslessRelayClient.BUSY_COOLDOWN_MS + 1
        assertThat(client.isCooled(base)).isTrue()
    }

    private companion object {
        const val INSTALL_ID = "0f7c3a2e-install"
    }

}
