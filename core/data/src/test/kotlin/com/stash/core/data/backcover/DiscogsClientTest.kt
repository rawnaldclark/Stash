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

class DiscogsClientTest {

    private val server = MockWebServer()
    private lateinit var client: DiscogsClientImpl

    @Before fun setUp() {
        server.start()
        client = DiscogsClientImpl(OkHttpClient()).apply {
            base = server.url("/").toString()
            minIntervalMs = 0L
        }
    }

    @After fun tearDown() = server.shutdown()

    private fun fixture(name: String) = File("src/test/resources/backcover/$name").readText()

    @Test fun `a release is parsed`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("discogs_release_tpab_vinyl.json")))
        val release = client.release(7557957)!!
        assertThat(release.id).isEqualTo(7557957)
        assertThat(release.images).hasSize(10)
        assertThat(release.images[0].url).startsWith("https://i.discogs.com/")
        assertThat(release.images[0].thumb).contains("/h:150/w:150/")
        assertThat(release.formats).containsExactly("Vinyl")
        assertThat(release.formatText).isEqualTo("Gatefold")
        assertThat(release.page).startsWith("https://www.discogs.com/release/7557957")
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/releases/7557957")
        assertThat(req.getHeader("User-Agent")).contains("Stash")
    }

    @Test fun `a master's versions are asked for by format and parsed`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("discogs_versions_wwafawdwg.json")))
        val versions = client.versions(1524311, "Vinyl")!!
        assertThat(versions).hasSize(74)
        assertThat(versions.first { it.id == 13414611L }.inCollection).isEqualTo(34131)
        assertThat(server.takeRequest().path).isEqualTo("/masters/1524311/versions?format=Vinyl&per_page=100")
    }

    @Test fun `an image without a URL is skipped`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":1,"uri":"https://www.discogs.com/release/1","images":[
                {"type":"primary","uri":"https://i.discogs.com/f.jpeg","width":600,"height":600},
                {"type":"secondary","uri":"","width":600,"height":600}]}""",
            ),
        )
        assertThat(client.release(1)!!.images.map { it.url }).containsExactly("https://i.discogs.com/f.jpeg")
    }

    @Test fun `a 404 is no release, and no versions`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        assertThat(client.release(1)).isNull()
        assertThat(client.versions(1, "CD")).isEmpty()
    }

    @Test fun `over the rate limit is a soft failure, asked once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertThat(client.versions(1, "Vinyl")).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `a busy server or a body that isn't JSON is a failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("<html>maintenance</html>"))
        assertThat(client.versions(1, "Vinyl")).isNull()
        assertThat(client.release(1)).isNull()
    }
}
