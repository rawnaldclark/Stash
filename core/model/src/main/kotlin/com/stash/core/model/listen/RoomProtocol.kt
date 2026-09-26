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
    @Serializable @SerialName("hello")
    data class Hello(val name: String? = null, val hostKey: String? = null, val resumeToken: String? = null) : ClientMessage

    @Serializable @SerialName("ping")
    data class Ping(val c: Long) : ClientMessage

    @Serializable @SerialName("load")
    /** [why]: skip, back, end, pick or radio, so phones can say "Rawn skipped" and stay quiet on a natural end. */
    data class Load(val track: SharedTrack, val positionMs: Long, val queue: List<SharedTrack>, val why: String? = null) : ClientMessage

    @Serializable @SerialName("play")
    data object Play : ClientMessage

    @Serializable @SerialName("pause")
    data object Pause : ClientMessage

    @Serializable @SerialName("seek")
    data class Seek(val positionMs: Long) : ClientMessage

    @Serializable @SerialName("queue")
    data class Queue(val queue: List<SharedTrack>) : ClientMessage

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

    @Serializable @SerialName("makeHost")
    data class MakeHost(val memberId: String) : ClientMessage

    @Serializable @SerialName("end")
    data object End : ClientMessage
}

/** Where the song is: at room time [atRoomMs] it is at [positionMs], moving if [playing] (spec §4). */
@Serializable
data class RoomTimeline(val positionMs: Long = 0, val atRoomMs: Long = 0, val playing: Boolean = false)

/** [status] is `ok`, `buffering`, `unavailable` or `drifting`. */
@Serializable
data class RoomMember(val id: String, val name: String? = null, val joinedAt: Long = 0, val status: String = "buffering")

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

    /** The room's queue after any change, each song with who added it: every phone's Up next. */
    @Serializable @SerialName("queue")
    data class QueueUpdate(val queue: List<SharedTrack>) : ServerMessage

    @Serializable @SerialName("reaction")
    data class Reaction(val from: String, val emoji: String) : ServerMessage

    @Serializable @SerialName("ended")
    data class Ended(val reason: String) : ServerMessage
}
