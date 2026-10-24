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

    @Test fun `a title search for another artist's album finds nothing`() {
        assertThat(releaseGroupFromSearch(fixture("rg_search_1989.json"), "1989", "Ryan Adams")).isNull()
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
