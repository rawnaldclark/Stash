package com.stash.core.model.listen

import kotlinx.serialization.Serializable

/**
 * What one session was, for the person who was in it (Sessions UX spec §6): made on the phone from what the room
 * sent while they were there, kept on the phone for a week, and never sent anywhere. A listener's and a host's are
 * the same record; [wasHost] says which screen reads it.
 */
@Serializable
data class SessionRecap(
    /** The room's code and when this phone entered it: one recap a stay. */
    val id: String,
    val stationId: String,
    val stationName: String? = null,
    val hostName: String? = null,
    val wasHost: Boolean = false,
    val startedAt: Long,
    val endedAt: Long,
    /** A listener: how long the room's music played on this phone. A host: how long the station was on the air. */
    val listenedMs: Long = 0,
    /** Songs that played while this phone was there. */
    val songs: Int = 0,
    /** Everyone else seen in the session. */
    val people: Int = 0,
    /** The most listeners at once (the host's recap). */
    val peak: Int = 0,
    /** Regulars among them (the host's recap). */
    val regulars: Int = 0,
    /** This person's songs that played, with how many people reacted to each. */
    val myPicks: List<RecapSong> = emptyList(),
    /** Listeners' and co-hosts' songs the host's session played (the host's recap). */
    val requestsPlayed: Int = 0,
    /** Songs that became crowd favourites. */
    val favourites: List<RecapSong> = emptyList(),
    /** Songs this person saved to Liked Songs during it. */
    val saved: List<RecapSong> = emptyList(),
    /** The song most people saved (the host's recap); null when nobody saved any. */
    val mostSaved: RecapSong? = null,
    /**
     * The station's followers gained while it was on the air (the host's recap): its count after, less its count
     * before. Null when either couldn't be read, and in a recap kept by an older app.
     */
    val newFollowers: Int? = null,
    /**
     * When the station's schedule puts it on the air next, as the station's page said when this stay ended (a
     * listener's recap). Null when it has no schedule, or the page couldn't be read.
     */
    val nextOnAt: Long? = null,
)

/** A song in a recap. [reactions] and [saves] are people, as the room counted them. */
@Serializable
data class RecapSong(val title: String, val artist: String, val reactions: Int = 0, val saves: Int = 0)
