package com.stash.core.media.service

import com.stash.core.data.db.entity.TrackEntity

/**
 * Queue expansion for Android Auto browse taps (issues #154 / #173).
 *
 * Browse children used to carry a bare `track.id` mediaId, which loses the
 * parent-playlist context — so tapping a song in an Auto playlist queued
 * exactly one item and next/previous had nowhere to go (the synthetic
 * `SHUFFLE_PLAY_` entry worked precisely because it DOES carry the playlist
 * id). Browse children now carry `AUTOQ_<parentBrowseId>_<trackId>` so the
 * `onSetMediaItems` handler can rebuild the whole parent as the queue,
 * starting at the tapped track.
 *
 * Pure JVM logic, extracted from [StashPlaybackService] for unit testing —
 * same pattern as [com.stash.core.media.PlaybackResumer].
 */
object AutoBrowseQueue {

    /** MediaId prefix marking a browse child that carries its parent id. */
    const val PREFIX = "AUTOQ_"

    /** Parsed form of a browse-child mediaId. */
    data class Parsed(val parentId: String, val trackId: Long)

    /**
     * The expanded queue for a browse tap.
     *
     * @property tracks     Playable tracks of the parent, in browse order.
     * @property startIndex Index of the tapped track within [tracks].
     */
    data class QueuePlan(val tracks: List<TrackEntity>, val startIndex: Int)

    /** MediaId for a track listed under [parentId] in the Auto browse tree. */
    fun childMediaId(parentId: String, trackId: Long): String =
        "$PREFIX${parentId}_$trackId"

    /**
     * Inverse of [childMediaId]. The parent id may itself contain
     * underscores (`PLAYLIST_42`, `RECENTLY_ADDED`), so the track id is
     * taken from the LAST underscore segment. Returns null for anything
     * that isn't a well-formed browse-child id.
     */
    fun parse(mediaId: String): Parsed? {
        if (!mediaId.startsWith(PREFIX)) return null
        val payload = mediaId.removePrefix(PREFIX)
        val parentId = payload.substringBeforeLast('_', missingDelimiterValue = "")
        val trackId = payload.substringAfterLast('_').toLongOrNull() ?: return null
        if (parentId.isEmpty()) return null
        return Parsed(parentId, trackId)
    }

    /**
     * Filters [tracks] down to what the browse list actually showed
     * (downloaded or streamable — keep this in lockstep with
     * `onGetChildren`) and locates the tapped track in the filtered list.
     * Falls back to index 0 if the tapped track vanished between browse
     * and tap (deleted / re-synced).
     */
    fun queuePlan(tracks: List<TrackEntity>, tappedTrackId: Long): QueuePlan {
        // isPlayableInAuto, NOT the bare is_streamable flag: onGetChildren listed
        // never-checked synced rows, so the tap must queue them too — the bare
        // filter dropped them, shifted the start index and played the wrong song.
        val playable = tracks.filter { it.isPlayableInAuto() }
        val startIndex = playable.indexOfFirst { it.id == tappedTrackId }
            .coerceAtLeast(0)
        return QueuePlan(playable, startIndex)
    }
}
