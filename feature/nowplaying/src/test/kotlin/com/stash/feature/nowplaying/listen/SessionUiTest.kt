package com.stash.feature.nowplaying.listen

import com.stash.core.media.listen.SessionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUiTest {

    @Test
    fun `a person keeps one colour, and a room of ten spreads across the palette`() {
        assertEquals(personColor("M7TAX7JN"), personColor("M7TAX7JN"))
        val ids = listOf("M7TAX7JN", "Q2WERT5Y", "H8JK3LZX", "P4NB6VCM", "R9TY2UIO", "K5LM8NBV", "Z3XC7VBN", "A6SD9FGH", "W2ER4TYU", "E8RT6YUI")
        assertTrue(ids.map(::personColor).toSet().size >= 5)
    }

    @Test
    fun `faces show initials, names read naturally, codes are easy to read out`() {
        assertEquals("ML", initials("Maya Lopez"))
        assertEquals("P", initials("Pixel5"))
        assertEquals("?", initials(null))
        assertEquals("Maya", nameList(listOf("Maya")))
        assertEquals("Maya, Sam", nameList(listOf("Maya", "Sam")))
        assertEquals("Maya, Sam +2", nameList(listOf("Maya", "Sam", "Jo", "Ana")))
        assertEquals("R2YH-WS55", formatCode("R2YHWS55"))
    }

    @Test
    fun `notices name the person, and you when it's you`() {
        assertEquals("Rawn skipped", eventText(SessionEvent(SessionEvent.Kind.SKIPPED, "h", "Rawn"), myId = "me"))
        assertEquals("Someone joined", eventText(SessionEvent(SessionEvent.Kind.JOINED, "x", null), myId = "me"))
        assertEquals("You're hosting now", eventText(SessionEvent(SessionEvent.Kind.HOSTING, "me", "Maya"), myId = "me"))
        assertEquals("Maya is hosting now", eventText(SessionEvent(SessionEvent.Kind.HOSTING, "m", "Maya"), myId = "me"))
    }
}
