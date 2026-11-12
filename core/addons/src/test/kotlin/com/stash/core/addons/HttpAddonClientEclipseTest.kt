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

    private fun preparing() = json("""{"error":"preparing"}""", 503).setHeader("Retry-After", "5")
    private val flacAnswer = """{"url":"https://cdn.example.org/a.flac","codec":"flac"}"""

    /**
     * review-882 M4. A server that is simply overloaded answers 503 with a short Retry-After for
     * every song. Read as "still preparing" each time, it cost every play a wait (twice, with the
     * lossy pass) and was never paused.
     */
    @Test fun `after two wasted waits in a row, still preparing is read as unavailable and pauses the add-on`() = runBlocking<Unit> {
        client.preparingWaitCapMs = 30
        val addon = eclipse(plain)
        fun waited() = gate.events.count { it.startsWith("rateLimited") }

        // Two plays whose wait did not help: asked, waited, asked again, still preparing.
        for (song in listOf("1", "2")) {
            server.enqueue(preparing())
            server.enqueue(preparing())
            val r = (client.stream(addon, song, lossless) as AddonResult.Failed).failure as AddonFailure.Refused
            assertThat(r.preparing).isTrue()
        }
        assertThat(server.requestCount).isEqualTo(4)
        assertThat(waited()).isEqualTo(0)

        // The third: one request, no wait, and the add-on is paused for its Retry-After like any other 503.
        server.enqueue(preparing())
        val third = (client.stream(addon, "3", lossless) as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(third.preparing).isFalse()
        assertThat(third.code).isEqualTo(ErrorCode.UNAVAILABLE)
        assertThat(server.requestCount).isEqualTo(5)
        assertThat(gate.events.last()).isEqualTo("rateLimited ${HttpAddonClient.rateKey("com.example.plain")} 5000")

        // The first stream it answers ends that: the next "preparing" is waited for again.
        server.enqueue(json(flacAnswer))
        assertThat(client.stream(addon, "4", lossless)).isInstanceOf(AddonResult.Ok::class.java)
        server.enqueue(preparing())
        server.enqueue(json(flacAnswer))
        assertThat(client.stream(addon, "5", lossless)).isInstanceOf(AddonResult.Ok::class.java)
        assertThat(server.requestCount).isEqualTo(8)
        assertThat(waited()).isEqualTo(1)
    }

    @Test fun `the lossy pass of a play whose lossless pass waited does not wait again`() = runBlocking<Unit> {
        // A long cap: a second wait would show.
        client.preparingWaitCapMs = 400
        val addon = eclipse(plain)
        server.enqueue(preparing().setHeader("Retry-After", "1"))
        server.enqueue(preparing())
        server.enqueue(preparing())
        client.stream(addon, "1", lossless)
        val started = System.nanoTime()
        val lossy = (client.stream(addon, "1", StreamRequest(Tier.HIGH, Tier.HIGH)) as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(lossy.preparing).isTrue()
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(300L)
        // Asked once, not twice.
        assertThat(server.requestCount).isEqualTo(3)
        // Another song is another wait.
        server.enqueue(preparing())
        server.enqueue(json(flacAnswer))
        assertThat(client.stream(addon, "2", lossless)).isInstanceOf(AddonResult.Ok::class.java)
        assertThat(server.requestCount).isEqualTo(5)
    }

    // ------------------------------------------------------------------------------------------ an answer that does not say what it is (review-882 J5)

    private val high = StreamRequest(Tier.HIGH, Tier.HIGH)
    private val low = StreamRequest(Tier.LOW, Tier.LOW)

    @Test fun `a lossy answer that states no quality is taken at the 320 setting and refused under Data saver`() = runBlocking<Unit> {
        val addon = eclipse(plain)
        for (bare in listOf("""{"url":"https://cdn.example.org/a"}""", """{"url":"https://cdn.example.org/a.mp3","format":"mp3"}""")) {
            server.enqueue(json(bare))
            assertThat(client.stream(addon, "1", high)).isInstanceOf(AddonResult.Ok::class.java)

            server.enqueue(json(bare))
            val saver = (client.stream(addon, "1", low) as AddonResult.Failed).failure as AddonFailure.Refused
            assertThat(saver.code).isEqualTo(ErrorCode.QUALITY_UNAVAILABLE)

            // On the lossless pass it is held, and the lossy pass of the same play may use it.
            server.enqueue(json(bare))
            val held = (client.stream(addon, "1", lossless) as AddonResult.Failed).failure as AddonFailure.Refused
            assertThat(held.code).isEqualTo(ErrorCode.QUALITY_UNAVAILABLE)
            assertThat(HttpAddonClient.answers(AddonDialect.ECLIPSE, high, held.held!!.info)).isTrue()
            assertThat(HttpAddonClient.answers(AddonDialect.ECLIPSE, low, held.held!!.info)).isFalse()
        }
    }

    @Test fun `a lossy answer that says it is under 256 kbps keeps its low both ways`() = runBlocking<Unit> {
        val addon = eclipse(plain)
        for (stated in listOf(
            """{"url":"https://cdn.example.org/a.mp3","format":"mp3","quality":"128kbps"}""",
            """{"url":"https://cdn.example.org/a.mp3","format":"mp3","bitrate":128}""",
            """{"url":"https://cdn.example.org/a.mp3","format":"mp3","quality":"LOW"}""",
        )) {
            server.enqueue(json(stated))
            val refused = (client.stream(addon, "1", high) as AddonResult.Failed).failure as AddonFailure.Refused
            assertThat(refused.code).isEqualTo(ErrorCode.QUALITY_UNAVAILABLE)
            server.enqueue(json(stated))
            assertThat(client.stream(addon, "1", low)).isInstanceOf(AddonResult.Ok::class.java)
        }
    }

    @Test fun `only an Eclipse-family answer gets that reading - a v1 answer says its tier itself`() {
        val bare = AddonEclipse.streamInfo(Json.parseToJsonElement("""{"url":"https://cdn.example.org/a"}"""))
        assertThat(bare.tier).isEqualTo(Tier.LOW)
        assertThat(AddonEclipse.tierIsDefault(bare)).isTrue()
        assertThat(HttpAddonClient.answers(AddonDialect.V1, high, bare)).isFalse()
        assertThat(HttpAddonClient.answers(AddonDialect.V1, low, bare)).isTrue()
        // A delivery that was not asked for is refused whatever the tier.
        assertThat(HttpAddonClient.answers(AddonDialect.ECLIPSE, StreamRequest(Tier.HIGH, Tier.HIGH, listOf(Delivery.DASH)), bare)).isFalse()
        // Lossless is never "unlabelled": its codec says what it is.
        val flac = AddonEclipse.streamInfo(Json.parseToJsonElement(flacAnswer))
        assertThat(AddonEclipse.tierIsDefault(flac)).isFalse()
        assertThat(HttpAddonClient.answers(AddonDialect.ECLIPSE, high, flac)).isFalse()
    }

    // ------------------------------------------------------------------------------------------ a search that did not answer (review-882 M3)

    @Test fun `a 200 search answer with no result list at all is the add-on failing, not an empty result`() = runBlocking<Unit> {
        val addon = eclipse(plain)
        server.enqueue(json("""{"error":"upstream timeout"}"""))
        val failed = (client.search(addon, "x") as AddonResult.Failed).failure
        assertThat((failed as AddonFailure.Refused).code).isEqualTo(ErrorCode.UNAVAILABLE)
        assertThat(failed.isMiss).isFalse()
        assertThat(failed.isOutage).isTrue()
        // It counts toward the breaker, as an add-on that cannot answer should.
        assertThat(gate.events.last()).startsWith("failure")

        // A search that found no songs is still an answer.
        server.enqueue(json("""{"tracks":[]}"""))
        server.enqueue(json("""{"albums":[],"artists":[]}"""))
        assertThat((client.search(addon, "x") as AddonResult.Ok).value.tracks).isEmpty()
        assertThat((client.search(addon, "x") as AddonResult.Ok).value.tracks).isEmpty()
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

    /**
     * A failed call and one request prove nothing by themselves: had the hop been followed, a
     * machine with no route to 192.168.1.1 fails the connect at once, which is also `Network` and
     * one request (review-882 M1). So: the client's own log line says why, and a second server
     * that an allowed hop would have reached is never asked. (`AddonMediaClientTest` measures the
     * same from the socket side: no connection attempt at all.)
     */
    @Test fun `a redirect hop to the user's own network is refused for an add-on the user added`() = runBlocking<Unit> {
        val logged = mutableListOf<String>()
        client.logHook = { logged += it }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://192.168.1.1/search?q=x"))
        val r = client.search(eclipse(plain), "x")
        assertThat((r as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(logged).contains("com.example.plain search: redirect refused (private address)")
    }

    @Test fun `a redirect hop the rules allow is followed - the refusal above is the rule's, not a dead route`() = runBlocking<Unit> {
        val other = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${other.port}/elsewhere/search?q=x"))
            other.enqueue(json("""{"tracks":[{"id":"7","title":"T","artist":"A"}]}"""))
            val r = client.search(eclipse(plain), "x")
            assertThat((r as AddonResult.Ok).value.tracks.single().id).isEqualTo("7")
            assertThat(other.requestCount).isEqualTo(1)
        } finally {
            other.shutdown()
        }
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
