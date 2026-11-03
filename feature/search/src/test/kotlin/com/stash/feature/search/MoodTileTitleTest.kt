package com.stash.feature.search

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [splitsAWord]: a moods or genres tile's title runs on over its covers only when Mochy's narrow title box would cut a
 * word in two; [fitWidest]: a word too wide even then takes a smaller size, just enough. Line ends are offsets into
 * the title, as a text layout gives them (a line broken at a space keeps the space, so it ends after it).
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

    private val title = TextStyle(fontSize = 15.sp, lineHeight = 17.sp)

    @Test
    fun `a word that fits over the covers keeps the title's size`() {
        assertEquals(title, fitWidest(title, widest = 300, room = 393, fontScale = 2f))
        assertEquals(title, fitWidest(title, widest = 393, room = 393, fontScale = 2f))
    }

    @Test
    fun `a word too wide even over the covers takes a size just small enough - Songwriters at font 2,0`() {
        // About 176 dp of "Songwriters" against 143 dp of room, in px at 2.75 per dp
        val fitted = fitWidest(title, widest = 484, room = 393, fontScale = 2f)
        val scale = 393f / 484 * 0.98f
        assertEquals(15f * scale, fitted.fontSize.value, 0.01f)
        assertEquals(17f * scale, fitted.lineHeight.value, 0.01f)
        assertTrue(fitted.fontSize.value * 484 / 15f <= 393f)
    }

    @Test
    fun `never below the title's size at the default font, and at the default font never smaller at all`() {
        assertEquals(7.5f, fitWidest(title, widest = 5_000, room = 393, fontScale = 2f).fontSize.value, 0.01f)
        assertEquals(title, fitWidest(title, widest = 500, room = 393, fontScale = 1f))
        assertEquals(title, fitWidest(title, widest = 500, room = 393, fontScale = 0.85f))
    }
}
