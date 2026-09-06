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
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.TrackItem
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Regression coverage for truthful terminal download status. */
class SearchDownloadCoordinatorCompletionTest {

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
    private val losslessUrlDownloader: com.stash.data.download.lossless.LosslessUrlDownloader = mockk()
    private val audioDurationExtractor: com.stash.core.data.audio.AudioDurationExtractor = mockk()
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val localFileOps: LocalFileOps = mockk()
    private val loudnessMeasurer: LoudnessMeasurer = mockk(relaxed = true)
    private val lyricsFetchTrigger: LyricsFetchTrigger = mockk(relaxed = true)

    private val tmpCacheDir = File(
        System.getProperty("java.io.tmpdir"),
        "stash-search-completion-test-${System.nanoTime()}",
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
    )

    @Before
    fun setUp() {
        every { context.cacheDir } returns tmpCacheDir
        every { localFileOps.acceptDownloadOrDelete(any()) } returns true
    }

    private fun track() = TrackItem(
        videoId = "vid42",
        title = "Sample",
        artist = "Sample Artist",
        durationSeconds = 200.0,
        thumbnailUrl = null,
    )

    private fun existingTrack() = TrackEntity(
        id = 7L,
        title = "Sample",
        artist = "Sample Artist",
        youtubeId = "vid42",
        canonicalTitle = "sample",
        canonicalArtist = "sample artist",
        durationMs = 200_000L,
        source = MusicSource.YOUTUBE,
        albumArtUrl = null,
    )

    private fun arrangeFinalizedYtDlpDownload() {
        coEvery { losslessPrefs.enabledNow() } returns false
        val tempFile = File.createTempFile("search_yt", ".opus").apply { deleteOnExit() }
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Success(tempFile)
        coEvery {
            trackFinalizer.finalizeFile(any(), any(), any(), any())
        } returns TrackFinalizer.FinalizeResult.Success(
            committed = CommittedTrack(
                filePath = "/library/Sample Artist/Sample.opus",
                sizeBytes = 4096L,
            ),
            meta = AudioMetadata(
                durationMs = 200_000L,
                bitrateKbps = 128,
                format = "opus",
                sampleRateHz = 48_000,
                bitsPerSample = 16,
            ),
        )
        coEvery { trackDao.findByYoutubeId("vid42") } returns existingTrack()
        coEvery {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        } returns 1
    }

    private fun arrangeFinalizedJioSaavnDownload() {
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery { jioSaavnResolver.resolve(any(), any()) } returns SourceResult(
            sourceId = JioSaavnResolver.SOURCE_ID,
            downloadUrl = "https://aac.saavncdn.com/song_320.mp4",
            format = AudioFormat(
                codec = "aac",
                bitrateKbps = 320,
                sampleRateHz = 44_100,
                fileExtension = "m4a",
            ),
            confidence = 0.97f,
        )
        coEvery { losslessUrlDownloader.download(any(), any(), any()) } coAnswers {
            Result.success(arg<File>(1))
        }
        coEvery { audioDurationExtractor.extract(any()) } returns AudioMetadata(
            durationMs = 200_000L,
            bitrateKbps = 313,
            format = "aac",
            sampleRateHz = 44_100,
            bitsPerSample = 16,
        )
        coEvery { trackFinalizer.finalizeFile(any(), any(), any(), any()) } returns
            TrackFinalizer.FinalizeResult.Success(
                committed = CommittedTrack(
                    filePath = "/library/Sample Artist/Sample.m4a",
                    sizeBytes = 4096L,
                ),
                meta = AudioMetadata(
                    durationMs = 200_000L,
                    bitrateKbps = 313,
                    format = "aac",
                    sampleRateHz = 44_100,
                    bitsPerSample = 16,
                ),
            )
        coEvery { trackDao.findByYoutubeId("vid42") } returns existingTrack()
        coEvery {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        } returns 1
    }

    private fun assertFailedNeverCompleted(statuses: List<SearchDownloadStatus>) {
        assertTrue("terminal status must be Failed, got $statuses", statuses.last() is SearchDownloadStatus.Failed)
        assertFalse("Completed must never be emitted after persistence failure, got $statuses", statuses.any {
            it is SearchDownloadStatus.Completed
        })
    }

    @Test
    fun `lossless disabled still tries JioSaavn before YouTube`() = runTest {
        arrangeFinalizedYtDlpDownload()

        val statuses = newSubject().download(track()).toList()

        assertTrue(statuses.last() is SearchDownloadStatus.Completed)
        coVerifyOrder {
            jioSaavnResolver.resolve(any(), any())
            downloadExecutor.download(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `valid JioSaavn AAC commits and never starts YouTube`() = runTest {
        arrangeFinalizedJioSaavnDownload()

        val statuses = newSubject().download(track()).toList()

        assertTrue(statuses.last() is SearchDownloadStatus.Completed)
        assertTrue(
            statuses.any {
                it == SearchDownloadStatus.Downloading(SearchDownloadStatus.Source.JIOSAAVN)
            },
        )
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid JioSaavn media falls through to YouTube before commit`() = runTest {
        arrangeFinalizedYtDlpDownload()
        coEvery { jioSaavnResolver.resolve(any(), any()) } returns SourceResult(
            sourceId = JioSaavnResolver.SOURCE_ID,
            downloadUrl = "https://aac.saavncdn.com/song_320.mp4",
            format = AudioFormat("aac", 320, 44_100, fileExtension = "m4a"),
            confidence = 0.97f,
        )
        coEvery { losslessUrlDownloader.download(any(), any(), any()) } coAnswers {
            Result.success(arg<File>(1))
        }
        coEvery { audioDurationExtractor.extract(any()) } returns AudioMetadata(
            durationMs = 200_000L,
            bitrateKbps = 160,
            format = "aac",
            sampleRateHz = 44_100,
            bitsPerSample = 16,
        )

        val statuses = newSubject().download(track()).toList()

        assertTrue(statuses.last() is SearchDownloadStatus.Completed)
        coVerify(exactly = 1) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { trackFinalizer.finalizeFile(any(), any(), any(), any()) }
    }

    @Test
    fun `JioSaavn persistence failure is terminal and never starts YouTube`() = runTest {
        arrangeFinalizedJioSaavnDownload()
        coEvery {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("database unavailable")

        val statuses = newSubject().download(track()).toList()

        assertFailedNeverCompleted(statuses)
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancelling one collector keeps the shared producer deduplicated`() = runTest {
        arrangeFinalizedYtDlpDownload()
        val resolverStarted = CompletableDeferred<Unit>()
        val releaseResolver = CompletableDeferred<Unit>()
        coEvery { jioSaavnResolver.resolve(any(), any()) } coAnswers {
            resolverStarted.complete(Unit)
            releaseResolver.await()
            null
        }
        val subject = newSubject()

        val firstCollector = launch { subject.download(track()).collect() }
        resolverStarted.await()
        firstCollector.cancelAndJoin()

        val secondCollector = async { subject.download(track()).toList() }
        // Let the second collector reach the in-flight map BEFORE the producer is
        // released: the release lets performDownload finish on the coordinator's
        // own IO scope, and its completion removes the entry. If the second
        // collector is still only queued at that point, it finds nothing, starts
        // a fresh producer, and the resolver is called twice — a slow runner lost
        // that race on CI (runs 34042699358, 34040230128, 34002425891).
        runCurrent()
        releaseResolver.complete(Unit)
        val statuses = secondCollector.await()

        assertTrue(statuses.last() is SearchDownloadStatus.Completed)
        coVerify(exactly = 1) { jioSaavnResolver.resolve(any(), any()) }
        coVerify(exactly = 1) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid committed file fails instead of completing`() = runTest {
        arrangeFinalizedYtDlpDownload()
        every { localFileOps.acceptDownloadOrDelete(any()) } returns false

        val statuses = newSubject().download(track()).toList()

        assertFailedNeverCompleted(statuses)
        coVerify(exactly = 0) { trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { musicRepository.linkTrackToDownloadsMix(any()) }
    }

    @Test
    fun `markAsDownloaded failure fails instead of completing`() = runTest {
        arrangeFinalizedYtDlpDownload()
        coEvery {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("database unavailable")

        val statuses = newSubject().download(track()).toList()

        assertFailedNeverCompleted(statuses)
        coVerify(exactly = 1) { musicRepository.linkTrackToDownloadsMix(7L) }
    }
    @Test
    fun `zero-row markAsDownloaded fails instead of completing`() = runTest {
        arrangeFinalizedYtDlpDownload()
        coEvery {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        } returns 0

        val statuses = newSubject().download(track()).toList()

        assertFailedNeverCompleted(statuses)
    }


    @Test
    fun `downloads-mix link failure fails instead of completing`() = runTest {
        arrangeFinalizedYtDlpDownload()
        coEvery { musicRepository.linkTrackToDownloadsMix(7L) } throws
            IllegalStateException("downloads mix unavailable")

        val statuses = newSubject().download(track()).toList()

        assertFailedNeverCompleted(statuses)
        coVerify(exactly = 0) { trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `persistence cancellation is propagated`() = runTest {
        arrangeFinalizedYtDlpDownload()
        coEvery {
            trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any())
        } throws CancellationException("cancelled")

        var cancellationPropagated = false
        try {
            newSubject().download(track()).toList()
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue(cancellationPropagated)
    }
    @Test
    fun `yt-dlp cancellation is propagated`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } throws CancellationException("cancelled")

        var cancellationPropagated = false
        try {
            newSubject().download(track()).toList()
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }
        assertTrue(cancellationPropagated)
    }

}
