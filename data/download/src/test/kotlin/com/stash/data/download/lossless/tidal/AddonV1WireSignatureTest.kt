package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonValidator
import com.stash.core.addons.HttpAddonClient
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.addon.RelayAddonSigner
import com.stash.data.download.lossless.quota.LosslessQuota
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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The Tidal add-on's three signature answers on `/v1/` (stash-addon-tidal f0c2014), through the
 * whole Tidal rung on the v1 wire with the app's real signer:
 *  1. a skewed clock: 401 with `X-Stash-Server-Time`, signed again once at that time, and it plays;
 *  2. any other signature failure: 400 `bad_request`, this lookup fails, nothing is paused;
 *  3. a wrong path token: 401 without the time, this lookup fails. The Tidal rung keeps its own
 *     circuit on both wires (`circuit = false`), so, as on the legacy relay, it is not paused
 *     either; the generic add-on client pauses such an add-on for an hour (HttpAddonClientTest).
 */
class AddonV1WireSignatureTest {

    private val server = MockWebServer()
    private var now = 1_700_000_000_123L
    private lateinit var client: TidalClient

    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky", isrc = "USQX91300108", durationMs = 248_000)

    @Before fun setUp() {
        server.start()
        val prefs: LosslessSourcePreferences = mockk {
            every { bitchordEndpoint } returns MutableStateFlow(null)
            coEvery { bitchordEndpointNow() } returns null
            coEvery { bitchordAccessTokenNow() } returns null
        }
        val config: LosslessConfigFetcher = mockk {
            every { tidalRelay } returns MutableStateFlow(TidalRelay("https://relay.unused.example", "tok"))
            every { relayKey } returns MutableStateFlow("k-secret")
            coEvery { installId() } returns "install-1"
        }
        val relaySigner = RelaySigner(config, supporter(), appVersionProvider())
        val host = TestAddonHost(firstParty = true)
        val signer = RelayAddonSigner(relaySigner).also { it.nowMs = { now } }
        val addonClient = HttpAddonClient(OkHttpClient(), host, host, signer, LocalHttp)
        val manifest = AddonValidator.anyManifest(Json.parseToJsonElement(V1FacePort.MANIFEST), AddonValidator.Options(allowLocalHttp = true))
        val registry = FixedRegistry(listOf(tidalAddon(server.url("/v1/tok/").toString(), manifest)))
        client = tidalClient(
            OkHttpClient(), prefs, config, FakeAddonRefDao(), relaySigner, LosslessQuota.inMemory { now },
            registry = registry, addonClient = addonClient,
        ).also { it.nowMs = { now } }
    }

    @After fun tearDown() { server.shutdown() }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun take(): RecordedRequest = server.takeRequest(2, TimeUnit.SECONDS)!!

    private fun searchHit() = json("""{"tracks":[{"id":"td:4711","title":"Get Lucky","artist":"Daft Punk","durationMs":248000,"isrc":"USQX91300108","maxTier":"lossless"}]}""")

    private fun flac() = json("""{"url":"https://cdn.example/a.flac","manifest":"none","tier":"lossless","codec":"flac","cors":false,"bitDepth":16,"sampleRate":44100,"bitrate":1411000}""")

    @Test fun `case 1 - a skewed clock is signed again once at the server's time, and it plays`() = runBlocking {
        val serverTime = now / 1000 + 600
        server.enqueue(searchHit())
        server.enqueue(json("""{"error":{"code":"unauthorized","message":"Install clock is off"}}""", 401).setHeader("X-Stash-Server-Time", "$serverTime"))
        server.enqueue(flac())

        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(take().path).startsWith("/v1/tok/search")
        val first = take()
        assertThat(first.getHeader("X-Stash-Ts")).isEqualTo("${now / 1000}")
        val again = take()
        assertThat(again.path).isEqualTo(first.path)
        assertThat(again.getHeader("X-Stash-Ts")).isEqualTo("$serverTime")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `case 2 - any other signature failure fails this lookup only`() = runBlocking {
        server.enqueue(searchHit())
        server.enqueue(json("""{"error":{"code":"bad_request","message":"Install signature refused"}}""", 400))
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.Failed)
        assertThat(client.isBusy()).isFalse()
        // Not paused: the next play asks again (the kept match: /stream only).
        server.enqueue(flac())
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `case 3 - a 401 without the server's time is a failed lookup, signed once`() = runBlocking {
        server.enqueue(searchHit())
        server.enqueue(json("""{"error":{"code":"unauthorized","message":"Missing or wrong token"}}""", 401))
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.Failed)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(client.isBusy()).isFalse()
    }
}
