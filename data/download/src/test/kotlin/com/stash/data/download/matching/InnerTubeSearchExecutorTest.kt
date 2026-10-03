package com.stash.data.download.matching

import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Contract tests for [InnerTubeSearchExecutor].
 *
 * The search parser must surface YouTube Music's authoritative
 * `musicVideoType` enum on each candidate so downstream scoring can
 * distinguish a Topic-channel audio master (ATV) from a music video
 * (OMV), lyrics/live reupload (UGC), or podcast. Today the parser only
 * keys off a heuristic text label ("Song" vs. "Video") — these tests
 * pin the structured-enum contract.
 *
 * Fixture `innertube_search_smooth_criminal.json` is a real InnerTube
 * response captured against production YT Music for the query
 * "Michael Jackson Smooth Criminal". Distribution of musicVideoType
 * values in the Songs shelf (per
 * `Agent.Phase0_5.smooth_criminal_search.json`):
 *   ATV: 15 occurrences — includes videoId XzNWRmqibNE (correct master)
 *   OMV:  8 — includes h_D3VFfhvs4 (official MV)
 *   UGC: 12 — includes gV5SnMKpEqs (lyrics video)
 *   PODCAST_EPISODE: 4
 */
class InnerTubeSearchExecutorTest {

    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!
            .getResourceAsStream("fixtures/$name")!!
            .bufferedReader()
            .use { it.readText() }

    private fun executorFor(fixture: String): InnerTubeSearchExecutor {
        val inner = mock<InnerTubeClient>()
        val parsed = Json.parseToJsonElement(loadFixture(fixture)).jsonObject
        // search(query, params = null): the executor calls search(query), which
        // the JVM expands to search(query, null) — so the params matcher must
        // accept null (anyOrNull), else Mockito sees 1 matcher for a 2-arg call.
        runBlocking { whenever(inner.search(any(), anyOrNull())).thenReturn(parsed) }
        return InnerTubeSearchExecutor(inner)
    }

    @Test
    fun `search surfaces ATV musicVideoType on the correct Smooth Criminal master`() = runTest {
        val executor = executorFor("innertube_search_smooth_criminal.json")

        val results = executor.search("Michael Jackson Smooth Criminal", maxResults = 10)

        assertTrue(
            "fixture returns 6 non-artist candidates; parser must yield at least 4",
            results.size >= 4,
        )
        val correctMaster = results.firstOrNull { it.id == "XzNWRmqibNE" }
        assertNotNull(
            "videoId XzNWRmqibNE is the Smooth Criminal ATV master and must be parsed",
            correctMaster,
        )
        assertEquals(
            "Songs-shelf candidates must be tagged ATV, not fall back to null",
            MusicVideoType.ATV,
            correctMaster!!.musicVideoType,
        )
    }

    @Test
    fun `search surfaces UGC musicVideoType on user-uploaded lyrics and live videos`() = runTest {
        val executor = executorFor("innertube_search_smooth_criminal.json")

        val results = executor.search("Michael Jackson Smooth Criminal", maxResults = 10)

        val lyrics = results.firstOrNull { it.id == "gV5SnMKpEqs" }
        assertNotNull(
            "videoId gV5SnMKpEqs is a user-uploaded lyrics video; must be parsed so the scorer can down-rank it",
            lyrics,
        )
        assertEquals(MusicVideoType.UGC, lyrics!!.musicVideoType)
    }

    @Test
    fun `a Video candidate's artist is its channel, without the view count`() = runTest {
        // The live flat-search row "Video • Grimm's VGM(UC…) • 845 views" (2026-09-27).
        // The executor reads musicShelfRenderer rows only, so the fixture wraps it
        // in the shelf that carries the same Video rows in the Smooth Criminal capture.
        val executor = executorFor("innertube_search_flat_video_row.json")

        val video = executor.search("Mii Channel Theme Cover").single()

        assertEquals("Grimm's VGM", video.uploader)
        assertEquals("Grimm's VGM", video.channel)
    }

    @Test
    fun `Song and Video candidates in the real Smooth Criminal shelf keep only the artist`() = runTest {
        val results = executorFor("innertube_search_smooth_criminal.json")
            .search("Michael Jackson Smooth Criminal", maxResults = 10)

        // "Video • Lyrixa(UC…) • 937K views"
        assertEquals("Lyrixa", results.single { it.id == "gV5SnMKpEqs" }.uploader)
        // "Song • Michael Jackson(UC…)": unchanged, still flagged as a Topic-equivalent.
        val song = results.single { it.id == "XzNWRmqibNE" }
        assertEquals("Michael Jackson", song.uploader)
        assertEquals("Michael Jackson - Topic", song.channel)
    }

    @Test
    fun `signed-out flat layout yields its Song and Video rows in order, skipping the card and albums`() = runTest {
        // Live signed-out "The Strokes Someday" search (WEB_REMIX, 2026-09-28): a
        // musicCardShelfRenderer, then one itemSectionRenderer per row and no
        // musicShelfRenderer at all. Trimmed to the card, 3 Songs, an Album and a Video.
        val results = executorFor("innertube_search_signed_out_flat.json")
            .search("The Strokes Someday", maxResults = 10)

        assertEquals(
            listOf("eArVJFjd6S0", "6FnbPTaoahM", "Bn-lcvrMOlc", "5kRQm3k3CU8"),
            results.map { it.id },
        )
        val song = results.first()
        assertEquals("Someday", song.title)
        assertEquals("The Strokes", song.uploader)
        assertEquals("Song rows get the Topic-equivalent channel", "The Strokes - Topic", song.channel)
        assertEquals(MusicVideoType.ATV, song.musicVideoType)
        val video = results.last()
        assertEquals("ToBe 카인드", video.uploader)
        assertEquals("ToBe 카인드", video.channel)
        assertEquals(MusicVideoType.UGC, video.musicVideoType)
    }

    @Test
    fun `verifyVideo returns OMV musicVideoType for Smooth Criminal MV videoId`() = runTest {
        // Player-endpoint fixture for the Smooth Criminal OMV (videoId h_D3VFfhvs4).
        // `videoDetails.musicVideoType == "MUSIC_VIDEO_TYPE_OMV"` per the real
        // production response we captured in Phase 0.5.
        val inner = mock<InnerTubeClient>()
        val playerResponse = Json.parseToJsonElement(
            loadFixture("innertube_player_smooth_criminal_omv.json"),
        ).jsonObject
        runBlocking { whenever(inner.player(any(), any(), anyOrNull())).thenReturn(playerResponse) }
        val executor = InnerTubeSearchExecutor(inner)

        val verification = executor.verifyVideo("h_D3VFfhvs4")

        assertNotNull("verifyVideo must parse the fixture", verification)
        assertEquals(
            "videoDetails.musicVideoType=MUSIC_VIDEO_TYPE_OMV must surface as enum OMV — Mode B canonicalization keys off this",
            MusicVideoType.OMV,
            verification!!.musicVideoType,
        )
    }

    /** An executor whose player endpoint answers [playerJson]. */
    private fun playerExecutorFor(playerJson: String): InnerTubeSearchExecutor {
        val inner = mock<InnerTubeClient>()
        val parsed = Json.parseToJsonElement(playerJson).jsonObject
        runBlocking { whenever(inner.player(any(), any(), anyOrNull())).thenReturn(parsed) }
        return InnerTubeSearchExecutor(inner)
    }

    @Test
    fun `verifyVideo carries the player's length`() = runTest {
        // The captured Smooth Criminal MV response: "lengthSeconds": "566".
        val verification = playerExecutorFor(loadFixture("innertube_player_smooth_criminal_omv.json"))
            .verifyVideo("h_D3VFfhvs4")

        assertEquals(566L, verification!!.lengthSeconds)
    }

    @Test
    fun `verifyVideo reads a length sent as a number`() = runTest {
        val verification = playerExecutorFor(
            """{"playabilityStatus":{"status":"OK"},"videoDetails":{"title":"Some Song","lengthSeconds":312}}""",
        ).verifyVideo("abc")

        assertEquals(312L, verification!!.lengthSeconds)
    }

    @Test
    fun `verifyVideo reports 0 for a missing or unusable length and keeps the title`() = runTest {
        // A bad length must not void the whole answer: the title check still needs it.
        for (length in listOf(null, "\"0\"", "\"abc\"", "{\"x\":1}")) {
            val lengthField = length?.let { ""","lengthSeconds":$it""" } ?: ""
            val verification = playerExecutorFor(
                """{"playabilityStatus":{"status":"OK"},"videoDetails":{"title":"Some Song"$lengthField}}""",
            ).verifyVideo("abc")

            assertNotNull("lengthSeconds=$length must still yield a verification", verification)
            assertEquals("lengthSeconds=$length", 0L, verification!!.lengthSeconds)
            assertEquals("Some Song", verification.title)
        }
    }

    @Test(expected = CancellationException::class)
    fun `verifyVideo lets a stopped download cancel instead of reporting a failed lookup`() {
        val inner = mock<InnerTubeClient>()
        runBlocking {
            whenever(inner.player(any(), any(), anyOrNull())).thenAnswer { throw CancellationException("stopped") }
        }

        runBlocking { InnerTubeSearchExecutor(inner).verifyVideo("abc") }
    }
}
