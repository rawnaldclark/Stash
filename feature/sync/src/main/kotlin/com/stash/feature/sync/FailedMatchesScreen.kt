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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.data.db.dao.UnmatchedTrackView
import com.stash.core.ui.theme.StashTheme

/**
 * Screen displaying tracks that could not be matched on YouTube during sync.
 *
 * Users can review unmatched songs and dismiss individual tracks so they
 * are no longer retried on future syncs. A confirmation dialog prevents
 * accidental dismissals.
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
                    // ── Header ─────────────────────────────────────────────
                    item(key = "header") {
                        FailedMatchesHeader(
                            trackCount = state.tracks.size,
                            onBack = onBack,
                        )
                    }

                    // ── Track list ─────────────────────────────────────────
                    itemsIndexed(
                        items = state.tracks,
                        key = { _, track -> track.id },
                    ) { index, track ->
                        UnmatchedTrackRow(
                            track = track,
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

    // ── Dismiss confirmation dialog ─────────────────────────────────────────
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

// ── Header composable ───────────────────────────────────────────────────────

/**
 * Displays the back button, screen title, subtitle, and track count.
 */
@Composable
private fun FailedMatchesHeader(
    trackCount: Int,
    onBack: () -> Unit,
) {
    val extendedColors = StashTheme.extendedColors

    Column(modifier = Modifier.fillMaxWidth()) {
        // Back button — statusBarsPadding ensures it sits below the system bar
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

        // Title and subtitle
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

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ── Unmatched track row composable ──────────────────────────────────────────

/**
 * A single row for an unmatched track. Shows album art (or gradient
 * placeholder), title, artist, and a dismiss button.
 *
 * Unlike [DetailTrackRow], this row is not tappable for playback because
 * unmatched tracks have no downloaded file.
 *
 * @param track     The unmatched track data to display.
 * @param onDismiss Callback invoked when the dismiss (X) button is tapped.
 */
@Composable
private fun UnmatchedTrackRow(
    track: UnmatchedTrackView,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Gradient placeholder (unmatched tracks have no downloaded art)
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
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title + artist
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
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

        // Dismiss button
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
