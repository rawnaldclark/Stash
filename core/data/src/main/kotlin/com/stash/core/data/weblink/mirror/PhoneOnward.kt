package com.stash.core.data.weblink.mirror

import android.util.Log
import com.stash.core.data.prefs.YouTubeHistoryPreference
import com.stash.core.data.social.LikeCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * What this phone sends on for a browser linked to it (owner's decision, 2026-11-01): the web can't reach Spotify, YouTube Music
 * or Last.fm itself, and the phone already holds those sign-ins. A browser's live likes go through the phone's "Like on … too"
 * and "Love on Last.fm too" switches, and its plays to YouTube Music history when "Add plays to your YouTube Music history" is on.
 * Nothing goes when a switch is off. [RoomMirrorLibrary] calls this only for changes the mirror read live from a browser.
 */
interface PhoneOnward {
    /** Sends a browser's like or unlike of [trackId] (already applied here) to the services the phone's switches turn on. */
    suspend fun like(trackId: Long, liked: Boolean)

    /** When "Add plays to your YouTube Music history" was turned on, or null while it is off (or can't be read). */
    suspend fun youTubeHistorySince(): Long?

    /** Sends nothing: tests, and a library that only reads. */
    object None : PhoneOnward {
        override suspend fun like(trackId: Long, liked: Boolean) = Unit
        override suspend fun youTubeHistorySince(): Long? = null
    }
}

/** [PhoneOnward] through [LikeCoordinator] (its outbox, pacing and switches) and the YouTube history switch. */
@Singleton
class PhoneSwitchesOnward @Inject constructor(
    private val likes: LikeCoordinator,
    private val history: YouTubeHistoryPreference,
) : PhoneOnward {
    override suspend fun like(trackId: Long, liked: Boolean) = likes.mirrorOnly(trackId, liked)

    /** Unreadable counts as off: a play is never sent to YouTube on a guess. */
    override suspend fun youTubeHistorySince(): Long? = try {
        history.sendPlaysSinceMs()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "couldn't read the YouTube history switch", e)
        null
    }

    private companion object {
        const val TAG = "PhoneOnward"
    }
}
