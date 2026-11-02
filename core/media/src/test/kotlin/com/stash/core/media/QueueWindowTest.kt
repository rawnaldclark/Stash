package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

/**
 * [QueueWindow]: a big queue's whole order kept as ids, and the window of it the player holds.
 * The fuzz at the end drives random edits against a plain-list reference (the play order and the
 * timeline as lists of unique ids) and checks after every step that the window is exactly the
 * reference timeline and the order is the reference order.
 */
class QueueWindowTest {

    private fun ids(n: Int) = LongArray(n) { it + 1L }

    @Test
    fun `a tap in the middle keeps 50 songs before it and fills the rest after`() {
        val (w, slot) = QueueWindow.create(ids(72_593), startIndex = 40_000, shuffle = false, random = Random(1))
        assertThat(w.count).isEqualTo(QUEUE_WINDOW_SIZE)
        assertThat(slot).isEqualTo(QUEUE_WINDOW_BEHIND)
        assertThat(w.idAtSlot(slot)).isEqualTo(40_001L)
        assertThat(w.windowIds().toList()).isEqualTo((39_951L..40_450L).toList())
        assertThat(w.songsBefore).isEqualTo(39_950)
        assertThat(w.songsAfter).isEqualTo(72_593 - 40_450)
    }

    @Test
    fun `a tap near the start starts the window at the first song`() {
        val (w, slot) = QueueWindow.create(ids(1_000), startIndex = 3, shuffle = false, random = Random(1))
        assertThat(w.start).isEqualTo(0)
        assertThat(slot).isEqualTo(3)
        assertThat(w.idAtSlot(slot)).isEqualTo(4L)
        assertThat(w.count).isEqualTo(QUEUE_WINDOW_SIZE)
    }

    @Test
    fun `a tap near the end fills the window from before it`() {
        val (w, slot) = QueueWindow.create(ids(1_000), startIndex = 990, shuffle = false, random = Random(1))
        assertThat(w.count).isEqualTo(QUEUE_WINDOW_SIZE)
        assertThat(w.start).isEqualTo(500)
        assertThat(w.idAtSlot(slot)).isEqualTo(991L)
        assertThat(w.songsAfter).isEqualTo(0)
        assertThat(w.forwardGap(repeatAll = false)).isEqualTo(0)
    }

    @Test
    fun `shuffle is over the whole list, the tapped song first`() {
        val (w, slot) = QueueWindow.create(ids(10_000), startIndex = 7_777, shuffle = true, random = Random(42))
        assertThat(slot).isEqualTo(0)
        assertThat(w.idAtSlot(0)).isEqualTo(7_778L)
        assertThat(w.isShuffled).isTrue()
        val playOrder = (0 until w.total).map { w.idAtPosition(it) }
        assertThat(playOrder.toSet()).isEqualTo((1L..10_000L).toSet())
        // Not just the neighbourhood of the tap: the window draws from the whole list.
        val window = w.windowIds()
        assertThat(window.count { it < 5_000 }).isGreaterThan(100)
        assertThat(window.count { it > 9_000 }).isGreaterThan(10)
    }

    @Test
    fun `sliding forward takes the next songs and lets the far back go`() {
        val (w, _) = QueueWindow.create(ids(2_000), startIndex = 0, shuffle = false, random = Random(1))
        w.extendForward(200)
        w.dropFront(200)
        assertThat(w.windowIds().toList()).isEqualTo((201L..700L).toList())
        assertThat(w.songsBefore).isEqualTo(200)
    }

    @Test
    fun `repeat-all goes from the last song of the whole list to its first`() {
        val (w, _) = QueueWindow.create(ids(1_000), startIndex = 999, shuffle = false, random = Random(1))
        assertThat(w.forwardGap(repeatAll = false)).isEqualTo(0)
        assertThat(w.forwardGap(repeatAll = true)).isEqualTo(500)
        assertThat(w.forwardPositions(3).toList()).containsExactly(0, 1, 2).inOrder()
        w.extendForward(3)
        assertThat(w.wrapped).isTrue()
        assertThat(w.windowIds().takeLast(4)).containsExactly(1_000L, 1L, 2L, 3L).inOrder()
    }

    @Test
    fun `repeat-all off cuts the next lap, or the old one once past the end`() {
        fun wrapped(): QueueWindow = QueueWindow.create(ids(1_000), 999, false, Random(1)).first.apply { extendForward(10) }
        val before = wrapped()
        val wrapSlot = 500 // slots 0..499 hold songs 501..1000
        assertThat(before.unwrapRange(currentSlot = 499)).isEqualTo(wrapSlot until 510)
        before.dropBack(10)
        assertThat(before.wrapped).isFalse()
        assertThat(before.windowIds().last()).isEqualTo(1_000L)

        val after = wrapped()
        assertThat(after.unwrapRange(currentSlot = 503)).isEqualTo(0 until wrapSlot)
        after.dropFront(wrapSlot)
        assertThat(after.start).isEqualTo(0)
        assertThat(after.windowIds().toList()).isEqualTo((1L..10L).toList())
    }

    @Test
    fun `a removed row leaves the whole order, a put-back row returns to its place`() {
        val (w, _) = QueueWindow.create(ids(1_000), startIndex = 100, shuffle = false, random = Random(1))
        val removed = w.idAtSlot(60)
        w.removeSlot(60)
        assertThat(w.total).isEqualTo(999)
        assertThat((0 until w.total).map { w.idAtPosition(it) }).doesNotContain(removed)
        w.insertSlot(60, removed)
        assertThat((0 until w.total).map { w.idAtPosition(it) }).isEqualTo((1L..1_000L).toList())
    }

    @Test
    fun `a move reorders the whole order the way Media3 moves the item`() {
        val (w, _) = QueueWindow.create(ids(600), startIndex = 0, shuffle = false, random = Random(1))
        w.moveSlot(1, 4)
        assertThat(w.windowIds().take(6).toList()).containsExactly(1L, 3L, 4L, 5L, 2L, 6L).inOrder()
        assertThat(w.idAtPosition(4)).isEqualTo(2L)
    }

    @Test
    fun `Play next under shuffle stays after its song when shuffle goes off`() {
        val (w, _) = QueueWindow.create(ids(1_000), startIndex = 500, shuffle = true, random = Random(7))
        val playing = w.idAtSlot(0)
        w.insertSlot(1, 5_000L)
        assertThat(w.idAtSlot(1)).isEqualTo(5_000L)
        val off = w.unshuffledFrom(0)
        // Unshuffled: the playing song (id 501), then the Play next song.
        val order = (0 until off.total).map { off.idAtPosition(it) }
        assertThat(order[order.indexOf(playing) + 1]).isEqualTo(5_000L)
        assertThat(off.idAtSlot(0)).isEqualTo(playing)
    }

    @Test
    fun `Add to queue under shuffle goes after everything, in both orders`() {
        val (w, _) = QueueWindow.create(ids(800), startIndex = 0, shuffle = true, random = Random(3))
        w.appendIds(longArrayOf(9_001L, 9_002L), intoWindow = 0)
        assertThat(w.idAtPosition(w.total - 2)).isEqualTo(9_001L)
        assertThat(w.idAtPosition(w.total - 1)).isEqualTo(9_002L)
        val off = w.unshuffledFrom(0)
        assertThat(off.idAtPosition(off.total - 1)).isEqualTo(9_002L)
    }

    @Test
    fun `turning shuffle on and off keeps the playing song and every song`() {
        val (w, slot) = QueueWindow.create(ids(3_000), startIndex = 1_234, shuffle = false, random = Random(5))
        val on = w.shuffledFrom(slot, Random(9))
        assertThat(on.count).isEqualTo(1)
        assertThat(on.idAtSlot(0)).isEqualTo(1_235L)
        assertThat((0 until on.total).map { on.idAtPosition(it) }.toSet()).hasSize(3_000)
        val off = on.unshuffledFrom(0)
        assertThat(off.start).isEqualTo(1_234)
        assertThat((0 until off.total).map { off.idAtPosition(it) }).isEqualTo((1L..3_000L).toList())
    }

    @Test
    fun `deleted songs leave the order inside and outside the window`() {
        val (w, _) = QueueWindow.create(ids(2_000), startIndex = 1_000, shuffle = false, random = Random(1))
        val inside = w.idAtSlot(10)
        assertThat(w.removeIds(setOf(5L, inside, 1_999L))).isTrue()
        assertThat(w.total).isEqualTo(1_997)
        assertThat(w.count).isEqualTo(499)
        assertThat(w.start).isEqualTo(949) // 950 before, one of them (5) gone
        assertThat(w.removeIds(setOf(123_456L))).isFalse()
    }

    @Test
    fun `songs that can't be read back leave the order as the window grows`() {
        val (w, _) = QueueWindow.create(ids(1_000), startIndex = 0, shuffle = false, random = Random(1))
        // The next four are 501..504; 502 and 504 are gone.
        w.admitForward(booleanArrayOf(true, false, true, false))
        assertThat(w.count).isEqualTo(502)
        assertThat(w.windowIds().takeLast(2)).containsExactly(501L, 503L).inOrder()
        assertThat(w.total).isEqualTo(998)
        assertThat(w.idAtPosition(502)).isEqualTo(505L)
    }

    @Test
    fun `the order before the window grows back the same way`() {
        val (w, _) = QueueWindow.create(ids(2_000), startIndex = 1_000, shuffle = false, random = Random(1))
        // Before the window (951..1450): 948, 949, 950.
        w.admitBackward(booleanArrayOf(true, false, true))
        assertThat(w.windowIds().take(3).toList()).containsExactly(948L, 950L, 951L).inOrder()
        assertThat(w.start).isEqualTo(947)
    }

    @Test
    fun `isTimelineOrder accepts only 0, 1, 2, and so on`() {
        assertThat(isTimelineOrder(3, 0) { it + 1 }).isTrue()
        assertThat(isTimelineOrder(3, 1) { (it + 1) % 3 }).isFalse()
        assertThat(isTimelineOrder(3, 0) { if (it == 0) 2 else 1 }).isFalse()
        assertThat(isTimelineOrder(0, -1) { it }).isTrue()
    }

    // ---- Fuzz against a plain-list reference ----

    /** The reference: the play order and the timeline as plain lists of unique ids. */
    private class Ref(var order: MutableList<Long>, var timeline: MutableList<Long>) {
        fun posOf(id: Long) = order.indexOf(id)
        fun nextAfterTimeline(n: Int): List<Long> {
            val end = posOf(timeline.last())
            return (1..n).map { order[(end + it) % order.size] }
        }
        fun beforeTimeline(n: Int): List<Long> {
            val first = posOf(timeline.first())
            return (n downTo 1).map { order[Math.floorMod(first - it, order.size)] }
        }
        fun insertSlot(slot: Int, id: Long) {
            if (slot > 0) order.add(posOf(timeline[slot - 1]) + 1, id) else order.add(posOf(timeline[0]), id)
            timeline.add(slot, id)
        }
    }

    private fun circular(order: List<Long>, from: Long): List<Long> {
        val i = order.indexOf(from)
        return order.subList(i, order.size) + order.subList(0, i)
    }

    private fun check(w: QueueWindow, ref: Ref, step: Int) {
        assertWithMessage("timeline at step $step").that(w.windowIds().toList()).isEqualTo(ref.timeline)
        val playOrder = (0 until w.total).map { w.idAtPosition(it) }
        if (ref.timeline.isNotEmpty()) {
            assertWithMessage("order at step $step").that(circular(playOrder, ref.timeline[0]))
                .isEqualTo(circular(ref.order, ref.timeline[0]))
        }
        if (!w.wrapped) assertWithMessage("linear order at step $step").that(playOrder).isEqualTo(ref.order)
        assertThat(w.count).isAtMost(w.total)
        if (w.isShuffled) {
            val off = w.copy().let { c -> if (c.count > 0) c.unshuffledFrom(0) else null }
            off?.let { o -> assertThat((0 until o.total).map { o.idAtPosition(it) }.sorted()).isEqualTo(playOrder.sorted()) }
        }
    }

    private fun fuzz(seed: Int, shuffle: Boolean) {
        val rnd = Random(seed)
        val total = 700 + rnd.nextInt(300)
        val (w, _) = QueueWindow.create(ids(total), startIndex = rnd.nextInt(total), shuffle = shuffle, random = Random(seed), size = 60, behind = 10)
        val ref = Ref(
            order = (0 until w.total).map { w.idAtPosition(it) }.toMutableList(),
            timeline = w.windowIds().toMutableList(),
        )
        var nextId = 100_000L
        check(w, ref, -1)
        val log = ArrayDeque<String>()
        repeat(1_500) { step ->
            val repeatAll = rnd.nextBoolean()
            val op = rnd.nextInt(10)
            var d = ""
            val before0 = "$w wrapped=${w.wrapped}"
            when (op) {
                0 -> if (w.count > 1) { val s = rnd.nextInt(w.count); d = "remove $s"; w.removeSlot(s); ref.order.remove(ref.timeline.removeAt(s)) }
                1 -> if (w.count > 0) { val s = rnd.nextInt(w.count + 1); val id = nextId++; d = "insert $s"; w.insertSlot(s, id); ref.insertSlot(s, id) }
                2 -> if (w.count > 1) {
                    val from = rnd.nextInt(w.count); val to = rnd.nextInt(w.count)
                    d = "move $from $to"; w.moveSlot(from, to)
                    if (from != to) { val id = ref.timeline.removeAt(from); ref.order.remove(id); ref.insertSlot(to, id) }
                }
                3, 4 -> {
                    val gap = w.forwardGap(repeatAll)
                    if (gap > 0 && w.count > 0) {
                        val n = 1 + rnd.nextInt(minOf(gap, 15))
                        val found = BooleanArray(n) { rnd.nextInt(5) != 0 }
                        val next = ref.nextAfterTimeline(n)
                        d = "fwd ${found.joinToString("") { if (it) "1" else "0" }}"; w.admitForward(found)
                        next.forEachIndexed { i, id -> if (found[i]) ref.timeline.add(id) else ref.order.remove(id) }
                    }
                }
                5 -> {
                    val gap = w.backwardGap(repeatAll)
                    if (gap > 0 && w.count > 0) {
                        val n = 1 + rnd.nextInt(minOf(gap, 15))
                        val found = BooleanArray(n) { rnd.nextInt(5) != 0 }
                        val before = ref.beforeTimeline(n)
                        d = "back ${found.joinToString("") { if (it) "1" else "0" }}"; w.admitBackward(found)
                        ref.timeline.addAll(0, before.filterIndexed { i, _ -> found[i] })
                        before.forEachIndexed { i, id -> if (!found[i]) ref.order.remove(id) }
                    }
                }
                6 -> if (w.count > 20) { val n = rnd.nextInt(10); d = "dropFront $n"; w.dropFront(n); repeat(n) { ref.timeline.removeAt(0) } }
                7 -> if (w.count > 20) { val n = rnd.nextInt(10); w.dropBack(n); repeat(n) { ref.timeline.removeAt(ref.timeline.lastIndex) } }
                8 -> {
                    val victims = (0 until 3).map { ref.order[rnd.nextInt(ref.order.size)] }
                        .filter { it !in ref.timeline || ref.timeline.size > 3 }.toSet()
                    d = "removeIds ${victims.map { ref.order.indexOf(it) to (it in ref.timeline) }}"; w.removeIds(victims)
                    ref.order.removeAll(victims); ref.timeline.removeAll(victims)
                }
                9 -> if (!repeatAll && w.count > 0) {
                    w.unwrapRange(rnd.nextInt(w.count))?.let { r ->
                        val n = r.last - r.first + 1
                        if (r.first == 0) { w.dropFront(n); repeat(n) { ref.timeline.removeAt(0) } }
                        else { w.dropBack(n); repeat(n) { ref.timeline.removeAt(ref.timeline.lastIndex) } }
                    }
                }
            }
            log.addLast("step $step op $op $d repeatAll=$repeatAll before $before0"); if (log.size > 4) log.removeFirst()
            try { check(w, ref, step) } catch (e: AssertionError) { throw AssertionError(log.joinToString(" | ") + " | after $w", e) }
        }
    }

    @Test
    fun `random edits keep the window and the order equal to the reference, unshuffled`() {
        for (seed in 1..12) fuzz(seed, shuffle = false)
    }

    @Test
    fun `random edits keep the window and the order equal to the reference, shuffled`() {
        for (seed in 101..112) fuzz(seed, shuffle = true)
    }
}
