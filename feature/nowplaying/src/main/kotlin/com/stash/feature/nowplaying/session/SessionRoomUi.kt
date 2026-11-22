package com.stash.feature.nowplaying.session

import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.listen.RoomMember
import com.stash.feature.nowplaying.listen.nameOf

/*
 * What a session's room shows, worked out from the room's state with no Android in it, so the rules are unit-tested
 * (SessionRoomViewModelTest). The composables only draw these.
 */

/** Whose pick the room's song is: [name] may be unknown (the room knew only their id); [mine] is this phone's own. */
internal data class Pick(val memberId: String, val name: String?, val mine: Boolean)

/**
 * "Ben's pick": who added the song the room is playing, from the song's `addedBy` and the names the room has sent
 * (kept after someone leaves). The host's own songs say nothing: the station is theirs.
 */
internal fun pickOf(room: ListenTogetherState.InRoom): Pick? {
    val by = room.track?.addedBy ?: return null
    if (by == room.hostId) return null
    return Pick(by, room.nameOf(by), mine = by == room.myId)
}

/** Someone in the booth: the host (the crown) or a co-host. */
internal data class Seat(val member: RoomMember, val host: Boolean)

/**
 * The room's crowd, as one band draws it: the [booth] on the left (the host, then co-hosts), then the [floor]
 * (everyone else, in the order they joined) as far as fits, and how many [more] are there. [everyone] is the whole
 * room in that order, for the sheet that lists names.
 */
internal data class Crowd(val booth: List<Seat>, val floor: List<RoomMember>, val more: Int, val everyone: List<RoomMember>)

/** The crowd of [room] with room for [fits] faces on the floor. A host or co-host who isn't connected isn't drawn. */
internal fun crowdOf(room: ListenTogetherState.InRoom, fits: Int): Crowd {
    val cohosts = room.session?.cohosts.orEmpty()
    val host = room.members.firstOrNull { it.id == room.hostId }
    val helpers = cohosts.mapNotNull { id -> room.members.firstOrNull { it.id == id && it.id != room.hostId } }
    val booth = listOfNotNull(host?.let { Seat(it, host = true) }) + helpers.map { Seat(it, host = false) }
    val inBooth = booth.map { it.member.id }.toSet()
    val floor = room.members.filter { it.id !in inBooth }.sortedBy { it.joinedAt }
    val shown = floor.take(fits.coerceAtLeast(0))
    return Crowd(booth = booth, floor = shown, more = floor.size - shown.size, everyone = booth.map { it.member } + floor)
}

/**
 * Reactions are drawn at most [perSecond] a second (Sessions step 2, decision 6): a full room sends each phone about
 * 50 reaction frames a second, and that many floaters would be a blur and a slow screen. The ones not drawn still
 * count for the song on the server. Main thread only.
 */
internal class ReactionGate(private val perSecond: Int = 4) {
    private val drawn = ArrayDeque<Long>()

    /** Whether a reaction arriving at [now] (ms) gets a floater. */
    fun allow(now: Long): Boolean {
        while (drawn.isNotEmpty() && now - drawn.first() >= WINDOW_MS) drawn.removeFirst()
        if (drawn.size >= perSecond) return false
        drawn.addLast(now)
        return true
    }

    private companion object {
        const val WINDOW_MS = 1_000L
    }
}
