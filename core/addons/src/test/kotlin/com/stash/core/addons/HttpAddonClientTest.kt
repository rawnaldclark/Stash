package com.stash.core.addons

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.stash.addons.contract.AddonKind
import com.stash.addons.contract.Auth
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.Descriptor
import com.stash.addons.contract.ErrorCode
import com.stash.addons.contract.Manifest
import com.stash.addons.contract.StreamCapabilities
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.addons.contract.TokenPlacement
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * [HttpAddonClient] against a MockWebServer add-on on 127.0.0.1 (http allowed there for tests, SPEC.md 1.1).
 * `runBlocking`, not `runTest`: the client's deadline is a coroutine timeout, and under `runTest`'s
 * virtual time it would end every call that waits on the real server at once.
 */
class HttpAddonClientTest {

    private lateinit var server: MockWebServer
    private val gate = RecordingGate()
    private val signer = RecordingSigner()
    private var firstParty = false
    private val sharedSaw = mutableListOf<String>()

    /** The app's shared client carries interceptors (debug logging prints URLs): the add-on client must drop them. */
    private val shared = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> sharedSaw += "app"; chain.proceed(chain.request()) })
        .build()

    private lateinit var client: HttpAddonClient

    @Before fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        client = HttpAddonClient(shared, gate, object : AddonTrust { override fun isFirstParty(base: String) = firstParty }, signer)
        client.options = AddonValidator.Options(allowLocalHttp = true)
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun base(prefix: String = "") = "http://127.0.0.1:${server.port}/$prefix"

    private fun manifest(
        resources: List<String> = listOf("search", "resolve", "stream", "download"),
        auth: Auth? = null,
    ) = Manifest(
        stash = 1, id = "test-addon", name = "Test", version = "1.0", kind = AddonKind.SERVER,
        resources = resources,
        stream = StreamCapabilities(cors = false, manifests = listOf("none", "dash"), tiers = listOf("lossless", "hires")),
        auth = auth,
    )

    private fun addon(
        resources: List<String> = listOf("search", "resolve", "stream", "download"),
        token: String? = null,
        prefix: String = "",
        settings: Map<String, String> = emptyMap(),
    ) = InstalledAddon(
        base = base(prefix),
        manifest = manifest(resources, auth = token?.let { Auth(TokenPlacement.HEADER) }),
        token = token,
        settings = settings,
    )

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private val flacCd = """{"url":"https://cdn.example/a.flac","manifest":"none","tier":"lossless","cors":false,"codec":"flac","bitDepth":16,"sampleRate":44100}"""
    private val lossless = StreamRequest(Tier.HIRES, Tier.LOSSLESS, listOf(Delivery.NONE, Delivery.DASH))

    // ------------------------------------------------------------------------------------------ routes

    @Test fun `manifest is read from base, without the app's interceptors`() = runBlocking<Unit> {
        server.enqueue(json("""{"stash":1,"id":"x","name":"X","version":"1","kind":"server","resources":["search"]}"""))
        val r = client.manifest(base("tok123/"))
        assertThat((r as AddonResult.Ok).value.id).isEqualTo("x")
        assertThat(server.takeRequest().path).isEqualTo("/tok123/manifest.json")
        assertThat(sharedSaw).isEmpty()
    }

    @Test fun `search sends q, limit and settings, and a header token goes in X-Access-Token`() = runBlocking<Unit> {
        server.enqueue(json("""{"tracks":[{"id":"1","title":"T","artist":"A","albumId":"al","artistId":"ar"}]}"""))
        val r = client.search(addon(token = "s3cret", settings = mapOf("region" to "us")), "daft punk", limit = 5)
        val req = server.takeRequest()
        assertThat(req.requestUrl!!.encodedPath).isEqualTo("/search")
        assertThat(req.requestUrl!!.queryParameter("q")).isEqualTo("daft punk")
        assertThat(req.requestUrl!!.queryParameter("limit")).isEqualTo("5")
        assertThat(req.requestUrl!!.queryParameter("region")).isEqualTo("us")
        assertThat(req.getHeader(Auth.HEADER_NAME)).isEqualTo("s3cret")
        // No album or artist route in the manifest: those ids are dropped (SPEC.md 4.1).
        val t = (r as AddonResult.Ok).value.tracks.single()
        assertThat(t.albumId).isNull()
        assertThat(t.artistId).isNull()
        assertThat(gate.events).containsExactly("admit addon_test-addon false", "answer addon_test-addon")
    }

    @Test fun `the manifest is never asked with the header token`() = runBlocking<Unit> {
        server.enqueue(json("""{"stash":1,"id":"x","name":"X","version":"1","kind":"server","resources":["search"]}"""))
        client.manifest(base())
        assertThat(server.takeRequest().getHeader(Auth.HEADER_NAME)).isNull()
    }

    @Test fun `resolve answers the add-on's item, and null for no match`() = runBlocking<Unit> {
        server.enqueue(json("""{"item":{"id":"td:1","title":"One More Time","artist":"Daft Punk","isrc":"GBDUW0000059","durationMs":320000}}"""))
        server.enqueue(json("""{"item":null}"""))
        val d = Descriptor("One More Time", "Daft Punk", isrc = "GBDUW0000059", durationMs = 320_357)
        assertThat((client.resolve(addon(), d) as AddonResult.Ok).value!!.id).isEqualTo("td:1")
        assertThat((client.resolve(addon(), d) as AddonResult.Ok).value).isNull()
        val q = server.takeRequest().requestUrl!!
        assertThat(q.queryParameter("isrc")).isEqualTo("GBDUW0000059")
        assertThat(q.queryParameter("durationMs")).isEqualTo("320357")
    }

    @Test fun `a resolve answer for another ISRC is rejected`() = runBlocking<Unit> {
        server.enqueue(json("""{"item":{"id":"1","title":"T","artist":"A","isrc":"USAAA0000001"}}"""))
        val r = client.resolve(addon(), Descriptor("T", "A", isrc = "GBBBB0000002"))
        assertThat(((r as AddonResult.Failed).failure as AddonFailure.InvalidResponse).path).isEqualTo("/item/isrc")
        assertThat(gate.events.last()).isEqualTo("failure addon_test-addon")
    }

    @Test fun `stream asks tier, minTier and manifest`() = runBlocking<Unit> {
        server.enqueue(json(flacCd))
        val r = client.stream(addon(), "td:1", lossless)
        assertThat((r as AddonResult.Ok).value.tier).isEqualTo(Tier.LOSSLESS)
        val req = server.takeRequest().requestUrl!!
        assertThat(req.encodedPath).isEqualTo("/stream/td:1")
        assertThat(req.queryParameter("tier")).isEqualTo("hires")
        assertThat(req.queryParameter("minTier")).isEqualTo("lossless")
        assertThat(req.queryParameter("manifest")).isEqualTo("none,dash")
    }

    @Test fun `a stream outside the asked range, or encrypted, is rejected`() = runBlocking<Unit> {
        server.enqueue(json("""{"url":"https://cdn.example/a.m4a","manifest":"none","tier":"high","cors":false,"codec":"aac","bitrate":320000}"""))
        server.enqueue(json("""{"url":"https://cdn.example/a.mpd","manifest":"hls","tier":"lossless","cors":false}"""))
        server.enqueue(json(flacCd.dropLast(1) + ""","encrypted":true}"""))
        assertThat(((client.stream(addon(), "1", lossless) as AddonResult.Failed).failure as AddonFailure.InvalidResponse).path).isEqualTo("/tier")
        assertThat(((client.stream(addon(), "1", lossless) as AddonResult.Failed).failure as AddonFailure.InvalidResponse).path).isEqualTo("/manifest")
        assertThat(((client.stream(addon(), "1", lossless) as AddonResult.Failed).failure as AddonFailure.InvalidResponse).path).isEqualTo("/encrypted")
    }

    @Test fun `an answer that breaks the contract is dropped whole`() = runBlocking<Unit> {
        server.enqueue(json("""{"url":"http://cdn.example/a.flac","manifest":"none","tier":"lossless","cors":false}"""))
        val r = client.stream(addon(), "1", lossless)
        assertThat(((r as AddonResult.Failed).failure as AddonFailure.InvalidResponse).path).isEqualTo("/url")
    }

    @Test fun `not JSON is an invalid response`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("<html>"))
        val r = client.search(addon(), "x")
        assertThat((r as AddonResult.Failed).failure).isInstanceOf(AddonFailure.InvalidResponse::class.java)
    }

    @Test fun `a body over 2 MiB is rejected`() = runBlocking<Unit> {
        server.enqueue(json("""{"tracks":[],"pad":"${"x".repeat(2 * 1024 * 1024)}"}"""))
        val r = client.search(addon(), "x")
        assertThat(((r as AddonResult.Failed).failure as AddonFailure.InvalidResponse).reason).contains("2 MiB")
    }

    // ------------------------------------------------------------------------------------------ errors and the circuit

    @Test fun `429 pauses the add-on for the body's retryAfter`() = runBlocking<Unit> {
        server.enqueue(json("""{"error":{"code":"rate_limited","message":"slow down","retryAfter":30}}""", 429).setHeader("Retry-After", "120"))
        val f = (client.search(addon(), "x") as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(f.code).isEqualTo(ErrorCode.RATE_LIMITED)
        assertThat(f.retryAfterSec).isEqualTo(30)
        assertThat(gate.events.last()).isEqualTo("rateLimited addon_test-addon 30000")
    }

    @Test fun `503 with only a Retry-After header pauses for the header`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "45").setBody("busy"))
        val f = (client.search(addon(), "x") as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(f.code).isEqualTo(ErrorCode.UNAVAILABLE)
        assertThat(gate.events.last()).isEqualTo("rateLimited addon_test-addon 45000")
    }

    @Test fun `503 without Retry-After counts toward the breaker`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503))
        client.search(addon(), "x")
        assertThat(gate.events.last()).isEqualTo("failure addon_test-addon")
    }

    @Test fun `not_found is a miss and an answer, never a failure`() = runBlocking<Unit> {
        server.enqueue(json("""{"error":{"code":"not_found","message":"no"}}""", 404))
        val f = (client.stream(addon(), "1", lossless) as AddonResult.Failed).failure
        assertThat(f.isMiss).isTrue()
        assertThat(gate.events.last()).isEqualTo("answer addon_test-addon")
    }

    @Test fun `an unknown error code is read by its status`() = runBlocking<Unit> {
        server.enqueue(json("""{"error":{"code":"brand_new_code","message":"?"}}""", 451))
        val f = (client.search(addon(), "x") as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(f.code).isEqualTo(ErrorCode.REGION_UNAVAILABLE)
    }

    @Test fun `401 leaves the add-on alone for an hour`() = runBlocking<Unit> {
        server.enqueue(json("""{"error":{"code":"unauthorized","message":"bad token"}}""", 401))
        client.search(addon(), "x")
        assertThat(gate.events.last()).isEqualTo("rateLimited addon_test-addon ${HttpAddonClient.UNAUTHORIZED_PAUSE_MS}")
    }

    @Test fun `a 5xx other than 503 is unavailable whatever its body says`() = runBlocking<Unit> {
        // SPEC.md 7. A 500 whose body says not_found is the add-on failing: never a miss to remember,
        // and it counts toward the breaker instead of resetting it.
        server.enqueue(json("""{"error":{"code":"not_found","message":"no"}}""", 500))
        server.enqueue(json("""{"error":{"code":"bad_request","message":"?"}}""", 502))
        val first = (client.stream(addon(), "1", lossless) as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(first.code).isEqualTo(ErrorCode.UNAVAILABLE)
        assertThat(first.isMiss).isFalse()
        assertThat(first.isOutage).isTrue()
        assertThat(gate.events.last()).isEqualTo("failure addon_test-addon")
        val second = (client.search(addon(), "x") as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(second.code).isEqualTo(ErrorCode.UNAVAILABLE)
        assertThat(gate.events.last()).isEqualTo("failure addon_test-addon")
    }

    @Test fun `quota_exceeded pauses stream and download only`() = runBlocking<Unit> {
        // SPEC.md 7: "pauses metered calls until then". Search, lyrics and the rest go on.
        gate.pauses = true
        server.enqueue(json("""{"error":{"code":"quota_exceeded","message":"spent","retryAfter":600}}""", 429))
        server.enqueue(json("""{"tracks":[]}"""))
        val f = (client.stream(addon(), "1", lossless) as AddonResult.Failed).failure as AddonFailure.Refused
        assertThat(f.code).isEqualTo(ErrorCode.QUOTA_EXCEEDED)
        assertThat(f.isOutage).isFalse()
        assertThat(gate.events.takeLast(2))
            .containsExactly("answer addon_test-addon", "rateLimited addon_test-addon_metered 600000").inOrder()
        assertThat(gate.paused).containsExactly(HttpAddonClient.meteredKey("test-addon"))

        assertThat(client.search(addon(), "x")).isInstanceOf(AddonResult.Ok::class.java)
        assertThat((client.stream(addon(), "1", lossless) as AddonResult.Failed).failure).isEqualTo(AddonFailure.Paused)
        assertThat((client.download(addon(), "1", lossless) as AddonResult.Failed).failure).isEqualTo(AddonFailure.Paused)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `rate_limited pauses the whole add-on`() = runBlocking<Unit> {
        gate.pauses = true
        server.enqueue(json("""{"error":{"code":"rate_limited","message":"slow down","retryAfter":30}}""", 429))
        client.stream(addon(), "1", lossless)
        assertThat(gate.paused).containsExactly(HttpAddonClient.rateKey("test-addon"))
        assertThat((client.search(addon(), "x") as AddonResult.Failed).failure).isEqualTo(AddonFailure.Paused)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `a paused add-on is not asked`() = runBlocking<Unit> {
        gate.admitResult = false
        val r = client.search(addon(), "x")
        assertThat((r as AddonResult.Failed).failure).isEqualTo(AddonFailure.Paused)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `a route the manifest doesn't list is not asked`() = runBlocking<Unit> {
        val r = client.lyrics(addon(resources = listOf("search")), Descriptor("T", "A"))
        assertThat((r as AddonResult.Failed).failure).isEqualTo(AddonFailure.NotOffered)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(gate.events).isEmpty()
    }

    @Test fun `downloads are paced, playback only checks the circuit`() = runBlocking<Unit> {
        server.enqueue(json(flacCd))
        server.enqueue(json(flacCd))
        client.download(addon(), "1", lossless)
        client.stream(addon(), "1", lossless)
        assertThat(gate.events.filter { it.startsWith("admit") })
            .containsExactly(
                "admit addon_test-addon true", "admit addon_test-addon_metered false",
                "admit addon_test-addon false", "admit addon_test-addon_metered false",
            ).inOrder()
    }

    @Test fun `no answer within the route's time is a timeout`() = runBlocking<Unit> {
        // The deadline is read from the clock once; the call gets what is left: 300 ms here.
        var reads = 0
        client.nowMs = { if (reads++ == 0) 0L else HttpAddonClient.Route.SEARCH.timeoutMs - 300 }
        server.enqueue(json("""{"tracks":[]}""").setHeadersDelay(3, TimeUnit.SECONDS))
        val r = client.search(addon(), "x")
        assertThat((r as AddonResult.Failed).failure).isEqualTo(AddonFailure.Timeout)
        assertThat(gate.events.last()).isEqualTo("failure addon_test-addon")
    }

    @Test fun `the deadline also bounds the wait for a connection slot`() = runBlocking<Unit> {
        // OkHttp starts a call's timeout only when its dispatcher runs the call (the app shares it,
        // at most 5 per host). Here the only slot is held by a slow call: the second call must still
        // end at its own deadline, not when the slot frees.
        val oneSlot = OkHttpClient.Builder().dispatcher(Dispatcher().apply { maxRequests = 1 }).build()
        val c = HttpAddonClient(oneSlot, gate, object : AddonTrust { override fun isFirstParty(base: String) = false }, signer)
        c.options = AddonValidator.Options(allowLocalHttp = true)
        // Each call reads the clock for its deadline, then for what is left: 8 s, then 300 ms.
        val t = HttpAddonClient.Route.SEARCH.timeoutMs
        val clock = ArrayDeque(listOf(0L, t - 8_000, 0L, t - 300))
        c.nowMs = { clock.removeFirstOrNull() ?: t }
        server.enqueue(json("""{"tracks":[]}""").setHeadersDelay(5, TimeUnit.SECONDS))
        server.enqueue(json("""{"tracks":[]}"""))

        val slow = async(start = CoroutineStart.UNDISPATCHED) { c.search(addon(), "slow") }
        val started = System.nanoTime()
        val r = c.search(addon(), "queued")
        val tookMs = (System.nanoTime() - started) / 1_000_000
        slow.cancel()

        assertThat((r as AddonResult.Failed).failure).isEqualTo(AddonFailure.Timeout)
        assertThat(tookMs).isLessThan(3_000L)
    }

    @Test fun `a refused connection is a network failure`() = runBlocking<Unit> {
        val dead = addon().copy(base = "http://127.0.0.1:1/")
        val r = client.search(dead, "x")
        assertThat((r as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)
    }

    // ------------------------------------------------------------------------------------------ signing

    @Test fun `install headers go to first-party add-ons only, on stream and download`() = runBlocking<Unit> {
        firstParty = true
        server.enqueue(json(flacCd))
        server.enqueue(json("""{"tracks":[]}"""))
        client.stream(addon(), "td:9", lossless)
        client.search(addon(), "x")
        assertThat(server.takeRequest().getHeader("X-Stash-Auth")).isEqualTo("sig:td:9")
        assertThat(server.takeRequest().getHeader("X-Stash-Auth")).isNull()
        assertThat(signer.signed).containsExactly("td:9")
    }

    @Test fun `a third-party add-on is never signed`() = runBlocking<Unit> {
        firstParty = false
        server.enqueue(json(flacCd))
        client.stream(addon(), "td:9", lossless)
        assertThat(server.takeRequest().getHeader("X-Stash-Auth")).isNull()
        assertThat(signer.signed).isEmpty()
    }

    @Test fun `a signed 401 naming the server's clock is signed again once`() = runBlocking<Unit> {
        firstParty = true
        signer.adopt = true
        server.enqueue(json("""{"error":{"code":"unauthorized","message":"skew"}}""", 401).setHeader("X-Stash-Server-Time", "1893456000"))
        server.enqueue(json(flacCd))
        val r = client.stream(addon(), "td:9", lossless)
        assertThat(r).isInstanceOf(AddonResult.Ok::class.java)
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(signer.signed).containsExactly("td:9", "td:9")
    }

    @Test fun `an unsigned add-on's 401 never moves the signing clock`() = runBlocking<Unit> {
        firstParty = false
        signer.adopt = true
        server.enqueue(json("""{"error":{"code":"unauthorized","message":"no"}}""", 401).setHeader("X-Stash-Server-Time", "1"))
        client.stream(addon(), "1", lossless)
        assertThat(signer.adopted).isEqualTo(0)
        assertThat(server.requestCount).isEqualTo(1)
    }

    // ------------------------------------------------------------------------------------------ redirects

    @Test fun `a header token and the signature never follow a redirect to another origin`() = runBlocking<Unit> {
        val other = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            firstParty = true
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${other.port}/stream/1"))
            other.enqueue(json(flacCd))
            val r = client.stream(addon(token = "s3cret"), "1", lossless)
            assertThat(r).isInstanceOf(AddonResult.Ok::class.java)
            assertThat(server.takeRequest().getHeader(Auth.HEADER_NAME)).isEqualTo("s3cret")
            val moved = other.takeRequest()
            assertThat(moved.getHeader(Auth.HEADER_NAME)).isNull()
            assertThat(moved.getHeader("X-Stash-Auth")).isNull()
        } finally {
            other.shutdown()
        }
    }

    @Test fun `a redirect to plain http elsewhere is refused`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.com/x"))
        val r = client.search(addon(), "x")
        assertThat((r as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)
    }

    @Test fun `at most 3 redirects`() = runBlocking<Unit> {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/search?q=x")) }
        val r = client.search(addon(), "x")
        assertThat((r as AddonResult.Failed).failure).isEqualTo(AddonFailure.Network)
        assertThat(server.requestCount).isEqualTo(4)
    }

    // ------------------------------------------------------------------------------------------ secrets

    @Test fun `an installed add-on never prints its base or token`() {
        val a = addon(token = "s3cret", prefix = "pathtoken/")
        assertThat(a.toString()).doesNotContain("s3cret")
        assertThat(a.toString()).doesNotContain("pathtoken")
        assertThat(a.toString()).doesNotContain("127.0.0.1")
    }

    @Test fun `a token a request can't carry is refused when the add-on is made, without repeating it`() {
        for (bad in listOf("s3cret\nX-Evil: 1", "s3cret\r", "s3cret\u0000", "tökén-s3cret", "")) {
            val e = runCatching { addon(token = bad) }.exceptionOrNull()
            assertWithMessage("token %s", bad.length).that(e).isInstanceOf(IllegalArgumentException::class.java)
            if (bad.isNotEmpty()) assertThat(e!!.stackTraceToString()).doesNotContain("s3cret")
        }
        assertThat(addon(token = "ok-Token_1.2~3").token).isEqualTo("ok-Token_1.2~3")
    }

    @Test fun `a header value a request can't carry is a network failure that never repeats it`() = runBlocking<Unit> {
        // Why the client never logs that exception: OkHttp's message names the value, and neither
        // X-Access-Token nor X-Stash-* is on its list of sensitive headers.
        val raw = runCatching { Request.Builder().header(Auth.HEADER_NAME, "SECRET\nx") }.exceptionOrNull()
        assertThat(raw!!.message).contains("SECRET")

        // A token can't get this far any more (InstalledAddon refuses it); a signer's header still can.
        val lines = mutableListOf<String>()
        for (secret in listOf("SECRET\nX-Evil: 1", "SECRÉT")) {
            val leaky = object : AddonInstallSigner {
                override suspend fun sign(builder: Request.Builder, payload: String): Boolean {
                    builder.header("X-Stash-Supporter", secret)
                    return true
                }
                override fun adoptServerTime(response: Response): Boolean = false
            }
            val c = HttpAddonClient(shared, gate, object : AddonTrust { override fun isFirstParty(base: String) = true }, leaky)
            c.options = AddonValidator.Options(allowLocalHttp = true)
            c.logHook = { lines += it }
            val r = runCatching { c.stream(addon(), "1", lossless) }
            assertThat(r.exceptionOrNull()).isNull()
            val f = (r.getOrThrow() as AddonResult.Failed).failure
            assertThat(f).isEqualTo(AddonFailure.Network)
            assertThat(f.toString()).doesNotContain("SECR")
            assertThat(gate.events.last()).isEqualTo("failure addon_test-addon")
        }
        assertThat(lines).hasSize(2)
        lines.forEach { assertThat(it).doesNotContain("SECR") }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun `add-on text is logged cut short and without control characters`() {
        assertThat(HttpAddonClient.loggable("/headers/X-A\nforged line\u0000\u0085end")).isEqualTo("/headers/X-A forged line  end")
        assertThat(HttpAddonClient.loggable("k".repeat(500))).hasLength(120)
    }

    @Test fun `baseOf strips manifest json`() {
        assertThat(HttpAddonClient.baseOf("https://relay.example/tok/manifest.json")).isEqualTo("https://relay.example/tok/")
        assertThat(HttpAddonClient.baseOf("https://relay.example/tok/")).isEqualTo("https://relay.example/tok/")
        assertThat(HttpAddonClient.baseOf("https://relay.example/tok")).isNull()
        assertThat(HttpAddonClient.baseOf("not a url/")).isNull()
    }

    // ------------------------------------------------------------------------------------------ fakes

    /** Records what the client tells the circuit. [pauses]: also refuses a key once it was rate limited. */
    class RecordingGate : AddonRateGate {
        var admitResult = true
        var pauses = false
        val paused = mutableSetOf<String>()
        val events = mutableListOf<String>()
        override suspend fun admit(key: String, paced: Boolean): Boolean {
            events += "admit $key $paced"
            return admitResult && !(pauses && key in paused)
        }
        override suspend fun onAnswer(key: String) { events += "answer $key" }
        override suspend fun onRateLimited(key: String, retryAfterMs: Long?) {
            events += "rateLimited $key $retryAfterMs"
            paused += key
        }
        override suspend fun onFailure(key: String) { events += "failure $key" }
    }

    class RecordingSigner : AddonInstallSigner {
        val signed = mutableListOf<String>()
        var adopt = false
        var adopted = 0
        override suspend fun sign(builder: Request.Builder, payload: String): Boolean {
            signed += payload
            builder.header("X-Stash-Auth", "sig:$payload")
            return true
        }
        override fun adoptServerTime(response: Response): Boolean { adopted++; return adopt }
    }
}
