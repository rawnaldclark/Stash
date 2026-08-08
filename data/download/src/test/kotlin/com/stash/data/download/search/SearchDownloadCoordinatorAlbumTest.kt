package com.stash.data.download.search

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.core.model.TrackItem
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Regression coverage for the "downloaded album doesn't appear in Library"
 * bug — when [TrackItem.album] is set (the Album Discovery flow), the
 * coordinator must hand a non-empty `album` to [TrackFinalizer.finalizeFile]
 * so the finalized track row groups into an album under
 * [TrackDao.getAllAlbums] (which excludes tracks with empty `album`).
 *
 * Drives the yt-dlp branch for the same reason as
 * [SearchDownloadCoordinatorLoudnessTest] — smallest mock surface that
 * still exercises `upsertSearchTrack`.
 */
class SearchDownloadCoordinatorAlbumTest {

    private val registry: LosslessSourceRegistry = mockk()
    private val previewCache: SimpleCache = mockk(relaxed = true)
    private val httpDataSourceFactory: HttpDataSource.Factory = mockk(relaxed = true)
    private val cacheKeyFactory: CacheKeyFactory = mockk(relaxed = true)
    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
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
    private val loudnessMeasurer: com.stash.core.data.audio.LoudnessMeasurer = mockk(relaxed = true)
    private val lyricsFetchTrigger: LyricsFetchTrigger = mockk(relaxed = true)

    private val tmpCacheDir: File = File(
        System.getProperty("java.io.tmpdir"),
        "stash-search-album-test-${System.nanoTime()}",
    ).also { it.mkdirs() }

    private fun newSubject(): SearchDownloadCoordinator = SearchDownloadCoordinator(
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
        localFileOps = mockk(relaxed = true) { every { acceptDownloadOrDelete(any()) } returns true },
        loudnessMeasurer = loudnessMeasurer,
        lyricsFetchTrigger = lyricsFetchTrigger,
    )

    @Before
    fun setUp() {
        every { context.cacheDir } returns tmpCacheDir
    }

    private fun stubExistingTrackRow(): TrackEntity = TrackEntity(
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

    private fun arrangeSuccessfulDownload(finalizerSlot: io.mockk.CapturingSlot<Track>) {
        coEvery { registry.resolve(any()) } returns null
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns true

        val tempFile = File.createTempFile("search_yt", ".opus")
        tempFile.deleteOnExit()
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Success(tempFile)

        val committed = CommittedTrack(
            filePath = "/library/Sample Artist/Sample.opus",
            sizeBytes = 4096L,
        )
        val meta = AudioMetadata(
            durationMs = 200_000L,
            bitrateKbps = 128,
            format = "opus",
            sampleRateHz = 48_000,
            bitsPerSample = 16,
        )
        coEvery {
            trackFinalizer.finalizeFile(any(), capture(finalizerSlot), any(), any())
        } returns TrackFinalizer.FinalizeResult.Success(committed, meta)

        coEvery { trackDao.findByYoutubeId("vid42") } returns stubExistingTrackRow()
    }

    @Test
    fun `album-context download passes album name to TrackFinalizer`() = runTest {
        val finalizerTrack = slot<Track>()
        arrangeSuccessfulDownload(finalizerTrack)

        val item = TrackItem(
            videoId = "vid42",
            title = "Sample",
            artist = "Sample Artist",
            durationSeconds = 200.0,
            thumbnailUrl = null,
            album = "Sample Album",
        )
        newSubject().download(item).toList()

        coVerify { trackFinalizer.finalizeFile(any(), any(), any(), any()) }
        assertEquals("Sample Album", finalizerTrack.captured.album)
    }

    @Test
    fun `loose-search download leaves album empty (no album context)`() = runTest {
        val finalizerTrack = slot<Track>()
        arrangeSuccessfulDownload(finalizerTrack)

        val item = TrackItem(
            videoId = "vid42",
            title = "Sample",
            artist = "Sample Artist",
            durationSeconds = 200.0,
            thumbnailUrl = null,
            // No album field — typical loose search result.
        )
        newSubject().download(item).toList()

        coVerify { trackFinalizer.finalizeFile(any(), any(), any(), any()) }
        assertEquals("", finalizerTrack.captured.album)
    }
}
