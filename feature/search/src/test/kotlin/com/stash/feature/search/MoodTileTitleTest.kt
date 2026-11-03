package com.stash.feature.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [splitsAWord]: a moods or genres tile's title runs on over its covers only when Mochy's narrow title box would cut a
 * word in two. Line ends are offsets into the title, as a text layout gives them (a line broken at a space keeps the
 * space, so it ends after it).
 */
class MoodTileTitleTest {

    @Test
    fun `a word cut between two letters is a split - the tile's title then runs on over the covers`() {
        // "Songwriter" | "s & producers", seen on a 393 dp phone
        assertTrue(splitsAWord("Songwriters & producers", listOf(10)))
        // "Christma" | "s", seen at the largest font
        assertTrue(splitsAWord("Christmas", listOf(8)))
        // The second line's break is checked too
        assertTrue(splitsAWord("Soundtracks & musicals", listOf(14, 19)))
    }

    @Test
    fun `a break at a space or after a hyphen, or no break at all, is not`() {
        // "Dance & " | "electronic"
        assertFalse(splitsAWord("Dance & electronic", listOf(8)))
        // "Hip-" | "hop"
        assertFalse(splitsAWord("Hip-hop", listOf(4)))
        // "R&B & soul" on one line
        assertFalse(splitsAWord("R&B & soul", emptyList()))
        // "Bollywood " | "& Indian"
        assertFalse(splitsAWord("Bollywood & Indian", listOf(10)))
    }

    @Test
    fun `a line end at either edge of the title is never a split`() {
        assertFalse(splitsAWord("Chill", listOf(0)))
        assertFalse(splitsAWord("Chill", listOf(5)))
        assertFalse(splitsAWord("", listOf(0)))
    }
}
