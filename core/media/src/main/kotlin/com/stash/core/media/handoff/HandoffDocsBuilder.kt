package com.stash.core.media.handoff

import com.stash.core.data.weblink.WebSongs
import com.stash.core.data.weblink.handoff.HandoffRepeat
import com.stash.core.data.weblink.handoff.HandoffWire
import com.stash.core.data.weblink.handoff.StashNow
import com.stash.core.data.weblink.handoff.StashQueue
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.data.weblink.merge.SyncHashes
import com.stash.core.model.PlayerState
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track

/** The two handoff documents built from one player state. */
data class HandoffDocs(val now: StashNow, val queue: StashQueue)

/**
 * This phone's player state → `stash-now` + `stash-queue` (spec §4.2, sync-v1 §5.1). Pure, so the window, the shuffle's
 * unshuffled order and the dropped songs are unit-tested.
 */
object HandoffDocsBuilder {
    /**
     * A library track as a wire song: the one rule the mirror and a browser playing this phone's queue use too
     * ([WebSongs.songOf]): `phoneOnly` (and counted in `leftOut`) when the web can't play it, SoundCloud included. Null without a
     * title or an artist.
     */
    fun songOf(t: Track): WireSong? = WebSongs.songOf(t)

    /**
     * The rows of [state]'s queue that travel: all of them up to 2,000, else 2,000 starting 100 songs before the current one,
     * moved back so a window near the end still holds 2,000 (sync-v1 §5.1; the web clamps the same way).
     */
    fun window(state: PlayerState): IntRange {
        val n = state.queue.size
        if (n == 0) return IntRange.EMPTY
        val cur = state.currentIndex.coerceIn(0, n - 1)
        val start = if (n > HandoffWire.MAX_QUEUE) (cur - HandoffWire.WINDOW_BEFORE).coerceAtMost(n - HandoffWire.MAX_QUEUE).coerceAtLeast(0) else 0
        return start until minOf(n, start + HandoffWire.MAX_QUEUE)
    }

    /**
     * @param songs the wire songs of [state]'s queue (play order), null where a song can't travel: either one per row of the
     *   whole queue ([songsStart] 0), or exactly the rows of [window] starting at [songsStart] (the publisher reads only those).
     * @param positionMs where the current song is now.
     */
    fun build(state: PlayerState, songs: List<WireSong?>, positionMs: Long, rate: Double, songsStart: Int = 0): HandoffDocs? {
        val n = state.queue.size
        if (n == 0) return null
        val w = window(state)
        val whole = songsStart == 0 && songs.size == n
        if (!whole && !(songsStart == w.first && songs.size == w.count())) return null
        val at = { i: Int -> songs.getOrNull(i - songsStart) }
        val cur = state.currentIndex.coerceIn(0, n - 1)
        val kept = w.filter { at(it) != null }
        if (kept.isEmpty()) return null
        val items = kept.map { at(it)!! }
        val start = w.first
        // The current song, or (when it can't travel) the next one that can.
        val index = kept.indexOfFirst { it >= cur }.takeIf { it >= 0 } ?: (kept.size - 1)
        val slots = state.shuffleTimelineSlots
        val original = if (state.isShuffleEnabled && slots != null && slots.size == n) {
            kept.indices.sortedBy { slots[kept[it]] }
        } else {
            null
        }
        val queue = StashQueue(SyncHashes.queueId(items, start, original), items, index, start, original)
        // A current song that can't travel (no title or artist) hands over the next one, from its start.
        val currentTravels = at(cur) != null
        val now = StashNow(
            playing = state.isPlaying || state.isBuffering,
            positionMs = if (currentTravels) positionMs.coerceAtLeast(0) else 0L,
            rate = rate,
            index = index,
            queueId = queue.id,
            song = items[index],
            shuffle = state.isShuffleEnabled,
            repeat = when (state.repeatMode) {
                RepeatMode.OFF -> HandoffRepeat.OFF
                RepeatMode.ALL -> HandoffRepeat.ALL
                RepeatMode.ONE -> HandoffRepeat.ONE
            },
            from = state.source.displayLabel.takeIf { it.isNotBlank() }?.let { HandoffWire.cleanText(it, 300) },
            leftOut = items.count { it.phoneOnly },
        )
        return HandoffDocs(now, queue)
    }
}
