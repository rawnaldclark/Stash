package com.stash.core.media.handoff

import android.util.Log
import com.stash.core.data.mapper.toDomain
import com.stash.core.data.weblink.handoff.HandoffChannel
import com.stash.core.data.weblink.handoff.HandoffRepeat
import com.stash.core.data.weblink.handoff.HandoffSongMatcher
import com.stash.core.data.weblink.handoff.StashNow
import com.stash.core.data.weblink.handoff.StashQueue
import com.stash.core.data.weblink.handoff.WireSong
import com.stash.core.media.PlayerRepository
import com.stash.core.model.PlaybackSource
import com.stash.core.model.RepeatMode
import com.stash.core.model.Track
import com.stash.core.model.weblink.SongKey
import javax.inject.Inject

/**
 * The other device's queue laid out for this player (spec §8.3), pure. [songs] is the timeline: with shuffle, the unshuffled
 * order (`original`), so turning shuffle off here gives the other device's order back; [playOrder] is the play order as
 * timeline indexes (null without shuffle). [startSlot] is the timeline index of the song to start.
 */
data class HandoffLayout(
    val songs: List<WireSong?>,
    val startSlot: Int,
    val playOrder: List<Int>?,
) {
    /** The timeline slots in the order they play. */
    val playSequence: List<Int> get() = playOrder ?: songs.indices.toList()

    companion object {
        /**
         * From the `now` and (when it was found) the queue it names. A queue with another id (published later or earlier than
         * the `now`) is still used when the current song is in it; with no usable queue the card's song plays alone.
         */
        fun of(now: StashNow, queue: StashQueue?): HandoffLayout? {
            val song = now.song ?: return null
            val items: List<WireSong?>
            val index: Int
            var original: List<Int>? = null
            when {
                queue != null && queue.id == now.queueId && now.index in queue.items.indices -> {
                    items = queue.items
                    index = now.index
                    original = queue.original
                }
                queue != null && nearest(queue.items, song, now.index) >= 0 -> {
                    items = queue.items
                    index = nearest(queue.items, song, now.index)
                    original = queue.original
                }
                else -> {
                    items = listOf(song)
                    index = 0
                }
            }
            if (!now.shuffle || items.size == 1) return HandoffLayout(items, index, null)
            // Shuffled: the timeline is the unshuffled order; slot t holds items[original[t]]. Without `original` the play
            // order is all there is, so it becomes the timeline too (shuffle off then keeps it).
            val orig = original ?: return HandoffLayout(items, index, items.indices.toList())
            val slotOf = IntArray(items.size).also { a -> orig.forEachIndexed { t, i -> a[i] = t } }
            return HandoffLayout(orig.map { items[it] }, slotOf[index], items.indices.map { slotOf[it] })
        }

        /** The occurrence of [song] in [items] closest to [hint], or −1. */
        private fun nearest(items: List<WireSong?>, song: WireSong, hint: Int): Int =
            items.indices.filter { i -> items[i]?.let { SongKey.sameSong(it, song) } == true }.minByOrNull { kotlin.math.abs(it - hint) } ?: -1
    }
}

/**
 * The rest of a restore once the first songs play (pure): what goes before and after them in the timeline, and the play order
 * as indexes into `before + first + after`. [firstSlots] are the timeline slots already playing (contiguous, in order);
 * [matched] holds this phone's track for every other slot it found.
 */
internal fun finishLayout(layout: HandoffLayout, firstSlots: List<Int>, matched: Map<Int, Track>): HandoffQueueRest {
    val lo = firstSlots.first()
    val hi = firstSlots.last()
    val beforeSlots = (0 until lo).filter { it in matched }
    val afterSlots = (hi + 1 until layout.songs.size).filter { it in matched }
    val timeline = beforeSlots + firstSlots + afterSlots
    val position = HashMap<Int, Int>(timeline.size).also { m -> timeline.forEachIndexed { i, s -> m[s] = i } }
    val order = layout.playOrder?.mapNotNull { position[it] }
    return HandoffQueueRest(beforeSlots.map { matched.getValue(it) }, afterSlots.map { matched.getValue(it) }, order)
}

/** How a "Continue" tap went. */
sealed interface RestoreResult {
    data object Started : RestoreResult

    /** Nothing in that queue could be played here. */
    data object NothingPlayable : RestoreResult

    data object Failed : RestoreResult
}

/**
 * Restores another device's queue here (spec §8.3): reads its queue slot, matches the current song first (ISRC, then the
 * library's own row, then a stream-only row) and starts it at the extrapolated position with that device's repeat, then
 * matches the rest in the background and fills the queue around it, shuffle order included. Songs this phone can't play are
 * left out and counted for the note.
 */
class HandoffRestorer @Inject constructor(
    private val channel: HandoffChannel,
    private val matcher: HandoffSongMatcher,
    private val player: PlayerRepository,
) {
    /** [onNote] gets "2 songs … were left out." once the queue is filled, when any were. */
    suspend fun restore(offer: HandoffOffer, positionMs: Long, onNote: (String) -> Unit): RestoreResult {
        val queue = channel.readQueue(offer.device)
        val layout = HandoffLayout.of(offer.now, queue) ?: return RestoreResult.Failed
        val matched = HashMap<Int, Track>()
        val missing = HashSet<Int>()
        suspend fun match(slot: Int): Track? {
            matched[slot]?.let { return it }
            if (slot in missing) return null
            return layout.songs[slot]?.let { matcher.match(it) }?.toDomain()?.also { matched[slot] = it } ?: run {
                missing += slot
                null
            }
        }

        // The song to start: the current one, or the next in play order this phone can play (then from its start).
        val sequence = layout.playSequence
        val from = sequence.indexOf(layout.startSlot).coerceAtLeast(0)
        var startSlot = -1
        for (k in from until sequence.size) {
            if (match(sequence[k]) != null) {
                startSlot = sequence[k]
                break
            }
        }
        if (startSlot < 0) return RestoreResult.NothingPlayable
        val position = if (startSlot == layout.startSlot) positionMs else 0L
        // Without shuffle the next few play straight away too; shuffled, the next song is elsewhere in the timeline.
        val firstSlots = mutableListOf(startSlot)
        if (layout.playOrder == null) {
            var s = startSlot + 1
            while (s < layout.songs.size && firstSlots.size < 1 + FIRST_AHEAD) {
                if (match(s) == null) break // keep the first block contiguous; the rest fills it in
                firstSlots += s
                s++
            }
        }
        var unmatched = 0
        val plan = HandoffQueuePlan(
            first = firstSlots.map { matched.getValue(it) },
            positionMs = position,
            source = PlaybackSource.Handoff(offer.now.from?.takeIf { it.isNotBlank() } ?: offer.deviceName),
            repeat = when (offer.now.repeat) {
                HandoffRepeat.OFF -> RepeatMode.OFF
                HandoffRepeat.ALL -> RepeatMode.ALL
                HandoffRepeat.ONE -> RepeatMode.ONE
            },
            rest = {
                // The rest in batches (one transaction each), not one write per song.
                val todo = layout.songs.indices.filter { it !in firstSlots && it !in matched && it !in missing }
                val rows = matcher.matchAll(todo.map { layout.songs[it] })
                todo.forEachIndexed { i, slot -> rows[i]?.toDomain()?.let { matched[slot] = it } ?: missing.add(slot) }
                unmatched = layout.songs.indices.count { it !in firstSlots && it !in matched }
                finishLayout(layout, firstSlots, matched)
            },
            onFilled = { dropped ->
                val left = unmatched + dropped
                Log.i(TAG, "handoff restored: ${layout.songs.size - left} of ${layout.songs.size} songs")
                if (left > 0) onNote(leftOutNote(left))
            },
        )
        return if (player.restoreHandoff(plan)) RestoreResult.Started else RestoreResult.Failed
    }

    companion object {
        private const val TAG = "WebLinkHandoff"

        /** Besides the current song, how many after it play before the rest is matched (spec §8.3). */
        const val FIRST_AHEAD = 5

        fun leftOutNote(n: Int) = if (n == 1) "1 song that isn't playable here was left out." else "$n songs that aren't playable here were left out."
    }
}
