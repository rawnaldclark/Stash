package com.stash.data.ytmusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #481: [YTMusicApiClient.resolveAlbum] is how the album screen finds the YouTube
 * Music copy of an album Qobuz doesn't sell in the user's country. Live probe
 * 2026-10-03: for 12 of 13 new releases it missed, YouTube had put the album in
 * its top-result card, which the search parser skipped (Search's top slot shows
 * artists and tracks only), while the flat rows below named only the artist's
 * other albums. [albumTopCardResponse] mirrors that live shape.
 */
class ResolveAlbumTest {
    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    private fun clientAnswering(responseJson: String): YTMusicApiClient {
        val inner = mock<InnerTubeClient>()
        val parsed = Json.parseToJsonElement(responseJson).jsonObject
        // anyOrNull() for params: searchAll omits it, and the typed any() doesn't match null.
        runBlocking { whenever(inner.search(any(), anyOrNull())).thenReturn(parsed) }
        return YTMusicApiClient(inner)
    }

    /** "Lithic Laura Misch", trimmed: the album as the top card, the artist's older album below. */
    private val albumTopCardResponse = """
        {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"musicCardShelfRenderer":{
            "title":{"runs":[{"text":"Lithic","navigationEndpoint":{"browseEndpoint":{
              "browseId":"MPREb_PB8kLguH9cS",
              "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
                "pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}]},
            "subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"Laura Misch"},{"text":" • "},{"text":"2026"}]},
            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[
              {"url":"https://yt3.googleusercontent.com/lithic=w60-h60","width":60},
              {"url":"https://yt3.googleusercontent.com/lithic=w544-h544","width":544}]}}}
          }},
          {"itemSectionRenderer":{"contents":[
            {"musicResponsiveListItemRenderer":{
              "navigationEndpoint":{"browseEndpoint":{
                "browseId":"MPREb_WeEzlnB9TOI",
                "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
                  "pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}},
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Sample The Sky"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[
                  {"text":"Album"},{"text":" • "},{"text":"Laura Misch"},{"text":" • "},{"text":"2023"}]}}}
              ]
            }}
          ]}}
        ]}}}}]}}}
    """.trimIndent()

    @Test fun `an album in YouTube's top-result card is the answer`() = runTest {
        val album = clientAnswering(albumTopCardResponse).resolveAlbum("Lithic", "Laura Misch")

        assertEquals("MPREb_PB8kLguH9cS", album?.id)
        assertEquals("Lithic", album?.title)
        assertEquals("Laura Misch", album?.artist)
        assertEquals("2026", album?.year)
        assertNotNull(album?.thumbnailUrl)
    }

    /** The top card is an artist here, so the Albums shelf answers, exactly as before. */
    @Test fun `without an album top card the Albums shelf answers as before`() = runTest {
        val album = clientAnswering(loadFixture("search_artist.json"))
            .resolveAlbum("Soundpieces: Da Antidote", "Lootpack")

        assertEquals("MPREb_ALBUM1", album?.id)
    }

    @Test fun `artist and track top cards are not albums`() = runTest {
        // search_artist.json leads with an artist card, search_track.json with a song card.
        assertNull(clientAnswering(loadFixture("search_artist.json")).searchAll("lootpack").topAlbum)
        assertNull(clientAnswering(loadFixture("search_track.json")).searchAll("never gonna give").topAlbum)
    }

    /** Leaving the album screen cancels the lookup; that must not come back as "no album". */
    @Test fun `a cancelled search is not swallowed into null`() = runTest {
        val inner = mock<InnerTubeClient>()
        whenever(inner.search(any(), anyOrNull())).doSuspendableAnswer { throw CancellationException("screen closed") }

        try {
            YTMusicApiClient(inner).resolveAlbum("Lithic", "Laura Misch")
            fail("expected the cancellation to propagate")
        } catch (e: CancellationException) {
            assertEquals("screen closed", e.message)
        }
    }
}
