package com.stash.core.media.listen

import com.stash.core.model.listen.RecapSong
import com.stash.core.model.listen.SessionRecap
import com.stash.core.model.share.SharedTrack

/**
 * Builds one stay's [SessionRecap] from the room's states as they arrive (Sessions UX spec §6): the songs that
 * played, who came by, this person's picks and how the room took them, the crowd favourites. Nothing here is asked
 * of the server: it is what this phone saw. Not thread-safe; fed from one place.
 */
class RecapBuilder(first: ListenTogetherState.InRoom, private val startedAt: Long) {
    private val code = first.code
    private val stationId = first.session?.station.orEmpty()
    private val wasHost = first.isHost
    private val myId = first.myId

    private var hostName: String? = null
    private val people = HashSet<String>()
    private val regulars = HashSet<String>()

    /** The host's: each listener's earlier sessions here, as the room said; and how long each has been in this one. */
    private val visits = HashMap<String, Int>()
    private val stayedMs = HashMap<String, Long>()
    private var present: Set<String> = emptySet()
    private var presentSince = 0L
    private var peak = 0

    /** The song that's playing, and the room's last counts for it. */
    private var song: SharedTrack? = null
    private var songSince = 0L
    private var songReactions = 0
    private var songSaves = 0
    private var songFavourite = false

    /** Whose turn on the open decks the room last said the playing song is; null with the decks closed. */
    private var songDeckTurn: String? = null

    /** The co-hosts as the room last had them: they add songs themselves, so theirs aren't requests. */
    private var cohosts: List<String> = emptyList()

    private var songs = 0
    private var requestsPlayed = 0
    private val myPicks = ArrayList<RecapSong>()
    private val favourites = ArrayList<RecapSong>()
    private val saved = ArrayList<RecapSong>()
    private var mostSaved: RecapSong? = null

    /** A listener's time with the room's music playing: added up between states. */
    private var listenedMs = 0L
    private var onAirSince: Long? = null

    init { on(first, startedAt) }

    /** The room as it is at [now]. */
    fun on(room: ListenTogetherState.InRoom, now: Long) {
        room.hostId?.let { id -> (room.members.firstOrNull { it.id == id }?.name ?: room.names[id])?.takeIf(String::isNotBlank)?.let { hostName = it } }
        // The time since the last state belongs to whoever was here through it.
        for (id in present) stayedMs[id] = (stayedMs[id] ?: 0L) + (now - presentSince)
        present = room.members.mapNotNull { m -> m.id.takeIf { it != myId } }.toSet()
        presentSince = now
        visits.putAll(room.visits)
        for (m in room.members) {
            if (m.id == myId) continue
            people += m.id
            if (m.regular) regulars += m.id
        }
        peak = maxOf(peak, room.session?.listeners ?: 0)

        if (room.track != song) {
            closeSong(now)
            song = room.track
            songSince = now
        }
        songDeckTurn = room.decks?.now
        cohosts = room.session?.cohosts.orEmpty()
        room.songStats?.let { stats ->
            songReactions = stats.reactions
            songSaves = stats.saves
            songFavourite = stats.favourite
        }

        // On air from the moment the state said so until the state that says otherwise (or the end).
        if (room.onAir) {
            if (onAirSince == null) onAirSince = now
        } else {
            onAirSince?.let { listenedMs += now - it }
            onAirSince = null
        }
    }

    /** This person saved the playing song. */
    fun saved() {
        val s = song ?: return
        if (saved.none { it.title == s.title && it.artist == s.artist }) saved += RecapSong(s.title, s.artist)
    }

    /** The song that was playing is over: what the room made of it goes into the lists. */
    private fun closeSong(now: Long) {
        val s = song ?: return
        // A song that was the room's for only a moment didn't play: the queue's restored song at its last second when
        // a host goes live, or one skipped as it started. It isn't counted, and nothing about it is listed.
        if (now - songSince < MIN_SONG_MS) {
            songReactions = 0
            songSaves = 0
            songFavourite = false
            return
        }
        songs++
        val line = RecapSong(s.title, s.artist, songReactions, songSaves)
        if (s.addedBy == myId && !wasHost) myPicks += line
        // A request is a listener's song the booth let in. A DJ's turn on the open decks is theirs by right, and a
        // co-host adds songs themselves: nobody asked for either.
        val by = s.addedBy
        if (wasHost && by != null && by != myId && by != songDeckTurn && by !in cohosts) requestsPlayed++
        if (songFavourite) favourites += line
        if (songSaves > 0 && songSaves > (mostSaved?.saves ?: 0)) mostSaved = line
        songReactions = 0
        songSaves = 0
        songFavourite = false
    }

    /**
     * The stay is over at [now] ([ended]: the session ended while this phone was in it, rather than this phone
     * leaving). A listener gets a recap for a stay that reached the session's end or lasted [MIN_STAY_MS]; a host
     * for any session that played a song. Null otherwise: a look-in isn't worth a recap.
     */
    fun finish(now: Long, ended: Boolean, stationName: String? = null): SessionRecap? {
        closeSong(now)
        song = null
        onAirSince?.let { listenedMs += now - it }
        onAirSince = null
        for (id in present) stayedMs[id] = (stayedMs[id] ?: 0L) + (now - presentSince)
        present = emptySet()
        if (songs == 0) return null
        if (!wasHost && !ended && now - startedAt < MIN_STAY_MS) return null
        return SessionRecap(
            id = "$code-$startedAt",
            stationId = stationId,
            stationName = stationName,
            hostName = hostName,
            wasHost = wasHost,
            startedAt = startedAt,
            endedAt = now,
            listenedMs = if (wasHost) now - startedAt else listenedMs,
            songs = songs,
            people = people.size,
            peak = peak,
            regulars = regulars.size,
            // One session short of a regular when they came, and this one counted.
            newRegulars = if (wasHost) visits.count { (id, n) -> n == REGULAR_SESSIONS - 1 && (stayedMs[id] ?: 0L) >= MIN_STAY_MS } else 0,
            myPicks = myPicks.toList(),
            requestsPlayed = requestsPlayed,
            favourites = favourites.toList(),
            saved = saved.toList(),
            mostSaved = mostSaved,
        )
    }

    companion object {
        /** A listener who left before the end gets a recap after this long (the spec's 10 minutes). */
        const val MIN_STAY_MS = 10 * 60_000L

        /** Sessions stayed for that make a listener a regular at a station (the room's REGULAR_SESSIONS). */
        const val REGULAR_SESSIONS = 3

        /** A song counts as played once it was the room's song this long. */
        const val MIN_SONG_MS = 15_000L
    }
}
