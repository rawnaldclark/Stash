package com.stash.core.data.genre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopGenresCalculatorTest {

    private fun g(vararg pairs: Pair<String, Float>) = pairs.map { WeightedGenre(it.first, it.second) }
    private fun a(name: String, weight: Long) = ArtistWeight(name, weight)

    private val eps = 1e-9

    @Test fun `one artist with one genre is all of it`() {
        val top = TopGenresCalculator.compute(listOf(a("Drake", 60)), mapOf("Drake" to g("hip-hop" to 1f)))

        assertEquals(1, top.items.size)
        assertEquals("hip-hop", top.items[0].key)
        assertEquals("Hip-Hop", top.items[0].name)
        assertEquals(1.0, top.items[0].share, eps)
        assertEquals(60.0, top.items[0].weight, eps)
        assertEquals(1.0, top.coverage, eps)
        assertTrue(top.isShowable)
    }

    @Test fun `an artist's weight is split across its genres by genre weight`() {
        // hip-hop 1.0 + r&b 0.5: two thirds and one third of 90 minutes.
        val top = TopGenresCalculator.compute(
            listOf(a("Drake", 90)),
            mapOf("Drake" to g("hip-hop" to 1f, "r&b" to 0.5f)),
        )

        assertEquals(listOf("hip-hop", "r&b"), top.items.map { it.key })
        assertEquals(60.0, top.items[0].weight, eps)
        assertEquals(30.0, top.items[1].weight, eps)
        assertEquals(2.0 / 3, top.items[0].share, eps)
        assertEquals(1.0 / 3, top.items[1].share, eps)
        assertEquals(1.0, top.items.sumOf { it.share }, eps)
    }

    @Test fun `genres add up across artists and rank by total`() {
        val top = TopGenresCalculator.compute(
            listOf(a("Drake", 100), a("SZA", 80), a("Radiohead", 30)),
            mapOf(
                "Drake" to g("hip-hop" to 1f),
                "SZA" to g("r&b" to 1f, "hip-hop" to 1f),
                "Radiohead" to g("alternative" to 1f),
            ),
        )

        // hip-hop 100 + 40 = 140; r&b 40; alternative 30. Total covered 210.
        assertEquals(listOf("hip-hop", "r&b", "alternative"), top.items.map { it.key })
        assertEquals(140.0 / 210, top.items[0].share, eps)
        assertEquals(40.0 / 210, top.items[1].share, eps)
        assertEquals(30.0 / 210, top.items[2].share, eps)
    }

    @Test fun `artists without genres lower coverage but not the shares`() {
        val top = TopGenresCalculator.compute(
            listOf(a("Drake", 30), a("Unknown Local Rip", 70)),
            mapOf("Drake" to g("hip-hop" to 1f)),
        )

        assertEquals(1.0, top.items.single().share, eps)
        assertEquals(0.3, top.coverage, eps)
        assertFalse("under half the listening has a genre", top.isShowable)
    }

    @Test fun `coverage of exactly one half is showable`() {
        val top = TopGenresCalculator.compute(
            listOf(a("Drake", 50), a("Nobody", 50)),
            mapOf("Drake" to g("hip-hop" to 1f)),
        )
        assertEquals(0.5, top.coverage, eps)
        assertTrue(top.isShowable)
    }

    @Test fun `collaboration credits count for the primary artist`() {
        val top = TopGenresCalculator.compute(
            listOf(a("Drake, 21 Savage", 10), a("Drake feat. Rihanna", 10), a("Drake (feat. Future)", 10), a("Drake ft. Lil Baby", 10)),
            mapOf("Drake" to g("hip-hop" to 1f)),
        )

        assertEquals(1.0, top.coverage, eps)
        assertEquals(40.0, top.items.single().weight, eps)
    }

    @Test fun `names match ignoring case on both sides`() {
        val top = TopGenresCalculator.compute(
            listOf(a("DRAKE", 10), a("sza", 10)),
            mapOf("drake" to g("hip-hop" to 1f), "SZA" to g("r&b" to 1f)),
        )
        assertEquals(1.0, top.coverage, eps)
        assertEquals(2, top.items.size)
    }

    @Test fun `ties rank by genre key, whatever the input order`() {
        val genres = mapOf("A" to g("rock" to 1f), "B" to g("jazz" to 1f), "C" to g("funk" to 1f))
        val one = TopGenresCalculator.compute(listOf(a("A", 10), a("B", 10), a("C", 10)), genres)
        val two = TopGenresCalculator.compute(listOf(a("C", 10), a("A", 10), a("B", 10)), genres)

        assertEquals(listOf("funk", "jazz", "rock"), one.items.map { it.key })
        assertEquals(one, two)
    }

    @Test fun `limit caps the list but shares stay relative to all covered listening`() {
        val genres = (1..8).associate { "Artist $it" to g("genre$it" to 1f) }
        val artists = (1..8).map { a("Artist $it", (10 - it).toLong()) }

        val top = TopGenresCalculator.compute(artists, genres, limit = 3)

        assertEquals(listOf("genre1", "genre2", "genre3"), top.items.map { it.key })
        val total = (1..8).sumOf { 10 - it }.toDouble()
        assertEquals(9 / total, top.items[0].share, eps)
        assertTrue(top.items.sumOf { it.share } < 1.0)
        assertEquals(1.0, top.coverage, eps)
    }

    @Test fun `the default limit is five`() {
        val genres = (1..8).associate { "Artist $it" to g("genre$it" to 1f) }
        val top = TopGenresCalculator.compute((1..8).map { a("Artist $it", 1) }, genres)
        assertEquals(5, top.items.size)
    }

    @Test fun `no artists, zero weights or a zero limit`() {
        assertEquals(TopGenres.EMPTY, TopGenresCalculator.compute(emptyList(), emptyMap()))
        assertEquals(
            TopGenres.EMPTY,
            TopGenresCalculator.compute(listOf(a("Drake", 0), a("SZA", -5)), mapOf("Drake" to g("hip-hop" to 1f))),
        )
        val zeroLimit = TopGenresCalculator.compute(listOf(a("Drake", 10)), mapOf("Drake" to g("hip-hop" to 1f)), limit = 0)
        assertTrue(zeroLimit.items.isEmpty())
        assertEquals(1.0, zeroLimit.coverage, eps)
        assertFalse(zeroLimit.isShowable)
    }

    @Test fun `no genres at all gives zero coverage`() {
        val top = TopGenresCalculator.compute(listOf(a("Drake", 10)), emptyMap())
        assertTrue(top.items.isEmpty())
        assertEquals(0.0, top.coverage, eps)
    }

    @Test fun `broken genre rows count as no genre`() {
        val top = TopGenresCalculator.compute(
            listOf(a("Drake", 10), a("SZA", 10)),
            mapOf("Drake" to g("hip-hop" to 0f), "SZA" to g("" to 1f, "r&b" to Float.NaN)),
        )
        assertTrue(top.items.isEmpty())
        assertEquals(0.0, top.coverage, eps)
    }

    @Test fun `the same artist listed twice adds up`() {
        val top = TopGenresCalculator.compute(
            listOf(a("Drake", 10), a("drake", 5)),
            mapOf("Drake" to g("hip-hop" to 1f)),
        )
        assertEquals(15.0, top.items.single().weight, eps)
    }

    @Test fun `large minute totals don't overflow`() {
        val top = TopGenresCalculator.compute(
            listOf(a("Drake", Long.MAX_VALUE / 2), a("SZA", Long.MAX_VALUE / 2)),
            mapOf("Drake" to g("hip-hop" to 1f), "SZA" to g("r&b" to 1f)),
        )
        assertEquals(0.5, top.items[0].share, 1e-6)
        assertEquals(1.0, top.coverage, 1e-6)
    }

    @Test fun `the shared index gives the same result as a raw map`() {
        val genres = mapOf("Drake" to g("hip-hop" to 1f), "SZA" to g("r&b" to 1f))
        val artists = listOf(a("Drake feat. SZA", 30), a("sza", 10), a("Nobody", 10))

        assertEquals(
            TopGenresCalculator.compute(artists, genres),
            TopGenresCalculator.compute(artists, ArtistGenreIndex.of(genres)),
        )
        assertEquals(ArtistGenreIndex.EMPTY, ArtistGenreIndex.of(emptyMap()))
    }
}
