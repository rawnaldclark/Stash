package com.stash.feature.nowplaying.session

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionRole
import com.stash.core.model.listen.DeckSeat
import com.stash.core.model.listen.DecksInfo
import com.stash.core.model.listen.RoomMember
import com.stash.core.model.listen.SessionInfo
import org.junit.Test

/** The open decks as the room's screen shows them ([decksUi]): the round, the spots, and where this phone stands. */
class SessionDecksTest {

    private fun room(
        decks: DecksInfo?,
        role: SessionRole = SessionRole.LISTENER,
        members: List<RoomMember> = listOf(RoomMember("h", "Maya"), RoomMember("me", "Rawn"), RoomMember("b", "Ben"), RoomMember("c", "Cy")),
        names: Map<String, String?> = emptyMap(),
    ) = ListenTogetherState.InRoom(
        code = "K7QA2PXM", url = "https://stashfm.app/s/St4tion1", myId = "me", isHost = role == SessionRole.HOST,
        hostId = if (role == SessionRole.HOST) "me" else "h", members = members, suggestions = emptyList(),
        queue = emptyList(), session = SessionInfo("St4tion1", listeners = 3), role = role, names = names, decks = decks,
    )

    @Test fun `closed decks show nothing`() {
        assertThat(decksUi(room(decks = null))).isNull()
        assertThat(onDecksOrLine(room(decks = null), "b")).isFalse()
    }

    @Test fun `open and empty, a listener is off them with four open spots and the host alone in the round`() {
        val ui = decksUi(room(DecksInfo(now = "h")))!!
        assertThat(ui.seats.map { it.id }).containsExactly("h")
        assertThat(ui.seats.single().host).isTrue()
        assertThat(ui.seats.single().now).isTrue()
        assertThat(ui.openSeats).isEqualTo(4)
        assertThat(ui.place).isEqualTo(DeckPlace.OFF)
        assertThat(ui.turnsAway).isNull()
        assertThat(ui.linePlace).isEqualTo(0)
    }

    @Test fun `the round is the host then the DJs, with whose song is on and whose is next`() {
        val ui = decksUi(room(DecksInfo(djs = listOf(DeckSeat("b", true), DeckSeat("me", false)), now = "b", next = "h")))!!
        assertThat(ui.seats.map { it.id }).containsExactly("h", "b", "me").inOrder()
        assertThat(ui.seats.map { it.name }).containsExactly("Maya", "Ben", "Rawn").inOrder()
        assertThat(ui.seats.map { it.now }).containsExactly(false, true, false).inOrder()
        assertThat(ui.seats.map { it.next }).containsExactly(true, false, false).inOrder()
        assertThat(ui.seats.map { it.ready }).containsExactly(true, true, false).inOrder()
        assertThat(ui.seats.map { it.me }).containsExactly(false, false, true).inOrder()
        assertThat(ui.openSeats).isEqualTo(2)
    }

    @Test fun `how far off this phone's turn is, counted round the decks`() {
        val djs = listOf(DeckSeat("b", true), DeckSeat("me", true), DeckSeat("c", true))
        fun away(now: String) = decksUi(room(DecksInfo(djs = djs, now = now)))!!.turnsAway
        assertThat(away("me")).isEqualTo(0) // its song is playing
        assertThat(away("b")).isEqualTo(1) // up next
        assertThat(away("h")).isEqualTo(2)
        assertThat(away("c")).isEqualTo(3) // right round: c, the host, b, then this phone
    }

    @Test fun `on the decks, with a song ready or not`() {
        val ready = decksUi(room(DecksInfo(djs = listOf(DeckSeat("me", true)), now = "h", next = "me")))!!
        assertThat(ready.place).isEqualTo(DeckPlace.ON_DECKS)
        assertThat(ready.myReady).isTrue()
        val not = decksUi(room(DecksInfo(djs = listOf(DeckSeat("me", false)), now = "me", next = "h")))!!
        assertThat(not.myReady).isFalse()
        assertThat(not.turnsAway).isEqualTo(0)
    }

    @Test fun `in the line, a place counted from one, and no spots left`() {
        val full = listOf(DeckSeat("b", true), DeckSeat("c", true), DeckSeat("d", true), DeckSeat("e", true))
        val ui = decksUi(room(DecksInfo(djs = full, line = listOf("f", "me"), now = "h")))!!
        assertThat(ui.place).isEqualTo(DeckPlace.IN_LINE)
        assertThat(ui.linePlace).isEqualTo(2)
        assertThat(ui.openSeats).isEqualTo(0)
        assertThat(ui.waiting).isEqualTo(2)
        assertThat(ui.turnsAway).isNull()
    }

    @Test fun `the host has no seat to take`() {
        val ui = decksUi(room(DecksInfo(djs = listOf(DeckSeat("b", true)), now = "me", next = "b"), role = SessionRole.HOST))!!
        assertThat(ui.place).isEqualTo(DeckPlace.HOST)
        assertThat(ui.seats.first().me).isTrue()
        assertThat(ui.turnsAway).isNull()
    }

    @Test fun `a DJ in their 60 seconds away keeps the name they were seen with`() {
        val ui = decksUi(room(DecksInfo(djs = listOf(DeckSeat("z", true)), now = "h"), names = mapOf("z" to "Zed")))!!
        assertThat(ui.seats.last().name).isEqualTo("Zed")
    }

    @Test fun `the host can take off someone on the decks or in the line, and nobody else`() {
        val r = room(DecksInfo(djs = listOf(DeckSeat("b", true)), line = listOf("c"), now = "h"))
        assertThat(onDecksOrLine(r, "b")).isTrue()
        assertThat(onDecksOrLine(r, "c")).isTrue()
        assertThat(onDecksOrLine(r, "me")).isFalse()
    }
}
