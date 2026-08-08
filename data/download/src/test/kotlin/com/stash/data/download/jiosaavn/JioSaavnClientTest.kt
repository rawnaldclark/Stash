package com.stash.data.download.jiosaavn

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class JioSaavnClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: JioSaavnClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = JioSaavnClient(OkHttpClient()).apply {
            baseUrl = server.url("/").toString()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `known native encrypted media template decrypts to AAC 320 MP4`() {
        val decrypted = client.decrypt320Url(KNOWN_ENCRYPTED_MEDIA_URL)

        assertThat(decrypted).isEqualTo(KNOWN_320_URL)
    }

    @Test
    fun `native search encodes query and normalizes a flagged 320 result`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(nativeResponse(has320 = "true")))

        val outcome = client.search("Arijit Singh Kesariya", limit = 10)

        assertThat(outcome).isInstanceOf(JioSaavnSearchOutcome.Success::class.java)
        val song = (outcome as JioSaavnSearchOutcome.Success).songs.single()
        assertThat(song.name).isEqualTo("Kesariya & Love")
        assertThat(song.artists.primary.single().name).isEqualTo("Arijit Singh")
        assertThat(song.downloadUrl.single().url).isEqualTo(KNOWN_320_URL)
        assertThat(song.image.single().url).contains("500x500")

        val request = server.takeRequest()
        assertThat(request.requestUrl?.queryParameter("__call")).isEqualTo("search.getResults")
        assertThat(request.requestUrl?.queryParameter("q")).isEqualTo("Arijit Singh Kesariya")
        assertThat(request.getHeader("User-Agent")).isNotEmpty()
    }

    @Test
    fun `native result without 320 flag never synthesizes a media URL`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(nativeResponse(has320 = "false")))

        val outcome = client.search("Arijit Singh Kesariya")

        val song = (outcome as JioSaavnSearchOutcome.Success).songs.single()
        assertThat(song.downloadUrl).isEmpty()
    }

    @Test
    fun `cancelling search aborts a stalled response body promptly`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(nativeResponse(has320 = "true"))
                .setBodyDelay(5, TimeUnit.SECONDS),
        )

        val startedAt = System.nanoTime()
        val outcome = withTimeoutOrNull(200L) {
            client.search("Arijit Singh Kesariya")
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertThat(outcome).isNull()
        assertThat(elapsedMs).isLessThan(2_000L)
    }

    @Test
    fun `media URL trust gate requires HTTPS exact CDN and 320 path`() {
        assertThat(client.isTrustedMediaUrl(KNOWN_320_URL)).isTrue()
        assertThat(client.isTrustedMediaUrl(KNOWN_320_URL.replace("https://", "http://"))).isFalse()
        assertThat(client.isTrustedMediaUrl("https://evil.example/song_320.mp4")).isFalse()
        assertThat(client.isTrustedMediaUrl(KNOWN_320_URL.replace("_320", "_160"))).isFalse()
    }

    private fun nativeResponse(has320: String): String =
        """{
          "results": [{
            "id": "rjkrTnma",
            "song": "Kesariya &amp; Love",
            "album": "Brahmastra",
            "duration": "268",
            "image": "http://c.saavncdn.com/871/cover-150x150.jpg",
            "primary_artists": "Arijit Singh",
            "explicit_content": 0,
            "320kbps": "$has320",
            "encrypted_media_url": "$KNOWN_ENCRYPTED_MEDIA_URL",
            "ignored_field": "safe"
          }]
        }""".trimIndent()

    private companion object {
        const val KNOWN_ENCRYPTED_MEDIA_URL =
            "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyryhkSYK5IH2E7FCO52VR6yhNbcEbes5iCcja4+W8xhE0SwtCJToN4Bw7tS9a8Gtq"
        const val KNOWN_320_URL =
            "https://aac.saavncdn.com/871/c2febd353f3a076a406fa37510f31f9f_320.mp4"
    }
}
