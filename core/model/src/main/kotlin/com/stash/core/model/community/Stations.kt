package com.stash.core.model.community

import com.stash.core.model.share.SharedTrack
import kotlinx.serialization.Serializable

/**
 * A station (Sessions; infra/share-worker/README.md "Contract for the app"): the README's Summary and every shape built
 * on it. An answer to a request that carried this phone's key also says [myVote], [mine], [heard] and [following]; your
 * own station (`stations/mine`, an edit) has the Own fields; one on a schedule has [schedule] and [next]; one in
 * `stations/following` has [live] while it's on the air.
 */
@Serializable
data class Station(
    val id: String,
    val name: String,
    /** The name the host goes by on the air. */
    val hostName: String? = null,
    /** The station's one line ("What's playing tonight?"). */
    val vibe: String? = null,
    val up: Int = 0,
    val down: Int = 0,
    val followers: Int = 0,
    /** -1, 0 or 1: this phone's vote. With a key only, like [mine], [heard] and [following]. */
    val myVote: Int = 0,
    val mine: Boolean = false,
    /** This phone has listened 2 minutes in the station's sessions, so it may vote. Your own station is heard. */
    val heard: Boolean = false,
    val following: Boolean = false,
    /** Own: `off`, `schedule` or `always` (auto-live, Sessions step 6). */
    val autoLive: String? = null,
    val schedule: StationSchedule? = null,
    /** Own: hidden from everyone else by votes (up minus down of -3 or below) until a moderator vouches for it. */
    val hidden: Boolean = false,
    /** Own: taken down by a moderator; deleted a day later. */
    val removed: Boolean = false,
    /** Own: when it was last on the air, ms; null if never. */
    val lastLiveAt: Long? = null,
    /** Own: when the daily cleanup deletes it, ms: 7 days after it was last on the air (or made). */
    val deleteAt: Long? = null,
    /** On a schedule: the window in progress, or else the next one. */
    val next: OnAirWindow? = null,
    /** In `stations/following`: its session, while it's on the air. */
    val live: StationLive? = null,
) {
    /** Up minus down: what Live now ranks by, with the listeners. */
    val score: Int get() = up - down
}

/** When a station goes live on its own (Sessions step 6): [windows] on the host's wall clock in [tz], an IANA zone. */
@Serializable
data class StationSchedule(val tz: String, val windows: List<ScheduleWindow> = emptyList())

/** [day] 1 to 7, Monday = 1; [start] and [end] as "HH:MM". An end before its start runs past midnight. */
@Serializable
data class ScheduleWindow(val day: Int, val start: String, val end: String)

/** A time on the air, in ms: the window in progress, or else the next one. */
@Serializable
data class OnAirWindow(val start: Long, val end: Long)

/** One of a session's first faces, the host first: the initial [i] (null without a name), and the member [id]. */
@Serializable
data class CrowdFace(val i: String? = null, val id: String)

/** A station on the air: [room] is its session's code; [full] at 50 listeners. [crowd] only in `stations/following`. */
@Serializable
data class StationLive(
    val room: String,
    val track: SharedTrack? = null,
    val listeners: Int = 0,
    val paused: Boolean = false,
    val full: Boolean = false,
    val crowd: List<CrowdFace> = emptyList(),
)

/** One of Live now's sessions (`GET live`), in the server's order: best first. */
@Serializable
data class LiveSession(
    val station: Station,
    val room: String,
    val track: SharedTrack? = null,
    val listeners: Int = 0,
    val paused: Boolean = false,
    val full: Boolean = false,
    val crowd: List<CrowdFace> = emptyList(),
    val startedAt: Long = 0,
)

/** A station's page (`GET stations/{id}`): the station, and its session while it's on the air. */
@Serializable
data class StationPage(val station: Station, val live: StationLive? = null)

/** A follow's answer: whether this phone follows the station now, and how many do. */
@Serializable
data class FollowState(val following: Boolean, val followers: Int)
