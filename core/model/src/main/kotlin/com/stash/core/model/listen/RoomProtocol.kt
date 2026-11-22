package com.stash.core.model.listen

import com.stash.core.model.share.SharedTrack
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Listen Together wire format (spec docs/superpowers/specs/2026-09-24-listen-together-design.md §3).
 * Each message is a JSON object whose `t` field names its type. The room side is
 * infra/share-worker/src/room.js. Songs travel as [SharedTrack] descriptors.
 */
object RoomProtocol {
    /** The six reactions, in picker order. Keep in sync with EMOJI in infra/share-worker/src/room.js. */
    val EMOJI: List<String> = listOf("❤️", "🔥", "😂", "😮", "👏", "🎶")

    /**
     * A session's 12 animated reactions, sent in [ClientMessage.React] as their id, in the tray's order. Keep in sync
     * with ANIMS in infra/share-worker/src/room.js. An id this app doesn't know is shown as nothing.
     */
    val ANIMS: List<String> = listOf(
        "a:applause", "a:fire", "a:hearts", "a:dancing", "a:mindblown", "a:lighter",
        "a:headphones", "a:rewind", "a:laughing", "a:bassdrop", "a:wave", "a:clap",
    )

    /**
     * A session's 10 quick phrases, sent in [ClientMessage.Say] as ids: Hi all, Who is this?, Tune!, Run it back!,
     * Turn it up, More like this, Chills, Thanks for the set, Good morning, Good night. Each app says them in its own
     * language. Keep in sync with PHRASES in room.js.
     */
    val PHRASES: List<String> = listOf("hi", "who", "tune", "again", "louder", "more", "chills", "thanks", "morning", "night")

    /**
     * What this app speaks, sent in [ClientMessage.Hello]: 3 = Sessions (a station's public room), which takes nothing
     * older; 2 was guests adding songs ([ADDS_VERSION]). A room answers with [RoomState.v]: a session 3, a private room
     * 2, an older Worker nothing (and the app suggests as before). Keep in sync with PUBLIC_V in room.js.
     */
    const val VERSION = 3

    /**
     * The first version that knows guest adds: a room or member at this or above takes [ClientMessage.Add],
     * [ClientMessage.Unqueue], [ClientMessage.Settings] and `qv`. Every capability check compares a room's or a
     * member's `v` with this, never with [VERSION]: a private room still answers 2 to an app that says more.
     */
    const val ADDS_VERSION = 2

    val json: Json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    /** A room message, or null for anything unknown or malformed: those are ignored (spec §6). */
    fun decode(text: String): ServerMessage? =
        runCatching { json.decodeFromString(ServerMessage.serializer(), text) }.getOrNull()
}

/** Phone → room (spec §3). Host-only messages sent by a listener are ignored by the room. */
@Serializable
sealed interface ClientMessage {
    /**
     * [v]: [RoomProtocol.VERSION]. [guestsAdd]: this phone's "Let guests add songs" switch, which rules the room
     * whenever this member hosts; sent on every hello, so a switch flipped while the socket was down still lands.
     * [communityKey]: a session listener's Community key, on every hello there (the room keeps only its hash);
     * never sent to a private room.
     */
    @Serializable @SerialName("hello")
    data class Hello(
        val name: String? = null,
        val hostKey: String? = null,
        val resumeToken: String? = null,
        val v: Int? = null,
        val guestsAdd: Boolean? = null,
        val communityKey: String? = null,
    ) : ClientMessage

    @Serializable @SerialName("ping")
    data class Ping(val c: Long) : ClientMessage

    /**
     * [why]: skip, back, end, pick, radio or unavailable, so phones can say "Rawn skipped" (or "Skipped …: Rawn can't
     * play it") and stay quiet on a natural end. [qv]: the room's queue version [queue] was built from, so the room can
     * put back the adds and removals that arrived since; null when the room never sent one (an older Worker).
     */
    @Serializable @SerialName("load")
    data class Load(
        val track: SharedTrack,
        val positionMs: Long,
        val queue: List<SharedTrack>,
        val why: String? = null,
        val qv: Int? = null,
    ) : ClientMessage

    @Serializable @SerialName("play")
    data object Play : ClientMessage

    @Serializable @SerialName("pause")
    data object Pause : ClientMessage

    @Serializable @SerialName("seek")
    data class Seek(val positionMs: Long) : ClientMessage

    /** The host's whole Up next after an edit; [qv] as in [Load]. */
    @Serializable @SerialName("queue")
    data class Queue(val queue: List<SharedTrack>, val qv: Int? = null) : ClientMessage

    /** `ready`, `buffering`, `unavailable` or `drifting`, for song [trackKey] so a late report can't count for the next song. */
    @Serializable @SerialName("status")
    data class Status(val status: String, val trackKey: Int? = null) : ClientMessage

    @Serializable @SerialName("suggest")
    data class Suggest(val track: SharedTrack) : ClientMessage

    /** [action] is `add` or `dismiss`. */
    @Serializable @SerialName("suggestion")
    data class SuggestionAction(val id: String, val action: String) : ClientMessage

    @Serializable @SerialName("react")
    data class React(val emoji: String) : ClientMessage

    /**
     * Play next ([next]) or Add to queue, a whole selection in one message (the room closes a socket over 20 messages
     * a second). Anyone may send it; a guest's becomes suggestions while the host doesn't let guests add.
     */
    @Serializable @SerialName("add")
    data class Add(val tracks: List<SharedTrack>, val next: Boolean) : ClientMessage

    /** Takes [track] out of the queue, where this phone saw it at [at]. The host any song; a guest only their own. */
    @Serializable @SerialName("unqueue")
    data class Unqueue(val track: SharedTrack, val at: Int) : ClientMessage

    /** Host only: the "Let guests add songs" switch. */
    @Serializable @SerialName("settings")
    data class Settings(val guestsAdd: Boolean) : ClientMessage

    @Serializable @SerialName("makeHost")
    data class MakeHost(val memberId: String) : ClientMessage

    @Serializable @SerialName("end")
    data object End : ClientMessage

    // ── Sessions only (infra/share-worker/README.md "Sessions protocol"); a private room ignores them ──

    /** A session listener asks for [track]: the host and co-hosts decide. Answered with [ServerMessage.RequestAnswer]. */
    @Serializable @SerialName("request")
    data class Request(val track: SharedTrack) : ClientMessage

    /** The host or a co-host answers request [id]: [action] is `next` (Play next), `queue` (Add to queue) or `pass`. */
    @Serializable @SerialName("requestAction")
    data class RequestAction(val id: String, val action: String) : ClientMessage

    /** A listener takes back their own waiting request [id]. */
    @Serializable @SerialName("requestCancel")
    data class RequestCancel(val id: String) : ClientMessage

    /** Host only: while [on], requests go straight in as Add to queue. */
    @Serializable @SerialName("autoAccept")
    data class AutoAccept(val on: Boolean) : ClientMessage

    /** Host only: member [memberId] becomes a co-host ([on]) or stops being one. At most 3. */
    @Serializable @SerialName("cohost")
    data class Cohost(val memberId: String, val on: Boolean) : ClientMessage

    /** Host only: [memberId] leaves the session and can't come back. Never a co-host: they stop being one first. */
    @Serializable @SerialName("boot")
    data class Boot(val memberId: String) : ClientMessage

    /** Host only: [state] is [SessionInfo.BOOTH] or [SessionInfo.AWAY]. */
    @Serializable @SerialName("presence")
    data class Presence(val state: String) : ClientMessage

    /** A co-host's Skip ([action] `skip`): the room passes it to the host's phone as [ServerMessage.Command]. */
    @Serializable @SerialName("command")
    data class Command(val action: String) : ClientMessage

    // ── Open decks (Sessions step 7): the host and up to four DJs take turns, one song each ──

    /** Host only: opens the decks, or closes them (every ready song is dropped). */
    @Serializable @SerialName("decks")
    data class Decks(val open: Boolean) : ClientMessage

    /** A listener takes a seat on the decks with [track] ready, or a place in the line when the seats are taken. */
    @Serializable @SerialName("deckJoin")
    data class DeckJoin(val track: SharedTrack) : ClientMessage

    /** A DJ's (or someone in the line's) ready song, replacing the one before. */
    @Serializable @SerialName("deckPick")
    data class DeckPick(val track: SharedTrack) : ClientMessage

    @Serializable @SerialName("deckLeave")
    data object DeckLeave : ClientMessage

    /** Host only: takes [memberId] off the decks or out of the line. */
    @Serializable @SerialName("deckRemove")
    data class DeckRemove(val memberId: String) : ClientMessage

    /** A quick phrase, by id ([RoomProtocol.PHRASES]): everyone gets [ServerMessage.Said]. One every 3 s. */
    @Serializable @SerialName("say")
    data class Say(val phrase: String) : ClientMessage

    /** Host only, "End after this song": the session ends when the playing song does. [on] false takes it back. */
    @Serializable @SerialName("lastSong")
    data class LastSong(val on: Boolean = true) : ClientMessage

    /** This person saved the song with [trackKey] (the playing one): counted once each in [SongStats.saves]. */
    @Serializable @SerialName("saved")
    data class Saved(val trackKey: Int) : ClientMessage
}

/** Why a room ended ([ServerMessage.Ended.reason]), as room.js says it. */
object EndedReason {
    /** The host ended it for everyone. */
    const val HOST_ENDED = "host_ended"

    /** A session's host was gone 60 s (a session is never handed on). */
    const val HOST_LEFT = "host_left"

    /** A session went 15 minutes without playing. */
    const val PAUSED = "paused"

    /** A session reached 12 hours. */
    const val MAX_AGE = "max_age"

    /** A session's station was deleted, removed, hidden by votes, or its phone blocked. */
    const val STATION_REMOVED = "station_removed"

    /** A newer session of the same station took over. */
    const val REPLACED = "replaced"

    /** A private room reached its age, or emptied. */
    const val CLOSED = "closed"

    /** This member was booted from the session. */
    const val BOOTED = "booted"

    /** The host's last song finished. */
    const val LAST_SONG = "last_song"
}

/**
 * What every phone in a session knows about it, in the welcome's state and (flat) in each `session` frame: the
 * [station] it's on the air for, [listeners] (everyone connected but the host), the [cohosts]' member ids, whether
 * requests go straight in ([autoAccept]), and where the host is ([presence]: [BOOTH] or [AWAY]).
 */
@Serializable
data class SessionInfo(
    val station: String,
    val listeners: Int = 0,
    val cohosts: List<String> = emptyList(),
    val autoAccept: Boolean = false,
    val presence: String = BOOTH,
    /** The host called the playing song the last one: the session ends when it does. */
    val lastSong: Boolean = false,
) {
    companion object {
        const val BOOTH = "booth"
        const val AWAY = "away"
    }
}

/** A DJ on the decks: [ready] when they have a song waiting, or already in the queue for their turn. */
@Serializable
data class DeckSeat(val id: String, val ready: Boolean = false)

/**
 * The open decks, as every phone knows them: the DJs in the order of their turns (the host's turn comes first in each
 * round and isn't listed), the line waiting for a seat, whose song is playing ([now]) and whose turn is next ([next];
 * null until it's decided, the host's id when it's the host's own queue).
 */
@Serializable
data class DecksInfo(
    val open: Boolean = true,
    val djs: List<DeckSeat> = emptyList(),
    val line: List<String> = emptyList(),
    val now: String? = null,
    val next: String? = null,
) {
    companion object {
        /** The room's rule: seats beside the host. */
        const val SEATS = 4
    }
}

/**
 * The playing song's crowd counts, in a session: how many people have reacted to it and saved it (never who), and
 * whether it became a crowd favourite (3 people and a third of the room reacting while it played).
 */
@Serializable
data class SongStats(val trackKey: Int, val reactions: Int = 0, val saves: Int = 0, val favourite: Boolean = false)

/** A request waiting for the host: [from] asked for [track]. The host and co-hosts see them all, a listener their own. */
@Serializable
data class RoomRequest(val id: String, val from: String, val track: SharedTrack)

/** Where the song is: at room time [atRoomMs] it is at [positionMs], moving if [playing] (spec §4). */
@Serializable
data class RoomTimeline(val positionMs: Long = 0, val atRoomMs: Long = 0, val playing: Boolean = false)

/** [status] is `ok`, `buffering`, `unavailable` or `drifting`. */
/** [v]: the protocol version this member's app speaks (null for an app from before guest adds). */
@Serializable
data class RoomMember(
    val id: String,
    val name: String? = null,
    val joinedAt: Long = 0,
    val status: String = "buffering",
    val v: Int? = null,
    /** In a session: a regular at this station (3 sessions of 10 minutes or more). Only the fact is ever sent. */
    val regular: Boolean = false,
)

@Serializable
data class RoomSuggestion(val id: String, val from: String, val track: SharedTrack)

@Serializable
data class RoomPhase(val kind: String = PLAYING, val trackKey: Int? = null, val deadlineMs: Long? = null) {
    companion object {
        const val PLAYING = "playing"
        const val PREPARING = "preparing"
    }
}

@Serializable
data class RoomState(
    val rev: Int = 0,
    val host: String? = null,
    val track: SharedTrack? = null,
    val trackKey: Int = 0,
    val timeline: RoomTimeline = RoomTimeline(),
    val queue: List<SharedTrack> = emptyList(),
    val members: List<RoomMember> = emptyList(),
    val suggestions: List<RoomSuggestion> = emptyList(),
    val phase: RoomPhase = RoomPhase(),
    /** The room's protocol version: 1 (missing) is a Worker from before guest adds, which knows only suggestions. */
    val v: Int = 1,
    /** Guests' adds go straight into the queue now (the host's app knows them and its switch is on). */
    val guestsAdd: Boolean = false,
    /** The queue's version; null from an older Worker. */
    val qv: Int? = null,
    /** A session's own state ([v] 3); null in a private room. */
    val session: SessionInfo? = null,
    /** The playing song's crowd counts, once anyone has reacted to it or saved it (a session only). */
    val songStats: SongStats? = null,
    /** The open decks; null while they're closed (a session only). */
    val decks: DecksInfo? = null,
)

/** Room → phone (spec §3). */
@Serializable
sealed interface ServerMessage {
    @Serializable @SerialName("welcome")
    data class Welcome(val memberId: String, val token: String, val state: RoomState) : ServerMessage

    /** [c] is the phone's ping time echoed back; [r] is the room clock. */
    @Serializable @SerialName("pong")
    data class Pong(val c: Long, val r: Long) : ServerMessage

    @Serializable @SerialName("state")
    data class StateSync(val state: RoomState) : ServerMessage

    @Serializable @SerialName("timeline")
    data class TimelineUpdate(
        val rev: Int,
        val trackKey: Int,
        val positionMs: Long,
        val atRoomMs: Long,
        val playing: Boolean,
        /** The member whose play, pause or seek caused this; null when the room itself started playback. */
        val by: String? = null,
    ) : ServerMessage

    /** [positionMs] is where to start the song; the spec's payload plus this one field, so phones needn't wait for `state`. */
    @Serializable @SerialName("prepare")
    data class Prepare(
        val trackKey: Int,
        val track: SharedTrack,
        val positionMs: Long = 0,
        val deadlineMs: Long,
        val by: String? = null,
        val why: String? = null,
    ) : ServerMessage

    @Serializable @SerialName("members")
    data class Members(val members: List<RoomMember>) : ServerMessage

    @Serializable @SerialName("suggestions")
    data class Suggestions(val suggestions: List<RoomSuggestion>) : ServerMessage

    /** The room's queue after any change, each song with who added it: every phone's Up next. [qv]: its version. */
    @Serializable @SerialName("queue")
    data class QueueUpdate(val queue: List<SharedTrack>, val qv: Int? = null) : ServerMessage

    /**
     * [by] added [count] songs, the first [track], as Play next ([next]) or to the queue: "Sam added …". Every `add`
     * gets one of these back, so with count 0 (to the sender alone) it says nothing went in.
     */
    @Serializable @SerialName("added")
    data class Added(
        val by: String,
        val next: Boolean = false,
        val count: Int = 1,
        val track: SharedTrack? = null,
        /** Only to the sender, with count 0: their add became this many suggestions (the room turned adds off, or the host's app is older). */
        val suggested: Int? = null,
    ) : ServerMessage

    /** Whether guests' adds go straight in now, after the host flipped the switch (or a new host's took over). */
    @Serializable @SerialName("settings")
    data class SettingsUpdate(val guestsAdd: Boolean) : ServerMessage

    @Serializable @SerialName("reaction")
    data class Reaction(val from: String, val emoji: String) : ServerMessage

    /** [reason]: one of [EndedReason]. */
    @Serializable @SerialName("ended")
    data class Ended(val reason: String) : ServerMessage

    // ── Sessions only (infra/share-worker/README.md "Sessions protocol") ──

    /** The session's state after a change: [SessionInfo]'s fields, flat. */
    @Serializable @SerialName("session")
    data class Session(
        val station: String,
        val listeners: Int = 0,
        val cohosts: List<String> = emptyList(),
        val autoAccept: Boolean = false,
        val presence: String = SessionInfo.BOOTH,
        val lastSong: Boolean = false,
    ) : ServerMessage {
        val info: SessionInfo get() = SessionInfo(station, listeners, cohosts, autoAccept, presence, lastSong)
    }

    /** The waiting requests this member may see, after any change: all of them to the host and co-hosts, a listener's own. */
    @Serializable @SerialName("requests")
    data class Requests(val requests: List<RoomRequest>) : ServerMessage

    /** The answer to this phone's request: [state] is `queued` (Auto-accept), `waiting`, or `full` (3 each, 30 in all). */
    @Serializable @SerialName("requestAnswer")
    data class RequestAnswer(val state: String) : ServerMessage

    /** To its requester alone: member [by] passed on [track]. */
    @Serializable @SerialName("requestPassed")
    data class RequestPassed(val track: SharedTrack, val by: String) : ServerMessage

    /** The decks after a change: [DecksInfo]'s fields, flat; [open] false when the host closed them. */
    @Serializable @SerialName("decksState")
    data class DecksState(
        val open: Boolean = true,
        val djs: List<DeckSeat> = emptyList(),
        val line: List<String> = emptyList(),
        val now: String? = null,
        val next: String? = null,
    ) : ServerMessage

    /** [memberId] is off the decks: [reason] `skipped` after two turns with no song ready. */
    @Serializable @SerialName("deckOut")
    data class DeckOut(val memberId: String, val reason: String = "") : ServerMessage

    /** Member [from] said quick phrase [phrase] (an id of [RoomProtocol.PHRASES]; an unknown one is shown as nothing). */
    @Serializable @SerialName("said")
    data class Said(val from: String, val phrase: String) : ServerMessage

    /** The playing song's counts after a change: [SongStats]'s fields, flat. At most once every 2 s. */
    @Serializable @SerialName("songStats")
    data class Stats(val trackKey: Int, val reactions: Int = 0, val saves: Int = 0, val favourite: Boolean = false) : ServerMessage

    /** Something this phone sent that the room turned down: [what] (`request`, `cohost`, `skip`, `boot`) and why. */
    @Serializable @SerialName("refused")
    data class Refused(val what: String, val reason: String) : ServerMessage

    /** To the host's phone: co-host [from] pressed [action] (`skip`) on song [trackKey]. Ignored once that song has changed. */
    @Serializable @SerialName("command")
    data class Command(val action: String, val from: String, val trackKey: Int) : ServerMessage
}
