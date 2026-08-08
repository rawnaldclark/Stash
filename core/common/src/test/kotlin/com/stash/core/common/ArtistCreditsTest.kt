package com.stash.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistCreditsTest {

    @Test
    fun `single artist round-trips to one element`() {
        assertEquals(listOf("Metro Boomin"), "Metro Boomin".splitArtistCredits())
    }

    @Test
    fun `comma separated collaboration splits`() {
        assertEquals(
            listOf("Metro Boomin", "Travis Scott"),
            "Metro Boomin, Travis Scott".splitArtistCredits(),
        )
    }

    @Test
    fun `ampersand collaboration splits`() {
        assertEquals(
            listOf("Kendrick Lamar", "SZA"),
            "Kendrick Lamar & SZA".splitArtistCredits(),
        )
    }

    @Test
    fun `feat clause splits into a separate credit`() {
        assertEquals(
            listOf("Drake", "Lil Wayne"),
            "Drake feat. Lil Wayne".splitArtistCredits(),
        )
    }

    @Test
    fun `ft and featuring variants split`() {
        assertEquals(listOf("A", "B"), "A ft. B".splitArtistCredits())
        assertEquals(listOf("A", "B"), "A featuring B".splitArtistCredits())
    }

    @Test
    fun `parenthesised feat clause is unwrapped before splitting`() {
        assertEquals(
            listOf("Post Malone", "21 Savage"),
            "Post Malone (feat. 21 Savage)".splitArtistCredits(),
        )
    }

    @Test
    fun `x collaboration splits`() {
        assertEquals(
            listOf("Travis Scott", "Lil Baby"),
            "Travis Scott x Lil Baby".splitArtistCredits(),
        )
    }

    @Test
    fun `slash collaboration splits`() {
        assertEquals(
            listOf("Halsey", "Bleachers"),
            "Halsey / Bleachers".splitArtistCredits(),
        )
    }

    @Test
    fun `allowlisted acts are never split`() {
        assertEquals(listOf("AC/DC"), "AC/DC".splitArtistCredits())
        assertEquals(listOf("Earth, Wind & Fire"), "Earth, Wind & Fire".splitArtistCredits())
        assertEquals(listOf("Dan + Shay"), "Dan + Shay".splitArtistCredits())
        assertEquals(listOf("Hall & Oates"), "Hall & Oates".splitArtistCredits())
        assertEquals(listOf("Simon & Garfunkel"), "Simon & Garfunkel".splitArtistCredits())
        assertEquals(listOf("Sonny & Cher"), "Sonny & Cher".splitArtistCredits())
        assertEquals(listOf("Peaches & Herb"), "Peaches & Herb".splitArtistCredits())
        assertEquals(listOf("X Ambassadors"), "X Ambassadors".splitArtistCredits())
        assertEquals(listOf("X Japan"), "X Japan".splitArtistCredits())
        // Allowlist match is case-insensitive.
        assertEquals(listOf("ac/dc"), "ac/dc".splitArtistCredits())
    }

    @Test
    fun `allowlisted act survives inside a wider collaboration`() {
        assertEquals(
            listOf("AC/DC", "Guns N' Roses"),
            "AC/DC & Guns N' Roses".splitArtistCredits(),
        )
    }

    @Test
    fun `results are distinct and trimmed`() {
        assertEquals(listOf("A", "B"), "A, B, A".splitArtistCredits())
        assertEquals(listOf("A", "B"), " A , B ".splitArtistCredits())
    }

    @Test
    fun `blank inputs split to empty`() {
        assertEquals(emptyList<String>(), "".splitArtistCredits())
        assertEquals(emptyList<String>(), "   ".splitArtistCredits())
    }

    @Test
    fun `expandArtistCredits merges track and album artist credits`() {
        assertEquals(
            listOf("Drake", "21 Savage", "Metro Boomin"),
            expandArtistCredits("Drake, 21 Savage", "Metro Boomin"),
        )
        // Album artist that already appears as a track credit is de-duped.
        assertEquals(
            listOf("Metro Boomin", "Travis Scott"),
            expandArtistCredits("Metro Boomin, Travis Scott", "Metro Boomin"),
        )
    }

    @Test
    fun `matchesArtistCredits includes exact whole-string matches`() {
        assertTrue(matchesArtistCredits("Drake, 21 Savage", "", "Drake, 21 Savage"))
        assertTrue(matchesArtistCredits("Drake", "Metro Boomin", "Metro Boomin"))
    }

    @Test
    fun `matchesArtistCredits matches individual collaboration credits`() {
        assertTrue(matchesArtistCredits("Metro Boomin, Travis Scott", "", "Metro Boomin"))
        assertTrue(matchesArtistCredits("Metro Boomin, Travis Scott", "", "Travis Scott"))
        assertTrue(matchesArtistCredits("Drake feat. Lil Wayne", "", "Lil Wayne"))
        assertTrue(matchesArtistCredits("Metro Boomin, Travis Scott", "", "metro boomin"))
        assertFalse(matchesArtistCredits("Metro Boomin, Travis Scott", "", "Kanye West"))
    }

    @Test
    fun `primaryArtist takes the first act from a collaboration credit`() {
        assertEquals("Aarne", "Aarne, Toxi$, Big Baby Tape".primaryArtist())
        assertEquals("Aarne", "Aarne, Платина".primaryArtist())
        assertEquals("Aarne", "Aarne & Big Baby Tape".primaryArtist())
        assertEquals("Drake", "Drake, 21 Savage".primaryArtist())
        assertEquals("Travis Scott", "Travis Scott x Lil Baby".primaryArtist())
        assertEquals("Post Malone", "Post Malone (feat. 21 Savage)".primaryArtist())
    }

    @Test
    fun `primaryArtist passes through a single artist and handles allowlist`() {
        assertEquals("Aarne", "Aarne".primaryArtist())
        assertEquals("AC/DC", "AC/DC".primaryArtist())
        assertEquals("Earth, Wind & Fire", "Earth, Wind & Fire".primaryArtist())
        assertEquals("Drake", "  Drake, 21 Savage ".primaryArtist())
        assertEquals("", "   ".primaryArtist())
    }
}
