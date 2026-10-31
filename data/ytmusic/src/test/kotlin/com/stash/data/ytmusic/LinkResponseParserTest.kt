package com.stash.data.ytmusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LinkResponseParser] on real responses (fetched 2026-10-29; the playlist one trimmed to its header). */
class LinkResponseParserTest {

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!.bufferedReader().use { it.readText() },
    ).jsonObject

    @Test
    fun `a playlist header gives its name, maker and the largest cover`() {
        val h = LinkResponseParser.playlistHeader(fixture("link_playlist_header.json"))!!
        assertEquals("Popular Music Videos", h.title)
        assertEquals("Music", h.author)
        assertTrue(h.thumbnailUrl!!.contains("12Z2lesRDLk4t_Pd"))
    }

    @Test
    fun `a video's byline gives its artist and no album`() {
        val s = LinkResponseParser.song(fixture("link_next_song.json"))!!
        assertEquals("dQw4w9WgXcQ", s.videoId)
        assertEquals("Never Gonna Give You Up", s.title)
        assertEquals("Rick Astley", s.artist)
        assertNull(s.album)
        assertEquals(214_000L, s.durationMs)
    }

    @Test
    fun `a track's byline names its album by the run that opens it`() {
        val next = Json.parseToJsonElement(
            """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{"watchNextTabbedResultsRenderer":{"tabs":[
              {"tabRenderer":{"content":{"musicQueueRenderer":{"content":{"playlistPanelRenderer":{"contents":[
                {"playlistPanelVideoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"One More Time"}]},
                 "longBylineText":{"runs":[{"text":"Daft Punk"},{"text":" • "},
                   {"text":"Discovery","navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_x"}}},{"text":" • "},{"text":"2001"}]},
                 "lengthText":{"runs":[{"text":"5:21"}]}}}]}}}}}}]}}}}}""",
        ).jsonObject
        val s = LinkResponseParser.song(next)!!
        assertEquals("Daft Punk", s.artist)
        assertEquals("Discovery", s.album)
        assertEquals(321_000L, s.durationMs)
    }

    @Test
    fun `an album playlist's album is the first album its songs open`() {
        val page = Json.parseToJsonElement(
            """{"contents":{"a":[{"browseId":"UCartist"},{"x":{"navigationEndpoint":{"browseEndpoint":{"browseId":"MPREb_album1"}}}}]}}""",
        )
        assertEquals("MPREb_album1", LinkResponseParser.albumBrowseId(page))
        assertNull(LinkResponseParser.albumBrowseId(Json.parseToJsonElement("""{"contents":{}}""")))
    }

    @Test
    fun `durations read minutes and hours`() {
        assertEquals(214_000L, LinkResponseParser.durationMs("3:34"))
        assertEquals(3_723_000L, LinkResponseParser.durationMs("1:02:03"))
        assertNull(LinkResponseParser.durationMs("live"))
        assertNull(LinkResponseParser.durationMs(null))
    }
}
