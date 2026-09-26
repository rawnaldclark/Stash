package com.stash.feature.nowplaying.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.stash.core.common.primaryArtist
import com.stash.core.model.PlaybackSource
import com.stash.core.model.Track
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    queue: List<Track>,
    currentIndex: Int,
    accentColor: Color,
    source: PlaybackSource = PlaybackSource.Unknown,
    /** #468: with shuffle on the rows are in play order and cannot be dragged (see below). */
    isShuffleEnabled: Boolean = false,
    onDismiss: () -> Unit,
    onTrackClick: (index: Int) -> Unit,
    onRemoveTrack: (index: Int) -> Unit,
    onMoveTrack: (from: Int, to: Int) -> Unit,
    // Per-track ⋮ menu actions (queue-appropriate subset — no Add to Queue /
    // Delete; swipe removes from the queue). Null = feature disabled.
    onPlayNext: ((Track) -> Unit)? = null,
    onStartRadio: ((Track) -> Unit)? = null,
    onSaveToPlaylist: ((Track) -> Unit)? = null,
    onShare: ((Track) -> Unit)? = null,
    onToggleDownload: ((Track) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // The upcoming track whose ⋮ menu is open (null = closed). Opens a nested
    // TrackOptionsSheet over the queue sheet.
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    val menuEnabled = onPlayNext != null

    // Local mutable copy of upcoming tracks for drag reordering.
    // Swaps happen here visually during drag; committed to player on drag end.
    // Rows are keyed by song (plus which copy, for duplicates), never by position:
    // position keys made every resync after a move or removal slide other songs
    // through the rows, and let a removed row's state leak into its successor.
    // Filled synchronously so the sheet doesn't open on an empty frame.
    val upcomingSource = queue.drop(currentIndex + 1)
    val localQueue = remember { mutableStateListOf<QueueEntry>().apply { addAll(queueEntries(upcomingSource)) } }

    // Sync local queue with source when not dragging. resyncTick replays a sync
    // that was skipped mid-drag when the drop sends no move.
    var draggedIdx by remember { mutableIntStateOf(-1) }
    var resyncTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(upcomingSource, resyncTick) {
        if (draggedIdx < 0) {
            localQueue.clear()
            localQueue.addAll(queueEntries(upcomingSource))
        }
    }

    // Where the dragged row started, in local upcoming-list index space, and the
    // current song then: if the song changes mid-drag the local indices are stale.
    var dragStartIdx by remember { mutableIntStateOf(-1) }
    var dragStartCurrent by remember { mutableIntStateOf(-1) }
    // Commit closures live inside pointerInput and outlast recompositions;
    // read currentIndex through updated-state so a track advancing while
    // the sheet is open can't stale the committed absolute indices.
    val liveCurrentIndex by rememberUpdatedState(currentIndex)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            QueueHeader(
                trackCount = queue.size,
                currentIndex = currentIndex,
                sourceLabel = source.displayLabel,
                showDragHint = !isShuffleEnabled,
                onClose = onDismiss,
            )

            if (currentIndex in queue.indices) {
                CurrentTrackRow(queue[currentIndex], accentColor)
            }

            if (localQueue.isNotEmpty()) {
                Text(
                    text = "Up Next",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                )
            }

            val listState = rememberLazyListState()
            var dragOffsetY by remember { mutableFloatStateOf(0f) }
            var itemHeight by remember { mutableIntStateOf(0) }

            // Edge auto-scroll (issue #319): holding a dragged row near the
            // sheet's top/bottom scrolls the list under it, so ONE gesture
            // can carry a track from deep in the queue to the top.
            val scope = rememberCoroutineScope()
            val density = LocalDensity.current
            val edgePx = with(density) { 64.dp.toPx() }
            val maxStepPx = with(density) { 6.dp.toPx() }
            var autoScrollJob by remember { mutableStateOf<Job?>(null) }
            var settleJob by remember { mutableStateOf<Job?>(null) }

            // One swap of the dragged row with its neighbour ([dir] = -1 up, +1 down).
            // LazyColumn keeps the first visible row's key pinned when rows move, so a
            // swap at the top would scroll the list under the finger; re-requesting the
            // current position keeps it still.
            val swapDragged = { dir: Int ->
                val from = draggedIdx
                val to = from + dir
                val first = listState.firstVisibleItemIndex
                if (from == first || to == first) {
                    listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
                }
                Collections.swap(localQueue, from, to)
                draggedIdx = to
                dragOffsetY -= dir * itemHeight
            }

            // Swap normalization shared by finger movement AND auto-scroll:
            // whenever the accumulated offset crosses half a row, swap and
            // re-anchor. Auto-scroll moves rows under a stationary finger,
            // so it must normalize too — drag events alone would miss it.
            val normalizeSwaps = {
                if (draggedIdx >= 0 && itemHeight > 0) {
                    val half = itemHeight / 2
                    while (dragOffsetY < -half && draggedIdx > 0) swapDragged(-1)
                    while (dragOffsetY > half && draggedIdx < localQueue.lastIndex) swapDragged(1)
                }
            }

            // The whole drag commits as ONE move: its net effect is "take the
            // row from where it started to where it was dropped" — exactly the
            // remove-then-insert the player's moveInQueue performs. Replaying
            // per-slot swaps as N separate player calls raced the queue
            // round-trip and could snap the row back to its old place on drop.
            // The row then eases from the finger into its slot instead of snapping.
            val commitDrag = {
                autoScrollJob?.cancel()
                autoScrollJob = null
                val committed = dragStartIdx >= 0 && draggedIdx >= 0 && dragStartIdx != draggedIdx &&
                    liveCurrentIndex == dragStartCurrent
                if (committed) {
                    onMoveTrack(
                        liveCurrentIndex + 1 + dragStartIdx,
                        liveCurrentIndex + 1 + draggedIdx,
                    )
                }
                dragStartIdx = -1
                settleJob = scope.launch {
                    animate(dragOffsetY, 0f, animationSpec = tween(150)) { v, _ -> dragOffsetY = v }
                    draggedIdx = -1
                    // No move went out, so no player update will resync the list.
                    if (!committed) resyncTick++
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false),
                userScrollEnabled = draggedIdx < 0,
            ) {
                itemsIndexed(
                    items = localQueue,
                    key = { _, entry -> entry.uid },
                ) { idx, entry ->
                    val track = entry.track
                    val isDragging = idx == draggedIdx
                    val queueIndex = currentIndex + 1 + idx
                    // The item's index shifts under an active drag as rows swap;
                    // pointerInput's coroutine captures values at launch, so it
                    // reads the live index through this instead.
                    val currentIdx by rememberUpdatedState(idx)

                    // Swipe to remove, by distance only: the row leaves when it is past
                    // half its width as the finger lifts. Fling speed is ignored, so a
                    // flick or a diagonal scroll can't remove a track (Material's swipe
                    // box removed on any flick over 125 dp/s, and that can't be tuned).
                    var swipeX by remember { mutableFloatStateOf(0f) }
                    var rowWidthPx by remember { mutableIntStateOf(0) }

                    Box(
                        // The held row follows the finger through its offset, so it must not
                        // also get animateItem's slide: that tugged it back a row on every swap.
                        modifier = if (isDragging) Modifier
                            .zIndex(10f)
                            .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                            .shadow(8.dp, RoundedCornerShape(8.dp))
                        else Modifier.animateItem().onSizeChanged { rowWidthPx = it.width },
                    ) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    MaterialTheme.colorScheme.errorContainer.copy(
                                        alpha = if (rowWidthPx > 0) (abs(swipeX) / (rowWidthPx / 2f)).coerceIn(0f, 1f) else 0f,
                                    ),
                                ),
                        )
                        Row(
                            modifier = Modifier
                                .offset { IntOffset(swipeX.roundToInt(), 0) }
                                .draggable(
                                    orientation = Orientation.Horizontal,
                                    enabled = draggedIdx < 0 && !listState.isScrollInProgress,
                                    state = rememberDraggableState { delta -> swipeX += delta },
                                    onDragStopped = {
                                        if (rowWidthPx > 0 && abs(swipeX) > rowWidthPx / 2f) {
                                            animate(swipeX, sign(swipeX) * rowWidthPx, animationSpec = tween(150)) { v, _ -> swipeX = v }
                                            // Look the row up now: the song may have changed since it was drawn.
                                            val i = localQueue.indexOfFirst { it.uid == entry.uid }
                                            if (i >= 0) {
                                                localQueue.removeAt(i)
                                                onRemoveTrack(liveCurrentIndex + 1 + i)
                                            }
                                        } else {
                                            animate(swipeX, 0f) { v, _ -> swipeX = v }
                                        }
                                    },
                                )
                                .fillMaxWidth()
                                .background(
                                    if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .clickable {
                                    onTrackClick(queueIndex)
                                }
                                .padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            QueueTrackArt(track)
                            Spacer(Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    com.stash.core.ui.components.FlacBadge(
                                        fileFormat = track.fileFormat,
                                        bitsPerSample = track.bitsPerSample,
                                        sampleRateHz = track.sampleRateHz,
                                    )
                                }
                                Text(
                                    text = remember(track.artist) { track.artist.primaryArtist() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Per-track ⋮ menu (Play next / Save / Download /
                            // Share / Start radio). Disabled, not hidden, while
                            // dragging: hiding it reflowed the title at pick-up.
                            if (menuEnabled) {
                                IconButton(
                                    onClick = { menuTrack = track },
                                    enabled = !isDragging,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Track options",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            // Drag handle - hidden under shuffle (#468): a controller cannot
                            // rewrite Media3's shuffle order, so a drag would move nothing.
                            if (isShuffleEnabled) {
                                androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(48.dp))
                            } else Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .pointerInput(entry.uid) {
                                        // Starts on touch, no long press: during the long-press
                                        // wait a sideways twitch went to the swipe and removed
                                        // the row instead of dragging it.
                                        detectDragGestures(
                                            onDragStart = {
                                                settleJob?.cancel()
                                                draggedIdx = currentIdx
                                                dragStartIdx = currentIdx
                                                dragStartCurrent = liveCurrentIndex
                                                dragOffsetY = 0f
                                                // Measure item height
                                                val info = listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.index == currentIdx }
                                                if (info != null) itemHeight = info.size
                                                // Frame loop for the whole drag; step is 0
                                                // while the row rides mid-viewport.
                                                autoScrollJob?.cancel()
                                                autoScrollJob = scope.launch {
                                                    while (isActive) {
                                                        withFrameNanos { }
                                                        val layout = listState.layoutInfo
                                                        val dragged = layout.visibleItemsInfo
                                                            .firstOrNull { it.index == draggedIdx }
                                                            ?: continue
                                                        val viewportPx = (layout.viewportEndOffset -
                                                            layout.viewportStartOffset).toFloat()
                                                        val step = autoScrollStep(
                                                            visualTop = dragged.offset + dragOffsetY,
                                                            visualBottom = dragged.offset + dragOffsetY + dragged.size,
                                                            viewportPx = viewportPx,
                                                            edgePx = edgePx,
                                                            maxStepPx = maxStepPx,
                                                        )
                                                        if (step != 0f) {
                                                            // scrollBy returns what actually moved —
                                                            // at the list bounds it tapers to zero.
                                                            val consumed = listState.scrollBy(step)
                                                            dragOffsetY += consumed
                                                            normalizeSwaps()
                                                        }
                                                    }
                                                }
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffsetY += amount.y
                                                normalizeSwaps()
                                            },
                                            onDragEnd = { commitDrag() },
                                            // A stolen pointer (sheet grab, palm
                                            // rejection) commits the row where it
                                            // visibly sits instead of silently
                                            // snapping the whole drag back.
                                            onDragCancel = { commitDrag() },
                                        )
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = "Drag to reorder",
                                    tint = if (isDragging) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (localQueue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No upcoming tracks",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    // ── Per-track options, opened over the queue by a row's ⋮ ────────────────
    // Queue-appropriate subset: Add-to-Queue and Delete are omitted (the track
    // is already queued; swipe removes it), so those rows don't render.
    menuTrack?.let { t ->
        val menuSheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { menuTrack = null },
            sheetState = menuSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            com.stash.core.ui.components.TrackOptionsSheet(
                track = t,
                onPlayNext = { onPlayNext?.invoke(it); menuTrack = null },
                onSaveToPlaylist = { onSaveToPlaylist?.invoke(it); menuTrack = null },
                onStartRadio = onStartRadio?.let { cb -> { track: Track -> cb(track); menuTrack = null } },
                onShare = onShare?.let { cb -> { track: Track -> cb(track); menuTrack = null } },
                onDownload = onToggleDownload?.let { cb -> { track: Track -> cb(track); menuTrack = null } },
                onRemoveDownload = onToggleDownload?.let { cb -> { track: Track -> cb(track); menuTrack = null } },
            )
        }
    }
}

// ---------------------------------------------------------------------------

/**
 * A queue row with a stable identity: [uid] is the song plus which copy of it
 * this is, so it survives swaps, resyncs and removals elsewhere in the queue,
 * and LazyColumn item identity (and an active drag gesture) stays with the song.
 */
internal class QueueEntry(val uid: String, val track: Track)

/** [tracks] as rows keyed `"<track id>:<copy number>"`, unique even when a song is queued twice. */
internal fun queueEntries(tracks: List<Track>): List<QueueEntry> {
    val copies = HashMap<Long, Int>()
    return tracks.map { t -> QueueEntry("${t.id}:${copies.merge(t.id, 1) { a, b -> a + b }}", t) }
}

@Composable
private fun QueueHeader(
    trackCount: Int,
    currentIndex: Int,
    sourceLabel: String,
    showDragHint: Boolean,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (sourceLabel.isNotBlank()) {
                Text(
                    "Playing from $sourceLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (trackCount > 0) {
                Text(
                    "${currentIndex + 1} of $trackCount tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Shuffle turns dragging off (the handle is hidden), so the hint goes too.
        if (showDragHint) {
            Text(
                "Drag ≡ to reorder",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, "Close", Modifier.size(24.dp))
        }
    }
}

@Composable
private fun CurrentTrackRow(track: Track, accentColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accentColor.copy(alpha = 0.1f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QueueTrackArt(track)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                com.stash.core.ui.components.FlacBadge(
                    fileFormat = track.fileFormat,
                    bitsPerSample = track.bitsPerSample,
                    sampleRateHz = track.sampleRateHz,
                    tint = accentColor,
                )
            }
            Text(
                remember(track.artist) { track.artist.primaryArtist() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Default.GraphicEq, "Now playing", tint = accentColor, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun QueueTrackArt(track: Track) {
    val artUrl = track.albumArtPath ?: track.albumArtUrl
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (artUrl != null) {
            AsyncImage(artUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Auto-scroll velocity for a drag near the viewport edges (issue #319):
 * zero while the dragged row rides mid-viewport, ramping linearly toward
 * ±[maxStepPx] per frame as the row's edge sinks into the top or bottom
 * [edgePx] zone. Negative scrolls toward the start of the list.
 */
internal fun autoScrollStep(
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
