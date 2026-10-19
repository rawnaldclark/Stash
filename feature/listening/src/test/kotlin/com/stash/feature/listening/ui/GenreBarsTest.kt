package com.stash.feature.listening.ui

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TopArtistRow
import com.stash.core.data.genre.ArtistGenreIndex
import com.stash.core.data.genre.GenreShare
import com.stash.core.data.genre.WeightedGenre
import com.stash.feature.listening.OncePerDayGate
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

    @Test fun `a featured or comma credit adds to its act, keyed like the cache`() {
        val index = ArtistGenreIndex.of(
            mapOf("Drake" to listOf(WeightedGenre("hip-hop", 1f)), "Earth, Wind & Fire" to listOf(WeightedGenre("funk", 1f))),
        )
        val credits = listOf(act("Drake", 100), act("drake feat. Rihanna", 50), act("Earth, Wind & Fire, Chicago", 20), act("SZA", 80))
        val top = ListeningInsights.genreTopArtists(credits, index, listOf("hip-hop", "funk"))
        assertThat(top["hip-hop"]).containsExactly("Drake")
        assertThat(top["funk"]).containsExactly("Earth, Wind & Fire")
    }

    @Test fun `the credit line says how much of the listening has a genre when not all of it does`() {
        assertThat(genreCredit(0.99)).isEqualTo("Share of your listening time · Genres from Last.fm")
        assertThat(genreCredit(0.95)).isEqualTo("Share of your listening time · Genres from Last.fm")
        assertThat(genreCredit(0.809)).isEqualTo("Genre known for 80% of your listening · Genres from Last.fm")
    }

    @Test fun `the thin state floors, says half or more, and tells not enough from on its way`() {
        assertThat(genresThinMessage(0.499, genresCached = true)).contains("49% of your listening")
        assertThat(genresThinMessage(0.499, genresCached = true)).contains("half or more")
        assertThat(genresThinMessage(0.0, genresCached = true)).isEqualTo("Not enough to rank genres for this period yet.")
        assertThat(genresThinMessage(0.0, genresCached = false)).contains("on their way")
    }

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
        val gate = OncePerDayGate()
        val day = 999_999L
        assertThat(gate.tryPass(day)).isTrue()
        assertThat(gate.tryPass(day)).isFalse()
        assertThat(gate.tryPass(day + 1)).isTrue()
        assertThat(gate.tryPass(day + 1)).isFalse()
    }
}
