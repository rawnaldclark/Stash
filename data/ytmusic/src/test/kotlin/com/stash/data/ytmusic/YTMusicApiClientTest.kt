package com.stash.data.ytmusic

import com.stash.core.model.SyncResult
import com.stash.data.ytmusic.model.MusicVideoType
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import com.stash.data.ytmusic.InnerTubeClient.RequestOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Parser-contract tests for [YTMusicApiClient.searchAll].
 *
 * Tests stub [InnerTubeClient.search] with fixture JSON that mirrors the real
 * InnerTube `tabbedSearchResultsRenderer` schema. The fixtures are synthetic
 * but structurally valid — they exercise the same renderer paths the parser
 * walks against real responses. When real recordings become available (see
 * Phase 9 of the plan), swap the fixtures — no parser changes should be needed.
 */
class YTMusicApiClientTest {

    /** Loads a fixture file from `src/test/resources/fixtures/`. */
    private fun loadFixture(name: String): String =
        this::class.java.classLoader!!
            .getResourceAsStream("fixtures/$name")!!
            .bufferedReader()
            .use { it.readText() }

    /**
     * Builds a [YTMusicApiClient] whose underlying [InnerTubeClient] returns
     * the given JSON from any `search(query)` call.
     */
    private fun fakeClient(responseJson: String): YTMusicApiClient {
        val inner = mock<InnerTubeClient>()
        val parsed = Json.parseToJsonElement(responseJson).jsonObject
        runBlocking { whenever(inner.search(any())).thenReturn(parsed) }
        return YTMusicApiClient(inner)
    }

    /**
     * Builds a [YTMusicApiClient] whose underlying [InnerTubeClient] returns
     * the given JSON from any `browse(browseId)` call. Used by the
     * `getArtist` tests since that method walks a browse response, not a
     * search response.
     */
    private fun fakeBrowseClient(responseJson: String): YTMusicApiClient {
        val inner = mock<InnerTubeClient>()
        val parsed = Json.parseToJsonElement(responseJson).jsonObject
        runBlocking { whenever(inner.browse(any())).thenReturn(parsed) }
        return YTMusicApiClient(inner)
    }

    @Test
    fun `searchAll returns top artists songs albums for artist query`() = runTest {
        val client = fakeClient(loadFixture("search_artist.json"))

        val result = client.searchAll("lootpack")

        // Section ordering must be Top, Songs, Artists, Albums
        val kinds = result.sections.map { it::class.simpleName }
        assertEquals(listOf("Top", "Songs", "Artists", "Albums"), kinds)

        val top = result.sections.first() as SearchResultSection.Top
        assertTrue(
            "top result should be an ArtistTop, was ${top.item::class.simpleName}",
            top.item is TopResultItem.ArtistTop,
        )

        val songs = result.sections[1] as SearchResultSection.Songs
        // Fixture ships 6 songs; parser must cap at 4 per the Search tab spec.
        assertEquals(
            "songs list must be capped at 4 even when the shelf has more",
            4,
            songs.tracks.size,
        )
        assertTrue(
            "first song videoId must be non-blank",
            songs.tracks.first().videoId.isNotBlank(),
        )
    }

    @Test
    fun `searchAll returns track as top when query matches single song`() = runTest {
        val client = fakeClient(loadFixture("search_track.json"))

        val result = client.searchAll("never gonna give")

        val top = result.sections.first() as SearchResultSection.Top
        assertTrue(
            "top result should be a TrackTop, was ${top.item::class.simpleName}",
            top.item is TopResultItem.TrackTop,
        )
    }

    @Test
    fun `searchAll returns empty sections list for zero-result query`() = runTest {
        val client = fakeClient(loadFixture("search_empty.json"))

        val result = client.searchAll("zzzzqqqqxxxxvvvv")

        assertTrue(
            "expected empty sections, got ${result.sections.map { it::class.simpleName }}",
            result.sections.isEmpty(),
        )
    }

    @Test
    fun `searchAll caps Songs shelf at 4 items even if fixture has more`() = runTest {
        // search_artist.json ships 6 songs; parser must cap at 4 per spec §3.2.
        val client = fakeClient(loadFixture("search_artist.json"))

        val result = client.searchAll("lootpack")

        val songs = result.sections.filterIsInstance<SearchResultSection.Songs>().single()
        assertEquals(4, songs.tracks.size)
    }

    @Test
    fun `searchAll joins collab artists on top-card track with comma-space`() = runTest {
        // search_track.json's top card has two artist runs (UC* browseIds) joined
        // by " & " plus an album run (MPREb_* browseId). Parser must classify by
        // endpoint prefix and emit artists joined by ", " rather than treating the
        // second artist as the album.
        val client = fakeClient(loadFixture("search_track.json"))

        val result = client.searchAll("never gonna give")

        val top = result.sections.first() as SearchResultSection.Top
        val trackTop = top.item as TopResultItem.TrackTop
        assertEquals("Rick Astley, John Smith", trackTop.track.artist)
        assertEquals("Whenever You Need Somebody", trackTop.track.album)
    }

    @Test
    fun `getArtist returns full profile for rich artist`() = runTest {
        val client = fakeBrowseClient(loadFixture("artist_rich.json"))

        val profile = client.getArtist("UCLOOTPACKID1")

        assertEquals("Lootpack", profile.name)
        assertTrue(
            "avatarUrl should start with https://, was ${profile.avatarUrl}",
            profile.avatarUrl!!.startsWith("https://"),
        )
        // Lock in "largest thumbnail wins" for the avatar: the fixture ships
        // both a 48×48 and a 544×544 variant. ArtUrlUpgrader additionally
        // normalizes lh3 URLs to `=w544-h544`, so "544" must appear regardless
        // of which variant was picked — but the picker must NOT have chosen
        // the 48 variant and then had its size token stripped (which would
        // still contain "544" via the upgrader). We assert on the original
        // `artist-avatar-large` path token to pin the picker to the 544 src.
        assertTrue(
            "avatar should be the 544 variant, was ${profile.avatarUrl}",
            profile.avatarUrl!!.contains("artist-avatar-large") &&
                profile.avatarUrl!!.contains("544"),
        )
        assertTrue(
            "popular.size should be in 5..10, was ${profile.popular.size}",
            profile.popular.size in 5..10,
        )
        assertTrue("albums should not be empty", profile.albums.isNotEmpty())
        assertTrue("singles should not be empty", profile.singles.isNotEmpty())
        assertTrue("related should not be empty", profile.related.isNotEmpty())
        assertTrue(
            "subscribersText should contain 'subscriber', was '${profile.subscribersText}'",
            profile.subscribersText?.contains("subscriber", ignoreCase = true) == true,
        )
    }

    @Test
    fun `getArtist tolerates sparse artist with only popular shelf`() = runTest {
        val client = fakeBrowseClient(loadFixture("artist_sparse.json"))

        val profile = client.getArtist("UCSPARSEID1")

        // Lock in sparse-header behaviour: even when the rest of the page is
        // empty we still extract name and subscriber text from the header.
        assertEquals("Obscure Artist", profile.name)
        assertTrue(
            "subscribersText should contain 'subscriber', was '${profile.subscribersText}'",
            profile.subscribersText?.contains("subscriber", ignoreCase = true) == true,
        )
        assertTrue("popular should not be empty", profile.popular.isNotEmpty())
        assertTrue("albums should be empty", profile.albums.isEmpty())
        assertTrue("singles should be empty", profile.singles.isEmpty())
        assertTrue("related should be empty", profile.related.isEmpty())
    }

    // NOTE: The three hand-built `getAlbum` fixture tests that used to live here
    // were written against a guessed JSON shape (`musicDetailHeaderRenderer` +
    // single-column layout) that does NOT match what InnerTube actually returns
    // for an album browse. The real shape — captured via on-device logcat
    // diagnostic on 2026-04-17 — uses `twoColumnBrowseResultsRenderer` +
    // `musicResponsiveHeaderRenderer`. Rather than keep passing-but-wrong tests,
    // they're deleted. Re-add with real captured responses if test coverage
    // becomes important for this parser.

    /**
     * Builds a minimal `musicResponsiveListItemRenderer` JSON object with the
     * fields [parseTrackFromRenderer] reads. Assembled via
     * [buildJsonObject] so the brace-nesting is enforced by Kotlin rather
     * than by me counting curly braces.
     */
    private fun listItemRenderer(
        videoId: String,
        title: String,
        artist: String,
        mvt: String,
    ) = buildJsonObject {
        putJsonObject("musicResponsiveListItemRenderer") {
            putJsonObject("playlistItemData") { put("videoId", videoId) }
            putJsonArray("flexColumns") {
                addJsonObject {
                    putJsonObject("musicResponsiveListItemFlexColumnRenderer") {
                        putJsonObject("text") {
                            putJsonArray("runs") {
                                addJsonObject { put("text", title) }
                            }
                        }
                    }
                }
                addJsonObject {
                    putJsonObject("musicResponsiveListItemFlexColumnRenderer") {
                        putJsonObject("text") {
                            putJsonArray("runs") {
                                addJsonObject { put("text", artist) }
                            }
                        }
                    }
                }
            }
            putJsonObject("overlay") {
                putJsonObject("musicItemThumbnailOverlayRenderer") {
                    putJsonObject("content") {
                        putJsonObject("musicPlayButtonRenderer") {
                            putJsonObject("playNavigationEndpoint") {
                                putJsonObject("watchEndpoint") {
                                    putJsonObject("watchEndpointMusicSupportedConfigs") {
                                        putJsonObject("watchEndpointMusicConfig") {
                                            put("musicVideoType", mvt)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `getPlaylistTracks threads musicVideoType from browse response`() = runTest {
        // Single-column browse response containing two tracks: an ATV
        // (Topic-channel audio) song and an OMV (official music video).
        // The OMV is the Mode B canonicalization trigger — a YT-library
        // import carrying OMV should later be reconciled to its ATV
        // equivalent. This test only pins the parser contract.
        val fixture = buildJsonObject {
            putJsonObject("contents") {
                putJsonObject("singleColumnBrowseResultsRenderer") {
                    putJsonArray("tabs") {
                        addJsonObject {
                            putJsonObject("tabRenderer") {
                                putJsonObject("content") {
                                    putJsonObject("sectionListRenderer") {
                                        putJsonArray("contents") {
                                            addJsonObject {
                                                putJsonObject("musicShelfRenderer") {
                                                    putJsonArray("contents") {
                                                        add(
                                                            listItemRenderer(
                                                                videoId = "XzNWRmqibNE",
                                                                title = "Smooth Criminal",
                                                                artist = "Michael Jackson",
                                                                mvt = "MUSIC_VIDEO_TYPE_ATV",
                                                            ),
                                                        )
                                                        add(
                                                            listItemRenderer(
                                                                videoId = "h_D3VFfhvs4",
                                                                title = "Smooth Criminal (Official Video)",
                                                                artist = "Michael Jackson",
                                                                mvt = "MUSIC_VIDEO_TYPE_OMV",
                                                            ),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()

        val client = fakeBrowseClient(fixture)
        val result = client.getPlaylistTracks("anyPlaylistId")
        assertTrue(
            "fixture should parse as SyncResult.Success, got $result",
            result is SyncResult.Success,
        )
        val tracks = (result as SyncResult.Success).data
        assertEquals(2, tracks.size)

        val atv = tracks.firstOrNull { it.videoId == "XzNWRmqibNE" }
        assertNotNull("ATV track must be parsed", atv)
        assertEquals(MusicVideoType.ATV, atv!!.musicVideoType)

        val omv = tracks.firstOrNull { it.videoId == "h_D3VFfhvs4" }
        assertNotNull("OMV track must be parsed — this is the Mode B canonicalization trigger", omv)
        assertEquals(MusicVideoType.OMV, omv!!.musicVideoType)
    }

    @Test fun `extractContinuationToken finds token in twoColumn initial response`() {
        val json = loadFixture("playlist_long_page1.json")
        val parsed = Json.parseToJsonElement(json).jsonObject
        val client = fakeBrowseClient("{}")  // any client; we call the helper directly
        val token = client.extractContinuationTokenForTest(parsed)
        assertNotNull("page1 should have a continuation token", token)
        assertTrue("token should be non-empty", token!!.isNotEmpty())
    }

    @Test fun `extractContinuationToken finds token in continuation response`() {
        val json = loadFixture("playlist_long_page2.json")
        val parsed = Json.parseToJsonElement(json).jsonObject
        val client = fakeBrowseClient("{}")
        val token = client.extractContinuationTokenForTest(parsed)
        // page2 may or may not have a token depending on whether playlist has >2 pages —
        // assert non-throw rather than non-null.
        assertTrue(token == null || token.isNotEmpty())
    }

    @Test fun `extractContinuationToken returns null for response with no token`() {
        val noToken = """{"contents":{"twoColumnBrowseResultsRenderer":{"secondaryContents":{"sectionListRenderer":{"contents":[{"musicPlaylistShelfRenderer":{"contents":[]}}]}}}}}"""
        val parsed = Json.parseToJsonElement(noToken).jsonObject
        val client = fakeBrowseClient("{}")
        assertNull(client.extractContinuationTokenForTest(parsed))
    }

    @Test fun `extractContinuationToken finds token in appendContinuationItemsAction shape`() {
        // Our captured playlist_long_page2 actually uses onResponseReceivedActions[0].appendContinuationItemsAction
        // with token at <last item>.continuationItemRenderer.continuationEndpoint.continuationCommand.token.
        val json = loadFixture("playlist_long_page2.json")
        val parsed = Json.parseToJsonElement(json).jsonObject
        val client = fakeBrowseClient("{}")
        val token = client.extractContinuationTokenForTest(parsed)
        assertNotNull("appendContinuationItemsAction shape should yield a token", token)
        assertTrue("token should be non-empty", token!!.isNotEmpty())
    }

    @Test fun `parseContinuationPage parses tracks from playlist continuation`() {
        val json = loadFixture("playlist_long_page2.json")
        val parsed = Json.parseToJsonElement(json).jsonObject
        val client = fakeBrowseClient("{}")
        val tracks = client.parseContinuationPageForTest(parsed)
        assertTrue("page2 should yield at least one track", tracks.isNotEmpty())
        tracks.forEach { assertNotNull(it.videoId); assertTrue(it.videoId.isNotEmpty()) }
    }

    @Test fun `parseContinuationPage parses tracks from liked-songs continuation`() {
        val json = loadFixture("liked_songs_page2.json")
        val parsed = Json.parseToJsonElement(json).jsonObject
        val client = fakeBrowseClient("{}")
        val tracks = client.parseContinuationPageForTest(parsed)
        assertTrue("liked songs page2 should yield at least one track", tracks.isNotEmpty())
        tracks.forEach { assertNotNull(it.videoId); assertTrue(it.videoId.isNotEmpty()) }
    }

    @Test
    fun `normalizeArtistBrowseId strips MPLA only before UC`() {
        // `MPLAUC…` is the music-channel variant — strip `MPLA` to expose the
        // bare `UC…` channel id that the cache uses as its stable key.
        assertEquals("UCabc", normalizeArtistBrowseId("MPLAUCabc"))
        // A raw channel id passes through untouched.
        assertEquals("UCabc", normalizeArtistBrowseId("UCabc"))
        // Unknown `MPLA`-prefixed ids (e.g. `MPLARZ…`) must NOT be truncated —
        // the old broad check produced "RZabc" here, which was wrong.
        assertEquals("MPLARZabc", normalizeArtistBrowseId("MPLARZabc"))
        assertEquals("MPLAxyz", normalizeArtistBrowseId("MPLAxyz"))
    }

    @Test fun `extractExpectedTrackCount handles playlist_long_page1 (Home Mix has no header)`() {
        val json = loadFixture("playlist_long_page1.json")
        val parsed = Json.parseToJsonElement(json).jsonObject
        val client = fakeBrowseClient("{}")
        val count = client.extractExpectedTrackCountForTest(parsed)
        // This particular fixture is a Home Mix — no header, so null is expected.
        // Real user-playlist fixtures with a header are exercised via the synthetic tests below.
        assertNull(count)
    }

    @Test fun `extractExpectedTrackCount returns null for liked songs (no header)`() {
        val json = loadFixture("liked_songs_page1.json")
        val parsed = Json.parseToJsonElement(json).jsonObject
        val client = fakeBrowseClient("{}")
        val count = client.extractExpectedTrackCountForTest(parsed)
        assertNull(count)
    }

    @Test fun `extractExpectedTrackCount handles plain '42 songs' (no comma)`() {
        val synthetic = """
        {"header":{"musicDetailHeaderRenderer":{"secondSubtitle":{"runs":[
            {"text":"42 songs"},{"text":" • "},{"text":"3:14:00"}
        ]}}}}""".trimIndent()
        val parsed = Json.parseToJsonElement(synthetic).jsonObject
        val client = fakeBrowseClient("{}")
        assertEquals(42, client.extractExpectedTrackCountForTest(parsed))
    }

    @Test fun `extractExpectedTrackCount handles 'X tracks' variant`() {
        val synthetic = """
        {"header":{"musicDetailHeaderRenderer":{"secondSubtitle":{"runs":[
            {"text":"7 tracks"}
        ]}}}}""".trimIndent()
        val parsed = Json.parseToJsonElement(synthetic).jsonObject
        val client = fakeBrowseClient("{}")
        assertEquals(7, client.extractExpectedTrackCountForTest(parsed))
    }

    @Test fun `extractExpectedTrackCount parses 1234 songs with comma from modern editable header`() {
        val synthetic = """
        {"header":{"musicEditablePlaylistDetailHeaderRenderer":{"header":{"musicResponsiveHeaderRenderer":{"secondSubtitle":{"runs":[
            {"text":"1,234 songs"}
        ]}}}}}}""".trimIndent()
        val parsed = Json.parseToJsonElement(synthetic).jsonObject
        val client = fakeBrowseClient("{}")
        assertEquals(1234, client.extractExpectedTrackCountForTest(parsed))
    }

    // ── paginateBrowse helpers ────────────────────────────────────────────

    /**
     * Builds an InnerTubeClient mock whose browseWithStatus(continuation)
     * returns scripted outcomes in order. A null entry maps to a transient
     * (null body, 503) outcome so callers can simply pass null for "transient
     * failure here."
     */
    private fun scriptedInner(vararg outcomes: RequestOutcome?): InnerTubeClient {
        val inner = mock<InnerTubeClient>()
        var i = 0
        runBlocking {
            whenever(inner.browseWithStatus(any())).thenAnswer {
                val r = outcomes.getOrNull(i) ?: RequestOutcome(null, 503)
                i++
                r ?: RequestOutcome(null, 503)
            }
        }
        return inner
    }

    private fun success(json: String): RequestOutcome =
        RequestOutcome(Json.parseToJsonElement(json).jsonObject, 200)
    private val transient503 = RequestOutcome(body = null, statusCode = 503)
    private val permanent401 = RequestOutcome(body = null, statusCode = 401)

    private fun pageWithToken(token: String, marker: List<String>): JsonObject =
        Json.parseToJsonElement("""
            {
              "marker": [${marker.joinToString(",") { "\"$it\"" }}],
              "continuationContents": {
                "musicPlaylistShelfContinuation": {
                  "contents": [],
                  "continuations": [{"nextContinuationData": {"continuation": "$token"}}]
                }
              }
            }
        """.trimIndent()).jsonObject

    private fun pageWithoutToken(marker: List<String>): JsonObject =
        Json.parseToJsonElement("""
            {
              "marker": [${marker.joinToString(",") { "\"$it\"" }}],
              "continuationContents": {
                "musicPlaylistShelfContinuation": {"contents": []}
              }
            }
        """.trimIndent()).jsonObject

    // ── paginateBrowse tests ──────────────────────────────────────────────

    @Test fun `paginateBrowse stops when first response has no token`() = runTest {
        val initial = Json.parseToJsonElement("""{"contents":{}}""").jsonObject
        val inner = scriptedInner()
        val client = YTMusicApiClient(inner)
        val result = client.paginateBrowseForTest(initial) { listOf("page0-item") }
        assertEquals(listOf("page0-item"), result.items)
        assertEquals(1, result.pagesFetched)
        assertFalse(result.partial)
    }

    @Test fun `paginateBrowse follows token to page 2 then stops`() = runTest {
        val page1 = pageWithToken("ABC", listOf("a", "b"))
        val page2 = pageWithoutToken(listOf("c", "d"))
        val inner = scriptedInner(RequestOutcome(page2, 200))
        val client = YTMusicApiClient(inner)
        val result = client.paginateBrowseForTest(page1) { jsonObj ->
            jsonObj["marker"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
        }
        assertEquals(listOf("a","b","c","d"), result.items)
        assertEquals(2, result.pagesFetched)
        assertFalse(result.partial)
    }

    @Test fun `paginateBrowse retries transient failure once then succeeds`() = runTest {
        val page1 = pageWithToken("ABC", listOf("a"))
        val page2 = pageWithoutToken(listOf("b"))
        val inner = scriptedInner(transient503, RequestOutcome(page2, 200))
        val client = YTMusicApiClient(inner)
        val result = client.paginateBrowseForTest(page1) { jsonObj ->
            jsonObj["marker"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
        }
        assertEquals(listOf("a","b"), result.items)
        assertFalse(result.partial)
    }

    @Test fun `paginateBrowse marks partial after exhausting retries`() = runTest {
        val page1 = pageWithToken("ABC", listOf("a"))
        val inner = scriptedInner(transient503, transient503, transient503)
        val client = YTMusicApiClient(inner)
        val result = client.paginateBrowseForTest(page1) { jsonObj ->
            jsonObj["marker"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
        }
        assertEquals(listOf("a"), result.items)
        assertTrue(result.partial)
        assertNotNull(result.partialReason)
    }

    @Test fun `paginateBrowse stops at MAX_PAGES safety cap`() = runTest {
        val page = pageWithToken("ABC", listOf("x"))
        val inner = mock<InnerTubeClient>()
        runBlocking {
            whenever(inner.browseWithStatus(any())).thenReturn(RequestOutcome(page, 200))
        }
        val client = YTMusicApiClient(inner)
        val result = client.paginateBrowseForTest(page) { jsonObj ->
            jsonObj["marker"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
        }
        assertEquals(YTMusicApiClient.MAX_PAGES, result.pagesFetched)
        assertTrue(result.partial)
        assertTrue(result.partialReason!!.contains("MAX_PAGES"))
    }

    @Test fun `paginateBrowse does not retry on 4xx`() = runTest {
        val page1 = pageWithToken("ABC", listOf("a"))
        val inner = mock<InnerTubeClient>()
        var calls = 0
        runBlocking {
            whenever(inner.browseWithStatus(any())).thenAnswer {
                calls++
                RequestOutcome(body = null, statusCode = 401)
            }
        }
        val client = YTMusicApiClient(inner)
        val result = client.paginateBrowseForTest(page1) { jsonObj ->
            jsonObj["marker"]?.jsonArray?.map { (it as JsonPrimitive).content } ?: emptyList()
        }
        assertEquals(1, calls)  // no retries
        assertTrue(result.partial)
        assertEquals(listOf("a"), result.items)
    }
}
