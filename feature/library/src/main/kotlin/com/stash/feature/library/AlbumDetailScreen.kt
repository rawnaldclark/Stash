package com.stash.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
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
import com.stash.core.model.Track
import com.stash.core.ui.components.DetailTrackRow
import com.stash.core.ui.components.SearchFilterBar
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.theme.StashTheme
import com.stash.core.ui.util.formatTotalDuration

/**
 * Album Detail screen entry point.
 *
 * Displays album artwork (from first track), album name, artist name,
 * track count, Play All / Shuffle buttons, and a scrollable track list.
 * Tapping a track starts playback; long-pressing opens a bottom sheet
 * with queue actions.
 *
 * @param onBack    Callback invoked when the back arrow is tapped.
 * @param viewModel Injected via Hilt; extracts `albumName` and `artistName` from nav args.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailScreen(
    onBack: () -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel(),
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
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                // -- Header section --
                item(key = "header") {
                    AlbumDetailHeader(
                        state = state,
                        onBack = onBack,
                        onPlayAll = {
                            val firstTrack = state.tracks.firstOrNull { it.filePath != null }
                            if (firstTrack != null) viewModel.playTrack(firstTrack.id)
                        },
                        onShuffle = { viewModel.shuffleAll() },
                        onToggleSearch = { viewModel.toggleSearch() },
                    )
                }

                // ── Search filter bar ───────────────────────────────────
                if (state.showSearch) {
                    item(key = "search") {
                        SearchFilterBar(
                            query = state.searchQuery,
                            onQueryChanged = viewModel::onSearchQueryChanged,
                            onClear = viewModel::clearSearch,
                        )
                    }
                }

                // ── Empty search results ───────────────────────────────
                if (state.tracks.isEmpty() && state.searchQuery.isNotEmpty()) {
                    item(key = "no-results") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No matching songs",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // -- Track list --
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
                        showArtist = false,
                    )

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

    // -- Track options bottom sheet --
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

    // -- Save to Playlist sheet --
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

// -- Header composable --

/**
 * Displays album artwork, name, artist, track count, and action buttons.
 * Uses the first track's artwork as the album cover.
 */
@Composable
private fun AlbumDetailHeader(
    state: AlbumDetailUiState,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSearch: () -> Unit,
) {
    val extendedColors = StashTheme.extendedColors

    // Try to find album art from the first track that has one.
    val albumArt = state.tracks.firstNotNullOfOrNull { it.albumArtPath ?: it.albumArtUrl }

    Column(modifier = Modifier.fillMaxWidth()) {
        // -- Artwork with back button and gradient scrim --
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            if (albumArt != null) {
                AsyncImage(
                    model = albumArt,
                    contentDescription = "${state.albumName} artwork",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // Gradient placeholder with album icon
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                        imageVector = Icons.Default.Album,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                    )
                }
            }

            // Gradient scrim at the bottom for text readability
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )

            // Back button
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

        // -- Album info below artwork --
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            // Album name
            Text(
                text = state.albumName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Artist name
            Text(
                text = state.artistName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Track count + total duration
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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

                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onToggleSearch,
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = extendedColors.glassBackground,
                            shape = RoundedCornerShape(12.dp),
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Filter tracks",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
