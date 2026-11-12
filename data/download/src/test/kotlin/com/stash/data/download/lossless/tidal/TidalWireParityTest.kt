package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.AddonValidator
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
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
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Add-ons phase 2 exit test (plan §7.1 B7): the same relay answers, served once as the legacy
 * relay serves them and once through the v1 face ([V1FacePort], a hand port of
 * `stash-addon-tidal/src/faces/v1.js` at the commit it names), give the same [TidalLookup], the
 * same `addon_refs` rows, the same allowance and the same busy circuit, whichever wire the Tidal
 * rung uses.
 *
 * **Where the wires differ** (review-881 M4). None of these changes which song is picked or which
 * file plays; each is pinned by a `difference - ...` test at the end of this class, so a change to
 * either wire that moves one shows up here:
 *
 * | Case | Legacy wire | v1 wire |
 * |---|---|---|
 * | A 503 with no `Retry-After` that the Worker didn't write (a CDN's error page) | busy 5 minutes | a failed lookup, no pause |
 * | A stream answer the app can't use (a codec other than FLAC or AAC) | a 14-day miss | the face answers 503: a failed lookup, nothing cached |
 * | "artist title" over 200 characters | sent whole | cut at 200 (SPEC.md 4.2's limit, which the face enforces) |
 * | One malformed row in a search answer | the other rows are read | the whole answer is dropped (reject, don't repair): a failed lookup |
 * | The relay reports 0 for bit depth, rate or bitrate | 0 is shown | the face fills in 16 / 44100 / 1411 |
 * | The longest wait on a hung server | 10 s search, 10 s stream | 10 s search, 15 s stream (the contract's timeouts) |
 *
 * Two more are decisions, not accidents, and have their own tests in `TidalV1WirePolicyTest`: at
 * the CD quality setting the v1 wire asks for CD FLAC only (decision D15), and a v1 face that
 * fails hard three times in a row is set aside for the legacy relay (review-881 M7).
 */
class TidalWireParityTest {

    private val legacyServer = MockWebServer()
    private val v1Server = MockWebServer()
    private var now = System.currentTimeMillis()
    private val exp get() = now / 1000 + 3600
    private val reset get() = LosslessQuota.nextUtcMidnightSec(now)

    /** The relay's answers; each scenario sets them. */
    @Volatile private var search: RelayAnswer = RelayAnswer(200, "{\"tracks\":[]}")
    @Volatile private var stream: (Map<String, String>) -> RelayAnswer = { RelayAnswer(404, "{\"error\":\"not_available\"}") }

    private val legacySearches = AtomicInteger()
    private val v1Searches = AtomicInteger()

    /** The `q` each server was last searched with. */
    @Volatile private var legacyQ: String? = null
    @Volatile private var v1Q: String? = null

    /** When set, both hosts answer every request with this instead (a CDN's own page, in front of the Worker). */
    @Volatile private var inFront: (() -> MockResponse)? = null

    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky", isrc = "USQX91300108", durationMs = 248_000)

    /** One side of the comparison: a client, its rows and its allowance. */
    private class Side(val client: TidalClient, val dao: FakeAddonRefDao, val quota: LosslessQuota)

    private lateinit var legacy: Side
    private lateinit var v1: Side

    @Before fun setUp() {
        legacyServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                inFront?.let { return it() }
                return when {
                    url.encodedPath == "/search" -> { legacySearches.incrementAndGet(); legacyQ = url.queryParameter("q"); search.raw() }
                    url.encodedPath.startsWith("/stream/") ->
                        stream(listOfNotNull(url.queryParameter("aac")?.let { "aac" to it }, url.queryParameter("hires")?.let { "hires" to it }).toMap()).raw()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        v1Server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val route = url.encodedPath.removePrefix("/v1/$TOKEN/")
                inFront?.let { return it() }
                return when {
                    route == "manifest.json" -> json(200, V1FacePort.MANIFEST)
                    route == "search" -> { v1Searches.incrementAndGet(); v1Q = url.queryParameter("q"); V1FacePort.search(search, url.queryParameter("limit")) }
                    route.startsWith("stream/") -> V1FacePort.stream(url, stream)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        legacyServer.start()
        v1Server.start()
        legacy = side(addon = false)
        v1 = side(addon = true)
    }

    @After fun tearDown() {
        legacyServer.shutdown()
        v1Server.shutdown()
    }

    /** Stash's relay in the config for both; the v1 side also has the first-party Tidal add-on loaded (the D9 switch). */
    private fun side(addon: Boolean): Side {
        val prefs: LosslessSourcePreferences = mockk {
            every { bitchordEndpoint } returns MutableStateFlow(null)
            coEvery { bitchordEndpointNow() } returns null
            coEvery { bitchordAccessTokenNow() } returns null
        }
        val config: LosslessConfigFetcher = mockk {
            every { tidalRelay } returns MutableStateFlow(TidalRelay(legacyServer.url("/").toString().trimEnd('/'), TOKEN))
            every { relayKey } returns MutableStateFlow("k-secret")
            coEvery { installId() } returns "install-1"
        }
        val registry = FixedRegistry()
        if (addon) {
            val base = v1Server.url("/v1/$TOKEN/").toString()
            val manifest = AddonValidator.anyManifest(Json.parseToJsonElement(V1FacePort.MANIFEST), AddonValidator.Options(allowLocalHttp = true))
            registry.state.value = listOf(tidalAddon(base, manifest))
        }
        val dao = FakeAddonRefDao()
        val quota = LosslessQuota.inMemory { now }
        val client = tidalClient(
            OkHttpClient(), prefs, config, dao, RelaySigner(config, supporter(), appVersionProvider()), quota, registry = registry,
        ).also { it.nowMs = { now } }
        return Side(client, dao, quota)
    }

    /** Runs [ask] on both sides and checks every observable the rung has. */
    private fun same(ask: suspend (TidalClient) -> TidalLookup): TidalLookup = runBlocking {
        val a = ask(legacy.client)
        val b = ask(v1.client)
        assertThat(b).isEqualTo(a)
        assertThat(v1.dao.rows.toMap()).isEqualTo(legacy.dao.rows.toMap())
        assertThat(v1.quota.current()).isEqualTo(legacy.quota.current())
        assertThat(v1.quota.isSpent()).isEqualTo(legacy.quota.isSpent())
        assertThat(v1.client.isBusy()).isEqualTo(legacy.client.isBusy())
        a
    }

    private fun quotaHeaders(used: Int) = mapOf("X-Stash-Lossless" to "$used/300", "X-Stash-Lossless-Reset" to "$reset")

    private fun searchHit(quality: String = "HI_RES_LOSSLESS") = RelayAnswer(
        200,
        """{"tracks":[
            {"id":"td:363236466","title":"Get Lucky","artist":"Daft Punk","album":"Random Access Memories","duration":248,
             "artwork":"https://resources.tidal.com/images/a/b/c/1280x1280.jpg","audioQuality":"$quality","isrc":"USQX91300108","explicit":false},
            {"id":"td:28048259","title":"Get Lucky (Radio Edit)","artist":"Daft Punk","album":"Get Lucky","duration":248,
             "audioQuality":"LOSSLESS","isrc":"USQX91300104","explicit":false},
            {"id":"td:1550546","title":"One More Time","artist":"Daft Punk","duration":320,"audioQuality":"LOSSLESS"}
        ]}""",
    )

    private fun hiResDash(used: Int = 7) = RelayAnswer(
        200,
        """{"url":"https://relay.example/dash/td:363236466.mpd?exp=$exp&sig=ab12","codec":"flac","container":"fmp4","manifest":"dash","bitDepth":24,"sampleRate":96000,"bitrate":4608}""",
        quotaHeaders(used),
    )

    private fun cdFlac(used: Int = 8) = RelayAnswer(
        200,
        """{"url":"https://lgf.audio.tidal.com/mediatracks/a.flac?token=t1","codec":"flac","container":"flac","manifest":"none","bitDepth":16,"sampleRate":44100,"bitrate":1411,"expiresAt":${exp - 600}}""",
        quotaHeaders(used),
    )

    private fun aac(used: Int = 9) = RelayAnswer(
        200,
        """{"url":"https://sp-ad-cf.audio.tidal.com/mediatracks/a.mp4?token=t2","codec":"aac","codecs":"mp4a.40.2","container":"mp4","manifest":"none","bitDepth":0,"sampleRate":44100,"bitrate":320,"expiresAt":${exp - 900}}""",
        quotaHeaders(used),
    )

    @Test fun `Hi-Res DASH - the same stream, match, row and allowance`() {
        search = searchHit()
        stream = { hiResDash() }
        val found = same { it.lookup(query) } as TidalLookup.Found
        assertThat(found.stream.isDash).isTrue()
        assertThat(found.stream.bitDepth to found.stream.sampleRateHz).isEqualTo(24 to 96_000)
        assertThat(found.stream.trackId).isEqualTo("td:363236466")
        assertThat(legacy.quota.current()?.used).isEqualTo(7)
        // The kept match: the second play asks /stream only, on both wires.
        same { it.lookup(query) }
        assertThat(v1Searches.get() to legacySearches.get()).isEqualTo(1 to 1)
    }

    @Test fun `CD progressive FLAC - its expiry from the answer`() {
        search = searchHit(quality = "LOSSLESS")
        stream = { cdFlac() }
        val found = same { it.lookup(query) } as TidalLookup.Found
        assertThat(found.stream.isDash).isFalse()
        assertThat(found.stream.codec).isEqualTo("flac")
    }

    @Test fun `AAC fallback when Tidal has no FLAC - and the lossless miss it stamps`() {
        search = searchHit(quality = "LOSSLESS")
        stream = { p -> if (p["aac"] == "1") aac() else RelayAnswer(404, """{"error":"lossless_unavailable"}""") }
        val found = same { it.lookup(query, allowAac = true) } as TidalLookup.Found
        assertThat(found.stream.codec).isEqualTo("aac")
        assertThat(found.stream.bitrateKbps).isEqualTo(320)
    }

    @Test fun `AAC 320 download mode asks aac only on both`() {
        search = searchHit()
        stream = { p -> if (p["aac"] == "only" && p["hires"] == "0") aac() else RelayAnswer(500, "{}") }
        val found = same { it.lookup(query, allowAac = true, aacOnly = true) } as TidalLookup.Found
        assertThat(found.stream.codec).isEqualTo("aac")
    }

    @Test fun `no confident match - a cached no-match on both`() {
        search = RelayAnswer(200, """{"tracks":[{"id":"td:1550546","title":"One More Time","artist":"Daft Punk","duration":320,"audioQuality":"LOSSLESS"}]}""")
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.NoMatch)
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.NoMatch)
        assertThat(v1Searches.get() to legacySearches.get()).isEqualTo(1 to 1)
    }

    @Test fun `an empty search`() {
        search = RelayAnswer(200, """{"tracks":[]}""")
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.NoMatch)
    }

    @Test fun `lossless_unavailable is the 14-day miss on both`() {
        search = searchHit()
        stream = { RelayAnswer(404, """{"error":"lossless_unavailable"}""") }
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.NoMatch)
    }

    @Test fun `not_available is the short miss on both`() {
        search = searchHit()
        stream = { RelayAnswer(404, """{"error":"not_available"}""") }
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.NoMatch)
    }

    @Test fun `a track Tidal doesn't offer here (region) is the short miss on both`() {
        search = searchHit()
        stream = { RelayAnswer(404, """{"error":"not_available"}""", tag = "rights") }
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.NoMatch)
    }

    @Test fun `a busy pool pauses the rung for the relay's Retry-After on both`() {
        search = searchHit()
        stream = { RelayAnswer(503, """{"error":"busy"}""", mapOf("Retry-After" to "15")) }
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.Busy(now + 15_000))
    }

    @Test fun `a paused relay pauses the rung for ten minutes on both`() {
        search = RelayAnswer(503, """{"error":"tidal_relay_paused"}""", mapOf("Retry-After" to "600"))
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.Busy(now + 600_000))
    }

    @Test fun `the day's allowance spent - Spent and the same recorded allowance`() {
        search = searchHit()
        stream = {
            RelayAnswer(
                429,
                """{"error":"daily_lossless_cap","used":300,"limit":300,"resetsAt":$reset}""",
                mapOf("Retry-After" to "3600") + quotaHeaders(300),
            )
        }
        val spent = same { it.lookup(query) }
        assertThat(spent).isInstanceOf(TidalLookup.Spent::class.java)
        assertThat(legacy.quota.isSpent()).isTrue()
        // And the next lookup asks nothing on either.
        same { it.lookup(query) }
    }

    @Test fun `a network's daily cap is a busy hour on both`() {
        search = searchHit()
        stream = { RelayAnswer(429, """{"error":"network_daily_cap"}""", mapOf("Retry-After" to "3600")) }
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.Busy(now + 3_600_000))
    }

    @Test fun `a relay 500 is a failed lookup on both`() {
        search = searchHit()
        stream = { RelayAnswer(500, """{"error":"internal"}""") }
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.Failed)
    }

    @Test fun `a cached speculative lookup needs the relay on both`() {
        assertThat(same { it.lookup(query, cacheOnly = true) }).isEqualTo(TidalLookup.NeedsRelay)
    }

    /**
     * A track Tidal refuses (the relay's 502). The face used to answer it as `unavailable` 503
     * with `retryAfter: 15`, which reads exactly like a busy pool: the v1 wire paused Tidal on the
     * whole phone for 15 s where the legacy wire failed that one song (review-881 M3). The face now
     * keeps the status 502 (stash-addon-tidal 40ab038), and the two agree.
     */
    @Test fun `a track Tidal refuses (relay 502) fails that one song on both, and pauses nothing`() {
        search = searchHit()
        stream = { RelayAnswer(502, """{"error":"upstream"}""") }
        assertThat(same { it.lookup(query) }).isEqualTo(TidalLookup.Failed)
        assertThat(v1.client.isBusy()).isFalse()
        assertThat(legacy.client.isBusy()).isFalse()
        // The same song is asked again at once on both: nothing was paused, nothing remembered.
        stream = { cdFlac() }
        assertThat(same { it.lookup(query) }).isInstanceOf(TidalLookup.Found::class.java)
    }

    // ------------------------------------------------------------------ where the wires differ (review-881 M4)

    @Test fun `difference - a 503 with no Retry-After from in front of the Worker`() = runBlocking<Unit> {
        inFront = { MockResponse().setResponseCode(503).setBody("<html>Service Unavailable</html>") }
        assertThat(legacy.client.lookup(query)).isEqualTo(TidalLookup.Busy(now + TidalClient.BUSY_BACKOFF_MS))
        assertThat(v1.client.lookup(query)).isEqualTo(TidalLookup.Failed)
        assertThat(legacy.client.isBusy()).isTrue()
        assertThat(v1.client.isBusy()).isFalse()
    }

    @Test fun `difference - a codec the app can't play`() = runBlocking<Unit> {
        search = searchHit()
        stream = { RelayAnswer(200, """{"url":"https://lgf.audio.tidal.com/mediatracks/a.m4a?token=t9","codec":"alac","container":"mp4","manifest":"none"}""") }
        // Legacy: the answer is read, refused by the client, and remembered as a miss (14 days).
        assertThat(legacy.client.lookup(query)).isEqualTo(TidalLookup.NoMatch)
        assertThat(legacy.dao.rows.values.single().losslessMissAt).isEqualTo(now)
        // v1: the face cannot read it and answers 503 with no retryAfter: a failed lookup, nothing remembered.
        assertThat(v1.client.lookup(query)).isEqualTo(TidalLookup.Failed)
        assertThat(v1.dao.rows.values.single().losslessMissAt).isNull()
        assertThat(v1.client.isBusy()).isFalse()
    }

    @Test fun `difference - a search of more than 200 characters`() = runBlocking<Unit> {
        val long = query.copy(title = "Get Lucky " + "x".repeat(300), isrc = null)
        legacy.client.lookup(long)
        v1.client.lookup(long)
        assertThat(legacyQ).isEqualTo("${long.artist} ${long.title}")
        assertThat(v1Q).isEqualTo("${long.artist} ${long.title}".take(200))
    }

    @Test fun `difference - one malformed search row`() = runBlocking<Unit> {
        // The second row's artwork has a space in it: no URL by the contract's rule.
        search = RelayAnswer(
            200,
            """{"tracks":[
                {"id":"td:363236466","title":"Get Lucky","artist":"Daft Punk","duration":248,"audioQuality":"LOSSLESS","isrc":"USQX91300108"},
                {"id":"td:1550546","title":"One More Time","artist":"Daft Punk","duration":320,"audioQuality":"LOSSLESS",
                 "artwork":"https://resources.tidal.com/images/a b/1280x1280.jpg"}
            ]}""",
        )
        stream = { cdFlac() }
        assertThat(legacy.client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(v1.client.lookup(query)).isEqualTo(TidalLookup.Failed)
        assertThat(v1.dao.rows).isEmpty()
    }

    @Test fun `difference - a FLAC answer with no numbers`() = runBlocking<Unit> {
        search = searchHit(quality = "LOSSLESS")
        stream = { RelayAnswer(200, """{"url":"https://lgf.audio.tidal.com/mediatracks/a.flac?token=t1","codec":"flac","container":"flac","manifest":"none","bitDepth":0,"sampleRate":0,"bitrate":0}""") }
        val a = (legacy.client.lookup(query) as TidalLookup.Found).stream
        val b = (v1.client.lookup(query) as TidalLookup.Found).stream
        assertThat(Triple(a.bitDepth, a.sampleRateHz, a.bitrateKbps)).isEqualTo(Triple(0, 0, 0))
        assertThat(Triple(b.bitDepth, b.sampleRateHz, b.bitrateKbps)).isEqualTo(Triple(16, 44_100, 1411))
        // The same file either way.
        assertThat(b.url).isEqualTo(a.url)
        assertThat(b.codec).isEqualTo(a.codec)
    }

    /** By their constants: waiting 15 s on a hung socket would only test OkHttp. */
    @Test fun `difference - the longest wait on a hung server`() {
        assertThat(TidalClient.TIMEOUT_S).isEqualTo(10L)
        assertThat(com.stash.core.addons.HttpAddonClient.Route.SEARCH.timeoutMs).isEqualTo(10_000L)
        assertThat(com.stash.core.addons.HttpAddonClient.Route.STREAM.timeoutMs).isEqualTo(15_000L)
    }

    /** What the relay answers one request with. [tag] is the relay's internal answer tag (`rights`), never on the wire. */
    data class RelayAnswer(val status: Int, val body: String, val headers: Map<String, String> = emptyMap(), val tag: String? = null) {
        fun raw(): MockResponse = MockResponse().setResponseCode(status).setBody(body).also { r -> headers.forEach { (k, v) -> r.setHeader(k, v) } }
    }

    companion object {
        const val TOKEN = "tok-123"

        fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()): MockResponse =
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
                .also { r -> headers.forEach { (k, v) -> r.setHeader(k, v) } }
    }
}
