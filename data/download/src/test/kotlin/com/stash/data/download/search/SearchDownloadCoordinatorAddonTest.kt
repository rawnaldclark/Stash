package com.stash.data.download.search

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.QualityPreference
import com.stash.core.model.DownloadMode
import com.stash.core.model.QualityTier
import com.stash.core.model.TrackItem
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
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * review-882 B1, the Search tab's Download button: a file an add-on answered is fetched by
 * [LosslessUrlDownloader] (which puts it on the add-on media client, `AddonFetchWiringTest`), never
 * by the preview cache's HTTP source this coordinator otherwise reads a progressive FLAC through.
 * That source follows redirects and resolves names with none of the add-on base rules.
 */
class SearchDownloadCoordinatorAddonTest {

    private val match = SourceResult(
        sourceId = "addon:stranger",
        downloadUrl = "https://media.stranger.example/a.flac",
        format = AudioFormat(codec = "flac", bitrateKbps = 0, sampleRateHz = 44_100, bitsPerSample = 16),
        confidence = 0.9f,
        sourceTrackId = "42",
    )

    private val item = TrackItem(videoId = "dQw4w9WgXcQ", title = "Digital Love", artist = "Daft Punk", durationSeconds = 301.0, thumbnailUrl = null)

    private val registry: LosslessSourceRegistry = mockk { coEvery { resolve(any()) } returns match }
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true) {
        coEvery { downloadModeNow() } returns DownloadMode.LOSSLESS
    }
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk {
        coEvery { download(any(), any(), any()) } coAnswers { Result.success(arg<File>(1)) }
    }
    private val httpDataSourceFactory: HttpDataSource.Factory = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true) {
        coEvery { findByAlias(any(), any()) } returns null
        coEvery { findByYoutubeId(any()) } returns null
        coEvery { markAsDownloaded(any(), any(), any(), any(), any(), any()) } returns 1
    }
    private val context: Context = mockk(relaxed = true) {
        every { cacheDir } returns File(System.getProperty("java.io.tmpdir"), "stash-addon-search-download").apply { mkdirs() }
    }

    private fun newSubject() = SearchDownloadCoordinator(
        registry = registry,
        previewCache = mockk(relaxed = true),
        httpDataSourceFactory = httpDataSourceFactory,
        cacheKeyFactory = mockk(relaxed = true),
        downloadExecutor = mockk(),
        trackFinalizer = mockk {
            coEvery { finalizeFile(any(), any(), any(), any()) } returns TrackFinalizer.FinalizeResult.Success(
                committed = CommittedTrack(filePath = "/library/daft-punk/digital-love.flac", sizeBytes = 30_000_000L),
                meta = AudioMetadata(durationMs = 301_000L, bitrateKbps = 900, format = "flac"),
            )
        },
        trackDao = trackDao,
        musicRepository = mockk(relaxed = true) { coEvery { ensureTrackPersisted(any()) } returns 4242L },
        blocklistGuard = mockk(relaxed = true),
        context = context,
        losslessPrefs = losslessPrefs,
        jioSaavnResolver = mockk(),
        tidalAacResolver = mockk(),
        losslessUrlDownloader = losslessUrlDownloader,
        audioDurationExtractor = mockk { every { extract(any()) } returns AudioMetadata(durationMs = 301_000L, bitrateKbps = 900, format = "flac") },
        downloadQueueDao = mockk(relaxed = true),
        localFileOps = mockk(relaxed = true) { every { acceptDownloadOrDelete(any()) } returns true },
        loudnessMeasurer = mockk(relaxed = true),
        lyricsFetchTrigger = mockk(relaxed = true),
        qualityPrefs = mockk<QualityPreference> { every { qualityTier } returns flowOf(QualityTier.MAX) },
        losslessQuota = LosslessQuota.inMemory { 1_792_238_400_000L },
    )

    @Test fun `an add-on's file is fetched by the downloader, never through the preview cache's HTTP source`() = runTest {
        val statuses = newSubject().download(item).toList()

        coVerify(exactly = 1) { losslessUrlDownloader.download(match, any(), any()) }
        verify(exactly = 0) { httpDataSourceFactory.createDataSource() }
        assertEquals(SearchDownloadStatus.Completed, statuses.last())
    }
}
