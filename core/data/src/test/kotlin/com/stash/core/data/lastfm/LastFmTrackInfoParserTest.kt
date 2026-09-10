package com.stash.core.data.lastfm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmTrackInfoParserTest {

    private val sample = """
        {"track":{
            "name":"505",
            "mbid":"abc-mbid-505",
            "duration":"253000",
            "listeners":"1234567",
            "playcount":"9876543",
            "userplaycount":"42",
            "userloved":"1",
            "artist":{"name":"Arctic Monkeys","mbid":"art-mbid"},
            "album":{"image":[
                {"size":"small","#text":"http://x/s.jpg"},
                {"size":"extralarge","#text":"http://x/xl.jpg"}
            ]},
            "toptags":{"tag":[
                {"name":"indie rock","count":"100"},
                {"name":"alternative","count":"60"}
            ]}
        }}
    """.trimIndent()

    @Test
    fun `parses full track info including userplaycount and userloved`() {
        val info = LastFmTrackInfo.parse(Json.parseToJsonElement(sample))

        assertEquals("abc-mbid-505", info.mbid)
        assertEquals(253000L, info.durationMs)
        assertEquals(1_234_567L, info.listeners)
        assertEquals(42, info.userPlaycount)
        assertEquals(true, info.userLoved)
        assertEquals("http://x/xl.jpg", info.bestImageUrl)
        assertEquals(listOf("indie rock", "alternative"), info.tags.map { it.name })
        assertEquals(listOf(100, 60), info.tags.map { it.count })
    }

    @Test
    fun `parse handles missing user fields when no username supplied`() {
        val withoutUser = """
            {"track":{"name":"x","artist":{"name":"y"},
                "listeners":"100","playcount":"500",
                "album":{"image":[]},"toptags":{"tag":[]}}}
        """
        val info = LastFmTrackInfo.parse(Json.parseToJsonElement(withoutUser))
        assertNull(info.userPlaycount)
        assertNull(info.userLoved)
        assertNull(info.mbid)
        assertEquals(100L, info.listeners)
    }

    @Test
    fun `parse handles toptags returned as single-tag object instead of array`() {
        val singleTag = """
            {"track":{"name":"x","artist":{"name":"y"},
                "listeners":"0","playcount":"0",
                "album":{"image":[]},
                "toptags":{"tag":{"name":"only","count":"5"}}}}
        """
        val info = LastFmTrackInfo.parse(Json.parseToJsonElement(singleTag))
        assertEquals(listOf("only"), info.tags.map { it.name })
    }

    @Test
    fun `parse skips Last_fm star-logo placeholder and returns null when no real art`() {
        val onlyPlaceholder = """
            {"track":{"name":"x","artist":{"name":"y"},
                "listeners":"0","playcount":"0",
                "album":{"image":[
                    {"size":"extralarge","#text":"https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png"}
                ]},
                "toptags":{"tag":[]}}}
        """
        val info = LastFmTrackInfo.parse(Json.parseToJsonElement(onlyPlaceholder))
        assertNull(info.bestImageUrl)
    }

    @Test
    fun `best image comes out already upgraded so cached responses never re-store 300px png art`() {
        val real = """
            {"track":{"name":"More and More","artist":{"name":"Captain Hollywood Project"},
                "listeners":"1","playcount":"1",
                "album":{"image":[
                    {"size":"small","#text":"https://lastfm-img.freetls.fastly.net/i/u/34s/a1e2a5b1e851d66bc10112a4dbceb750.png"},
                    {"size":"extralarge","#text":"https://lastfm-img.freetls.fastly.net/i/u/300x300/a1e2a5b1e851d66bc10112a4dbceb750.png"}
                ]},"toptags":{"tag":[]}}}
        """
        val info = LastFmTrackInfo.parse(Json.parseToJsonElement(real))
        assertEquals(
            "https://lastfm-img.freetls.fastly.net/i/u/770x0/a1e2a5b1e851d66bc10112a4dbceb750.jpg",
            info.bestImageUrl,
        )
    }
}
