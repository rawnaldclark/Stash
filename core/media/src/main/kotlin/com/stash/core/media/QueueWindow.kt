package com.stash.core.media

import kotlin.random.Random

/**
 * Most songs the player holds at once. A queue up to this size goes to Media3 whole, exactly as
 * before; a bigger one (a whole library, 72,593 songs in the crash reports that started this) is
 * kept here as a list of ids and the player holds a window of it ([QueueWindow]).
 *
 * Why 500: every change to the player's list (each song's look-ahead swap, a quality stamp, Play
 * next) re-sends the whole list to every controller, and Media3 republishes it as the system media
 * session's queue, which SystemUI, Bluetooth and Android Auto read. At 72k that was the crash; at
 * 500 it is about 1/150th of it, and still enough upcoming songs (449) that nobody scrolls off the
 * end of Up next in practice. It is also above the size of almost every playlist, album and mix, so
 * those keep the full-timeline behaviour unchanged.
 */
internal const val QUEUE_WINDOW_SIZE = 500

/** Songs kept in the window before the playing one, for Back. */
internal const val QUEUE_WINDOW_BEHIND = 50

/** The window slides forward once fewer than this many songs are left after the playing one. */
internal const val QUEUE_WINDOW_LOW_WATER = 50

/** The window slides back once fewer than this many songs are kept before the playing one. */
internal const val QUEUE_WINDOW_LOW_WATER_BEHIND = 10

/**
 * The full play order of a queue too big for the player, and the window of it the player holds.
 *
 * The order is the [logical] (unshuffled) list of track ids and, under shuffle, [perm]: play
 * position → logical index. Shuffle is over the whole list, never just the window. The player's
 * timeline is always exactly the window: slot `i` is play position `(start + i) mod total`, in
 * play order (under shuffle the repository keeps Media3's shuffle order the identity, so the walk
 * is the timeline). The window only wraps past the order's end under repeat-all, which is how
 * repeat-all goes back to the first song of the WHOLE list.
 *
 * Every edit that the repository makes to the player's timeline is made here too, slot for slot,
 * so the two never disagree. Pure: no Android, no player; the repository applies the result.
 * Main thread only (no locking).
 */
internal class QueueWindow private constructor(
    private var logical: LongArray,
    private var perm: IntArray?,
    start: Int,
    count: Int,
) {
    /** Play position of timeline slot 0. */
    var start: Int = start
        private set

    /** How many songs the player holds (its timeline size). */
    var count: Int = count
        private set

    /** Songs in the whole queue. */
    val total: Int get() = logical.size

    val isShuffled: Boolean get() = perm != null

    /** True when the window runs past the order's end into its beginning (repeat-all only). */
    val wrapped: Boolean get() = start + count > total

    /** Play position of timeline [slot]. */
    fun position(slot: Int): Int = (start + slot) % total

    fun idAtPosition(position: Int): Long = logical[perm?.get(position) ?: position]

    fun idAtSlot(slot: Int): Long = idAtPosition(position(slot))

    /** Index in the unshuffled list of the song at [slot]. */
    fun logicalIndexAtSlot(slot: Int): Int = position(slot).let { perm?.get(it) ?: it }

    /** The window's ids in timeline order. */
    fun windowIds(): LongArray = LongArray(count) { idAtSlot(it) }

    /** Whether play position [position] is in the window. */
    fun inWindow(position: Int): Boolean = total > 0 && Math.floorMod(position - start, total) < count

    /** Songs of the order before the window (for "12 of 72,593"): none once it wraps. */
    val songsBefore: Int get() = if (wrapped) 0 else start

    /** Songs of the order after the window ("and 72,000 more"). */
    val songsAfter: Int get() = total - count - songsBefore

    /** How many songs could join the window after its end. */
    fun forwardGap(repeatAll: Boolean): Int = when {
        repeatAll -> total - count
        wrapped -> 0
        else -> total - start - count
    }

    /** How many songs could join the window before its start. */
    fun backwardGap(repeatAll: Boolean): Int = when {
        repeatAll -> total - count
        wrapped -> 0
        else -> start
    }

    /** Play positions of the next [n] songs after the window, in play order. */
    fun forwardPositions(n: Int): IntArray = IntArray(n) { (start + count + it) % total }

    /** Play positions of the [n] songs before the window, in play order. */
    fun backwardPositions(n: Int): IntArray = IntArray(n) { Math.floorMod(start - n + it, total) }

    /** The [n] songs after the window joined it (appended to the timeline). */
    fun extendForward(n: Int) {
        require(n in 0..(total - count)) { "extendForward($n) with ${total - count} outside" }
        count += n
    }

    /** The [n] songs before the window joined it (inserted at the timeline's start). */
    fun extendBackward(n: Int) {
        require(n in 0..(total - count)) { "extendBackward($n) with ${total - count} outside" }
        if (total == 0) return
        start = Math.floorMod(start - n, total)
        count += n
    }

    /**
     * The songs at [forwardPositions] (`found.size` of them) were read back: those [found] join
     * the window at its end, the others (deleted, or unplayable now) leave the order.
     */
    fun admitForward(found: BooleanArray) {
        val positions = forwardPositions(found.size)
        removeMissing(positions, found)
        extendForward(found.count { it })
    }

    /** [admitForward] for the songs at [backwardPositions]: those [found] join at the window's start. */
    fun admitBackward(found: BooleanArray) {
        val positions = backwardPositions(found.size)
        removeMissing(positions, found)
        extendBackward(found.count { it })
    }

    /** Takes the positions not [found] out of the order, highest first so the rest keep their place. */
    private fun removeMissing(positions: IntArray, found: BooleanArray) {
        val missing = positions.indices.filter { !found[it] }.map { positions[it] }.sortedDescending()
        for (p in missing) removePosition(p)
    }

    /** The window's first [n] slots left the timeline. */
    fun dropFront(n: Int) {
        require(n in 0..count) { "dropFront($n) of $count" }
        count -= n
        start = if (total == 0) 0 else (start + n) % total
    }

    /** The window's last [n] slots left the timeline. */
    fun dropBack(n: Int) {
        require(n in 0..count) { "dropBack($n) of $count" }
        count -= n
    }

    /**
     * The slots to take out of the timeline so the window no longer wraps, for repeat-all going
     * off or an append ([appendIds]): the next lap's songs when [currentSlot] is still before the
     * order's end, else the old lap's ones before it. Null when it doesn't wrap. Apply with
     * [dropFront] (range starting at 0) or [dropBack].
     */
    fun unwrapRange(currentSlot: Int): IntRange? {
        if (!wrapped) return null
        val wrapSlot = total - start
        return if (currentSlot < wrapSlot) wrapSlot until count else 0 until wrapSlot
    }

    /** Takes the song at timeline [slot] out of the order (a swipe in the queue sheet). */
    fun removeSlot(slot: Int) {
        require(slot in 0 until count) { "removeSlot($slot) of $count" }
        removePositionInternal(position(slot))
        count -= 1
        normalizeStart()
    }

    /** Takes play position [position] out of the order; in the window or not. */
    fun removePosition(position: Int) {
        require(position in 0 until total) { "removePosition($position) of $total" }
        val inside = inWindow(position)
        removePositionInternal(position)
        if (inside) count -= 1
        normalizeStart()
    }

    private fun removePositionInternal(p: Int) {
        val l = perm?.get(p) ?: p
        logical = LongArray(logical.size - 1) { i -> logical[if (i < l) i else i + 1] }
        perm = perm?.let { pm ->
            IntArray(pm.size - 1) { i ->
                val v = pm[if (i < p) i else i + 1]
                if (v > l) v - 1 else v
            }
        }
        if (p < start) start -= 1
    }

    private fun normalizeStart() {
        if (total == 0 || start >= total) start = 0
    }

    /**
     * Puts [id] in at timeline [slot] (0..[count]): Play next, the queue sheet's Undo, a move.
     * Under shuffle it takes the unshuffled place right after the song before it in play order, so
     * turning shuffle off later keeps it there; [logicalAtEnd] puts it at the unshuffled end instead.
     */
    fun insertSlot(slot: Int, id: Long, logicalAtEnd: Boolean = false) {
        require(slot in 0..count) { "insertSlot($slot) of $count" }
        val unwrapped = start + slot
        // Past the order's end the slot is in the wrapped part, at the order's beginning: positions
        // there are before start, so start moves up one. Exactly at the end it is an append.
        val p = if (unwrapped <= total) unwrapped else unwrapped - total
        insertPositionInternal(p, id, logicalAtEnd)
        if (unwrapped > total) start += 1
        count += 1
    }

    private fun insertPositionInternal(p: Int, id: Long, logicalAtEnd: Boolean) {
        val pm = perm
        val n = logical.size
        val l = when {
            pm == null -> p
            logicalAtEnd || n == 0 -> n
            else -> pm[if (p > 0) p - 1 else n - 1] + 1
        }
        logical = LongArray(n + 1) { i ->
            when {
                i < l -> logical[i]
                i == l -> id
                else -> logical[i - 1]
            }
        }
        perm = pm?.let {
            fun bump(v: Int) = if (v >= l) v + 1 else v
            IntArray(it.size + 1) { i ->
                when {
                    i < p -> bump(it[i])
                    i == p -> l
                    else -> bump(it[i - 1])
                }
            }
        }
    }

    /** Moves the song at [from] to [to] (timeline slots, Media3's moveMediaItem semantics). */
    fun moveSlot(from: Int, to: Int) {
        require(from in 0 until count && to in 0 until count) { "moveSlot($from, $to) of $count" }
        if (from == to) return
        val id = idAtSlot(from)
        removeSlot(from)
        insertSlot(to, id)
    }

    /**
     * Appends [ids] to the order's end ("Add to queue": after everything queued), the first
     * [intoWindow] of them into the window too. Only an unwrapped window that reaches the order's
     * end can take them ([endsAtOrderEnd]); otherwise [intoWindow] must be 0.
     */
    fun appendIds(ids: LongArray, intoWindow: Int) {
        require(intoWindow in 0..ids.size) { "appendIds intoWindow=$intoWindow of ${ids.size}" }
        require(intoWindow == 0 || endsAtOrderEnd) { "appendIds into a window that doesn't end the order" }
        require(!wrapped) { "appendIds into a wrapped window: unwrap it first" }
        val n = logical.size
        logical = logical.copyOf(n + ids.size).also { ids.copyInto(it, n) }
        perm = perm?.let { pm -> IntArray(pm.size + ids.size) { i -> if (i < pm.size) pm[i] else n + (i - pm.size) } }
        count += intoWindow
    }

    /** True when the window is unwrapped and its last slot is the order's last song. */
    val endsAtOrderEnd: Boolean get() = !wrapped && start + count == total

    /**
     * Takes every entry whose id is in [ids] out of the order (deleted songs), in the window or
     * not, in one pass. False when none was there.
     */
    fun removeIds(ids: Set<Long>): Boolean {
        val n = total
        if (n == 0 || ids.isEmpty()) return false
        val keepPos = BooleanArray(n) { idAtPosition(it) !in ids }
        if (keepPos.all { it }) return false
        var keptBeforeStart = 0
        for (p in 0 until start) if (keepPos[p]) keptBeforeStart++
        var keptInWindow = 0
        for (s in 0 until count) if (keepPos[position(s)]) keptInWindow++
        val pm = perm
        val keepLogical = if (pm == null) keepPos else BooleanArray(n).also { k -> for (p in 0 until n) k[pm[p]] = keepPos[p] }
        val newIndex = IntArray(n)
        var next = 0
        for (l in 0 until n) if (keepLogical[l]) newIndex[l] = next++
        val newLogical = LongArray(next)
        for (l in 0 until n) if (keepLogical[l]) newLogical[newIndex[l]] = logical[l]
        perm = pm?.let {
            val out = IntArray(next)
            var j = 0
            for (p in 0 until n) if (keepPos[p]) out[j++] = newIndex[it[p]]
            out
        }
        logical = newLogical
        start = keptBeforeStart
        count = keptInWindow
        normalizeStart()
        return true
    }

    /**
     * Shuffle turned on: the whole list in a new random order with the song at [currentSlot]
     * first, and a window of just that song (the repository fills it from there).
     */
    fun shuffledFrom(currentSlot: Int, random: Random): QueueWindow {
        val l = logicalIndexAtSlot(currentSlot)
        val rest = IntArray(total - 1) { if (it < l) it else it + 1 }
        rest.shuffle(random)
        val newPerm = IntArray(total) { if (it == 0) l else rest[it - 1] }
        return QueueWindow(logical.copyOf(), newPerm, start = 0, count = 1)
    }

    /** Shuffle turned off: the unshuffled list, and a window of just the song at [currentSlot]. */
    fun unshuffledFrom(currentSlot: Int): QueueWindow {
        val l = logicalIndexAtSlot(currentSlot)
        return QueueWindow(logical.copyOf(), null, start = l, count = 1)
    }

    /** An independent copy, for a structural change built off the main path and swapped in. */
    fun copy(): QueueWindow = QueueWindow(logical.copyOf(), perm?.copyOf(), start, count)

    override fun toString(): String = "QueueWindow(total=$total, start=$start, count=$count, shuffled=$isShuffled)"

    companion object {
        /** A window holding all of [ids], unshuffled: a queue the player holds whole, about to grow past it. */
        fun whole(ids: LongArray): QueueWindow = QueueWindow(ids.copyOf(), null, start = 0, count = ids.size)

        /**
         * A window over [ids] (the playable queue, in its unshuffled order) for a play starting at
         * [startIndex]. With [shuffle] the order is the whole list shuffled with that song first;
         * otherwise the window keeps up to [behind] songs before it and fills the rest after it
         * (or before it, near the end). Returns the window and the slot of the starting song.
         */
        fun create(
            ids: LongArray,
            startIndex: Int,
            shuffle: Boolean,
            random: Random,
            size: Int = QUEUE_WINDOW_SIZE,
            behind: Int = QUEUE_WINDOW_BEHIND,
        ): Pair<QueueWindow, Int> {
            require(ids.isNotEmpty() && startIndex in ids.indices) { "create: start $startIndex of ${ids.size}" }
            val total = ids.size
            val full = QueueWindow(ids.copyOf(), null, start = startIndex, count = 1)
            val base = if (shuffle) full.shuffledFrom(0, random) else full
            val cap = size.coerceAtMost(total)
            if (shuffle) {
                base.extendForward(cap - 1)
                return base to 0
            }
            val before = minOf(startIndex, behind)
            base.extendBackward(before)
            base.extendForward(minOf(cap - base.count, base.forwardGap(repeatAll = false)))
            // Near the end: fill the window from before instead.
            val more = minOf(cap - base.count, base.backwardGap(repeatAll = false))
            base.extendBackward(more)
            return base to (before + more)
        }
    }
}

/**
 * Whether a shuffle walk over [count] timeline items, starting at [first] and going on by [next],
 * is the timeline's own order (0, 1, 2, ...): what the service keeps for a big queue
 * (StashPlaybackService.COMMAND_KEEP_TIMELINE_ORDER).
 */
internal fun isTimelineOrder(count: Int, first: Int, next: (Int) -> Int): Boolean {
    if (count == 0) return true
    if (first != 0) return false
    for (i in 0 until count - 1) if (next(i) != i + 1) return false
    return true
}
