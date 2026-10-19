package com.stash.data.download.search

import android.content.Context
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.QualityPreference
import com.stash.core.model.DownloadMode
import com.stash.core.model.MusicSource
import com.stash.core.model.QualityTier
import com.stash.core.model.TrackItem
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.quota.LosslessQuota
import com.stash.data.download.lossless.tidal.TidalAacResolver
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The search-result Download button's lossy decision table in [SearchDownloadCoordinator] (Search,
 * an artist page, an album page). Lossless is on and found no FLAC in every case; the table is
 * the one [com.stash.data.download.DownloadManagerAacDecisionTest] holds the queue path to:
 *
 * | fallback | allowance | Tidal AAC | result                                       |
 * |----------|-----------|-----------|----------------------------------------------|
 * | off      | spent     | any       | WaitingForLossless; no lossy rung is asked   |
 * | off      | left      | has it    | Tidal AAC                                    |
 * | off      | left      | none      | WaitingForLossless; no JioSaavn, no yt-dlp   |
 * | on       | any       | has it    | Tidal AAC, before JioSaavn and YouTube       |
 * | on       | left      | none      | JioSaavn, then YouTube                       |
 *
 * Over three kinds of result: the owner's "Paint a Face" music video (3:00) after it was played
 * (its library row exists), the same video never played (no row), and a song-shelf result with
 * an album (its length is the song's). Tidal's file is the 2:49 album version in every case.
 */
class SearchDownloadCoordinatorAacDecisionTest {

    private val now = 1_792_238_400_000L
    private val losslessQuota = LosslessQuota.inMemory { now }
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val tidalAacResolver: TidalAacResolver = mockk()
    private val registry: LosslessSourceRegistry = mockk { coEvery { resolve(any()) } returns null }
    private val jioSaavnResolver: JioSaavnResolver = mockk { coEvery { resolve(any(), any()) } returns null }
    private val downloadExecutor: DownloadExecutor = mockk {
        coEvery { download(any(), any(), any(), any(), any()) } returns DownloadResult.Error("yt-dlp reached")
    }
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk {
        coEvery { download(any(), any(), any()) } coAnswers { Result.success(arg<File>(1)) }
    }
    private val audioDurationExtractor: com.stash.core.data.audio.AudioDurationExtractor = mockk {
        every { extract(any()) } returns AudioMetadata(durationMs = 168_994L, bitrateKbps = 320, format = "aac")
    }
    private val trackFinalizer: TrackFinalizer = mockk {
        coEvery { finalizeFile(any(), any(), any(), any()) } returns TrackFinalizer.FinalizeResult.Success(
            committed = CommittedTrack(filePath = "/library/neil-halstead/paint-a-face.m4a", sizeBytes = 6_700_000L),
            meta = AudioMetadata(durationMs = 168_994L, bitrateKbps = 320, format = "aac"),
        )
    }
    private val musicRepository: com.stash.core.data.repository.MusicRepository = mockk(relaxed = true) {
        coEvery { ensureTrackPersisted(any()) } returns 4242L
    }
    private val downloadQueueDao: com.stash.core.data.db.dao.DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true) {
        coEvery { markAsDownloaded(any(), any(), any(), any(), any(), any()) } returns 1
    }
    private val context: Context = mockk(relaxed = true) {
        every { cacheDir } returns File(System.getProperty("java.io.tmpdir"), "stash-aac-decision").apply { mkdirs() }
    }

    private fun newSubject() = SearchDownloadCoordinator(
        registry = registry,
        previewCache = mockk(relaxed = true),
        httpDataSourceFactory = mockk(relaxed = true),
        cacheKeyFactory = mockk(relaxed = true),
        downloadExecutor = downloadExecutor,
        trackFinalizer = trackFinalizer,
        trackDao = trackDao,
        musicRepository = musicRepository,
        blocklistGuard = mockk(relaxed = true),
        context = context,
        losslessPrefs = losslessPrefs,
        jioSaavnResolver = jioSaavnResolver,
        tidalAacResolver = tidalAacResolver,
        losslessUrlDownloader = losslessUrlDownloader,
        audioDurationExtractor = audioDurationExtractor,
        downloadQueueDao = downloadQueueDao,
        localFileOps = mockk(relaxed = true) { every { acceptDownloadOrDelete(any()) } returns true },
        loudnessMeasurer = mockk(relaxed = true),
        lyricsFetchTrigger = mockk(relaxed = true),
        qualityPrefs = mockk<QualityPreference> { every { qualityTier } returns flowOf(QualityTier.MAX) },
        losslessQuota = losslessQuota,
    )

    /** What was tapped, and the library row streaming made for it, if it was played. */
    private enum class Tapped(val item: TrackItem, val row: TrackEntity?) {
        PLAYED_MUSIC_VIDEO(
            TrackItem(videoId = VIDEO_ID, title = "Paint a Face", artist = "Neil Halstead", durationSeconds = 180.0, thumbnailUrl = null),
            TrackEntity(
                id = 990_649L, title = "Paint a Face", artist = "Neil Halstead", youtubeId = VIDEO_ID,
                canonicalTitle = "paint a face", canonicalArtist = "neil halstead",
                durationMs = 180_000L, source = MusicSource.YOUTUBE, albumArtUrl = null,
            ),
        ),
        NEW_MUSIC_VIDEO(
            TrackItem(videoId = VIDEO_ID, title = "Paint a Face", artist = "Neil Halstead", durationSeconds = 180.0, thumbnailUrl = null),
            null,
        ),
        SONG_WITH_ALBUM(
            TrackItem(
                videoId = SONG_ID, title = "Paint a Face", artist = "Neil Halstead", durationSeconds = 170.0,
                thumbnailUrl = null, album = "Oh! Mighty Engine",
            ),
            null,
        ),
    }

    private fun arrange(
        result: Tapped,
        fallbackOn: Boolean,
        tidalHasAac: Boolean,
        allowanceSpent: Boolean,
        mode: DownloadMode = DownloadMode.LOSSLESS,
    ) {
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
        coEvery { trackDao.findByYoutubeId(result.item.videoId) } returns result.row
        losslessQuota.onHeaders(if (allowanceSpent) "500/500" else "10/500", "${now / 1000 + 3600}")
    }

    private suspend fun download(result: Tapped) = newSubject().download(result.item).toList()

    private fun List<SearchDownloadStatus>.savedTidalAac() =
        contains(SearchDownloadStatus.Downloading(SearchDownloadStatus.Source.TIDAL_AAC)) &&
            last() is SearchDownloadStatus.Completed

    @Test
    fun `fallback off + allowance spent waits, and asks no lossy rung`() = runTest {
        for (result in Tapped.values()) {
            arrange(result, fallbackOn = false, tidalHasAac = true, allowanceSpent = true)

            val statuses = download(result)

            assertEquals("$result", SearchDownloadStatus.WaitingForAllowance, statuses.last())
        }
        coVerify(exactly = 0) { tidalAacResolver.resolve(any(), any(), any()) }
        coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fallback off + Tidal has AAC saves Tidal AAC for every kind of result`() = runTest {
        for (result in Tapped.values()) {
            arrange(result, fallbackOn = false, tidalHasAac = true, allowanceSpent = false)

            val statuses = download(result)

            assertTrue("$result: expected Tidal AAC, got $statuses", statuses.savedTidalAac())
        }
        coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fallback off + no Tidal AAC waits, never JioSaavn or YouTube`() = runTest {
        for (result in Tapped.values()) {
            arrange(result, fallbackOn = false, tidalHasAac = false, allowanceSpent = false)

            assertEquals("$result", SearchDownloadStatus.WaitingForLossless, download(result).last())
        }
        coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fallback off + a Tidal AAC preview stub waits, never YouTube`() = runTest {
        every { audioDurationExtractor.extract(any()) } returns AudioMetadata(durationMs = 30_000L, bitrateKbps = 320, format = "aac")
        for (result in Tapped.values()) {
            arrange(result, fallbackOn = false, tidalHasAac = true, allowanceSpent = false)

            assertEquals("$result", SearchDownloadStatus.WaitingForLossless, download(result).last())
        }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fallback on + Tidal has AAC takes it before JioSaavn and YouTube`() = runTest {
        for (result in Tapped.values()) {
            arrange(result, fallbackOn = true, tidalHasAac = true, allowanceSpent = false)

            val statuses = download(result)

            assertTrue("$result: expected Tidal AAC, got $statuses", statuses.savedTidalAac())
        }
        coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `fallback on + allowance spent gets no Tidal AAC and goes to YouTube`() = runTest {
        arrange(Tapped.PLAYED_MUSIC_VIDEO, fallbackOn = true, tidalHasAac = true, allowanceSpent = true)

        val statuses = download(Tapped.PLAYED_MUSIC_VIDEO)

        assertTrue(statuses.contains(SearchDownloadStatus.Downloading(SearchDownloadStatus.Source.YOUTUBE)))
        assertFalse(statuses.savedTidalAac())
    }

    @Test
    fun `AAC mode with the allowance spent - fallback off waits for the reset, on goes to YouTube`() = runTest {
        arrange(Tapped.SONG_WITH_ALBUM, fallbackOn = false, tidalHasAac = true, allowanceSpent = true, mode = DownloadMode.AAC)
        assertEquals(SearchDownloadStatus.WaitingForAllowance, download(Tapped.SONG_WITH_ALBUM).last())

        arrange(Tapped.SONG_WITH_ALBUM, fallbackOn = true, tidalHasAac = true, allowanceSpent = true, mode = DownloadMode.AAC)
        val statuses = download(Tapped.SONG_WITH_ALBUM)
        assertTrue(statuses.contains(SearchDownloadStatus.Downloading(SearchDownloadStatus.Source.YOUTUBE)))
        coVerify(exactly = 0) { registry.resolve(any()) }
    }

    @Test
    fun `fallback on + no Tidal AAC goes on to JioSaavn, then YouTube`() = runTest {
        arrange(Tapped.PLAYED_MUSIC_VIDEO, fallbackOn = true, tidalHasAac = false, allowanceSpent = false)

        val statuses = download(Tapped.PLAYED_MUSIC_VIDEO)

        assertTrue(statuses.contains(SearchDownloadStatus.Downloading(SearchDownloadStatus.Source.YOUTUBE)))
        coVerifyOrder {
            tidalAacResolver.resolve(any(), any(), any())
            jioSaavnResolver.resolve(any(), any())
            downloadExecutor.download(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `Tidal is asked as streaming asks for the video's row, so a video's length is not the song's`() = runTest {
        val asked = mutableListOf<TrackQuery>()
        for (result in Tapped.values()) {
            arrange(result, fallbackOn = false, tidalHasAac = false, allowanceSpent = false)
            coEvery { tidalAacResolver.resolve(capture(asked), any(), any()) } returns com.stash.data.download.lossless.tidal.TidalAac.NoAac
            download(result)
        }

        // The played video: its row's words and length (what TidalStreamResolver asked with).
        assertEquals(990_649L, asked[0].trackId)
        assertEquals(180_000L, asked[0].durationMs)
        assertFalse(asked[0].durationReliable)
        // Never played: the row this download would make, a YouTube video's.
        assertFalse(asked[1].durationReliable)
        // A song with an album: its length is the song's, so the tight window stays.
        assertTrue(asked[2].durationReliable)
    }

    @Test
    fun `a song-shelf result still rejects a Tidal file far off its own length`() = runTest {
        // 2:20 against a 2:50 song: within the matcher's video allowance, not the song window.
        every { audioDurationExtractor.extract(any()) } returns AudioMetadata(durationMs = 140_000L, bitrateKbps = 320, format = "aac")
        arrange(Tapped.SONG_WITH_ALBUM, fallbackOn = false, tidalHasAac = true, allowanceSpent = false)

        assertEquals(SearchDownloadStatus.WaitingForLossless, download(Tapped.SONG_WITH_ALBUM).last())
    }

    @Test
    fun `a row with no length is checked against the search result's length`() = runTest {
        // A stub synced without a length: 0 ms on the row, 2:50 on the search result.
        val stub = Tapped.PLAYED_MUSIC_VIDEO.row!!.copy(durationMs = 0L)
        coEvery { losslessPrefs.downloadModeNow() } returns DownloadMode.LOSSLESS
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns false
        coEvery { tidalAacResolver.resolve(any(), any(), any()) } returns com.stash.data.download.lossless.tidal.TidalAac.Found(tidalAac)
        coEvery { trackDao.findByYoutubeId(VIDEO_ID) } returns stub
        val item = Tapped.PLAYED_MUSIC_VIDEO.item.copy(durationSeconds = 170.0)

        // A 30 s preview against 170 s fails; the full 169 s file passes.
        every { audioDurationExtractor.extract(any()) } returns AudioMetadata(durationMs = 30_000L, bitrateKbps = 320, format = "aac")
        assertEquals(SearchDownloadStatus.WaitingForLossless, newSubject().download(item).toList().last())
        every { audioDurationExtractor.extract(any()) } returns AudioMetadata(durationMs = 168_994L, bitrateKbps = 320, format = "aac")
        assertTrue(newSubject().download(item).toList().savedTidalAac())
    }

    // -- Download quality: AAC 320 and Opus ------------------------------------------------

    @Test
    fun `AAC mode saves Tidal AAC for every kind of result and never asks for FLAC`() = runTest {
        for (result in Tapped.values()) {
            for (fallbackOn in listOf(false, true)) {
                arrange(result, fallbackOn = fallbackOn, tidalHasAac = true, allowanceSpent = false, mode = DownloadMode.AAC)

                val statuses = download(result)

                assertTrue("$result, fallback=$fallbackOn: expected Tidal AAC, got $statuses", statuses.savedTidalAac())
            }
        }
        coVerify(exactly = 0) { registry.resolve(any()) }
        coVerify(exactly = 0) { tidalAacResolver.resolve(any(), aacOnly = false, foreground = any()) }
        coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `AAC mode with no Tidal AAC and the fallback off waits as not available as AAC yet`() = runTest {
        for (result in Tapped.values()) {
            arrange(result, fallbackOn = false, tidalHasAac = false, allowanceSpent = false, mode = DownloadMode.AAC)

            assertEquals("$result", SearchDownloadStatus.WaitingForAac, download(result).last())
        }
        coVerify(exactly = 0) { registry.resolve(any()) }
        coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `AAC mode with no Tidal AAC and the fallback on goes to JioSaavn, then YouTube`() = runTest {
        arrange(Tapped.PLAYED_MUSIC_VIDEO, fallbackOn = true, tidalHasAac = false, allowanceSpent = false, mode = DownloadMode.AAC)

        val statuses = download(Tapped.PLAYED_MUSIC_VIDEO)

        assertTrue(statuses.contains(SearchDownloadStatus.Downloading(SearchDownloadStatus.Source.YOUTUBE)))
        coVerify(exactly = 0) { registry.resolve(any()) }
        coVerifyOrder {
            tidalAacResolver.resolve(any(), aacOnly = true, foreground = any())
            jioSaavnResolver.resolve(any(), any())
            downloadExecutor.download(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `Opus mode downloads from YouTube only`() = runTest {
        for (fallbackOn in listOf(false, true)) {
            arrange(Tapped.SONG_WITH_ALBUM, fallbackOn = fallbackOn, tidalHasAac = true, allowanceSpent = false, mode = DownloadMode.OPUS)

            val statuses = download(Tapped.SONG_WITH_ALBUM)

            assertTrue("fallback=$fallbackOn: $statuses", statuses.contains(SearchDownloadStatus.Downloading(SearchDownloadStatus.Source.YOUTUBE)))
        }
        coVerify(exactly = 0) { registry.resolve(any()) }
        coVerify(exactly = 0) { tidalAacResolver.resolve(any(), any(), any()) }
        coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
    }

    // -- Tidal gave no answer; and every wait is a real queue row ----------------------------

    @Test
    fun `Tidal busy waits as Tidal busy in both modes and both fallbacks, never YouTube`() = runTest {
        for (mode in listOf(DownloadMode.LOSSLESS, DownloadMode.AAC)) {
            for (fallbackOn in listOf(false, true)) {
                arrange(Tapped.SONG_WITH_ALBUM, fallbackOn = fallbackOn, tidalHasAac = true, allowanceSpent = false, mode = mode)
                coEvery { tidalAacResolver.resolve(any(), any(), any()) } returns
                    com.stash.data.download.lossless.tidal.TidalAac.Unavailable(com.stash.data.download.lossless.tidal.TidalAac.Reason.BUSY, 0L)

                assertEquals("$mode, fallback=$fallbackOn", SearchDownloadStatus.WaitingForTidal, download(Tapped.SONG_WITH_ALBUM).last())
            }
        }
        coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a search tap asks Tidal as a foreground lookup`() = runTest {
        arrange(Tapped.SONG_WITH_ALBUM, fallbackOn = false, tidalHasAac = true, allowanceSpent = false, mode = DownloadMode.AAC)
        download(Tapped.SONG_WITH_ALBUM)
        coVerify { tidalAacResolver.resolve(any(), aacOnly = true, foreground = true) }
    }

    @Test
    fun `a wait with no library row makes one, and a waiting queue row the sweep will pick up`() = runTest {
        arrange(Tapped.NEW_MUSIC_VIDEO, fallbackOn = false, tidalHasAac = false, allowanceSpent = false, mode = DownloadMode.AAC)
        coEvery { downloadQueueDao.getByTrackId(4242L) } returns null
        val inserted = slot<com.stash.core.data.db.entity.DownloadQueueEntity>()
        coEvery { downloadQueueDao.insert(capture(inserted)) } returns 1L

        assertEquals(SearchDownloadStatus.WaitingForAac, download(Tapped.NEW_MUSIC_VIDEO).last())

        coVerify { musicRepository.ensureTrackPersisted(any()) }
        assertEquals(4242L, inserted.captured.trackId)
        assertEquals(com.stash.core.model.DownloadStatus.WAITING_FOR_LOSSLESS, inserted.captured.status)
        assertEquals("wait:aac", inserted.captured.errorMessage)
        assertTrue("the user's ask, so the sweep takes it in either mode", inserted.captured.userRequested)
    }

    @Test
    fun `a wait on a song that already has a queue row marks that row, with its reason`() = runTest {
        arrange(Tapped.PLAYED_MUSIC_VIDEO, fallbackOn = false, tidalHasAac = true, allowanceSpent = true, mode = DownloadMode.LOSSLESS)
        coEvery { downloadQueueDao.getByTrackId(990_649L) } returns
            com.stash.core.data.db.entity.DownloadQueueEntity(id = 77L, trackId = 990_649L)

        download(Tapped.PLAYED_MUSIC_VIDEO)

        coVerify { downloadQueueDao.deferForTap(77L, "wait:reset") }
        coVerify(exactly = 0) { downloadQueueDao.insert(any()) }
    }

    @Test
    fun `Opus mode asks yt-dlp for Opus only`() = runTest {
        arrange(Tapped.SONG_WITH_ALBUM, fallbackOn = false, tidalHasAac = true, allowanceSpent = false, mode = DownloadMode.OPUS)
        download(Tapped.SONG_WITH_ALBUM)
        coVerify { downloadExecutor.download(any(), any(), any(), listOf("-f", "251/250/249/bestaudio[acodec=opus]", "--embed-metadata"), any()) }
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
        const val SONG_ID = "aBcDeFgHiJk"
    }
}
