package com.stash.data.download.lossless.tidal

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.StreamRequest
import com.stash.addons.contract.Tier
import com.stash.core.addons.AddonValidator
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.NoUserAddons
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.bitchord.LegacyRelayWire
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.relay.TidalRelay
import com.stash.data.download.lossless.relay.appVersionProvider
import com.stash.data.download.lossless.relay.supporter
import com.stash.data.download.lossless.tidal.TidalWireParityTest.Companion.json
import com.stash.data.download.lossless.tidal.TidalWireParityTest.RelayAnswer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the v1 wire does on purpose that the legacy wire does not, through the whole Tidal rung
 * (the real client, both real wires, [TidalWires] choosing between them), against a relay served
 * as the legacy routes and as the v1 face ([V1FacePort]):
 *
 *  - **CD means CD** (decision D15, review-881 M5): at the CD quality setting the v1 wire asks
 *    `tier=lossless`, and the add-on's CD lane answers CD FLAC for a track Tidal also has in
 *    Hi-Res. The legacy wire is asked exactly as before.
 *  - **A broken face is set aside** (review-881 M7): three hard failures in a row and lookups go
 *    to the legacy relay until the next applied config.
 */
class TidalV1WirePolicyTest {

    private val legacyServer = MockWebServer()
    private val v1Server = MockWebServer()
    private var now = System.currentTimeMillis()
    private val exp get() = now / 1000 + 3600

    /** The relay's stream answer for an ask (its `aac` / `hires` parameters, and [V1FacePort.CD_LANE] from the face). */
    @Volatile private var relayStream: (Map<String, String>) -> RelayAnswer = { p -> if (p[V1FacePort.CD_LANE] == "1") cdFlac() else hiResDash() }

    /** The add-on's `V1_LOSSLESS_TIER_CD_ONLY`: "1" as deployed; false is an add-on without its CD lane. */
    @Volatile private var cdLane = true

    /** When set, the v1 host answers `/stream` (or `/search`) with this instead of the face's answer. */
    @Volatile private var v1StreamInstead: (() -> MockResponse)? = null
    @Volatile private var v1SearchInstead: (() -> MockResponse)? = null

    private val legacyStreams = CopyOnWriteArrayList<HttpUrl>()
    private val legacySearches = CopyOnWriteArrayList<HttpUrl>()
    private val v1Streams = CopyOnWriteArrayList<HttpUrl>()
    private val v1Searches = CopyOnWriteArrayList<HttpUrl>()

    /** The config fetcher's `refreshedAt`: moves when a config is applied. */
    private val refreshedAt = MutableStateFlow(0L)
    private val configRelay = MutableStateFlow<TidalRelay?>(null)
    private val registry = FixedRegistry()

    private lateinit var wires: TidalWires
    private lateinit var client: TidalClient

    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky", isrc = "USQX91300108", durationMs = 248_000)

    private val searchHit = RelayAnswer(
        200,
        """{"tracks":[{"id":"td:363236466","title":"Get Lucky","artist":"Daft Punk","album":"Random Access Memories","duration":248,
            "audioQuality":"HI_RES_LOSSLESS","isrc":"USQX91300108","explicit":false}]}""",
    )

    private fun hiResDash() = RelayAnswer(
        200,
        """{"url":"https://relay.example/dash/td:363236466.mpd?exp=$exp&sig=ab12","codec":"flac","container":"fmp4","manifest":"dash","bitDepth":24,"sampleRate":96000,"bitrate":4608}""",
    )

    private fun cdFlac() = RelayAnswer(
        200,
        """{"url":"https://lgf.audio.tidal.com/mediatracks/a.flac?token=t1","codec":"flac","container":"flac","manifest":"none","bitDepth":16,"sampleRate":44100,"bitrate":1411,"expiresAt":${exp - 600}}""",
    )

    private fun aac() = RelayAnswer(
        200,
        """{"url":"https://sp-ad-cf.audio.tidal.com/mediatracks/a.mp4?token=t2","codec":"aac","container":"mp4","manifest":"none","bitDepth":0,"sampleRate":44100,"bitrate":320,"expiresAt":${exp - 900}}""",
    )

    @Before fun setUp() {
        legacyServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when {
                    url.encodedPath == "/search" -> { legacySearches += url; searchHit.raw() }
                    url.encodedPath.startsWith("/stream/") -> {
                        legacyStreams += url
                        relayStream(listOfNotNull(url.queryParameter("aac")?.let { "aac" to it }, url.queryParameter("hires")?.let { "hires" to it }).toMap()).raw()
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        v1Server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val route = url.encodedPath.removePrefix("/v1/$TOKEN/")
                return when {
                    route == "manifest.json" -> json(200, V1FacePort.MANIFEST)
                    route == "search" -> { v1Searches += url; v1SearchInstead?.invoke() ?: V1FacePort.search(searchHit, url.queryParameter("limit")) }
                    route.startsWith("stream/") -> { v1Streams += url; v1StreamInstead?.invoke() ?: V1FacePort.stream(url, relayStream, cdOnly = cdLane) }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        legacyServer.start()
        v1Server.start()
        configRelay.value = TidalRelay(legacyServer.url("/").toString().trimEnd('/'), TOKEN)
        addonLoaded(true)

        val prefs: LosslessSourcePreferences = mockk {
            every { bitchordEndpoint } returns MutableStateFlow(null)
            coEvery { bitchordEndpointNow() } returns null
            coEvery { bitchordAccessTokenNow() } returns null
        }
        val config: LosslessConfigFetcher = mockk {
            every { tidalRelay } returns configRelay
            every { relayKey } returns MutableStateFlow("k-secret")
            every { refreshedAt } returns this@TidalV1WirePolicyTest.refreshedAt
            coEvery { installId() } returns "install-1"
        }
        val quota = LosslessQuota.inMemory { now }
        val http = OkHttpClient()
        val addonClient = HttpAddonClient(http, TestAddonHost(), TestAddonHost(), TestAddonHost(), LocalHttp)
        val legacy = LegacyRelayWire(http, RelaySigner(config, supporter(), appVersionProvider()), quota)
        wires = TidalWires(config, registry, legacy, AddonV1Wire(addonClient, quota), TidalDebugSettings.off(), OwnTidalServer(NoUserAddons, prefs))
        client = TidalClient(prefs, FakeAddonRefDao(), quota, wires).also { it.nowMs = { now } }
    }

    @After fun tearDown() {
        legacyServer.shutdown()
        v1Server.shutdown()
    }

    /** The D9 switch: the first-party Tidal add-on loaded (v1 wire) or not (legacy wire). */
    private fun addonLoaded(loaded: Boolean) {
        registry.state.value = if (!loaded) {
            emptyList()
        } else {
            val manifest = AddonValidator.anyManifest(Json.parseToJsonElement(V1FacePort.MANIFEST), AddonValidator.Options(allowLocalHttp = true))
            listOf(tidalAddon(v1Server.url("/v1/$TOKEN/").toString(), manifest))
        }
    }

    private fun refused(status: Int, code: String) = json(status, """{"error":{"code":"$code","message":"fixed"}}""")

    private fun lookup(cdOnly: Boolean = false, allowAac: Boolean = false, aacOnly: Boolean = false): TidalLookup =
        runBlocking { client.lookup(query, allowAac = allowAac, aacOnly = aacOnly, cdOnly = cdOnly) }

    // ------------------------------------------------------------------------------- CD means CD (M5, D15)

    @Test fun `the v1 ranges - CD caps the tier at lossless, Hi-Res asks up to hires, AAC mode is untouched`() {
        val both = listOf(Delivery.NONE, Delivery.DASH)
        assertThat(AddonV1Wire.request(TidalStreamMode.LOSSLESS, cdOnly = true)).isEqualTo(StreamRequest(Tier.LOSSLESS, Tier.LOSSLESS, both))
        assertThat(AddonV1Wire.request(TidalStreamMode.LOSSLESS_OR_AAC, cdOnly = true)).isEqualTo(StreamRequest(Tier.LOSSLESS, Tier.HIGH, both))
        assertThat(AddonV1Wire.request(TidalStreamMode.AAC_ONLY, cdOnly = true)).isEqualTo(StreamRequest(Tier.HIGH, Tier.HIGH, listOf(Delivery.NONE)))
        assertThat(AddonV1Wire.request(TidalStreamMode.LOSSLESS, cdOnly = false)).isEqualTo(StreamRequest(Tier.HIRES, Tier.LOSSLESS, both))
        assertThat(AddonV1Wire.request(TidalStreamMode.LOSSLESS_OR_AAC, cdOnly = false)).isEqualTo(StreamRequest(Tier.HIRES, Tier.HIGH, both))
        assertThat(AddonV1Wire.request(TidalStreamMode.AAC_ONLY)).isEqualTo(StreamRequest(Tier.HIGH, Tier.HIGH, listOf(Delivery.NONE)))
    }

    @Test fun `at the CD setting the v1 wire asks tier=lossless and a Hi-Res track comes back as CD FLAC`() {
        val found = lookup(cdOnly = true) as TidalLookup.Found
        val asked = v1Streams.single()
        assertThat(asked.queryParameter("tier")).isEqualTo("lossless")
        assertThat(asked.queryParameter("minTier")).isEqualTo("lossless")
        assertThat(found.stream.bitDepth to found.stream.sampleRateHz).isEqualTo(16 to 44_100)
        assertThat(found.stream.isDash).isFalse()
        assertThat(found.stream.codec).isEqualTo("flac")
        assertThat(legacyStreams).isEmpty()
    }

    @Test fun `at the Hi-Res setting the v1 wire asks tier=hires and the same track comes back in Hi-Res`() {
        val found = lookup(cdOnly = false) as TidalLookup.Found
        assertThat(v1Streams.single().queryParameter("tier")).isEqualTo("hires")
        assertThat(found.stream.bitDepth to found.stream.sampleRateHz).isEqualTo(24 to 96_000)
        assertThat(found.stream.isDash).isTrue()
    }

    @Test fun `CD with the AAC fallback asks lossless down to high, and still gets Tidal's AAC when there is no FLAC`() {
        relayStream = { p -> if (p["aac"] == "1" && p[V1FacePort.CD_LANE] == "1") aac() else RelayAnswer(404, """{"error":"lossless_unavailable"}""") }
        val found = lookup(cdOnly = true, allowAac = true) as TidalLookup.Found
        val asked = v1Streams.single()
        assertThat(asked.queryParameter("tier") to asked.queryParameter("minTier")).isEqualTo("lossless" to "high")
        assertThat(found.stream.codec).isEqualTo("aac")
    }

    @Test fun `AAC download mode asks the same at either setting`() {
        relayStream = { p -> if (p["aac"] == "only") aac() else RelayAnswer(500, "{}") }
        assertThat(lookup(cdOnly = true, allowAac = true, aacOnly = true)).isInstanceOf(TidalLookup.Found::class.java)
        val asked = v1Streams.single()
        assertThat(asked.queryParameter("tier") to asked.queryParameter("minTier")).isEqualTo("high" to "high")
    }

    @Test fun `the legacy wire is asked exactly as before at the CD setting, and answers what Tidal gives`() {
        addonLoaded(false)
        val cd = lookup(cdOnly = true) as TidalLookup.Found
        val hiRes = lookup(cdOnly = false) as TidalLookup.Found
        assertThat(legacyStreams).hasSize(2)
        // Not one parameter differs between the two asks: the relay cannot be told "CD only".
        assertThat(legacyStreams[0].query).isEqualTo(legacyStreams[1].query)
        assertThat(legacyStreams[0].queryParameterNames).containsExactly("client", "title", "artist")
        assertThat(cd.stream.bitDepth to cd.stream.sampleRateHz).isEqualTo(24 to 96_000)
        assertThat(cd.stream).isEqualTo(hiRes.stream)
        assertThat(v1Streams).isEmpty()
    }

    /**
     * review-881 R2-m1. An add-on without its CD lane refuses every Hi-Res track asked at the CD
     * setting, and the miss stamp is shared with the Hi-Res setting and the legacy wire: kept for
     * 14 days it would hide the song from all of them, with nothing the server could do about it.
     */
    @Test fun `an add-on without its CD lane costs a short miss at the CD setting, never the 14-day one`() {
        cdLane = false
        assertThat(lookup(cdOnly = true)).isEqualTo(TidalLookup.NoMatch)
        val refused = v1Streams.single()
        assertThat(refused.queryParameter("tier") to refused.queryParameter("minTier")).isEqualTo("lossless" to "lossless")

        // Inside the short miss the song is not asked for again, at either setting.
        now += 60 * 60_000L
        assertThat(lookup(cdOnly = false)).isEqualTo(TidalLookup.NoMatch)
        assertThat(lookup(cdOnly = true)).isEqualTo(TidalLookup.NoMatch)
        assertThat(v1Streams).hasSize(1)

        // Past it (6 hours), the Hi-Res setting asks again and plays. A 14-day stamp would still stand.
        now += TidalClient.SHORT_MISS_TTL_MS
        val found = lookup(cdOnly = false) as TidalLookup.Found
        assertThat(v1Streams).hasSize(2)
        assertThat(found.stream.bitDepth to found.stream.sampleRateHz).isEqualTo(24 to 96_000)
        assertThat(legacyStreams).isEmpty()
    }

    @Test fun `no lossless at the Hi-Res setting is still a real answer about the song, kept 14 days`() {
        relayStream = { RelayAnswer(404, """{"error":"lossless_unavailable"}""") }
        assertThat(lookup(cdOnly = false)).isEqualTo(TidalLookup.NoMatch)
        assertThat(v1Streams).hasSize(1)

        relayStream = { hiResDash() }
        now += TidalClient.SHORT_MISS_TTL_MS + 60 * 60_000L
        assertThat(lookup(cdOnly = false)).isEqualTo(TidalLookup.NoMatch)
        now += 12L * 24 * 60 * 60_000
        assertThat(lookup(cdOnly = true)).isEqualTo(TidalLookup.NoMatch)
        assertThat(v1Streams).hasSize(1)
    }

    // ------------------------------------------------------------------------------- a broken face (M7)

    @Test fun `three hard failures in a row set the v1 wire aside for the legacy relay, until the next applied config`() = runBlocking<Unit> {
        v1StreamInstead = { refused(401, "unauthorized") }
        repeat(3) { assertThat(lookup()).isEqualTo(TidalLookup.Failed) }
        assertThat(v1Streams).hasSize(3)
        assertThat(legacyStreams).isEmpty()
        assertThat(wires.describe()).isEqualTo("legacy relay (v1 add-on set aside after failures)")

        // The fourth lookup is the legacy relay's, and it plays. The v1 face is not asked.
        assertThat(lookup()).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(lookup()).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(legacyStreams).hasSize(2)
        assertThat(v1Streams).hasSize(3)

        // A config is applied (every 6 hours, and at each cold start): the v1 wire gets another go.
        v1StreamInstead = null
        refreshedAt.value = now
        assertThat(wires.describe()).isEqualTo("v1 add-on")
        assertThat(lookup()).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(v1Streams).hasSize(4)
        assertThat(legacyStreams).hasSize(2)
    }

    @Test fun `a face still broken after the applied config is set aside again after three more`() = runBlocking<Unit> {
        v1StreamInstead = { refused(400, "bad_request") }
        repeat(3) { lookup() }
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Relay::class.java)
        refreshedAt.value = now
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Addon::class.java)
        repeat(2) { lookup() }
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Addon::class.java)
        lookup()
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Relay::class.java)
    }

    /**
     * review-881 R2-n1. The counts are only looked at when a lookup picks its route. Failures from
     * before a config was applied must not set the face aside after it: the config may be what
     * mended it, and the wire would stay aside for a whole config cycle more.
     */
    @Test fun `failures from before an applied config do not set the wire aside after it`() = runBlocking<Unit> {
        v1StreamInstead = { refused(401, "unauthorized") }
        repeat(3) { assertThat(lookup()).isEqualTo(TidalLookup.Failed) }
        // Nobody has asked for a route since the third failure. A config is applied, and the face is mended.
        v1StreamInstead = null
        now += 1_000
        refreshedAt.value = now

        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Addon::class.java)
        assertThat(wires.describe()).isEqualTo("v1 add-on")
        assertThat(lookup()).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(v1Streams).hasSize(4)
        assertThat(legacyStreams).isEmpty()
    }

    @Test fun `a face that fails again after the applied config is counted from there`() = runBlocking<Unit> {
        v1StreamInstead = { refused(401, "unauthorized") }
        repeat(3) { lookup() }
        now += 1_000
        refreshedAt.value = now
        // The three old failures are let go at the next look, so it takes three new ones.
        now += 1_000
        repeat(2) { lookup() }
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Addon::class.java)
        lookup()
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Relay::class.java)
        assertThat(v1Streams).hasSize(6)
    }

    @Test fun `any good answer starts the count over`() = runBlocking<Unit> {
        val broken = { refused(401, "unauthorized") }
        v1StreamInstead = broken
        repeat(2) { lookup() }
        v1StreamInstead = null
        assertThat(lookup()).isInstanceOf(TidalLookup.Found::class.java)
        v1StreamInstead = broken
        repeat(2) { lookup() }
        // Four hard failures, never three in a row: still the v1 wire.
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Addon::class.java)
        assertThat(legacyStreams).isEmpty()
        assertThat(v1Streams).hasSize(5)
    }

    @Test fun `a 400 and an answer that breaks the contract count like a 401`() = runBlocking<Unit> {
        v1StreamInstead = { refused(400, "bad_request") }
        lookup()
        v1StreamInstead = { json(200, """{"url":"http://plain.example/a.flac","manifest":"none","tier":"lossless","cors":false}""") }
        lookup()
        v1StreamInstead = { refused(401, "unauthorized") }
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Addon::class.java)
        lookup()
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Relay::class.java)
    }

    @Test fun `a server error, a busy pool and a miss are no reason to set the wire aside`() = runBlocking<Unit> {
        val answers: List<() -> MockResponse> = listOf(
            { refused(500, "unavailable") },
            { MockResponse().setResponseCode(502) },
            // A busy pool: the rung pauses itself for 15 s, so the clock moves on after it.
            { json(503, """{"error":{"code":"unavailable","message":"fixed","retryAfter":15}}""") },
            // Last: a miss is remembered, and the song is not asked for again.
            { refused(404, "not_found") },
        )
        for (answer in answers) {
            v1StreamInstead = answer
            repeat(4) { lookup() }
            assertThat(wires.route()).isInstanceOf(TidalWires.Route.Addon::class.java)
            now += 60_000
        }
        // Every kind was asked of the v1 face at least once, and the relay never.
        assertThat(v1Streams.size).isAtLeast(4 + 4 + 1 + 1)
        assertThat(legacyStreams).isEmpty()
    }

    @Test fun `a face whose search fails hard is set aside too, though nothing ever reaches its stream`() = runBlocking<Unit> {
        v1SearchInstead = { refused(401, "unauthorized") }
        repeat(3) { assertThat(lookup()).isEqualTo(TidalLookup.Failed) }
        assertThat(v1Searches).hasSize(3)
        assertThat(lookup()).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(legacySearches).hasSize(1)
        assertThat(legacyStreams).hasSize(1)
    }

    /** A lookup is a search and then a stream: the search's good answer must not hide a stream that always fails. */
    @Test fun `a good search between them does not start the stream's count over`() = runBlocking<Unit> {
        v1StreamInstead = { refused(400, "bad_request") }
        // Three songs to the cache (their lengths fall in three 10 s buckets, all within the
        // matcher's 5 % of the row's 248 s): each is searched (a good answer) and then refused on /stream.
        for (ms in listOf(244_000L, 248_000L, 256_000L)) {
            assertThat(client.lookup(query.copy(isrc = null, durationMs = ms))).isEqualTo(TidalLookup.Failed)
        }
        assertThat(v1Searches).hasSize(3)
        assertThat(v1Streams).hasSize(3)
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Relay::class.java)
    }

    @Test fun `with no relay to fall back to, the v1 wire stays`() = runBlocking<Unit> {
        configRelay.value = null
        v1StreamInstead = { refused(401, "unauthorized") }
        repeat(5) { assertThat(lookup()).isEqualTo(TidalLookup.Failed) }
        assertThat(v1Streams).hasSize(5)
        assertThat(wires.route()).isInstanceOf(TidalWires.Route.Addon::class.java)
    }

    private companion object {
        const val TOKEN = "tok-123"
    }
}
