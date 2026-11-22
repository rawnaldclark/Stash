package com.stash.feature.nowplaying.session

import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.media.listen.SessionRole
import com.stash.core.model.listen.SessionInfo
import com.stash.core.model.share.SharedTrack

/**
 * The words and lists of a session's requests (Sessions step 3), worked out from the room with no screen in them, so
 * the rules are tested as plain functions.
 */

/** The most requests one listener can have waiting: the room's own rule, said on the sheet ("2 of 3"). */
internal const val MAX_WAITING = 3

/** Where the host is, as a listener's Request sheet says it. */
internal enum class HostPresence {
    /** "Maya is in the booth and sees requests." */
    BOOTH,

    /** "Maya is away. Requests wait." */
    AWAY_WAIT,

    /** "Maya is away. Requests play automatically." */
    AWAY_AUTO,

    /** In the booth with Auto-accept on: "Requests play automatically." */
    BOOTH_AUTO,
}

internal fun hostPresence(session: SessionInfo): HostPresence = when {
    session.presence == SessionInfo.AWAY && session.autoAccept -> HostPresence.AWAY_AUTO
    session.presence == SessionInfo.AWAY -> HostPresence.AWAY_WAIT
    session.autoAccept -> HostPresence.BOOTH_AUTO
    else -> HostPresence.BOOTH
}

/** The host's name as the room knows it, or null. */
internal fun hostName(room: ListenTogetherState.InRoom): String? =
    room.hostId?.let { id -> room.members.firstOrNull { it.id == id }?.name ?: room.names[id] }?.takeIf(String::isNotBlank)

/** One of this listener's requests, as "Your requests" shows it. */
internal data class MyRequest(
    val track: SharedTrack,
    /** True once the host took it: it is in Up next, at [queueIndex]. False while it waits for an answer. */
    val accepted: Boolean,
    /** Where it sits in the room's queue, for taking it back; -1 while it waits. */
    val queueIndex: Int = -1,
)

/** This listener's songs: the ones in Up next first (in the order they'll play), then the ones still waiting. */
internal fun myRequests(room: ListenTogetherState.InRoom): List<MyRequest> {
    val accepted = room.queue.mapIndexedNotNull { i, t -> if (t.addedBy == room.myId) MyRequest(t, accepted = true, queueIndex = i) else null }
    val waiting = room.requests.filter { it.from == room.myId }.map { MyRequest(it.track, accepted = false) }
    return accepted + waiting
}

/** How many of this listener's requests wait for an answer: against [MAX_WAITING]. */
internal fun myWaiting(room: ListenTogetherState.InRoom): Int = room.requests.count { it.from == room.myId }

/** A request as the booth lists it. */
internal data class WaitingRequest(val id: String, val track: SharedTrack, val from: String?)

/** The requests the host and co-hosts answer, oldest first, each with who asked (null when the room has no name). */
internal fun waitingRequests(room: ListenTogetherState.InRoom): List<WaitingRequest> =
    room.requests.map { r ->
        val name = (room.members.firstOrNull { it.id == r.from }?.name ?: room.names[r.from])?.takeIf(String::isNotBlank)
        WaitingRequest(r.id, r.track, name)
    }

/** Whether this phone answers requests: the host and the co-hosts. */
internal fun answersRequests(room: ListenTogetherState.InRoom): Boolean =
    room.role == SessionRole.HOST || room.role == SessionRole.COHOST

/** What the host can do to a member from the crowd's list. */
internal data class MemberActions(val canCohost: Boolean, val isCohost: Boolean, val canBoot: Boolean)

/**
 * Only the host acts on people, never on themselves. Making a 4th co-host isn't offered ([MAX_COHOSTS]); taking the
 * part away always is.
 */
internal fun memberActions(room: ListenTogetherState.InRoom, memberId: String): MemberActions? {
    if (room.role != SessionRole.HOST || memberId == room.myId || memberId == room.hostId) return null
    val cohosts = room.session?.cohosts.orEmpty()
    val isCohost = memberId in cohosts
    return MemberActions(canCohost = isCohost || cohosts.size < MAX_COHOSTS, isCohost = isCohost, canBoot = true)
}

/** The room's rule. */
internal const val MAX_COHOSTS = 3
