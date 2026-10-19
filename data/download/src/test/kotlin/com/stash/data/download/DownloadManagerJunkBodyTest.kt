package com.stash.data.download

import android.content.Context
import com.stash.core.common.constants.StashConstants
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.files.LocalFileOps
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.model.QualityTier
import com.stash.core.model.Track
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A fetched file is checked BEFORE the commit. A junk body (a ~274-byte yt-dlp
 * error written as audio) used to be committed first: on a re-download it
 * replaced the song's own library file at the same name, and the check after
 * the commit then deleted that path, the one the row records, so the song had
 * no file at all. Now junk never reaches the commit, and the library file is
 * never touched.
 */
class DownloadManagerJunkBodyTest {

    @get:Rule val tmp = TemporaryFolder()

    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true)
    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val losslessRegistry: LosslessSourceRegistry = mockk()
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk()
    private val trackFinalizer: TrackFinalizer = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)

    /** The real gate: plain temp paths need nothing from the context. */
    private val localFileOps = LocalFileOps(mockk<Context>(relaxed = true))

    @Before
    fun setUp() {
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        coEvery { losslessPrefs.downloadModeNow() } returns com.stash.core.model.DownloadMode.OPUS
        coEvery { trackDao.getById(any()) } returns null
        coEvery { fileOrganizer.getTempDir() } returns tmp.root
        coEvery { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            FileOrganizer.CommittedTrack("/music/evanescence-lacrymosa.opus", AUDIO_BYTES.toLong())
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
        losslessRegistry = losslessRegistry,
        losslessUrlDownloader = losslessUrlDownloader,
        losslessPrefs = losslessPrefs,
        jioSaavnResolver = mockk<JioSaavnResolver> { coEvery { resolve(any(), any()) } returns null },
        tidalAacResolver = mockk<com.stash.data.download.lossless.tidal.TidalAacResolver> { coEvery { resolve(any(), any(), any()) } returns com.stash.data.download.lossless.tidal.TidalAac.NoAac },
        trackFinalizer = trackFinalizer,
        loudnessMeasurer = mockk(relaxed = true),
        metadataEmbedder = mockk<MetadataEmbedder>(relaxed = true),
        albumArtCache = mockk<AlbumArtCache>(relaxed = true),
        lyricsFetchTrigger = mockk<LyricsFetchTrigger>(relaxed = true),
        audioDurationExtractor = mockk(relaxed = true),
        losslessHealthGate = mockk(relaxed = true),
        localFileOps = localFileOps,
        losslessQuota = com.stash.data.download.lossless.quota.LosslessQuota.inMemory(),
    )

    /** A downloaded song whose audio the user picked: straight to the YouTube rung. */
    private val track = Track(
        id = 7L,
        title = "Lacrymosa",
        artist = "Evanescence",
        album = "Synthesis",
        youtubeId = "pickedVideo",
        matchPickedAt = 1_000L,
        filePath = "/music/evanescence-lacrymosa.opus",
        isDownloaded = true,
    )

    private fun ytDlpWrites(bytes: Int): File {
        val file = tmp.newFile("dl_7_abcd1234.webm").apply { writeBytes(ByteArray(bytes)) }
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns DownloadResult.Success(file)
        return file
    }

    @Test
    fun `a junk yt-dlp body fails the download without committing anything`() = runTest {
        val junk = ytDlpWrites(JUNK_BYTES)

        val result = newSubject().downloadTrack(track)

        assertEquals(
            TrackDownloadResult.Failed("Downloaded file too small — discarded as a failed download"),
            result,
        )
        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertFalse("the junk temp file is deleted", junk.exists())
    }

    @Test
    fun `a real yt-dlp body is still committed`() = runTest {
        ytDlpWrites(AUDIO_BYTES)

        val result = newSubject().downloadTrack(track)

        assertTrue(result is TrackDownloadResult.Success)
        coVerify(exactly = 1) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a junk lossless body is never committed`() = runTest {
        coEvery { losslessRegistry.resolve(any()) } returns SourceResult(
            sourceId = "squid_qobuz",
            downloadUrl = "https://example.test/x.flac",
            format = AudioFormat(codec = "flac", bitrateKbps = 1000, bitsPerSample = 16, sampleRateHz = 44100),
            confidence = 0.95f,
        )
        var fetched: File? = null
        coEvery { losslessUrlDownloader.download(any(), any(), any()) } answers {
            val destination = secondArg<File>().apply { writeBytes(ByteArray(JUNK_BYTES)) }
            fetched = destination
            Result.success(destination)
        }

        val result = newSubject().tryLosslessDownload(track)

        assertNull("a failed fetch: the next source gets its turn", result)
        coVerify(exactly = 0) { trackFinalizer.finalizeFile(any(), any(), any(), any()) }
        assertFalse(fetched!!.exists())
    }

    private companion object {
        /** A yt-dlp error body written as audio. */
        const val JUNK_BYTES = 274
        val AUDIO_BYTES = (StashConstants.MIN_PLAYABLE_LOCAL_BYTES + 1).toInt()
    }
}
