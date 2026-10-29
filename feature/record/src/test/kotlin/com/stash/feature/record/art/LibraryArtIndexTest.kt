package com.stash.feature.record.art

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.LibraryArtDao.ScannedArt
import com.stash.core.data.db.dao.LibraryArtDao.TrackArt
import org.junit.Test

/** The library's covers by the record's keys: files before addresses, remasters, guests and album artists. */
class LibraryArtIndexTest {

    private fun track(title: String, artist: String, album: String, path: String? = null, url: String? = null, albumArtist: String = "") =
        TrackArt(title, artist, albumArtist, album, path, url)

    @Test fun `an album's ask finds the file of any of its songs, its edition marks aside`() {
        val ix = LibraryArtIndex.build(listOf(track("Ivy", "Frank Ocean", "Blonde (Deluxe Edition)", path = "/data/art/blonde.jpg")), emptyList()) { null }
        assertThat(ix.localFor(ArtSong("Blonde", "Frank Ocean", "Blonde"))).isEqualTo("/data/art/blonde.jpg")
        assertThat(ix.localFor(ArtSong("Nights", "Frank Ocean", "Blonde"))).isEqualTo("/data/art/blonde.jpg")
        assertThat(ix.localFor(ArtSong("Abbey Road", "The Beatles", "Abbey Road"))).isNull()
    }

    @Test fun `a remaster and a song without an album are the same song by title`() {
        val ix = LibraryArtIndex.build(listOf(track("Heroes - 2017 Remaster", "David Bowie", "", path = "/data/h.jpg")), emptyList()) { null }
        assertThat(ix.localFor(ArtSong("Heroes", "David Bowie"))).isEqualTo("/data/h.jpg")
    }

    @Test fun `a guest credit and the album artist both find the album`() {
        val ix = LibraryArtIndex.build(
            listOf(track("Love Me", "Lil Wayne feat. Drake, Future", "I Am Not a Human Being II", path = "/data/l.jpg", albumArtist = "Lil Wayne Crew")),
            emptyList(),
        ) { null }
        assertThat(ix.localFor(ArtSong("Love Me", "Lil Wayne", "I Am Not a Human Being II"))).isEqualTo("/data/l.jpg")
        assertThat(ix.localFor(ArtSong("I Am Not a Human Being II", "Lil Wayne Crew", "I Am Not a Human Being II"))).isEqualTo("/data/l.jpg")
        assertThat(ix.localFor(ArtSong("Love Me", "Lil Wayne feat. Drake"))).isEqualTo("/data/l.jpg")
    }

    @Test fun `a file comes before an address, whichever row came first, and an address is only ever remote`() {
        val ix = LibraryArtIndex.build(
            listOf(
                track("Ivy", "Frank Ocean", "Blonde", url = "https://i.scdn.co/image/a"),
                track("Nights", "Frank Ocean", "Blonde", path = "/data/blonde.jpg", url = "https://i.scdn.co/image/b"),
            ),
            listOf(ScannedArt("Solo", "Frank Ocean", "Blonde", "scanned_x.jpg")),
        ) { "/files/scanned_art/$it" }
        val blonde = ArtSong("Blonde", "Frank Ocean", "Blonde")
        assertThat(ix.localFor(blonde)).isEqualTo("/data/blonde.jpg")
        assertThat(ix.remoteFor(blonde)).isEqualTo("https://i.scdn.co/image/a")
        assertThat(ix.localFor(ArtSong("Solo", "Frank Ocean"))).isEqualTo("/files/scanned_art/scanned_x.jpg")
    }

    @Test fun `scanned songs' covers count as own files, and an address alone is no file`() {
        val ix = LibraryArtIndex.build(
            listOf(track("Ivy", "Frank Ocean", "Blonde", url = "https://i.ytimg.com/vi/x/hq.jpg")),
            listOf(ScannedArt("Ivy", "Frank Ocean", "Blonde", "scanned_y.jpg")),
        ) { "/files/scanned_art/$it" }
        assertThat(ix.localFor(ArtSong("Blonde", "Frank Ocean", "Blonde"))).isEqualTo("/files/scanned_art/scanned_y.jpg")
    }

    @Test fun `artists stay with Deezer, and content addresses or words that fold to nothing make nothing`() {
        val ix = LibraryArtIndex.build(
            listOf(
                track("Ivy", "Frank Ocean", "Blonde", path = "/data/b.jpg"),
                track("A", "B", "C", path = "content://media/1"),
                track("!!!", "???", "...", path = "/data/odd.jpg"),
            ),
            emptyList(),
        ) { null }
        assertThat(ix.localFor(ArtSong("Frank Ocean", "Frank Ocean", isArtist = true))).isNull()
        assertThat(ix.localFor(ArtSong("C", "B", "C"))).isNull()
        assertThat(ix.localFor(ArtSong("&&&", "%%%", ",,,"))).isNull()
        assertThat(ix.localKeys).isEqualTo(2) // Blonde's al: and Ivy's tr:
    }
}
