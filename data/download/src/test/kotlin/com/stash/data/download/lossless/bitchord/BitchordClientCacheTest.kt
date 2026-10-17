package com.stash.data.download.lossless.bitchord

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TidalMatchDao
import com.stash.core.data.db.entity.TidalMatchEntity
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.relay.LosslessConfigFetcher
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
        client = BitchordClient(OkHttpClient(), prefs, config, dao)
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
        assertThat(BitchordClient.cacheKey(query)).isEqualTo("isrc:USQX91300108")
    }

    private companion object {
        fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)
        const val SEARCH_HIT = """{"tracks":[{"id":"td:1","title":"Get Lucky","artist":"Daft Punk","duration":248,"audioQuality":"LOSSLESS","isrc":"USQX91300108"}]}"""
        const val FLAC = """{"url":"https://cdn.example/a.flac","codec":"flac","manifest":"none","bitDepth":16,"sampleRate":44100}"""
        const val AAC = """{"url":"https://cdn.example/a.mp4","codec":"aac","manifest":"none","bitrate":320}"""
    }
}
