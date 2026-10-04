package com.stash.core.data.share

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.share.ShareConfig
import com.stash.core.model.share.ShareLinks
import com.stash.core.model.share.SharedTrack
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ShareApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ShareApiClient
    private val doc = SharedMixDocument(name = "Ambient", tracks = listOf(SharedTrack("T", "A", isrc = "X")))

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        client = ShareApiClient(OkHttpClient()).apply { baseUrls = listOf(server.url("/").toString().removeSuffix("/")) }
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `create posts doc and key, returns id and version`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        assertThat(client.create(doc, "KEY")).isEqualTo(ShareResult.Ok(ShareApiClient.Created("Kx7Qa2pL", 1)))
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/v1/mixes")
        val body = req.body.readUtf8()
        assertThat(body).contains("\"editKey\":\"KEY\"")
        assertThat(body).contains("\"isrc\":\"X\"")
        assertThat(body).doesNotContain("\"sp\"") // nulls omitted
    }

    @Test fun `update sends the key header, 403 404 410 map to typed results`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"version":3}"""))
        assertThat(client.update("Kx7Qa2pL", doc, "KEY", 2)).isEqualTo(ShareResult.Ok(3))
        val put = server.takeRequest()
        assertThat(put.getHeader("X-Stash-Edit-Key")).isEqualTo("KEY")
        assertThat(put.body.readUtf8()).contains("\"baseVersion\":2")
        server.enqueue(MockResponse().setResponseCode(403))
        assertThat(client.update("Kx7Qa2pL", doc, "BAD", 2)).isEqualTo(ShareResult.Forbidden)
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.version("Kx7Qa2pL")).isEqualTo(ShareResult.NotFound)
        server.enqueue(MockResponse().setResponseCode(410))
        assertThat(client.get("Kx7Qa2pL")).isEqualTo(ShareResult.Gone)
        server.enqueue(MockResponse().setResponseCode(400))
        assertThat(client.update("Kx7Qa2pL", doc, "KEY", 2)).isEqualTo(ShareResult.Rejected(400))
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(client.update("Kx7Qa2pL", doc, "KEY", 2)).isInstanceOf(ShareResult.Failed::class.java)
    }

    private val song = SharedTrack(
        "Teardrop", "Massive Attack", "Mezzanine", 330_000, "GBAAA9800174", "67Hna13dNDkZvBpTXRIaOJ", "u7K72X4eo_s",
        artUrl = "https://i.scdn.co/image/ab67616d0000b273",
    )

    @Test fun `createTrackLink posts every field and returns the short link, new or existing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Ab3xY9qk","url":"https://stashfm.app/t/Ab3xY9qk"}"""))
        assertThat(client.createTrackLink(song))
            .isEqualTo(ShareResult.Ok(ShareApiClient.TrackLink("Ab3xY9qk", "${ShareConfig.BASE_URL}/t/Ab3xY9qk")))
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("POST")
        assertThat(req.path).isEqualTo("/v1/tracks")
        assertThat(req.body.readUtf8()).isEqualTo(
            """{"t":"Teardrop","a":"Massive Attack","al":"Mezzanine","d":330000,"isrc":"GBAAA9800174",""" +
                """"sp":"67Hna13dNDkZvBpTXRIaOJ","yt":"u7K72X4eo_s","art":"https://i.scdn.co/image/ab67616d0000b273"}""",
        )
        // The same song again: the Worker finds the link it already made (200).
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"Ab3xY9qk","url":"https://stashfm.app/t/Ab3xY9qk"}"""))
        assertThat((client.createTrackLink(song) as ShareResult.Ok).value.id).isEqualTo("Ab3xY9qk")
    }

    @Test fun `createTrackLink sends the cover only from a known host, and never the room's adder`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Ab3xY9qk","url":"u"}"""))
        client.createTrackLink(SharedTrack("T", "A", artUrl = "https://tracker.example/pixel.jpg", addedBy = "m1"))
        val off = server.takeRequest().body.readUtf8()
        assertThat(off).isEqualTo("""{"t":"T","a":"A"}""")
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Ab3xY9qk","url":"u"}"""))
        client.createTrackLink(SharedTrack("T", "A", artUrl = "/data/user/0/com.stash.app/files/art/1.jpg"))
        assertThat(server.takeRequest().body.readUtf8()).doesNotContain("art")
    }

    @Test fun `createTrackLink maps refusals, rate limits, server errors, bad replies and no network`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid"}"""))
        assertThat(client.createTrackLink(song)).isEqualTo(ShareResult.Rejected(400))
        server.enqueue(MockResponse().setResponseCode(413))
        assertThat(client.createTrackLink(song)).isEqualTo(ShareResult.Rejected(413))
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(client.createTrackLink(song)).isEqualTo(ShareResult.Failed(ShareResult.Failed.RATE_LIMITED))
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(client.createTrackLink(song)).isEqualTo(ShareResult.Failed("HTTP 503"))
        // A 2xx without a usable id must not become a link nobody can open.
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"../../x","url":"u"}"""))
        assertThat(client.createTrackLink(song)).isInstanceOf(ShareResult.Failed::class.java)
        server.enqueue(MockResponse().setResponseCode(201).setBody("not json"))
        assertThat(client.createTrackLink(song)).isInstanceOf(ShareResult.Failed::class.java)
        assertThat(server.requestCount).isEqualTo(6) // a 4xx is never retried
        server.shutdown()
        assertThat(client.createTrackLink(song)).isInstanceOf(ShareResult.Failed::class.java)
    }

    @Test fun `a server that answers too slowly fails the short link within the call timeout`() = runBlocking {
        client.trackLinkTimeoutMs = 200
        server.enqueue(MockResponse().setHeadersDelay(3, TimeUnit.SECONDS).setBody("""{"id":"Ab3xY9qk","url":"u"}"""))
        val started = System.nanoTime()
        assertThat(client.createTrackLink(song)).isInstanceOf(ShareResult.Failed::class.java)
        // A blocking execute() ignores coroutine cancellation, so only the call timeout keeps the sheet's 4 s promise.
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2_000L)
    }

    @Test fun `a DNS lookup that hangs can't hold the share sheet past its time box`() = runBlocking {
        // Weak signal can leave a lookup hanging, and nothing can interrupt a thread inside one.
        val stalled = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                try { Thread.sleep(10_000) } catch (_: InterruptedException) { }
                throw UnknownHostException(hostname)
            }
        }).build()
        val api = ShareApiClient(stalled) // the real hosts: the lookup never answers, so nothing leaves this machine
        val started = System.nanoTime()
        // What the share sheet does (stashSongLink): wait SHORT_LINK_TIMEOUT_MS for the short link, else share the long one.
        val link = withTimeoutOrNull(ShareLinks.SHORT_LINK_TIMEOUT_MS) { (api.createTrackLink(song) as? ShareResult.Ok)?.value?.url }
            ?: ShareLinks.trackUrl(song)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        stalled.dispatcher.executorService.shutdownNow()
        assertThat(link).isEqualTo(ShareLinks.trackUrl(song))
        assertThat(ms).isLessThan(4_500L)
    }

    /** A DNS filter blocking stashfm.app: no answer for that one name, everything else resolves. */
    private val blockedDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname == "stashfm.invalid") throw UnknownHostException(hostname) else Dns.SYSTEM.lookup(hostname)
    }
    private fun MockWebServer.base() = url("/").toString().removeSuffix("/")

    @Test fun `when stashfm-app can't be reached, the call goes to the old host and the link is still on stashfm-app`() = runBlocking {
        val api = ShareApiClient(OkHttpClient.Builder().dns(blockedDns).build())
            .apply { baseUrls = listOf("http://stashfm.invalid", server.base()) }
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Ab3xY9qk","url":"https://stash-share.rawnaldclark.workers.dev/t/Ab3xY9qk"}"""))
        assertThat(api.createTrackLink(song))
            .isEqualTo(ShareResult.Ok(ShareApiClient.TrackLink("Ab3xY9qk", "https://stashfm.app/t/Ab3xY9qk")))
        assertThat(server.takeRequest().path).isEqualTo("/v1/tracks")
        server.enqueue(MockResponse().setBody("""{"track":{"t":"T","a":"A"}}"""))
        assertThat(api.getTrack("Ab3xY9qk")).isEqualTo(ShareResult.Ok(SharedTrack("T", "A")))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"Kx7Qa2pL","version":1,"url":"u"}"""))
        assertThat(api.create(doc, "KEY")).isEqualTo(ShareResult.Ok(ShareApiClient.Created("Kx7Qa2pL", 1)))
    }

    @Test fun `an answer from stashfm-app is final - a 4xx, a 5xx or a drop after sending never tries the old host`() = runBlocking {
        val old = MockWebServer().also { it.start() }
        client.baseUrls = listOf(server.base(), old.base())
        server.enqueue(MockResponse().setResponseCode(400))
        assertThat(client.createTrackLink(song)).isEqualTo(ShareResult.Rejected(400))
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.getTrack("Zz9xY9qk")).isEqualTo(ShareResult.NotFound)
        server.enqueue(MockResponse().setResponseCode(503))
        assertThat(client.get("Kx7Qa2pL")).isEqualTo(ShareResult.Failed("HTTP 503"))
        // The request went out, so the mix may already exist: sending it again elsewhere could make it twice.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertThat(client.create(doc, "KEY")).isInstanceOf(ShareResult.Failed::class.java)
        assertThat(old.requestCount).isEqualTo(0)
        old.shutdown()
    }

    @Test fun `getTrack reads the song back, 404 is NotFound, no network is Failed`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"track":{"t":"Teardrop","a":"Massive Attack","al":"Mezzanine","d":330000,"isrc":"GBAAA9800174","sp":"67Hna13dNDkZvBpTXRIaOJ","yt":"u7K72X4eo_s","art":"https://i.scdn.co/image/ab67616d0000b273","new":1}}"""))
        assertThat(client.getTrack("Ab3xY9qk")).isEqualTo(ShareResult.Ok(song))
        val req = server.takeRequest()
        assertThat(req.method).isEqualTo("GET")
        assertThat(req.path).isEqualTo("/v1/tracks/Ab3xY9qk")
        server.enqueue(MockResponse().setBody("""{"track":{"t":"T","a":"A"}}"""))
        assertThat(client.getTrack("Ab3xY9qk")).isEqualTo(ShareResult.Ok(SharedTrack("T", "A")))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not_found"}"""))
        assertThat(client.getTrack("Zz9xY9qk")).isEqualTo(ShareResult.NotFound)
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(client.getTrack("Ab3xY9qk")).isInstanceOf(ShareResult.Failed::class.java)
        server.shutdown()
        assertThat(client.getTrack("Ab3xY9qk")).isInstanceOf(ShareResult.Failed::class.java)
    }

    @Test fun `get parses the doc, transport failure is Failed`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"v":1,"id":"Kx7Qa2pL","version":2,"updatedAt":5,"name":"Ambient","tracks":[{"t":"T","a":"A"}],"extra":true}"""))
        val got = client.get("Kx7Qa2pL") as ShareResult.Ok
        assertThat(got.value.version).isEqualTo(2)
        assertThat(got.value.tracks.single()).isEqualTo(SharedTrack("T", "A"))
        server.shutdown()
        assertThat(client.version("Kx7Qa2pL")).isInstanceOf(ShareResult.Failed::class.java)
    }
}
