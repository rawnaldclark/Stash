package com.stash.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.Track
import com.stash.core.ui.components.DetailTrackRow
import com.stash.core.ui.components.SourceIndicator
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.theme.StashTheme
import com.stash.core.ui.util.formatTotalDuration

/**
 * Liked Songs Detail screen entry point.
 *
 * Displays a gradient header with a heart icon, the merged liked-songs
 * track list, and action buttons for playback. Tapping a track starts
 * playback; long-pressing opens a bottom sheet with queue actions.
 *
 * @param onBack    Callback invoked when the back arrow is tapped.
 * @param viewModel Injected via Hilt; extracts optional `source` filter from nav args.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedSongsDetailScreen(
    onBack: () -> Unit,
    viewModel: LikedSongsDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val extendedColors = StashTheme.extendedColors

    // Bottom sheet state for the long-press track menu.
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    var trackToSave by remember { mutableStateOf<Track?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            // -- Loading indicator centered on screen --
            state.isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // -- Empty state: no liked songs yet --
            !state.isLoading && state.tracks.isEmpty() -> {
                LikedSongsEmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    onBack = onBack,
                )
            }

            // -- Content: header + track list --
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 120.dp),
                ) {
                    // ── Header section ──────────────────────────────────────
                    item(key = "header") {
                        LikedSongsHeader(
                            state = state,
                            onBack = onBack,
                            onPlayAll = {
                                val firstTrack = state.tracks.firstOrNull { it.filePath != null }
                                if (firstTrack != null) viewModel.playTrack(firstTrack.id)
                            },
                            onShuffle = { viewModel.shuffleAll() },
                        )
                    }

                    // ── Track list ──────────────────────────────────────────
                    itemsIndexed(
                        items = state.tracks,
                        key = { _, track -> track.id },
                    ) { index, track ->
                        DetailTrackRow(
                            track = track,
                            trackNumber = index + 1,
                            isPlaying = track.id == state.currentlyPlayingTrackId,
                            onClick = { viewModel.playTrack(track.id) },
                            onLongPress = { selectedTrack = track },
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

    // ── Track options bottom sheet ───────────────────────────────────────
    if (selectedTrack != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedTrack = null },
            sheetState = sheetState,
            containerColor = extendedColors.elevatedSurface,
        ) {
            TrackOptionsSheet(
                track = selectedTrack!!,
                onPlayNext = {
                    viewModel.playNext(it)
                    selectedTrack = null
                },
                onAddToQueue = {
                    viewModel.addToQueue(it)
                    selectedTrack = null
                },
                onSaveToPlaylist = {
                    trackToSave = it
                    selectedTrack = null
                },
                onDelete = {
                    viewModel.deleteTrack(it)
                    selectedTrack = null
                },
            )
        }
    }

    // ── Save to Playlist sheet ─────────────────────────────────────────────
    if (trackToSave != null) {
        com.stash.core.ui.components.SaveToPlaylistSheet(
            playlists = userPlaylists.map {
                com.stash.core.ui.components.PlaylistInfo(it.id, it.name, it.trackCount)
            },
            onSaveToPlaylist = { playlistId ->
                viewModel.saveTrackToPlaylist(trackToSave!!.id, playlistId)
                trackToSave = null
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTrack(name, trackToSave!!.id)
                trackToSave = null
            },
            onDismiss = { trackToSave = null },
        )
    }
}

// ── Empty state composable ─────────────────────────────────────────────────

/**
 * Centered empty state shown when the user has no liked songs.
 * Includes a back button at the top-left so the user can navigate away.
 */
@Composable
private fun LikedSongsEmptyState(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val extendedColors = StashTheme.extendedColors

    // Back button pinned to top-left
    Box(modifier = Modifier.fillMaxSize()) {
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
            modifier = modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No liked songs yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Sync your library to get started",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Header composable ───────────────────────────────────────────────────────

/**
 * Displays a gradient box with a large heart icon, title, metadata subtitle,
 * and action buttons (Play All + Shuffle).
 */
@Composable
private fun LikedSongsHeader(
    state: LikedSongsDetailUiState,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
) {
    val extendedColors = StashTheme.extendedColors

    Column(modifier = Modifier.fillMaxWidth()) {
        // -- Gradient header with heart icon and back button --
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.6f)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                            extendedColors.purpleDark.copy(alpha = 0.4f),
                            MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Large heart icon
            Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )

            // Back button — statusBarsPadding ensures it sits below the system bar
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
        }

        // -- Liked songs info below gradient header --
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            // Title
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Source indicator (if filtered) + track count + total duration
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.source != null) {
                    SourceIndicator(source = state.source, size = 8.dp, showLabel = true)

                    Text(
                        text = "\u2022",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val trackCount = state.tracks.size
                Text(
                    text = "$trackCount track${if (trackCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val totalDuration = state.tracks.sumOf { it.durationMs }
                if (totalDuration > 0) {
                    Text(
                        text = "\u2022",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatTotalDuration(totalDuration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons: Play All + Shuffle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPlayAll,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Play All", style = MaterialTheme.typography.labelLarge)
                }

                OutlinedButton(
                    onClick = onShuffle,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Shuffle", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
