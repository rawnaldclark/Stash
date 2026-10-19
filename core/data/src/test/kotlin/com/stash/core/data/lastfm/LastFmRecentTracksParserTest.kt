package com.stash.core.data.lastfm

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class LastFmRecentTracksParserTest {

    private fun parse(raw: String) = LastFmRecentTracksParser.parse(Json.parseToJsonElement(raw).jsonObject)

    private val placeholder = "https://lastfm.freetls.fastly.net/i/u/300x300/2a96cbd8b46e442fc41c2b86b821562f.png"

    @Test fun `reads scrobbles and paging, skipping the now-playing row`() {
        val page = parse(
            """
            {"recenttracks":{"track":[
              {"artist":{"#text":"Slow Joy"},"name":"Drowning","album":{"#text":"A Joy"},"@attr":{"nowplaying":"true"},
               "image":[{"size":"extralarge","#text":"https://lastfm.freetls.fastly.net/i/u/300x300/aa.jpg"}]},
              {"artist":{"mbid":"","#text":"Slow Joy"},"name":"Drowning","album":{"#text":"A Joy"},
               "image":[{"size":"small","#text":"https://lastfm.freetls.fastly.net/i/u/34s/aa.jpg"},
                        {"size":"extralarge","#text":"https://lastfm.freetls.fastly.net/i/u/300x300/aa.jpg"}],
               "date":{"uts":"1792387626","#text":"19 Oct 2026, 05:27"}},
              {"artist":{"#text":"Warpaint"},"name":"Common Blue","album":{"#text":""},
               "image":[{"size":"extralarge","#text":"$placeholder"}],
               "date":{"uts":"1792387401"}}
            ],"@attr":{"user":"rawnclark","totalPages":"860","page":"3","perPage":"2","total":"1719"}}}
            """,
        )
        assertThat(page.page).isEqualTo(3)
        assertThat(page.totalPages).isEqualTo(860)
        assertThat(page.total).isEqualTo(1719)
        assertThat(page.scrobbles.map { it.uts }).containsExactly(1792387626L, 1792387401L).inOrder()
        val first = page.scrobbles[0]
        assertThat(first.artist).isEqualTo("Slow Joy")
        assertThat(first.title).isEqualTo("Drowning")
        assertThat(first.album).isEqualTo("A Joy")
        assertThat(first.image).contains("aa.jpg")
        // Only the star placeholder: no image.
        assertThat(page.scrobbles[1].image).isNull()
    }

    @Test fun `a single track comes as an object, not an array`() {
        val page = parse(
            """{"recenttracks":{"track":{"artist":{"#text":"A"},"name":"B","date":{"uts":"5"}},"@attr":{"page":"1","totalPages":"1","total":"1"}}}""",
        )
        assertThat(page.scrobbles).hasSize(1)
        assertThat(page.scrobbles[0].uts).isEqualTo(5L)
    }

    @Test fun `the extended artist shape and an empty range both read`() {
        val extended = parse(
            """{"recenttracks":{"track":[{"artist":{"name":"Extended"},"name":"T","date":{"uts":"9"}}],"@attr":{"total":"1"}}}""",
        )
        assertThat(extended.scrobbles.single().artist).isEqualTo("Extended")
        val empty = parse("""{"recenttracks":{"track":[],"@attr":{"page":"1","totalPages":"0","total":"0"}}}""")
        assertThat(empty.scrobbles).isEmpty()
        assertThat(empty.total).isEqualTo(0)
    }

    @Test fun `rows without a title, artist or date are dropped`() {
        val page = parse(
            """{"recenttracks":{"track":[
                {"artist":{"#text":""},"name":"T","date":{"uts":"1"}},
                {"artist":{"#text":"A"},"name":"","date":{"uts":"2"}},
                {"artist":{"#text":"A"},"name":"T"}
            ]}}""",
        )
        assertThat(page.scrobbles).isEmpty()
    }

    @Test fun `registration date from user getInfo`() {
        val root = Json.parseToJsonElement("""{"user":{"name":"rawnclark","registered":{"unixtime":"1776526202","#text":1776526202}}}""").jsonObject
        assertThat(LastFmRecentTracksParser.registeredAt(root)).isEqualTo(1776526202L)
        assertThat(LastFmRecentTracksParser.registeredAt(Json.parseToJsonElement("""{"user":{}}""").jsonObject)).isNull()
    }
}
