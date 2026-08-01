package com.stash.data.download.lossless.arcod

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contract tests for ARCOD's `/v2/stash/…` routes, written against the operator's
 * spec while the live server still answers 403 to everything.
 *
 * That ordering is deliberate. The API has never returned 200 to us, so these lock
 * in what we *send* and how we *interpret* documented responses — the parts we
 * control and can be wrong about independently of the server. When the 403 clears,
 * a green suite here means any remaining failure is the server's shape differing
 * from its spec, which is a much smaller search space.
 *
 * Robolectric only because the client logs through `android.util.Log`; `runBlocking`
 * rather than `runTest` because MockWebServer does real socket I/O and a virtual
 * clock would fire timeouts instantly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ArcodStashApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ArcodStashApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ArcodStashApiClient(OkHttpClient()).apply {
            baseUrl = server.url("/").toString().removeSuffix("/")
        }
    }

    @After fun tearDown() { server.shutdown() }

    /**
     * Both headers on every request. Omitting X-Stash-Key is an automatic 403 per the
     * spec, and it is exactly what the OLD client did — the likeliest reason ARCOD
     * was written off as broken.
     */
    @Test fun `every request carries the bearer token and the stash key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))

        client.search(token = "  user-tok  ", query = "mac miller")

        val req = server.takeRequest()
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer user-tok")
        assertThat(req.getHeader("X-Stash-Key")).isNotNull()
    }

    @Test fun `search uses the stash route with q limit and offset`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))

        client.search(token = "t", query = "mac miller", limit = 12, offset = 24)

        val path = server.takeRequest().path!!
        assertThat(path).startsWith("/v2/stash/search")
        assertThat(path).contains("q=mac+miller")
        assertThat(path).contains("limit=12")
        assertThat(path).contains("offset=24")
        // The generic routes are explicitly not for Stash.
        assertThat(path).doesNotContain("/v2/search")
    }

    @Test fun `stream requests the stash route with the quality tier`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"url":"https://api.arcod.xyz/v2/stream/play?t=abc","mimeType":"audio/flac","quality":27,"trackId":"123"}""",
            ),
        )

        val result = client.streamUrl(token = "t", trackId = "123456789", quality = 27)

        assertThat(server.takeRequest().path).isEqualTo("/v2/stash/stream/123456789?quality=27")
        val ok = result as ArcodStashApiClient.Result.Ok
        assertThat(ok.value.url).isEqualTo("https://api.arcod.xyz/v2/stream/play?t=abc")
        assertThat(ok.value.mimeType).isEqualTo("audio/flac")
        assertThat(ok.value.quality).isEqualTo(27)
    }

    @Test fun `Token-Country is sent only when supplied`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        client.search(token = "t", query = "x", country = "FR")
        assertThat(server.takeRequest().getHeader("Token-Country")).isEqualTo("FR")

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[]}"""))
        client.search(token = "t", query = "x")
        assertThat(server.takeRequest().getHeader("Token-Country")).isNull()
    }

    /**
     * The status mapping is the load-bearing part. 401 and 403 look alike to a user
     * ("it didn't work") and are completely different to us: 401 means reconnect,
     * 403 means the integration itself is refused and no user action helps.
     */
    @Test fun `401 is unauthorized and 403 is forbidden`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThat(client.search("t", "x")).isEqualTo(ArcodStashApiClient.Result.Unauthorized)

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden"}"""))
        assertThat(client.search("t", "x")).isEqualTo(ArcodStashApiClient.Result.Forbidden)
    }

    @Test fun `429 carries Retry-After when the server sends one`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "42"))
        val limited = client.search("t", "x") as ArcodStashApiClient.Result.RateLimited
        assertThat(limited.retryAfterSeconds).isEqualTo(42L)

        server.enqueue(MockResponse().setResponseCode(429))
        val noHeader = client.search("t", "x") as ArcodStashApiClient.Result.RateLimited
        assertThat(noHeader.retryAfterSeconds).isNull()
    }

    /** A 5xx or dead socket is the service failing, never a bad request. */
    @Test fun `5xx and transport failure are unavailable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(client.search("t", "x"))
            .isInstanceOf(ArcodStashApiClient.Result.Unavailable::class.java)

        server.shutdown()
        assertThat(client.search("t", "x"))
            .isInstanceOf(ArcodStashApiClient.Result.Unavailable::class.java)
    }

    /** A 200 whose body isn't JSON must not be reported as success. */
    @Test fun `an unparseable 200 body is a bad request, not a success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        assertThat(client.search("t", "x"))
            .isInstanceOf(ArcodStashApiClient.Result.BadRequest::class.java)
    }

    /** One malformed entry must not lose the rest of the page. */
    @Test fun `search skips entries with no id and keeps the others`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"items":[
                   {"title":"No Id Here","artist":"X"},
                   {"id":"42","title":"Good Track","artist":"Mac Miller","album":"Faces","duration":"245"}
                ]}""",
            ),
        )

        val ok = client.search("t", "mac miller") as ArcodStashApiClient.Result.Ok
        assertThat(ok.value).hasSize(1)
        assertThat(ok.value.single().id).isEqualTo("42")
        assertThat(ok.value.single().album).isEqualTo("Faces")
        assertThat(ok.value.single().durationMs).isEqualTo(245_000L)
    }
}
