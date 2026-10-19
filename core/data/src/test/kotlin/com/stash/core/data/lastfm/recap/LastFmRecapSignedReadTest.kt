package com.stash.core.data.lastfm.recap

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmRateLimitGate
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.math.BigInteger
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * The private-profile fallback over real HTTP (MockWebServer behind the real client): an
 * unsigned read answered with error 17 is retried as a SIGNED POST carrying the account's own
 * session key and a correct api_sig — never in the URL — and the next page goes signed at once.
 * Without a session key, nothing is ever signed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LastFmRecapSignedReadTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val secret = "shared-secret"

    @After fun stop() = server.shutdown()

    private val september = YearMonth.of(2026, 9)
    private val from = september.atDay(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond()

    /** 250 scrobbles: two pages of 200. */
    private fun pageJson(page: Int): String {
        val all = (0 until 250).map { from + 3_600 + it * 600L }.sortedDescending()
        val slice = all.drop((page - 1) * 200).take(200)
        val tracks = slice.joinToString(",") { uts -> """{"artist":{"#text":"A"},"name":"T$uts","date":{"uts":"$uts"}}""" }
        return """{"recenttracks":{"track":[$tracks],"@attr":{"page":"$page","totalPages":"2","total":"250"}}}"""
    }

    private fun params(request: RecordedRequest): Map<String, String> {
        val raw = if (request.method == "POST") request.body.clone().readUtf8() else request.requestUrl!!.encodedQuery.orEmpty()
        return raw.split("&").filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }

    private fun expectedSig(p: Map<String, String>): String {
        val base = p.filterKeys { it != "format" && it != "api_sig" }.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
        return BigInteger(1, MessageDigest.getInstance("MD5").digest(base.toByteArray())).toString(16).padStart(32, '0')
    }

    private fun repository(io: CoroutineDispatcher): LastFmRecapRepository {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = params(request)
                // A private profile: unsigned reads are refused.
                if (p["sk"] == null) return MockResponse().setResponseCode(403).setBody("""{"error":17,"message":"Login: User required to be logged in"}""")
                return MockResponse().setBody(pageJson(p.getValue("page").toInt()))
            }
        }
        server.start()
        val toServer = Interceptor { chain ->
            val original = chain.request()
            val url = original.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(original.newBuilder().url(url).build())
        }
        val api = LastFmApiClient(
            okHttpClient = OkHttpClient.Builder().addInterceptor(toServer).build(),
            credentials = LastFmCredentials(apiKey = "primary", apiSecret = secret),
            rateLimitGate = LastFmRateLimitGate(),
            cacheDao = mockk(relaxed = true),
        )
        val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        return LastFmRecapRepository(
            remote = LastFmRecapApiRemote(api),
            store = LastFmRecapStore(tmp.root),
            genreRows = { emptyList() },
            nowMs = { now },
            elapsedMs = { 0L },
            io = io,
        )
    }

    @Test fun `17 unsigned, then a signed POST with a correct api_sig, then page 2 signed at once`() = runTest {
        val repo = repository(StandardTestDispatcher(testScheduler))
        val read = repo.scrobbles("rawnclark", LastFmRecapSpan.Month(september), ZoneOffset.UTC, sessionKey = "SESSION-KEY")
        assertThat(read).hasSize(250)

        val first = server.takeRequest()
        assertThat(first.method).isEqualTo("GET")
        assertThat(first.requestUrl!!.queryParameter("sk")).isNull()

        val signed = server.takeRequest()
        assertThat(signed.method).isEqualTo("POST")
        // The secret travels in the body, never in the URL the debug logger prints.
        assertThat(signed.requestUrl.toString()).doesNotContain("SESSION-KEY")
        assertThat(signed.requestUrl!!.queryParameter("api_sig")).isNull()
        val p = params(signed)
        assertThat(p["sk"]).isEqualTo("SESSION-KEY")
        assertThat(p["method"]).isEqualTo("user.getRecentTracks")
        assertThat(p["user"]).isEqualTo("rawnclark")
        assertThat(p["page"]).isEqualTo("1")
        assertThat(p["api_key"]).isEqualTo("primary")
        assertThat(p["api_sig"]).isEqualTo(expectedSig(p))

        val second = server.takeRequest()
        assertThat(second.method).isEqualTo("POST")
        assertThat(params(second)["page"]).isEqualTo("2")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `no session key - a private profile is never signed`() = runTest {
        val repo = repository(StandardTestDispatcher(testScheduler))
        val e = runCatching { repo.scrobbles("someone", LastFmRecapSpan.Month(september), ZoneOffset.UTC) }.exceptionOrNull()
        assertThat((e as LastFmRecapException).reason).isEqualTo(LastFmRecapException.Reason.PRIVATE)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().method).isEqualTo("GET")
    }
}
