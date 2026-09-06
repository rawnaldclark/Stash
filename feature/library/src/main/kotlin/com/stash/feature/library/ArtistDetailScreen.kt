package com.stash.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.stash.core.media.BulkPlayAction
import com.stash.core.model.Track
import com.stash.core.ui.components.DetailTrackRow
import com.stash.core.ui.components.SearchFilterBar
import com.stash.core.ui.components.TrackOptionsSheet
import com.stash.core.ui.selection.SelectionAction
import com.stash.core.ui.selection.SelectionScaffoldOverlay
import com.stash.core.ui.selection.rememberSelectionState
import com.stash.core.ui.theme.StashTheme

/**
 * Artist Detail screen entry point.
 *
 * Displays the artist name as header, track count, Play All / Shuffle / Search buttons,
 * and a scrollable track list. Tapping a track starts playback; long-pressing
 * opens a bottom sheet with queue actions.
 *
 * @param onBack    Callback invoked when the back arrow is tapped.
 * @param viewModel Injected via Hilt; extracts `artistName` from nav args.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ArtistDetailScreen(
    onBack: () -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit = {},
    viewModel: ArtistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tappedTrackId by viewModel.tappedTrackId.collectAsStateWithLifecycle()
    val bulkPlayInFlight by viewModel.bulkPlayInFlight.collectAsStateWithLifecycle()
    val extendedColors = StashTheme.extendedColors

    // Bottom sheet state for the long-press track menu.
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    var trackToShare by remember { mutableStateOf<Track?>(null) }
    var trackToSave by remember { mutableStateOf<Track?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())

    // Multi-select state. `isActive` (non-empty selection) drives the contextual
    // chrome and is signalled out so the host can hide the mini-player (Task 7).
    val selection = rememberSelectionState()
    LaunchedEffect(selection.isActive) { onSelectionModeChanged(selection.isActive) }
    BackHandler(enabled = selection.isActive) { selection.clear() }

    // Batch Save sheet flag: distinguishes the batch Save surface from the
    // single-track path that shares the same SaveToPlaylistSheet composable.
    var showBatchSave by remember { mutableStateOf(false) }

    // Snackbar for the batch download roll-up summaries.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.userMessages.collect { snackbarHostState.showSnackbar(it) }
    }

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
                contentPadding = PaddingValues(bottom = if (selection.isActive) 140.dp else 120.dp),
            ) {
                // ── Header section ──────────────────────────────────────
                item(key = "header") {
                    ArtistDetailHeader(
                        state = state,
                        bulkPlayInFlight = bulkPlayInFlight,
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
                        subtitleOverride = track.album,
                        isResolving = track.id == tappedTrackId,
                        selectionActive = selection.isActive,
                        selected = selection.isSelected(track.id),
                        onMoreClick = { selectedTrack = track },
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

        // ── Selection chrome (overlaid contextual top + bottom bars) ────────
        val selectedTracks = state.tracks.filter { it.id in selection.selectedIds }
        val selectedIds = selection.selectedIds.toList()

        // Aggregate download state: if every selected track is already on disk,
        // offer Remove download; otherwise offer Download.
        val allDownloaded = selectedTracks.isNotEmpty() && selectedTracks.all { it.isDownloaded }

        // Artist detail has exactly four actions (no Delete) — all fit inline,
        // so none collapse into the ⋮ overflow.
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
                    viewModel.deleteTrack(it)
                    selectedTrack = null
                },
                onShare = { trackToShare = it; selectedTrack = null },
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

    // ── Share links sheet ──────────────────────────────────────────────────
    trackToShare?.let { t ->
        com.stash.core.ui.components.ShareTrackSheet(
            title = t.title,
            artist = t.artist,
            spotifyUri = t.spotifyUri,
            youtubeId = t.youtubeId,
            onDismiss = { trackToShare = null },
        )
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

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) { data -> Snackbar(snackbarData = data) }
}

// ── Header composable ───────────────────────────────────────────────────────

/**
 * Displays the artist icon, name, track count, and action buttons.
 *
 * @param onToggleSearch Called when the user taps the search icon button.
 */
@Composable
private fun ArtistDetailHeader(
    state: ArtistDetailUiState,
    bulkPlayInFlight: BulkPlayAction?,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onToggleSearch: () -> Unit,
) {
    val extendedColors = StashTheme.extendedColors

    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Back button row ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 8.dp, top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
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

        Spacer(modifier = Modifier.height(24.dp))

        // ── Artist icon + name ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Circular artist icon — real photo from the artist_images cache
            // (ArtistImageBackfillWorker output), else the gradient placeholder.
            // The painter state is collected in composition so the circle
            // upgrades to the photo the moment the request succeeds and never
            // renders blank when the URL fails (Coil draws nothing outside a
            // Success state, so everything else keeps the placeholder).
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
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
                val photoUrl = state.photoUrl
                if (photoUrl != null) {
                    val painter = rememberAsyncImagePainter(model = photoUrl)
                    val painterState by painter.state.collectAsState()
                    if (painterState is AsyncImagePainter.State.Success) {
                        Image(
                            painter = painter,
                            contentDescription = state.artistName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        ArtistHeaderPlaceholder()
                    }
                } else {
                    ArtistHeaderPlaceholder()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Artist name
            Text(
                text = state.artistName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Track count
            val trackCount = state.tracks.size
            Text(
                text = "$trackCount track${if (trackCount != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Action buttons: Play All + Shuffle + Search ─────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
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

            // Search toggle button — same glass-background style as PlaylistHeader
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

/** The no-photo fallback for the artist header — a soft Person icon. */
@Composable
private fun ArtistHeaderPlaceholder() {
    Icon(
        imageVector = Icons.Default.Person,
        contentDescription = null,
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
    )
}
