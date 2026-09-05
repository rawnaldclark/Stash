package com.stash.data.download.lossless.qbdlx

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class QbdlxApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: QbdlxApiClient
    private val webCreds: QobuzWebCredentialsClient = mockk()

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = QbdlxApiClient(
            sharedClient = OkHttpClient(),
            signer = QbdlxSigner { 1000L },
            // Every token in these tests signs under the same test pair; getFileUrl
            // reads app_id/secret from here (the real store resolves per-token).
            signingResolver = { QbdlxSigning(appId = "798273057", appSecret = "secret") },
            webCreds = webCreds,
        ).also {
            it.baseUrl = server.url("/").toString().trimEnd('/')
        }
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun `search parses track items`() = runTest {
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[{"id":42,"title":"Murderers","isrc":"USWB10003085","duration":160,"performer":{"name":"John Frusciante"},"maximum_bit_depth":16,"maximum_sampling_rate":44.1}]}}"""))
        val items = client.search("John Frusciante Murderers")
        assertThat(items).hasSize(1)
        assertThat(items[0].id).isEqualTo(42)
        assertThat(server.takeRequest().getHeader("X-User-Auth-Token")).isNull()
    }

    /**
     * Catalog browsing needs no account: Qobuz's own web player reads the catalog
     * logged-out under its web app_id (live-probed 2026-08-29 — all eight catalog
     * endpoints answer 200 tokenless there, and 401 under the bundled
     * Android-lineage id). Sending a pool token here is what used to mark two
     * thirds of the pool dead on `search` before signing ever ran.
     */
    @Test fun `catalog calls send the web app_id and NO user token`() = runTest {
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[]}}"""))
        client.search("anything")
        val req = server.takeRequest()
        assertThat(req.getHeader("X-App-Id")).isEqualTo(QbdlxApiClient.WEB_APP_ID)
        assertThat(req.path).contains("app_id=${QbdlxApiClient.WEB_APP_ID}")
        assertThat(req.getHeader("X-User-Auth-Token")).isNull()
        assertThat(req.path).doesNotContain("request_sig")
    }

    /** Qobuz rotates the web app_id on deploy — re-scrape it once and retry. */
    @Test fun `catalog 401 refreshes the web app_id once and retries`() = runTest {
        coEvery { webCreds.fetch() } returns QobuzWebCreds(appId = "999999999", appSecret = "s")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"status":"error","code":401,"message":"User authentication is required."}"""))
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[{"id":7,"title":"x"}]}}"""))
        val items = client.search("anything")
        assertThat(items.single().id).isEqualTo(7)
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("X-App-Id")).isEqualTo("999999999")
        assertThat(client.catalogAppId).isEqualTo("999999999")
    }

    /** One heal, then the 401 is real — never a scrape-retry loop. */
    @Test fun `a 401 retries at most once`() = runTest {
        coEvery { webCreds.fetch() } returns QobuzWebCreds(appId = "999999999", appSecret = "s")
        repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setBody("{}")) }
        try { client.search("anything"); org.junit.Assert.fail("expected QbdlxAuthException") }
        catch (e: QbdlxAuthException) { assertThat(e.status).isEqualTo(401) }
        assertThat(server.requestCount).isEqualTo(2)
    }

    /**
     * An app_id rotation is a Qobuz deploy, not a burst. Home fires ~6 catalog calls
     * at once, so an unfloored heal turns one rotation into six page+bundle scrapes.
     */
    @Test fun `heal is throttled — a second 401 within the interval does not scrape again`() = runTest {
        client.clock = { NOW }
        coEvery { webCreds.fetch() } returns QobuzWebCreds(appId = "999999999", appSecret = "s")
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setBody("""{"tracks":{"items":[]}}"""))
        client.search("anything")                    // heals: WEB_APP_ID -> 999999999
        assertThat(client.catalogAppId).isEqualTo("999999999")

        client.catalogAppId = QbdlxApiClient.WEB_APP_ID   // pretend the id went bad again
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try { client.search("anything"); org.junit.Assert.fail("expected QbdlxAuthException") }
        catch (e: QbdlxAuthException) { assertThat(e.status).isEqualTo(401) }
        coVerify(exactly = 1) { webCreds.fetch() }
        assertThat(server.requestCount).isEqualTo(3)
    }

    /**
     * Loser of a heal race: a concurrent caller rotated the field while this request
     * was in flight, so this one reuses that id instead of scraping a second time.
     * Comparing the scrape against the CURRENT field instead of the id this call used
     * made the loser rethrow even though a good id was already sitting there.
     *
     * Driven from the server dispatcher rather than two coroutines so the interleaving
     * is exact rather than hopeful.
     */
    @Test fun `a concurrent healer's app_id is reused without scraping`() = runTest {
        coEvery { webCreds.fetch() } returns QobuzWebCreds(appId = "111111111", appSecret = "s")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.contains("app_id=${QbdlxApiClient.WEB_APP_ID}")) {
                    client.catalogAppId = "999999999"    // the winner heals mid-flight
                    MockResponse().setResponseCode(401).setBody("{}")
                } else {
                    MockResponse().setBody("""{"tracks":{"items":[{"id":7,"title":"x"}]}}""")
                }
        }
        assertThat(client.search("anything").single().id).isEqualTo(7)
        assertThat(server.requestCount).isEqualTo(2)
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("X-App-Id")).isEqualTo("999999999")
        coVerify(exactly = 0) { webCreds.fetch() }
    }

    @Test fun `catalog 401 with no fresh app_id throws QbdlxAuthException`() = runTest {
        coEvery { webCreds.fetch() } returns null
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        try { client.search("anything"); org.junit.Assert.fail("expected QbdlxAuthException") }
        catch (e: QbdlxAuthException) { assertThat(e.status).isEqualTo(401) }
    }

    @Test fun `getFileUrl Ok when url present and not restricted`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn/file?fmt=6","format_id":6,"bit_depth":16,"sampling_rate":44.1,"sample":false,"restrictions":[]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.Ok::class.java)
        val ok = r as QbdlxResolveResult.Ok
        assertThat(ok.url).contains("cdn/file")
        assertThat(ok.bitDepth).isEqualTo(16)
        val req = server.takeRequest()
        assertThat(req.getHeader("X-App-Id")).isEqualTo("798273057")
        assertThat(req.path).contains("request_sig=")
    }

    @Test fun `getFileUrl TokenDead on UserUnauthenticated preview`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn/file?fmt=5&range=20-30","format_id":5,"sample":true,"restrictions":[{"code":"UserUnauthenticated"}]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.TokenDead::class.java)
    }

    @Test fun `getFileUrl RegionLocked when restricted with no usable url`() = runTest {
        server.enqueue(MockResponse().setBody("""{"format_id":6,"restrictions":[{"code":"TrackRestrictedByRights"}]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.RegionLocked::class.java)
    }

    @Test fun `getFileUrl accepts format-downgrade to CD FLAC`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn/file?fmt=6","format_id":6,"restrictions":[{"code":"FormatRestrictedByFormatAvailability"}]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.Ok::class.java)  // fmt6 is still lossless
    }

    /**
     * A banned Qobuz account answers 403 USER_BLOCKED. That is a dead TOKEN, not a
     * sick service: it must surface as QbdlxAuthException so the source marks it
     * dead and rotates. Treated as a generic failure it never rotates, and because
     * the active token is sticky one banned account silently downgrades every play
     * to lossy YouTube while live tokens sit unused.
     */
    @Test fun `403 USER_BLOCKED is a dead token, not a service failure`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"status":"error","code":403,"message":"Account is blocked","error_code":"USER_BLOCKED"}"""
            )
        )
        try {
            client.getFileUrl(42, 27, token = "tok")
            assertThat(false).isTrue()
        } catch (e: QbdlxAuthException) {
            assertThat(e.status).isEqualTo(403)
        }
    }

    /** A 403 that is NOT a ban stays transient — do not burn a good token on it. */
    @Test fun `other 403s remain transient api errors`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"status":"error","code":403,"message":"Rate limited"}""")
        )
        try {
            client.getFileUrl(42, 27, token = "tok")
            assertThat(false).isTrue()
        } catch (e: QbdlxApiException) {
            assertThat(e.status).isEqualTo(403)
        }
    }

    @Test fun `getFeaturedAlbums sends type + genre_id + app_id and parses`() = runTest {
        server.enqueue(MockResponse().setBody("""{"albums":{"items":[
            {"id":"a1","title":"T","image":{"large":"L"},"artist":{"name":"AR"},
             "release_date_original":"2026-01-02","tracks_count":9}]}}"""))
        val items = client.getFeaturedAlbums("best-sellers", genreId = 112)
        val req = server.takeRequest()
        assertThat(req.path).contains("album/getFeatured")
        assertThat(req.path).contains("type=best-sellers")
        assertThat(req.path).contains("genre_id=112")
        assertThat(req.path).contains("app_id=")
        assertThat(items.single().title).isEqualTo("T")
    }

    @Test fun `getFeaturedAlbums omits genre_id when null`() = runTest {
        server.enqueue(MockResponse().setBody("""{"albums":{"items":[]}}"""))
        client.getFeaturedAlbums("new-releases-full", genreId = null)
        assertThat(server.takeRequest().path).doesNotContain("genre_id")
    }

    @Test fun `getFeaturedPlaylists parses playlist items`() = runTest {
        server.enqueue(MockResponse().setBody("""{"playlists":{"items":[
            {"id":5,"name":"P","owner":{"name":"O"},"tracks_count":3,"images300":["i"]}]}}"""))
        val items = client.getFeaturedPlaylists(genreId = null)
        assertThat(server.takeRequest().path).contains("playlist/getFeatured")
        assertThat(items.single().name).isEqualTo("P")
    }

    @Test fun `getFeaturedPlaylists uses genre_ids plural + offset`() = runTest {
        server.enqueue(MockResponse().setBody("""{"playlists":{"items":[]}}"""))
        client.getFeaturedPlaylists(genreId = 133, limit = 30, offset = 60)
        val path = server.takeRequest().path!!
        assertThat(path).contains("genre_ids=133")   // plural — singular is ignored by Qobuz
        assertThat(path).doesNotContain("genre_id=133&")
        assertThat(path).contains("offset=60")
        assertThat(path).contains("limit=30")
    }

    @Test fun `getPlaylist sends extra=tracks and parses detail`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":5,"name":"P","owner":{"name":"O"},
            "images300":["i"],"tracks":{"items":[
              {"id":9,"title":"S","performer":{"name":"AR"},"duration":100,
               "album":{"title":"AL","image":{"large":"L"}}}]}}"""))
        val d = client.getPlaylist("5")
        val req = server.takeRequest()
        assertThat(req.path).contains("playlist/get")
        assertThat(req.path).contains("extra=tracks")
        assertThat(d.tracks.items.single().title).isEqualTo("S")
    }

    private companion object {
        /** Fixed clock for the heal throttle — any two reads land inside the floor. */
        const val NOW = 1_000_000L
    }

    /** An MP3 for a LOSSLESS request is about the track (region/licence), never the account — the relay learned this the hard way. */
    @Test fun `getFileUrl RegionLocked not TokenDead when a lossless request is answered with format 5`() = runTest {
        server.enqueue(MockResponse().setBody("""{"url":"https://cdn/file?fmt=5","format_id":5,"bit_depth":16,"sampling_rate":44.1,"sample":false,"restrictions":[]}"""))
        val r = client.getFileUrl(trackId = 42, formatId = 27, token = "tok")
        assertThat(r).isInstanceOf(QbdlxResolveResult.RegionLocked::class.java)
    }
}
