package com.stash.data.download.lossless.addon

import com.google.common.truth.Truth.assertThat
import com.stash.addons.contract.Delivery
import com.stash.addons.contract.StreamInfo
import com.stash.addons.contract.Tier
import com.stash.core.addons.AddonHostOptions
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.NoFirstPartyAddons
import com.stash.core.addons.OpenAddonRateGate
import com.stash.core.addons.PrivateAddressException
import com.stash.core.addons.UnsignedAddons
import com.stash.core.data.audio.FFmpegBridge
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.DashFlacDownloader
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * review-882 B1, the download half: a file an add-on handed over is fetched through the add-on
 * media client and nothing else. The downloader used the app's shared client for it: system DNS,
 * redirects followed, the app's interceptors (their debug logging prints the URL), no
 * `User-Agent: Stash/<version>`. A DASH answer's segment URLs were never shown to the URL rule at
 * all.
 *
 * Each test serves an add-on's file from a MockWebServer and has it point at the user's own
 * network (a redirect to a private address, a redirect to a name that resolves privately, a DASH
 * segment on a private address), then asserts, from the shared client's own event listener, that
 * no connection to anything but the add-on's host was even attempted. On the downloader as it was,
 * each records an attempt on `192.168.1.1` or `10.0.0.5`.
 */
class AddonFetchWiringTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Every DNS lookup and connection attempt of any client built from [shared]. */
    private class Wire : EventListener() {
        val lookups = CopyOnWriteArrayList<String>()
        val connections = CopyOnWriteArrayList<InetSocketAddress>()

        override fun dnsStart(call: Call, domainName: String) {
            lookups += domainName
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connections += inetSocketAddress
            // Don't wait out a connect timeout to report the failure being tested for.
            if (!inetSocketAddress.address.isLoopbackAddress) call.cancel()
        }

        val offLoopback get() = connections.filterNot { it.address.isLoopbackAddress }
    }

    private val wire = Wire()
    private val appInterceptorSaw = CopyOnWriteArrayList<String>()

    private val shared: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname == "media.attacker.example") {
                    listOf(InetAddress.getByAddress(hostname, byteArrayOf(192.toByte(), 168.toByte(), 1, 1)))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
        })
        .eventListener(wire)
        .addInterceptor(Interceptor { chain -> appInterceptorSaw += chain.request().url.encodedPath; chain.proceed(chain.request()) })
        .build()

    /** A debug host: the test's servers are plain http on 127.0.0.1, which only such a host may call. */
    private val host = object : AddonHostOptions {
        override val allowLocalHttp = true
        override val userAgent = "Stash/9.9.9-test"
    }

    private val ffmpeg = object : FFmpegBridge {
        override suspend fun runWithStderrCapture(args: List<String>): String {
            File(args[args.indexOf("-i") + 1]).copyTo(File(args.last()), overwrite = true)
            return ""
        }
    }

    private val server = MockWebServer()
    private lateinit var downloader: LosslessUrlDownloader

    @Before fun setUp() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val addons = HttpAddonClient(shared, OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons, host)
        downloader = LosslessUrlDownloader(shared, DashFlacDownloader(ffmpeg), addons)
    }

    @After fun tearDown() = server.shutdown()

    private fun source(path: String, sourceId: String = "addon:stranger", dash: Boolean = false, maxBytes: Long? = null) = SourceResult(
        sourceId = sourceId,
        downloadUrl = at(path),
        format = AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 44_100, bitsPerSample = 16),
        confidence = 0.9f,
        isDash = dash,
        maxBytes = maxBytes,
    )

    /** Never `server.url()`: its host is the machine's reverse name for 127.0.0.1, which is not always a development host. */
    private fun at(path: String) = "http://127.0.0.1:${server.port}$path"

    private fun redirect(location: String) = MockResponse().setResponseCode(302).setHeader("Location", location)

    private fun dest() = File(tmp.newFolder(), "t.flac")

    @Test fun `an add-on's file is fetched on the add-on client - Stash's User-Agent, none of the app's interceptors`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("fLaC_BYTES_OF_A_FLAC"))
        val dest = dest()
        assertThat(downloader.download(source("/a.flac"), dest).isSuccess).isTrue()
        assertThat(dest.readText()).isEqualTo("fLaC_BYTES_OF_A_FLAC")
        assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("Stash/9.9.9-test")
        assertThat(appInterceptorSaw).isEmpty()

        // Stash's own sources stay on the shared client, as before.
        server.enqueue(MockResponse().setBody("FLAC_BYTES"))
        assertThat(downloader.download(source("/b.flac", sourceId = "qbdlx_qobuz"), dest()).isSuccess).isTrue()
        assertThat(appInterceptorSaw).containsExactly("/b.flac")
    }

    @Test fun `a redirect to a private address - the download fails and no second connection is attempted`() = runBlocking<Unit> {
        server.enqueue(redirect("https://192.168.1.1/a.flac"))
        val dest = dest()
        val result = downloader.download(source("/a.flac"), dest)
        assertThat(result.exceptionOrNull()).isInstanceOf(PrivateAddressException::class.java)
        assertThat(dest.exists()).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(wire.connections).isNotEmpty()
        assertThat(wire.offLoopback).isEmpty()
    }

    @Test fun `a redirect to a name that resolves privately - the download fails and no second connection is attempted`() = runBlocking<Unit> {
        server.enqueue(redirect("https://media.attacker.example/a.flac"))
        val result = downloader.download(source("/a.flac"), dest())
        assertThat(result.exceptionOrNull()).isInstanceOf(UnknownHostException::class.java)
        assertThat(wire.lookups).contains("media.attacker.example")
        assertThat(wire.offLoopback).isEmpty()
    }

    private fun mpd(init: String, media: String) =
        """<MPD><SegmentTemplate initialization="$init" media="$media" startNumber="1">
            <SegmentTimeline><S d="1" r="2"/></SegmentTimeline></SegmentTemplate></MPD>"""

    @Test fun `DASH - a segment on a private address is refused before any connection, and nothing is kept`() = runBlocking<Unit> {
        // The manifest and the init segment are the add-on's own; the media segments are on the user's network.
        server.enqueue(MockResponse().setBody(mpd(at("/init.mp4"), "https://10.0.0.5/seg-\$Number\$.m4s")))
        server.enqueue(MockResponse().setBody("I"))
        val dest = dest()
        val result = downloader.download(source("/m.mpd", dash = true), dest)
        assertThat(result.exceptionOrNull()).isNotNull()
        assertThat(generateSequence(result.exceptionOrNull()) { it.cause }.any { it is PrivateAddressException }).isTrue()
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
        // Asked once and not retried: the manifest, the init, nothing else anywhere.
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(wire.offLoopback).isEmpty()
    }

    @Test fun `DASH - a segment on a name that resolves privately, and one behind a redirect, are refused the same way`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(mpd("https://media.attacker.example/init.mp4", at("/seg/") + "\$Number\$.mp4")))
        assertThat(downloader.download(source("/m.mpd", dash = true), dest()).isFailure).isTrue()
        assertThat(wire.offLoopback).isEmpty()

        server.enqueue(MockResponse().setBody(mpd(at("/init.mp4"), at("/seg/") + "\$Number\$.mp4")))
        server.enqueue(redirect("https://192.168.1.1/init.mp4"))
        assertThat(downloader.download(source("/m.mpd", dash = true), dest()).isFailure).isTrue()
        assertThat(wire.offLoopback).isEmpty()
    }

    @Test fun `DASH - an add-on's manifest and segments are fetched on the add-on client`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(mpd(at("/init.mp4"), at("/seg/") + "\$Number\$.mp4")))
        // The fake remux copies the segments through, so the "FLAC" starts with the init segment.
        server.enqueue(MockResponse().setBody("fLaC_INIT_BYTES_"))
        repeat(3) { server.enqueue(MockResponse().setBody("x")) }
        val dest = dest()
        assertThat(downloader.download(source("/m.mpd", dash = true), dest).isSuccess).isTrue()
        assertThat(dest.readText()).isEqualTo("fLaC_INIT_BYTES_xxx")
        repeat(5) { assertThat(server.takeRequest().getHeader("User-Agent")).isEqualTo("Stash/9.9.9-test") }
        assertThat(appInterceptorSaw).isEmpty()
    }

    // ------------------------------------------------------------------------------------------ the size ceiling (M11)

    @Test fun `a file larger than the song can be is refused by its Content-Length, before a byte is kept`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("x".repeat(4_000)))
        val dest = dest()
        val result = downloader.download(source("/a.flac", maxBytes = 1_000), dest)
        assertThat(result.isFailure).isTrue()
        assertThat(dest.exists()).isFalse()
    }

    @Test fun `a body with no length is cut off at the ceiling and nothing is kept`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setChunkedBody("x".repeat(400_000), 8_192))
        val dest = dest()
        var seen = 0L
        val result = downloader.download(source("/a.flac", maxBytes = 100_000), dest) { read, _ -> seen = read }
        assertThat(result.isFailure).isTrue()
        assertThat(dest.exists()).isFalse()
        // Stopped soon after the ceiling, not at the end of the body.
        assertThat(seen).isLessThan(250_000)
    }

    @Test fun `DASH - segments that add up to more than the song can be fail the download`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(mpd(at("/init.mp4"), at("/seg/") + "\$Number\$.mp4")))
        repeat(4) { server.enqueue(MockResponse().setBody("x".repeat(1_000))) }
        val dest = dest()
        assertThat(downloader.download(source("/m.mpd", dash = true, maxBytes = 2_500), dest).isFailure).isTrue()
        assertThat(dest.parentFile!!.listFiles()!!.toList()).isEmpty()
        // The manifest, the init and two segments: the third was never asked for.
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test fun `within the ceiling the file is kept`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("fLaC" + "x".repeat(896)))
        val dest = dest()
        assertThat(downloader.download(source("/a.flac", maxBytes = 1_000), dest).isSuccess).isTrue()
        assertThat(dest.length()).isEqualTo(900L)
    }

    @Test fun `the ceiling for a song is its own PCM size and a margin, and an hour's when the length is unknown`() {
        val cd = StreamInfo(url = "https://cdn.example/a.flac", manifest = Delivery.NONE, tier = Tier.LOSSLESS, cors = false, bitDepth = 16, sampleRate = 44_100)
        val fourMinutes = AddonLosslessSource.ceilingBytes(240_000L, cd)
        // 240 s of 16-bit/44.1 kHz stereo is 42.3 MB of samples: a real FLAC of it is about 28 MB.
        assertThat(fourMinutes).isGreaterThan(42_336_000L)
        assertThat(fourMinutes).isLessThan(70_000_000L)
        // No format stated: the largest the app asks for, 24-bit/192 kHz.
        val unstated = StreamInfo(url = "https://cdn.example/a.flac", manifest = Delivery.NONE, tier = Tier.LOSSLESS, cors = false)
        assertThat(AddonLosslessSource.ceilingBytes(240_000L, unstated)).isGreaterThan(276_480_000L)
        assertThat(AddonLosslessSource.ceilingBytes(240_000L, unstated)).isLessThan(400_000_000L)
        // No length the app can trust: an hour of it, never "no ceiling".
        assertThat(AddonLosslessSource.ceilingBytes(null, cd)).isEqualTo(AddonLosslessSource.ceilingBytes(3_600_000L, cd))
        assertThat(AddonLosslessSource.ceilingBytes(0L, cd)).isEqualTo(AddonLosslessSource.ceilingBytes(null, cd))
        // Absurd stated formats are held to what exists.
        val absurd = cd.copy(bitDepth = 64, sampleRate = 1_000_000)
        assertThat(AddonLosslessSource.ceilingBytes(240_000L, absurd)).isEqualTo(AddonLosslessSource.ceilingBytes(240_000L, cd.copy(bitDepth = 32, sampleRate = 384_000)))
    }

    // ------------------------------------------------------------------------------------------ the format it was said to be (M9)

    private fun bytes(vararg parts: Any): okio.Buffer = okio.Buffer().apply {
        for (p in parts) when (p) {
            is String -> writeUtf8(p)
            is ByteArray -> write(p)
            else -> error("unsupported part")
        }
    }

    /** An ID3v2.3 tag of [length] bytes of padding: "ID3", version, flags, the length in four 7-bit bytes. */
    private fun id3(length: Int) = byteArrayOf(
        'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
        ((length shr 21) and 0x7f).toByte(), ((length shr 14) and 0x7f).toByte(), ((length shr 7) and 0x7f).toByte(), (length and 0x7f).toByte(),
    ) + ByteArray(length)

    @Test fun `an add-on's lossy file under a FLAC label is not kept`() = runBlocking<Unit> {
        val mp3Frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(400)
        val bodies = listOf(
            bytes(id3(300), mp3Frame), // an MP3 with its tag in front
            bytes(mp3Frame), // a bare MP3
            bytes(ByteArray(4), "ftypM4A ", ByteArray(400)), // AAC in MP4
            bytes("<html>not found</html>".padEnd(64)),
            bytes("fLa"), // too short to be anything
        )
        for (body in bodies) {
            server.enqueue(MockResponse().setBody(body))
            val dest = dest()
            val result = downloader.download(source("/a.flac"), dest)
            assertThat(result.exceptionOrNull()).isInstanceOf(LosslessUrlDownloader.NotTheStatedFormatException::class.java)
            assertThat(dest.exists()).isFalse()
        }
    }

    @Test fun `a FLAC is kept, with or without an ID3 tag in front`() = runBlocking<Unit> {
        for (body in listOf(bytes("fLaC", ByteArray(400)), bytes(id3(300), "fLaC", ByteArray(400)))) {
            server.enqueue(MockResponse().setBody(body))
            assertThat(downloader.download(source("/a.flac"), dest()).isSuccess).isTrue()
        }
    }

    @Test fun `Stash's own sources are not judged by this check`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("x".repeat(64)))
        assertThat(downloader.download(source("/a.flac", sourceId = "qbdlx_qobuz"), dest()).isSuccess).isTrue()
    }
}
