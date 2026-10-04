package com.stash.data.download.backfill

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The backfill deletes a wrong-version (music video) download's file and
 * queues the song again. Two rows can share that file (an import that took a
 * downloaded song's file before imports picked a name of their own), so it
 * stays while another row still records it; the song is queued either way.
 */
class YtLibraryBackfillWorkerSharedFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private val trackDao: TrackDao = mockk(relaxed = true)

    @Test
    fun `a wrong-version file another song still plays from is kept`() = runTest {
        val file = tmp.newFile("lacrymosa.opus")
        coEvery { trackDao.getYtSourceVideoTitleCandidates() } returns listOf(musicVideo(file.absolutePath))
        coEvery { trackDao.countOtherTracksWithFilePath(file.absolutePath, 7L) } returns 1

        worker().doWork()

        assertTrue(file.exists())
        coVerify(exactly = 1) { trackDao.resetForReDownload(7L) }
    }

    @Test
    fun `a wrong-version file only it records is deleted`() = runTest {
        val file = tmp.newFile("lacrymosa.opus")
        coEvery { trackDao.getYtSourceVideoTitleCandidates() } returns listOf(musicVideo(file.absolutePath))
        coEvery { trackDao.countOtherTracksWithFilePath(file.absolutePath, 7L) } returns 0

        worker().doWork()

        assertFalse(file.exists())
        coVerify(exactly = 1) { trackDao.resetForReDownload(7L) }
    }

    /** Already known to be a music video, so no InnerTube lookup is made. */
    private fun musicVideo(filePath: String) = TrackEntity(
        id = 7L, title = "Lacrymosa (Official Video)", artist = "Evanescence", source = MusicSource.YOUTUBE,
        youtubeId = "omv-video", musicVideoType = "OMV", filePath = filePath, isDownloaded = true,
    )

    /** The foreground notification needs WorkManager; it is not what these tests are about. */
    private fun worker(): YtLibraryBackfillWorker {
        val worker = spyk(
            YtLibraryBackfillWorker(
                appContext = mockk<Context>(relaxed = true),
                params = mockk<WorkerParameters>(relaxed = true),
                trackDao = trackDao,
                downloadQueueDao = mockk(relaxed = true),
                searchExecutor = mockk(relaxed = true),
                syncNotificationManager = mockk(relaxed = true),
                syncScheduler = mockk(relaxed = true),
                canonicalizer = mockk(relaxed = true),
            ),
            recordPrivateCalls = true,
        )
        every { worker["createForegroundInfo"](any<String>(), any<String>(), any<Float>()) } returns mockk<ForegroundInfo>()
        coEvery { worker.setForeground(any()) } just Runs
        return worker
    }
}
