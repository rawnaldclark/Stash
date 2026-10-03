package com.stash.data.download.files

import android.content.Context
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.prefs.LibraryLayout
import com.stash.core.data.prefs.StoragePreference
import com.stash.core.data.sync.TrackIdentityEvents
import com.stash.core.model.QualityTier
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.prefs.QualityPreferencesManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #531 with real files: Evanescence recorded Lacrymosa twice, and the user
 * swaps the Synthesis one (track 7) while the other recording (track 8, The
 * Open Door) already has its file. Single-folder and Per-playlist layouts
 * name files `<artist>-<title>`, and same or blank albums collide under
 * Artist/Album, so both tracks can resolve to one file: the swap must give
 * track 7 its own file and leave track 8's alone.
 */
class SwapCoordinatorLayoutTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val storagePreference = mockk<StoragePreference>()
    private val trackDao = mockk<TrackDao>(relaxed = true)
    private val downloadExecutor = mockk<DownloadExecutor>()
    private val qualityPrefs = mockk<QualityPreferencesManager>()
    private val blocklistGuard = mockk<BlocklistGuard>(relaxed = true)
    private val localFileOps = spyk(LocalFileOps(mockk(relaxed = true)))
    private val trackIdentityEvents = mockk<TrackIdentityEvents>(relaxed = true)
    private val audioExtractor = mockk<AudioDurationExtractor>()
    private val saved = slot<String>()
    private lateinit var musicDir: File

    @Before
    fun setUp() {
        val filesDir = tmp.newFolder("files")
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns tmp.newFolder("cache")
        every { context.noBackupFilesDir } returns tmp.newFolder("no-backup")
        every { storagePreference.externalTreeUri } returns flowOf(null)
        musicDir = File(filesDir, "music")
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        every { localFileOps.acceptDownloadOrDelete(any()) } returns true
        every { audioExtractor.extract(any()) } returns null
        coEvery { trackDao.getFirstPlaylistNameForTrack(any()) } returns "Gothic"
        // No other track has the video (a relaxed mock would return a dummy row).
        coEvery { trackDao.findByYoutubeId(any()) } returns null
        coEvery {
            trackDao.completeSwap(
                trackId = 7L,
                youtubeId = any(),
                filePath = capture(saved),
                fileSizeBytes = any(),
                fileFormat = any(),
                qualityKbps = any(),
                sampleRateHz = any(),
                bitsPerSample = any(),
                durationMs = any(),
                metadataEmbeddedAt = any(),
                pickedAt = any(),
                downloadedAt = any(),
            )
        } returns 1
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } answers {
            val dir = secondArg<File>()
            val name = thirdArg<String>()
            DownloadResult.Success(File(dir, "$name.opus").apply { writeText("synthesis audio") })
        }
    }

    private fun coordinator(layout: LibraryLayout): SwapCoordinator {
        every { storagePreference.libraryLayout } returns flowOf(layout)
        return SwapCoordinator(
            downloadExecutor = downloadExecutor,
            fileOrganizer = FileOrganizer(context, storagePreference, trackDao),
            qualityPrefs = qualityPrefs,
            trackDao = trackDao,
            blocklistGuard = blocklistGuard,
            localFileOps = localFileOps,
            trackIdentityEvents = trackIdentityEvents,
            audioExtractor = audioExtractor,
            metadataEmbedder = mockk(relaxed = true),
            albumArtCache = mockk(relaxed = true),
            journal = SwapJournal(context),
        )
    }

    /** Track 8's file, the other recording, at [relative] under the music folder. */
    private fun siblingsFile(relative: String): File {
        val file = File(musicDir, relative).apply {
            parentFile!!.mkdirs()
            writeText("open door audio")
        }
        coEvery { trackDao.countOtherTracksWithFilePath(any(), 7L) } answers {
            if (firstArg<String>() == file.absolutePath) 1 else 0
        }
        return file
    }

    private fun flagged(album: String, filePath: String?) {
        coEvery { trackDao.getById(7L) } returns TrackEntity(
            id = 7L,
            title = "Lacrymosa",
            artist = "Evanescence",
            album = album,
            filePath = filePath,
            youtubeId = "wrong-video",
            isDownloaded = filePath != null,
            matchFlagged = true,
        )
    }

    /** Both tracks point at one file, as two downloads into the same name left them. */
    private suspend fun assertSwapGetsItsOwnFile(layout: LibraryLayout, sharedRelative: String, album: String) {
        val shared = siblingsFile(sharedRelative)
        flagged(album = album, filePath = shared.absolutePath)

        coordinator(layout).performSwap(7L, "vid123")

        assertEquals("track 8's audio is untouched and still there", "open door audio", shared.readText())
        assertNotEquals("track 7 got its own file", shared.absolutePath, saved.captured)
        assertEquals("synthesis audio", File(saved.captured).readText())
        assertEquals("same folder, distinct name", shared.parent, File(saved.captured).parent)
    }

    @Test
    fun `single folder - the swap gets its own file and the other recording keeps its file`() = runTest {
        assertSwapGetsItsOwnFile(LibraryLayout.SINGLE_FOLDER, "evanescence-lacrymosa.opus", album = "Synthesis")
    }

    @Test
    fun `per playlist - the swap gets its own file and the other recording keeps its file`() = runTest {
        assertSwapGetsItsOwnFile(LibraryLayout.PLAYLIST, "gothic/evanescence-lacrymosa.opus", album = "Synthesis")
    }

    @Test
    fun `artist and album with blank albums - the swap gets its own file`() = runTest {
        assertSwapGetsItsOwnFile(LibraryLayout.ARTIST_ALBUM, "evanescence/singles/lacrymosa.opus", album = "")
    }

    @Test
    fun `artist and album with the same album name - the swap gets its own file`() = runTest {
        assertSwapGetsItsOwnFile(LibraryLayout.ARTIST_ALBUM, "evanescence/fallen/lacrymosa.opus", album = "Fallen")
    }

    @Test
    fun `artist and album with different albums - the swap replaces only its own file`() = runTest {
        val siblings = siblingsFile("evanescence/the-open-door/lacrymosa.opus")
        val wrongFile = File(musicDir, "evanescence/synthesis/lacrymosa.flac").apply {
            parentFile!!.mkdirs()
            writeText("the wrong recording")
        }
        flagged(album = "Synthesis", filePath = wrongFile.absolutePath)

        coordinator(LibraryLayout.ARTIST_ALBUM).performSwap(7L, "vid123")

        assertEquals(File(musicDir, "evanescence/synthesis/lacrymosa.opus").absolutePath, saved.captured)
        assertEquals("synthesis audio", File(saved.captured).readText())
        assertFalse("its own wrong file is gone", wrongFile.exists())
        assertEquals("open door audio", siblings.readText())
    }
}
