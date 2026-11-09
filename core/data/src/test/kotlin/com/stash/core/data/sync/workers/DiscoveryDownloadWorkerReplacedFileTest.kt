package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.files.TrackFileReplacement
import com.stash.core.data.sync.DownloadJobRegistry
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.data.sync.TrackDownloader
import com.stash.core.model.MusicSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A re-download's old file goes only after the row records the new one. The
 * downloader deleted it as soon as the new file passed validation, so a cancel
 * landing before the row write (whose branch deletes the new file) or a
 * failure in between left the track with no file, or its row on a deleted
 * one. And a track handed back for a better copy with its file kept
 * (markForReplacement: not downloaded, file_path kept) gets that file back as
 * its download when the re-download fails for good, instead of dropping out of
 * the Library. A real Room database and real files.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiscoveryDownloadWorkerReplacedFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: StashDatabase
    private val trackDownloader = mockk<TrackDownloader>()
    private val audioDurationExtractor = mockk<AudioDurationExtractor>(relaxed = true)
    private lateinit var oldFile: File
    private lateinit var newFile: File

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, StashDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        oldFile = tmp.newFile("lacrymosa.opus").apply { writeBytes(ByteArray(AUDIO_BYTES)) }
        newFile = tmp.newFile("lacrymosa-synthesis.opus").apply { writeBytes(ByteArray(AUDIO_BYTES)) }
    }

    @After fun tearDown() { db.close() }

    @Test fun `a committed re-download deletes the old file once the row records the new one`() = runBlocking {
        val trackId = trackHandedBackForReplacement()
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns
            TrackDownloadOutcome.Success(newFile.absolutePath)

        buildWorker().doWork()

        val row = db.trackDao().getById(trackId)!!
        assertEquals(newFile.absolutePath, row.filePath)
        assertTrue(row.isDownloaded)
        assertTrue(newFile.exists())
        assertFalse(oldFile.exists())
    }

    @Test fun `a cancel after the new file passed validation keeps the old file`() = runBlocking {
        val trackId = trackHandedBackForReplacement()
        val queueId = db.downloadQueueDao().getByTrackId(trackId)!!.id
        // The user cancels while the downloader returns: the worker's cancel
        // branch deletes the new file before any row write.
        coEvery { trackDownloader.downloadTrack(any(), any()) } coAnswers {
            db.downloadQueueDao().markCancelledIfActive(queueId, System.currentTimeMillis())
            TrackDownloadOutcome.Success(newFile.absolutePath)
        }

        buildWorker().doWork()

        assertTrue("the old file is the track's only copy", oldFile.exists())
        assertFalse(newFile.exists())
        val row = db.trackDao().getById(trackId)!!
        assertEquals(oldFile.absolutePath, row.filePath)
        assertTrue("the kept file is its download again", row.isDownloaded)
    }

    @Test fun `a failure after validation keeps the old file`() = runBlocking {
        val trackId = trackHandedBackForReplacement()
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns
            TrackDownloadOutcome.Success(newFile.absolutePath)
        // Throws between validation and the row write.
        every { audioDurationExtractor.extract(any()) } throws IllegalStateException("retriever died")

        runCatching { buildWorker().doWork() }

        assertTrue(oldFile.exists())
        assertEquals(oldFile.absolutePath, db.trackDao().getById(trackId)!!.filePath)
    }

    @Test fun `a re-download that fails for good restores the kept file`() = runBlocking {
        val trackId = trackHandedBackForReplacement()
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns
            TrackDownloadOutcome.Failed("video unavailable")

        buildWorker().doWork()

        val row = db.trackDao().getById(trackId)!!
        assertTrue(oldFile.exists())
        assertEquals(oldFile.absolutePath, row.filePath)
        assertTrue("back in the Library on its kept file", row.isDownloaded)
    }

    @Test fun `a re-download the user cancels before it returns restores the kept file`() = runBlocking {
        val trackId = trackHandedBackForReplacement()
        val queueId = db.downloadQueueDao().getByTrackId(trackId)!!.id
        coEvery { trackDownloader.downloadTrack(any(), any()) } coAnswers {
            db.downloadQueueDao().markCancelledIfActive(queueId, System.currentTimeMillis())
            throw CancellationException("cancelled")
        }

        buildWorker().doWork()

        val row = db.trackDao().getById(trackId)!!
        assertEquals(oldFile.absolutePath, row.filePath)
        assertTrue("back in the Library on its kept file", row.isDownloaded)
    }

    @Test fun `a re-download stopped with the worker restores the kept file`() = runBlocking {
        val trackId = trackHandedBackForReplacement()
        coEvery { trackDownloader.downloadTrack(any(), any()) } throws CancellationException("stopped")

        try {
            buildWorker().doWork()
        } catch (expected: CancellationException) {
        }

        val row = db.trackDao().getById(trackId)!!
        assertEquals(oldFile.absolutePath, row.filePath)
        assertTrue(row.isDownloaded)
        assertTrue(oldFile.exists())
    }

    @Test fun `a failed first download restores nothing`() = runBlocking {
        val trackId = db.trackDao().insert(song())
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = trackId, userRequested = true))
        coEvery { trackDownloader.downloadTrack(any(), any()) } returns TrackDownloadOutcome.Failed("x")

        buildWorker().doWork()

        assertFalse(db.trackDao().getById(trackId)!!.isDownloaded)
    }

    @Test fun `a kept file whose row moved on is not restored`() = runBlocking {
        val trackId = trackHandedBackForReplacement()
        val replacement = TrackFileReplacement(db.trackDao(), LocalFileOps(context))

        replacement.restoreKeptFile(trackId, newFile.absolutePath) // not the path the row records
        assertFalse(db.trackDao().getById(trackId)!!.isDownloaded)

        oldFile.delete()
        replacement.restoreKeptFile(trackId, oldFile.absolutePath) // the file is gone
        assertFalse(db.trackDao().getById(trackId)!!.isDownloaded)
    }

    /** A downloaded track the YouTube-library backfill handed back for a better copy. */
    private suspend fun trackHandedBackForReplacement(): Long {
        val trackId = db.trackDao().insert(song(filePath = oldFile.absolutePath, isDownloaded = true))
        db.trackDao().markForReplacement(trackId)
        db.downloadQueueDao().insert(DownloadQueueEntity(trackId = trackId, userRequested = true))
        return trackId
    }

    private fun song(filePath: String? = null, isDownloaded: Boolean = false) = TrackEntity(
        title = "Lacrymosa", artist = "Evanescence", durationMs = 1000L, source = MusicSource.YOUTUBE,
        canonicalTitle = "lacrymosa", canonicalArtist = "evanescence",
        filePath = filePath, isDownloaded = isDownloaded,
    )

    /** Real WorkerParameters, as in [DiscoveryDownloadWorkerAskedForTest]. */
    private fun buildWorker(): DiscoveryDownloadWorker = TestListenableWorkerBuilder<DiscoveryDownloadWorker>(context)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = DiscoveryDownloadWorker(
                appContext, workerParameters,
                downloadQueueDao = db.downloadQueueDao(),
                trackDao = db.trackDao(),
                trackDownloader = trackDownloader,
                audioDurationExtractor = audioDurationExtractor,
                blocklistGuard = mockk { coEvery { isBlocked(any(), any(), any(), any()) } returns false },
                syncNotificationManager = mockk(relaxed = true),
                downloadJobs = DownloadJobRegistry(),
                fileReplacement = TrackFileReplacement(db.trackDao(), LocalFileOps(appContext)),
                classifier = com.stash.core.data.sync.classifier.DownloadFailureClassifier(),
                queueState = mockk(relaxed = true),
                resumer = mockk(relaxed = true),
            )
        })
        .build()

    private companion object {
        const val AUDIO_BYTES = 20_000
    }
}
