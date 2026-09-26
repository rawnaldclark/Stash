package com.stash.feature.nowplaying.listen

import com.stash.core.media.listen.SessionEvent
import com.stash.core.model.share.SharedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val avril = SharedTrack("Avril 14th", "Aphex Twin")
    private val nude = SharedTrack("Nude", "Radiohead")
    private val xtal = SharedTrack("Xtal", "Aphex Twin")

    @Test
    fun `Up next rows keep their keys through moves and the room's by stamps, and a song queued twice gets two`() {
        val keys = upNextEntries(listOf(avril, nude, avril)).map { it.uid }
        assertEquals(3, keys.toSet().size)
        // The room stamps who added a song after the host's own add: the row must stay the same row.
        assertEquals(keys, upNextEntries(listOf(avril.copy(addedBy = "m"), nude, avril)).map { it.uid })
        assertEquals(listOf(keys[1], keys[0], keys[2]), upNextEntries(listOf(nude, avril, avril)).map { it.uid })
    }

    @Test
    fun `two different songs that read the same still get their own rows`() {
        // Crafted suggestions whose descriptions are the same text: a repeated key would crash every phone's list.
        val a = SharedTrack("A, artist=B", "C")
        val b = SharedTrack("A", "B, artist=C")
        assertEquals(a.toString(), b.toString()) // the collision is real
        val keys = upNextEntries(listOf(a, b)).map { it.uid }
        assertEquals(2, keys.toSet().size)
        assertEquals(1, listOf(a, b).indexOfEntry(keys[1]))
    }

    @Test
    fun `an edit finds its song where it is now, and moves only that song`() {
        val queue = listOf(avril, nude, xtal)
        val nudeKey = upNextEntries(queue)[1].uid
        assertEquals(1, queue.indexOfEntry(nudeKey))
        assertEquals(0, queue.drop(1).indexOfEntry(nudeKey)) // the song before it started playing
        assertEquals(-1, listOf(avril, xtal).indexOfEntry(nudeKey)) // already gone
        assertEquals(listOf(nude, xtal, avril), queue.moved(0, 2))
        assertEquals(listOf(xtal, avril, nude), queue.moved(2, 0))
    }

    @Test
    fun `a song's thumbnail comes only from a real-looking YouTube id`() {
        assertEquals("https://i.ytimg.com/vi/D5Y11hwjMNs/mqdefault.jpg", thumbnailUrl(nude.copy(youtubeId = "D5Y11hwjMNs")))
        assertNull(thumbnailUrl(nude))
        // The id comes from whoever added the song, and the room only limits its length.
        assertNull(thumbnailUrl(nude.copy(youtubeId = "../x?y=1#zz")))
        assertNull(thumbnailUrl(nude.copy(youtubeId = "D5Y11hwjMNsX")))
    }

    @Test
    fun `notices name the person, and you when it's you`() {
        assertEquals("Rawn skipped", eventText(SessionEvent(SessionEvent.Kind.SKIPPED, "h", "Rawn"), myId = "me"))
        assertEquals("Someone joined", eventText(SessionEvent(SessionEvent.Kind.JOINED, "x", null), myId = "me"))
        assertEquals("You're hosting now", eventText(SessionEvent(SessionEvent.Kind.HOSTING, "me", "Maya"), myId = "me"))
        assertEquals("Maya is hosting now", eventText(SessionEvent(SessionEvent.Kind.HOSTING, "m", "Maya"), myId = "me"))
    }
}
