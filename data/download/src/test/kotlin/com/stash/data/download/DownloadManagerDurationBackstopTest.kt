package com.stash.data.download

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.audio.AudioDurationExtractor
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

/**
 * Tests the download-path duration backstop added 2026-06-05: a degraded
 * source can serve a 30s preview stub whose URL lacked the `range=` marker.
 * The backstop probes the finished file, rejects sample-length stubs,
 * records the source degraded, and re-resolves so the registry fails over.
 */
class DownloadManagerDurationBackstopTest {

    // ── Pure helper ─────────────────────────────────────────────────────

    @Test fun `30s file against 4min expected is degraded`() {
        assertThat(isLosslessDurationDegraded(probedMs = 30_000L, expectedMs = 240_000L)).isTrue()
    }

    @Test fun `full-length file is not degraded`() {
        assertThat(isLosslessDurationDegraded(probedMs = 238_000L, expectedMs = 240_000L)).isFalse()
    }

    @Test fun `short track whose real length is also short is not degraded`() {
        // A genuine 30s interlude: probed ~= expected, not below the fraction.
        assertThat(isLosslessDurationDegraded(probedMs = 30_000L, expectedMs = 32_000L)).isFalse()
    }

    @Test fun `unknown expected duration is never degraded`() {
        assertThat(isLosslessDurationDegraded(probedMs = 30_000L, expectedMs = 0L)).isFalse()
    }

    @Test fun `unreadable probe is not degraded`() {
        assertThat(isLosslessDurationDegraded(probedMs = null, expectedMs = 240_000L)).isFalse()
    }

    // ── Integration: backstop triggers failover ─────────────────────────

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
    private val audioDurationExtractor: AudioDurationExtractor = mockk(relaxed = true)
    private val losslessHealthGate: LosslessSourceHealthGate = mockk(relaxed = true)

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
        durationMs = 240_000L,
    )

    private fun match(srcId: String): SourceResult = SourceResult(
        sourceId = srcId,
        downloadUrl = "https://example.test/$srcId.flac",
        format = AudioFormat(codec = "flac", bitrateKbps = 1000, bitsPerSample = 16, sampleRateHz = 44100),
        confidence = 0.95f,
    )

    @Test
    fun `sample-length download is rejected, source recorded degraded, and failover succeeds`() = runTest {
        val track = stubTrack()
        val tempDir = File.createTempFile("tmp", "").apply { delete(); mkdirs() }
        coEvery { fileOrganizer.getTempDir() } returns tempDir

        // First resolve → kennyy (will serve a stub); second resolve → squid (good).
        coEvery { losslessRegistry.resolve(any()) } returnsMany
            listOf(match("kennyy_qobuz"), match("squid_qobuz"))

        coEvery { losslessUrlDownloader.download(any(), any(), any()) } answers {
            val destination = secondArg<File>()
            destination.parentFile?.mkdirs()
            destination.writeText("fake-flac-bytes")
            Result.success(destination)
        }

        // Probe: first file is a 30s stub, second is full-length.
        every { audioDurationExtractor.extractMs(any()) } returnsMany listOf(30_000L, 240_000L)

        val committed = FileOrganizer.CommittedTrack(
            filePath = "/library/Sample Artist/Sample.flac",
            sizeBytes = 1234L,
        )
        coEvery { trackFinalizer.finalizeFile(any(), any(), any()) } returns
            TrackFinalizer.FinalizeResult.Success(committed, meta = null)

        val result = newSubject().tryLosslessDownload(track)

        assertThat(result).isInstanceOf(TrackDownloadResult.Success::class.java)
        assertThat((result as TrackDownloadResult.Success).filePath).isEqualTo(committed.filePath)
        // The degraded first source was cooled down…
        coVerify { losslessHealthGate.recordDegraded("kennyy_qobuz") }
        // …and we re-resolved to reach the good second source.
        coVerify(exactly = 2) { losslessRegistry.resolve(any()) }
    }
}
