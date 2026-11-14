package com.stash.data.download

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.model.DownloadMode
import com.stash.core.model.QualityTier
import com.stash.core.model.Track
import com.stash.data.download.files.AlbumArtCache
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.files.MetadataEmbedder
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourceHealthGate
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.tidal.TidalAac
import com.stash.data.download.lossless.tidal.TidalAacResolver
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

/**
 * No download is kept as the song when it is a preview or clip of it (Pixel 5, 2026-11-13: "Linger"
 * from Now Playing's Download came from Tidal as its 30 s PREVIEW, remuxed to a FLAC whose header
 * still said 4:34.77, and was kept: the duration backstop read only the header).
 *
 * The file's header and its frames ([AudioDurationExtractor.extractMs], [AudioDurationExtractor.extractFramesMs])
 * are stubbed here; FlacAudioEndTest and AudioDurationExtractorTest measure them on real bytes.
 */
class DownloadManagerClipTest {

    private val temp = File.createTempFile("clip", "").apply { delete(); mkdirs() }
    private val losslessRegistry: LosslessSourceRegistry = mockk()
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk()
    private val losslessHealthGate: LosslessSourceHealthGate = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val tidalAacResolver: TidalAacResolver = mockk { coEvery { resolve(any(), any(), any()) } returns TidalAac.NoAac }
    private val audioDurationExtractor: AudioDurationExtractor = mockk(relaxed = true)
    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true) { coEvery { getTempDir() } returns temp }
    private val trackFinalizer: TrackFinalizer = mockk {
        coEvery { finalizeFile(any(), any(), any()) } returns TrackFinalizer.FinalizeResult.Success(
            FileOrganizer.CommittedTrack(filePath = SAVED, sizeBytes = 1L),
            meta = null,
        )
    }

    private fun newSubject(): DownloadManager = DownloadManager(
        downloadExecutor = downloadExecutor,
        searchExecutor = mockk<HybridSearchExecutor>(relaxed = true),
        albumMatchExecutor = mockk<AlbumMatchExecutor>(relaxed = true),
        matchScorer = mockk<MatchScorer>(relaxed = true),
        duplicateDetection = mockk<DuplicateDetectionService>(relaxed = true),
        fileOrganizer = fileOrganizer,
        qualityPrefs = mockk<QualityPreferencesManager>(relaxed = true) { every { qualityTier } returns flowOf(QualityTier.MAX) },
        ytLibraryCanonicalizer = mockk<YtLibraryCanonicalizer>(relaxed = true),
        trackDao = mockk<TrackDao>(relaxed = true) { coEvery { getById(any()) } returns null },
        playlistDao = mockk<PlaylistDao>(relaxed = true),
        lastFmApiClient = mockk<LastFmApiClient>(relaxed = true),
        lastFmCredentials = mockk<LastFmCredentials>(relaxed = true),
        losslessRegistry = losslessRegistry,
        losslessUrlDownloader = losslessUrlDownloader,
        losslessPrefs = losslessPrefs,
        jioSaavnResolver = mockk<JioSaavnResolver> { coEvery { resolve(any(), any()) } returns null },
        tidalAacResolver = tidalAacResolver,
        trackFinalizer = trackFinalizer,
        loudnessMeasurer = mockk<com.stash.core.data.audio.LoudnessMeasurer>(relaxed = true),
        metadataEmbedder = mockk<MetadataEmbedder>(relaxed = true),
        albumArtCache = mockk<AlbumArtCache>(relaxed = true),
        lyricsFetchTrigger = mockk<LyricsFetchTrigger>(relaxed = true),
        audioDurationExtractor = audioDurationExtractor,
        losslessHealthGate = losslessHealthGate,
        localFileOps = mockk<com.stash.core.data.files.LocalFileOps> { every { acceptDownloadOrDelete(any()) } returns true },
        losslessQuota = com.stash.data.download.lossless.quota.LosslessQuota.inMemory(),
    )

    /** "Linger" as the rig's row had it: 273 s. */
    private val linger = Track(id = 990_825L, title = "Linger", artist = "The Cranberries", durationMs = 273_000L)

    private fun flac(sourceId: String, dash: Boolean = false) = SourceResult(
        sourceId = sourceId,
        downloadUrl = "https://example.test/$sourceId.mpd",
        format = AudioFormat(codec = "flac", bitrateKbps = 1411, bitsPerSample = 16, sampleRateHz = 44_100),
        confidence = 1.0f,
        isDash = dash,
    )

    /** Every fetch writes the file it was asked for. */
    private fun fetchesSucceed() {
        coEvery { losslessUrlDownloader.download(any(), any(), any()) } answers {
            Result.success(secondArg<File>().apply { writeText("fake-audio-bytes") })
        }
    }

    @Test
    fun `a 30 s file whose header says 4 35 is refused, its source cooled down, and the next source is tried`() = runTest {
        coEvery { losslessRegistry.resolve(any()) } returnsMany listOf(flac("tidal_bitchord", dash = true), flac("qbdlx_qobuz"))
        fetchesSucceed()
        // Both files' headers say the song; Tidal's frames stop at 0:30 (its PREVIEW), Qobuz's run it whole.
        every { audioDurationExtractor.extractMs(any()) } returns 274_773L
        every { audioDurationExtractor.extractFramesMs(any()) } returnsMany listOf(29_907L, 274_773L)

        val result = newSubject().tryLosslessDownload(linger)

        assertThat((result as TrackDownloadResult.Success).filePath).isEqualTo(SAVED)
        coVerify(exactly = 1) { losslessHealthGate.recordWrongFile("tidal_bitchord") }
        coVerify(exactly = 2) { losslessRegistry.resolve(any()) }
        // Only the second file was kept.
        coVerify(exactly = 1) { trackFinalizer.finalizeFile(any(), any(), any()) }
    }

    @Test
    fun `with no other source the clip is never kept - the download goes on as no lossless copy`() = runTest {
        coEvery { losslessRegistry.resolve(any()) } returnsMany listOf(flac("tidal_bitchord", dash = true), null)
        fetchesSucceed()
        every { audioDurationExtractor.extractMs(any()) } returns 274_773L
        every { audioDurationExtractor.extractFramesMs(any()) } returns 29_907L

        assertThat(newSubject().tryLosslessDownload(linger)).isNull()

        coVerify(exactly = 0) { trackFinalizer.finalizeFile(any(), any(), any()) }
        assertThat(temp.listFiles()!!.toList()).isEmpty()
    }

    @Test
    fun `a preview answer is refused - the source is cooled down and the next one asked`() = runTest {
        coEvery { losslessRegistry.resolve(any()) } returnsMany listOf(flac("tidal_bitchord", dash = true), flac("qbdlx_qobuz"))
        val asked = mutableListOf<SourceResult>()
        coEvery { losslessUrlDownloader.download(capture(asked), any(), any()) } answers {
            if (firstArg<SourceResult>().sourceId == "tidal_bitchord") {
                // What DashFlacDownloader says of Tidal's 30 s PREVIEW manifest, before it fetches a segment.
                Result.failure(LosslessUrlDownloader.PreviewException(29_907L))
            } else {
                Result.success(secondArg<File>().apply { writeText("fake-audio-bytes") })
            }
        }
        every { audioDurationExtractor.extractMs(any()) } returns 274_773L
        every { audioDurationExtractor.extractFramesMs(any()) } returns 274_773L

        val result = newSubject().tryLosslessDownload(linger)

        assertThat((result as TrackDownloadResult.Success).filePath).isEqualTo(SAVED)
        coVerify(exactly = 1) { losslessHealthGate.recordWrongFile("tidal_bitchord") }
        // The fetch was told the song's length: that is what the manifest is held to.
        assertThat(asked.map { it.songMs }).containsExactly(273_000L, 273_000L)
    }

    @Test
    fun `Tidal's AAC whose packets stop at 0 30 under the song's header is not saved`() = runTest {
        coEvery { tidalAacResolver.resolve(any(), any(), any()) } returns TidalAac.Found(SourceResult(
            sourceId = TidalAacResolver.SOURCE_ID,
            downloadUrl = "https://sp-ad-cf.audio.tidal.com/x.mp4",
            format = AudioFormat(codec = "aac", bitrateKbps = 320, fileExtension = "m4a"),
            confidence = 1.0f,
        ))
        fetchesSucceed()
        every { audioDurationExtractor.extract(any()) } returns AudioMetadata(durationMs = 274_773L, bitrateKbps = 320, format = "aac")
        every { audioDurationExtractor.extractFramesMs(any()) } returns 29_907L

        val step = newSubject().tidalAacStep(linger)

        assertThat(step).isEqualTo(DownloadManager.AacStep.NoAac)
        coVerify(exactly = 0) { trackFinalizer.finalizeFile(any(), any(), any()) }
    }

    @Test
    fun `a YouTube download that is a clip of the song is not kept`() = runTest {
        coEvery { losslessPrefs.downloadModeNow() } returns DownloadMode.OPUS
        val fetched = slot<String>()
        coEvery { downloadExecutor.download(any(), any(), capture(fetched), any(), any()) } answers {
            DownloadResult.Success(File(secondArg<File>(), "${thirdArg<String>()}.opus").apply { writeText("fake-opus") })
        }
        every { audioDurationExtractor.extractMs(any()) } returns 273_000L
        every { audioDurationExtractor.extractFramesMs(any()) } returns 29_907L

        val result = newSubject().downloadTrack(linger, preResolvedUrl = "https://music.youtube.com/watch?v=PXDlU0YDQ6U")

        assertThat(result).isInstanceOf(TrackDownloadResult.Failed::class.java)
        coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertThat(File(temp, "${fetched.captured}.opus").exists()).isFalse()
    }

    @Test
    fun `a whole song from YouTube is kept as before`() = runTest {
        coEvery { losslessPrefs.downloadModeNow() } returns DownloadMode.OPUS
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } answers {
            DownloadResult.Success(File(secondArg<File>(), "${thirdArg<String>()}.opus").apply { writeText("fake-opus") })
        }
        coEvery { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            FileOrganizer.CommittedTrack(filePath = SAVED, sizeBytes = 1L)
        every { audioDurationExtractor.extractMs(any()) } returns 273_000L
        every { audioDurationExtractor.extractFramesMs(any()) } returns 272_980L

        val result = newSubject().downloadTrack(linger, preResolvedUrl = "https://music.youtube.com/watch?v=PXDlU0YDQ6U")

        assertThat((result as TrackDownloadResult.Success).filePath).isEqualTo(SAVED)
    }

    private companion object {
        const val SAVED = "/library/the-cranberries/linger.flac"
    }
}
