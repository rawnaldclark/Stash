package com.stash.core.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.Collections
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Drag to reorder, the queue's way (QueueBottomSheet, and Up next in the Session sheet): the row moves by its handle from
// the first touch, no long press; it rides above the others with a shadow while they slide out of its way; it swaps with a
// neighbour once it's past half of it; holding it near the top or bottom edge scrolls the page under it; and the whole drag
// is ONE move, from where the row started to where it was dropped, after which it eases into its slot. The queue does this
// for a LazyColumn; this is the same for the rows of a plain Column in a scrolling page (Settings), whose rows may differ in
// height.

/** Where holding a dragged row near an edge starts scrolling the list under it (the queue's edge zone). */
val DragAutoScrollEdge: Dp = 64.dp

/** The fastest that edge scroll goes, per frame, with the row all the way into the edge zone. */
val DragAutoScrollMaxStep: Dp = 6.dp

/**
 * The tick as a dragged row is picked up, the same in every reorderable list (Home layout, the queue, Up next): one light
 * tick (SEGMENT_TICK), on pick-up only, not on each swap. LongPress is a heavier buzz; SegmentFrequentTick is made for a
 * run of ticks while scrubbing, too faint for a single one.
 */
val DragPickUpHaptic: HapticFeedbackType = HapticFeedbackType.SegmentTick

/**
 * Auto-scroll velocity for a drag near the viewport edges (issue #319):
 * zero while the dragged row rides mid-viewport, ramping linearly toward
 * ±[maxStepPx] per frame as the row's edge sinks into the top or bottom
 * [edgePx] zone. Negative scrolls toward the start of the list.
 */
fun autoScrollStep(
    visualTop: Float,
    visualBottom: Float,
    viewportPx: Float,
    edgePx: Float,
    maxStepPx: Float,
): Float {
    if (edgePx <= 0f || viewportPx <= 0f) return 0f
    return when {
        visualTop < edgePx -> {
            val depth = ((edgePx - visualTop) / edgePx).coerceIn(0f, 1f)
            -maxStepPx * depth
        }
        visualBottom > viewportPx - edgePx -> {
            val depth = ((visualBottom - (viewportPx - edgePx)) / edgePx).coerceIn(0f, 1f)
            maxStepPx * depth
        }
        else -> 0f
    }
}

/**
 * Whether the held row at [index], [offsetY] px from its slot, swaps now: +1 with the row below, -1 with the row above, 0
 * not. It swaps once it's past half of that neighbour ([heights] are the rows' heights in the order shown, gaps included),
 * the queue's half-a-row rule for rows of any height. After a swap the row's offset drops by the neighbour's height, so it
 * stays under the finger; dragging back over the same half swaps back, with no flip-flop in between. 0 while the held row
 * hasn't been measured.
 */
fun heldRowSwap(index: Int, offsetY: Float, heights: List<Int>): Int {
    if (index !in heights.indices || heights[index] <= 0) return 0
    val below = heights.getOrNull(index + 1)
    if (below != null && offsetY > below / 2f) return 1
    val above = heights.getOrNull(index - 1)
    if (above != null && offsetY < -above / 2f) return -1
    return 0
}

/**
 * The held row's offset as drawn: with the finger, but never out of the Column ([heights] in the order shown). The card
 * around a Settings list clips it, so past the first or last row it waits at that end until the finger comes back.
 */
fun heldRowOffsetInColumn(index: Int, offsetY: Float, heights: List<Int>): Float {
    if (index !in heights.indices) return offsetY
    var above = 0
    for (i in 0 until index) above += heights[i]
    var below = 0
    for (i in index + 1 until heights.size) below += heights[i]
    return offsetY.coerceIn(-above.toFloat(), below.toFloat())
}

/**
 * An edge-scroll [step] cut to what still shows more of the list, which runs [listTop]..[listBottom] on screen in a viewport
 * [viewTop]..[viewBottom]: once the list's end is in view the page stays put, though there's more page past it (the queue's
 * list is its whole viewport, so it stops there by itself).
 */
fun edgeScrollWithinList(step: Float, listTop: Float, listBottom: Float, viewTop: Float, viewBottom: Float): Float = when {
    step > 0f -> min(step, listBottom - viewBottom).coerceAtLeast(0f)
    step < 0f -> max(step, listTop - viewTop).coerceAtMost(0f)
    else -> 0f
}

/**
 * The rows of a reorderable Column and the drag, kept while the screen is up. [rows] is what to show: the caller's order
 * ([rememberReorderableColumnState]'s keys) plus a drag's moves until the drop, which sends one `onMove(key, to)` and keeps
 * showing the dropped order until the caller's order comes back with it. Give the Column [reorderableColumn], each row
 * (composed in `key(row) { }`) [reorderableRow], and its handle [reorderHandle].
 */
@Stable
class ReorderableColumnState<K : Any> internal constructor(
    keys: List<K>,
    private val scope: CoroutineScope,
    private val scrollState: ScrollableState?,
    private val edgePx: Float,
    private val maxStepPx: Float,
) {
    /** The caller's order as of the last composition. */
    internal var keys: List<K> = keys
    internal var onMove: (key: K, to: Int) -> Unit = { _, _ -> }

    /** How much of the screen's bottom the floating chrome covers: the edge scroll's bottom edge is above it. */
    internal var bottomInsetPx: Float = 0f

    /** Ticks as a row is picked up ([DragPickUpHaptic]). */
    internal var haptics: HapticFeedback? = null

    /** The rows as shown. Filled at once, so the screen never opens on an empty list. */
    val rows = mutableStateListOf<K>().apply { addAll(keys) }

    private var draggedIdx by mutableIntStateOf(-1)

    /** How far the held row is from its slot, following the finger. */
    private var offsetY by mutableFloatStateOf(0f)

    /** The other rows' slides into their new slots after a swap: how far each still is from it. */
    private val slides = mutableStateMapOf<K, Float>()
    private val slideJobs = HashMap<K, Job>()
    private val heights = HashMap<K, Int>()
    private var startIdx = -1
    /** The rows as they were when the drag started: what the finger moved the row through. */
    private var startRows: List<K>? = null
    private var autoScroll: Job? = null
    private var settle: Job? = null
    internal var columnCoords: LayoutCoordinates? = null

    /** Whether a row is held (or still easing into its slot after the drop). */
    val isDragging: Boolean get() = draggedIdx >= 0

    /** Whether [key]'s row is the one held. */
    fun isDragging(key: K): Boolean = rows.getOrNull(draggedIdx) == key

    /** How far [key]'s row is drawn from its slot right now. Read at placement, so the drag never recomposes the rows. */
    internal fun offsetOf(key: K): Float = if (isDragging(key)) heldOffset() else slides[key] ?: 0f

    private fun shownHeights(): List<Int> = rows.map { heights[it] ?: 0 }

    /** The held row's offset as drawn, inside the Column ([heldRowOffsetInColumn]). */
    private fun heldOffset(): Float = heldRowOffsetInColumn(draggedIdx, offsetY, shownHeights())

    internal fun measured(key: K, height: Int) {
        heights[key] = height
    }

    /** Shows the caller's order, unless a row is held: the rows are the finger's until the drop. */
    internal fun sync() {
        if (draggedIdx >= 0) return
        if (rows.toList() == keys) return
        rows.clear()
        rows.addAll(keys)
    }

    fun startDrag(key: K) {
        settle?.cancel()
        val i = rows.indexOf(key)
        if (i < 0) return
        draggedIdx = i
        startIdx = i
        startRows = rows.toList()
        haptics?.performHapticFeedback(DragPickUpHaptic)
        // Still sliding from an earlier swap: pick it up where it's drawn, not where its slot is.
        slideJobs.remove(key)?.cancel()
        offsetY = slides.remove(key) ?: 0f
        // Holding the row near the top or bottom scrolls the page under it, so one drag can go anywhere. Every frame of the
        // drag; the step is 0 while the row rides mid-screen.
        autoScroll?.cancel()
        val scroll = scrollState ?: return
        autoScroll = scope.launch {
            while (isActive) {
                withFrameNanos { }
                val step = edgeStep()
                if (step != 0f) {
                    offsetY += scroll.scrollBy(step) // what actually moved: nothing at the page's ends
                    normalize()
                }
            }
        }
    }

    fun dragBy(dy: Float) {
        if (draggedIdx < 0) return
        offsetY += dy
        normalize()
    }

    /** The whole drag is one move, from where the row started to where it was dropped; the row then eases into its slot. */
    fun endDrag() {
        autoScroll?.cancel()
        autoScroll = null
        if (draggedIdx < 0) return
        val from = startIdx
        val to = draggedIdx
        // Only a drag over the caller's order as it is now: if that changed mid-drag the indices are stale, so the move is
        // dropped (and the rows put back below). A drag straight after a drop counts once the dropped order is back.
        val moved = startRows?.takeIf { it == keys && from >= 0 && from != to }
        moved?.let { onMove(it[from], to) }
        val keysAtDrop = keys
        startIdx = -1
        startRows = null
        offsetY = heldOffset()
        settle = scope.launch {
            animate(offsetY, 0f, animationSpec = tween(150)) { v, _ -> offsetY = v }
            draggedIdx = -1
            // No move went out, or the caller's order arrived while the row settled: show it. Otherwise the dropped order
            // stays up until the caller's comes back with it, so the row never snaps back for a frame.
            if (moved == null || keys != keysAtDrop) sync()
        }
    }

    /** Swaps the held row with its neighbour ([dir] -1 up, +1 down) and re-anchors it under the finger. */
    private fun swap(dir: Int) {
        val from = draggedIdx
        val to = from + dir
        val held = rows[from]
        val neighbour = rows[to]
        Collections.swap(rows, from, to)
        draggedIdx = to
        offsetY -= dir * (heights[neighbour] ?: 0)
        // The neighbour takes the held row's old place by sliding there from where it was, like the queue's animateItem.
        slideBy(neighbour, dir * (heights[held] ?: 0).toFloat())
    }

    private fun slideBy(key: K, by: Float) {
        slideJobs.remove(key)?.cancel()
        val start = (slides[key] ?: 0f) + by
        slides[key] = start
        slideJobs[key] = scope.launch {
            // animateItem's placement spring, so the rows give way as the queue's do.
            animate(start, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> slides[key] = v }
            slides.remove(key)
        }
    }

    /** Past half a neighbour, from the finger or the edge scroll, the held row swaps. */
    private fun normalize() {
        while (true) {
            val dir = heldRowSwap(draggedIdx, offsetY, shownHeights())
            if (dir == 0) return
            swap(dir)
        }
    }

    /**
     * This frame's edge scroll ([autoScrollStep]). The viewport is the part of the Column on screen: its bounds clipped by
     * the scrolling page around it, and above the floating chrome at the bottom. It scrolls only while that shows more of
     * the list ([edgeScrollWithinList]).
     */
    private fun edgeStep(): Float {
        val column = columnCoords?.takeIf { it.isAttached } ?: return 0f
        val held = rows.getOrNull(draggedIdx) ?: return 0f
        val height = heights[held] ?: return 0f
        val visible = column.boundsInRoot()
        val viewTop = visible.top
        val viewBottom = min(visible.bottom, column.findRootCoordinates().size.height - bottomInsetPx)
        if (viewBottom <= viewTop) return 0f
        val listTop = column.positionInRoot().y
        var top = listTop + heldOffset()
        for (i in 0 until draggedIdx) top += heights[rows[i]] ?: 0
        val step = autoScrollStep(
            visualTop = top - viewTop,
            visualBottom = top - viewTop + height,
            viewportPx = viewBottom - viewTop,
            edgePx = edgePx,
            maxStepPx = maxStepPx,
        )
        return edgeScrollWithinList(step, listTop, listTop + column.size.height, viewTop, viewBottom)
    }
}

/**
 * A [ReorderableColumnState] showing [keys] (no duplicates) in a Column inside the page scrolled by [scrollState] (null: no
 * edge scroll). [onMove] gets each drop that moved a row: the row's key and its new index in [keys]. [bottomInset] is how
 * much of the screen's bottom the floating chrome covers.
 */
@Composable
fun <K : Any> rememberReorderableColumnState(
    keys: List<K>,
    scrollState: ScrollableState?,
    onMove: (key: K, to: Int) -> Unit,
    bottomInset: Dp = LocalBottomChromeInset.current,
): ReorderableColumnState<K> {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val state = remember(scrollState) {
        with(density) {
            ReorderableColumnState(keys, scope, scrollState, DragAutoScrollEdge.toPx(), DragAutoScrollMaxStep.toPx())
        }
    }
    SideEffect {
        state.keys = keys
        state.onMove = onMove
        state.bottomInsetPx = with(density) { bottomInset.toPx() }
        state.haptics = haptics
    }
    LaunchedEffect(state, keys) { state.sync() }
    return state
}

/** The Column whose rows [state] reorders: where it sits on screen, for the edge scroll. */
fun Modifier.reorderableColumn(state: ReorderableColumnState<*>): Modifier = onPlaced { state.columnCoords = it }

/**
 * One row of a reorderable Column, on the Column's direct child: measured for the swaps, slid out of the held row's way,
 * and while held, lifted above the others with the queue's shadow, following the finger. Give the held row an opaque
 * background after this ([ReorderableColumnState.isDragging]); the queue's is `surfaceVariant`.
 */
fun <K : Any> Modifier.reorderableRow(state: ReorderableColumnState<K>, key: K): Modifier {
    val held = state.isDragging(key)
    return this
        .onSizeChanged { state.measured(key, it.height) }
        // The held row follows the finger through its offset, and the others slide through theirs: one placement-time
        // read each, so a drag moves the rows without recomposing them.
        .zIndex(if (held) 10f else 0f)
        .offset { IntOffset(0, state.offsetOf(key).roundToInt()) }
        .then(if (held) Modifier.shadow(8.dp, RoundedCornerShape(8.dp)) else Modifier)
}

/**
 * The drag handle of [key]'s row. Starts on touch, no long press, as the queue's does: during a long-press wait a sideways
 * twitch went elsewhere. A stolen pointer drops the row where it sits instead of snapping the drag back.
 */
fun <K : Any> Modifier.reorderHandle(state: ReorderableColumnState<K>, key: K): Modifier =
    pointerInput(state, key) {
        detectDragGestures(
            onDragStart = { state.startDrag(key) },
            onDrag = { change, amount ->
                change.consume()
                state.dragBy(amount.y)
            },
            onDragEnd = state::endDrag,
            onDragCancel = state::endDrag,
        )
    }
