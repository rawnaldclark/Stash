package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.audio.FFmpegBridge
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DashFlacDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer

    /** Fake ffmpeg: "remuxes" by copying input to output, recording what it was fed. */
    private var remuxInput: String? = null
    private var remuxFails = false
    private val ffmpeg = object : FFmpegBridge {
        override suspend fun runWithStderrCapture(args: List<String>): String {
            if (remuxFails) throw IOException("ffmpeg exited with code 1")
            val input = File(args[args.indexOf("-i") + 1])
            remuxInput = input.readText()
            input.copyTo(File(args.last()), overwrite = true)
            return ""
        }
    }

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() = server.shutdown()

    private fun mpd(segments: Int): String {
        val init = server.url("/init.mp4")
        val media = server.url("/seg/").toString() + "\$Number\$.mp4"
        return """<MPD><SegmentTemplate initialization="$init" media="$media" startNumber="1">
            <SegmentTimeline><S d="1" r="${segments - 1}"/></SegmentTimeline></SegmentTemplate></MPD>"""
    }

    private fun dest() = File(tmp.newFolder(), "t.flac")

    @Test fun `concatenates init and segments in order then remuxes`() = runTest {
        server.enqueue(MockResponse().setBody(mpd(3)))
        server.enqueue(MockResponse().setBody("I"))
        server.enqueue(MockResponse().setBody("1"))
        server.enqueue(MockResponse().setResponseCode(503)) // retried
        server.enqueue(MockResponse().setBody("2"))
        server.enqueue(MockResponse().setBody("3"))
        val dest = dest()
        val progress = mutableListOf<Pair<Long, Long>>()

        val r = DashFlacDownloader(ffmpeg).download(
            OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest,
        ) { d, t -> progress += d to t }

        assertThat(r.isSuccess).isTrue()
        assertThat(remuxInput).isEqualTo("I123")
        assertThat(dest.readText()).isEqualTo("I123")
        assertThat(progress.last()).isEqualTo(4L to 4L)
        assertThat(dest.parentFile!!.listFiles()!!.map { it.name }).containsExactly("t.flac")
    }

    @Test fun `a segment 404 fails the download and leaves nothing behind`() = runTest {
        server.enqueue(MockResponse().setBody(mpd(2)))
        server.enqueue(MockResponse().setBody("I"))
        server.enqueue(MockResponse().setResponseCode(404))
        val dest = dest()

        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest)

        assertThat(r.isFailure).isTrue()
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
    }

    /**
     * Stash's own Tidal downloads come through here. A segment whose body ends cleanly short of
     * its `Content-Length` (HTTP/2 does not check that itself) is not the segment: it is asked
     * again, and never appended as it is (review-882 round 2, n1).
     */
    @Test fun `a segment shorter than its Content-Length is never appended - the download fails`() = runTest {
        server.enqueue(MockResponse().setBody(mpd(1)))
        server.enqueue(MockResponse().setBody("I"))
        repeat(DashFlacDownloader.MAX_RETRIES + 1) { server.enqueue(MockResponse().setBody("1")) }
        // The wire says ten bytes and gives four, then ends without an error.
        val cutShort = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (!chain.request().url.encodedPath.endsWith("/1.mp4")) return@addInterceptor response
            response.body?.close()
            response.newBuilder().body(
                object : okhttp3.ResponseBody() {
                    override fun contentType(): okhttp3.MediaType? = null
                    override fun contentLength(): Long = 10L
                    override fun source(): okio.BufferedSource = okio.Buffer().writeUtf8("1234")
                },
            ).build()
        }.build()
        val dest = dest()

        val r = DashFlacDownloader(ffmpeg).download(cutShort, server.url("/m.mpd").toString(), emptyMap(), dest)

        assertThat(r.isFailure).isTrue()
        assertThat(remuxInput).isNull()
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
        // Asked again each time, as any other failed read of a segment is.
        assertThat(server.requestCount).isEqualTo(2 + DashFlacDownloader.MAX_RETRIES + 1)
    }

    @Test fun `remux failure is a failure, not an mp4 named flac`() = runTest {
        remuxFails = true
        server.enqueue(MockResponse().setBody(mpd(1)))
        server.enqueue(MockResponse().setBody("I"))
        server.enqueue(MockResponse().setBody("1"))
        val dest = dest()

        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest)

        assertThat(r.isFailure).isTrue()
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `remux args stream-copy the first audio stream into flac`() {
        val args = DashFlacDownloader.buildRemuxArgs(File("/a/in.mp4"), File("/a/out.flac"))
        assertThat(args).containsAtLeast("-map", "0:a:0", "-c:a", "copy", "-vn", "-f", "flac").inOrder()
        assertThat(args.last()).endsWith("out.flac")
    }
}
