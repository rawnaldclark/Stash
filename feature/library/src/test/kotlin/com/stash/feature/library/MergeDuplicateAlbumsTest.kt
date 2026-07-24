package com.stash.feature.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Regression tests for [mergeDuplicateAlbums] — the collapse that keeps the
 * Albums grid from crashing on a duplicate `LazyVerticalGrid` key (issue #244).
 *
 * The DAO groups albums by `(album, album_artist)`, so one album with
 * inconsistent `album_artist` tags across its tracks arrives as two rows that
 * render to the same `"name|artist"` grid key.
 */
class MergeDuplicateAlbumsTest {

    @Test fun `two rows for the same album+artist collapse into one`() {
        val input = listOf(
            AlbumInfo(name = "Songs in the Key of Life", artist = "Stevie Wonder", trackCount = 9, artPath = null, artUrl = "http://art"),
            AlbumInfo(name = "Songs in the Key of Life", artist = "Stevie Wonder", trackCount = 12, artPath = "/local/cover.jpg", artUrl = null),
        )

        val merged = mergeDuplicateAlbums(input)

        assertThat(merged).hasSize(1)
        assertThat(merged.single().trackCount).isEqualTo(21)
        // first non-null art of each kind survives the merge
        assertThat(merged.single().artUrl).isEqualTo("http://art")
        assertThat(merged.single().artPath).isEqualTo("/local/cover.jpg")
    }

    @Test fun `resulting grid keys are unique (the crash guard)`() {
        val input = listOf(
            AlbumInfo("Greatest Hits", "ABBA", 2),
            AlbumInfo("greatest hits", "abba", 3), // same album, different tag casing
            AlbumInfo("Greatest Hits", "Queen", 4), // genuinely different artist
        )

        val keys = mergeDuplicateAlbums(input).map { "${it.name}|${it.artist}" }

        assertThat(keys).containsNoDuplicates()
    }

    @Test fun `distinct albums are left untouched`() {
        val input = listOf(
            AlbumInfo("Rumours", "Fleetwood Mac", 11),
            AlbumInfo("Innervisions", "Stevie Wonder", 9),
        )

        assertThat(mergeDuplicateAlbums(input)).containsExactlyElementsIn(input)
    }

    @Test fun `empty input yields empty output`() {
        assertThat(mergeDuplicateAlbums(emptyList())).isEmpty()
    }
}
