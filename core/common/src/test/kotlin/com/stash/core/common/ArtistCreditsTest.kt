package com.stash.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the artist-credit parser:
 * [String.splitArtistCredits], [expandArtistCredits],
 * [matchesArtistCredits], and [String.primaryArtist].
 *
 * The parser is comma-only + allowlist: collaborations joined with `", "`
 * (the delimiter the app itself uses) collapse correctly, while single acts
 * that merely contain separator characters — including comma-bearing ones
 * on [ARTIST_CREDIT_ALLOWLIST] — are never shredded.
 */
class ArtistCreditsTest {

    // ── splitArtistCredits ────────────────────────────────────────────

    @Test fun `split splits a comma-joined collaboration into its acts`() {
        assertEquals(
            listOf("Aarne", "Toxi$", "Big Baby Tape"),
            "Aarne, Toxi$, Big Baby Tape".splitArtistCredits(),
        )
        assertEquals(
            listOf("Metro Boomin", "Travis Scott"),
            "Metro Boomin, Travis Scott".splitArtistCredits(),
        )
    }

    @Test fun `split round-trips a single artist`() {
        assertEquals(listOf("Metro Boomin"), "Metro Boomin".splitArtistCredits())
        assertEquals(listOf("Kozak System"), "Kozak System".splitArtistCredits())
    }

    @Test fun `split keeps ampersand and feat clauses whole`() {
        // Comma-only: "&" and "feat." are NOT delimiters.
        assertEquals(listOf("Mumford & Sons"), "Mumford & Sons".splitArtistCredits())
        assertEquals(listOf("Drake feat. Rihanna"), "Drake feat. Rihanna".splitArtistCredits())
    }

    @Test fun `split never shreds an allowlisted band inside a collaboration`() {
        assertEquals(
            listOf("Earth, Wind & Fire", "Chicago"),
            "Earth, Wind & Fire, Chicago".splitArtistCredits(),
        )
        assertEquals(
            listOf("Blood, Sweat & Tears", "Chicago"),
            "Blood, Sweat & Tears, Chicago".splitArtistCredits(),
        )
    }

    @Test fun `split trims whitespace and drops blank segments`() {
        assertEquals(listOf("Aarne", "Toxi$"), "  Aarne, Toxi$  ".splitArtistCredits())
        assertEquals(listOf("Aarne"), "Aarne, ".splitArtistCredits())
        assertEquals(emptyList<String>(), "   ".splitArtistCredits())
        assertEquals(emptyList<String>(), "".splitArtistCredits())
    }

    // ── expandArtistCredits ───────────────────────────────────────────

    @Test fun `expand unions artist and albumArtist credits without duplicates`() {
        assertEquals(
            listOf("Metro Boomin", "Travis Scott"),
            expandArtistCredits("Metro Boomin, Travis Scott", "Metro Boomin"),
        )
        assertEquals(
            listOf("Metro Boomin", "Travis Scott"),
            expandArtistCredits("Metro Boomin", "Travis Scott"),
        )
    }

    @Test fun `expand is case-insensitive against the allowlist`() {
        assertEquals(
            listOf("earth, wind & fire"),
            expandArtistCredits("earth, wind & fire", ""),
        )
    }

    // ── matchesArtistCredits ──────────────────────────────────────────

    @Test fun `matches an individual act of a collaboration credit`() {
        assertTrue(matchesArtistCredits("Metro Boomin, Travis Scott", "", "Metro Boomin"))
        assertTrue(matchesArtistCredits("Metro Boomin, Travis Scott", "", "Travis Scott"))
        assertTrue(matchesArtistCredits("Metro Boomin, Travis Scott", "", "metro boomin"))
        assertFalse(matchesArtistCredits("Metro Boomin, Travis Scott", "", "Kanye West"))
    }

    @Test fun `matches the albumArtist credit`() {
        assertTrue(matchesArtistCredits("Metro Boomin", "Metro Boomin, Travis Scott", "Travis Scott"))
        assertTrue(matchesArtistCredits("", "Drake, 21 Savage", "21 Savage"))
        assertFalse(matchesArtistCredits("Metro Boomin", "Travis Scott", "21 Savage"))
    }

    @Test fun `matches the exact combined credit string`() {
        assertTrue(matchesArtistCredits("Drake, 21 Savage", "", "Drake, 21 Savage"))
        assertTrue(matchesArtistCredits("", "Drake, 21 Savage", "drake, 21 savage"))
    }

    @Test fun `blank query never matches`() {
        assertFalse(matchesArtistCredits("Metro Boomin", "", ""))
        assertFalse(matchesArtistCredits("Metro Boomin", "", "   "))
    }

    // ── primaryArtist ─────────────────────────────────────────────────

    @Test fun `collaboration credit collapses to the lead act`() {
        assertEquals("Aarne", "Aarne, Toxi$, Big Baby Tape".primaryArtist())
        assertEquals("Metro Boomin", "Metro Boomin, Travis Scott".primaryArtist())
        assertEquals("Drake", "Drake, 21 Savage".primaryArtist())
    }

    @Test fun `single act with internal separators round-trips whole`() {
        // No comma → the comma-only split never touches these.
        assertEquals("Mumford & Sons", "Mumford & Sons".primaryArtist())
        assertEquals("Sleeping With Sirens", "Sleeping With Sirens".primaryArtist())
        assertEquals("Florence + the Machine", "Florence + the Machine".primaryArtist())
        assertEquals("Simon & Garfunkel", "Simon & Garfunkel".primaryArtist())
        assertEquals("Kool & The Gang", "Kool & The Gang".primaryArtist())
    }

    @Test fun `allowlisted comma-bearing single act is never split`() {
        assertEquals("Earth, Wind & Fire", "Earth, Wind & Fire".primaryArtist())
        assertEquals("Blood, Sweat & Tears", "Blood, Sweat & Tears".primaryArtist())
    }

    @Test fun `single artist without separators round-trips whole`() {
        assertEquals("Metro Boomin", "Metro Boomin".primaryArtist())
        assertEquals("Kozak System", "Kozak System".primaryArtist())
    }

    @Test fun `collaboration with no comma is left whole`() {
        // Split is comma-only, so a "feat." clause is preserved verbatim.
        assertEquals("Post Malone (feat. 21 Savage)", "Post Malone (feat. 21 Savage)".primaryArtist())
        assertEquals("IU (아이유)", "IU (아이유)".primaryArtist())
    }

    @Test fun `whitespace is trimmed before and after`() {
        assertEquals("Aarne", "  Aarne, Toxi$  ".primaryArtist())
        assertEquals("Metro Boomin", "  Metro Boomin  ".primaryArtist())
    }

    @Test fun `blank input returns blank`() {
        assertEquals("", "".primaryArtist())
        assertEquals("   ", "   ".primaryArtist())
    }
}