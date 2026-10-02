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
import com.stash.data.download.matching.InnerTubeSearchExecutor
import com.stash.data.download.matching.MatchScorer
import com.stash.data.download.matching.YouTubeSearchExecutor
import com.stash.data.download.matching.YtLibraryCanonicalizer
import com.stash.data.download.prefs.QualityPreferencesManager
import com.stash.data.download.shared.TrackFinalizer
import com.stash.data.download.ytdlp.YtDlpSearchResult
import com.stash.data.ytmusic.InnerTubeClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [DownloadManager.resolveUrl]'s checks on search candidates, through the
 * public [DownloadManager.downloadTrack]: the real [MatchScorer] ranks stubbed
 * search rows, and yt-dlp answers [DownloadResult.Error] so the pipeline stops
 * right after the URL is chosen (the [DownloadManagerDeferTest] trick). Each test
 * asserts which URL reached yt-dlp.
 *
 * Most tests replay #533: Spotify's "Patient Zero" is 226 s, and its official
 * music video is 312 s. Signed-out YouTube Music rows carry no length, so only
 * the player endpoint's length tells the two apart.
 *
 * Kept in its own file, like [DownloadManagerDeferTest], to keep the fixture's
 * blast radius small.
 */
class DownloadManagerMatchVerifyTest {

    private companion object {
        /** The official music video: 312 s. */
        const val MV = "mw3kSNIxjqo"
        const val MV_PLAYER_TITLE = "Taylor Swift - Patient Zero (Official Music Video)"

        /** The song itself: 226 s. */
        const val AUDIO = "V-uIp-WuD60"

        /** buildSearchQueries' first query for [patientZero]. */
        const val FIRST_QUERY = "Taylor Swift Patient Zero"

        /**
         * A signed-out search answer in the live flat layout: one itemSectionRenderer
         * per row, and a "Song • Artist" subtitle with no length.
         */
        val SIGNED_OUT_SEARCH = """
            {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
              {"itemSectionRenderer":{"contents":[{"musicResponsiveListItemRenderer":{
                "playlistItemData":{"videoId":"$MV"},
                "flexColumns":[
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Patient Zero"}]}}},
                  {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                    {"text":"Song"},
                    {"text":" • "},
                    {"text":"Taylor Swift","navigationEndpoint":{"browseEndpoint":{"browseId":"UCqECaJ8Gagnn7YCbPEzWH6g"}}}
                  ]}}}
                ]
              }}]}}
            ]}}}}]}}}
        """.trimIndent()

        /** The player's answer for the video, as it comes signed out: the length is a string. */
        val MV_PLAYER = """
            {"playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable"},
             "videoDetails":{"videoId":"$MV","title":"$MV_PLAYER_TITLE","lengthSeconds":"312","musicVideoType":"MUSIC_VIDEO_TYPE_UGC"}}
        """.trimIndent()
    }

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

    private val scorer = MatchScorer(TrackMatcher())

    private fun newSubject(search: HybridSearchExecutor = searchExecutor): DownloadManager = DownloadManager(
        downloadExecutor = downloadExecutor,
        searchExecutor = search,
        albumMatchExecutor = albumMatchExecutor,
        matchScorer = scorer,
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

    /** Spotify's "Patient Zero". */
    private val patientZero = Track(
        id = 7L,
        title = "Patient Zero",
        artist = "Taylor Swift",
        album = "The Life of a Showgirl",
        durationMs = 226_000L,
    )

    private fun watch(id: String) = "https://www.youtube.com/watch?v=$id"

    /**
     * A YouTube Music search row as a signed-out search returns it: no length, no
     * view count. Untyped, so a clean title scores 0.75, clear of the 0.60 bar.
     */
    private fun ytmRow(id: String, title: String = "Patient Zero", artist: String = "Taylor Swift") =
        YtDlpSearchResult(
            id = id,
            title = title,
            uploader = artist,
            channel = artist,
            duration = 0.0,
            viewCount = 0,
            webpageUrl = watch(id),
        )

    /** The player endpoint's answer for [id]. Without one, its lookup fails. */
    private fun player(id: String, title: String, lengthSeconds: Long) {
        coEvery { searchExecutor.verifyVideo(id) } returns InnerTubeSearchExecutor.VideoVerification(
            title = title,
            // WEB_REMIX without a PO token says UNPLAYABLE, and still sends the length.
            isPlayable = false,
            musicVideoType = null,
            lengthSeconds = lengthSeconds,
        )
    }

    private fun everySearchReturns(vararg rows: YtDlpSearchResult) {
        coEvery { searchExecutor.search(any(), any()) } returns rows.toList()
    }

    private fun firstSearchReturns(vararg rows: YtDlpSearchResult) {
        coEvery { searchExecutor.search(FIRST_QUERY, any()) } returns rows.toList()
    }

    private fun downloaded(id: String, times: Int) =
        coVerify(exactly = times) { downloadExecutor.download(watch(id), any(), any(), any(), any()) }

    private fun lookedUp(id: String, times: Int) =
        coVerify(exactly = times) { searchExecutor.verifyVideo(id) }

    /** The id the real scorer ranks first for [patientZero]. */
    private fun rankedFirst(vararg rows: YtDlpSearchResult): String = scorer.scoreResults(
        targetTitle = patientZero.title,
        targetArtist = patientZero.artist,
        targetDurationMs = patientZero.durationMs,
        results = rows.toList(),
        targetAlbum = patientZero.album,
    ).first().videoId

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

    @Test
    fun `a video the player says runs 312 s is not taken for a 226 s song`() = runTest {
        // #533: the video under the song's own title, and no length on the row.
        everySearchReturns(ytmRow(MV))
        player(MV, MV_PLAYER_TITLE, 312)

        val result = newSubject().downloadTrack(patientZero)

        assertEquals(TrackDownloadResult.Unmatched(rejectedVideoId = MV), result)
        downloaded(MV, times = 0)
        // Looked up once, not again by the second search.
        lookedUp(MV, times = 1)
    }

    @Test
    fun `a video the player says runs 226 s is taken`() = runTest {
        everySearchReturns(ytmRow(AUDIO))
        player(AUDIO, "Patient Zero", 226)

        newSubject().downloadTrack(patientZero)

        downloaded(AUDIO, times = 1)
    }

    @Test
    fun `when the player lookup fails the top pick is taken unchecked, as before`() = runTest {
        everySearchReturns(ytmRow(MV), ytmRow(AUDIO))
        // No player answers: every lookup fails.

        newSubject().downloadTrack(patientZero)

        downloaded(MV, times = 1)
        lookedUp(AUDIO, times = 0)
    }

    @Test
    fun `an unknown song length never rejects on the player's length`() = runTest {
        everySearchReturns(ytmRow(MV))
        player(MV, MV_PLAYER_TITLE, 312)

        newSubject().downloadTrack(patientZero.copy(durationMs = 0L))

        downloaded(MV, times = 1)
    }

    @Test
    fun `an unknown player length passes`() = runTest {
        everySearchReturns(ytmRow(MV))
        player(MV, MV_PLAYER_TITLE, 0)

        newSubject().downloadTrack(patientZero)

        downloaded(MV, times = 1)
    }

    @Test
    fun `the song behind a music video the length check rejects is taken`() = runTest {
        // Identical untyped rows tie and keep YouTube's order, so the video ranks first.
        firstSearchReturns(ytmRow(MV), ytmRow(AUDIO))
        player(MV, MV_PLAYER_TITLE, 312)
        player(AUDIO, "Patient Zero", 226)
        assertEquals("premise: the video outranks the song", MV, rankedFirst(ytmRow(MV), ytmRow(AUDIO)))

        newSubject().downloadTrack(patientZero)

        downloaded(AUDIO, times = 1)
        downloaded(MV, times = 0)
        // The accepted candidate's id is the one saved on the track.
        coVerify(exactly = 1) { trackDao.fillMissingMetadata(7L, any(), any(), any(), AUDIO) }
        coVerify(exactly = 0) { trackDao.fillMissingMetadata(any(), any(), any(), any(), MV) }
        lookedUp(MV, times = 1)
        lookedUp(AUDIO, times = 1)
    }

    @Test
    fun `at most three candidates are checked per search`() = runTest {
        val ids = listOf("c1", "c2", "c3", "c4", "c5")
        firstSearchReturns(*ids.map { ytmRow(it) }.toTypedArray())
        // The existing player-title check rejects each one, which isolates the loop.
        ids.forEach { player(it, "Something Else Entirely", 226) }

        val result = newSubject().downloadTrack(patientZero)

        assertEquals(TrackDownloadResult.Unmatched(rejectedVideoId = "c1"), result)
        listOf("c1", "c2", "c3").forEach { lookedUp(it, times = 1) }
        listOf("c4", "c5").forEach { lookedUp(it, times = 0) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a video the player rejected does not use up the next search's checks`() = runTest {
        // Both searches return three wrong cuts ahead of the song.
        everySearchReturns(ytmRow(MV), ytmRow("mv2"), ytmRow("mv3"), ytmRow(AUDIO))
        listOf(MV, "mv2", "mv3").forEach { player(it, MV_PLAYER_TITLE, 312) }
        player(AUDIO, "Patient Zero", 226)

        newSubject().downloadTrack(patientZero)

        downloaded(AUDIO, times = 1)
        listOf(MV, "mv2", "mv3").forEach { lookedUp(it, times = 1) }
    }

    @Test
    fun `a lower candidate is taken only when the player answered for it`() = runTest {
        firstSearchReturns(ytmRow(MV), ytmRow("unchecked"), ytmRow(AUDIO))
        player(MV, MV_PLAYER_TITLE, 312)
        // No answer for "unchecked": its lookup fails.
        player(AUDIO, "Patient Zero", 226)

        newSubject().downloadTrack(patientZero)

        downloaded("unchecked", times = 0)
        downloaded(AUDIO, times = 1)
    }

    @Test
    fun `when YouTube Music shows only the video, the yt-dlp search finds the song`() = runTest {
        everySearchReturns(ytmRow(MV))
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns listOf(
            YtDlpSearchResult(
                id = AUDIO,
                title = "Patient Zero",
                uploader = "Taylor Swift - Topic",
                channel = "Taylor Swift - Topic",
                duration = 226.0,
                viewCount = 1_000_000,
                webpageUrl = watch(AUDIO),
            ),
        )
        player(MV, MV_PLAYER_TITLE, 312)
        player(AUDIO, "Patient Zero", 226)

        newSubject().downloadTrack(patientZero)

        downloaded(AUDIO, times = 1)
        downloaded(MV, times = 0)
    }

    @Test
    fun `a yt-dlp row without a length is checked against the player's length too`() = runTest {
        // YouTube Music finds nothing, and yt-dlp's flat search can leave the length out.
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns listOf(
            YtDlpSearchResult(
                id = MV,
                title = "Patient Zero (Official Music Video)",
                uploader = "Taylor Swift",
                channel = "Taylor Swift",
                duration = 0.0,
                viewCount = 900_000_000,
                webpageUrl = watch(MV),
            ),
        )
        player(MV, MV_PLAYER_TITLE, 312)

        val result = newSubject().downloadTrack(patientZero)

        assertEquals(TrackDownloadResult.Unmatched(rejectedVideoId = MV), result)
        downloaded(MV, times = 0)
    }

    @Test
    fun `wiring - a signed-out row with no length meets the player's real answer`() = runTest {
        // The real search and player parsers, fed what YouTube sends signed out.
        val innerTube = mockk<InnerTubeClient> {
            coEvery { search(any(), any()) } returns Json.parseToJsonElement(SIGNED_OUT_SEARCH).jsonObject
            coEvery { player(any(), any(), any()) } returns Json.parseToJsonElement(MV_PLAYER).jsonObject
        }
        val ytDlp = mockk<YouTubeSearchExecutor> {
            coEvery { search(any(), any()) } returns emptyList()
        }

        val result = newSubject(search = HybridSearchExecutor(InnerTubeSearchExecutor(innerTube), ytDlp))
            .downloadTrack(patientZero)

        assertEquals(TrackDownloadResult.Unmatched(rejectedVideoId = MV), result)
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { innerTube.player(MV, any(), any()) }
    }
}
