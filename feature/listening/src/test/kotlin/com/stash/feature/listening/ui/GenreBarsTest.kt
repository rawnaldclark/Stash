package com.stash.feature.listening.ui

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.genre.GenreShare
import com.stash.core.data.genre.WeightedGenre
import com.stash.feature.listening.GenreRefreshGate
import com.stash.feature.listening.stats.ListeningInsights
import org.junit.Test

/** Genre bars' words and lengths, a genre's top artists, and Home's once-a-day genre request. */
class GenreBarsTest {

    private fun share(key: String, s: Double) = GenreShare(key, key, s, s * 100)

    @Test fun `percent is whole, a sliver is under one, never zero`() {
        assertThat(genrePercent(0.344)).isEqualTo("34%")
        assertThat(genrePercent(0.005)).isEqualTo("1%")
        assertThat(genrePercent(0.004)).isEqualTo("<1%")
        assertThat(genrePercent(1.0)).isEqualTo("100%")
    }

    @Test fun `bars are relative to the strongest genre`() {
        assertThat(genreFractions(listOf(share("a", 0.4), share("b", 0.2), share("c", 0.1))))
            .containsExactly(1f, 0.5f, 0.25f).inOrder()
        assertThat(genreFractions(emptyList())).isEmpty()
        assertThat(genreFractions(listOf(share("a", 0.0)))).containsExactly(0f)
    }

    private fun act(name: String, ms: Long) = TopArtistRow(name, 1, 1, 0, null, null, ms)

    @Test fun `a genre's top artists are the ones giving it the most time`() {
        val index = ArtistGenreIndex.of(
            mapOf(
                "Big" to listOf(WeightedGenre("rock", 1f), WeightedGenre("pop", 0.25f)),
                "Small" to listOf(WeightedGenre("pop", 1f)),
                "Mid" to listOf(WeightedGenre("rock", 1f)),
            ),
        )
        val acts = listOf(act("Big", 1_000), act("Mid", 300), act("Small", 150), act("Untagged", 5_000), act("Silent", 0))
        val top = ListeningInsights.genreTopArtists(acts, index, listOf("rock", "pop", "jazz"), perGenre = 2)
        assertThat(top["rock"]).containsExactly("Big", "Mid").inOrder()
        // Big gives pop 1000 * 0.25 / 1.25 = 200 > Small's 150.
        assertThat(top["pop"]).containsExactly("Big", "Small").inOrder()
        assertThat(top["jazz"]).isEmpty()
    }

    @Test fun `artist weights are listening time per main act`() {
        val w = ListeningInsights.artistWeights(listOf(act("A", 10), act("B", 0)))
        assertThat(w.map { it.name to it.weight }).containsExactly("A" to 10L, "B" to 0L).inOrder()
    }

    @Test fun `the genre request goes through once a day`() {
        val day = 999_999L
        assertThat(GenreRefreshGate.tryPass(day)).isTrue()
        assertThat(GenreRefreshGate.tryPass(day)).isFalse()
        assertThat(GenreRefreshGate.tryPass(day + 1)).isTrue()
        assertThat(GenreRefreshGate.tryPass(day + 1)).isFalse()
    }
}
