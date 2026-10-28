package com.stash.feature.record.art

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The cover lookup's words rules against the web's (worker/art.test.ts, src/lib/spotify/art.test.ts and their sources). */
class ArtTextTest {

    @Test fun `the first credited artist`() {
        assertThat(ArtText.primary("Frank Ocean, André 3000")).isEqualTo("Frank Ocean")
        assertThat(ArtText.primary("Calvin Harris feat. Frank Ocean & Migos")).isEqualTo("Calvin Harris")
        assertThat(ArtText.primary("A ft B")).isEqualTo("A")
        assertThat(ArtText.primary("Simon & Garfunkel")).isEqualTo("Simon & Garfunkel")
        assertThat(ArtText.primary("Beyoncé with Jay-Z")).isEqualTo("Beyoncé")
        // "featuring" inside a word is no credit
        assertThat(ArtText.primary("The Withdrawals")).isEqualTo("The Withdrawals")
        // nothing before the cut: the name as it is
        assertThat(ArtText.primary(", Someone")).isEqualTo(", Someone")
    }

    @Test fun `an album's marks that aren't its name go`() {
        assertThat(ArtText.bareAlbum("Blonde (Deluxe Edition)")).isEqualTo("Blonde")
        assertThat(ArtText.bareAlbum("Get Lucky - Single")).isEqualTo("Get Lucky")
        assertThat(ArtText.bareAlbum("Harbor - EP")).isEqualTo("Harbor")
        assertThat(ArtText.bareAlbum("Abbey Road [2019 Remaster]")).isEqualTo("Abbey Road")
        assertThat(ArtText.bareAlbum("Kid A")).isEqualTo("Kid A")
        // "- Single" only at the very end
        assertThat(ArtText.bareAlbum("Single - Ladies")).isEqualTo("Single - Ladies")
        // a name that is all marks stays
        assertThat(ArtText.bareAlbum("(Deluxe)")).isEqualTo("(Deluxe)")
    }

    @Test fun `a song is searched for without its remaster and featuring marks`() {
        assertThat(ArtText.searchTitle("Dreams - 2004 Remaster")).isEqualTo("Dreams")
        assertThat(ArtText.searchTitle("Song (feat. B)")).isEqualTo("Song")
        assertThat(ArtText.searchTitle("Song (Remastered 2011)")).isEqualTo("Song")
        assertThat(ArtText.searchTitle("Song (Live)")).isEqualTo("Song (Live)")
    }

    @Test fun `a cover is kept under the song's words without a trailing remaster mark`() {
        assertThat(ArtText.keyTitle("Dreams (2004 Remaster)")).isEqualTo("Dreams")
        assertThat(ArtText.keyTitle("Here Comes the Sun - Remastered 2009")).isEqualTo("Here Comes the Sun")
        assertThat(ArtText.keyTitle("Song (Live)")).isEqualTo("Song (Live)")
        assertThat(ArtText.keyTitle("Remaster")).isEqualTo("Remaster")
    }

    @Test fun `one cover per album, whatever its marks and credits`() {
        assertThat(ArtText.artKey(ArtSong("A", "Frank Ocean, André 3000", "Blonde [Remastered 2016]")))
            .isEqualTo(ArtText.artKey(ArtSong("B", "Frank Ocean", "Blonde")))
        assertThat(ArtText.artKey(ArtSong("Ivy", "Frank Ocean", "Blonde"))).isEqualTo("al:frank ocean|blonde")
        assertThat(ArtText.artKey(ArtSong("Ivy", "Frank Ocean"))).isEqualTo("tr:frank ocean|ivy")
        assertThat(ArtText.artKey(ArtSong("Ivy", "Frank Ocean", album = ""))).isEqualTo("tr:frank ocean|ivy")
        assertThat(ArtText.artKey(ArtSong("Sepultura", "Sepultura", isArtist = true))).isEqualTo("ar2:sepultura")
        // the identity fold: a bracketed featuring credit isn't the song
        assertThat(ArtText.artKey(ArtSong("Song (feat. X)", "A"))).isEqualTo(ArtText.artKey(ArtSong("Song", "A")))
    }

    @Test fun `the Worker takes clean words only`() {
        assertThat(ArtText.clean("  Frank \t Ocean \n")).isEqualTo("Frank Ocean")
        assertThat(ArtText.clean("")).isNull()
        assertThat(ArtText.clean("   ")).isNull()
        assertThat(ArtText.clean(null)).isNull()
        assertThat(ArtText.clean("a".repeat(301))).isNull()
        assertThat(ArtText.clean("a".repeat(300))).hasLength(300)
        assertThat(ArtText.clean("evil‮override")).isNull()
        assertThat(ArtText.clean("bell\u0007")).isNull()
    }

    @Test fun `queries are encoded as encodeURIComponent encodes them`() {
        assertThat(ArtText.encodeUriComponent("artist:\"Sepultura\" album:\"Roots\""))
            .isEqualTo("artist%3A%22Sepultura%22%20album%3A%22Roots%22")
        assertThat(ArtText.encodeUriComponent("Beyoncé & Jay-Z (it's) !*~._-")).isEqualTo("Beyonc%C3%A9%20%26%20Jay-Z%20(it's)%20!*~._-")
        assertThat(ArtText.encodeUriComponent("🎵/?#")).isEqualTo("%F0%9F%8E%B5%2F%3F%23")
    }

    @Test fun `only the hosts the record's page allows`() {
        assertThat(isAllowedCover("https://cdn-images.dzcdn.net/images/cover/f7/500x500-000000-80-0-0.jpg")).isTrue()
        assertThat(isAllowedCover("https://e-cdns-images.dzcdn.net/images/artist/88/500x500.jpg")).isTrue()
        assertThat(isAllowedCover("https://is1-ssl.mzstatic.com/image/thumb/x/600x600bb.jpg")).isTrue()
        assertThat(isAllowedCover("http://cdn-images.dzcdn.net/images/cover/x.jpg")).isFalse()
        assertThat(isAllowedCover("https://mzstatic.com.evil.example/x.jpg")).isFalse()
        assertThat(isAllowedCover("https://evil@cdn-images.dzcdn.net/x.jpg")).isFalse()
        assertThat(isAllowedCover("https://cdn-images.dzcdn.net:8443/x.jpg")).isFalse()
        assertThat(isAllowedCover("https://dz/a.jpg")).isFalse()
        assertThat(isAllowedCover("not a url")).isFalse()
    }
}
