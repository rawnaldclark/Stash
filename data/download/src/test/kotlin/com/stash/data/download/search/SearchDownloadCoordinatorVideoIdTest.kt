package com.stash.data.download.search

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.audio.LoudnessMeasurer
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.prefs.QualityPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.QualityTier
import com.stash.core.model.TrackItem
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A search row's video id names the download's temp files and its yt-dlp
 * URL, and becomes the track's youtube_id. An id without the YouTube
 * video-id shape fails the download before any of that happens, on every
 * path.
 */
class SearchDownloadCoordinatorVideoIdTest {

    private val registry: LosslessSourceRegistry = mockk(relaxed = true)
    private val previewCache: SimpleCache = mockk(relaxed = true)
    private val httpDataSourceFactory: HttpDataSource.Factory = mockk(relaxed = true)
    private val cacheKeyFactory: CacheKeyFactory = mockk(relaxed = true)
    private val downloadExecutor: DownloadExecutor = mockk()
    private val trackFinalizer: TrackFinalizer = mockk()
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val jioSaavnResolver: JioSaavnResolver = mockk {
        coEvery { resolve(any(), any()) } returns null
    }
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk()
    private val audioDurationExtractor: com.stash.core.data.audio.AudioDurationExtractor = mockk()
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val localFileOps: LocalFileOps = mockk()
    private val loudnessMeasurer: LoudnessMeasurer = mockk(relaxed = true)
    private val lyricsFetchTrigger: LyricsFetchTrigger = mockk(relaxed = true)
    private val qualityPrefs: QualityPreference = mockk { every { qualityTier } returns flowOf(QualityTier.MAX) }

    private val tmpCacheDir = File(
        System.getProperty("java.io.tmpdir"),
        "stash-search-videoid-test-${System.nanoTime()}",
    ).also { it.mkdirs() }

    private fun newSubject() = SearchDownloadCoordinator(
        registry = registry,
        previewCache = previewCache,
        httpDataSourceFactory = httpDataSourceFactory,
        cacheKeyFactory = cacheKeyFactory,
        downloadExecutor = downloadExecutor,
        trackFinalizer = trackFinalizer,
        trackDao = trackDao,
        musicRepository = musicRepository,
        blocklistGuard = blocklistGuard,
        context = context,
        losslessPrefs = losslessPrefs,
        jioSaavnResolver = jioSaavnResolver,
        losslessUrlDownloader = losslessUrlDownloader,
        audioDurationExtractor = audioDurationExtractor,
        downloadQueueDao = downloadQueueDao,
        localFileOps = localFileOps,
        loudnessMeasurer = loudnessMeasurer,
        lyricsFetchTrigger = lyricsFetchTrigger,
        qualityPrefs = qualityPrefs,
    )

    @Before
    fun setUp() {
        every { context.cacheDir } returns tmpCacheDir
        every { localFileOps.acceptDownloadOrDelete(any()) } returns true
    }

    private fun item(videoId: String) = TrackItem(
        videoId = videoId,
        title = "Sample",
        artist = "Sample Artist",
        durationSeconds = 200.0,
        thumbnailUrl = null,
    )

    private val malformedIds = listOf(
        "../x",
        "dQw4w9WgXcQ/../x",
        "x\\y",
        "a%(title)s",
        "dQw4w9WgXc", // ten characters
        "dQw4w9WgXcQQ", // twelve
        "",
    )

    private fun assertFailed(statuses: List<SearchDownloadStatus>, id: String) {
        assertTrue("'$id' must fail, got $statuses", statuses.last() is SearchDownloadStatus.Failed)
        assertTrue("'$id' must never complete", statuses.none { it is SearchDownloadStatus.Completed })
    }

    @Test
    fun `a malformed id never reaches yt-dlp`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false

        malformedIds.forEach { id ->
            assertFailed(newSubject().download(item(id)).toList(), id)
        }

        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackFinalizer.finalizeFile(any(), any(), any(), any()) }
    }

    @Test
    fun `a malformed id never reaches the lossless or JioSaavn temp files`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns true
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns true

        malformedIds.forEach { id ->
            assertFailed(newSubject().download(item(id)).toList(), id)
        }

        coVerify(exactly = 0) { registry.resolve(any()) }
        coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { losslessUrlDownloader.download(any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.insert(any()) }
    }

    @Test
    fun `a real video id still downloads through yt-dlp`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        val tempFile = File.createTempFile("search_yt", ".opus").apply { deleteOnExit() }
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns
            DownloadResult.Success(tempFile)
        coEvery { trackFinalizer.finalizeFile(any(), any(), any(), any()) } returns
            TrackFinalizer.FinalizeResult.Success(
                committed = CommittedTrack(filePath = "/library/Sample Artist/Sample.opus", sizeBytes = 4096L),
                meta = AudioMetadata(
                    durationMs = 200_000L,
                    bitrateKbps = 128,
                    format = "opus",
                    sampleRateHz = 48_000,
                    bitsPerSample = 16,
                ),
            )
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.findByCanonicalIdentity(any(), any()) } returns null
        coEvery { trackDao.insert(any()) } returns 7L
        coEvery { trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any()) } returns 1

        val statuses = newSubject().download(item("dQw4w9WgXcQ")).toList()

        assertTrue("got $statuses", statuses.last() is SearchDownloadStatus.Completed)
        coVerify(exactly = 1) {
            downloadExecutor.download(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                any(),
                "search_dQw4w9WgXcQ",
                any(),
                any(),
            )
        }
    }
}
