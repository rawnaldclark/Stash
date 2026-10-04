package com.stash.data.ytmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTrackingParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val parser = PlaybackTrackingParser()

    private fun playerResponse(playbackUrl: String): JsonObject = buildJsonObject {
        putJsonObject("playbackTracking") {
            putJsonObject("videostatsPlaybackUrl") { put("baseUrl", playbackUrl) }
            putJsonObject("videostatsWatchtimeUrl") { put("baseUrl", "https://s.youtube.com/api/stats/watchtime?docid=abc") }
        }
    }

    @Test
    fun `extracts videostatsPlaybackUrl when present`() {
        val response = json.parseToJsonElement(
            """
            {
              "playbackTracking": {
                "videostatsPlaybackUrl": { "baseUrl": "https://s.youtube.com/api/stats/playback?docid=abc" },
                "videostatsWatchtimeUrl": { "baseUrl": "https://s.youtube.com/api/stats/watchtime?docid=abc" }
              }
            }
            """.trimIndent()
        ).jsonObject
        val url = parser.extract(response)
        assertEquals(
            "https://s.youtube.com/api/stats/playback?docid=abc",
            url,
        )
    }

    @Test
    fun `returns null when playbackTracking is missing`() {
        val response = json.parseToJsonElement("""{"responseContext": {}}""").jsonObject
        assertNull(parser.extract(response))
    }

    @Test
    fun `returns null when videostatsPlaybackUrl is missing but watchtime is present`() {
        // Guard against accidentally reading the wrong url if the shape drifts.
        val response = json.parseToJsonElement(
            """
            {
              "playbackTracking": {
                "videostatsWatchtimeUrl": { "baseUrl": "https://s.youtube.com/api/stats/watchtime?docid=abc" }
              }
            }
            """.trimIndent()
        ).jsonObject
        assertNull(parser.extract(response))
    }

    @Test
    fun `keeps https URLs on youtube_com and its subdomains`() {
        listOf(
            "https://s.youtube.com/api/stats/playback?docid=abc",
            "https://music.youtube.com/api/stats/playback?docid=abc",
            "https://www.youtube.com/api/stats/playback?docid=abc",
        ).forEach { assertEquals(it, parser.extract(playerResponse(it))) }
    }

    @Test
    fun `drops a playback URL that the user's YouTube credentials must not go to`() {
        // The scrobbler sends cookies and SAPISIDHASH to this URL, so the
        // parser hands back only https youtube.com ones.
        listOf(
            "https://other.example/api/stats/playback?docid=abc",
            "http://s.youtube.com/api/stats/playback?docid=abc",
            "https://s.youtube.com.other.example/api/stats/playback",
            "https://other.example\\@s.youtube.com/api/stats/playback",
            "https://user@s.youtube.com/api/stats/playback",
            "https://s.youtube.com:8443/api/stats/playback",
            "https://youtubei.googleapis.com/api/stats/playback?docid=abc",
            "/api/stats/playback?docid=abc",
            "",
        ).forEach { assertNull(it, parser.extract(playerResponse(it))) }
    }
}
