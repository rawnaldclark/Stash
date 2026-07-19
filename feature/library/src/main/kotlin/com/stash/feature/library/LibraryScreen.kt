package com.stash.feature.library

import android.net.Uri
import kotlin.math.absoluteValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.core.model.Playlist
import com.stash.core.model.PlaylistType
import coil3.compose.AsyncImage
import com.stash.core.model.Track
import com.stash.core.ui.components.GlassCard
import com.stash.core.ui.components.SourceIndicator
import com.stash.core.ui.components.TrackListItem
import com.stash.core.ui.components.VerticalScrollbar
import com.stash.core.ui.selection.SelectionAction
import com.stash.core.ui.selection.SelectionScaffoldOverlay
import com.stash.core.ui.selection.SelectionState
import com.stash.core.ui.theme.StashTheme

/**
 * Library screen entry point. Injects the [LibraryViewModel] via Hilt
 * and delegates rendering to the stateless [LibraryContent] composable.
 *
 * Multi-select (Task 11) applies ONLY to the Tracks tab. The [selection]
 * state is hoisted here so the contextual chrome ([SelectionScaffoldOverlay])
 * and the batch Save/Delete surfaces can sit in this screen's root Box. The
 * selection is cleared whenever the active tab changes so it can never strand
 * across tabs (or hide the nav bar while a non-Tracks tab is showing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onNavigateToPlaylist: (Long) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToAlbum: (String, String) -> Unit = { _, _ -> },
    onSelectionModeChanged: (Boolean) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by viewModel.localImportState.collectAsStateWithLifecycle()
    val userPlaylists by viewModel.userPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())
    val likedTracks by viewModel.likedTracks.collectAsStateWithLifecycle()
    val likedFilter by viewModel.likedFilter.collectAsStateWithLifecycle()
    val likedSources by viewModel.likedSources.collectAsStateWithLifecycle()

    // Multi-select state — Tracks tab only. `isActive` signals out so the host
    // can hide the mini-player (Task 7), and the selection is force-cleared on
    // every tab change so it can't leak onto Playlists/Artists/Albums.
    val selection = com.stash.core.ui.selection.rememberSelectionState()
    androidx.compose.runtime.LaunchedEffect(selection.isActive) { onSelectionModeChanged(selection.isActive) }
    androidx.compose.runtime.LaunchedEffect(state.activeTab) { selection.clear() }
    androidx.activity.compose.BackHandler(enabled = selection.isActive) { selection.clear() }

    // Batch-flow flags for the Save / Delete surfaces.
    var showBatchSave by remember { mutableStateOf(false) }
    var showBatchDelete by remember { mutableStateOf(false) }

    // Snackbar for the batch roll-up summaries.
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.userMessages.collect { snackbarHostState.showSnackbar(it) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LibraryContent(
            state = state,
            importState = importState,
            onShuffleLibrary = viewModel::shuffleLibrary,
            onTabSelected = viewModel::selectTab,
            onSearchQueryChanged = viewModel::setSearchQuery,
            onSortOrderChanged = viewModel::setSortOrder,
            onSourceFilterChanged = viewModel::setSourceFilter,
            onTrackClick = { track -> viewModel.playTrack(track, state.tracks) },
            onPlayNext = viewModel::playNext,
            onAddToQueue = viewModel::addToQueue,
            onDeleteTrack = viewModel::deleteTrack,
            onPlayPlaylist = { playlist -> onNavigateToPlaylist(playlist.id) },
            onAddPlaylistToQueue = viewModel::addPlaylistToQueue,
            onRemovePlaylist = viewModel::removePlaylist,
            onDeletePlaylist = viewModel::deletePlaylist,
            onSetPlaylistImage = viewModel::setPlaylistImage,
            onRemovePlaylistImage = viewModel::removePlaylistImage,
            onPlayArtist = onNavigateToArtist,
            onAddArtistToQueue = viewModel::addArtistToQueue,
            onDeleteArtist = viewModel::deleteArtist,
            onPlayAlbum = onNavigateToAlbum,
            onAddAlbumToQueue = viewModel::addAlbumToQueue,
            onStartImport = viewModel::startLocalImport,
            onCancelImport = viewModel::cancelLocalImport,
            onDismissImport = viewModel::dismissLocalImport,
            selection = selection,
            likedTracks = likedTracks,
            likedFilter = likedFilter,
            likedSources = likedSources,
            onSelectLikedSource = viewModel::setLikedFilter,
            onPlayLikedTrack = viewModel::playLiked,
        )

        // ── Selection chrome — only meaningful on the Tracks tab. Selection
        // can only be entered from a TrackListItem (Tracks tab), and we clear
        // on tab change, so guarding the overlay on activeTab is belt-and-braces.
        val selectedTracks = state.tracks.filter { it.id in selection.selectedIds }
        val selectedIds = selection.selectedIds.toList()
        val allDownloaded = selectedTracks.isNotEmpty() && selectedTracks.all { it.isDownloaded }

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

        if (state.activeTab == LibraryTab.TRACKS) {
            SelectionScaffoldOverlay(
                selection = selection,
                allIds = state.tracks.map { it.id },
                actions = selectionActions,
            )
        }

        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        ) { data -> androidx.compose.material3.Snackbar(snackbarData = data) }
    }

    // ── Batch Save to Playlist sheet (its own ModalBottomSheet) ────────────
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
    // Mirrors the single-track Library delete dialog (same "also block" toggle
    // and "Delete & Block" wording), pluralised across the current selection.
    if (showBatchDelete) {
        val batchTracks = state.tracks.filter { it.id in selection.selectedIds }
        val n = batchTracks.size
        var alsoBlacklist by remember(showBatchDelete) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showBatchDelete = false },
            title = { Text("Delete $n song${if (n != 1) "s" else ""}?") },
            text = {
                Column {
                    Text(
                        "${n} song${if (n != 1) "s" else ""} will be removed from your " +
                            "library and deleted from disk.",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { alsoBlacklist = !alsoBlacklist },
                    ) {
                        androidx.compose.material3.Checkbox(
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
                                text = "Blocked songs never re-download. Unblock them in Settings later.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                        text = if (alsoBlacklist) "Delete & Block" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDelete = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Stateless content composable ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(
    state: LibraryUiState,
    importState: com.stash.data.download.files.LocalImportState,
    onShuffleLibrary: () -> Unit,
    onTabSelected: (LibraryTab) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSortOrderChanged: (SortOrder) -> Unit,
    onSourceFilterChanged: (SourceFilter) -> Unit,
    onTrackClick: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onDeleteTrack: (Track, Boolean) -> Unit,
    onPlayPlaylist: (Playlist) -> Unit,
    onAddPlaylistToQueue: (Playlist) -> Unit,
    onRemovePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist, Boolean) -> Unit,
    onSetPlaylistImage: (Long, Uri) -> Unit,
    onRemovePlaylistImage: (Long) -> Unit,
    onPlayArtist: (String) -> Unit,
    onAddArtistToQueue: (String) -> Unit,
    onDeleteArtist: (String) -> Unit,
    onPlayAlbum: (String, String) -> Unit,
    onAddAlbumToQueue: (String, String) -> Unit,
    onStartImport: (List<Uri>) -> Unit,
    onCancelImport: () -> Unit,
    onDismissImport: () -> Unit,
    selection: SelectionState,
    likedTracks: List<Track>,
    likedFilter: LikedFilter,
    likedSources: Set<LikedFilter>,
    onSelectLikedSource: (LikedFilter) -> Unit,
    onPlayLikedTrack: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 16.dp),
    ) {
        // -- Heading + Import action --
        // SAF audio picker launched from the "Add tracks" icon button in
        // the heading row. Returns a List<Uri> that we hand directly to
        // the coordinator; no persistable permission needed because we
        // copy the bytes immediately at import time.
        val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri>? ->
            if (!uris.isNullOrEmpty()) onStartImport(uris)
        }
        var searchOpen by remember { mutableStateOf(false) }
        var sortFilterOpen by remember { mutableStateOf(false) }

        // Recent-downloads rail — the scrolling leading content of the Songs
        // landing (scrolls away while the compact header + category chips stay
        // pinned). The old purple shuffle hero is gone — it duplicated the
        // header's shuffle action.
        val libraryHeader: @Composable () -> Unit = {
            // Songs landing only — noise above the other grids.
            if (state.activeTab == LibraryTab.TRACKS) {
                Column {
                    if (state.recentlyAdded.isNotEmpty()) {
                        RecentlyDownloadedRail(
                            tracks = state.recentlyAdded.take(12),
                            onTrackClick = onTrackClick,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // Compact header: title + Import (+), search, and sort/filter icons —
        // replaces the old heading row + the six stacked control bars.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            // Shuffle stays in the always-visible header so it's reachable even
            // once the hero has scrolled away.
            IconButton(onClick = onShuffleLibrary) {
                Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle library", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { importPicker.launch(arrayOf("audio/*")) }) {
                Icon(Icons.Filled.Add, contentDescription = "Import tracks")
            }
            IconButton(onClick = { searchOpen = !searchOpen }) {
                Icon(Icons.Filled.Search, contentDescription = "Search library")
            }
            IconButton(onClick = { sortFilterOpen = true }) {
                Icon(Icons.Filled.Tune, contentDescription = "Sort and filter")
            }
        }

        // -- Import progress strip (only when Running / Done / Error) --
        LocalImportStrip(
            state = importState,
            onCancel = onCancelImport,
            onDismiss = onDismissImport,
        )

        // -- Inline search (toggled by the header 🔍) — no permanent bar --
        if (searchOpen) {
            GlassSearchBar(
                query = state.searchQuery,
                onQueryChange = onSearchQueryChanged,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        // -- Category chips (pinned): Songs / Playlists / Artists / Albums --
        val chipTabs = listOf(
            "Songs" to LibraryTab.TRACKS,
            "Liked" to LibraryTab.LIKED,
            "Playlists" to LibraryTab.PLAYLISTS,
            "Artists" to LibraryTab.ARTISTS,
            "Albums" to LibraryTab.ALBUMS,
        )
        com.stash.core.ui.components.CrispChipRow(
            chips = chipTabs.map { it.first },
            selected = chipTabs.first { it.second == state.activeTab }.first,
            onSelect = { label -> onTabSelected(chipTabs.first { it.first == label }.second) },
            modifier = Modifier.padding(vertical = 4.dp),
        )

        Spacer(modifier = Modifier.height(4.dp))

        // -- Sort & filter sheet (toggled by the header ⇅) --
        if (sortFilterOpen) {
            LibrarySortFilterSheet(
                sortOrder = state.sortOrder,
                sourceFilter = state.sourceFilter,
                showDuration = state.activeTab == LibraryTab.TRACKS,
                onSortSelected = onSortOrderChanged,
                onFilterSelected = onSourceFilterChanged,
                onDismiss = { sortFilterOpen = false },
            )
        }

        // -- Content area --
        val anyServiceConnected = state.spotifyConnected || state.youTubeConnected
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            when (state.activeTab) {
                LibraryTab.PLAYLISTS -> PlaylistsGrid(
                    playlists = state.playlists,
                    anyServiceConnected = anyServiceConnected,
                    onPlayPlaylist = onPlayPlaylist,
                    onAddPlaylistToQueue = onAddPlaylistToQueue,
                    onRemovePlaylist = onRemovePlaylist,
                    onDeletePlaylist = onDeletePlaylist,
                    onSetPlaylistImage = onSetPlaylistImage,
                    onRemovePlaylistImage = onRemovePlaylistImage,
                    header = libraryHeader,
                )
                LibraryTab.TRACKS -> TracksTab(
                    tracks = state.tracks,
                    currentlyPlayingTrackId = state.currentlyPlayingTrackId,
                    onTrackClick = onTrackClick,
                    onPlayNext = onPlayNext,
                    onAddToQueue = onAddToQueue,
                    onDeleteTrack = onDeleteTrack,
                    anyServiceConnected = anyServiceConnected,
                    selection = selection,
                    header = libraryHeader,
                )
                LibraryTab.LIKED -> LikedTab(
                    tracks = likedTracks,
                    filter = likedFilter,
                    sources = likedSources,
                    currentlyPlayingTrackId = state.currentlyPlayingTrackId,
                    onSelectSource = onSelectLikedSource,
                    onTrackClick = onPlayLikedTrack,
                )
                LibraryTab.ARTISTS -> ArtistsGrid(
                    artists = state.artists,
                    singleTrackArtists = state.singleTrackArtists,
                    anyServiceConnected = anyServiceConnected,
                    onPlayArtist = onPlayArtist,
                    onAddArtistToQueue = onAddArtistToQueue,
                    onDeleteArtist = onDeleteArtist,
                    header = libraryHeader,
                )
                LibraryTab.ALBUMS -> AlbumsGrid(
                    albums = state.albums,
                    singleTrackAlbums = state.singleTrackAlbums,
                    anyServiceConnected = anyServiceConnected,
                    onPlayAlbum = onPlayAlbum,
                    onAddAlbumToQueue = onAddAlbumToQueue,
                    header = libraryHeader,
                )
            }
        }
    }
}

// ── Recently downloaded rail ─────────────────────────────────────────────────

@Composable
private fun RecentlyDownloadedRail(
    tracks: List<Track>,
    onTrackClick: (Track) -> Unit,
) {
    Column {
        Text(
            text = "RECENTLY DOWNLOADED",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(tracks, key = { it.id }) { track ->
                com.stash.core.ui.components.AlbumSquareCard(
                    title = track.title,
                    artist = track.artist,
                    thumbnailUrl = track.albumArtUrl,
                    year = null,
                    isLossless = track.fileFormat.equals("flac", ignoreCase = true),
                    onClick = { onTrackClick(track) },
                )
            }
        }
    }
}

// ── Liked subcategory (browse + sift likes by origin) ───────────────────────

@Composable
private fun LikedTab(
    tracks: List<Track>,
    filter: LikedFilter,
    sources: Set<LikedFilter>,
    currentlyPlayingTrackId: Long?,
    onSelectSource: (LikedFilter) -> Unit,
    onTrackClick: (Track) -> Unit,
) {
    // Sift chips: "All" plus only the origins that actually have likes.
    val sift = buildList {
        add("All" to LikedFilter.ALL)
        if (LikedFilter.STASH in sources) add("Stash" to LikedFilter.STASH)
        if (LikedFilter.SPOTIFY in sources) add("Spotify" to LikedFilter.SPOTIFY)
        if (LikedFilter.YOUTUBE in sources) add("YouTube" to LikedFilter.YOUTUBE)
    }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState) {
            // Only offer the sift when there's more than one origin to pick between.
            if (sift.size > 2) {
                item {
                    com.stash.core.ui.components.CrispChipRow(
                        chips = sift.map { it.first },
                        selected = sift.firstOrNull { it.second == filter }?.first ?: "All",
                        onSelect = { label -> onSelectSource(sift.first { it.first == label }.second) },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            if (tracks.isEmpty()) {
                item { EmptyTabMessage("No liked songs yet — tap the heart on a track to like it") }
            }
            items(tracks, key = { it.id }) { track ->
                TrackListItem(
                    track = track,
                    onClick = { onTrackClick(track) },
                    isPlaying = track.id == currentlyPlayingTrackId,
                    onLongPress = {},
                    selectionActive = false,
                    selected = false,
                    onMoreClick = {},
                )
            }
        }
        VerticalScrollbar(state = listState)
    }
}

// ── Search bar ───────────────────────────────────────────────────────────────

@Composable
private fun GlassSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extendedColors = StashTheme.extendedColors

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = extendedColors.glassBackground,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, extendedColors.glassBorder),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    "Search library...",
                    color = extendedColors.textTertiary,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = extendedColors.textTertiary,
                )
            },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Local import progress strip ─────────────────────────────────────────────

/**
 * Compact banner that appears below the "Library" heading while
 * [LocalImportState] is non-Idle. Shows progress during a batch import,
 * a summary after Done, or an error + dismiss after Error. Hides
 * completely when [state] is [LocalImportState.Idle].
 */
@Composable
private fun LocalImportStrip(
    state: com.stash.data.download.files.LocalImportState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        com.stash.data.download.files.LocalImportState.Idle -> Unit
        is com.stash.data.download.files.LocalImportState.Running -> {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Importing ${state.current} of ${state.total}\u2026",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    androidx.compose.material3.TextButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = {
                        if (state.total == 0) 0f
                        else state.current.toFloat() / state.total.toFloat()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        is com.stash.data.download.files.LocalImportState.Done -> {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = buildString {
                        append("Imported ${state.imported} track")
                        if (state.imported != 1) append("s")
                        if (state.failed > 0) append(" \u2022 ${state.failed} failed")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
        is com.stash.data.download.files.LocalImportState.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Import failed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

// ── Playlists tab (2-column grid) ────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistsGrid(
    playlists: List<Playlist>,
    anyServiceConnected: Boolean,
    onPlayPlaylist: (Playlist) -> Unit,
    onAddPlaylistToQueue: (Playlist) -> Unit,
    onRemovePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist, Boolean) -> Unit,
    onSetPlaylistImage: (Long, Uri) -> Unit,
    onRemovePlaylistImage: (Long) -> Unit,
    header: @Composable () -> Unit = {},
) {
    // Playlist selected for the context-menu bottom sheet.
    var selectedPlaylist by remember { mutableStateOf<Playlist?>(null) }
    // Playlist pending delete confirmation.
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    // Playlist awaiting image picker result.
    var playlistForImagePick by remember { mutableStateOf<Playlist?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && playlistForImagePick != null) {
            onSetPlaylistImage(playlistForImagePick!!.id, uri)
        }
        playlistForImagePick = null
    }

    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Full-width leading header (Shuffle hero + recent rail, Songs-tab only).
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                header()
            }
            if (playlists.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    EmptyTabMessage(
                        if (anyServiceConnected) "Sync your playlists to see them here"
                        else "Connect a service in Settings to see your playlists",
                    )
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                if (playlist.artUrl != null) {
                    // Playlist with artwork: image background + dark overlay
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(
                                onClick = { onPlayPlaylist(playlist) },
                                onLongClick = { selectedPlaylist = playlist },
                            ),
                    ) {
                        AsyncImage(
                            model = playlist.artUrl,
                            contentDescription = "${playlist.name} artwork",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                        // Dark scrim overlay for text legibility
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.7f),
                                        ),
                                        startY = 40f,
                                    ),
                                ),
                        )
                        // Text pinned to bottom
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SourceIndicator(source = playlist.source)
                                Text(
                                    text = "${playlist.trackCount} tracks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                )
                            }
                        }
                    }
                } else {
                    // No artwork: keep the original GlassCard look
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onPlayPlaylist(playlist) },
                                onLongClick = { selectedPlaylist = playlist },
                            ),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SourceIndicator(source = playlist.source)
                                Text(
                                    text = "${playlist.trackCount} tracks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        VerticalScrollbar(state = gridState)
    }

    // ── Context-menu bottom sheet ───────────────────────────────────────
    selectedPlaylist?.let { playlist ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedPlaylist = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // Header: playlist name + track count
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${playlist.trackCount} tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            BottomSheetActionRow(
                icon = Icons.Default.PlayArrow,
                label = "Play All",
                onClick = {
                    onPlayPlaylist(playlist)
                    selectedPlaylist = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.PlaylistAdd,
                label = "Add to Queue",
                onClick = {
                    onAddPlaylistToQueue(playlist)
                    selectedPlaylist = null
                },
            )
            // Image options — only for custom playlists
            if (playlist.type == PlaylistType.CUSTOM) {
                BottomSheetActionRow(
                    icon = Icons.Default.Image,
                    label = if (playlist.artUrl != null) "Change Image" else "Add Image",
                    onClick = {
                        playlistForImagePick = playlist
                        selectedPlaylist = null
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                )

                // Remove Image — only shown when a custom image is set
                if (playlist.artUrl != null) {
                    BottomSheetActionRow(
                        icon = Icons.Default.ImageNotSupported,
                        label = "Remove Image",
                        onClick = {
                            onRemovePlaylistImage(playlist.id)
                            selectedPlaylist = null
                        },
                    )
                }
            }

            BottomSheetActionRow(
                icon = Icons.Default.RemoveCircleOutline,
                label = "Remove Playlist",
                onClick = {
                    onRemovePlaylist(playlist)
                    selectedPlaylist = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.Delete,
                label = "Delete Playlist & Songs",
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    playlistToDelete = playlist
                    selectedPlaylist = null
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Delete confirmation dialog ──────────────────────────────────────
    playlistToDelete?.let { playlist ->
        var alsoBlacklist by remember(playlist.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Delete \"${playlist.name}\"?") },
            text = {
                Column {
                    Text(
                        "\"${playlist.name}\" and all its tracks will be removed from " +
                            "your library and deleted from disk. This cannot be undone.",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { alsoBlacklist = !alsoBlacklist },
                    ) {
                        androidx.compose.material3.Checkbox(
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
                                text = "Blocked songs never re-download. Unblock them in Settings later.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePlaylist(playlist, alsoBlacklist)
                        playlistToDelete = null
                    },
                ) {
                    Text(
                        text = if (alsoBlacklist) "Delete & Block" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Tracks tab (lazy column of TrackListItems) ──────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TracksTab(
    tracks: List<Track>,
    currentlyPlayingTrackId: Long?,
    onTrackClick: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onDeleteTrack: (Track, Boolean) -> Unit,
    anyServiceConnected: Boolean,
    selection: SelectionState,
    header: @Composable () -> Unit = {},
) {
    if (tracks.isEmpty()) {
        Column {
            header()
            EmptyTabMessage(
                if (anyServiceConnected) "Sync your library to see tracks here"
                else "Connect a service in Settings to see your tracks",
            )
        }
        return
    }

    // Track selected for the context-menu bottom sheet (opened via the ⋮ now;
    // long-press enters multi-select instead).
    var selectedTrack by remember { mutableStateOf<Track?>(null) }
    // Track whose share sheet is open (fork issue ParaliyzedEvo/Stash#40).
    var trackToShare by remember { mutableStateOf<Track?>(null) }
    trackToShare?.let { t ->
        com.stash.core.ui.components.ShareTrackSheet(
            title = t.title,
            artist = t.artist,
            spotifyUri = t.spotifyUri,
            youtubeId = t.youtubeId,
            onDismiss = { trackToShare = null },
        )
    }
    // Track pending delete confirmation.
    var trackToDelete by remember { mutableStateOf<Track?>(null) }

    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // While selecting, the mini-player hides (Task 7) but the bottom
            // selection bar takes its place — pad enough that the last row clears
            // it in either state.
            contentPadding = PaddingValues(bottom = if (selection.isActive) 140.dp else 0.dp),
        ) {
            item { header() }
            items(tracks, key = { it.id }) { track ->
                TrackListItem(
                    track = track,
                    onClick = {
                        if (selection.isActive) selection.toggle(track.id)
                        else onTrackClick(track)
                    },
                    isPlaying = track.id == currentlyPlayingTrackId,
                    onLongPress = { if (!selection.isActive) selection.enter(track.id) },
                    selectionActive = selection.isActive,
                    selected = selection.isSelected(track.id),
                    onMoreClick = { selectedTrack = track },
                )
            }
        }
        VerticalScrollbar(state = listState)
    }

    // ── Context-menu bottom sheet ───────────────────────────────────────
    selectedTrack?.let { track ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedTrack = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // Header: track title + artist
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    com.stash.core.ui.components.FlacBadge(
                        fileFormat = track.fileFormat,
                        bitsPerSample = track.bitsPerSample,
                        sampleRateHz = track.sampleRateHz,
                        size = 16.dp,
                    )
                }
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Action rows
            BottomSheetActionRow(
                icon = Icons.Default.PlaylistPlay,
                label = "Play Next",
                onClick = {
                    onPlayNext(track)
                    selectedTrack = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.PlaylistAdd,
                label = "Add to Queue",
                onClick = {
                    onAddToQueue(track)
                    selectedTrack = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.Share,
                label = "Share",
                onClick = {
                    trackToShare = track
                    selectedTrack = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.Delete,
                label = "Delete",
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    trackToDelete = track
                    selectedTrack = null
                },
            )

            // Bottom padding for gesture navigation inset
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // ── Delete confirmation dialog ──────────────────────────────────────
    trackToDelete?.let { track ->
        var alsoBlacklist by remember(track.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            title = { Text("Delete \"${track.title}\"?") },
            text = {
                Column {
                    Text(
                        "\"${track.title}\" by ${track.artist} will be removed from " +
                            "your library and deleted from disk.",
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { alsoBlacklist = !alsoBlacklist },
                    ) {
                        androidx.compose.material3.Checkbox(
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
                                text = "Blocked songs never re-download. Unblock them in Settings later.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTrack(track, alsoBlacklist)
                        trackToDelete = null
                    },
                ) {
                    Text(
                        text = if (alsoBlacklist) "Delete & Block" else "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * A single action row inside the track context-menu bottom sheet.
 *
 * @param icon  Leading icon for the action.
 * @param label Human-readable label.
 * @param tint  Icon and label color. Defaults to [MaterialTheme.colorScheme.onSurface].
 * @param onClick Callback when the row is tapped.
 */
@Composable
private fun BottomSheetActionRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

// ── Artists tab (2-column grid) ──────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ArtistsGrid(
    artists: List<ArtistInfo>,
    singleTrackArtists: List<ArtistInfo>,
    anyServiceConnected: Boolean,
    onPlayArtist: (String) -> Unit,
    onAddArtistToQueue: (String) -> Unit,
    onDeleteArtist: (String) -> Unit,
    header: @Composable () -> Unit = {},
) {
    if (artists.isEmpty() && singleTrackArtists.isEmpty()) {
        Column {
            header()
            EmptyTabMessage(
                if (anyServiceConnected) "Sync your library to see artists here"
                else "Connect a service in Settings to see your artists",
            )
        }
        return
    }

    var artistToDelete by remember { mutableStateOf<ArtistInfo?>(null) }
    var selectedArtist by remember { mutableStateOf<ArtistInfo?>(null) }
    var showSingleTrack by remember { mutableStateOf(false) }

    val displayList = if (showSingleTrack) artists + singleTrackArtists else artists

    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(displayList, key = { it.name }) { artist ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .combinedClickable(
                            onClick = { onPlayArtist(artist.name) },
                            onLongClick = { selectedArtist = artist },
                        ),
                ) {
                    // Album art proxy or gradient circle fallback
                    if (artist.artUrl != null) {
                        coil3.compose.AsyncImage(
                            model = artist.artUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        val gradientColors = remember(artist.name) {
                            artistGradient(artist.name)
                        }
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(gradientColors)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = artist.name.firstOrNull()
                                    ?.uppercaseChar()?.toString() ?: "?",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = "${artist.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Collapsible section for single-track artists
            if (singleTrackArtists.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { showSingleTrack = !showSingleTrack },
                        color = StashTheme.extendedColors.glassBackground,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, StashTheme.extendedColors.glassBorder,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (showSingleTrack) "Hide single-track artists"
                                else "${singleTrackArtists.size} artists with 1 track",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (showSingleTrack) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        VerticalScrollbar(state = gridState)
    }

    // ── Context-menu bottom sheet ───────────────────────────────────────
    selectedArtist?.let { artist ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedArtist = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // Header: artist name + track count
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${artist.trackCount} tracks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            BottomSheetActionRow(
                icon = Icons.Default.PlayArrow,
                label = "Play All by Artist",
                onClick = {
                    onPlayArtist(artist.name)
                    selectedArtist = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.PlaylistAdd,
                label = "Add to Queue",
                onClick = {
                    onAddArtistToQueue(artist.name)
                    selectedArtist = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.Delete,
                label = "Delete All by Artist",
                tint = MaterialTheme.colorScheme.error,
                onClick = {
                    artistToDelete = artist
                    selectedArtist = null
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Delete artist confirmation
    artistToDelete?.let { artist ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { artistToDelete = null },
            title = { Text("Delete all by ${artist.name}?") },
            text = { Text("This will delete all ${artist.trackCount} downloaded songs by this artist from your device.") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        onDeleteArtist(artist.name)
                        artistToDelete = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { artistToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Albums tab (2-column grid) ───────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsGrid(
    albums: List<AlbumInfo>,
    singleTrackAlbums: List<AlbumInfo>,
    anyServiceConnected: Boolean,
    onPlayAlbum: (String, String) -> Unit,
    onAddAlbumToQueue: (String, String) -> Unit,
    header: @Composable () -> Unit = {},
) {
    if (albums.isEmpty() && singleTrackAlbums.isEmpty()) {
        Column {
            header()
            EmptyTabMessage(
                if (anyServiceConnected) "Sync your library to see albums here"
                else "Connect a service in Settings to see your albums",
            )
        }
        return
    }

    var selectedAlbum by remember { mutableStateOf<AlbumInfo?>(null) }
    var showSingleTrack by remember { mutableStateOf(false) }

    val displayList = if (showSingleTrack) albums + singleTrackAlbums else albums

    val gridState = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(displayList, key = { "${it.name}|${it.artist}" }) { album ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .combinedClickable(
                            onClick = { onPlayAlbum(album.name, album.artist) },
                            onLongClick = { selectedAlbum = album },
                        ),
                ) {
                    // Album art: try local path, then remote URL, then fallback icon
                    val artModel = album.artPath ?: album.artUrl
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(StashTheme.extendedColors.elevatedSurface),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (artModel != null) {
                            AsyncImage(
                                model = artModel,
                                contentDescription = "${album.name} album art",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Album,
                                contentDescription = null,
                                tint = StashTheme.extendedColors.textTertiary,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = album.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = "· ${album.trackCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Collapsible section for single-track albums
            if (singleTrackAlbums.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable { showSingleTrack = !showSingleTrack },
                        color = StashTheme.extendedColors.glassBackground,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, StashTheme.extendedColors.glassBorder,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (showSingleTrack) "Hide single-track albums"
                                else "${singleTrackAlbums.size} albums with 1 track",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = if (showSingleTrack) Icons.Default.ExpandLess
                                else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        VerticalScrollbar(state = gridState)
    }

    // ── Context-menu bottom sheet ───────────────────────────────────────
    selectedAlbum?.let { album ->
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { selectedAlbum = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            // Header: album name + artist
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
            ) {
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = album.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            BottomSheetActionRow(
                icon = Icons.Default.PlayArrow,
                label = "Play Album",
                onClick = {
                    onPlayAlbum(album.name, album.artist)
                    selectedAlbum = null
                },
            )
            BottomSheetActionRow(
                icon = Icons.Default.PlaylistAdd,
                label = "Add to Queue",
                onClick = {
                    onAddAlbumToQueue(album.name, album.artist)
                    selectedAlbum = null
                },
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ── Artist gradient helper ───────────────────────────────────────────────────

/**
 * Pre-defined gradient pairs used for artist avatar backgrounds.
 * A deterministic pair is picked based on the hash of the artist name,
 * giving each artist a unique-ish but consistent color.
 */
private val artistGradientPalette = listOf(
    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),  // Indigo -> Violet
    listOf(Color(0xFFEC4899), Color(0xFFF43F5E)),  // Pink -> Rose
    listOf(Color(0xFF14B8A6), Color(0xFF06B6D4)),  // Teal -> Cyan
    listOf(Color(0xFFF97316), Color(0xFFEAB308)),  // Orange -> Yellow
    listOf(Color(0xFF3B82F6), Color(0xFF6366F1)),  // Blue -> Indigo
    listOf(Color(0xFF10B981), Color(0xFF14B8A6)),  // Emerald -> Teal
    listOf(Color(0xFFE11D48), Color(0xFFDB2777)),  // Rose -> Pink
    listOf(Color(0xFF8B5CF6), Color(0xFFA855F7)),  // Violet -> Purple
)

/**
 * Returns a gradient color list for a given artist name.
 * The selection is deterministic so the same artist always gets the same gradient.
 */
private fun artistGradient(name: String): List<Color> {
    val index = name.hashCode().absoluteValue % artistGradientPalette.size
    return artistGradientPalette[index]
}

// ── Empty state placeholder ──────────────────────────────────────────────────

@Composable
private fun EmptyTabMessage(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = StashTheme.extendedColors.textTertiary,
        )
    }
}
