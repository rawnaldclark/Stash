package com.stash.feature.search

import androidx.lifecycle.SavedStateHandle
import com.stash.core.data.cache.AlbumCache
import com.stash.core.media.actions.TrackActionsDelegate
import com.stash.core.media.preview.PreviewState
import com.stash.core.model.Playlist
import com.stash.data.download.lossless.qbdlx.QbdlxApiClient
import com.stash.data.download.lossless.qbdlx.QbdlxApiException
import com.stash.data.download.lossless.qbdlx.QobuzAlbumFetcherImpl
import com.stash.data.ytmusic.InnerTubeClient
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.AlbumSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #481 end to end below the ViewModel: the REAL [QobuzAlbumFetcherImpl] (its Qobuz
 * client answering 404, as it does in a country Qobuz doesn't sell in), the REAL
 * [AlbumCache] and the REAL [YTMusicApiClient], over a mocked [InnerTubeClient] that
 * returns what InnerTube returns, nothing included. The ViewModel tests mock the
 * cache; this proves the pieces hand the ViewModel what it expects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumRegionFallbackWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private val qobuz = mock<QbdlxApiClient>()
    private val innerTube = mock<InnerTubeClient>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        runBlocking {
            whenever(qobuz.getAlbum(any())).doSuspendableAnswer {
                throw QbdlxApiException(404, """{"status":"error","code":404,"message":"No result matching given argument"}""")
            }
        }
    }

    @After fun tear() { Dispatchers.resetMain() }

    /** The Home card for Laura Misch's "Lithic", a Qobuz new release ([qobuzTrackCount] tracks, when known). */
    private fun vm(qobuzTrackCount: Int? = null): AlbumDiscoveryViewModel {
        val yt = YTMusicApiClient(innerTube)
        return AlbumDiscoveryViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "browseId" to "z9qobuzlithic",
                    "title" to "Lithic",
                    "artist" to "Laura Misch",
                    "thumbnailUrl" to null,
                    "year" to "2026",
                    "source" to AlbumSource.QOBUZ,
                    "qobuzTrackCount" to qobuzTrackCount,
                ),
            ),
            albumCache = AlbumCache(yt, QobuzAlbumFetcherImpl(qobuz)),
            ytMusicApiClient = yt,
            prefetcher = mock(),
            playerRepository = mock(),
            musicRepository = mock(),
            streamingPreference = mock(),
            delegate = stubDelegate(),
            losslessPrefetcher = mock(),
        )
    }

    private fun stubDelegate(): TrackActionsDelegate = mock {
        on { previewState } doReturn MutableStateFlow(PreviewState.Idle as PreviewState).asStateFlow()
        on { userMessages } doReturn MutableSharedFlow<String>().asSharedFlow()
        on { downloadingIds } doReturn MutableStateFlow(emptySet<String>()).asStateFlow()
        on { downloadedIds } doReturn MutableStateFlow(emptySet<String>()).asStateFlow()
        on { previewLoadingId } doReturn MutableStateFlow<String?>(null).asStateFlow()
        on { userPlaylists } doReturn MutableStateFlow(emptyList<Playlist>())
    }

    private val couldNotLoad =
        AlbumDiscoveryStatus.Error("Check your connection and try again.", canRetry = true)
    private val notAvailable =
        AlbumDiscoveryStatus.Error("This album isn't available in your country.", canRetry = false)

    // ── What InnerTube returns ──────────────────────────────────────────────

    private fun answer(json: String) =
        InnerTubeClient.RequestOutcome(body = Json.parseToJsonElement(json).jsonObject, statusCode = 200)

    /** No connection, a timeout, a 429 or a 5xx all come back like this. */
    private val noAnswer =
        InnerTubeClient.RequestOutcome(body = null, statusCode = InnerTubeClient.RequestOutcome.STATUS_NETWORK_ERROR)

    private fun searchResponse(vararg shelves: String) =
        """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[""" +
            shelves.joinToString(",") + "]}}}}]}}}"

    private fun runs(texts: Array<out String>) =
        texts.joinToString(""",{"text":" • "},""") { """{"text":"$it"}""" }

    private fun albumTopCard(id: String, title: String, vararg subtitle: String) = """
        {"musicCardShelfRenderer":{
          "title":{"runs":[{"text":"$title","navigationEndpoint":{"browseEndpoint":{"browseId":"$id",
            "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
              "pageType":"MUSIC_PAGE_TYPE_ALBUM"}}}}}]},
          "subtitle":{"runs":[${runs(subtitle)}]}}}
    """.trimIndent()

    private val artistTopCard = """
        {"musicCardShelfRenderer":{
          "title":{"runs":[{"text":"Laura Misch","navigationEndpoint":{"browseEndpoint":{"browseId":"UCant3y1RMmQNUV3MPIpXPVg",
            "browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{
              "pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}}]},
          "subtitle":{"runs":[{"text":"Artist"}]}}}
    """.trimIndent()

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

    private val lithicTopCard = albumTopCard("MPREb_lithic", "Lithic", "Album", "Laura Misch", "2026")

    /** The YouTube Music album page for "Lithic", trimmed to two tracks. */
    private val lithicPage = Json.parseToJsonElement(
        """
        {"contents":{"twoColumnBrowseResultsRenderer":{
          "tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            {"musicResponsiveHeaderRenderer":{
              "title":{"runs":[{"text":"Lithic"}]},
              "straplineTextOne":{"runs":[{"text":"Laura Misch",
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCant3y1RMmQNUV3MPIpXPVg"}}}]},
              "subtitle":{"runs":[{"text":"Album"},{"text":" • "},{"text":"2026"}]}}}
          ]}}}}],
          "secondaryContents":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"contents":[
              {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"lithic01"},
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Hide To Seek"}]}}}],
                "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:41"}]}}}]}},
              {"musicResponsiveListItemRenderer":{"playlistItemData":{"videoId":"lithic02"},
                "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Portals"}]}}}],
                "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"4:02"}]}}}]}}
            ]}}
          ]}}
        }}}
        """.trimIndent(),
    ).jsonObject

    // ── The flows ───────────────────────────────────────────────────────────

    /** M1: a search that never came back is "try again", not "not in your country". */
    @Test fun `a YouTube search that doesn't answer offers Retry, and Retry asks again`() = runTest {
        whenever(innerTube.searchWithStatus(any(), anyOrNull()))
            .thenReturn(noAnswer, answer(searchResponse(lithicTopCard)))
        whenever(innerTube.browse(eq("MPREb_lithic"), anyOrNull())).thenReturn(lithicPage)
        val vm = vm()
        advanceUntilIdle()

        assertEquals(couldNotLoad, vm.uiState.value.status)

        vm.retry()
        advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        assertEquals(listOf("lithic01", "lithic02"), vm.uiState.value.tracks.map { it.videoId })
        verify(innerTube, times(2)).searchWithStatus(any(), anyOrNull())
    }

    /** M2: an album page that never came back is a failed load: Retry, and nothing kept for 30 minutes. */
    @Test fun `a YouTube album page that doesn't load offers Retry, and isn't kept`() = runTest {
        whenever(innerTube.searchWithStatus(any(), anyOrNull())).thenReturn(answer(searchResponse(lithicTopCard)))
        whenever(innerTube.browse(eq("MPREb_lithic"), anyOrNull())).thenReturn(null, lithicPage)
        val vm = vm()
        advanceUntilIdle()

        assertEquals(couldNotLoad, vm.uiState.value.status)

        vm.retry()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(AlbumDiscoveryStatus.Fresh, state.status)
        assertEquals("Lithic", state.hero.title)
        assertEquals(listOf("lithic01", "lithic02"), state.tracks.map { it.videoId })
        verify(innerTube, times(2)).browse(eq("MPREb_lithic"), anyOrNull())
        // Retry went straight back to the YouTube album; no second search.
        verify(innerTube, times(1)).searchWithStatus(any(), anyOrNull())
    }

    /** M4: the album sits on the shelf behind another album by the same artist. */
    @Test fun `the album is found behind another album by the same artist`() = runTest {
        whenever(innerTube.searchWithStatus(any(), anyOrNull())).thenReturn(
            answer(
                searchResponse(
                    artistTopCard,
                    flatAlbumRow("MPREb_sky", "Sample The Sky", "Album", "Laura Misch", "2023"),
                    flatAlbumRow("MPREb_lithic", "Lithic", "Album", "Laura Misch", "2026"),
                ),
            ),
        )
        whenever(innerTube.browse(eq("MPREb_lithic"), anyOrNull())).thenReturn(lithicPage)
        val vm = vm()
        advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        verify(innerTube).browse(eq("MPREb_lithic"), anyOrNull())
        verify(innerTube, never()).browse(eq("MPREb_sky"), anyOrNull())
    }

    /** M3: YouTube's top card is the title track's single; the album is on the shelf. */
    @Test fun `a single that shares the album's title is passed over for the album`() = runTest {
        whenever(innerTube.searchWithStatus(any(), anyOrNull())).thenReturn(
            answer(
                searchResponse(
                    albumTopCard("MPREb_single", "Lithic", "Single", "Laura Misch", "2026"),
                    flatAlbumRow("MPREb_lithic", "Lithic", "Album", "Laura Misch", "2026"),
                ),
            ),
        )
        whenever(innerTube.browse(eq("MPREb_lithic"), anyOrNull())).thenReturn(lithicPage)
        val vm = vm()
        advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        verify(innerTube, never()).browse(eq("MPREb_single"), anyOrNull())
    }

    @Test fun `a single alone is not taken`() = runTest {
        whenever(innerTube.searchWithStatus(any(), anyOrNull())).thenReturn(
            answer(searchResponse(albumTopCard("MPREb_single", "Lithic", "Single", "Laura Misch", "2026"))),
        )
        val vm = vm()
        advanceUntilIdle()

        assertEquals(notAvailable, vm.uiState.value.status)
        verify(innerTube, never()).browse(any(), anyOrNull())
    }

    /** The Home card was a one-track Qobuz single (Top Albums lists those): YouTube's single is its copy. */
    @Test fun `a Qobuz single opens YouTube's single`() = runTest {
        whenever(innerTube.searchWithStatus(any(), anyOrNull())).thenReturn(
            answer(searchResponse(albumTopCard("MPREb_single", "Lithic", "Single", "Laura Misch", "2026"))),
        )
        whenever(innerTube.browse(eq("MPREb_single"), anyOrNull())).thenReturn(lithicPage)
        val vm = vm(qobuzTrackCount = 1)
        advanceUntilIdle()

        assertEquals(AlbumDiscoveryStatus.Fresh, vm.uiState.value.status)
        verify(innerTube).browse(eq("MPREb_single"), anyOrNull())
    }
}
