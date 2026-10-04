package com.stash.data.download.files

import android.content.Context
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * not destroy the user's existing file or leave the track half-changed, a
 * successful swap must commit the new audio before touching the old file, no
 * swap may write over or delete another track's file, and every swap must
 * tell the screen how it went.
 *
 * File moves run for real ([LocalFileOps] is a spy over the real class; its
 * plain-path branch needs no Android), so "the old bytes survive" is checked
 * on disk. The journal of set-aside files is real too.
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
    private val metadataEmbedder = mockk<MetadataEmbedder>(relaxed = true)
    private val albumArtCache = mockk<AlbumArtCache>(relaxed = true)
    private lateinit var journal: SwapJournal

    private lateinit var coordinator: SwapCoordinator

    /** plannedPaths' name-suffix list (lastArg() of a suspend fun is its continuation). */
    private val SUFFIXES = 5

    @Before
    fun setUp() {
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        every { fileOrganizer.getTempDir() } returns tmp.newFolder("temp")
        destinationIs(null) // nothing at the destination yet, unless a test says otherwise
        every { localFileOps.acceptDownloadOrDelete(any()) } returns true
        every { audioExtractor.extract(any()) } returns null
        givenRow()
        // No other track has the video (a relaxed mock would return a dummy row).
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery { trackDao.completeSwap(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val context = mockk<Context> { every { noBackupFilesDir } returns tmp.newFolder("no-backup") }
        journal = SwapJournal(context)
        coordinator = SwapCoordinator(
            downloadExecutor = downloadExecutor,
            fileOrganizer = fileOrganizer,
            qualityPrefs = qualityPrefs,
            trackDao = trackDao,
            blocklistGuard = blocklistGuard,
            localFileOps = localFileOps,
            trackIdentityEvents = trackIdentityEvents,
            audioExtractor = audioExtractor,
            metadataEmbedder = metadataEmbedder,
            albumArtCache = albumArtCache,
            journal = journal,
        )
    }

    /** Where the save would land, for every candidate name. */
    private fun destinationIs(path: String?) {
        coEvery { fileOrganizer.plannedPaths(any(), any(), any(), any(), any(), any()) } answers {
            List(arg<List<String?>>(SUFFIXES).size) { path }
        }
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
        flagged: Boolean = true,
    ) = TrackEntity(
        id = 7L,
        title = title,
        artist = artist,
        album = album,
        filePath = filePath,
        youtubeId = "wrong-video",
        durationMs = 200_000L,
        isDownloaded = filePath != null,
        matchFlagged = flagged,
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

    private suspend fun swap() = coordinator.performSwap(trackId = 7L, newVideoId = "vid12345678")

    private fun neverWroteTheVideo() {
        // The video id is written only by the final write (completeSwap).
        coVerify(exactly = 0) { trackDao.updateYoutubeIdIfUnclaimed(any(), any()) }
        coVerify(exactly = 0) { trackDao.updateYoutubeId(any(), any()) }
        coVerify(exactly = 0) {
            trackDao.completeSwap(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `failed download keeps the old file on disk`() = runTest {
        val oldFile = tmp.newFile("old.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.YtDlpError("boom")

        swap()

        assertTrue("old file must survive a failed swap", oldFile.exists())
        neverWroteTheVideo()
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

        // One guarded write records the new video with the new file: it can
        // never take a video another track owns (tracks.youtube_id is UNIQUE).
        coVerify {
            trackDao.completeSwap(
                trackId = 7L,
                youtubeId = "vid12345678",
                filePath = committedPath,
                fileSizeBytes = 123L,
                fileFormat = any(),
                qualityKbps = any(),
                sampleRateHz = any(),
                bitsPerSample = any(),
                durationMs = any(),
                metadataEmbeddedAt = any(),
                pickedAt = any(),
                downloadedAt = any(),
            )
        }
        coVerify(exactly = 0) { trackDao.updateYoutubeIdIfUnclaimed(any(), any()) }
        assertFalse("old file should be deleted only after a successful swap", oldFile.exists())
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(any(), any()) }
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
    fun `the replacement gets Stash's tags before it is saved`() = runTest {
        val newTemp = stubSuccessfulDownload()

        swap()

        // yt-dlp's tags are YouTube's; a sync download embeds Stash's own.
        coVerify { metadataEmbedder.embedMetadata(newTemp, any(), any()) }
        coVerify {
            trackDao.completeSwap(
                trackId = 7L, youtubeId = any(), filePath = any(), fileSizeBytes = any(),
                fileFormat = any(), qualityKbps = any(), sampleRateHz = any(), bitsPerSample = any(),
                durationMs = any(), metadataEmbeddedAt = match { it != null }, pickedAt = any(), downloadedAt = any(),
            )
        }
    }

    @Test
    fun `a video another track took during the download stops the swap before touching any file`() = runTest {
        val oldFile = tmp.newFile("old-claimed.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath, title = "Lacrymosa")
        val newTemp = stubSuccessfulDownload()
        stubCommitOverwriting(oldFile)
        coEvery { trackDao.findByYoutubeId("vid12345678") } returns TrackEntity(
            id = 8L,
            title = "Lacrymosa",
            artist = "Evanescence",
            album = "The Open Door",
            youtubeId = "vid12345678",
        )
        val outcomes = collectOutcomes()

        swap()

        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("the user's audio must be untouched", "original audio", oldFile.readText())
        neverWroteTheVideo()
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(any(), any()) }
        assertFalse("the unused download must be cleaned up", newTemp.exists())
        assertEquals(
            listOf(
                SwapOutcome.AlreadyLinked(
                    trackId = 7L,
                    newVideoId = "vid12345678",
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
    fun `a video another track takes during the save is caught by the final write and undone`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath, title = "Lacrymosa")
        stubSuccessfulDownload()
        destinationIs(oldFile.absolutePath)
        stubCommitOverwriting(oldFile)
        val owner = TrackEntity(id = 8L, title = "Lacrymosa", artist = "Evanescence", album = "The Open Door", youtubeId = "vid12345678")
        coEvery { trackDao.findByYoutubeId("vid12345678") } returnsMany listOf(null, owner)
        // The guarded write finds the video taken: nothing is written.
        coEvery {
            trackDao.completeSwap(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns 0
        val outcomes = collectOutcomes()

        swap()

        assertEquals("original audio", oldFile.readText())
        assertEquals(1, outcomes.size)
        assertTrue("got ${outcomes.single()}", outcomes.single() is SwapOutcome.AlreadyLinked)
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `an error before the save leaves the track's video untouched`() = runTest {
        val oldFile = tmp.newFile("old-error.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        coEvery { trackDao.countOtherTracksWithFilePath(any(), any()) } throws IllegalStateException("database is locked")
        val outcomes = collectOutcomes()

        swap()

        // Writing the video first left the row on the new video with its old
        // file: resync then excluded the right video as "the wrong one".
        neverWroteTheVideo()
        assertEquals("original audio", oldFile.readText())
        assertEquals(listOf(SwapOutcome.SaveFailed(7L, "vid12345678", "Title")), outcomes)
    }

    @Test
    fun `a junk download never touches the old file even when it would land on the same path`() = runTest {
        val oldFile = tmp.newFile("same-path.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload(content = "x")
        stubCommitOverwriting(oldFile)
        destinationIs(oldFile.absolutePath)
        every { localFileOps.acceptDownloadOrDelete(any()) } returns false

        swap()

        assertEquals("the user's audio must be untouched", "original audio", oldFile.readText())
        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any()) }
        neverWroteTheVideo()
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(any(), any()) }
    }

    @Test
    fun `format, quality and length go in the same write as the new file`() = runTest {
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

        // Bit depth written outright, null included: markAsDownloaded's
        // COALESCE kept a replaced FLAC's 24 bits ("OPUS · 24-bit/48.0 kHz").
        // Format and bitrate in the same write: a crash can't leave 'flac' on
        // opus audio. 201 s against the row's 200 s is within 10%, so the
        // row's length stays (null = keep).
        coVerify {
            trackDao.completeSwap(
                trackId = 7L,
                youtubeId = "vid12345678",
                filePath = committedPath,
                fileSizeBytes = 123L,
                fileFormat = "opus",
                qualityKbps = 160,
                sampleRateHz = 48_000,
                bitsPerSample = null,
                durationMs = null,
                metadataEmbeddedAt = any(),
                pickedAt = any(),
                downloadedAt = any(),
            )
        }
        coVerify(exactly = 0) { trackDao.markAsDownloaded(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.setFormatAndQuality(any(), any(), any()) }
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

        coVerify {
            trackDao.completeSwap(
                trackId = 7L, youtubeId = any(), filePath = any(), fileSizeBytes = any(),
                fileFormat = "aac", qualityKbps = 128, sampleRateHz = any(), bitsPerSample = any(),
                durationMs = 260_000L, metadataEmbeddedAt = any(), pickedAt = any(), downloadedAt = any(),
            )
        }
    }

    @Test
    fun `a save failure leaves the track's video as it was, so the same replacement can be retried`() = runTest {
        val oldFile = tmp.newFile("old-save-fail.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any())
        } throws IOException("disk full")
        val outcomes = collectOutcomes()

        swap()

        neverWroteTheVideo()
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(any(), any()) }
        assertEquals("original audio", oldFile.readText())
        // Not "download again": retrying only helps once storage is sorted out.
        assertEquals(listOf(SwapOutcome.SaveFailed(7L, "vid12345678", "Title")), outcomes)
    }

    // -- A save onto the old file's own path (album folders make it the norm) --

    @Test
    fun `a failed save onto the old file's own path leaves the old song intact`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        destinationIs(oldFile.absolutePath)
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
        assertTrue("no backup is left behind", oldFile.parentFile!!.listFiles()!!.none { it.name.endsWith(".swapbak") })
        assertTrue(journal.pending().isEmpty())
        neverWroteTheVideo()
        assertEquals(listOf(SwapOutcome.SaveFailed(7L, "vid12345678", "Title")), outcomes)
    }

    @Test
    fun `a failed record after saving onto the old file's own path puts the old song back`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        destinationIs(oldFile.absolutePath)
        stubCommitOverwriting(oldFile)
        coEvery {
            trackDao.completeSwap(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("database is locked")

        swap()

        assertEquals("the row still points here, so its old audio must be here", "original audio", oldFile.readText())
        assertTrue(oldFile.parentFile!!.listFiles()!!.none { it.name.endsWith(".swapbak") })
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `a successful save onto the old file's own path leaves just the new song`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload(content = "replacement")
        destinationIs(oldFile.absolutePath)
        stubCommitOverwriting(oldFile)
        val outcomes = collectOutcomes()

        swap()

        assertEquals("replacement", oldFile.readText())
        assertTrue("the backup goes once the swap is recorded", oldFile.parentFile!!.listFiles()!!.none { it.name.endsWith(".swapbak") })
        assertTrue(journal.pending().isEmpty())
        assertEquals(listOf(SwapOutcome.Swapped(7L, "vid12345678", "Title")), outcomes)
    }

    // -- A swap cut short by the app being killed --------------------------------

    @Test
    fun `a swap cut short after setting the old file aside is put back at the next start`() = runTest {
        val song = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        val backup = localFileOps.setAside(song.absolutePath)!!
        journal.record(SwapJournal.Entry(7L, song.absolutePath, backup, newVideoId = "vid12345678"))
        song.writeText("unrecorded replacement") // the save ran; the app died before the record
        coEvery { trackDao.getById(7L) } returns row(filePath = song.absolutePath, flagged = true)

        coordinator.recoverInterruptedSwaps()

        assertEquals("original audio", song.readText())
        assertFalse(File(backup).exists())
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `a set-aside copy left after a finished swap is removed at the next start`() = runTest {
        val song = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        val backup = localFileOps.setAside(song.absolutePath)!!
        journal.record(SwapJournal.Entry(7L, song.absolutePath, backup, newVideoId = "vid12345678"))
        song.writeText("recorded replacement")
        // completeSwap wrote the new video with the new file: the swap finished,
        // only the cleanup didn't.
        coEvery { trackDao.getById(7L) } returns row(filePath = song.absolutePath, flagged = false).copy(youtubeId = "vid12345678")

        coordinator.recoverInterruptedSwaps()

        assertEquals("recorded replacement", song.readText())
        assertFalse(File(backup).exists())
        assertTrue(journal.pending().isEmpty())
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
        coEvery { fileOrganizer.plannedPaths(any(), any(), any(), any(), any(), any()) } answers {
            arg<List<String?>>(SUFFIXES).map { suffix -> if (suffix == null) siblingsPath else distinctPath }
        }
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
    fun `on SAF a destination the folder listing found counts as there, so the old file is set aside`() = runTest {
        val old = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/" +
            "primary%3AMusic%2Fartist%2Falbum%2Ftitle.m4a"
        givenRow(filePath = old)
        stubSuccessfulDownload(committedPath = old)
        destinationIs(old)

        swap()

        // SAF exists() can wrongly say no; a false negative skipped the
        // set-aside and let the save delete the old document first.
        verify { localFileOps.setAside(old) }
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

        assertEquals(listOf(SwapOutcome.Swapped(7L, "vid12345678", "Title")), outcomes)
    }

    @Test
    fun `a blocked replacement brings the row back and says why`() = runTest {
        coEvery { blocklistGuard.isBlocked(any(), any(), any(), "vid12345678") } returns true
        val outcomes = collectOutcomes()

        swap()

        // It used to return silently after the screen had already cleared the
        // flag, so the row vanished and the track kept its wrong audio. Now
        // nothing cleared the flag, and the outcome says why.
        coVerify(exactly = 0) { trackDao.updateMatchFlagged(any(), any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        assertEquals(listOf(SwapOutcome.Blocked(7L, "vid12345678", "Title")), outcomes)
    }

    @Test
    fun `a failed download reports that it failed`() = runTest {
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.YtDlpError("Requested format is not available")
        val outcomes = collectOutcomes()

        swap()

        assertEquals(listOf(SwapOutcome.DownloadFailed(7L, "vid12345678", "Title")), outcomes)
    }

    @Test
    fun `a stray CancellationException from the save is still reported and undone`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        destinationIs(oldFile.absolutePath)
        // A callee's timeout, not this swap being cancelled.
        coEvery {
            fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any())
        } throws kotlinx.coroutines.CancellationException("timed out")
        val outcomes = collectOutcomes()

        swap()

        assertEquals("original audio", oldFile.readText())
        neverWroteTheVideo()
        assertEquals(listOf(SwapOutcome.SaveFailed(7L, "vid12345678", "Title")), outcomes)
    }

    // -- One swap per track (#531 review) ------------------------------------------

    @Test
    fun `a second swap of a track whose swap is running is refused`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } coAnswers {
            gate.await()
            DownloadResult.Error("stopped")
        }

        assertTrue(coordinator.swap(7L, "vid12345678"))
        // Approve, leave, come back and approve again: the screen's own guard
        // is gone with the old screen, so the coordinator must refuse. Two
        // swaps would interleave set-aside, save and undo.
        assertEquals(setOf(7L), coordinator.running.value)
        assertFalse(coordinator.swap(7L, "vid45678901"))

        gate.complete(Unit)
        coordinator.running.first { it.isEmpty() }
        coVerify(exactly = 1) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    // -- #531 review: recovery decides by the video, and only restores where safe --

    /** The old file set aside and written down, as a swap of [videoId] leaves it. */
    private fun setAsideFor(song: File, videoId: String? = "vid12345678"): String {
        val backup = localFileOps.setAside(song.absolutePath)!!
        journal.record(SwapJournal.Entry(7L, song.absolutePath, backup, newVideoId = videoId))
        return backup
    }

    @Test
    fun `a swap the user unflagged before the app was killed is put back at the next start`() = runTest {
        val song = tmp.newFile("lacrymosa.m4a").apply { writeText("the audio the user kept") }
        val backup = setAsideFor(song)
        song.writeText("unrecorded replacement")
        // Unflagged mid-swap, then killed before completeSwap: still the old video.
        coEvery { trackDao.getById(7L) } returns row(filePath = song.absolutePath, flagged = false)

        coordinator.recoverInterruptedSwaps()

        // The flag said "finished" and the backup was deleted: the very audio
        // the user chose to keep.
        assertEquals("the audio the user kept", song.readText())
        assertFalse(File(backup).exists())
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `a finished swap is told apart by its video, even when the row is flagged again`() = runTest {
        val song = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        val backup = setAsideFor(song)
        song.writeText("recorded replacement")
        coEvery { trackDao.getById(7L) } returns row(filePath = song.absolutePath, flagged = true).copy(youtubeId = "vid12345678")

        coordinator.recoverInterruptedSwaps()

        assertEquals("recorded replacement", song.readText())
        assertFalse(File(backup).exists())
    }

    @Test
    fun `an entry from the previous version falls back to the flag`() = runTest {
        val song = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        setAsideFor(song, videoId = null)
        song.writeText("unrecorded replacement")
        coEvery { trackDao.getById(7L) } returns row(filePath = song.absolutePath, flagged = true)

        coordinator.recoverInterruptedSwaps()

        assertEquals("original audio", song.readText())
    }

    @Test
    fun `a backup is kept when the track no longer points at its original file`() = runTest {
        val song = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        val backup = setAsideFor(song)
        // Reconciliation found the old path empty during the set-aside and reset the row.
        coEvery { trackDao.getById(7L) } returns row(filePath = null, flagged = true)

        coordinator.recoverInterruptedSwaps()

        assertFalse("nothing is written where no track expects it", song.exists())
        assertEquals("the audio is kept", "original audio", File(backup).readText())
        assertTrue(journal.pending().isEmpty())
    }

    @Test
    fun `a backup is kept when another track now uses its original path`() = runTest {
        val song = tmp.newFile("evanescence-lacrymosa.m4a").apply { writeText("original audio") }
        val backup = setAsideFor(song)
        song.writeText("the other recording's file")
        coEvery { trackDao.getById(7L) } returns row(filePath = song.absolutePath, flagged = true)
        coEvery { trackDao.countOtherTracksWithFilePath(song.absolutePath, 7L) } returns 1

        coordinator.recoverInterruptedSwaps()

        assertEquals("the other track's file is untouched", "the other recording's file", song.readText())
        assertEquals("original audio", File(backup).readText())
    }

    @Test
    fun `a backup that is the track's own current file is never deleted`() = runTest {
        // A SAF provider with stable document ids keeps the URI through the
        // rename, so the backup can be the very file the track points at.
        val song = tmp.newFile("lacrymosa.m4a").apply { writeText("the only copy") }
        journal.record(SwapJournal.Entry(7L, song.absolutePath, song.absolutePath, newVideoId = "vid12345678"))
        coEvery { trackDao.getById(7L) } returns row(filePath = song.absolutePath, flagged = false).copy(youtubeId = "vid12345678")

        coordinator.recoverInterruptedSwaps()

        assertTrue(song.exists())
    }

    // -- #531 review: the journal --------------------------------------------------

    @Test
    fun `the set-aside is written down before the old file moves`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        destinationIs(oldFile.absolutePath)
        stubCommitOverwriting(oldFile)
        every { localFileOps.setAside(any(), any()) } answers {
            check(journal.has(7L)) { "the move came before the journal entry" }
            callOriginal()
        }
        val outcomes = collectOutcomes()

        swap()

        assertEquals(listOf(SwapOutcome.Swapped(7L, "vid12345678", "Title")), outcomes)
    }

    @Test
    fun `a new swap of a track that still has a set-aside file waits`() = runTest {
        journal.record(SwapJournal.Entry(7L, "/music/lacrymosa.m4a", "/music/lacrymosa.m4a.swapbak", newVideoId = "old"))
        val outcomes = collectOutcomes()

        swap()

        // A second set-aside could bury the user's original audio; the next
        // start's recovery handles the first one.
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        assertEquals(listOf(SwapOutcome.SaveFailed(7L, "vid12345678", "Title")), outcomes)
    }

    @Test
    fun `the journal keeps the entry while the backup can't be deleted`() = runTest {
        val oldFile = tmp.newFile("lacrymosa.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        stubSuccessfulDownload()
        destinationIs(oldFile.absolutePath)
        stubCommitOverwriting(oldFile)
        every { localFileOps.delete(match { it.endsWith(".swapbak") }) } returns false

        swap()

        assertEquals(1, journal.pending().size)
    }

    @Test
    fun `a replacement id that isn't a YouTube video id is never downloaded or written`() = runTest {
        val oldFile = tmp.newFile("kept.m4a").apply { writeText("original audio") }
        givenRow(filePath = oldFile.absolutePath)
        val outcomes = collectOutcomes()
        val malformed = listOf("../x", "a/b", "a%(title)s", "x\\y", "short", "")

        malformed.forEach { coordinator.performSwap(trackId = 7L, newVideoId = it) }

        // The id names the temp file and the yt-dlp URL, and would become the
        // track's youtube_id: none of that may happen for a malformed one.
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        neverWroteTheVideo()
        assertTrue("the old file must be untouched", oldFile.exists())
        assertTrue(journal.pending().isEmpty())
        assertEquals(malformed.map { SwapOutcome.DownloadFailed(7L, it, "Title") }, outcomes)
    }

    @Test
    fun `a real video id still names the temp file and the URL`() = runTest {
        stubSuccessfulDownload()

        coordinator.performSwap(trackId = 7L, newVideoId = "dQw4w9WgXcQ")

        coVerify(exactly = 1) {
            downloadExecutor.download(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                any(),
                "swap_7_dQw4w9WgXcQ",
                any(),
                any(),
            )
        }
    }
}
