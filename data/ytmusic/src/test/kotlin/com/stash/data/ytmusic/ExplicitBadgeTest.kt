package com.stash.data.ytmusic

import com.stash.core.auth.TokenManager
import com.stash.core.auth.youtube.YouTubeCookieHelper
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * YouTube Music's explicit badge, and the twin preference built on it.
 *
 * Fixtures are trimmed from live InnerTube WEB_REMIX responses (2026-10-26) for
 * "Kanye West Father Stretch My Hands Pt. 1", whose explicit (`wuO4_P_8p-Q`) and
 * clean (`_MIsk8VoNhM`) copies share title, artist, album name and length:
 *  - `search_songs_explicit_twins.json`: the songs-filtered search's first four
 *    rows: the explicit twin (badged), the clean twin, "All Falls Down"
 *    (unbadged), "Runaway (feat. Pusha T)" (badged).
 *  - `search_flat_explicit_top_card.json`: the unfiltered (signed-out, flat)
 *    search: the explicit song as the top card, the album row, and the clean
 *    twin as the only Song row.
 */
class ExplicitBadgeTest {

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(
        this::class.java.classLoader!!.getResourceAsStream("fixtures/$name")!!
            .bufferedReader().use { it.readText() },
    ).jsonObject

    private val songsRows: JsonArray by lazy {
        fixture("search_songs_explicit_twins.json")
            .navigatePath("contents", "tabbedSearchResultsRenderer", "tabs")!!.jsonArray[0].jsonObject
            .navigatePath("tabRenderer", "content", "sectionListRenderer", "contents")!!.jsonArray[0].jsonObject
            .navigatePath("musicShelfRenderer", "contents")!!.jsonArray
    }

    /** A songs-filtered search response holding the fixture's rows [rows], in that order. */
    private fun songsResponse(vararg rows: Int): JsonObject = Json.parseToJsonElement(
        """{"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            {"musicShelfRenderer":{"title":{"runs":[{"text":"Songs"}]},"contents":[${rows.joinToString(",") { songsRows[it].toString() }}]}}
        ]}}}}]}}}""",
    ).jsonObject

    private fun innerTube() = InnerTubeClient(OkHttpClient(), mock<TokenManager>(), mock<YouTubeCookieHelper>())

    private fun pick(response: JsonObject, explicit: Boolean?): String? =
        (innerTube().canonicalFromSongsSearch(response, "test", explicit) as? CanonicalLookup.Found)?.match?.videoId

    // ── the badge ────────────────────────────────────────────────────────

    @Test fun `a search row's badge reads as explicit, its absence as unknown`() {
        val explicitRow = songsRows[0].jsonObject["musicResponsiveListItemRenderer"]!!.jsonObject
        val cleanRow = songsRows[1].jsonObject["musicResponsiveListItemRenderer"]!!.jsonObject

        assertTrue(hasExplicitBadge(explicitRow))
        assertEquals(true, explicitFromBadge(explicitRow))
        assertFalse(hasExplicitBadge(cleanRow))
        assertNull("no badge doesn't prove a song clean", explicitFromBadge(cleanRow))
        assertEquals(true, parseTrackSummaryFromListItem(explicitRow)?.explicit)
        assertNull(parseTrackSummaryFromListItem(cleanRow)?.explicit)
    }

    @Test fun `an album page header keeps its badge in subtitleBadge`() {
        // musicResponsiveHeaderRenderer of MPREb_ZIbQK9RFLQY (The Life Of Pablo, explicit), trimmed.
        val header = Json.parseToJsonElement(
            """{"title":{"runs":[{"text":"The Life Of Pablo"}]},
                "subtitleBadge":[{"musicInlineBadgeRenderer":{"icon":{"iconType":"MUSIC_EXPLICIT_BADGE"}}}]}""",
        ).jsonObject

        assertTrue(hasExplicitBadge(header))
    }

    @Test fun `the unfiltered search's top card carries the badge its clean twin row lacks`() = runTest {
        val inner = mock<InnerTubeClient>()
        val parsed = fixture("search_flat_explicit_top_card.json")
        runBlocking {
            whenever(inner.searchWithStatus(any(), anyOrNull()))
                .thenReturn(InnerTubeClient.RequestOutcome(parsed, 200))
        }

        val results = YTMusicApiClient(inner).searchAll("Kanye West Father Stretch My Hands Pt. 1")

        val top = (results.sections.filterIsInstance<SearchResultSection.Top>().single().item
            as TopResultItem.TrackTop).track
        assertEquals("wuO4_P_8p-Q", top.videoId)
        assertEquals(true, top.explicit)
        val song = results.sections.filterIsInstance<SearchResultSection.Songs>().single().tracks.single()
        assertEquals("_MIsk8VoNhM", song.videoId)
        assertNull(song.explicit)
    }

    @Test fun `synced Liked Music rows carry the badge`() {
        // A real Liked Music continuation page: Deceptacon is badged, Midnight Train isn't.
        val tracks = YTMusicApiClient(mock()).parseContinuationPageForTest(fixture("liked_songs_page2.json"))

        assertEquals(true, tracks.single { it.videoId == "-auecEXpmlM" }.explicit)
        assertNull(tracks.single { it.videoId == "jmD6foRtZL4" }.explicit)
    }

    // ── searchCanonicalLookup's twin preference ──────────────────────────

    @Test fun `the explicit twin is taken when the track is explicit or doesn't say`() {
        for (explicit in listOf(true, null)) {
            assertEquals("explicit=$explicit, explicit row first", "wuO4_P_8p-Q", pick(songsResponse(0, 1, 2), explicit))
            assertEquals("explicit=$explicit, clean row first", "wuO4_P_8p-Q", pick(songsResponse(1, 0, 2), explicit))
        }
    }

    @Test fun `the clean twin is taken when the track is known clean`() {
        assertEquals("_MIsk8VoNhM", pick(songsResponse(0, 1, 2), explicit = false))
        assertEquals("_MIsk8VoNhM", pick(songsResponse(1, 0, 2), explicit = false))
    }

    @Test fun `a row that is another song never replaces the first result`() {
        // All Falls Down (unbadged) then Runaway (badged): no twins, so the first stands.
        assertEquals("W0VnPiyXSRQ", pick(songsResponse(2, 3), explicit = true))
        assertEquals("W0VnPiyXSRQ", pick(songsResponse(2, 3), explicit = null))
        // And a badged first result stands for a clean track when it has no clean twin.
        assertEquals("VhEoCOWUtcU", pick(songsResponse(3, 2), explicit = false))
    }

    // ── what counts as a twin ────────────────────────────────────────────

    private fun key(title: String, seconds: Double, badged: Boolean, artist: String = "Mobb Deep") =
        ExplicitTwinKey(title, artist, seconds, badged)

    @Test fun `an instrumental or another artist's song of the same length is not a twin`() {
        val first = key("Shook Ones, Pt. II", 326.0, badged = false)
        val candidates = listOf(
            first,
            key("Shook Ones, Pt. II (Instrumental)", 326.0, badged = true),
            key("Shook Ones, Pt. II", 326.0, badged = true, artist = "CICI Zhu"),
        )

        assertEquals(first, preferExplicitTwin(candidates, explicit = true) { it })
    }

    @Test fun `twins agree on length within a few seconds, and an unknown length doesn't split them`() {
        val first = key("Shook Ones, Pt. II", 326.0, badged = false)

        assertEquals(
            "2 s apart: twins",
            true,
            preferExplicitTwin(listOf(first, key("shook ones,  pt. ii", 328.0, badged = true)), null) { it }?.explicitBadge,
        )
        assertEquals(
            "10 s apart: another cut",
            first,
            preferExplicitTwin(listOf(first, key("Shook Ones, Pt. II", 336.0, badged = true)), null) { it },
        )
        assertEquals(
            "a flat row has no length",
            true,
            preferExplicitTwin(listOf(first, key("Shook Ones, Pt. II", 0.0, badged = true)), null) { it }?.explicitBadge,
        )
        assertNull(preferExplicitTwin(emptyList<ExplicitTwinKey>(), null) { it })
    }
}
