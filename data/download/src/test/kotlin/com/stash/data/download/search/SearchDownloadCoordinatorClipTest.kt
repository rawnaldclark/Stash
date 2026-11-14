package com.stash.data.download.search

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.QualityPreference
import com.stash.core.model.DownloadMode
import com.stash.core.model.QualityTier
import com.stash.core.model.TrackItem
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Search tab's Download keeps no clip as the song either (DownloadManagerClipTest is the
 * library's): a file whose frames stop at 0:30 under the song's 4:35 header (Tidal's PREVIEW of
 * "Linger", 2026-11-13) is never committed, from a lossless source or from YouTube.
 */
class SearchDownloadCoordinatorClipTest {

    private val tidal = SourceResult(
        sourceId = "tidal_bitchord",
        downloadUrl = "https://relay.example/dash/td/575545.mpd",
        format = AudioFormat(codec = "flac", bitrateKbps = 1411, sampleRateHz = 44_100, bitsPerSample = 16),
        confidence = 1.0f,
        sourceTrackId = "575545",
        isDash = true,
    )

    private val item = TrackItem(videoId = "PXDlU0YDQ6U", title = "Linger", artist = "The Cranberries", durationSeconds = 275.0, thumbnailUrl = null)

    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk {
        coEvery { download(any(), any(), any()) } coAnswers { Result.success(arg<File>(1).apply { writeText("fake-flac") }) }
    }
    private val downloadExecutor: DownloadExecutor = mockk()
    private val trackFinalizer: TrackFinalizer = mockk {
        coEvery { finalizeFile(any(), any(), any(), any()) } returns TrackFinalizer.FinalizeResult.Success(
            committed = CommittedTrack(filePath = "/library/the-cranberries/linger.flac", sizeBytes = 30_000_000L),
            meta = AudioMetadata(durationMs = 274_773L, bitrateKbps = 900, format = "flac"),
        )
    }
    /** Every file's header says the song (4:35); [framesMs] is where its audio stops. */
    private fun extractor(framesMs: Long) = mockk<AudioDurationExtractor> {
        every { extract(any()) } returns AudioMetadata(durationMs = 274_773L, bitrateKbps = 81, format = "flac")
        every { extractMs(any()) } returns 274_773L
        every { extractFramesMs(any()) } returns framesMs
    }
    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "stash-clip-search-download").apply { mkdirs() }

    private fun newSubject(audio: AudioDurationExtractor) = SearchDownloadCoordinator(
        registry = mockk<LosslessSourceRegistry> { coEvery { resolve(any()) } returns tidal },
        previewCache = mockk(relaxed = true),
        httpDataSourceFactory = mockk<HttpDataSource.Factory>(relaxed = true),
        cacheKeyFactory = mockk(relaxed = true),
        downloadExecutor = downloadExecutor,
        trackFinalizer = trackFinalizer,
        trackDao = mockk<TrackDao>(relaxed = true) {
            coEvery { findByAlias(any(), any()) } returns null
            coEvery { findByYoutubeId(any()) } returns null
            coEvery { markAsDownloaded(any(), any(), any(), any(), any(), any()) } returns 1
        },
        musicRepository = mockk(relaxed = true) { coEvery { ensureTrackPersisted(any()) } returns 990_825L },
        blocklistGuard = mockk(relaxed = true),
        context = mockk<Context>(relaxed = true) { every { cacheDir } returns this@SearchDownloadCoordinatorClipTest.cacheDir },
        losslessPrefs = losslessPrefs,
        jioSaavnResolver = mockk(),
        tidalAacResolver = mockk(),
        losslessUrlDownloader = losslessUrlDownloader,
        audioDurationExtractor = audio,
        downloadQueueDao = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true) { every { acceptDownloadOrDelete(any()) } returns true },
        loudnessMeasurer = mockk(relaxed = true),
        lyricsFetchTrigger = mockk(relaxed = true),
        qualityPrefs = mockk<QualityPreference> { every { qualityTier } returns flowOf(QualityTier.MAX) },
        losslessQuota = LosslessQuota.inMemory { 1_792_238_400_000L },
    )

    @Test fun `a lossless file that is a clip under the song's header is never committed`() = runTest {
        coEvery { losslessPrefs.downloadModeNow() } returns DownloadMode.LOSSLESS

        val statuses = newSubject(extractor(framesMs = 29_907L)).download(item).toList()

        assertFalse("a clip must never complete, got $statuses", statuses.any { it is SearchDownloadStatus.Completed })
        coVerify(exactly = 0) { trackFinalizer.finalizeFile(any(), any(), any(), any()) }
    }

    @Test fun `the same file whole is committed as before`() = runTest {
        coEvery { losslessPrefs.downloadModeNow() } returns DownloadMode.LOSSLESS

        val statuses = newSubject(extractor(framesMs = 274_773L)).download(item).toList()

        assertTrue("got $statuses", statuses.last() is SearchDownloadStatus.Completed)
    }

    @Test fun `a YouTube file that is a clip of the song is never committed`() = runTest {
        coEvery { losslessPrefs.downloadModeNow() } returns DownloadMode.OPUS
        val fetched = File.createTempFile("search_yt", ".opus").apply { writeText("fake-opus"); deleteOnExit() }
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns DownloadResult.Success(fetched)

        val statuses = newSubject(extractor(framesMs = 29_907L)).download(item).toList()

        assertTrue("got $statuses", statuses.last() is SearchDownloadStatus.Failed)
        coVerify(exactly = 0) { trackFinalizer.finalizeFile(any(), any(), any(), any()) }
        assertFalse(fetched.exists())
    }
}
