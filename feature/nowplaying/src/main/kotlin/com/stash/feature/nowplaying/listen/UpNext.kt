package com.stash.feature.nowplaying.listen

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.stash.core.media.listen.ListenTogetherState
import com.stash.core.model.share.SharedTrack
import com.stash.core.ui.theme.StashTheme
import com.stash.feature.nowplaying.ui.autoScrollStep
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Up next in the Session sheet. Everyone sees who added each song. The host can also swipe a song away or drag it
// by its handle, with the queue sheet's gestures (QueueBottomSheet says why each detail is the way it is). An edit
// sends the whole new queue to the room; the room's reply resyncs the rows.

internal const val MAX_UP_NEXT = 20

/** A queued song keyed by the song plus which copy it is: the key survives moves, removals, the room's "by" stamps and its cover. */
internal class UpNextEntry(val uid: String, val track: SharedTrack)

internal fun upNextEntries(tracks: List<SharedTrack>): List<UpNextEntry> {
    // Copies are counted per key text, not per song: two different songs can read the same (a listener can
    // craft that), and a repeated key would crash the list on every phone in the room.
    val copies = HashMap<String, Int>()
    return tracks.map { t ->
        val song = t.copy(addedBy = null, artUrl = null).toString()
        UpNextEntry("$song#${copies.merge(song, 1) { a, b -> a + b }}", t)
    }
}

/** Where the row [uid] is in this queue now; -1 once its song has left (it started playing, or was removed). */
internal fun List<SharedTrack>.indexOfEntry(uid: String): Int = upNextEntries(this).indexOfFirst { it.uid == uid }

internal fun List<SharedTrack>.moved(from: Int, to: Int): List<SharedTrack> = toMutableList().apply { add(to, removeAt(from)) }

/** Up next's rows and the host's drag, kept while the Session sheet is open. */
internal class UpNextState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgePx: Float,
    private val maxStepPx: Float,
    /** The room's whole queue as of the last composition. */
    var queue: List<SharedTrack>,
) {
    var onEdit: (List<SharedTrack>) -> Unit = {}

    /** The first [MAX_UP_NEXT] songs as shown: the room's order plus a drag's moves until the drop. Filled at once, so the sheet never opens on an empty list. */
    val rows = mutableStateListOf<UpNextEntry>().apply { addAll(upNextEntries(queue.take(MAX_UP_NEXT))) }
    var draggedIdx by mutableIntStateOf(-1)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    var resyncTick by mutableIntStateOf(0)
        private set
    private var itemHeight = 0
    private var startIdx = -1
    private var startQueue: List<SharedTrack>? = null
    private var autoScroll: Job? = null
    private var settle: Job? = null

    fun sync() {
        if (draggedIdx >= 0) return // the rows are the finger's until the drop, which resyncs
        rows.clear()
        rows.addAll(upNextEntries(queue.take(MAX_UP_NEXT)))
    }

    fun remove(uid: String) {
        rows.removeAll { it.uid == uid }
        val i = queue.indexOfEntry(uid)
        if (i >= 0) onEdit(queue.filterIndexed { j, _ -> j != i })
    }

    /** One place up ([by] = -1) or down (+1): TalkBack's way to reorder, since it can't drag. */
    fun move(uid: String, by: Int): Boolean {
        val i = queue.indexOfEntry(uid)
        if (i < 0 || i + by !in queue.indices) return false
        onEdit(queue.moved(i, i + by))
        return true
    }

    fun startDrag(uid: String) {
        settle?.cancel()
        val i = rows.indexOfFirst { it.uid == uid }
        if (i < 0) return
        draggedIdx = i
        startIdx = i
        startQueue = queue
        offsetY = 0f
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == uid }?.let { itemHeight = it.size }
        // Holding the row near the sheet's top or bottom scrolls the list under it, so one drag can go anywhere.
        autoScroll?.cancel()
        autoScroll = scope.launch {
            while (isActive) {
                withFrameNanos { }
                val key = rows.getOrNull(draggedIdx)?.uid ?: continue
                val layout = listState.layoutInfo
                val row = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: continue
                val step = autoScrollStep(
                    visualTop = row.offset + offsetY,
                    visualBottom = row.offset + offsetY + row.size,
                    viewportPx = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat(),
                    edgePx = edgePx,
                    maxStepPx = maxStepPx,
                )
                if (step != 0f) {
                    offsetY += listState.scrollBy(step) // what actually moved: nothing at the list's ends
                    normalize()
                }
            }
        }
    }

    fun dragBy(dy: Float) {
        offsetY += dy
        normalize()
    }

    /** The whole drag is one move, from where the row started to where it was dropped; the row then eases into its slot. */
    fun endDrag() {
        autoScroll?.cancel()
        autoScroll = null
        // A song ended mid-drag: the queue moved under the finger and the indices are stale, so the move is dropped.
        startQueue?.takeIf { it == queue && startIdx >= 0 && draggedIdx >= 0 && startIdx != draggedIdx }
            ?.let { onEdit(it.moved(startIdx, draggedIdx)) }
        startIdx = -1
        startQueue = null
        settle = scope.launch {
            animate(offsetY, 0f, animationSpec = tween(150)) { v, _ -> offsetY = v }
            draggedIdx = -1
            resyncTick++ // shows what arrived mid-drag, and puts back a dropped move
        }
    }

    /** Swaps the held row with its neighbour ([dir] -1 up, +1 down) and re-anchors it under the finger. */
    private fun swap(dir: Int) {
        val from = draggedIdx
        val to = from + dir
        // LazyColumn keeps the first visible row where it is when rows move, so a swap involving it would
        // scroll the list under the finger; asking for the current position keeps it still.
        val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
        if (first == rows[from].uid || first == rows[to].uid) {
            listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
        Collections.swap(rows, from, to)
        draggedIdx = to
        offsetY -= dir * itemHeight
    }

    /** Past half a row, from the finger or the auto-scroll, the held row swaps. */
    private fun normalize() {
        if (draggedIdx < 0 || itemHeight <= 0) return
        val half = itemHeight / 2
        while (offsetY < -half && draggedIdx > 0) swap(-1)
        while (offsetY > half && draggedIdx < rows.lastIndex) swap(1)
    }
}

@Composable
internal fun rememberUpNextState(listState: LazyListState, queue: List<SharedTrack>, onEdit: (List<SharedTrack>) -> Unit): UpNextState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember { UpNextState(listState, scope, with(density) { 64.dp.toPx() }, with(density) { 6.dp.toPx() }, queue) }
    SideEffect {
        state.queue = queue
        state.onEdit = onEdit
    }
    LaunchedEffect(queue.take(MAX_UP_NEXT), state.resyncTick) { state.sync() }
    return state
}

/** UP NEXT with its rows, which the host can edit, then "+N more". */
internal fun LazyListScope.upNextItems(room: ListenTogetherState.InRoom, upNext: UpNextState) {
    item { SectionLabel("UP NEXT") }
    if (upNext.rows.isEmpty()) {
        item { Text("Nothing queued yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    items(upNext.rows, key = { it.uid }) { entry ->
        val face: @Composable () -> Unit = { entry.track.addedBy?.let { PersonAvatar(it, room.nameOf(it), 22.dp) } }
        if (room.isHost) EditableRow(entry, upNext, face) else SongRow(entry.track, modifier = Modifier.animateItem(), trailing = face)
    }
    if (room.queue.size > MAX_UP_NEXT) {
        item { Text("+${room.queue.size - MAX_UP_NEXT} more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun LazyItemScope.EditableRow(entry: UpNextEntry, upNext: UpNextState, face: @Composable () -> Unit) {
    val isDragging = upNext.rows.getOrNull(upNext.draggedIdx)?.uid == entry.uid
    var swipeX by remember { mutableFloatStateOf(0f) }
    var rowWidthPx by remember { mutableIntStateOf(0) }
    Box(
        // The held row follows the finger through its offset, so it must not also get animateItem's slide.
        if (isDragging) Modifier.zIndex(10f).offset { IntOffset(0, upNext.offsetY.roundToInt()) }.shadow(8.dp, RoundedCornerShape(8.dp))
        else Modifier.animateItem().onSizeChanged { rowWidthPx = it.width },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(
                    MaterialTheme.colorScheme.errorContainer.copy(
                        alpha = if (rowWidthPx > 0) (abs(swipeX) / (rowWidthPx / 2f)).coerceIn(0f, 1f) else 0f,
                    ),
                ),
        )
        SongRow(
            entry.track,
            modifier = Modifier
                .offset { IntOffset(swipeX.roundToInt(), 0) }
                // Swipe to remove by distance only: the row goes when it's past half its width as the finger lifts,
                // so a flick or a diagonal scroll can't remove a song.
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = upNext.draggedIdx < 0 && !upNext.listState.isScrollInProgress,
                    state = rememberDraggableState { delta -> swipeX += delta },
                    onDragStopped = {
                        if (rowWidthPx > 0 && abs(swipeX) > rowWidthPx / 2f) {
                            animate(swipeX, sign(swipeX) * rowWidthPx, animationSpec = tween(150)) { v, _ -> swipeX = v }
                            upNext.remove(entry.uid)
                        } else {
                            animate(swipeX, 0f) { v, _ -> swipeX = v }
                        }
                    },
                )
                .background(if (isDragging) MaterialTheme.colorScheme.surfaceVariant else StashTheme.extendedColors.elevatedSurface)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Remove from Up next") { upNext.remove(entry.uid); true },
                        CustomAccessibilityAction("Move up") { upNext.move(entry.uid, -1) },
                        CustomAccessibilityAction("Move down") { upNext.move(entry.uid, 1) },
                    )
                },
        ) {
            face()
            Box(
                Modifier
                    .size(48.dp)
                    .pointerInput(entry.uid) {
                        // Starts on touch, no long press: during a long-press wait a sideways twitch went to the swipe.
                        detectDragGestures(
                            onDragStart = { upNext.startDrag(entry.uid) },
                            onDrag = { change, amount ->
                                change.consume()
                                upNext.dragBy(amount.y)
                            },
                            onDragEnd = upNext::endDrag,
                            // A stolen pointer drops the row where it sits instead of snapping the drag back.
                            onDragCancel = upNext::endDrag,
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = null, // TalkBack reorders with the row's Move up / Move down actions
                    tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
