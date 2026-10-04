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
import org.junit.Assert.assertTrue
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

    /** The real scorer's ranking of [rows] for [patientZero], best first. */
    private fun ranked(vararg rows: YtDlpSearchResult) = scorer.scoreResults(
        targetTitle = patientZero.title,
        targetArtist = patientZero.artist,
        targetDurationMs = patientZero.durationMs,
        results = rows.toList(),
        targetAlbum = patientZero.album,
    )

    /** The id the real scorer ranks first for [patientZero]. */
    private fun rankedFirst(vararg rows: YtDlpSearchResult): String = ranked(*rows).first().videoId

    @Test
    fun `a percent sign in the song's name does not break its match`() = runTest {
        // Two log lines handed format() a pattern with a title inside it, so "% P" read
        // as a format conversion and threw: the accept line, and bestMatch's line for a
        // search whose best row is under the bar, which left every later search unrun.
        val track = Track(id = 9L, title = "100% Pure Love", artist = "Crystal Waters")
        // YouTube Music shows only a karaoke cut, and yt-dlp's search finds the song.
        val karaoke = ytmRow("karaokeRow1", "100% Pure Love (Karaoke)", "Crystal Waters")
        everySearchReturns(karaoke)
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns
            listOf(ytmRow("percentRow1", "100% Pure Love", "Crystal Waters"))
        val karaokeScore = scorer.scoreResults(track.title, track.artist, track.durationMs, listOf(karaoke))
            .single().matchScore
        assertTrue(
            "premise: the karaoke cut scores under the bar, at $karaokeScore",
            karaokeScore < MatchScorer.AUTO_ACCEPT_THRESHOLD,
        )

        newSubject().downloadTrack(track)

        downloaded("percentRow1", times = 1)
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
        val ids = listOf("candidate01", "candidate02", "candidate03", "candidate04", "candidate05")
        firstSearchReturns(*ids.map { ytmRow(it) }.toTypedArray())
        // The existing player-title check rejects each one, which isolates the loop.
        ids.forEach { player(it, "Something Else Entirely", 226) }

        val result = newSubject().downloadTrack(patientZero)

        assertEquals(TrackDownloadResult.Unmatched(rejectedVideoId = "candidate01"), result)
        listOf("candidate01", "candidate02", "candidate03").forEach { lookedUp(it, times = 1) }
        listOf("candidate04", "candidate05").forEach { lookedUp(it, times = 0) }
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a video the player rejected does not use up the next search's checks`() = runTest {
        // Both searches return three wrong cuts ahead of the song.
        everySearchReturns(ytmRow(MV), ytmRow("musicVideo2"), ytmRow("musicVideo3"), ytmRow(AUDIO))
        listOf(MV, "musicVideo2", "musicVideo3").forEach { player(it, MV_PLAYER_TITLE, 312) }
        player(AUDIO, "Patient Zero", 226)

        newSubject().downloadTrack(patientZero)

        downloaded(AUDIO, times = 1)
        listOf(MV, "musicVideo2", "musicVideo3").forEach { lookedUp(it, times = 1) }
    }

    @Test
    fun `a video the player says is another song does not use up the next search's checks`() = runTest {
        // Both searches return three videos the player names as another song, ahead of
        // the song. Their length is the song's, so only the title check rejects them.
        everySearchReturns(ytmRow("anotherOne1"), ytmRow("anotherOne2"), ytmRow("anotherOne3"), ytmRow(AUDIO))
        listOf("anotherOne1", "anotherOne2", "anotherOne3").forEach { player(it, "Something Else Entirely", 226) }
        player(AUDIO, "Patient Zero", 226)

        newSubject().downloadTrack(patientZero)

        downloaded(AUDIO, times = 1)
        listOf("anotherOne1", "anotherOne2", "anotherOne3").forEach { lookedUp(it, times = 1) }
    }

    @Test
    fun `a lower candidate is taken only when the player answered for it`() = runTest {
        firstSearchReturns(ytmRow(MV), ytmRow("uncheckedV1"), ytmRow(AUDIO))
        player(MV, MV_PLAYER_TITLE, 312)
        // No answer for "uncheckedV1": its lookup fails.
        player(AUDIO, "Patient Zero", 226)

        newSubject().downloadTrack(patientZero)

        downloaded("uncheckedV1", times = 0)
        downloaded(AUDIO, times = 1)
    }

    @Test
    fun `a candidate behind a video an earlier search rejected still needs the player's answer`() = runTest {
        // Every search returns the video ahead of a row whose lookup fails, yt-dlp's
        // too. Later searches skip the rejected video, which leaves that row first in
        // line, but it is still the lower candidate: taken on a failed lookup, it
        // would be downloaded with no title or length check.
        everySearchReturns(ytmRow(MV), ytmRow("uncheckedV1"))
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns listOf(ytmRow(MV), ytmRow("uncheckedV1"))
        player(MV, MV_PLAYER_TITLE, 312)
        // No answer for "uncheckedV1": its lookup fails every time.

        val result = newSubject().downloadTrack(patientZero)

        assertEquals(TrackDownloadResult.Unmatched(rejectedVideoId = MV), result)
        downloaded("uncheckedV1", times = 0)
        lookedUp(MV, times = 1)
        // Once per search: two on YouTube Music, then yt-dlp's.
        lookedUp("uncheckedV1", times = 3)
    }

    @Test
    fun `a candidate under the score bar is not taken, even behind a rejected video`() = runTest {
        // Only bestMatch's pick used to be checked, and bestMatch keeps the 0.60 bar.
        // Lower candidates are checked now, so the loop has to keep the bar itself:
        // the instrumental passes every gate (its title strips to the song's, and the
        // player gives the song's length), and only its score marks the wrong cut.
        val instrumental = ytmRow("instrumentl", "Patient Zero (Instrumental)")
        firstSearchReturns(ytmRow(MV), instrumental)
        player(MV, MV_PLAYER_TITLE, 312)
        player("instrumentl", "Patient Zero (Instrumental)", 226)
        val instrumentalScore = ranked(ytmRow(MV), instrumental).single { it.videoId == "instrumentl" }.matchScore
        assertTrue(
            "premise: the instrumental scores under the bar, at $instrumentalScore",
            instrumentalScore < MatchScorer.AUTO_ACCEPT_THRESHOLD,
        )

        val result = newSubject().downloadTrack(patientZero)

        assertEquals(TrackDownloadResult.Unmatched(rejectedVideoId = MV), result)
        downloaded("instrumentl", times = 0)
        lookedUp(MV, times = 1)
        lookedUp("instrumentl", times = 0)
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
    fun `the yt-dlp search takes and saves the song behind a video the player rejected`() = runTest {
        // YouTube Music shows only the video, so every search there ends on its
        // rejection. yt-dlp's rows can carry no length either, and the video's views
        // rank it ahead of the song.
        everySearchReturns(ytmRow(MV))
        val videoRow = YtDlpSearchResult(
            id = MV,
            title = "Patient Zero (Official Music Video)",
            uploader = "Taylor Swift",
            channel = "Taylor Swift",
            duration = 0.0,
            viewCount = 900_000_000,
            webpageUrl = watch(MV),
        )
        val songRow = YtDlpSearchResult(
            id = AUDIO,
            title = "Patient Zero",
            uploader = "Taylor Swift",
            channel = "Taylor Swift",
            duration = 0.0,
            viewCount = 1_000_000,
            webpageUrl = watch(AUDIO),
        )
        coEvery { searchExecutor.searchYtDlpDirect(any(), any()) } returns listOf(videoRow, songRow)
        player(MV, MV_PLAYER_TITLE, 312)
        player(AUDIO, "Patient Zero", 226)
        assertEquals("premise: the video outranks the song", MV, rankedFirst(videoRow, songRow))

        newSubject().downloadTrack(patientZero)

        downloaded(AUDIO, times = 1)
        downloaded(MV, times = 0)
        // The accepted candidate's id is saved, not the search's top pick: a saved id
        // is reused unchecked on the next download, which would fetch the video.
        coVerify(exactly = 1) { trackDao.fillMissingMetadata(7L, any(), any(), any(), AUDIO) }
        coVerify(exactly = 0) { trackDao.fillMissingMetadata(any(), any(), any(), any(), MV) }
        // Rejected in the YouTube Music searches, and not looked up again by yt-dlp's.
        lookedUp(MV, times = 1)
        lookedUp(AUDIO, times = 1)
    }

    @Test
    fun `a search row whose id isn't a video id is never taken, and the valid row behind it is`() = runTest {
        // Identical untyped rows tie and keep the search's order, so the malformed rows rank first.
        val malformed = listOf("../x", "a%(title)s")
        val rows = (malformed.map { ytmRow(it) } + ytmRow(AUDIO)).toTypedArray()
        firstSearchReturns(*rows)
        player(AUDIO, "Patient Zero", 226)
        assertEquals("premise: a malformed row outranks the song", "../x", rankedFirst(*rows))

        newSubject().downloadTrack(patientZero)

        downloaded(AUDIO, times = 1)
        coVerify(exactly = 1) { trackDao.fillMissingMetadata(7L, any(), any(), any(), AUDIO) }
        malformed.forEach { id ->
            coVerify(exactly = 0) { downloadExecutor.download(match { id in it }, any(), any(), any(), any()) }
            coVerify(exactly = 0) { trackDao.fillMissingMetadata(any(), any(), any(), any(), id) }
            coVerify(exactly = 0) { trackDao.updateYoutubeId(any(), id) }
            lookedUp(id, times = 0)
        }
    }

    @Test
    fun `yt-dlp gets the URL built from the accepted row's id, not the row's own link`() = runTest {
        val row = ytmRow(AUDIO).copy(webpageUrl = "https://other.example/watch?v=$AUDIO")
        everySearchReturns(row)
        player(AUDIO, "Patient Zero", 226)

        newSubject().downloadTrack(patientZero)

        downloaded(AUDIO, times = 1)
        coVerify(exactly = 0) { downloadExecutor.download(row.webpageUrl, any(), any(), any(), any()) }
    }

    @Test
    fun `an album match reaches yt-dlp as the URL built from its id`() = runTest {
        val albumRow = ytmRow(AUDIO).copy(webpageUrl = "https://other.example/watch?v=$AUDIO")
        coEvery { albumMatchExecutor.findTrackInAlbum(any(), any(), any(), any()) } returns albumRow

        newSubject().downloadTrack(patientZero)

        downloaded(AUDIO, times = 1)
        coVerify(exactly = 0) { downloadExecutor.download(albumRow.webpageUrl, any(), any(), any(), any()) }
    }

    @Test
    fun `an album row whose id isn't a video id is never taken`() = runTest {
        coEvery { albumMatchExecutor.findTrackInAlbum(any(), any(), any(), any()) } returns ytmRow("../x")

        val result = newSubject().downloadTrack(patientZero)

        assertTrue("expected Unmatched, got $result", result is TrackDownloadResult.Unmatched)
        coVerify(exactly = 0) { downloadExecutor.download(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { trackDao.fillMissingMetadata(any(), any(), any(), any(), "../x") }
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
