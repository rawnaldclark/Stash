package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.TopResultItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
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
}
