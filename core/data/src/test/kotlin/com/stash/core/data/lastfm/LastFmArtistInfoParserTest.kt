package com.stash.core.data.lastfm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class LastFmArtistInfoParserTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s) as JsonObject

    @Test fun `strips read-more anchor and tags`() {
        val r = parseArtistInfo(obj("""{"artist":{"mbid":"m1","bio":{"content":
            "Real bio here. <a href=\"http://last.fm\">Read more on Last.fm</a>"}}}"""))
        assertThat(r!!.bio).isEqualTo("Real bio here.")
        assertThat(r.mbid).isEqualTo("m1")
    }

    @Test fun `placeholder bio becomes null bio`() {
        val r = parseArtistInfo(obj("""{"artist":{"mbid":"","bio":{"content":
            "<a href=\"x\">Read more on Last.fm</a>"}}}"""))
        assertThat(r!!.bio).isNull()
        assertThat(r.mbid).isNull() // blank mbid normalised to null
    }

    @Test fun `missing artist returns null`() {
        assertThat(parseArtistInfo(obj("""{"error":6,"message":"not found"}"""))).isNull()
    }

    @Test fun `short terse placeholder is nulled`() {
        val r = parseArtistInfo(obj("""{"artist":{"mbid":"m1","bio":{"content":"Metallica is an artist."}}}"""))
        assertThat(r!!.bio).isNull()
    }

    @Test fun `real bio opening with is-a-genre-artist is preserved`() {
        // Regression: the old greedy placeholder regex nulled real bios like this.
        val bio = "Bad Bunny is a Puerto Rican recording artist."
        val r = parseArtistInfo(obj("""{"artist":{"mbid":"m1","bio":{"content":"$bio"}}}"""))
        assertThat(r!!.bio).isEqualTo(bio)
    }
}
