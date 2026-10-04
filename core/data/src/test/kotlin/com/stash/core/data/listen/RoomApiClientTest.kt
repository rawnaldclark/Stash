package com.stash.core.data.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.share.ShareResult
import com.stash.core.model.share.SharedTrack
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RoomApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: RoomApiClient

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = RoomApiClient(OkHttpClient()).apply { baseUrls = listOf(server.url("/").toString().removeSuffix("/")) }
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `create posts the display name and returns the code, host key and link`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"code":"K7QA2PXM","hostKey":"KEY","url":"https://x/l/K7QA2PXM"}"""))
        assertThat(client.create("Rawn")).isEqualTo(ShareResult.Ok(RoomApiClient.Created("K7QA2PXM", "KEY", "https://x/l/K7QA2PXM")))
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v1/rooms")
        assertThat(req.body.readUtf8()).isEqualTo("""{"hostName":"Rawn"}""")
    }

    @Test fun `create without a name sends an empty object, and 429 is a retryable failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(client.create(null)).isInstanceOf(ShareResult.Failed::class.java)
        assertThat(server.takeRequest().body.readUtf8()).isEqualTo("{}")
    }

    @Test fun `a room is made on the old host when stashfm-app has no DNS answer, and a 404 there is final`() = runBlocking {
        val blocked = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == "stashfm.invalid") throw UnknownHostException(hostname) else Dns.SYSTEM.lookup(hostname)
        }
        val api = RoomApiClient(OkHttpClient.Builder().dns(blocked).build())
            .apply { baseUrls = listOf("http://stashfm.invalid", server.url("/").toString().removeSuffix("/")) }
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"code":"K7QA2PXM","hostKey":"KEY","url":"https://x/l/K7QA2PXM"}"""))
        assertThat((api.create("Rawn") as ShareResult.Ok).value.code).isEqualTo("K7QA2PXM")
        assertThat(server.takeRequest().path).isEqualTo("/v1/rooms")
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(api.preview("K7QA2PXM")).isEqualTo(ShareResult.NotFound)
    }

    @Test fun `preview maps 200 and 404`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"hostName":"Rawn","memberCount":3,"full":false,"track":{"t":"Xtal","a":"Aphex Twin"}}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.preview("K7QA2PXM"))
            .isEqualTo(ShareResult.Ok(RoomApiClient.Preview("Rawn", 3, false, SharedTrack("Xtal", "Aphex Twin"))))
        assertThat(server.takeRequest().path).isEqualTo("/v1/rooms/K7QA2PXM")
        assertThat(client.preview("K7QA2PXM")).isEqualTo(ShareResult.NotFound)
    }
}
