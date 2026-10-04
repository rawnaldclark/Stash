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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #531 review: a track whose audio the user picked in Failed Matches is
 * re-downloaded as exactly that video. The lossless and JioSaavn lookups match
 * by title/artist/album and likely chose the wrong recording in the first
 * place; the YouTube-library canonicalizer would swap the pick for another
 * video. The "YouTube fallback off" setting is still respected.
 */
class DownloadManagerPickedTrackTest {

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

    @Before
    fun setUp() {
        coEvery { losslessRegistry.resolve(any()) } returns null
        every { qualityPrefs.qualityTier } returns flowOf(QualityTier.MAX)
        // End the YouTube rung right after the download call; the URL it got
        // is what these tests are about.
        coEvery { downloadExecutor.download(any(), any(), any(), any(), any()) } returns
            DownloadResult.Error("test-stop")
    }

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
        // BuildConfig.DEBUG is true under unit tests; use release behavior.
        forceYoutubeFallbackOnDebugBuilds = false
    }

    private fun pickedTrack(source: MusicSource = MusicSource.SPOTIFY) = Track(
        id = 7L,
        title = "Lacrymosa",
        artist = "Evanescence",
        album = "Synthesis",
        youtubeId = "pickedVideo",
        source = source,
        matchPickedAt = 1_000L,
    )

    @Test
    fun `a picked track downloads its picked video, not a lossless or JioSaavn match`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns true
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns true

        newSubject().downloadTrack(
            track = pickedTrack(),
            preResolvedUrl = "https://music.youtube.com/watch?v=syncMatch01",
        )

        coVerify(exactly = 0) { losslessRegistry.resolve(any()) }
        coVerify(exactly = 0) { jioSaavnResolver.resolve(any(), any()) }
        coVerify { downloadExecutor.download(match { it.contains("pickedVideo") }, any(), any(), any(), any()) }
        coVerify(exactly = 0) { downloadExecutor.download(match { it.contains("syncMatch01") }, any(), any(), any(), any()) }
    }

    @Test
    fun `a picked YouTube-library track skips the canonicalizer`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false

        newSubject().downloadTrack(track = pickedTrack(source = MusicSource.YOUTUBE), preResolvedUrl = null)

        coVerify(exactly = 0) { ytLibraryCanonicalizer.canonicalize(any(), any()) }
        coVerify { downloadExecutor.download(match { it.contains("pickedVideo") }, any(), any(), any(), any()) }
    }

    @Test
    fun `a picked track still waits when the YouTube fallback is off`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns true
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns false

        val result = newSubject().downloadTrack(track = pickedTrack(), preResolvedUrl = null)

        assertTrue("fallback off means no YouTube download, got $result", result is TrackDownloadResult.Deferred)
        coVerify(exactly = 0) { losslessRegistry.resolve(any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    // A stored or queued id without the YouTube video-id shape is never built
    // into a yt-dlp URL; the track is left unmatched so Failed Matches can find
    // it a real video.

    @Test
    fun `a picked id that isn't a YouTube video id is never handed to yt-dlp`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false

        val result = newSubject().downloadTrack(
            track = pickedTrack().copy(youtubeId = "../x"),
            preResolvedUrl = null,
        )

        assertTrue("expected Unmatched, got $result", result is TrackDownloadResult.Unmatched)
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a stored id that isn't a YouTube video id is never handed to yt-dlp`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false

        listOf(MusicSource.SPOTIFY, MusicSource.YOUTUBE).forEach { source ->
            val result = newSubject().downloadTrack(
                track = pickedTrack(source).copy(youtubeId = "x&list=../y", matchPickedAt = null),
                preResolvedUrl = null,
            )
            assertTrue("expected Unmatched for $source, got $result", result is TrackDownloadResult.Unmatched)
        }
        coVerify(exactly = 0) { ytLibraryCanonicalizer.canonicalize(any(), any()) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a queued URL whose id isn't a YouTube video id is never handed to yt-dlp`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false

        listOf(
            "https://music.youtube.com/watch?v=../x",
            "https://music.youtube.com/watch?v=a%25(title)s",
            "https://other.example/watch?v=dQw4w9WgXcQ",
            "--x",
        ).forEach { url ->
            val result = newSubject().downloadTrack(
                track = pickedTrack().copy(youtubeId = null, matchPickedAt = null),
                preResolvedUrl = url,
            )
            assertTrue("expected Unmatched for $url, got $result", result is TrackDownloadResult.Unmatched)
        }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a queued watch URL with a real video id still downloads`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false

        newSubject().downloadTrack(
            track = pickedTrack().copy(youtubeId = null, matchPickedAt = null),
            preResolvedUrl = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
        )

        coVerify(exactly = 1) {
            downloadExecutor.download("https://music.youtube.com/watch?v=dQw4w9WgXcQ", any(), "dl_7", any(), any())
        }
    }

    @Test
    fun `a queued watch URL reaches yt-dlp rebuilt from its id, on its own YouTube host`() = runTest {
        coEvery { losslessPrefs.enabledNow() } returns false
        val queued = mapOf(
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM1" to "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?t=3&v=V-uIp-WuD60" to "https://www.youtube.com/watch?v=V-uIp-WuD60",
        )

        queued.keys.forEach { url ->
            newSubject().downloadTrack(
                track = pickedTrack().copy(youtubeId = null, matchPickedAt = null),
                preResolvedUrl = url,
            )
        }

        queued.forEach { (url, rebuilt) ->
            coVerify(exactly = 1) { downloadExecutor.download(rebuilt, any(), "dl_7", any(), any()) }
            coVerify(exactly = 0) { downloadExecutor.download(url, any(), any(), any(), any()) }
        }
    }
}
