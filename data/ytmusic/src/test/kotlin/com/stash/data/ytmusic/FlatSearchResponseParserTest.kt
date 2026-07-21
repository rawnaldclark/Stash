package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.SearchResultSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #268: unauthenticated / region-limited InnerTube search returns a FLAT
 * `itemSectionRenderer` list instead of the titled `musicShelfRenderer` shelves,
 * so the legacy parser produced zero sections and the UI showed "No results
 * found" over a response full of hits. [parseFlatSearchSections] recovers those
 * rows. The fixture below mirrors the real shape captured from the live API.
 */
class FlatSearchResponseParserTest {
    private fun shelves(json: String) = Json.parseToJsonElement(json).jsonArray

    // One song, one artist, one album — the three flat row kinds interleaved,
    // each an itemSectionRenderer wrapping a single musicResponsiveListItemRenderer.
    private val flatResponse = """
        [
          {"itemSectionRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
              "playlistItemData":{"videoId":"vidSong1"},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Pagan Poetry"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Song"},{"text":" • "},{"text":"Bjork"}]}}}
              ]
            }}
          ]}},
          {"itemSectionRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
              "navigationEndpoint":{"browseEndpoint":{
                "browseId":"UCartist1",
                "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
                  "pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Sunna Bjork"}]}}}
              ]
            }}
          ]}},
          {"itemSectionRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
              "navigationEndpoint":{"browseEndpoint":{
                "browseId":"MPREb_album1",
                "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
                  "pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Debut"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Album"},{"text":" • "},{"text":"Bjork"},{"text":" • "},{"text":"1993"}]}}}
              ]
            }}
          ]}}
        ]
    """.trimIndent()

    @Test fun `flat response yields Songs, Artists and Albums sections`() {
        val sections = parseFlatSearchSections(shelves(flatResponse))
        assertEquals(3, sections.size)
        assertTrue(sections.any { it is SearchResultSection.Songs })
        assertTrue(sections.any { it is SearchResultSection.Artists })
        assertTrue(sections.any { it is SearchResultSection.Albums })
    }

    @Test fun `song row strips the leading Song label from the artist`() {
        val songs = parseFlatSearchSections(shelves(flatResponse))
            .filterIsInstance<SearchResultSection.Songs>().single().tracks
        assertEquals(1, songs.size)
        assertEquals("Pagan Poetry", songs[0].title)
        assertEquals("vidSong1", songs[0].videoId)
        // "Song • Bjork" must not leak the type label / separator into the artist.
        assertEquals("Bjork", songs[0].artist)
    }

    @Test fun `artist row carries browseId and name`() {
        val artists = parseFlatSearchSections(shelves(flatResponse))
            .filterIsInstance<SearchResultSection.Artists>().single().artists
        assertEquals(1, artists.size)
        assertEquals("UCartist1", artists[0].id)
        assertEquals("Sunna Bjork", artists[0].name)
    }

    @Test fun `album row parses id, artist and year from the subtitle`() {
        val albums = parseFlatSearchSections(shelves(flatResponse))
            .filterIsInstance<SearchResultSection.Albums>().single().albums
        assertEquals(1, albums.size)
        assertEquals("MPREb_album1", albums[0].id)
        assertEquals("Debut", albums[0].title)
        assertEquals("Bjork", albums[0].artist)
        assertEquals("1993", albums[0].year)
    }

    @Test fun `empty shelves yield no sections`() {
        assertTrue(parseFlatSearchSections(shelves("[]")).isEmpty())
    }
}
