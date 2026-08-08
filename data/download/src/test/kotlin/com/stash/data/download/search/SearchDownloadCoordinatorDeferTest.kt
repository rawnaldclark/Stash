package com.stash.data.download.search

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.DownloadStatus
import com.stash.core.model.TrackItem
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.DownloadResult
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the v0.9.17 deferral branch in [SearchDownloadCoordinator].
 *
 * Search-tab parity with [com.stash.data.download.DownloadManager]: when
 * the lossless registry returns null AND
 * [LosslessSourcePreferences.youtubeFallbackEnabledNow] is false, the
 * coordinator must short-circuit with
 * [SearchDownloadStatus.WaitingForLossless] and write
 * [DownloadStatus.WAITING_FOR_LOSSLESS] to the queue rather than falling
 * through to yt-dlp.
 *
 * Unlike the sync side, the search-tab path has NO Stash-Mix exemption —
 * search-tab downloads are explicit user actions (the user clicked
 * Download on a specific track) and Stash Mix is a sync-time
 * auto-population mechanism orthogonal to the search-tab path.
 */
class SearchDownloadCoordinatorDeferTest {

    private val registry: LosslessSourceRegistry = mockk()
    private val previewCache: SimpleCache = mockk(relaxed = true)
    private val httpDataSourceFactory: HttpDataSource.Factory = mockk(relaxed = true)
    private val cacheKeyFactory: CacheKeyFactory = mockk(relaxed = true)
    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val trackFinalizer: TrackFinalizer = mockk(relaxed = true)
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

    private fun stubTrack(): TrackItem = TrackItem(
        videoId = "vid42",
        title = "Sample",
        artist = "Sample Artist",
        durationSeconds = 200.0,
        thumbnailUrl = null,
    )

    @Test
    fun `registry-null + fallback-off emits WaitingForLossless and writes DB row`() = runTest {
        // Defer only makes sense with lossless ON (master switch) + fallback OFF.
        coEvery { losslessPrefs.enabledNow() } returns true
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns false
        coEvery { registry.resolve(any()) } returns null
        // Search-tab defer path looks up the Track row by youtubeId first,
        // then the queue row by trackId. Both must be stubbed for the
        // DAO write to fire.
        val trackEntity = TrackEntity(
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
        coEvery { trackDao.findByYoutubeId("vid42") } returns trackEntity
        val queueEntry = DownloadQueueEntity(
            id = 99L,
            trackId = 7L,
            status = DownloadStatus.IN_PROGRESS,
        )
        coEvery { downloadQueueDao.getByTrackId(7L) } returns queueEntry

        val statuses = newSubject().download(stubTrack()).toList()

        assertTrue(
            "expected a WaitingForLossless emission, got $statuses",
            statuses.any { it is SearchDownloadStatus.WaitingForLossless },
        )
        coVerify {
            downloadQueueDao.updateStatus(
                id = 99L,
                status = DownloadStatus.WAITING_FOR_LOSSLESS,
            )
        }
    }

    @Test
    fun `lossless disabled skips the registry and never defers (goes straight to yt-dlp)`() = runTest {
        // Master lossless switch OFF: the search path must NOT consult the
        // lossless registry and must NOT defer to WAITING_FOR_LOSSLESS — it
        // should go straight to yt-dlp, matching DownloadManager. (Bug: the
        // search path previously ignored enabledNow(), so with lossless off +
        // fallback off + sources down, every artist-page download hung on
        // "waiting for lossless".)
        coEvery { losslessPrefs.enabledNow() } returns false
        // yt-dlp path builds File(context.cacheDir, ...); give it a real dir.
        every { context.cacheDir } returns
            java.io.File(System.getProperty("java.io.tmpdir"), "stashtest").apply { mkdirs() }
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.NoOutput(null, null)

        val statuses = newSubject().download(stubTrack()).toList()

        assertTrue(
            "must not wait for lossless when lossless is disabled, got $statuses",
            statuses.none { it is SearchDownloadStatus.WaitingForLossless },
        )
        coVerify(exactly = 0) { registry.resolve(any()) }
    }
}
