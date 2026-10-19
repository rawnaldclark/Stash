package com.stash.data.download

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.model.QualityTier
import com.stash.core.model.Track
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.DuplicateDetectionService
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.matching.MatchScorer
import com.stash.data.download.matching.YtLibraryCanonicalizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The YouTube rung's temp file (one name per attempt) is deleted when the
 * commit fails or the tagging is cancelled; it used to stay, one leftover per
 * retry. A cancel during tagging propagates instead of being swallowed as a
 * failed embed, so the commit never runs for a cancelled download.
 */
class DownloadManagerTempFileTest {

    @get:Rule val tmp = TemporaryFolder()

    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true)
    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val metadataEmbedder: MetadataEmbedder = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private lateinit var downloaded: File

    @Before
    fun setUp() {
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        coEvery { losslessPrefs.downloadModeNow() } returns com.stash.core.model.DownloadMode.OPUS
        coEvery { trackDao.getById(any()) } returns null
        coEvery { fileOrganizer.getTempDir() } returns tmp.root
        downloaded = tmp.newFile("dl_7_abcd1234.opus").apply { writeText("audio") }
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns
            DownloadResult.Success(downloaded)
    }

    private fun newSubject(): DownloadManager = DownloadManager(
        downloadExecutor = downloadExecutor,
        searchExecutor = mockk<HybridSearchExecutor>(relaxed = true),
        albumMatchExecutor = mockk<AlbumMatchExecutor>(relaxed = true),
        matchScorer = mockk<MatchScorer>(relaxed = true),
        duplicateDetection = mockk<DuplicateDetectionService>(relaxed = true),
        fileOrganizer = fileOrganizer,
        qualityPrefs = qualityPrefs,
        ytLibraryCanonicalizer = mockk<YtLibraryCanonicalizer>(relaxed = true),
        trackDao = trackDao,
        playlistDao = mockk<PlaylistDao>(relaxed = true),
        lastFmApiClient = mockk<LastFmApiClient>(relaxed = true),
        lastFmCredentials = mockk<LastFmCredentials>(relaxed = true),
        losslessRegistry = mockk<LosslessSourceRegistry>(relaxed = true),
        losslessUrlDownloader = mockk<LosslessUrlDownloader>(relaxed = true),
        losslessPrefs = losslessPrefs,
        jioSaavnResolver = mockk<JioSaavnResolver> { coEvery { resolve(any(), any()) } returns null },
        tidalAacResolver = mockk<com.stash.data.download.lossless.tidal.TidalAacResolver> { coEvery { resolve(any(), any(), any()) } returns com.stash.data.download.lossless.tidal.TidalAac.NoAac },
        trackFinalizer = mockk<TrackFinalizer>(relaxed = true),
        loudnessMeasurer = mockk(relaxed = true),
        metadataEmbedder = metadataEmbedder,
        albumArtCache = mockk<AlbumArtCache>(relaxed = true),
        lyricsFetchTrigger = mockk<LyricsFetchTrigger>(relaxed = true),
        audioDurationExtractor = mockk(relaxed = true),
        losslessHealthGate = mockk(relaxed = true),
        // Every fetched file counts as audio here; DownloadManagerJunkBodyTest checks the gate.
        localFileOps = mockk<com.stash.core.data.files.LocalFileOps> { every { acceptDownloadOrDelete(any()) } returns true },
        losslessQuota = com.stash.data.download.lossless.quota.LosslessQuota.inMemory(),
    )

    /** A track whose audio the user picked: straight to the YouTube rung. */
    private val track = Track(
        id = 7L,
        title = "Lacrymosa",
        artist = "Evanescence",
        album = "Synthesis",
        youtubeId = "pickedVideo",
        matchPickedAt = 1_000L,
    )

    @Test
    fun `a failed commit deletes the temp file`() = runTest {
        coEvery { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) } throws
            IOException("No space left on device")

        try {
            newSubject().downloadTrack(track)
            fail("the commit failure propagates")
        } catch (expected: IOException) {
        }

        assertFalse("one leftover per retry", downloaded.exists())
    }

    @Test
    fun `a cancel during tagging propagates and deletes the temp file`() = runTest {
        coEvery { metadataEmbedder.embedMetadata(any(), any(), any()) } throws CancellationException("stopped")

        try {
            newSubject().downloadTrack(track)
            fail("the cancel was swallowed as a failed embed")
        } catch (expected: CancellationException) {
        }

        assertFalse(downloaded.exists())
        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a failed embed still commits the file`() = runTest {
        coEvery { metadataEmbedder.embedMetadata(any(), any(), any()) } throws IllegalStateException("ffmpeg died")
        coEvery { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            FileOrganizer.CommittedTrack("/music/evanescence-lacrymosa.opus", 5L)

        val result = newSubject().downloadTrack(track)

        assertTrue("got $result", result is TrackDownloadResult.Success)
        // The temp file is the commit's to delete once it has taken it.
        assertTrue(downloaded.exists())
    }
}
