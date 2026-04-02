package com.stash.feature.nowplaying.ui

import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.LazyListItemInfo
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlin.math.roundToInt

/**
 * Queue visualizer bottom sheet with drag-to-reorder and swipe-to-remove.
 *
 * Long-press a track to start dragging it through the list.
 * Swipe left or right to remove a track from the queue.
 * Tap a track to jump to it.
 */
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

            // Currently playing track (pinned, not draggable)
            if (currentIndex in queue.indices) {
                CurrentTrackRow(
                    track = queue[currentIndex],
                    accentColor = accentColor,
                )
            }

            // "Up Next" label
            val upcomingTracks = queue.drop(currentIndex + 1)
            if (upcomingTracks.isNotEmpty()) {
                Text(
                    text = "Up Next",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                )
            }

            // Drag-to-reorder list
            val listState = rememberLazyListState()
            var draggedIndex by remember { mutableIntStateOf(-1) }
            var dragOffset by remember { mutableFloatStateOf(0f) }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .pointerInput(upcomingTracks.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                // Find which item we started dragging
                                val item = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { info ->
                                        offset.y.toInt() in info.offset..(info.offset + info.size)
                                    }
                                draggedIndex = item?.index ?: -1
                                dragOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffset += dragAmount.y

                                if (draggedIndex < 0) return@detectDragGesturesAfterLongPress

                                // Check if we need to swap with neighbor
                                val currentItem = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == draggedIndex }
                                    ?: return@detectDragGesturesAfterLongPress

                                val itemCenter = currentItem.offset + currentItem.size / 2 + dragOffset.toInt()

                                // Check item above
                                if (dragOffset < 0 && draggedIndex > 0) {
                                    val above = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == draggedIndex - 1 }
                                    if (above != null && itemCenter < above.offset + above.size / 2) {
                                        val fromQueue = currentIndex + 1 + draggedIndex
                                        val toQueue = currentIndex + 1 + draggedIndex - 1
                                        onMoveTrack(fromQueue, toQueue)
                                        draggedIndex -= 1
                                        dragOffset += currentItem.size.toFloat()
                                    }
                                }
                                // Check item below
                                if (dragOffset > 0 && draggedIndex < upcomingTracks.lastIndex) {
                                    val below = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.index == draggedIndex + 1 }
                                    if (below != null && itemCenter > below.offset + below.size / 2) {
                                        val fromQueue = currentIndex + 1 + draggedIndex
                                        val toQueue = currentIndex + 1 + draggedIndex + 1
                                        onMoveTrack(fromQueue, toQueue)
                                        draggedIndex += 1
                                        dragOffset -= currentItem.size.toFloat()
                                    }
                                }
                            },
                            onDragEnd = {
                                draggedIndex = -1
                                dragOffset = 0f
                            },
                            onDragCancel = {
                                draggedIndex = -1
                                dragOffset = 0f
                            },
                        )
                    },
            ) {
                itemsIndexed(
                    items = upcomingTracks,
                    key = { listIdx, track -> "${track.id}_${currentIndex + 1 + listIdx}" },
                ) { listIdx, track ->
                    val queueIndex = currentIndex + 1 + listIdx
                    val isDragging = listIdx == draggedIndex

                    val elevation by animateDpAsState(
                        targetValue = if (isDragging) 8.dp else 0.dp,
                        label = "drag-elevation",
                    )

                    Box(
                        modifier = Modifier
                            .then(
                                if (isDragging) {
                                    Modifier
                                        .zIndex(1f)
                                        .offset { IntOffset(0, dragOffset.roundToInt()) }
                                        .shadow(elevation, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                }
                            ),
                    ) {
                        SwipeableQueueRow(
                            track = track,
                            isDragging = isDragging,
                            onClick = { onTrackClick(queueIndex) },
                            onRemove = { onRemoveTrack(queueIndex) },
                        )
                    }
                }
            }

            if (upcomingTracks.isEmpty()) {
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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun QueueHeader(
    trackCount: Int,
    currentIndex: Int,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Queue",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (trackCount > 0) {
                Text(
                    text = "${currentIndex + 1} of $trackCount tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = "Hold & drag to reorder",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close queue",
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun CurrentTrackRow(
    track: Track,
    accentColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(accentColor.copy(alpha = 0.1f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QueueTrackArt(track = track)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
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
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = "Now playing",
            tint = accentColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableQueueRow(
    track: Track,
    isDragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart ||
                value == SwipeToDismissBoxValue.StartToEnd) {
                onRemove()
                true
            } else {
                false
            }
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val isActive = dismissState.targetValue != SwipeToDismissBoxValue.Settled
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (isActive) MaterialTheme.colorScheme.errorContainer
                        else Color.Transparent,
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    else -> Alignment.CenterEnd
                },
            ) {
                if (isActive) {
                    Text(
                        text = "Remove",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                )
                .clickable(onClick = onClick)
                .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueTrackArt(track = track)
            Spacer(modifier = Modifier.width(12.dp))
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
            Spacer(modifier = Modifier.width(8.dp))
            // Drag handle visual hint
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp),
            )
        }
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
            AsyncImage(
                model = artUrl,
                contentDescription = "${track.title} album art",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
