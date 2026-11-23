package com.stash.core.media.listen

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.listen.AwayRequestsNotifier.Companion.waitingWhileAway
import com.stash.core.model.listen.RoomRequest
import com.stash.core.model.listen.SessionInfo
import com.stash.core.model.share.SharedTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** [AwayRequestsNotifier]: an away host hears of waiting requests once, and the notice goes when it has nothing to say. */
@OptIn(ExperimentalCoroutinesApi::class)
class AwayRequestsNotifierTest {
    private val waiting = MutableStateFlow<Int?>(null)
    private val log = mutableListOf<String>()
    private val notices = object : AwayRequestNotices {
        override fun waiting(count: Int) { log += "waiting $count" }
        override fun update(count: Int) { log += "update $count" }
        override fun clear() { log += "clear" }
    }

    private fun TestScope.notifier() = AwayRequestsNotifier(waiting, notices, backgroundScope).also { it.start(); runCurrent() }

    private fun TestScope.now(count: Int?) { waiting.value = count; runCurrent() }

    @Test fun `the first request while away is said once, and later ones only change the number`() = runTest {
        notifier()
        now(0) // away, nothing waiting
        assertThat(log).isEmpty()
        now(1); now(2); now(1)
        assertThat(log).containsExactly("waiting 1", "update 2", "update 1").inOrder()
    }

    @Test fun `it goes when the host is back, and a later spell away is said again`() = runTest {
        notifier()
        now(2)
        now(null) // back in the booth
        now(2) // away again, the same two still waiting
        assertThat(log).containsExactly("waiting 2", "clear", "waiting 2").inOrder()
    }

    @Test fun `it goes when nothing waits any more, and nothing is cleared that was never shown`() = runTest {
        notifier()
        now(0); now(null)
        assertThat(log).isEmpty()
        now(3); now(0)
        assertThat(log).containsExactly("waiting 3", "clear").inOrder()
    }

    // ── which rooms count ───────────────────────────────────────────────

    private val song = SharedTrack("Nights", "Frank Ocean")

    private fun room(host: Boolean = true, presence: String = SessionInfo.AWAY, requests: Int = 2, session: Boolean = true) = ListenTogetherState.InRoom(
        code = "K7QA2PXM", url = "u", myId = "me", isHost = host, hostId = if (host) "me" else "h", members = emptyList(), suggestions = emptyList(),
        session = if (session) SessionInfo("St4tion1", presence = presence) else null,
        requests = List(requests) { RoomRequest("r$it", "b", song) },
    )

    @Test fun `only a session this phone hosts, while it is away, counts`() {
        assertThat(waitingWhileAway(room())).isEqualTo(2)
        assertThat(waitingWhileAway(room(requests = 0))).isEqualTo(0)
        assertThat(waitingWhileAway(room(presence = SessionInfo.BOOTH))).isNull()
        assertThat(waitingWhileAway(room(host = false))).isNull() // a co-host, or a listener with their own waiting
        assertThat(waitingWhileAway(room(session = false))).isNull()
        assertThat(waitingWhileAway(ListenTogetherState.Idle)).isNull()
    }
}
