package com.stash.core.data.backcover

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class CoverArtArchiveClientTest {

    private val server = MockWebServer()
    private lateinit var client: CoverArtArchiveClientImpl

    @Before fun setUp() {
        server.start()
        client = CoverArtArchiveClientImpl(OkHttpClient()).apply {
            base = server.url("/").toString()
            retryMs = 0
        }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `a listing is parsed`() = runTest {
        server.enqueue(MockResponse().setBody(File("src/test/resources/backcover/caa_release_nevermind_vinyl.json").readText()))
        val listing = client.release("006cb56d-6eff-4b7d-853f-ecd2db97f3b2")
        assertThat(listing!!.images.any { it.back }).isTrue()
        assertThat(server.takeRequest().path).isEqualTo("/release/006cb56d-6eff-4b7d-853f-ecd2db97f3b2")
    }

    @Test fun `no art is an answer, not a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.releaseGroup("rg")).isEqualTo(CaaListing.NONE)
    }

    @Test fun `a server error is tried again`() = runTest {
        // archive.org's 500 now and then: the next try answers (2026-10-25).
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.releaseGroup("rg")).isEqualTo(CaaListing.NONE)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a server error every time, an unreadable body or a refusal is a failure`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(502)) }
        assertThat(client.release("r")).isNull()
        assertThat(server.requestCount).isEqualTo(3)
        server.enqueue(MockResponse().setBody("<html>"))
        assertThat(client.release("r")).isNull()
        server.enqueue(MockResponse().setResponseCode(400))
        assertThat(client.release("r")).isNull()
        assertThat(server.requestCount).isEqualTo(5)
    }
}
