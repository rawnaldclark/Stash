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
import io.mockk.spyk
import io.mockk.verify
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
 * successful swap must commit the new audio before touching the old file, no
 * swap may write over or delete another track's file, and every swap must
 * tell the screen how it went.
 *
 * File moves run for real ([LocalFileOps] is a spy over the real class; its
 * plain-path branch needs no Android), so "the old bytes survive" is checked
 * on disk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapCoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val downloadExecutor = mockk<DownloadExecutor>()
    private val fileOrganizer = mockk<FileOrganizer>()
    private val qualityPrefs = mockk<QualityPreferencesManager>()
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val blocklistGuard = mockk<BlocklistGuard>(relaxed = true)
    private val localFileOps = spyk(LocalFileOps(mockk(relaxed = true)))
    private val trackIdentityEvents = mockk<TrackIdentityEvents>(relaxed = true)
    private val audioExtractor = mockk<AudioDurationExtractor>()

    private lateinit var coordinator: SwapCoordinator

    @Before
    fun setUp() {
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        every { fileOrganizer.getTempDir() } returns tmp.newFolder("temp")
        // Nothing at the destination yet, unless a test says otherwise.
        coEvery { fileOrganizer.plannedPath(any(), any(), any(), any(), any(), any()) } returns null
        every { localFileOps.acceptDownloadOrDelete(any()) } returns true
        every { audioExtractor.extract(any()) } returns null
        givenRow()
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

    /** The flagged track as the coordinator reads it. */
    private fun givenRow(
        filePath: String? = null,
        artist: String = "Artist",
        title: String = "Title",
        album: String = "Album",
    ) {
        coEvery { trackDao.getById(7L) } returns row(filePath, artist, title, album)
    }

    private fun row(
        filePath: String?,
        artist: String = "Artist",
        title: String = "Title",
        album: String = "Album",
    ) = TrackEntity(
        id = 7L,
        title = title,
        artist = artist,
        album = album,
        filePath = filePath,
        youtubeId = "wrong-video",
        durationMs = 200_000L,
        isDownloaded = filePath != null,
        matchFlagged = true,
    )

    /** A download that lands [content] in a temp file, committed to [committedPath]. */
    private fun stubSuccessfulDownload(
        committedPath: String = File(tmp.root, "Artist/Album/Title.m4a").absolutePath,
        content: String = "replacement",
    ): File {
        val newTemp = tmp.newFile("swap_7_vid123.m4a").apply { writeText(content) }
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Success(newTemp)
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any())
        } returns FileOrganizer.CommittedTrack(committedPath, 123L)
        return newTemp
    }

    /** A commit that really overwrites [target], the way a same-path commit does. */
    private fun stubCommitOverwriting(target: File) {
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any())
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

    private suspend fun swap() = coordinator.performSwap(trackId = 7L, newVideoId = "vid123")

    @Test
    fun `failed download keeps the old file on disk`() = runTest {
        val oldFile = tmp.newFile("old.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.YtDlpError("boom")

        swap()

        assertTrue("old file must survive a failed swap", oldFile.exists())
        coVerify(exactly = 0) { trackDao.updateYoutubeIdIfUnclaimed(any(), any()) }
        coVerify(exactly = 0) { trackDao.updateYoutubeId(any(), any()) }
    }

    @Test
    fun `a failed download leaves the flag alone, so the row stays where it was`() = runTest {
        givenRow(filePath = tmp.newFile("old2.m4a").absolutePath)
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Error("network down")

        swap()

        // The flag is cleared only by the write that records a finished swap
        // (completeSwap), so a failure has nothing to put back (#36, #531).
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(any(), any()) }
    }

    @Test
    fun `successful swap commits new audio, updates the track, then deletes the old file`() = runTest {
        val oldFile = tmp.newFile("old3.m4a").apply { writeText("original") }
        givenRow(filePath = oldFile.absolutePath)
        val committedPath = File(tmp.root, "Artist/Album/Title.m4a").absolutePath
        stubSuccessfulDownload(committedPath = committedPath)

        swap()

        // The identity write is the guarded one: it can never take a video
        // another track owns (tracks.youtube_id is UNIQUE).
        coVerify { trackDao.updateYoutubeIdIfUnclaimed(7L, "vid123") }
        coVerify { trackDao.completeSwap(7L, committedPath, 123L, any(), any(), any(), any()) }
        assertFalse("old file should be deleted only after a successful swap", oldFile.exists())
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(7L, true) }
    }

    @Test
    fun `the replacement is filed under the track's own album`() = runTest {
        givenRow(artist = "Evanescence", title = "Lacrymosa", album = "Synthesis")
        stubSuccessfulDownload()

        swap()

        // album = null filed every swap under <artist>/singles/<title>, so a
        // second "Lacrymosa" swap overwrote the first one's file.
        coVerify {
            fileOrganizer.commitDownload(any(), "Evanescence", "Synthesis", "Lacrymosa", any(), 7L, any())
        }
    }

    @Test
    fun `a video another track claimed during the download stops the swap without touching any file`() = runTest {
        val oldFile = tmp.newFile("old-claimed.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath, title = "Lacrymosa")
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

        swap()

        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("the user's audio must be untouched", "original audio", oldFile.readText())
        coVerify(exactly = 0) { trackDao.completeSwap(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(any(), any()) }
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
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload(content = "x")
        stubCommitOverwriting(oldFile)
        coEvery { fileOrganizer.plannedPath(any(), any(), any(), any(), any(), any()) } returns oldFile.absolutePath
        every { localFileOps.acceptDownloadOrDelete(any()) } returns false

        swap()

        assertEquals("the user's audio must be untouched", "original audio", oldFile.readText())
        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.updateYoutubeIdIfUnclaimed(any(), any()) }
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(any(), any()) }
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
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any())
        } throws IOException("disk full")
        val outcomes = collectOutcomes()

        swap()

        coVerify { trackDao.restoreYoutubeIdIfClaimed(7L, "vid123", "wrong-video") }
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(any(), any()) }
        assertEquals("original audio", oldFile.readText())
        // Not "download again": retrying only helps once storage is sorted out.
        assertEquals(listOf(SwapOutcome.SaveFailed(7L, "vid123", "Title")), outcomes)
    }

    // -- A save onto the old file's own path (album folders make it the norm) --

    @Test
    fun `a failed save onto the old file's own path leaves the old song intact`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        coEvery { fileOrganizer.plannedPath(any(), any(), any(), any(), any(), any()) } returns oldFile.absolutePath
        // Behaves like File.copyTo(overwrite = true): deletes the target,
        // starts writing, then fails part-way.
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any())
        } answers {
            oldFile.delete()
            oldFile.writeText("half a repl")
            throw IOException("No space left on device")
        }
        val outcomes = collectOutcomes()

        swap()

        assertEquals("original audio", oldFile.readText())
        assertFalse("no backup is left behind", File(oldFile.path + ".swapbak").exists())
        coVerify { trackDao.restoreYoutubeIdIfClaimed(7L, "vid123", "wrong-video") }
        coVerify(exactly = 0) { trackDao.completeSwap(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(listOf(SwapOutcome.SaveFailed(7L, "vid123", "Title")), outcomes)
    }

    @Test
    fun `a failed record after saving onto the old file's own path puts the old song back`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        coEvery { fileOrganizer.plannedPath(any(), any(), any(), any(), any(), any()) } returns oldFile.absolutePath
        stubCommitOverwriting(oldFile)
        coEvery {
            trackDao.completeSwap(any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("database is locked")

        swap()

        assertEquals("the row still points here, so its old audio must be here", "original audio", oldFile.readText())
        assertFalse(File(oldFile.path + ".swapbak").exists())
        coVerify { trackDao.restoreYoutubeIdIfClaimed(7L, "vid123", "wrong-video") }
    }

    @Test
    fun `a successful save onto the old file's own path leaves just the new song`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload(content = "replacement")
        coEvery { fileOrganizer.plannedPath(any(), any(), any(), any(), any(), any()) } returns oldFile.absolutePath
        stubCommitOverwriting(oldFile)
        val outcomes = collectOutcomes()

        swap()

        assertEquals("replacement", oldFile.readText())
        assertFalse("the backup goes once the swap is recorded", File(oldFile.path + ".swapbak").exists())
        assertEquals(listOf(SwapOutcome.Swapped(7L, "vid123", "Title")), outcomes)
    }

    // -- Files other tracks use (Single folder / Per playlist / same album) -----

    @Test
    fun `the old file stays when another track uses it`() = runTest {
        val shared = tmp.newFile("evanescence-lacrymosa.m4a").apply { writeText("the other recording") }
        givenRow(filePath = shared.absolutePath)
        coEvery { trackDao.countOtherTracksWithFilePath(shared.absolutePath, 7L) } returns 1
        stubSuccessfulDownload(committedPath = File(tmp.root, "evanescence-lacrymosa-synthesis.m4a").absolutePath)

        swap()

        assertEquals("the other track still plays from it", "the other recording", shared.readText())
    }

    @Test
    fun `a swap never writes over a file another track uses`() = runTest {
        val siblingsPath = File(tmp.root, "evanescence-lacrymosa.m4a").absolutePath
        val distinctPath = File(tmp.root, "evanescence-lacrymosa-album.m4a").absolutePath
        stubSuccessfulDownload(committedPath = distinctPath)
        coEvery { fileOrganizer.plannedPath(any(), any(), any(), any(), any(), isNull()) } returns siblingsPath
        coEvery { fileOrganizer.plannedPath(any(), any(), any(), any(), any(), "Album") } returns distinctPath
        coEvery { trackDao.countOtherTracksWithFilePath(siblingsPath, 7L) } returns 1

        swap()

        coVerify { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), 7L, "Album") }
        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), isNull()) }
    }

    // -- SAF libraries -----------------------------------------------------------

    @Test
    fun `the old SAF document is deleted through the SAF-aware helper`() = runTest {
        val old = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/" +
            "primary%3AMusic%2Fartist%2Fsingles%2Ftitle.flac"
        val new = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/" +
            "primary%3AMusic%2Fartist%2Falbum%2Ftitle.m4a"
        givenRow(filePath = old)
        stubSuccessfulDownload(committedPath = new)

        swap()

        // File(old).delete() did nothing for a content:// path, and library
        // reconciliation could later repoint the row to the leftover.
        verify { localFileOps.delete(old) }
    }

    @Test
    fun `the file just written is never deleted, even under another SAF URI`() = runTest {
        val old = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/" +
            "primary%3AMusic%2Fartist%2Falbum%2Ftitle.m4a"
        val sameDocument = "content://com.android.externalstorage.documents/document/" +
            "primary%3AMusic%2Fartist%2Falbum%2Ftitle.m4a"
        givenRow(filePath = old)
        stubSuccessfulDownload(committedPath = sameDocument)

        swap()

        verify(exactly = 0) { localFileOps.delete(old) }
        verify(exactly = 0) { localFileOps.delete(sameDocument) }
    }

    @Test
    fun `the swap replaces the file the track points at when it saves, not when it was approved`() = runTest {
        val atApproval = tmp.newFile("before-reorganize.m4a").apply { writeText("moved away") }
        val atSave = tmp.newFile("after-reorganize.m4a").apply { writeText("wrong song") }
        coEvery { trackDao.getById(7L) } returnsMany listOf(
            row(filePath = atApproval.absolutePath),
            row(filePath = atSave.absolutePath),
        )
        stubSuccessfulDownload()

        swap()

        assertFalse("the file the row pointed at when saving is the one replaced", atSave.exists())
        assertTrue(atApproval.exists())
    }

    // -- Outcomes ------------------------------------------------------------------

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

        // It used to return silently after the screen had already cleared the
        // flag, so the row vanished and the track kept its wrong audio. Now
        // nothing cleared the flag, and the outcome says why.
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(any(), any()) }
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

        assertEquals(listOf(SwapOutcome.DownloadFailed(7L, "vid123", "Title")), outcomes)
    }

    @Test
    fun `a stray CancellationException from the save is still reported and undone`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        coEvery { fileOrganizer.plannedPath(any(), any(), any(), any(), any(), any()) } returns oldFile.absolutePath
        // A callee's timeout, not this swap being cancelled.
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any())
        } throws kotlinx.coroutines.CancellationException("timed out")
        val outcomes = collectOutcomes()

        swap()

        assertEquals("original audio", oldFile.readText())
        coVerify { trackDao.restoreYoutubeIdIfClaimed(7L, "vid123", "wrong-video") }
        assertEquals(listOf(SwapOutcome.SaveFailed(7L, "vid123", "Title")), outcomes)
    }
}
