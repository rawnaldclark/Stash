package com.stash.data.ytmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackTrackingParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val parser = PlaybackTrackingParser()

    @Test
    fun `extracts videostatsPlaybackUrl when present`() {
        val response = json.parseToJsonElement(
            """
            {
              "playbackTracking": {
                "videostatsPlaybackUrl": { "baseUrl": "https://youtubei.googleapis.com/api/stats/playback?docid=abc" },
                "videostatsWatchtimeUrl": { "baseUrl": "https://youtubei.googleapis.com/api/stats/watchtime?docid=abc" }
              }
            }
            """.trimIndent()
        ).jsonObject
        val url = parser.extract(response)
        assertEquals(
            "https://youtubei.googleapis.com/api/stats/playback?docid=abc",
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
                "videostatsWatchtimeUrl": { "baseUrl": "https://youtubei.googleapis.com/api/stats/watchtime?docid=abc" }
              }
            }
            """.trimIndent()
        ).jsonObject
        assertNull(parser.extract(response))
    }
}
