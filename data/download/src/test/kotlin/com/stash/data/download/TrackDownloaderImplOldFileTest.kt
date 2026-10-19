package com.stash.data.download

import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.files.TrackFileReplacement
import com.stash.core.data.sync.TrackDownloadOutcome
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The downloader never deletes a re-download's old file: the row still
 * records it until the worker's markAsDownloaded, so deleting it here (889c24ae)
 * lost both files when the worker was cancelled or the process died before
 * that write. The worker deletes it after the write (TrackFileReplacement).
 * A deferred re-download of a track whose file was kept for it
 * (TrackDao.markForReplacement) gets that file back as its download.
 */
class TrackDownloaderImplOldFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private val downloadManager: DownloadManager = mockk()
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)

    // Real LocalFileOps over a mock Context: plain paths never touch the Context.
    private fun newSubject(): TrackDownloaderImpl {
        val localFileOps = LocalFileOps(mockk(relaxed = true))
        return TrackDownloaderImpl(
            downloadManager = downloadManager,
            downloadQueueDao = downloadQueueDao,
            localFileOps = localFileOps,
            fileReplacement = TrackFileReplacement(trackDao, localFileOps),
        )
    }

    private fun downloads(bytes: Int): String {
        val new = tmp.newFile("lacrymosa.flac").apply { writeBytes(ByteArray(bytes)) }
        coEvery { downloadManager.downloadTrack(any(), any()) } returns TrackDownloadResult.Success(new.absolutePath)
        return new.absolutePath
    }

    @Test
    fun `a validated re-download leaves the old file for the worker`() = runTest {
        val old = tmp.newFile("lacrymosa-old.opus")
        downloads(REAL_SIZE)
        coEvery { trackDao.countOtherTracksWithFilePath(old.absolutePath, 7L) } returns 0

        val outcome = newSubject().downloadTrack(track(old.absolutePath))

        assertTrue("got $outcome", outcome is TrackDownloadOutcome.Success)
        assertTrue("the row still records it until the worker's write", old.exists())
    }

    @Test
    fun `a junk re-download keeps the old file`() = runTest {
        val old = tmp.newFile("lacrymosa-old.opus")
        downloads(274)

        val outcome = newSubject().downloadTrack(track(old.absolutePath))

        assertTrue("got $outcome", outcome is TrackDownloadOutcome.Failed)
        assertTrue(old.exists())
        coVerify(exactly = 0) { trackDao.restoreKeptFile(any(), any()) }
    }

    @Test
    fun `a re-download at the same path keeps the new file`() = runTest {
        val path = downloads(REAL_SIZE)

        val outcome = newSubject().downloadTrack(track(path))

        assertTrue("got $outcome", outcome is TrackDownloadOutcome.Success)
        assertTrue(java.io.File(path).exists())
    }

    @Test
    fun `a deferred re-download restores the kept file as the track's download`() = runTest {
        val kept = tmp.newFile("lacrymosa-old.opus").apply { writeBytes(ByteArray(REAL_SIZE)) }
        coEvery { downloadManager.downloadTrack(any(), any()) } returns TrackDownloadResult.Deferred()

        val outcome = newSubject().downloadTrack(track(kept.absolutePath))

        assertTrue("got $outcome", outcome is TrackDownloadOutcome.Deferred)
        assertTrue(kept.exists())
        coVerify(exactly = 1) { trackDao.restoreKeptFile(7L, kept.absolutePath) }
    }

    @Test
    fun `a deferred download restores nothing when the kept file is gone`() = runTest {
        val gone = tmp.newFile("lacrymosa-old.opus").apply { delete() }
        coEvery { downloadManager.downloadTrack(any(), any()) } returns TrackDownloadResult.Deferred()

        newSubject().downloadTrack(track(gone.absolutePath))
        newSubject().downloadTrack(track(oldPath = null))

        assertFalse(gone.exists())
        coVerify(exactly = 0) { trackDao.restoreKeptFile(any(), any()) }
    }

    private fun track(oldPath: String?) = Track(
        id = 7L,
        title = "Lacrymosa",
        artist = "Evanescence",
        album = "Synthesis",
        filePath = oldPath,
    )

    private companion object {
        const val REAL_SIZE = 20_000 // above the 16 KiB floor
    }
}
