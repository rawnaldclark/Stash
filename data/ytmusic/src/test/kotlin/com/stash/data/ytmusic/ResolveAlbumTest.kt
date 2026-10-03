package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.AlbumSearch
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
 * #481: [YTMusicApiClient.searchAlbums] is how the album screen finds the YouTube
 * Music copy of an album Qobuz doesn't sell in the user's country, and
 * [YTMusicApiClient.resolveAlbum] is Now Playing's and Library's "View Album".
 * Live probe 2026-10-03: for 12 of 13 new releases the old lookup missed, YouTube
 * had put the album in its top-result card, which the search parser skipped
 * (Search's top slot shows artists and tracks only), while the flat rows below
 * named only the artist's other albums. [albumTopCardResponse] mirrors that shape.
 */
class ResolveAlbumTest {
    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() }

    private fun clientAnswering(responseJson: String): YTMusicApiClient {
        val inner = mock<InnerTubeClient>()
        val parsed = Json.parseToJsonElement(responseJson).jsonObject
        // anyOrNull() for params: callers omit it, and the typed any() doesn't match null.
        runBlocking {
            whenever(inner.search(any(), anyOrNull())).thenReturn(parsed)
            whenever(inner.searchWithStatus(any(), anyOrNull()))
                .thenReturn(InnerTubeClient.RequestOutcome(body = parsed, statusCode = 200))
        }
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
          ${flatAlbumRow("MPREb_WeEzlnB9TOI", "Sample The Sky", "Album", "Laura Misch", "2023")}
        ]}}}}]}}}
    """.trimIndent()

    /** A search response made of [shelves] (top card first, then rows). */
    private fun searchResponse(vararg shelves: String) =
        """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[""" +
            shelves.joinToString(",") + "]}}}}]}}}"

    /** An album top card; [subtitle] are its runs' texts without the " • " separators. */
    private fun albumTopCard(id: String, title: String, vararg subtitle: String) = """
        {"musicCardShelfRenderer":{
          "title":{"runs":[{"text":"$title","navigationEndpoint":{"browseEndpoint":{"browseId":"$id",
            "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
              "pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}]},
          "subtitle":{"runs":[${runs(subtitle)}]}
        }}
    """.trimIndent()

    /** A flat search row for an album, as unauthenticated searches return them. */
    private fun flatAlbumRow(id: String, title: String, vararg subtitle: String) = """
        {"itemSectionRenderer":{"contents":[{"musicResponsiveListItemRenderer":{
          "navigationEndpoint":{"browseEndpoint":{"browseId":"$id",
            "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
              "pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}},
          "flexColumns":[
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"$title"}]}}},
            {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[${runs(subtitle)}]}}}
          ]}}]}}
    """.trimIndent()

    private fun runs(texts: Array<out String>) =
        texts.joinToString(""",{"text":" • "},""") { """{"text":"$it"}""" }

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
        whenever(inner.searchWithStatus(any(), anyOrNull())).doSuspendableAnswer { throw CancellationException("screen closed") }

        try {
            YTMusicApiClient(inner).resolveAlbum("Lithic", "Laura Misch")
            fail("expected the cancellation to propagate")
        } catch (e: CancellationException) {
            assertEquals("screen closed", e.message)
        }
    }

    // ── searchAlbums: every candidate, and whether YouTube answered at all ──

    /** No connection, a timeout, a 429 or a 5xx is not "no such album": the caller can retry. */
    @Test fun `a search that doesn't answer is Failed, not an empty answer`() = runTest {
        val inner = mock<InnerTubeClient>()
        whenever(inner.searchWithStatus(any(), anyOrNull())).thenReturn(
            InnerTubeClient.RequestOutcome(body = null, statusCode = InnerTubeClient.RequestOutcome.STATUS_NETWORK_ERROR),
        )

        assertEquals(AlbumSearch.Failed, YTMusicApiClient(inner).searchAlbums("Lithic", "Laura Misch"))
    }

    @Test fun `an answer naming no album is an empty answer, not a failure`() = runTest {
        val found = clientAnswering(searchResponse()).searchAlbums("Lithic", "Laura Misch")

        assertEquals(AlbumSearch.Answered(topAlbum = null, shelf = emptyList()), found)
    }

    @Test fun `searchAlbums lists the top card first, then the shelf, each with its type label`() = runTest {
        val found = clientAnswering(albumTopCardResponse).searchAlbums("Lithic", "Laura Misch") as AlbumSearch.Answered

        assertEquals(listOf("MPREb_PB8kLguH9cS", "MPREb_WeEzlnB9TOI"), found.albums.map { it.id })
        assertEquals(listOf("Album", "Album"), found.albums.map { it.releaseType })
    }

    /** A single often shares its album's title; the label is how the album screen tells them apart. */
    @Test fun `a top card labelled Single carries the label`() = runTest {
        val response = searchResponse(albumTopCard("MPREb_single", "Lithic", "Single", "Laura Misch", "2026"))

        val found = clientAnswering(response).searchAlbums("Lithic", "Laura Misch") as AlbumSearch.Answered

        assertEquals("Single", found.topAlbum?.releaseType)
        assertEquals("Laura Misch", found.topAlbum?.artist)
    }

    // ── resolveAlbum ("View Album") keeps its old answers when the top card doesn't fit ──

    /** With no shelf the old lookup found nothing. It must not now open another artist's album. */
    @Test fun `an album top card by another artist, with no shelf, finds nothing`() = runTest {
        val response = searchResponse(albumTopCard("MPREb_other", "Lithic", "Album", "Someone Else", "2026"))

        assertNull(clientAnswering(response).resolveAlbum("Lithic", "Laura Misch"))
    }

    /**
     * An album named after its lead single (Lover, Future Nostalgia, After Hours): YouTube's
     * top card is often the single. "View Album" on a track from the album must open the
     * album when the shelf has it.
     */
    @Test fun `a top-card single gives way to the same-titled album on the shelf`() = runTest {
        val response = searchResponse(
            albumTopCard("MPREb_single", "Lithic", "Single", "Laura Misch", "2026"),
            flatAlbumRow("MPREb_lithic", "Lithic", "Album", "Laura Misch", "2026"),
        )

        assertEquals("MPREb_lithic", clientAnswering(response).resolveAlbum("Lithic", "Laura Misch")?.id)
    }

    /** A track tagged with the single's own title still opens the single. */
    @Test fun `a top-card single with no same-titled album is still the answer`() = runTest {
        val response = searchResponse(
            albumTopCard("MPREb_single", "Lithic", "Single", "Laura Misch", "2026"),
            flatAlbumRow("MPREb_WeEzlnB9TOI", "Sample The Sky", "Album", "Laura Misch", "2023"),
        )

        assertEquals("MPREb_single", clientAnswering(response).resolveAlbum("Lithic", "Laura Misch")?.id)
    }

    /** A blank artist "contains" every name, so a card naming no artist must not count as a match. */
    @Test fun `a top card naming no artist is passed over for the shelf, as before`() = runTest {
        val response = searchResponse(
            albumTopCard("MPREb_noartist", "Lithic", "Album", "2026"),
            flatAlbumRow("MPREb_WeEzlnB9TOI", "Sample The Sky", "Album", "Laura Misch", "2023"),
        )

        assertEquals("MPREb_WeEzlnB9TOI", clientAnswering(response).resolveAlbum("Lithic", "Laura Misch")?.id)
    }
}
