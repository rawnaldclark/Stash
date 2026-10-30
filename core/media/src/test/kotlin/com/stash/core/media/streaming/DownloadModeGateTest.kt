package com.stash.core.media.streaming

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import java.io.IOException
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Download mode at open time (review on #731): a source made while streaming was allowed, opened after the switch flipped,
 * makes no request; a file on the phone still opens.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadModeGateTest {

    /** Counts opens instead of touching the network. */
    private class CountingSource : DataSource {
        var opens = 0
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long {
            opens++
            return 0
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    @Test
    fun `a stream made online and opened after the switch flips makes no request`() {
        var allowed = true
        val upstream = CountingSource()
        val gated = DownloadModeGate.gate({ upstream }, { allowed }).createDataSource() // made while streaming is allowed
        allowed = false

        assertThrows(IOException::class.java) { gated.open(DataSpec(Uri.parse("https://cdn.example/a.m4a"))) }
        assertThat(upstream.opens).isEqualTo(0)

        allowed = true
        gated.open(DataSpec(Uri.parse("https://cdn.example/a.m4a")))
        assertThat(upstream.opens).isEqualTo(1)
    }

    @Test
    fun `a file on the phone opens in Download mode, a cloud file does not`() {
        val upstream = CountingSource()
        DownloadModeGate(upstream, { false }).open(DataSpec(Uri.parse("file:///music/a.flac")))
        DownloadModeGate(upstream, { false }).open(DataSpec(Uri.parse("content://media/external/audio/1")))
        assertThat(upstream.opens).isEqualTo(2)

        assertThrows(IOException::class.java) {
            DownloadModeGate(upstream, { false }, alwaysNetwork = true).open(DataSpec(Uri.parse("stash-cloud://dropbox/x")))
        }
        assertThat(upstream.opens).isEqualTo(2)
    }

    @Test
    fun `the factory's own sources check the switch when they open, not when made`() {
        var allowed = true
        val factory = StashMediaSourceFactory(
            context = ApplicationProvider.getApplicationContext(),
            streamingFactory = mockk(relaxed = true),
            streamingTrackId = { null },
            isJioSaavnOrigin = { false },
            httpClient = OkHttpClient(),
            resolver = mockk(relaxed = true),
            urlCache = mockk(relaxed = true),
            trackDao = mockk(relaxed = true),
            streamAllowed = { allowed },
        )
        val stream = MediaItem.fromUri("https://127.0.0.1:9/never.m4a")
        val source = factory.dataSourceFactoryFor(stream).createDataSource() // routed and made in Stream only
        allowed = false

        // Refused by the gate before any connection: the message names Download mode, not a refused connection.
        val e = assertThrows(IOException::class.java) { source.open(DataSpec(stream.localConfiguration!!.uri)) }
        assertThat(e.message).contains("Download mode")
    }

    @Test
    fun `network and local uris`() {
        assertThat(DownloadModeGate.isNetwork(Uri.parse("https://x/a"))).isTrue()
        assertThat(DownloadModeGate.isNetwork(Uri.parse("stash-cloud://d/x"))).isTrue()
        assertThat(DownloadModeGate.isNetwork(Uri.parse("file:///a"))).isFalse()
        assertThat(DownloadModeGate.isNetwork(Uri.parse("stash-resolve://track/1"))).isFalse()
        assertThat(DownloadModeGate.isNetwork(null)).isFalse()
    }
}
