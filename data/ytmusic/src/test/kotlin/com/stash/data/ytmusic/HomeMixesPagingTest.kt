package com.stash.data.ytmusic

import com.stash.core.model.SyncResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * "Mixed for you" (My Supermix, My Mix N) sits below the home feed's first page. Reading page
 * one alone found 5 mixes on a real account (2026-10-29), so the sync follows continuations.
 */
class HomeMixesPagingTest {

    private fun card(id: String, title: String) = """
        {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"$title"}]},
         "navigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"$id"}}}}
    """

    private fun shelf(vararg cards: String) =
        """{"musicCarouselShelfRenderer":{"contents":[${cards.joinToString(",")}]}}"""

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val pageOne = json(
        """
        {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{
          "contents":[${shelf(card("RDTMAK5uy_discover", "Discover Mix"), card("MPREb_album", "An album"))}],
          "continuations":[{"nextContinuationData":{"continuation":"page2"}}]
        }}}}]}}}
        """,
    )

    private val pageTwo = json(
        """
        {"continuationContents":{"sectionListContinuation":{
          "contents":[${shelf(card("RDTMAK5uy_supermix", "My Supermix"), card("RDTMAK5uy_discover", "Discover Mix"))}],
          "continuations":[{"nextContinuationData":{"continuation":"page3"}}]
        }}}
        """,
    )

    private val pageThree = json(
        """
        {"continuationContents":{"sectionListContinuation":{
          "contents":[${shelf(card("RDTMAK5uy_mymix1", "My Mix 1"))}]
        }}}
        """,
    )

    @Test fun `mixes further down the home feed are found, each once`() = runTest {
        val inner = mock<InnerTubeClient>()
        runBlocking {
            whenever(inner.browse(any(), anyOrNull())).thenReturn(pageOne)
            whenever(inner.browseContinuation("page2")).thenReturn(pageTwo)
            whenever(inner.browseContinuation("page3")).thenReturn(pageThree)
        }

        val result = YTMusicApiClient(inner).getHomeMixes()

        val titles = (result as SyncResult.Success).data.map { it.title }
        assertEquals(listOf("Discover Mix", "My Supermix", "My Mix 1"), titles)
    }

    @Test fun `a failed later page keeps what the first page found`() = runTest {
        val inner = mock<InnerTubeClient>()
        runBlocking {
            whenever(inner.browse(any(), anyOrNull())).thenReturn(pageOne)
            whenever(inner.browseContinuation("page2")).thenReturn(null)
        }

        val result = YTMusicApiClient(inner).getHomeMixes()

        assertEquals(listOf("Discover Mix"), (result as SyncResult.Success).data.map { it.title })
    }
}
