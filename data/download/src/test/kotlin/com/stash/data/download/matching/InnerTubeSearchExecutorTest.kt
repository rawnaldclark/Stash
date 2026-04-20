package com.stash.data.download.matching

import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.model.MusicVideoType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
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
        runBlocking { whenever(inner.search(any())).thenReturn(parsed) }
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
    fun `verifyVideo returns OMV musicVideoType for Smooth Criminal MV videoId`() = runTest {
        // Player-endpoint fixture for the Smooth Criminal OMV (videoId h_D3VFfhvs4).
        // `videoDetails.musicVideoType == "MUSIC_VIDEO_TYPE_OMV"` per the real
        // production response we captured in Phase 0.5.
        val inner = mock<InnerTubeClient>()
        val playerResponse = Json.parseToJsonElement(
            loadFixture("innertube_player_smooth_criminal_omv.json"),
        ).jsonObject
        runBlocking { whenever(inner.player(any(), any())).thenReturn(playerResponse) }
        val executor = InnerTubeSearchExecutor(inner)

        val verification = executor.verifyVideo("h_D3VFfhvs4")

        assertNotNull("verifyVideo must parse the fixture", verification)
        assertEquals(
            "videoDetails.musicVideoType=MUSIC_VIDEO_TYPE_OMV must surface as enum OMV — Mode B canonicalization keys off this",
            MusicVideoType.OMV,
            verification!!.musicVideoType,
        )
    }
}
