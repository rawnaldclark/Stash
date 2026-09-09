package com.stash.data.download.files

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.model.QualityTier
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.prefs.QualityPreferencesManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Regression tests for the wrong-match swap (#36): a failed swap must not
 * destroy the user's existing file or silently vanish the flagged row, and
 * a successful swap must commit the new audio before touching the old file.
 */
class SwapCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val downloadExecutor = mockk<DownloadExecutor>()
    private val fileOrganizer = mockk<FileOrganizer>()
    private val qualityPrefs = mockk<QualityPreferencesManager>()
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val blocklistGuard = mockk<BlocklistGuard>(relaxed = true)
    private val trackIdentityEvents = mockk<TrackIdentityEvents>(relaxed = true)

    private lateinit var coordinator: SwapCoordinator

    @Before
    fun setUp() {
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        every { fileOrganizer.getTempDir() } returns tmp.newFolder("temp")
        coordinator = SwapCoordinator(
            downloadExecutor = downloadExecutor,
            fileOrganizer = fileOrganizer,
            qualityPrefs = qualityPrefs,
            trackDao = trackDao,
            blocklistGuard = blocklistGuard,
            localFileOps = mockk(relaxed = true) { every { acceptDownloadOrDelete(any()) } returns true },
            trackIdentityEvents = trackIdentityEvents,
        )
    }

    @Test
    fun `failed download keeps the old file on disk`() = runTest {
        val oldFile = tmp.newFile("old.m4a").apply { writeText("original audio") }
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.YtDlpError("boom")

        coordinator.performSwap(
            trackId = 7L,
            oldFilePath = oldFile.absolutePath,
            artist = "Artist",
            title = "Title",
            newVideoId = "vid123",
        )

        assertTrue("old file must survive a failed swap", oldFile.exists())
        coVerify(exactly = 0) { trackDao.updateYoutubeId(any(), any()) }
    }

    @Test
    fun `failed download re-flags the track so the row reappears`() = runTest {
        val oldFile = tmp.newFile("old2.m4a")
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Error("network down")

        coordinator.performSwap(
            trackId = 7L,
            oldFilePath = oldFile.absolutePath,
            artist = "Artist",
            title = "Title",
            newVideoId = "vid123",
        )

        coVerify { trackDao.updateMatchFlagged(7L, true) }
    }

    @Test
    fun `successful swap commits new audio, updates the track, then deletes the old file`() = runTest {
        val oldFile = tmp.newFile("old3.m4a").apply { writeText("original") }
        val newTemp = tmp.newFile("swap_vid123.m4a").apply { writeText("replacement") }
        val committedPath = File(tmp.root, "Artist/Title.m4a").absolutePath
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Success(newTemp)
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any())
        } returns FileOrganizer.CommittedTrack(committedPath, 123L)

        coordinator.performSwap(
            trackId = 7L,
            oldFilePath = oldFile.absolutePath,
            artist = "Artist",
            title = "Title",
            newVideoId = "vid123",
        )

        coVerify { trackDao.updateYoutubeId(7L, "vid123") }
        coVerify { trackDao.markAsDownloaded(7L, committedPath, 123L, any(), any(), any()) }
        assertFalse("old file should be deleted only after a successful swap", oldFile.exists())
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(7L, true) }
    }
}
