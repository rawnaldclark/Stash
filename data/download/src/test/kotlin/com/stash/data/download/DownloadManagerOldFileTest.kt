package com.stash.data.download

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.MusicSource
import com.stash.core.model.QualityTier
import com.stash.core.model.Track
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.prefs.QualityPreferencesManager
import io.mockk.coEvery
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

/**
 * A re-download that lands on a new path deletes the track's old file. Two
 * rows can share that file (an import that took a downloaded song's file
 * before imports picked a name of their own), so it stays while another row
 * still records it.
 */
class DownloadManagerOldFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true)
    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)

    @Before
    fun setUp() {
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        coEvery { trackDao.getById(any()) } returns null
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } answers {
            DownloadResult.Success(tmp.newFile("dl_7.opus").apply { writeText("new audio") })
        }
        coEvery { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            FileOrganizer.CommittedTrack("/music/evanescence/synthesis/lacrymosa.opus", 9L)
    }

    @Test
    fun `a re-download keeps an old file another song still plays from`() = runTest {
        val old = tmp.newFile("lacrymosa-old.opus")
        coEvery { trackDao.countOtherTracksWithFilePath(old.absolutePath, 7L) } returns 1

        val result = newSubject().downloadTrack(track(old.absolutePath))

        assertTrue("got $result", result is TrackDownloadResult.Success)
        assertTrue(old.exists())
    }

    @Test
    fun `a re-download deletes an old file only it records`() = runTest {
        val old = tmp.newFile("lacrymosa-old.opus")
        coEvery { trackDao.countOtherTracksWithFilePath(old.absolutePath, 7L) } returns 0

        val result = newSubject().downloadTrack(track(old.absolutePath))

        assertTrue("got $result", result is TrackDownloadResult.Success)
        assertFalse(old.exists())
    }

    /** A picked track goes straight to the YouTube rung, which is the one that deletes the old file. */
    private fun track(oldPath: String) = Track(
        id = 7L,
        title = "Lacrymosa",
        artist = "Evanescence",
        album = "Synthesis",
        youtubeId = "pickedVideo",
        source = MusicSource.SPOTIFY,
        matchPickedAt = 1_000L,
        filePath = oldPath,
        isDownloaded = true,
    )

    private fun newSubject(): DownloadManager = DownloadManager(
        downloadExecutor = downloadExecutor,
        searchExecutor = mockk(relaxed = true),
        albumMatchExecutor = mockk(relaxed = true),
        matchScorer = mockk(relaxed = true),
        duplicateDetection = mockk(relaxed = true),
        fileOrganizer = fileOrganizer,
        qualityPrefs = qualityPrefs,
        ytLibraryCanonicalizer = mockk(relaxed = true),
        trackDao = trackDao,
        playlistDao = mockk(relaxed = true),
        lastFmApiClient = mockk(relaxed = true),
        lastFmCredentials = mockk(relaxed = true),
        losslessRegistry = mockk(relaxed = true),
        losslessUrlDownloader = mockk(relaxed = true),
        losslessPrefs = mockk(relaxed = true),
        jioSaavnResolver = mockk(relaxed = true),
        trackFinalizer = mockk(relaxed = true),
        loudnessMeasurer = mockk(relaxed = true),
        metadataEmbedder = mockk(relaxed = true),
        albumArtCache = mockk(relaxed = true),
        lyricsFetchTrigger = mockk(relaxed = true),
        audioDurationExtractor = mockk(relaxed = true),
        losslessHealthGate = mockk(relaxed = true),
    ).apply {
        forceYoutubeFallbackOnDebugBuilds = false
    }
}
