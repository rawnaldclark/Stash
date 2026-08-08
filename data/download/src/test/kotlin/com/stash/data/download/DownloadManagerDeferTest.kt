package com.stash.data.download

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
import com.stash.data.download.lyrics.LyricsFetchTrigger
import com.stash.data.download.jiosaavn.JioSaavnResolver
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.lossless.LosslessUrlDownloader
import com.stash.data.download.matching.AlbumMatchExecutor
import com.stash.data.download.matching.HybridSearchExecutor
import com.stash.data.download.matching.MatchScorer
import com.stash.data.download.matching.DuplicateDetectionService
import com.stash.data.download.matching.YtLibraryCanonicalizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the v0.9.17 deferral branch in [DownloadManager.executeDownload].
 *
 * When the lossless registry returns null AND
 * [LosslessSourcePreferences.youtubeFallbackEnabledNow] is false, the
 * pipeline must short-circuit with [TrackDownloadResult.Deferred] rather
 * than falling through to yt-dlp. Stash-Mix tracks (force-lossless)
 * always bypass deferral so the curated rotating playlist never empties.
 *
 * Kept in its own file (not co-located with future DownloadManager tests)
 * to keep the test-fixture blast radius small for this single behavior
 * change.
 */
class DownloadManagerDeferTest {

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
    private val losslessUrlDownloader: LosslessUrlDownloader = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val jioSaavnResolver: JioSaavnResolver = mockk {
        coEvery { resolve(any(), any()) } returns null
    }
    private val trackFinalizer: TrackFinalizer = mockk(relaxed = true)
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
    ).apply {
        // Under testDebugUnitTest, BuildConfig.DEBUG is unconditionally true,
        // which would otherwise make the strict-FLAC Deferred branch under
        // test permanently unreachable. Force the real (release) behavior.
        forceYoutubeFallbackOnDebugBuilds = false
    }

    private fun stubTrack(): Track = Track(
        id = 42L,
        title = "Sample",
        artist = "Sample Artist",
    )

    @Test
    fun `registry-null + fallback-off returns Deferred`() = runTest {
        // Lossless on, fallback off, registry returns null, NOT a Stash Mix track.
        coEvery { losslessPrefs.enabledNow() } returns true
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns false
        coEvery { losslessRegistry.resolve(any()) } returns null
        coEvery { playlistDao.isTrackInStashMix(any()) } returns false

        val result = newSubject().downloadTrack(track = stubTrack(), preResolvedUrl = null)

        assertTrue("expected Deferred, got $result", result is TrackDownloadResult.Deferred)
    }

    @Test
    fun `registry-null + fallback-on falls through to yt-dlp path`() = runTest {
        // Fallback on — even when registry returns null, the pipeline should
        // proceed past the deferral check into the yt-dlp branch.
        coEvery { losslessPrefs.enabledNow() } returns true
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns true
        coEvery { losslessRegistry.resolve(any()) } returns null
        coEvery { playlistDao.isTrackInStashMix(any()) } returns false
        // Force resolveUrl to bail with no match (Unmatched). That's enough
        // to prove we reached the yt-dlp branch — we just need to NOT see
        // Deferred. Stub all search paths to return empty so resolveUrl
        // hits its "all strategies failed" exit.
        coEvery { albumMatchExecutor.findTrackInAlbum(any(), any(), any(), any()) } returns null
        coEvery { searchExecutor.search(any(), any()) } returns emptyList()
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns emptyList()

        val result = newSubject().downloadTrack(track = stubTrack(), preResolvedUrl = null)

        assertFalse("did not defer, got $result", result is TrackDownloadResult.Deferred)
    }

    @Test
    fun `fallback-off + Spotify track WITH preResolvedUrl defers (does not download lossy)`() = runTest {
        // THE LEAK: a Spotify track acquires a youtube_id from match-for-
        // playback, which the queue turns into a youtubeUrl → preResolvedUrl.
        // Pre-fix, the preResolvedUrl carve-out skipped the defer and the
        // track downloaded as lossy mp4 even with fallback OFF (671 such
        // tracks observed on-device). A SPOTIFY-source track's youtube_id is
        // a match, NOT a user opt-in to YouTube, so it must defer.
        coEvery { losslessPrefs.enabledNow() } returns true
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns false
        coEvery { losslessRegistry.resolve(any()) } returns null
        coEvery { playlistDao.isTrackInStashMix(any()) } returns false
        // Stub the fall-through path so that, PRE-fix, the test fails on the
        // assertion (returns a non-Deferred result) rather than throwing
        // inside the yt-dlp branch.
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns
            DownloadResult.Error("test-stop")

        val result = newSubject().downloadTrack(
            track = stubTrack(), // source defaults to SPOTIFY
            preResolvedUrl = "https://music.youtube.com/watch?v=abc",
        )

        assertTrue(
            "Spotify track with a youtube_id match must defer when fallback off, got $result",
            result is TrackDownloadResult.Deferred,
        )
    }

    @Test
    fun `fallback-off + YouTube-source track WITH preResolvedUrl now defers (no lossy YouTube download)`() = runTest {
        // Fallback off means NO opus/m4a from the YouTube path — period. A
        // genuinely YouTube-sourced track (source = YOUTUBE) used to fall
        // through to yt-dlp here via the youtubeOptIn carve-out; that carve-out
        // is removed so it defers like every other non-Stash-Mix track,
        // matching SearchDownloadCoordinator's unconditional gate.
        coEvery { losslessPrefs.enabledNow() } returns true
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns false
        coEvery { losslessRegistry.resolve(any()) } returns null
        coEvery { playlistDao.isTrackInStashMix(any()) } returns false

        val result = newSubject().downloadTrack(
            track = stubTrack().copy(source = MusicSource.YOUTUBE),
            preResolvedUrl = "https://music.youtube.com/watch?v=abc",
        )

        assertTrue(
            "YouTube-source track must defer when fallback off, got $result",
            result is TrackDownloadResult.Deferred,
        )
    }

    @Test
    fun `registry-null + fallback-off + Stash Mix track does NOT defer`() = runTest {
        // Stash-Mix tracks force lossless but should NOT defer when registry
        // returns null — they fall through to yt-dlp so the curated mix never
        // empties because of a transient source outage.
        coEvery { losslessPrefs.enabledNow() } returns false  // global toggle off, but force-lossless still kicks in
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns false
        coEvery { losslessRegistry.resolve(any()) } returns null
        coEvery { playlistDao.isTrackInStashMix(any()) } returns true  // forceLossless = true
        coEvery { albumMatchExecutor.findTrackInAlbum(any(), any(), any(), any()) } returns null
        coEvery { searchExecutor.search(any(), any()) } returns emptyList()
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns emptyList()

        val result = newSubject().downloadTrack(track = stubTrack(), preResolvedUrl = null)

        assertFalse("Stash-Mix track must not defer, got $result", result is TrackDownloadResult.Deferred)
    }
}
