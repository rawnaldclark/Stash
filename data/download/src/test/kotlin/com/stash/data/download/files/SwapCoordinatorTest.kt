package com.stash.data.download.files

import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.model.QualityTier
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.prefs.QualityPreferencesManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Regression tests for the wrong-match swap (#36, #531): a failed swap must
 * not destroy the user's existing file or silently vanish the flagged row, a
 * successful swap must commit the new audio before touching the old file, and
 * every swap must tell the screen how it went.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val downloadExecutor = mockk<DownloadExecutor>()
    private val fileOrganizer = mockk<FileOrganizer>()
    private val qualityPrefs = mockk<QualityPreferencesManager>()
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val blocklistGuard = mockk<BlocklistGuard>(relaxed = true)
    private val localFileOps = mockk<LocalFileOps>(relaxed = true)
    private val trackIdentityEvents = mockk<TrackIdentityEvents>(relaxed = true)
    private val audioExtractor = mockk<AudioDurationExtractor>()

    private lateinit var coordinator: SwapCoordinator

    @Before
    fun setUp() {
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        every { fileOrganizer.getTempDir() } returns tmp.newFolder("temp")
        every { localFileOps.acceptDownloadOrDelete(any()) } returns true
        every { audioExtractor.extract(any()) } returns null
        coEvery { trackDao.getById(7L) } returns TrackEntity(
            id = 7L,
            title = "Title",
            artist = "Artist",
            youtubeId = "wrong-video",
            durationMs = 200_000L,
        )
        coEvery { trackDao.updateYoutubeIdIfUnclaimed(7L, "vid123") } returns 1
        coEvery { trackDao.completeSwap(any(), any(), any(), any(), any(), any(), any()) } returns 1
        coordinator = SwapCoordinator(
            downloadExecutor = downloadExecutor,
            fileOrganizer = fileOrganizer,
            qualityPrefs = qualityPrefs,
            trackDao = trackDao,
            blocklistGuard = blocklistGuard,
            localFileOps = localFileOps,
            trackIdentityEvents = trackIdentityEvents,
            audioExtractor = audioExtractor,
        )
    }

    /** A download that lands [content] in a temp file, committed to [committedPath]. */
    private fun stubSuccessfulDownload(
        committedPath: String = File(tmp.root, "Artist/Album/Title.m4a").absolutePath,
        content: String = "replacement",
    ): File {
        val newTemp = tmp.newFile("swap_vid123.m4a").apply { writeText(content) }
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Success(newTemp)
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any())
        } returns FileOrganizer.CommittedTrack(committedPath, 123L)
        return newTemp
    }

    /** A commit that really overwrites [target], the way a same-path commit does. */
    private fun stubCommitOverwriting(target: File) {
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any())
        } answers {
            val temp = firstArg<File>()
            temp.copyTo(target, overwrite = true)
            temp.delete()
            FileOrganizer.CommittedTrack(target.absolutePath, target.length())
        }
    }

    private fun TestScope.collectOutcomes(): MutableList<SwapOutcome> {
        val outcomes = mutableListOf<SwapOutcome>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.outcomes.collect { outcomes += it }
        }
        return outcomes
    }

    private suspend fun swap(
        oldFilePath: String? = null,
        artist: String = "Artist",
        title: String = "Title",
        album: String? = "Album",
    ) = coordinator.performSwap(
        trackId = 7L,
        oldFilePath = oldFilePath,
        artist = artist,
        title = title,
        album = album,
        newVideoId = "vid123",
    )

    @Test
    fun `failed download keeps the old file on disk`() = runTest {
        val oldFile = tmp.newFile("old.m4a").apply { writeText("original audio") }
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.YtDlpError("boom")

        swap(oldFilePath = oldFile.absolutePath)

        assertTrue("old file must survive a failed swap", oldFile.exists())
        coVerify(exactly = 0) { trackDao.updateYoutubeIdIfUnclaimed(any(), any()) }
        coVerify(exactly = 0) { trackDao.updateYoutubeId(any(), any()) }
    }

    @Test
    fun `failed download re-flags the track so the row reappears`() = runTest {
        val oldFile = tmp.newFile("old2.m4a")
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Error("network down")

        swap(oldFilePath = oldFile.absolutePath)

        coVerify { trackDao.updateMatchFlagged(7L, true) }
    }

    @Test
    fun `successful swap commits new audio, updates the track, then deletes the old file`() = runTest {
        val oldFile = tmp.newFile("old3.m4a").apply { writeText("original") }
        val committedPath = File(tmp.root, "Artist/Album/Title.m4a").absolutePath
        stubSuccessfulDownload(committedPath = committedPath)

        swap(oldFilePath = oldFile.absolutePath)

        // The identity write is the guarded one: it can never take a video
        // another track owns (tracks.youtube_id is UNIQUE).
        coVerify { trackDao.updateYoutubeIdIfUnclaimed(7L, "vid123") }
        coVerify { trackDao.completeSwap(7L, committedPath, 123L, any(), any(), any(), any()) }
        assertFalse("old file should be deleted only after a successful swap", oldFile.exists())
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(7L, true) }
    }

    @Test
    fun `the replacement is filed under the track's own album`() = runTest {
        stubSuccessfulDownload()

        swap(artist = "Evanescence", title = "Lacrymosa", album = "Synthesis")

        // album = null filed every swap under <artist>/singles/<title>, so a
        // second "Lacrymosa" swap overwrote the first one's file.
        coVerify {
            fileOrganizer.commitDownload(any(), "Evanescence", "Synthesis", "Lacrymosa", any(), 7L)
        }
    }

    @Test
    fun `a video another track claimed during the download stops the swap without touching any file`() = runTest {
        val oldFile = tmp.newFile("old-claimed.m4a").apply { writeText("original audio") }
        val newTemp = stubSuccessfulDownload()
        stubCommitOverwriting(oldFile)
        coEvery { trackDao.updateYoutubeIdIfUnclaimed(7L, "vid123") } returns 0
        coEvery { trackDao.findByYoutubeId("vid123") } returns TrackEntity(
            id = 8L,
            title = "Lacrymosa",
            artist = "Evanescence",
            album = "The Open Door",
            youtubeId = "vid123",
        )
        val outcomes = collectOutcomes()

        swap(oldFilePath = oldFile.absolutePath, title = "Lacrymosa")

        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any()) }
        assertEquals("the user's audio must be untouched", "original audio", oldFile.readText())
        coVerify(exactly = 0) { trackDao.completeSwap(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any()) }
        coVerify { trackDao.updateMatchFlagged(7L, true) }
        assertFalse("the unused download must be cleaned up", newTemp.exists())
        assertEquals(
            listOf(
                SwapOutcome.AlreadyLinked(
                    trackId = 7L,
                    newVideoId = "vid123",
                    title = "Lacrymosa",
                    ownerArtist = "Evanescence",
                    ownerTitle = "Lacrymosa",
                    ownerAlbum = "The Open Door",
                ),
            ),
            outcomes,
        )
    }

    @Test
    fun `a junk download never touches the old file even when it would land on the same path`() = runTest {
        val oldFile = tmp.newFile("same-path.m4a").apply { writeText("original audio") }
        stubSuccessfulDownload(content = "x")
        stubCommitOverwriting(oldFile)
        every { localFileOps.acceptDownloadOrDelete(any()) } returns false

        swap(oldFilePath = oldFile.absolutePath)

        assertEquals("the user's audio must be untouched", "original audio", oldFile.readText())
        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.updateYoutubeIdIfUnclaimed(any(), any()) }
        coVerify { trackDao.updateMatchFlagged(7L, true) }
    }

    @Test
    fun `format and quality of the new file are written to the track`() = runTest {
        val committedPath = File(tmp.root, "Artist/Album/Title.opus").absolutePath
        stubSuccessfulDownload(committedPath = committedPath)
        every { audioExtractor.extract(any()) } returns AudioMetadata(
            durationMs = 201_000L,
            bitrateKbps = 160,
            format = "opus",
            bitsPerSample = null,
            sampleRateHz = 48_000,
        )

        swap()

        // Bit depth is written outright, null included: markAsDownloaded's
        // COALESCE kept a replaced FLAC's 24 bits ("OPUS · 24-bit/48.0 kHz").
        coVerify { trackDao.completeSwap(7L, committedPath, 123L, 48_000, null, any(), any()) }
        coVerify(exactly = 0) { trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any()) }
        // Without this a swap over a FLAC kept saying 'flac' on lossy audio.
        coVerify { trackDao.setFormatAndQuality(7L, "opus", 160) }
        // 201 s against the row's 200 s is within 10%: the row's length stays.
        coVerify(exactly = 0) { trackDao.setDuration(any(), any()) }
    }

    @Test
    fun `the new file's length replaces a row length that is more than 10 percent off`() = runTest {
        stubSuccessfulDownload()
        every { audioExtractor.extract(any()) } returns AudioMetadata(
            durationMs = 260_000L,
            bitrateKbps = 128,
            format = "aac",
        )

        swap()

        coVerify { trackDao.setDuration(7L, 260_000L) }
    }

    @Test
    fun `a save failure after the claim gives the video back so the same replacement can be retried`() = runTest {
        val oldFile = tmp.newFile("old-save-fail.m4a").apply { writeText("original audio") }
        stubSuccessfulDownload()
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any())
        } throws IOException("disk full")
        val outcomes = collectOutcomes()

        swap(oldFilePath = oldFile.absolutePath)

        coVerify { trackDao.restoreYoutubeIdIfClaimed(7L, "vid123", "wrong-video") }
        coVerify { trackDao.updateMatchFlagged(7L, true) }
        assertTrue(oldFile.exists())
        assertEquals(listOf(SwapOutcome.Failed(7L, "vid123", "Title")), outcomes)
    }

    @Test
    fun `a successful swap reports that it swapped`() = runTest {
        stubSuccessfulDownload()
        val outcomes = collectOutcomes()

        swap()

        assertEquals(listOf(SwapOutcome.Swapped(7L, "vid123", "Title")), outcomes)
    }

    @Test
    fun `a blocked replacement brings the row back and says why`() = runTest {
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), "vid123") } returns true
        val outcomes = collectOutcomes()

        swap()

        // It used to return silently with the flag already cleared, so the
        // row vanished and the track kept its wrong audio.
        coVerify { trackDao.updateMatchFlagged(7L, true) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        assertEquals(listOf(SwapOutcome.Blocked(7L, "vid123", "Title")), outcomes)
    }

    @Test
    fun `a failed download reports that it failed`() = runTest {
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.YtDlpError("Requested format is not available")
        val outcomes = collectOutcomes()

        swap()

        assertEquals(listOf(SwapOutcome.Failed(7L, "vid123", "Title")), outcomes)
    }
}
