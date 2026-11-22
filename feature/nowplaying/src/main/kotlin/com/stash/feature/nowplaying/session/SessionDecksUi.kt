package com.stash.feature.nowplaying.session

import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.listen.DecksInfo

/**
 * The open decks (Sessions step 7) as the room's screen shows them, worked out from the room with no screen in it:
 * who is on, whose song is playing and whose is next, the open seats, the line, and where this phone stands.
 */

/** Where this phone is, as far as the decks go. */
internal enum class DeckPlace { OFF, IN_LINE, ON_DECKS, HOST }

/** One seat in the round, the host's first. */
internal data class DeckSeatUi(
    val id: String,
    val name: String?,
    val host: Boolean,
    /** Their song is playing. */
    val now: Boolean,
    /** Their turn is next. */
    val next: Boolean,
    /** A DJ with a song ready (the host always has a queue). */
    val ready: Boolean,
    val me: Boolean,
)

internal data class DecksUi(
    val seats: List<DeckSeatUi>,
    /** Seats nobody has taken. */
    val openSeats: Int,
    /** People waiting in the line. */
    val waiting: Int,
    val place: DeckPlace,
    /** This phone's place in the line, from 1; 0 when it isn't in it. */
    val linePlace: Int,
    /** On the decks: this phone has a song ready (or already queued for its turn). */
    val myReady: Boolean,
    /**
     * On the decks: how many songs until this phone's turn, counting the one playing. 0 = its song is playing,
     * 1 = it's up next. Null when it isn't on the decks.
     */
    val turnsAway: Int?,
)

/** The decks as [room] has them, or null while they're closed (or outside a session). */
internal fun decksUi(room: ListenTogetherState.InRoom): DecksUi? {
    val decks = room.decks ?: return null
    val hostId = room.hostId
    fun nameOf(id: String) = (room.members.firstOrNull { it.id == id }?.name ?: room.names[id])?.takeIf(String::isNotBlank)
    val order = listOfNotNull(hostId) + decks.djs.map { it.id }
    val seats = order.map { id ->
        DeckSeatUi(
            id = id,
            name = nameOf(id),
            host = id == hostId,
            now = id == decks.now,
            next = id == decks.next,
            ready = id == hostId || decks.djs.firstOrNull { it.id == id }?.ready == true,
            me = id == room.myId,
        )
    }
    val mine = decks.djs.firstOrNull { it.id == room.myId }
    val linePlace = decks.line.indexOf(room.myId) + 1
    val place = when {
        room.isHost -> DeckPlace.HOST
        mine != null -> DeckPlace.ON_DECKS
        linePlace > 0 -> DeckPlace.IN_LINE
        else -> DeckPlace.OFF
    }
    val turnsAway = if (mine == null) null else {
        val nowAt = order.indexOf(decks.now).coerceAtLeast(0)
        val meAt = order.indexOf(room.myId)
        Math.floorMod(meAt - nowAt, order.size)
    }
    return DecksUi(
        seats = seats,
        openSeats = (DecksInfo.SEATS - decks.djs.size).coerceAtLeast(0),
        waiting = decks.line.size,
        place = place,
        linePlace = linePlace,
        myReady = mine?.ready == true,
        turnsAway = turnsAway,
    )
}

/** Whether [memberId] is on the decks or in the line: someone the host can take off. */
internal fun onDecksOrLine(room: ListenTogetherState.InRoom, memberId: String): Boolean =
    room.decks?.let { d -> d.djs.any { it.id == memberId } || memberId in d.line } == true
