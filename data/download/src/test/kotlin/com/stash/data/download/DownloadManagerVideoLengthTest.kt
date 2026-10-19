package com.stash.data.download

import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.model.MusicSource
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
import com.stash.data.download.lossless.TrackQuery
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A YouTube music-video row's length is the video's ([TrackQuery.durationReliable] false). The
 * catalog matcher allows it 40% off when it picks the Tidal track, and the Tidal AAC file is held
 * to the same 40% ([lossyAacLengthMatches]), so the file of the track streaming plays for the row
 * is kept. It used to be held to max(8 s, 3%) and then skipped outright, so "Paint a Face" (a 3:00
 * video, a 2:49 Tidal track) streamed as Tidal AAC but downloaded as YouTube Opus. A row whose
 * length is the song's keeps the tight window, and JioSaavn, whose matcher applies that window
 * before anything is fetched, keeps it for every row.
 */
class DownloadManagerVideoLengthTest {

    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true) {
        every { qualityTier } returns flowOf(QualityTier.MAX)
    }
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk(relaxed = true)
    private val jioSaavnResolver: JioSaavnResolver = mockk { coEvery { resolve(any(), any()) } returns null }
    private val tidalAacResolver: TidalAacResolver = mockk { coEvery { resolve(any()) } returns null }
    private val audioDurationExtractor: com.stash.core.data.audio.AudioDurationExtractor = mockk(relaxed = true)
    private val trackFinalizer: TrackFinalizer = mockk {
        coEvery { finalizeFile(any(), any(), any()) } returns TrackFinalizer.FinalizeResult.Success(
            FileOrganizer.CommittedTrack(filePath = SAVED, sizeBytes = 1L),
            meta = null,
        )
    }

    private fun newSubject(): DownloadManager = DownloadManager(
        downloadExecutor = mockk<DownloadExecutor>(relaxed = true),
        searchExecutor = mockk<HybridSearchExecutor>(relaxed = true),
        albumMatchExecutor = mockk<AlbumMatchExecutor>(relaxed = true),
        matchScorer = mockk<MatchScorer>(relaxed = true),
        duplicateDetection = mockk<DuplicateDetectionService>(relaxed = true),
        fileOrganizer = mockk<FileOrganizer>(relaxed = true) {
            coEvery { getTempDir() } returns File.createTempFile("videolength", "").apply { delete(); mkdirs() }
        },
        qualityPrefs = qualityPrefs,
        ytLibraryCanonicalizer = mockk<YtLibraryCanonicalizer>(relaxed = true),
        trackDao = mockk<TrackDao>(relaxed = true) { coEvery { getById(any()) } returns null },
        playlistDao = mockk<PlaylistDao>(relaxed = true),
        lastFmApiClient = mockk<LastFmApiClient>(relaxed = true),
        lastFmCredentials = mockk<LastFmCredentials>(relaxed = true),
        losslessRegistry = mockk<LosslessSourceRegistry>(relaxed = true),
        losslessUrlDownloader = losslessUrlDownloader,
        losslessPrefs = mockk<LosslessSourcePreferences>(relaxed = true),
        jioSaavnResolver = jioSaavnResolver,
        tidalAacResolver = tidalAacResolver,
        trackFinalizer = trackFinalizer,
        loudnessMeasurer = mockk<com.stash.core.data.audio.LoudnessMeasurer>(relaxed = true),
        metadataEmbedder = mockk<MetadataEmbedder>(relaxed = true),
        albumArtCache = mockk<AlbumArtCache>(relaxed = true),
        lyricsFetchTrigger = mockk<LyricsFetchTrigger>(relaxed = true),
        audioDurationExtractor = audioDurationExtractor,
        losslessHealthGate = mockk<com.stash.data.download.lossless.LosslessSourceHealthGate>(relaxed = true),
        localFileOps = mockk<com.stash.core.data.files.LocalFileOps> { every { acceptDownloadOrDelete(any()) } returns true },
        losslessQuota = com.stash.data.download.lossless.quota.LosslessQuota.inMemory(),
    )

    private fun youTubeRow(album: String = "", musicVideoType: String? = null) = Track(
        id = 7L,
        title = "Artist - Song (Official Video)",
        artist = "ArtistVEVO",
        album = album,
        durationMs = 280_000L,
        source = MusicSource.YOUTUBE,
        musicVideoType = musicVideoType,
    )

    /** Tidal answers with its AAC, and the fetched file measures [fileMs] at 320 kbps. */
    private fun tidalServes(fileMs: Long) {
        coEvery { tidalAacResolver.resolve(any()) } returns SourceResult(
            sourceId = TidalAacResolver.SOURCE_ID,
            downloadUrl = "https://sp-ad-cf.audio.tidal.com/x.mp4",
            format = AudioFormat(codec = "aac", bitrateKbps = 320, fileExtension = "m4a"),
            confidence = 0.85f,
        )
        coEvery { losslessUrlDownloader.download(any(), any(), any()) } answers {
            Result.success(secondArg<File>().apply { writeText("fake-m4a-bytes") })
        }
        every { audioDurationExtractor.extract(any()) } returns AudioMetadata(durationMs = fileMs, bitrateKbps = 320, format = "aac")
    }

    @Test
    fun `a YouTube music-video row asks Tidal for AAC with its length marked unreliable`() = runTest {
        val asked = slot<TrackQuery>()
        coEvery { tidalAacResolver.resolve(capture(asked)) } returns null

        newSubject().tryTidalAacDownload(youTubeRow(musicVideoType = "OMV"))

        assertFalse(asked.captured.durationReliable)
        assertEquals(280_000L, asked.captured.durationMs)
    }

    @Test
    fun `a music-video row keeps a Tidal AAC file shorter than the video`() = runTest {
        // A 3:50 song for a 4:40 video: 18% off, inside the matcher's 40%.
        tidalServes(fileMs = 230_000L)

        val result = newSubject().tryTidalAacDownload(youTubeRow())

        assertEquals(TrackDownloadResult.Success(SAVED), result)
    }

    @Test
    fun `a music-video row still rejects a preview stub or another recording`() = runTest {
        tidalServes(fileMs = 30_000L)
        assertNull(newSubject().tryTidalAacDownload(youTubeRow()))

        tidalServes(fileMs = 420_000L) // 7:00 for a 4:40 video: 50% off
        assertNull(newSubject().tryTidalAacDownload(youTubeRow()))
    }

    @Test
    fun `an official audio row keeps the tight window`() = runTest {
        val asked = slot<TrackQuery>()
        tidalServes(fileMs = 230_000L)
        coEvery { tidalAacResolver.resolve(capture(asked)) } returns null

        newSubject().tryTidalAacDownload(youTubeRow(musicVideoType = "ATV"))

        assertTrue(asked.captured.durationReliable)
        assertEquals("Artist", asked.captured.artist)
        assertEquals("Song", asked.captured.title)

        // The ATV row's length is the song's: a file 50 s off it is another recording.
        tidalServes(fileMs = 230_000L)
        assertNull(newSubject().tryTidalAacDownload(youTubeRow(musicVideoType = "ATV")))
        tidalServes(fileMs = 275_000L)
        assertEquals(TrackDownloadResult.Success(SAVED), newSubject().tryTidalAacDownload(youTubeRow(musicVideoType = "ATV")))
    }

    @Test
    fun `a Spotify row asks Tidal for AAC with its length trusted`() = runTest {
        val asked = slot<TrackQuery>()
        coEvery { tidalAacResolver.resolve(capture(asked)) } returns null
        val spotify = Track(id = 8L, title = "Song", artist = "Artist", durationMs = 210_000L, source = MusicSource.SPOTIFY)

        newSubject().tryTidalAacDownload(spotify)

        assertTrue(asked.captured.durationReliable)
    }

    @Test
    fun `the length rule, both ways`() {
        // Song length: max(8 s, 3%).
        assertTrue(lossyAacLengthMatches(probedMs = 207_000L, expectedMs = 200_000L, lengthReliable = true))
        assertFalse(lossyAacLengthMatches(probedMs = 209_000L, expectedMs = 200_000L, lengthReliable = true))
        // Video length: under 40%. The owner's song: a 169 s file for a 180 s video.
        assertTrue(lossyAacLengthMatches(probedMs = 168_994L, expectedMs = 180_000L, lengthReliable = false))
        assertFalse(lossyAacLengthMatches(probedMs = 168_994L, expectedMs = 180_000L, lengthReliable = true))
        assertFalse(lossyAacLengthMatches(probedMs = 30_000L, expectedMs = 180_000L, lengthReliable = false))
        assertFalse(lossyAacLengthMatches(probedMs = 252_000L, expectedMs = 180_000L, lengthReliable = false))
        // Unknown expected length: any file; unreadable file: never.
        assertTrue(lossyAacLengthMatches(probedMs = 1_000L, expectedMs = 0L, lengthReliable = true))
        assertFalse(lossyAacLengthMatches(probedMs = null, expectedMs = 180_000L, lengthReliable = false))
    }

    @Test
    fun `a preview-length file needs the tight window even for a video row`() {
        // 30 s against a 45 s video is 33% off, inside the 40%, yet it is a preview.
        assertFalse(lossyAacLengthMatches(probedMs = 30_000L, expectedMs = 45_000L, lengthReliable = false))
        // A genuinely short song still passes when its length agrees.
        assertTrue(lossyAacLengthMatches(probedMs = 31_000L, expectedMs = 33_000L, lengthReliable = false))
    }

    @Test
    fun `JioSaavn still runs for a YouTube video row`() = runTest {
        val asked = slot<TrackQuery>()
        coEvery { jioSaavnResolver.resolve(capture(asked), any()) } returns null

        newSubject().tryJioSaavnDownload(youTubeRow())

        assertFalse(asked.captured.durationReliable)
        assertEquals(280_000L, asked.captured.durationMs)
    }

    @Test
    fun `the FLAC backstop keeps a full song shorter than the video`() {
        // A 3:30 FLAC for a 4:40 video row is a song, not a preview stub.
        assertFalse(isLosslessDurationDegraded(probedMs = 210_000L, expectedMs = 280_000L))
        assertTrue(isLosslessDurationDegraded(probedMs = 30_000L, expectedMs = 280_000L))
    }

    private companion object {
        const val SAVED = "/library/artist/song.m4a"
    }
}
