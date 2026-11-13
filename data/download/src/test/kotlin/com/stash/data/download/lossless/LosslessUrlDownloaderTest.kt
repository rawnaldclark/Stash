package com.stash.data.download.lossless

import com.google.common.truth.Truth.assertThat
import com.stash.core.addons.HttpAddonClient
import com.stash.core.addons.NoFirstPartyAddons
import com.stash.core.addons.OpenAddonRateGate
import com.stash.core.addons.UnsignedAddons
import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests for [LosslessUrlDownloader]: it streams the resolved CDN body to the
 * destination temp file and reports success/failure so the caller can fall
 * through to the next download strategy.
 */
class LosslessUrlDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: LosslessUrlDownloader

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        val http = OkHttpClient()
        // The add-on client is only reached for an `addon:` source (AddonFetchWiringTest); none here.
        val addons = HttpAddonClient(http, OpenAddonRateGate, NoFirstPartyAddons, UnsignedAddons)
        downloader = LosslessUrlDownloader(http, io.mockk.mockk(relaxed = true), addons)
    }

    @After fun tearDown() = server.shutdown()

    private fun source(url: String) = SourceResult(
        sourceId = "qbdlx_qobuz",
        downloadUrl = url,
        format = AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 0, bitsPerSample = 0),
        confidence = 0.9f,
    )

    @Test fun `writes body to destination on success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("CLEAR_FLAC_BYTES"))
        val dest = File(tmp.root, "track.flac")

        val result = downloader.download(source(server.url("/f.flac").toString()), dest)

        assertThat(result.isSuccess).isTrue()
        assertThat(dest.readText()).isEqualTo("CLEAR_FLAC_BYTES")
    }

    @Test fun `returns failure and cleans up on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val dest = File(tmp.root, "track.flac")

        val result = downloader.download(source(server.url("/missing").toString()), dest)

        assertThat(result.isFailure).isTrue()
        assertThat(dest.exists()).isFalse()
    }
}
