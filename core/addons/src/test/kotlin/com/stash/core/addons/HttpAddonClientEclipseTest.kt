package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Descriptor
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.ResolveQuery
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * An Eclipse-family add-on through [HttpAddonClient] (add-ons plan phase 4 §2.3): asked with its
 * own parameters, read through [AddonEclipse], its errors read by status, and every request
 * carrying Stash's User-Agent. Also the base rules on a redirect hop (phase 4 §2.6).
 */
class HttpAddonClientEclipseTest {

    private lateinit var server: MockWebServer
    private val gate = HttpAddonClientTest.RecordingGate()
    private lateinit var client: HttpAddonClient

    private val host = object : AddonHostOptions {
        override val allowLocalHttp = true
        override val userAgent = "Stash/9.9.9"
    }

    @Before fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        client = HttpAddonClient(OkHttpClient(), gate, NoFirstPartyAddons, UnsignedAddons, host)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun base() = "http://127.0.0.1:${server.port}/tok/"

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun eclipse(manifest: String): InstalledAddon {
        val read = AddonValidator.readManifest(Json.parseToJsonElement(manifest), client.optionsFor(publicOnly = true))
        return InstalledAddon(base = base(), manifest = read.manifest, userAdded = true, dialect = read.dialect, eclipse = read.eclipse)
    }

    private val plain = """{"id":"com.example.plain","name":"Plain","version":"1","resources":["search","stream","resolve"]}"""
    private val lossless = StreamRequest(Tier.HIRES, Tier.LOSSLESS, listOf(Delivery.NONE, Delivery.DASH))

    @Test fun `the manifest reads as Eclipse, with Stash's User-Agent`() = runBlocking<Unit> {
        server.enqueue(json(plain))
        val r = client.readManifest(base(), publicOnly = true)
        assertThat((r as AddonResult.Ok).value.dialect).isEqualTo(AddonDialect.ECLIPSE)
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("Stash/9.9.9")
    }

    @Test fun `search sends q alone, and stream sends quality and no tier parameters`() = runBlocking<Unit> {
        val addon = eclipse(plain)
        server.enqueue(json("""{"tracks":[{"id":7,"title":"T","artist":"A","duration":200}]}"""))
        val s = client.search(addon, "a t", limit = 5)
        assertThat((s as AddonResult.Ok).value.tracks.single().id).isEqualTo("7")
        val search = server.takeRequest().requestUrl!!
        assertThat(search.queryParameterNames).containsExactly("q")

        server.enqueue(json("""{"url":"https://cdn.example.org/a.flac","format":"flac","sampleRate":44100,"bitDepth":16}"""))
        val st = client.stream(addon, "7", lossless)
        assertThat((st as AddonResult.Ok).value.tier).isEqualTo(Tier.LOSSLESS)
        val stream = server.takeRequest()
        assertThat(stream.requestUrl!!.encodedPath).isEqualTo("/tok/stream/7")
        assertThat(stream.requestUrl!!.queryParameterNames).containsExactly("quality")
        assertThat(stream.requestUrl!!.queryParameter("quality")).isEqualTo("LOSSLESS")
        assertThat(stream.getHeader("User-Agent")).isEqualTo("Stash/9.9.9")

        // The lossy pass asks HIGH, Data saver LOW.
        server.enqueue(json("""{"url":"https://cdn.example.org/a.mp3","format":"mp3","quality":"320kbps"}"""))
        server.enqueue(json("""{"url":"https://cdn.example.org/b.mp3","format":"mp3","quality":"128kbps"}"""))
        assertThat(client.stream(addon, "7", StreamRequest(Tier.HIGH, Tier.HIGH)) is AddonResult.Ok).isTrue()
        assertThat(client.stream(addon, "7", StreamRequest(Tier.LOW, Tier.LOW)) is AddonResult.Ok).isTrue()
        assertThat(server.takeRequest().requestUrl!!.queryParameter("quality")).isEqualTo("HIGH")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("quality")).isEqualTo("LOW")
    }

    @Test fun `an add-on's own quality select sends its first option`() = runBlocking<Unit> {
        val addon = eclipse(
            """{"id":"q.addon","resources":["search","stream"],"settings":[{"key":"quality","type":"select","label":"Q",""" +
                """"options":[{"value":"HI_RES","label":"Hi"},{"value":"LOW","label":"Lo"}]}]}""",
        )
        server.enqueue(json("""{"url":"https://cdn.example.org/a.flac","codec":"flac"}"""))
        client.stream(addon, "1", lossless)
        assertThat(server.takeRequest().requestUrl!!.queryParameter("quality")).isEqualTo("HI_RES")
    }

    @Test fun `resolve sends title, artist, isrc and length only`() = runBlocking<Unit> {
        val addon = eclipse(plain)
        server.enqueue(json("""{"item":{"id":"x1","title":"T","artist":"A"}}"""))
        val q = ResolveQuery(Descriptor("T", "A", album = "Al", durationMs = 200_000, isrc = "USAAA0000001", explicit = true), minTier = Tier.LOSSLESS)
        assertThat((client.resolve(addon, q) as AddonResult.Ok).value!!.id).isEqualTo("x1")
        val sent = server.takeRequest().requestUrl!!
        assertThat(sent.queryParameterNames).containsExactly("title", "artist", "isrc", "durationMs")
    }

    @Test fun `isrc without resolve asks resolve-isrc, and only with an ISRC`() = runBlocking<Unit> {
        val addon = eclipse("""{"id":"i.addon","name":"I","version":"1","resources":["search","stream","isrc"]}""")
        assertThat((client.resolve(addon, ResolveQuery(Descriptor("T", "A"))) as AddonResult.Ok).value).isNull()
        assertThat(server.requestCount).isEqualTo(0)
        server.enqueue(json("""{"trackId":991}"""))
        server.enqueue(json("null"))
        val q = ResolveQuery(Descriptor("T", "A", isrc = "USAAA0000001"))
        val hit = (client.resolve(addon, q) as AddonResult.Ok).value!!
        assertThat(hit.id).isEqualTo("991")
        assertThat(hit.title).isEqualTo("T")
        assertThat((client.resolve(addon, q) as AddonResult.Ok).value).isNull()
        val sent = server.takeRequest().requestUrl!!
        assertThat(sent.encodedPath).isEqualTo("/tok/resolve-isrc")
        assertThat(sent.queryParameter("isrc")).isEqualTo("USAAA0000001")
    }

    @Test fun `a 200 error with no url is a miss, never a broken answer`() = runBlocking<Unit> {
        server.enqueue(json("""{"error":"not available","notice":"hi"}"""))
        val r = client.stream(eclipse(plain), "1", lossless)
        assertThat(((r as AddonResult.Failed).failure as AddonFailure.Refused).code).isEqualTo(ErrorCode.NOT_FOUND)
        assertThat(gate.events.none { it.startsWith("failure") }).isTrue()
    }

    @Test fun `a lossy answer on the lossless pass is held for the lossy pass`() = runBlocking<Unit> {
        server.enqueue(json("""{"url":"https://cdn.example.org/a.mp3","format":"mp3","quality":"320kbps"}"""))
        val r = client.stream(eclipse(plain), "1", lossless)
        val refused = (r as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(refused.code).isEqualTo(ErrorCode.QUALITY_UNAVAILABLE)
        assertThat(refused.held!!.info.tier).isEqualTo(Tier.HIGH)
    }

    @Test fun `a 503 still preparing waits once in the foreground and never pauses the add-on`() = runBlocking<Unit> {
        server.enqueue(json("""{"error":"preparing"}""", 503).setHeader("Retry-After", "1"))
        server.enqueue(json("""{"url":"https://cdn.example.org/a.flac","codec":"flac"}"""))
        val started = System.nanoTime()
        val r = client.stream(eclipse(plain), "1", lossless)
        assertThat(r is AddonResult.Ok).isTrue()
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isAtLeast(900L)
        assertThat(gate.events.none { it.startsWith("rateLimited") || it.startsWith("failure") }).isTrue()

        // A download doesn't wait: it is retried later.
        server.enqueue(json("{}", 503).setHeader("Retry-After", "20"))
        val d = client.stream(eclipse(plain), "1", lossless, paced = true)
        val f = (d as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(f.preparing).isTrue()
        assertThat(f.isOutage).isTrue()
        assertThat(gate.events.none { it.startsWith("rateLimited") }).isTrue()
    }

    @Test fun `a 503 with a long Retry-After, or a 429, pauses it`() = runBlocking<Unit> {
        server.enqueue(json("{}", 503).setHeader("Retry-After", "120"))
        client.stream(eclipse(plain), "1", lossless)
        server.enqueue(json("{}", 429).setHeader("Retry-After", "60"))
        client.search(eclipse(plain), "x")
        assertThat(gate.events.filter { it.startsWith("rateLimited") }).hasSize(2)
    }

    @Test fun `bitchordAuth and a 401 naming BitChord are refused`() = runBlocking<Unit> {
        server.enqueue(json("""{"id":"b.addon","name":"B","version":"1","resources":["search","stream"],"bitchordAuth":true}"""))
        val m = (client.manifest(base()) as AddonResult.Failed).failure as AddonFailure.InvalidResponse
        assertThat(m.reason).isEqualTo(AddonEclipse.ONLY_BITCHORD)
        server.enqueue(json("{}", 401).setHeader("WWW-Authenticate", "BitChord realm=\"app\""))
        val r = (client.search(eclipse(plain), "x") as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(r.code).isEqualTo(ErrorCode.UNAUTHORIZED)
        assertThat(r.onlyBitChord).isTrue()
    }

    @Test fun `other 4xx is a bad request for that call only, other 5xx unavailable`() = runBlocking<Unit> {
        server.enqueue(json("{}", 418))
        server.enqueue(json("{}", 502))
        assertThat(((client.search(eclipse(plain), "x") as AddonResult.Failed).failure as AddonFailure.Refused).code).isEqualTo(ErrorCode.BAD_REQUEST)
        assertThat(((client.search(eclipse(plain), "x") as AddonResult.Failed).failure as AddonFailure.Refused).code).isEqualTo(ErrorCode.UNAVAILABLE)
        assertThat(gate.events.count { it.startsWith("failure") }).isEqualTo(1)
    }

    @Test fun `a redirect hop to the user's own network is refused for an add-on the user added`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://192.168.1.1/search?q=x"))
        val r = client.search(eclipse(plain), "x")
        assertThat((r as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `an add-on's stream on a private address is refused`() = runBlocking<Unit> {
        server.enqueue(json("""{"url":"https://10.0.0.1/x.flac","codec":"flac"}"""))
        val r = client.stream(eclipse(plain), "1", lossless)
        assertThat(((r as AddonResult.Failed).failure as AddonFailure.InvalidResponse).reason).isEqualTo(AddonEclipse.PRIVATE_ADDRESS)
    }

    @Test fun `downloads honour allowDownloads`() {
        assertThat(eclipse("""{"id":"d.addon","resources":["search","stream"],"allowDownloads":0}""").allowsDownloads).isFalse()
        assertThat(eclipse(plain).allowsDownloads).isTrue()
    }
}
