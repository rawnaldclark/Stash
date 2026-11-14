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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * The YouTube rung fetches the album cover WHILE yt-dlp downloads (it used to
 * wait for the download to finish first), and that fetch never outlives the
 * attempt that started it: a download that fails, throws or is cancelled (the
 * user's Stop, a lost Wi-Fi constraint) stops its cover fetch too.
 *
 * Real threads and real time ([runBlocking]): the fetch runs on the manager's
 * own scope, outside any test scheduler.
 */
class DownloadManagerArtOverlapTest {

    @get:Rule val tmp = TemporaryFolder()

    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val fileOrganizer: FileOrganizer = mockk(relaxed = true)
    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val metadataEmbedder: MetadataEmbedder = mockk(relaxed = true)
    private val albumArtCache: AlbumArtCache = mockk()
    private val trackDao: TrackDao = mockk(relaxed = true)
    private lateinit var downloaded: File
    private lateinit var cover: File

    /** The cover fetch began. */
    private val artStarted = CompletableDeferred<Unit>()

    /** The cover fetch is over: it returned, threw, or was cancelled. */
    private val artEnded = CompletableDeferred<Unit>()

    /** Whether the cover fetch ended because it was cancelled. */
    @Volatile private var artCancelled = false

    @Before
    fun setUp() {
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        coEvery { losslessPrefs.downloadModeNow() } returns com.stash.core.model.DownloadMode.OPUS
        coEvery { trackDao.getById(any()) } returns null
        coEvery { fileOrganizer.getTempDir() } returns tmp.root
        coEvery { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            FileOrganizer.CommittedTrack("/music/evanescence-lacrymosa.opus", 5L)
        downloaded = tmp.newFile("dl_7_abcd1234.opus").apply { writeText("audio") }
        cover = tmp.newFile("cover.jpg").apply { writeText("jpeg") }
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
        albumArtCache = albumArtCache,
        lyricsFetchTrigger = mockk<LyricsFetchTrigger>(relaxed = true),
        audioDurationExtractor = mockk(relaxed = true),
        losslessHealthGate = mockk(relaxed = true),
        localFileOps = mockk<com.stash.core.data.files.LocalFileOps> { every { acceptDownloadOrDelete(any()) } returns true },
        losslessQuota = com.stash.data.download.lossless.quota.LosslessQuota.inMemory(),
    )

    /** A track whose audio the user picked: straight to the YouTube rung. */
    private val track = Track(
        id = 7L,
        title = "Lacrymosa",
        artist = "Evanescence",
        album = "Synthesis",
        albumArtUrl = "https://i.scdn.co/image/synthesis",
        youtubeId = "pickedVideo",
        matchPickedAt = 1_000L,
    )

    /** A cover fetch that never answers: it ends only when something cancels it. */
    private fun coverNeverArrives() {
        coEvery { albumArtCache.resolveArt(any()) } coAnswers {
            artStarted.complete(Unit)
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                artCancelled = true
                throw cancelled
            } finally {
                artEnded.complete(Unit)
            }
        }
    }

    /** yt-dlp answers [result], but only once the cover fetch has begun (or [WAIT_MS] have passed). */
    private fun ytDlpAnswersOnceTheCoverFetchBegan(result: () -> DownloadResult) {
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } coAnswers {
            withTimeoutOrNull(WAIT_MS) { artStarted.await() }
            result()
        }
    }

    private suspend fun assertCoverFetchWasCancelled() {
        assertNotNull(
            "the cover fetch was left running after its download ended",
            withTimeoutOrNull(WAIT_MS) { artEnded.await() },
        )
        assertTrue("the cover fetch ended, but not by being cancelled", artCancelled)
    }

    @Test
    fun `the cover is fetched while yt-dlp is still downloading`() = runBlocking(Dispatchers.Default) {
        coEvery { albumArtCache.resolveArt(any()) } coAnswers {
            artStarted.complete(Unit)
            cover
        }
        var coverFetchBeganDuringDownload = false
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } coAnswers {
            coverFetchBeganDuringDownload = withTimeoutOrNull(WAIT_MS) { artStarted.await() } != null
            DownloadResult.Success(downloaded)
        }

        val result = newSubject().downloadTrack(track)

        assertTrue("the cover fetch waited for the download to finish", coverFetchBeganDuringDownload)
        assertTrue("got $result", result is TrackDownloadResult.Success)
        // The cover fetched alongside is the one the tags get.
        coVerify(exactly = 1) { metadataEmbedder.embedMetadata(downloaded, any(), cover) }
        coVerify(exactly = 1) { albumArtCache.resolveArt(any()) }
    }

    @Test
    fun `a cover that fails to load still saves the song, without art`() = runBlocking(Dispatchers.Default) {
        coEvery { albumArtCache.resolveArt(any()) } throws IOException("art server down")
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns DownloadResult.Success(downloaded)

        val result = newSubject().downloadTrack(track)

        assertTrue("got $result", result is TrackDownloadResult.Success)
        coVerify(exactly = 1) { metadataEmbedder.embedMetadata(downloaded, any(), null) }
    }

    @Test
    fun `a failed download stops its cover fetch and does not wait for it`() = runBlocking(Dispatchers.Default) {
        coverNeverArrives()
        ytDlpAnswersOnceTheCoverFetchBegan { DownloadResult.YtDlpError("Video unavailable") }

        val subject = newSubject()

        val result = withTimeoutOrNull(WAIT_MS) { subject.downloadTrack(track) }

        assertTrue("got $result (null: the failure waited for the cover)", result is TrackDownloadResult.Failed)
        assertCoverFetchWasCancelled()
    }

    @Test
    fun `a download cancelled while yt-dlp runs stops its cover fetch`() = runBlocking(Dispatchers.Default) {
        coverNeverArrives()
        val ytDlpRunning = CompletableDeferred<Unit>()
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } coAnswers {
            withTimeoutOrNull(WAIT_MS) { artStarted.await() }
            ytDlpRunning.complete(Unit)
            awaitCancellation()
        }
        val subject = newSubject()

        val download = launch { subject.downloadTrack(track) }
        ytDlpRunning.await()
        download.cancelAndJoin()

        assertCoverFetchWasCancelled()
    }

    @Test
    fun `a download cancelled while its cover is still loading stops the fetch and keeps nothing`() =
        runBlocking(Dispatchers.Default) {
            coverNeverArrives()
            // yt-dlp is done; the attempt now waits for the cover, to tag the file with it.
            val ytDlpDone = CompletableDeferred<Unit>()
            coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } coAnswers {
                withTimeoutOrNull(WAIT_MS) { artStarted.await() }
                ytDlpDone.complete(Unit)
                DownloadResult.Success(downloaded)
            }
            val subject = newSubject()

            val download = launch { subject.downloadTrack(track) }
            ytDlpDone.await()
            download.cancelAndJoin()

            assertCoverFetchWasCancelled()
            assertFalse("the temp file of a cancelled download stayed", downloaded.exists())
            coVerify(exactly = 0) { fileOrganizer.commitDownload(any(), any(), any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `a download that throws stops its cover fetch`() = runBlocking(Dispatchers.Default) {
        coverNeverArrives()
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } coAnswers {
            withTimeoutOrNull(WAIT_MS) { artStarted.await() }
            throw IOException("No space left on device")
        }

        try {
            newSubject().downloadTrack(track)
            fail("the failure propagates")
        } catch (expected: IOException) {
        }

        assertCoverFetchWasCancelled()
    }

    private companion object {
        /** Longest any step here waits for the other side, in real time; only a failing run waits it out. */
        const val WAIT_MS = 10_000L
    }
}
