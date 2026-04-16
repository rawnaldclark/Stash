package com.stash.feature.sync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.media.preview.PreviewState
import com.stash.core.ui.theme.StashTheme

/**
 * Screen displaying tracks that could not be matched on YouTube during sync.
 *
 * Users can review unmatched songs, trigger a resync to find new candidates,
 * preview and approve matches, or dismiss individual tracks so they are no
 * longer retried on future syncs. A confirmation dialog prevents accidental
 * dismissals.
 *
 * @param onBack   Callback invoked when the back arrow is tapped.
 * @param viewModel Injected via Hilt; provides unmatched track data.
 */
@Composable
fun FailedMatchesScreen(
    onBack: () -> Unit,
    viewModel: FailedMatchesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()
    val extendedColors = StashTheme.extendedColors

    // Track pending dismiss confirmation dialog.
    var trackToDismiss by remember { mutableStateOf<UnmatchedTrackView?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            // -- Loading state --
            state.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // -- Empty state: all caught up --
            state.tracks.isEmpty() -> {
                // Back button pinned to top-left
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(start = 8.dp, top = 8.dp)
                        .align(Alignment.TopStart)
                        .size(48.dp)
                        .background(
                            color = extendedColors.glassBackground,
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                // Centered empty message
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "All caught up!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "No unmatched songs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // -- Content: header + track list --
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                ) {
                    // -- Header --
                    item(key = "header") {
                        FailedMatchesHeader(
                            trackCount = state.tracks.size,
                            isResyncing = state.isResyncing,
                            resyncProgress = state.resyncProgress,
                            onBack = onBack,
                            onResync = { viewModel.resync() },
                        )
                    }

                    // -- Track list --
                    itemsIndexed(
                        items = state.tracks,
                        key = { _, track -> track.id },
                    ) { index, track ->
                        val candidate = state.resyncCandidates[track.trackId]
                        val isPreviewPlaying = previewState is PreviewState.Playing &&
                            (previewState as PreviewState.Playing).videoId == candidate?.videoId
                        val isPreviewLoading = state.previewLoading == candidate?.videoId

                        UnmatchedTrackRow(
                            track = track,
                            candidate = candidate,
                            resyncAttempted = state.resyncProgress.isNotEmpty(),
                            isPreviewPlaying = isPreviewPlaying,
                            isPreviewLoading = isPreviewLoading,
                            isApproving = track.trackId in state.approvingIds,
                            onPreview = { videoId -> viewModel.previewRejectedMatch(videoId) },
                            onStopPreview = { viewModel.stopPreview() },
                            onApprove = {
                                candidate?.let {
                                    viewModel.approveMatch(track.trackId, track.id, it)
                                }
                            },
                            onDismiss = { trackToDismiss = track },
                        )

                        // Subtle divider between rows (skip after last item).
                        if (index < state.tracks.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 80.dp, end = 20.dp),
                                thickness = 0.5.dp,
                                color = extendedColors.glassBorder,
                            )
                        }
                    }
                }
            }
        }
    }

    // -- Dismiss confirmation dialog --
    if (trackToDismiss != null) {
        val track = trackToDismiss!!
        AlertDialog(
            onDismissRequest = { trackToDismiss = null },
            title = {
                Text(
                    text = "Stop retrying this song?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = "'${track.artist} \u2014 ${track.title}' won't be downloaded during future syncs. You can find it manually using Search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.dismissTrack(track.trackId)
                        trackToDismiss = null
                    },
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { trackToDismiss = null }) {
                    Text("Cancel")
                }
            },
            containerColor = extendedColors.elevatedSurface,
        )
    }
}

// -- Header composable -------------------------------------------------------

/**
 * Displays the back button, screen title, subtitle, track count, and a
 * resync button that triggers a fresh YouTube search for all unmatched tracks.
 */
@Composable
private fun FailedMatchesHeader(
    trackCount: Int,
    isResyncing: Boolean,
    resyncProgress: String,
    onBack: () -> Unit,
    onResync: () -> Unit,
) {
    val extendedColors = StashTheme.extendedColors

    Column(modifier = Modifier.fillMaxWidth()) {
        // Back button -- statusBarsPadding ensures it sits below the system bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, top = 8.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = extendedColors.glassBackground,
                        shape = CircleShape,
                    ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Title, subtitle, track count, and resync button
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "Unmatched Songs",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "These songs couldn't be found on YouTube during sync.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "$trackCount track${if (trackCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Resync button -- triggers a fresh YouTube search for all tracks
            Button(
                onClick = onResync,
                enabled = !isResyncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (isResyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Searching $resyncProgress...")
                } else {
                    Icon(Icons.Default.Refresh, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Resync")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// -- Unmatched track row composable ------------------------------------------

/**
 * A single row for an unmatched track. Shows album art (or gradient
 * placeholder), original track info, optional resync candidate info,
 * preview/approve/dismiss buttons.
 *
 * @param track            The unmatched track data to display.
 * @param candidate        The resync candidate for this track, if one was found.
 * @param resyncAttempted  Whether a resync has been run (to show "No match found").
 * @param isPreviewPlaying Whether this track's candidate is currently playing.
 * @param isPreviewLoading Whether the preview URL is currently being extracted.
 * @param isApproving      Whether a download is in progress for this track.
 * @param onPreview        Callback invoked with the videoId to start preview.
 * @param onStopPreview    Callback invoked to stop the current preview.
 * @param onApprove        Callback invoked when the approve (checkmark) button is tapped.
 * @param onDismiss        Callback invoked when the dismiss (X) button is tapped.
 */
@Composable
private fun UnmatchedTrackRow(
    track: UnmatchedTrackView,
    candidate: ResyncCandidate?,
    resyncAttempted: Boolean,
    isPreviewPlaying: Boolean,
    isPreviewLoading: Boolean,
    isApproving: Boolean,
    onPreview: (String) -> Unit,
    onStopPreview: () -> Unit,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Album art: show candidate thumbnail if available, otherwise gradient placeholder
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (candidate?.thumbnailUrl != null) {
                AsyncImage(
                    model = candidate.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Text column: original track info + candidate info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            // Line 1: original track title and artist
            Text(
                text = "${track.title} \u2014 ${track.artist}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // Line 2: candidate info or "No match found"
            if (candidate != null) {
                Text(
                    text = "${candidate.title} \u2014 ${candidate.artist}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (resyncAttempted) {
                Text(
                    text = "No match found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                )
            }
        }

        // Preview button -- only shown when a candidate exists
        if (candidate != null) {
            IconButton(
                onClick = if (isPreviewPlaying) onStopPreview else {
                    { onPreview(candidate.videoId) }
                },
                modifier = Modifier.size(40.dp),
            ) {
                when {
                    isPreviewLoading -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    isPreviewPlaying -> Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop preview",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    else -> Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Preview match",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Approve button -- only shown when a candidate exists
        if (candidate != null) {
            if (isApproving) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF4CAF50),
                    )
                }
            } else {
                IconButton(
                    onClick = onApprove,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Approve match",
                        tint = Color(0xFF4CAF50),
                    )
                }
            }
        }

        // Dismiss button -- always shown
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
