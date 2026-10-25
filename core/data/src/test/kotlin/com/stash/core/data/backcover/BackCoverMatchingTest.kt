package com.stash.core.data.backcover

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * The back cover lookup's matching, against trimmed real responses (captured 2026-10-24 into
 * src/test/resources/backcover/).
 */
class BackCoverMatchingTest {

    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(File("src/test/resources/backcover/$name").readText()).jsonObject

    private val nevermind = "1b022e01-4da6-387b-8658-8678046e4cef"

    @Test fun `an edition and punctuation don't change an album's title`() {
        assertThat(albumTitleKey("Rumours (Super Deluxe)")).isEqualTo(albumTitleKey("Rumours"))
        assertThat(albumTitleKey("Nevermind - Remastered")).isEqualTo(albumTitleKey("Nevermind"))
        assertThat(albumTitleKey("WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?"))
            .isEqualTo(albumTitleKey("When We All Fall Asleep, Where Do We Go?"))
        assertThat(albumTitleKey("Short n' Sweet")).isEqualTo(albumTitleKey("Short n’ Sweet"))
        assertThat(albumTitleKey("Beyoncé")).isEqualTo(albumTitleKey("Beyonce"))
        assertThat(albumTitleKey("N/O/I/S/E")).isEqualTo(albumTitleKey("N / O / I / S / E"))
    }

    @Test fun `an edition named after a colon or at the end is the album`() {
        assertThat(albumTitleKey("Bakesale: Deluxe Edition")).isEqualTo(albumTitleKey("Bakesale"))
        assertThat(plainAlbumTitle("Bakesale: Deluxe Edition")).isEqualTo("Bakesale")
        assertThat(plainAlbumTitle("Abbey Road: 50th Anniversary Super Deluxe Edition")).isEqualTo("Abbey Road")
        assertThat(plainAlbumTitle("Rumours 35th Anniversary Edition")).isEqualTo("Rumours")
        assertThat(plainAlbumTitle("Pet Sounds Remastered")).isEqualTo("Pet Sounds")
        // A subtitle, a number or a lone edition word is the title's own.
        assertThat(plainAlbumTitle("OK Computer: OKNOTOK 1997 2017")).isEqualTo("OK Computer: OKNOTOK 1997 2017")
        assertThat(plainAlbumTitle("1999")).isEqualTo("1999")
        assertThat(plainAlbumTitle("Blink-182: The Mark, Tom and Travis Show")).isEqualTo("Blink-182: The Mark, Tom and Travis Show")
        assertThat(plainAlbumTitle("Deluxe")).isEqualTo("Deluxe")
        assertThat(plainAlbumTitle("Use Your Illusion 2")).isEqualTo("Use Your Illusion 2")
        // A span of years is the edition's too; a single year is the title's.
        assertThat(albumTitleKey("Songs to No One 1991–1992")).isEqualTo(albumTitleKey("Songs To No One"))
        assertThat(plainAlbumTitle("Rumours 1977")).isEqualTo("Rumours 1977")
    }

    @Test fun `a performance in the title is another album`() {
        assertThat(albumTitleKey("1989 (Taylor's Version)")).isNotEqualTo(albumTitleKey("1989"))
        assertThat(albumTitleKey("1989 (Taylor's Version)")).isEqualTo(albumTitleKey("1989 (Taylor’s version)"))
        assertThat(albumTitleKey("1989 [nightcore]")).isNotEqualTo(albumTitleKey("1989"))
    }

    @Test fun `a title that is all brackets keeps its words`() {
        assertThat(albumTitleKey("[Untitled]")).isEqualTo("untitled")
        assertThat(plainAlbumTitle("[Untitled]")).isEqualTo("[Untitled]")
    }

    @Test fun `the plain title drops the edition for the search`() {
        assertThat(plainAlbumTitle("Rumours (Super Deluxe)")).isEqualTo("Rumours")
        assertThat(plainAlbumTitle("Nevermind - Remastered")).isEqualTo("Nevermind")
        assertThat(plainAlbumTitle("AM")).isEqualTo("AM")
    }

    @Test fun `an artist matches the whole credit or one act in it`() {
        assertThat(artistMatches("Nirvana", "Nirvana")).isTrue()
        assertThat(artistMatches("Kendrick Lamar", "Kendrick Lamar feat. SZA")).isTrue()
        assertThat(artistMatches("Tyler", "Tyler, The Creator")).isTrue()
        assertThat(artistMatches("Beyonce", "Beyoncé")).isTrue()
        assertThat(artistMatches("Nirvana", "Nirvana Tribute Band Ensemble")).isTrue()
        assertThat(artistMatches("Nirvana", "Pink Floyd")).isFalse()
        assertThat(artistMatches("Arc", "Arctic Monkeys")).isFalse()
    }

    @Test fun `an ISRC finds the album the song is on, not its single or a compilation`() {
        val body = fixture("isrc_smells_like_teen_spirit.json")
        assertThat(releaseGroupFromIsrcSearch(body, "Nevermind (Remastered)")).isEqualTo(nevermind)
        assertThat(releaseGroupFromIsrcSearch(body, "Nevermind")).isEqualTo(nevermind)
    }

    @Test fun `an ISRC on an album titled otherwise finds nothing`() {
        // Played from a playlist named after something else: the title search takes over.
        assertThat(releaseGroupFromIsrcSearch(fixture("isrc_smells_like_teen_spirit.json"), "In Utero")).isNull()
    }

    @Test fun `a title search prefers the album to its demo`() {
        val body = fixture("rg_search_mbdtf.json")
        assertThat(releaseGroupFromSearch(body, "My Beautiful Dark Twisted Fantasy", "Kanye West"))
            .startsWith("5d6e21e1")
    }

    @Test fun `a title search keeps Taylor's Version apart from the original`() {
        val body = fixture("rg_search_1989.json")
        val original = releaseGroupFromSearch(body, "1989", "Taylor Swift")
        val taylors = releaseGroupFromSearch(body, "1989 (Taylor's Version)", "Taylor Swift")
        assertThat(original).isNotNull()
        assertThat(taylors).isNotNull()
        assertThat(taylors).isNotEqualTo(original)
    }

    /** A release group search's body: (id, title, releases, secondary types), all by The Beatles, scored 100. */
    private fun groups(vararg g: Triple<String, String, Pair<Int, List<String>>>): JsonObject = Json.parseToJsonElement(
        g.joinToString(",", """{"release-groups":[""", "]}") { (id, title, rest) ->
            val types = rest.second.joinToString(",") { "\"$it\"" }
            """{"id":"$id","score":100,"title":"$title","count":${rest.first},"secondary-types":[$types],""" +
                """"artist-credit":[{"name":"The Beatles","joinphrase":""}]}"""
        },
    ).jsonObject

    @Test fun `the album with the most releases wins over a stray of the same name`() {
        // Measured 2026-10-24: "Help!" is a Soundtrack with 95 releases beside "Help! (Deluxe)" with 1.
        val help = groups(
            Triple("stray", "Help! (Deluxe)", 1 to emptyList()),
            Triple("help", "Help!", 95 to listOf("Soundtrack")),
        )
        assertThat(releaseGroupFromSearch(help, "Help! (Remastered 2009)", "The Beatles")).isEqualTo("help")
    }

    @Test fun `a soundtrack is still the album`() {
        // Magical Mystery Tour is typed Soundtrack (34 releases); two untyped strays share its name.
        val mmt = groups(
            Triple("one", "Magical Mystery Tour", 1 to emptyList()),
            Triple("six", "Magical Mystery Tour", 6 to emptyList()),
            Triple("mmt", "Magical Mystery Tour", 34 to listOf("Soundtrack")),
        )
        assertThat(releaseGroupFromSearch(mmt, "Magical Mystery Tour (Remastered 2009)", "The Beatles")).isEqualTo("mmt")
    }

    @Test fun `an album named like its artist is the one with the most releases`() {
        val white = groups(
            *Array(12) { Triple("comp$it", "The Beatles", (1 + it % 3) to listOf("Compilation")) },
            Triple("white", "The Beatles", 58 to emptyList()),
        )
        assertThat(releaseGroupFromSearch(white, "The Beatles (Remastered)", "The Beatles")).isEqualTo("white")
    }

    @Test fun `a concert of the same name loses to the album, however many releases it has`() {
        val body = groups(
            Triple("live", "Abbey Road", 80 to listOf("Live")),
            Triple("studio", "Abbey Road", 5 to emptyList()),
        )
        assertThat(releaseGroupFromSearch(body, "Abbey Road", "The Beatles")).isEqualTo("studio")
    }

    @Test fun `an edition titled apart from its album finds the album through its releases`() {
        // Measured 2026-10-25: OKNOTOK is releases of "OK Computer", no release group of its own.
        fun release(title: String, group: String, artist: String = "Radiohead", score: Int = 100) =
            """{"id":"r","score":$score,"title":"$title","artist-credit":[{"name":"$artist","joinphrase":""}],""" +
                """"release-group":{"id":"$group","title":"x","secondary-types":[]}}"""
        val body = Json.parseToJsonElement(
            """{"releases":[""" + listOf(
                release("OK Computer: OKNOTOK 1997 2017", "okc"),
                release("OK Computer OKNOTOK 1997 2017", "okc"),
                release("OK Computer OKNOTOK 1997 2017", "stray"),
                release("OK Computer OKNOTOK 1997 2017", "tribute", artist = "Some Band"),
                release("OK Computer", "plain"),
            ).joinToString(",") + "]}",
        ).jsonObject
        assertThat(releaseGroupFromReleaseSearch(body, "OK Computer OKNOTOK 1997 2017", "Radiohead")).isEqualTo("okc")
        assertThat(releaseGroupFromReleaseSearch(body, "Kid A", "Radiohead")).isNull()
    }

    @Test fun `a title search for another artist's album finds nothing`() {
        assertThat(releaseGroupFromSearch(fixture("rg_search_1989.json"), "1989", "Ryan Adams")).isNull()
    }

    @Test fun `a release whose archive art is a front at most is skipped, one with more or unknown kept`() {
        // Reach Out's releases, 2026-10-25: two with no art, five with a front alone, one with six images.
        val body = Json.parseToJsonElement(
            """{"releases":[
                {"id":"none","cover-art-archive":{"artwork":false,"count":0,"front":false,"back":false}},
                {"id":"front","cover-art-archive":{"artwork":true,"count":1,"front":true,"back":false}},
                {"id":"six","cover-art-archive":{"artwork":true,"count":6,"front":true,"back":true}},
                {"id":"pages","cover-art-archive":{"artwork":true,"count":3,"front":true,"back":false}},
                {"id":"unknown"}
            ]}""",
        ).jsonObject
        assertThat(releasesWithoutMoreArt(body)).containsExactly("none", "front")
    }

    @Test fun `releases are checked vinyl first, digital last`() {
        val body = fixture("releases_nevermind.json")
        val formatsById = body["releases"]!!.jsonArray.associate { el ->
            val r = el.jsonObject
            r["id"]!!.jsonPrimitive.content to r["media"]!!.jsonArray.map { it.jsonObject["format"]?.jsonPrimitive?.content.orEmpty() }
        }
        val vinyl = formatsById.filterValues { fs -> fs.any { "Vinyl" in it } }.keys.toList()
        val digital = formatsById.filterValues { fs -> fs.isNotEmpty() && fs.all { it == "Digital Media" } }.keys.toList()

        val ranked = rankReleasesForBack(body)

        assertThat(ranked).containsExactlyElementsIn(formatsById.keys)
        assertThat(vinyl).hasSize(21)
        assertThat(ranked.take(vinyl.size)).containsExactlyElementsIn(vinyl).inOrder()
        assertThat(digital).isNotEmpty()
        assertThat(ranked.takeLast(digital.size)).containsExactlyElementsIn(digital).inOrder()
    }

    @Test fun `a listing's back is the image marked back, at 1200px over https`() {
        val listing = parseCaaListing(fixture("caa_release_nevermind_vinyl.json"))
        val back = backCoverImage(listing)
        assertThat(back).isNotNull()
        assertThat(back!!.types).contains("Back")
        assertThat(back.url).startsWith("https://")
        assertThat(back.url).endsWith("-1200.jpg")
        assertThat(listing.releaseMbid).isEqualTo("006cb56d-6eff-4b7d-853f-ecd2db97f3b2")
    }

    @Test fun `the inside is the booklet, panels, inner sleeve and tray, in the archive's order`() {
        fun img(vararg types: String, front: Boolean = false, back: Boolean = false) =
            CaaImage(types.toList(), front, back, url = types.joinToString("+") + front + back)
        val listing = CaaListing(
            listOf(
                img("Front", front = true),
                img("Booklet", "Front"), // the booklet's cover is the album's front
                img("Booklet"),
                img("Back", back = true),
                img("Panel"),
                img("Medium"),
                img("Booklet", "Raw/Unedited"),
                img("Liner"),
                img("Tray"),
                img("Obi"),
            ),
            null,
        )
        assertThat(insidePages(listing).map { it.types.single() })
            .containsExactly("Booklet", "Panel", "Liner", "Tray").inOrder()
    }

    @Test fun `a listing with only a front has no inside`() {
        assertThat(insidePages(parseCaaListing(fixture("caa_release_nevermind_canonical.json")))).isEmpty()
    }

    @Test fun `a listing with only a front has no back`() {
        assertThat(backCoverImage(parseCaaListing(fixture("caa_release_nevermind_canonical.json")))).isNull()
    }

    @Test fun `a release group listing names the release its cover comes from`() {
        val listing = parseCaaListing(fixture("caa_group_nevermind.json"))
        assertThat(listing.releaseMbid).isNotNull()
        assertThat(listing.images).isNotEmpty()
    }

    @Test fun `an untyped back marked back still counts, an unedited scan only as a last resort`() {
        fun img(vararg types: String, back: Boolean = false) = CaaImage(types.toList(), front = false, back = back, url = "u${types.joinToString()}$back")
        val raw = img("Back", "Raw/Unedited")
        val clean = img("Back")
        assertThat(backCoverImage(CaaListing(listOf(raw, clean), null))).isEqualTo(clean)
        assertThat(backCoverImage(CaaListing(listOf(raw), null))).isEqualTo(raw)
        val marked = img(back = true)
        assertThat(backCoverImage(CaaListing(listOf(clean, marked), null))).isEqualTo(marked)
    }

    @Test fun `a song's album is its album artist's, collaborations reduced to the lead act`() {
        val song = Track(title = "Song", artist = "Metro Boomin, Travis Scott", album = "Heroes & Villains", isrc = " USUM72212345 ")
        val album = BackCoverAlbum.of(song)!!
        assertThat(album.artist).isEqualTo("Metro Boomin")
        assertThat(album.isrc).isEqualTo("USUM72212345")
        assertThat(BackCoverAlbum.of(song.copy(albumArtist = "Various Artists"))!!.artist).isEqualTo("Various Artists")
        assertThat(BackCoverAlbum.of(song.copy(album = " "))).isNull()
        assertThat(BackCoverAlbum.of(song.copy(isrc = "")).let { it!!.isrc }).isNull()
    }

    @Test fun `every song on an album shares its key, whatever the case`() {
        assertThat(BackCoverAlbum("Nirvana", "Nevermind", "A").key)
            .isEqualTo(BackCoverAlbum("NIRVANA ", "nevermind", "B").key)
    }
}
