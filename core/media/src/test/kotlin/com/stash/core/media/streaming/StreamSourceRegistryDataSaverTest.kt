package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import com.stash.data.download.lossless.LosslessSourcePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Data saver in the resolver chain ([StreamQualityPolicy.dataSaverApplies]: the switch on AND a
 * metered network). Where it applies, no FLAC: the lossless relay rungs and cloud copies are never
 * asked and the lossy rungs serve (at the policy's [LossyQuality], which their own tests cover).
 * Where it doesn't (Wi-Fi, or the switch off) the chain is exactly what it was.
 */
class StreamSourceRegistryDataSaverTest {

    private val qbdlx: QbdlxStreamResolver = mockk { coEvery { resolve(any()) } returns flac("qbdlx") }
    private val jiosaavn: JioSaavnStreamResolver = mockk { coEvery { resolve(any()) } returns null }
    private val youtube: YouTubeStreamResolver = mockk {
        coEvery { resolve(any(), any()) } returns StreamUrl(
            url = "https://example.test/yt.webm", expiresAtMs = Long.MAX_VALUE, codec = "opus", origin = "youtube",
        )
    }
    private val tidal: TidalStreamResolver = mockk {
        coEvery { resolve(any()) } returns flac("tidal")
        coEvery { resolveAacOnly(any()) } returns StreamUrl(
            url = "https://example.test/tidal.mp4", expiresAtMs = Long.MAX_VALUE, codec = "aac", origin = "tidal",
        )
    }
    private val streamingPreference: StreamingPreference = mockk {
        coEvery { isForceQbdlxOnly() } returns false
        coEvery { isForceYouTubeFallback() } returns false
        coEvery { isSkipQobuz() } returns false
    }
    private val losslessPrefs: LosslessSourcePreferences = mockk { coEvery { enabledNow() } returns true }
    private val cloudCopies: CloudCopyFinder = mockk {
        coEvery { find(any(), any(), any()) } returns null
    }

    private fun policy(dataSaver: Boolean, preferTidalAac: Boolean = false): StreamQualityPolicy = mockk {
        coEvery { dataSaverApplies() } returns dataSaver
        coEvery { preferTidalAac() } returns preferTidalAac
        coEvery { preferCloudCopies() } returns true
    }

    private fun registry(policy: StreamQualityPolicy) = StreamSourceRegistry(
        mockk(), mockk(), qbdlx, jiosaavn, youtube, streamingPreference,
        LosslessSourceHealth(), losslessPrefs, Dispatchers.IO, PlaybackDiagnosticsLog(),
        tidal = tidal, qualityPolicy = policy, cloudCopies = cloudCopies,
    )

    @Test
    fun `on mobile data under Data saver no lossless rung is asked and YouTube serves`() = runTest {
        val result = registry(policy(dataSaver = true)).resolve(track())

        assertThat(result!!.origin).isEqualTo("youtube")
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
        coVerify(exactly = 0) { tidal.resolve(any()) }
        coVerify(exactly = 0) { tidal.resolveAacOnly(any()) }
    }

    @Test
    fun `on mobile data under Data saver JioSaavn is still asked first`() = runTest {
        coEvery { jiosaavn.resolve(any()) } returns StreamUrl(
            url = "https://example.test/js.mp4", expiresAtMs = Long.MAX_VALUE, codec = "aac", origin = "jiosaavn",
        )

        assertThat(registry(policy(dataSaver = true)).resolve(track())!!.origin).isEqualTo("jiosaavn")
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
    }

    @Test
    fun `a cloud copy is a lossless file too and waits for Wi-Fi under Data saver`() = runTest {
        registry(policy(dataSaver = true)).resolve(track())

        coVerify(exactly = 0) { cloudCopies.find(any(), any(), any()) }
    }

    @Test
    fun `an opted-in Prefer Tidal AAC still goes first under Data saver, never FLAC after it`() = runTest {
        val result = registry(policy(dataSaver = true, preferTidalAac = true)).resolve(track())

        assertThat(result!!.codec).isEqualTo("aac")
        coVerify(exactly = 1) { tidal.resolveAacOnly(any()) }
        coEvery { tidal.resolveAacOnly(any()) } returns null
        registry(policy(dataSaver = true, preferTidalAac = true)).resolve(track().copy(id = 2L))
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
        coVerify(exactly = 0) { tidal.resolve(any()) }
    }

    @Test
    fun `where Data saver does not apply the lossless chain is unchanged`() = runTest {
        val result = registry(policy(dataSaver = false)).resolve(track())

        assertThat(result!!.origin).isEqualTo("qbdlx")
        coVerifyOrder {
            cloudCopies.find(any(), any(), any())
            qbdlx.resolve(any())
        }
    }

    private fun flac(origin: String) = StreamUrl(
        url = "https://example.test/$origin.flac", expiresAtMs = Long.MAX_VALUE, codec = "flac", origin = origin,
    )

    private fun track() = TrackEntity(
        id = 1L, title = "Title", artist = "Artist", album = "Album", durationMs = 200_000L, youtubeId = "abc123",
    )
}
