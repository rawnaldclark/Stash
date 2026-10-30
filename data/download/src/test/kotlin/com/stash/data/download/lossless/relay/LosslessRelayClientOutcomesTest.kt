package com.stash.data.download.lossless.relay

import com.google.common.truth.Truth.assertThat
import com.stash.data.download.lossless.quota.LosslessQuota
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The relay client remembers what each relay answered, for the diagnostics bundle. */
class LosslessRelayClientOutcomesTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LosslessRelayClient
    private var now = 1_000_000L
    private val quota = LosslessQuota.inMemory { now }
    private val config: LosslessConfigFetcher = mockk {
        every { relayKey } returns MutableStateFlow<String?>(null)
        every { relays } returns MutableStateFlow(emptyList())
    }

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = LosslessRelayClient(OkHttpClient(), RelaySigner(config, supporter(), appVersionProvider()), quota).also { it.clock = { now } }
    }

    @After fun tearDown() = server.shutdown()

    private fun base() = server.url("/").toString().trimEnd('/')

    @Test fun `each answer becomes one host-only line, oldest first`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"url":"https://cdn/x","format_id":7}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))

        client.mint(base(), 1L, 7)
        client.mint(base(), 2L, 7)
        client.mint(base(), 3L, 7) // cools the base 60 s
        client.mint(base(), 4L, 7) // skipped while cooled

        val lines = client.recentOutcomes()
        assertThat(lines).hasSize(4)
        assertThat(lines[0]).contains("ok fmt=7")
        assertThat(lines[1]).contains("404 not available")
        assertThat(lines[2]).contains("503 busy")
        assertThat(lines[3]).contains("skipped: cooled")
        lines.forEach { line ->
            assertThat(line).contains(server.hostName)
            assertThat(line).doesNotContain("http://") // host only, never the base
            assertThat(line).endsWith("ms")
        }
    }

    @Test fun `app too old - the relay is left alone an hour, every format and downloads too, then asked again`() = runTest {
        server.enqueue(MockResponse().setResponseCode(RelaySigner.APP_TOO_OLD).setBody("""{"error":"app_too_old"}"""))
        assertThat(client.mint(base(), 1L, 27)).isEqualTo(RelayMint.Unavailable)
        assertThat(server.requestCount).isEqualTo(1)

        now += RelaySigner.APP_TOO_OLD_BACKOFF_MS - 1_000
        assertThat(client.mint(base(), 1L, 6)).isEqualTo(RelayMint.Unavailable) // another format
        val download = LosslessDownloadPurpose()
        assertThat(kotlinx.coroutines.withContext(download) { client.mint(base(), 2L, 27) }).isEqualTo(RelayMint.Unavailable)
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(client.recentOutcomes()[0]).contains("426 app too old")

        now += 2_000
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"url":"https://cdn/x","format_id":27}"""))
        assertThat(client.mint(base(), 1L, 27)).isInstanceOf(RelayMint.Ok::class.java)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `only the newest RECENT_MAX answers are kept`() = runTest {
        repeat(LosslessRelayClient.RECENT_MAX + 5) { server.enqueue(MockResponse().setResponseCode(404)) }
        repeat(LosslessRelayClient.RECENT_MAX + 5) { client.mint(base(), it.toLong(), 7) }
        assertThat(client.recentOutcomes()).hasSize(LosslessRelayClient.RECENT_MAX)
    }
}
