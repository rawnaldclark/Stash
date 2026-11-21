package com.stash.app

import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionRole
import com.stash.core.model.listen.SessionInfo
import com.stash.core.ui.components.ListenTogetherRole
import org.junit.Assert.assertEquals
import org.junit.Test

/** The menus' role in a room (MainActivity's LocalListenTogetherRole): a session first, then a private room's switch. */
class ListenTogetherRolesTest {
    private fun room(isHost: Boolean = false, guestsAdd: Boolean = false, role: SessionRole? = null) = ListenTogetherState.InRoom(
        code = "K7QA2PXM", url = "https://stashfm.app/l/K7QA2PXM", myId = "me", isHost = isHost, hostId = "h",
        members = emptyList(), suggestions = emptyList(), guestsAdd = guestsAdd,
        session = role?.let { SessionInfo("Ab3dEf9h") }, role = role,
    )

    @Test fun `a private room's roles are as before`() {
        assertEquals(ListenTogetherRole.NONE, listenTogetherRole(null))
        assertEquals(ListenTogetherRole.HOST, listenTogetherRole(room(isHost = true)))
        assertEquals(ListenTogetherRole.GUEST, listenTogetherRole(room(guestsAdd = true)))
        assertEquals(ListenTogetherRole.LISTENER, listenTogetherRole(room(guestsAdd = false)))
    }

    @Test fun `in a session the host hosts, a co-host adds straight in, and anyone else requests`() {
        assertEquals(ListenTogetherRole.HOST, listenTogetherRole(room(isHost = true, role = SessionRole.HOST)))
        assertEquals(ListenTogetherRole.GUEST, listenTogetherRole(room(role = SessionRole.COHOST)))
        assertEquals(ListenTogetherRole.SESSION_LISTENER, listenTogetherRole(room(role = SessionRole.LISTENER)))
    }
}
