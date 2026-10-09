package com.stash.core.media.cast

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CastMediaServerTest {

    /** A FLAC-looking file: the magic, then counting bytes. */
    private val song = byteArrayOf(0x66, 0x4C, 0x61, 0x43) + ByteArray(196_000) { (it % 251).toByte() }

    private val opens = java.util.concurrent.atomic.AtomicInteger()

    private val server = CastMediaServer(
        audioSource = { opens.incrementAndGet(); ByteArrayDataSource(song) },
        artworkSource = { ByteArrayDataSource(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)) },
        bindAddress = { InetAddress.getLoopbackAddress() },
    )

    @After fun stop() = server.stop()

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun request(url: String, method: String = "GET", range: String? = null): Response {
        val parsed = Uri.parse(url)
        Socket(parsed.host, parsed.port).use { socket ->
            val head = buildString {
                append("$method ${parsed.path} HTTP/1.1\r\nHost: ${parsed.host}\r\n")
                range?.let { append("Range: $it\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(head.toByteArray())
            val raw = ByteArrayOutputStream().also { socket.getInputStream().copyTo(it) }.toByteArray()
            val split = (0 until raw.size - 3).first {
                raw[it] == '\r'.code.toByte() && raw[it + 1] == '\n'.code.toByte() &&
                    raw[it + 2] == '\r'.code.toByte() && raw[it + 3] == '\n'.code.toByte()
            }
            val lines = String(raw, 0, split, Charsets.US_ASCII).split("\r\n")
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Response(lines[0].split(' ')[1].toInt(), headers, raw.copyOfRange(split + 4, raw.size))
        }
    }

    @Test fun `serves the whole song with its sniffed type`() {
        assertThat(server.start()).isTrue()
        val url = server.audioUrl(Uri.parse("file:///music/a"), payload = null)!!

        val response = request(url)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers["content-type"]).isEqualTo("audio/flac")
        assertThat(response.headers["content-length"]).isEqualTo(song.size.toString())
        assertThat(response.body).isEqualTo(song)
    }

    @Test fun `answers range requests, which is how the speaker seeks`() {
        server.start()
        val url = server.audioUrl(Uri.parse("file:///music/a"), payload = null)!!
        request(url, method = "HEAD") // the first request learns the type

        val response = request(url, range = "bytes=100000-100099")

        assertThat(response.status).isEqualTo(206)
        assertThat(response.headers["content-range"]).isEqualTo("bytes 100000-100099/${song.size}")
        assertThat(response.headers["content-type"]).isEqualTo("audio/flac")
        assertThat(response.body).isEqualTo(song.copyOfRange(100_000, 100_100))
    }

    @Test fun `a HEAD after the first read doesn't open the source again`() {
        server.start()
        val url = server.audioUrl(Uri.parse("file:///music/a"), payload = null)!!
        request(url, range = "bytes=0-99")
        val opensAfterGet = opens.get()

        val head = request(url, method = "HEAD", range = "bytes=1000-")

        assertThat(opens.get()).isEqualTo(opensAfterGet)
        assertThat(head.status).isEqualTo(206)
        assertThat(head.headers["content-range"]).isEqualTo("bytes 1000-${song.size - 1}/${song.size}")
        assertThat(head.headers["content-type"]).isEqualTo("audio/flac")
        assertThat(head.body).isEmpty()
    }

        @Test fun `an open-ended range runs to the end`() {
        server.start()
        val url = server.audioUrl(Uri.parse("file:///music/a"), payload = null)!!

        val response = request(url, range = "bytes=190000-")

        assertThat(response.status).isEqualTo(206)
        assertThat(response.body).isEqualTo(song.copyOfRange(190_000, song.size))
    }

    @Test fun `a wrong token or unknown song gets nothing`() {
        server.start()
        val url = server.audioUrl(Uri.parse("file:///music/a"), payload = null)!!
        val parsed = Uri.parse(url)
        val segments = parsed.pathSegments

        val wrongToken = request("http://${parsed.host}:${parsed.port}/${"0".repeat(32)}/m/${segments[2]}")
        val unknownKey = request("http://${parsed.host}:${parsed.port}/${segments[0]}/m/999")
        val audioAsArt = request("http://${parsed.host}:${parsed.port}/${segments[0]}/a/${segments[2]}")

        assertThat(wrongToken.status).isEqualTo(404)
        assertThat(unknownKey.status).isEqualTo(404)
        assertThat(audioAsArt.status).isEqualTo(404)
    }

    @Test fun `web artwork goes to the speaker as is, local covers are served`() {
        server.start()
        assertThat(server.artworkUrl(Uri.parse("https://img.example/cover.jpg"))).isEqualTo("https://img.example/cover.jpg")

        val local = server.artworkUrl(Uri.parse("/data/user/0/com.stash.app/files/art/1.jpg"))!!
        val response = request(local)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.headers["content-type"]).isEqualTo("image/jpeg")
    }

    @Test fun `nothing is served before start or after stop`() {
        assertThat(server.audioUrl(Uri.parse("file:///music/a"), payload = null)).isNull()
        server.start()
        server.stop()
        assertThat(server.audioUrl(Uri.parse("file:///music/a"), payload = null)).isNull()
    }

    @Test fun `a seek past the end is refused, not served`() {
        server.start()
        val url = server.audioUrl(Uri.parse("file:///music/a"), payload = null)!!

        val response = request(url, range = "bytes=${song.size}-")

        assertThat(response.status).isEqualTo(416)
    }

    @Test fun `picks the Wi-Fi address, never mobile data or a VPN`() {
        fun ip(a: Int, b: Int, c: Int, d: Int) = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
        val wifi = ip(192, 168, 1, 23)

        assertThat(
            CastMediaServer.pickLanAddress(
                listOf("rmnet_data0" to ip(10, 44, 0, 7), "tun0" to ip(10, 8, 0, 2), "wlan0" to wifi),
            ),
        ).isEqualTo(wifi)
        // Only mobile data and a VPN: there is no LAN to serve on.
        assertThat(
            CastMediaServer.pickLanAddress(listOf("rmnet_data0" to ip(10, 44, 0, 7), "tun0" to ip(10, 8, 0, 2))),
        ).isNull()
        // An IPv6-only Wi-Fi's 464XLAT shim has an IPv4 address no speaker can reach.
        assertThat(CastMediaServer.pickLanAddress(listOf("v4-wlan0" to ip(192, 0, 0, 4)))).isNull()
        assertThat(CastMediaServer.pickLanAddress(listOf("wlan0" to ip(192, 0, 0, 4)))).isNull()
    }

    @Test fun `accepts a carrier-grade NAT LAN, prefers a private address, skips link-local`() {
        fun ip(a: Int, b: Int, c: Int, d: Int) = InetAddress.getByAddress(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
        val cgnat = ip(100, 64, 3, 9)

        assertThat(CastMediaServer.pickLanAddress(listOf("wlan0" to cgnat))).isEqualTo(cgnat)
        assertThat(CastMediaServer.pickLanAddress(listOf("wlan0" to ip(169, 254, 1, 1)))).isNull()
        assertThat(
            CastMediaServer.pickLanAddress(listOf("wlan0" to cgnat, "wlan1" to ip(192, 168, 0, 5))),
        ).isEqualTo(ip(192, 168, 0, 5))
        assertThat(
            CastMediaServer.pickLanAddress(listOf("wlan0" to InetAddress.getByName("fe80::1"))),
        ).isNull()
    }

        @Test fun `a changed LAN address moves the server, an unchanged one leaves it`() {
        var address: InetAddress? = InetAddress.getByName("127.0.0.1")
        val events = mutableListOf<String>()
        val moving = CastMediaServer(
            audioSource = { ByteArrayDataSource(song) },
            artworkSource = { ByteArrayDataSource(song) },
            bindAddress = { address },
            onEvent = { events += it },
        )
        try {
            moving.start()
            val before = moving.audioUrl(Uri.parse("file:///music/a"), payload = null)!!
            assertThat(moving.ensureCurrentAddress()).isTrue()
            assertThat(moving.audioUrl(Uri.parse("file:///music/a"), payload = null))
                .startsWith(before.substringBeforeLast("/m/"))

            address = InetAddress.getByName("127.0.0.2")
            assertThat(moving.ensureCurrentAddress()).isTrue()
            val after = moving.audioUrl(Uri.parse("file:///music/a"), payload = null)!!
            assertThat(Uri.parse(after).host).isEqualTo("127.0.0.2")
            assertThat(request(after).status).isEqualTo(200)
            assertThat(events).contains("server: LAN address changed, restarting")

            address = null
            assertThat(moving.ensureCurrentAddress()).isFalse()
        } finally {
            moving.stop()
        }
    }

    @Test fun `a file the app may no longer read is a load error, not a crash`() {
        // Thrown from open(), as ContentDataSource does when the folder grant is gone.
        val unreadable = object : androidx.media3.datasource.DataSource {
            override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long =
                throw SecurityException("Permission Denial: opening provider")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
            override fun getUri(): Uri? = null
            override fun close() = Unit
            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
        }
        val denied = CastMediaServer(
            audioSource = { unreadable },
            artworkSource = { ByteArrayDataSource(song) },
            bindAddress = { InetAddress.getLoopbackAddress() },
        )
        try {
            denied.start()
            val url = denied.audioUrl(Uri.parse("content://com.android.externalstorage.documents/x"), payload = null)!!

            assertThat(request(url).status).isEqualTo(502)
        } finally {
            denied.stop()
        }
    }

    @Test fun `any other failure in a request is a 500, not a crash`() {
        val broken = CastMediaServer(
            audioSource = { throw IllegalStateException("boom") },
            artworkSource = { ByteArrayDataSource(song) },
            bindAddress = { InetAddress.getLoopbackAddress() },
        )
        try {
            broken.start()
            val url = broken.audioUrl(Uri.parse("file:///music/a"), payload = null)!!

            assertThat(request(url).status).isEqualTo(500)
        } finally {
            broken.stop()
        }
    }

    @Test fun `the same file keeps its URL, so queue edits can't push the playing song out`() {
        server.start()
        val playing = server.audioUrl(Uri.parse("file:///music/a"), payload = null)!!
        // Far more re-queues than the server keeps entries for: all of b.
        repeat(100) { server.audioUrl(Uri.parse("file:///music/b"), payload = null) }

        assertThat(server.audioUrl(Uri.parse("file:///music/a"), payload = null)).isEqualTo(playing)
        assertThat(request(playing).status).isEqualTo(200)
        assertThat(server.artworkUrl(Uri.parse("file:///music/a"))).isNotEqualTo(playing) // a cover is its own entry
    }

    @Test fun `stopping ends a transfer that is still running`() {
        // A source that never ends: a speaker mid-song, fed a little at a time.
        val endless = object : androidx.media3.datasource.DataSource {
            override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long = androidx.media3.common.C.LENGTH_UNSET.toLong()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                Thread.sleep(5)
                return minOf(length, 1024)
            }
            override fun getUri(): Uri? = null
            override fun close() = Unit
            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
        }
        val streaming = CastMediaServer(
            audioSource = { endless },
            artworkSource = { endless },
            bindAddress = { InetAddress.getLoopbackAddress() },
        )
        streaming.start()
        val url = Uri.parse(streaming.audioUrl(Uri.parse("file:///music/a"), payload = null)!!)
        Socket(url.host, url.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET ${url.path} HTTP/1.1\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            input.read() // the response has started

            streaming.stop()

            // The server closed the connection: the read ends (EOF or reset), it doesn't time out.
            val ended = runCatching { while (input.read(ByteArray(64 * 1024)) != -1) Unit }
            assertThat(ended.exceptionOrNull()).isNotInstanceOf(java.net.SocketTimeoutException::class.java)
        }
    }

    /** What the guarded server reported for the diagnostics bundle. */
    private val guardedEvents: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private fun guarded(maxClients: Int = 16, headerTimeoutMs: Int = 5_000) = CastMediaServer(
        audioSource = { ByteArrayDataSource(song) },
        artworkSource = { ByteArrayDataSource(song) },
        bindAddress = { InetAddress.getLoopbackAddress() },
        onEvent = { guardedEvents += it },
        maxClients = maxClients,
        headerTimeoutMs = headerTimeoutMs,
    )

    /** Reads until the server ends the connection; the milliseconds that took. Fails on a client-side timeout. */
    private fun msUntilClosed(socket: Socket): Long {
        val started = System.nanoTime()
        socket.soTimeout = 5_000
        val ended = runCatching { while (socket.getInputStream().read() != -1) Unit }
        assertThat(ended.exceptionOrNull()).isNotInstanceOf(java.net.SocketTimeoutException::class.java)
        return (System.nanoTime() - started) / 1_000_000
    }

    @Test fun `a flood of connections is turned away at the door, and the speaker gets in once it ends`() {
        val guarded = guarded(maxClients = 2)
        try {
            guarded.start()
            val url = Uri.parse(guarded.audioUrl(Uri.parse("file:///music/a"), payload = null)!!)
            val idle = List(2) { Socket(url.host, url.port) }
            Thread.sleep(300) // both accepted and waiting for a request

            Socket(url.host, url.port).use { extra -> assertThat(msUntilClosed(extra)).isLessThan(2_000L) }
            Socket(url.host, url.port).use { extra -> assertThat(msUntilClosed(extra)).isLessThan(2_000L) }
            // Refusals show in the diagnostics, once per stretch, not once per connection.
            assertThat(guardedEvents.count { it == "server: connection limit reached, refusing connections" }).isEqualTo(1)

            idle.forEach { it.close() }
            // The idle ones' slots free up as their handlers see the close.
            var status = 0
            val deadline = System.nanoTime() + 3_000_000_000L
            while (status != 200 && System.nanoTime() < deadline) {
                status = runCatching { request(url.toString()).status }.getOrDefault(0)
                if (status != 200) Thread.sleep(50)
            }
            assertThat(status).isEqualTo(200)
        } finally {
            guarded.stop()
        }
    }

    @Test fun `a client that connects and says nothing is dropped after the header timeout`() {
        val guarded = guarded(headerTimeoutMs = 300)
        try {
            guarded.start()
            val url = Uri.parse(guarded.audioUrl(Uri.parse("file:///music/a"), payload = null)!!)
            Socket(url.host, url.port).use { silent -> assertThat(msUntilClosed(silent)).isLessThan(2_500L) }
        } finally {
            guarded.stop()
        }
    }

    @Test fun `a client trickling its request a byte at a time is dropped at the header deadline`() {
        val guarded = guarded(headerTimeoutMs = 300)
        try {
            guarded.start()
            val url = Uri.parse(guarded.audioUrl(Uri.parse("file:///music/a"), payload = null)!!)
            Socket(url.host, url.port).use { slow ->
                // Each byte arrives well inside the read timeout; only the overall deadline catches it.
                val drip = Thread {
                    runCatching {
                        val out = slow.getOutputStream()
                        repeat(100) { out.write('G'.code); out.flush(); Thread.sleep(100) }
                    }
                }.apply { isDaemon = true; start() }
                assertThat(msUntilClosed(slow)).isLessThan(2_500L)
                drip.interrupt()
            }
        } finally {
            guarded.stop()
        }
    }

    @Test fun `range parsing`() {
        assertThat(CastMediaServer.parseRange("bytes=0-")).isEqualTo(0L to null)
        assertThat(CastMediaServer.parseRange("bytes=10-20")).isEqualTo(10L to 20L)
        assertThat(CastMediaServer.parseRange("bytes=20-10")).isNull()
        assertThat(CastMediaServer.parseRange("bytes=-500")).isNull()
        assertThat(CastMediaServer.parseRange("bytes=0-1,5-6")).isNull()
    }

    @Test fun `sniffing audio types`() {
        fun sniff(vararg bytes: Int) = CastMediaServer.sniff(ByteArray(bytes.size) { bytes[it].toByte() }, bytes.size, isArtwork = false)
        assertThat(sniff(0x49, 0x44, 0x33, 4)).isEqualTo("audio/mpeg")
        assertThat(sniff(0xFF, 0xFB, 0x90)).isEqualTo("audio/mpeg")
        assertThat(sniff(0xFF, 0xF1, 0x50)).isEqualTo("audio/aac")
        assertThat(sniff(0x4F, 0x67, 0x67, 0x53)).isEqualTo("audio/ogg")
        assertThat(sniff(0, 0, 0, 0x20, 0x66, 0x74, 0x79, 0x70)).isEqualTo("audio/mp4")
        assertThat(sniff(0x1A, 0x45, 0xDF, 0xA3)).isEqualTo("audio/webm")
        assertThat(sniff(1, 2, 3, 4)).isNull()
    }
}
