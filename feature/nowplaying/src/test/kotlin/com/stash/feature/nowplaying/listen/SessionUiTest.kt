package com.stash.feature.nowplaying.listen

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionEvent
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.share.SharedTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric for the notices' string resources. */
@RunWith(RobolectricTestRunner::class)
class SessionUiTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

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
        assertEquals(keys, upNextEntries(listOf(avril.copy(addedBy = "m", artUrl = "https://i.scdn.co/image/aa"), nude, avril)).map { it.uid })
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
    fun `a row shows the adder's cover when it came along, else the YouTube thumbnail`() {
        val cover = "https://i.scdn.co/image/ab67616d0000b273aa"
        val song = nude.copy(youtubeId = "D5Y11hwjMNs")
        assertEquals(cover, coverUrl(song.copy(artUrl = cover)))
        assertEquals(thumbnailUrl(song), coverUrl(song.copy(artUrl = "https://tracker.example/x.jpg")))
        assertNull(coverUrl(nude))
    }

    @Test
    fun `notices name the person, and you when it's you`() {
        assertEquals("Rawn skipped", eventText(SessionEvent(SessionEvent.Kind.SKIPPED, "h", "Rawn"), myId = "me", res))
        assertEquals("Someone joined", eventText(SessionEvent(SessionEvent.Kind.JOINED, "x", null), myId = "me", res))
        assertEquals("You're hosting now", eventText(SessionEvent(SessionEvent.Kind.HOSTING, "me", "Maya"), myId = "me", res))
        assertEquals("Maya is hosting now", eventText(SessionEvent(SessionEvent.Kind.HOSTING, "m", "Maya"), myId = "me", res))
    }

    @Test
    fun `an add names who and what, or how many`() {
        val added = SessionEvent(SessionEvent.Kind.ADDED, "s", "Sam", song = "Xtal", count = 1)
        assertEquals("Sam added Xtal", eventText(added, myId = "me", res))
        assertEquals("Sam added 3 songs", eventText(added.copy(count = 3), myId = "me", res))
        assertEquals("Someone added 1 song", eventText(added.copy(name = null, song = null), myId = "me", res))
    }

    @Test
    fun `a song the host couldn't play says whose phone, or yours`() {
        val skipped = SessionEvent(SessionEvent.Kind.SKIPPED_UNAVAILABLE, "h", "Rawn", song = "Xtal")
        assertEquals("Skipped Xtal: Rawn can't play it", eventText(skipped, myId = "me", res))
        assertEquals("Skipped Xtal: you can't play it", eventText(skipped, myId = "h", res))
        assertEquals("Skipped a song: Rawn can't play it", eventText(skipped.copy(song = null), myId = "me", res))
    }

    // The host's drop (UpNextState.endDrag): the move is made by song, on the queue as it is now.

    private val a = SharedTrack("A", "X")
    private val b = SharedTrack("B", "X")
    private val c = SharedTrack("C", "X")
    private val guest = SharedTrack("G", "Y", addedBy = "g")
    private fun uid(queue: List<SharedTrack>, t: SharedTrack) = upNextEntries(queue).first { it.track == t }.uid

    @Test fun `a drop on an unchanged queue is the same move as ever`() {
        val q = listOf(a, b, c)
        assertEquals(q.moved(2, 1), q.withMove(uid(q, c), listOf(uid(q, a))))
        assertEquals(q.moved(0, 2), q.withMove(uid(q, a), listOf(uid(q, b), uid(q, c))))
        assertEquals(q.moved(2, 0), q.withMove(uid(q, c), emptyList()))
    }

    @Test fun `a guest's add arriving mid-drag keeps both the add and the host's move`() {
        val before = listOf(a, b, c)
        val now = listOf(guest, a, b, c)
        assertEquals(listOf(guest, a, c, b), now.withMove(uid(before, c), listOf(uid(before, a))))
    }

    @Test fun `a song that started mid-drag doesn't undo the move`() {
        val before = listOf(a, b, c)
        val now = listOf(b, c)
        assertEquals("the row above left: first", listOf(c, b), now.withMove(uid(before, c), listOf(uid(before, a))))
        assertEquals("the nearest row left above it", listOf(b, c), now.withMove(uid(before, c), listOf(uid(before, a), uid(before, b))))
    }

    @Test fun `a held song that has left drops the move`() {
        val before = listOf(a, b, c)
        assertNull(listOf(a, b).withMove(uid(before, c), listOf(uid(before, a))))
    }

    @Test fun `a removed row above falls back to the next one up`() {
        val d = SharedTrack("D", "X")
        val before = listOf(a, b, c, d)
        val now = listOf(a, c, d) // b left
        assertEquals(listOf(a, d, c), now.withMove(uid(before, d), listOf(uid(before, a), uid(before, b))))
    }

    private fun guestRoom(waitingForHost: Boolean, unavailable: Boolean = false) = ListenTogetherState.InRoom(
        code = "K7QA2PXM", url = "https://stashfm.app/l/K7QA2PXM", myId = "me", isHost = false, hostId = "h",
        members = listOf(RoomMember("h", "Pixel5", 1, "buffering"), RoomMember("me", "Me", 2, "ok")), suggestions = emptyList(),
        waitingForHost = waitingForHost, unavailable = unavailable,
    )

    @Test
    fun `a guest whose song the room holds for the host's phone is told who it waits for`() {
        assertEquals("Waiting for Pixel5…", sessionSubtitle(guestRoom(waitingForHost = true)))
        assertEquals("Pixel5 is hosting", sessionSubtitle(guestRoom(waitingForHost = false)))
        // This phone's own trouble still comes first.
        assertEquals("This song isn't available to you", sessionSubtitle(guestRoom(waitingForHost = true, unavailable = true)))
    }
}
