package com.stash.data.spotify

import com.stash.data.spotify.SpotifyApiClient.Companion.findArtistId
import com.stash.data.spotify.SpotifyApiClient.Companion.parseArtistVisuals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The artist page's photos: finding the artist on Spotify by name, then reading its gallery and header. */
class SpotifyArtistVisualsTest {

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun artist(id: String, name: String) =
        """{"data":{"uri":"spotify:artist:$id","profile":{"name":"$name"}}}"""

    @Test fun `the exact name wins over a more relevant namesake`() {
        val root = json("""{"data":{"searchV2":{"artists":{"items":[
            ${artist("tribute", "Mazzy Star Tribute")}, ${artist("real", "Mazzy Star")}
        ]}}}}""")
        assertEquals("real", findArtistId(root, "mazzy star "))
    }

    @Test fun `the older search shape is read too`() {
        val root = json("""{"data":{"search":{"artists":{"items":[
            {"uri":"spotify:artist:abc","profile":{"name":"Beck"}}
        ]}}}}""")
        assertEquals("abc", findArtistId(root, "Beck"))
    }

    @Test fun `two different artists with the exact name, no artist`() {
        val root = json("""{"data":{"searchV2":{"artists":{"items":[
            ${artist("first", "Nirvana")}, ${artist("sixties", "Nirvana")}
        ]}}}}""")
        assertNull(findArtistId(root, "Nirvana"))
    }

    @Test fun `the same artist in both answer shapes is one artist`() {
        val root = json("""{"data":{
            "search":{"artists":{"items":[${artist("abc", "Beck")}]}},
            "searchV2":{"artists":{"items":[${artist("abc", "Beck")}]}}
        }}""")
        assertEquals("abc", findArtistId(root, "Beck"))
    }

    @Test fun `no exact match, no artist`() {
        val root = json("""{"data":{"searchV2":{"artists":{"items":[${artist("x", "Beckett")}]}}}}""")
        assertNull(findArtistId(root, "Beck"))
    }

    @Test fun `gallery takes each photo's largest size, header from headerImage data`() {
        val root = json("""{"data":{"artistUnion":{"visuals":{
            "gallery":{"items":[
                {"sources":[{"url":"https://i/1-small","width":160},{"url":"https://i/1-big","width":1280}]},
                {"sources":[{"url":"https://i/2","width":640}]}
            ]},
            "headerImage":{"data":{"sources":[{"url":"https://i/header","width":2660}]}}
        }}}}""")
        val v = parseArtistVisuals(root)!!
        assertEquals(listOf("https://i/1-big", "https://i/2"), v.gallery)
        assertEquals("https://i/header", v.headerUrl)
    }

    @Test fun `an artist with no photos gives nothing`() {
        val root = json("""{"data":{"artistUnion":{"visuals":{"gallery":{"items":[]},"headerImage":null}}}}""")
        assertNull(parseArtistVisuals(root))
    }

    /**
     * The overview answer, written from the field names the 2026-11-01 probe quoted
     * (`data.artistUnion.stats.monthlyListeners`, beside `followers`): no saved capture existed.
     */
    private fun overviewFixture(): JsonObject = json(
        javaClass.classLoader!!.getResourceAsStream("fixtures/query_artist_overview.json")!!
            .bufferedReader().use { it.readText() },
    )

    @Test fun `the overview's monthly listeners come with its photos, not its followers`() {
        val v = parseArtistVisuals(overviewFixture())!!
        assertEquals(1_734_567L, v.monthlyListeners)
        assertEquals(listOf("https://i.scdn.co/image/gallery1-big", "https://i.scdn.co/image/gallery2"), v.gallery)
        assertEquals("https://i.scdn.co/image/header", v.headerUrl)
        // The top tracks come along, to tell the artist from a namesake.
        assertEquals(listOf("Teen Age Riot", "Kool Thing", "Schizophrenia - Remastered"), v.topTrackTitles)
    }

    @Test fun `no discography, no top tracks`() {
        val root = json("""{"data":{"artistUnion":{"stats":{"monthlyListeners":523}}}}""")
        assertEquals(emptyList<String>(), parseArtistVisuals(root)!!.topTrackTitles)
    }

    @Test fun `monthly listeners without photos still count`() {
        val root = json("""{"data":{"artistUnion":{"stats":{"followers":10,"monthlyListeners":523}}}}""")
        val v = parseArtistVisuals(root)!!
        assertEquals(523L, v.monthlyListeners)
        assertEquals(emptyList<String>(), v.gallery)
        assertNull(v.headerUrl)
    }

    @Test fun `zero monthly listeners is no number`() {
        val root = json("""{"data":{"artistUnion":{"stats":{"monthlyListeners":0},
            "visuals":{"gallery":{"items":[{"sources":[{"url":"https://i/2","width":640}]}]}}}}}""")
        assertNull(parseArtistVisuals(root)!!.monthlyListeners)
        assertNull(parseArtistVisuals(json("""{"data":{"artistUnion":{"stats":{"monthlyListeners":0}}}}""")))
    }

    @Test fun `photos without stats have no monthly listeners`() {
        val root = json("""{"data":{"artistUnion":{"visuals":{
            "gallery":{"items":[{"sources":[{"url":"https://i/2","width":640}]}]}}}}}""")
        assertNull(parseArtistVisuals(root)!!.monthlyListeners)
    }
}
