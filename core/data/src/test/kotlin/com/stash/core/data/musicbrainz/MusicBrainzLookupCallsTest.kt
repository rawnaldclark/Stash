package com.stash.core.data.musicbrainz

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The back cover lookup's MusicBrainz calls: a busy server is retried, anything else fails soft. */
class MusicBrainzLookupCallsTest {

    private val server = MockWebServer()
    private lateinit var client: MusicBrainzClientImpl

    @Before fun setUp() {
        server.start()
        client = MusicBrainzClientImpl(OkHttpClient()).apply {
            base = server.url("/").toString()
            minIntervalMs = 0L
            busyRetryMs = 0L
        }
    }

    @After fun tearDown() = server.shutdown()

    private val groups = """{"release-groups":[{"id":"rg","score":100,"title":"Abbey Road"}]}"""

    @Test fun `a 503 is retried and the answer comes through`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(groups))

        val body = client.searchReleaseGroups("The Beatles", "Abbey Road")

        assertThat(body.toString()).contains("Abbey Road")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun `a server busy past the retries is a failure`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertThat(client.searchReleaseGroups("The Beatles", "Abbey Road")).isNull()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun `any other error fails at once`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(client.browseReleases("rg")).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `a body that isn't JSON is a failure`() = runTest {
        server.enqueue(MockResponse().setBody("<html>maintenance</html>"))
        assertThat(client.searchRecordingsByIsrc("USGF19942501")).isNull()
    }

    @Test fun `the search asks for the album and the artist by phrase`() = runTest {
        server.enqueue(MockResponse().setBody(groups))
        client.searchReleaseGroups("The Beatles", "Sgt. Pepper's Lonely Hearts Club Band")
        val path = server.takeRequest().path!!
        assertThat(path).startsWith("/release-group?query=")
        assertThat(java.net.URLDecoder.decode(path, "UTF-8"))
            .contains("releasegroup:\"Sgt. Pepper's Lonely Hearts Club Band\" AND artist:\"The Beatles\"")
    }
}
