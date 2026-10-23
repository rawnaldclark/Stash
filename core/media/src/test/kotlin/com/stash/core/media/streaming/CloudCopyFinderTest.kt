package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.CloudCopyCandidate
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.diagnostics.PlaybackDiagnosticsLog
import com.stash.data.download.lossless.LosslessSourcePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CloudCopyFinderTest {

    private fun copy(
        id: Long, title: String, artist: String = "Radiohead", durationMs: Long = 290_000,
        format: String = "flac", bits: Int? = 16,
    ) = CloudCopyCandidate(id, title, artist, durationMs, "stash-cloud://dropbox/1/item$id?v=1", format, bits, 44_100)

    // -- matching ------------------------------------------------------------------------

    @Test fun `the same song by its words matches, case and featuring aside`() {
        assertThat(matchKey("Reckoner", "Radiohead")).isEqualTo(matchKey("reckoner", "RADIOHEAD"))
        assertThat(matchKey("Song (feat. Someone)", "Artist")).isEqualTo(matchKey("Song", "Artist"))
    }

    @Test fun `blank words never match`() {
        assertThat(matchKey("", "Radiohead")).isNull()
    }

    @Test fun `a live take or remix is another recording`() {
        val picked = pickCloudCopy(listOf(copy(1, "Reckoner (Live)")), "Reckoner", 290_000)
        assertThat(picked).isNull()
    }

    @Test fun `lengths must be within three seconds when both are known`() {
        assertThat(pickCloudCopy(listOf(copy(1, "Reckoner", durationMs = 292_500)), "Reckoner", 290_000)).isNotNull()
        assertThat(pickCloudCopy(listOf(copy(1, "Reckoner", durationMs = 310_000)), "Reckoner", 290_000)).isNull()
        assertThat(pickCloudCopy(listOf(copy(1, "Reckoner", durationMs = 0)), "Reckoner", 290_000)).isNotNull()
    }

    @Test fun `the highest bit depth wins, and a failed copy is skipped`() {
        val cd = copy(1, "Reckoner", bits = 16)
        val hires = copy(2, "Reckoner", bits = 24)
        assertThat(pickCloudCopy(listOf(cd, hires), "Reckoner", 290_000)).isEqualTo(hires)
        assertThat(pickCloudCopy(listOf(cd, hires), "Reckoner", 290_000, failed = setOf(hires.cloudUri))).isEqualTo(cd)
    }

    @Test fun `only lossless files stand in, and the index is rebuilt only when the cloud changes`() = runTest {
        val dao = mockk<TrackDao> {
            coEvery { cloudCopySignature() } returns "2:2:1:1"
            coEvery { cloudCopyCandidates() } returns listOf(copy(1, "Reckoner", format = "mp3"), copy(2, "Nude"))
        }
        val finder = CloudCopyFinder(dao)
        assertThat(finder.find("Reckoner", "Radiohead", 290_000)).isNull() // an mp3 copy doesn't count
        assertThat(finder.find("Nude", "Radiohead", 290_000)?.id).isEqualTo(2L)
        coVerify(exactly = 1) { dao.cloudCopyCandidates() }
    }

    // -- the resolve chain ---------------------------------------------------------------

    private val qbdlx: QbdlxStreamResolver = mockk()
    private val losslessPrefs: LosslessSourcePreferences = mockk { coEvery { enabledNow() } returns true }
    private val streamingPreference: StreamingPreference = mockk {
        coEvery { isForceQbdlxOnly() } returns false
        coEvery { isSkipQobuz() } returns false
        coEvery { isForceYouTubeFallback() } returns false
    }
    private val track = TrackEntity(id = 7L, title = "Reckoner", artist = "Radiohead", album = "In Rainbows", durationMs = 290_000L)

    private fun registry(prefer: Boolean, finder: CloudCopyFinder) = StreamSourceRegistry(
        mockk(), mockk(), qbdlx, mockk { coEvery { resolve(any()) } returns null }, mockk(), streamingPreference,
        LosslessSourceHealth(), losslessPrefs, Dispatchers.IO, PlaybackDiagnosticsLog(), null, null,
        mockk<StreamQualityPolicy>(relaxed = true) { coEvery { preferCloudCopies() } returns prefer },
        finder,
    )

    private fun finderWith(vararg copies: CloudCopyCandidate) = CloudCopyFinder(
        mockk {
            coEvery { cloudCopySignature() } returns "x"
            coEvery { cloudCopyCandidates() } returns copies.toList()
        },
    )

    @Test fun `with the setting on, a matching cloud copy plays and no relay is asked`() = runTest {
        val url = registry(prefer = true, finder = finderWith(copy(9, "Reckoner"))).resolve(track)
        assertThat(url?.origin).isEqualTo(CloudCopyFinder.ORIGIN)
        assertThat(url?.url).startsWith("stash-cloud://")
        coVerify(exactly = 0) { qbdlx.resolve(any()) }
    }

    @Test fun `with the setting off, the chain is unchanged`() = runTest {
        coEvery { qbdlx.resolve(any()) } returns StreamUrl("https://q/x.flac", Long.MAX_VALUE, origin = "qbdlx")
        val url = registry(prefer = false, finder = finderWith(copy(9, "Reckoner"))).resolve(track)
        assertThat(url?.origin).isEqualTo("qbdlx")
    }

    @Test fun `with Lossless off, no cloud copy is used`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        val youtube = mockk<YouTubeStreamResolver> { coEvery { resolve(any(), any()) } returns StreamUrl("https://y/x", Long.MAX_VALUE, origin = "youtube") }
        val r = StreamSourceRegistry(
            mockk(), mockk(), qbdlx, mockk { coEvery { resolve(any()) } returns null }, youtube, streamingPreference,
            LosslessSourceHealth(), losslessPrefs, Dispatchers.IO, PlaybackDiagnosticsLog(), null, null,
            mockk<StreamQualityPolicy>(relaxed = true) { coEvery { preferCloudCopies() } returns true },
            finderWith(copy(9, "Reckoner")),
        )
        assertThat(r.resolve(track)?.origin).isNotEqualTo(CloudCopyFinder.ORIGIN)
    }
}
