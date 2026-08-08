package com.stash.data.download

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
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
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the v0.9.35 metadata-embedding plan, Task 7 contract for
 * [DownloadManager]:
 *
 *  * On the lossless-source success path, [TrackDao.setMetadataEmbeddedAt]
 *    must fire with a non-zero timestamp before the download returns
 *    Success.
 *
 *  * Stamping is non-fatal — if the DAO call throws, the download still
 *    surfaces as [TrackDownloadResult.Success] (the file is on disk).
 *
 * The yt-dlp branch is covered by the parallel
 * [com.stash.data.download.search.SearchDownloadCoordinatorEmbedStampTest]
 * and the integration test in Task 14 (real device, real ffmpeg). Mocking
 * the full yt-dlp branch in DownloadManager would require staging a real
 * file on disk for downstream `fileOrganizer.commitDownload` to accept,
 * which adds noise without proving more than the lossless test already
 * does — both paths share the same one-line stamp call.
 */
class DownloadManagerEmbedStampTest {

    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val searchExecutor: HybridSearchExecutor = mockk(relaxed = true)
    private val albumMatchExecutor: AlbumMatchExecutor = mockk(relaxed = true)
    private val matchScorer: MatchScorer = mockk(relaxed = true)
    private val duplicateDetection: DuplicateDetectionService = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true)
    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true)
    private val ytLibraryCanonicalizer: YtLibraryCanonicalizer = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
    private val lastFmCredentials: LastFmCredentials = mockk(relaxed = true)
    private val losslessRegistry: LosslessSourceRegistry = mockk()
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk()
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val jioSaavnResolver: JioSaavnResolver = mockk {
        coEvery { resolve(any(), any()) } returns null
    }
    private val trackFinalizer: TrackFinalizer = mockk()
    private val loudnessMeasurer: com.stash.core.data.audio.LoudnessMeasurer = mockk(relaxed = true)
    private val metadataEmbedder: MetadataEmbedder = mockk(relaxed = true)
    private val albumArtCache: AlbumArtCache = mockk(relaxed = true)
    private val lyricsFetchTrigger: LyricsFetchTrigger = mockk(relaxed = true)
    private val audioDurationExtractor: com.stash.core.data.audio.AudioDurationExtractor =
        mockk(relaxed = true)
    private val losslessHealthGate: com.stash.data.download.lossless.LosslessSourceHealthGate =
        mockk(relaxed = true)

    private fun newSubject(): DownloadManager = DownloadManager(
        downloadExecutor = downloadExecutor,
        searchExecutor = searchExecutor,
        albumMatchExecutor = albumMatchExecutor,
        matchScorer = matchScorer,
        duplicateDetection = duplicateDetection,
        fileOrganizer = fileOrganizer,
        qualityPrefs = qualityPrefs,
        ytLibraryCanonicalizer = ytLibraryCanonicalizer,
        trackDao = trackDao,
        playlistDao = playlistDao,
        lastFmApiClient = lastFmApiClient,
        lastFmCredentials = lastFmCredentials,
        losslessRegistry = losslessRegistry,
        losslessUrlDownloader = losslessUrlDownloader,
        losslessPrefs = losslessPrefs,
        jioSaavnResolver = jioSaavnResolver,
        trackFinalizer = trackFinalizer,
        loudnessMeasurer = loudnessMeasurer,
        metadataEmbedder = metadataEmbedder,
        albumArtCache = albumArtCache,
        lyricsFetchTrigger = lyricsFetchTrigger,
        audioDurationExtractor = audioDurationExtractor,
        losslessHealthGate = losslessHealthGate,
    )

    private fun stubTrack(): Track = Track(
        id = 42L,
        title = "Sample",
        artist = "Sample Artist",
        album = "Sample Album",
    )

    private fun stubSourceResult(tempFile: File): SourceResult = SourceResult(
        sourceId = "test",
        downloadUrl = "https://example.test/file.flac",
        format = AudioFormat(codec = "flac", bitrateKbps = 1000, bitsPerSample = 16, sampleRateHz = 44100),
        confidence = 0.95f,
    )

    private fun jioSaavnResult() = SourceResult(
        sourceId = JioSaavnResolver.SOURCE_ID,
        downloadUrl = "https://aac.saavncdn.com/song_320.mp4",
        format = AudioFormat(
            codec = "aac",
            bitrateKbps = 320,
            sampleRateHz = 44_100,
            fileExtension = "m4a",
        ),
        confidence = 0.94f,
    )

    @Test
    fun `JioSaavn AAC 320 is committed as m4a before YouTube fallback`() = runTest {
        val track = stubTrack().copy(durationMs = 200_000L)
        val tempDir = File.createTempFile("tmp", "").apply { delete(); mkdirs() }
        coEvery { fileOrganizer.getTempDir() } returns tempDir
        coEvery { jioSaavnResolver.resolve(any(), any()) } returns jioSaavnResult()
        coEvery { losslessUrlDownloader.download(any(), any(), any()) } answers {
            val destination = secondArg<File>()
            destination.writeText("fake-m4a-bytes")
            Result.success(destination)
        }
        every { audioDurationExtractor.extract(any()) } returns AudioMetadata(
            durationMs = 200_000L,
            bitrateKbps = 313,
            format = "aac",
            sampleRateHz = 44_100,
        )
        val committed = FileOrganizer.CommittedTrack(
            filePath = "/library/Sample Artist/Sample.m4a",
            sizeBytes = 1234L,
        )
        coEvery { trackFinalizer.finalizeFile(any(), any(), any()) } returns
            TrackFinalizer.FinalizeResult.Success(committed, meta = null)

        val result = newSubject().tryJioSaavnDownload(track)

        assertTrue("expected Success, got $result", result is TrackDownloadResult.Success)
        assertEquals(committed.filePath, (result as TrackDownloadResult.Success).filePath)
        coVerify { trackFinalizer.finalizeFile(any(), any(), match { it.fileExtension == "m4a" }) }
    }

    @Test
    fun `JioSaavn media below 256 kbps is rejected before commit`() = runTest {
        val track = stubTrack().copy(durationMs = 200_000L)
        val tempDir = File.createTempFile("tmp", "").apply { delete(); mkdirs() }
        coEvery { fileOrganizer.getTempDir() } returns tempDir
        coEvery { jioSaavnResolver.resolve(any(), any()) } returns jioSaavnResult()
        coEvery { losslessUrlDownloader.download(any(), any(), any()) } answers {
            val destination = secondArg<File>()
            destination.writeText("fake-low-bitrate-m4a")
            Result.success(destination)
        }
        every { audioDurationExtractor.extract(any()) } returns AudioMetadata(
            durationMs = 200_000L,
            bitrateKbps = 160,
            format = "aac",
        )

        val result = newSubject().tryJioSaavnDownload(track)

        assertEquals(null, result)
        coVerify(exactly = 0) { trackFinalizer.finalizeFile(any(), any(), any()) }
    }

    @Test
    fun `lossless success path stamps metadata_embedded_at with non-zero timestamp`() = runTest {
        val track = stubTrack()
        val tempDir = File.createTempFile("tmp", "").apply { delete(); mkdirs() }
        coEvery { fileOrganizer.getTempDir() } returns tempDir

        val match = stubSourceResult(tempDir)
        coEvery { losslessRegistry.resolve(any()) } returns match
        coEvery { losslessUrlDownloader.download(any(), any(), any()) } answers {
            // Touch the destination file so callers see a fetched artefact.
            val destination = secondArg<File>()
            destination.parentFile?.mkdirs()
            destination.writeText("fake-flac-bytes")
            Result.success(destination)
        }

        val committed = FileOrganizer.CommittedTrack(
            filePath = "/library/Sample Artist/Sample.flac",
            sizeBytes = 1234L,
        )
        coEvery { trackFinalizer.finalizeFile(any(), any(), any()) } returns
            TrackFinalizer.FinalizeResult.Success(committed, meta = null)

        val tsSlot = slot<Long>()
        coEvery { trackDao.setMetadataEmbeddedAt(track.id, capture(tsSlot)) } answers { }

        val before = System.currentTimeMillis()
        val result = newSubject().tryLosslessDownload(track)
        val after = System.currentTimeMillis()

        assertTrue("expected Success, got $result", result is TrackDownloadResult.Success)
        coVerify { trackDao.setMetadataEmbeddedAt(track.id, any()) }
        assertTrue(
            "stamp ${tsSlot.captured} must be in [$before, $after]",
            tsSlot.captured in before..after,
        )
    }

    @Test
    fun `lossless success path remains Success when stamp DAO call throws`() = runTest {
        val track = stubTrack()
        val tempDir = File.createTempFile("tmp", "").apply { delete(); mkdirs() }
        coEvery { fileOrganizer.getTempDir() } returns tempDir

        val match = stubSourceResult(tempDir)
        coEvery { losslessRegistry.resolve(any()) } returns match
        coEvery { losslessUrlDownloader.download(any(), any(), any()) } answers {
            val destination = secondArg<File>()
            destination.parentFile?.mkdirs()
            destination.writeText("fake-flac-bytes")
            Result.success(destination)
        }

        val committed = FileOrganizer.CommittedTrack(
            filePath = "/library/Sample Artist/Sample.flac",
            sizeBytes = 1234L,
        )
        coEvery { trackFinalizer.finalizeFile(any(), any(), any()) } returns
            TrackFinalizer.FinalizeResult.Success(committed, meta = null)
        coEvery { trackDao.setMetadataEmbeddedAt(any(), any()) } throws
            RuntimeException("simulated DAO failure")

        val result = newSubject().tryLosslessDownload(track)

        assertTrue("expected Success despite stamp failure, got $result", result is TrackDownloadResult.Success)
        assertEquals(committed.filePath, (result as TrackDownloadResult.Success).filePath)
    }
}
