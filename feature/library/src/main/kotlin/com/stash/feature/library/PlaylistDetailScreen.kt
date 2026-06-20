package com.stash.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.stash.core.data.mix.MixBuildState
import com.stash.core.media.BulkPlayAction
import com.stash.core.model.PlaylistType
import com.stash.core.model.Track
import com.stash.core.ui.components.DetailTrackRow
import com.stash.core.ui.components.SearchFilterBar
import com.stash.core.ui.components.SourceIndicator
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.components.VerticalScrollbar
import com.stash.core.ui.selection.SelectionAction
import com.stash.core.ui.selection.SelectionScaffoldOverlay
import com.stash.core.ui.selection.rememberSelectionState
import com.stash.core.ui.theme.StashTheme
import com.stash.core.ui.util.formatTotalDuration

/**
 * Playlist Detail screen entry point.
 *
 * Displays the playlist header (artwork, name, source, action buttons)
 * followed by a scrollable track list. Tapping a track starts playback;
 * long-pressing opens a bottom sheet with queue actions.
 *
 * @param onBack   Callback invoked when the back arrow is tapped.
 * @param viewModel Injected via Hilt; extracts `playlistId` from nav args.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit = {},
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tappedTrackId by viewModel.tappedTrackId.collectAsStateWithLifecycle()
    val buildState by viewModel.buildState.collectAsStateWithLifecycle()
    val bulkPlayInFlight by viewModel.bulkPlayInFlight.collectAsStateWithLifecycle()
    val extendedColors = StashTheme.extendedColors

    // Bottom sheet state for the ⋮ track menu.
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    var trackToSave by remember { mutableStateOf<Track?>(null) }
    var trackToDelete by remember { mutableStateOf<Track?>(null) }
    val sheetState = rememberModalBottomSheetState()

    // Multi-select state. `isActive` (non-empty selection) drives the contextual
    // chrome and is signalled out so the host can hide the mini-player (Task 7).
    val selection = rememberSelectionState()
    LaunchedEffect(selection.isActive) { onSelectionModeChanged(selection.isActive) }
    BackHandler(enabled = selection.isActive) { selection.clear() }

    // Batch-flow flags: distinguish the batch Save / Delete surfaces from the
    // single-track paths that share the same sheet/dialog composables.
    var showBatchSave by remember { mutableStateOf(false) }
    var showBatchDelete by remember { mutableStateOf(false) }

    // Snackbar for the cascade-removal summary so users see what
    // happened (e.g. "Kept on disk — also in Liked Songs").
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { snackbarHostState.showSnackbar(it) }
    }
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())

    // Image picker for custom playlist cover art
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val playlist = state.playlist ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            viewModel.setPlaylistImage(playlist.id, uri)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (state.isLoading) {
            // -- Loading indicator centered on screen --
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Task 7 hides the mini-player while selecting, but the bottom
                    // selection bar (~100dp + nav insets) then takes its place. Pad
                    // enough that the last row clears it in either state.
                    contentPadding = PaddingValues(bottom = if (selection.isActive) 140.dp else 120.dp),
                ) {
                    // ── Header section ──────────────────────────────────────
                    item(key = "header") {
                        PlaylistHeader(
                            state = state,
                            bulkPlayInFlight = bulkPlayInFlight,
                            onBack = onBack,
                            onPlayAll = { viewModel.playAll() },
                            onShuffle = { viewModel.shuffleAll() },
                            onToggleSearch = { viewModel.toggleSearch() },
                            onSetImage = {
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
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

                    // ── Custom-mix building / empty state ───────────────────
                    // A freshly created mix populates asynchronously; show a
                    // "Building…" state instead of a blank body, and a clear
                    // "found nothing" state if discovery comes up empty.
                    if (state.tracks.isEmpty() && state.searchQuery.isEmpty()) {
                        when (buildState) {
                            MixBuildState.BUILDING -> item(key = "mix-building") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 56.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        text = "Building your mix…",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Finding fresh tracks from your genres — this can take a moment.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 32.dp),
                                    )
                                }
                            }
                            MixBuildState.EMPTY -> item(key = "mix-empty") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 56.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "Couldn't find tracks",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Try editing this mix with different genres or moods.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 32.dp),
                                    )
                                }
                            }
                            MixBuildState.READY -> Unit
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
                VerticalScrollbar(state = listState, thumbHeightOffset = 20f)
            }
        }

        // ── Selection chrome (overlaid contextual top + bottom bars) ────────
        val selectedTracks = state.tracks.filter { it.id in selection.selectedIds }
        val selectedIds = selection.selectedIds.toList()

        // Aggregate download state: if every selected track is already on disk,
        // offer Remove download; otherwise offer Download.
        val allDownloaded = selectedTracks.isNotEmpty() && selectedTracks.all { it.isDownloaded }

        // Action order: Delete must stay within the first four (visible) and
        // Play next collapses into the ⋮ overflow as the least-used action.
        val selectionActions = listOf(
            SelectionAction("add_queue", "Add to queue", Icons.Default.PlaylistAdd) {
                viewModel.addSelectedToQueue(selectedTracks); selection.clear()
            },
            SelectionAction("add_playlist", "Add to playlist", Icons.Default.PlaylistAddCheck) {
                showBatchSave = true
            },
            if (allDownloaded) {
                SelectionAction("remove_download", "Remove download", Icons.Default.DownloadDone) {
                    viewModel.removeDownloadsForSelected(selectedIds); selection.clear()
                }
            } else {
                SelectionAction("download", "Download", Icons.Default.Download) {
                    viewModel.downloadSelected(selectedIds); selection.clear()
                }
            },
            SelectionAction("delete", "Delete", Icons.Default.Delete) {
                showBatchDelete = true
            },
            SelectionAction("play_next", "Play next", Icons.Default.PlaylistPlay) {
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
                    // Hand off to the confirmation dialog so the user can
                    // choose "delete only" vs "delete and block future
                    // syncs". Closing the sheet here prevents it from
                    // lingering behind the dialog.
                    trackToDelete = it
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

    // ── Delete track confirmation dialog ──────────────────────────────────
    trackToDelete?.let { track ->
        val isDownloadsMix = state.playlist?.type == PlaylistType.DOWNLOADS_MIX
        var alsoBlacklist by remember(track.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = {
                Text(
                    if (isDownloadsMix) "Delete from library?" else "Delete ${track.title}?"
                )
            },
            text = {
                Column {
                    Text(
                        text = if (isDownloadsMix) {
                            "This track will be deleted from your library and the audio file removed from disk."
                        } else {
                            "Removes the song from this playlist. If it's also in Liked Songs or an in-app playlist, the file stays so those lists keep playing."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!isDownloadsMix) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { alsoBlacklist = !alsoBlacklist },
                        ) {
                            Checkbox(
                                checked = alsoBlacklist,
                                onCheckedChange = { alsoBlacklist = it },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Also block this song from future syncs",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "Blocked songs never re-download. Unblock in Settings later.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTrackFromPlaylist(track, alsoBlacklist)
                        trackToDelete = null
                    },
                ) {
                    Text(
                        text = if (isDownloadsMix) "Delete" else if (alsoBlacklist) "Delete & Block" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) { Text("Cancel") }
            },
        )
    }

    // ── Batch delete confirmation dialog ──────────────────────────────────
    // Mirrors the single-track dialog (same wording / "also block" toggle),
    // pluralised across the current selection.
    if (showBatchDelete) {
        val isDownloadsMix = state.playlist?.type == PlaylistType.DOWNLOADS_MIX
        val batchTracks = state.tracks.filter { it.id in selection.selectedIds }
        val n = batchTracks.size
        var alsoBlacklist by remember(showBatchDelete) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showBatchDelete = false },
            title = {
                Text(
                    if (isDownloadsMix) "Delete $n song${if (n != 1) "s" else ""} from library?"
                    else "Remove $n song${if (n != 1) "s" else ""} from this playlist?"
                )
            },
            text = {
                Column {
                    Text(
                        text = if (isDownloadsMix) {
                            "These tracks will be deleted from your library and their audio files removed from disk."
                        } else {
                            "Removes the songs from this playlist. If any are also in Liked Songs or an in-app playlist, the file stays so those lists keep playing."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!isDownloadsMix) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { alsoBlacklist = !alsoBlacklist },
                        ) {
                            Checkbox(
                                checked = alsoBlacklist,
                                onCheckedChange = { alsoBlacklist = it },
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Also block these songs from future syncs",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "Blocked songs never re-download. Unblock in Settings later.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSelected(batchTracks, alsoBlacklist)
                        showBatchDelete = false
                        selection.clear()
                    },
                ) {
                    Text(
                        text = if (isDownloadsMix) "Delete" else if (alsoBlacklist) "Delete & Block" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDelete = false }) { Text("Cancel") }
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

// ── Header composable ───────────────────────────────────────────────────────

/**
 * Displays the playlist artwork, title, metadata subtitle, and action buttons.
 * A gradient scrim overlays the bottom of the artwork for text readability.
 */
@Composable
private fun PlaylistHeader(
    state: PlaylistDetailUiState,
    bulkPlayInFlight: BulkPlayAction?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSearch: () -> Unit,
    onSetImage: () -> Unit,
) {
    val playlist = state.playlist ?: return
    val extendedColors = StashTheme.extendedColors

    Column(modifier = Modifier.fillMaxWidth()) {
        // -- Artwork with back button and gradient scrim --
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            // Album art or gradient placeholder
            if (playlist.artUrl != null) {
                AsyncImage(
                    model = playlist.artUrl,
                    contentDescription = "${playlist.name} artwork",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                // Gradient placeholder with music icon
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
                        imageVector = Icons.Default.MusicNote,
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

        // -- Playlist info below artwork --
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            // Playlist name
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Source indicator + track count + total duration
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceIndicator(source = playlist.source, size = 8.dp, showLabel = true)

                Text(
                    text = "\u2022",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

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
                        Text(text = "Play All", style = MaterialTheme.typography.labelLarge)
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
                        Text(text = "Shuffle", style = MaterialTheme.typography.labelLarge)
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
                        contentDescription = "Filter tracks",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (playlist.type == PlaylistType.CUSTOM) {
                    IconButton(
                        onClick = onSetImage,
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = extendedColors.glassBackground,
                                shape = RoundedCornerShape(12.dp),
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Set cover image",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

