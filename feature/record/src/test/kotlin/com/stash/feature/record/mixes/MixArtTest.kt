package com.stash.feature.record.mixes

import com.google.common.truth.Truth.assertThat
import com.stash.feature.record.art.ArtSong
import com.stash.feature.record.art.Found
import com.stash.feature.record.art.MemoryArtStore
import com.stash.feature.record.art.RecordArt
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The covers behind a mix, as the web lays them: Home's tile (Tile.svelte: the first four songs with different covers, a song
 * with none keeping its own square), the Mixes tab's card (routes/spotify/+page.svelte `coversOf`: the first four different covers
 * found anywhere in the mix) and what is asked for (art.ts `withCovers`: your rows' own covers, then the first albums with none).
 */
class MixArtTest {
    private fun song(title: String, album: String? = null, art: String? = null) = MixSong(
        title = title, artist = "Band", album = album, key = "${title.lowercase()}|band", songKey = "${title.lowercase()}|band",
        lib = art?.let { LibrarySong(trackId = title.hashCode().toLong(), title = title, artist = "Band", album = album, art = it) },
    )

    @Test fun `Home's tile - the first four songs with different covers, a song with none keeping its own square`() {
        val items = listOf(song("One"), song("Two"), song("Three"), song("Four"), song("Five"), song("Six"))
        val covers = listOf("https://a", "https://a", null, "https://b", "https://c", "https://d")
        assertThat(tileCovers(items, covers)).containsExactly(
            TileCover("https://a", "One"),
            // "Two" shares One's cover: left out; "Three" has none: its own square, by its key
            TileCover(null, "Three"),
            TileCover("https://b", "Four"),
            TileCover("https://c", "Five"),
        ).inOrder()
        // fewer than four: as many as there are (the tile then shows the first alone)
        assertThat(tileCovers(items.take(2), listOf("https://a", "https://a"))).containsExactly(TileCover("https://a", "One"))
        assertThat(tileCovers(emptyList(), emptyList())).isEmpty()
    }

    @Test fun `the Mixes tab's card - the first four different covers found anywhere in the mix`() {
        assertThat(cardCovers(listOf(null, "https://a", "https://a", "https://b", null, "https://c", "https://d", "https://e")))
            .containsExactly("https://a", "https://b", "https://c", "https://d").inOrder()
        assertThat(cardCovers(listOf(null, null))).isEmpty()
        assertThat(cardCovers(listOf("https://a", null, "https://a"))).containsExactly("https://a")
    }

    @Test fun `asked for - your rows' own covers first, then the first albums with none, each album once`() = runTest {
        val asked = mutableListOf<ArtSong>()
        val art = RecordArt(
            lookUp = { s -> asked += s; Found.Cover("https://found/${s.album ?: s.title}") },
            store = MemoryArtStore(),
            now = { 0L },
            work = EmptyCoroutineContext,
        )
        val items = listOf(
            song("Mine", album = "Own", art = "https://mine"),
            song("A1", album = "Alpha"),
            song("A2", album = "Alpha"),
            song("B1", album = "Beta"),
            song("Loose"),
            song("C1", album = "Gamma"),
        )
        val covers = mixCovers(art, items, albums = 2)
        // two albums asked for, Alpha once for both its songs; your own row's cover kept, never asked
        assertThat(asked.map { it.album ?: it.title }).containsExactly("Alpha", "Beta").inOrder()
        assertThat(covers).containsExactly("https://mine", "https://found/Alpha", "https://found/Alpha", "https://found/Beta", null, null).inOrder()
        // every album: the songs with no album asked by their own words
        asked.clear()
        assertThat(mixCovers(art, items, albums = Int.MAX_VALUE)).containsExactly(
            "https://mine", "https://found/Alpha", "https://found/Alpha", "https://found/Beta", "https://found/Loose", "https://found/Gamma",
        ).inOrder()
        assertThat(asked.map { it.album ?: it.title }).containsExactly("Loose", "Gamma").inOrder()
    }
}
