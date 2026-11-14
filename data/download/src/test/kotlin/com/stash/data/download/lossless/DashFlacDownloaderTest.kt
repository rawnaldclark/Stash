package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.audio.FFmpegBridge
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
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

    /** Tidal's 30 s PREVIEW of "Linger" (2026-11-13): its timeline is 29.907 s of a 273 s song. */
    private fun previewMpd(): String {
        val init = server.url("/init.mp4")
        val media = server.url("/seg/").toString() + "\$Number\$.mp4"
        return """<MPD mediaPresentationDuration="PT29.907S"><SegmentTemplate timescale="44100" initialization="$init" media="$media" startNumber="1">
            <SegmentTimeline><S d="176128" r="6"/><S d="86016"/></SegmentTimeline></SegmentTemplate></MPD>"""
    }

    @Test fun `a preview manifest is refused before a segment of it is fetched`() = runTest {
        server.enqueue(MockResponse().setBody(previewMpd()))
        val dest = dest()

        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest, songMs = 273_000L)

        assertThat(r.exceptionOrNull()).isInstanceOf(LosslessUrlDownloader.PreviewException::class.java)
        assertThat((r.exceptionOrNull() as LosslessUrlDownloader.PreviewException).lengthMs).isEqualTo(29_907L)
        assertThat(server.requestCount).isEqualTo(1) // the manifest only
        assertThat(remuxInput).isNull()
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `a short manifest of a song that short is fetched as before`() = runTest {
        server.enqueue(MockResponse().setBody(previewMpd()))
        repeat(9) { server.enqueue(MockResponse().setBody("$it")) }

        // A 30 s song, or one whose length isn't known: nothing to hold the manifest to.
        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest(), songMs = 31_000L)
        assertThat(r.isSuccess).isTrue()
        assertThat(remuxInput).isEqualTo("012345678")
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

    // ------------------------------------------------------------------------------------------ an add-on's answer (review-882 round 2, F3)

    private fun Throwable.causes() = generateSequence(this) { it.cause }.toList()

    /** A megabyte and more of comment in front of a manifest that is otherwise fine. */
    private fun paddedMpd(segments: Int) = "<!--" + "x".repeat(DashFlacDownloader.MAX_ADDON_MANIFEST_BYTES.toInt() + 4_096) + "-->" + mpd(segments)

    @Test fun `an add-on's manifest over a megabyte is refused, and no segment is asked for`() = runTest {
        server.enqueue(MockResponse().setBody(paddedMpd(2)))
        val dest = dest()

        // A ceiling means an add-on answered: LosslessUrlDownloader passes one for those only.
        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest, maxBytes = 50_000_000)

        assertThat(r.exceptionOrNull()!!.causes().any { it is DashFlacDownloader.TooLargeException }).isTrue()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `Stash's own Tidal manifest is not held to the add-on's megabyte`() = runTest {
        server.enqueue(MockResponse().setBody(paddedMpd(1)))
        server.enqueue(MockResponse().setBody("I"))
        server.enqueue(MockResponse().setBody("1"))
        val dest = dest()

        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest)

        assertThat(r.isSuccess).isTrue()
        assertThat(dest.readText()).isEqualTo("I1")
    }

    /** A client that counts the bytes read from the segments' bodies (`/seg/`), as they come off the wire. */
    private fun countingClient(read: AtomicLong) = OkHttpClient.Builder().addInterceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!chain.request().url.encodedPath.startsWith("/seg/")) return@addInterceptor response
        val body = response.body!!
        val counted = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long =
                super.read(sink, byteCount).also { if (it > 0) read.addAndGet(it) }
        }.buffer()
        response.newBuilder().body(counted.asResponseBody(body.contentType(), body.contentLength())).build()
    }.build()

    @Test fun `an add-on's segment is read no further than what is left of the song's ceiling, and is not asked again`() = runTest {
        server.enqueue(MockResponse().setBody(mpd(2)))
        server.enqueue(MockResponse().setBody("I".repeat(1_000)))
        // No Content-Length: only reading it shows how large it is. Far more than the 1,500 bytes left.
        server.enqueue(MockResponse().setChunkedBody("x".repeat(400_000), 8_192))
        val dest = dest()
        val read = AtomicLong()

        val r = DashFlacDownloader(ffmpeg).download(countingClient(read), server.url("/m.mpd").toString(), emptyMap(), dest, maxBytes = 2_500)

        // Stopped at the ceiling while reading: what came off the wire is what is left plus at most
        // one read's worth, not the 400 kB (review-882 round 3, nit 7: the sum check after the
        // segment throws the same exception, so the exception alone did not pin the early stop).
        assertThat(r.exceptionOrNull()!!.causes().any { it is DashFlacDownloader.TooLargeException }).isTrue()
        assertThat(read.get()).isAtMost(1_501L + 16_384)
        // "Too large" is not a reason to fetch it again.
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `an add-on's segment is never more than 8 MiB, however much of the ceiling is left`() = runTest {
        server.enqueue(MockResponse().setBody(mpd(1)))
        server.enqueue(MockResponse().setBody("I"))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(DashFlacDownloader.MAX_ADDON_SEGMENT_BYTES.toInt() + 1))))
        val dest = dest()
        val read = AtomicLong()

        // A 24/192 song's ceiling: 354 MB left for its first segment (review-882 round 3, R2).
        val r = DashFlacDownloader(ffmpeg).download(countingClient(read), server.url("/m.mpd").toString(), emptyMap(), dest, maxBytes = 354_000_000)

        assertThat(r.exceptionOrNull()!!.causes().any { it is DashFlacDownloader.TooLargeException }).isTrue()
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
    }

    @Test fun `Stash's own Tidal segment is not held to the add-on's 8 MiB`() = runTest {
        val size = DashFlacDownloader.MAX_ADDON_SEGMENT_BYTES.toInt() + 1
        server.enqueue(MockResponse().setBody(mpd(1)))
        server.enqueue(MockResponse().setBody("I"))
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(size))))
        val dest = dest()

        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest)

        assertThat(r.isSuccess).isTrue()
        assertThat(dest.length()).isEqualTo(1L + size)
    }

    @Test fun `an add-on's manifest whose segment URLs add up to too much is refused before a segment is asked for`() = runTest {
        // 1 kB of URL over 10,000 segments: 10 M characters, over the add-on's 8 Mi.
        val media = server.url("/seg/").toString() + "x".repeat(1_000) + "\$Number\$.mp4"
        server.enqueue(
            MockResponse().setBody(
                """<MPD><SegmentTemplate initialization="${server.url("/init.mp4")}" media="$media"><SegmentTimeline><S d="1" r="9999"/></SegmentTimeline></SegmentTemplate></MPD>""",
            ),
        )
        val dest = dest()

        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest, maxBytes = 354_000_000)

        assertThat(r.exceptionOrNull()).isInstanceOf(DashManifestException::class.java)
        assertThat(r.exceptionOrNull()).hasMessageThat().isEqualTo("segment URLs too long")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun `an add-on's segments within the ceiling are kept`() = runTest {
        server.enqueue(MockResponse().setBody(mpd(2)))
        server.enqueue(MockResponse().setBody("I"))
        server.enqueue(MockResponse().setChunkedBody("1", 8_192))
        server.enqueue(MockResponse().setBody("2"))
        val dest = dest()

        val r = DashFlacDownloader(ffmpeg).download(OkHttpClient(), server.url("/m.mpd").toString(), emptyMap(), dest, maxBytes = 3)

        assertThat(r.isSuccess).isTrue()
        assertThat(dest.readText()).isEqualTo("I12")
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
