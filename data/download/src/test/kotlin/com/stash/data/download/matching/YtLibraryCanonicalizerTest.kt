package com.stash.data.download.matching

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.download.ytdlp.YtDlpSearchResult
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Contract tests for [YtLibraryCanonicalizer].
 *
 * Mode B fix: when a YT-library import resolves a videoId whose
 * `musicVideoType` is OMV / UGC / PODCAST_EPISODE, the canonicalizer
 * searches YT Music for the ATV equivalent and swaps the URL. Anchors
 * on the real Smooth Criminal search + player fixtures captured in
 * Phase 0.5 so the test proves it fixes the user's reported case.
 */
class YtLibraryCanonicalizerTest {

    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!
            .getResourceAsStream("fixtures/$name")!!
            .bufferedReader()
            .use { it.readText() }

    /**
     * Runs the real [InnerTubeSearchExecutor] parser against the captured
     * Smooth Criminal search JSON and returns the parsed candidate list.
     * Lets us feed production-realistic results into the canonicalizer
     * without spinning up a full network mock.
     */
    private fun smoothCriminalCandidates(): List<YtDlpSearchResult> {
        val inner = mock<InnerTubeClient>()
        val json = loadFixture("innertube_search_smooth_criminal.json")
        runBlocking {
            whenever(inner.search(any())).thenReturn(Json.parseToJsonElement(json).jsonObject)
        }
        val realExecutor = InnerTubeSearchExecutor(inner)
        return runBlocking { realExecutor.search("Michael Jackson Smooth Criminal", maxResults = 10) }
    }

    @Test
    fun `OMV videoId is swapped for ATV equivalent when search returns one`() = runTest {
        val searchExecutor = mock<InnerTubeSearchExecutor>()
        val trackDao = mock<TrackDao>()

        // Precompute the candidates before any `whenever` stubbing — the
        // helper internally stubs another mock (InnerTubeClient), and
        // nesting that inside an outer `whenever(...).thenReturn(...)`
        // confuses Mockito's argument recorder.
        val candidates = smoothCriminalCandidates()

        // verifyVideo reports the OMV for this videoId.
        whenever(searchExecutor.verifyVideo("h_D3VFfhvs4")).thenReturn(
            InnerTubeSearchExecutor.VideoVerification(
                title = "Smooth Criminal (Official Video)",
                isPlayable = true,
                musicVideoType = MusicVideoType.OMV,
            ),
        )
        // Canonicalizer's search returns the real fixture candidates.
        whenever(searchExecutor.search(any(), any())).thenReturn(candidates)

        val canonicalizer = YtLibraryCanonicalizer(
            searchExecutor = searchExecutor,
            matchScorer = MatchScorer(TrackMatcher()),
            trackDao = trackDao,
            trackMatcher = TrackMatcher(),
        )

        // Track as imported from the user's Archive Mix — MV duration,
        // MV-style title.
        val track = Track(
            id = 514,
            title = "Smooth Criminal (Official Video)",
            artist = "Michael Jackson",
            durationMs = 566_000,
            source = MusicSource.YOUTUBE,
            youtubeId = "h_D3VFfhvs4",
        )

        val url = canonicalizer.canonicalize(track, "h_D3VFfhvs4")

        assertEquals(
            "OMV h_D3VFfhvs4 must be replaced by ATV master XzNWRmqibNE — " +
                "this is the Smooth Criminal regression fix",
            "https://www.youtube.com/watch?v=XzNWRmqibNE",
            url,
        )
        // Persisting the swap: without this, the DB still claims the OMV
        // videoId even though the file on disk is the ATV audio. Future
        // code that joins by youtube_id (Failed Matches' exclude-current
        // guard, duplicate detection) would see inconsistent state.
        verify(trackDao).updateYoutubeId(eq(514L), eq("XzNWRmqibNE"))
        // Phase 7: also persist the ATV's display/dedup metadata so the
        // UI stops showing the MV's title + thumbnail for the studio
        // audio that just landed on disk.
        verify(trackDao).updateCanonicalMetadata(
            trackId = eq(514L),
            title = eq("Smooth Criminal"),
            canonicalTitle = any(),
            canonicalArtist = any(),
            album = any(),
            albumArtUrl = any(),
            durationMs = any(),
        )
    }

    @Test
    fun `ATV videoId is returned unchanged without calling search or DB`() = runTest {
        val searchExecutor = mock<InnerTubeSearchExecutor>()
        val trackDao = mock<TrackDao>()
        whenever(searchExecutor.verifyVideo("atv1")).thenReturn(
            InnerTubeSearchExecutor.VideoVerification(
                title = "Studio Song",
                isPlayable = true,
                musicVideoType = MusicVideoType.ATV,
            ),
        )
        val canonicalizer = YtLibraryCanonicalizer(
            searchExecutor = searchExecutor,
            matchScorer = MatchScorer(TrackMatcher()),
            trackDao = trackDao,
            trackMatcher = TrackMatcher(),
        )
        val track = Track(
            title = "Studio Song",
            artist = "Some Artist",
            source = MusicSource.YOUTUBE,
            youtubeId = "atv1",
        )

        val url = canonicalizer.canonicalize(track, "atv1")

        assertEquals("https://www.youtube.com/watch?v=atv1", url)
        verify(searchExecutor, never()).search(any(), any())
        verify(trackDao, never()).updateYoutubeId(any(), any())
    }

    @Test
    fun `OMV falls back to original when no better candidate is found`() = runTest {
        val searchExecutor = mock<InnerTubeSearchExecutor>()
        val trackDao = mock<TrackDao>()
        whenever(searchExecutor.verifyVideo("omv1")).thenReturn(
            InnerTubeSearchExecutor.VideoVerification(
                title = "Obscure OMV",
                isPlayable = true,
                musicVideoType = MusicVideoType.OMV,
            ),
        )
        whenever(searchExecutor.search(any(), any())).thenReturn(emptyList())
        val canonicalizer = YtLibraryCanonicalizer(
            searchExecutor = searchExecutor,
            matchScorer = MatchScorer(TrackMatcher()),
            trackDao = trackDao,
            trackMatcher = TrackMatcher(),
        )
        val track = Track(
            title = "Obscure Song",
            artist = "Obscure Artist",
            source = MusicSource.YOUTUBE,
            youtubeId = "omv1",
        )

        val url = canonicalizer.canonicalize(track, "omv1")

        assertEquals(
            "with no alternative, keep the original URL rather than failing the track",
            "https://www.youtube.com/watch?v=omv1",
            url,
        )
        verify(trackDao, never()).updateYoutubeId(any(), any())
    }
}
