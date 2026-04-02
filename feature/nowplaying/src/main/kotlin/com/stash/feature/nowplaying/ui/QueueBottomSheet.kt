package com.stash.feature.nowplaying.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.stash.core.model.Track

/**
 * Modal bottom sheet that displays the current playback queue.
 *
 * Shows the currently playing track at the top (highlighted, non-dismissible)
 * followed by all upcoming tracks. Each upcoming track can be tapped to jump
 * to it, or swiped to remove it from the queue.
 *
 * @param queue         The full list of tracks in the queue.
 * @param currentIndex  Index of the currently playing track within [queue].
 * @param accentColor   Accent color derived from album art palette for highlights.
 * @param onDismiss     Called when the bottom sheet is dismissed.
 * @param onTrackClick  Called with the queue index when a track is tapped.
 * @param onRemoveTrack Called with the queue index when a track is swiped away.
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
            // -- Header --
            QueueHeader(
                trackCount = queue.size,
                currentIndex = currentIndex,
                onClose = onDismiss,
            )

            // -- Currently playing track (pinned, not swipeable) --
            if (currentIndex in queue.indices) {
                CurrentTrackRow(
                    track = queue[currentIndex],
                    accentColor = accentColor,
                )
            }

            // -- Divider label for upcoming tracks --
            val upcomingCount = queue.size - currentIndex - 1
            if (upcomingCount > 0) {
                Text(
                    text = "Up Next",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                )
            }

            // -- Upcoming tracks with swipe-to-remove --
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
            ) {
                // Show tracks after the current index
                val upcomingTracks = queue.drop(currentIndex + 1)
                itemsIndexed(
                    items = upcomingTracks,
                    key = { listIdx, track -> "${track.id}_${currentIndex + 1 + listIdx}" },
                ) { listIdx, track ->
                    val queueIndex = currentIndex + 1 + listIdx
                    val isFirst = listIdx == 0
                    val isLast = listIdx == upcomingTracks.lastIndex
                    SwipeableQueueRow(
                        track = track,
                        onClick = { onTrackClick(queueIndex) },
                        onRemove = { onRemoveTrack(queueIndex) },
                        onMoveUp = if (!isFirst) {{ onMoveTrack(queueIndex, queueIndex - 1) }} else null,
                        onMoveDown = if (!isLast) {{ onMoveTrack(queueIndex, queueIndex + 1) }} else null,
                    )
                }
            }

            // -- Empty state if no upcoming tracks --
            if (upcomingCount <= 0) {
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
// Private composables
// ---------------------------------------------------------------------------

/**
 * Header row showing "Queue" title, track position indicator, and a close button.
 */
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

        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close queue",
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * Highlighted row for the currently-playing track. This row is not swipeable
 * and is visually distinguished with an accent-tinted background and an
 * animated equalizer icon.
 */
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
        // Album art
        QueueTrackArt(track = track)

        Spacer(modifier = Modifier.width(12.dp))

        // Track info
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

        // Animated equalizer icon
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = "Now playing",
            tint = accentColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A single queue row wrapped in [SwipeToDismissBox] for swipe-to-remove.
 *
 * Swiping from end-to-start reveals a red delete background. When the swipe
 * is confirmed, [onRemove] fires to remove the track from the queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableQueueRow(
    track: Track,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
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
            // Red background with delete icon revealed on swipe
            val backgroundColor by animateColorAsState(
                targetValue = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                },
                animationSpec = tween(200),
                label = "swipe-bg",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove from queue",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
    ) {
        QueueTrackRow(
            track = track,
            onClick = onClick,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
        )
    }
}

/**
 * Standard queue row showing album art, title, artist, and move up/down controls.
 * Tapping the row jumps playback to that track.
 */
@Composable
private fun QueueTrackRow(
    track: Track,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Album art
        QueueTrackArt(track = track)

        Spacer(modifier = Modifier.width(12.dp))

        // Track info
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

        // Move up/down buttons
        Column {
            IconButton(
                onClick = { onMoveUp?.invoke() },
                enabled = onMoveUp != null,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Move up",
                    tint = if (onMoveUp != null) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = { onMoveDown?.invoke() },
                enabled = onMoveDown != null,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Move down",
                    tint = if (onMoveDown != null) MaterialTheme.colorScheme.onSurfaceVariant
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 48dp rounded album art thumbnail used in queue rows.
 * Falls back to a music note icon when no art is available.
 */
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
