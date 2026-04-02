package com.stash.feature.nowplaying.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.stash.core.model.Track
import java.util.Collections
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueBottomSheet(
    queue: List<Track>,
    currentIndex: Int,
    accentColor: Color,
    onDismiss: () -> Unit,
    onTrackClick: (index: Int) -> Unit,
    onRemoveTrack: (index: Int) -> Unit,
    onMoveTrack: (from: Int, to: Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Local mutable copy of upcoming tracks for drag reordering.
    // Swaps happen here visually during drag; committed to player on drag end.
    val upcomingSource = queue.drop(currentIndex + 1)
    val localQueue = remember { mutableStateListOf<Track>() }

    // Sync local queue with source when not dragging
    var draggedIdx by remember { mutableIntStateOf(-1) }
    LaunchedEffect(upcomingSource) {
        if (draggedIdx < 0) {
            localQueue.clear()
            localQueue.addAll(upcomingSource)
        }
    }

    // Track cumulative moves during a drag so we can commit them
    val pendingMoves = remember { mutableStateListOf<Pair<Int, Int>>() }

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

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false),
                userScrollEnabled = draggedIdx < 0,
            ) {
                itemsIndexed(localQueue) { idx, track ->
                    val isDragging = idx == draggedIdx

                    Box(
                        modifier = Modifier
                            .then(
                                if (isDragging) Modifier
                                    .zIndex(10f)
                                    .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                                    .shadow(8.dp, RoundedCornerShape(8.dp))
                                else Modifier.zIndex(0f)
                            ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface,
                                )
                                .clickable {
                                    onTrackClick(currentIndex + 1 + idx)
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
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = track.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // Drag handle
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .pointerInput(idx) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                draggedIdx = idx
                                                dragOffsetY = 0f
                                                pendingMoves.clear()
                                                // Measure item height
                                                val info = listState.layoutInfo.visibleItemsInfo
                                                    .firstOrNull { it.index == idx }
                                                if (info != null) itemHeight = info.size
                                            },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragOffsetY += amount.y

                                                if (draggedIdx < 0 || itemHeight <= 0) return@detectDragGesturesAfterLongPress

                                                val half = itemHeight / 2

                                                // Move up
                                                while (dragOffsetY < -half && draggedIdx > 0) {
                                                    val from = draggedIdx
                                                    val to = draggedIdx - 1
                                                    Collections.swap(localQueue, from, to)
                                                    pendingMoves.add(Pair(
                                                        currentIndex + 1 + from,
                                                        currentIndex + 1 + to,
                                                    ))
                                                    draggedIdx = to
                                                    dragOffsetY += itemHeight
                                                }
                                                // Move down
                                                while (dragOffsetY > half && draggedIdx < localQueue.lastIndex) {
                                                    val from = draggedIdx
                                                    val to = draggedIdx + 1
                                                    Collections.swap(localQueue, from, to)
                                                    pendingMoves.add(Pair(
                                                        currentIndex + 1 + from,
                                                        currentIndex + 1 + to,
                                                    ))
                                                    draggedIdx = to
                                                    dragOffsetY -= itemHeight
                                                }
                                            },
                                            onDragEnd = {
                                                // Commit all moves to the actual player queue
                                                pendingMoves.forEach { (from, to) ->
                                                    onMoveTrack(from, to)
                                                }
                                                pendingMoves.clear()
                                                draggedIdx = -1
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                // Revert: resync local queue from source
                                                localQueue.clear()
                                                localQueue.addAll(upcomingSource)
                                                pendingMoves.clear()
                                                draggedIdx = -1
                                                dragOffsetY = 0f
                                            },
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
}

// ---------------------------------------------------------------------------

@Composable
private fun QueueHeader(trackCount: Int, currentIndex: Int, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Queue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (trackCount > 0) {
                Text(
                    "${currentIndex + 1} of $trackCount tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Hold ≡ to drag",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
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
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
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
