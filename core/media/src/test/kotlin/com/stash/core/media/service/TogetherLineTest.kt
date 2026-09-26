package com.stash.core.media.service

import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.listen.RoomMember
import org.junit.Assert.assertEquals
import org.junit.Test

class TogetherLineTest {
    private fun room(myId: String, hostId: String, vararg members: Pair<String, String?>) = ListenTogetherState.InRoom(
        code = "K7QA2PXM", url = "https://x/l/K7QA2PXM", myId = myId, isHost = myId == hostId, hostId = hostId,
        members = members.map { RoomMember(it.first, it.second) }, suggestions = emptyList(),
    )

    @Test fun `alone, or still connecting, it says listening together`() {
        assertEquals("Listening together", togetherLine(room("me", "me", "me" to "Rawn")))
        assertEquals("Listening together", togetherLine(ListenTogetherState.Connecting(hosting = false)))
    }

    @Test fun `a listener sees the host first, then how many more`() {
        assertEquals("With Maya +1", togetherLine(room("me", "h", "s" to "Sam", "me" to "Rawn", "h" to "Maya")))
    }

    @Test fun `the host sees who joined, and an unnamed member is someone`() {
        assertEquals("With Sam", togetherLine(room("me", "me", "me" to "Rawn", "s" to "Sam")))
        assertEquals("With Someone", togetherLine(room("me", "me", "me" to "Rawn", "x" to null)))
    }
}
