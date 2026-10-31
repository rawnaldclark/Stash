package com.stash.data.spotify

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SpotifyArtworkTest {
    @Test fun `widest image wins regardless of source ordering`() {
        for (sources in listOf(
            """[{"url":"https://cdn/small","width":64},{"url":"https://cdn/large","width":640}]""",
            """[{"url":"https://cdn/large","width":640},{"url":"https://cdn/small","width":64}]""")) {
            assertEquals("https://cdn/large", SpotifyArtwork.largestSource(Json.parseToJsonElement(sources)))
        }
    }
    @Test fun `invalid images do not hide a usable source`() {
        assertEquals("https://cdn/good", SpotifyArtwork.largestSource(Json.parseToJsonElement(
            """[null,{"url":"http://cdn/bad","width":2000},{"width":3000},{"url":"https://cdn/good"}]""")))
        assertNull(SpotifyArtwork.largestSource(null))
    }
}
