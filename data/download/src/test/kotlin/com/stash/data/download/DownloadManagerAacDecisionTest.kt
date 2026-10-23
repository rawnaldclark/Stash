package com.stash.data.download

import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.model.DownloadMode
import com.stash.core.model.DownloadWaitReason
import com.stash.core.model.MusicSource
import com.stash.core.model.QualityTier
import com.stash.core.model.Track
import com.stash.data.download.files.FileOrganizer
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.tidal.TidalAacResolver
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The single-song download's lossy decision table in [DownloadManager] (Now Playing / track
 * options "Download", a Library row, a kept playlist: all reach it through the download queue).
 *
 * Lossless is on and found no FLAC in every case. Across each row type (a Spotify row, a YouTube
 * official-audio row, a YouTube music-video row), the YouTube fallback, whether Tidal has the song
 * as AAC (the "Tidal AAC when no FLAC" switch off reads the same: TidalAacResolverTest), and the
 * day's lossless allowance:
 *
 * | fallback | allowance | Tidal AAC | result                                   |
 * |----------|-----------|-----------|------------------------------------------|
 * | off      | spent     | any       | waits (Deferred); no lossy rung is asked |
 * | off      | left      | has it    | Tidal AAC (.m4a)                         |
 * | off      | left      | none      | waits (Deferred); no JioSaavn, no yt-dlp |
 * | on       | any       | has it    | Tidal AAC, before JioSaavn and YouTube   |
 * | on       | left      | none      | JioSaavn, then YouTube                   |
 *
 * With the fallback off, nothing from JioSaavn or YouTube is ever saved, in any build: debug
 * builds used to force the lossy chain here, which is how "Paint a Face" (a YouTube music video
 * that streamed as Tidal AAC) came down as YouTube Opus.
 */
class DownloadManagerAacDecisionTest {

    private val now = 1_792_238_400_000L
    private val losslessQuota = LosslessQuota.inMemory { now }
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val losslessRegistry: LosslessSourceRegistry = mockk { coEvery { resolve(any()) } returns null }
    private val tidalAacResolver: TidalAacResolver = mockk()
    private val jioSaavnResolver: JioSaavnResolver = mockk { coEvery { resolve(any(), any()) } returns null }
    private val downloadExecutor: DownloadExecutor = mockk {
        coEvery { download(any(), any(), any(), any(), any()) } returns DownloadResult.Error("yt-dlp reached")
    }
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk {
        coEvery { download(any(), any(), any()) } answers {
            Result.success(secondArg<File>().apply { writeText("fake-m4a-bytes") })
        }
    }
    private val audioDurationExtractor: com.stash.core.data.audio.AudioDurationExtractor = mockk(relaxed = true) {
        // Tidal's "Paint a Face (Album Version)": 2:49 of 320 kbps AAC.
        every { extract(any()) } returns AudioMetadata(durationMs = 169_000L, bitrateKbps = 320, format = "aac")
    }
    private val trackFinalizer: TrackFinalizer = mockk {
        coEvery { finalizeFile(any(), any(), any()) } returns TrackFinalizer.FinalizeResult.Success(
            FileOrganizer.CommittedTrack(filePath = SAVED_AAC, sizeBytes = 6_700_000L),
            meta = null,
        )
    }
    private val trackDao: TrackDao = mockk(relaxed = true) { coEvery { getById(any()) } returns null }
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true) {
        coEvery { getTempDir() } returns File.createTempFile("aacdecision", "").apply { delete(); mkdirs() }
    }

    private fun newSubject(
        handoff: com.stash.data.download.lossless.ResolvedSourceHandoff = com.stash.data.download.lossless.ResolvedSourceHandoff(),
        qualityPrefs: QualityPreferencesManager = mockk<QualityPreferencesManager>(relaxed = true) {
            every { qualityTier } returns flowOf(QualityTier.MAX)
        },
    ): DownloadManager = DownloadManager(
        downloadExecutor = downloadExecutor,
        searchExecutor = mockk(relaxed = true),
        albumMatchExecutor = mockk(relaxed = true),
        matchScorer = mockk(relaxed = true),
        duplicateDetection = mockk(relaxed = true),
        fileOrganizer = fileOrganizer,
        qualityPrefs = qualityPrefs,
        ytLibraryCanonicalizer = mockk(relaxed = true),
        trackDao = trackDao,
        playlistDao = mockk<PlaylistDao>(relaxed = true) { coEvery { isTrackInStashMix(any()) } returns false },
        lastFmApiClient = mockk(relaxed = true),
        lastFmCredentials = mockk(relaxed = true),
        losslessRegistry = losslessRegistry,
        losslessUrlDownloader = losslessUrlDownloader,
        losslessPrefs = losslessPrefs,
        jioSaavnResolver = jioSaavnResolver,
        tidalAacResolver = tidalAacResolver,
        trackFinalizer = trackFinalizer,
        loudnessMeasurer = mockk(relaxed = true),
        metadataEmbedder = mockk(relaxed = true),
        albumArtCache = mockk(relaxed = true),
        lyricsFetchTrigger = mockk(relaxed = true),
        audioDurationExtractor = audioDurationExtractor,
        losslessHealthGate = mockk(relaxed = true),
        localFileOps = mockk { every { acceptDownloadOrDelete(any()) } returns true },
        losslessQuota = losslessQuota,
        handoff = handoff,
    )

    /** The three kinds of library row; each one's length agrees with Tidal's 169 s file as it should. */
    private enum class Row(val track: Track) {
        SPOTIFY(Track(id = 1L, title = "Paint a Face", artist = "Neil Halstead", durationMs = 170_000L, source = MusicSource.SPOTIFY)),
        YOUTUBE_OFFICIAL_AUDIO(
            Track(
                id = 2L, title = "Paint a Face", artist = "Neil Halstead", durationMs = 170_000L,
                source = MusicSource.YOUTUBE, musicVideoType = "ATV", youtubeId = VIDEO_ID,
            ),
        ),
        // The owner's row: the music video, 3:00, unclassified and without an album.
        YOUTUBE_MUSIC_VIDEO(
            Track(
                id = 3L, title = "Paint a Face", artist = "Neil Halstead", durationMs = 180_000L,
                source = MusicSource.YOUTUBE, youtubeId = VIDEO_ID,
            ),
        ),
    }

    private fun arrange(
        fallbackOn: Boolean,
        tidalHasAac: Boolean,
        allowanceSpent: Boolean,
        mode: DownloadMode = DownloadMode.LOSSLESS,
    ) {
        clearMocks(tidalAacResolver, jioSaavnResolver, downloadExecutor, losslessUrlDownloader, losslessRegistry, answers = false)
        coEvery { losslessPrefs.downloadModeNow() } returns mode
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns fallbackOn
        // As the real stack answers: a spent day gets nothing from Tidal (AAC counts toward it).
        coEvery { tidalAacResolver.resolve(any(), any(), any()) } answers {
            when {
                losslessQuota.isSpent() -> com.stash.data.download.lossless.tidal.TidalAac.Unavailable(com.stash.data.download.lossless.tidal.TidalAac.Reason.SPENT, 0L)
                tidalHasAac -> com.stash.data.download.lossless.tidal.TidalAac.Found(tidalAac)
                else -> com.stash.data.download.lossless.tidal.TidalAac.NoAac
            }
        }
        losslessQuota.onHeaders(if (allowanceSpent) "500/500" else "10/500", "${now / 1000 + 3600}")
    }

    private suspend fun download(row: Row): TrackDownloadResult =
        newSubject().downloadTrack(row.track, preResolvedUrl = "https://music.youtube.com/watch?v=$VIDEO_ID")

    @Test
    fun `fallback off + allowance spent waits, and asks no lossy rung`() = runTest {
        for (row in Row.values()) {
            for (tidalHasAac in listOf(true, false)) {
                arrange(fallbackOn = false, tidalHasAac = tidalHasAac, allowanceSpent = true)

                val result = download(row)

                assertTrue("$row, AAC=$tidalHasAac: expected Deferred, got $result", result is TrackDownloadResult.Deferred)
                coVerify(exactly = 0) { tidalAacResolver.resolve(any(), any(), any()) }
                coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
                coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
            }
        }
    }

    @Test
    fun `fallback off + Tidal has AAC saves the Tidal AAC file for every row type`() = runTest {
        for (row in Row.values()) {
            arrange(fallbackOn = false, tidalHasAac = true, allowanceSpent = false)

            val result = download(row)

            assertEquals("$row", TrackDownloadResult.Success(SAVED_AAC), result)
            coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
            coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `fallback off + no Tidal AAC waits, never JioSaavn or YouTube`() = runTest {
        for (row in Row.values()) {
            arrange(fallbackOn = false, tidalHasAac = false, allowanceSpent = false)

            val result = download(row)

            assertTrue("$row: expected Deferred, got $result", result is TrackDownloadResult.Deferred)
            coVerify(exactly = 1) { tidalAacResolver.resolve(any(), any(), any()) }
            coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
            coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `fallback off + a Tidal AAC file that fails its check waits, never YouTube`() = runTest {
        // A preview stub of the song: the relay answered, the file is no good.
        every { audioDurationExtractor.extract(any()) } returns AudioMetadata(durationMs = 30_000L, bitrateKbps = 320, format = "aac")
        for (row in Row.values()) {
            arrange(fallbackOn = false, tidalHasAac = true, allowanceSpent = false)

            val result = download(row)

            assertTrue("$row: expected Deferred, got $result", result is TrackDownloadResult.Deferred)
            coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `fallback on + Tidal has AAC takes it before JioSaavn and YouTube`() = runTest {
        for (row in Row.values()) {
            arrange(fallbackOn = true, tidalHasAac = true, allowanceSpent = false)

            val result = download(row)

            assertEquals("$row", TrackDownloadResult.Success(SAVED_AAC), result)
            coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
            coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `fallback on + allowance spent gets no Tidal AAC and goes to JioSaavn, then YouTube`() = runTest {
        // AAC counts toward the day's allowance: past it, neither Tidal nor Qobuz answers.
        for (row in Row.values()) {
            arrange(fallbackOn = true, tidalHasAac = true, allowanceSpent = true)

            val result = download(row)

            assertTrue("$row: expected the YouTube rung, got $result", result is TrackDownloadResult.Failed)
            coVerifyOrder {
                jioSaavnResolver.resolve(any(), any())
                downloadExecutor.download(any(), any(), any(), any(), any())
            }
        }
    }

    @Test
    fun `fallback on + no Tidal AAC goes on to JioSaavn, then YouTube`() = runTest {
        for (row in Row.values()) {
            arrange(fallbackOn = true, tidalHasAac = false, allowanceSpent = false)

            val result = download(row)

            // The stubbed yt-dlp run ends in an error: reaching it is what counts here.
            assertTrue("$row: expected the YouTube rung's failure, got $result", result is TrackDownloadResult.Failed)
            coVerifyOrder {
                tidalAacResolver.resolve(any(), any(), any())
                jioSaavnResolver.resolve(any(), any())
                downloadExecutor.download(any(), any(), any(), any(), any())
            }
        }
    }

    @Test
    fun `Tidal is asked with the row's own words, length and reliability, as streaming asks`() = runTest {
        arrange(fallbackOn = false, tidalHasAac = false, allowanceSpent = false)
        val asked = mutableListOf<com.stash.data.download.lossless.TrackQuery>()
        coEvery { tidalAacResolver.resolve(capture(asked), any(), any()) } returns com.stash.data.download.lossless.tidal.TidalAac.NoAac

        download(Row.YOUTUBE_MUSIC_VIDEO)
        download(Row.SPOTIFY)

        assertEquals(180_000L, asked[0].durationMs)
        assertEquals(false, asked[0].durationReliable)
        assertEquals(170_000L, asked[1].durationMs)
        assertEquals(true, asked[1].durationReliable)
    }

    // -- Download quality: AAC 320 ----------------------------------------------------------
    //
    // | fallback | Tidal AAC | result                                                   |
    // |----------|-----------|----------------------------------------------------------|
    // | any      | has it    | Tidal AAC (?aac=only), even for a song with FLAC         |
    // | off      | none      | waits ("Not available as AAC yet")                      |
    // | on       | none      | JioSaavn, then YouTube                                   |
    // Never a lossless lookup. Each AAC answer uses the daily allowance, so a spent day stops
    // it like FLAC: fallback on → JioSaavn, then YouTube; off → waits for the reset.

    @Test
    fun `AAC mode saves Tidal AAC for every row type, and never asks for FLAC`() = runTest {
        for (row in Row.values()) {
            for (fallbackOn in listOf(false, true)) {
                arrange(fallbackOn = fallbackOn, tidalHasAac = true, allowanceSpent = false, mode = DownloadMode.AAC)

                val result = download(row)

                assertEquals("$row, fallback=$fallbackOn", TrackDownloadResult.Success(SAVED_AAC), result)
                coVerify(exactly = 1) { tidalAacResolver.resolve(any(), aacOnly = true, foreground = any()) }
                coVerify(exactly = 0) { losslessRegistry.resolve(any()) }
                coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
                coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
            }
        }
    }

    @Test
    fun `AAC mode with the allowance spent - fallback off waits for the reset, on goes lossy`() = runTest {
        for (row in Row.values()) {
            arrange(fallbackOn = false, tidalHasAac = true, allowanceSpent = true, mode = DownloadMode.AAC)
            assertEquals("$row", TrackDownloadResult.Deferred(DownloadWaitReason.RESET), download(row))
            coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }

            arrange(fallbackOn = true, tidalHasAac = true, allowanceSpent = true, mode = DownloadMode.AAC)
            val lossy = download(row)
            assertTrue("$row: expected the YouTube rung, got $lossy", lossy is TrackDownloadResult.Failed)
            coVerify(exactly = 0) { losslessRegistry.resolve(any()) }
        }
    }

    @Test
    fun `AAC mode with no Tidal AAC and the fallback off waits, never FLAC, JioSaavn or YouTube`() = runTest {
        for (row in Row.values()) {
            arrange(fallbackOn = false, tidalHasAac = false, allowanceSpent = false, mode = DownloadMode.AAC)

            val result = download(row)

            assertTrue("$row: expected Deferred, got $result", result is TrackDownloadResult.Deferred)
            coVerify(exactly = 0) { losslessRegistry.resolve(any()) }
            coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
            coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `AAC mode with no Tidal AAC and the fallback on goes to JioSaavn, then YouTube`() = runTest {
        for (row in Row.values()) {
            arrange(fallbackOn = true, tidalHasAac = false, allowanceSpent = false, mode = DownloadMode.AAC)

            val result = download(row)

            assertTrue("$row: expected the YouTube rung's failure, got $result", result is TrackDownloadResult.Failed)
            coVerify(exactly = 0) { losslessRegistry.resolve(any()) }
            coVerifyOrder {
                tidalAacResolver.resolve(any(), aacOnly = true, foreground = any())
                jioSaavnResolver.resolve(any(), any())
                downloadExecutor.download(any(), any(), any(), any(), any())
            }
        }
    }

    // -- Download quality: Opus --------------------------------------------------------------

    @Test
    fun `Opus mode downloads from YouTube only, whatever the fallback, Tidal or the allowance`() = runTest {
        for (row in Row.values()) {
            for (fallbackOn in listOf(false, true)) {
                arrange(fallbackOn = fallbackOn, tidalHasAac = true, allowanceSpent = true, mode = DownloadMode.OPUS)

                val result = download(row)

                assertTrue("$row, fallback=$fallbackOn: expected the YouTube rung, got $result", result is TrackDownloadResult.Failed)
                coVerify(exactly = 1) { downloadExecutor.download(any(), any(), any(), any(), any()) }
                coVerify(exactly = 0) { losslessRegistry.resolve(any()) }
                coVerify(exactly = 0) { tidalAacResolver.resolve(any(), any(), any()) }
                coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
            }
        }
    }

    // -- A song whose audio the user picked (Failed Matches) ----------------------------------

    @Test
    fun `a picked song waits with the fallback off in Lossless and AAC mode, and downloads its video in Opus`() = runTest {
        val picked = Row.YOUTUBE_MUSIC_VIDEO.track.copy(matchPickedAt = 1L)
        for (mode in DownloadMode.entries) {
            arrange(fallbackOn = false, tidalHasAac = true, allowanceSpent = false, mode = mode)

            val result = newSubject().downloadTrack(picked, preResolvedUrl = null)

            if (mode == DownloadMode.OPUS) {
                assertTrue("$mode: expected the YouTube rung, got $result", result is TrackDownloadResult.Failed)
            } else {
                assertTrue("$mode: expected Deferred, got $result", result is TrackDownloadResult.Deferred)
                coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
            }
            coVerify(exactly = 0) { tidalAacResolver.resolve(any(), any(), any()) }
        }
    }

    // -- Tidal gave no answer (busy, paced, failed): the download waits, whatever the fallback --
    //
    // The tables above mock the resolver's verdict; these drive its typed "no answer" result,
    // the one a bare null used to hide (busy read as "no AAC" and went on to YouTube).

    private fun tidalUnavailable(reason: com.stash.data.download.lossless.tidal.TidalAac.Reason) {
        coEvery { tidalAacResolver.resolve(any(), any(), any()) } returns
            com.stash.data.download.lossless.tidal.TidalAac.Unavailable(reason, now + 60_000)
    }

    @Test
    fun `Tidal busy, paced or failing waits in both modes and both fallbacks, never JioSaavn or YouTube`() = runTest {
        val noAnswer = listOf(
            com.stash.data.download.lossless.tidal.TidalAac.Reason.BUSY,
            com.stash.data.download.lossless.tidal.TidalAac.Reason.RATE_LIMITED,
            com.stash.data.download.lossless.tidal.TidalAac.Reason.FAILED,
        )
        for (mode in listOf(DownloadMode.LOSSLESS, DownloadMode.AAC)) {
            for (fallbackOn in listOf(false, true)) {
                for (reason in noAnswer) {
                    arrange(fallbackOn = fallbackOn, tidalHasAac = true, allowanceSpent = false, mode = mode)
                    tidalUnavailable(reason)

                    val result = download(Row.SPOTIFY)

                    assertEquals("$mode, fallback=$fallbackOn, $reason", TrackDownloadResult.Deferred(DownloadWaitReason.TIDAL_BUSY), result)
                    coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
                    coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
                }
            }
        }
    }

    @Test
    fun `a spent day follows the fallback - on goes lossy, off waits for the reset`() = runTest {
        for (mode in listOf(DownloadMode.LOSSLESS, DownloadMode.AAC)) {
            arrange(fallbackOn = true, tidalHasAac = true, allowanceSpent = false, mode = mode)
            tidalUnavailable(com.stash.data.download.lossless.tidal.TidalAac.Reason.SPENT)
            val lossy = download(Row.SPOTIFY)
            assertTrue("$mode: expected the YouTube rung, got $lossy", lossy is TrackDownloadResult.Failed)

            arrange(fallbackOn = false, tidalHasAac = true, allowanceSpent = false, mode = mode)
            tidalUnavailable(com.stash.data.download.lossless.tidal.TidalAac.Reason.SPENT)
            assertEquals("$mode", TrackDownloadResult.Deferred(DownloadWaitReason.RESET), download(Row.SPOTIFY))
        }
    }

    @Test
    fun `a Tidal AAC file the CDN failed to deliver waits rather than go lossy`() = runTest {
        arrange(fallbackOn = true, tidalHasAac = true, allowanceSpent = false, mode = DownloadMode.AAC)
        coEvery { losslessUrlDownloader.download(any(), any(), any()) } returns Result.failure(java.io.IOException("reset"))
        assertEquals(TrackDownloadResult.Deferred(DownloadWaitReason.TIDAL_BUSY), download(Row.SPOTIFY))
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the sweep's match is taken by the download instead of asking Tidal again`() = runTest {
        val handoff = com.stash.data.download.lossless.ResolvedSourceHandoff()
        arrange(fallbackOn = false, tidalHasAac = true, allowanceSpent = false, mode = DownloadMode.AAC)
        handoff.put(Row.SPOTIFY.track.id, tidalAac, com.stash.data.download.lossless.ResolvedSourceHandoff.Kind.TIDAL_AAC)

        val result = newSubject(handoff).downloadTrack(Row.SPOTIFY.track, preResolvedUrl = null)

        assertEquals(TrackDownloadResult.Success(SAVED_AAC), result)
        coVerify(exactly = 0) { tidalAacResolver.resolve(any(), any(), any()) }
    }

    @Test
    fun `Opus mode asks yt-dlp for Opus first, even on Best, and AAC only when there is none`() = runTest {
        arrange(fallbackOn = false, tidalHasAac = true, allowanceSpent = false, mode = DownloadMode.OPUS)
        val best = mockk<QualityPreferencesManager>(relaxed = true) { every { qualityTier } returns flowOf(QualityTier.BEST) }

        newSubject(qualityPrefs = best).downloadTrack(Row.YOUTUBE_MUSIC_VIDEO.track, preResolvedUrl = "https://music.youtube.com/watch?v=$VIDEO_ID")

        coVerify { downloadExecutor.download(any(), any(), any(), listOf("-f", "251/250/249/bestaudio[acodec=opus]/140/bestaudio", "--embed-metadata"), any()) }
    }

    @Test
    fun `a picked song with the fallback off waits for the fallback switch`() = runTest {
        arrange(fallbackOn = false, tidalHasAac = true, allowanceSpent = false, mode = DownloadMode.LOSSLESS)
        val picked = Row.YOUTUBE_MUSIC_VIDEO.track.copy(matchPickedAt = 1L)
        assertEquals(TrackDownloadResult.Deferred(DownloadWaitReason.FALLBACK), newSubject().downloadTrack(picked, null))
    }

    private val tidalAac = SourceResult(
        sourceId = TidalAacResolver.SOURCE_ID,
        downloadUrl = "https://sp-ad-cf.audio.tidal.com/x.mp4",
        format = AudioFormat(codec = "aac", bitrateKbps = 320, sampleRateHz = 44_100, bitsPerSample = 0, fileExtension = "m4a"),
        confidence = 0.85f,
        sourceTrackId = "td:3717530",
    )

    private companion object {
        const val VIDEO_ID = "0xWKpAqtzrY"
        const val SAVED_AAC = "/library/neil-halstead/paint-a-face.m4a"
    }
}
