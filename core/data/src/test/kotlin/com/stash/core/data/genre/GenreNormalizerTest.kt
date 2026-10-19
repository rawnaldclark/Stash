package com.stash.core.data.genre

import com.stash.core.data.lastfm.LastFmTag
import com.stash.core.data.mix.GenreCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreNormalizerTest {

    private fun tag(name: String, weight: Float = 1f) = LastFmTag(name, weight)

    // ── canonical ───────────────────────────────────────────────────

    @Test fun `hip-hop spellings all become hip-hop`() {
        listOf("hip-hop", "hip hop", "Hip Hop", "hiphop", "HIP-HOP", "hip_hop", "Hip-Hop/Rap", "rap/hip-hop", "hip  hop")
            .forEach { assertEquals(it, "hip-hop", GenreNormalizer.canonical(it)) }
    }

    @Test fun `hip-hop inside a longer genre is respelled too`() {
        assertEquals("underground hip-hop", GenreNormalizer.canonical("underground hip hop"))
        assertEquals("conscious hip-hop", GenreNormalizer.canonical("Conscious Hiphop"))
    }

    @Test fun `r and b spellings all become r&b`() {
        listOf("r&b", "R&B", "rnb", "RnB", "r'n'b", "r & b", "r and b", "rhythm and blues", "Rhythm & Blues", "contemporary r&b")
            .forEach { assertEquals(it, "r&b", GenreNormalizer.canonical(it)) }
    }

    @Test fun `synonyms map to one genre`() {
        assertEquals("electronic", GenreNormalizer.canonical("electronica"))
        assertEquals("electronic", GenreNormalizer.canonical("Electronic Music"))
        assertEquals("edm", GenreNormalizer.canonical("electronic dance music"))
        assertEquals("drum and bass", GenreNormalizer.canonical("drum & bass"))
        assertEquals("drum and bass", GenreNormalizer.canonical("dnb"))
        assertEquals("drum and bass", GenreNormalizer.canonical("drum n bass"))
        assertEquals("lo-fi", GenreNormalizer.canonical("lofi"))
        assertEquals("lo-fi", GenreNormalizer.canonical("lo fi"))
        assertEquals("lo-fi", GenreNormalizer.canonical("lo-fi hip hop"))
        assertEquals("lo-fi", GenreNormalizer.canonical("chillhop"))
        assertEquals("alternative rock", GenreNormalizer.canonical("alt rock"))
        assertEquals("rock and roll", GenreNormalizer.canonical("rock n roll"))
        assertEquals("rock and roll", GenreNormalizer.canonical("Rock & Roll"))
        assertEquals("soundtrack", GenreNormalizer.canonical("OST"))
        assertEquals("reggaeton", GenreNormalizer.canonical("Reggaetón"))
    }

    @Test fun `spacing and hyphen variants find the canonical spelling`() {
        assertEquals("synth pop", GenreNormalizer.canonical("synthpop"))
        assertEquals("synth pop", GenreNormalizer.canonical("synth-pop"))
        assertEquals("k-pop", GenreNormalizer.canonical("kpop"))
        assertEquals("k-pop", GenreNormalizer.canonical("K Pop"))
        assertEquals("neo-soul", GenreNormalizer.canonical("neo soul"))
        assertEquals("post-rock", GenreNormalizer.canonical("post rock"))
        assertEquals("trip-hop", GenreNormalizer.canonical("trip hop"))
        assertEquals("trip-hop", GenreNormalizer.canonical("triphop"))
        assertEquals("indie rock", GenreNormalizer.canonical("indie-rock"))
        assertEquals("dream pop", GenreNormalizer.canonical("Dream-Pop"))
        assertEquals("singer-songwriter", GenreNormalizer.canonical("singer songwriter"))
        assertEquals("classical", GenreNormalizer.canonical("Classical Music"))
        assertEquals("indie pop", GenreNormalizer.canonical("  Indie   Pop  "))
    }

    @Test fun `non-genre tags are dropped`() {
        listOf(
            "seen live", "female vocalists", "male vocalists", "british", "american", "uk", "canadian",
            "00s", "80s", "2010s", "favorites", "favourite", "love", "awesome", "chill", "sad", "beautiful",
            "spotify", "my top songs", "instrumental", "acoustic", "piano", "", "   ", "music",
        ).forEach { assertNull(it, GenreNormalizer.canonical(it)) }
    }

    @Test fun `every Mix Builder genre is a genre, except instruments`() {
        (GenreCatalog.allGenres() - "piano").forEach {
            assertEquals(it, it, GenreNormalizer.canonical(it))
        }
    }

    @Test fun `no two genres squash to the same key`() {
        assertEquals(emptyList<Set<String>>(), GenreNormalizer.squashCollisions())
    }

    @Test fun `every genre is canonical and lower case, and every synonym lands on a genre`() {
        GenreNormalizer.genres.forEach {
            assertEquals(it, it.lowercase(), it)
            assertEquals(it, it, GenreNormalizer.canonical(it))
        }
        GenreNormalizer.SYNONYMS.forEach { (spelling, genre) ->
            assertTrue("$spelling -> $genre is not a genre", genre in GenreNormalizer.genres)
            assertEquals(spelling, genre, GenreNormalizer.canonical(spelling))
        }
    }

    // ── topGenres ───────────────────────────────────────────────────

    @Test fun `keeps the top three genres, strongest first, rescaled to the strongest`() {
        val genres = GenreNormalizer.topGenres(
            listOf(
                tag("seen live", 1f),
                tag("hip-hop", 0.8f),
                tag("rap", 0.6f),
                tag("british", 0.5f),
                tag("trap", 0.4f),
                tag("r&b", 0.3f),
            ),
        )
        assertEquals(listOf("hip-hop", "rap", "trap"), genres.map { it.genre })
        assertEquals(1f, genres[0].weight, 1e-6f)
        assertEquals(0.75f, genres[1].weight, 1e-6f)
        assertEquals(0.5f, genres[2].weight, 1e-6f)
    }

    @Test fun `spellings of one genre merge, the strongest spelling counts once`() {
        val genres = GenreNormalizer.topGenres(
            listOf(tag("hip-hop", 0.5f), tag("Hip Hop", 1f), tag("hiphop", 0.9f), tag("soul", 0.5f)),
        )
        assertEquals(listOf("hip-hop", "soul"), genres.map { it.genre })
        assertEquals(1f, genres[0].weight, 1e-6f)
        assertEquals(0.5f, genres[1].weight, 1e-6f)
    }

    @Test fun `weak strays are dropped`() {
        // pop is under a quarter of indie rock; folk is under the absolute floor.
        val genres = GenreNormalizer.topGenres(
            listOf(tag("indie rock", 1f), tag("alternative", 0.3f), tag("pop", 0.2f), tag("folk", 0.01f)),
        )
        assertEquals(listOf("indie rock", "alternative"), genres.map { it.genre })
    }

    @Test fun `an artist whose only genre tag is negligible has no genre`() {
        assertTrue(GenreNormalizer.topGenres(listOf(tag("seen live", 1f), tag("rock", 0.02f))).isEmpty())
    }

    @Test fun `no tags or only non-genres give no genres`() {
        assertTrue(GenreNormalizer.topGenres(emptyList()).isEmpty())
        assertTrue(GenreNormalizer.topGenres(listOf(tag("seen live"), tag("female vocalists"), tag("90s"))).isEmpty())
    }

    @Test fun `zero, negative and non-finite weights are ignored`() {
        val genres = GenreNormalizer.topGenres(
            listOf(tag("rock", 0f), tag("pop", -1f), tag("jazz", Float.NaN), tag("soul", 0.4f)),
        )
        assertEquals(listOf("soul"), genres.map { it.genre })
        assertEquals(1f, genres.single().weight, 1e-6f)
    }

    @Test fun `equal weights are ordered by name so results are stable`() {
        val a = GenreNormalizer.topGenres(listOf(tag("rock", 1f), tag("pop", 1f), tag("jazz", 1f), tag("funk", 1f)))
        val b = GenreNormalizer.topGenres(listOf(tag("funk", 1f), tag("jazz", 1f), tag("pop", 1f), tag("rock", 1f)))
        assertEquals(listOf("funk", "jazz", "pop"), a.map { it.genre })
        assertEquals(a, b)
    }

    // ── displayName ─────────────────────────────────────────────────

    @Test fun `display names read like genre names`() {
        mapOf(
            "hip-hop" to "Hip-Hop",
            "r&b" to "R&B",
            "indie pop" to "Indie Pop",
            "k-pop" to "K-Pop",
            "edm" to "EDM",
            "idm" to "IDM",
            "uk garage" to "UK Garage",
            "drum and bass" to "Drum and Bass",
            "rock and roll" to "Rock and Roll",
            "lo-fi" to "Lo-Fi",
            "alternative r&b" to "Alternative R&B",
            "singer-songwriter" to "Singer-Songwriter",
            "rock" to "Rock",
            "mpb" to "MPB",
        ).forEach { (key, shown) -> assertEquals(shown, GenreNormalizer.displayName(key)) }
    }
}
