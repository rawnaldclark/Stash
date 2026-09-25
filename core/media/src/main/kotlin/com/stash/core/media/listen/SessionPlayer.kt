package com.stash.core.media.listen

import androidx.media3.common.MediaItem
import com.stash.core.model.share.SharedTrack

/** The user's own queue as the service held it when the session began. */
data class UserQueue(val current: MediaItem?, val upcoming: List<MediaItem>, val positionMs: Long)

/**
 * What the session engine needs from the playback service (spec §5 "New player abilities").
 * StashPlaybackService implements it over its master ExoPlayer; tests fake it. The spec's
 * `startAt(roomMs)` is not here: only the session knows the room clock, so it schedules [play].
 */
interface SessionPlayer {
    fun userQueue(): UserQueue

    /** Writes the exact current position into PlaybackStateStore; the queue itself is already saved there (spec §4). */
    suspend fun saveUserPosition()

    /**
     * Puts ListenTogetherPlayer in front of the MediaSession for this role, suspends crossfade in
     * memory and keeps the notification in the foreground. Called again when the role changes.
     */
    fun enterSession(isHost: Boolean, interceptor: SessionInterceptor)

    /** Stops and empties the player, then undoes [enterSession]. PlayerRepositoryImpl then restores the user's queue. */
    fun exitSession()

    /** prepareAt: one item at [positionMs], prepared and held with playWhenReady = false. */
    fun load(item: MediaItem, positionMs: Long)
    fun seekTo(positionMs: Long)
    fun play()
    fun pause()

    /** Pitch-preserving speed (PlaybackParameters; Sonic is at the end of the StashRenderersFactory chain). */
    fun setSpeed(speed: Float)

    val positionMs: Long

    /** The loaded item's duration, or null while unknown. */
    val durationMs: Long?

    /** The loaded song played to its end (STATE_ENDED): a new host's Play must move the room on, not replay it. */
    val ended: Boolean

    var events: SessionPlayerEvents?
}

interface SessionPlayerEvents {
    fun onReady()
    fun onBuffering()
    fun onEnded()
    fun onError()

    /** Headphones unplugged or audio focus lost: ExoPlayer paused itself, past ListenTogetherPlayer. */
    fun onExternalPause()

    /** A transient focus loss ended and ExoPlayer resumed itself. */
    fun onExternalResume()
}

/** Transport and queue commands from every MediaSession client (spec §5 "One place catches every playback command"). */
interface SessionInterceptor {
    fun onPlay()
    fun onPause()
    fun onSeek(positionMs: Long)
    fun onNext()
    fun onPrevious()

    /** "Play next" or "Add to queue" from any track menu: the host queues it, a listener suggests it. */
    fun onAdd(items: List<MediaItem>)

    /** Tapping a song or a playlist to play it. */
    fun onSet(items: List<MediaItem>, startIndex: Int)
}

/** Song descriptor ↔ playable item. */
interface SessionCatalog {
    /** A playable item for [track] through the exact persist, or null when that fails. */
    suspend fun mediaItemFor(track: SharedTrack): MediaItem?

    suspend fun sharedTrackFor(item: MediaItem): SharedTrack?

    /** A song radio seeded from [track], without [track] itself; empty on any failure. */
    suspend fun radioAfter(track: SharedTrack): List<SharedTrack>
}
