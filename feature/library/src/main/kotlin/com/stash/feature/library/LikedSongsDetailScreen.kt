package com.stash.feature.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.media.BulkPlayAction
import com.stash.core.model.Track
import com.stash.core.ui.R
import com.stash.core.ui.components.DetailTrackRow
import com.stash.core.ui.components.SearchFilterBar
import com.stash.core.ui.components.SourceIndicator
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.selection.SelectionAction
import com.stash.core.ui.selection.SelectionScaffoldOverlay
import com.stash.core.ui.selection.rememberSelectionState
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
    onSelectionModeChanged: (Boolean) -> Unit = {},
    viewModel: LikedSongsDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tappedTrackId by viewModel.tappedTrackId.collectAsStateWithLifecycle()
    val bulkPlayInFlight by viewModel.bulkPlayInFlight.collectAsStateWithLifecycle()
    val extendedColors = StashTheme.extendedColors

    // Bottom sheet state for the long-press / ⋮ track menu.
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    var trackToSave by remember { mutableStateOf<Track?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())

    // Multi-select state. `isActive` (non-empty selection) drives the contextual
    // chrome and is signalled out so the host can hide the mini-player (Task 7).
    val selection = rememberSelectionState()
    LaunchedEffect(selection.isActive) { onSelectionModeChanged(selection.isActive) }
    BackHandler(enabled = selection.isActive) { selection.clear() }

    // Batch-flow flags: distinguish the batch Save / Delete surfaces from the
    // single-track paths that share the same sheet/dialog composables.
    var showBatchSave by remember { mutableStateOf(false) }
    var showBatchDelete by remember { mutableStateOf(false) }

    // Snackbar for the batch roll-up summaries.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { snackbarHostState.showSnackbar(it) }
    }

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

            // -- Empty state: no liked songs yet (and not actively searching) --
            state.tracks.isEmpty() && state.searchQuery.isEmpty() -> {
                LikedSongsEmptyState(
                    modifier = Modifier.align(Alignment.Center),
                    onBack = onBack,
                )
            }

            // -- Content: header + optional search bar + track list (or no-results message) --
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Task 7 hides the mini-player while selecting, but the bottom
                    // selection bar then takes its place. Pad enough that the last
                    // row clears it in either state.
                    contentPadding = PaddingValues(bottom = if (selection.isActive) 140.dp else 120.dp),
                ) {
                    // ── Header section ──────────────────────────────────────
                    item(key = "header") {
                        LikedSongsHeader(
                            state = state,
                            bulkPlayInFlight = bulkPlayInFlight,
                            onBack = onBack,
                            onPlayAll = { viewModel.playAll() },
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
                                    text = stringResource(R.string.label_no_matching_songs),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                            onClick = {
                                if (selection.isActive) selection.toggle(track.id)
                                else viewModel.playTrack(track.id)
                            },
                            onLongPress = { if (!selection.isActive) selection.enter(track.id) },
                            isResolving = track.id == tappedTrackId,
                            selectionActive = selection.isActive,
                            selected = selection.isSelected(track.id),
                            onMoreClick = { selectedTrack = track },
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

        // ── Selection chrome (overlaid contextual top + bottom bars) ────────
        val selectedTracks = state.tracks.filter { it.id in selection.selectedIds }
        val selectedIds = selection.selectedIds.toList()

        // Aggregate download state: if every selected track is already on disk,
        // offer Remove download; otherwise offer Download.
        val allDownloaded = selectedTracks.isNotEmpty() && selectedTracks.all { it.isDownloaded }

        // Action order mirrors the reference: Delete stays within the first four
        // (visible) and Play next collapses into the ⋮ overflow.
        val selectionActions = listOf(
            SelectionAction("add_queue", stringResource(R.string.action_add_to_queue), Icons.Default.PlaylistAdd) {
                viewModel.addSelectedToQueue(selectedTracks); selection.clear()
            },
            SelectionAction("add_playlist", stringResource(R.string.selection_add_to_playlist), Icons.Default.PlaylistAddCheck) {
                showBatchSave = true
            },
            if (allDownloaded) {
                SelectionAction("remove_download", stringResource(R.string.selection_remove_download), Icons.Default.DownloadDone) {
                    viewModel.removeDownloadsForSelected(selectedIds); selection.clear()
                }
            } else {
                SelectionAction("download", stringResource(R.string.selection_download), Icons.Default.Download) {
                    viewModel.downloadSelected(selectedIds); selection.clear()
                }
            },
            SelectionAction("delete", stringResource(R.string.selection_delete), Icons.Default.Delete) {
                showBatchDelete = true
            },
            SelectionAction("play_next", stringResource(R.string.action_play_next), Icons.Default.PlaylistPlay) {
                viewModel.playSelectedNext(selectedTracks); selection.clear()
            },
        )

        SelectionScaffoldOverlay(
            selection = selection,
            allIds = state.tracks.map { it.id },
            actions = selectionActions,
        )
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
                onDownload = {
                    viewModel.queueDownload(it.id)
                    selectedTrack = null
                },
                onRemoveDownload = {
                    viewModel.removeDownload(it.id)
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

    // ── Batch Save to Playlist sheet ───────────────────────────────────────
    // Reuses the same composable as the single-track path; the batch flag picks
    // the multi-id callbacks and clears the selection once the save dispatches.
    if (showBatchSave) {
        val batchIds = selection.selectedIds.toList()
        com.stash.core.ui.components.SaveToPlaylistSheet(
            playlists = userPlaylists.map {
                com.stash.core.ui.components.PlaylistInfo(it.id, it.name, it.trackCount)
            },
            onSaveToPlaylist = { playlistId ->
                viewModel.saveSelectedToPlaylist(batchIds, playlistId)
                showBatchSave = false
                selection.clear()
            },
            onCreatePlaylist = { name ->
                viewModel.createPlaylistAndAddTracks(name, batchIds)
                showBatchSave = false
                selection.clear()
            },
            onDismiss = { showBatchSave = false },
        )
    }

    // ── Batch delete confirmation dialog ──────────────────────────────────
    // Liked "delete" = remove from Liked Songs (the unlike path). No "also
    // block" toggle here (unlike the Playlist screen's cascade delete).
    if (showBatchDelete) {
        val batchTracks = state.tracks.filter { it.id in selection.selectedIds }
        val n = batchTracks.size
        AlertDialog(
            onDismissRequest = { showBatchDelete = false },
            title = {
                Text(stringResource(R.string.dialog_title_remove_liked, n))
            },
            text = {
                Text(
                    text = stringResource(R.string.dialog_body_remove_liked),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected(batchTracks)
                        showBatchDelete = false
                        selection.clear()
                    },
                ) {
                    Text(text = stringResource(R.string.action_remove), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) { data -> Snackbar(snackbarData = data) }
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
                contentDescription = stringResource(R.string.cd_back),
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
                text = stringResource(R.string.label_no_liked_songs),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.desc_sync_to_start),
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
    bulkPlayInFlight: BulkPlayAction?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSearch: () -> Unit,
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
                    contentDescription = stringResource(R.string.cd_back),
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
                BulkPlayButtonBox(
                    modifier = Modifier.weight(1f),
                    showProgress = bulkPlayInFlight == BulkPlayAction.PLAY_ALL,
                ) {
                    Button(
                        onClick = onPlayAll,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.action_play_all), style = MaterialTheme.typography.labelLarge)
                    }
                }

                BulkPlayButtonBox(
                    modifier = Modifier.weight(1f),
                    showProgress = bulkPlayInFlight == BulkPlayAction.SHUFFLE_ALL,
                ) {
                    OutlinedButton(
                        onClick = onShuffle,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.action_shuffle), style = MaterialTheme.typography.labelLarge)
                    }
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
                        contentDescription = stringResource(R.string.cd_filter_tracks),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
