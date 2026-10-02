package com.stash.data.download

import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.sync.TrackMatcher
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
import com.stash.data.download.ytdlp.YtDlpSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tests [DownloadManager.resolveUrl]'s checks on search candidates, through the
 * public [DownloadManager.downloadTrack]: the real [MatchScorer] ranks stubbed
 * search rows, and yt-dlp answers [DownloadResult.Error] so the pipeline stops
 * right after the URL is chosen (the [DownloadManagerDeferTest] trick). Each test
 * asserts which URL reached yt-dlp.
 *
 * Kept in its own file, like [DownloadManagerDeferTest], to keep the fixture's
 * blast radius small.
 */
class DownloadManagerMatchVerifyTest {

    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true) {
        coEvery { download(any(), any(), any(), any(), any()) } returns DownloadResult.Error("test-stop")
    }

    // The catch-alls come first: MockK answers with the last matching stub, so a
    // test's own stubs win.
    private val searchExecutor: HybridSearchExecutor = mockk(relaxed = true) {
        coEvery { search(any(), any()) } returns emptyList()
        coEvery { searchYtDlpDirect(any(), any()) } returns emptyList()
        // A failed lookup. Relaxed, it would answer a mock with a blank title.
        coEvery { verifyVideo(any()) } returns null
    }

    // Relaxed, these would answer mock rows: a fake album match, a fake refreshed track.
    private val albumMatchExecutor: AlbumMatchExecutor = mockk(relaxed = true) {
        coEvery { findTrackInAlbum(any(), any(), any(), any()) } returns null
    }
    private val trackDao: TrackDao = mockk(relaxed = true) {
        coEvery { getById(any()) } returns null
    }

    private val qualityPrefs: QualityPreferencesManager = mockk(relaxed = true) {
        every { qualityTier } returns flowOf(QualityTier.MAX)
    }
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true) {
        coEvery { enabledNow() } returns false
    }
    private val jioSaavnResolver: JioSaavnResolver = mockk {
        coEvery { resolve(any(), any()) } returns null
    }

    private fun newSubject(search: HybridSearchExecutor = searchExecutor): DownloadManager = DownloadManager(
        downloadExecutor = downloadExecutor,
        searchExecutor = search,
        albumMatchExecutor = albumMatchExecutor,
        matchScorer = MatchScorer(TrackMatcher()),
        duplicateDetection = mockk<DuplicateDetectionService>(relaxed = true),
        fileOrganizer = mockk<FileOrganizer>(relaxed = true),
        qualityPrefs = qualityPrefs,
        ytLibraryCanonicalizer = mockk<YtLibraryCanonicalizer>(relaxed = true),
        trackDao = trackDao,
        playlistDao = mockk<PlaylistDao>(relaxed = true),
        lastFmApiClient = mockk<LastFmApiClient>(relaxed = true),
        lastFmCredentials = mockk<LastFmCredentials>(relaxed = true),
        losslessRegistry = mockk<LosslessSourceRegistry>(relaxed = true),
        losslessUrlDownloader = mockk<LosslessUrlDownloader>(relaxed = true),
        losslessPrefs = losslessPrefs,
        jioSaavnResolver = jioSaavnResolver,
        trackFinalizer = mockk<TrackFinalizer>(relaxed = true),
        loudnessMeasurer = mockk<com.stash.core.data.audio.LoudnessMeasurer>(relaxed = true),
        metadataEmbedder = mockk<MetadataEmbedder>(relaxed = true),
        albumArtCache = mockk<AlbumArtCache>(relaxed = true),
        lyricsFetchTrigger = mockk<LyricsFetchTrigger>(relaxed = true),
        audioDurationExtractor = mockk<com.stash.core.data.audio.AudioDurationExtractor>(relaxed = true),
        losslessHealthGate = mockk<com.stash.data.download.lossless.LosslessSourceHealthGate>(relaxed = true),
    )

    private fun watch(id: String) = "https://www.youtube.com/watch?v=$id"

    /** A YouTube Music search row as a signed-out search returns it: no length, no view count. */
    private fun ytmRow(id: String, title: String, artist: String) = YtDlpSearchResult(
        id = id,
        title = title,
        uploader = artist,
        channel = artist,
        duration = 0.0,
        viewCount = 0,
        webpageUrl = watch(id),
    )

    @Test
    fun `a percent sign in the song's name does not break its match`() = runTest {
        // The accept line's log text went through format() with the title inside it,
        // so "% P" read as a format conversion and threw.
        val track = Track(id = 9L, title = "100% Pure Love", artist = "Crystal Waters")
        coEvery { searchExecutor.search(any(), any()) } returns
            listOf(ytmRow("pct", "100% Pure Love", "Crystal Waters"))

        newSubject().downloadTrack(track)

        coVerify(exactly = 1) { downloadExecutor.download(watch("pct"), any(), any(), any(), any()) }
    }
}
