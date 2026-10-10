package com.stash.core.data.weblink.mirror

import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.merge.Follow
import com.stash.core.data.weblink.merge.LikedSong
import com.stash.core.data.weblink.merge.PlayRec

/** One of this phone's playlists as the mirror sees it. [ro]: synced from Spotify or YouTube Music (it mirrors phone → web only). */
data class LocalPlaylist(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val ro: Boolean,
    val follow: Follow?,
    val songs: Int,
)

/** A mirrored playlist written here: its id, and per song of the list whether this phone holds it. */
data class PutPlaylist(val id: Long, val held: List<Boolean>)

/** A local playlist's content now. */
data class LocalVersion(val name: String, val items: List<WireSong>, val follow: Follow?, val ro: Boolean)

/**
 * The phone's library as the mirror reads and writes it (spec §7; sync-v1 §7). Writes never fan out: a mirrored like is a Stash
 * like (never sent to Spotify or YouTube Music), an unlike only clears a Stash like, and a play from another device goes into
 * History marked with its origin (never scrobbled, never fed to mixes).
 */
interface MirrorLibrary {
    /** Liked songs, newest like first; [LikedSong.external]: liked only through a Spotify / YouTube Music Liked Songs playlist. */
    suspend fun likes(): List<LikedSong<WireSong>>

    /** Likes [on] as Stash likes (at [at]); unlikes [off] (only a Stash like can be cleared). */
    suspend fun setLikes(on: List<WireSong>, off: List<WireSong>, at: Long)

    /** This phone's own plays (no origin) that started at or after [since], newest first, at most [limit]. */
    suspend fun ownPlays(since: Long, limit: Int = 50_000): List<PlayRec<WireSong>>

    /** When the [n]-th newest own play started (1-based), or 0 when there are fewer. */
    suspend fun ownPlayAt(n: Int): Long

    /** Adds plays from another device ([PlayRec.origin] set); a play already here (same words and start) is skipped. */
    suspend fun addPlays(plays: List<PlayRec<WireSong>>)

    /** Drops the plays that came from [origin] and started before [before]. */
    suspend fun dropPlaysFrom(origin: String, before: Long)

    /**
     * "Clear on all your devices" from another device: drops the plays before [before] that the mirror covers (every play from
     * another device, and this phone's own from [floor] on).
     */
    suspend fun dropPlaysBefore(before: Long, floor: Long)

    /** The playlists one can choose to mirror. */
    suspend fun playlists(): List<LocalPlaylist>

    /** [localId]'s content now, or null when it is gone. */
    suspend fun playlist(localId: Long): LocalVersion?

    /**
     * Writes a mirrored playlist: replaces [localId]'s name and songs, or makes it (CUSTOM, `custom_sync_<mirrorId>`) when
     * [localId] is null or gone. Says which of [items] it holds (a song with no row here, or a repeat the library keeps once,
     * isn't). Throws, writing nothing, when the songs couldn't be matched at all.
     */
    suspend fun putPlaylist(localId: Long?, mirrorId: String, name: String, items: List<WireSong>): PutPlaylist

    suspend fun deletePlaylist(localId: Long)
}
