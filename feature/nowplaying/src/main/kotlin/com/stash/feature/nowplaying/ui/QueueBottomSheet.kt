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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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

            if (currentIndex in queue.indices) {
                CurrentTrackRow(
                    track = queue[currentIndex],
                    accentColor = accentColor,
                )
            }

            val upcomingTracks = queue.drop(currentIndex + 1)
            if (upcomingTracks.isNotEmpty()) {
                Text(
                    text = "Up Next",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                )
            }

            // -- Reorderable queue list --
            // Drag state is tracked here; the gesture is on each row's drag handle.
            val listState = rememberLazyListState()
            var draggedListIndex by remember { mutableIntStateOf(-1) }
            var dragOffsetY by remember { mutableFloatStateOf(0f) }
            val itemHeightPx = remember { mutableIntStateOf(0) }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false),
                // Disable LazyColumn scroll while dragging to prevent conflicts
                userScrollEnabled = draggedListIndex < 0,
            ) {
                itemsIndexed(
                    items = upcomingTracks,
                    key = { listIdx, track -> "${track.id}_$listIdx" },
                ) { listIdx, track ->
                    val queueIndex = currentIndex + 1 + listIdx
                    val isDragging = listIdx == draggedListIndex

                    val elevation by animateDpAsState(
                        targetValue = if (isDragging) 8.dp else 0.dp,
                        label = "drag-elev",
                    )

                    Box(
                        modifier = Modifier
                            .then(
                                if (isDragging) {
                                    Modifier
                                        .zIndex(10f)
                                        .offset { IntOffset(0, dragOffsetY.roundToInt()) }
                                        .shadow(elevation, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier.zIndex(0f)
                                }
                            ),
                    ) {
                        QueueTrackRowWithDrag(
                            track = track,
                            isDragging = isDragging,
                            onClick = { onTrackClick(queueIndex) },
                            onDragStart = {
                                draggedListIndex = listIdx
                                dragOffsetY = 0f
                            },
                            onDrag = { deltaY ->
                                dragOffsetY += deltaY

                                if (draggedListIndex < 0) return@QueueTrackRowWithDrag

                                // Estimate item height from layout info
                                val currentItemInfo = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.index == draggedListIndex }
                                if (currentItemInfo != null) {
                                    itemHeightPx.intValue = currentItemInfo.size
                                }

                                val halfItem = itemHeightPx.intValue / 2

                                // Swap with item above
                                if (dragOffsetY < -halfItem && draggedListIndex > 0) {
                                    val from = currentIndex + 1 + draggedListIndex
                                    val to = from - 1
                                    onMoveTrack(from, to)
                                    draggedListIndex -= 1
                                    dragOffsetY += itemHeightPx.intValue.toFloat()
                                }
                                // Swap with item below
                                else if (dragOffsetY > halfItem && draggedListIndex < upcomingTracks.lastIndex) {
                                    val from = currentIndex + 1 + draggedListIndex
                                    val to = from + 1
                                    onMoveTrack(from, to)
                                    draggedListIndex += 1
                                    dragOffsetY -= itemHeightPx.intValue.toFloat()
                                }
                            },
                            onDragEnd = {
                                draggedListIndex = -1
                                dragOffsetY = 0f
                            },
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
            text = "Hold to drag",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
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
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = "Now playing",
            tint = accentColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Queue row with a drag handle that initiates long-press drag.
 * The drag gesture lives on the drag handle icon only, so it doesn't
 * conflict with the row's tap or the LazyColumn's scroll.
 */
@Composable
private fun QueueTrackRowWithDrag(
    track: Track,
    isDragging: Boolean,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (deltaY: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
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
        // Drag handle — long press HERE to start dragging
        Box(
            modifier = Modifier
                .size(48.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.y)
                        },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
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
                contentDescription = null,
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
