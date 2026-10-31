package com.stash.data.download.lossless.bitchord

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TidalMatchDao
import com.stash.core.data.db.entity.TidalMatchEntity
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
import com.stash.data.download.lossless.relay.RelaySigner
import com.stash.data.download.lossless.relay.appVersionProvider
import com.stash.data.download.lossless.relay.supporter
import com.stash.data.download.lossless.relay.TidalRelay
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The relay-budget behaviour of [BitchordClient]: per-song cache (hit, miss, TTL), the
 * pool-busy circuit (503 / 429), and in-flight dedupe. Every assertion is on how many
 * requests reached the (mock) relay.
 */
class BitchordClientCacheTest {

    private class FakeDao : TidalMatchDao {
        val rows = ConcurrentHashMap<String, TidalMatchEntity>()
        override suspend fun get(key: String) = rows[key]
        override suspend fun upsert(entity: TidalMatchEntity) { rows[entity.key] = entity }
        override suspend fun deleteOlderThan(before: Long) = 0
    }

    private val server = MockWebServer()
    private val dao = FakeDao()
    private var now = 1_000_000_000_000L
    private val quota = LosslessQuota.inMemory { now }
    private val searches = AtomicInteger()
    private val streams = AtomicInteger()
    private lateinit var client: BitchordClient

    /** What the mock relay answers; tests swap these. */
    @Volatile private var searchReply: () -> MockResponse = { ok(SEARCH_HIT) }
    @Volatile private var streamReply: (RecordedRequest) -> MockResponse = { ok(FLAC) }

    private val query = TrackQuery(artist = "Daft Punk", title = "Get Lucky", isrc = "USQX91300108", durationMs = 248_000)

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/search") -> { searches.incrementAndGet(); searchReply() }
                request.path!!.startsWith("/stream") -> { streams.incrementAndGet(); streamReply(request) }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        val prefs: LosslessSourcePreferences = mockk {
            every { bitchordEndpoint } returns flowOf(base)
            coEvery { bitchordEndpointNow() } returns base
            coEvery { bitchordAccessTokenNow() } returns null
        }
        val config: LosslessConfigFetcher = mockk {
            every { tidalRelay } returns MutableStateFlow<TidalRelay?>(null)
        }
        client = BitchordClient(OkHttpClient(), prefs, config, dao, RelaySigner(config, supporter(), appVersionProvider()), quota)
        client.nowMs = { now }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `a cached match skips search and asks stream only`() = runBlocking {
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(searches.get() to streams.get()).isEqualTo(1 to 1)

        val again = client.lookup(query) as TidalLookup.Found
        assertThat(again.stream.trackId).isEqualTo("td:1")
        assertThat(searches.get() to streams.get()).isEqualTo(1 to 2)
    }

    @Test fun `a cached no-match makes no request at all`() = runBlocking {
        searchReply = { ok("""{"tracks":[]}""") }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch)
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch)
        assertThat(client.lookup(query, allowAac = true)).isEqualTo(TidalLookup.NoMatch)
        assertThat(searches.get() to streams.get()).isEqualTo(1 to 0)
    }

    @Test fun `a lossless miss is cached but does not block the AAC lookup`() = runBlocking {
        streamReply = { r -> if (r.path!!.contains("aac=1")) ok(AAC) else MockResponse().setResponseCode(404).setBody("""{"error":"lossless_unavailable"}""") }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch)
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch) // cached
        assertThat(streams.get()).isEqualTo(1)

        val aac = client.lookup(query, allowAac = true) as TidalLookup.Found
        assertThat(aac.stream.codec).isEqualTo("aac")
        assertThat(searches.get() to streams.get()).isEqualTo(1 to 2)
    }

    @Test fun `AAC download mode asks with aac=only and gets AAC even for a song Tidal has in FLAC`() = runBlocking {
        streamReply = { r -> if (r.path!!.contains("aac=only")) ok(AAC) else ok(FLAC) }

        val aac = client.lookup(query, allowAac = true, aacOnly = true) as TidalLookup.Found

        assertThat(aac.stream.codec).isEqualTo("aac")
        assertThat(server.takeRequest().path).startsWith("/search")
        assertThat(server.takeRequest().path).contains("aac=only")
        // An aac=only answer says nothing about lossless: streaming's FLAC lookup still goes out.
        assertThat(dao.rows.values.single { it.tidalId != null }.losslessMissAt).isNull()
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(streams.get()).isEqualTo(2)
    }

    /**
     * The exact request "Prefer Tidal AAC while streaming" (and the AAC download mode) makes:
     * `aac=only` plus `hires=0` (no Hi-Res seat call), never `aac=1`.
     */
    @Test fun `aac=only asks with hires=0 and no aac=1`() = runBlocking {
        streamReply = { ok(AAC) }

        client.lookup(query, allowAac = true, aacOnly = true)

        server.takeRequest() // /search
        val stream = server.takeRequest().requestUrl!!
        assertThat(stream.queryParameterValues("aac")).containsExactly("only")
        assertThat(stream.queryParameter("hires")).isEqualTo("0")
        assertThat(stream.queryParameter("client")).isEqualTo("media3")
    }

    /** A cached FLAC miss (Tidal has no lossless of it) must not stop the AAC-only lookup. */
    @Test fun `a lossless miss does not block an aac=only lookup`() = runBlocking {
        streamReply = { r ->
            if (r.path!!.contains("aac=only")) ok(AAC) else MockResponse().setResponseCode(404).setBody("""{"error":"lossless_unavailable"}""")
        }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch)

        val aac = client.lookup(query, allowAac = true, aacOnly = true) as TidalLookup.Found
        assertThat(aac.stream.codec).isEqualTo("aac")
        assertThat(searches.get() to streams.get()).isEqualTo(1 to 2)
    }

    @Test fun `an aac=only miss is cached on its own stamp, and never stops streaming's aac=1 lookup`() = runBlocking {
        streamReply = { r ->
            if (r.path!!.contains("aac=only")) MockResponse().setResponseCode(404).setBody("""{"error":"lossless_unavailable"}""") else ok(FLAC)
        }

        assertThat(client.lookup(query, allowAac = true, aacOnly = true)).isEqualTo(TidalLookup.NoMatch)
        assertThat(streams.get()).isEqualTo(1)
        // AAC mode asks no more for days: its sweep skips the song for free.
        assertThat(client.lookup(query, allowAac = true, aacOnly = true)).isEqualTo(TidalLookup.NoMatch)
        assertThat(client.lookup(query, allowAac = true, cacheOnly = true, aacOnly = true)).isEqualTo(TidalLookup.NoMatch)
        assertThat(streams.get()).isEqualTo(1)

        // Streaming's own stamps are untouched, and its lookup still goes out.
        val song = dao.rows.values.single { it.tidalId != null }
        assertThat(song.aacMissAt).isNull()
        assertThat(song.losslessMissAt).isNull()
        assertThat(client.lookup(query, allowAac = true)).isInstanceOf(TidalLookup.Found::class.java)

        // After the stamp's three days, AAC mode asks again.
        now += BitchordClient.AAC_ONLY_MISS_TTL_MS
        streamReply = { ok(AAC) }
        assertThat(client.lookup(query, allowAac = true, aacOnly = true)).isInstanceOf(TidalLookup.Found::class.java)
    }

    @Test fun `a lossless_unavailable 404 is trusted the full 14 days`() = runBlocking {
        streamReply = { MockResponse().setResponseCode(404).setBody("""{"error":"lossless_unavailable"}""") }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch)
        now += BitchordClient.MISS_TTL_MS - 1
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch)
        assertThat(streams.get()).isEqualTo(1)
        now += 1
        streamReply = { ok(FLAC) }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(streams.get()).isEqualTo(2)
    }

    @Test fun `a not_available 404 is only a 6-hour miss`() = runBlocking {
        streamReply = { MockResponse().setResponseCode(404).setBody("""{"error":"not_available"}""") }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch)
        now += BitchordClient.SHORT_MISS_TTL_MS - 1
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch) // still cached
        assertThat(streams.get()).isEqualTo(1)
        now += 1
        streamReply = { ok(FLAC) }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        // The match itself was kept: the retry asks /stream only.
        assertThat(searches.get() to streams.get()).isEqualTo(1 to 2)
    }

    @Test fun `an AAC lookup's short miss keeps a longer lossless miss already cached`() = runBlocking {
        streamReply = { r ->
            if (r.path!!.contains("aac=1")) MockResponse().setResponseCode(404).setBody("""{"error":"not_available"}""")
            else MockResponse().setResponseCode(404).setBody("""{"error":"lossless_unavailable"}""")
        }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch) // definitive: 14 days
        assertThat(client.lookup(query, allowAac = true)).isEqualTo(TidalLookup.NoMatch) // short: 6 h
        assertThat(streams.get()).isEqualTo(2)

        // Past the short miss, the lossless one still holds: no lossless request.
        now += BitchordClient.SHORT_MISS_TTL_MS
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch)
        assertThat(streams.get()).isEqualTo(2)
        // The AAC miss has expired: AAC asks again.
        streamReply = { ok(AAC) }
        assertThat(client.lookup(query, allowAac = true)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(streams.get()).isEqualTo(3)
    }

    @Test fun `a 404 with an unknown or unreadable body is a short miss in both modes`() = runBlocking {
        streamReply = { MockResponse().setResponseCode(404).setBody("<html>Not Found</html>") }
        assertThat(client.lookup(query, allowAac = true)).isEqualTo(TidalLookup.NoMatch)
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch) // AAC's 404 also stamps lossless
        assertThat(streams.get()).isEqualTo(1)
        now += BitchordClient.SHORT_MISS_TTL_MS
        streamReply = { MockResponse().setResponseCode(404).setBody("""{"error":"something_new"}""") }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.NoMatch)
        assertThat(streams.get()).isEqualTo(2)
        now += BitchordClient.SHORT_MISS_TTL_MS
        streamReply = { ok(FLAC) }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
    }

    @Test fun `cacheOnly never touches the relay`() = runBlocking {
        assertThat(client.lookup(query, cacheOnly = true)).isEqualTo(TidalLookup.NeedsRelay)
        assertThat(searches.get() + streams.get()).isEqualTo(0)
    }

    @Test fun `a miss expires after 14 days and a match after 30`() = runBlocking {
        searchReply = { ok("""{"tracks":[]}""") }
        client.lookup(query)
        now += BitchordClient.MISS_TTL_MS - 1
        client.lookup(query)
        assertThat(searches.get()).isEqualTo(1)
        now += 2
        searchReply = { ok(SEARCH_HIT) }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(searches.get()).isEqualTo(2)

        now += BitchordClient.MATCH_TTL_MS - 1
        client.lookup(query)
        assertThat(searches.get()).isEqualTo(2)
        now += 2
        client.lookup(query)
        assertThat(searches.get()).isEqualTo(3)
    }

    @Test fun `503 without Retry-After opens the busy circuit for five minutes and is not cached as a miss`() = runBlocking {
        searchReply = { MockResponse().setResponseCode(503).setBody("""{"error":"no_account"}""") }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Busy::class.java)
        assertThat(client.isBusy()).isTrue()
        // Every caller, any song: no request while busy.
        assertThat(client.lookup(query.copy(isrc = "OTHER"))).isInstanceOf(TidalLookup.Busy::class.java)
        assertThat(searches.get()).isEqualTo(1)

        now += BitchordClient.BUSY_BACKOFF_MS
        searchReply = { ok(SEARCH_HIT) }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(searches.get()).isEqualTo(2)
    }

    @Test fun `503 honours Retry-After so a 15 s pacing busy is not a 5-minute blackout`() = runBlocking {
        searchReply = { MockResponse().setResponseCode(503).setHeader("Retry-After", "15").setBody("""{"error":"busy"}""") }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Busy::class.java)
        now += 14_999
        assertThat(client.isBusy()).isTrue()
        now += 1
        assertThat(client.isBusy()).isFalse()
        searchReply = { ok(SEARCH_HIT) }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(searches.get()).isEqualTo(2)
    }

    @Test fun `503 Retry-After is clamped to between 15 s and 15 min`() = runBlocking {
        searchReply = { MockResponse().setResponseCode(503).setHeader("Retry-After", "1").setBody("""{"error":"busy"}""") }
        val short = client.lookup(query) as TidalLookup.Busy
        assertThat(short.untilMs - now).isEqualTo(BitchordClient.BUSY_MIN_RETRY_AFTER_MS)

        now = short.untilMs
        searchReply = { MockResponse().setResponseCode(503).setHeader("Retry-After", "86400").setBody("""{"error":"tidal_relay_paused"}""") }
        val long = client.lookup(query) as TidalLookup.Busy
        assertThat(long.untilMs - now).isEqualTo(BitchordClient.BUSY_MAX_RETRY_AFTER_MS)
    }

    @Test fun `429 network_daily_cap on search honours an hour-long Retry-After`() = runBlocking {
        searchReply = { MockResponse().setResponseCode(429).setHeader("Retry-After", "3600").setBody("""{"error":"network_daily_cap"}""") }
        val busy = client.lookup(query) as TidalLookup.Busy
        assertThat(busy.untilMs - now).isEqualTo(3_600_000L)
        assertThat(dao.rows).isEmpty()
    }

    @Test fun `429 honours Retry-After`() = runBlocking {
        streamReply = { MockResponse().setResponseCode(429).setHeader("Retry-After", "60") }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Busy::class.java)
        now += 59_000
        assertThat(client.isBusy()).isTrue()
        now += 1_000
        assertThat(client.isBusy()).isFalse()
        // The search's match was kept: the retry asks /stream only.
        streamReply = { ok(FLAC) }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(searches.get() to streams.get()).isEqualTo(1 to 2)
    }

    @Test fun `a 5xx is a failure, never a cached miss`() = runBlocking {
        streamReply = { MockResponse().setResponseCode(502) }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.Failed)
        assertThat(client.isBusy()).isFalse()
        streamReply = { ok(FLAC) }
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
    }

    @Test fun `a 502 upstream_error is never cached as a miss`() = runBlocking {
        streamReply = { MockResponse().setResponseCode(502).setBody("""{"error":"upstream_error"}""") }
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.Failed)
        assertThat(client.lookup(query, allowAac = true)).isEqualTo(TidalLookup.Failed)
        val row = dao.rows[BitchordClient.cacheKey(query)]!!
        assertThat(row.losslessMissAt).isNull()
        assertThat(row.aacMissAt).isNull()
        // Asked again straight away: nothing was remembered against the song.
        assertThat(client.lookup(query, cacheOnly = true)).isEqualTo(TidalLookup.NeedsRelay)
        assertThat(streams.get()).isEqualTo(2)
    }

    @Test fun `a progressive expiresAt lowers the stream expiry to a minute before it`() = runBlocking {
        val tenMin = now / 1000 + 600
        streamReply = { ok("""{"url":"https://cdn.example/a.flac","codec":"flac","manifest":"none","expiresAt":$tenMin}""") }
        val soon = client.lookup(query) as TidalLookup.Found
        assertThat(soon.stream.expiresAtMs).isEqualTo(tenMin * 1000 - BitchordClient.EXPIRY_MARGIN_MS)

        // Later than the cap: the cap still wins.
        val days = now / 1000 + 86_400
        streamReply = { ok("""{"url":"https://cdn.example/a.flac","codec":"flac","manifest":"none","expiresAt":$days}""") }
        val late = client.lookup(query) as TidalLookup.Found
        assertThat(late.stream.expiresAtMs).isEqualTo(now + BitchordClient.MAX_TTL_MS)
    }

    @Test fun `no expiresAt keeps the signed exp or the cap`() = runBlocking {
        val plain = client.lookup(query) as TidalLookup.Found
        assertThat(plain.stream.expiresAtMs).isEqualTo(now + BitchordClient.MAX_TTL_MS)

        val exp = now / 1000 + 300
        streamReply = { ok("""{"url":"https://relay.example/dash/td/1.mpd?exp=$exp&sig=x","codec":"flac","manifest":"dash"}""") }
        val dash = client.lookup(query) as TidalLookup.Found
        assertThat(dash.stream.isDash).isTrue()
        assertThat(dash.stream.expiresAtMs).isEqualTo(exp * 1000)
    }

    @Test fun `concurrent identical lookups share one request`() = runBlocking {
        val gate = CountDownLatch(1)
        searchReply = { gate.await(5, TimeUnit.SECONDS); ok(SEARCH_HIT) }
        val callers = List(5) { async(kotlinx.coroutines.Dispatchers.IO) { client.lookup(query) } }
        // Let every caller reach the in-flight map before the relay answers.
        Thread.sleep(300)
        gate.countDown()
        val results = callers.awaitAll()
        assertThat(results.all { it is TidalLookup.Found }).isTrue()
        assertThat(searches.get() to streams.get()).isEqualTo(1 to 1)
    }

    @Test fun `title key buckets duration and ignores case and spacing`() {
        val a = TrackQuery(artist = "Daft  Punk", title = "Get Lucky ", durationMs = 248_000)
        val b = TrackQuery(artist = "daft punk", title = "get lucky", durationMs = 251_000)
        assertThat(BitchordClient.cacheKey(a)).isEqualTo(BitchordClient.cacheKey(b))
        assertThat(BitchordClient.cacheKey(query)).isEqualTo("isrc:USQX91300108|explicit")
    }

    @Test fun `the key carries the twin the pick prefers, unknown reading as explicit`() {
        val song = TrackQuery(artist = "Daft Punk", title = "Get Lucky", durationMs = 248_000)
        assertThat(BitchordClient.cacheKey(song)).isEqualTo("t:daft punk|get lucky|25|explicit")
        assertThat(BitchordClient.cacheKey(song.copy(explicit = true))).isEqualTo(BitchordClient.cacheKey(song))
        assertThat(BitchordClient.cacheKey(song.copy(explicit = false))).isEqualTo("t:daft punk|get lucky|25|clean")
        assertThat(BitchordClient.cacheKey(query.copy(explicit = false))).isEqualTo("isrc:USQX91300108|clean")
    }

    @Test fun `a length that is only a hint keys apart from the same length taken as exact`() {
        // Shook Ones from Search: the top card is the 4:25 music video. Taken as exact, the
        // pick was the 4:49 compilation edit; that answer must not serve the hinted lookup.
        val video = TrackQuery(artist = "Mobb Deep", title = "Shook Ones, Pt. II", durationMs = 265_000)
        assertThat(BitchordClient.cacheKey(video)).isEqualTo("t:mobb deep|shook ones, pt. ii|27|explicit")
        assertThat(BitchordClient.cacheKey(video.copy(durationReliable = false)))
            .isEqualTo("t:mobb deep|shook ones, pt. ii|~27|explicit")
        assertThat(BitchordClient.cacheKey(video.copy(durationMs = null, durationReliable = false)))
            .isEqualTo("t:mobb deep|shook ones, pt. ii|?|explicit")
    }

    @Test fun `twins go to the explicit one unless the row is known clean, each cached apart`() = runBlocking {
        searchReply = { ok(TWINS) }
        val song = TrackQuery(artist = "Daft Punk", title = "Get Lucky", durationMs = 248_000)

        val unknown = client.lookup(song) as TidalLookup.Found
        assertThat(unknown.stream.trackId).isEqualTo("td:2")
        val clean = client.lookup(song.copy(explicit = false)) as TidalLookup.Found
        assertThat(clean.stream.trackId).isEqualTo("td:1")
        assertThat(searches.get()).isEqualTo(2)

        // An explicit row reads the unknown row's cached pick: no third search.
        val explicit = client.lookup(song.copy(explicit = true)) as TidalLookup.Found
        assertThat(explicit.stream.trackId).isEqualTo("td:2")
        assertThat(searches.get()).isEqualTo(2)
    }

    @Test fun `the row's own ISRC wins over a twin that matches on words and length`() = runBlocking {
        // The clean twin (td:1) scores 1.0 on words and length; the row's ISRC is the explicit
        // one's (td:2), which scores 0.95. A clean row naming it still gets it: the ISRC is the row.
        searchReply = { ok(ISRC_TWINS) }

        val found = client.lookup(query.copy(isrc = "USQX91300199", explicit = false)) as TidalLookup.Found

        assertThat(found.stream.trackId).isEqualTo("td:2")
    }

    @Test fun `a match cached before the explicit tie-break is not served`() = runBlocking {
        // The old key had no preference suffix; its pick was by search order alone.
        dao.rows["isrc:USQX91300108"] = TidalMatchEntity(key = "isrc:USQX91300108", tidalId = "td:9", searchedAt = now)

        val found = client.lookup(query) as TidalLookup.Found

        assertThat(found.stream.trackId).isEqualTo("td:1")
        assertThat(searches.get()).isEqualTo(1)
    }

    // -- The user's own server giving no answer (it is the base these tests use) --

    /** The server stops answering: each request gives up after a quarter second instead of 10 s. */
    private fun serverHangs() {
        client.httpClient = OkHttpClient.Builder().readTimeout(250, TimeUnit.MILLISECONDS).build()
        searchReply = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        streamReply = { MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
    }

    private fun serverAnswers() {
        client.httpClient = OkHttpClient()
        searchReply = { ok(SEARCH_HIT) }
        streamReply = { ok(FLAC) }
    }

    /** Leading the chain, every play waited out the timeout before Stash's relay was asked. */
    @Test fun `the user's own server giving no answer is skipped for a minute, then asked again`() = runBlocking {
        serverHangs()
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.Failed)
        assertThat(client.isBusy()).isTrue()
        // Any song, any mode: no request while paused.
        assertThat(client.lookup(query.copy(isrc = "OTHER"))).isInstanceOf(TidalLookup.Busy::class.java)
        assertThat(client.lookup(query, allowAac = true)).isInstanceOf(TidalLookup.Busy::class.java)
        assertThat(searches.get()).isEqualTo(1)
        // Not remembered against the song: an outage is not a miss.
        assertThat(dao.rows).isEmpty()

        now += BitchordClient.OWN_SERVER_PAUSE_MS
        serverAnswers()
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        assertThat(searches.get()).isEqualTo(2)
    }

    @Test fun `the pause doubles while the own server stays down, and its next answer starts it over`() = runBlocking {
        serverHangs()
        val pauses = List(5) {
            assertThat(client.lookup(query)).isEqualTo(TidalLookup.Failed)
            val busy = client.lookup(query) as TidalLookup.Busy
            (busy.untilMs - now).also { now = busy.untilMs }
        }
        assertThat(pauses).containsExactly(60_000L, 120_000L, 240_000L, 300_000L, 300_000L).inOrder()

        serverAnswers()
        assertThat(client.lookup(query)).isInstanceOf(TidalLookup.Found::class.java)
        serverHangs()
        assertThat(client.lookup(query)).isEqualTo(TidalLookup.Failed)
        val busy = client.lookup(query) as TidalLookup.Busy
        assertThat(busy.untilMs - now).isEqualTo(BitchordClient.OWN_SERVER_PAUSE_MS)
    }

    private companion object {
        fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
        const val SEARCH_HIT = """{"tracks":[{"id":"td:1","title":"Get Lucky","artist":"Daft Punk","duration":248,"audioQuality":"LOSSLESS","isrc":"USQX91300108"}]}"""
        /** A song's clean (td:1, listed first) and explicit (td:2) twins: same words, same length. */
        const val TWINS = """{"tracks":[{"id":"td:1","title":"Get Lucky","artist":"Daft Punk","duration":248,"audioQuality":"LOSSLESS","explicit":false},{"id":"td:2","title":"Get Lucky","artist":"Daft Punk","duration":248,"audioQuality":"LOSSLESS","explicit":true}]}"""
        /** [TWINS] with ISRCs: the explicit td:2 is USQX91300199. */
        const val ISRC_TWINS = """{"tracks":[{"id":"td:1","title":"Get Lucky","artist":"Daft Punk","duration":248,"audioQuality":"LOSSLESS","isrc":"USQX91300198","explicit":false},{"id":"td:2","title":"Get Lucky","artist":"Daft Punk","duration":248,"audioQuality":"LOSSLESS","isrc":"USQX91300199","explicit":true}]}"""
        const val FLAC = """{"url":"https://cdn.example/a.flac","codec":"flac","manifest":"none","bitDepth":16,"sampleRate":44100}"""
        const val AAC = """{"url":"https://cdn.example/a.mp4","codec":"aac","manifest":"none","bitrate":320}"""
    }
}
