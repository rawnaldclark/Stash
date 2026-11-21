package com.stash.app

import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionRole
import com.stash.core.ui.components.ListenTogetherRole

/**
 * This phone's part in a Listen Together room or a station's session, as the menus read it (LocalListenTogetherRole).
 * A session comes first: its guests-add switch is always off, so the private room's rule would make every session
 * listener a LISTENER, whose Play next suggests, and a session ignores suggestions.
 */
internal fun listenTogetherRole(room: ListenTogetherState.InRoom?): ListenTogetherRole = when {
    room == null -> ListenTogetherRole.NONE
    room.isHost -> ListenTogetherRole.HOST
    room.role == SessionRole.COHOST -> ListenTogetherRole.GUEST // a co-host's adds go straight in
    room.role == SessionRole.LISTENER -> ListenTogetherRole.SESSION_LISTENER
    // A guest adds straight to the room's queue when the host lets them; otherwise Play next suggests.
    room.guestsAdd -> ListenTogetherRole.GUEST
    else -> ListenTogetherRole.LISTENER
}
