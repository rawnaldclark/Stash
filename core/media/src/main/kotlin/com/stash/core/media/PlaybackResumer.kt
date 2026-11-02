package com.stash.core.media

import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the playback queue to hand back to Media3's
 * [androidx.media3.session.MediaLibraryService.MediaLibrarySession.Callback.onPlaybackResumption]
 * contract when a controller (Bluetooth / Android Auto) asks to resume
 * playback after the process was killed.
 *
 * The decision logic lives here, free of Android `MediaItem` types, so it
 * can be unit-tested on the JVM — the host service is responsible only for
 * turning a [ResumePlan] into `MediaItem`s. This mirrors the
 * [com.stash.core.media.streaming.PrefetchOrchestrator] extraction pattern.
 *
 * Returning `null` means "no restorable queue was persisted" and the caller
 * should fall back to its single-track behaviour (last-played / most
 * recently added).
 */
@Singleton
class PlaybackResumer @Inject constructor(
    private val playbackStateStore: PlaybackStateStore,
    private val trackDao: TrackDao,
) {

    /**
     * The fully-resolved queue to resume into.
     *
     * @property tracks      Ordered queue tracks, ready to be mapped to `MediaItem`s.
     * @property startIndex  Index within [tracks] of the track to start on.
     * @property positionMs  Position within the start track to resume from (>= 0).
     * @property isShuffled  Whether shuffle mode was active when last saved.
     */
    data class ResumePlan(
        val tracks: List<TrackEntity>,
        val startIndex: Int,
        val positionMs: Long,
        val isShuffled: Boolean,
        val repeatMode: com.stash.core.model.RepeatMode,
        val source: com.stash.core.model.PlaybackSource,
    )

    suspend fun buildResumePlan(): ResumePlan? {
        // A queue saved whole before big queues were windowed (72,593 ids in one report) comes back
        // as the window around its song: every row of it was read and built into the player at once.
        val saved = playbackStateStore.getLastPlaybackState()?.boundedToWindow() ?: return null
        if (saved.queueTrackIds.isEmpty()) return null

        // `IN` doesn't preserve order, so re-sort into the saved queue order.
        val byId = trackDao.getByIds(saved.queueTrackIds).associateBy { it.id }
        // A row a duplicate merge took away (schema 76) plays as the row it went into. Only the
        // missing ids are looked up, in one batched read; a merged song already in the queue, or
        // reached twice through merges, is not added again. Tracks gone for any other reason are
        // dropped.
        val missing = saved.queueTrackIds.filterNot { it in byId }
        val mergedInto = if (missing.isEmpty()) emptyMap() else trackDao.mergedIntoAll(missing)
        val ordered = ArrayList<TrackEntity>(saved.queueTrackIds.size)
        val savedIndexOf = ArrayList<Int>(saved.queueTrackIds.size) // each entry's index in the saved queue
        val mergedAdded = HashSet<Long>()
        saved.queueTrackIds.forEachIndexed { index, id ->
            val row = byId[id]
                ?: mergedInto[id]?.takeIf { it.id !in byId && mergedAdded.add(it.id) }
                ?: return@forEachIndexed
            ordered += row
            savedIndexOf += index
        }
        if (ordered.isEmpty()) return null

        // The song that was playing, under its current row id.
        val currentId = if (saved.trackId in byId) saved.trackId else mergedInto[saved.trackId]?.id ?: saved.trackId

        // Prefer the entry that sat at the saved index when it still holds that song: with the
        // song queued twice, the first match resumed at the EARLIER copy and replayed everything
        // between. Else locate it by id (robust to dropped rows shifting positions); else the
        // saved index clamped into range.
        val startIndex = savedIndexOf.indexOf(saved.queueIndex).takeIf { it >= 0 && ordered[it].id == currentId }
            ?: ordered.indexOfFirst { it.id == currentId }
                .let { if (it >= 0) it else saved.queueIndex.coerceIn(0, ordered.size - 1) }

        return ResumePlan(
            tracks = ordered,
            startIndex = startIndex,
            // The saved position belongs to the saved track. When that track is
            // gone the start is whatever now sits at its index (under shuffle a
            // walk index, so possibly anything): starting THAT song 3:40 in, or
            // auto-advancing past it when it is shorter, was the old result.
            positionMs = if (ordered[startIndex].id == currentId) saved.positionMs.coerceAtLeast(0) else 0L,
            isShuffled = saved.isShuffled,
            repeatMode = saved.repeatMode,
            source = saved.source,
        )
    }
}

/**
 * This saved queue cut to at most [size] ids around its song (up to [behind] before it), with the
 * saved index moved to match; unchanged when it is no bigger. The song is found as
 * [PlaybackResumer.buildResumePlan] finds it: at the saved index when it holds it, else by id, else
 * the saved index itself.
 */
internal fun SavedPlaybackState.boundedToWindow(
    size: Int = QUEUE_WINDOW_SIZE,
    behind: Int = QUEUE_WINDOW_BEHIND,
): SavedPlaybackState {
    val ids = queueTrackIds
    if (ids.size <= size) return this
    val center = queueIndex.takeIf { it in ids.indices && ids[it] == trackId }
        ?: ids.indexOf(trackId).takeIf { it >= 0 }
        ?: queueIndex.coerceIn(0, ids.size - 1)
    val from = (center - behind).coerceIn(0, ids.size - size)
    return copy(queueTrackIds = ids.subList(from, from + size).toList(), queueIndex = center - from)
}
